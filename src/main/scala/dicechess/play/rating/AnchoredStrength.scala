package dicechess.play.rating

import cats.syntax.all.*
import dicechess.play.rating.BradleyTerry.{FitFailure, FitResult}
import dicechess.play.rating.GraphConnectivity.AdmissionStatus

import scala.collection.mutable
import scala.util.Random

/** Anchored, time-aware bot strength estimator with immutable scale anchors (#147, ADR 008, #170).
  *
  * Unlike static pool Bradley–Terry which recenters to mean=0 on every pool change and averages across the bot's entire
  * lifetime, AnchoredStrength:
  *   1. Validates real comparison graph connectivity (Weakly Connected Components) against the versioned anchor set.
  *   2. Exposes insufficient/disconnected evidence with explicit statuses (`Provisional`, `Disconnected`) rather than
  *      inventing global ranks.
  *   3. Pins the reference scale to an [[AnchorSet]] (e.g. `AnchorSet.V1_0`), ensuring that additions or departures of
  *      unrelated participants do not shift the ratings of established bots or anchors.
  *   4. Preserves CRN pairing groups during bootstrap resampling to produce honest 95% confidence intervals and
  *      neighbour LOS.
  */
object AnchoredStrength:

  val ModelVersion = "anchored-bt-color-ci-v2"
  // At least one observation in each 2.5% percentile tail; this is a resolution guard, not a coverage claim.
  private[rating] val MinimumBootstrapSamples = 40

  type Game = BradleyTerry.Game

  final case class Config(
      anchorSet: AnchorSet = AnchorSet.Default,
      minGamesForAdmission: Int = GraphConnectivity.DefaultMinGames,
      minOpponentsForAdmission: Int = GraphConnectivity.DefaultMinOpponents,
      bootstrapIterations: Int = 1000,
      seed: Long = 42L,
      whiteAdvantageElo: Double = 25.0,
      maxCiWidthElo: Option[Double] = None
  ):
    require(minGamesForAdmission > 0 && minOpponentsForAdmission > 0, "admission counts must be positive")
    require(
      anchorSet.anchors.forall(a => a.targetElo.isFinite && a.weight.isFinite && a.weight > 0.0),
      "anchor targets and positive weights must be finite"
    )
    require(
      anchorSet.anchors.map(_.identity).distinct.size == anchorSet.anchors.size,
      "anchor identities must be unique"
    )
    require(bootstrapIterations >= 0, "bootstrapIterations must be non-negative")
    private val colorStrength = math.pow(10.0, whiteAdvantageElo / 400.0)
    require(
      whiteAdvantageElo.isFinite && colorStrength.isFinite && colorStrength > 0.0,
      "whiteAdvantageElo must have a finite, positive strength multiplier"
    )
    require(maxCiWidthElo.forall(w => w.isFinite && w > 0.0), "maxCiWidthElo must be finite and positive")

  object Config:
    val Default: Config = Config()

  final case class RankedBot(
      player: String,
      elo: Double,
      ciLow: Double,
      ciHigh: Double,
      losVsNext: Option[Double],
      status: AdmissionStatus,
      isAnchor: Boolean,
      games: Int,
      opponents: Int,
      validBootstrapSamples: Int = 0
  )

  final case class Result(
      epoch: Int,
      anchorSetVersion: String,
      admitted: List[RankedBot],
      provisional: List[RankedBot],
      disconnected: List[RankedBot],
      connectivity: GraphConnectivity.Result,
      appliedOffset: Option[Double] = None,
      modelVersion: String = ModelVersion,
      whiteAdvantageElo: Double = Config.Default.whiteAdvantageElo,
      maxCiWidthElo: Option[Double] = None,
      bootstrapIterations: Int = 0,
      calibrationOffsets: Map[String, Double] = Map.empty,
      seed: Long = Config.Default.seed,
      minGamesForAdmission: Int = Config.Default.minGamesForAdmission,
      minOpponentsForAdmission: Int = Config.Default.minOpponentsForAdmission
  ):
    val isCalibrated: Boolean = appliedOffset.isDefined || calibrationOffsets.nonEmpty

    /** Admitted ranking followed by alphabetically ordered provisional estimates. */
    val allConnected: List[RankedBot] = admitted ++ provisional

  /** Fits the anchored strength model over the given observation groups.
    *
    * @param groups
    *   Observation units (a CRN mirror pair = one Seq[Game], a single game = one Seq[Game]).
    * @param config
    *   Configuration specifying the AnchorSet, thresholds, iterations, and seed.
    */
  def evaluate(
      groups: Seq[Seq[Game]],
      config: Config = Config.Default
  ): FitResult[Result] =
    val valid = groups.flatten.forall((w, b, score) => w != b && score.isFinite && score >= 0.0 && score <= 1.0)
    Either
      .cond(valid, (), FitFailure("observations must have distinct participants and finite scores in [0, 1]"))
      .flatMap(_ => evaluateValid(groups, config))

  final private case class BootstrapEvidence(
      lows: Map[String, Double],
      highs: Map[String, Double],
      samples: Map[String, Array[Double]]
  ):
    def finiteSamples(player: String): Int = samples.get(player).fold(0)(_.count(_.isFinite))

  final private case class RankingContext(
      config: Config,
      connectivity: GraphConnectivity.Result,
      ratings: Map[String, Double],
      bootstrap: BootstrapEvidence
  )

  private def evaluateValid(groups: Seq[Seq[Game]], config: Config): FitResult[Result] =
    val flatGames    = groups.flatten
    val connectivity = GraphConnectivity.analyze(
      flatGames.map(g => (g._1, g._2)),
      config.anchorSet.identities,
      minGames = config.minGamesForAdmission,
      minOpponents = config.minOpponentsForAdmission
    )
    // Only groups whose games remain on the anchor-connected comparison graph feed the anchored scale.
    val connectedGroups = groups.filter { group =>
      group.forall(g =>
        connectivity.anchorConnectedPlayers.contains(g._1) && connectivity.anchorConnectedPlayers.contains(g._2)
      )
    }
    if connectedGroups.isEmpty then Right(makeResult(config, connectivity, Nil, Nil, Map.empty, None))
    else
      fitComponents(connectedGroups.flatten, config).map { (base, offsets) =>
        assembleResult(connectedGroups, config, connectivity, base, offsets)
      }

  private def assembleResult(
      groups: Seq[Seq[Game]],
      config: Config,
      connectivity: GraphConnectivity.Result,
      base: Map[String, Double],
      offsets: Map[String, Double]
  ): Result =
    val scaleOffset            = if connectivity.components.size == 1 then offsets.values.headOption else None
    val (lows, highs, samples) = computeBootstrap(groups, config)
    val bootstrap              = BootstrapEvidence(lows, highs, samples)
    val statuses               = connectivity.statuses.map { (player, graphStatus) =>
      player -> admissionStatus(player, graphStatus, config, connectivity, offsets, bootstrap)
    }
    val finalConnectivity = connectivity.copy(statuses = statuses)
    val context           = RankingContext(config, finalConnectivity, base, bootstrap)
    val admittedPlayers   = connectivity.anchorConnectedPlayers
      .filter(p => base.contains(p) && finalConnectivity.isAdmitted(p))
      .toList
      .sortBy(p => (-base(p), p))
    val admitted = admittedPlayers.zipWithIndex.map { (player, index) =>
      rankedBot(player, admittedPlayers.lift(index + 1), context)
    }
    val provisional = connectivity.anchorConnectedPlayers
      .filter(p => base.contains(p) && finalConnectivity.isProvisional(p))
      .toList
      .sorted
      .map(player => rankedBot(player, None, context))
    makeResult(config, finalConnectivity, admitted, provisional, offsets, scaleOffset)

  private def admissionStatus(
      player: String,
      graphStatus: AdmissionStatus,
      config: Config,
      connectivity: GraphConnectivity.Result,
      offsets: Map[String, Double],
      bootstrap: BootstrapEvidence
  ): AdmissionStatus =
    val games     = connectivity.gameCounts.getOrElse(player, 0)
    val opponents = connectivity.opponentCounts.getOrElse(player, 0)
    val samples   = bootstrap.finiteSamples(player)
    if graphStatus == AdmissionStatus.Disconnected then graphStatus
    else if offsets.contains(player) && games >= config.minGamesForAdmission &&
      opponents >= config.minOpponentsForAdmission &&
      intervalAdmits(bootstrap.lows.get(player), bootstrap.highs.get(player), samples, config)
    then AdmissionStatus.Admitted
    else AdmissionStatus.Provisional(games, opponents)

  private def rankedBot(player: String, nextPlayer: Option[String], context: RankingContext): RankedBot =
    val los = nextPlayer.flatMap { next =>
      context.bootstrap.samples
        .get(player)
        .zip(context.bootstrap.samples.get(next))
        .flatMap((a, b) => neighbourLos(a, b))
    }
    RankedBot(
      player = player,
      elo = context.ratings.getOrElse(player, 0.0),
      ciLow = context.bootstrap.lows.getOrElse(player, Double.NaN),
      ciHigh = context.bootstrap.highs.getOrElse(player, Double.NaN),
      losVsNext = los,
      status = context.connectivity.statuses.getOrElse(player, AdmissionStatus.Disconnected),
      isAnchor = context.config.anchorSet.identities.contains(player),
      games = context.connectivity.gameCounts.getOrElse(player, 0),
      opponents = context.connectivity.opponentCounts.getOrElse(player, 0),
      validBootstrapSamples = context.bootstrap.finiteSamples(player)
    )

  private def neighbourLos(a: Array[Double], b: Array[Double]): Option[Double] =
    val n     = math.min(a.length, b.length)
    var wins  = 0
    var total = 0
    var i     = 0
    while i < n do
      val first  = a(i)
      val second = b(i)
      if first.isFinite && second.isFinite then
        total += 1
        if first > second then wins += 1
      i += 1
    if total == 0 then None else Some(wins.toDouble / total)

  private def disconnectedRows(config: Config, connectivity: GraphConnectivity.Result): List[RankedBot] =
    connectivity.disconnectedPlayers.toList.sorted.map { player =>
      RankedBot(
        player = player,
        elo = 0.0,
        ciLow = 0.0,
        ciHigh = 0.0,
        losVsNext = None,
        status = AdmissionStatus.Disconnected,
        isAnchor = config.anchorSet.identities.contains(player),
        games = connectivity.gameCounts.getOrElse(player, 0),
        opponents = connectivity.opponentCounts.getOrElse(player, 0)
      )
    }

  private def makeResult(
      config: Config,
      connectivity: GraphConnectivity.Result,
      admitted: List[RankedBot],
      provisional: List[RankedBot],
      offsets: Map[String, Double],
      scaleOffset: Option[Double]
  ): Result =
    Result(
      epoch = config.anchorSet.epoch,
      anchorSetVersion = config.anchorSet.version,
      admitted = admitted,
      provisional = provisional,
      disconnected = disconnectedRows(config, connectivity),
      connectivity = connectivity,
      appliedOffset = scaleOffset,
      whiteAdvantageElo = config.whiteAdvantageElo,
      maxCiWidthElo = config.maxCiWidthElo,
      bootstrapIterations = config.bootstrapIterations,
      calibrationOffsets = offsets,
      seed = config.seed,
      minGamesForAdmission = config.minGamesForAdmission,
      minOpponentsForAdmission = config.minOpponentsForAdmission
    )

  private def fitComponents(games: Seq[Game], config: Config): FitResult[(Map[String, Double], Map[String, Double])] =
    val graph       = GraphConnectivity.analyze(games.map(g => (g._1, g._2)), config.anchorSet.identities)
    val componentOf = graph.components.zipWithIndex.flatMap((players, i) => players.map(_ -> i)).toMap
    val ratings     = mutable.Map.empty[String, Double]
    val offsets     = mutable.Map.empty[String, Double]
    games
      .groupBy(g => componentOf(g._1))
      .toList
      .sortBy(_._1)
      .traverse_ { (_, componentGames) =>
        BradleyTerry.ratings(componentGames, config.whiteAdvantageElo).map { raw =>
          val offset = config.anchorSet.scaleOffset(raw).filter(_.isFinite)
          raw.foreach { (player, elo) =>
            ratings(player) = elo - offset.getOrElse(0.0)
            offset.foreach(o => offsets(player) = o)
          }
        }
      }
      .map(_ => (ratings.toMap, offsets.toMap))

  /** Runs bootstrap resampling over the observation units. */
  private def computeBootstrap(
      groups: Seq[Seq[Game]],
      config: Config
  ): (Map[String, Double], Map[String, Double], Map[String, Array[Double]]) =
    val groupArray = groups.toArray
    val groupCount = groupArray.length
    if groupCount == 0 || config.bootstrapIterations <= 0 then (Map.empty, Map.empty, Map.empty)
    else
      val rng     = new Random(config.seed)
      val players = groups.flatten.flatMap(g => List(g._1, g._2)).distinct.sorted

      // Reusable accumulator per player for bootstrap samples:
      // Replicate order is load-bearing: LOS pairs player samples by iteration index.
      val playerReplicates = mutable.Map.empty[String, Array[Double]]
      players.foreach(p => playerReplicates(p) = Array.fill(config.bootstrapIterations)(Double.NaN))

      var b = 0
      while b < config.bootstrapIterations do
        // Draw groups with replacement
        val sampleGroups = new Array[Seq[Game]](groupCount)
        var i            = 0
        while i < groupCount do
          sampleGroups(i) = groupArray(rng.nextInt(groupCount))
          i += 1

        val sampleGames              = sampleGroups.flatten.toSeq
        val (sampleRatings, offsets) = fitComponents(sampleGames, config)
          .getOrElse((Map.empty[String, Double], Map.empty[String, Double]))
        // A lost anchor/bridge cannot be replaced with the base offset or a smoothing-created comparison.
        sampleRatings.foreach { case (player, elo) =>
          if offsets.contains(player) && elo.isFinite then playerReplicates(player)(b) = elo
        }
        b += 1

      def percentile(sorted: Array[Double], p: Double): Double =
        if sorted.isEmpty then 0.0
        else sorted(math.min(sorted.length - 1, math.max(0, math.round(p * (sorted.length - 1)).toInt)))

      val lowsMap  = mutable.Map.empty[String, Double]
      val highsMap = mutable.Map.empty[String, Double]

      players.foreach { p =>
        val present = playerReplicates(p).filter(_.isFinite)
        val sorted  = present.sorted
        if sorted.length >= MinimumBootstrapSamples && sorted.length == config.bootstrapIterations then
          lowsMap(p) = percentile(sorted, 0.025)
          highsMap(p) = percentile(sorted, 0.975)
      }

      (lowsMap.toMap, highsMap.toMap, playerReplicates.toMap)

  /** Missing/conditional bootstrap evidence never grants admission. The width policy is operator supplied; absent
    * policy means provisional. All requested replicates must be on the calibrated comparison graph.
    */
  private[rating] def intervalAdmits(low: Option[Double], high: Option[Double], samples: Int, config: Config): Boolean =
    samples >= MinimumBootstrapSamples && samples == config.bootstrapIterations &&
      (for
        lo    <- low
        hi    <- high
        limit <- config.maxCiWidthElo
      yield lo.isFinite && hi.isFinite && lo <= hi && (hi - lo).isFinite && hi - lo <= limit).getOrElse(false)
