package dicechess.play.store

import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.store.LaboratoryFixtures.snapshot
import dicechess.play.store.LaboratoryStorage.AcceptedCommand
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.flywaydb.core.Flyway
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

class PgLaboratoryStoreSuite extends CatsEffectSuite with TestContainerForAll:
  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def config(pg: PostgreSQLContainer): PgLaboratoryStore.Config =
    PgLaboratoryStore.Config(pg.jdbcUrl, pg.username, pg.password)

  // Bootstrap durable interrupted fixtures without running the room's sibling-task state machine.
  private def seed(db: PgLaboratoryStore, id: GameId, value: GameSnapshot): IO[Unit] =
    db.commit(id, None, value).flatMap(result => IO(assert(result != LaboratoryWrite.Conflict)))

  private def ended(reason: Termination = Termination.Draw): GameSnapshot =
    snapshot(Phase.Ended).copy(status = GameStatus.Ended(GameOver(GameResult.Draw, reason)))

  private def initialRematch(source: GameSnapshot): GameSnapshot =
    snapshot(Phase.AwaitingPlayers).copy(
      version = 0,
      timeControl = source.timeControl,
      serverSeed = "cd" * 32,
      seatTokens = Map(Seat.White -> "new-white-capability", Seat.Black -> "new-black-capability")
    )

  private def expiry(db: PgLaboratoryStore, id: GameId): IO[Instant] =
    sql"SELECT expires_at FROM laboratory.games WHERE game_id = ${id.value}::uuid"
      .query[Instant]
      .unique
      .transact(db.xa)

  test("fresh migration creates only laboratory tables and queryable variant projections"):
    withContainers { pg =>
      PgLaboratoryStore.resource(config(pg)).use { db =>
        for
          id     <- GameId.random
          _      <- seed(db, id, ended())
          tables <- sql"SELECT tablename FROM pg_tables WHERE schemaname = 'laboratory'"
            .query[String]
            .to[List]
            .transact(db.xa)
          foreign <- sql"SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'play')"
            .query[Boolean]
            .unique
            .transact(db.xa)
          projected <- sql"""SELECT g.variant_id, g.variant_revision, r.variant_id, t.variant_id
              FROM laboratory.games g JOIN laboratory.results r USING (game_id)
              JOIN laboratory.transcripts t USING (game_id) WHERE game_id = ${id.value}::uuid"""
            .query[(String, Int, String, String)]
            .unique
            .transact(db.xa)
        yield
          assertEquals(tables.toSet, Set("flyway_schema_history", "games", "results", "transcripts", "rematches"))
          assert(!foreign)
          assertEquals(projected, (TwoPairs.id, 1, TwoPairs.id, TwoPairs.id))
      }
    }

  test("every interrupted stage, complete generated offers, clocks and receipts survives a new store instance"):
    withContainers { pg =>
      for
        saved <- PgLaboratoryStore.resource(config(pg)).use { db =>
          Phase.values.toList.traverse { phase =>
            for
              id <- GameId.random
              base  = snapshot(phase)
              timed = base.copy(
                timeControl = TimeControl.Fischer(300, 3),
                remainingMs = Map(Seat.White -> 290123L, Seat.Black -> 299456L)
              )
              value = timed.copy(lab =
                timed.lab
                  .map(_.copy(receipts = Vector(AcceptedCommand(Seat.White, Command("first", 1, Action.RollFirst), 2))))
              )
              _ <- seed(db, id, value)
            yield id -> value
          }
        }
        _ <- PgLaboratoryStore.resource(config(pg)).use { fresh =>
          saved.traverse_ { case (id, value) =>
            for
              loaded  <- fresh.load(id)
              receipt <- fresh.receiptFor(id, Seat.White, "first")
              other   <- fresh.receiptFor(id, Seat.Black, "first")
            yield
              assertEquals(loaded, Some(value))
              assertEquals(receipt.map(_.appliedVersion), Some(2L))
              assertEquals(other, None)
          } *> fresh.loadActive.map { active =>
            saved.foreach { case (id, value) => assertEquals(active.exists(_._1 == id), !value.ended) }
          }
        }
      yield ()
    }

  test("version fencing accepts one concurrent transition and scopes durable retry fingerprints by seat"):
    withContainers { pg =>
      PgLaboratoryStore.resource(config(pg)).use { db =>
        for
          id <- GameId.random
          old = snapshot()
          _ <- db.commit(id, None, old)
          next = snapshot(Phase.AwaitingSecondRoll).copy(version = 11)
          a    = next.copy(lab =
            next.lab
              .map(_.copy(receipts = Vector(AcceptedCommand(Seat.White, Command("roll-a", 10, Action.RollFirst), 11))))
          )
          b = next.copy(lab =
            next.lab
              .map(_.copy(receipts = Vector(AcceptedCommand(Seat.White, Command("roll-b", 10, Action.RollFirst), 11))))
          )
          writes <- (db.commit(id, Some(10), a), db.commit(id, Some(10), b)).parTupled
          loaded <- db.load(id)
          retry  <- db.commit(id, Some(10), loaded.get)
          stale  <- db.commit(id, Some(10), next.copy(version = 12))
        yield
          assertEquals(List(writes._1, writes._2).count(_ == LaboratoryWrite.Applied), 1)
          assertEquals(List(writes._1, writes._2).count(_ == LaboratoryWrite.Conflict), 1)
          assertEquals(retry, LaboratoryWrite.Unchanged)
          assertEquals(stale, LaboratoryWrite.Conflict)
          assertEquals(loaded.flatMap(_.lab).map(_.receipts.size), Some(1))
      }
    }

  test("terminal writes retain selected and unselected offers, are idempotent, and never fabricate abort draws"):
    withContainers { pg =>
      PgLaboratoryStore.resource(config(pg)).use { db =>
        for
          id <- GameId.random
          choice = HistoryChoice(List(DicePair(1, 3), DicePair(4, 6)), 1)
          value  = ended()
            .copy(turns = Vector(TurnRecord(0, "w", List(4, 6), Nil, snapshot().dfen, labChoice = Some(choice))))
          _           <- seed(db, id, value)
          first       <- db.resultFor(id)
          _           <- db.save(id, value)
          second      <- db.resultFor(id)
          archive     <- db.transcriptFor(id)
          abortId     <- GameId.random
          _           <- seed(db, abortId, ended(Termination.Aborted))
          aborted     <- db.resultFor(abortId)
          abortExport <- db.transcriptFor(abortId)
        yield
          assertEquals(first, second)
          assertEquals(first.map(_.variant), Some(TwoPairs))
          assertEquals(first.map(_.result), Some(Some(0)))
          val turn = archive.get.payload.hcursor.downField("turns").downN(0)
          assertEquals(turn.get[List[List[Int]]]("offers"), Right(List(List(1, 3), List(4, 6))))
          assertEquals(turn.get[Int]("selectedPairIndex"), Right(1))
          assertEquals(aborted.map(_.result), Some(None))
          assertEquals(abortExport.get.payload.hcursor.get[String]("completeness"), Right("incomplete"))
      }
    }

  test("a failed terminal projection rolls back the snapshot, public transcript and receipt together"):
    withContainers { pg =>
      PgLaboratoryStore.resource(config(pg)).use { db =>
        for
          id <- GameId.random
          old = snapshot()
          _      <- seed(db, id, old)
          before <- db.transcriptFor(id)
          next = old.copy(
            version = 11,
            status = GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted)),
            lab = old.lab.map(s =>
              s.copy(
                turn = s.turn.copy(phase = Phase.Ended),
                receipts = Vector(AcceptedCommand(Seat.White, Command("terminal", 10, Action.RollFirst), 11))
              )
            )
          )
          _ <- sql"""ALTER TABLE laboratory.results ADD CONSTRAINT fixture_terminal_failure
              CHECK (termination <> 'Aborted') NOT VALID""".update.run.transact(db.xa)
          failed <- db
            .commit(id, Some(10), next)
            .attempt
            .guarantee(
              sql"ALTER TABLE laboratory.results DROP CONSTRAINT fixture_terminal_failure".update.run
                .transact(db.xa)
                .void
            )
          loaded  <- db.load(id)
          after   <- db.transcriptFor(id)
          receipt <- db.receiptFor(id, Seat.White, "terminal")
          result  <- db.resultFor(id)
        yield
          assert(failed.isLeft)
          assertEquals(loaded, Some(old))
          assertEquals(after, before)
          assertEquals(receipt, None)
          assertEquals(result, None)
      }
    }

  test("immutable settings, captured provenance and already-generated offers cannot be replaced"):
    withContainers { pg =>
      PgLaboratoryStore.resource(config(pg)).use { db =>
        for
          id <- GameId.random
          old = snapshot(Phase.AwaitingSecondRoll)
          _          <- seed(db, id, old)
          settings   <- db.commit(id, Some(10), old.copy(version = 11, timeControl = TimeControl.PerMove(30))).attempt
          provenance <- db
            .commit(
              id,
              Some(10),
              old.copy(
                version = 11,
                lab = old.lab.map(s => s.copy(provenance = s.provenance.copy(rulesVersion = "another-version")))
              )
            )
            .attempt
          offers <- db
            .commit(
              id,
              Some(10),
              old.copy(
                version = 11,
                lab =
                  old.lab.map(s => s.copy(turn = s.turn.copy(generatedOffers = List(DicePair(6, 6), DicePair(1, 1)))))
              )
            )
            .attempt
          loaded <- db.load(id)
        yield
          assert(settings.isLeft)
          assert(provenance.isLeft)
          assert(offers.isLeft)
          assertEquals(loaded, Some(old))
      }
    }

  test("unsupported revisions fail before writing and corrupt stored revisions produce sanitized visible errors"):
    withContainers { pg =>
      PgLaboratoryStore.resource(config(pg)).use { db =>
        for
          id <- GameId.random
          value = snapshot()
          unsupported <- db.commit(id, None, value.copy(variant = Some(TwoPairs.copy(revision = 2)))).attempt
          absent      <- db.load(id)
          _           <- seed(db, id, value)
          _           <- sql"""UPDATE laboratory.games SET snapshot = jsonb_set(snapshot, '{variant,revision}', '2')
              WHERE game_id = ${id.value}::uuid""".update.run.transact(db.xa)
          corrupt <- db.load(id).attempt
        yield
          assert(unsupported.isLeft)
          assertEquals(absent, None)
          assert(corrupt.isLeft)
          val message = corrupt.swap.toOption.get.getMessage
          assert(!message.contains(value.serverSeed))
          assert(!message.contains("private-white-token"))
      }
    }

  test("archive reads validate public stages and strip unexpected private-shaped fields"):
    withContainers { pg =>
      PgLaboratoryStore.resource(config(pg)).use { db =>
        for
          id <- GameId.random
          _  <- seed(db, id, snapshot(Phase.AwaitingSecondRoll))
          _  <- sql"""UPDATE laboratory.transcripts SET payload = payload || '{"seatTokens":"must-not-export"}'::jsonb
              WHERE game_id = ${id.value}::uuid""".update.run.transact(db.xa)
          clean <- db.transcriptFor(id)
          _     <-
            sql"""UPDATE laboratory.transcripts SET payload = jsonb_set(payload, '{currentTurn,selectedPairIndex}', '1')
              WHERE game_id = ${id.value}::uuid""".update.run.transact(db.xa)
          corrupt <- db.transcriptFor(id).attempt
          terminal   = ended().copy(lab = ended().lab.map(_.copy(fairnessRevealed = true)))
          fabricated = LaboratoryStorage.transcript(terminal).toOption.get
          _ <- sql"UPDATE laboratory.transcripts SET payload = $fabricated WHERE game_id = ${id.value}::uuid".update.run
            .transact(db.xa)
          prematureReveal <- db.transcriptFor(id).attempt
        yield
          assert(!clean.get.payload.noSpaces.contains("must-not-export"))
          assert(!clean.get.payload.noSpaces.contains("2,5"))
          assert(corrupt.isLeft)
          assert(prematureReveal.isLeft)
      }
    }

  test("retention is fixed at creation, bounds active and aborted transcripts, and cascades private records"):
    withContainers { pg =>
      PgLaboratoryStore.resource(config(pg)).use { db =>
        for
          active  <- GameId.random
          aborted <- GameId.random
          old = snapshot()
          _           <- seed(db, active, old)
          firstExpiry <- expiry(db, active)
          _           <- db.commit(active, Some(10), old.copy(version = 11))
          laterExpiry <- expiry(db, active)
          _           <- seed(db, aborted, ended(Termination.Aborted))
          _           <- List(active, aborted).traverse_(id => sql"""UPDATE laboratory.games
              SET created_at = now() - interval '3 days', expires_at = now() - interval '1 day'
              WHERE game_id = ${id.value}::uuid""".update.run.transact(db.xa))
          hidden            <- db.load(active)
          expiredTranscript <- db.transcriptFor(aborted)
          refused           <- db.commit(active, Some(11), old.copy(version = 12))
          one               <- db.pruneExpired(1)
          two               <- db.pruneExpired(1)
          none              <- db.pruneExpired(1)
          remaining         <- sql"""SELECT count(*) FROM laboratory.transcripts
              WHERE game_id IN (${active.value}::uuid, ${aborted.value}::uuid)""".query[Long].unique.transact(db.xa)
        yield
          assertEquals(firstExpiry, laterExpiry)
          assertEquals(hidden, None)
          assertEquals(expiredTranscript, None)
          assertEquals(refused, LaboratoryWrite.Conflict)
          assertEquals((one, two, none), (1, 1, 0))
          assertEquals(remaining, 0L)
      }
    }

  test("rematch creation atomically inherits variant and time control and keeps one successor under retries"):
    withContainers { pg =>
      PgLaboratoryStore.resource(config(pg)).use { db =>
        for
          source    <- GameId.random
          candidate <- GameId.random
          retryId   <- GameId.random
          old = ended().copy(timeControl = TimeControl.Fischer(300, 3))
          _ <- seed(db, source, old)
          fresh = initialRematch(old)
          created    <- db.createRematch(source, candidate, fresh)
          retry      <- db.createRematch(source, retryId, fresh)
          link       <- db.successorOf(source)
          loaded     <- db.load(candidate)
          absent     <- db.load(retryId)
          conditions <- sql"SELECT conditions FROM laboratory.rematches WHERE source_game_id = ${source.value}::uuid"
            .query[Json]
            .unique
            .transact(db.xa)
          _ <- sql"""UPDATE laboratory.rematches SET conditions = jsonb_set(conditions, '{variant,revision}', '2')
              WHERE source_game_id = ${source.value}::uuid""".update.run.transact(db.xa)
          invalidConditions <- db.successorOf(source).attempt
        yield
          assertEquals(created, LaboratoryRematchWrite.Created(candidate))
          assertEquals(retry, LaboratoryRematchWrite.Existing(candidate))
          assertEquals(link, Some(candidate))
          assertEquals(loaded, Some(fresh))
          assertEquals(absent, None)
          assertEquals(conditions.as[LaboratoryStorage.Conditions], Right(LaboratoryStorage.conditions(old)))
          assert(invalidConditions.isLeft)
      }
    }

  test("rematches reject changed settings, reused credentials and technical-abort sources without creating a game"):
    withContainers { pg =>
      PgLaboratoryStore.resource(config(pg)).use { db =>
        for
          source      <- GameId.random
          abortSource <- GameId.random
          candidate   <- GameId.random
          old = ended()
          _ <- seed(db, source, old)
          _ <- seed(db, abortSource, ended(Termination.Aborted))
          fresh = initialRematch(old)
          changed     <- db.createRematch(source, candidate, fresh.copy(timeControl = TimeControl.PerMove(60)))
          credentials <- db.createRematch(source, candidate, fresh.copy(seatTokens = old.seatTokens))
          abort       <- db.createRematch(abortSource, candidate, fresh)
          absent      <- db.load(candidate)
        yield
          assertEquals(changed, LaboratoryRematchWrite.Rejected)
          assertEquals(credentials, LaboratoryRematchWrite.Rejected)
          assertEquals(abort, LaboratoryRematchWrite.Rejected)
          assertEquals(absent, None)
      }
    }

  test("a disposable V1 classic fixture upgrades normally while the lab migrator refuses that database unchanged"):
    withContainers { pg =>
      for
        name <- IO("classic_fixture_" + UUID.randomUUID().toString.replace("-", ""))
        _    <- IO.blocking {
          val connection = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
          try
            val statement = connection.createStatement()
            try statement.executeUpdate(s"CREATE DATABASE $name")
            finally statement.close()
          finally connection.close()
        }
        url = pg.jdbcUrl.take(pg.jdbcUrl.lastIndexOf('/') + 1) + name
        _ <- IO.blocking {
          Flyway
            .configure()
            .dataSource(url, pg.username, pg.password)
            .schemas("play")
            .createSchemas(true)
            .locations("classpath:db/migration")
            .target("1")
            .load()
            .migrate()
          ()
        }
        id <- GameId.random
        classic = snapshot().copy(variant = None, lab = None)
        _ <- IO.blocking {
          val connection = DriverManager.getConnection(url, pg.username, pg.password)
          try
            val insert = connection.prepareStatement(
              "INSERT INTO play.games (id,status,snapshot) VALUES (?::uuid,'active',?::jsonb)"
            )
            try
              insert.setString(1, id.value)
              insert.setString(2, classic.asJson.noSpaces)
              insert.executeUpdate()
            finally insert.close()
          finally connection.close()
        }
        _ <- PgGameStore.resource(PgGameStore.Config(url, pg.username, pg.password)).use { db =>
          for
            loaded    <- db.loadActive
            wrong     <- db.save(id, snapshot()).attempt
            denied    <- PgLaboratoryStore.migrate(PgLaboratoryStore.Config(url, pg.username, pg.password)).attempt
            labSchema <- sql"SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'laboratory')"
              .query[Boolean]
              .unique
              .transact(db.xa)
            snapshots <- sql"SELECT count(*) FROM play.games".query[Long].unique.transact(db.xa)
            outbox    <- sql"SELECT count(*) FROM play.outbox".query[Long].unique.transact(db.xa)
          yield
            assertEquals(loaded.find(_._1 == id).map(_._2.effectiveVariant), Some(Classic))
            assert(wrong.isLeft)
            assert(denied.isLeft)
            assert(!labSchema)
            assertEquals(snapshots, 1L)
            assertEquals(outbox, 0L)
        }
      yield ()
    }

  test("invalid configuration and retention batch sizes return explicit failures"):
    withContainers { pg =>
      for
        pool <- PgLaboratoryStore.migrate(config(pg).copy(poolSize = 0)).attempt
        ttl  <- PgLaboratoryStore.migrate(config(pg).copy(retention = 366.days)).attempt
        _    <- PgLaboratoryStore.resource(config(pg)).use { db =>
          (db.pruneExpired(0).attempt, db.pruneExpired(1001).attempt).tupled.map { case (zero, large) =>
            assert(zero.isLeft)
            assert(large.isLeft)
          }
        }
      yield
        assert(pool.isLeft)
        assert(ttl.isLeft)
    }

  test("snapshot readers reject disagreement with queryable status projections"):
    withContainers { pg =>
      PgLaboratoryStore.resource(config(pg)).use { db =>
        for
          id <- GameId.random
          _  <- seed(db, id, snapshot())
          _  <- sql"UPDATE laboratory.games SET status = 'ended' WHERE game_id = ${id.value}::uuid".update.run
            .transact(db.xa)
          corrupt <- db.load(id).attempt
        yield assert(corrupt.isLeft)
      }
    }

  test("ordinary saves create a fresh invitation but cannot recreate a pruned in-flight game"):
    withContainers { pg =>
      PgLaboratoryStore.resource(config(pg)).use { db =>
        for
          id <- GameId.random
          initial = snapshot(Phase.AwaitingPlayers).copy(version = 0)
          _ <- db.save(id, initial)
          next = initial.copy(version = 1)
          _ <- db.save(id, next)
          _ <- sql"""UPDATE laboratory.games SET created_at = now() - interval '3 days',
              expires_at = now() - interval '1 day' WHERE game_id = ${id.value}::uuid""".update.run.transact(db.xa)
          _       <- db.pruneExpired(1)
          resumed <- db.save(id, next).attempt
          stale   <- db.commit(id, Some(1), next.copy(version = 2))
          absent  <- db.load(id)
        yield
          assert(resumed.isLeft)
          assertEquals(stale, LaboratoryWrite.Conflict)
          assertEquals(absent, None)
      }
    }
