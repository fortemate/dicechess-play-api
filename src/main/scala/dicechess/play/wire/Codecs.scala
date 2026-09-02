package dicechess.play.wire

import dicechess.play.core.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Decoder, Encoder, Json, KeyDecoder}

/** JSON wire codecs for the transport-neutral protocol. The WebSocket edge (and later the Bot API) are codecs over
  * these types — the game core never imports JSON.
  *
  * Simple enums serialize as their case name; ADTs use Circe's discriminated-object form (e.g.
  * `{"SubmitTurn":{"moves":[...]}}`).
  */
object Codecs:

  /** Makes a case class's DEFAULT VALUES actually apply when a field is missing from the JSON, for request types that
    * derive `ConfiguredCodec` instead of `Codec.AsObject`.
    *
    * This is not a style preference. Circe's Scala 3 derivation ignores default values: with plain `Codec.AsObject`, a
    * `rated: Boolean = false` field is *required* on the wire and a body omitting it fails to decode with "Missing
    * required field". Only `Option` fields tolerate absence. #279 added exactly such a field to three request bodies
    * and shipped it, which made `POST /bot/seeks`, `POST /lobby/seeks` and `POST /lobby/play-bot` answer 400 to any
    * client that had not been updated — while the published OpenAPI described the field as optional.
    *
    * `withDefaults` only affects DECODING; the encoded shape is unchanged, so it is safe to switch an existing type.
    */
  given Configuration = Configuration.default.withDefaults

  // Total, exception-free enum codec: decode by name lookup, encode as the case name.
  private def nameCodec[A](label: String, values: Array[A]): Codec[A] =
    val byName = values.iterator.map(v => v.toString -> v).toMap
    Codec.from(
      Decoder.decodeString.emap(s => byName.get(s).toRight(s"invalid $label: $s")),
      Encoder.encodeString.contramap(_.toString)
    )

  private def wireNameCodec[A](label: String, values: List[A], wireName: A => String): Codec[A] =
    val byName = values.iterator.map(value => wireName(value) -> value).toMap
    Codec.from(
      Decoder.decodeString.emap(name => byName.get(name).toRight(s"invalid $label: $name")),
      Encoder.encodeString.contramap(wireName)
    )

  given Codec[Side]              = nameCodec("Side", Side.values)
  given Codec[Seat]              = nameCodec("Seat", Seat.values)
  given Codec[Termination]       = nameCodec("Termination", Termination.values)
  given Codec[PlayerKind]        = nameCodec("PlayerKind", PlayerKind.values)
  given Codec[WebhookCapability] =
    wireNameCodec("WebhookCapability", WebhookCapability.registry, _.wireName)
  given Codec[WebhookCapabilityStatus] =
    wireNameCodec("WebhookCapabilityStatus", WebhookCapabilityStatus.values.toList, _.wireName)
  given Codec[GameOrigin] =
    wireNameCodec("GameOrigin", GameOrigin.valuesList, _.wireName)

  // MoveTree is recursive, so it can't be derived: a node encodes as the plain object of its children (sorted for a
  // stable wire), and any JSON object decodes back into nodes.
  private def encodeMoveTree(node: MoveTree): Json =
    Json.obj(node.children.toList.sortBy(_._1).map((move, child) => move -> encodeMoveTree(child))*)

  given Codec[MoveTree] = Codec.from(
    Decoder.recursive(rec => Decoder.decodeMap(using KeyDecoder.decodeKeyString, rec).map(MoveTree.apply)),
    Encoder.instance(encodeMoveTree)
  )

  given Codec[GameResult]      = deriveCodec
  given Codec[GameOver]        = deriveCodec
  given Codec[GameStatus]      = deriveCodec
  given Codec[TimeControl]     = deriveCodec
  given Codec[Seek]            = deriveCodec
  given Codec[Clocks]          = deriveCodec
  given Codec[ClientSeeds]     = deriveCodec
  given Codec[Principal]       = deriveCodec
  given Codec[DrawOffer]       = deriveCodec
  given Codec[PublicPlayer]    = deriveCodec
  given Codec[Players]         = deriveCodec
  given Codec[PublicGameState] = deriveCodec
  given Codec[GameMoves]       = deriveCodec
  given Codec[SnapshotTurn]    = deriveCodec
  // ConfiguredCodec, not deriveCodec: `SubmitTurn.offerDraw` (#327) is a defaulted non-Option field.
  given Codec[GameCommand] = ConfiguredCodec.derived
  given Codec[GameEvent]   = deriveCodec
  // ConfiguredCodec, not deriveCodec: `Challenge.rated` (#282) is a defaulted non-Option field, and the rule is about
  // the field rather than the direction of travel — see the `Configuration` given above.
  given Codec[Challenge] = ConfiguredCodec.derived
  given Codec[BotEvent]  = deriveCodec
