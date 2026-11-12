package dicechess.play.rating

import cats.effect.ExitCode
import dicechess.play.core.{GameId, RatingCategory}
import dicechess.play.store.{GameResultRow, GameResultsStore}

import java.nio.file.Paths
import java.time.Instant

/** Pure — no IO, no Docker: rows in, report out. Anchors: a complete CRN pair becomes ONE pentanomial observation
  * (never two singles), broken/unpaired games degrade to trinomial, non-bot or undecided rows are excluded but counted,
  * and the perspective of a matchup is stable across runs.
  */
class StrengthReportSuite extends munit.CatsEffectSuite:

  private def buildReport(
      rows: List[GameResultRow],
      category: RatingCategory,
      config: StrengthReport.Config = StrengthReport.Config()
  ): StrengthReport =
    StrengthReport.build(rows, category, config).fold(error => fail(error.message), identity)

  private def parsedConfig(
      elo0: Option[String],
      elo1: Option[String],
      alpha: Option[String],
      beta: Option[String],
      iterations: Option[String]
  ): StrengthReport.Config =
    StrengthReport.Config.fromValues(elo0, elo1, alpha, beta, iterations).fold(error => fail(error), identity)

  private def parsedOptions(args: List[String]): LadderReportMain.Options =
    LadderReportMain.parseOptions(args).fold(error => fail(error), identity)

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
    val report = buildReport(pair("p1", secondResult = -1), RatingCategory.Default) // v3 wins both halves
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
    val report  = buildReport(rows, RatingCategory.Default)
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
    val report = buildReport(rows, RatingCategory.Default)
    assertEquals(report.completePairs, 0)
    assertEquals(report.singles, 2)

  test("an incomplete pairing group and an unpaired game both degrade to trinomial singles"):
    val rows = List(
      row(v3, v1, Some(1), Some("half-pair")), // partner never finished
      row(v1, v3, Some(0), None)               // ordinary unpaired rated game
    )
    val report = buildReport(rows, RatingCategory.Default)
    assertEquals(report.completePairs, 0)
    assertEquals(report.singles, 2)
    assertEquals(report.pairwise.head.singles, Sprt.Trinomial(losses = 1, draws = 1, wins = 0))

  test("non-bot participants, undecided results and self-play are excluded but counted"):
    val rows = List(
      row("guest:human", v1, Some(1), None),
      row(v1, v3, None, None),
      row(v1, v1, Some(1), None)
    )
    val report = buildReport(rows, RatingCategory.Default)
    assertEquals(report.excludedRows, 3)
    assertEquals(report.pairwise, Nil)
    assertEquals(report.ranking, Nil)

  test("a dominant corpus yields an SPRT accept and matching provisional strength estimates"):
    val rows   = (1 to 30).toList.flatMap(i => pair(s"p$i", secondResult = -1)) // v3 sweeps 30 pairs
    val report = buildReport(rows, RatingCategory.Default, StrengthReport.Config(bootstrapIterations = 100))
    val m      = report.pairwise.head
    // Perspective v1 loses everything: H0 accepted from v1's side = v3 is the stronger one.
    assertEquals(m.result.verdict, Sprt.Verdict.AcceptH0)
    val winner = report.ranking.find(_.player == "oracle/v3").get
    val loser  = report.ranking.find(_.player == "oracle/v1").get
    assert(winner.elo > loser.elo)
    assertEquals(report.anchored.admitted, Nil, "unanchored evidence has estimates but no admitted ranks")

  test("a game from another category is excluded and counted, not folded into the report (#280)"):
    // Same two bots, same result, only the control differs: 10+10 is Rapid, so a Blitz report must not see it. The
    // conflation this prevents is a bot's 1+1 results being averaged into its 10+10 strength.
    val blitzOnly = pair("p1", secondResult = -1)
    val rapid     = row(v3, v1, Some(1), None).copy(timeControl = "Fischer(600,10)")
    val report    = buildReport(blitzOnly :+ rapid, RatingCategory.Default)
    val blitz     = buildReport(blitzOnly, RatingCategory.Default)
    assertEquals(report.completePairs, blitz.completePairs)
    assertEquals(report.singles, 0, "the Rapid game must not become a Blitz single")
    assertEquals(report.excludedRows, 1, "and it must be counted as excluded, not silently dropped")

  test("render mentions the counts and each matchup once"):
    val report   = buildReport(pair("p1", -1) :+ row(v3, v1, Some(1), None), RatingCategory.Default)
    val rendered = StrengthReport.render(report, StrengthReport.Config())
    assert(rendered.contains("1 CRN pairs + 1 singles"), rendered.linesIterator.toList.head)
    assert(rendered.linesIterator.count(_.contains("oracle/v1")) >= 1)
    // Locale-pinned rendering (live-corpus regression: a comma-decimal JVM once printed "alpha=beta=0,05").
    assert(rendered.contains("alpha=beta=0.05"), "the report must render dot decimals regardless of JVM locale")

  test("Config.fromValues falls back to the default on every absent or unparseable knob (#181)"):
    val default = StrengthReport.Config()
    assertEquals(parsedConfig(None, None, None, None, None), default)
    assertEquals(
      parsedConfig(Some("junk"), Some("junk"), Some("junk"), Some("junk"), Some("junk")),
      default
    )

  test("Config.fromValues accepts every knob when all are valid (#181)"):
    val config = parsedConfig(Some("10"), Some("30"), Some("0.01"), Some("0.02"), Some("500"))
    assertEquals(
      config,
      StrengthReport.Config(elo0 = 10.0, elo1 = 30.0, alpha = 0.01, beta = 0.02, bootstrapIterations = 500)
    )

  test(
    "Config.fromValues rejects an alpha/beta outside (0, 1) and a non-positive bootstrapIterations, falling back " +
      "instead of disabling anything (#181)"
  ):
    val default = StrengthReport.Config()
    assertEquals(parsedConfig(None, None, Some("0"), None, None).alpha, default.alpha)
    assertEquals(parsedConfig(None, None, Some("1"), None, None).alpha, default.alpha)
    assertEquals(parsedConfig(None, None, None, Some("-0.1"), None).beta, default.beta)
    assertEquals(
      parsedConfig(None, None, None, None, Some("0")).bootstrapIterations,
      default.bootstrapIterations
    )
    assertEquals(
      parsedConfig(None, None, None, None, Some("-5")).bootstrapIterations,
      default.bootstrapIterations
    )

  test("Config.fromValues combines a lone valid override with the other side's default (#181)"):
    val config = parsedConfig(Some("5"), None, None, None, None)
    assertEquals((config.elo0, config.elo1), (5.0, StrengthReport.Config().elo1), "5 < the default elo1 (20): valid")

  test(
    "Config.fromValues rejects an elo0/elo1 pair that violates ordering (inverted, equal, or non-finite), " +
      "falling back to BOTH defaults together rather than keeping the one value that was parsed (#181)"
  ):
    val default = StrengthReport.Config()
    // Only elo0 given, but to a value that inverts against the untouched elo1 default (20) — must not
    // silently combine into (30, 20); the whole pair falls back together.
    val inverted = parsedConfig(Some("30"), None, None, None, None)
    assertEquals(inverted.elo0, default.elo0, "an inverted pair must not keep the one side that WAS parsed")
    assertEquals(inverted.elo1, default.elo1)
    // Both set explicitly, equal: degenerate (s1 - s0 == 0 forever) — same rejection as inverted.
    val equal = parsedConfig(Some("15"), Some("15"), None, None, None)
    assertEquals((equal.elo0, equal.elo1), (default.elo0, default.elo1))
    // A non-finite value (parseable by toDoubleOption, useless as a hypothesis bound).
    val nonFinite = parsedConfig(Some("Infinity"), None, None, None, None)
    assertEquals((nonFinite.elo0, nonFinite.elo1), (default.elo0, default.elo1))

  test("renderMarkdown produces valid Quartz frontmatter, summary, and Markdown tables"):
    val report    = buildReport(pair("p1", secondResult = -1), RatingCategory.Default)
    val config    = StrengthReport.Config()
    val fixedTime = Instant.parse("2026-09-16T12:00:00Z")
    val md        = StrengthReport.renderMarkdown(report, config, fixedTime)

    assert(md.contains("title: Bot Strength Report"), "contains frontmatter title")
    assert(md.contains("draft: false"), "contains draft: false")
    assert(md.contains("- **Observations:** 1 complete pairs, 0 singles"), "contains observations")
    assert(md.contains("## Pool Ranking (Bradley-Terry)"), "contains ranking table header")
    assert(
      md.contains("| Rank | Bot | Relative Elo | 95% Confidence Interval | LOS vs Next |"),
      "contains ranking columns"
    )
    assert(md.contains("`oracle/v1`"), "contains bot names in ranking")
    assert(md.contains("## Pairwise Matchups (SPRT)"), "contains pairwise table header")
    assert(
      md.contains("| Matchup | Verdict | Pairs [0..4] | Singles (W/D/L) | LLR | Bounds |"),
      "contains pairwise columns"
    )
    assert(md.contains("| `oracle/v1` vs `oracle/v3` |"), "contains matchup row")
    assert(md.contains("2026-09-16T12:00:00Z"), "contains generated timestamp")

  test("LadderReportMain.parseOptions parses key-value options and falls back to positional"):
    // Key-value parsing
    val kvArgs = List(
      "out=content/reports/bot-strength.md",
      "category=blitz",
      "iterations=2000",
      "elo0=5.0",
      "elo1=25.0",
      "alpha=0.01",
      "beta=0.02"
    )
    val kvOptions = parsedOptions(kvArgs)
    assertEquals(kvOptions.out, Some(Paths.get("content/reports/bot-strength.md")))
    assertEquals(kvOptions.format, LadderReportMain.Format.Markdown)
    assertEquals(kvOptions.category, RatingCategory.Blitz)
    assertEquals(kvOptions.config.bootstrapIterations, 2000)
    assertEquals(kvOptions.config.elo0, 5.0)
    assertEquals(kvOptions.config.elo1, 25.0)
    assertEquals(kvOptions.config.alpha, 0.01)
    assertEquals(kvOptions.config.beta, 0.02)
    assertEquals(kvOptions.saveSnapshot, false)

    val dbOptions = parsedOptions(List("savedb=true", "out=test.md"))
    assertEquals(dbOptions.saveSnapshot, true)
    val snapOptions = parsedOptions(List("savesnapshot=yes"))
    assertEquals(snapOptions.saveSnapshot, true)

    // Positional fallback
    val posArgs    = List("10.0", "30.0", "0.02", "0.03")
    val posOptions = parsedOptions(posArgs)
    assertEquals(posOptions.out, None)
    assertEquals(posOptions.format, LadderReportMain.Format.Text)
    assertEquals(posOptions.saveSnapshot, false)
    assertEquals(posOptions.config.elo0, 10.0)
    assertEquals(posOptions.config.elo1, 30.0)
    assertEquals(posOptions.config.alpha, 0.02)
    assertEquals(posOptions.config.beta, 0.03)

  test("LadderReportMain.parseOptions validates its arguments through Config.fromValues (#181)"):
    val default = StrengthReport.Config()
    // A lone elo0 above the elo1 default would invert the SPRT bounds; the pair falls back together instead.
    val inverted = parsedOptions(List("elo0=30"))
    assertEquals((inverted.config.elo0, inverted.config.elo1), (default.elo0, default.elo1))
    // Error rates outside (0, 1) and a non-positive iteration count fall back to their defaults.
    val outOfRange = parsedOptions(List("alpha=0", "beta=1", "iterations=0"))
    assertEquals(outOfRange.config.alpha, default.alpha)
    assertEquals(outOfRange.config.beta, default.beta)
    assertEquals(outOfRange.config.bootstrapIterations, default.bootstrapIterations)
    // The positional form goes through the same checks.
    val positional = parsedOptions(List("30", "20"))
    assertEquals((positional.config.elo0, positional.config.elo1), (default.elo0, default.elo1))

  test("LadderReportMain.parseOptions filters non-positive window values"):
    val zeroWindow = parsedOptions(List("window=0"))
    assertEquals(zeroWindow.config.windowDays, None)

    val negWindow = parsedOptions(List("window=-5"))
    assertEquals(negWindow.config.windowDays, None)

    val validWindow = parsedOptions(List("window=14"))
    assertEquals(validWindow.config.windowDays, Some(14))

  test("StrengthReport.build validates category and handles default fallback vs mismatched custom set"):
    val games = pair("p1", -1)

    // Default config (AnchorSet.Default has category="blitz") with Rapid category falls back to unanchored
    val rapidReport = buildReport(games, RatingCategory.Rapid)
    assertEquals(rapidReport.anchored.anchorSetVersion, "unanchored-rapid")
    assert(!rapidReport.anchored.isCalibrated)

    val mismatchedSet = AnchorSet("custom-blitz", 1, "blitz", Nil)
    val mismatch = StrengthReport.build(games, RatingCategory.Rapid, StrengthReport.Config(anchorSet = mismatchedSet))
    assert(mismatch.left.toOption.exists(_.message.contains("category")))

  test("Provisional bots render caveats and badges when no bots reach admission thresholds"):
    // Under-threshold games: only 2 games between two provisional bots
    val games  = pair("p1", -1)
    val report = buildReport(
      games,
      RatingCategory.Default,
      StrengthReport.Config(minGamesForAdmission = 50, minOpponentsForAdmission = 5)
    )

    assertEquals(report.anchored.admitted, Nil)
    assert(report.anchored.provisional.nonEmpty)

    val text =
      StrengthReport.render(report, StrengthReport.Config(minGamesForAdmission = 50, minOpponentsForAdmission = 5))
    assert(text.contains("--- Provisional bots (insufficient counts, calibration or uncertainty evidence) ---"))
    assert(text.contains("[Provisional]"))

    val md = StrengthReport.renderMarkdown(
      report,
      StrengthReport.Config(minGamesForAdmission = 50, minOpponentsForAdmission = 5)
    )
    assert(md.contains("### Provisional Bots"))
    assert(md.contains("lack sufficient game/opponent counts, calibration or uncertainty evidence"))
    assert(md.contains("`[Provisional]`"))

  test("database-prefiltered input produces the full-corpus report including bootstrap and exclusion diagnostics"):
    val recent = pair("source-recent", -1).zipWithIndex.map { (game, index) =>
      game.copy(finishedAt = at.minusSeconds((index + 1L) * 86400))
    }
    val old   = pair("source-old", 0).map(_.copy(finishedAt = at.minusSeconds(40L * 86400)))
    val split = pair("source-split", 1).zipWithIndex.map { (game, index) =>
      game.copy(finishedAt = at.minusSeconds((15L - index) * 86400))
    }
    val excluded = List(
      row(v1, v3, Some(1), None).copy(timeControl = "Fischer(600,0)"),
      row("user:source-fixture", v1, Some(0), None).copy(finishedAt = at.minusSeconds(3L * 86400)),
      row(v1, v3, None, None).copy(finishedAt = at.minusSeconds(4L * 86400))
    )
    val full = recent ++ old ++ split ++ excluded
    List(None, Some(14)).foreach { days =>
      val options = parsedOptions(List("iterations=256")).copy(config =
        StrengthReport.Config(
          bootstrapIterations = 256,
          windowDays = days,
          anchorSet = AnchorSet("source-fixture", 1, "blitz", List(Anchor("oracle/v1", 0.0))),
          minGamesForAdmission = 1,
          minOpponentsForAdmission = 1,
          maxCiWidthElo = Some(10000.0)
        )
      )
      val lower    = days.map(d => at.minus(java.time.Duration.ofDays(d.toLong)))
      val selected = full.filter(game =>
        RatingCategory.ofStored(game.timeControl).contains(options.category) &&
          lower.forall(cutoff => !game.finishedAt.isBefore(cutoff))
      )
      val cutoff = full
        .filter(game => RatingCategory.ofStored(game.timeControl).contains(options.category))
        .map(_.finishedAt)
        .maxOption
      val source   = GameResultsStore.ReportSource(selected, full.size - selected.size, cutoff)
      val expected = StrengthReport.build(full, options.category, options.config)
      assert(expected.isRight)
      val ranking = expected.toOption.get.ranking
      assert(ranking.nonEmpty)
      assert(ranking.forall(bot => bot.ciLow.isFinite && bot.ciHigh.isFinite))
      assertEquals(LadderReportMain.buildReport(source, options), expected)
    }

  test("LadderReportMain helpers: parsePlayerKey, resolveEffectiveAnchor, assembleLeaders, and renderReport"):
    import dicechess.play.store.{LeaderboardEntry, ResultTally}

    // parsePlayerKey
    assertEquals(LadderReportMain.parsePlayerKey("oracle/v1"), (Some("oracle"), "v1"))
    assertEquals(LadderReportMain.parsePlayerKey("standalone"), (None, "standalone"))

    // resolveEffectiveAnchor
    val defaultBlitz = AnchorSet.Default
    assertEquals(LadderReportMain.resolveEffectiveAnchor(defaultBlitz, RatingCategory.Default), defaultBlitz)
    val unanchoredRapid = LadderReportMain.resolveEffectiveAnchor(defaultBlitz, RatingCategory.Rapid)
    assertEquals(unanchoredRapid.category, "rapid")
    assertEquals(unanchoredRapid.epoch, 0)
    val custom = AnchorSet("custom-blitz", 2, "blitz", Nil)
    assertEquals(LadderReportMain.resolveEffectiveAnchor(custom, RatingCategory.Default), custom)

    // resolveCutoff
    val t1 = Instant.parse("2026-10-01T12:00:00Z")
    val t2 = Instant.parse("2026-10-02T12:00:00Z")
    assertEquals(LadderReportMain.resolveCutoff(Some(t1), Some(t2)), t1)
    assertEquals(LadderReportMain.resolveCutoff(Some(t2), Some(t1)), t1)
    assertEquals(LadderReportMain.resolveCutoff(Some(t1), None), t1)
    assertEquals(LadderReportMain.resolveCutoff(None, Some(t2)), t2)

    // assembleLeaders
    val existing = List(
      // v1: lower anchored elo (1600), but higher conservative rating (1800 - 2*50 = 1700)
      LeaderboardEntry("oracle", "v1", 1800.0, 50.0, onLadder = true, ResultTally(6, 2, 2)),
      // v2: higher anchored elo (1700), but lower conservative rating (1600 - 2*50 = 1500)
      LeaderboardEntry("oracle", "v2", 1600.0, 50.0, onLadder = true, ResultTally(6, 2, 2)),
      // provisional: filtered out because rd = 350 > 110
      LeaderboardEntry("oracle", "provisional", 1750.0, 350.0, onLadder = true, ResultTally.Empty)
    )
    val leaders = LadderReportMain.assembleLeaders(existing, epoch = 1)
    assertEquals(leaders.size, 2)
    assertEquals(leaders.map(_.name), List("v1", "v2"), "sorted by conservative Glicko-2 rating, not anchored elo")
    assertEquals(leaders.map(_.rank), List(1, 2), "contiguous ranks without gaps from excluded bots")
    assertEquals(leaders.head.team, Some("oracle"))
    assertEquals(leaders.head.epoch, Some(1))

    // renderReport
    val games   = pair("p1", -1)
    val report  = buildReport(games, RatingCategory.Default)
    val optText =
      LadderReportMain.Options(StrengthReport.Config(), RatingCategory.Default, None, LadderReportMain.Format.Text)
    val optMd = optText.copy(format = LadderReportMain.Format.Markdown)
    assert(LadderReportMain.renderReport(report, optText).contains("Dice Chess ladder — strength report"))
    assert(LadderReportMain.renderReport(report, optMd).contains("title: Bot Strength Report"))

  test("report CLI binds versioned color and uncertainty policy and rejects malformed explicit overrides"):
    val options = parsedOptions(List("whiteadvantage=90", "maxciwidth=80", "iterations=160"))
    assertEquals(options.config.whiteAdvantageElo, 90.0)
    assertEquals(options.config.maxCiWidthElo, Some(80.0))
    val report = buildReport(pair("fixture", -1), RatingCategory.Default, options.config)
    assertEquals(report.anchored.whiteAdvantageElo, 90.0)
    assertEquals(report.anchored.maxCiWidthElo, Some(80.0))
    assertEquals(report.anchored.bootstrapIterations, 160)
    val text = StrengthReport.render(report, options.config)
    val md   = StrengthReport.renderMarkdown(report, options.config)
    assert(text.contains(AnchoredStrength.ModelVersion))
    assert(text.contains("white advantage: 90.0"))
    assert(text.contains("max CI width: 80.0"))
    assert(md.contains(AnchoredStrength.ModelVersion))
    assert(md.contains("**White advantage Elo:** 90.0"))
    assert(md.contains("**Maximum CI width Elo:** 80.0"))
    Seq(
      "whiteadvantage=NaN",
      "whiteadvantage=Infinity",
      "whiteadvantage=garbage",
      "whiteadvantage=1e308",
      "maxciwidth=NaN",
      "maxciwidth=Infinity",
      "maxciwidth=garbage",
      "maxciwidth=0",
      "maxciwidth=-1"
    ).foreach { arg =>
      val result = LadderReportMain.parseOptions(List(arg))
      assert(result.isLeft, s"expected a typed policy error for $arg")
      val error = result.swap.toOption.get
      assert(error.contains(arg.takeWhile(_ != '=')))
      assert(!error.contains(arg.dropWhile(_ != '=').drop(1)), "raw input must not be echoed")
    }

  test("missing bootstrap remains visibly unavailable and provisional estimates never fall back into rank tables"):
    val cfg    = StrengthReport.Config(bootstrapIterations = 0)
    val report = buildReport(pair("fixture", -1), RatingCategory.Default, cfg)
    assertEquals(report.anchored.admitted, Nil)
    val text = StrengthReport.render(report, cfg)
    val md   = StrengthReport.renderMarkdown(report, cfg)
    assert(text.contains("admission disabled"))
    assert(text.contains("unavailable"))
    assert(!text.contains("NaN"))
    assert(!text.linesIterator.exists(_.matches(" *[0-9]+\\..*")))
    val rankingTable = md.split("### Provisional Bots")(0).split("## Pool Ranking")(1)
    assert(!rankingTable.contains("`oracle/v1`"))
    assert(md.contains("unavailable"))
    assert(!md.contains("NaN"))

  test("invalid policy inputs produce an error exit before any database access"):
    LadderReportMain.run(List("maxciwidth=0")).map(code => assertEquals(code, ExitCode.Error))

  test("invalid output paths produce a typed argument error"):
    val result = LadderReportMain.parseOptions(List("out=bad" + 0.toChar + "path"))
    assert(result.left.toOption.exists(_.contains("out")))

  test("empty rankings identify disabled admission separately from unmet evidence requirements"):
    val cfg          = StrengthReport.Config(bootstrapIterations = 0)
    val disabled     = buildReport(pair("disabled", -1), RatingCategory.Default, cfg)
    val activeConfig = cfg.copy(maxCiWidthElo = Some(80.0))
    val active       = buildReport(pair("active", -1), RatingCategory.Default, activeConfig)
    List(StrengthReport.render(disabled, cfg), StrengthReport.renderMarkdown(disabled, cfg)).foreach { text =>
      assert(text.contains("No bots admitted: admission disabled"))
    }
    List(StrengthReport.render(active, activeConfig), StrengthReport.renderMarkdown(active, activeConfig)).foreach {
      text =>
        assert(text.contains("No bots admitted: no estimate satisfies the admission requirements."))
        assert(!text.contains("admission disabled"))
    }
