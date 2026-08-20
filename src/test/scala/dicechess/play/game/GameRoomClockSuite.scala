package dicechess.play.game

import cats.effect.IO
import cats.syntax.all.*
import dicechess.engine.search.BotRegistry
import dicechess.play.core.*
import dicechess.play.dice.DiceSource

import scala.concurrent.duration.*

/** Enforcement of the real per-side clocks (#46). Flag-fall tests use a tiny clock against the default 120s
  * `idleCheck`: completing in a second or two proves the chess clock — not the anti-abandonment cap — ended the game.
  */
class GameRoomClockSuite extends munit.CatsEffectSuite:

  private def greedy = BotRegistry.getAlgorithm("greedy").get
  private def dice   = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
  private def seats  =
    Map[Seat, Principal](Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black"))

  /** Proves the chess clock — not the 120s anti-abandonment `idleCheck` — is what ends a timed game: with nobody ever
    * submitting, the deadline that fires must be the (tiny) per-side clock, well inside the 5s guard.
    */
  private def flagFall(timeControl: TimeControl): IO[GameOver] =
    GameRoom
      // No one seeds here, so force-start almost immediately; the (tiny) chess clock is what must flag.
      .create(seats, dice, timeControl = timeControl, seedGrace = 50.millis)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          (room.start *> room.result)
            .timeoutTo(5.seconds, IO.raiseError(RuntimeException("clock did not flag before the 120s idleCheck")))
      }

  test("SuddenDeath: the side to move flags when its clock runs out"):
    flagFall(TimeControl.SuddenDeath(1)).map: over =>
      assertEquals(over.termination, Termination.Timeout)
      assert(over.result.isInstanceOf[GameResult.Win], s"a flag-fall is a win on time, got: ${over.result}")

  test("Fischer: an idle side still flags (the increment can't save a turn never made)"):
    flagFall(TimeControl.Fischer(1, 2)).map: over =>
      assertEquals(over.termination, Termination.Timeout)
      assert(over.result.isInstanceOf[GameResult.Win], s"a flag-fall is a win on time, got: ${over.result}")

  test("PerMove: exceeding the per-move budget flags"):
    flagFall(TimeControl.PerMove(1)).map: over =>
      assertEquals(over.termination, Termination.Timeout)
      assert(over.result.isInstanceOf[GameResult.Win], s"a flag-fall is a win on time, got: ${over.result}")

  /** Proves an active game is decided on the board, never by the clock: when both sides move in time, no one flags. The
    * generous control also exercises the per-turn debit + increment path that the idle tests never reach.
    */
  private def botsFinishUnderClock(timeControl: TimeControl): IO[GameOver] =
    val white = BotConnection(Principal.Guest("white"), Seat.White, greedy)
    val black = BotConnection(Principal.Bot("acme", "greedy"), Seat.Black, greedy)
    GameRoom
      .create(seats, dice, timeControl = timeControl)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          (white.run(room).background, black.run(room).background).tupled
            .use(_ => room.start *> room.result)
            .timeoutTo(30.seconds, IO.raiseError(RuntimeException("timed game with active bots did not finish")))
      }

  test("Fischer: bots that move in time finish on the board, never on the clock"):
    botsFinishUnderClock(TimeControl.Fischer(600, 2)).map: over =>
      assertNotEquals(over.termination, Termination.Timeout)

  test("SuddenDeath: bots with ample time finish on the board, never on the clock"):
    botsFinishUnderClock(TimeControl.SuddenDeath(600)).map: over =>
      assertNotEquals(over.termination, Termination.Timeout)

  test("Unlimited games carry no clocks on the wire"):
    GameRoom
      .create(seats, dice)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) => room.start *> room.snapshot
      }
      .map(ps => assertEquals(ps.clocks, None))

  test("a timed game's snapshot shows live clocks — the mover's ticks down, the other side's stays full"):
    GameRoom
      // Force-start quickly (no one seeds), so the clock is already ticking when we snapshot at 400ms.
      .create(seats, dice, timeControl = TimeControl.SuddenDeath(60), seedGrace = 50.millis)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) => room.start *> IO.sleep(400.millis) *> room.snapshot
      }
      .map: ps =>
        val clocks         = ps.clocks.getOrElse(fail("a timed game must carry clocks"))
        val (mover, other) =
          if ps.activeSeat == Seat.White then (clocks.white, clocks.black) else (clocks.black, clocks.white)
        assert(mover < 60000L, s"the mover's clock should have ticked down from 60s, got ${mover}ms")
        assert(mover > 0L, s"the mover should not have flagged yet, got ${mover}ms")
        assertEquals(other, 60000L, "the side not to move keeps its full bank")
