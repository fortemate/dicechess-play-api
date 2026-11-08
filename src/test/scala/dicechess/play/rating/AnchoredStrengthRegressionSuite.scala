package dicechess.play.rating

import dicechess.play.rating.GraphConnectivity.AdmissionStatus

/** Synthetic regression oracles: all policy values here are fixture values, not deployment settings. */
class AnchoredStrengthRegressionSuite extends munit.FunSuite:
  private val anchors = AnchorSet("fixture-v1", 7, "blitz", List(Anchor("ref/a", 0.0), Anchor("ref/b", 0.0)))
  private val config  = AnchoredStrength.Config(
    anchorSet = anchors,
    bootstrapIterations = 160,
    whiteAdvantageElo = 0.0,
    maxCiWidthElo = Some(80.0)
  )
  private def row(result: AnchoredStrength.Result, player: String): AnchoredStrength.RankedBot =
    (result.allConnected ++ result.disconnected).find(_.player == player).get
  private def probability(delta: Double): Double = 1.0 / (1.0 + math.pow(10.0, -delta / 400.0))

  test("color likelihood recovers equal strength despite unequal color exposure"):
    val h     = 120.0
    val games = (1 to 400).flatMap(_ =>
      Seq(
        ("a", "b", probability(h)),
        ("a", "c", probability(h)),
        ("b", "c", probability(h))
      )
    )
    val corrected = BradleyTerry.ratings(games, h)
    corrected.values.foreach(v => assert(math.abs(v) < 1.0, s"equal strength attributed to color: $corrected"))
    val biased = BradleyTerry.ratings(games)
    assert(biased("a") - biased("c") > 100.0)
    val swapped = BradleyTerry.ratings(games.map((w, b, s) => (b, w, 1.0 - s)), -h)
    corrected.foreach((p, v) => assertEqualsDouble(swapped(p), v, 1e-7))

  test("zero color offset agrees with the two-player smoothed likelihood oracle"):
    val games              = Seq(("a", "b", 1.0), ("a", "b", 1.0), ("b", "a", 0.0), ("b", "a", 1.0))
    val expectedDifference = 400.0 * math.log10(3.25 / 1.25)
    val ratings            = BradleyTerry.ratings(games, 0.0)
    assertEqualsDouble(ratings("a"), expectedDifference / 2.0, 1e-6)
    assertEqualsDouble(ratings("b"), -expectedDifference / 2.0, 1e-6)

  test("point fit and each paired bootstrap replicate use the same color likelihood and anchor scale"):
    val groups = (0 until 24).map { i =>
      val score = if i % 3 == 0 then 0.0 else 1.0
      Seq(
        ("candidate", "ref/a", score),
        ("ref/a", "candidate", 1.0 - score),
        ("candidate", "ref/b", score),
        ("ref/b", "candidate", 1.0 - score)
      )
    }
    val cfg        = config.copy(whiteAdvantageElo = 95.0, maxCiWidthElo = Some(10000.0), seed = 19L)
    val result     = AnchoredStrength.evaluate(groups, cfg)
    val base       = BradleyTerry.ratings(groups.flatten, cfg.whiteAdvantageElo)
    val baseOffset = anchors.scaleOffset(base).get
    val rng        = new scala.util.Random(cfg.seed)
    val samples    = Vector.fill(cfg.bootstrapIterations) {
      val picked = Vector.fill(groups.size)(groups(rng.nextInt(groups.size))).flatten
      val raw    = BradleyTerry.ratings(picked, cfg.whiteAdvantageElo)
      val offset = anchors.scaleOffset(raw).get
      raw.view.mapValues(_ - offset).toMap
    }
    result.allConnected.foreach { bot =>
      val sorted                      = samples.map(_(bot.player)).sorted
      def quantile(p: Double): Double = sorted(math.round(p * (sorted.size - 1)).toInt)
      assertEqualsDouble(bot.elo, base(bot.player) - baseOffset, 1e-8)
      assertEqualsDouble(bot.ciLow, quantile(0.025), 1e-8)
      assertEqualsDouble(bot.ciHigh, quantile(0.975), 1e-8)
      assertEquals(bot.validBootstrapSamples, cfg.bootstrapIterations)
    }
    result.admitted.sliding(2).foreach {
      case List(a, b) =>
        assertEquals(a.losVsNext, Some(samples.count(s => s(a.player) > s(b.player)).toDouble / samples.size))
      case _ => ()
    }
    assertEquals(result.modelVersion, AnchoredStrength.ModelVersion)
    assertEquals(result.whiteAdvantageElo, cfg.whiteAdvantageElo)
    assertEquals(result.maxCiWidthElo, cfg.maxCiWidthElo)
    assertEquals(result, AnchoredStrength.evaluate(groups, cfg))
    val symmetric = AnchoredStrength.evaluate(groups, cfg.copy(whiteAdvantageElo = 0.0))
    // Symmetric color exposure still changes likelihood curvature and hence the interval.
    assert(math.abs(row(result, "candidate").ciHigh - row(symmetric, "candidate").ciHigh) > 0.01)

  test("counts and connectivity cannot admit a wide interval, but additional evidence can"):
    val groups = (0 until 30).map(i =>
      Seq(
        ("candidate", if i % 2 == 0 then "ref/a" else "ref/b", if i % 3 == 0 then 0.0 else 1.0)
      )
    )
    val sparse = AnchoredStrength.evaluate(groups, config)
    val bot    = row(sparse, "candidate")
    assertEquals(bot.games, 30)
    assertEquals(bot.opponents, 2)
    assert(bot.ciHigh - bot.ciLow > config.maxCiWidthElo.get)
    assertEquals(bot.status, AdmissionStatus.Provisional(30, 2))
    assert(!sparse.connectivity.isAdmitted("candidate"))
    assertEquals(bot.losVsNext, None)
    val dense    = AnchoredStrength.evaluate(Vector.fill(25)(groups).flatten, config)
    val admitted = row(dense, "candidate")
    assert(admitted.ciHigh - admitted.ciLow <= config.maxCiWidthElo.get)
    assertEquals(admitted.status, AdmissionStatus.Admitted)
    assert(dense.connectivity.isAdmitted("candidate"))
    assertEquals(
      row(AnchoredStrength.evaluate(groups, config.copy(maxCiWidthElo = None)), "candidate").status,
      AdmissionStatus.Provisional(30, 2)
    )

  test("uncertainty policy rejects missing, nonfinite, reversed, overflowing and incomplete intervals"):
    def accepts(low: Option[Double], high: Option[Double], samples: Int = config.bootstrapIterations): Boolean =
      AnchoredStrength.intervalAdmits(low, high, samples, config)
    assert(accepts(Some(0.0), Some(80.0)))
    assert(!accepts(Some(0.0), Some(80.0001)))
    assert(!accepts(None, Some(1.0)))
    assert(!accepts(Some(0.0), None))
    assert(!accepts(Some(Double.NaN), Some(1.0)))
    assert(!accepts(Some(0.0), Some(Double.PositiveInfinity)))
    assert(!accepts(Some(2.0), Some(1.0)))
    assert(!accepts(Some(-Double.MaxValue), Some(Double.MaxValue)))
    assert(!accepts(Some(0.0), Some(1.0), config.bootstrapIterations - 1))
    assert(!AnchoredStrength.intervalAdmits(Some(0.0), Some(1.0), 39, config.copy(bootstrapIterations = 39)))

  test("disabled or insufficient bootstrap cannot collapse a missing interval to the point estimate"):
    val groups = Vector.fill(30)(Seq(("ref/a", "ref/b", 0.5), ("candidate", "ref/a", 0.5)))
    Seq(0, 1, 39).foreach { n =>
      val result = AnchoredStrength.evaluate(groups, config.copy(bootstrapIterations = n))
      assertEquals(result.admitted, Nil)
      if n == 0 then
        assert(row(result, "candidate").ciLow.isNaN)
        assert(row(result, "candidate").ciHigh.isNaN)
      assert(row(result, "ref/a").isAnchor)
    }

  test("anchor identity never waives count or uncertainty admission"):
    val result    = AnchoredStrength.evaluate(Seq(Seq(("ref/a", "candidate", 0.5))), config)
    val reference = row(result, "ref/a")
    assert(reference.isAnchor)
    assertEquals(reference.status, AdmissionStatus.Provisional(1, 1))
    assert(result.isCalibrated)
    assertEquals(result.admitted, Nil)

  test("lost bootstrap bridges cannot manufacture anchored samples from smoothing or a base offset"):
    val groups    = Seq(Seq(("ref/a", "bridge", 0.5))) ++ Vector.fill(29)(Seq(("bridge", "candidate", 0.5)))
    val cfg       = config.copy(minGamesForAdmission = 1, minOpponentsForAdmission = 1, maxCiWidthElo = Some(10000.0))
    val result    = AnchoredStrength.evaluate(groups, cfg)
    val candidate = row(result, "candidate")
    assert(candidate.validBootstrapSamples > 0)
    assert(candidate.validBootstrapSamples < cfg.bootstrapIterations)
    assert(candidate.status.isInstanceOf[AdmissionStatus.Provisional])
    assert(!row(result, "bridge").isAnchor)

  test("separate anchor-containing components retain their independent reference scales"):
    val anchorSet = anchors.copy(anchors = List(Anchor("ref/a", -200.0), Anchor("ref/b", 300.0)))
    val cfg       = config.copy(anchorSet = anchorSet)
    val first     = Vector.fill(40)(Seq(("ref/a", "candidate/a", 0.5)))
    val second    = Vector.fill(40)(Seq(("ref/b", "candidate/b", 0.5)))
    val alone     = AnchoredStrength.evaluate(first, cfg)
    val combined  = AnchoredStrength.evaluate(first ++ second, cfg)
    assertEqualsDouble(row(alone, "candidate/a").elo, -200.0, 1e-8)
    assertEqualsDouble(row(combined, "candidate/a").elo, row(alone, "candidate/a").elo, 1e-8)
    assertEqualsDouble(row(combined, "candidate/b").elo, 300.0, 1e-8)
    assert(combined.isCalibrated)
    assertEquals(combined.appliedOffset, None, "separate components have no single pool-wide offset")

  test("unanchored islands remain disconnected when real references are present"):
    val groups = Vector.fill(40)(Seq(("ref/a", "candidate", 0.5))) ++
      Vector.fill(40)(Seq(("island/a", "island/b", 1.0)))
    val result = AnchoredStrength.evaluate(groups, config)
    assertEquals(result.disconnected.map(_.player), List("island/a", "island/b"))
    assert(result.disconnected.forall(_.status == AdmissionStatus.Disconnected))
    assert(!result.allConnected.exists(_.player.startsWith("island/")))

  test("invalid configurations and malformed observations fail explicitly"):
    Seq(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity, Double.MaxValue, -Double.MaxValue).foreach { h =>
      intercept[IllegalArgumentException](config.copy(whiteAdvantageElo = h))
      intercept[IllegalArgumentException](BradleyTerry.ratings(Seq(("a", "b", 0.5)), h))
    }
    Seq(0.0, -1.0, Double.NaN, Double.PositiveInfinity).foreach { width =>
      intercept[IllegalArgumentException](config.copy(maxCiWidthElo = Some(width)))
    }
    intercept[IllegalArgumentException](config.copy(bootstrapIterations = -1))
    intercept[IllegalArgumentException](config.copy(minGamesForAdmission = 0))
    intercept[IllegalArgumentException](config.copy(minOpponentsForAdmission = 0))
    Seq(("a", "a", 0.5), ("a", "b", Double.NaN), ("a", "b", -0.1), ("a", "b", 1.1)).foreach { game =>
      intercept[IllegalArgumentException](AnchoredStrength.evaluate(Seq(Seq(game)), config))
    }

  test("high-count separated results converge rather than publishing an iteration-limited fit"):
    val games = Vector.fill(3000)(Seq(("a", "b", 1.0), ("b", "c", 1.0), ("a", "c", 1.0))).flatten
    Seq(0.0, 95.0, -95.0, -1200.0).foreach { h =>
      val ratings = BradleyTerry.ratings(games, h)
      assertEqualsDouble(ratings("b"), 0.0, 1e-5)
      assertEqualsDouble(ratings("a"), -ratings("c"), 1e-5)
      // Independent likelihood score equation for a, with neutral virtual-draw weight per pair.
      val expected = 3000 * (probability(ratings("a") - ratings("b") + h) +
        probability(ratings("a") - ratings("c") + h)) +
        0.5 * (probability(ratings("a") - ratings("b")) + probability(ratings("a") - ratings("c")))
      assertEqualsDouble(expected, 6000.5, 1e-6)
    }

  test("Newton fallback rejects invalid or numerically singular likelihoods explicitly"):
    val points = Array(Array(0.0, 0.25), Array(0.25, 0.0))
    val games  = Array.ofDim[Double](2, 2)
    intercept[BradleyTerryNewton.FitFailure] {
      BradleyTerryNewton.fit(points, games, 1.0, 0.5, Array(Double.NaN, 1.0))
    }
    intercept[BradleyTerryNewton.FitFailure] {
      BradleyTerryNewton.fit(points, games, 0.0, 0.5, Array(1.0, 1.0))
    }
    intercept[BradleyTerryNewton.FitFailure] {
      BradleyTerryNewton.fit(points, games, 1.0, 0.0, Array(1.0, 1.0))
    }
    assertEquals(
      BradleyTerryNewton
        .fit(Array.empty[Array[Double]], Array.empty[Array[Double]], 1.0, 0.5, Array.emptyDoubleArray)
        .toList,
      Nil
    )
    assertEquals(BradleyTerryNewton.fit(Array(Array(0.0)), Array(Array(0.0)), 1.0, 0.5, Array(2.0)).toList, List(1.0))
    val extreme = Array(Double.MaxValue, Double.MinPositiveValue)
    intercept[BradleyTerryNewton.FitFailure] {
      BradleyTerryNewton.fit(points, games, 1.0, 0.5, extreme)
    }
