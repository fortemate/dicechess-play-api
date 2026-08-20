package dicechess.play.store

import scala.concurrent.duration.*

/** Pure logic for webhook delivery telemetry (#225): the outcome taxonomy's storage key, the latency histogram's bucket
  * assignment, and the percentile aggregation over raw histogram rows — all DB-free, unlike the upsert/query mechanics
  * covered against real Postgres in `PgGameStoreSuite`.
  */
class WebhookStatsSuite extends munit.FunSuite:

  // ── DeliveryOutcome ───────────────────────────────────────────────────────────

  test("the storage key is stable text, independent of declaration order, and folds the status into HttpStatus"):
    assertEquals(DeliveryOutcome.key(DeliveryOutcome.Applied), "applied")
    assertEquals(DeliveryOutcome.key(DeliveryOutcome.Declined), "declined")
    assertEquals(DeliveryOutcome.key(DeliveryOutcome.Refused), "refused")
    assertEquals(DeliveryOutcome.key(DeliveryOutcome.Garbled), "garbled")
    assertEquals(DeliveryOutcome.key(DeliveryOutcome.OversizedBody), "oversized_body")
    assertEquals(DeliveryOutcome.key(DeliveryOutcome.HttpStatus(503)), "http_503")
    assertEquals(DeliveryOutcome.key(DeliveryOutcome.HttpStatus(429)), "http_429")
    assertEquals(DeliveryOutcome.key(DeliveryOutcome.TimedOut), "timed_out")
    assertEquals(DeliveryOutcome.key(DeliveryOutcome.Unreachable), "unreachable")

  test("only a genuine fault counts as a failure — a clean decline does not overwrite last-failure"):
    assert(!DeliveryOutcome.isFailure(DeliveryOutcome.Applied), "a usable move is not a failure")
    assert(!DeliveryOutcome.isFailure(DeliveryOutcome.Declined), "an explicit decline is the bot behaving as designed")
    assert(DeliveryOutcome.isFailure(DeliveryOutcome.Refused))
    assert(DeliveryOutcome.isFailure(DeliveryOutcome.Garbled))
    assert(DeliveryOutcome.isFailure(DeliveryOutcome.OversizedBody))
    assert(DeliveryOutcome.isFailure(DeliveryOutcome.HttpStatus(500)))
    assert(DeliveryOutcome.isFailure(DeliveryOutcome.TimedOut))
    assert(DeliveryOutcome.isFailure(DeliveryOutcome.Unreachable))

  test("describe gives a human sentence distinct per outcome, including the HTTP status"):
    val described = List(
      DeliveryOutcome.Applied,
      DeliveryOutcome.Declined,
      DeliveryOutcome.Refused,
      DeliveryOutcome.Garbled,
      DeliveryOutcome.OversizedBody,
      DeliveryOutcome.HttpStatus(503),
      DeliveryOutcome.TimedOut,
      DeliveryOutcome.Unreachable
    ).map(DeliveryOutcome.describe)
    assertEquals(described.distinct.size, described.size, s"expected all-distinct sentences, got: $described")
    assert(DeliveryOutcome.describe(DeliveryOutcome.HttpStatus(503)).contains("503"))
    assert(DeliveryOutcome.describe(DeliveryOutcome.HttpStatus(429)).contains("429"))

  // ── LatencyHistogram ──────────────────────────────────────────────────────────

  test("bucketOf assigns the first boundary the elapsed time does not exceed"):
    assertEquals(LatencyHistogram.bucketOf(1.millis), 0)  // <= 50ms
    assertEquals(LatencyHistogram.bucketOf(50.millis), 0) // exactly on a boundary counts as within it
    assertEquals(LatencyHistogram.bucketOf(51.millis), 1)
    assertEquals(LatencyHistogram.bucketOf(299.seconds), LatencyHistogram.BucketUpperBoundsMs.size - 1)

  test("bucketOf overflows past the last named boundary, rather than throwing or wrapping"):
    assertEquals(LatencyHistogram.bucketOf(301.seconds), LatencyHistogram.OverflowBucket)
    assertEquals(LatencyHistogram.bucketOf(1.hour), LatencyHistogram.OverflowBucket)

  test("upperBoundMs is defined for every real bucket and None for the overflow bucket"):
    LatencyHistogram.BucketUpperBoundsMs.indices.foreach { i =>
      assertEquals(LatencyHistogram.upperBoundMs(i), Some(LatencyHistogram.BucketUpperBoundsMs(i)))
    }
    assertEquals(LatencyHistogram.upperBoundMs(LatencyHistogram.OverflowBucket), None)

  // ── DeliveryStatsWindow.aggregate ─────────────────────────────────────────────

  test("aggregating no rows is the empty window, not an error"):
    assertEquals(DeliveryStatsWindow.aggregate(Nil), DeliveryStatsWindow.empty)

  test("outcome counts are summed across buckets and sorted by count descending, ties broken by name"):
    val window = DeliveryStatsWindow.aggregate(
      List(("applied", 0, 5L), ("applied", 1, 3L), ("timed_out", 2, 8L), ("garbled", 0, 8L))
    )
    assertEquals(window.totalDeliveries, 24L)
    assertEquals(
      window.outcomes,
      List(OutcomeCount("applied", 8), OutcomeCount("garbled", 8), OutcomeCount("timed_out", 8)),
      "same count (8) for all three, so alphabetical order breaks the tie"
    )

  test("a single bucket reports that bucket's upper bound for every percentile"):
    val window = DeliveryStatsWindow.aggregate(List(("applied", 3, 10L)))
    val bound  = LatencyHistogram.upperBoundMs(3)
    assertEquals(window.p50Ms, bound)
    assertEquals(window.p90Ms, bound)
    assertEquals(window.p99Ms, bound)

  test("percentiles walk the histogram ascending — p50 lands in the bucket holding the middle delivery"):
    // 100 deliveries: 60 in bucket 0, 30 in bucket 1, 10 in bucket 2. Cumulative: 60, 90, 100.
    val window = DeliveryStatsWindow.aggregate(List(("applied", 0, 60L), ("applied", 1, 30L), ("applied", 2, 10L)))
    assertEquals(window.p50Ms, LatencyHistogram.upperBoundMs(0), "the 50th delivery is still within bucket 0's 60")
    assertEquals(window.p90Ms, LatencyHistogram.upperBoundMs(1), "the 90th delivery needs bucket 1's cumulative 90")
    assertEquals(window.p99Ms, LatencyHistogram.upperBoundMs(2), "the 99th delivery needs bucket 2's cumulative 100")

  test("a percentile landing in the overflow bucket reports None — there is no upper bound to give"):
    val window = DeliveryStatsWindow.aggregate(List(("timed_out", LatencyHistogram.OverflowBucket, 5L)))
    assertEquals(window.p50Ms, None)
    assertEquals(window.p90Ms, None)
    assertEquals(window.p99Ms, None)

  test("rows for the same outcome and bucket across different callers/hours are summed, not overwritten"):
    val window = DeliveryStatsWindow.aggregate(List(("applied", 0, 4L), ("applied", 0, 6L)))
    assertEquals(window.totalDeliveries, 10L)
    assertEquals(window.outcomes, List(OutcomeCount("applied", 10)))
