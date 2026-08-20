package dicechess.play.store

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*

import java.time.Instant
import scala.concurrent.duration.*

/** Periodic retention pass (#179): the operational tables stop growing forever once `game_archive` (#177) is the
  * durable history record and `GET /games/{id}/history` (#178) serves replay from it.
  *
  * What it removes, and why each is dead weight rather than history:
  *   - **ended `games` snapshots** — nothing reads them after a game ends (boot resume loads `WHERE status='active'`,
  *     and no HTTP path touches an ended snapshot). They are also the only place per-seat join tokens persist past a
  *     game, so keeping them indefinitely is a small standing liability, not just bytes.
  *   - **delivered `outbox` rows** — `markDelivered` only stamps `delivered_at`; the row has done its job.
  *   - **delivered `client_reports` rows (#212)** — same rule: a browser report exists only to be forwarded, and its
  *     durable home is analytics, not this queue.
  *
  * Never touched: `game_archive` (permanent by contract), `game_results` (the list/rating projection), `bots`,
  * `bot_webhooks`, anything still active, and any snapshot whose history is not safely in the archive (see
  * `PgGameStore.pruneOnce`).
  *
  * '''Opt-in, like every other scheduled feature here.''' `RETENTION_INTERVAL_SECONDS` is the on/off switch — absent
  * means this never runs, matching `LADDER_INTERVAL_SECONDS`/`RATING_INTERVAL_SECONDS`. That idiom matters more for
  * this task than the others: it is the only one that deletes, so it must be impossible to enable by accident.
  */
final class Retention(store: RetentionStore, config: Retention.Config):

  /** One pass: keep pruning bounded batches until a batch removes nothing, then log a single summary line. Paging
    * rather than one statement keeps each transaction short (see `PgGameStore.pruneOnce`) while still draining a
    * backlog — after downtime, or on the first run against an unpruned table — in one tick.
    */
  def tick: IO[Unit] =
    IO.realTimeInstant.flatMap { now =>
      val cutoff = now.minusSeconds(config.retentionDays.toLong * 24 * 60 * 60)
      drain(cutoff, outbox = 0, snapshots = 0, reports = 0).flatMap { (outbox, snapshots, reports, retained) =>
        val retainedNote =
          if retained > 0 then s"; retained $retained unarchived snapshot(s) — history exists nowhere else" else ""
        IO.println(
          s"[play][retention] cutoff $cutoff: pruned $outbox outbox row(s), $snapshots ended snapshot(s), " +
            s"$reports client report(s)$retainedNote"
        ).whenA(outbox > 0 || snapshots > 0 || reports > 0 || retained > 0)
      }
    }

  private def drain(cutoff: Instant, outbox: Int, snapshots: Int, reports: Int): IO[(Int, Int, Int, Int)] =
    store.pruneOnce(cutoff, config.batchSize).flatMap { sweep =>
      if sweep.removedAnything then
        drain(
          cutoff,
          outbox + sweep.outboxDeleted,
          snapshots + sweep.snapshotsDeleted,
          reports + sweep.clientReportsDeleted
        )
      else
        IO.pure(
          (
            outbox + sweep.outboxDeleted,
            snapshots + sweep.snapshotsDeleted,
            reports + sweep.clientReportsDeleted,
            sweep.retainedUnarchived
          )
        )
    }

  /** Background loop; start once at boot. A failure is logged and the loop lives on to retry next interval — same
    * stance as `RatingBatch.scheduler`: a transient database problem must not silently end retention for the lifetime
    * of the process.
    */
  def scheduler(interval: FiniteDuration = config.interval): IO[Unit] =
    (IO.sleep(interval) *> tick.handleErrorWith(error =>
      Console[IO].errorln(s"[play][retention] tick failed, retrying next interval: $error")
    )).foreverM

object Retention:

  /** `interval` between passes; `retentionDays` is how long an ended game's operational rows are kept before they count
    * as dead weight; `batchSize` bounds one delete transaction.
    */
  final case class Config(interval: FiniteDuration, retentionDays: Int, batchSize: Int)

  object Config:
    val DefaultRetentionDays: Int = 30
    val DefaultBatchSize: Int     = 1000

    /** Parse from explicit optional raw values (the same split and strictly-positive validation as
      * `RatingBatch.Config.fromValues`): an absent or unparseable interval disables retention entirely — it is the
      * on/off switch — while `retentionDays`/`batchSize` are tuning knobs that fall back to their defaults.
      *
      * `retentionDays` is deliberately NOT allowed to be zero even if asked: a cutoff of "now" would prune a game the
      * moment it ends, which is indistinguishable from a bug and destroys the operational record while an operator may
      * still need it. A non-positive value falls back to the default rather than being honoured.
      */
    def fromValues(
        intervalSecondsRaw: Option[String],
        retentionDaysRaw: Option[String],
        batchSizeRaw: Option[String]
    ): Option[Config] =
      intervalSecondsRaw
        .flatMap(_.toIntOption)
        .filter(_ > 0)
        .map { seconds =>
          Config(
            interval = seconds.seconds,
            retentionDays = retentionDaysRaw.flatMap(_.toIntOption).filter(_ > 0).getOrElse(DefaultRetentionDays),
            batchSize = batchSizeRaw.flatMap(_.toIntOption).filter(_ > 0).getOrElse(DefaultBatchSize)
          )
        }

  def configFromEnv: Option[Config] =
    Config.fromValues(
      sys.env.get("RETENTION_INTERVAL_SECONDS"),
      sys.env.get("RETENTION_DAYS"),
      sys.env.get("RETENTION_BATCH_SIZE")
    )
