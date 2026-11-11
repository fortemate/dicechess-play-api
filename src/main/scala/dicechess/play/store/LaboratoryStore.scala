package dicechess.play.store

import cats.effect.IO
import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.{Provenance, VariantIdentity}
import io.circe.Json
import java.time.Instant

enum LaboratoryWrite:
  case Applied, Unchanged, Conflict

final case class LaboratoryResult(
    variant: VariantIdentity,
    provenance: Provenance,
    timeControl: TimeControl,
    result: Option[Int],
    termination: Termination,
    finishedAt: Instant
)

final case class LaboratoryTranscriptRecord(variant: VariantIdentity, payload: Json, expiresAt: Instant)

enum LaboratoryRematchWrite:
  case Created(gameId: GameId)
  case Existing(gameId: GameId)
  case Rejected

/** Storage only: authorization, live presence, move legality and broadcasts belong to the room. */
trait LaboratoryStore extends GameStore:
  def load(id: GameId): IO[Option[GameSnapshot]]

  /** None is a strict create; Some fences an update. Snapshot, receipt list and public projections commit together. */
  def commit(id: GameId, expectedVersion: Option[Long], snapshot: GameSnapshot): IO[LaboratoryWrite]
  def transcriptFor(id: GameId): IO[Option[LaboratoryTranscriptRecord]]
  def resultFor(id: GameId): IO[Option[LaboratoryResult]]
  def receiptFor(id: GameId, seat: Seat, commandId: String): IO[Option[LaboratoryStorage.AcceptedCommand]]

  /** The room must obtain both authenticated consents first. This seam freezes inheritance and one successor. */
  def createRematch(source: GameId, candidate: GameId, initial: GameSnapshot): IO[LaboratoryRematchWrite]
  def successorOf(source: GameId): IO[Option[GameId]]

  /** All data, including incomplete transcripts and receipts, has one fixed creation-time expiry. */
  def pruneExpired(limit: Int): IO[Int]
