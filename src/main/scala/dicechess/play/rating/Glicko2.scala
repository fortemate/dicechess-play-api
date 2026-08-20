package dicechess.play.rating

/** A bot's Glicko-2 state: the public-scale rating, its deviation (RD — the uncertainty around the rating), and the
  * volatility (how erratic the results have been). Mirrors the `bots.glicko_*` columns; `BotRating` wraps this plus
  * ladder bookkeeping.
  */
final case class Glicko(rating: Double, deviation: Double, volatility: Double)

object Glicko:
  /** Glickman's suggested starting state for a new, unrated player — matches `BotRating.initial`. */
  val Initial: Glicko = Glicko(rating = 1500.0, deviation = 350.0, volatility = 0.06)

/** Glicko-2 (Glickman, <http://www.glicko.net/glicko/glicko2.pdf>) as a pure function — no IO, no clock, no store. The
  * rating batch (`RatingBatch`, #119) feeds it one game at a time; the algorithm itself takes any number of results per
  * rating period, which is exactly what the Glickman worked example (and its test) exercises.
  *
  * '''Rating-period choice (documented per #119, revisited and deliberately KEPT per #169):''' the ladder treats EVERY
  * game as its own one-game rating period for both participants (the lichess-style continuous approximation), rather
  * than batching games into Glickman's suggested 10–15-game windows. #169 weighed batching as a damper on streak-driven
  * rating wiggle and rejected it: the batch's crash-safety rests on applying one game to BOTH participants in one
  * transaction with that game's `rating_applied_at` stamp, while per-player N-game periods close at different moments
  * for the two sides of one game — honouring that would need per-side apply stamps or a period ledger, real schema for
  * a presentation problem. The wiggle itself is treated where it is felt instead: the public board orders by a
  * conservative estimate (`rating − k·RD`, see [[ConservativeOrderingK]]) and τ is lowered to damp volatility spikes,
  * while "which bot is actually stronger" was never this number's job — that is SPRT/Bradley-Terry (#120,
  * `GET /strength`). Idle-time RD inflation (Glicko-2's "did not compete in a period" rule) is deliberately NOT applied
  * — on-ladder bots play continuously by construction (the scheduler pairs them), so there is no meaningful idle time
  * to model yet; revisit when human accounts arrive.
  */
