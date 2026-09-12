package dicechess.play.rating

import cats.syntax.all.*
import dicechess.play.rating.RatingModels.{Estimate, Model, Observation}
import io.circe.derivation.ConfiguredCodec

import java.time.{Instant, LocalDate, ZoneOffset}
import java.time.temporal.ChronoUnit
import scala.collection.mutable

/** Leakage-free chronological comparison of rating models over a corpus export (#148) — the evidence step of ADR 008
  * that selects the time-varying model: every game is forecast from state that has seen only earlier games, the
  * forecasts are scored with log loss, Brier score and calibration per domain, window and segment, challengers are
  * compared with the baseline by a day-block bootstrap, and the drift of identities known to be constant is reported
  * for every model. Pure: corpus in, [[Report]] out; [[RatingEvaluationMain]] is the file shell.
  *
  * The corpus is the same JSONL export [[RatingReplay]] reads ([[RatingReplay.Game]]); only rated, decided, categorised
  * games between two different identities are forecast. Windows (training / validation / test) are chronological by
  * `finished_at` and are INPUTS — the preregistered boundaries live in the private knowledge base and the report echoes
  * what it was given. Segments mark each forecast once, independently of the model: a newcomer's early games, a return
  * after idle days, low activity (the less active seat's games in the previous seven days at or below the corpus
  * median), a game with a known-constant identity, a day on which the roster changed; the one model-dependent segment
  * (a large forecast gap) is taken from each model's own probability.
  */
