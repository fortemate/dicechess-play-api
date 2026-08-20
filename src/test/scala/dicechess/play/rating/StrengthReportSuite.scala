package dicechess.play.rating

import dicechess.play.core.{GameId, RatingCategory}
import dicechess.play.store.GameResultRow

import java.time.Instant

/** Pure — no IO, no Docker: rows in, report out. Anchors: a complete CRN pair becomes ONE pentanomial observation
  * (never two singles), broken/unpaired games degrade to trinomial, non-bot or undecided rows are excluded but counted,
  * and the perspective of a matchup is stable across runs.
  */
class StrengthReportSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-07-17T10:00:00Z")
  private val v1 = "bot:team:oracle:v1"
  private val v3 = "bot:team:oracle:v3"

  private var nextId = 0
  private def row(
      white: String,
      black: String,
      result: Option[Int],
      pairingId: Option[String]
  ): GameResultRow =
    nextId += 1
    GameResultRow(
      GameId(s"g-$nextId"),
      white,
      black,
      result,
      "resign",
      rated = true,
      "Fischer(300,3)",
      "ab",
      pairingId,
      false,
      at
    )

  /** One complete CRN pair: v3 as White wins game 1, v3 as Black gets `secondResult` in the mirror. */
  private def pair(id: String, secondResult: Int): List[GameResultRow] =
    List(row(v3, v1, Some(1), Some(id)), row(v1, v3, Some(secondResult), Some(id)))

  test("a complete CRN pair is one pentanomial observation, not two singles"):
    val report = StrengthReport.build(pair("p1", secondResult = -1), RatingCategory.Default) // v3 wins both halves
    assertEquals(report.completePairs, 1)
    assertEquals(report.singles, 0)
    val matchup = report.pairwise.head
    // Perspective is the lexicographically smaller external id: bot:team:oracle:v1.
    assertEquals(matchup.perspective, "oracle/v1")
    assertEquals(matchup.opponent, "oracle/v3")
    assertEquals(matchup.pairs, Sprt.Pentanomial(1, 0, 0, 0, 0), "v1 lost both games of the pair -> bin 0")

  test("pentanomial bins land by the pair's summed score"):
    val rows = pair("a", -1) ++ // v1 loses both -> bin 0
      pair(
        "b",
        1
      ) ++ // v1 loses as Black, wins as White (2nd game result=1 means White=v1 wins) -> bin 2? no: 0 + 1 = 1 -> bin 2
      pair("c", 0) // loss + draw -> 0.5 -> bin 1
    val report  = StrengthReport.build(rows, RatingCategory.Default)
    val matchup = report.pairwise.head
    assertEquals(matchup.pairs.total, 3L)
    assertEquals(matchup.pairs.n0, 1L)
    assertEquals(matchup.pairs.n1, 1L, "loss + draw = 0.5 for v1")
    assertEquals(matchup.pairs.n2, 1L, "loss + win = 1.0 for v1")

  test("a same-colour 'pair' (no seat swap) degrades to singles — the pentanomial model assumes the swap"):
    val rows = List(
      row(v3, v1, Some(1), Some("no-swap")),
      row(v3, v1, Some(-1), Some("no-swap")) // same participants, same colours: NOT a CRN pair
    )
    val report = StrengthReport.build(rows, RatingCategory.Default)
    assertEquals(report.completePairs, 0)
    assertEquals(report.singles, 2)

  test("an incomplete pairing group and an unpaired game both degrade to trinomial singles"):
    val rows = List(
      row(v3, v1, Some(1), Some("half-pair")), // partner never finished
      row(v1, v3, Some(0), None)               // ordinary unpaired rated game
    )
    val report = StrengthReport.build(rows, RatingCategory.Default)
    assertEquals(report.completePairs, 0)
    assertEquals(report.singles, 2)
    assertEquals(report.pairwise.head.singles, Sprt.Trinomial(losses = 1, draws = 1, wins = 0))

  test("non-bot participants, undecided results and self-play are excluded but counted"):
    val rows = List(
      row("guest:human", v1, Some(1), None),
      row(v1, v3, None, None),
      row(v1, v1, Some(1), None)
    )
    val report = StrengthReport.build(rows, RatingCategory.Default)
    assertEquals(report.excludedRows, 3)
    assertEquals(report.pairwise, Nil)
    assertEquals(report.ranking, Nil)

  test("a dominant corpus yields an SPRT accept and a matching Bradley-Terry order"):
    val rows   = (1 to 30).toList.flatMap(i => pair(s"p$i", secondResult = -1)) // v3 sweeps 30 pairs
    val report = StrengthReport.build(rows, RatingCategory.Default, StrengthReport.Config(bootstrapIterations = 100))
    val m      = report.pairwise.head
    // Perspective v1 loses everything: H0 accepted from v1's side = v3 is the stronger one.
    assertEquals(m.result.verdict, Sprt.Verdict.AcceptH0)
    assertEquals(report.ranking.head.player, "oracle/v3")
    assert(report.ranking.head.elo > report.ranking.last.elo)

  test("a game from another category is excluded and counted, not folded into the report (#280)"):
    // Same two bots, same result, only the control differs: 10+10 is Rapid, so a Blitz report must not see it. The
    // conflation this prevents is a bot's 1+1 results being averaged into its 10+10 strength.
    val blitzOnly = pair("p1", secondResult = -1)
    val rapid     = row(v3, v1, Some(1), None).copy(timeControl = "Fischer(600,10)")
    val report    = StrengthReport.build(blitzOnly :+ rapid, RatingCategory.Default)
    val blitz     = StrengthReport.build(blitzOnly, RatingCategory.Default)
    assertEquals(report.completePairs, blitz.completePairs)
    assertEquals(report.singles, 0, "the Rapid game must not become a Blitz single")
    assertEquals(report.excludedRows, 1, "and it must be counted as excluded, not silently dropped")

  test("render mentions the counts and each matchup once"):
    val report   = StrengthReport.build(pair("p1", -1) :+ row(v3, v1, Some(1), None), RatingCategory.Default)
    val rendered = StrengthReport.render(report, StrengthReport.Config())
    assert(rendered.contains("1 CRN pairs + 1 singles"), rendered.linesIterator.toList.head)
    assert(rendered.linesIterator.count(_.contains("oracle/v1")) >= 1)
    // Locale-pinned rendering (live-corpus regression: a comma-decimal JVM once printed "alpha=beta=0,05").
    assert(rendered.contains("alpha=beta=0.05"), "the report must render dot decimals regardless of JVM locale")

  test("Config.fromValues falls back to the default on every absent or unparseable knob (#181)"):
    val default = StrengthReport.Config()
    assertEquals(StrengthReport.Config.fromValues(None, None, None, None, None), default)
    assertEquals(
      StrengthReport.Config.fromValues(Some("junk"), Some("junk"), Some("junk"), Some("junk"), Some("junk")),
      default
    )

  test("Config.fromValues accepts every knob when all are valid (#181)"):
    val config = StrengthReport.Config.fromValues(Some("10"), Some("30"), Some("0.01"), Some("0.02"), Some("500"))
    assertEquals(
      config,
      StrengthReport.Config(elo0 = 10.0, elo1 = 30.0, alpha = 0.01, beta = 0.02, bootstrapIterations = 500)
    )

  test(
    "Config.fromValues rejects an alpha/beta outside (0, 1) and a non-positive bootstrapIterations, falling back " +
      "instead of disabling anything (#181)"
  ):
    val default = StrengthReport.Config()
    assertEquals(StrengthReport.Config.fromValues(None, None, Some("0"), None, None).alpha, default.alpha)
    assertEquals(StrengthReport.Config.fromValues(None, None, Some("1"), None, None).alpha, default.alpha)
    assertEquals(StrengthReport.Config.fromValues(None, None, None, Some("-0.1"), None).beta, default.beta)
    assertEquals(
      StrengthReport.Config.fromValues(None, None, None, None, Some("0")).bootstrapIterations,
      default.bootstrapIterations
    )
    assertEquals(
      StrengthReport.Config.fromValues(None, None, None, None, Some("-5")).bootstrapIterations,
      default.bootstrapIterations
    )

  test("Config.fromValues combines a lone valid override with the other side's default (#181)"):
    val config = StrengthReport.Config.fromValues(Some("5"), None, None, None, None)
    assertEquals((config.elo0, config.elo1), (5.0, StrengthReport.Config().elo1), "5 < the default elo1 (20): valid")

  test(
    "Config.fromValues rejects an elo0/elo1 pair that violates ordering (inverted, equal, or non-finite), " +
      "falling back to BOTH defaults together rather than keeping the one value that was parsed (#181)"
  ):
    val default = StrengthReport.Config()
    // Only STRENGTH_ELO0 set, but to a value that inverts against the untouched elo1 default (20) — must not
    // silently combine into (30, 20); the whole pair falls back together.
    val inverted = StrengthReport.Config.fromValues(Some("30"), None, None, None, None)
    assertEquals(inverted.elo0, default.elo0, "an inverted pair must not keep the one side that WAS parsed")
    assertEquals(inverted.elo1, default.elo1)
    // Both set explicitly, equal: degenerate (s1 - s0 == 0 forever) — same rejection as inverted.
    val equal = StrengthReport.Config.fromValues(Some("15"), Some("15"), None, None, None)
    assertEquals((equal.elo0, equal.elo1), (default.elo0, default.elo1))
    // A non-finite value (parseable by toDoubleOption, useless as a hypothesis bound).
    val nonFinite = StrengthReport.Config.fromValues(Some("Infinity"), None, None, None, None)
    assertEquals((nonFinite.elo0, nonFinite.elo1), (default.elo0, default.elo1))