object Glicko2:

  /** Glicko ↔ Glicko-2 scale factor, from the paper. */
  private val Scale = 173.7178

  /** The system constant τ — how much the volatility is allowed to change per update. Glickman suggests 0.3–1.2,
    * smaller values for games where improbable results are expected noise rather than a sign the rating is wrong.
    * Dice-driven upsets are exactly that noise, and with one-game periods (see the object doc) every update sees a
    * single result, the setting most prone to volatility spikes — so #169 moved from 0.5 (the paper's own example
    * value) to the bottom of Glickman's range. Applies to future updates only; stored `glicko_vol` values evolved under
    * 0.5 are valid states, τ only constrains how fast they may change from here (#169's no-backfill decision).
    */
  val DefaultTau: Double = 0.3

  /** Convergence tolerance ε for the volatility iteration (step 5 of the paper). */
  private val ConvergenceTolerance = 1e-6

  /** RD at or below which a rating is considered converged. Above it the bot is '''provisional''': its games count, but
    * D.2 (#103) hides it from the public leaderboard until the deviation settles. A fresh bot starts at RD 350 and
    * typically converges within a few dozen ladder games.
    */
  val ProvisionalDeviationThreshold: Double = 110.0

  /** k in the public board's conservative ordering key `rating − k·RD` (#169). The board still DISPLAYS `rating ± RD`;
    * only the sort uses this. A justified constant rather than a knob: 2 is the conventional conservative estimate —
    * rank by what the rating is at least, at roughly the 97.7% one-sided level — the same idea as TrueSkill's μ − kσ
    * default, with RD being the standard deviation of the rating. k = 1 (the printed lower bound) was considered and
    * passed over: the case that motivated the change is a converged bot whose hot streak has inflated its volatility
    * and thus its RD, and doubling the RD's weight is precisely what keeps that entry from jumping settled ones.
    */
  val ConservativeOrderingK: Double = 2.0

  /** The board's ordering key: the conservative estimate `rating − k·RD`. In ONE place (shared by the SQL `ORDER BY`s
    * through interpolation of [[ConservativeOrderingK]] and by the routes' merged re-sort through this function) so the
    * two orderings cannot drift.
    */
  def conservativeRating(rating: Double, deviation: Double): Double =
    rating - ConservativeOrderingK * deviation

  /** One game from the updating player's point of view: the opponent's PRE-game state and the score (1.0 win, 0.5 draw,
    * 0.0 loss).
    */
  final case class Result(opponent: Glicko, score: Double)

  /** The player's post-period state given the games of one rating period (all opponents at their pre-period state).
    * With no games the state is returned unchanged — see the object doc for why idle RD inflation is not applied.
    */
  def update(player: Glicko, games: List[Result], tau: Double = DefaultTau): Glicko =
    if games.isEmpty then player
    else
      // Step 2: convert to the Glicko-2 scale.
      val mu        = (player.rating - 1500.0) / Scale
      val phi       = player.deviation / Scale
      val opponents = games.map(r => ((r.opponent.rating - 1500.0) / Scale, r.opponent.deviation / Scale, r.score))

      // Step 3: v — the estimated variance of the rating from game outcomes alone.
      val v = 1.0 / opponents.map { (muJ, phiJ, _) =>
        val gJ = g(phiJ)
        val eJ = e(mu, muJ, phiJ)
        gJ * gJ * eJ * (1.0 - eJ)
      }.sum

      // Step 4: Δ — the estimated rating improvement suggested by the outcomes.
      val outcomeSum = opponents.map((muJ, phiJ, score) => g(phiJ) * (score - e(mu, muJ, phiJ))).sum
      val delta      = v * outcomeSum

      // Steps 5–7: new volatility, pre-update deviation inflation, then the constrained update.
      val sigmaPrime = newVolatility(phi, v, delta, player.volatility, tau)
      val phiStar    = math.sqrt(phi * phi + sigmaPrime * sigmaPrime)
      val phiPrime   = 1.0 / math.sqrt(1.0 / (phiStar * phiStar) + 1.0 / v)
      val muPrime    = mu + phiPrime * phiPrime * outcomeSum

      // Step 8: back to the public scale.
      Glicko(rating = muPrime * Scale + 1500.0, deviation = phiPrime * Scale, volatility = sigmaPrime)

  /** g(φ): dampens an opponent's influence by the uncertainty of their own rating. */
  private def g(phi: Double): Double = 1.0 / math.sqrt(1.0 + 3.0 * phi * phi / (math.Pi * math.Pi))

  /** E(μ, μj, φj): the expected score against opponent j. */
  private def e(mu: Double, muJ: Double, phiJ: Double): Double =
    1.0 / (1.0 + math.exp(-g(phiJ) * (mu - muJ)))

  /** Step 5 of the paper: the new volatility σ′, found with the Illinois variant of regula falsi — guaranteed to
    * converge, and in practice within a handful of iterations. Local mutation only; the function is pure.
    */
  private def newVolatility(phi: Double, v: Double, delta: Double, sigma: Double, tau: Double): Double =
    val a      = math.log(sigma * sigma)
    val phi2   = phi * phi
    val delta2 = delta * delta

    def f(x: Double): Double =
      val ex = math.exp(x)
      (ex * (delta2 - phi2 - v - ex)) / (2.0 * (phi2 + v + ex) * (phi2 + v + ex)) - (x - a) / (tau * tau)

    var A = a
    var B =
      if delta2 > phi2 + v then math.log(delta2 - phi2 - v)
      else
        var k = 1
        while f(a - k * tau) < 0 do k += 1
        a - k * tau
    var fA = f(A)
    var fB = f(B)
    while math.abs(B - A) > ConvergenceTolerance do
      val c  = A + (A - B) * fA / (fB - fA)
      val fC = f(c)
      if fC * fB <= 0 then
        A = B
        fA = fB
      else fA = fA / 2.0
      B = c
      fB = fC
    math.exp(A / 2.0)
