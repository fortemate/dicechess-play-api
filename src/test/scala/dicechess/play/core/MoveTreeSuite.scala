package dicechess.play.core

class MoveTreeSuite extends munit.FunSuite:

  test("fromPaths groups shared prefixes into one subtree"):
    val tree = MoveTree.fromPaths(List(List("e2e4", "g1f3"), List("e2e4", "b1c3"), List("d2d4")))
    assertEquals(
      tree,
      MoveTree(
        Map(
          "e2e4" -> MoveTree(Map("g1f3" -> MoveTree.empty, "b1c3" -> MoveTree.empty)),
          "d2d4" -> MoveTree.empty
        )
      )
    )

  test("fromPaths of no paths is the empty tree (a forced pass)"):
    assertEquals(MoveTree.fromPaths(Nil), MoveTree.empty)

  test("fromPaths drops empty paths — a pass is signalled by the empty tree, not encoded in it"):
    assertEquals(MoveTree.fromPaths(List(Nil)), MoveTree.empty)
    assertEquals(MoveTree.fromPaths(List(Nil, List("e2e4"))), MoveTree(Map("e2e4" -> MoveTree.empty)))

  test("a king-capturing short turn stays an exclusive leaf"):
    // The engine never generates a continuation past a king capture, so a shorter path only ever
    // shares a prefix with nothing — the tree keeps it as a childless (= complete) node.
    val tree = MoveTree.fromPaths(List(List("d5e6"), List("d2d4", "g1f3")))
    assertEquals(
      tree,
      MoveTree(Map("d5e6" -> MoveTree.empty, "d2d4" -> MoveTree(Map("g1f3" -> MoveTree.empty))))
    )
