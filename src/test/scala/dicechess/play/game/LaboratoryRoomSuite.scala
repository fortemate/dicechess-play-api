package dicechess.play.game

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.dice.DiceSource
import dicechess.play.store.GameSnapshot

import scala.concurrent.duration.*

class LaboratoryRoomSuite extends munit.CatsEffectSuite:

  private val white      = Principal.Guest("11111111-1111-1111-1111-111111111111")
  private val black      = Principal.Guest("22222222-2222-2222-2222-222222222222")
  private val seats      = Map[Seat, Principal](Seat.White -> white, Seat.Black -> black)
  private val aliceSeed  = "alice-seed-fixture-0001"
  private val bobSeed    = "bob-seed-fixture-0001"
  private val baseTuning = GameRoom.RoomTuning(seedGrace = 100.millis, disconnectGrace = 30.seconds)

  private def seed = DiceSource.commitReveal(Array.tabulate[Byte](32)(_.toByte))

  private def initial(timeControl: TimeControl = TimeControl.Fischer(600, 10)): IO[GameSnapshot] =
    for
      now      <- IO.realTimeInstant
      made     <- GameRoom.laboratorySnapshot(seats, seed, timeControl, now.plusSeconds(30))
      snapshot <- IO.fromEither(made.leftMap(message => new RuntimeException(message)))
    yield snapshot

  private def restore(
      snapshot: GameSnapshot,
      tuning: GameRoom.RoomTuning = baseTuning,
      durability: Durability = Durability.required(_ => IO.unit),
      customSave: Option[(GameSnapshot, Ref[IO, Vector[GameSnapshot]]) => IO[Unit]] = None,
      now: IO[java.time.Instant] = IO.realTimeInstant
  ): IO[(GameRoom, Ref[IO, Vector[GameSnapshot]])] =
    for
      saved <- Ref.of[IO, Vector[GameSnapshot]](Vector(snapshot))
      persistence = GameRoom.RoomPersistence(
        save = next => customSave.fold(saved.update(_ :+ next))(_(next, saved)),
        durability = durability
      )
      made <- GameRoom.restoreLaboratory(snapshot, tuning, persistence, now = now)
      room <- IO.fromEither(made.leftMap(error => RuntimeException(s"laboratory restore failed: $error")))
    yield room -> saved

  private def withBoth[A](room: GameRoom)(action: IO[A]): IO[A] =
    (room.connection(Seat.White), room.connection(Seat.Black)).tupled.use(_ => action)

  private def startReady(room: GameRoom): IO[PublicGameState] =
    withBoth(room):
      room.submit(Seat.White, GameCommand.SubmitSeed(aliceSeed)) *>
        room.submit(Seat.Black, GameCommand.SubmitSeed(bobSeed)) *>
        awaitPhase(room, Phase.AwaitingFirstRoll)

  private def awaitPhase(room: GameRoom, phase: Phase, attempts: Int = 150): IO[PublicGameState] =
    room.snapshot.flatMap: state =>
      if state.lab.exists(_.turn.phase == phase) then IO.pure(state)
      else if attempts <= 0 then IO.raiseError(RuntimeException(s"expected $phase, got ${state.lab.map(_.turn.phase)}"))
      else IO.sleep(10.millis) *> IO.defer(awaitPhase(room, phase, attempts - 1))

  private def latest(saved: Ref[IO, Vector[GameSnapshot]]): IO[GameSnapshot] = saved.get.map(_.last)

  private def recover(
      room: GameRoom,
      saved: Ref[IO, Vector[GameSnapshot]]
  ): IO[(GameRoom, Ref[IO, Vector[GameSnapshot]])] =
    for
      snapshot <- latest(saved)
      _        <- room.stopForRestart
      next     <- restore(snapshot)
    yield next

  private def command(room: GameRoom, seat: Seat, action: Action, id: String): IO[Receipt] =
    room.snapshot.flatMap: state =>
      room
        .submitLaboratory(seat, Command(id, state.version, action))
        .flatMap:
          case Right(receipt)  => IO.pure(receipt)
          case Left(rejection) => IO.raiseError(RuntimeException(s"unexpected ${rejection.code}: ${rejection.reason}"))

  private def firstPath(tree: MoveTree): List[String] =
    tree.children.toList.sortBy(_._1).headOption match
      case None                => Nil
      case Some((move, child)) => move :: firstPath(child)

  test("seed submissions alone do not open the join gate or charge clocks; both live seats do"):
    for
      snapshot      <- initial()
      (room, saved) <- restore(snapshot)
      initialRemaining = snapshot.remainingMs
      _ <- room
        .connection(Seat.White)
        .use: _ =>
          for
            _                 <- room.submit(Seat.White, GameCommand.SubmitSeed(aliceSeed)) *> IO.sleep(250.millis)
            (public, durable) <- (room.snapshot, latest(saved)).tupled
            _ = assertEquals(public.lab.map(_.turn.phase), Some(Phase.AwaitingPlayers))
            _ = assertEquals(
              public.clocks.map(c => Map(Seat.White -> c.white, Seat.Black -> c.black)),
              Some(Map(Seat.White -> 600000L, Seat.Black -> 600000L))
            )
            _ = assertEquals(durable.remainingMs, initialRemaining)
            _ = assertEquals(durable.lab.map(_.turn.generatedOffers), Some(Nil))
            _ <- room
              .connection(Seat.Black)
              .use: _ =>
                room
                  .submit(Seat.Black, GameCommand.SubmitSeed(bobSeed)) *> awaitPhase(room, Phase.AwaitingFirstRoll).void
          yield ()
      opened <- room.snapshot
      _ = assertEquals(opened.lab.map(_.turn.offers), Some(Nil))
      _ <- room.stopForRestart
    yield ()

  test("disconnecting during the seed grace leaves the gate closed until both seats return"):
    for
      snapshot  <- initial()
      (room, _) <- restore(snapshot)
      _         <- room
        .connection(Seat.White)
        .use: _ =>
          room.submit(Seat.White, GameCommand.SubmitSeed(aliceSeed)) *>
            room.connection(Seat.Black).use(_ => IO.sleep(20.millis)) *>
            IO.sleep(180.millis) *>
            room.snapshot.flatMap: state =>
              IO(assertEquals(state.lab.map(_.turn.phase), Some(Phase.AwaitingPlayers)))
      opened <- withBoth(room):
        room.submit(Seat.Black, GameCommand.SubmitSeed(bobSeed)) *> awaitPhase(room, Phase.AwaitingFirstRoll)
      _ = assertEquals(opened.lab.map(_.turn.offers), Some(Nil))
      _ <- room.stopForRestart
    yield ()

  test("a presence write that reaches the join deadline aborts instead of opening the room"):
    for
      snapshot <- initial()
      deadline = java.time.Instant.ofEpochMilli(snapshot.lab.get.joinDeadlineAtEpochMs)
      clock      <- Ref.of[IO, java.time.Instant](deadline.minusSeconds(10))
      seedsSaved <- Deferred[IO, Unit]
      writer = (next: GameSnapshot, saved: Ref[IO, Vector[GameSnapshot]]) =>
        saved.update(_ :+ next) *>
          seedsSaved.complete(()).void.whenA(next.clientSeeds.size == 2) *>
          clock.set(deadline).whenA(next.lab.exists(_.joined == Set(Seat.White, Seat.Black)))
      (room, saved) <- restore(snapshot, customSave = Some(writer), now = clock.get)
      over          <- room
        .connection(Seat.White)
        .use: _ =>
          room.submit(Seat.White, GameCommand.SubmitSeed(aliceSeed)) *>
            room.submit(Seat.Black, GameCommand.SubmitSeed(bobSeed)) *>
            seedsSaved.get.timeout(2.seconds) *>
            room.connection(Seat.Black).use(_ => room.result.timeout(2.seconds))
      images <- saved.get
      _ = assertEquals(over, GameOver(GameResult.Draw, Termination.Aborted))
      _ = assert(images.forall(!_.started), "an expired join must never start a sporting game")
      _ = assert(images.forall(_.lab.exists(_.turn.generatedOffers.isEmpty)))
      _ = assertEquals(images.last.lab.map(_.turn.phase), Some(Phase.Ended))
      _ <- room.stopForRestart
    yield ()

  test("all active lab stages recover with offers, commit, seats and history intact; exact retries do not reapply"):
    for
      snapshot   <- initial()
      (r0, p0)   <- restore(snapshot)
      _          <- startReady(r0)
      firstStage <- latest(p0)
      (r1, p1)   <- recover(r0, p0)
      _ = assertEquals(r1.joinTokens, firstStage.seatTokens)
      _ <- withBoth(r1):
        val version = firstStage.version
        val request = Command("first-roll", version, Action.RollFirst)
        for
          firstReceipt      <- r1.submitLaboratory(Seat.White, request)
          afterFirst        <- r1.snapshot
          writesBeforeRetry <- p1.get.map(_.size)
          repeated          <- r1.submitLaboratory(Seat.White, request)
          writesAfterRetry  <- p1.get.map(_.size)
          _ = assertEquals(firstReceipt.toOption, repeated.toOption)
          _ = assertEquals(afterFirst.lab.map(_.turn.phase), Some(Phase.AwaitingSecondRoll))
          _ = assertEquals(afterFirst.version, version + 1)
          _ = assertEquals(writesAfterRetry, writesBeforeRetry)
        yield ()
      secondStage <- latest(p1)
      _ = assertEquals(secondStage.lab.map(_.turn.revealedOfferCount), Some(1))
      (r2, p2) <- recover(r1, p1)
      r1Public <- r1.snapshot
      r2Public <- r2.snapshot
      _ = assertEquals(r2Public.lab, r1Public.lab)
      _           <- withBoth(r2)(command(r2, Seat.White, Action.RollSecond, "second-roll").void)
      choiceStage <- latest(p2)
      _ = assertEquals(choiceStage.lab.map(_.turn.phase), Some(Phase.AwaitingPairChoice))
      _ = assertEquals(choiceStage.lab.map(_.turn.revealedOfferCount), Some(2))
      (r3, p3) <- recover(r2, p2)
      choices = choiceStage.lab.toList
        .flatMap(_.turn.generatedOffers.zipWithIndex)
        .collect:
          case (pair, index)
              if EngineOps
                .legalMovePaths(
                  EngineOps.withDice(
                    EngineOps.parse(choiceStage.dfen).toOption.getOrElse(fail("invalid recovered dfen")),
                    pair.values
                  )
                )
                .nonEmpty =>
            index
      _ = assert(choices.nonEmpty, "the fixture must have a selectable legal pair")
      selected <- withBoth(r3)(command(r3, Seat.White, Action.ChoosePair(choices.head), "choose-pair"))
      _ = assertEquals(selected.commandId, "choose-pair")
      playing <- r3.snapshot
      _ = assertEquals(playing.lab.map(_.turn.phase), Some(Phase.PlayingSelectedPair))
      _ = assertEquals(playing.lab.map(_.turn.selectedPairIndex), Some(Some(choices.head)))
      legal  <- r3.legalMoves
      legacy <- r3.submitTurn(Seat.White, firstPath(legal.legalMoves), offerDraw = false)
      _ = assert(legacy.isInstanceOf[GameRoom.TurnVerdict.Refused])
      (r4, p4) <- recover(r3, p3)
      r3Public <- r3.snapshot
      r4Public <- r4.snapshot
      _ = assertEquals(r4Public.lab, r3Public.lab)
      _ <- withBoth(r4):
        r4.legalMoves.flatMap: moves =>
          val path = firstPath(moves.legalMoves)
          command(r4, Seat.White, Action.SubmitTurn(path), "play-turn").void
      history <- r4.resync.map:
        case GameEvent.Snapshot(_, _, turns) => turns
        case _                               => Nil
      durable <- latest(p4)
      _ = assert(history.lastOption.exists(_.labChoice.isDefined))
      _ = assert(durable.turns.lastOption.exists(_.labChoice.isDefined))
      _ <- r4.stopForRestart
    yield ()

  test("an accepted stage is persisted before its event and receipt become visible"):
    for
      snapshot   <- initial()
      entered    <- Deferred[IO, Unit]
      subscribed <- Deferred[IO, Unit]
      observed   <- Deferred[IO, Unit]
      release    <- Deferred[IO, Unit]
      seen       <- Ref.of[IO, Vector[GameEvent]](Vector.empty)
      writer = (next: GameSnapshot, saved: Ref[IO, Vector[GameSnapshot]]) =>
        if next.lab.exists(_.turn.phase == Phase.AwaitingSecondRoll) then
          entered.complete(()).attempt.void *> release.get *> saved.update(_ :+ next)
        else saved.update(_ :+ next)
      (room, _)  <- restore(snapshot, customSave = Some(writer))
      subscriber <- room.subscribe
        .evalMap(event =>
          seen.update(_ :+ event) *> subscribed.complete(()).attempt.void *> (event match
            case GameEvent.LabTurnChanged(_, _, lab, _, _) if lab.turn.phase == Phase.AwaitingSecondRoll =>
              observed.complete(()).void
            case _ => IO.unit)
        )
        .compile
        .drain
        .start
      _ <- subscribed.get.timeoutTo(1.second, IO.raiseError(RuntimeException("subscriber did not attach")))
      _ <- withBoth(room):
        room.submit(Seat.White, GameCommand.SubmitSeed(aliceSeed)) *>
          room.submit(Seat.Black, GameCommand.SubmitSeed(bobSeed)) *>
          awaitPhase(room, Phase.AwaitingFirstRoll).void *>
          room.snapshot.flatMap: before =>
            room
              .submitLaboratory(Seat.White, Command("blocked-roll", before.version, Action.RollFirst))
              .start
              .flatMap: pending =>
                entered.get.timeoutTo(2.seconds, IO.raiseError(RuntimeException("write did not block"))) *>
                  (room.snapshot, seen.get).tupled.flatMap: (during, events) =>
                    IO:
                      assertEquals(during.version, before.version)
                      assertEquals(during.lab.map(_.turn.phase), Some(Phase.AwaitingFirstRoll))
                      assert(!events.exists {
                        case GameEvent.LabTurnChanged(_, _, lab, _, _) => lab.turn.phase == Phase.AwaitingSecondRoll
                        case _                                         => false
                      })
                  *> release.complete(()).void *> pending.join.void *>
                  awaitPhase(room, Phase.AwaitingSecondRoll).void
      _ <- observed.get.timeoutTo(2.seconds, IO.raiseError(RuntimeException("subscriber did not observe the roll")))
      _ <- subscriber.cancel
      events <- seen.get
      _ = assert(events.exists {
        case GameEvent.LabTurnChanged(_, _, lab, _, _) => lab.turn.phase == Phase.AwaitingSecondRoll
        case _                                         => false
      })
      _ <- room.stopForRestart
    yield ()

  test("one per-move budget spans both rolls and choice, and timeout never selects for the player"):
    for
      snapshot      <- initial(TimeControl.PerMove(1))
      (room, saved) <- restore(snapshot, baseTuning.copy(idleCheck = 3.seconds))
      over          <- withBoth(room):
        room.submit(Seat.White, GameCommand.SubmitSeed(aliceSeed)) *>
          room.submit(Seat.Black, GameCommand.SubmitSeed(bobSeed)) *>
          awaitPhase(room, Phase.AwaitingFirstRoll).void *>
          IO.sleep(350.millis) *>
          command(room, Seat.White, Action.RollFirst, "timed-first").void *>
          IO.sleep(350.millis) *>
          command(room, Seat.White, Action.RollSecond, "timed-second").void *>
          room.result.timeoutTo(700.millis, IO.raiseError(RuntimeException("per-move clock reset during roll")))
      ended <- latest(saved)
      _ = assertEquals(over.termination, Termination.Timeout)
      _ = assertEquals(ended.lab.map(_.turn.phase), Some(Phase.Ended))
      _ = assertEquals(ended.lab.map(_.turn.selectedPairIndex), Some(None))
      _ = assert(ended.turns.isEmpty)
      _ <- room.stopForRestart
    yield ()

  test("accepted stage progress refreshes the unlimited idle cap"):
    for
      snapshot  <- initial(TimeControl.Unlimited)
      (room, _) <- restore(snapshot, baseTuning.copy(idleCheck = 350.millis))
      state     <- withBoth(room):
        room.submit(Seat.White, GameCommand.SubmitSeed(aliceSeed)) *>
          room.submit(Seat.Black, GameCommand.SubmitSeed(bobSeed)) *>
          awaitPhase(room, Phase.AwaitingFirstRoll).void *>
          IO.sleep(200.millis) *>
          command(room, Seat.White, Action.RollFirst, "idle-progress").void *>
          IO.sleep(200.millis) *>
          room.snapshot
      _ = assertEquals(state.status, GameStatus.Active)
      over <- room.result.timeoutTo(700.millis, IO.raiseError(RuntimeException("idle cap did not eventually expire")))
      _ = assertEquals(over.termination, Termination.Timeout)
      _ <- room.stopForRestart
    yield ()

  test("duplicate or stale commands and reconnects do not refresh the unlimited idle cap"):
    for
      snapshot  <- initial(TimeControl.Unlimited)
      (room, _) <- restore(snapshot, baseTuning.copy(idleCheck = 350.millis))
      over      <- withBoth(room):
        room.submit(Seat.White, GameCommand.SubmitSeed(aliceSeed)) *>
          room.submit(Seat.Black, GameCommand.SubmitSeed(bobSeed)) *>
          awaitPhase(room, Phase.AwaitingFirstRoll).flatMap: state =>
            IO.sleep(190.millis) *>
              room
                .submitLaboratory(Seat.White, Command("idle-roll", state.version, Action.RollFirst))
                .flatMap: accepted =>
                  val stale = room.submitLaboratory(Seat.White, Command("stale", state.version, Action.RollSecond))
                  accepted match
                    case Right(_) =>
                      val duplicate =
                        room.submitLaboratory(Seat.White, Command("idle-roll", state.version, Action.RollFirst))
                      (duplicate, stale).tupled.flatMap: (repeated, staleResult) =>
                        if repeated != accepted || !staleResult.left.toOption.exists(_.code == ErrorCode.StaleVersion)
                        then IO.raiseError(RuntimeException("duplicate receipt or stale rejection was not preserved"))
                        else
                          room.connection(Seat.White).use(_ => IO.unit) *>
                            room.connection(Seat.White).use(_ => IO.sleep(230.millis)) *>
                            room.result.timeoutTo(
                              500.millis,
                              IO.raiseError(RuntimeException("stale/reconnect traffic refreshed idle cap"))
                            )
                    case Left(error) => IO.raiseError(RuntimeException(s"valid stage failed: ${error.code}"))
      _ = assertEquals(over.termination, Termination.Timeout)
      _ <- room.stopForRestart
    yield ()

  test("an exhausted intermediate write aborts from the last durable stage without an accepted receipt"):
    for
      snapshot <- initial()
      attempts <- Ref.of[IO, Int](0)
      required = Durability.Required(
        RetryPolicy(Some(2), 5.millis, 5.millis),
        RetryPolicy(Some(2), 5.millis, 5.millis),
        50.millis,
        _ => IO.unit
      )
      failSecond = (next: GameSnapshot, saved: Ref[IO, Vector[GameSnapshot]]) =>
        if next.lab.exists(_.turn.phase == Phase.AwaitingSecondRoll) then
          attempts.update(_ + 1) *> IO.raiseError(RuntimeException("transient store outage"))
        else saved.update(_ :+ next)
      (room, saved) <- restore(snapshot, durability = required, customSave = Some(failSecond))
      _             <- startReady(room)
      first         <- room.snapshot
      _             <- room.submitLaboratory(Seat.White, Command("lost-roll", first.version, Action.RollFirst)).attempt
      over  <- room.result.timeoutTo(3.seconds, IO.raiseError(RuntimeException("required write failure did not abort")))
      count <- attempts.get
      terminal <- latest(saved)
      _ = assertEquals(over.termination, Termination.Aborted)
      _ = assertEquals(count, 2)
      _ = assertEquals(terminal.lab.map(_.turn.phase), Some(Phase.Ended))
      _ = assert(!terminal.lab.exists(_.receipts.exists(_.command.commandId == "lost-roll")))
      _ <- room.stopForRestart
    yield ()

  test("restore rejects unsupported provenance and altered committed offers"):
    for
      snapshot <- initial()
      badProvenance = snapshot.copy(lab =
        snapshot.lab.map(state =>
          state.copy(
            provenance = state.provenance.copy(rulesVersion = "unsupported")
          )
        )
      )
      invalid <- GameRoom.restoreLaboratory(
        badProvenance,
        baseTuning,
        GameRoom.RoomPersistence(durability = Durability.required(_ => IO.unit))
      )
      (room, saved) <- restore(snapshot)
      _             <- startReady(room)
      _             <- withBoth(room)(command(room, Seat.White, Action.RollFirst, "reveal-first").void)
      revealed      <- latest(saved)
      tampered = revealed.copy(lab = revealed.lab.map: state =>
        val firstPair = state.turn.generatedOffers.head
        val changed   = firstPair.copy(first = if firstPair.first == 6 then 5 else firstPair.first + 1)
        state.copy(turn = state.turn.copy(generatedOffers = state.turn.generatedOffers.updated(0, changed))))
      badOffers <- GameRoom.restoreLaboratory(
        tampered,
        baseTuning,
        GameRoom.RoomPersistence(durability = Durability.required(_ => IO.unit))
      )
      _ = assert(invalid.isLeft)
      _ = assert(badOffers.isLeft)
      _ <- room.stopForRestart
    yield ()
