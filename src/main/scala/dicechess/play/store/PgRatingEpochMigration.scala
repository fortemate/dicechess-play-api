package dicechess.play.store

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{Principal, RatingCategory, RatingDomain}
import dicechess.play.rating.Glicko2
import dicechess.play.rating.migration.RatingEpochPlan
import dicechess.play.server.{LeaderRow, LeaderboardRoutes}
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.{Encoder, Json, Printer}
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID

/** Offline maintenance only. All projection changes, backup, publication and pointer movement share one transaction.
  * Operators must drain admissions, games and every writer first; the database fence is an additional consistency
  * check. This class neither opens connections nor runs schema migrations.
  */
final class PgRatingEpochMigration private (xa: Transactor[IO]):
  import PgRatingEpochMigration.*

  private def requireState(condition: Boolean, message: String): ConnectionIO[Unit] =
    Either.cond(condition, (), Refusal(message)).liftTo[ConnectionIO]

  // A stable table lock also covers identity lifecycle changes and legacy writers which do not use our CAS protocol.
  private val sessionFormat: ConnectionIO[Unit] =
    sql"SET LOCAL TIME ZONE 'UTC'".update.run.void *>
      sql"SET LOCAL DateStyle = 'ISO, YMD'".update.run.void

  private val lock: ConnectionIO[Unit] = control.void *>
    sql"""LOCK TABLE play.bots, play.users, play.game_results, play.bot_ratings, play.user_ratings,
           play.user_training_ratings, play.rating_epoch_counts, play.bot_strength_snapshots
           IN SHARE ROW EXCLUSIVE MODE""".update.run.void *>
    sessionFormat *>
    sql"SELECT set_config('play.rating_epoch_admin', 'on', true)".query[String].unique.void

  private def control: ConnectionIO[Control] =
    sql"""SELECT revision, paused, active_epoch, active_run_id
           FROM play.rating_epoch_control WHERE singleton FOR UPDATE"""
      .query[(Long, Boolean, Option[Int], Option[UUID])]
      .unique
      .map(Control.apply.tupled)

  private val fingerprintQueries: List[Fragment] = List(
    fr"SELECT jsonb_build_array('bot',team,name,incarnation_id,ownership_generation,owner_external_id,on_ladder)::text FROM play.bots ORDER BY team,name",
    fr"SELECT jsonb_build_array('user',id,created_at,is_active)::text FROM play.users ORDER BY id",
    // No seed, credential, or event payload is exported. The source facts and recorded movements are hashed in place.
    fr"SELECT (to_jsonb(g) - 'server_seed')::text FROM play.game_results g ORDER BY game_id",
    fr"SELECT to_jsonb(r)::text FROM play.bot_ratings r ORDER BY team,name,category",
    fr"SELECT to_jsonb(r)::text FROM play.user_ratings r ORDER BY user_id,category",
    fr"SELECT to_jsonb(r)::text FROM play.user_training_ratings r ORDER BY user_id,category",
    fr"SELECT to_jsonb(r)::text FROM play.rating_epoch_counts r ORDER BY participant_external_id,category",
    fr"SELECT to_jsonb(r)::text FROM play.bot_strength_snapshots r ORDER BY id"
  )

  private def sourceFingerprint: ConnectionIO[String] =
    doobie.free.connection.delay(MessageDigest.getInstance("SHA-256")).flatMap { digest =>
      fingerprintQueries.zipWithIndex
        .traverse_ { (query, index) =>
          query
            .query[String]
            .stream
            .evalMap { row =>
              doobie.free.connection.delay {
                digest.update(s"$index:${row.length}:".getBytes(UTF_8))
                digest.update(row.getBytes(UTF_8))
              }
            }
            .compile
            .drain
        }
        .as(digest)
        .map(value => hex(value.digest()))
    }

  private def probeSql: ConnectionIO[Probe] =
    (control, sourceFingerprint).mapN((c, hash) => Probe(c.revision, c.paused, c.epoch, c.run, hash))

  /** A consistent fingerprint of source facts, identities, projections and existing publications. */
  def probe: IO[Probe] =
    (sql"SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY".update.run *>
      sessionFormat *> (for
        c <- sql"SELECT revision,paused,active_epoch,active_run_id FROM play.rating_epoch_control WHERE singleton"
          .query[(Long, Boolean, Option[Int], Option[UUID])]
          .unique
        hash <- sourceFingerprint
      yield Probe(c._1, c._2, c._3, c._4, hash))).transact(xa)

  def setPaused(expectedRevision: Long, paused: Boolean): IO[Probe] =
    (lock *> (for
      c <- control
      _ <- requireState(c.revision == expectedRevision, "rating epoch revision changed")
      _ <- sql"""UPDATE play.rating_epoch_control
                 SET paused = $paused, revision = revision + 1, changed_at = clock_timestamp()
                 WHERE singleton AND paused <> $paused""".update.run
      result <- probeSql
    yield result)).transact(xa)

  private def statusSql(id: UUID): ConnectionIO[RunStatus] =
    sql"""SELECT run_id, phase, plan_sha256, target_epoch, source_revision, applied_revision,
                  before_state_sha256, after_state_sha256
           FROM play.rating_epoch_runs WHERE run_id = $id"""
      .query[(UUID, String, String, Int, Long, Option[Long], Option[String], Option[String])]
      .unique
      .map(RunStatus.apply.tupled)

  def status(runId: UUID): IO[RunStatus] = statusSql(runId).transact(xa)

  def stage(plan: RatingEpochPlan.Plan, expectedRevision: Long, expectedSourceSha256: String): IO[RunStatus] =
    val hash  = RatingEpochPlan.planDigest(plan)
    val id    = UUID.nameUUIDFromBytes(s"rating-epoch:$hash".getBytes(UTF_8))
    val valid = RatingEpochPlan.validate(plan).leftMap(new IllegalArgumentException(_)).liftTo[ConnectionIO]
    val tx    = for
      _ <- lock
      _ <- valid
      _ <- requireState(plan.activatable, "plan contains unknown rows or no eligible games")
      _ <- requireState(
        plan.config.databaseSourceSha256.contains(expectedSourceSha256),
        "plan must bind the exact database source fingerprint from its accepted probe"
      )
      existing <- sql"SELECT run_id FROM play.rating_epoch_runs WHERE plan_sha256 = $hash".query[UUID].option
      result   <- existing match
        case Some(found) => statusSql(found)
        case None        =>
          for
            c      <- control
            _      <- requireState(c.paused && c.revision == expectedRevision, "pause and re-probe before staging")
            source <- sourceFingerprint
            _      <- requireState(source == expectedSourceSha256, "source state changed before staging")
            _      <- checkTarget(plan)
            _      <- checkIdentities(plan)
            _      <- sql"""INSERT INTO play.rating_epoch_runs
                       (run_id, plan_sha256, phase, source_revision, source_state_sha256, target_epoch,
                        cutoff, anchor_set_version, plan)
                       VALUES ($id, $hash, 'staged', $expectedRevision, $source, ${plan.config.targetEpoch},
                               ${plan.config.cutoff}, ${plan.config.anchorSetVersion}, ${plan.asJson})""".update.run
            status <- statusSql(id)
          yield status
    yield result
    tx.transact(xa)

  private def checkTarget(plan: RatingEpochPlan.Plan): ConnectionIO[Unit] =
    for
      highest <- sql"""SELECT COALESCE(MAX(epoch),0) FROM (
                       SELECT epoch FROM play.bot_strength_snapshots
                       UNION ALL SELECT target_epoch FROM play.rating_epoch_runs WHERE phase <> 'staged'
                       UNION ALL SELECT active_epoch FROM play.rating_epoch_control WHERE singleton
                       ) prior""".query[Int].unique
      _ <- requireState(
        plan.config.targetEpoch > highest,
        "target epoch must be newer than all existing publication/history"
      )
    yield ()

  private def checkIdentities(plan: RatingEpochPlan.Plan): ConnectionIO[Unit] =
    plan.identityIncarnations.toList.traverse_ { (externalId, incarnation) =>
      val bot =
        Principal.fromBotExternalId(externalId).getOrElse(throw new IllegalArgumentException("invalid bot identity"))
      sql"SELECT incarnation_id FROM play.bots WHERE team = ${bot.team} AND name = ${bot.name}"
        .query[UUID]
        .option
        .flatMap(found =>
          requireState(
            found.contains(UUID.fromString(incarnation)),
            "historical bot incarnation is not the current identity"
          )
        )
    } *> plan.rows.flatMap(row => Principal.fromUserExternalId(row.externalId)).distinct.toList.traverse_ { user =>
      sql"SELECT EXISTS(SELECT 1 FROM play.users WHERE id = $user::uuid AND is_active)"
        .query[Boolean]
        .unique
        .flatMap(found => requireState(found, "projection refers to a missing or inactive account"))
    }

  // JSONB preserves SQL types (including timestamps) for exact restore. A digest is checked before trusting the backup.
  private def backup: ConnectionIO[Json] =
    sql"""SELECT jsonb_build_object(
      'bots', COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY team,name,category) FROM play.bot_ratings r),'[]'::jsonb),
      'users', COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY user_id,category) FROM play.user_ratings r),'[]'::jsonb),
      'training', COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY user_id,category) FROM play.user_training_ratings r),'[]'::jsonb),
      'counts', COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY participant_external_id,category) FROM play.rating_epoch_counts r),'[]'::jsonb),
      'activeEpoch', (SELECT to_jsonb(active_epoch) FROM play.rating_epoch_control WHERE singleton),
      'activeRun', (SELECT to_jsonb(active_run_id) FROM play.rating_epoch_control WHERE singleton))"""
      .query[Json]
      .unique

  private def clear: ConnectionIO[Unit] =
    sql"DELETE FROM play.bot_ratings".update.run.void *>
      sql"DELETE FROM play.user_ratings".update.run.void *>
      sql"DELETE FROM play.user_training_ratings".update.run.void *>
      sql"DELETE FROM play.rating_epoch_counts".update.run.void

  private def install(plan: RatingEpochPlan.Plan): ConnectionIO[Unit] =
    clear *> plan.rows.traverse_ { row =>
      val g          = row.glicko
      val category   = row.category.wireName
      val projection = Principal.fromUserExternalId(row.externalId) match
        case Some(user) if row.domain == RatingDomain.Training =>
          sql"""INSERT INTO play.user_training_ratings
                 (user_id,category,rating,rd,vol,games,wins,draws,losses,updated_at,computed_at)
                 VALUES ($user::uuid,$category,${g.rating},${g.deviation},${g.volatility},${row.games},
                         ${row.wins},${row.draws},${row.losses},${row.lastPlayedAt},clock_timestamp())""".update.run.void
        case Some(user) =>
          sql"""INSERT INTO play.user_ratings (user_id,category,rating,rd,vol)
                 VALUES ($user::uuid,$category,${g.rating},${g.deviation},${g.volatility})""".update.run.void
        case None =>
          val bot = Principal
            .fromBotExternalId(row.externalId)
            .getOrElse(throw new IllegalArgumentException("invalid bot projection"))
          sql"""INSERT INTO play.bot_ratings (team,name,category,rating,rd,vol)
                 VALUES (${bot.team},${bot.name},$category,${g.rating},${g.deviation},${g.volatility})""".update.run.void
      projection *> (if row.domain == RatingDomain.Competitive then
                       sql"""INSERT INTO play.rating_epoch_counts (participant_external_id,category,games,wins,draws,losses)
               VALUES (${row.externalId},$category,${row.games.toLong},${row.wins.toLong},${row.draws.toLong},${row.losses.toLong})""".update.run.void
                     else ().pure[ConnectionIO])
    }

  private def publish(plan: RatingEpochPlan.Plan): ConnectionIO[Unit] =
    RatingCategory.values.toList.traverse_ { category =>
      for
        entries <- sql"""SELECT b.team,b.name,r.rating,r.rd,b.on_ladder,c.wins,c.draws,c.losses
                         FROM play.bot_ratings r JOIN play.bots b USING(team,name)
                         JOIN play.rating_epoch_counts c ON c.participant_external_id = 'bot:team:' || b.team || ':' || b.name
                           AND c.category = r.category
                         WHERE r.category = ${category.wireName} AND r.rd <= ${Glicko2.ProvisionalDeviationThreshold}
                         ORDER BY r.rating - ${Glicko2.ConservativeOrderingK} * r.rd DESC,r.rd,b.team,b.name
                         LIMIT ${LeaderboardStore.MaxBoardSize}"""
          .query[(String, String, Double, Double, Boolean, Int, Int, Int)]
          .to[List]
        rows: List[LeaderRow] = entries.zipWithIndex.map {
          case ((team, name, rating, rd, onLadder, wins, draws, losses), index) =>
            LeaderboardRoutes
              .botRow(
                LeaderboardEntry(team, name, rating, rd, onLadder, ResultTally(wins, draws, losses)),
                Some(plan.config.targetEpoch)
              )
              .copy(rank = index + 1)
        }
        payload = Json.obj("leaders" -> rows.asJson)
        _ <- sql"""INSERT INTO play.bot_strength_snapshots
                   (category,epoch,anchor_set_version,cutoff,is_current,payload)
                   VALUES (${category.wireName},${plan.config.targetEpoch},${plan.config.anchorSetVersion},
                           ${plan.config.cutoff},true,$payload)""".update.run
      yield ()
    }

  def activate(runId: UUID, expectedRevision: Long, expectedPlanSha256: String): IO[RunStatus] =
    val tx = for
      _      <- lock
      status <- statusSql(runId)
      _      <- requireState(status.planSha256 == expectedPlanSha256, "plan hash changed")
      result <-
        if status.phase == "applied" then status.pure[ConnectionIO]
        else
          for
            _ <- requireState(status.phase == "staged", "only a staged run can be applied")
            c <- control
            _ <- requireState(
              c.paused && c.revision == expectedRevision && c.revision == status.sourceRevision,
              "source revision changed; re-plan from a fresh probe"
            )
            stored <- sql"SELECT plan,source_state_sha256 FROM play.rating_epoch_runs WHERE run_id = $runId"
              .query[(Json, String)]
              .unique
            plan <- stored._1.as[RatingEpochPlan.Plan].leftMap(new IllegalStateException(_)).liftTo[ConnectionIO]
            _    <- RatingEpochPlan.validate(plan).leftMap(new IllegalStateException(_)).liftTo[ConnectionIO]
            _    <- requireState(
              plan.activatable && RatingEpochPlan.planDigest(plan) == expectedPlanSha256,
              "stored plan is invalid"
            )
            source <- sourceFingerprint
            _ <- requireState(source == stored._2, "source facts, identities or publication changed; re-plan required")
            _ <- checkTarget(plan)
            _ <- checkIdentities(plan)
            before <- backup
            _      <- install(plan)
            // The post-state digest includes the new pointer, not its predecessor.
            _ <-
              sql"""UPDATE play.rating_epoch_control SET active_epoch = ${plan.config.targetEpoch},active_run_id = $runId,
                     revision = revision + 1,changed_at = clock_timestamp() WHERE singleton""".update.run
            _           <- publish(plan)
            complete    <- backup
            fingerprint <- sourceFingerprint
            _           <- sql"""UPDATE play.rating_epoch_runs SET phase = 'applied',before_state = $before,
                     before_state_sha256 = ${jsonDigest(before)},after_state_sha256 = ${postDigest(
                complete,
                fingerprint
              )},
                     applied_revision = ${c.revision + 1},applied_at = clock_timestamp() WHERE run_id = $runId""".update.run
            applied <- statusSql(runId)
          yield applied
    yield result
    tx.transact(xa)

  def rollback(runId: UUID, expectedRevision: Long): IO[RunStatus] =
    val tx = for
      _      <- lock
      status <- statusSql(runId)
      result <-
        if status.phase == "rolled_back" then status.pure[ConnectionIO]
        else
          for
            _ <- requireState(status.phase == "applied", "only an applied run can be rolled back")
            c <- control
            _ <- requireState(
              c.paused && c.run.contains(runId) && c.revision == expectedRevision && status.appliedRevision
                .contains(c.revision),
              "post-cutover state advanced; exact rollback would discard changes"
            )
            current     <- backup
            fingerprint <- sourceFingerprint
            _           <- requireState(
              status.afterStateSha256.contains(postDigest(current, fingerprint)),
              "source or active projection changed; exact rollback refused"
            )
            saved <- sql"SELECT before_state FROM play.rating_epoch_runs WHERE run_id = $runId".query[Json].unique
            _     <- requireState(status.beforeStateSha256.contains(jsonDigest(saved)), "backup digest mismatch")
            _     <- clear
            _     <-
              sql"INSERT INTO play.bot_ratings SELECT * FROM jsonb_populate_recordset(NULL::play.bot_ratings, $saved -> 'bots')".update.run
            _ <-
              sql"INSERT INTO play.user_ratings SELECT * FROM jsonb_populate_recordset(NULL::play.user_ratings, $saved -> 'users')".update.run
            _ <-
              sql"INSERT INTO play.user_training_ratings SELECT * FROM jsonb_populate_recordset(NULL::play.user_training_ratings, $saved -> 'training')".update.run
            _ <-
              sql"INSERT INTO play.rating_epoch_counts SELECT * FROM jsonb_populate_recordset(NULL::play.rating_epoch_counts, $saved -> 'counts')".update.run
            _ <- sql"""UPDATE play.rating_epoch_control SET active_epoch = ($saved ->> 'activeEpoch')::integer,
                     active_run_id = ($saved ->> 'activeRun')::uuid,revision = revision + 1,changed_at = clock_timestamp()
                     WHERE singleton""".update.run
            restored <- backup
            _ <- requireState(jsonDigest(restored) == jsonDigest(saved), "restored projection differs from backup")
            _ <-
              sql"UPDATE play.bot_strength_snapshots SET is_current = false WHERE epoch = ${status.targetEpoch}".update.run
            _ <-
              sql"UPDATE play.rating_epoch_runs SET phase = 'rolled_back',rolled_back_at = clock_timestamp() WHERE run_id = $runId".update.run
            rolledBack <- statusSql(runId)
          yield rolledBack
    yield result
    tx.transact(xa)

