package dicechess.play.server

import dicechess.play.core.*
import dicechess.play.store.*
import dicechess.play.wire.Codecs.given
import io.circe.{Codec, Encoder}
import io.circe.generic.semiauto.deriveEncoder
import java.time.Instant

final case class RematchSettings(timeControl: TimeControl, rated: Boolean, mode: String = "classic")
    derives Codec.AsObject
final case class RematchJoin(seat: Seat, token: String) derives Codec.AsObject:
  override def toString: String = s"RematchJoin($seat,<redacted>)"
final case class PrivateRematch(
    sourceGameId: String,
    phase: String,
    serverNow: Instant,
    myConsent: Boolean,
    allowedActions: List[String],
    settings: RematchSettings,
    deadlineAt: Option[Instant] = None,
    nextGameId: Option[String] = None,
    joinDeadlineAt: Option[Instant] = None,
    join: Option[RematchJoin] = None,
    closedReason: Option[String] = None
)
object PrivateRematch:
  given Encoder.AsObject[PrivateRematch] = deriveEncoder[PrivateRematch].mapJsonObject(_.filter(!_._2.isNull))
  def of(s: RematchSession, seat: Seat, now: Instant, successor: Option[RematchSuccessor]): PrivateRematch =
    val pending = s.phase == RematchPhase.Available || s.phase == RematchPhase.Offered
    val phase   =
      if s.phase == RematchPhase.Matched && successor.isEmpty then "starting" else s.phase.toString.toLowerCase
    val actions = s.phase match
      case RematchPhase.Available                             => List("propose")
      case RematchPhase.Offered if s.offeredBy.contains(seat) => List("cancel")
      case RematchPhase.Offered                               => List("accept", "decline")
      case _                                                  => Nil
    PrivateRematch(
      s.sourceId.value,
      phase,
      now,
      s.consents(seat),
      actions,
      RematchSettings(s.source.conditions.timeControl, s.source.conditions.rated),
      Option.when(pending)(s.deadlineAt),
      successor.map(_.gameId.value),
      successor.map(_.joinDeadlineAt),
      successor.flatMap(_.seatAccess(s.source.players(seat))).map((seat, token) => RematchJoin(seat, token)),
      s.closedReason.map(_.stored)
    )

final case class PublicContinuation(
    sourceGameId: String,
    phase: String,
    serverNow: Instant,
    deadlineAt: Option[Instant] = None,
    nextGameId: Option[String] = None
)
object PublicContinuation:
  given Encoder.AsObject[PublicContinuation] = deriveEncoder[PublicContinuation].mapJsonObject(_.filter(!_._2.isNull))
  def of(s: RematchSession, now: Instant, successor: Option[RematchSuccessor]): PublicContinuation =
    val phase =
      if successor.isDefined then "matched" else if s.phase == RematchPhase.Closed then "closed" else "waiting"
    PublicContinuation(
      s.sourceId.value,
      phase,
      now,
      Option.when(s.phase == RematchPhase.Available || s.phase == RematchPhase.Offered)(s.deadlineAt),
      successor.map(_.gameId.value)
    )
