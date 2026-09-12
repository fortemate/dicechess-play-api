package dicechess.play.rating

import scala.collection.mutable

/** The forecasting models the chronological evaluation ([[RatingEvaluation]], #148) compares. Every model is an ONLINE
  * learner driven day by day: [[Model.forecast]] answers from state that has seen only earlier games, then
  * [[Model.observe]] hands it the outcome. One-game-period models update at once; daily models buffer until
  * [[Model.endDay]]. No model ever sees a later game, which is the leakage rule the evaluation exists to enforce.
  *
  * Three families, matching the preregistration in the private knowledge base:
  *   - [[GlickoModel]] — production Glicko-2 as the baseline, with the knobs the preregistered grid varies: τ, idle RD
  *     inflation per calendar day, daily instead of one-game rating periods, and pinned anchors;
  *   - [[BradleyTerryModel]] — a STATIC Bradley–Terry refitted every day over all prior games (the stationarity
  *     control: if bots never changed, using everything should win) or over a trailing window;
  *   - [[WhrModel]] — Coulom's Whole-History Rating: one strength per player per day of play, Wiener-process prior
  *     between days, refitted by Newton–Raphson at the end of every day.
  *
  * Strengths are reported on the public Elo-like scale (Glicko's own numbers; `1500 + 400·log10(γ)` for the
  * Bradley–Terry family) so drift and coverage can be read side by side.
  */
