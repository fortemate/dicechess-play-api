package dicechess.play.core

/** The Bullet / Blitz / Rapid mapping (#280) and the stored-form parser it reaches it through.
  *
  * Three things are pinned here, in order of how expensive they are to get wrong:
  *   - every control the platform actually offers lands where the issue says it does — that table IS the spec;
  *   - the two boundaries, from both sides, because an off-by-one there silently re-buckets a whole speed;
  *   - `TimeControl.parse` round-trips its own `toString`, since a case with no parse branch would not fail to compile
  *     — it would quietly make every game under that control uncategorised.
  */
class RatingCategorySuite extends munit.FunSuite:

  private def categoryOf(timeControl: TimeControl): Option[RatingCategory] = RatingCategory.of(timeControl)

  test("the lobby's own presets bucket exactly as the SPA groups them by hand"):
    // `timeControls.ts`: Blitz = 3+2, 5+3, 5+5, 5 min; Rapid = 10+5, 10+10, 15+10, 10 min. The formula with the
    // measured M reproduces that grouping, which is what lets phase 2 derive the SPA's groups instead of maintaining
    // a parallel list.
    val blitz = List(
      TimeControl.Fischer(180, 2), // 3+2   → 194 s
      TimeControl.Fischer(300, 3), // 5+3   → 321 s (the ladder's own control)
      TimeControl.Fischer(300, 5), // 5+5   → 335 s
      TimeControl.SuddenDeath(300) // 5 min → 300 s
    )
    val rapid = List(
      TimeControl.Fischer(600, 5),  // 10+5   → 635 s
      TimeControl.Fischer(600, 10), // 10+10  → 670 s (also TimeControl.Default)
      TimeControl.Fischer(900, 10), // 15+10  → 970 s
      TimeControl.SuddenDeath(600)  // 10 min → 600 s
    )
    blitz.foreach(tc => assertEquals(categoryOf(tc), Some(RatingCategory.Blitz), s"$tc must be Blitz"))
    rapid.foreach(tc => assertEquals(categoryOf(tc), Some(RatingCategory.Rapid), s"$tc must be Rapid"))

  test("the bot catalog's fastest presets are Bullet and Blitz"):
    assertEquals(categoryOf(TimeControl.Fischer(60, 1)), Some(RatingCategory.Bullet)) // 1+1 → 67 s
    assertEquals(categoryOf(TimeControl.Fischer(180, 3)), Some(RatingCategory.Blitz)) // 3+3 → 201 s

  test("the estimate is initial + 7 x increment, and sudden death is its own initial"):
    assertEquals(RatingCategory.estimatedSeconds(TimeControl.Fischer(300, 3)), Some(321L))
    assertEquals(RatingCategory.estimatedSeconds(TimeControl.Fischer(60, 1)), Some(67L))
    assertEquals(RatingCategory.estimatedSeconds(TimeControl.SuddenDeath(300)), Some(300L))

  test("an absurd increment estimates in Long — in Int it would wrap and read as Bullet"):
    // Nothing validates the seconds a creation request asks for, so this control is storable. 7 x 613566757 exceeds
    // Int.MaxValue by 4; wrapped it is 3 seconds (Bullet), and the database — which computes in bigint — would
    // disagree with us about the same stored row.
    assertEquals(RatingCategory.estimatedSeconds(TimeControl.Fischer(0, 613566757)), Some(4294967299L))
    assertEquals(RatingCategory.of(TimeControl.Fischer(0, 613566757)), Some(RatingCategory.Rapid))

  test("both boundaries are exclusive below and inclusive above"):
    // 179 / 180 around the Bullet ceiling, 479 / 480 around the Blitz one — expressed as sudden death so the
    // estimate IS the number under test, with no multiplication in the way.
    assertEquals(categoryOf(TimeControl.SuddenDeath(179)), Some(RatingCategory.Bullet))
    assertEquals(categoryOf(TimeControl.SuddenDeath(180)), Some(RatingCategory.Blitz))
    assertEquals(categoryOf(TimeControl.SuddenDeath(479)), Some(RatingCategory.Blitz))
    assertEquals(categoryOf(TimeControl.SuddenDeath(480)), Some(RatingCategory.Rapid))

  test("a control that bounds no game length belongs to no scale"):
    assertEquals(categoryOf(TimeControl.Unlimited), None)
    assertEquals(categoryOf(TimeControl.PerMove(30)), None)

  test("TimeControl.parse round-trips every case's own toString — the form game_results stores"):
    val everyShape = List(
      TimeControl.Unlimited,
      TimeControl.SuddenDeath(300),
      TimeControl.Fischer(300, 3),
      TimeControl.Fischer(600, 0),
      TimeControl.PerMove(30)
    )
    everyShape.foreach(tc => assertEquals(TimeControl.parse(tc.toString), Some(tc), s"round trip of $tc"))
    // The literal forms too, so the test also fails if `toString` itself changes shape under us — a round trip
    // alone would happily agree with a renamed encoding on both sides.
    assertEquals(TimeControl.parse("Fischer(300,3)"), Some(TimeControl.Fischer(300, 3)))
    assertEquals(TimeControl.parse("SuddenDeath(300)"), Some(TimeControl.SuddenDeath(300)))
    assertEquals(TimeControl.parse("PerMove(30)"), Some(TimeControl.PerMove(30)))
    assertEquals(TimeControl.parse("Unlimited"), Some(TimeControl.Unlimited))

  test("an unparseable stored control is uncategorised, never an error and never a guess"):
    List("", "Fischer(300, 3)", "Fischer(300)", "Fischer(-1,3)", "Blitz", "Fischer(99999999999,3)").foreach: raw =>
      assertEquals(TimeControl.parse(raw), None, s"'$raw' must not parse")
      assertEquals(RatingCategory.ofStored(raw), None, s"'$raw' must not categorise")

  test("ofStored agrees with of, going through the stored form"):
    assertEquals(RatingCategory.ofStored("Fischer(300,3)"), Some(RatingCategory.Blitz))
    assertEquals(RatingCategory.ofStored("Fischer(60,1)"), Some(RatingCategory.Bullet))
    assertEquals(RatingCategory.ofStored("SuddenDeath(600)"), Some(RatingCategory.Rapid))
    assertEquals(RatingCategory.ofStored("Unlimited"), None)
    assertEquals(RatingCategory.ofStored("PerMove(30)"), None)

  test("wire names round-trip and are the lowercase enum names the database CHECK allows"):
    RatingCategory.values.foreach: category =>
      assertEquals(RatingCategory.fromWireName(category.wireName), Some(category))
    assertEquals(RatingCategory.values.map(_.wireName).toList, List("bullet", "blitz", "rapid"))
    assertEquals(RatingCategory.fromWireName("Blitz"), None)
    assertEquals(RatingCategory.fromWireName("classical"), None)
