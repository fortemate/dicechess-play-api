package dicechess.play.store

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.{GameId, Principal, RatingCategory, RatingPolicy, Seat}
import dicechess.play.rating.Glicko
import doobie.ConnectionIO
import doobie.implicits.*
import doobie.postgres.implicits.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import java.time.Instant
import java.util.UUID

/** Real PostgreSQL checks for the read-to-write generation fence and active-epoch projections. The admin setting below
  * simulates an isolated, coordinated cutover, never a production operation.
  */
class RatingEpochFenceSuite extends CatsEffectSuite with TestContainerForAll:
  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private val cutoff   = Instant.parse("2026-10-01T00:00:00Z")
  private val later    = cutoff.plusSeconds(30)
  private val white    = RatedIdentity.Bot("epoch-test", "white")
  private val black    = RatedIdentity.Bot("epoch-test", "black")
  private val category = RatingCategory.Blitz

  private def admin[A](db: PgGameStore)(action: ConnectionIO[A]): IO[A] =
    (sql"SELECT set_config('play.rating_epoch_admin', 'on', true)".query[String].unique *> action).transact(db.xa)

  private def withStore[A](action: PgGameStore => IO[A]): IO[A] =
    withContainers { pg =>
      PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password)).use { db =>
        val reset = for
          _ <- sql"""UPDATE play.rating_epoch_control SET active_epoch = NULL, active_run_id = NULL,
                     paused = false, revision = 0 WHERE singleton""".update.run
          _ <- sql"DELETE FROM play.rating_epoch_counts".update.run
          _ <- sql"DELETE FROM play.bot_ratings".update.run
          _ <- sql"DELETE FROM play.user_ratings".update.run
          _ <- sql"DELETE FROM play.user_training_ratings".update.run
          _ <- sql"DELETE FROM play.bot_strength_snapshots".update.run
          _ <- sql"DELETE FROM play.rating_epoch_runs".update.run
          _ <- sql"""INSERT INTO play.bots (team, name, token_hash)
                     VALUES ('epoch-test', 'white', 'epoch-white-fixture'), ('epoch-test', 'black', 'epoch-black-fixture')
                     ON CONFLICT (team, name) DO NOTHING""".update.run
        yield ()
        admin(db)(reset) *> action(db)
      }
    }

  private def activate(db: PgGameStore): IO[Unit] =
    val id = UUID.randomUUID()
    admin(db) {
      sql"""INSERT INTO play.rating_epoch_runs
             (run_id, plan_sha256, phase, source_revision, source_state_sha256, target_epoch, cutoff, anchor_set_version, plan, applied_at)
             VALUES ($id, ${id.toString}, 'applied', 0, 'fixture-source', 42, $cutoff, 'v1.0',
               '{"config":{"modelVersion":"glicko2-one-game-training-v1","policyVersion":2}}'::jsonb, now())""".update.run *>
        sql"""UPDATE play.rating_epoch_control SET active_epoch = 42, active_run_id = $id,
               revision = revision + 1 WHERE singleton""".update.run.void
    }

  private def game(db: PgGameStore, policy: Int = 2, finishedAt: Instant = later): IO[GameId] =
    val id = UUID.randomUUID()
    sql"""INSERT INTO play.game_results
           (game_id, white_external_id, black_external_id, result, termination, rated, time_control,
            server_seed, finished_at, rating_domain, rating_policy_version, rating_outcome)
           VALUES ($id, 'bot:team:epoch-test:white', 'bot:team:epoch-test:black', 1, 'resign', true,
                   'Fischer(300,3)', 'fixture', $finishedAt, 'competitive', $policy, 'pending')""".update.run
      .transact(db.xa)
      .as(GameId(id.toString))

  private def update(db: PgGameStore, id: GameId): IO[Unit] =
    db.applyRatingUpdate(
      id,
      RatingUpdate(white, category, Glicko(1500, 80, 0.06), Glicko(1510, 75, 0.06)),
      RatingUpdate(black, category, Glicko(1600, 80, 0.06), Glicko(1590, 75, 0.06))
    )

  test("a generation change after the state read rejects the write without stamping the game") {
    withStore { db =>
      for
        id       <- game(db)
        read     <- Deferred[IO, Unit]
        continue <- Deferred[IO, Unit]
        writer   <- db
          .withRatingEpoch {
            db.categoryRatingOf(white, category) *> read.complete(()) *> continue.get *> update(db, id)
          }
          .attempt
          .start
        _ <- read.get
        _ <- sql"UPDATE play.rating_epoch_control SET revision = revision + 1 WHERE singleton".update.run
          .transact(db.xa)
        _        <- continue.complete(())
        result   <- writer.joinWithNever
        recorded <- db.ratingChangeFor(id)
        rating   <- db.categoryRatingOf(white, category)
      yield
        assert(result.isLeft)
        assertEquals(recorded.map(_.applied), Some(false))
        assertEquals(rating, Glicko.Initial)
    }
  }

  test("an activated epoch requires a fenced writer and pause still permits privacy deletion") {
    withStore { db =>
      for
        _ <-
          sql"""INSERT INTO play.bot_ratings (team, name, category) VALUES ('epoch-test', 'white', 'blitz')""".update.run
            .transact(db.xa)
        _        <- activate(db)
        rejected <- sql"UPDATE play.bot_ratings SET rating = 1700 WHERE team = 'epoch-test'".update.run
          .transact(db.xa)
          .attempt
        _      <- sql"UPDATE play.rating_epoch_control SET paused = true WHERE singleton".update.run.transact(db.xa)
        paused <- db.withRatingEpoch(IO.unit).attempt
        _      <- sql"DELETE FROM play.bots WHERE team = 'epoch-test' AND name = 'white'".update.run.transact(db.xa)
        rows   <- sql"SELECT count(*) FROM play.bot_ratings WHERE team = 'epoch-test' AND name = 'white'"
          .query[Long]
          .unique
          .transact(db.xa)
      yield
        assert(rejected.isLeft)
        assert(paused.isLeft)
        assertEquals(rows, 0L)
    }
  }

  test("ownership changes between participant/state reads and commit invalidate the calculation") {
    withStore { db =>
      for
        id       <- game(db)
        _        <- activate(db)
        read     <- Deferred[IO, Unit]
        continue <- Deferred[IO, Unit]
        writer   <- db
          .withRatingEpoch {
            db.ratingOf("epoch-test", "white") *> db.categoryRatingOf(white, category) *>
              read.complete(()) *> continue.get *> update(db, id)
          }
          .attempt
          .start
        _ <- read.get
        _ <- sql"""UPDATE play.bots SET owner_external_id = 'user:ownership-fixture',
                   ownership_generation = ownership_generation + 1
                   WHERE team = 'epoch-test' AND name = 'white'""".update.run.transact(db.xa)
        _        <- continue.complete(())
        result   <- writer.joinWithNever
        recorded <- db.ratingChangeFor(id)
        rating   <- db.categoryRatingOf(white, category)
      yield
        assert(result.isLeft)
        assertEquals(recorded.map(_.applied), Some(false))
        assertEquals(rating, Glicko.Initial)
    }
  }

  test("competitive movements atomically count once and public reads use the selected epoch") {
    withStore { db =>
      for
        id <- game(db)
        _  <- activate(db)
        _  <- admin(db) {
          sql"""INSERT INTO play.rating_epoch_counts VALUES
                 ('bot:team:epoch-test:white', 'blitz', 10, 6, 1, 3),
                 ('bot:team:epoch-test:black', 'blitz', 10, 6, 1, 3)""".update.run *>
            sql"""INSERT INTO play.bot_strength_snapshots
                   (category, epoch, anchor_set_version, cutoff, is_current, payload)
                   VALUES ('blitz', 99, 'unselected', $cutoff, true, '{"leaders":[]}'::jsonb)""".update.run.void
        }
        _       <- db.withRatingEpoch(update(db, id))
        _       <- db.withRatingEpoch(update(db, id))
        tallies <- db.categoryTalliesFor("bot:team:epoch-test:white")
        view    <- db.liveLeaderboardView(category, true, false, 110, 200)
        epoch   <- sql"SELECT rating_epoch FROM play.game_results WHERE game_id = ${id.value}::uuid"
          .query[Option[Int]]
          .unique
          .transact(db.xa)
      yield
        assertEquals(tallies(category), ResultTally(7, 1, 3))
        assertEquals(view.bots.find(_.name == "white").map(_.tally.games), Some(11))
        assertEquals(view.snapshot.map(_.epoch), Some(42))
        assertEquals(view.dataCutoff, Some(later))
        assertEquals(epoch, Some(42))
    }
  }

  test("a missing game cannot mutate rating projections or counts before or after epoch activation") {
    List(false, true).traverse_ { activated =>
      withStore { db =>
        for
          _ <- admin(db) {
            sql"""INSERT INTO play.bot_ratings (team, name, category, rating, rd, vol)
                   VALUES ('epoch-test', 'white', 'blitz', 1500, 80, 0.06),
                          ('epoch-test', 'black', 'blitz', 1600, 80, 0.06)""".update.run *>
              sql"""INSERT INTO play.rating_epoch_counts
                     VALUES ('bot:team:epoch-test:white', 'blitz', 10, 6, 1, 3)""".update.run.void
          }
          _          <- activate(db).whenA(activated)
          missing    <- GameId.random
          _          <- db.withRatingEpoch(update(db, missing))
          whiteAfter <- db.categoryRatingOf(white, category)
          blackAfter <- db.categoryRatingOf(black, category)
          counts     <- sql"""SELECT games, wins, draws, losses FROM play.rating_epoch_counts
                          WHERE participant_external_id = 'bot:team:epoch-test:white' AND category = 'blitz'"""
            .query[(Long, Long, Long, Long)]
            .unique
            .transact(db.xa)
          recorded <- db.ratingChangeFor(missing)
        yield
          assertEquals(whiteAfter, Glicko(1500, 80, 0.06))
          assertEquals(blackAfter, Glicko(1600, 80, 0.06))
          assertEquals(counts, (10L, 6L, 1L, 3L))
          assertEquals(recorded, None)
      }
    }
  }

  test("late source-policy and pre-cutoff games are skipped without moving the active projection") {
    withStore { db =>
      for
        source       <- game(db, policy = 1)
        old          <- game(db, finishedAt = cutoff)
        _            <- activate(db)
        _            <- db.withRatingEpoch(update(db, source))
        _            <- db.withRatingEpoch(update(db, old))
        sourceChange <- db.ratingChangeFor(source)
        oldChange    <- db.ratingChangeFor(old)
        rating       <- db.categoryRatingOf(white, category)
        tallies      <- db.categoryTalliesFor("bot:team:epoch-test:white")
      yield
        assertEquals(sourceChange.map(_.outcome.wireName), Some("skipped"))
        assertEquals(oldChange.map(_.outcome.wireName), Some("skipped"))
        assertEquals(sourceChange.flatMap(_.domain.map(_.wireName)), Some("competitive"))
        assertEquals(rating, Glicko.Initial)
        assertEquals(tallies, Map.empty[RatingCategory, ResultTally])
    }
  }

  test("training updates retain their own counters and leave competitive epoch counts alone") {
    withStore { db =>
      val user = UUID.randomUUID()
      for
        _  <- sql"INSERT INTO play.users (id, nickname) VALUES ($user, ${user.toString})".update.run.transact(db.xa)
        id <- game(db)
        _  <- sql"""UPDATE play.game_results SET white_external_id = ${Principal.User(user.toString).externalId},
                   rated = false, rating_domain = 'training' WHERE game_id = ${id.value}::uuid""".update.run
          .transact(db.xa)
        _ <- activate(db)
        _ <- db.withRatingEpoch {
          db.applyTrainingUpdate(
            id,
            RatingUpdate(RatedIdentity.User(user.toString), category, Glicko.Initial, Glicko(1510, 100, 0.06)),
            Seat.Black,
            1600,
            1.0,
            later
          )
        }
        state  <- db.trainingStateOf(user.toString, category)
        counts <- db.categoryTalliesFor(Principal.User(user.toString).externalId)
      yield
        assertEquals(state.games, 1)
        assertEquals(state.wins, 1)
        assertEquals(counts, Map.empty[RatingCategory, ResultTally])
    }
  }

  test("active provenance is honest and the runtime policy/model contract fails closed") {
    withStore { db =>
      for
        before   <- db.activeRatingEpoch
        _        <- activate(db)
        metadata <- db.activeRatingEpoch
        _        <- db.validateRatingEpochRuntime(RatingPolicy.Matrix)
        legacy   <- db.validateRatingEpochRuntime(RatingPolicy.Legacy).attempt
        _        <- admin(db) {
          sql"""UPDATE play.rating_epoch_runs SET plan =
                 jsonb_set(plan, '{config,modelVersion}', '"unsupported"'::jsonb)""".update.run.void
        }
        unsupported <- db.validateRatingEpochRuntime(RatingPolicy.Matrix).attempt
      yield
        assertEquals(before, None)
        assertEquals(metadata.map(_.epoch), Some(42))
        assertEquals(metadata.map(_.modelVersion), Some("glicko2-one-game-training-v1"))
        assertEquals(metadata.map(_.cutoff), Some(cutoff))
        assert(legacy.isLeft)
        assert(unsupported.isLeft)
    }
  }
