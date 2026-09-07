package dicechess.play.store

import cats.effect.IO
import dicechess.play.core.*
import java.time.Instant
import java.util.UUID

/** Command receipts and state transitions share the source-row transaction. */
enum RematchAction:
  case Propose, Accept, Decline, Cancel
  def wire: String = toString.toLowerCase

final case class RematchRead(session: Option[RematchSession], serverNow: Instant)
final case class RematchCommandResult(state: RematchRead, error: Option[String])

trait RematchCommands:
  def pendingCreation(after: Option[GameId], limit: Int): IO[List[Either[CorruptRematchRecord, RematchSession]]]
  def current(sourceId: GameId): IO[RematchRead]
  def command(sourceId: GameId, seat: Seat, requestId: UUID, action: RematchAction): IO[RematchCommandResult]

/** Pure role/state rules; the store expires deadlines first, using its clock after acquiring the lock. */
private[store] object RematchCommands:
  def transition(s: RematchSession, seat: Seat, action: RematchAction, now: Instant): Either[String, RematchSession] =
    import RematchAction.*
    import RematchPhase.*
    val initiator = s.offeredBy.contains(seat)
    if !s.source.players.contains(seat) then Left("not_participant")
    else if (s.phase == Available || s.phase == Offered) &&
      (action == Propose || action == Accept) && !s.source.admissible
    then Left("settings_unavailable")
    else
      (s.phase, action) match
        case (Available, Propose) =>
          Right(s.copy(phase = Offered, consents = Set(seat), offeredBy = Some(seat), deadlineAt = now.plusSeconds(15)))
        case (Offered, Propose) if initiator           => Right(s)
        case (Offered, Propose | Accept) if !initiator =>
          Right(s.copy(phase = Starting, consents = Set(Seat.White, Seat.Black)))
        case (Offered, Cancel) if initiator =>
          Right(s.copy(phase = Closed, closedReason = Some(RematchCloseReason.Cancelled)))
        case (Offered, Decline) if !initiator =>
          Right(s.copy(phase = Closed, closedReason = Some(RematchCloseReason.Declined)))
        case (Starting | Matched, Propose | Accept) if s.consents(seat)                              => Right(s)
        case (Closed, Cancel) if initiator && s.closedReason.contains(RematchCloseReason.Cancelled)  => Right(s)
        case (Closed, Decline) if !initiator && s.closedReason.contains(RematchCloseReason.Declined) => Right(s)
        case (Closed, _) => Left("rematch_closed")
        case _           => Left("invalid_transition")