object RatingModels:

  /** One rated, decided game as the models see it: the day index (0 = the corpus's first day), the category scale it
    * lands on, the two identities and white's score (1 win, 0.5 draw, 0 loss).
    */
  final case class Observation(day: Int, category: String, white: String, black: String, whiteScore: Double)

  /** A rating estimate with its uncertainty on the public scale, when the model has one — for drift and coverage. */
  final case class Estimate(rating: Double, sd: Option[Double])

  trait Model:
    def name: String

    /** The driver has moved to `day`; nothing of that day has been seen yet. */
    def startDay(day: Int): Unit

    /** P(white scores) from the state before the game. */
    def forecast(o: Observation): Double

    /** Learn from the game's outcome. */
    def observe(o: Observation): Unit

    /** Every game of `day` has been observed. */
    def endDay(day: Int): Unit

    /** The current estimate for one identity on one category scale, if it has been seen. */
    def estimate(id: String, category: String): Option[Estimate]

  /** Elo points per natural-log unit of Bradley–Terry strength: `400 / ln 10`. */
  val EloPerNat: Double = 400.0 / math.log(10.0)

  /** `P(a beats b)` for two strengths on the Elo scale. */
  def eloProbability(a: Double, b: Double): Double = 1.0 / (1.0 + math.pow(10.0, (b - a) / 400.0))

  // ── Glicko-2 ──────────────────────────────────────────────────────────────────

  /** The Glicko-2 knobs. `idleEloPerSqrtDay` inflates the deviation for calendar days without a game (`RD² += c² ·
    * days`, capped at the initial 350); `daily` closes one rating period per player per UTC day instead of per game;
    * `anchors` pins identities at a fixed state that the model never updates.
    */
  final case class GlickoSpec(
      tau: Double = Glicko2.DefaultTau,
      idleEloPerSqrtDay: Double = 0.0,
      daily: Boolean = false,
      anchors: Map[String, Glicko] = Map.empty
  )

  final class GlickoModel(spec: GlickoSpec) extends Model:
    final private class State(var glicko: Glicko, var lastDay: Int)

    private val states  = mutable.HashMap.empty[(String, String), State]
    private val pending = mutable.HashMap.empty[(String, String), mutable.ListBuffer[Glicko2.Result]]
    private var today   = 0

    val name: String =
      val period = if spec.daily then "day" else "game"
      val anchor = if spec.anchors.isEmpty then "" else s",anchors=${spec.anchors.size}"
      f"glicko(tau=${spec.tau}%.2f,idle=${spec.idleEloPerSqrtDay}%.0f,period=$period$anchor)"

    def startDay(day: Int): Unit = today = day

    private def stateOf(id: String, category: String): State =
      states.getOrElseUpdate((id, category), new State(spec.anchors.getOrElse(id, Glicko.Initial), today))

    /** The pre-game state, with idle inflation applied on the first touch of a new day. */
    private def current(id: String, category: String): Glicko =
      val state = stateOf(id, category)
      if spec.idleEloPerSqrtDay > 0.0 && today > state.lastDay && !spec.anchors.contains(id) then
        val idle     = (today - state.lastDay).toDouble
        val inflated = math.sqrt(
          state.glicko.deviation * state.glicko.deviation + spec.idleEloPerSqrtDay * spec.idleEloPerSqrtDay * idle
        )
        val deviation = math.min(Glicko.Initial.deviation, inflated)
        state.glicko = state.glicko.copy(deviation = deviation)
      state.lastDay = today
      state.glicko

    def forecast(o: Observation): Double =
      Glicko2.expectedScore(current(o.white, o.category), current(o.black, o.category))

    def observe(o: Observation): Unit =
      val white = current(o.white, o.category)
      val black = current(o.black, o.category)
      if spec.daily then
        pending.getOrElseUpdate((o.white, o.category), mutable.ListBuffer.empty) += Glicko2.Result(black, o.whiteScore)
        pending
          .getOrElseUpdate((o.black, o.category), mutable.ListBuffer.empty) += Glicko2.Result(white, 1.0 - o.whiteScore)
      else
        applyResults(o.white, o.category, List(Glicko2.Result(black, o.whiteScore)))
        applyResults(o.black, o.category, List(Glicko2.Result(white, 1.0 - o.whiteScore)))

    private def applyResults(id: String, category: String, results: List[Glicko2.Result]): Unit =
      if !spec.anchors.contains(id) then
        val state = stateOf(id, category)
        state.glicko = Glicko2.update(state.glicko, results, spec.tau)

    def endDay(day: Int): Unit =
      if spec.daily then
        // Keys in insertion order would need a LinkedHashMap; the per-player updates are independent (every result
        // carries the opponent's start-of-day state), so the order cannot change the outcome.
        pending.foreach { case ((id, category), results) => applyResults(id, category, results.toList) }
        pending.clear()

    def estimate(id: String, category: String): Option[Estimate] =
      states.get((id, category)).map(s => Estimate(s.glicko.rating, Some(s.glicko.deviation)))

  // ── Bradley–Terry, refitted daily ──────────────────────────────────────────────

  /** `windowDays = None` fits every prior game (the stationarity control); `Some(n)` only the trailing `n` days. */
  final case class BradleyTerrySpec(windowDays: Option[Int] = None)

  final class BradleyTerryModel(spec: BradleyTerrySpec) extends Model:
    final private case class Row(day: Int, a: String, b: String, scoreA: Double)

    private val history = mutable.HashMap.empty[String, mutable.ArrayBuffer[Row]]
    private var fitted  = Map.empty[String, Map[String, Double]]
    private var dirty   = Set.empty[String]

    val name: String = s"bt(window=${spec.windowDays.fold("all")(_.toString)})"

    def startDay(day: Int): Unit =
      dirty.foreach { category =>
        val rows     = history.getOrElse(category, mutable.ArrayBuffer.empty[Row])
        val selected = spec.windowDays.fold(rows.toSeq)(n => rows.view.filter(_.day >= day - n).toSeq)
        fitted = fitted.updated(category, BradleyTerry.ratings(selected.map(r => (r.a, r.b, r.scoreA))))
      }
      dirty = Set.empty

    def forecast(o: Observation): Double =
      val ratings = fitted.getOrElse(o.category, Map.empty)
      eloProbability(ratings.getOrElse(o.white, 0.0), ratings.getOrElse(o.black, 0.0))

    def observe(o: Observation): Unit =
      history.getOrElseUpdate(o.category, mutable.ArrayBuffer.empty) += Row(o.day, o.white, o.black, o.whiteScore)
      dirty += o.category

    def endDay(day: Int): Unit = ()

    def estimate(id: String, category: String): Option[Estimate] =
      fitted.get(category).flatMap(_.get(id)).map(relative => Estimate(1500.0 + relative, None))

  // ── Whole-History Rating ───────────────────────────────────────────────────────

  /** `eloPerSqrtDay` is the Wiener-process standard deviation: how far a strength may wander in one day, in Elo.
    * `sweeps` is the number of Newton–Raphson passes over every player per refit.
    */
  final case class WhrSpec(eloPerSqrtDay: Double, sweeps: Int = 3)

  final class WhrModel(spec: WhrSpec) extends Model:
    /** Daily variance of the prior in natural-log units. */
    private val variance = math.pow(spec.eloPerSqrtDay / EloPerNat, 2)

    final private class Player:
      val days: mutable.ArrayBuffer[Int]                        = mutable.ArrayBuffer.empty
      val r: mutable.ArrayBuffer[Double]                        = mutable.ArrayBuffer.empty
      val games: mutable.ArrayBuffer[mutable.ArrayBuffer[Link]] = mutable.ArrayBuffer.empty

      def last: Double = if r.isEmpty then 0.0 else r(r.length - 1)

      def nodeFor(day: Int): Int =
        if days.nonEmpty && days(days.length - 1) == day then days.length - 1
        else
          days += day
          r += last
          games += mutable.ArrayBuffer.empty
          days.length - 1

    /** One game from a player's side: the opponent's node whose strength it was played against, and the score. */
    final private case class Link(opponent: Player, node: Int, score: Double)

    private val players = mutable.LinkedHashMap.empty[(String, String), Player]
    private val buffer  = mutable.ArrayBuffer.empty[Observation]

    val name: String = f"whr(w=${spec.eloPerSqrtDay}%.0f)"

    private def playerOf(id: String, category: String): Player =
      players.getOrElseUpdate((category, id), new Player)

    def startDay(day: Int): Unit = ()

    def forecast(o: Observation): Double =
      val a = players.get((o.category, o.white)).fold(0.0)(_.last)
      val b = players.get((o.category, o.black)).fold(0.0)(_.last)
      1.0 / (1.0 + math.exp(b - a))

    def observe(o: Observation): Unit = buffer += o

    def endDay(day: Int): Unit =
      val touched = mutable.LinkedHashSet.empty[String]
      buffer.foreach { o =>
        val white = playerOf(o.white, o.category)
        val black = playerOf(o.black, o.category)
        val wNode = white.nodeFor(day)
        val bNode = black.nodeFor(day)
        white.games(wNode) += Link(black, bNode, o.whiteScore)
        black.games(bNode) += Link(white, wNode, 1.0 - o.whiteScore)
        touched += o.category
      }
      buffer.clear()
      if touched.nonEmpty then
        var sweep = 0
        while sweep < spec.sweeps do
          players.foreach { case ((category, _), player) => if touched.contains(category) then newton(player) }
          sweep += 1

    /** One Newton–Raphson step for one player over all its days (Coulom 2008, appendix A): gradient and tridiagonal
      * Hessian of the log-posterior — Bradley–Terry likelihood of its games at every node, the Wiener prior between
      * consecutive nodes, and Coulom's virtual win and loss against a strength-0 opponent on the first node, which is
      * what keeps a player with a perfect record finite and pins the scale's origin.
      */
    private def newton(p: Player): Unit =
      val n = p.days.length
      if n > 0 then
        val gradient = new Array[Double](n)
        val diagonal = new Array[Double](n)
        val off      = new Array[Double](math.max(n - 1, 0))
        var i        = 0
        while i < n do
          val ri = p.r(i)
          if i == 0 then
            val p0 = 1.0 / (1.0 + math.exp(-ri))
            gradient(i) += 1.0 - 2.0 * p0
            diagonal(i) -= 2.0 * p0 * (1.0 - p0)
          p.games(i).foreach { link =>
            val pij = 1.0 / (1.0 + math.exp(link.opponent.r(link.node) - ri))
            gradient(i) += link.score - pij
            diagonal(i) -= pij * (1.0 - pij)
          }
          if i > 0 then
            val v = variance * (p.days(i) - p.days(i - 1))
            gradient(i) -= (ri - p.r(i - 1)) / v
            diagonal(i) -= 1.0 / v
          if i < n - 1 then
            val v = variance * (p.days(i + 1) - p.days(i))
            gradient(i) += (p.r(i + 1) - ri) / v
            diagonal(i) -= 1.0 / v
            off(i) = 1.0 / v
          i += 1
        val step = Whr.solveTridiagonal(diagonal, off, gradient)
        i = 0
        while i < n do
          p.r(i) -= step(i)
          i += 1

    def estimate(id: String, category: String): Option[Estimate] =
      players.get((category, id)).filter(_.days.nonEmpty).map { p =>
        val sd = Whr.posteriorSd(
          p.days.toArray,
          p.r.toArray,
          p.games.map(_.toSeq.map(l => (l.opponent.r(l.node), l.score))).toArray,
          variance
        )
        Estimate(1500.0 + EloPerNat * p.last, Some(EloPerNat * sd))
      }

  /** The linear algebra behind [[WhrModel]], separated so it can be unit-tested against small hand-checked systems. */
  object Whr:

    /** Solve `T x = rhs` for a symmetric tridiagonal `T` with main diagonal `diagonal` and off-diagonal `off` (Thomas
      * algorithm). The inputs are not modified.
      */
    def solveTridiagonal(diagonal: Array[Double], off: Array[Double], rhs: Array[Double]): Array[Double] =
      val n = diagonal.length
      val c = new Array[Double](n) // modified diagonal
      val d = new Array[Double](n) // modified rhs
      val x = new Array[Double](n)
      if n > 0 then
        c(0) = diagonal(0)
        d(0) = rhs(0)
        var i = 1
        while i < n do
          val m = off(i - 1) / c(i - 1)
          c(i) = diagonal(i) - m * off(i - 1)
          d(i) = rhs(i) - m * d(i - 1)
          i += 1
        x(n - 1) = d(n - 1) / c(n - 1)
        i = n - 2
        while i >= 0 do
          x(i) = (d(i) - off(i) * x(i + 1)) / c(i)
          i -= 1
      x

    /** The posterior standard deviation (natural-log units) of a player's LAST node: the corresponding diagonal entry
      * of the inverse of the negative Hessian, obtained by solving one tridiagonal system — exact, and cheap at the few
      * dozen nodes a player accumulates.
      */
    def posteriorSd(
        days: Array[Int],
        r: Array[Double],
        games: Array[Seq[(Double, Double)]],
        variance: Double
    ): Double =
      val n        = days.length
      val diagonal = new Array[Double](n)
      val off      = new Array[Double](math.max(n - 1, 0))
      var i        = 0
      while i < n do
        if i == 0 then
          val p0 = 1.0 / (1.0 + math.exp(-r(0)))
          diagonal(i) += 2.0 * p0 * (1.0 - p0)
        games(i).foreach { (opponent, _) =>
          val pij = 1.0 / (1.0 + math.exp(opponent - r(i)))
          diagonal(i) += pij * (1.0 - pij)
        }
        if i > 0 then diagonal(i) += 1.0 / (variance * (days(i) - days(i - 1)))
        if i < n - 1 then
          val v = variance * (days(i + 1) - days(i))
          diagonal(i) += 1.0 / v
          off(i) = -1.0 / v
        i += 1
      val unit = new Array[Double](n)
      unit(n - 1) = 1.0
      math.sqrt(solveTridiagonal(diagonal, off, unit)(n - 1))
