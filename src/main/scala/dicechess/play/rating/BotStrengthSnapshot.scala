package dicechess.play.rating

import dicechess.play.core.RatingCategory
import dicechess.play.rating.GraphConnectivity.AdmissionStatus
import io.circe.{Codec, Decoder, Encoder, HCursor, Json}
import io.circe.generic.semiauto.*
import io.circe.syntax.*

import java.time.Instant

/** Retrospective anchored strength snapshot stored in PostgreSQL (#150, ADR 008, #170). */
final case class BotStrengthSnapshot(
    id: Long,
    category: RatingCategory,
    epoch: Int,
    anchorSetVersion: String,
    cutoff: Instant,
    computedAt: Instant,
    isCurrent: Boolean,
    result: AnchoredStrength.Result
)

final case class BotStrengthSnapshotSummary(
    id: Long,
    category: RatingCategory,
    epoch: Int,
    anchorSetVersion: String,
    cutoff: Instant,
    computedAt: Instant,
    isCurrent: Boolean,
    admittedCount: Int,
    provisionalCount: Int
)

object BotStrengthSnapshot:

  given Codec[RatingCategory] = Codec.from(
    Decoder.decodeString.emap(s => RatingCategory.fromWireName(s).toRight(s"Invalid category: $s")),
    Encoder.encodeString.contramap(_.wireName)
  )

  given Codec[AdmissionStatus] = new Codec[AdmissionStatus]:
    override def apply(c: HCursor): Decoder.Result[AdmissionStatus] =
      c.downField("type")
        .as[String]
        .flatMap:
          case "admitted"     => Right(AdmissionStatus.Admitted)
          case "disconnected" => Right(AdmissionStatus.Disconnected)
          case "provisional"  =>
            for
              games     <- c.downField("games").as[Int]
              opponents <- c.downField("opponents").as[Int]
            yield AdmissionStatus.Provisional(games, opponents)
          case other => Left(io.circe.DecodingFailure(s"Unknown AdmissionStatus type: $other", c.history))

    override def apply(status: AdmissionStatus): Json = status match
      case AdmissionStatus.Admitted =>
        Json.obj("type" -> "admitted".asJson)
      case AdmissionStatus.Disconnected =>
        Json.obj("type" -> "disconnected".asJson)
      case AdmissionStatus.Provisional(games, opponents) =>
        Json.obj(
          "type"      -> "provisional".asJson,
          "games"     -> games.asJson,
          "opponents" -> opponents.asJson
        )

  given Codec[AnchoredStrength.RankedBot] = deriveCodec[AnchoredStrength.RankedBot]

  /** Serializes/deserializes AnchoredStrength.Result for snapshot persistence. */
  given Codec[AnchoredStrength.Result] = new Codec[AnchoredStrength.Result]:
    override def apply(c: HCursor): Decoder.Result[AnchoredStrength.Result] =
      for
        epoch            <- c.downField("epoch").as[Int]
        anchorSetVersion <- c.downField("anchorSetVersion").as[String]
        admitted         <- c.downField("admitted").as[List[AnchoredStrength.RankedBot]]
        provisional      <- c.downField("provisional").as[List[AnchoredStrength.RankedBot]]
        disconnected     <- c.downField("disconnected").as[List[AnchoredStrength.RankedBot]]
        appliedOffset    <- c.downField("appliedOffset").as[Option[Double]]
      yield AnchoredStrength.Result(
        epoch = epoch,
        anchorSetVersion = anchorSetVersion,
        admitted = admitted,
        provisional = provisional,
        disconnected = disconnected,
        connectivity = GraphConnectivity.Result(
          statuses = (admitted ++ provisional ++ disconnected).map(b => b.player -> b.status).toMap,
          gameCounts = (admitted ++ provisional ++ disconnected).map(b => b.player -> b.games).toMap,
          opponentCounts = (admitted ++ provisional ++ disconnected).map(b => b.player -> b.opponents).toMap,
          anchorConnectedPlayers = (admitted ++ provisional).map(_.player).toSet,
          disconnectedPlayers = disconnected.map(_.player).toSet,
          components = Nil,
          hasAnchors = (admitted ++ provisional).exists(_.isAnchor)
        ),
        appliedOffset = appliedOffset
      )

    override def apply(r: AnchoredStrength.Result): Json =
      Json.obj(
        "epoch"            -> r.epoch.asJson,
        "anchorSetVersion" -> r.anchorSetVersion.asJson,
        "admitted"         -> r.admitted.asJson,
        "provisional"      -> r.provisional.asJson,
        "disconnected"     -> r.disconnected.asJson,
        "appliedOffset"    -> r.appliedOffset.asJson
      )

  given Codec[BotStrengthSnapshotSummary] = deriveCodec[BotStrengthSnapshotSummary]
  given Codec[BotStrengthSnapshot]        = deriveCodec[BotStrengthSnapshot]

  extension (bot: AnchoredStrength.RankedBot)
    /** Public 1500-based rating (Variant A: 1500 + relative Elo). */
    def publicRating: Double   = 1500.0 + bot.elo
    def publicCiLow: Double    = 1500.0 + bot.ciLow
    def publicCiHigh: Double   = 1500.0 + bot.ciHigh
    def publicRd: Double       = (bot.ciHigh - bot.ciLow) / (2.0 * Glicko2.ConfidenceLevel95Z)
    def isProvisional: Boolean = bot.status match
      case AdmissionStatus.Admitted => false
      case _                        => true

    def teamAndName: (Option[String], String) =
      val idx = bot.player.indexOf('/')
      if idx >= 0 then (Some(bot.player.substring(0, idx)), bot.player.substring(idx + 1))
      else (None, bot.player)
