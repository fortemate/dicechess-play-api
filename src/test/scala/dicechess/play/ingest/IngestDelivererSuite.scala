package dicechess.play.ingest

import cats.effect.{IO, Ref, Resource}
import com.comcast.ip4s.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.ingest.IngestDeliverer.Outcome
import dicechess.play.store.{GameSnapshot, OutboxStore, PgGameStore, TurnRecord}
import munit.CatsEffectSuite
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.http4s.server.Server
import org.http4s.{HttpRoutes, Status, Uri}
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.*

/** The outbox handoff end to end: the transactional enqueue on a finished game's save, and the deliverer's three
  * outcomes against a stub ingest endpoint — delivered (2xx), retried with backoff (5xx/transport), parked (4xx).
  */
class IngestDelivererSuite extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def store(pg: PostgreSQLContainer) =
    PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))

  private def endedSnapshot: GameSnapshot =
    GameSnapshot(
      version = 9L,
      dfen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      players = Map(Seat.White -> Principal.Guest("w-uuid"), Seat.Black -> Principal.Guest("b-uuid")),
      seatTokens = Map(Seat.White -> "tok-w", Seat.Black -> "tok-b"),
      serverSeed = "ab12cd34",
      clientSeeds = Map.empty,
      started = true,
      ply = 1L,
      pending = false,
      status = GameStatus.Ended(GameOver(GameResult.Win(Side.Black), Termination.Resign)),
      timeControl = TimeControl.Unlimited,
      remainingMs = Map.empty,
      lastRoll = List(1, 2, 3),
      turns = Vector(TurnRecord(1L, "w", List(1, 2, 3), List("e2e4"), "fen-after")),
      createdAtEpochMs = Some(1_782_000_000_000L)
    )

  private def abortedSnapshot: GameSnapshot =
    endedSnapshot.copy(status = GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted)))

  /** A stub analytics endpoint answering with whatever status the Ref holds; records received Bearer tokens. */
  private def stubIngest(status: Ref[IO, Status], tokens: Ref[IO, List[String]]): Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"0")
      .withShutdownTimeout(1.second)
      .withHttpApp(
        HttpRoutes
          .of[IO] { case req @ POST -> Root / "api" / "games" =>
            val bearer = req.headers.get[Authorization].map(_.credentials.toString).getOrElse("")
            tokens.update(_ :+ bearer) *> status.get.flatMap(s => IO.pure(org.http4s.Response[IO](s)))
          }
          .orNotFound
      )
      .build

  private def deliverer(queue: OutboxStore, base: Uri) =
    EmberClientBuilder
      .default[IO]
      .build
      .map(http => IngestDeliverer(queue, http, IngestDeliverer.Config(base / "api" / "games", "test-token")))

  test("saving a finished game enqueues its payload exactly once; an aborted one never"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          finished <- GameId.random
          aborted  <- GameId.random
          _        <- db.save(finished, endedSnapshot)
          _        <- db.save(finished, endedSnapshot) // a re-save must not duplicate the handoff
          _        <- db.save(aborted, abortedSnapshot)
          rows     <- db.due(10)
        yield
          assertEquals(rows.count(_.gameId.value == finished.value), 1)
          assertEquals(rows.count(_.gameId.value == aborted.value), 0)
          val payload = rows.find(_.gameId.value == finished.value).get.payload.hcursor
          assertEquals(payload.get[String]("source").toOption, Some("playsite"))
          assertEquals(payload.get[String]("termination").toOption, Some("resign"))
      }
    }

  test("a 201 delivers the row (with the Bearer token) and it never comes due again"):
    withContainers { pg =>
      store(pg).use { db =>
        (for
          status <- Resource.eval(Ref.of[IO, Status](Status.Created))
          tokens <- Resource.eval(Ref.of[IO, List[String]](Nil))
          server <- stubIngest(status, tokens)
          d      <- deliverer(db, Uri.unsafeFromString(s"http://127.0.0.1:${server.address.getPort}"))
        yield (d, tokens)).use { (d, tokens) =>
          for
            id       <- GameId.random
            _        <- db.save(id, endedSnapshot)
            outcomes <- d.deliverDueOnce
            after    <- db.due(10)
            sent     <- tokens.get
          yield
            assert(outcomes.contains(Outcome.Delivered))
            assert(after.forall(_.gameId.value != id.value), "a delivered row must not come due again")
            assert(sent.exists(_.contains("test-token")), "the Bearer token must be sent")
        }
      }
    }

  test("a 500 schedules a retry with backoff; a later 201 delivers it"):
    withContainers { pg =>
      store(pg).use { db =>
        (for
          status <- Resource.eval(Ref.of[IO, Status](Status.InternalServerError))
          tokens <- Resource.eval(Ref.of[IO, List[String]](Nil))
          server <- stubIngest(status, tokens)
          d      <- deliverer(db, Uri.unsafeFromString(s"http://127.0.0.1:${server.address.getPort}"))
        yield (d, status)).use { (d, status) =>
          for
            id     <- GameId.random
            _      <- db.save(id, endedSnapshot)
            first  <- d.deliverDueOnce
            parked <- db.due(10) // backoff pushed next_attempt_at into the future
            // Force the retry due now, flip the endpoint to success, and deliver.
            _      <- db.markRetry(id, attempts = 1, retryIn = 0.seconds, error = "test")
            _      <- status.set(Status.Created)
            second <- d.deliverDueOnce
          yield
            assertEquals(first, List(Outcome.Retried))
            assert(parked.forall(_.gameId.value != id.value), "a retried row is not due until its backoff elapses")
            assertEquals(second, List(Outcome.Delivered))
        }
      }
    }

  test("a browser report drains from client_reports with the same delivery semantics (#212)"):
    withContainers { pg =>
      store(pg).use { db =>
        (for
          status <- Resource.eval(Ref.of[IO, Status](Status.Created))
          tokens <- Resource.eval(Ref.of[IO, List[String]](Nil))
          server <- stubIngest(status, tokens)
          d      <- deliverer(db.clientReports, Uri.unsafeFromString(s"http://127.0.0.1:${server.address.getPort}"))
        yield (d, tokens)).use { (d, tokens) =>
          for
            id <- GameId.random
            payload = io.circe.Json.obj("id" -> io.circe.Json.fromString(id.value))
            inserted  <- db.insertClientReport(id, payload)
            duplicate <- db.insertClientReport(id, payload)
            outcomes  <- d.deliverDueOnce
            after     <- db.clientReports.due(10)
            sent      <- tokens.get
          yield
            assert(inserted, "the first write must report acceptance (201)")
            assert(!duplicate, "the second write must report a known report (200)")
            assertEquals(outcomes, List(Outcome.Delivered))
            assert(after.forall(_.gameId.value != id.value), "a delivered report must not come due again")
            assert(sent.exists(_.contains("test-token")), "the Bearer token must be sent for reports too")
        }
      }
    }

  test("a 422 from the replay gate parks a browser report permanently (#212)"):
    withContainers { pg =>
      store(pg).use { db =>
        (for
          status <- Resource.eval(Ref.of[IO, Status](Status.UnprocessableEntity))
          tokens <- Resource.eval(Ref.of[IO, List[String]](Nil))
          server <- stubIngest(status, tokens)
          d      <- deliverer(db.clientReports, Uri.unsafeFromString(s"http://127.0.0.1:${server.address.getPort}"))
        yield d).use { d =>
          for
            id       <- GameId.random
            _        <- db.insertClientReport(id, io.circe.Json.obj("id" -> io.circe.Json.fromString(id.value)))
            outcomes <- d.deliverDueOnce
            after    <- db.clientReports.due(10)
          yield
            assertEquals(outcomes, List(Outcome.Parked))
            assert(after.forall(_.gameId.value != id.value), "a parked report must never come due again")
        }
      }
    }

  test("a 422 from the replay gate parks the row permanently"):
    withContainers { pg =>
      store(pg).use { db =>
        (for
          status <- Resource.eval(Ref.of[IO, Status](Status.UnprocessableEntity))
          tokens <- Resource.eval(Ref.of[IO, List[String]](Nil))
          server <- stubIngest(status, tokens)
          d      <- deliverer(db, Uri.unsafeFromString(s"http://127.0.0.1:${server.address.getPort}"))
        yield d).use { d =>
          for
            id       <- GameId.random
            _        <- db.save(id, endedSnapshot)
            outcomes <- d.deliverDueOnce
            after    <- db.due(10)
          yield
            assertEquals(outcomes, List(Outcome.Parked))
            assert(after.forall(_.gameId.value != id.value), "a parked row must never come due again")
        }
      }
    }
