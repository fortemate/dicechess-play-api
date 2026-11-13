package dicechess.play

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.ingest.PlaysiteIngest
import dicechess.play.server.{CreateGame, LaboratoryConfig, LaboratoryRegistry, RuntimeMode}
import dicechess.play.store.{GameArchive, PgLaboratoryStore}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.{Header, Headers, Method, Request, Status, Uri}
import org.http4s.Method.POST
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.jdkhttpclient.JdkHttpClient
import org.testcontainers.utility.DockerImageName
import org.typelevel.ci.*

import scala.concurrent.duration.*

class LaboratoryRuntimeSuite extends munit.CatsEffectSuite with TestContainerForAll:
  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def config(pg: PostgreSQLContainer, admissionOpen: Option[Boolean] = None): LaboratoryConfig =
    val env = Map(
      "PLAY_RUNTIME_MODE"   -> "laboratory",
      "LAB_DB_URL"          -> pg.jdbcUrl,
      "LAB_DB_USER"         -> pg.username,
      "LAB_DB_PASSWORD"     -> pg.password,
      "LAB_FRONTEND_ORIGIN" -> "https://lab.example"
    ) ++ admissionOpen.map(value => "LAB_ADMISSION_OPEN" -> value.toString)
    LaboratoryConfig.fromEnv(env).fold(message => fail(message), identity)

  private def store(pg: PostgreSQLContainer): Resource[IO, PgLaboratoryStore] =
    PgLaboratoryStore.resource(PgLaboratoryStore.Config(pg.jdbcUrl, pg.username, pg.password))

  private def server(registry: LaboratoryRegistry, runtimeConfig: LaboratoryConfig): Resource[IO, Int] =
    EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"0")
      .withShutdownTimeout(1.second)
      .withHttpWebSocketApp(wsb => LaboratoryRuntime.httpApp(registry, runtimeConfig, wsb, "lab-test"))
      .build
      .map(_.address.getPort)

  private def base(port: Int): Uri = Uri.unsafeFromString(s"http://127.0.0.1:$port")

  private def origin(value: String): Header.Raw = Header.Raw(ci"Origin", value)

  private def createBody: Json =
    CreateGame(
      Some("11111111-1111-1111-1111-111111111111"),
      Some("22222222-2222-2222-2222-222222222222"),
      Some(TimeControl.Fischer(60, 5)),
      Some(TwoPairs),
      Some(ProtocolVersion),
      Some(false)
    ).asJson

  private def headerValues(headers: Headers, name: CIString): Set[String] =
    headers.get(name).fold(Set.empty[String])(_.head.value.split(',').iterator.map(_.trim.toLowerCase).toSet)

  private def inspectResponse(http: Client[IO], request: Request[IO]): IO[(Status, Headers)] =
    http.run(request).use(response => response.body.compile.drain.as(response.status -> response.headers))

  test("the composed laboratory runtime is isolated, enforces admission and origin policy, and persists valid rooms"):
    withContainers { pg =>
      store(pg).use { db =>
        val closedConfig = config(pg, Some(false))
        assertEquals(RuntimeMode.fromEnv(Map("PLAY_RUNTIME_MODE" -> "laboratory")), Right(RuntimeMode.Laboratory))

        for
          denied <- LaboratoryRuntime.registry(closedConfig, db).use { closed =>
            closed.createFriend(
              Principal.Guest("11111111-1111-1111-1111-111111111111"),
              Principal.Guest("22222222-2222-2222-2222-222222222222"),
              TimeControl.Fischer(60, 5),
              Some(TwoPairs),
              Some(ProtocolVersion),
              requestedRated = false
            )
          }
          afterDenied <- db.loadActive
          _ = assert(denied.isLeft, "a closed LAB_ADMISSION_OPEN setting must reject direct registry admission")
          _ = assertEquals(afterDenied.size, 0, "a rejected direct creation must not write a laboratory game")
          openConfig = config(pg)
          _ <- LaboratoryRuntime.registry(openConfig, db).use { registry =>
            for
              bot <- registry.createFriend(
                Principal.Guest("11111111-1111-1111-1111-111111111111"),
                Principal.Bot("house", "laboratory-test"),
                TimeControl.Fischer(60, 5),
                Some(TwoPairs),
                Some(ProtocolVersion),
                requestedRated = false
              )
              rated <- registry.createFriend(
                Principal.Guest("11111111-1111-1111-1111-111111111111"),
                Principal.Guest("22222222-2222-2222-2222-222222222222"),
                TimeControl.Fischer(60, 5),
                Some(TwoPairs),
                Some(ProtocolVersion),
                requestedRated = true
              )
              afterDirectDenials <- db.loadActive
              _ = assert(bot.isLeft, "laboratory admission must reject bot participants even when called directly")
              _ = assert(rated.isLeft, "laboratory admission must reject rated requests even when called directly")
              _ = assertEquals(afterDirectDenials.size, 0, "invalid direct admission must not write a laboratory game")
              _ <- server(registry, openConfig).use { port =>
                JdkHttpClient.simple[IO].use { http =>
                  val uri = base(port)
                  for
                    health  <- http.status(Request[IO](Method.GET, uri / "health"))
                    version <- http.expect[String](uri / "version")
                    blocked <- inspectResponse(
                      http,
                      POST(createBody, uri / "games").putHeaders(origin("https://evil.example"))
                    )
                    blockedRows <- db.loadActive
                    opaque <- inspectResponse(http, Request[IO](Method.GET, uri / "health").putHeaders(origin("null")))
                    preflight <- inspectResponse(
                      http,
                      Request[IO](Method.OPTIONS, uri / "games").putHeaders(
                        origin("https://lab.example"),
                        Header.Raw(ci"Access-Control-Request-Method", "POST"),
                        Header.Raw(ci"Access-Control-Request-Headers", "content-type, x-laboratory-seat-token")
                      )
                    )
                    unavailable <- List(
                      Request[IO](Method.POST, (uri / "bot" / "anon") +? ("name" -> "laboratory-test")),
                      Request[IO](Method.POST, uri / "bot" / "register"),
                      Request[IO](Method.GET, uri / "leaderboard"),
                      Request[IO](Method.POST, uri / "lobby" / "seeks"),
                      Request[IO](Method.POST, uri / "ingest" / "games"),
                      Request[IO](Method.GET, uri / "me" / "games"),
                      Request[IO](Method.GET, uri / "training"),
                      Request[IO](Method.POST, uri / "showcase" / "claim")
                    ).traverse(request => http.status(request))
                    created <- http.status(POST(createBody, uri / "games").putHeaders(origin("https://lab.example")))
                    active  <- db.loadActive
                  yield
                    assertEquals(health, Status.Ok)
                    assertEquals(version, "lab-test")
                    assertEquals(blocked._1, Status.Forbidden)
                    assertEquals(blocked._2.get(ci"Access-Control-Allow-Origin"), None)
                    assertEquals(blockedRows.size, 0, "a blocked browser origin must not create a room")
                    assertEquals(opaque._1, Status.Forbidden)
                    assertEquals(opaque._2.get(ci"Access-Control-Allow-Origin"), None)
                    assert(preflight._1 == Status.Ok || preflight._1 == Status.NoContent)
                    assertEquals(
                      preflight._2.get(ci"Access-Control-Allow-Origin").map(_.head.value),
                      Some("https://lab.example")
                    )
                    val allowedHeaders = headerValues(preflight._2, ci"Access-Control-Allow-Headers")
                    assert(allowedHeaders.contains("content-type"))
                    assert(allowedHeaders.contains("x-laboratory-seat-token"))
                    assertEquals(unavailable, List.fill(unavailable.size)(Status.NotFound))
                    assert(
                      created == Status.Created || created == Status.Ok,
                      s"valid laboratory create returned $created"
                    )
                    assertEquals(active.size, 1, "an allowed laboratory create must persist exactly one room")
                    val (gameId, labSnapshot) = active.head
                    assert(!labSnapshot.isClassic)
                    val ended = labSnapshot.copy(
                      status = GameStatus.Ended(GameOver(GameResult.Draw, Termination.Draw)),
                      ratedRequested = Some(true),
                      rated = Some(true),
                      origin = Some(GameOrigin.Showcase)
                    )
                    List(RatingDomain.Training, RatingDomain.Competitive).foreach: domain =>
                      val marked = ended.copy(ratingDomain = Some(domain))
                      assertEquals(PlaysiteIngest.payload(gameId, marked), None)
                      assertEquals(GameArchive.entry(marked), None)
                }
              }
            yield ()
          }
        yield ()
      }
    }
