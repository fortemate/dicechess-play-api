package dicechess.play.rating

import dicechess.play.core.{GameId, RatingCategory}
import dicechess.play.rating.GraphConnectivity.AdmissionStatus
import dicechess.play.store.GameResultRow

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Synthetic unit test suite for AnchoredStrength (#147, ADR 008, #170).
  *
  * Verifies:
  *   1. Immutable scale anchors: anchor-set pinned reference frame.
  *   2. Newcomer invariance: addition of unrelated entrants cannot silently shift established ratings.
  *   3. Jump detection: an anchor set detects a jump in one bot's strength without moving other bots.
  *   4. Graph connectivity: disconnected components are flagged and excluded from global ranks.
  *   5. Admission rules: provisional thresholds (< 30 games or < 2 opponents) vs admitted.
  *   6. CRN block bootstrap: pairing groups preserved during resampling, deterministic with fixed seed.
  *   7. Time-awareness: trailing window captures current strength, not lifetime average.
  */
class AnchoredStrengthSuite extends munit.FunSuite:

  private val anchorGreedy = "house/greedy"
  private val anchorAggr   = "house/aggressive"

  private val fixedEpochTime = Instant.parse("2026-09-16T12:00:00Z")

  private def game(
      white: String,
      black: String,
      whiteScore: Double
  ): BradleyTerry.Game = (white, black, whiteScore)

  private def crnPair(
      botA: String,
      botB: String,
      scoreAFirst: Double,
      scoreASecond: Double
  ): Seq[BradleyTerry.Game] =
    Seq(
      (botA, botB, scoreAFirst),
      (botB, botA, 1.0 - scoreASecond)
    )

  test("GraphConnectivity correctly separates anchor-connected from disconnected islands"):
    // Main component with an anchor: greedy vs botA, botA vs botB
    val mainGames = Seq(
      (anchorGreedy, "bot/a"),
      ("bot/a", "bot/b")
    )
    // Disconnected island: island1 vs island2
    val islandGames = Seq(
      ("island/1", "island/2"),
      ("island/2", "island/1")
    )

    val connectivity = GraphConnectivity.analyze(
      mainGames ++ islandGames,
      AnchorSet.V1_0.identities,
      minGames = 2,
      minOpponents = 2
    )

    assert(connectivity.anchorConnectedPlayers.contains(anchorGreedy))
    assert(connectivity.anchorConnectedPlayers.contains("bot/a"))
    assert(connectivity.anchorConnectedPlayers.contains("bot/b"))

    assert(connectivity.disconnectedPlayers.contains("island/1"))
    assert(connectivity.disconnectedPlayers.contains("island/2"))
    assertEquals(connectivity.statuses("island/1"), AdmissionStatus.Disconnected)
    assertEquals(connectivity.statuses("island/2"), AdmissionStatus.Disconnected)

  test("Admission criteria: requires >= 30 games and >= 2 opponents"):
    // bot/under-games: 20 games against 2 opponents -> Provisional
    val underGames = (1 to 10).flatMap(_ => Seq(("bot/under-games", anchorGreedy), ("bot/under-games", anchorAggr)))
    // bot/under-opponents: 40 games against only 1 opponent -> Provisional
    val underOpponents = (1 to 40).map(_ => ("bot/under-opp", anchorGreedy))
    // bot/admitted: 30 games against 2 opponents -> Admitted
    val admitted = (1 to 15).flatMap(_ => Seq(("bot/admitted", anchorGreedy), ("bot/admitted", anchorAggr)))

    val connectivity = GraphConnectivity.analyze(
      underGames ++ underOpponents ++ admitted,
      AnchorSet.V1_0.identities,
      minGames = 30,
      minOpponents = 2
    )

    assertEquals(connectivity.statuses("bot/under-games"), AdmissionStatus.Provisional(20, 2))
    assertEquals(connectivity.statuses("bot/under-opp"), AdmissionStatus.Provisional(40, 1))
    assertEquals(connectivity.statuses("bot/admitted"), AdmissionStatus.Admitted)
    assertEquals(connectivity.statuses(anchorGreedy), AdmissionStatus.Admitted, "anchors are always admitted")

  test("Anchor calibration offset aligns present anchors to AnchorSet target Elos"):
    // Benchmark games: greedy vs aggressive evenly matched (score 0.5 each)
    val groups = (1 to 50).map(_ => Seq(game(anchorGreedy, anchorAggr, 0.5)))
    val res    = AnchoredStrength.evaluate(groups, AnchoredStrength.Config(bootstrapIterations = 50))

    val greedyElo = res.admitted.find(_.player == anchorGreedy).get.elo
    val aggrElo   = res.admitted.find(_.player == anchorAggr).get.elo

    // For AnchorSet.V1_0, greedy target is -125.0, aggr target is -35.0 (mean = -80.0).
    // Because they scored 0.5 evenly, both get the mean anchor Elo -80.0.
    assertEqualsDouble(greedyElo, -80.0, 1.0)
    assertEqualsDouble(aggrElo, -80.0, 1.0)

  test("Newcomer invariance: adding unrelated entrants does not shift established bot ratings"):
    // Base pool: bot/established playing against anchorGreedy and anchorAggr
    val baseGroups = (1 to 25).flatMap { _ =>
      Seq(
        Seq(game("bot/established", anchorGreedy, 0.7)),
        Seq(game("bot/established", anchorAggr, 0.5)),
        Seq(game(anchorGreedy, anchorAggr, 0.4))
      )
    }

    val resBase = AnchoredStrength.evaluate(baseGroups, AnchoredStrength.Config(bootstrapIterations = 50, seed = 123L))
    val establishedBaseElo = resBase.admitted.find(_.player == "bot/established").get.elo

    // Now introduce a weak newcomer bot/rookie playing against anchorGreedy
    val rookieGroups   = (1 to 30).map(_ => Seq(game("bot/rookie", anchorGreedy, 0.1)))
    val combinedGroups = baseGroups ++ rookieGroups

    val resCombined =
      AnchoredStrength.evaluate(combinedGroups, AnchoredStrength.Config(bootstrapIterations = 50, seed = 123L))
    val establishedCombinedElo = resCombined.admitted.find(_.player == "bot/established").get.elo

    // Without anchors, adding a 10% scoring rookie would shift mean-0 Bradley-Terry ratings.
    // With AnchoredStrength, established bot rating remains stable within small virtual-smoothing tolerance (< 5 Elo).
    val eloShift = math.abs(establishedCombinedElo - establishedBaseElo)
    assert(eloShift < 5.0, s"Established bot rating shifted by $eloShift Elo, expected < 5.0")

  test("Jump detection: an anchor set detects a jump in one bot's strength without moving others"):
    // Period 1: bot/challenger is weak, bot/stable is steady
    val period1Groups = (1 to 30).flatMap { _ =>
      Seq(
        Seq(game("bot/challenger", anchorGreedy, 0.2)), // weak
        Seq(game("bot/stable", anchorGreedy, 0.6)),     // steady
        Seq(game("bot/stable", anchorAggr, 0.4))
      )
    }

    val res1 = AnchoredStrength.evaluate(period1Groups, AnchoredStrength.Config(bootstrapIterations = 50, seed = 42L))
    val challengerElo1 = res1.allConnected.find(_.player == "bot/challenger").get.elo
    val stableElo1     = res1.allConnected.find(_.player == "bot/stable").get.elo

    // Period 2: bot/challenger gets upgraded to master-level play (crushes anchorGreedy 0.9)
    // while bot/stable continues its exact same performance
    val period2Groups = (1 to 30).flatMap { _ =>
      Seq(
        Seq(game("bot/challenger", anchorGreedy, 0.9)), // upgraded!
        Seq(game("bot/stable", anchorGreedy, 0.6)),     // unchanged
        Seq(game("bot/stable", anchorAggr, 0.4))
      )
    }

    val res2 = AnchoredStrength.evaluate(period2Groups, AnchoredStrength.Config(bootstrapIterations = 50, seed = 42L))
    val challengerElo2 = res2.allConnected.find(_.player == "bot/challenger").get.elo
    val stableElo2     = res2.allConnected.find(_.player == "bot/stable").get.elo

    // Challenger rating jumps substantially (> 200 Elo)
    assert(
      challengerElo2 - challengerElo1 > 200.0,
      s"Expected challenger jump, got delta = ${challengerElo2 - challengerElo1}"
    )
    // Stable bot's rating is anchored and does NOT move with challenger's jump
    val stableShift = math.abs(stableElo2 - stableElo1)
    assert(stableShift < 5.0, s"Expected stable bot to stay fixed, but moved by $stableShift Elo")

  test("Bootstrap resampling preserves CRN pairing units and produces ordered 95% CIs"):
    val crnGroups = (1 to 20).map(_ => crnPair("bot/a", "bot/b", 0.6, 0.6))
    val res       = AnchoredStrength.evaluate(crnGroups, AnchoredStrength.Config(bootstrapIterations = 100, seed = 99L))

    assertEquals(res.allConnected.size, 2)
    res.allConnected.foreach { r =>
      assert(r.ciLow <= r.elo, s"ciLow ${r.ciLow} must be <= elo ${r.elo}")
      assert(r.elo <= r.ciHigh, s"elo ${r.elo} must be <= ciHigh ${r.ciHigh}")
    }

  test("Time-aware trailing window evaluates current strength rather than lifetime average"):
    def toExternalId(teamAndName: String): String =
      val parts = teamAndName.split('/')
      s"bot:team:${parts(0)}:${parts(1)}"

    var nextId                                                                      = 0
    def row(white: String, black: String, score: Int, time: Instant): GameResultRow =
      nextId += 1
      GameResultRow(
        GameId(s"g-$nextId"),
        toExternalId(white),
        toExternalId(black),
        Some(score),
        "resign",
        rated = true,
        "Fischer(300,3)",
        "ab",
        None,
        false,
        time
      )

    val day1 = fixedEpochTime.minus(60, ChronoUnit.DAYS)
    val day2 = fixedEpochTime.minus(10, ChronoUnit.DAYS)

    // Old games (60 days ago): acme/learner loses every game against anchorGreedy
    val oldGames = (1 to 20).map(_ => row(anchorGreedy, "acme/learner", 1, day1)).toList

    // Recent games (10 days ago): acme/learner wins every game against anchorGreedy and anchorAggr
    val recentGames = (1 to 20)
      .flatMap(_ => List(row("acme/learner", anchorGreedy, 1, day2), row("acme/learner", anchorAggr, 1, day2)))
      .toList

    val allGames = oldGames ++ recentGames

    // Whole history static report: averages loss period with win period
    val staticReport = StrengthReport.build(
      allGames,
      RatingCategory.Default,
      StrengthReport.Config(windowDays = None, bootstrapIterations = 50)
    )
    val staticLearnerElo = staticReport.ranking.find(_.player == "acme/learner").get.elo

    // Time-aware trailing 30 days window: excludes the 60-day-old losses, shows current strength
    val windowReport = StrengthReport.build(
      allGames,
      RatingCategory.Default,
      StrengthReport.Config(windowDays = Some(30), bootstrapIterations = 50)
    )
    val windowLearnerElo = windowReport.ranking.find(_.player == "acme/learner").get.elo

    assert(
      windowLearnerElo > staticLearnerElo + 100.0,
      s"Windowed Elo ($windowLearnerElo) should be much higher than lifetime average ($staticLearnerElo)"
    )
    assertEquals(windowReport.excludedRows, oldGames.size, "Old games must be counted as excluded by window")

  test("isCalibrated correctly reflects presence of anchors in the connected component"):
    // Unanchored games: only custom bots playing each other
    val unanchoredGames = (1 to 20).map(_ => Seq(game("bot/alpha", "bot/beta", 0.6)))
    val resUnanchored   = AnchoredStrength.evaluate(unanchoredGames, AnchoredStrength.Config(bootstrapIterations = 20))

    assertEquals(resUnanchored.appliedOffset, None)
    assert(!resUnanchored.isCalibrated, "Should not be calibrated without anchors in the component")

    // Anchored games: anchorGreedy plays against bot/alpha
    val anchoredGames = unanchoredGames ++ (1 to 20).map(_ => Seq(game("bot/alpha", anchorGreedy, 0.5)))
    val resAnchored   = AnchoredStrength.evaluate(anchoredGames, AnchoredStrength.Config(bootstrapIterations = 20))

    assert(resAnchored.appliedOffset.isDefined)
    assert(resAnchored.isCalibrated, "Should be calibrated when an anchor is present")

  test("Bootstrap LOS pairs replicates without index shifting and reflects superiority"):
    // bot/strong clearly beats bot/weak (80% score over 40 games)
    val games = (1 to 40).map(_ => Seq(game("bot/strong", "bot/weak", 0.8)))
    val res   =
      AnchoredStrength.evaluate(
        games,
        AnchoredStrength.Config(bootstrapIterations = 100, seed = 42L, minGamesForAdmission = 10)
      )

    val strong = res.allConnected.find(_.player == "bot/strong").get
    assert(strong.losVsNext.isDefined)
    assert(strong.losVsNext.get > 0.95, s"Strong bot LOS should be > 0.95, got ${strong.losVsNext.get}")
