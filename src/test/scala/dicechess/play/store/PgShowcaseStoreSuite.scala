package dicechess.play.store

import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.server.ShowcaseHarness.{await, FeaturedBot}
import dicechess.play.server.{AdmissionGuard, GameRegistry, ShowcaseConfig, ShowcaseTable}
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import java.util.UUID

/** The showcase table's durable half against a real PostgreSQL (V6, ADR-005 §5–§7, #46): the singleton row, the claim
  * fence and colour advance in one transaction, idempotent records with a retention window, the crash-recovery repair,
  * and — end to end — a claim that survives a "restart" (a fresh registry and coordinator over the same database) and
  * reopens the table only once the game's terminal transaction has landed.
  *
  * `showcase_table` is a singleton, so every test resets it first; the suite shares one database and runs serially.
  */
class PgShowcaseStoreSuite extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def storeAndXa(pg: PostgreSQLContainer) =
    for
      db        <- PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))
      connectEC <- ExecutionContexts.fixedThreadPool[IO](2)
      xa        <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = "org.postgresql.Driver",
        url = pg.jdbcUrl,
        user = pg.username,
        pass = pg.password,
        connectEC = connectEC
      )
    yield (db, xa)

  private def reset(xa: Transactor[IO]): IO[Unit] =
    (sql"UPDATE play.showcase_table SET next_human_color = 'white', current_game_id = NULL WHERE id = 1".update.run *>
      sql"DELETE FROM play.showcase_claims".update.run).void.transact(xa)

  private val guest = Principal.Guest("44444444-4444-4444-4444-444444444444")

  test("V6 seeds exactly one table row: White to play first, no current game"):
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        for
          _     <- reset(xa)
          rows  <- sql"SELECT count(*) FROM play.showcase_table".query[Int].unique.transact(xa)
          table <- db.showcaseTable
          // The CHECK admits no second row, whatever id is offered.
          second <- sql"INSERT INTO play.showcase_table (id) VALUES (2)".update.run.transact(xa).attempt
        yield
          assertEquals(rows, 1)
          assertEquals(table, ShowcaseTableRecord(Side.White, None))
          assert(second.isLeft, "a second table row must violate the singleton constraint")
      }
    }

  test(
    "commitShowcaseClaim advances the colour, points the table at the game and records the claim in one transaction"
  ):
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        for
          _     <- reset(xa)
          id    <- GameId.random
          key   <- IO(UUID.randomUUID())
          moved <- db.commitShowcaseClaim(guest.externalId, key, "hash", id, Side.White, Side.White)
          table <- db.showcaseTable
          found <- db.findShowcaseClaim(guest.externalId, key)
          // The fence: with a game current, no further claim can commit, whatever colour it expects.
          other     <- GameId.random
          k2        <- IO(UUID.randomUUID())
          refused   <- db.commitShowcaseClaim(guest.externalId, k2, "hash", other, Side.Black, Side.Black)
          unchanged <- db.showcaseTable
          none      <- db.findShowcaseClaim(guest.externalId, k2)
        yield
          assert(moved)
          assertEquals(table, ShowcaseTableRecord(Side.Black, Some(id)))
          assertEquals(
            found.map(r => (r.outcome, r.gameId, r.humanColor, r.requestHash)),
            Some((ShowcaseClaimOutcome.Claimed, Some(id), Some(Side.White), "hash"))
          )
          assert(found.exists(r => r.expiresAt.isAfter(r.createdAt)))
          assert(!refused, "a second game cannot become current while one is")
          assertEquals(unchanged, table)
          assertEquals(none, None, "a refused commit writes no claim record")
      }
    }

  test("commitShowcaseClaim refuses a stale colour expectation without writing anything"):
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        for
          _       <- reset(xa)
          id      <- GameId.random
          key     <- IO(UUID.randomUUID())
          refused <- db.commitShowcaseClaim(guest.externalId, key, "hash", id, Side.Black, Side.Black)
          table   <- db.showcaseTable
        yield
          assert(!refused)
          assertEquals(table, ShowcaseTableRecord(Side.White, None))
      }
    }

  test("clearShowcaseGame answers true once and false for a duplicate completion"):
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        for
          _      <- reset(xa)
          id     <- GameId.random
          key    <- IO(UUID.randomUUID())
          _      <- db.commitShowcaseClaim(guest.externalId, key, "hash", id, Side.White, Side.White)
          first  <- db.clearShowcaseGame(id)
          second <- db.clearShowcaseGame(id)
          table  <- db.showcaseTable
        yield
          assert(first)
          assert(!second)
          assertEquals(table, ShowcaseTableRecord(Side.Black, None), "clearing never touches the colour")
      }
    }

  test("adoptShowcaseGame repairs a colour the claim transaction never advanced, and only once"):
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        for
          _  <- reset(xa)
          id <- GameId.random
          // The row still says White although a White human is playing: the claim transaction was lost to a crash.
          repaired <- db.adoptShowcaseGame(id, Side.White)
          again    <- db.adoptShowcaseGame(id, Side.White)
          _        <- reset(xa)
          // The row already advanced (says Black) for a White human: nothing to repair.
          _        <- sql"UPDATE play.showcase_table SET next_human_color = 'black'".update.run.transact(xa)
          advanced <- db.adoptShowcaseGame(id, Side.White)
        yield
          assertEquals(repaired, ShowcaseTableRecord(Side.Black, Some(id)))
          assertEquals(again, repaired)
          assertEquals(advanced, ShowcaseTableRecord(Side.Black, Some(id)))
      }
    }

  test("spectating records are idempotent, expired records are invisible, and the next write prunes them"):
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        for
          _     <- reset(xa)
          id    <- GameId.random
          key   <- IO(UUID.randomUUID())
          _     <- db.recordSpectatingClaim(guest.externalId, key, "hash", Some(id))
          _     <- db.recordSpectatingClaim(guest.externalId, key, "other-hash-ignored", None)
          found <- db.findShowcaseClaim(guest.externalId, key)
          // Age the row past its window; both stamps move so the `expires_at > created_at` constraint still holds.
          _ <- sql"""UPDATE play.showcase_claims
                     SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day'
                     WHERE idempotency_key = $key""".update.run.transact(xa)
          gone <- db.findShowcaseClaim(guest.externalId, key)
          k2   <- IO(UUID.randomUUID())
          _    <- db.recordSpectatingClaim(guest.externalId, k2, "hash", None)
          rows <- sql"SELECT count(*) FROM play.showcase_claims".query[Int].unique.transact(xa)
        yield
          assertEquals(
            found.map(r => (r.outcome, r.gameId, r.requestHash)),
            Some((ShowcaseClaimOutcome.Spectating, Some(id), "hash"))
          )
          assertEquals(gone, None)
          assertEquals(rows, 1, "the expired record was pruned by the following write")
      }
    }

  test(
    "end to end over PostgreSQL: a claim survives a restart as live, and the table reopens after the durable ending"
  ):
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        val config = ShowcaseConfig(enabled = true, featuredBot = Some(FeaturedBot), reservedSeats = 1)
        def boot: IO[(GameRegistry, AdmissionGuard)] =
          for
            registry <- GameRegistry.create(store = db)
            guard    <- AdmissionGuard.create(db, config, registry = Some(registry))
            _        <- registry.attachAdmissionGuard(guard)
            _        <- registry.resume
          yield (registry, guard)
        def table(registry: GameRegistry, guard: AdmissionGuard) =
          ShowcaseTable.create(config, registry, guard, Some(db), botReady = IO.pure(true), alert = _ => IO.unit)
        for
          _                   <- reset(xa)
          _                   <- db.register(FeaturedBot.team, FeaturedBot.name, s"hash-${UUID.randomUUID()}")
          _                   <- db.setMaxConcurrentGames(FeaturedBot.team, FeaturedBot.name, 3)
          (registry1, guard1) <- boot
          key                 <- IO(UUID.randomUUID())
          won                 <- table(registry1, guard1).use { t =>
            t.reconcile.flatMap(phase => IO(assertEquals(phase, ShowcaseTable.Phase.Open(Side.White)))) *>
              t.claim(guest, key, "hash", None).map {
                case c: ShowcaseTable.ClaimOutcome.Claimed => c
                case other                                 => fail(s"expected a win, got $other")
              }
          }
          active   <- db.activeShowcaseGameIds
          recorded <- db.showcaseTable
          // "Restart": a fresh registry resumes the room from its durable snapshot; a fresh coordinator adopts it.
          (registry2, guard2) <- boot
          _                   <- table(registry2, guard2).use { t2 =>
            for
              phase    <- t2.reconcile
              replay   <- t2.claim(guest, key, "hash", None)
              room     <- registry2.get(won.gameId).map(_.getOrElse(fail("the showcase game was not resumed")))
              _        <- room.submit(Seat.White, GameCommand.Resign)
              reopened <- await(t2.currentPhase)(_ == ShowcaseTable.Phase.Open(Side.Black))
              after    <- db.showcaseTable
              none     <- db.activeShowcaseGameIds
              archive  <- db.archiveFor(won.gameId)
            yield
              assert(phase.isInstanceOf[ShowcaseTable.Phase.Live], phase.toString)
              assertEquals(
                replay,
                won,
                "the seat token was durable, so the winner's retry is honoured after the restart"
              )
              assertEquals(reopened, ShowcaseTable.Phase.Open(Side.Black))
              assertEquals(after, ShowcaseTableRecord(Side.Black, None))
              assertEquals(none, Nil)
              assert(archive.isDefined, "the table reopened only after the game's archive row was committed")
          }
        yield
          assertEquals(active, List(won.gameId))
          assertEquals(recorded, ShowcaseTableRecord(Side.Black, Some(won.gameId)))
      }
    }
