package dicechess.play.game

import cats.effect.IO
import cats.syntax.all.*
import dicechess.engine.search.BotRegistry
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.store.TurnRecord

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

  private def testSession(
      timeControl: TimeControl,
      players: Map[Seat, Principal] = seats,
      turns: Vector[TurnRecord] = Vector.empty,
      remaining: Map[Seat, FiniteDuration] = Map.empty,
      turnStartedAt: Option[FiniteDuration] = None
  ): GameRoom.Session =
    GameRoom.Session(
      state = EngineOps.parse(EngineOps.InitialDfen).getOrElse(fail("parse failed")),
      version = 1L,
      players = players,
      displayNames = Map.empty,
      ratings = Map.empty,
      dice = dice,
      ply = 1L,
      pending = true,
      status = GameStatus.Active,
      timeControl = timeControl,
      remaining = remaining,
      turnStartedAt = turnStartedAt,
      turns = turns
    )

  test("presentationGraceFor returns Duration.Zero for bots"):
    val botSeats = Map[Seat, Principal](
      Seat.White -> Principal.Bot("team", "alpha"),
      Seat.Black -> Principal.Bot("team", "beta")
    )
    val s = testSession(
      timeControl = TimeControl.SuddenDeath(60),
      players = botSeats,
      turns = Vector(TurnRecord(1L, "w", List(1, 2, 3), List("e2e4", "g1f3", "b1c3"), "dfen", Some(100L)))
    )
    assertEquals(GameRoom.presentationGraceFor(s, Seat.Black), Duration.Zero)

  test("presentationGraceFor returns Duration.Zero on game opening (no turns yet)"):
    val s = testSession(
      timeControl = TimeControl.SuddenDeath(60),
      players = seats,
      turns = Vector.empty
    )
    assertEquals(GameRoom.presentationGraceFor(s, Seat.White), Duration.Zero)

  test("presentationGraceFor calculates correct animation timings for human player"):
    val humanVsBot = Map[Seat, Principal](
      Seat.White -> Principal.Bot("team", "alpha"),
      Seat.Black -> Principal.Guest("human")
    )
    // 3 moves: 3 * 1000ms + 600ms = 3600ms
    val s3 = testSession(
      timeControl = TimeControl.SuddenDeath(60),
      players = humanVsBot,
      turns = Vector(TurnRecord(1L, "w", List(1, 2, 3), List("e2e4", "g1f3", "b1c3"), "dfen", Some(100L)))
    )
    assertEquals(GameRoom.presentationGraceFor(s3, Seat.Black), 3600.millis)

    // 1 move: 1 * 1000ms + 600ms = 1600ms
    val s1 = testSession(
      timeControl = TimeControl.SuddenDeath(60),
      players = humanVsBot,
      turns = Vector(TurnRecord(1L, "w", List(1), List("e2e4"), "dfen", Some(100L)))
    )
    assertEquals(GameRoom.presentationGraceFor(s1, Seat.Black), 1600.millis)

    // Pass (0 moves): 1500ms + 600ms = 2100ms
    val sPass = testSession(
      timeControl = TimeControl.SuddenDeath(60),
      players = humanVsBot,
      turns = Vector(TurnRecord(1L, "w", List(1, 2, 3), Nil, "dfen", Some(0L)))
    )
    assertEquals(GameRoom.presentationGraceFor(sPass, Seat.Black), 2100.millis)

  test("liveClocks remains frozen at full bank during presentation grace window"):
    val s = testSession(
      timeControl = TimeControl.SuddenDeath(60),
      players = seats,
      remaining = Map(Seat.White -> 60.seconds, Seat.Black -> 60.seconds),
      // Grace period ends at t = 10.seconds (started at t = 6.4s with 3.6s grace)
      turnStartedAt = Some(10.seconds)
    )
    // 1) Mid-grace (now = 8s < 10s): White is active mover, elapsed = 0
    val midGrace = GameRoom.liveClocks(s, now = 8.seconds).get
    assertEquals(midGrace.white, 60000L)
    assertEquals(midGrace.black, 60000L)

    // 2) Exact grace end (now = 10s == 10s): elapsed = 0
    val atEnd = GameRoom.liveClocks(s, now = 10.seconds).get
    assertEquals(atEnd.white, 60000L)
    assertEquals(atEnd.black, 60000L)

    // 3) Post-grace thinking time (now = 12.5s > 10s): 2500ms debited
    val postGrace = GameRoom.liveClocks(s, now = 12500.millis).get
    assertEquals(postGrace.white, 57500L)
    assertEquals(postGrace.black, 60000L)

  test("debit charges 0 ms during presentation grace and charges thinking time post-grace"):
    val s = testSession(
      timeControl = TimeControl.Fischer(60, 2),
      remaining = Map(Seat.White -> 60.seconds, Seat.Black -> 60.seconds),
      turnStartedAt = Some(10.seconds)
    )
    // Submitting move during grace (at 8s < 10s) charges 0ms + grants Fischer increment (2s)
    val (sDuring, elapsedDuring) = GameRoom.debit(s, Seat.White, stoppedAt = 8.seconds)
    assertEquals(elapsedDuring, 0L)
    assertEquals(sDuring.remaining(Seat.White), 62.seconds)

    // Submitting move after grace (at 11.5s > 10s) charges 1500ms + grants Fischer increment (2s)
    val (sAfter, elapsedAfter) = GameRoom.debit(s, Seat.White, stoppedAt = 11500.millis)
    assertEquals(elapsedAfter, 1500L)
    assertEquals(sAfter.remaining(Seat.White), 60500.millis) // 60s - 1.5s + 2s = 60.5s

  test("Bot vs Human: human clock is paused during presentation grace and does not flag prematurely"):
    val botVsHuman = Map[Seat, Principal](
      Seat.White -> Principal.Bot("acme", "greedy"),
      Seat.Black -> Principal.Guest("human")
    )
    val white = BotConnection(Principal.Bot("acme", "greedy"), Seat.White, greedy)
    GameRoom
      // SuddenDeath(1): human only has 1s total bank!
      .create(botVsHuman, dice, timeControl = TimeControl.SuddenDeath(1), seedGrace = 50.millis)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          white.run(room).background.use { _ =>
            for
              _ <- room.start
              // Wait for White bot to finish turn 1 and for Black to enter presentation grace.
              _  <- IO.sleep(300.millis)
              ps <- room.snapshot
              clocks = ps.clocks.getOrElse(fail("a timed game must carry clocks"))
              // Black's clock should still be at full 1000ms bank during presentation grace
              _ = assertEquals(clocks.black, 1000L)
              // Wait past 1.5s total time since Black's turn began; Black must NOT have flagged.
              _          <- IO.sleep(1000.millis)
              activeSnap <- room.snapshot
              _ = assertEquals(activeSnap.status, GameStatus.Active)
              // Wait for the full deadline (1s bank + grace) to expire; now Black must flag on timeout.
              over <- room.result.timeoutTo(6.seconds, IO.raiseError(RuntimeException("game did not timeout")))
            yield
              assertEquals(over.termination, Termination.Timeout)
              assertEquals(over.result, GameResult.Win(dicechess.play.core.Side.White))
          }
      }

  test("Bot vs Bot: no presentation grace is added and idle bot flags immediately"):
    val botVsBot = Map[Seat, Principal](
      Seat.White -> Principal.Bot("acme", "alpha"),
      Seat.Black -> Principal.Bot("acme", "beta")
    )
    val white = BotConnection(Principal.Bot("acme", "alpha"), Seat.White, greedy)
    GameRoom
      .create(botVsBot, dice, timeControl = TimeControl.SuddenDeath(1), seedGrace = 50.millis)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          white.run(room).background.use { _ =>
            for
              _ <- room.start
              // Wait for White to move; Black is an idle bot with 1s bank and 0s grace.
              // Black must flag on timeout within ~2.5 seconds (no 3.6s grace).
              over <- room.result.timeoutTo(
                2500.millis,
                IO.raiseError(RuntimeException("bot did not flag within 2.5s"))
              )
            yield
              assertEquals(over.termination, Termination.Timeout)
              assertEquals(over.result, GameResult.Win(dicechess.play.core.Side.White))
          }
      }

  test("presentationGraceFor returns Duration.Zero when the same principal occupies both seats"):
    val sameUser  = Principal.User("alice")
    val sameSeats = Map[Seat, Principal](Seat.White -> sameUser, Seat.Black -> sameUser)
    val s         = testSession(
      timeControl = TimeControl.SuddenDeath(60),
      players = sameSeats,
      turns = Vector(TurnRecord(1L, "w", List(1, 2, 3), List("e2e4", "g1f3", "b1c3"), "dfen", Some(100L)))
    )
    assertEquals(GameRoom.presentationGraceFor(s, Seat.Black), Duration.Zero)

  test("Draw decline after initial grace window freezes clock at post-deliberation bank for roll animation"):
    val botVsHuman = Map[Seat, Principal](
      Seat.White -> Principal.Bot("acme", "greedy"),
      Seat.Black -> Principal.Guest("human")
    )
    val white = BotConnection(Principal.Bot("acme", "greedy"), Seat.White, greedy)
    GameRoom
      .create(botVsHuman, dice, timeControl = TimeControl.SuddenDeath(60), seedGrace = 50.millis)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          white.run(room).background.use { _ =>
            for
              offeredFiber <- room.subscribe
                .collectFirst { case e: GameEvent.DrawOffered => e }
                .compile
                .lastOrError
                .start
              _ <- room.armDrawOffer(Seat.White, true)
              _ <- room.start
              // Wait for White bot to move and deliver the draw offer (DrawOffered emitted)
              _   <- offeredFiber.joinWithNever.timeout(10.seconds)
              ps1 <- room.snapshot
              _ = assertEquals(ps1.drawOffer, Some(DrawOffer(true)))
              // Wait 3.5 seconds from DrawOffered so initial move grace (at most 3s) expires and Black deliberates
              _ <- IO.sleep(3500.millis)
              // Black declines the draw
              verdict <- room.respondDraw(Seat.Black, accept = false)
              _ = assert(verdict.isInstanceOf[GameRoom.TurnVerdict.Applied], s"decline applied: $verdict")
              // Immediately after decline, dice are revealed and roll animation (600ms) plays
              snapDuringRoll1 <- room.snapshot
              clocks1    = snapDuringRoll1.clocks.getOrElse(fail("missing clocks"))
              bankAtRoll = clocks1.black
              _          = assert(bankAtRoll < 60000L, s"deliberation should have debited the bank, got $bankAtRoll")
              // 200ms into roll animation, Black's clock must remain frozen at bankAtRoll
              _               <- IO.sleep(200.millis)
              snapDuringRoll2 <- room.snapshot
              clocks2 = snapDuringRoll2.clocks.getOrElse(fail("missing clocks"))
              _       = assertEquals(clocks2.black, bankAtRoll)
              // Another 200ms later (400ms total, still within 600ms roll animation), clock is still frozen
              _               <- IO.sleep(200.millis)
              snapDuringRoll3 <- room.snapshot
              clocks3 = snapDuringRoll3.clocks.getOrElse(fail("missing clocks"))
              _       = assertEquals(clocks3.black, bankAtRoll)
              // Wait 600ms more (1000ms total after decline > 600ms roll animation); now clock begins ticking
              _            <- IO.sleep(600.millis)
              snapPostRoll <- room.snapshot
              clocksPost = snapPostRoll.clocks.getOrElse(fail("missing clocks"))
              _ = assert(clocksPost.black < bankAtRoll, s"clock should have started ticking, got ${clocksPost.black}")
            yield ()
          }
      }

  private def leafPath(tree: MoveTree): List[String] =
    tree.children.headOption match
      case None              => Nil
      case Some((uci, next)) => uci :: leafPath(next)

  test("Draw decline followed by forced pass does not leak turnStartedAt to opponent or penalize their clock"):
    val passDice = new DiceSource:
      def commit: String                                     = "fixed-commit"
      def reveal: String                                     = "fixed-reveal"
      def roll(ply: Long, sW: String, sB: String): List[Int] =
        if ply == 0L then List(1, 2, 3)
        else if ply == 1L then List(3, 4, 5)
        else List(1, 2, 3)

    for
      stored  <- cats.effect.Ref.of[IO, Option[dicechess.play.store.GameSnapshot]](None)
      roomRes <- GameRoom.create(
        seats,
        passDice,
        timeControl = TimeControl.SuddenDeath(60),
        seedGrace = 50.millis,
        persist = s => stored.set(Some(s))
      )
      room   <- IO.fromEither(roomRes.left.map(e => RuntimeException(s"room creation failed: $e")))
      _      <- room.start
      _      <- IO.sleep(200.millis)
      moves0 <- room.legalMoves
      path0 = leafPath(moves0.legalMoves)
      _           <- room.submitTurn(Seat.White, path0, offerDraw = true)
      snapPreRoll <- room.snapshot
      _                   = assertEquals(snapPreRoll.activeSeat, Seat.Black)
      _                   = assertEquals(snapPreRoll.drawOffer, Some(DrawOffer(pending = true)))
      whiteBankAfterTurn0 = snapPreRoll.clocks.getOrElse(fail("missing clocks")).white
      // Black deliberates past initial move grace
      _       <- IO.sleep(3500.millis)
      verdict <- room.respondDraw(Seat.Black, accept = false)
      _ = assert(verdict.isInstanceOf[GameRoom.TurnVerdict.Applied], s"decline applied: $verdict")
      snapWhite <- room.snapshot
      _      = assertEquals(snapWhite.activeSeat, Seat.White)
      clocks = snapWhite.clocks.getOrElse(fail("missing clocks"))
      _      = assertEquals(
        clocks.white,
        whiteBankAfterTurn0,
        s"White's clock must match bank after turn 0, got ${clocks.white}"
      )
      // 500ms into presentation grace of turn 2, White's clock must still be frozen
      _          <- IO.sleep(500.millis)
      snapGrace2 <- room.snapshot
      clocksGrace2 = snapGrace2.clocks.getOrElse(fail("missing clocks"))
      _            = assertEquals(clocksGrace2.white, whiteBankAfterTurn0)
      // Black's deliberation was debited to Black
      _ = assert(clocks.black < 60000L, s"Black's clock should be debited for deliberation, got ${clocks.black}")
      persisted <- stored.get.map(_.getOrElse(fail("missing persisted snapshot")))
      passRecord = persisted.turns.find(t => t.turnNumber == 2L).getOrElse(fail("missing pass turn record"))
      _          = assertEquals(passRecord.moves, Nil)
      _ = assert(passRecord.thinkingTimeMs.exists(_ > 0L), s"pass turn should record thinking time: $passRecord")
    yield ()
