package dicechess.play.game

import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.dice.LaboratoryDiceSource
import dicechess.play.store.{LaboratoryStorage, TurnRecord}
import dicechess.play.game.GameRoom.{Session, TurnCache}
import dicechess.play.wire.LaboratoryCodecs
import dicechess.play.wire.LaboratoryCodecs.given

import scala.concurrent.duration.*

/** Pure single-writer transitions. An effect group, including its retry receipt, is committed once before any event. */
private[play] object LaboratoryTurns:
  final case class Effect(session: Session, events: List[GameEvent])

  def publicState(s: Session): PublicState =
    val lab = s.lab.get
    PublicState(TwoPairs, lab.protocolVersion, lab.provenance, lab.turn.publicTurn)

  def rejection(s: Session, commandId: Option[String], code: ErrorCode, reason: String): Rejection =
    Rejection(
      s.version,
      commandId.filter(validCommandId),
      code,
      reason,
      resync = code == ErrorCode.StaleVersion || code == ErrorCode.CommandConflict
    )

  def admit(s: Session, seat: Seat, command: Command): Either[Rejection, Option[Receipt]] =
    def refuse(code: ErrorCode, reason: String) = Left(rejection(s, Some(command.commandId), code, reason))
    if s.lab.isEmpty then refuse(ErrorCode.InvalidCommand, "laboratory commands are not enabled")
    else if !s.players.contains(seat) || seat.side.isEmpty then refuse(ErrorCode.WrongSeat, "player seat required")
    else if !validCommandId(command.commandId) || !validVersion(command.expectedVersion) then
      refuse(ErrorCode.InvalidCommand, "invalid laboratory command")
    else
      s.lab.get.receipts.find(r => r.seat == seat && r.command.commandId == command.commandId) match
        case Some(receipt) if receipt.command == command =>
          Right(Some(Receipt(command.commandId, receipt.appliedVersion)))
        case Some(_) => refuse(ErrorCode.CommandConflict, "command id already used with different fields")
        case None    => admitFresh(s, seat, command).map(_ => None)

  private def admitFresh(s: Session, seat: Seat, command: Command): Either[Rejection, Unit] =
    def refuse(code: ErrorCode, reason: String) = Left(rejection(s, Some(command.commandId), code, reason))
    val invalidIndex                            = command.action match
      case Action.ChoosePair(index) => index < 0 || index > 1
      case _                        => false
    if s.ended then refuse(ErrorCode.GameEnded, "game is over")
    else if command.expectedVersion != s.version then refuse(ErrorCode.StaleVersion, "fetch the current snapshot")
    else if !s.started then refuse(ErrorCode.NotReady, "waiting for both players and seed readiness")
    else if EngineOps.activeSeat(s.state) != seat then refuse(ErrorCode.WrongSeat, "not your turn")
    else if s.isDrawPending then refuse(ErrorCode.WrongStage, "respond to the pending draw offer first")
    else if invalidIndex then refuse(ErrorCode.InvalidPairIndex, "pair index must be 0 or 1")
    else if summon[LaboratoryCodecs.ValidatedCodec[Command]].validateValue(command).isLeft then
      refuse(ErrorCode.InvalidCommand, "invalid laboratory command")
    else Right(())

  def open(s: Session, at: FiniteDuration): Either[String, Effect] =
    val lab   = s.lab.get
    val seeds = lab.frozenClientSeeds.getOrElse(s.clientSeedsRevealed)
    for
      source <- LaboratoryDiceSource.fromHexSeed(s.dice.reveal, lab.provenance.diceAlgorithm)
      offers <- source.offers(s.ply, seeds.white, seeds.black)
    yield
      val opened = s.copy(
        started = true,
        pending = false,
        state = EngineOps.withDice(s.state, Nil),
        lastRoll = Nil,
        turnCache = TurnCache.empty,
        turnStartedAt = Some(at),
        labActivityAt = Some(at),
        lab = Some(
          lab.copy(
            frozenClientSeeds = Some(seeds),
            turn = LaboratoryStorage.PendingOffers(Phase.AwaitingFirstRoll, offers.values, 0, None)
          )
        )
      )
      stage(opened, at)

  def apply(s: Session, seat: Seat, command: Command, at: FiniteDuration): Either[Rejection, Effect] =
    val phase                                   = s.lab.get.turn.phase
    def refuse(code: ErrorCode, reason: String) = Left(rejection(s, Some(command.commandId), code, reason))
    val effect                                  = command.action match
      case Action.RollFirst if phase == Phase.AwaitingFirstRoll   => Right(reveal(s, Phase.AwaitingSecondRoll, 1, at))
      case Action.RollSecond if phase == Phase.AwaitingSecondRoll => Right(reveal(s, Phase.AwaitingPairChoice, 2, at))
      case Action.ChoosePair(index) if index < 0 || index > 1     =>
        refuse(ErrorCode.InvalidPairIndex, "pair index must be 0 or 1")
      case Action.ChoosePair(index) if phase == Phase.AwaitingPairChoice =>
        val selected = s.lab.get.turn.generatedOffers(index).values
        val rolled   = EngineOps.withDice(s.state, selected)
        val cache    = GameRoom.turnCache(rolled)
        val chosen   = s.copy(
          state = rolled,
          pending = true,
          lastRoll = selected,
          turnCache = cache,
          labActivityAt = Some(at),
          lab = s.lab.map(l =>
            l.copy(turn = l.turn.copy(phase = Phase.PlayingSelectedPair, selectedPairIndex = Some(index)))
          )
        )
        val choice = stage(chosen, at)
        if cache.turns.nonEmpty then Right(choice)
        else Right(append(choice, complete(choice.session, seat, Nil, Nil, offerDraw = false, at)))
      case Action.SubmitTurn(moves, offerDraw) if phase == Phase.PlayingSelectedPair =>
        s.turnCache.turns.get(moves).filter(_.nonEmpty) match
          case None       => refuse(ErrorCode.InvalidCommand, "illegal selected-pair turn")
          case Some(path) => Right(complete(s, seat, moves, path, offerDraw, at))
      case _ => refuse(ErrorCode.WrongStage, "command is not allowed at this stage")
    effect.map: group =>
      val receipt = LaboratoryStorage.AcceptedCommand(seat, command, group.session.version)
      group.copy(session =
        group.session.copy(lab = group.session.lab.map(l => l.copy(receipts = l.receipts :+ receipt)))
      )

  private def reveal(s: Session, phase: Phase, count: Int, at: FiniteDuration): Effect =
    stage(
      s.copy(
        labActivityAt = Some(at),
        lab = s.lab.map(l => l.copy(turn = l.turn.copy(phase = phase, revealedOfferCount = count)))
      ),
      at
    )

  private def stage(s: Session, at: FiniteDuration): Effect =
    val next = s.copy(version = s.version + 1)
    Effect(
      next,
      List(
        GameEvent.LabTurnChanged(
          next.version,
          EngineOps.activeSeat(next.state),
          publicState(next),
          EngineOps.serialize(next.state),
          GameRoom.liveClocks(next, at)
        )
      )
    )

  private def append(first: Effect, second: Effect): Effect =
    Effect(second.session, first.events ++ second.events)

  private def complete(
      s: Session,
      seat: Seat,
      uci: List[String],
      path: List[dicechess.engine.domain.Move],
      offerDraw: Boolean,
      at: FiniteDuration
  ): Effect =
    val (nextState, winner) = EngineOps.applyPath(s.state, path)
    val (debited, elapsed)  = GameRoom.debit(s, seat, at)
    val offer  = winner.isEmpty && (offerDraw || s.armedDrawOffer.getOrElse(seat, false)) && s.mayOffer(seat)
    val lab    = s.lab.get
    val choice = HistoryChoice(lab.turn.generatedOffers, lab.turn.selectedPairIndex.get)
    val next   = debited.copy(
      version = s.version + 1,
      state = nextState,
      ply = s.ply + 1,
      pending = false,
      turnCache = TurnCache.empty,
      armedDrawOffer = s.armedDrawOffer.updated(seat, false),
      drawTogglesThisTurn = Map.empty,
      pendingDrawOffer = Option.when(offer)(seat),
      lastDrawOfferer = if offer then Some(seat) else s.lastDrawOfferer,
      turnsSinceLastOffer =
        s.turnsSinceLastOffer.updated(seat, if offer then 0 else s.turnsSinceLastOffer.getOrElse(seat, 0) + 1),
      turns = s.turns :+ TurnRecord(
        s.ply,
        if seat == Seat.White then "w" else "b",
        s.lastRoll,
        uci,
        EngineOps.serialize(nextState),
        Some(elapsed),
        Some(choice)
      )
    )
    val played = Effect(next, List(GameEvent.TurnPlayed(next.version, seat, uci, EngineOps.serialize(nextState))))
    val over   = winner
      .map(w => GameOver(GameResult.Win(w), Termination.KingCaptured))
      .orElse(
        Option.when(nextState.halfMoveClock >= 100 || next.ply >= 5000)(GameOver(GameResult.Draw, Termination.Draw))
      )
    over match
      case Some(result) => append(played, end(next, result, at))
      case None         =>
        val start  = at + GameRoom.presentationGraceFor(next, EngineOps.activeSeat(nextState), includeRoll = false)
        val opened =
          open(next, start).fold(_ => end(next, GameOver(GameResult.Draw, Termination.Aborted), at), identity)
        if offer then
          val offered = opened.session.copy(version = opened.session.version + 1)
          append(append(played, opened), Effect(offered, List(GameEvent.DrawOffered(offered.version, seat))))
        else append(played, opened)

  def end(s: Session, over: GameOver, at: FiniteDuration): Effect =
    val ended = s.copy(
      status = GameStatus.Ended(over),
      pending = false,
      turnStartedAt = None,
      lab = s.lab.map(l =>
        l.copy(turn = l.turn.copy(phase = Phase.Ended), fairnessRevealed = l.frozenClientSeeds.isDefined)
      )
    )
    val projected = stage(ended, at)
    val next      = projected.session.copy(version = projected.session.version + 1)
    val revealed  = next.lab.exists(_.fairnessRevealed)
    append(
      projected,
      Effect(
        next,
        List(
          GameEvent.GameEnded(
            next.version,
            over,
            Option.when(revealed)(s.dice.reveal),
            Option.when(revealed)(s.clientSeedsRevealed)
          )
        )
      )
    )

  def validateRecovery(snapshot: dicechess.play.store.GameSnapshot): Either[String, Unit] =
    for
      _ <- LaboratoryStorage.validateSnapshot(snapshot)
      _ <- Either.cond(
        snapshot.players.keySet == Set(Seat.White, Seat.Black) &&
          snapshot.seatTokens.keySet == snapshot.players.keySet && snapshot.seatTokens.values.toSet.size == 2,
        (),
        "laboratory recovery requires both private seats"
      )
      lab <- snapshot.lab.toRight("laboratory recovery state is required")
      _ <- Either.cond(lab.provenance.rulesVersion == RulesVersion.value, (), "unsupported laboratory rules provenance")
      source <- LaboratoryDiceSource.fromHexSeed(snapshot.serverSeed, lab.provenance.diceAlgorithm)
      _      <-
        if snapshot.ended || lab.turn.phase == Phase.AwaitingPlayers then Right(())
        else
          for
            seeds    <- lab.frozenClientSeeds.toRight("frozen laboratory seeds are required")
            expected <- source.offers(snapshot.ply, seeds.white, seeds.black)
            _        <- Either.cond(
              expected.values == lab.turn.generatedOffers,
              (),
              "laboratory offers do not match committed inputs"
            )
          yield ()
    yield ()
