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
      components: List[Set[String]]
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
    val players = mutable.Set.empty[String]
    val parent  = mutable.Map.empty[String, String]

    def find(x: String): String =
      var root = x
      while parent(root) != root do root = parent(root)
      var curr = x
      while curr != root do
        val nxt = parent(curr)
        parent(curr) = root
        curr = nxt
      root

    def union(x: String, y: String): Unit =
      val rx = find(x)
      val ry = find(y)
      if rx != ry then parent(rx) = ry

    val gameCounts   = mutable.Map.empty[String, Int].withDefaultValue(0)
    val opponentsMap = mutable.Map.empty[String, mutable.Set[String]]

    games.foreach { (white, black) =>
      players += white
      players += black

      if !parent.contains(white) then parent(white) = white
      if !parent.contains(black) then parent(black) = black

      if white != black then
        union(white, black)
        gameCounts(white) += 1
        gameCounts(black) += 1
        opponentsMap.getOrElseUpdate(white, mutable.Set.empty) += black
        opponentsMap.getOrElseUpdate(black, mutable.Set.empty) += white
    }

    val hasAnchorsInGraph = anchors.exists(players.contains)

    // Group players by connected component root
    val componentMap = mutable.Map.empty[String, mutable.Set[String]]
    players.foreach { p =>
      val root = find(p)
      componentMap.getOrElseUpdate(root, mutable.Set.empty) += p
    }

    val components = componentMap.values.map(_.toSet).toList

    val anchorConnectedPlayers = mutable.Set.empty[String]
    val disconnectedPlayers    = mutable.Set.empty[String]

    components.foreach { comp =>
      val hasAnchor = if hasAnchorsInGraph then comp.exists(anchors.contains) else true
      if hasAnchor then anchorConnectedPlayers ++= comp
      else disconnectedPlayers ++= comp
    }

    val opponentCounts  = players.map(p => p -> opponentsMap.get(p).map(_.size).getOrElse(0)).toMap
    val finalGameCounts = players.map(p => p -> gameCounts(p)).toMap

    val statuses = players.map { p =>
      val g      = finalGameCounts(p)
      val o      = opponentCounts(p)
      val status =
        if !anchorConnectedPlayers.contains(p) then AdmissionStatus.Disconnected
        else if anchors.contains(p) || (g >= minGames && o >= minOpponents) then AdmissionStatus.Admitted
        else AdmissionStatus.Provisional(g, o)
      p -> status
    }.toMap

    Result(
      statuses = statuses,
      gameCounts = finalGameCounts,
      opponentCounts = opponentCounts,
      anchorConnectedPlayers = anchorConnectedPlayers.toSet,
      disconnectedPlayers = disconnectedPlayers.toSet,
      components = components
    )
