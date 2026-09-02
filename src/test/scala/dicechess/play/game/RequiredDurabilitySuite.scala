package dicechess.play.game

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.store.GameSnapshot

import scala.concurrent.duration.*

/** The fail-closed persistence contract of ADR-005 §7 (#47), checked through an in-memory `persist` callback whose
  * failures the test switches on and off: no version is published before it commits, a stalled write halts the room and
  * is retried, an exhausted intermediate write aborts the game FROM THE LAST DURABLE VERSION, the ending completes the
  * game only once its write commits, and a restart from any committed version continues consistently.
  *
  * Every wait here is on a condition (a snapshot written, an event seen, a telemetry entry recorded), never on a fixed
  * delay — the testing notes' rule against timing-dependent assertions.
  */
class RequiredDurabilitySuite extends munit.CatsEffectSuite:

  private def dice  = DiceSource.commitReveal("required-durability-fixture".getBytes("UTF-8"))
  private def seats =
    Map[Seat, Principal](Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black"))

  /** A store whose writes fail while the test's predicate says so; every successful write is kept in order, and every
    * telemetry event the room reports is recorded.
    */
  final class Store(
      val written: Ref[IO, Vector[GameSnapshot]],
      failing: Ref[IO, GameSnapshot => IO[Boolean]],
      val events: Ref[IO, Vector[PersistenceTelemetry]]
  ):
    def persist(s: GameSnapshot): IO[Unit] =
      failing.get
        .flatMap(_(s))
        .flatMap: fail =>
          if fail then IO.raiseError(RuntimeException(s"database unavailable (v${s.version})"))
          else written.update(_ :+ s)

    def failWhen(p: GameSnapshot => Boolean): IO[Unit]       = failing.set(s => IO.pure(p(s)))
    def failWhenIO(p: GameSnapshot => IO[Boolean]): IO[Unit] = failing.set(p)
    def heal: IO[Unit]                                       = failing.set(_ => IO.pure(false))

    def required(
        intermediateAttempts: Option[Int] = Some(3),
        stalledGrace: FiniteDuration = 10.seconds
    ): Durability.Required =
      Durability.Required(
        intermediate = RetryPolicy(intermediateAttempts, initialBackoff = 20.millis, maxBackoff = 40.millis),
        terminal = RetryPolicy(None, initialBackoff = 20.millis, maxBackoff = 40.millis),
        stalledSubscriberGrace = stalledGrace,
        telemetry = event => events.update(_ :+ event)
      )

  private def store: IO[Store] =
    (
      Ref.of[IO, Vector[GameSnapshot]](Vector.empty),
      Ref.of[IO, GameSnapshot => IO[Boolean]](_ => IO.pure(false)),
      Ref.of[IO, Vector[PersistenceTelemetry]](Vector.empty)
    ).mapN(new Store(_, _, _))

  private def await[A](ref: Ref[IO, Vector[A]])(pred: Vector[A] => Boolean): IO[Vector[A]] =
    ref.get
      .flatTap(v => IO.sleep(10.millis).unlessA(pred(v)))
      .iterateUntil(pred)
      .timeoutTo(15.seconds, IO.raiseError(RuntimeException("the awaited condition never held")))

  private def create(st: Store, durability: Durability): IO[GameRoom] =
    GameRoom
      .create(seats, dice, seedGrace = 50.millis, persist = st.persist, durability = durability)
      .flatMap:
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) => IO.pure(room)

  /** Record every event a subscriber sees; the returned effect waits for the stream to end. */
  private def observe(room: GameRoom): IO[(Ref[IO, Vector[GameEvent]], IO[Unit])] =
    Ref.of[IO, Vector[GameEvent]](Vector.empty).flatMap { seen =>
      room.subscribe.evalMap(e => seen.update(_ :+ e)).compile.drain.start.map(fiber => (seen, fiber.joinWithNever))
    }

  private def isRoll(e: GameEvent): Boolean  = e.isInstanceOf[GameEvent.DiceRolled]
  private def isEnded(e: GameEvent): Boolean = e.isInstanceOf[GameEvent.GameEnded]

  test("RetryPolicy doubles from the initial backoff up to the cap and knows when it is exhausted"):
    val policy = RetryPolicy(Some(4), 100.millis, 350.millis)
    assertEquals(policy.backoff(1), 100.millis)
    assertEquals(policy.backoff(2), 200.millis)
    assertEquals(policy.backoff(3), 350.millis, "capped")
    assertEquals(policy.backoff(40), 350.millis, "a long outage never overflows the shift")
    assert(!policy.exhausted(3))
    assert(policy.exhausted(4))
    assert(!RetryPolicy(None, 1.second, 30.seconds).exhausted(1_000_000), "an unbounded policy never gives up")

  test("the creation snapshot is fail-closed in required mode: a failed first write yields no room"):
    store.flatMap { st =>
      st.failWhen(_.version == 0L) *>
        GameRoom.create(seats, dice, persist = st.persist, durability = st.required()).attempt.map { outcome =>
          assert(outcome.isLeft, "a room whose creation row never committed must not be handed to its caller")
        }
    }

  test("an intermediate write is retried and nothing is published until it commits"):
    for
      st       <- store
      failures <- Ref.of[IO, Int](2)
      // The opening roll (v1) fails twice, then goes through.
      _         <- st.failWhenIO(s => if s.version == 1L then failures.modify(n => (n - 1, n > 0)) else IO.pure(false))
      room      <- create(st, st.required())
      (seen, _) <- observe(room)
      _         <- room.start
      _         <- await(seen)(_.exists(isRoll))
      written   <- st.written.get
      events    <- st.events.get
      stalled   <- room.persistenceStalled
      state     <- room.snapshot
    yield
      assert(written.exists(_.version == 1L), "the roll was durable before any subscriber saw it")
      assertEquals(
        events.collect { case PersistenceTelemetry.SaveFailed(v, attempt, terminal, retryIn, _) =>
          (v, attempt, terminal, retryIn.isDefined)
        },
        Vector((1L, 1, false, true), (1L, 2, false, true)),
        "each failed attempt is reported with what happens next"
      )
      assert(
        events.exists { case PersistenceTelemetry.SaveRecovered(1L, 3, _) => true; case _ => false },
        s"recovery is reported with the attempt count: $events"
      )
      assert(!stalled, "once the write commits the room is no longer stalled")
      assertEquals(state.version, 1L)

  test("an exhausted intermediate write aborts the game from the last durable version, and the abort itself commits"):
    for
      st <- store
      // Every live write of v1 fails (the policy allows three attempts); the terminal write is allowed through — it
      // also carries version 1, because the abort starts from durable v0, not from the roll that never committed.
      _                  <- st.failWhen(s => !s.ended && s.version == 1L)
      room               <- create(st, st.required(intermediateAttempts = Some(3)))
      (seen, streamDone) <- observe(room)
      _                  <- room.start
      over       <- room.result.timeoutTo(15.seconds, IO.raiseError(RuntimeException("the abort never completed")))
      _          <- streamDone
      written    <- st.written.get
      events     <- st.events.get
      seenEvents <- seen.get
    yield
      assertEquals(over.termination, Termination.Aborted)
      assert(events.contains(PersistenceTelemetry.SaveAbandoned(1L, 3)), s"abandonment is reported: $events")
      val terminal = written.last
      assert(terminal.ended)
      assertEquals(terminal.version, 1L, "the abort is v(last durable) + 1, not v(unsaved) + 1")
      assertEquals(terminal.ply, 0L, "the roll that never committed is not in the durable record")
      assertEquals(terminal.lastRoll, Nil)
      assert(!terminal.pending)
      assertEquals(terminal.status, GameStatus.Ended(over))
      assert(!seenEvents.exists(isRoll), "a subscriber never saw the roll that did not commit")
      assert(seenEvents.exists(isEnded), "but did see the durable ending")

  test("the ending is published and the game completes only after the terminal write commits"):
    for
      st        <- store
      room      <- create(st, st.required())
      (seen, _) <- observe(room)
      _         <- room.start
      _         <- await(seen)(_.exists(isRoll))
      // The database goes away exactly when the game ends.
      _ <- st.failWhen(_.ended)
      _ <- room.submit(Seat.White, GameCommand.Resign)
      _ <- await(st.events)(_.count {
        case PersistenceTelemetry.SaveFailed(_, _, terminal, _, _) => terminal
        case _                                                     => false
      } >= 2)
      endedYet   <- room.hasEnded
      stalled    <- room.persistenceStalled
      seenSoFar  <- seen.get
      writtenNow <- st.written.get
      _          <- st.heal
      over       <- room.result.timeoutTo(15.seconds, IO.raiseError(RuntimeException("the ending never committed")))
      _          <- await(seen)(_.exists(isEnded))
      written    <- st.written.get
      events     <- st.events.get
    yield
      assert(!endedYet, "the room does not consider itself ended while the ending is uncommitted")
      assert(stalled, "and reports the stall")
      assert(!seenSoFar.exists(isEnded), "no subscriber saw an ending that was not durable")
      assert(!writtenNow.exists(_.ended))
      assertEquals(over, GameOver(GameResult.Win(Side.Black), Termination.Resign))
      assert(written.last.ended, "once the store is back the ending commits")
      assertEquals(written.last.status, GameStatus.Ended(over))
      assert(
        events.exists {
          case PersistenceTelemetry.SaveRecovered(v, _, _) => v == written.last.version; case _ => false
        },
        s"the terminal recovery is reported: $events"
      )

  test("a write stalled past the grace releases the subscribers and keeps retrying"):
    for
      st                 <- store
      _                  <- st.failWhen(s => !s.ended && s.version == 1L)
      room               <- create(st, st.required(intermediateAttempts = Some(8), stalledGrace = 100.millis))
      (seen, streamDone) <- observe(room)
      _                  <- room.start
      // The subscriber's stream is interrupted while the room is still retrying — it ends without a terminal event.
      _          <- streamDone.timeoutTo(15.seconds, IO.raiseError(RuntimeException("subscriber was never released")))
      seenEvents <- seen.get
      events     <- st.events.get
      over       <- room.result.timeoutTo(15.seconds, IO.raiseError(RuntimeException("the abort never completed")))
    yield
      assert(!seenEvents.exists(isEnded), "the released stream carried no ending — nothing durable had happened")
      assert(
        events.exists { case PersistenceTelemetry.SubscribersDropped(1L, _) => true; case _ => false },
        s"the drop is reported: $events"
      )
      assertEquals(over.termination, Termination.Aborted, "the room went on to abort durably after releasing them")

  test("restart after a commit that was never broadcast: the restored room continues from that exact version"):
    for
      st   <- store
      room <- create(st, st.required())
      _    <- room.start
      // v1 is the committed opening roll; pretend the process died before anyone was told about it.
      v1           <- await(st.written)(_.exists(_.version == 1L)).map(_.find(_.version == 1L).get)
      st2          <- store
      restoredDice <- IO.fromEither(DiceSource.fromHexSeed(v1.serverSeed).left.map(RuntimeException(_)))
      restored     <- GameRoom
        .restore(v1, restoredDice, persist = st2.persist, durability = st2.required())
        .flatMap(_.fold(e => IO.raiseError(RuntimeException(e)), IO.pure))
      state <- restored.snapshot
      _     <- restored.submit(Seat.White, GameCommand.Resign)
      over  <- restored.result
      after <- st2.written.get
    yield
      assertEquals(state.version, 1L)
      assertEquals(state.dfen, v1.dfen, "the committed roll — dice pool included — is the position the game resumes at")
      assert(state.dicePending)
      assertEquals(over.termination, Termination.Resign)
      assertEquals(after.last.version, 2L, "versions continue from the committed one")
      assertEquals(after.last.lastRoll, v1.lastRoll, "the ending records the roll that was durable")

  test("restart after a crash BEFORE the roll committed: the durable state re-rolls the identical dice"):
    for
      st   <- store
      room <- create(st, st.required())
      _    <- room.start
      v1   <- await(st.written)(_.exists(_.version == 1L)).map(_.find(_.version == 1L).get)
      v0 = st.written.get.map(_.find(_.version == 0L).get)
      creation     <- v0
      st2          <- store
      restoredDice <- IO.fromEither(DiceSource.fromHexSeed(creation.serverSeed).left.map(RuntimeException(_)))
      restored     <- GameRoom
        .restore(creation, restoredDice, seedGrace = 50.millis, persist = st2.persist, durability = st2.required())
        .flatMap(_.fold(e => IO.raiseError(RuntimeException(e)), IO.pure))
      _        <- restored.start
      rerolled <- await(st2.written)(_.exists(_.version == 1L)).map(_.find(_.version == 1L).get)
    yield
      assertEquals(rerolled.lastRoll, v1.lastRoll, "commit-reveal dice are a function of the durable seed and ply")
      assertEquals(rerolled.dfen, v1.dfen)

  test("best-effort rooms are unchanged: a failing store costs a log line and the game plays on in memory"):
    for
      st        <- store
      _         <- st.failWhen(_.version >= 1L)
      room      <- create(st, Durability.BestEffort)
      (seen, _) <- observe(room)
      _         <- room.start
      _         <- await(seen)(_.exists(isRoll))
      _         <- room.submit(Seat.White, GameCommand.Resign)
      over      <- room.result.timeoutTo(15.seconds, IO.raiseError(RuntimeException("best-effort game froze")))
      written   <- st.written.get
      stalled   <- room.persistenceStalled
    yield
      assertEquals(over.termination, Termination.Resign)
      // Two writes of version 0 — the fail-closed creation row and the committed `started` flag — and nothing after:
      // every published version failed to persist and the game went on regardless.
      assert(written.nonEmpty && written.forall(_.version == 0L), s"only version-0 rows reached the store: $written")
      assert(!stalled, "a best-effort room never reports a stall")

  /** White's first legal turn at the position the room is holding, as the UCI path `submitTurn` takes. */
  private def firstLegalTurn(room: GameRoom): IO[List[String]] =
    room.legalMoves.flatMap: moves =>
      IO.fromEither(EngineOps.parse(moves.dfen).left.map(RuntimeException(_)))
        .map(state => EngineOps.legalMovePaths(state).head.map(EngineOps.toUci))

  test("a turn whose write is abandoned answers its caller with a refusal once the abort has committed"):
    for
      st   <- store
      room <- create(st, st.required(intermediateAttempts = Some(2)))
      _    <- room.start
      v1   <- await(st.written)(_.exists(_.version == 1L)).map(_.find(_.version == 1L).get)
      // The move (v2) can never commit; the abort that follows is v2 too, built from durable v1.
      _       <- st.failWhen(s => !s.ended && s.version == 2L)
      path    <- firstLegalTurn(room)
      verdict <- room
        .submitTurn(Seat.White, path)
        .timeoutTo(15.seconds, IO.raiseError(RuntimeException("the caller was never answered")))
      over    <- room.result
      written <- st.written.get
    yield
      assert(verdict.isInstanceOf[GameRoom.TurnVerdict.Refused], s"the caller must learn the game is over: $verdict")
      assertEquals(over.termination, Termination.Aborted)
      val terminal = written.last
      assertEquals(terminal.version, 2L)
      assertEquals(terminal.turns, Vector.empty, "the move that never committed is not in the durable record")
      assertEquals(terminal.lastRoll, v1.lastRoll, "the abort starts from the durable roll")

  test("a failing telemetry sink never turns a committed write into a failed commit, and `started` is committed"):
    for
      st       <- store
      failures <- Ref.of[IO, Int](1)
      _        <- st.failWhenIO(s => if s.version == 1L then failures.modify(n => (n - 1, n > 0)) else IO.pure(false))
      broken = st.required().copy(telemetry = _ => IO.raiseError(RuntimeException("metrics backend down")))
      room      <- create(st, broken)
      (seen, _) <- observe(room)
      _         <- room.start
      _         <- await(seen)(_.exists(isRoll))
      written   <- st.written.get
      stalled   <- room.persistenceStalled
    yield
      assert(written.exists(_.version == 1L), "the roll committed although every telemetry call failed")
      assert(!stalled, "and the recovery was still recorded on the room")
      assert(
        written.exists(s => s.started && s.ply == 0L && !s.ended),
        "the start is committed before the opening roll, so an abort never starts from an uncommitted state"
      )
