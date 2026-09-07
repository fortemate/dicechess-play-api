package dicechess.play.store

import cats.effect.IO
import dicechess.play.core.*
import io.circe.{Codec, KeyDecoder, KeyEncoder}
import io.circe.generic.semiauto.deriveCodec

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Private storage data, never a public response. Classic is the only supported mode in this release. */
final case class RematchConditions(timeControl: TimeControl, rated: Boolean)

final case class RematchSource(
    conditions: RematchConditions,
    players: Map[Seat, Principal],
    guestTokenHashes: Map[Seat, String]
):
  /** Shared by command admission and successor creation; inherited settings are never silently normalized. */
  def admissible: Boolean =
    val control = conditions.timeControl match
      case TimeControl.Unlimited      => true
      case TimeControl.SuddenDeath(s) => s > 0
      case TimeControl.Fischer(s, i)  => s > 0 && i >= 0
      case TimeControl.PerMove(s)     => s > 0
    val humans = players.values.toList.flatMap(RematchSource.identity)
    control && players.keySet == Set(Seat.White, Seat.Black) && humans.size == 2 && humans.distinct.size == 2 &&
    (!conditions.rated || (humans.forall(_._1 == "user") && RatingCategory.of(conditions.timeControl).isDefined))

  /** Capability fallback applies ONLY to final guest seats, never to an account-owned seat. */
  def guestSeat(token: String): Option[Seat] =
    val presented = RematchSource.hash(token).getBytes(StandardCharsets.UTF_8)
    guestTokenHashes.collectFirst:
      case (seat, expected)
          if players.get(seat).exists(_.isInstanceOf[Principal.Guest]) &&
            MessageDigest.isEqual(presented, expected.getBytes(StandardCharsets.UTF_8)) =>
        seat

object RematchSource:
  import dicechess.play.wire.Codecs.given
  private given KeyEncoder[Seat] = KeyEncoder.encodeKeyString.contramap(_.toString)
  private given KeyDecoder[Seat] = KeyDecoder.instance(s => Seat.values.find(_.toString == s))
  given Codec[RematchConditions] = deriveCodec
  given Codec[RematchSource]     = deriveCodec

  private[store] def hash(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)).map(b => f"$b%02x").mkString

  private def identity(principal: Principal): Option[(String, UUID)] = principal match
    case Principal.User(id)  => Try(UUID.fromString(id)).toOption.map("user" -> _)
    case Principal.Guest(id) => Try(UUID.fromString(id)).toOption.map("guest" -> _)
    case _: Principal.Bot    => None

  /** The first terminal save freezes the actual final seats. Duplicate/unclaimed friend seats are ineligible. */
  private[store] def capture(snapshot: GameSnapshot): Option[RematchSource] =
    val sporting = snapshot.status match
      case GameStatus.Ended(GameOver(_, termination)) => termination != Termination.Aborted
      case _                                          => false
    val seats  = Set(Seat.White, Seat.Black)
    val humans = snapshot.players.values.toList.flatMap(identity)
    val tokens = snapshot.seatTokens
    Option.when(
      sporting && !snapshot.effectiveOrigin.isShowcase && snapshot.effectiveOrigin != GameOrigin.Ladder &&
        !snapshot.ladder.contains(true) && snapshot.players.keySet == seats && humans.size == 2 &&
        humans.distinct.size == 2 && tokens.keySet == seats && tokens.values.forall(_.nonEmpty) &&
        tokens.values.toSet.size == 2 && (!snapshot.rated.contains(true) || humans.forall(_._1 == "user"))
    ):
      RematchSource(
        RematchConditions(snapshot.timeControl, snapshot.rated.getOrElse(false)),
        snapshot.players,
        snapshot.players.collect { case (seat, _: Principal.Guest) => seat -> hash(tokens(seat)) }
      )

enum RematchPhase:
  case Available, Offered, Starting, Matched, Closed

enum RematchCloseReason(val stored: String):
  case Declined         extends RematchCloseReason("declined")
  case Cancelled        extends RematchCloseReason("cancelled")
  case Expired          extends RematchCloseReason("expired")
  case TechnicalFailure extends RematchCloseReason("technical_failure")
  case Restart          extends RematchCloseReason("restart")

