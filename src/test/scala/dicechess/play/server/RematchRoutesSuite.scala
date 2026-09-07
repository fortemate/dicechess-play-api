package dicechess.play.server

import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.EngineOps
import dicechess.play.store.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.headers.`Retry-After`
import org.http4s.implicits.*
import org.http4s.{Header, Headers, HttpApp, Method, Request, RequestCookie, Response, Status, Uri}
import org.testcontainers.utility.DockerImageName
import org.typelevel.ci.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** HTTP authorization and persistence contract for the participant rematch surface (#129). */
class RematchRoutesSuite extends munit.CatsEffectSuite with TestContainerForAll:
  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private val Secret     = "rematch-routes-test-secret"
  private val Origin     = "https://play.example"
  private val GuestWhite = "11111111-1111-1111-1111-111111111111"
  private val GuestBlack = "22222222-2222-2222-2222-222222222222"
  private val WhiteToken = "guest-seat-token-white-0123456789"
  private val BlackToken = "guest-seat-token-black-0123456789"
  private val PruneAt    = Instant.parse("9999-12-31T00:00:00Z")

  private def resources(pg: PostgreSQLContainer): Resource[IO, (PgGameStore, GameRegistry)] =
    for
      db  <- PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))
      reg <- Resource.make(GameRegistry.create(store = db))(_.list.flatMap(_.traverse_(_._2.stopForRestart)))
    yield (db, reg)

  private def app(
      db: PgGameStore,
      registry: GameRegistry,
      session: Option[AuthSession] = None,
      limits: RematchLimiter.Config = RematchLimiter.Config()
  ): Resource[IO, HttpApp[IO]] =
    RematchRoutes
      .resource(registry, Some(db), session, Cors.allowedOrigins(Origin), limits)
      .map(_.orNotFound)

  private def snapshot(white: Principal, black: Principal, status: GameStatus, origin: GameOrigin = GameOrigin.Direct) =
    GameSnapshot(
      version = 2L,
      dfen = EngineOps.InitialDfen,
      players = Map(Seat.White -> white, Seat.Black -> black),
      seatTokens = Map(Seat.White -> WhiteToken, Seat.Black -> BlackToken),
      serverSeed = "a" * 64,
      clientSeeds = Map(Seat.White -> "white-client-seed", Seat.Black -> "black-client-seed"),
      started = true,
      ply = 2L,
      pending = false,
      status = status,
      timeControl = TimeControl.Fischer(300, 3),
      remainingMs = Map(Seat.White -> 295000L, Seat.Black -> 295000L),
      lastRoll = List(2, 3, 6),
      turns = Vector.empty,
      createdAtEpochMs = Some(1_782_000_000_000L),
      rated = Some(false),
      ladder = Some(false),
      origin = Some(origin)
    )

  private def ended(white: Principal, black: Principal, origin: GameOrigin = GameOrigin.Direct) =
    snapshot(white, black, GameStatus.Ended(GameOver(GameResult.Win(Side.White), Termination.Resign)), origin)

  private def seed(db: PgGameStore, white: Principal, black: Principal): IO[(GameId, String, String)] =
    for
      id <- GameId.random
      _  <- db.save(id, ended(white, black))
      _  <- db.due(500).flatMap(_.traverse_(row => db.markDelivered(row.gameId)))
      _  <- db.rematches.session(id).flatMap(_.liftTo[IO](AssertionError(s"no rematch session for $id")))
    yield (id, WhiteToken, BlackToken)

  private def request(
      method: Method,
      path: String,
      token: Option[String] = None,
      cookie: Option[String] = None,
      headers: List[Header.Raw] = Nil,
      body: Option[Json] = None
  ): Request[IO] =
    val base       = Request[IO](method, Uri.unsafeFromString(path)).withHeaders(Headers(headers))
    val withToken  = token.fold(base)(value => base.putHeaders(Header.Raw(ci"X-Rematch-Seat-Token", value)))
    val withCookie =
      cookie.fold(withToken)(value => withToken.addCookie(RequestCookie(AuthSession.SessionCookieName, value)))
    body.fold(withCookie)(withCookie.withEntity)

  private def get(app: HttpApp[IO], id: GameId, token: Option[String] = None, cookie: Option[String] = None) =
    app.run(request(Method.GET, s"/games/${id.value}/rematch", token, cookie))

  private def public(app: HttpApp[IO], id: String) = app.run(request(Method.GET, s"/games/$id/continuation"))

  private def post(
      app: HttpApp[IO],
      id: GameId,
      token: Option[String],
      action: String,
      requestId: UUID = UUID.randomUUID(),
      cookie: Option[String] = None,
      origin: Option[String] = Some(Origin),
      csrf: Boolean = true
  ): IO[Response[IO]] =
    val headers =
      List(
        origin.map(value => Header.Raw(ci"Origin", value)),
        Option.when(csrf)(Header.Raw(ci"X-DiceChess-CSRF", "1"))
      ).flatten
    app.run(
      request(
        Method.POST,
        s"/games/${id.value}/rematch",
        token,
        cookie,
        headers,
        Some(Json.obj("requestId" -> requestId.toString.asJson, "action" -> action.asJson))
      )
    )

  private def json(response: Response[IO]): IO[Json] = response.as[Json]

  private def noStore(response: Response[IO]): Unit =
    assertEquals(response.headers.get(ci"Cache-Control").map(_.head.value), Some("no-store"))
    assertEquals(response.headers.get(ci"Pragma").map(_.head.value), Some("no-cache"))

  private def account(db: PgGameStore, subject: String): IO[(UserAccount, String)] =
    for
      user  <- db.upsertOnLogin("google", subject, None, IO.pure(subject))
      token <- AuthSession(db, Secret).sign(user)
    yield (user, token)

  test("guest token authorizes after the operational snapshot is pruned, while a UUID is not a capability"):
    withContainers { pg =>
      resources(pg).use { (db, registry) =>
        val session = None
        seed(db, Principal.Guest(GuestWhite), Principal.Guest(GuestBlack)).flatMap { (id, _, _) =>
          db.pruneOnce(PruneAt, 500) *> app(db, registry, session).use { routes =>
            for
              valid     <- get(routes, id, Some(WhiteToken))
              invalid   <- get(routes, id, Some(GuestWhite))
              validBody <- json(valid)
            yield
              assertEquals(valid.status, Status.Ok)
              assert(validBody.hcursor.get[String]("phase").toOption.contains("available"))
              assertEquals(invalid.status, Status.Forbidden)
              noStore(valid)
              noStore(invalid)
          }
        }
      }
    }

  test("account seats require a matching active session; a valid account exposes its final seat"):
    withContainers { pg =>
      resources(pg).use { (db, registry) =>
        account(db, "account-seat").flatMap { (user, cookie) =>
          seed(db, Principal.User(user.id), Principal.Guest(GuestBlack)).flatMap { (id, _, _) =>
            app(db, registry, Some(AuthSession(db, Secret))).use { routes =>
              for
                tokenOnly     <- get(routes, id, Some(WhiteToken))
                noAuth        <- get(routes, id)
                _             <- db.pruneOnce(PruneAt, 500)
                valid         <- get(routes, id, cookie = Some(cookie))
                _             <- db.deleteUser(user.id)
                inactive      <- get(routes, id, cookie = Some(cookie))
                deletedNoAuth <- get(routes, id)
                validBody     <- json(valid)
              yield
                assertEquals(tokenOnly.status, Status.Forbidden)
                assertEquals(noAuth.status, Status.Unauthorized)
                assertEquals(valid.status, Status.Ok)
                assertEquals(validBody.hcursor.get[String]("sourceGameId").toOption, Some(id.value))
                assertEquals(inactive.status, Status.Unauthorized)
                assertEquals(deletedNoAuth.status, Status.Unauthorized)
            }
          }
        }
      }
    }

  test("session identity wins over a guest token, and POST requires the configured origin and CSRF header"):
    withContainers { pg =>
      resources(pg).use { (db, registry) =>
        account(db, "session-wins").flatMap { (user, cookie) =>
          account(db, "nonparticipant").flatMap { (_, otherCookie) =>
            seed(db, Principal.User(user.id), Principal.Guest(GuestBlack)).flatMap { (id, _, _) =>
              app(db, registry, Some(AuthSession(db, Secret))).use { routes =>
                for
                  missing <- post(routes, id, Some(BlackToken), "propose", cookie = Some(cookie), origin = None)
                  noCsrf  <- post(routes, id, Some(BlackToken), "propose", cookie = Some(cookie), csrf = false)
                  hostile <- post(
                    routes,
                    id,
                    Some(BlackToken),
                    "propose",
                    cookie = Some(cookie),
                    origin = Some("https://evil.example")
                  )
                  other  <- get(routes, id, Some(BlackToken), Some(otherCookie))
                  absent <- get(routes, id)
                  ok     <- post(routes, id, Some(BlackToken), "propose", cookie = Some(cookie))
                  stored <- db.rematches.session(id).map(_.get)
                yield
                  assertEquals(missing.status, Status.Forbidden)
                  assertEquals(noCsrf.status, Status.Forbidden)
                  assertEquals(hostile.status, Status.Forbidden)
                  assertEquals(other.status, Status.Forbidden)
                  assertEquals(absent.status, Status.Unauthorized)
                  assertEquals(ok.status, Status.Ok)
                  assertEquals(stored.offeredBy, Some(Seat.White))
                  noStore(missing)
                  noStore(noCsrf)
                  noStore(hostile)
                  noStore(other)
                  noStore(absent)
                  noStore(ok)
              }
            }
          }
        }
      }
    }

  test("body validation is strict, all responses are uncacheable, and configured writes return Retry-After"):
    withContainers { pg =>
      resources(pg).use { (db, registry) =>
        seed(db, Principal.Guest(GuestWhite), Principal.Guest(GuestBlack)).flatMap { (id, _, _) =>
          val limits = RematchLimiter.Config(writesPerMinute = 2)
          app(db, registry, limits = limits).use { routes =>
            val baseHeaders = List(Header.Raw(ci"Origin", Origin), Header.Raw(ci"X-DiceChess-CSRF", "1"))
            val invalidBody = Json.obj("requestId" -> "nope".asJson, "action" -> "propose".asJson)
            for
              bad <- routes.run(
                request(
                  Method.POST,
                  s"/games/${id.value}/rematch",
                  Some(WhiteToken),
                  headers = baseHeaders,
                  body = Some(invalidBody)
                )
              )
              first  <- post(routes, id, Some(WhiteToken), "propose")
              second <- post(routes, id, Some(WhiteToken), "propose")
            yield
              assertEquals(bad.status, Status.BadRequest)
              assertEquals(first.status, Status.Ok)
              assertEquals(second.status, Status.TooManyRequests)
              assertEquals(second.headers.get[`Retry-After`].map(_.retry), Some(Right(60L)))
              noStore(bad)
              noStore(first)
              noStore(second)
          }
        }
      }
    }

  test("public continuation distinguishes active ordinary games, ineligible games, and missing ids"):
    withContainers { pg =>
      resources(pg).use { (db, registry) =>
        for
          ordinary <- GameId.random
          showcase <- GameId.random
          bot      <- GameId.random
          _ <- db.save(ordinary, snapshot(Principal.Guest(GuestWhite), Principal.Guest(GuestBlack), GameStatus.Active))
          _ <- db.save(
            showcase,
            snapshot(Principal.Guest(GuestWhite), Principal.Guest(GuestBlack), GameStatus.Active, GameOrigin.Showcase)
          )
          _ <- db.save(bot, snapshot(Principal.Bot("team", "bot"), Principal.Guest(GuestBlack), GameStatus.Active))
          _ <- app(db, registry).use { routes =>
            for
              waiting     <- public(routes, ordinary.value)
              closed      <- public(routes, showcase.value)
              closedBot   <- public(routes, bot.value)
              missing     <- public(routes, UUID.randomUUID().toString)
              waitingJson <- json(waiting)
              closedJson  <- json(closed)
              botJson     <- json(closedBot)
            yield
              assertEquals(waiting.status, Status.Ok)
              assertEquals(closed.status, Status.Ok)
              assertEquals(closedBot.status, Status.Ok)
              assertEquals(missing.status, Status.NotFound)
              assertEquals(waitingJson.hcursor.get[String]("phase").toOption, Some("waiting"))
              assertEquals(waitingJson.hcursor.get[Option[String]]("deadlineAt").toOption, Some(None))
              assertEquals(closedJson.hcursor.get[String]("phase").toOption, Some("closed"))
              assertEquals(botJson.hcursor.get[String]("phase").toOption, Some("closed"))
              noStore(waiting)
              noStore(closed)
              noStore(closedBot)
              noStore(missing)
          }
        yield ()
      }
    }

  test("propose then accept reaches one matched successor and exposes only caller credentials"):
    withContainers { pg =>
      resources(pg).use { (db, registry) =>
        seed(db, Principal.Guest(GuestWhite), Principal.Guest(GuestBlack)).flatMap { (id, _, _) =>
          app(db, registry).use { routes =>
            def matched(remaining: Int): IO[Json] =
              get(routes, id, Some(WhiteToken)).flatMap(json).flatMap { body =>
                if body.hcursor.get[String]("phase").toOption.contains("matched") then IO.pure(body)
                else if remaining <= 0 then IO.raiseError(AssertionError("rematch did not become matched"))
                else IO.sleep(100.millis) *> matched(remaining - 1)
              }
            for
              proposed    <- post(routes, id, Some(WhiteToken), "propose")
              accepted    <- post(routes, id, Some(BlackToken), "accept")
              white       <- matched(100).timeoutTo(15.seconds, IO.raiseError(AssertionError("rematch poll timed out")))
              black       <- get(routes, id, Some(BlackToken)).flatMap(json)
              publicState <- public(routes, id.value).flatMap(json)
            yield
              assertEquals(proposed.status, Status.Ok)
              assertEquals(accepted.status, Status.Ok)
              assert(white.hcursor.downField("join").get[String]("token").toOption.nonEmpty)
              assert(!white.hcursor.downField("join").get[String]("token").toOption.contains(WhiteToken))
              assert(black.hcursor.downField("join").get[String]("token").toOption.nonEmpty)
              assertNotEquals(
                white.hcursor.downField("join").get[String]("token").toOption,
                black.hcursor.downField("join").get[String]("token").toOption
              )
              assert(!white.noSpaces.contains(black.hcursor.downField("join").get[String]("token").toOption.get))
              assert(!black.noSpaces.contains(white.hcursor.downField("join").get[String]("token").toOption.get))
              assertEquals(white.hcursor.get[String]("nextGameId"), black.hcursor.get[String]("nextGameId"))
              assert(!publicState.noSpaces.contains("token"))
              assert(!publicState.noSpaces.contains("join"))
              assertEquals(
                publicState.hcursor.keys.map(_.toSet),
                Some(Set("sourceGameId", "phase", "serverNow", "nextGameId"))
              )
          }
        }
      }
    }

  test("a Starting row is recovered by the coordinator sweep without an HTTP accept response"):
    withContainers { pg =>
      resources(pg).use { (db, registry) =>
        seed(db, Principal.Guest(GuestWhite), Principal.Guest(GuestBlack)).flatMap { (id, _, _) =>
          for
            first <- db.rematchCommands.command(id, Seat.White, UUID.randomUUID(), RematchAction.Propose)
            _     <- db.rematchCommands.command(id, Seat.Black, UUID.randomUUID(), RematchAction.Accept)
            _ = assertEquals(first.error, None)
            _ <- app(db, registry).use { _ =>
              def waitForMatch(remaining: Int): IO[Unit] =
                (db.rematchCommands.current(id), db.rematches.session(id), registry.list).tupled.flatMap {
                  case (current, Some(stored), rooms)
                      if stored.phase == RematchPhase.Matched && stored.successorId.isDefined &&
                        current.session.flatMap(_.successorId).isDefined &&
                        rooms.exists(_._1 == stored.successorId.get) =>
                    IO.unit
                  case _ if remaining <= 0 => IO.raiseError(AssertionError("coordinator did not recover Starting"))
                  case _                   => IO.sleep(200.millis) *> waitForMatch(remaining - 1)
                }
              waitForMatch(50)
            }
          yield ()
        }
      }
    }

  test("same request id retries the current terminal command without changing the result"):
    withContainers { pg =>
      resources(pg).use { (db, registry) =>
        seed(db, Principal.Guest(GuestWhite), Principal.Guest(GuestBlack)).flatMap { (id, _, _) =>
          app(db, registry).use { routes =>
            val requestId = UUID.randomUUID()
            for
              first      <- post(routes, id, Some(WhiteToken), "propose", requestId)
              declined   <- post(routes, id, Some(BlackToken), "decline")
              replay     <- post(routes, id, Some(WhiteToken), "propose", requestId)
              fresh      <- post(routes, id, Some(WhiteToken), "propose")
              state      <- db.rematches.session(id).map(_.get)
              firstJson  <- json(first)
              replayJson <- json(replay)
            yield
              assertEquals(first.status, Status.Ok)
              assertEquals(declined.status, Status.Ok)
              assertEquals(replay.status, Status.Ok)
              assertEquals(fresh.status, Status.Gone)
              assertEquals(firstJson.hcursor.get[String]("phase").toOption, Some("offered"))
              assertEquals(replayJson.hcursor.get[String]("phase").toOption, Some("closed"))
              assertEquals(state.phase, RematchPhase.Closed)
          }
        }
      }
    }

  test("committed links remain private until publication and stay readable through terminal history and retention"):
    withContainers { pg =>
      resources(pg).use { (db, registry) =>
        for
          entered <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          _       <- registry.onRegister((_, _, _) => entered.complete(()).void *> release.get)
          seeded  <- seed(db, Principal.Guest(GuestWhite), Principal.Guest(GuestBlack))
          id = seeded._1
          _ <- app(db, registry).use { routes =>
            (for
              _              <- post(routes, id, Some(WhiteToken), "propose")
              _              <- post(routes, id, Some(BlackToken), "accept")
              _              <- entered.get.timeout(5.seconds)
              privateWaiting <- get(routes, id, Some(WhiteToken)).flatMap(json)
              publicWaiting  <- public(routes, id.value).flatMap(json)
              _ = assertEquals(privateWaiting.hcursor.get[String]("phase").toOption, Some("starting"))
              _ = assert(!privateWaiting.asObject.get.contains("join"))
              _ = assert(!privateWaiting.asObject.get.contains("nextGameId"))
              _ = assertEquals(publicWaiting.asObject.get.keys.toSet, Set("sourceGameId", "phase", "serverNow"))
              _           <- release.complete(())
              successorId <- db.rematches.session(id).map(_.get.successorId.get)
              room        <- {
                def waitRoom: IO[dicechess.play.game.GameRoom] = registry.get(successorId).flatMap {
                  case Some(room) => IO.pure(room)
                  case None       => IO.sleep(20.millis) *> waitRoom
                }
                waitRoom.timeout(5.seconds)
              }
              before    <- get(routes, id, Some(WhiteToken)).flatMap(json)
              _         <- room.abort
              _         <- room.result
              _         <- db.markDelivered(successorId)
              _         <- db.pruneOnce(PruneAt, 500)
              history   <- db.archiveFor(successorId)
              after     <- get(routes, id, Some(WhiteToken)).flatMap(json)
              continued <- public(routes, id.value).flatMap(json)
            yield
              assert(history.isDefined)
              assertEquals(after.hcursor.get[String]("phase").toOption, Some("matched"))
              assertEquals(after.hcursor.downField("join").as[Json], before.hcursor.downField("join").as[Json])
              assertEquals(continued.hcursor.get[String]("nextGameId").toOption, Some(successorId.value))
            ).guarantee(release.complete(()).void)
          }
        yield ()
      }
    }

  test("unsupported inherited settings are refused without creating a successor or changing the root conditions"):
    withContainers { pg =>
      resources(pg).use { (db, registry) =>
        for
          id <- GameId.random
          invalid = ended(Principal.Guest(GuestWhite), Principal.Guest(GuestBlack))
            .copy(timeControl = TimeControl.PerMove(0))
          _ <- db.save(id, invalid)
          _ <- app(db, registry).use { routes =>
            for
              response <- post(routes, id, Some(WhiteToken), "propose")
              body     <- json(response)
              stored   <- db.rematches.session(id).map(_.get)
            yield
              assertEquals(response.status, Status.Conflict)
              assertEquals(body.hcursor.downField("error").get[String]("code").toOption, Some("settings_unavailable"))
              assertEquals(stored.phase, RematchPhase.Available)
              assertEquals(stored.source.conditions.timeControl, TimeControl.PerMove(0))
              assertEquals(stored.successorId, None)
          }
        yield ()
      }
    }
