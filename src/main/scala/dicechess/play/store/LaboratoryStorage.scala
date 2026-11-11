package dicechess.play.store

import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.EngineOps
import dicechess.play.wire.Codecs.given
import dicechess.play.wire.LaboratoryCodecs
import dicechess.play.wire.LaboratoryCodecs.{ValidatedCodec, encodeValidated}
import dicechess.play.wire.LaboratoryCodecs.given
import io.circe.{Codec, Json}
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax.*

/** Private recovery records. Public projections are deliberately constructed field by field below. */
object LaboratoryStorage:
  private val Seats = Set(Seat.White, Seat.Black)

  final case class PendingOffers(
      phase: Phase,
      generatedOffers: List[DicePair],
      revealedOfferCount: Int,
      selectedPairIndex: Option[Int]
  ):
    def publicTurn: PublicTurn = PublicTurn(phase, generatedOffers.take(revealedOfferCount), selectedPairIndex)

  /** The semantic request, including its original expectedVersion, is the durable retry fingerprint. */
  final case class AcceptedCommand(seat: Seat, command: Command, appliedVersion: Long)

  final case class State(
      protocolVersion: Int,
      provenance: Provenance,
      initialDfen: String,
      turn: PendingOffers,
      frozenClientSeeds: Option[ClientSeeds],
      receipts: Vector[AcceptedCommand],
      joinDeadlineAtEpochMs: Long,
      joined: Set[Seat],
      fairnessRevealed: Boolean
  ):
    override def toString: String = s"LaboratoryState($protocolVersion,${turn.phase},<private recovery data>)"

  final case class Conditions(variant: VariantIdentity, protocolVersion: Int, timeControl: TimeControl)

  def conditions(snapshot: GameSnapshot): Conditions =
    Conditions(snapshot.effectiveVariant, snapshot.lab.fold(ProtocolVersion)(_.protocolVersion), snapshot.timeControl)

  private def valid[A](ok: Boolean, value: A, message: String): Either[String, A] = Either.cond(ok, value, message)

  def validatePending(turn: PendingOffers): Either[String, PendingOffers] =
    val count     = turn.generatedOffers.size
    val generated = turn.phase match
      case Phase.AwaitingPlayers   => count == 0
      case Phase.AwaitingFirstRoll => count == 0 || count == 2
      case Phase.Ended             => count == 0 || count == 2
      case _                       => count == 2
    for
      _ <- valid(
        generated && turn.revealedOfferCount >= 0 && turn.revealedOfferCount <= count,
        (),
        "invalid private offer state"
      )
      _ <- turn.generatedOffers.traverse(validatePair)
      _ <- validateTurn(turn.publicTurn)
    yield turn

  private def validateReceipt(receipt: AcceptedCommand): Either[String, AcceptedCommand] =
    for
      _ <- encodeValidated(receipt.command).left.map(_.message)
      _ <- valid(
        receipt.seat.side.isDefined && validVersion(receipt.appliedVersion) &&
          receipt.appliedVersion > receipt.command.expectedVersion,
        (),
        "invalid laboratory command receipt"
      )
    yield receipt

  private def validateState(state: State): Either[String, State] =
    for
      _ <- valid(state.protocolVersion == ProtocolVersion, (), "unsupported laboratory protocol version")
      _ <- encodeValidated(state.provenance).left.map(_.message)
      _ <- EngineOps.parse(state.initialDfen).left.map(_ => "invalid laboratory initial position")
      _ <- validatePending(state.turn)
      _ <- state.receipts.toList.traverse(validateReceipt)
      _ <- valid(
        state.receipts.map(r => (r.seat, r.command.commandId)).distinct.size == state.receipts.size,
        (),
        "duplicate laboratory command receipt"
      )
      _ <- valid(
        state.joinDeadlineAtEpochMs > 0 && state.joined.subsetOf(Seats),
        (),
        "invalid laboratory join recovery data"
      )
      _ <- valid(
        state.frozenClientSeeds.forall(s => s.white.nonEmpty && s.black.nonEmpty),
        (),
        "invalid frozen seed inputs"
      )
    yield state

  def validateTurnRecord(turn: TurnRecord): Either[String, TurnRecord] =
    turn.labChoice
      .traverse { choice =>
        for
          _ <- encodeValidated(choice).left.map(_.message)
          _ <- valid(
            turn.dice == choice.offers(choice.selectedPairIndex).values && turn.turnNumber >= 0 &&
              Set("w", "b")(turn.activeColor) && turn.moves.size <= 2 &&
              turn.moves.forall(_.matches("[a-h][1-8][a-h][1-8][qrbn]?")),
            (),
            "invalid laboratory completed turn"
          )
        yield choice
      }
      .map(_ => turn)

  private def validTimeControl(control: TimeControl): Boolean = control match
    case TimeControl.Unlimited      => true
    case TimeControl.SuddenDeath(s) => s > 0
    case TimeControl.Fischer(s, i)  => s > 0 && i >= 0
    case TimeControl.PerMove(s)     => s > 0

  /** Strict only for laboratory records; established classic snapshots retain their existing legacy semantics. */
  def validateSnapshot(snapshot: GameSnapshot): Either[String, GameSnapshot] =
    for
      _ <- validateVariant(snapshot.effectiveVariant)
      _ <- snapshot.turns.toList.traverse(validateTurnRecord)
      _ <-
        if snapshot.effectiveVariant == Classic then
          valid(
            snapshot.lab.isEmpty && snapshot.turns.forall(_.labChoice.isEmpty),
            (),
            "classic snapshot contains laboratory state"
          )
        else
          snapshot.lab.toRight("laboratory snapshot is missing recovery state").flatMap { state =>
            for
              _ <- validateState(state)
              _ <- valid(
                snapshot.variant.contains(TwoPairs) && validVersion(snapshot.version) && snapshot.ply >= 0 &&
                  validTimeControl(snapshot.timeControl),
                (),
                "invalid laboratory identity, version or settings"
              )
              _ <- valid(
                !snapshot.rated.contains(true) && !snapshot.ratedRequested.contains(true) &&
                  !snapshot.ladder.contains(true) && snapshot.ratingDomain.forall(_ == RatingDomain.Casual) &&
                  !snapshot.effectiveOrigin.isShowcase,
                (),
                "laboratory games must remain casual and isolated"
              )
              _ <- valid(
                snapshot.players.nonEmpty && snapshot.players.keySet.subsetOf(Seats) &&
                  snapshot.players.values.forall { case _: Principal.Bot => false; case _ => true } &&
                  snapshot.seatTokens.keySet.subsetOf(Seats) && snapshot.seatTokens.values.forall(_.nonEmpty),
                (),
                "laboratory seats must be human and private"
              )
              _ <- valid(
                snapshot.serverSeed.matches("[0-9a-f]{64}") &&
                  (!snapshot.started || state.frozenClientSeeds.isDefined),
                (),
                "invalid laboratory seed recovery context"
              )
              _ <- valid(
                state.receipts.forall(_.appliedVersion <= snapshot.version) &&
                  snapshot.turns.forall(_.labChoice.isDefined),
                (),
                "incomplete laboratory receipt or turn history"
              )
              _ <- valid(
                snapshot.ended == (state.turn.phase == Phase.Ended) &&
                  (snapshot.ended || snapshot.started == (state.turn.phase != Phase.AwaitingPlayers)) &&
                  (!state.fairnessRevealed || snapshot.ended),
                (),
                "laboratory status and stage disagree"
              )
              position <- EngineOps.parse(snapshot.dfen).left.map(_ => "invalid laboratory position")
              _        <-
                if snapshot.ended then Right(())
                else
                  val pool =
                    state.turn.selectedPairIndex.fold(List.empty[Int])(i => state.turn.generatedOffers(i).values)
                  valid(
                    snapshot.pending == (state.turn.phase == Phase.PlayingSelectedPair) &&
                      EngineOps.serialize(EngineOps.withDice(position, pool)) == EngineOps.serialize(position),
                    (),
                    "laboratory selected pool and position disagree"
                  )
            yield ()
          }
    yield snapshot

  /** Immutable identity/settings and append-only history/receipts are fenced inside the store's transaction. */
  def validateUpdate(previous: GameSnapshot, next: GameSnapshot): Either[String, Unit] =
    for
      before <- previous.lab.toRight("missing prior laboratory state")
      after  <- next.lab.toRight("missing next laboratory state")
      _      <- valid(
        conditions(previous) == conditions(next) && before.provenance == after.provenance &&
          before.initialDfen == after.initialDfen && previous.serverSeed == next.serverSeed &&
          previous.createdAtEpochMs == next.createdAtEpochMs &&
          before.joinDeadlineAtEpochMs == after.joinDeadlineAtEpochMs &&
          before.frozenClientSeeds.forall(s => after.frozenClientSeeds.contains(s)),
        (),
        "laboratory creation data is immutable"
      )
      _ <- valid(
        next.turns.startsWith(previous.turns) && before.receipts.forall(after.receipts.contains),
        (),
        "laboratory history and receipts are append-only"
      )
      _ <- valid(
        next.ply >= previous.ply && (next.ply != previous.ply ||
          ((before.turn.generatedOffers.isEmpty || after.turn.generatedOffers == before.turn.generatedOffers) &&
            after.turn.revealedOfferCount >= before.turn.revealedOfferCount &&
            before.turn.selectedPairIndex.forall(i => after.turn.selectedPairIndex.contains(i)))),
        (),
        "laboratory offers and confirmed choice cannot be rewritten"
      )
      _ <- valid(
        !previous.started || (previous.players == next.players && previous.seatTokens == next.seatTokens),
        (),
        "active laboratory seat ownership cannot be rewritten"
      )
      _ <- valid(
        before.turn.selectedPairIndex.forall { index =>
          val appended = next.turns.drop(previous.turns.size)
          (next.ply == previous.ply || appended.nonEmpty) && appended.headOption.forall(
            _.labChoice.contains(HistoryChoice(before.turn.generatedOffers, index))
          )
        },
        (),
        "completed laboratory turn must preserve both generated offers and choice"
      )
      _ <- valid(!before.fairnessRevealed || after.fairnessRevealed, (), "terminal reveal cannot be retracted")
    yield ()

  given ValidatedCodec[PendingOffers]   = LaboratoryCodecs.checked(deriveCodec[PendingOffers])(validatePending)
  given ValidatedCodec[AcceptedCommand] = LaboratoryCodecs.checked(deriveCodec[AcceptedCommand])(validateReceipt)
  given ValidatedCodec[State]           = LaboratoryCodecs.checked(deriveCodec[State])(validateState)
  given ValidatedCodec[Conditions]      = LaboratoryCodecs.checked(deriveCodec[Conditions]) { value =>
    valid(
      value.variant == TwoPairs && value.protocolVersion == ProtocolVersion && validTimeControl(value.timeControl),
      value,
      "unsupported laboratory rematch conditions"
    )
  }

  /** The exact schema-1 public export; never derive an encoder for the private snapshot. */
  def transcript(snapshot: GameSnapshot): Either[String, Json] =
    for
      _     <- validateSnapshot(snapshot)
      state <- snapshot.lab.toRight("a laboratory transcript requires laboratory state")
      turns <- snapshot.turns.toList.traverse { t =>
        t.labChoice.toRight("laboratory history is missing offers").map { choice =>
          Json.obj(
            "turnNumber"        -> t.turnNumber.asJson,
            "activeColor"       -> t.activeColor.asJson,
            "offers"            -> choice.offers.asJson,
            "selectedPairIndex" -> choice.selectedPairIndex.asJson,
            "dice"              -> t.dice.asJson,
            "moves"             -> t.moves.asJson,
            "fenAfter"          -> t.fenAfter.asJson
          )
        }
      }
      fairness <-
        if state.fairnessRevealed then
          for
            seeds <- state.frozenClientSeeds.toRight("terminal fairness reveal is missing frozen inputs")
            dice  <- DiceSource.fromHexSeed(snapshot.serverSeed).left.map(_ => "invalid terminal fairness seed")
          yield Json.obj(
            "commit"      -> dice.commit.asJson,
            "serverSeed"  -> snapshot.serverSeed.asJson,
            "clientSeeds" -> seeds.asJson
          )
        else Right(Json.Null)
    yield
      val sporting = snapshot.status match
        case GameStatus.Ended(GameOver(_, termination)) => termination != Termination.Aborted
        case _                                          => false
      Json.obj(
        "schemaVersion" -> ExportSchemaVersion.asJson,
        "variant"       -> snapshot.effectiveVariant.asJson,
        "provenance"    -> state.provenance.asJson,
        "timeControl"   -> snapshot.timeControl.asJson,
        "initialDfen"   -> state.initialDfen.asJson,
        "status"        -> snapshot.status.asJson,
        "completeness"  -> (if sporting && state.fairnessRevealed then "complete" else "incomplete").asJson,
        "turns"         -> turns.asJson,
        "currentTurn"   -> state.turn.publicTurn.asJson,
        "fairness"      -> fairness
      )

/** Messages contain no decoder details, private JSON, tokens or seeds. */
final case class LaboratoryStorageFailure(code: String) extends RuntimeException(s"Laboratory storage: $code")
