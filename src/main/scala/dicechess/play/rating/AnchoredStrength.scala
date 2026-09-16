package dicechess.play.rating

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

  type Game = BradleyTerry.Game

  final case class Config(
      anchorSet: AnchorSet = AnchorSet.Default,
      minGamesForAdmission: Int = GraphConnectivity.DefaultMinGames,
      minOpponentsForAdmission: Int = GraphConnectivity.DefaultMinOpponents,
      bootstrapIterations: Int = 1000,
      seed: Long = 42L,
      whiteAdvantageElo: Double = 25.0
  )

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
      opponents: Int
  )

  final case class Result(
      epoch: Int,
      anchorSetVersion: String,
      admitted: List[RankedBot],
      provisional: List[RankedBot],
      disconnected: List[RankedBot],
      connectivity: GraphConnectivity.Result
  ):
    /** Combined ranking of admitted followed by provisional bots. */
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
  ): Result =
    val flatGames    = groups.flatten
    val connectivity = GraphConnectivity.analyze(
      flatGames.map(g => (g._1, g._2)),
      config.anchorSet.identities,
      minGames = config.minGamesForAdmission,
      minOpponents = config.minOpponentsForAdmission
    )

    // Separate games: only games between anchor-connected players feed the main anchored scale
    val connectedGroups = groups.filter { group =>
      group.forall(g =>
        connectivity.anchorConnectedPlayers.contains(g._1) && connectivity.anchorConnectedPlayers.contains(g._2)
      )
    }

    if connectedGroups.isEmpty then
      Result(
        epoch = config.anchorSet.epoch,
        anchorSetVersion = config.anchorSet.version,
        admitted = Nil,
        provisional = Nil,
        disconnected = connectivity.disconnectedPlayers.toList.sorted.map { p =>
          RankedBot(
            player = p,
            elo = 0.0,
            ciLow = 0.0,
            ciHigh = 0.0,
            losVsNext = None,
            status = AdmissionStatus.Disconnected,
            isAnchor = config.anchorSet.identities.contains(p),
            games = connectivity.gameCounts.getOrElse(p, 0),
            opponents = connectivity.opponentCounts.getOrElse(p, 0)
          )
        },
        connectivity = connectivity
      )
    else
      // 1. Raw Bradley-Terry fit over the connected component
      val rawBaseRatings = BradleyTerry.ratings(connectedGroups.flatten)

      // 2. Compute anchor calibration offset
      val scaleOffset = config.anchorSet.scaleOffset(rawBaseRatings).getOrElse(0.0)

      val anchoredBase = rawBaseRatings.map { case (player, rawElo) =>
        player -> (rawElo - scaleOffset)
      }

      // 3. Bootstrap resampling for 95% CIs and LOS
      val (ciLows, ciHighs, sampleValues) = computeBootstrap(connectedGroups, config, scaleOffset)

      // 4. Build RankedBot entries
      def makeRanked(player: String, nextPlayer: Option[String]): RankedBot =
        val elo     = anchoredBase.getOrElse(player, 0.0)
        val ciLow   = ciLows.getOrElse(player, elo)
        val ciHigh  = ciHighs.getOrElse(player, elo)
        val samples = sampleValues.getOrElse(player, Vector.empty)

        val los = nextPlayer.flatMap { next =>
          val nextSamples = sampleValues.getOrElse(next, Vector.empty)
          val pairs       = samples.zip(nextSamples)
          if pairs.isEmpty then None
          else Some(pairs.count((a, b) => a > b).toDouble / pairs.size)
        }

        RankedBot(
          player = player,
          elo = elo,
          ciLow = ciLow,
          ciHigh = ciHigh,
          losVsNext = los,
          status = connectivity.statuses.getOrElse(player, AdmissionStatus.Disconnected),
          isAnchor = config.anchorSet.identities.contains(player),
          games = connectivity.gameCounts.getOrElse(player, 0),
          opponents = connectivity.opponentCounts.getOrElse(player, 0)
        )

      // Split into Admitted and Provisional
      val admittedPlayers = connectivity.anchorConnectedPlayers
        .filter(p => anchoredBase.contains(p) && connectivity.isAdmitted(p))
        .toList
        .sortBy(p => -anchoredBase(p))

      val admittedRanked = admittedPlayers.zipWithIndex.map { case (p, idx) =>
        makeRanked(p, admittedPlayers.lift(idx + 1))
      }

      val provisionalPlayers = connectivity.anchorConnectedPlayers
        .filter(p => anchoredBase.contains(p) && connectivity.isProvisional(p))
        .toList
        .sortBy(p => -anchoredBase(p))

      val provisionalRanked = provisionalPlayers.zipWithIndex.map { case (p, idx) =>
        makeRanked(p, provisionalPlayers.lift(idx + 1))
      }

      val disconnectedRanked = connectivity.disconnectedPlayers.toList.sorted.map { p =>
        RankedBot(
          player = p,
          elo = 0.0,
          ciLow = 0.0,
          ciHigh = 0.0,
          losVsNext = None,
          status = AdmissionStatus.Disconnected,
          isAnchor = config.anchorSet.identities.contains(p),
          games = connectivity.gameCounts.getOrElse(p, 0),
          opponents = connectivity.opponentCounts.getOrElse(p, 0)
        )
      }

      Result(
        epoch = config.anchorSet.epoch,
        anchorSetVersion = config.anchorSet.version,
        admitted = admittedRanked,
        provisional = provisionalRanked,
        disconnected = disconnectedRanked,
        connectivity = connectivity
      )

  /** Runs bootstrap resampling over the observation units. */
  private def computeBootstrap(
      groups: Seq[Seq[Game]],
      config: Config,
      baseOffset: Double
  ): (Map[String, Double], Map[String, Double], Map[String, Vector[Double]]) =
    val groupArray = groups.toArray
    val groupCount = groupArray.length
    if groupCount == 0 || config.bootstrapIterations <= 0 then (Map.empty, Map.empty, Map.empty)
    else
      val rng     = new Random(config.seed)
      val players = groups.flatten.flatMap(g => List(g._1, g._2)).distinct.sorted

      // Reusable accumulator per player for bootstrap samples
      val playerSamples = mutable.Map.empty[String, mutable.ArrayBuffer[Double]]
      players.foreach(p => playerSamples(p) = new mutable.ArrayBuffer[Double](config.bootstrapIterations))

      var b = 0
      while b < config.bootstrapIterations do
        // Draw groups with replacement
        val sampleGroups = new Array[Seq[Game]](groupCount)
        var i            = 0
        while i < groupCount do
          sampleGroups(i) = groupArray(rng.nextInt(groupCount))
          i += 1

        val rawSample = BradleyTerry.ratings(sampleGroups.flatten.toSeq)
        // Recalibrate each bootstrap sample by its present anchors; fall back to baseOffset
        val sampleOffset = config.anchorSet.scaleOffset(rawSample).getOrElse(baseOffset)

        rawSample.foreach { case (player, rawElo) =>
          playerSamples.get(player).foreach(_ += (rawElo - sampleOffset))
        }
        b += 1

      def percentile(sorted: Vector[Double], p: Double): Double =
        if sorted.isEmpty then 0.0
        else sorted(math.min(sorted.size - 1, math.max(0, math.round(p * (sorted.size - 1)).toInt)))

      val lowsMap    = mutable.Map.empty[String, Double]
      val highsMap   = mutable.Map.empty[String, Double]
      val sortedVals = mutable.Map.empty[String, Vector[Double]]

      players.foreach { p =>
        val vals = playerSamples(p).toVector.sorted
        sortedVals(p) = vals
        if vals.nonEmpty then
          lowsMap(p) = percentile(vals, 0.025)
          highsMap(p) = percentile(vals, 0.975)
      }

      (lowsMap.toMap, highsMap.toMap, sortedVals.toMap)
