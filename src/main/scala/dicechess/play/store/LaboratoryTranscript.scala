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
import io.circe.Json
import io.circe.generic.semiauto.deriveCodec

/** Schema-1 public history/export. These types contain no private seat or recovery records. */
object LaboratoryTranscript:
  final case class Turn(
      turnNumber: Long,
      activeColor: String,
      offers: List[DicePair],
      selectedPairIndex: Int,
      dice: List[Int],
      moves: List[String],
      fenAfter: String
  )
  final case class Fairness(commit: String, serverSeed: String, clientSeeds: ClientSeeds):
    override def toString: String = "LaboratoryFairness(<terminal reveal>)"
  final case class Record(
      schemaVersion: Int,
      variant: VariantIdentity,
      provenance: Provenance,
      timeControl: TimeControl,
      initialDfen: String,
      status: GameStatus,
      completeness: String,
      turns: List[Turn],
      currentTurn: PublicTurn,
      fairness: Option[Fairness]
  )

  given ValidatedCodec[Turn] = LaboratoryCodecs.checked(deriveCodec[Turn]) { turn =>
    LaboratoryStorage
      .validateTurnRecord(
        TurnRecord(
          turn.turnNumber,
          turn.activeColor,
          turn.dice,
          turn.moves,
          turn.fenAfter,
          labChoice = Some(HistoryChoice(turn.offers, turn.selectedPairIndex))
        )
      )
      .map(_ => turn)
  }
  given ValidatedCodec[Fairness] = LaboratoryCodecs.checked(deriveCodec[Fairness]) { value =>
    for
      _    <- Either.cond(value.serverSeed.matches("[0-9a-f]{64}"), (), "invalid terminal fairness evidence")
      dice <- DiceSource.fromHexSeed(value.serverSeed).leftMap(_ => "invalid terminal fairness evidence")
      _    <- Either.cond(
        dice.commit == value.commit && value.clientSeeds.white.nonEmpty && value.clientSeeds.black.nonEmpty,
        (),
        "invalid terminal fairness evidence"
      )
    yield value
  }
  given ValidatedCodec[Record] = LaboratoryCodecs.checked(deriveCodec[Record]) { value =>
    val ended = value.status match
      case GameStatus.Active => false
      case _                 => true
    val sporting = value.status match
      case GameStatus.Ended(GameOver(_, termination)) => termination != Termination.Aborted
      case _                                          => false
    for
      _ <- Either.cond(
        value.schemaVersion == ExportSchemaVersion && value.variant == TwoPairs,
        (),
        "unsupported laboratory export version"
      )
      _ <- encodeValidated(LaboratoryStorage.Conditions(value.variant, ProtocolVersion, value.timeControl))
        .leftMap(_.message)
      _ <- encodeValidated(value.provenance).leftMap(_.message)
      _ <- EngineOps.parse(value.initialDfen).leftMap(_ => "invalid laboratory export initial position")
      _ <- value.turns.traverse(t => encodeValidated(t).leftMap(_.message))
      _ <- encodeValidated(value.currentTurn).leftMap(_.message)
      _ <- value.fairness.traverse(f => encodeValidated(f).leftMap(_.message))
      _ <- Either.cond(
        ended == (value.currentTurn.phase == Phase.Ended) &&
          (value.fairness.isEmpty || ended) && Set("complete", "incomplete")(value.completeness) &&
          (value.completeness != "complete" || (sporting && value.fairness.isDefined)),
        (),
        "invalid laboratory export completeness or stage"
      )
    yield value
  }

  def decode(json: Json): Either[String, Record] = json.as[Record].leftMap(_ => "invalid laboratory transcript")