object PgRatingEpochMigration:
  /** Contains only fixed operator-safe diagnostics, never SQL, source identities or connection details. */
  final case class Refusal(problem: String) extends RuntimeException(problem)
  final case class Probe(
      revision: Long,
      paused: Boolean,
      activeEpoch: Option[Int],
      activeRunId: Option[UUID],
      sourceSha256: String
  )
  final case class RunStatus(
      runId: UUID,
      phase: String,
      planSha256: String,
      targetEpoch: Int,
      sourceRevision: Long,
      appliedRevision: Option[Long],
      beforeStateSha256: Option[String],
      afterStateSha256: Option[String]
  )
  given Encoder[Probe]     = deriveEncoder
  given Encoder[RunStatus] = deriveEncoder
  final private case class Control(revision: Long, paused: Boolean, epoch: Option[Int], run: Option[UUID])
  def fromStore(db: PgGameStore): PgRatingEpochMigration             = new PgRatingEpochMigration(db.xa)
  def fromTransactor(xa: Transactor[IO]): PgRatingEpochMigration     = new PgRatingEpochMigration(xa)
  private def hex(bytes: Array[Byte]): String                        = bytes.map(b => f"${b & 0xff}%02x").mkString
  private def postDigest(backup: Json, sourceSha256: String): String =
    jsonDigest(Json.obj("backup" -> backup, "sourceSha256" -> sourceSha256.asJson))
  private def jsonDigest(json: Json): String =
    // PostgreSQL JSONB may normalize number notation; normalize it here before comparing round-tripped backups.
    def normalize(value: Json): Json = value.arrayOrObject(
      value.asNumber
        .flatMap(_.toBigDecimal)
        .fold(value)(n => Json.fromBigDecimal(BigDecimal(n.bigDecimal.stripTrailingZeros))),
      values => Json.fromValues(values.map(normalize)),
      fields => Json.fromFields(fields.toVector.map((key, value) => key -> normalize(value)))
    )
    hex(
      MessageDigest
        .getInstance("SHA-256")
        .digest(Printer.noSpaces.copy(sortKeys = true).print(normalize(json)).getBytes(UTF_8))
    )
