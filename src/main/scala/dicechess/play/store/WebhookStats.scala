package dicechess.play.store

import cats.effect.IO

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** The taxonomy of one webhook turn-delivery attempt (#225) — recorded so an author can tell apart the three
  * otherwise-indistinguishable reasons a bot gets few turns: a declared capacity (#189) in genuine use, a broken
  * endpoint, or simply not being picked (a fact this store says nothing about; see [[dicechess.play.server.SeatGuard]]
  * and the ladder scheduler for that). Only `Webhooks.deliverTurn` produces these — the registration handshake and the
  * catalog `wake` probe are different traffic and are never recorded here.
  */
enum DeliveryOutcome:
  case Applied               // 2xx, a non-empty legal move, accepted by the room
  case Declined              // 2xx, an explicit empty `{"moves":[]}` — the bot chose not to move
  case Refused               // 2xx, moves the room rejected (stale/illegal)
  case Garbled               // 2xx, a body that did not decode as `{"moves":[...]}`
  case OversizedBody         // 2xx, body over the response-size cap — rejected unread past the cap
  case HttpStatus(code: Int) // non-2xx, the status the endpoint actually sent (preserves it — the diagnosable case)
  case TimedOut              // the server's own per-turn deadline expired before any response arrived
  case Unreachable           // any other transport failure: refused connection, DNS, TLS, a URL-policy re-check

object DeliveryOutcome:

  /** The storage/wire key: a stable string, independent of the enum's declaration order — an `Ordinal` would break
    * silently the moment a case is inserted or reordered. `HttpStatus` folds its code into the string so the whole
    * classification stays one NOT NULL column (`bot_webhook_stats.outcome`), not a nullable side column.
    */
  def key(outcome: DeliveryOutcome): String = outcome match
    case Applied          => "applied"
    case Declined         => "declined"
    case Refused          => "refused"
    case Garbled          => "garbled"
    case OversizedBody    => "oversized_body"
    case HttpStatus(code) => s"http_$code"
    case TimedOut         => "timed_out"
    case Unreachable      => "unreachable"

  /** Whether this outcome should overwrite a bot's "last failure" (#225's other half of report-it-back). `Declined` is
    * excluded deliberately: an explicit empty-moves answer is the bot behaving exactly as designed, not a fault — the
    * same reasoning `Webhooks.deliverTurn`'s own log line already applies to it.
    */
  def isFailure(outcome: DeliveryOutcome): Boolean = outcome match
    case Applied | Declined => false
    case _                  => true

  /** The human-facing sentence stored as `bot_webhooks.last_failure_reason` — written once, at record time, so the read
    * side never has to re-derive prose from the terse storage `key`. Mirrors the wording `Webhooks.deliverTurn`'s own
    * log lines already use, so an author sees the same story in `GET /bot/webhook/stats` as an operator would in the
    * server log.
    */
  def describe(outcome: DeliveryOutcome): String = outcome match
    case Applied          => "delivered and applied"
    case Declined         => "the bot declined (empty moves)"
    case Refused          => "the room refused the moves"
    case Garbled          => "the response did not decode as a move"
    case OversizedBody    => "the response exceeded the size cap"
    case HttpStatus(code) => s"the endpoint answered HTTP $code"
    case TimedOut         => "the server's own delivery window expired with no response"
    case Unreachable      => "could not reach the endpoint"

/** Fixed log-spaced latency histogram — approximates percentiles to the bucket's own resolution, which is the point:
  * "is my endpoint fast or slow" needs a shape, not microsecond precision. Bounds are in milliseconds; the last one
  * comfortably covers the public deployment's documented per-turn ceiling (120s docs/reference/webhooks.md) — anything
  * slower still lands in the overflow bucket rather than being silently dropped or mis-bucketed.
  */
object LatencyHistogram:
  val BucketUpperBoundsMs: Vector[Long] =
    Vector(50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 30000, 60000, 120000, 300000)

  /** One past the last real boundary — deliveries slower than every named bound land here. */
  val OverflowBucket: Int = BucketUpperBoundsMs.size

  def bucketOf(elapsed: FiniteDuration): Int =
    val ms = elapsed.toMillis
    BucketUpperBoundsMs.indexWhere(ms <= _) match
      case -1  => OverflowBucket
      case idx => idx

  /** The bucket's own upper bound — what a reported percentile actually means. `None` for the overflow bucket, which
    * has no upper bound to give.
    */
  def upperBoundMs(bucket: Int): Option[Long] = BucketUpperBoundsMs.lift(bucket)

/** One outcome's share of a window. */
final case class OutcomeCount(outcome: String, count: Long)

/** The aggregated answer for one time window (#225): counts by outcome, and percentiles approximated from the latency
  * histogram (bucket-resolution — see [[LatencyHistogram.upperBoundMs]]). `totalDeliveries` and the percentiles cover
  * EVERY recorded attempt, success or not: an author asking "how long do my deliveries take" wants the failed ones
  * counted too, not a survivorship-biased view of only what succeeded.
  */
final case class DeliveryStatsWindow(
    totalDeliveries: Long,
    outcomes: List[OutcomeCount],
    p50Ms: Option[Long],
    p90Ms: Option[Long],
    p99Ms: Option[Long]
)

object DeliveryStatsWindow:
  val empty: DeliveryStatsWindow = DeliveryStatsWindow(0L, Nil, None, None, None)

  /** Pure aggregation over raw histogram rows `(outcome, bucket, count)` — DB-free and unit-testable on its own
    * (`WebhookStatsSuite`). Outcomes are sorted by count descending (the biggest share first, ties broken by name for a
    * deterministic order); percentiles walk the bucket totals ascending and report the upper bound of the first bucket
    * whose cumulative count reaches the target fraction of the total.
    */
  def aggregate(rows: List[(String, Int, Long)]): DeliveryStatsWindow =
    if rows.isEmpty then empty
    else
      val total    = rows.map(_._3).sum
      val outcomes = rows
        .groupMapReduce(_._1)(_._3)(_ + _)
        .toList
        .map(OutcomeCount.apply)
        .sortBy(oc => (-oc.count, oc.outcome))
      val byBucket = rows.groupMapReduce(_._2)(_._3)(_ + _).toList.sortBy(_._1)

      def percentile(p: Double): Option[Long] =
        val target = math.ceil(total * p).toLong
        byBucket
          .scanLeft((-1, 0L)) { case ((_, cumulative), (bucket, count)) => (bucket, cumulative + count) }
          .drop(1) // scanLeft's own seed, not a real bucket
          .find(_._2 >= target)
          .flatMap((bucket, _) => LatencyHistogram.upperBoundMs(bucket))

      DeliveryStatsWindow(total, outcomes, percentile(0.50), percentile(0.90), percentile(0.99))

/** The most recent delivery that was a genuine fault (`DeliveryOutcome.isFailure`), for the author-facing question a
  * histogram alone can't answer: not "how often", but "is it still happening, and since when".
  */
final case class LastFailure(at: Instant, reason: String)

/** `GET /bot/webhook/stats`'s full answer (#225): two windows over the same underlying rows, plus the last failure.
  */
final case class WebhookStats(
    last24h: DeliveryStatsWindow,
    last7d: DeliveryStatsWindow,
    lastFailure: Option[LastFailure]
)

object WebhookStats:
  val empty: WebhookStats = WebhookStats(DeliveryStatsWindow.empty, DeliveryStatsWindow.empty, None)

/** Persistence seam for webhook delivery telemetry (#225). `recordDelivery` is fire-and-forget from every caller's
  * point of view — `Webhooks`'s own drain loop is the only caller, off the turn path entirely — and `statsFor` serves
  * the `GET /bot/webhook/stats` read. Postgres only, like the catalog/leaderboard: the read route is simply not mounted
  * without persistence (see `Main.scala`), so `noop` exists only to give `Webhooks` itself one seam to depend on
  * regardless of mode, not because anything in memory-only mode ever calls it.
  */
trait WebhookStatsStore:
  /** One delivery attempt, folded into its `(hour, outcome, bucket)` histogram cell. `at` is the caller's own
    * observation of when the attempt happened (via `IO.realTime`) — read at the call site, not inside this store, so
    * the store stays a plain aggregation seam and is trivially testable against a fixed instant.
    */
  def recordDelivery(
      team: String,
      name: String,
      outcome: DeliveryOutcome,
      elapsed: FiniteDuration,
      at: Instant
  ): IO[Unit]

  /** The aggregated windows an author reads: last 24h and last 7d over the histogram, plus the most recent genuine
    * failure (`None` if the bot has never had one — no deliveries yet, or every one so far succeeded or was a clean
    * decline).
    */
  def statsFor(team: String, name: String, now: Instant): IO[WebhookStats]

object WebhookStatsStore:
  val noop: WebhookStatsStore = new WebhookStatsStore:
    def recordDelivery(
        team: String,
        name: String,
        outcome: DeliveryOutcome,
        elapsed: FiniteDuration,
        at: Instant
    ): IO[Unit] =
      IO.unit
    def statsFor(team: String, name: String, now: Instant): IO[WebhookStats] = IO.pure(WebhookStats.empty)
