package dicechess.play.rating

import dicechess.play.rating.RatingEvaluation.ModelSpec
import dicechess.play.rating.RatingModels.Observation
import io.circe.derivation.ConfiguredCodec

import scala.util.Random

/** Synthetic stationary corpora for the confidence-coverage and drift checks the preregistration asks for (#148): a
  * pool of identities of KNOWN, constant strength, random pairings every day, dice-like outcomes drawn from the Elo win
  * probability, and a roster change midway — newcomers arrive, veterans leave — with nobody's strength moving. Because
  * the truth is known, the run can ask what no production corpus can answer: how often a model's nominal 95 % interval
  * covers the true (pool-centred) strength, and how far the ratings of unchanged identities move when the pool around
  * them changes. No production data anywhere in here.
  */
object RatingEvaluationFixture:

  import RatingReplay.given

  final case class Bot(id: String, elo: Double)

  final case class Scenario(
      name: String,
      bots: Vector[Bot],
      days: Int,
      gamesPerDay: Int,
      /** day → identities that start playing that day */
      arrivals: Map[Int, Vector[Bot]] = Map.empty,
      /** day → identities whose last game was the day before */
      departures: Map[Int, Set[String]] = Map.empty
  ):
    def everyone: Vector[Bot] = bots ++ arrivals.values.flatten

    /** The first day the roster changes, or the middle of the run when it never does — the drift reference day. */
    def changeDay: Int = (arrivals.keys ++ departures.keys).minOption.getOrElse(days / 2)

  /** The preregistered stationary scenario: eight constant bots spread over 800 Elo, three strong newcomers on day 20
    * and two departures on day 25, forty days of three hundred games.
    */
  val Stationary: Scenario = Scenario(
    name = "stationary-pool-change",
    bots = Vector(
      Bot("bot:team:fx:a", 1300),
      Bot("bot:team:fx:b", 1450),
      Bot("bot:team:fx:c", 1550),
      Bot("bot:team:fx:d", 1650),
      Bot("bot:team:fx:e", 1750),
      Bot("bot:team:fx:f", 1850),
      Bot("bot:team:fx:g", 1950),
      Bot("bot:team:fx:h", 2100)
    ),
    days = 40,
    gamesPerDay = 300,
    arrivals = Map(20 -> Vector(Bot("bot:team:fx:n1", 2000), Bot("bot:team:fx:n2", 2150), Bot("bot:team:fx:n3", 2300))),
    departures = Map(25 -> Set("bot:team:fx:b", "bot:team:fx:d"))
  )

  val Category = "blitz"

  /** Random pairings among the identities active on each day, outcomes drawn from the Elo probability. */
  def simulate(scenario: Scenario, seed: Long): Vector[Observation] =
    val rng    = new Random(seed)
    val active = scala.collection.mutable.ArrayBuffer.from(scenario.bots)
    val out    = Vector.newBuilder[Observation]
    var day    = 0
    while day < scenario.days do
      scenario.arrivals.get(day).foreach(active ++= _)
      scenario.departures.get(day).foreach(gone => active.filterInPlace(b => !gone.contains(b.id)))
      var g = 0
      while g < scenario.gamesPerDay && active.length >= 2 do
        val i = rng.nextInt(active.length)
        var j = rng.nextInt(active.length - 1)
        if j >= i then j += 1
        val white = active(i)
        val black = active(j)
        val p     = RatingModels.eloProbability(white.elo, black.elo)
        val score = if rng.nextDouble() < p then 1.0 else 0.0
        out += Observation(day, Category, white.id, black.id, score)
        g += 1
      day += 1
    out.result()

  /** One model's coverage over all seeds: how many pool-centred true strengths its nominal 95 % interval covered
    * (`withInterval` is the number of estimates that carried an interval at all), the mean absolute error of the
    * centred estimates, and the largest rating move of an identity that was present, unchanged, from before the roster
    * change to the end.
    */
  final case class ModelCoverage(
      model: String,
      estimates: Long,
      withInterval: Long,
      covered: Long,
      meanAbsError: Double,
      maxConstantDrift: Double,
      meanConstantDrift: Double
  ) derives ConfiguredCodec:
    def coverage: Option[Double] = Option.when(withInterval > 0)(covered.toDouble / withInterval)

  final case class Summary(scenario: String, seeds: Int, models: List[ModelCoverage]) derives ConfiguredCodec

  private val Z95 = 1.959964

  def evaluate(scenario: Scenario, specs: List[ModelSpec], seeds: Range): Summary =
    val everyone = scenario.everyone
    val ids      = everyone.map(_.id).toSet
    val eloOf    = everyone.map(b => b.id -> b.elo).toMap
    val gone     = scenario.departures.values.flatten.toSet
    val veterans = scenario.bots.map(_.id).filterNot(gone.contains)
    val perModel = specs.map { spec =>
      var estimates = 0L
      var withSd    = 0L
      var covered   = 0L
      var absError  = 0.0
      var driftMax  = 0.0
      var driftSum  = 0.0
      var driftN    = 0L
      var name      = ""
      seeds.foreach { seed =>
        val run = RatingEvaluation.drive(simulate(scenario, seed.toLong), spec.build, ids, Category)
        name = run.name
        val finalDay = run.constantsByDay.keys.max
        val end      = run.constantsByDay(finalDay).filter((id, _) => !gone.contains(id))
        if end.nonEmpty then
          val estMean  = end.values.map(_.rating).sum / end.size
          val trueMean = end.keys.map(eloOf).sum / end.size
          end.foreach { (id, estimate) =>
            val error = (estimate.rating - estMean) - (eloOf(id) - trueMean)
            estimates += 1
            absError += math.abs(error)
            estimate.sd.foreach { sd =>
              withSd += 1
              if math.abs(error) <= Z95 * sd then covered += 1
            }
          }
        val before = run.constantsByDay.getOrElse(scenario.changeDay, Map.empty)
        veterans.foreach { id =>
          for
            b <- before.get(id)
            e <- end.get(id)
          do
            val d = math.abs(e.rating - b.rating)
            driftMax = math.max(driftMax, d)
            driftSum += d
            driftN += 1
        }
      }
      ModelCoverage(
        model = name,
        estimates = estimates,
        withInterval = withSd,
        covered = covered,
        meanAbsError = if estimates == 0 then 0.0 else absError / estimates,
        maxConstantDrift = driftMax,
        meanConstantDrift = if driftN == 0 then 0.0 else driftSum / driftN
      )
    }
    Summary(scenario.name, seeds.size, perModel)

  def render(summary: Summary): List[String] =
    val head = List(
      "",
      s"--- synthetic coverage: ${summary.scenario}, ${summary.seeds} seeds ---",
      String.format(
        java.util.Locale.ROOT,
        "%-40s %9s %9s %9s %10s %11s",
        "model",
        "estimates",
        "coverage",
        "mean|err|",
        "max drift",
        "mean drift"
      )
    )
    head ++ summary.models.map { m =>
      String.format(
        java.util.Locale.ROOT,
        "%-40s %9d %9s %9.1f %10.1f %11.1f",
        m.model,
        m.estimates,
        m.coverage.fold("n/a")(c => f"${c * 100}%.1f%%"),
        m.meanAbsError,
        m.maxConstantDrift,
        m.meanConstantDrift
      )
    }
