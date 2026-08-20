package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.EngineOps
import dicechess.play.store.{GameSnapshot, GuestLink, PgGameStore, TurnRecord}
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.util.ExecutionContexts
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.headers.`Cache-Control`
import org.http4s.implicits.*
import org.http4s.{CacheDirective, HttpApp, Method, Request, Status, Uri}
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.*

/** `GET /games/{id}/history` (#178) against a real Postgres: the fairness reveal, anonymization, cache headers, and the
  * several 404 paths.
  */
class HistoryRoutesSuite extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def store(pg: PostgreSQLContainer) =
    PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))

  private def app(pg: PgGameStore): HttpApp[IO] = HistoryRoutes(pg, pg).orNotFound

  private def get(app: HttpApp[IO], id: String): IO[org.http4s.Response[IO]] =
    app.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/games/$id/history")))

  private def snapshotFixture(white: Principal, black: Principal, status: GameStatus): GameSnapshot =
    GameSnapshot(
      version = 5L,
      dfen = EngineOps.InitialDfen,
      players = Map(Seat.White -> white, Seat.Black -> black),
      seatTokens = Map(Seat.White -> "tok-w", Seat.Black -> "tok-b"),
      serverSeed = "ab12cd34",
      clientSeeds = Map(Seat.White -> "white-seed", Seat.Black -> "black-seed"),
      started = true,
      ply = 2L,
      pending = false,
      status = status,
      timeControl = TimeControl.Fischer(300, 3),
      remainingMs = Map.empty,
      lastRoll = List(2, 3, 6),
      turns = Vector(TurnRecord(1L, "w", List(1, 1, 4), List("e2e4"), "fen-after")),
      createdAtEpochMs = Some(1_782_000_000_000L),
      rated = Some(true)
    )

  private def ended(result: GameResult = GameResult.Win(Side.White), termination: Termination = Termination.Resign) =
    GameStatus.Ended(GameOver(result, termination))

  test("a finished game reveals its fairness block immediately, cached but not as immutable"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("hr-unpaired-white")
        val black = Principal.Bot("hr-team", "hr-bot")
        for
          id   <- GameId.random
          _    <- db.save(id, snapshotFixture(white, black, ended()))
          resp <- get(app(db), id.value)
          json <- resp.as[io.circe.Json]
        yield
          assertEquals(resp.status, Status.Ok)
          val c = json.hcursor
          assertEquals(c.downField("players").downField("white").get[String]("kind").toOption, Some("Human"))
          assertEquals(c.downField("players").downField("white").get[Option[String]]("name").toOption, Some(None))
          assertEquals(c.downField("players").downField("black").get[String]("kind").toOption, Some("Bot"))
          assertEquals(
            c.downField("players").downField("black").get[Option[String]]("name").toOption,
            Some(Some("hr-team hr-bot"))
          )
          assertEquals(c.downField("timeControl").downField("Fischer").get[Int]("initialSeconds").toOption, Some(300))
          assertEquals(c.get[Int]("result").toOption, Some(1))
          assertEquals(c.get[String]("termination").toOption, Some("resign"))
          assertEquals(
            c.downField("turns").downN(0).get[List[String]]("moves").toOption,
            Some(List("e2e4"))
          )
          assertEquals(c.downField("turns").downN(0).get[String]("activeColor").toOption, Some("White"))
          assertEquals(c.downField("fairness").get[String]("seed").toOption, Some("ab12cd34"))
          assertEquals(
            c.downField("fairness").downField("clientSeeds").get[String]("white").toOption,
            Some("white-seed")
          )
          assertEquals(
            resp.headers.get[`Cache-Control`],
            Some(
              // NOT `immutable`, and minutes rather than a year: the seat faces resolve live nicknames, so a deleted
              // account's name must not be servable by a cache long after #237 anonymised its history.
              `Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(5.minutes))
            )
          )
      }
    }

  test("an unknown game id 404s"):
    withContainers { pg =>
      store(pg).use { db =>
        get(app(db), "00000000-0000-0000-0000-000000000000").map(resp => assertEquals(resp.status, Status.NotFound))
      }
    }

  test("a malformed (non-UUID) game id 404s instead of a raw database error"):
    withContainers { pg =>
      store(pg).use { db =>
        get(app(db), "not-a-uuid").map(resp => assertEquals(resp.status, Status.NotFound))
      }
    }

  /** The seat faces on a replay (#194 step 4). Two cases, and the second is the one that matters: a guest id that an
    * account has CLAIMED must still render anonymous here, or signing up would retroactively put a name on every
    * anonymous game that browser ever played — the promise #236 made.
    */
  test("a replay names a registered player"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          account <- db.upsertOnLogin("google", "hr-sub-named", None, IO.pure("HistoryNick"))
          id      <- GameId.random
          _    <- db.save(id, snapshotFixture(Principal.User(account.id), Principal.Bot("hr-team", "hr-bot"), ended()))
          resp <- get(app(db), id.value)
          json <- resp.as[io.circe.Json]
        yield
          assertEquals(resp.status, Status.Ok)
          val white = json.hcursor.downField("players").downField("white")
          assertEquals(white.get[String]("kind").toOption, Some("Human"))
          assertEquals(white.get[Option[String]]("name").toOption, Some(Some("HistoryNick")))
      }
    }

  test("a replay keeps a CLAIMED guest anonymous — signing up must not rename past anonymous games"):
    withContainers { pg =>
      store(pg).use { db =>
        val guestId = java.util.UUID.randomUUID().toString
        for
          account <- db.upsertOnLogin("google", "hr-sub-claimer", None, IO.pure("ClaimerNick"))
          link    <- db.linkGuest(account.id, guestId)
          id      <- GameId.random
          _       <- db.save(id, snapshotFixture(Principal.Guest(guestId), Principal.Bot("hr-team", "hr-bot"), ended()))
          resp    <- get(app(db), id.value)
          json    <- resp.as[io.circe.Json]
        yield
          assertEquals(link, GuestLink.Linked, "precondition: the guest id is claimed by the account")
          assertEquals(resp.status, Status.Ok)
          val white = json.hcursor.downField("players").downField("white")
          assertEquals(white.get[String]("kind").toOption, Some("Human"))
          assertEquals(
            white.get[Option[String]]("name").toOption,
            Some(None),
            "a claimed guest id must not resolve to its owner's nickname"
          )
      }
    }

  test("a replay leaves an account with is_active = false anonymous"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          account <- db.upsertOnLogin("google", "hr-sub-inactive", None, IO.pure("InactiveNick"))
          id      <- GameId.random
          _ <- db.save(id, snapshotFixture(Principal.User(account.id), Principal.Bot("hr-team", "hr-bot"), ended()))
          // Deactivation, not deletion: the row stays, so this is what exercises the `WHERE is_active` clause that the
          // deleted-account test below cannot reach. No store method does this — nothing in the product deactivates an
          // account yet — hence the direct UPDATE over the same raw connection the backfill-gap test uses.
          _    <- sql"UPDATE play.users SET is_active = false WHERE id = ${account.id}::uuid".update.run.transact(xa)
          resp <- get(app(db), id.value)
          json <- resp.as[io.circe.Json]
        yield
          assertEquals(resp.status, Status.Ok)
          val white = json.hcursor.downField("players").downField("white")
          assertEquals(white.get[Option[String]]("name").toOption, Some(None))
      }
    }

  test("a replay leaves a deleted account anonymous, as the rest of the public surface does"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          account <- db.upsertOnLogin("google", "hr-sub-gone", None, IO.pure("GoneNick"))
          id      <- GameId.random
          _    <- db.save(id, snapshotFixture(Principal.User(account.id), Principal.Bot("hr-team", "hr-bot"), ended()))
          _    <- db.deleteUser(account.id)
          resp <- get(app(db), id.value)
          json <- resp.as[io.circe.Json]
        yield
          assertEquals(resp.status, Status.Ok)
          val white = json.hcursor.downField("players").downField("white")
          assertEquals(white.get[Option[String]]("name").toOption, Some(None))
      }
    }

  test("an active (not yet ended) game 404s — it has no archive row yet"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("hr-active-white")
        val black = Principal.Guest("hr-active-black")
        for
          id   <- GameId.random
          _    <- db.save(id, snapshotFixture(white, black, GameStatus.Active))
          resp <- get(app(db), id.value)
        yield assertEquals(resp.status, Status.NotFound)
      }
    }

  /** A second, unpooled connection to the SAME database — used only to simulate the pre-#177 backfill gap below by
    * deleting an archive row out from under `PgGameStore.save`'s usual atomicity (something no production code path
    * ever does; this is a test-only forgery of "a game that finished before the archive existed").
    */
  private def rawXa(pg: PostgreSQLContainer) =
    for
      connectEC <- ExecutionContexts.fixedThreadPool[IO](2)
      xa        <- HikariTransactor
        .newHikariTransactor[IO]("org.postgresql.Driver", pg.jdbcUrl, pg.username, pg.password, connectEC)
    yield xa

  test("a finished game with no archive row 404s (the pre-#177 backfill gap, #178)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("hr-nobackfill-white")
        val black = Principal.Guest("hr-nobackfill-black")
        for
          id <- GameId.random
          _  <- db.save(id, snapshotFixture(white, black, ended()))
          // Simulate a pre-#177 game: game_results exists (the game plainly finished), but its archive row doesn't —
          // exactly the gap the design note calls out (the ~11k pre-migration games, never backfilled).
          _       <- sql"DELETE FROM play.game_archive WHERE game_id = ${id.value}::uuid".update.run.transact(xa)
          resp    <- get(app(db), id.value)
          results <- db.recentResultsFor(white.externalId)
        yield
          assertEquals(resp.status, Status.NotFound)
          assert(results.exists(_.gameId.value == id.value), "the game must still show as finished in game_results")
      }
    }