object RatingEvaluation:

  import RatingReplay.given

  /** Who sat at the two seats, from the corpus's recorded kinds. */
  enum Domain(val wireName: String):
    case BotBot     extends Domain("bot-bot")
    case HumanBot   extends Domain("human-bot")
    case HumanHuman extends Domain("human-human")
    case Other      extends Domain("other")

  object Domain:
    def of(whiteKind: String, blackKind: String): Domain =
      (whiteKind, blackKind) match
        case ("bot", "bot")                      => BotBot
        case ("human", "human")                  => HumanHuman
        case ("bot", "human") | ("human", "bot") => HumanBot
        case _                                   => Other

  /** Chronological windows by `finished_at`: training before `validationFrom`, validation before `testFrom`, test from
    * there to the corpus's end. UTC dates.
    */
  final case class Windows(validationFrom: LocalDate, testFrom: LocalDate) derives ConfiguredCodec:
    def of(day: LocalDate): String =
      if day.isBefore(validationFrom) then Windows.Training
      else if day.isBefore(testFrom) then Windows.Validation
      else Windows.Test

  object Windows:
    val Training   = "training"
    val Validation = "validation"
    val Test       = "test"
    val All        = List(Training, Validation, Test)

    /** The preregistered split for the 2026-09-10 corpus. */
    val Preregistered: Windows = Windows(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 9, 1))

  /** Model-independent segment names. */
  object Segment:
    val All           = "all"
    val Newcomer      = "newcomer"
    val Returning     = "returning"
    val LowActivity   = "low-activity"
    val HighActivity  = "high-activity"
    val ConstantBot   = "constant-bot"
    val LargeGap      = "large-gap"
    val PoolChangeDay = "pool-change-day"

    /** Games before which an identity is still a newcomer. */
    val NewcomerGames = 100

    /** Idle days before a game that make it a return. */
    val ReturningIdleDays = 3

    /** `|p − 0.5|` at or above which a forecast is a large gap. */
    val LargeGapMargin = 0.4

  /** One forecastable game with its model-independent tags. */
  final case class Tagged(
      observation: Observation,
      finishedAt: Instant,
      domain: Domain,
      window: String,
      newcomer: Boolean,
      returning: Boolean,
      lowActivity: Boolean,
      constantBot: Boolean,
      poolChangeDay: Boolean
  )

  final case class Prepared(games: Vector[Tagged], firstDay: LocalDate, days: Int, excludedRows: Int):
    def dateOf(day: Int): LocalDate = firstDay.plusDays(day.toLong)

  /** Filter, order and tag the corpus. `constants` are identities preregistered as constant-strength. */
  def prepare(rows: Seq[RatingReplay.Game], windows: Windows, constants: Set[String]): Prepared =
    val eligible = rows
      .filter(g => g.rated && g.result.isDefined && g.category.isDefined && g.white.id != g.black.id)
      .sortBy(g => (g.finishedAt, g.seqFinished))
    if eligible.isEmpty then Prepared(Vector.empty, LocalDate.EPOCH, 0, rows.size)
    else
      val firstDay                   = dateOf(eligible.head.finishedAt)
      def dayIndex(at: Instant): Int = ChronoUnit.DAYS.between(firstDay, dateOf(at)).toInt

      val firstSeen = mutable.HashMap.empty[String, Int]
      val lastSeen  = mutable.HashMap.empty[String, Int]
      eligible.foreach { g =>
        val day = dayIndex(g.finishedAt)
        List(g.white.id, g.black.id).foreach { id =>
          firstSeen.getOrElseUpdate(id, day)
          lastSeen.update(id, day)
        }
      }
      val poolChangeDays = firstSeen.values.toSet ++ lastSeen.values.map(_ + 1)

      val gamesSoFar                             = mutable.HashMap.empty[String, Int]
      val lastDay                                = mutable.HashMap.empty[String, Int]
      val perDay                                 = mutable.HashMap.empty[String, mutable.HashMap[Int, Int]]
      def recentGames(id: String, day: Int): Int =
        val counts = perDay.getOrElse(id, mutable.HashMap.empty[Int, Int])
        (day - 7 until day).map(d => counts.getOrElse(d, 0)).sum

      val tagged = Vector.newBuilder[(Tagged, Int)]
      eligible.foreach { g =>
        val day   = dayIndex(g.finishedAt)
        val score = g.result.get match
          case 1  => 1.0
          case -1 => 0.0
          case _  => 0.5
        val ids      = List(g.white.id, g.black.id)
        val activity = ids.map(recentGames(_, day)).min
        val entry    = Tagged(
          observation = Observation(day, g.category.get, g.white.id, g.black.id, score),
          finishedAt = g.finishedAt,
          domain = Domain.of(g.white.kind, g.black.kind),
          window = windows.of(dateOf(g.finishedAt)),
          newcomer = ids.exists(id => gamesSoFar.getOrElse(id, 0) < Segment.NewcomerGames),
          returning = ids.exists(id => lastDay.get(id).exists(last => day - last >= Segment.ReturningIdleDays)),
          lowActivity = false,
          constantBot = ids.exists(constants.contains),
          poolChangeDay = poolChangeDays.contains(day)
        )
        tagged += ((entry, activity))
        ids.foreach { id =>
          gamesSoFar.update(id, gamesSoFar.getOrElse(id, 0) + 1)
          lastDay.update(id, day)
          val counts = perDay.getOrElseUpdate(id, mutable.HashMap.empty)
          counts.update(day, counts.getOrElse(day, 0) + 1)
        }
      }
      val built      = tagged.result()
      val activities = built.map(_._2).sorted
      val median     = activities(activities.length / 2)
      val games      = built.map((entry, activity) => entry.copy(lowActivity = activity <= median))
      val lastIndex  = dayIndex(eligible.last.finishedAt)
      Prepared(games, firstDay, lastIndex + 1, rows.size - eligible.size)

  private def dateOf(at: Instant): LocalDate = at.atOffset(ZoneOffset.UTC).toLocalDate

  /** Which model to run, as the runner's `model=` vocabulary names it. */
  enum ModelSpec:
    case Glicko(spec: RatingModels.GlickoSpec)
    case BradleyTerry(spec: RatingModels.BradleyTerrySpec)
    case Whr(spec: RatingModels.WhrSpec)

    def build: Model = this match
      case Glicko(spec)       => RatingModels.GlickoModel(spec)
      case BradleyTerry(spec) => RatingModels.BradleyTerryModel(spec)
      case Whr(spec)          => RatingModels.WhrModel(spec)

  /** One model's forecasts over the prepared corpus, plus what the run cost and what the constants read at the start of
    * every day (for drift).
    */
  final case class Run(
      name: String,
      forecasts: Array[Double],
      computeMillis: Long,
      maxDayMillis: Long,
      constantsByDay: Map[Int, Map[String, Estimate]]
  )

  /** Drive one model chronologically over the prepared corpus. */
  def run(prepared: Prepared, spec: ModelSpec, constants: Set[String], category: String): Run =
    drive(prepared.games.map(_.observation), spec.build, constants, category)

  /** The chronological driver: day by day, forecast before observe, `constants` sampled at each day's start (and once
    * more after the last day, under the key `lastDay + 1`) for drift and coverage reporting.
    */
  def drive(observations: IndexedSeq[Observation], model: Model, constants: Set[String], category: String): Run =
    val forecasts        = new Array[Double](observations.length)
    val byDay            = mutable.Map.empty[Int, Map[String, Estimate]]
    val started          = System.nanoTime()
    var maxDay           = 0L
    var index            = 0
    var day              = -1
    def closeDay(): Unit = if day >= 0 then model.endDay(day)
    while index < observations.length do
      val observation = observations(index)
      val d           = observation.day
      if d != day then
        val dayStarted = System.nanoTime()
        closeDay()
        day = d
        model.startDay(day)
        byDay.update(day, constants.iterator.flatMap(id => model.estimate(id, category).map(id -> _)).toMap)
        maxDay = math.max(maxDay, (System.nanoTime() - dayStarted) / 1000000L)
      forecasts(index) = model.forecast(observation)
      model.observe(observation)
      index += 1
    closeDay()
    byDay.update(day + 1, constants.iterator.flatMap(id => model.estimate(id, category).map(id -> _)).toMap)
    Run(model.name, forecasts, (System.nanoTime() - started) / 1000000L, maxDay, byDay.toMap)

  // ── Scoring ───────────────────────────────────────────────────────────────────

  /** One calibration bin: forecasts with `lo ≤ p < hi`, their mean forecast and mean observed score. */
  final case class Bin(lo: Double, hi: Double, games: Long, meanForecast: Double, meanScore: Double)
      derives ConfiguredCodec

  final case class Metrics(
      games: Long,
      logLoss: Double,
      brier: Double,
      accuracy: Double,
      ece: Double,
      bins: List[Bin]
  ) derives ConfiguredCodec

  private val Bins    = 10
  private val Epsilon = 1e-6

  /** Cross-entropy of a forecast `p` against a score in {0, ½, 1}: the binary log loss, with a draw counted as half a
    * win and half a loss. Clipped so a saturated forecast cannot produce an infinite penalty.
    */
  def logLoss(p: Double, score: Double): Double =
    val q = math.min(1.0 - Epsilon, math.max(Epsilon, p))
    -(score * math.log(q) + (1.0 - score) * math.log(1.0 - q))

  def metrics(pairs: Iterable[(Double, Double)]): Metrics =
    var n       = 0L
    var ll      = 0.0
    var brier   = 0.0
    var correct = 0L
    val binN    = new Array[Long](Bins)
    val binP    = new Array[Double](Bins)
    val binS    = new Array[Double](Bins)
    pairs.foreach { (p, score) =>
      n += 1
      ll += logLoss(p, score)
      brier += (p - score) * (p - score)
      if (p >= 0.5) == (score >= 0.5) then correct += 1
      val bin = math.min(Bins - 1, math.max(0, (p * Bins).toInt))
      binN(bin) += 1
      binP(bin) += p
      binS(bin) += score
    }
    val bins = (0 until Bins).toList.map { b =>
      val count = binN(b)
      Bin(
        lo = b.toDouble / Bins,
        hi = (b + 1).toDouble / Bins,
        games = count,
        meanForecast = if count == 0 then 0.0 else binP(b) / count,
        meanScore = if count == 0 then 0.0 else binS(b) / count
      )
    }
    val ece = if n == 0 then 0.0 else bins.map(b => b.games.toDouble / n * math.abs(b.meanForecast - b.meanScore)).sum
    Metrics(
      games = n,
      logLoss = if n == 0 then 0.0 else ll / n,
      brier = if n == 0 then 0.0 else brier / n,
      accuracy = if n == 0 then 0.0 else correct.toDouble / n,
      ece = ece,
      bins = bins
    )

  /** Mean log-loss difference challenger − baseline with a day-block bootstrap interval: days are resampled with
    * replacement as units, because a day's games share the pool state and are not independent draws.
    */
  final case class Delta(games: Long, logLossDelta: Double, ciLow: Double, ciHigh: Double) derives ConfiguredCodec

  def bootstrapDelta(
      challenger: Array[Double],
      baseline: Array[Double],
      games: Vector[Tagged],
      indices: Seq[Int],
      resamples: Int,
      seed: Long
  ): Delta =
    val byDay = indices.groupBy(i => games(i).observation.day).toVector.sortBy(_._1)
    val sums  = byDay.map { (_, ids) =>
      ids.foldLeft((0.0, 0L)) { case ((acc, count), i) =>
        val s = games(i).observation.whiteScore
        (acc + logLoss(challenger(i), s) - logLoss(baseline(i), s), count + 1)
      }
    }
    val total   = sums.map(_._2).sum
    val overall = if total == 0 then 0.0 else sums.map(_._1).sum / total
    val rng     = new scala.util.Random(seed)
    val draws   = Vector
      .fill(resamples) {
        var acc   = 0.0
        var count = 0L
        var k     = 0
        while k < sums.length do
          val (s, c) = sums(rng.nextInt(sums.length))
          acc += s
          count += c
          k += 1
        if count == 0 then 0.0 else acc / count
      }
      .sorted
    def percentile(p: Double): Double =
      if draws.isEmpty then overall
      else draws(math.min(draws.length - 1, math.max(0, (p * (draws.length - 1)).round.toInt)))
    Delta(total, overall, percentile(0.025), percentile(0.975))

  // ── Report ────────────────────────────────────────────────────────────────────

  final case class Drift(from: Double, to: Double) derives ConfiguredCodec:
    def delta: Double = to - from

  final case class ModelReport(
      name: String,
      computeMillis: Long,
      maxDayMillis: Long,
      /** window → domain → segment → metrics */
      metrics: Map[String, Map[String, Map[String, Metrics]]],
      /** window → domain → delta vs the baseline (absent for the baseline itself) */
      versusBaseline: Map[String, Map[String, Delta]],
      /** window → constant identity → rating drift from the window's first day to its end */
      drift: Map[String, Map[String, Drift]]
  ) derives ConfiguredCodec

  final case class Report(
      windows: Windows,
      firstDay: LocalDate,
      days: Int,
      forecastGames: Int,
      excludedRows: Int,
      constants: List[String],
      reportedWindows: List[String],
      bootstrapResamples: Int,
      seed: Long,
      provenance: Option[RatingReplay.Provenance] = None,
      models: List[ModelReport] = Nil,
      synthetic: Option[RatingEvaluationFixture.Summary] = None
  ) derives ConfiguredCodec

  final case class Config(
      windows: Windows = Windows.Preregistered,
      constants: Set[String] = Set.empty,
      category: String = "blitz",
      reportedWindows: List[String] = List(Windows.Training, Windows.Validation),
      bootstrapResamples: Int = 1000,
      seed: Long = 42L
  )

  /** Run every spec (the first is the baseline) and assemble the report over the requested windows only — the test
    * window stays unreported until the runner is told to report it, which is how the preregistration's single look at
    * the test interval is enforced operationally.
    */
  def evaluate(rows: Seq[RatingReplay.Game], specs: List[ModelSpec], config: Config): Report =
    val prepared = prepare(rows, config.windows, config.constants)
    val runs     = specs.map(run(prepared, _, config.constants, config.category))
    val baseline = runs.headOption
    val models   = runs.map(r => modelReport(prepared, r, baseline.filter(_ ne r), config))
    Report(
      windows = config.windows,
      firstDay = prepared.firstDay,
      days = prepared.days,
      forecastGames = prepared.games.length,
      excludedRows = prepared.excludedRows,
      constants = config.constants.toList.sorted,
      reportedWindows = config.reportedWindows,
      bootstrapResamples = config.bootstrapResamples,
      seed = config.seed,
      models = models
    )

  private def modelReport(prepared: Prepared, run: Run, baseline: Option[Run], config: Config): ModelReport =
    val games      = prepared.games
    val byWindow   = games.indices.groupBy(i => games(i).window).filter((w, _) => config.reportedWindows.contains(w))
    val metricsMap = byWindow.map { (window, indices) =>
      val byDomain = indices.groupBy(i => games(i).domain.wireName).map { (domain, ids) =>
        domain -> segmentMetrics(games, run.forecasts, ids)
      }
      window -> byDomain
    }
    val deltas = baseline.fold(Map.empty[String, Map[String, Delta]]) { base =>
      byWindow.map { (window, indices) =>
        window -> indices.groupBy(i => games(i).domain.wireName).map { (domain, ids) =>
          domain -> bootstrapDelta(run.forecasts, base.forecasts, games, ids, config.bootstrapResamples, config.seed)
        }
      }
    }
    val drift = byWindow.map { (window, indices) =>
      val first = indices.map(i => games(i).observation.day).min
      val after = indices.map(i => games(i).observation.day).max + 1
      val start = run.constantsByDay.getOrElse(first, Map.empty)
      val end   = run.constantsByDay.getOrElse(after, Map.empty)
      window -> config.constants.toList.sorted.flatMap { id =>
        (start.get(id), end.get(id)).mapN((s, e) => id -> Drift(s.rating, e.rating))
      }.toMap
    }
    ModelReport(run.name, run.computeMillis, run.maxDayMillis, metricsMap, deltas, drift)

  private def segmentMetrics(games: Vector[Tagged], forecasts: Array[Double], ids: Seq[Int]): Map[String, Metrics] =
    def of(selected: Seq[Int]): Metrics =
      metrics(selected.view.map(i => (forecasts(i), games(i).observation.whiteScore)))
    Map(
      Segment.All           -> of(ids),
      Segment.Newcomer      -> of(ids.filter(i => games(i).newcomer)),
      Segment.Returning     -> of(ids.filter(i => games(i).returning)),
      Segment.LowActivity   -> of(ids.filter(i => games(i).lowActivity)),
      Segment.HighActivity  -> of(ids.filter(i => !games(i).lowActivity)),
      Segment.ConstantBot   -> of(ids.filter(i => games(i).constantBot)),
      Segment.LargeGap      -> of(ids.filter(i => math.abs(forecasts(i) - 0.5) >= Segment.LargeGapMargin)),
      Segment.PoolChangeDay -> of(ids.filter(i => games(i).poolChangeDay))
    )

  // ── Text rendering ────────────────────────────────────────────────────────────

  private def line(pattern: String, args: Any*): String =
    String.format(java.util.Locale.ROOT, pattern, args.map(_.asInstanceOf[Object])*)

  def render(report: Report): String =
    val header = List(
      "=== Rating model evaluation — chronological forecasts (#148) ===",
      line(
        "corpus: %d forecast games over %d days from %s (excluded rows: %d); windows: validation from %s, test from %s",
        report.forecastGames,
        report.days,
        report.firstDay,
        report.excludedRows,
        report.windows.validationFrom,
        report.windows.testFrom
      ),
      line(
        "reported windows: %s; constants: %s; bootstrap: %d day-block resamples, seed %d",
        report.reportedWindows.mkString(", "),
        if report.constants.isEmpty then "none" else report.constants.mkString(", "),
        report.bootstrapResamples,
        report.seed
      ),
      report.provenance.fold("")(p =>
        line("provenance: %s sha256 %s; %s; %s", p.corpusFile, p.corpusSha256, p.javaRuntime, p.osArch)
      ),
      ""
    )
    val sections = report.reportedWindows.flatMap(window => renderWindow(report, window))
    val compute  = "--- compute ---" :: report.models.map(m =>
      line("%-40s total %6d ms, slowest day %5d ms", m.name, m.computeMillis, m.maxDayMillis)
    )
    val synth = report.synthetic.fold(Nil)(RatingEvaluationFixture.render)
    (header ++ sections ++ compute ++ synth).mkString("\n")

  private def renderWindow(report: Report, window: String): List[String] =
    val domains = report.models.flatMap(_.metrics.getOrElse(window, Map.empty).keys).distinct.sorted
    domains.flatMap { domain =>
      val title = s"--- $window / $domain ---"
      val head  = line(
        "%-40s %8s %9s %8s %7s %7s  %s",
        "model",
        "games",
        "log loss",
        "brier",
        "acc",
        "ece",
        "Δ vs baseline [95% CI]"
      )
      val rows = report.models.flatMap { m =>
        m.metrics.get(window).flatMap(_.get(domain)).map { segments =>
          val all   = segments(Segment.All)
          val delta = m.versusBaseline
            .get(window)
            .flatMap(_.get(domain))
            .fold("baseline")(d => line("%+.4f [%+.4f, %+.4f]", d.logLossDelta, d.ciLow, d.ciHigh))
          line(
            "%-40s %8d %9.4f %8.4f %7.3f %7.3f  %s",
            m.name,
            all.games,
            all.logLoss,
            all.brier,
            all.accuracy,
            all.ece,
            delta
          )
        }
      }
      val segmentLines = report.models.flatMap { m =>
        m.metrics.get(window).flatMap(_.get(domain)).toList.flatMap { segments =>
          segments.toList.filter((name, s) => name != Segment.All && s.games > 0).sortBy(_._1).map { (name, s) =>
            line("    %-36s %-16s %8d %9.4f %8.4f %7.3f", m.name, name, s.games, s.logLoss, s.brier, s.ece)
          }
        }
      }
      val driftLines = report.models.flatMap { m =>
        m.drift.get(window).toList.flatMap(_.toList.sortBy(_._1)).map { (id, d) =>
          line("    %-36s drift %-28s %+7.1f (%.1f → %.1f)", m.name, id, d.delta, d.from, d.to)
        }
      }
      (title :: head :: rows) ++ (if segmentLines.isEmpty then Nil
                                  else "  segments (model, segment, games, log loss, brier, ece):" :: segmentLines) ++
        (if driftLines.isEmpty then Nil
         else "  constant identities, rating at window start → end:" :: driftLines) ++ List("")
    }
