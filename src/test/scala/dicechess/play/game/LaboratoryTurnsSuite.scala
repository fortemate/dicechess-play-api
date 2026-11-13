package dicechess.play.game

import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.dice.DiceSource
import dicechess.play.store.{GameSnapshot, LaboratoryStorage}

import scala.concurrent.duration.*

class LaboratoryTurnsSuite extends munit.FunSuite:
  private val whiteId     = "018bcfe5-6800-7cc2-98c4-7e2a1f94c201"
  private val blackId     = "018bcfe5-6800-7cc2-98c4-7e2a1f94c202"
  private val white       = Seat.White
  private val black       = Seat.Black
  private val seed        = (0 until 32).map(_.toByte).toArray
  private val clientSeeds = ClientSeeds("white-seed-fixture", "black-seed-fixture")

  private def fresh(
      timeControl: TimeControl = TimeControl.Fischer(60, 5),
      frozenSeeds: Option[ClientSeeds] = Some(clientSeeds)
  ): GameRoom.Session =
    val state = EngineOps.parse(EngineOps.InitialDfen).fold(error => fail(error), identity)
    val lab   = LaboratoryStorage.State(
      ProtocolVersion,
      Provenance(RulesVersion.value, DiceAlgorithm),
      EngineOps.InitialDfen,
      LaboratoryStorage.PendingOffers(Phase.AwaitingPlayers, Nil, 0, None),
      frozenSeeds,
      Vector.empty,
      joinDeadlineAtEpochMs = 1L,
      joined = Set.empty,
      fairnessRevealed = false
    )
    GameRoom.Session(
      state,
      0L,
      Map(white -> Principal.Guest(whiteId), black -> Principal.Guest(blackId)),
      Map.empty,
      Map.empty,
      DiceSource.commitReveal(seed),
      0L,
      false,
      GameStatus.Active,
      timeControl,
      remaining = Map(white -> 60.seconds, black -> 60.seconds),
      lab = Some(lab)
    )

  private def open(s: GameRoom.Session, at: FiniteDuration = Duration.Zero): GameRoom.Session =
    LaboratoryTurns.open(s, at).fold(error => fail(error), _.session)

  private def command(s: GameRoom.Session, action: Action, id: String): Command =
    Command(id, s.version, action)

  private def snapshotOf(session: GameRoom.Session): GameSnapshot =
    GameSnapshot(
      session.version,
      EngineOps.serialize(session.state),
      session.players,
      Map(white -> "white-token", black -> "black-token"),
      session.dice.reveal,
      Map(white -> clientSeeds.white, black -> clientSeeds.black),
      session.started,
      session.ply,
      session.pending,
      session.status,
      session.timeControl,
      Map(white -> 60000L, black -> 60000L),
      session.lastRoll,
      session.turns,
      variant = Some(TwoPairs),
      lab = session.lab
    )

  /** Every accepted fixture action follows the production boundary: admission first, then the pure transition. */
  private def accept(
      s: GameRoom.Session,
      seat: Seat,
      action: Action,
      id: String,
      at: FiniteDuration = Duration.Zero
  ): LaboratoryTurns.Effect =
    val cmd = command(s, action, id)
    assertEquals(LaboratoryTurns.admit(s, seat, cmd), Right(None))
    LaboratoryTurns.apply(s, seat, cmd, at).fold(r => fail(s"unexpected rejection: $r"), identity)

  private def awaitingChoice(s: GameRoom.Session): GameRoom.Session =
    val opened = open(s)
    val first  = accept(opened, white, Action.RollFirst, "first-roll")
    val second = accept(first.session, white, Action.RollSecond, "second-roll")
    second.session

  private def atChoiceWithOffers(offers: List[DicePair]): GameRoom.Session =
    val s = awaitingChoice(fresh())
    s.copy(lab = s.lab.map(l => l.copy(turn = l.turn.copy(generatedOffers = offers))))

  private def reject(s: GameRoom.Session, seat: Seat, cmd: Command, expected: ErrorCode): Rejection =
    val refusal = LaboratoryTurns.admit(s, seat, cmd).left.toOption.getOrElse(fail("expected command refusal"))
    assertEquals(refusal.code, expected)
    refusal

  test("opening freezes effective seeds and publishes a hidden two-offer context"):
    val effect = LaboratoryTurns.open(fresh(), 3.seconds).fold(error => fail(error), identity)
    val state  = effect.session
    val turn   = state.lab.get.turn
    assertEquals(state.version, 1L)
    assertEquals(turn.phase, Phase.AwaitingFirstRoll)
    assertEquals(turn.generatedOffers.size, 2)
    assertEquals(turn.revealedOfferCount, 0)
    assertEquals(turn.publicTurn.offers, Nil)
    assertEquals(state.lab.get.frozenClientSeeds, Some(clientSeeds))
    assertEquals(effect.events.size, 1)
    val event = effect.events.head.asInstanceOf[GameEvent.LabTurnChanged]
    assertEquals(event.lab.turn.offers, Nil)
    assertEquals(event.lab.turn.phase, Phase.AwaitingFirstRoll)

  test("seed fallback is frozen at opening and the derived pair order is repeatable"):
    val original = fresh(frozenSeeds = None)
    val first    = open(original)
    val again    = open(original)
    assert(first.lab.get.frozenClientSeeds.nonEmpty)
    assertEquals(first.lab.get.frozenClientSeeds, again.lab.get.frozenClientSeeds)
    assertEquals(first.lab.get.turn.generatedOffers, again.lab.get.turn.generatedOffers)

  test("each roll reveals exactly one more offer and cannot be repeated at the wrong stage"):
    val opened = open(fresh())
    val first  = accept(opened, white, Action.RollFirst, "first-roll")
    assertEquals(first.session.lab.get.turn.publicTurn.offers.size, 1)
    assertEquals(first.session.lab.get.turn.publicTurn.phase, Phase.AwaitingSecondRoll)
    val second = accept(first.session, white, Action.RollSecond, "second-roll")
    assertEquals(second.session.lab.get.turn.publicTurn.offers.size, 2)
    assertEquals(second.session.lab.get.turn.publicTurn.phase, Phase.AwaitingPairChoice)

    val tooSoon = command(opened, Action.RollSecond, "too-soon")
    assertEquals(LaboratoryTurns.admit(opened, white, tooSoon), Right(None))
    assertEquals(
      LaboratoryTurns.apply(opened, white, tooSoon, 0.seconds).left.toOption.map(_.code),
      Some(ErrorCode.WrongStage)
    )
    assertEquals(opened.version, 1L)

  test("admission rejects missing laboratory context, unready games, wrong seats and stale versions"):
    val unstarted = fresh()
    val staged    = open(unstarted)
    val valid     = Command("valid", staged.version, Action.RollFirst)
    assertEquals(
      reject(
        unstarted.copy(lab = None),
        white,
        Command("classic", 0, Action.RollFirst),
        ErrorCode.InvalidCommand
      ).resync,
      false
    )
    assertEquals(
      reject(unstarted, white, Command("not-ready", 0, Action.RollFirst), ErrorCode.NotReady).code,
      ErrorCode.NotReady
    )
    assertEquals(reject(staged, black, valid, ErrorCode.WrongSeat).code, ErrorCode.WrongSeat)
    assertEquals(reject(staged, Seat.Spectator, valid, ErrorCode.WrongSeat).code, ErrorCode.WrongSeat)
    val stale = Command("stale", staged.version - 1, Action.RollFirst)
    assertEquals(reject(staged, white, stale, ErrorCode.StaleVersion).resync, true)
    assertEquals(
      reject(staged, white, Command("bad id", staged.version, Action.RollFirst), ErrorCode.InvalidCommand).code,
      ErrorCode.InvalidCommand
    )
    assertEquals(
      reject(staged, white, Command("too-large", MaxWireVersion + 1, Action.RollFirst), ErrorCode.InvalidCommand).code,
      ErrorCode.InvalidCommand
    )

  test("command receipts retry unchanged requests across later versions and reject id reuse"):
    val opened       = open(fresh())
    val firstCommand = Command("stable-id", opened.version, Action.RollFirst)
    assertEquals(LaboratoryTurns.admit(opened, white, firstCommand), Right(None))
    val applied     = LaboratoryTurns.apply(opened, white, firstCommand, 0.seconds).toOption.get
    val afterSecond = accept(applied.session, white, Action.RollSecond, "later-id").session
    assertEquals(
      LaboratoryTurns.admit(afterSecond, white, firstCommand),
      Right(Some(Receipt("stable-id", applied.session.version)))
    )
    val changed = firstCommand.copy(action = Action.RollSecond)
    assertEquals(reject(afterSecond, white, changed, ErrorCode.CommandConflict).resync, true)

  test("a receipt remains retryable after the game ends"):
    val opened  = open(fresh())
    val cmd     = Command("before-end", opened.version, Action.RollFirst)
    val applied = accept(opened, white, cmd.action, cmd.commandId)
    val ended   = LaboratoryTurns.end(applied.session, GameOver(GameResult.Draw, Termination.Aborted), 1.second).session
    assertEquals(LaboratoryTurns.admit(ended, white, cmd), Right(Some(Receipt(cmd.commandId, applied.session.version))))
    assertEquals(
      reject(ended, white, Command("new-after-end", ended.version, Action.RollFirst), ErrorCode.GameEnded).code,
      ErrorCode.GameEnded
    )

  test("pair selection exposes only its legal tree and rejects incomplete or empty submissions"):
    val choice = atChoiceWithOffers(List(DicePair(3, 4), DicePair(1, 3)))
    val chosen = accept(choice, white, Action.ChoosePair(1), "choose-legal")
    assertEquals(chosen.session.lastRoll, List(1, 3))
    assert(chosen.session.turnCache.turns.contains(List("e2e3", "f1c4")))
    assert(!chosen.session.turnCache.turns.contains(List("e2e3")))
    assertEquals(chosen.events.head.asInstanceOf[GameEvent.LabTurnChanged].lab.turn.selectedPairIndex, Some(1))

    val illegal = Command("incomplete", chosen.session.version, Action.SubmitTurn(List("e2e3")))
    assertEquals(LaboratoryTurns.admit(chosen.session, white, illegal), Right(None))
    val rejected = LaboratoryTurns.apply(chosen.session, white, illegal, 0.seconds).left.toOption.get
    assertEquals(rejected.code, ErrorCode.InvalidCommand)
    assertEquals(chosen.session.version, 4L)
    assertEquals(
      chosen.session.lab.get.receipts.map(_.command.commandId),
      Vector("first-roll", "second-roll", "choose-legal")
    )
    val empty = Command("empty", chosen.session.version, Action.SubmitTurn(Nil))
    assertEquals(
      LaboratoryTurns.admit(chosen.session, white, empty).left.toOption.map(_.code),
      Some(ErrorCode.InvalidCommand)
    )
    assertEquals(
      LaboratoryTurns.apply(chosen.session, white, empty, 0.seconds).left.toOption.map(_.code),
      Some(ErrorCode.InvalidCommand)
    )

  test("an unplayable alternative is not passed until explicitly selected"):
    val choice = atChoiceWithOffers(List(DicePair(3, 4), DicePair(1, 3)))
    assertEquals(choice.lab.get.turn.phase, Phase.AwaitingPairChoice)
    assertEquals(choice.ply, 0L)
    assertEquals(choice.turns, Vector.empty)
    assertEquals(choice.version, 3L)

    val passed = accept(choice, white, Action.ChoosePair(0), "choose-pass", 0.seconds)
    assertEquals(passed.session.ply, 1L)
    assertEquals(passed.session.turns.size, 1)
    val record = passed.session.turns.head
    assertEquals(record.moves, Nil)
    assertEquals(record.dice, List(3, 4))
    assertEquals(record.labChoice, Some(HistoryChoice(List(DicePair(3, 4), DicePair(1, 3)), 0)))
    assertEquals(passed.events.collect { case _: GameEvent.TurnPlayed => 1 }.size, 1)
    assertEquals(passed.events.last.asInstanceOf[GameEvent.LabTurnChanged].v, passed.session.version)
    assertEquals(passed.session.lab.get.receipts.last.appliedVersion, passed.session.version)
    assertEquals(passed.session.remaining(white), 65.seconds)

  test("equal generated offers preserve a valid independent choice index"):
    val equal  = List(DicePair(3, 4), DicePair(3, 4))
    val choice = atChoiceWithOffers(equal)
    val effect = accept(choice, white, Action.ChoosePair(1), "equal-offer-choice")
    assertEquals(effect.session.turns.head.dice, equal(1).values)
    assertEquals(effect.session.lastRoll, Nil)
    assertEquals(effect.session.turns.head.labChoice, Some(HistoryChoice(equal, 1)))

  test("a complete selected-pair path records both offers and the chosen index"):
    val choice   = atChoiceWithOffers(List(DicePair(3, 4), DicePair(1, 3)))
    val selected = accept(choice, white, Action.ChoosePair(1), "choose-playable")
    val play     = Command("play-selected", selected.session.version, Action.SubmitTurn(List("e2e3", "f1c4")))
    assertEquals(LaboratoryTurns.admit(selected.session, white, play), Right(None))
    val completed = LaboratoryTurns
      .apply(selected.session, white, play, 1.second)
      .fold(r => fail(s"unexpected rejection: $r"), identity)
    assertEquals(completed.session.ply, 1L)
    assertEquals(completed.session.turns.last.moves, List("e2e3", "f1c4"))
    assertEquals(completed.session.turns.last.labChoice, Some(HistoryChoice(List(DicePair(3, 4), DicePair(1, 3)), 1)))
    assert(completed.events.exists {
      case GameEvent.TurnPlayed(_, seat, moves, _) => seat == white && moves == List("e2e3", "f1c4")
      case _                                       => false
    })

  test("pair indices are bounded and a changed choice is not allowed after selection"):
    val choice  = atChoiceWithOffers(List(DicePair(1, 3), DicePair(2, 2)))
    val invalid = Command("bad-index", choice.version, Action.ChoosePair(2))
    assertEquals(
      LaboratoryTurns.admit(choice, white, invalid).left.toOption.map(_.code),
      Some(ErrorCode.InvalidPairIndex)
    )
    assertEquals(
      LaboratoryTurns.apply(choice, white, invalid, 0.seconds).left.toOption.map(_.code),
      Some(ErrorCode.InvalidPairIndex)
    )
    val selected = accept(choice, white, Action.ChoosePair(0), "select-once")
    val repeated = Command("different-choice", selected.session.version, Action.ChoosePair(1))
    assertEquals(LaboratoryTurns.admit(selected.session, white, repeated), Right(None))
    assertEquals(
      LaboratoryTurns.apply(selected.session, white, repeated, 0.seconds).left.toOption.map(_.code),
      Some(ErrorCode.WrongStage)
    )

  test("recovery validation re-derives the committed offers from frozen inputs"):
    val session  = open(fresh())
    val snapshot = snapshotOf(session)
    assertEquals(LaboratoryTurns.validateRecovery(snapshot), Right(()))
    val tampered = snapshot.copy(lab =
      snapshot.lab.map(l =>
        l.copy(
          turn = l.turn.copy(generatedOffers = List(DicePair(1, 1), DicePair(1, 1)))
        )
      )
    )
    assert(LaboratoryTurns.validateRecovery(tampered).isLeft)

  test("recovery rejects missing offers after opening but allows waiting and ended snapshots"):
    val opened        = open(fresh())
    val missingOffers =
      snapshotOf(opened).copy(lab = opened.lab.map(l => l.copy(turn = l.turn.copy(generatedOffers = Nil))))
    assert(LaboratoryTurns.validateRecovery(missingOffers).isLeft)

    assertEquals(LaboratoryTurns.validateRecovery(snapshotOf(fresh())), Right(()))

    val ended = LaboratoryTurns.end(opened, GameOver(GameResult.Draw, Termination.Aborted), 1.second).session
    val endedWithoutOffers =
      snapshotOf(ended).copy(lab = ended.lab.map(l => l.copy(turn = l.turn.copy(generatedOffers = Nil))))
    assertEquals(LaboratoryTurns.validateRecovery(endedWithoutOffers), Right(()))
