package dicechess.play.rating

import dicechess.play.rating.RatingEvaluation.*
import dicechess.play.rating.RatingModels.{GlickoSpec, WhrSpec}

import java.time.{Instant, LocalDate}

/** The chronological evaluation (#148) on hand-made rows: eligibility and ordering, every segment tag, the scoring
  * rules, the day-block bootstrap, window reporting discipline, the synthetic coverage fixture and the runner's option
  * parsing. Pure — no IO, no Docker.
  */
class RatingEvaluationSuite extends munit.FunSuite:

  private val A     = "bot:team:fx:a"
  private val B     = "bot:team:fx:b"
  private val C     = "bot:team:fx:c"
  private val Human = "human:0123456789abcdef"
  private val Day0  = Instant.parse("2026-08-01T00:00:00Z")

  private def row(
      day: Int,
      white: String,
      black: String,
      result: Option[Int],
      rated: Boolean = true,
      category: Option[String] = Some("blitz"),
      minute: Int = 0
  ): RatingReplay.Game =
    val at               = Day0.plusSeconds(day * 86400L + minute * 60L)
    def seat(id: String) =
      RatingReplay.Seat(id, if id.startsWith("bot:") then "bot" else "human", resolvableNow = true, None, None)
    RatingReplay.Game(
      gameId = s"g-$day-$minute-$white-$black",
      finishedAt = at,
      ratingAppliedAt = Some(at.plusSeconds(1)),
      seqFinished = day * 10000L + minute,
      seqApplied = None,
      category = category,
      timeControl = "Fischer(300,3)",
      result = result,
      termination = "king_captured",
      rated = rated,
      origin = "ladder",
      ladder = true,
      pairingId = None,
      applied = true,
      numericRecorded = false,
      white = seat(white),
      black = seat(black),
      ownerRelation = None,
      hasArchive = false,
      archiveSportingEligible = None
    )

  private val windows = Windows(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 10))

  test("prepare keeps rated, decided, categorised games between two identities, in finish order"):
    val rows = List(
      row(1, A, B, Some(1), minute = 5),
      row(1, B, A, Some(-1), minute = 1),
      row(0, A, B, None),
      row(0, A, B, Some(1), rated = false),
      row(0, A, B, Some(1), category = None),
      row(0, A, A, Some(1))
    )
    val prepared = prepare(rows, windows, Set(A))
    assertEquals(prepared.games.map(_.observation.whiteScore), Vector(0.0, 1.0))
    assertEquals(prepared.excludedRows, 4)
    assertEquals(prepared.firstDay, LocalDate.of(2026, 8, 2))
    assertEquals(prepared.days, 1)
    assert(prepared.games.forall(_.constantBot))

  test("segments: newcomer, returning, activity, pool-change day, window and domain"):
    val rows =
      (0 until 120).map(i => row(0, A, B, Some(if i % 2 == 0 then 1 else -1), minute = i)).toList ++
        List(row(4, A, C, Some(1)), row(6, A, C, Some(-1)), row(11, Human, A, Some(1)))
    val prepared = prepare(rows, windows, Set.empty)
    val games    = prepared.games
    assert(
      games(0).newcomer && games(99).newcomer && !games(100).newcomer,
      "the 101st game of both is no newcomer game"
    )
    val day4 = games.find(_.observation.day == 4).get
    val day6 = games.find(_.observation.day == 6).get
    assert(day4.returning, "A last played on day 0, four idle days")
    assert(!day6.returning, "two idle days do not count")
    assert(day4.poolChangeDay, "C's first day changes the pool")
    assert(games(0).poolChangeDay, "the first day is everyone's first day")
    assert(day4.lowActivity, "C had no games in the previous week")
    assert(!day6.lowActivity, "by day 6 both seats have prior-week games, above the zero median")
    assertEquals(games(0).window, Windows.Training)
    assertEquals(day4.window, Windows.Validation, "2026-08-05 is the validation boundary itself")
    assertEquals(day6.window, Windows.Validation)
    val last = games.last
    assertEquals(last.window, Windows.Test)
    assertEquals(last.domain, Domain.HumanBot)
    assertEquals(games(0).domain, Domain.BotBot)
    assertEquals(Domain.of("human", "human"), Domain.HumanHuman)
    assertEquals(Domain.of("guest", "bot"), Domain.Other)

  test("log loss, Brier, accuracy and calibration bins on known forecasts"):
    val pairs = List((0.9, 1.0), (0.9, 1.0), (0.9, 0.0), (0.2, 0.0), (0.5, 0.5))
    val m     = metrics(pairs)
    assertEquals(m.games, 5L)
    val expectedLl = -(2 * math.log(0.9) + math.log(0.1) + math.log(0.8) + math.log(0.5)) / 5
    assertEqualsDouble(m.logLoss, expectedLl, 1e-12)
    assertEqualsDouble(m.brier, (0.01 + 0.01 + 0.81 + 0.04 + 0.0) / 5, 1e-12)
    assertEqualsDouble(m.accuracy, 0.8, 1e-12)
    val top = m.bins.find(_.lo == 0.9).get
    assertEquals(top.games, 3L)
    assertEqualsDouble(top.meanScore, 2.0 / 3, 1e-12)
    assertEqualsDouble(m.ece, (3.0 / 5) * math.abs(0.9 - 2.0 / 3) + (1.0 / 5) * math.abs(0.2 - 0.0) + 0.0, 1e-12)
    assertEqualsDouble(logLoss(0.0, 1.0), -math.log(1e-6), 1e-9, "saturated forecasts are clipped")
    assertEquals(metrics(Nil).games, 0L)

  test("the day-block bootstrap centres on the mean difference and its interval covers it"):
    val rows     = (0 until 4).flatMap(day => (0 until 20).map(m => row(day, A, B, Some(1), minute = m))).toList
    val prepared = prepare(rows, windows, Set.empty)
    val n        = prepared.games.length
    val better   = Array.fill(n)(0.8)
    val worse    = Array.fill(n)(0.6)
    val delta    = bootstrapDelta(better, worse, prepared.games, prepared.games.indices, 200, 1L)
    assertEquals(delta.games, n.toLong)
    assertEqualsDouble(delta.logLossDelta, math.log(0.6) - math.log(0.8), 1e-12)
    assert(delta.ciLow <= delta.logLossDelta && delta.logLossDelta <= delta.ciHigh)

  test("evaluate reports only the requested windows, compares with the first model, and renders"):
    val rng  = new scala.util.Random(3)
    val rows = (0 until 14).flatMap { day =>
      (0 until 40).map { m =>
        val (w, b) = if rng.nextBoolean() then (A, B) else (B, C)
        val p      = if w == A then 0.7 else 0.6
        row(day, w, b, Some(if rng.nextDouble() < p then 1 else -1), minute = m)
      }
    }.toList
    val specs = List(
      ModelSpec.Glicko(GlickoSpec()),
      ModelSpec.Whr(WhrSpec(30.0)),
      ModelSpec.BradleyTerry(RatingModels.BradleyTerrySpec())
    )
    val config = Config(windows = windows, constants = Set(A), bootstrapResamples = 50)
    val report = evaluate(rows, specs, config)
    assertEquals(report.models.map(_.name).head, "glicko(tau=0.30,idle=0,period=game)")
    assertEquals(report.models.head.versusBaseline, Map.empty)
    val whr = report.models(1)
    assertEquals(whr.metrics.keySet, Set(Windows.Training, Windows.Validation), "the test window is not reported")
    assert(whr.versusBaseline(Windows.Validation)("bot-bot").games > 0)
    assert(whr.drift(Windows.Validation).contains(A))
    val text = render(report)
    assert(text.contains("--- validation / bot-bot ---"))
    assert(text.contains("baseline"))
    assert(text.contains("drift bot:team:fx:a"))
    assert(!text.contains("--- test /"))
    val everything = evaluate(rows, specs.take(1), config.copy(reportedWindows = Windows.All))
    assertEquals(everything.models.head.metrics.keySet, Set(Windows.Training, Windows.Validation, Windows.Test))

  test("the synthetic fixture is deterministic per seed and the coverage summary renders"):
    val scenario = RatingEvaluationFixture.Stationary.copy(
      days = 6,
      gamesPerDay = 60,
      arrivals = Map(3 -> Vector(RatingEvaluationFixture.Bot("bot:team:fx:n1", 2000))),
      departures = Map(4 -> Set("bot:team:fx:b"))
    )
    val once = RatingEvaluationFixture.simulate(scenario, 5L)
    assertEquals(once, RatingEvaluationFixture.simulate(scenario, 5L))
    assertEquals(once.count(_.day == 0), 60)
    assert(once.exists(o => o.day >= 3 && (o.white == "bot:team:fx:n1" || o.black == "bot:team:fx:n1")))
    assert(!once.exists(o => o.day >= 4 && (o.white == "bot:team:fx:b" || o.black == "bot:team:fx:b")))
    assertEquals(scenario.changeDay, 3)
    val summary = RatingEvaluationFixture.evaluate(
      scenario,
      List(
        ModelSpec.Glicko(GlickoSpec()),
        ModelSpec.Whr(WhrSpec(30.0)),
        ModelSpec.BradleyTerry(RatingModels.BradleyTerrySpec())
      ),
      1 to 2
    )
    assertEquals(summary.seeds, 2)
    val glicko = summary.models.head
    assertEquals(glicko.estimates, 16L, "eight bots remain at the end (8 + 1 − 1), two seeds")
    assertEquals(glicko.withInterval, 16L)
    assert(glicko.coverage.exists(c => c >= 0.0 && c <= 1.0))
    assertEquals(summary.models(2).coverage, None, "Bradley–Terry carries no interval")
    val lines = RatingEvaluationFixture.render(summary)
    assert(lines.exists(_.contains("synthetic coverage")))
    assert(lines.exists(_.contains("n/a")))
    val withSynthetic =
      evaluate(Nil, List(ModelSpec.Glicko(GlickoSpec())), Config(windows = windows)).copy(synthetic = Some(summary))
    assert(render(withSynthetic).contains("stationary-pool-change"))

  test("Windows.of and the preregistered split"):
    assertEquals(Windows.Preregistered.of(LocalDate.of(2026, 8, 16)), Windows.Training)
    assertEquals(Windows.Preregistered.of(LocalDate.of(2026, 8, 17)), Windows.Validation)
    assertEquals(Windows.Preregistered.of(LocalDate.of(2026, 9, 1)), Windows.Test)

  test("model specs build the model they name"):
    assertEquals(ModelSpec.Glicko(GlickoSpec(tau = 0.1)).build.name, "glicko(tau=0.10,idle=0,period=game)")
    assertEquals(ModelSpec.BradleyTerry(RatingModels.BradleyTerrySpec(Some(7))).build.name, "bt(window=7)")
    assertEquals(ModelSpec.Whr(WhrSpec(100.0)).build.name, "whr(w=100)")

  // ── runner option parsing ──────────────────────────────────────────────────────

  private def parse(args: String*) = RatingEvaluationMain.parseOptions(args.toList)

  test("the runner requires a corpus, an output directory and at least one model"):
    assert(parse("out=x").left.exists(_.contains("corpus=")))
    assert(parse("corpus=c.jsonl").left.exists(_.contains("out=")))
    assert(parse("corpus=c.jsonl", "out=x").left.exists(_.contains("at least one model")))
    assert(parse("corpus=c.jsonl", "out=x", "models=grid").isRight)

  test("models=grid is the preregistered grid with the production Glicko-2 first; model= may repeat"):
    val grid = parse("corpus=c.jsonl", "out=x", "models=grid").toOption.get
    assertEquals(grid.specs.length, RatingEvaluationMain.Grid.length)
    assertEquals(grid.specs.head.build.name, "glicko(tau=0.30,idle=0,period=game)")
    assertEquals(grid.config.constants, RatingEvaluationMain.PreregisteredConstants)
    assertEquals(grid.config.reportedWindows, List(Windows.Training, Windows.Validation))
    val two = parse("corpus=c.jsonl", "out=x", "model=whr:w=30", "model=bt:window=7").toOption.get
    assertEquals(two.specs.map(_.build.name), List("whr(w=30)", "bt(window=7)"))
    val one = parse("corpus=c.jsonl", "out=x", "models=baseline").toOption.get
    assertEquals(one.specs.length, 1)

  test("model spec vocabulary and refusals"):
    assertEquals(
      RatingEvaluationMain.modelSpec("glicko:tau=0.6:idle=10:period=day").map(_.build.name),
      Right("glicko(tau=0.60,idle=10,period=day)")
    )
    assertEquals(
      RatingEvaluationMain.modelSpec("glicko:anchor=bot/team/fx/a").map(_.build.name),
      Right("glicko(tau=0.30,idle=0,period=game,anchors=1)")
    )
    assert(RatingEvaluationMain.modelSpec("glicko:period=week").isLeft)
    assert(RatingEvaluationMain.modelSpec("glicko:tau=-1").isLeft)
    assert(RatingEvaluationMain.modelSpec("glicko:foo=1").isLeft)
    assert(RatingEvaluationMain.modelSpec("bt:window=0").isLeft)
    assert(RatingEvaluationMain.modelSpec("bt:foo=1").isLeft)
    assert(RatingEvaluationMain.modelSpec("whr").isLeft)
    assert(RatingEvaluationMain.modelSpec("whr:x=1").isLeft)
    assert(RatingEvaluationMain.modelSpec("elo").isLeft)
    assert(RatingEvaluationMain.modelSpec("glicko:tau").isLeft)

  test("every runner option is validated as a value"):
    val base                 = List("corpus=c.jsonl", "out=x", "models=baseline")
    def withArg(arg: String) = RatingEvaluationMain.parseOptions(base :+ arg)
    assert(withArg("bogus=1").left.exists(_.contains("unknown option")))
    assert(RatingEvaluationMain.parseOptions(base :+ "out=y").left.exists(_.contains("more than once")))
    assert(withArg("nonsense").left.exists(_.contains("key=value")))
    assert(withArg("models=all").isLeft)
    assert(withArg("report-windows=training,future").isLeft)
    assert(withArg("validation-from=yesterday").isLeft)
    assert(withArg("test-from=2026-08-01").isLeft, "test must follow validation")
    assert(withArg("bootstrap=0").isLeft)
    assert(withArg("seed=x").isLeft)
    assert(withArg("synthetic=maybe").isLeft)
    assert(withArg("synthetic-seeds=-1").isLeft)
    val full = RatingEvaluationMain
      .parseOptions(
        base ++ List(
          "report-windows=training,validation,test",
          "validation-from=2026-08-10",
          "test-from=2026-08-20",
          "constants=bot:team:fx:a, bot:team:fx:b",
          "category=rapid",
          "bootstrap=10",
          "seed=7",
          "synthetic=true",
          "synthetic-seeds=3",
          "revision=abc"
        )
      )
      .toOption
      .get
    assertEquals(full.config.reportedWindows, Windows.All)
    assertEquals(full.config.windows, Windows(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20)))
    assertEquals(full.config.constants, Set("bot:team:fx:a", "bot:team:fx:b"))
    assertEquals(full.config.category, "rapid")
    assertEquals((full.config.bootstrapResamples, full.config.seed), (10, 7L))
    assertEquals((full.synthetic, full.syntheticSeeds, full.revision), (true, 3, Some("abc")))
