package dicechess.play.game

import cats.effect.IO

import scala.concurrent.duration.*

/** How a room treats the snapshot write behind every published version (ADR-005 §7, #47).
  *
  * Every room writes its creation snapshot fail-closed — a room whose seat tokens and dice commitment never reached the
  * store is not returned to its caller. The two modes differ in what happens to every write after that:
  *
  *   - [[BestEffort]] is availability-first, the pre-#47 behaviour and still the rule for every non-showcase game: a
  *     failed write is logged and the game plays on in memory, on the theory that a lost snapshot is cheaper than a
  *     frozen game between two people who can both see the board.
  *   - [[Required]] is fail-closed, the showcase table's rule: no version is acknowledged, broadcast to a spectator, or
  *     handed to a bot webhook until it is committed. A failed write halts the room's writer fiber — forward progress
  *     stops, the mover's clock is credited for the stall — and is retried per its policy. If an intermediate write
  *     exhausts its retries the game is technically aborted FROM THE LAST DURABLE VERSION, never from the unsaved one,
  *     and the terminal write itself is retried until it commits: the room's `result` (and with it the registry's
  *     deregistration, the admission release, and the table reopening) fires only once the archive is on disk.
  */
enum Durability:
  case BestEffort

  /** @param intermediate
    *   retry policy for a live version (a roll, a move, a claim). Bounded: a database that stays down mid-game turns
    *   the game into a technical abort rather than an indefinitely frozen board.
    * @param terminal
    *   retry policy for the ending. Unbounded by default: the terminal transaction is the archive, and the table must
    *   not reopen without it, so the only alternative to "keep trying" is a restart — after which the game resumes from
    *   its last durable version and ends again, durably, by play.
    * @param stalledSubscriberGrace
    *   how long a write may keep failing before the room's subscribers are dropped so their transports close (ADR-005
    *   §11: sessions settle instead of hanging on a writer that cannot publish). The room keeps retrying; a client that
    *   reconnects sees the last durable state.
    * @param telemetry
    *   receives every failure, recovery, abandonment and drop — the operator's view of a stalled table. `GameRegistry`
    *   supplies a logger that names the game; tests capture the events.
    */
  case Required(
      intermediate: RetryPolicy,
      terminal: RetryPolicy,
      stalledSubscriberGrace: FiniteDuration,
      telemetry: PersistenceTelemetry => IO[Unit]
  )

object Durability:

  /** Four attempts about three seconds apart in total, each of which `PgGameStore` already bounds by its own save
    * timeout — long enough to ride out a failover blip, short enough that a board does not sit frozen for a minute.
    */
  val DefaultIntermediateRetry: RetryPolicy =
    RetryPolicy(maxAttempts = Some(4), initialBackoff = 250.millis, maxBackoff = 2.seconds)

  /** Never gives up; backs off to one attempt every thirty seconds. See the `terminal` parameter above. */
  val DefaultTerminalRetry: RetryPolicy =
    RetryPolicy(maxAttempts = None, initialBackoff = 1.second, maxBackoff = 30.seconds)

  val DefaultStalledSubscriberGrace: FiniteDuration = 15.seconds

  /** The production showcase policy: the defaults above with the caller's telemetry sink. */
  def required(telemetry: PersistenceTelemetry => IO[Unit]): Durability.Required =
    Required(DefaultIntermediateRetry, DefaultTerminalRetry, DefaultStalledSubscriberGrace, telemetry)

/** Exponential backoff with a cap and an optional attempt limit. `maxAttempts = None` retries forever. */
final case class RetryPolicy(maxAttempts: Option[Int], initialBackoff: FiniteDuration, maxBackoff: FiniteDuration):
  require(maxAttempts.forall(_ >= 1), "a retry policy must allow at least one attempt")

  /** Whether `failedAttempts` failures in a row have used up the budget. */
  def exhausted(failedAttempts: Int): Boolean = maxAttempts.exists(failedAttempts >= _)

  /** The delay before the attempt that follows `failedAttempts` (≥ 1) failures: doubles from `initialBackoff`, capped.
    */
  def backoff(failedAttempts: Int): FiniteDuration =
    // Capped doublings keep the shift well inside a Long however long an outage lasts.
    val doublings = math.min(math.max(failedAttempts - 1, 0), 20)
    val raw       = initialBackoff * (1L << doublings)
    if raw > maxBackoff then maxBackoff else raw

/** What a fail-closed room reports about its writes — the "actionable telemetry" of ADR-005 §7. `version` is the
  * version being written; `terminal` says whether it is the game's ending (and so under the unbounded policy).
  */
enum PersistenceTelemetry:
  /** One attempt failed. `retryIn = None` means this was the last attempt the policy allowed. */
  case SaveFailed(version: Long, attempt: Int, terminal: Boolean, retryIn: Option[FiniteDuration], error: Throwable)

  /** The write went through after `attempts` tries; the room was stalled for `stalledFor` in the meantime. */
  case SaveRecovered(version: Long, attempts: Int, stalledFor: FiniteDuration)

  /** An intermediate write exhausted its policy: the room now aborts technically from its last durable version. */
  case SaveAbandoned(version: Long, attempts: Int)

  /** The write had been failing for longer than the grace, so every subscriber was released. */
  case SubscribersDropped(version: Long, stalledFor: FiniteDuration)

/** Raised inside the writer fiber when an intermediate required write exhausts its retry policy; the room's supervisor
  * turns it into a technical abort from the last durable version.
  */
final class RequiredSaveAbandoned(val version: Long, val attempts: Int, cause: Throwable)
    extends RuntimeException(s"required snapshot write of version $version abandoned after $attempts attempt(s)", cause)
