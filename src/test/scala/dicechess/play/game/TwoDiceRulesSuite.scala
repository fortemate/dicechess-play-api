package dicechess.play.game

import dicechess.engine.domain.GameState
import dicechess.play.core.{RulesVersion, Side}

/** Independent positions and UCI witnesses for the laboratory's chosen two-dice pool. No bot/search dependency is used
  * here: this qualifies the rules artifact the server actually resolves, not another checkout of the engine.
  */
class TwoDiceRulesSuite extends munit.FunSuite:

  private def position(fen: String, dice: List[Int]): GameState =
    EngineOps.withDice(EngineOps.parse(fen).fold(fail(_), identity), dice)

  private def paths(state: GameState): Set[List[String]] =
    EngineOps.legalMovePaths(state).map(_.map(EngineOps.toUci)).toSet

  test("the fixture report identifies the resolved server rules dependency"):
    assert(RulesVersion.value.nonEmpty)

  test("a pawn can unblock its bishop and both dice must be consumed"):
    val state = position(EngineOps.InitialDfen, List(1, 3))
    val legal = paths(state)
    assert(legal.contains(List("e2e3", "f1c4")))
    assert(!legal.contains(List("e2e3")))
    assert(legal.forall(_.size == 2))
    assertEquals(EngineOps.serialize(state).split(" ").last.toLowerCase, "pb")

  test("duplicate knight dice allow the same knight to move twice"):
    val legal = paths(position(EngineOps.InitialDfen, List(2, 2)))
    assert(legal.contains(List("g1f3", "f3g5")))
    assert(!legal.contains(List("g1f3")))
    assert(legal.forall(_.size == 2))

  test("castling consumes the king and rook dice in one micro-move"):
    val fen = "4k3/8/8/8/8/8/8/4K2R w K - 0 1"
    assert(paths(position(fen, List(6, 4))).contains(List("e1g1")))
    assert(!paths(position(fen, List(6))).exists(_.contains("e1g1")))

  test("a promoted queen can spend the queen die and capture the king"):
    val state = position("7k/P7/8/8/8/8/8/7K w - - 0 1", List(1, 5))
    val legal = EngineOps.legalMovePaths(state)
    val path  = legal
      .find(_.map(EngineOps.toUci) == List("a7a8q", "a8h8"))
      .getOrElse(fail("expected the independently specified promotion-and-capture path"))
    assertEquals(EngineOps.applyPath(state, path)._2, Some(Side.White))

  test("an already adjacent pawn may capture en passant and then move again"):
    val legal = paths(position("7k/8/8/3pP3/8/8/8/7K w - d6 0 2", List(1, 1)))
    assertEquals(legal, Set(List("e5d6", "d6d7"), List("e5e6", "e6e7")))

  test("a pawn reaching the adjacent rank during the turn cannot gain en-passant eligibility"):
    val legal = paths(position("7k/8/8/3p4/4P3/8/8/7K w - d6 0 2", List(1, 1)))
    // e4xd5 is an ordinary capture, followed by d5-d6. Only e5xd6 en passant is forbidden after e4-e5.
    assertEquals(legal, Set(List("e4e5", "e5e6"), List("e4d5", "d5d6")))

  test("an unusable selected pair has no legal turn"):
    assertEquals(paths(position(EngineOps.InitialDfen, List(3, 4))), Set.empty[List[String]])

  test("an immediate king capture remains legal even if another die could be spent"):
    val state = position("7k/8/8/8/8/8/P7/K6Q w - - 0 1", List(5, 1))
    val legal = EngineOps.legalMovePaths(state)
    assert(legal.exists(_.map(EngineOps.toUci) == List("a2a3", "h1h8")))
    val win = legal
      .find(_.map(EngineOps.toUci) == List("h1h8"))
      .getOrElse(fail("the king-capture exception permits a one-move win"))
    assertEquals(EngineOps.applyPath(state, win)._2, Some(Side.White))
