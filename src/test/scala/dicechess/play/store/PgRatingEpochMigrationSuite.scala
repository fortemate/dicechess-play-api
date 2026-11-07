package dicechess.play.store

import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.{GameOrigin, ParticipantKind, RatingDomain, RatingOutcome}
import dicechess.play.rating.{CapturedTrainingReference, Glicko, TrainingReference, TrainingReferenceSource}
import dicechess.play.rating.migration.RatingEpochPlan
import dicechess.play.rating.migration.RatingEpochPlan.*
import dicechess.play.server.{Leaderboard, LeaderboardRoutes}
import doobie.ConnectionIO
import doobie.implicits.*
import doobie.postgres.implicits.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import org.http4s.{Request, Uri}
import org.http4s.implicits.*
import io.circe.Json
import io.circe.generic.semiauto.deriveDecoder
import io.circe.parser.parse

import java.time.Instant
import java.util.UUID

/** Synthetic rehearsal against the production migrations and a real PostgreSQL database. */
class PgRatingEpochMigrationSuite extends CatsEffectSuite with TestContainerForAll:
  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))
  private val cutoff   = Instant.parse("2026-10-01T00:00:00Z")
  private val white    = "00000000-0000-0000-0000-000000000001"
  private val black    = "00000000-0000-0000-0000-000000000002"
  private val evidence = "a" * 64
  private val config   = Config("b" * 64, 42, "synthetic-anchors", cutoff)
  private val reset    = RatingEpochPlan
    .build(
      config.copy(approvedReset =
        Some(ApprovedReset("Synthetic isolated reset decision", "synthetic-owner", evidence))
      ),
      Iterator.empty
    )
    .toOption
    .get
  private val game = SourceGame(
    "fixture-game",
    cutoff.minusSeconds(1),
    "Fischer(300,3)",
    SourceSeat("bot:team:epoch-migration:white", ParticipantKind.Bot, Some(IdentityContinuity(evidence, Some(white)))),
    SourceSeat("bot:team:epoch-migration:black", ParticipantKind.Bot, Some(IdentityContinuity(evidence, Some(black)))),
    Some(1),
    "king_captured",
    GameOrigin.Ladder,
    true,
    true,
    RatingOutcome.Applied,
    Some(ClassificationEvidence(2, true, true, RatingDomain.Competitive, evidence)),
    Some(OwnershipEvidence(false, evidence))
  )
  private val rebuilt = RatingEpochPlan.build(config, Iterator(game)).toOption.get
  private case class LegacyRow(rank: Int, name: String, rating: Double, rd: Double, games: Int)
  private given io.circe.Decoder[LegacyRow] = deriveDecoder
  private case class LegacyBoard(category: String, leaders: List[LegacyRow])
  private given io.circe.Decoder[LegacyBoard] = deriveDecoder

  private def response(db: PgGameStore, path: String): IO[Json] =
    LeaderboardRoutes(db, db, db, db, Some(db)).orNotFound.run(Request[IO](uri = Uri.unsafeFromString(path))).flatMap {
      result =>
        assertEquals(result.status.code, 200)
        result.bodyText.compile.string.flatMap(text => IO.fromEither(parse(text)))
    }

  private def admin[A](db: PgGameStore)(action: ConnectionIO[A]): IO[A] =
    (sql"SELECT set_config('play.rating_epoch_admin','on',true)".query[String].unique *> action).transact(db.xa)

  private def fixture[A](action: (PgGameStore, PgRatingEpochMigration) => IO[A]): IO[A] = withContainers { pg =>
    PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password)).use { db =>
      val seed = for
        _ <-
          sql"UPDATE play.rating_epoch_control SET active_epoch=NULL,active_run_id=NULL,paused=false,revision=0 WHERE singleton".update.run
        _ <- sql"DELETE FROM play.rating_epoch_counts".update.run
        _ <- sql"DELETE FROM play.bot_ratings".update.run
        _ <- sql"DELETE FROM play.user_ratings".update.run
        _ <- sql"DELETE FROM play.user_training_ratings".update.run
        _ <- sql"DELETE FROM play.bot_strength_snapshots".update.run
        _ <- sql"DELETE FROM play.rating_epoch_runs".update.run
        _ <- sql"DELETE FROM play.game_results".update.run
        _ <- sql"DELETE FROM play.bots WHERE team='epoch-migration'".update.run
        _ <- sql"""INSERT INTO play.bots(team,name,token_hash,incarnation_id) VALUES
                   ('epoch-migration','white','synthetic-migration-white',$white::uuid),
                   ('epoch-migration','black','synthetic-migration-black',$black::uuid)""".update.run
        _ <- sql"""INSERT INTO play.bot_ratings(team,name,category,rating,rd,vol)
                   VALUES ('epoch-migration','white','blitz',1678,77,0.07)""".update.run
        _ <- sql"""INSERT INTO play.game_results(game_id,white_external_id,black_external_id,result,termination,
                   rated,time_control,server_seed,finished_at,rating_domain,rating_policy_version,rating_outcome,
                   white_rating_before,white_rating_after,rating_applied_at)
                   VALUES (${UUID.randomUUID()},'bot:team:epoch-migration:white','bot:team:epoch-migration:black',1,
                   'resign',true,'Fischer(300,3)','synthetic',$cutoff,'legacy',0,'legacy',1600,1678,$cutoff)""".update.run
        _ <- sql"""INSERT INTO play.bot_strength_snapshots(category,epoch,anchor_set_version,cutoff,is_current,payload)
                   VALUES ('blitz',1,'source-synthetic',$cutoff,true,'{"leaders":[]}'::jsonb)""".update.run
      yield ()
      admin(db)(seed) *> action(db, PgRatingEpochMigration.fromStore(db))
    }
  }

  private def staged(
      tool: PgRatingEpochMigration,
      plan: Plan = reset
  ): IO[(PgRatingEpochMigration.Probe, PgRatingEpochMigration.RunStatus)] =
    for
      initial <- tool.probe
      paused  <- tool.setPaused(initial.revision, true)
      bound = plan.copy(config = plan.config.copy(databaseSourceSha256 = Some(paused.sourceSha256)))
      run <- tool.stage(bound, paused.revision, paused.sourceSha256)
    yield (paused, run)

  test("dry probe and repeated stage are non-destructive and immutable") {
    fixture { (_, tool) =>
      for
        original      <- tool.probe
        (paused, run) <- staged(tool)
        again         <- tool.stage(
          reset.copy(config = reset.config.copy(databaseSourceSha256 = Some(paused.sourceSha256))),
          paused.revision,
          paused.sourceSha256
        )
        after <- tool.probe
      yield
        assertEquals(run, again)
        assertEquals(after.sourceSha256, original.sourceSha256)
        assertEquals(run.phase, "staged")
        assert(after.activeEpoch.isEmpty)
    }
  }

  test("reset activation and exact rollback preserve recorded history and source snapshot") {
    fixture { (db, tool) =>
      val history =
        sql"SELECT to_jsonb(g)::text FROM play.game_results g ORDER BY game_id".query[String].to[List].transact(db.xa)
      for
        original         <- tool.probe
        before           <- history
        (paused, run)    <- staged(tool)
        applied          <- tool.activate(run.runId, paused.revision, run.planSha256)
        repeated         <- tool.activate(run.runId, paused.revision, run.planSha256)
        active           <- tool.probe
        empty            <- sql"SELECT count(*) FROM play.bot_ratings".query[Long].unique.transact(db.xa)
        after            <- history
        rolled           <- tool.rollback(run.runId, active.revision)
        repeatedRollback <- tool.rollback(run.runId, active.revision)
        restored         <- tool.probe
        sourceRating     <- sql"SELECT rating FROM play.bot_ratings WHERE team='epoch-migration'"
          .query[Double]
          .unique
          .transact(db.xa)
        sourceCurrent <- sql"SELECT is_current FROM play.bot_strength_snapshots WHERE epoch=1"
          .query[Boolean]
          .unique
          .transact(db.xa)
        retired <- sql"SELECT count(*) FROM play.bot_strength_snapshots WHERE epoch=42 AND NOT is_current"
          .query[Long]
          .unique
          .transact(db.xa)
      yield
        assertEquals(applied, repeated)
        assertEquals(rolled, repeatedRollback)
        assertEquals(empty, 0L)
        assertEquals(before, after)
        assertEquals(active.activeEpoch, Some(42))
        assertEquals(restored.activeEpoch, original.activeEpoch)
        assertEquals(sourceRating, 1678.0)
        assert(sourceCurrent)
        assertEquals(retired, 3L)
    }
  }

  test("a failure during publication rolls back the complete swap and retry applies exactly once") {
    fixture { (db, tool) =>
      for
        (paused, run) <- staged(tool)
        _             <- doobie.Fragment
          .const("""CREATE FUNCTION play.synthetic_epoch_failure() RETURNS trigger LANGUAGE plpgsql AS
                   $$ BEGIN RAISE EXCEPTION 'synthetic interruption'; END $$""")
          .update
          .run
          .transact(db.xa)
        _ <- sql"""CREATE TRIGGER synthetic_epoch_failure BEFORE INSERT ON play.bot_strength_snapshots
                   FOR EACH ROW EXECUTE FUNCTION play.synthetic_epoch_failure()""".update.run.transact(db.xa)
        failed  <- tool.activate(run.runId, paused.revision, run.planSha256).attempt
        after   <- tool.probe
        stage   <- tool.status(run.runId)
        _       <- sql"DROP TRIGGER synthetic_epoch_failure ON play.bot_strength_snapshots".update.run.transact(db.xa)
        _       <- sql"DROP FUNCTION play.synthetic_epoch_failure()".update.run.transact(db.xa)
        applied <- tool.activate(run.runId, paused.revision, run.planSha256)
      yield
        assert(failed.isLeft)
        assertEquals(after, paused)
        assertEquals(stage.phase, "staged")
        assertEquals(stage.beforeStateSha256, None)
        assertEquals(applied.phase, "applied")
    }
  }

  test("source ledger edits invalidate a staged fingerprint even without a projection revision change") {
    fixture { (db, tool) =>
      for
        (paused, run) <- staged(tool)
        _             <- sql"UPDATE play.game_results SET termination='king_captured'".update.run.transact(db.xa)
        unchanged     <- sql"SELECT revision FROM play.rating_epoch_control WHERE singleton"
          .query[Long]
          .unique
          .transact(db.xa)
        rejected <- tool.activate(run.runId, paused.revision, run.planSha256).attempt
        result   <- tool.status(run.runId)
      yield
        assertEquals(unchanged, paused.revision)
        assert(rejected.isLeft)
        assertEquals(result.phase, "staged")
    }
  }

  test("rebuild installs domain projections and counters without rewriting legacy decisions") {
    fixture { (db, tool) =>
      for
        (paused, run) <- staged(tool, rebuilt)
        _             <- tool.activate(run.runId, paused.revision, run.planSha256)
        counts <- sql"SELECT games,wins,draws,losses FROM play.rating_epoch_counts ORDER BY participant_external_id"
          .query[(Long, Long, Long, Long)]
          .to[List]
          .transact(db.xa)
        provenance <- sql"SELECT rating_domain,rating_policy_version,rating_epoch FROM play.game_results"
          .query[(String, Int, Option[Int])]
          .unique
          .transact(db.xa)
        changed <- sql"SELECT rating FROM play.bot_ratings WHERE name='white'".query[Double].unique.transact(db.xa)
      yield
        assertEquals(counts, List((1L, 0L, 0L, 1L), (1L, 1L, 0L, 0L)))
        assertEquals(provenance, ("legacy", 0, None))
        assertEquals(changed, rebuilt.rows.find(_.externalId.endsWith(":white")).get.glicko.rating)
    }
  }

  test("post-cutover ledger edits close fast rollback without losing the active epoch") {
    fixture { (db, tool) =>
      for
        (paused, run) <- staged(tool)
        _             <- tool.activate(run.runId, paused.revision, run.planSha256)
        active        <- tool.probe
        _             <- sql"UPDATE play.game_results SET termination='king_captured'".update.run.transact(db.xa)
        rejected      <- tool.rollback(run.runId, active.revision).attempt
        after         <- tool.probe
      yield
        assert(rejected.isLeft)
        assertEquals(after.activeEpoch, Some(42))
    }
  }

  test("a committed revision advance cannot be hidden by pausing again for rollback") {
    fixture { (_, tool) =>
      for
        (paused, run) <- staged(tool)
        _             <- tool.activate(run.runId, paused.revision, run.planSha256)
        active        <- tool.probe
        resumed       <- tool.setPaused(active.revision, false)
        pausedAgain   <- tool.setPaused(resumed.revision, true)
        refused       <- tool.rollback(run.runId, pausedAgain.revision).attempt
      yield assert(refused.isLeft)
    }
  }

  test("unknown provenance and an occupied target epoch are rejected before projection mutation") {
    fixture { (_, tool) =>
      val unknown   = RatingEpochPlan.build(config, Iterator(game.copy(classification = None))).toOption.get
      val collision = reset.copy(config = reset.config.copy(targetEpoch = 1))
      for
        original <- tool.probe
        paused   <- tool.setPaused(original.revision, true)
        a        <- tool.stage(unknown, paused.revision, paused.sourceSha256).attempt
        b        <- tool
          .stage(
            collision.copy(config = collision.config.copy(databaseSourceSha256 = Some(paused.sourceSha256))),
            paused.revision,
            paused.sourceSha256
          )
          .attempt
        after <- tool.probe
      yield
        assert(a.isLeft)
        assert(b.isLeft)
        assertEquals(after, paused)
    }
  }

  test("a valid audited historical incarnation cannot be installed onto a newly registered bot") {
    fixture { (_, tool) =>
      val mismatched = rebuilt.copy(identityIncarnations =
        rebuilt.identityIncarnations.updated("bot:team:epoch-migration:white", "00000000-0000-0000-0000-000000000003")
      )
      for
        initial  <- tool.probe
        paused   <- tool.setPaused(initial.revision, true)
        rejected <- tool
          .stage(
            mismatched.copy(config = mismatched.config.copy(databaseSourceSha256 = Some(paused.sourceSha256))),
            paused.revision,
            paused.sourceSha256
          )
          .attempt
        after <- tool.probe
      yield
        assertEquals(RatingEpochPlan.validate(mismatched), Right(()))
        assert(rejected.isLeft)
        assertEquals(after, paused)
    }
  }

  test("legacy and current clients decode the selected board, training epoch and retained source snapshot") {
    fixture { (db, tool) =>
      val account = UUID.randomUUID()
      val games   = Vector.tabulate(40) { index =>
        game.copy(
          gameId = s"fixture-$index",
          finishedAt = cutoff.minusSeconds(100 - index),
          result = Some(if index % 2 == 0 then 1 else -1)
        )
      }
      val training = game.copy(
        gameId = "training-fixture",
        finishedAt = cutoff.minusSeconds(1),
        ladder = false,
        white = SourceSeat(s"user:$account", ParticipantKind.Human, Some(IdentityContinuity(evidence))),
        classification = Some(ClassificationEvidence(2, true, false, RatingDomain.Training, evidence)),
        reference = CapturedTrainingReference.Recorded(
          TrainingReference(TrainingReferenceSource.Anchor("synthetic-recorded", 1), Glicko(1600, 50, 0.06))
        )
      )
      val plan = RatingEpochPlan.build(config, (games :+ training).iterator).toOption.get
      for
        _ <- sql"INSERT INTO play.users(id,nickname) VALUES ($account,'epoch-migration-player')".update.run
          .transact(db.xa)
        oldSnapshot   <- response(db, s"/leaderboard/snapshots/blitz/1/$cutoff")
        (paused, run) <- staged(tool, plan)
        _             <- tool.activate(run.runId, paused.revision, run.planSha256)
        board         <- response(db, "/leaderboard?category=blitz")
        legacy        <- IO.fromEither(board.as[LegacyBoard])
        current       <- IO.fromEither(board.as[Leaderboard])
        player        <- response(db, "/players/by-nickname/epoch-migration-player")
        retained      <- response(db, s"/leaderboard/snapshots/blitz/1/$cutoff")
      yield
        assertEquals(legacy.category, "blitz")
        assertEquals(legacy.leaders.size, 2)
        assertEquals(legacy.leaders.map(_.games), List(40, 40))
        assertEquals(current.epoch, Some(42))
        assert(current.leaders.forall(row => row.domain.contains("competitive") && row.epoch.contains(42)))
        assertEquals(player.hcursor.downField("training").downArray.get[Int]("epoch"), Right(42))
        assertEquals(retained, oldSnapshot)
    }
  }
