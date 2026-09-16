package dicechess.play.rating

import scala.collection.mutable

/** Comparison graph connectivity and evidence admission rules (#147, ADR 008, #170).
  *
  * Regularized estimators (like Bradley–Terry with Laplace virtual draws) silently manufacture global order for
  * disconnected player islands that never competed against each other.
  *
  * This module validates real comparison paths (Weakly Connected Components) against the versioned anchor set and
  * enforces admission evidence thresholds before any global rank is published.
  */
object GraphConnectivity:

  enum AdmissionStatus:
    case Admitted
    case Provisional(games: Int, opponents: Int)
    case Disconnected

  final case class Result(
      statuses: Map[String, AdmissionStatus],
      gameCounts: Map[String, Int],
      opponentCounts: Map[String, Int],
      anchorConnectedPlayers: Set[String],
      disconnectedPlayers: Set[String],
      components: List[Set[String]],
      hasAnchors: Boolean = false
  ):
    def isAdmitted(player: String): Boolean =
      statuses.get(player).contains(AdmissionStatus.Admitted)

    def isProvisional(player: String): Boolean =
      statuses.get(player).exists(_.isInstanceOf[AdmissionStatus.Provisional])

    def isDisconnected(player: String): Boolean =
      statuses.get(player).contains(AdmissionStatus.Disconnected)

  val DefaultMinGames: Int     = 30
  val DefaultMinOpponents: Int = 2

  /** Analyzes the comparison graph formed by the observed games.
    *
    * @param games
    *   sequence of `(white, black)` game pairs
    * @param anchors
    *   the set of anchor identities
    * @param minGames
    *   minimum required games for full admission (default 30)
    * @param minOpponents
    *   minimum required distinct opponents for full admission (default 2)
    */
  def analyze(
      games: Seq[(String, String)],
      anchors: Set[String],
      minGames: Int = DefaultMinGames,
      minOpponents: Int = DefaultMinOpponents
  ): Result =
    val dsu          = new DisjointSet
    val players      = mutable.Set.empty[String]
    val gameCounts   = mutable.Map.empty[String, Int].withDefaultValue(0)
    val opponentsMap = mutable.Map.empty[String, mutable.Set[String]]

    games.foreach { (white, black) =>
      players += white
      players += black
      if white != black then
        dsu.union(white, black)
        gameCounts(white) += 1
        gameCounts(black) += 1
        opponentsMap.getOrElseUpdate(white, mutable.Set.empty) += black
        opponentsMap.getOrElseUpdate(black, mutable.Set.empty) += white
    }

    val playerSet         = players.toSet
    val components        = dsu.components(playerSet)
    val hasAnchorsInGraph = anchors.exists(playerSet.contains)

    val (anchorConnected, disconnected) = partitionComponents(components, anchors, hasAnchorsInGraph)
    val opponentCounts                  = playerSet.map(p => p -> opponentsMap.get(p).fold(0)(_.size)).toMap
    val finalGameCounts                 = playerSet.map(p => p -> gameCounts(p)).toMap

    val statuses =
      classifyStatuses(playerSet, anchorConnected, anchors, finalGameCounts, opponentCounts, minGames, minOpponents)

    Result(
      statuses = statuses,
      gameCounts = finalGameCounts,
      opponentCounts = opponentCounts,
      anchorConnectedPlayers = anchorConnected,
      disconnectedPlayers = disconnected,
      components = components,
      hasAnchors = hasAnchorsInGraph
    )

  final private class DisjointSet:
    private val parent = mutable.Map.empty[String, String]

    def find(x: String): String =
      var root = x
      while parent.getOrElse(root, root) != root do root = parent(root)
      var curr = x
      while curr != root do
        val nxt = parent(curr)
        parent(curr) = root
        curr = nxt
      root

    def union(x: String, y: String): Unit =
      if !parent.contains(x) then parent(x) = x
      if !parent.contains(y) then parent(y) = y
      val rx = find(x)
      val ry = find(y)
      if rx != ry then parent(rx) = ry

    def components(players: Set[String]): List[Set[String]] =
      val map = mutable.Map.empty[String, mutable.Set[String]]
      players.foreach { p =>
        val root = find(p)
        map.getOrElseUpdate(root, mutable.Set.empty) += p
      }
      map.values.map(_.toSet).toList

  private def partitionComponents(
      components: List[Set[String]],
      anchors: Set[String],
      hasAnchors: Boolean
  ): (Set[String], Set[String]) =
    val connected    = mutable.Set.empty[String]
    val disconnected = mutable.Set.empty[String]
    components.foreach { comp =>
      val isConnected = if hasAnchors then comp.exists(anchors.contains) else true
      if isConnected then connected ++= comp
      else disconnected ++= comp
    }
    (connected.toSet, disconnected.toSet)

  private def classifyStatuses(
      players: Set[String],
      anchorConnected: Set[String],
      anchors: Set[String],
      gameCounts: Map[String, Int],
      opponentCounts: Map[String, Int],
      minGames: Int,
      minOpponents: Int
  ): Map[String, AdmissionStatus] =
    players.map { p =>
      val g      = gameCounts.getOrElse(p, 0)
      val o      = opponentCounts.getOrElse(p, 0)
      val status =
        if !anchorConnected.contains(p) then AdmissionStatus.Disconnected
        else if anchors.contains(p) || (g >= minGames && o >= minOpponents) then AdmissionStatus.Admitted
        else AdmissionStatus.Provisional(g, o)
      p -> status
    }.toMap
