package dicechess.play.rating

/** Pure math — no IO, no Docker. Anchors: transitive dominance ordering, the mean-zero anchor, smoothing keeping a
  * 100%-scorer finite, and bootstrap determinism under a fixed seed.
  */
class BradleyTerrySuite extends munit.FunSuite:

  private def repeat(game: BradleyTerry.Game, times: Int): Seq[BradleyTerry.Game] = Seq.fill(times)(game)

  private val dominanceGames =
    repeat(("a", "b", 1.0), 3) ++ repeat(("a", "b", 0.0), 1) ++   // a beats b 3:1
      repeat(("b", "c", 1.0), 3) ++ repeat(("b", "c", 0.0), 1) ++ // b beats c 3:1
      repeat(("a", "c", 1.0), 9) ++ repeat(("a", "c", 0.0), 1)    // a crushes c 9:1

  test("a transitive dominance corpus ranks a > b > c with mean-zero relative Elo"):
    val elo = BradleyTerry.ratings(dominanceGames)
    assert(elo("a") > elo("b") && elo("b") > elo("c"), s"expected a > b > c, got $elo")
    assertEqualsDouble(elo.values.sum, 0.0, 1e-6)

  test("a perfectly symmetric matchup lands both players on zero"):
    val elo = BradleyTerry.ratings(repeat(("x", "y", 1.0), 5) ++ repeat(("x", "y", 0.0), 5))
    assertEqualsDouble(elo("x"), 0.0, 1e-6)
    assertEqualsDouble(elo("y"), 0.0, 1e-6)

  test("draws count half a win each way"):
    val elo = BradleyTerry.ratings(repeat(("x", "y", 0.5), 10))
    assertEqualsDouble(elo("x"), elo("y"), 1e-6)

  test("the virtual draw keeps an undefeated player's rating finite"):
    val elo = BradleyTerry.ratings(repeat(("champ", "victim", 1.0), 50))
    assert(elo("champ").isFinite && elo("champ") > 0)
    assert(elo("victim").isFinite && elo("victim") < 0)

  test("bootstrap is deterministic for a fixed seed and orders CIs sanely"):
    val groups = dominanceGames.grouped(2).toSeq
    val first  = BradleyTerry.rankedWithBootstrap(groups, iterations = 200, seed = 7L)
    val second = BradleyTerry.rankedWithBootstrap(groups, iterations = 200, seed = 7L)
    assertEquals(first, second, "same seed must reproduce the identical report")
    assertEquals(first.map(_.player), List("a", "b", "c"))
    first.foreach { row =>
      assert(row.ciLow <= row.ciHigh, s"$row")
    }
    assert(first.last.losVsNext.isEmpty, "the last row has no neighbour")

  test("LOS against the next rank is high when the gap is decisive"):
    val lopsided = repeat(("a", "b", 1.0), 40) ++ repeat(("a", "b", 0.0), 2)
    val ranked   = BradleyTerry.rankedWithBootstrap(lopsided.grouped(2).toSeq, iterations = 300, seed = 11L)
    assertEquals(ranked.head.player, "a")
    assert(ranked.head.losVsNext.exists(_ > 0.95), s"expected decisive LOS, got ${ranked.head.losVsNext}")

  /** A deliberately awkward corpus for the golden vector below: four players, uneven group sizes, and `dave` present in
    * exactly ONE group — so a good share of resamples omit him entirely and exercise the "a bootstrap sample ranks only
    * the players it actually contains" path, which a corpus where everyone appears everywhere would never reach.
    */
  private val goldenGroups: Seq[Seq[BradleyTerry.Game]] = Seq(
    Seq(("alice", "bob", 1.0), ("bob", "alice", 0.0)),
    Seq(("alice", "carol", 0.5)),
    Seq(("bob", "carol", 1.0), ("carol", "bob", 0.5)),
    Seq(("alice", "bob", 0.0)),
    Seq(("carol", "dave", 1.0), ("dave", "carol", 0.0)),
    Seq(("alice", "carol", 1.0))
  )

  test("the bootstrap is a fixed function of (corpus, iterations, seed) — golden vector"):
    // Recorded from the implementation as it stood when this was written. The point is not that these numbers are
    // "right" — the surrounding tests cover the statistics — but that OPTIMISING the fit must not move them: the
    // report is reproducible by seed, so a changed number is a changed published ranking.
    //
    // Compared with a tolerance rather than `==`, and the tolerance is the interesting part. On one JVM the values
    // ARE bit-exact, which is what makes this a useful regression guard. Across platforms they are not: `Math.log`,
    // `Math.exp`, and `Math.log10` are permitted 1 ulp of error and use different intrinsics per architecture, so an
    // aarch64 dev machine and an x86-64 CI runner disagree in the last digit or two (observed: 1e-14 on values of
    // magnitude 300 — this test failed exactly that way on its first CI run). 1e-9 is orders of magnitude above that
    // noise and orders of magnitude below any structural change: dropping the per-resample player subsetting, or
    // reordering the accumulation, moves whole Elo points, and LOS moves in steps of 1/iterations.
    val expected = List(
      ("alice", 134.39508571506718, -71.59493280482212, 332.4184545365102, Some(0.5979381443298969)),
      ("bob", 61.064746775853656, -133.19303125947457, 223.98402138106349, Some(0.7448979591836735)),
      ("carol", 3.438752686306725, -144.32867782314693, 160.77211715151589, Some(1.0)),
      ("dave", -198.89858517722757, -323.90795132933715, -155.79970059805692, None)
    )
    val actual = BradleyTerry.rankedWithBootstrap(goldenGroups, iterations = 100, seed = 2026L)
    assertEquals(actual.map(_.player), expected.map(_._1), "the ranking order itself must not move")
    actual.zip(expected).foreach { case (row, (player, elo, ciLow, ciHigh, los)) =>
      assertEqualsDouble(row.elo, elo, 1e-9, s"$player elo")
      assertEqualsDouble(row.ciLow, ciLow, 1e-9, s"$player ciLow")
      assertEqualsDouble(row.ciHigh, ciHigh, 1e-9, s"$player ciHigh")
      assertEquals(row.losVsNext.isDefined, los.isDefined, s"$player losVsNext presence")
      row.losVsNext.zip(los).foreach((got, want) => assertEqualsDouble(got, want, 1e-9, s"$player losVsNext"))
    }

  test("lopsided multi-player corpus with undefeated/winless players and unplayed pairs - regression test"):
    val lopsidedGames: Seq[BradleyTerry.Game] = Seq(
      ("alpha", "beta", 1.0),
      ("alpha", "beta", 1.0),
      ("beta", "gamma", 1.0),
      ("beta", "gamma", 0.5),
      ("gamma", "delta", 1.0),
      ("gamma", "delta", 1.0)
    )
    val expected = Map(
      "alpha" -> 206.36668287373766,
      "beta"  -> 3.180168593266478,
      "gamma" -> -3.1801685864093105,
      "delta" -> -206.36668288059482
    )
    val elos = BradleyTerry.ratings(lopsidedGames)
    assertEquals(elos.keySet, expected.keySet)
    expected.foreach { (player, wantElo) =>
      assertEqualsDouble(elos(player), wantElo, 1e-9, s"$player elo drift")
    }

  test("empty and single-player inputs degrade gracefully"):
    assertEquals(BradleyTerry.ratings(Nil), Map.empty[String, Double])
    assertEquals(BradleyTerry.rankedWithBootstrap(Nil, iterations = 10, seed = 1L), Nil)
