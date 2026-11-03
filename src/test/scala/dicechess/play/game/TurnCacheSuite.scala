package dicechess.play.game

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class TurnCacheSuite extends munit.CatsEffectSuite:
  private def pendingRoll(builds: AtomicInteger): GameRoom.Session =
    val state = EngineOps.withDice(EngineOps.parse(EngineOps.InitialDfen).toOption.get, List(1, 2, 3))
    val turns = EngineOps.legalMovePaths(state).map(path => path.map(EngineOps.toUci) -> path).toMap
    val cache = new GameRoom.TurnCache(
      turns, {
        builds.incrementAndGet()
        MoveTree.fromPaths(turns.keys.toList)
      }
    )
    GameRoom.Session(
      state = state,
      version = 1L,
      players = Map.empty,
      displayNames = Map.empty,
      ratings = Map.empty,
      dice = DiceSource.commitReveal("lazy-tree-fixture".getBytes("UTF-8")),
      ply = 1L,
      pending = true,
      status = GameStatus.Active,
      timeControl = TimeControl.Unlimited,
      turnCache = cache
    )

  test("a pending roll with cap zero serves snapshots and validates paths without building its tree"):
    val builds = new AtomicInteger()
    val roll   = pendingRoll(builds)
    val path   = roll.turnCache.turns.keys.head
    assert(path.nonEmpty)
    assert(roll.turnCache.turns.get(path).exists(_.nonEmpty))
    assertEquals(roll.turnCache.inline(0), None)
    assertEquals(roll.publicAt(Duration.Zero, 0).legalMoves, None)
    assertEquals(roll.copy(version = 2L).publicAt(Duration.Zero, 0).legalMoves, None)
    assertEquals(builds.get(), 0)

  test("concurrent full-tree readers build once and share the same tree across session copies"):
    val builds = new AtomicInteger()
    val roll   = pendingRoll(builds)
    val copy   = roll.copy(version = 2L)
    List
      .range(0, 64)
      .parTraverse { i =>
        IO(if i % 2 == 0 then roll.turnCache.tree else copy.turnCache.tree)
      }
      .map { trees =>
        assertEquals(builds.get(), 1)
        assert(trees.forall(_ eq trees.head))
        assertEquals(trees.head, MoveTree.fromPaths(roll.turnCache.turns.keys.toList))
        assertEquals(roll.publicAt(Duration.Zero, 0).legalMoves, None)
      }

  test("the cap includes its boundary and the default preserves inline trees for small rolls"):
    val builds = new AtomicInteger()
    val roll   = pendingRoll(builds)
    val count  = roll.turnCache.turns.size
    assert(count > 1)
    assert(count <= GameRoom.DefaultMaxInlineTurnPaths)
    assertEquals(roll.turnCache.inline(count - 1), None)
    assertEquals(builds.get(), 0)
    assertEquals(roll.turnCache.inline(count), Some(roll.turnCache.tree))
    assertEquals(roll.publicAt(Duration.Zero, GameRoom.DefaultMaxInlineTurnPaths).legalMoves, Some(roll.turnCache.tree))
    assertEquals(builds.get(), 1)

  test("cap zero elides forced passes without building even an empty tree"):
    val builds = new AtomicInteger()
    val cache  = new GameRoom.TurnCache(Map.empty, { builds.incrementAndGet(); MoveTree.empty })
    assertEquals(cache.inline(0), None)
    assertEquals(builds.get(), 0)
    assertEquals(cache.inline(GameRoom.DefaultMaxInlineTurnPaths), Some(MoveTree.empty))
    assertEquals(builds.get(), 1)
