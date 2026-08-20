package dicechess.play.store

import scala.concurrent.duration.*

/** `Retention.Config` parsing (#179) — pure, Docker-free. The on/off switch and the "a bad knob must not become a
  * dangerous value" rules are the whole point of this class, so they are pinned here rather than left to a live run.
  */
class RetentionConfigSuite extends munit.FunSuite:

  private def parse(interval: Option[String], days: Option[String] = None, batch: Option[String] = None) =
    Retention.Config.fromValues(interval, days, batch)

  test("an absent interval disables retention entirely — the safe default for the only task that deletes"):
    assertEquals(parse(None), None)

  test("an unparseable or non-positive interval also disables it, rather than busy-spinning"):
    assertEquals(parse(Some("soon")), None)
    assertEquals(parse(Some("0")), None)
    assertEquals(parse(Some("-60")), None)

  test("a valid interval enables it, with both knobs falling back to their defaults"):
    val config = parse(Some("3600")).getOrElse(fail("expected a config"))
    assertEquals(config.interval, 1.hour)
    assertEquals(config.retentionDays, Retention.Config.DefaultRetentionDays)
    assertEquals(config.batchSize, Retention.Config.DefaultBatchSize)

  test("retentionDays and batchSize are honoured when sane"):
    val config = parse(Some("600"), days = Some("7"), batch = Some("250")).getOrElse(fail("expected a config"))
    assertEquals(config.interval, 10.minutes)
    assertEquals(config.retentionDays, 7)
    assertEquals(config.batchSize, 250)

  test("retentionDays = 0 is NOT honoured — a cutoff of 'now' would prune a game the moment it ended"):
    // Falling back to the default is the conservative direction: the alternative silently turns a typo into immediate
    // deletion of the operational record.
    assertEquals(parse(Some("600"), days = Some("0")).map(_.retentionDays), Some(Retention.Config.DefaultRetentionDays))
    assertEquals(
      parse(Some("600"), days = Some("-1")).map(_.retentionDays),
      Some(Retention.Config.DefaultRetentionDays)
    )
    assertEquals(
      parse(Some("600"), days = Some("wat")).map(_.retentionDays),
      Some(Retention.Config.DefaultRetentionDays)
    )

  test("a bad batchSize falls back too — it is a tuning knob, not the switch"):
    assertEquals(parse(Some("600"), batch = Some("0")).map(_.batchSize), Some(Retention.Config.DefaultBatchSize))
    assertEquals(parse(Some("600"), batch = Some("nope")).map(_.batchSize), Some(Retention.Config.DefaultBatchSize))
