package dicechess.play.store

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.{GameOrigin, ParticipantKind, RatingDomain, RatingOutcome}
import dicechess.play.rating.{CapturedTrainingReference, Glicko, TrainingReference, TrainingReferenceSource}
import dicechess.play.rating.migration.RatingEpochPlan
import dicechess.play.rating.migration.RatingEpochPlan.*
import dicechess.play.server.{Leaderboard, LeaderboardRoutes}
import doobie.{ConnectionIO, Transactor}
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
import java.net.URI
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

  private def historyOf(xa: Transactor[IO]): IO[List[String]] =
    sql"SELECT to_jsonb(g)::text FROM play.game_results g ORDER BY game_id".query[String].to[List].transact(xa)

  private def snapshotsOf(xa: Transactor[IO]): IO[List[String]] =
    sql"SELECT jsonb_build_object('category',category,'epoch',epoch,'anchorSet',anchor_set_version,'cutoff',cutoff,'payload',payload)::text FROM play.bot_strength_snapshots ORDER BY category,epoch"
      .query[String]
      .to[List]
      .transact(xa)

  private def projectionsOf(xa: Transactor[IO]): IO[String] =
    sql"""SELECT jsonb_build_object(
           'bots',COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY team,name,category) FROM play.bot_ratings r),'[]'::jsonb),
           'users',COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY user_id,category) FROM play.user_ratings r),'[]'::jsonb),
           'training',COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY user_id,category) FROM play.user_training_ratings r),'[]'::jsonb),
           'counts',COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY participant_external_id,category) FROM play.rating_epoch_counts r),'[]'::jsonb),
           'lifetime',COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY participant_external_id,category) FROM play.leaderboard_lifetime_counts r),'[]'::jsonb))::text"""
      .query[String]
      .unique
      .transact(xa)

  private def assertProjectionCollectionsPopulated(value: String): Unit =
    val json = parse(value).toOption.get
    List("bots", "users", "training", "counts", "lifetime").foreach { collection =>
      assert(json.hcursor.get[List[Json]](collection).toOption.exists(_.nonEmpty), s"$collection projection is empty")
    }

  private def publicationFailure(db: PgGameStore): Resource[IO, Unit] =
    val install = for
      _ <- doobie.Fragment
        .const("""CREATE FUNCTION play.synthetic_epoch_failure() RETURNS trigger LANGUAGE plpgsql AS
                 $$ BEGIN RAISE EXCEPTION 'synthetic interruption'; END $$""")
        .update
        .run
      _ <- sql"""CREATE TRIGGER synthetic_epoch_failure BEFORE INSERT ON play.bot_strength_snapshots
                 FOR EACH ROW EXECUTE FUNCTION play.synthetic_epoch_failure()""".update.run
    yield ()
    val remove = for
      _ <- sql"DROP TRIGGER synthetic_epoch_failure ON play.bot_strength_snapshots".update.run
      _ <- sql"DROP FUNCTION play.synthetic_epoch_failure()".update.run
    yield ()
    Resource.make(install.transact(db.xa))(_ => remove.transact(db.xa))

  private def fixture[A](action: (PgGameStore, PgRatingEpochMigration) => IO[A]): IO[A] =
    fixtureWithPostgres((_, db, tool) => action(db, tool))

  private def fixtureWithPostgres[A](
      action: (PostgreSQLContainer, PgGameStore, PgRatingEpochMigration) => IO[A]
  ): IO[A] = withContainers { pg =>
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
      admin(db)(seed) *> action(pg, db, PgRatingEpochMigration.fromStore(db))
    }
  }

  private def postgresCommand(pg: PostgreSQLContainer, arguments: String*): IO[Unit] =
    IO.blocking(pg.container.execInContainer(arguments*)).flatMap { result =>
      if result.getExitCode == 0 then IO.unit
      else
        val utility = arguments.headOption.getOrElse("unknown utility")
        IO.raiseError(
          new RuntimeException(
            s"PostgreSQL utility $utility failed with exit code ${result.getExitCode}: ${result.getStderr}"
          )
        )
    }

  private def isolatedDatabase(pg: PostgreSQLContainer)(action: String => IO[Unit]): IO[Unit] =
    val suffix = UUID.randomUUID().toString.replace("-", "")
    val target = s"epoch_restore_$suffix"
    val dump   = s"/tmp/epoch_restore_$suffix.dump"
    val uri    = URI.create(pg.jdbcUrl.stripPrefix("jdbc:"))
    val source = uri.getPath.stripPrefix("/")
    Resource
      .make(postgresCommand(pg, "createdb", "--username", pg.username, target)) { _ =>
        postgresCommand(pg, "dropdb", "--if-exists", "--username", pg.username, target).attempt.flatMap { dropped =>
          postgresCommand(pg, "rm", "-f", dump).attempt.flatMap(removed => (dropped *> removed).liftTo[IO])
        }
      }
      .use { _ =>
        val targetUrl =
          s"jdbc:${new URI(uri.getScheme, uri.getUserInfo, uri.getHost, uri.getPort, s"/$target", uri.getQuery, uri.getFragment)}"
        postgresCommand(pg, "pg_dump", "--format=custom", s"--file=$dump", "--username", pg.username, source) *>
          postgresCommand(pg, "pg_restore", "--exit-on-error", s"--dbname=$target", "--username", pg.username, dump) *>
          action(targetUrl)
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

  test("a fresh executor acknowledges an applied activation without changing state") {
    fixture { (db, tool) =>
      for
        (paused, run) <- staged(tool)
        applied       <- tool.activate(run.runId, paused.revision, run.planSha256)
        beforeProbe   <- tool.probe
        beforeStatus  <- tool.status(run.runId)
        reopened = PgRatingEpochMigration.fromStore(db)
        acknowledged <- reopened.activate(run.runId, paused.revision, run.planSha256)
        afterProbe   <- reopened.probe
        afterStatus  <- reopened.status(run.runId)
      yield
        assertEquals(applied.phase, "applied")
        assertEquals(acknowledged, beforeStatus)
        assertEquals(afterStatus, beforeStatus)
        assertEquals(afterProbe, beforeProbe)
    }
  }

  test("a database dump restores an applied paused epoch for a fresh executor and guarded rollback") {
    fixtureWithPostgres { (pg, db, tool) =>
      val competitiveUser = UUID.randomUUID()
      val trainingUser    = UUID.randomUUID()
      val competitiveGame = game.copy(
        gameId = "fixture-human-competitive",
        finishedAt = cutoff.minusSeconds(2),
        origin = GameOrigin.Legacy,
        ladder = false,
        white = SourceSeat(s"user:$competitiveUser", ParticipantKind.Human, Some(IdentityContinuity(evidence))),
        black = SourceSeat(s"user:$trainingUser", ParticipantKind.Human, Some(IdentityContinuity(evidence))),
        classification = Some(ClassificationEvidence(2, true, true, RatingDomain.Competitive, evidence))
      )
      val trainingGame = game.copy(
        gameId = "fixture-human-training",
        finishedAt = cutoff.minusSeconds(3),
        origin = GameOrigin.Legacy,
        ladder = false,
        white = SourceSeat(s"user:$trainingUser", ParticipantKind.Human, Some(IdentityContinuity(evidence))),
        classification = Some(ClassificationEvidence(2, true, false, RatingDomain.Training, evidence)),
        reference = CapturedTrainingReference.Recorded(
          TrainingReference(TrainingReferenceSource.Anchor("synthetic-anchors", 1), Glicko(1550, 60, 0.06))
        )
      )
      val plan = RatingEpochPlan.build(config, Iterator(trainingGame, competitiveGame, game)).toOption.get
      val seedSourceProjections = admin(db) {
        sql"""INSERT INTO play.users(id,nickname) VALUES
               ($competitiveUser,'epoch-migration-competitive'),($trainingUser,'epoch-migration-training')""".update.run *>
          sql"""INSERT INTO play.user_ratings(user_id,category,rating,rd,vol)
                 VALUES ($competitiveUser,'blitz',1234,91,0.08)""".update.run *>
          sql"""INSERT INTO play.user_training_ratings
                 (user_id,category,rating,rd,vol,games,wins,draws,losses,updated_at,computed_at)
                 VALUES ($trainingUser,'blitz',1321,88,0.07,3,2,1,0,$cutoff,$cutoff)""".update.run *>
          sql"""INSERT INTO play.rating_epoch_counts
                 (participant_external_id,category,games,wins,draws,losses)
                 VALUES (${s"user:$competitiveUser"},'blitz',4,2,0,2)""".update.run
      }
      for
        _                 <- seedSourceProjections
        sourceState       <- projectionsOf(db.xa)
        _                 <- IO(assertProjectionCollectionsPopulated(sourceState))
        (paused, run)     <- staged(tool, plan)
        applied           <- tool.activate(run.runId, paused.revision, run.planSha256)
        active            <- tool.probe
        activeProjections <- projectionsOf(db.xa)
        expectedHistory   <- historyOf(db.xa)
        expectedSnapshots <- snapshotsOf(db.xa)
        _                 <- IO(assertProjectionCollectionsPopulated(activeProjections))
        _                 <- IO {
          val source = parse(sourceState).toOption.get
          val active = parse(activeProjections).toOption.get
          List("bots", "users", "training", "counts").foreach { collection =>
            assertNotEquals(
              active.hcursor.downField(collection).focus,
              source.hcursor.downField(collection).focus,
              s"$collection projection did not change during activation"
            )
          }
        }
        _ <- isolatedDatabase(pg) { targetUrl =>
          PgGameStore.resource(PgGameStore.Config(targetUrl, pg.username, pg.password)).use { restoredDb =>
            val reopened = PgRatingEpochMigration.fromStore(restoredDb)
            for
              restoredStatus            <- reopened.status(run.runId)
              restoredProbe             <- reopened.probe
              restoredActiveProjections <- projectionsOf(restoredDb.xa)
              restoredHistory           <- historyOf(restoredDb.xa)
              restoredSnapshots         <- snapshotsOf(restoredDb.xa)
              _                         <- IO(assertEquals(restoredActiveProjections, activeProjections))
              rolled                    <- reopened.rollback(run.runId, restoredProbe.revision)
              after                     <- reopened.probe
              restoredSourceProjections <- projectionsOf(restoredDb.xa)
              afterHistory              <- historyOf(restoredDb.xa)
              afterSnapshots            <- snapshotsOf(restoredDb.xa)
              sourceCurrent             <- sql"SELECT is_current FROM play.bot_strength_snapshots WHERE epoch=1"
                .query[Boolean]
                .unique
                .transact(restoredDb.xa)
              targetFlags <- sql"SELECT is_current FROM play.bot_strength_snapshots WHERE epoch=42 ORDER BY category"
                .query[Boolean]
                .to[List]
                .transact(restoredDb.xa)
            yield
              assertEquals(applied.phase, "applied")
              assert(active.paused)
              assertEquals(restoredStatus, applied)
              assertEquals(restoredProbe, active)
              assertEquals(restoredHistory, expectedHistory)
              assertEquals(restoredSnapshots, expectedSnapshots)
              assertEquals(rolled.phase, "rolled_back")
              assertEquals(after.activeEpoch, None)
              assert(after.paused)
              assertEquals(restoredSourceProjections, sourceState)
              assertEquals(afterHistory, expectedHistory)
              assertEquals(afterSnapshots, expectedSnapshots)
              assert(sourceCurrent)
              assertEquals(targetFlags, List(false, false, false))
          }
        }
      yield ()
    }
  }

  test("reset activation and exact rollback preserve recorded history and source snapshot") {
    fixture { (db, tool) =>
      val history = historyOf(db.xa)
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
        _             <- publicationFailure(db).use { _ =>
          for
            failed <- tool.activate(run.runId, paused.revision, run.planSha256).attempt
            after  <- tool.probe
            stage  <- tool.status(run.runId)
          yield
            assert(failed.isLeft)
            assertEquals(after, paused)
            assertEquals(stage.phase, "staged")
            assertEquals(stage.beforeStateSha256, None)
        }
        applied <- tool.activate(run.runId, paused.revision, run.planSha256)
      yield assertEquals(applied.phase, "applied")
    }
  }

  test("publication failure trigger is removed when its test body fails") {
    fixture { (db, _) =>
      val bodyFailure = new RuntimeException("synthetic test body failure")
      val insert      = admin(db) {
        sql"""INSERT INTO play.bot_strength_snapshots(category,epoch,anchor_set_version,cutoff,is_current,payload)
                 VALUES ('blitz',99,'cleanup-synthetic',$cutoff,false,'{"leaders":[]}'::jsonb)""".update.run
      }
      for
        failed <- publicationFailure(db).use { _ =>
          insert.attempt.flatMap { interrupted =>
            IO(assert(interrupted.isLeft)) *> IO.raiseError[Unit](bodyFailure)
          }
        }.attempt
        inserted <- insert
      yield
        assertEquals(failed, Left(bodyFailure))
        assertEquals(inserted, 1)
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

  test("plan validation returns typed refusals for malformed bot bindings and projection identities") {
    fixture { (db, tool) =>
      val identity = game.white.externalId
      val invalid  = Vector(
        rebuilt.copy(identityIncarnations = rebuilt.identityIncarnations.updated(identity, "not-a-uuid")),
        rebuilt.copy(identityIncarnations = rebuilt.identityIncarnations - identity + ("invalid-bot" -> white)),
        rebuilt.copy(rows =
          rebuilt.rows.map(row => if row.externalId == identity then row.copy(externalId = "invalid-bot") else row)
        )
      )
      for
        initial <- tool.probe
        paused  <- tool.setPaused(initial.revision, true)
        refused <- invalid.traverse { plan =>
          tool
            .stage(
              plan.copy(config = plan.config.copy(databaseSourceSha256 = Some(paused.sourceSha256))),
              paused.revision,
              paused.sourceSha256
            )
            .attempt
        }
        after <- tool.probe
        runs  <- sql"SELECT count(*) FROM play.rating_epoch_runs".query[Long].unique.transact(db.xa)
      yield
        assert(refused.forall { case Left(_: PgRatingEpochMigration.Refusal) => true; case _ => false })
        assertEquals(after, paused)
        assertEquals(runs, 0L)
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
