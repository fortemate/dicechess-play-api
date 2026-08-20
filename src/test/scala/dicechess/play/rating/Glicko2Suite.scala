package dicechess.play.rating

import dicechess.play.rating.Glicko2.Result

/** Pure math — no IO, no Docker. The anchor is Glickman's worked example from the Glicko-2 paper
  * (<http://www.glicko.net/glicko/glicko2.pdf>, "Example calculation"): if those exact numbers come out, the scale
  * conversions, v/Δ, the volatility iteration and the constrained update are all wired correctly.
  */
class Glicko2Suite extends munit.FunSuite:

  private val player = Glicko(rating = 1500.0, deviation = 200.0, volatility = 0.06)

  private val glickmanGames = List(
    Result(Glicko(1400.0, 30.0, 0.06), score = 1.0),  // win vs the accurate low-rated opponent
    Result(Glicko(1550.0, 100.0, 0.06), score = 0.0), // loss vs the mid one
    Result(Glicko(1700.0, 300.0, 0.06), score = 0.0)  // loss vs the uncertain high one
  )

  test("reproduces Glickman's worked example (r'=1464.06, RD'=151.52, sigma'=0.05999)"):
    val updated = Glicko2.update(player, glickmanGames, tau = 0.5)
    // The paper rounds to two decimals; full-precision reference values (cross-checked against independent
    // implementations) are r'=1464.0507, RD'=151.5165, sigma'=0.0599960.
    assertEqualsDouble(updated.rating, 1464.05, 0.01)
    assertEqualsDouble(updated.deviation, 151.52, 0.01)
    assertEqualsDouble(updated.volatility, 0.05999, 0.0001)

  test("a win raises the rating, a loss lowers it, and either shrinks the deviation"):
    val even = Glicko.Initial
    val won  = Glicko2.update(even, List(Result(Glicko.Initial, 1.0)))
    val lost = Glicko2.update(even, List(Result(Glicko.Initial, 0.0)))
    assert(won.rating > even.rating, s"winner must gain: ${won.rating}")
    assert(lost.rating < even.rating, s"loser must lose: ${lost.rating}")
    assert(won.deviation < even.deviation, "playing at all must shrink RD")
    assert(lost.deviation < even.deviation, "for the loser too")

  test("a draw between equals leaves the rating unchanged but still shrinks the deviation"):
    val drawn = Glicko2.update(Glicko.Initial, List(Result(Glicko.Initial, 0.5)))
    assertEqualsDouble(drawn.rating, 1500.0, 1e-9)
    assert(drawn.deviation < Glicko.Initial.deviation)

  test("equal players' single-game updates are symmetric (winner's gain = loser's loss)"):
    val won  = Glicko2.update(Glicko.Initial, List(Result(Glicko.Initial, 1.0)))
    val lost = Glicko2.update(Glicko.Initial, List(Result(Glicko.Initial, 0.0)))
    assertEqualsDouble(won.rating - 1500.0, 1500.0 - lost.rating, 1e-9)
    assertEqualsDouble(won.deviation, lost.deviation, 1e-9)

  test("an upset moves the rating more than an expected result"):
    val underdog     = Glicko(1400.0, 150.0, 0.06)
    val favourite    = Glicko(1600.0, 150.0, 0.06)
    val upsetGain    = Glicko2.update(underdog, List(Result(favourite, 1.0))).rating - underdog.rating
    val expectedGain = Glicko2.update(favourite, List(Result(underdog, 1.0))).rating - favourite.rating
    assert(
      upsetGain > expectedGain && expectedGain > 0,
      s"beating a favourite ($upsetGain) must pay more than beating an underdog ($expectedGain)"
    )

  test("no games in the period returns the state unchanged (no idle inflation, by design)"):
    val settled = Glicko(1650.0, 80.0, 0.055)
    assertEquals(Glicko2.update(settled, Nil), settled)

  test("a fresh bot is provisional; a settled deviation is not"):
    assert(Glicko.Initial.deviation > Glicko2.ProvisionalDeviationThreshold, "RD 350 must be provisional")
    assert(80.0 < Glicko2.ProvisionalDeviationThreshold + 1, "sanity: a converged RD (~80) sits under the threshold")

  test("the default tau damps a shock result's volatility and rating swing compared to the old 0.5 (#169)"):
    // A settled player suffers a maximally surprising loss — the volatility update's trigger case. The whole point
    // of moving tau to the bottom of Glickman's range is that this single dice-noise upset shifts sigma (and through
    // it, the rating) LESS than it did under 0.5.
    val settled = Glicko(1600.0, 50.0, 0.06)
    val shock   = List(Result(Glicko(1200.0, 50.0, 0.06), score = 0.0))
    val damped  = Glicko2.update(settled, shock) // the default tau
    val old     = Glicko2.update(settled, shock, tau = 0.5)
    assert(damped.volatility > settled.volatility, "a shock must still raise volatility — tau damps, not disables")
    assert(
      damped.volatility < old.volatility,
      s"tau=${Glicko2.DefaultTau} must cap the volatility jump below tau=0.5's (${damped.volatility} vs ${old.volatility})"
    )
    assert(
      damped.rating > old.rating,
      s"the damped loss must cost fewer points (${settled.rating - damped.rating} vs ${settled.rating - old.rating})"
    )

  test("conservative ordering demotes a streak-inflated high-RD entry that raw rating would rank first (#169)"):
    // The motivating case: a converged bot on a hot streak — rating up, and the streak's volatility has pushed its
    // RD up too — against a steadier bot with the better-settled estimate. Raw rating ranks the streaker first; the
    // board's key must not.
    val streaker = (1580.0, 105.0) // converged (RD <= 110), but barely
    val steady   = (1560.0, 45.0)
    assert(streaker._1 > steady._1, "sanity: raw rating alone would put the streaker on top")
    assert(
      Glicko2.conservativeRating(steady._1, steady._2) > Glicko2.conservativeRating(streaker._1, streaker._2),
      "the conservative key must rank the settled estimate first"
    )
    // And it is rating MINUS k*RD with k=2 — the docs state the formula, so pin it.
    assertEqualsDouble(Glicko2.conservativeRating(1500.0, 100.0), 1300.0, 1e-9)
