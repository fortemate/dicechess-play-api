package dicechess.play.rating

import dicechess.play.rating.RatingModels.*

/** The forecasting models (#148), each pinned to something checkable by hand: the Glicko-2 model reproduces the
  * production one-game update exactly, daily periods and idle inflation change state only where they should, anchors
  * never move, the Bradley–Terry model is symmetric and learns the order of a stationary pool, and the WHR linear
  * algebra matches a dense solve.
  */
class RatingModelsSuite extends munit.FunSuite:

  private val A = "bot:team:fx:a"
  private val B = "bot:team:fx:b"
  private val C = "bot:team:fx:c"

  private def game(day: Int, white: String, black: String, score: Double): Observation =
    Observation(day, "blitz", white, black, score)

  test("one-game-period Glicko-2 matches the production update from both pre-game states"):
    val model = GlickoModel(GlickoSpec())
    model.startDay(0)
    val first = game(0, A, B, 1.0)
    assertEqualsDouble(model.forecast(first), 0.5, 1e-12, "two fresh states are an even game")
    model.observe(first)
    val a = Glicko2.update(Glicko.Initial, List(Glicko2.Result(Glicko.Initial, 1.0)))
    val b = Glicko2.update(Glicko.Initial, List(Glicko2.Result(Glicko.Initial, 0.0)))
    assertEquals(model.estimate(A, "blitz").map(_.rating), Some(a.rating))
    assertEquals(model.estimate(B, "blitz").map(_.rating), Some(b.rating))
    assert(model.forecast(game(0, A, B, 0.0)) > 0.5, "the winner is now favoured")
    assertEqualsDouble(model.forecast(game(0, A, B, 0.0)) + model.forecast(game(0, B, A, 0.0)), 1.0, 1e-12)

  test("daily periods hold the state until the day closes, then apply the day as one period"):
    val model = GlickoModel(GlickoSpec(daily = true))
    model.startDay(0)
    model.observe(game(0, A, B, 1.0))
    assertEqualsDouble(model.forecast(game(0, A, B, 1.0)), 0.5, 1e-12, "mid-day forecasts use the start-of-day state")
    model.observe(game(0, A, B, 1.0))
    model.endDay(0)
    val expected = Glicko2.update(
      Glicko.Initial,
      List(Glicko2.Result(Glicko.Initial, 1.0), Glicko2.Result(Glicko.Initial, 1.0))
    )
    assertEquals(model.estimate(A, "blitz").map(_.rating), Some(expected.rating))

  test("idle inflation widens the deviation by calendar days without a game, capped at the initial 350"):
    val model = GlickoModel(GlickoSpec(idleEloPerSqrtDay = 30.0))
    model.startDay(0)
    model.observe(game(0, A, B, 1.0))
    val settled = model.estimate(A, "blitz").get.sd.get
    model.startDay(4)
    model.forecast(game(4, A, B, 1.0))
    val inflated = model.estimate(A, "blitz").get.sd.get
    assertEqualsDouble(inflated, math.sqrt(settled * settled + 30.0 * 30.0 * 4), 1e-9)
    model.startDay(4000)
    model.forecast(game(4000, A, B, 1.0))
    assertEqualsDouble(model.estimate(A, "blitz").get.sd.get, Glicko.Initial.deviation, 1e-9)

  test("an anchored identity never moves, its opponents still learn"):
    val anchor = Glicko(1500.0, 50.0, 0.06)
    val model  = GlickoModel(GlickoSpec(anchors = Map(A -> anchor)))
    model.startDay(0)
    (1 to 20).foreach(_ => model.observe(game(0, A, B, 0.0)))
    assertEquals(model.estimate(A, "blitz"), Some(Estimate(anchor.rating, Some(anchor.deviation))))
    assert(model.estimate(B, "blitz").get.rating > 1500.0)
    assert(model.name.contains("anchors=1"))

  test("the daily Bradley–Terry model is symmetric, refits at day start, and learns a stationary pool's order"):
    val model = BradleyTerryModel(BradleyTerrySpec())
    model.startDay(0)
    assertEqualsDouble(model.forecast(game(0, A, B, 1.0)), 0.5, 1e-12, "unseen players sit at the pool mean")
    val rng = new scala.util.Random(7)
    (0 until 2).foreach { day =>
      model.startDay(day)
      (0 until 300).foreach { _ =>
        val (w, b) = if rng.nextBoolean() then (A, C) else (B, C)
        val p      = if w == A then 0.8 else 0.6
        val score  = if rng.nextDouble() < p then 1.0 else 0.0
        model.observe(game(day, w, b, score))
      }
      model.endDay(day)
    }
    model.startDay(2)
    val pa = model.forecast(game(2, A, C, 1.0))
    val pb = model.forecast(game(2, B, C, 1.0))
    assert(pa > pb && pb > 0.5, s"A > B > C expected, got $pa, $pb")
    assertEqualsDouble(pa + model.forecast(game(2, C, A, 1.0)), 1.0, 1e-12)
    assert(model.estimate(A, "blitz").get.rating > model.estimate(C, "blitz").get.rating)
    assertEquals(model.estimate(A, "blitz").get.sd, None)

  test("a windowed Bradley–Terry forgets games outside the window"):
    val model = BradleyTerryModel(BradleyTerrySpec(Some(2)))
    model.startDay(0)
    (0 until 50).foreach(_ => model.observe(game(0, A, B, 1.0)))
    model.endDay(0)
    model.startDay(1)
    assert(model.forecast(game(1, A, B, 1.0)) > 0.9)
    model.observe(game(10, B, A, 0.5))
    model.endDay(10)
    model.startDay(11)
    assertEqualsDouble(model.forecast(game(11, A, B, 1.0)), 0.5, 1e-9, "only the day-10 draw is inside the window")
    assertEquals(model.name, "bt(window=2)")

  test("Whr.solveTridiagonal agrees with a dense solve"):
    val diagonal = Array(4.0, 5.0, 6.0, 7.0)
    val off      = Array(1.0, -2.0, 0.5)
    val rhs      = Array(1.0, 2.0, 3.0, 4.0)
    val x        = Whr.solveTridiagonal(diagonal, off, rhs)
    val product  = Array.tabulate(4) { i =>
      diagonal(i) * x(i) + (if i > 0 then off(i - 1) * x(i - 1) else 0.0) + (if i < 3 then off(i) * x(i + 1) else 0.0)
    }
    product.zip(rhs).foreach((got, want) => assertEqualsDouble(got, want, 1e-9))

  test("Whr.posteriorSd of a single node is one over the square root of its curvature"):
    val r         = 0.0
    val opponents = Seq((0.0, 1.0), (0.0, 0.0), (0.0, 1.0))
    val sd        = Whr.posteriorSd(Array(0), Array(r), Array(opponents), variance = 1.0)
    // virtual win+loss: 2·¼ = 0.5; three games at p = ½: 3·¼ = 0.75
    assertEqualsDouble(sd, 1.0 / math.sqrt(0.5 + 0.75), 1e-12)

  test("WHR learns a stationary pool, is symmetric, and its interval narrows with evidence"):
    val model = WhrModel(WhrSpec(30.0))
    val rng   = new scala.util.Random(11)
    (0 until 5).foreach { day =>
      model.startDay(day)
      (0 until 200).foreach { _ =>
        val score = if rng.nextDouble() < 0.75 then 1.0 else 0.0
        model.observe(game(day, A, B, score))
      }
      model.endDay(day)
    }
    val p = model.forecast(game(5, A, B, 1.0))
    assert(p > 0.65 && p < 0.85, s"A should be favoured about 3:1, got $p")
    assertEqualsDouble(p + model.forecast(game(5, B, A, 1.0)), 1.0, 1e-12)
    val a = model.estimate(A, "blitz").get
    val b = model.estimate(B, "blitz").get
    assert(a.rating > b.rating)
    assert(a.sd.get < 60.0, s"a thousand games pin the strength, sd was ${a.sd.get}")
    assertEquals(model.estimate(C, "blitz"), None)
    assertEquals(model.name, "whr(w=30)")

  test("eloProbability and EloPerNat agree with the Elo convention"):
    assertEqualsDouble(eloProbability(1500.0, 1500.0), 0.5, 1e-12)
    assertEqualsDouble(eloProbability(1900.0, 1500.0), 10.0 / 11.0, 1e-12)
    assertEqualsDouble(EloPerNat, 173.7178, 1e-4)
