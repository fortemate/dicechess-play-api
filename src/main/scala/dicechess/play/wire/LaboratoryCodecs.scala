package dicechess.play.wire

import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.wire.Codecs.given
import io.circe.{Codec, Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.derivation.ConfiguredCodec
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax.*

/** Explicit opt-in laboratory codecs. Importing the classic Codecs alone does not decode laboratory commands; the
  * laboratory WebSocket adapter handles their separate envelope and the room enforces the mode boundary.
  */
object LaboratoryCodecs:

  final case class EncodingFailure(message: String)

  /** The typed boundary is used for recoverable validation failures. Circe's Encoder cannot return an error value, so
    * its adapter retains a last-resort invariant guard for callers that bypass this boundary with `.asJson`.
    */
  final class ValidatedCodec[A] private[LaboratoryCodecs] (
      codec: Codec[A],
      private[play] val validateValue: A => Either[String, A]
  ) extends Codec[A]:
    private val decoder = codec.emap(validateValue)

    override def apply(cursor: HCursor): Decoder.Result[A] = decoder(cursor)

    def encodeValidated(value: A): Either[EncodingFailure, Json] =
      validateValue(value).left.map(EncodingFailure.apply).map(codec.apply)

    override def apply(value: A): Json =
      encodeValidated(value).fold(error => throw IllegalArgumentException(error.message), identity)

  def encodeValidated[A](value: A)(using codec: ValidatedCodec[A]): Either[EncodingFailure, Json] =
    codec.encodeValidated(value)

  private[play] def checked[A](codec: Codec[A])(validate: A => Either[String, A]): ValidatedCodec[A] =
    new ValidatedCodec(codec, validate)

  // Composite validation precedes encoding, so a bad nested value returns Left without invoking a throwing adapter.
  private def nested[A](value: A)(using codec: ValidatedCodec[A]): Either[String, A] = codec.validateValue(value)

  private def enumCodec[A](label: String, values: Array[A]): Codec[A] =
    Codec.from(
      Decoder.decodeString.emap(s => values.find(_.toString == s).toRight(s"invalid $label")),
      Encoder.encodeString.contramap(_.toString)
    )

  given ValidatedCodec[VariantIdentity] = checked(deriveCodec[VariantIdentity])(validateVariant)

  /** Apply ONLY at a known pre-variant classic storage boundary, never to a new lab creation request. An explicitly
    * null/unknown identity is corrupt rather than an instruction to fall back to classic.
    */
  def variantOfLegacyClassicRecord(record: Json): Decoder.Result[VariantIdentity] =
    record.asObject match
      case None                                  => Left(DecodingFailure("legacy record must be an object", Nil))
      case Some(obj) if !obj.contains("variant") => Right(Classic)
      case Some(_)                               => record.hcursor.get[VariantIdentity]("variant")

  given ValidatedCodec[DicePair] = checked(
    Codec.from(
      Decoder
        .decodeList[Int]
        .emap:
          case List(first, second) => Right(DicePair(first, second))
          case _                   => Left("a dice offer must contain exactly two dice"),
      Encoder.instance(pair => pair.values.asJson)
    )
  )(validatePair)

  given Codec[Phase]               = enumCodec("laboratory phase", Phase.values)
  given ValidatedCodec[Provenance] = checked(deriveCodec[Provenance]): value =>
    Either.cond(
      value.rulesVersion.trim.nonEmpty && value.diceAlgorithm == DiceAlgorithm,
      value,
      "unsupported dice algorithm or missing captured rules version"
    )
  given ValidatedCodec[PublicTurn]  = checked(deriveCodec[PublicTurn])(validateTurn)
  given ValidatedCodec[PublicState] = checked(deriveCodec[PublicState]): value =>
    for
      _ <- Either.cond(
        value.variant == TwoPairs && value.protocolVersion == ProtocolVersion,
        (),
        "laboratory state requires choice-2x2d6 revision 1 and protocol version 1"
      )
      _ <- nested(value.provenance)
      _ <- nested(value.turn)
    yield value
  given ValidatedCodec[HistoryChoice] = checked(deriveCodec[HistoryChoice]): value =>
    validateTurn(PublicTurn(Phase.PlayingSelectedPair, value.offers, Some(value.selectedPairIndex))).map(_ => value)

  private val uci              = "[a-h][1-8][a-h][1-8][qrbn]?".r
  given ValidatedCodec[Action] = checked(ConfiguredCodec.derived[Action]): action =>
    action match
      case Action.ChoosePair(index) =>
        Either.cond(index == 0 || index == 1, action, "pairIndex must be 0 or 1")
      case Action.SubmitTurn(moves, _) =>
        Either.cond(
          moves.nonEmpty && moves.size <= 2 && moves.forall(m => uci.matches(m)),
          action,
          "submit one or two UCI micro-moves; a selected empty tree passes on the server"
        )
      case _ => Right(action)

  given ValidatedCodec[Command] = checked(deriveCodec[Command]): command =>
    for
      _ <- Either.cond(
        validCommandId(command.commandId) && validVersion(command.expectedVersion),
        (),
        "invalid commandId or expectedVersion"
      )
      _ <- nested(command.action)
    yield command
  given ValidatedCodec[CommandFrame] = checked(deriveCodec[CommandFrame]): frame =>
    nested(frame.LabCommand).map(_ => frame)
  given Codec[ErrorCode]          = enumCodec("laboratory rejection code", ErrorCode.values)
  given ValidatedCodec[Rejection] = checked(deriveCodec[Rejection]): value =>
    Either.cond(
      validVersion(value.v) && value.commandId.forall(validCommandId),
      value,
      "invalid rejection version or commandId"
    )
  given ValidatedCodec[RejectionFrame] = checked(deriveCodec[RejectionFrame]): frame =>
    nested(frame.LabRejected).map(_ => frame)
  given ValidatedCodec[TurnChanged] = checked(deriveCodec[TurnChanged]): value =>
    for
      _ <- Either.cond(
        validVersion(value.v) && value.seat.side.isDefined,
        (),
        "invalid laboratory event version or seat"
      )
      _ <- nested(value.lab)
    yield value
  given ValidatedCodec[TurnChangedFrame] = checked(deriveCodec[TurnChangedFrame]): frame =>
    nested(frame.LabTurnChanged).map(_ => frame)
  given ValidatedCodec[Receipt] = checked(deriveCodec[Receipt]): value =>
    Either.cond(
      validCommandId(value.commandId) && validVersion(value.appliedVersion),
      value,
      "invalid receipt version or commandId"
    )
  given ValidatedCodec[ReceiptFrame] = checked(deriveCodec[ReceiptFrame]): frame =>
    nested(frame.LabCommandApplied).map(_ => frame)
