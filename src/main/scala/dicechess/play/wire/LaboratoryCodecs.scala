package dicechess.play.wire

import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.wire.Codecs.given
import io.circe.{Codec, Decoder, DecodingFailure, Encoder, Json}
import io.circe.derivation.ConfiguredCodec
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax.*

/** Explicit opt-in laboratory codecs. Importing the classic Codecs alone does not accept laboratory commands. These
  * types are not routed into GameRoom yet; they pin the additive wire contract ahead of the room implementation.
  */
object LaboratoryCodecs:

  private def checked[A](codec: Codec[A])(validate: A => Either[String, A]): Codec[A] =
    Codec.from(
      codec.emap(validate),
      Encoder.instance(value => validate(value).fold(error => throw IllegalArgumentException(error), codec.apply))
    )

  private def enumCodec[A](label: String, values: Array[A]): Codec[A] =
    Codec.from(
      Decoder.decodeString.emap(s => values.find(_.toString == s).toRight(s"invalid $label")),
      Encoder.encodeString.contramap(_.toString)
    )

  given Codec[VariantIdentity] = checked(deriveCodec[VariantIdentity])(validateVariant)

  /** Apply ONLY at a known pre-variant classic storage boundary, never to a new lab creation request. An explicitly
    * null/unknown identity is corrupt rather than an instruction to fall back to classic.
    */
  def variantOfLegacyClassicRecord(record: Json): Decoder.Result[VariantIdentity] =
    record.asObject match
      case None                                  => Left(DecodingFailure("legacy record must be an object", Nil))
      case Some(obj) if !obj.contains("variant") => Right(Classic)
      case Some(_)                               => record.hcursor.get[VariantIdentity]("variant")

  given Codec[DicePair] = Codec.from(
    Decoder
      .decodeList[Int]
      .emap:
        case List(first, second) => validatePair(DicePair(first, second))
        case _                   => Left("a dice offer must contain exactly two dice"),
    Encoder.instance(pair => pair.values.asJson)
  )

  given Codec[Phase]      = enumCodec("laboratory phase", Phase.values)
  given Codec[Provenance] = checked(deriveCodec[Provenance]): value =>
    Either.cond(
      value.rulesVersion.trim.nonEmpty && value.diceAlgorithm == DiceAlgorithm,
      value,
      "unsupported dice algorithm or missing captured rules version"
    )
  given Codec[PublicTurn]  = checked(deriveCodec[PublicTurn])(validateTurn)
  given Codec[PublicState] = checked(deriveCodec[PublicState]): value =>
    Either.cond(
      value.variant == TwoPairs && value.protocolVersion == ProtocolVersion,
      value,
      "laboratory state requires choice-2x2d6 revision 1 and protocol version 1"
    )
  given Codec[HistoryChoice] = checked(deriveCodec[HistoryChoice]): value =>
    validateTurn(PublicTurn(Phase.PlayingSelectedPair, value.offers, Some(value.selectedPairIndex))).map(_ => value)

  private val uci     = "[a-h][1-8][a-h][1-8][qrbn]?".r
  given Codec[Action] = checked(ConfiguredCodec.derived[Action]): action =>
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

  given Codec[Command] = checked(deriveCodec[Command]): command =>
    Either.cond(
      validCommandId(command.commandId) && validVersion(command.expectedVersion),
      command,
      "invalid commandId or expectedVersion"
    )
  given Codec[CommandFrame] = deriveCodec
  given Codec[ErrorCode]    = enumCodec("laboratory rejection code", ErrorCode.values)
  given Codec[Rejection]    = checked(deriveCodec[Rejection]): value =>
    Either.cond(
      validVersion(value.v) && value.commandId.forall(validCommandId),
      value,
      "invalid rejection version or commandId"
    )
  given Codec[RejectionFrame] = deriveCodec
  given Codec[TurnChanged]    = checked(deriveCodec[TurnChanged]): value =>
    Either.cond(validVersion(value.v) && value.seat.side.isDefined, value, "invalid laboratory event version or seat")
  given Codec[TurnChangedFrame] = deriveCodec
  given Codec[Receipt]          = checked(deriveCodec[Receipt]): value =>
    Either.cond(
      validCommandId(value.commandId) && validVersion(value.appliedVersion),
      value,
      "invalid receipt version or commandId"
    )
  given Codec[ReceiptFrame] = deriveCodec