final case class RematchSession(
    sourceId: GameId,
    rootId: GameId,
    source: RematchSource,
    endedAt: Instant,
    phase: RematchPhase,
    version: Long,
    consents: Set[Seat],
    offeredBy: Option[Seat],
    deadlineAt: Instant,
    closedReason: Option[RematchCloseReason],
    successorId: Option[GameId]
)

/** Version-fenced persistence transitions. The coordinator (#129) must authorize the caller's source seat first. */
enum RematchChange:
  case Offer(by: Seat)
  case Accept(by: Seat)
  case Close(reason: RematchCloseReason)

enum RematchWrite:
  case Applied(session: RematchSession)
  case Conflict(current: RematchSession)
  case Missing

enum RematchStartupPhase(val stored: String):
  case AwaitingJoins extends RematchStartupPhase("awaiting_joins")
  case Active        extends RematchStartupPhase("active")
  case Aborted       extends RematchStartupPhase("aborted")

final case class RematchStartup(
    phase: RematchStartupPhase,
    joined: Set[Seat],
    activatedAt: Option[Instant]
)

/** Secrets are retained under the same private storage boundary as GameSnapshot; never log this envelope. */
final case class RematchSuccessor(
    gameId: GameId,
    sourceId: GameId,
    initialSnapshot: GameSnapshot,
    committedAt: Instant,
    joinDeadlineAt: Instant,
    startupVersion: Long,
    startup: RematchStartup
):
  override def toString: String = s"RematchSuccessor($gameId,$sourceId,${startup.phase},<private snapshot>)"

  /** The caller must have authenticated this final principal before requesting access. */
  def seatAccess(principal: Principal): Option[(Seat, String)] =
    initialSnapshot.players
      .collectFirst { case (seat, owner) if owner == principal => seat }
      .flatMap(seat => initialSnapshot.seatTokens.get(seat).map(seat -> _))

enum RematchCommit:
  case Committed(game: RematchSuccessor)
  case Existing(game: RematchSuccessor)
  case Rejected

/** Identifies a corrupt private record without exposing JSON values or decoder details. */
final case class CorruptRematchRecord(table: String, rowId: GameId, field: String)
    extends RuntimeException(s"Invalid rematch record: $table/${rowId.value}/$field")

/** A definitive rejected write, distinct from a possibly committed database failure. */
final case class RematchTransitionRejected(gameId: GameId, deadlineExpired: Boolean)
    extends RuntimeException(s"Rematch transition rejected: ${gameId.value}; deadlineExpired=$deadlineExpired")

/** Postgres-only foundation; no HTTP routes, room activation or public DTOs are supplied by this seam. */
trait RematchStore:
  def session(sourceId: GameId): IO[Option[RematchSession]]
  def advance(sourceId: GameId, expectedVersion: Long, change: RematchChange): IO[RematchWrite]

  /** Strict INSERT of a fresh initial snapshot + colour/credential record + accepted link, in one transaction. A
    * committed source returns Existing even when a retrier supplies a different candidate or stale version.
    */
  def commitSuccessor(
      sourceId: GameId,
      expectedVersion: Long,
      gameId: GameId,
      initialSnapshot: GameSnapshot
  ): IO[RematchCommit]
  def successor(gameId: GameId): IO[Option[RematchSuccessor]]

  /** Keyset-paged startup reconciliation, including aborted startup whose terminal snapshot still needs saving. Generic
    * loadActive deliberately excludes these games.
    */
  def pendingStartup(after: Option[GameId], limit: Int): IO[List[RematchSuccessor]]

  /** Recovery keeps malformed rows addressable so one private record cannot block every other game on boot. */
  def pendingStartupRecords(
      after: Option[GameId],
      limit: Int
  ): IO[List[Either[CorruptRematchRecord, RematchSuccessor]]] =
    pendingStartup(after, limit).map(_.map(Right(_)))

  /** Store observed join history/activation. Live connection checks and terminal game writes belong to #128. */
  def updateStartup(gameId: GameId, expectedVersion: Long, next: RematchStartup): IO[Boolean]

  /** Boot-only, before admitting new requests. Close unfinished offers, never committed successor mappings. */
  def closeUncommittedOnRestart: IO[Int]
