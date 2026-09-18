package dicechess.play.rating

import dicechess.play.core.RatingCategory
import munit.FunSuite

import java.time.Instant
import java.time.temporal.ChronoUnit

class TrainingEstimateSuite extends FunSuite:

  test("Initial TrainingState has default Glicko-2 prior and provisional status"):
    val state = TrainingState.Initial
    assertEquals(state.rating, 1500.0)
    assertEquals(state.deviation, 350.0)
    assertEquals(state.volatility, 0.06)
    assertEquals(state.games, 0)
    assertEquals(state.wins, 0)
    assertEquals(state.draws, 0)
    assertEquals(state.losses, 0)
    assert(state.isProvisional, "Initial state with 0 games must be provisional")
    assertEquals(state.conservativeRating, 1500.0 - 2.0 * 350.0) // 800.0
    val (low, high) = state.confidenceInterval95
    assertEqualsDouble(low, 1500.0 - 1.96 * 350.0, 1e-6)
    assertEqualsDouble(high, 1500.0 + 1.96 * 350.0, 1e-6)

  test("resolveBotReference selects anchor target in matching category and never borrows across categories"):
    val anchorSet                  = AnchorSet.V1_0 // Blitz
    val storedNone: Option[Glicko] = None

    // Blitz matches anchor set
    val randomBlitz = TrainingEstimate.resolveBotReference("anchor/random", RatingCategory.Blitz, anchorSet, storedNone)
    assert(randomBlitz.isDefined)
    assertEquals(randomBlitz.get.rating, 1500.0 - 610.0)                      // 890.0
    assertEquals(randomBlitz.get.deviation, TrainingEstimate.AnchorDeviation) // 50.0

    // Rapid does NOT match anchor set V1.0 category (Blitz)
    val randomRapid = TrainingEstimate.resolveBotReference("anchor/random", RatingCategory.Rapid, anchorSet, storedNone)
    assertEquals(randomRapid, None, "Must never borrow Blitz anchor for Rapid game")

    // Stored category rating is used when category matches
    val customRapidGlicko = Glicko(1650.0, 75.0, 0.05)
    val customRapid       = TrainingEstimate.resolveBotReference(
      "team/bot",
      RatingCategory.Rapid,
      anchorSet,
      Some(customRapidGlicko)
    )
    assertEquals(customRapid, Some(customRapidGlicko))

  test("first-move White advantage shifts opponent effective reference rating"):
    val botRef = Glicko(1500.0, 50.0, 0.06)

    val whenHumanIsWhite = Glicko2.withWhiteAdvantage(botRef, playerIsWhite = true, whiteAdvantage = 25.0)
    assertEquals(whenHumanIsWhite.rating, 1475.0, "Opponent shifted down by 25 when player is White")

    val whenHumanIsBlack = Glicko2.withWhiteAdvantage(botRef, playerIsWhite = false, whiteAdvantage = 25.0)
    assertEquals(whenHumanIsBlack.rating, 1525.0, "Opponent shifted up by 25 when player is Black")

    // Symmetry check in forecast probabilities
    val pWhite = Glicko2.expectedScore(Glicko.Initial, whenHumanIsWhite)
    val pBlack = Glicko2.expectedScore(Glicko.Initial, whenHumanIsBlack)
    assertEqualsDouble(pWhite + pBlack, 1.0, 1e-9)

  test("winning as Black produces a larger rating increase than winning as White against equal opponent"):
    val now     = Instant.parse("2026-09-16T12:00:00Z")
    val botRef  = Glicko(1500.0, 50.0, 0.06)
    val initial = TrainingState.Initial

    val updateAsWhite = TrainingEstimate.update(initial, botRef, humanIsWhite = true, score = 1.0, gameTime = now)
    val updateAsBlack = TrainingEstimate.update(initial, botRef, humanIsWhite = false, score = 1.0, gameTime = now)

    assert(
      updateAsBlack.rating > updateAsWhite.rating,
      s"Black win (${updateAsBlack.rating}) must yield higher rating than White win (${updateAsWhite.rating})"
    )

  test("inactivity inflation widens RD for returning players and caps at initial deviation"):
    val t0        = Instant.parse("2026-08-01T12:00:00Z")
    val converged = TrainingState(
      rating = 1600.0,
      deviation = 80.0,
      volatility = 0.06,
      games = 20,
      wins = 12,
      draws = 4,
      losses = 4,
      updatedAt = Some(t0)
    )

    // Same day: no idle inflation
    val sameDay = TrainingEstimate.update(
      converged,
      Glicko(1500.0, 50.0, 0.06),
      humanIsWhite = true,
      score = 0.5,
      gameTime = t0
    )
    assert(sameDay.deviation < converged.deviation, "deviation should decrease after a game on the same day")

    // 20 days later: inflation should increase deviation prior to game
    val t20         = t0.plus(20, ChronoUnit.DAYS)
    val after20Days = TrainingEstimate.update(
      converged,
      Glicko(1500.0, 50.0, 0.06),
      humanIsWhite = true,
      score = 0.5,
      gameTime = t20
    )
    val expectedPreDev = math.sqrt(80.0 * 80.0 + 10.0 * 10.0 * 20.0) // sqrt(6400 + 2000) = sqrt(8400) ≈ 91.65
    val inflated       = Glicko2.inflateIdleDeviation(80.0, 20.0, idleEloPerSqrtDay = 10.0)
    assertEqualsDouble(inflated, expectedPreDev, 1e-6)
    assert(after20Days.games == 21)

    // 1500 days later: capped at 350.0
    val capped = Glicko2.inflateIdleDeviation(80.0, 1500.0, idleEloPerSqrtDay = 10.0)
    assertEquals(capped, 350.0)

  test("provisional status respects both game count and RD threshold"):
    // Under 10 games with settled RD is still provisional
    val fewGames = TrainingState(1500.0, 90.0, 0.06, games = 9, wins = 5, draws = 2, losses = 2)
    assert(fewGames.isProvisional, "games < 10 must be provisional")

    // 10+ games with high RD (> 110) is provisional
    val highRd = TrainingState(1500.0, 115.0, 0.06, games = 15, wins = 8, draws = 3, losses = 4)
    assert(highRd.isProvisional, "RD > 110 must be provisional")

    // 10+ games with low RD (<= 110) is settled / non-provisional
    val settled = TrainingState(1500.0, 95.0, 0.06, games = 10, wins = 6, draws = 2, losses = 2)
    assert(!settled.isProvisional, "games >= 10 and RD <= 110 must be non-provisional")

  test("one-way update never alters bot reference state"):
    val botRef             = Glicko(1450.0, 48.0, 0.055)
    val originalRating     = botRef.rating
    val originalDeviation  = botRef.deviation
    val originalVolatility = botRef.volatility

    val human        = TrainingState.Initial
    val updatedHuman = TrainingEstimate.update(
      human,
      botRef,
      humanIsWhite = true,
      score = 1.0,
      gameTime = Instant.now()
    )

    assertEquals(botRef.rating, originalRating)
    assertEquals(botRef.deviation, originalDeviation)
    assertEquals(botRef.volatility, originalVolatility)
    assert(updatedHuman.games == 1)
    assert(updatedHuman.wins == 1)
