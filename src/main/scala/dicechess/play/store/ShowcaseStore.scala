package dicechess.play.store

import cats.effect.IO
import dicechess.play.core.{GameId, Side}

import java.time.Instant
import java.util.UUID

/** How a processed claim was answered (ADR-005 §5, #46) — the two committed outcomes a same-key retry replays. */
enum ShowcaseClaimOutcome(val wireName: String):
  case Claimed    extends ShowcaseClaimOutcome("claimed")
  case Spectating extends ShowcaseClaimOutcome("spectating")

object ShowcaseClaimOutcome:
  def fromWireName(name: String): Option[ShowcaseClaimOutcome] = values.find(_.wireName == name)

/** The singleton table's durable row (`showcase_table`, V6): the colour the next human will play and the game the table
  * currently considers its own, if any.
  */
final case class ShowcaseTableRecord(nextHumanColor: Side, currentGameId: Option[GameId])

/** One processed claim (`showcase_claims`, V6). `humanColor` and `gameId` are present for a winning claim; a spectating
  * claim carries the game it was told to watch, when there was one.
  */
final case class ShowcaseClaimRecord(
    actorId: String,
    idempotencyKey: UUID,
    requestHash: String,
    outcome: ShowcaseClaimOutcome,
    gameId: Option[GameId],
    humanColor: Option[Side],
    createdAt: Instant,
    expiresAt: Instant
)

/** Persistence seam for the singleton showcase table (ADR-005 §5–§7, #46). Everything the coordinator must remember
  * across a restart lives behind it: the next human colour, the current game, and the claim idempotency records. The
  * in-memory store deliberately has no implementation — the table is unavailable without PostgreSQL, by contract.
  */
trait ShowcaseStore:

  /** The table row. Always present: V6 inserts it, and nothing deletes it. */
  def showcaseTable: IO[ShowcaseTableRecord]

  /** The ids of every live showcase game — the reconciliation read: exactly one means a table to resume, none means a
    * table that may open, several is split-brain and fails closed.
    */
  def activeShowcaseGameIds: IO[List[GameId]]

  /** The unexpired claim record for `(actorId, key)`, if one exists. An expired record reads as absent. */
  def findShowcaseClaim(actorId: String, key: UUID): IO[Option[ShowcaseClaimRecord]]

  /** Commit a winning claim in ONE transaction: advance the colour from `expectedNextHumanColor` to its opposite, point
    * the table at `gameId`, and write the claim record. The colour advances only here, and only after the caller has
    * durably created the room, which is what makes a failed creation colour-neutral (ADR-005 §6).
    *
    * Answers `false` — committing nothing — when the fence does not hold: the row's colour is not the expected one, or
    * a game is already recorded as current. Either means the coordinator's view of the table has diverged from the
    * database, and the caller fails closed rather than seating a second table.
    */
  def commitShowcaseClaim(
      actorId: String,
      key: UUID,
      requestHash: String,
      gameId: GameId,
      humanColor: Side,
      expectedNextHumanColor: Side
  ): IO[Boolean]

  /** Record a claim that was answered with the spectator outcome, so a same-key retry replays it. Idempotent. */
  def recordSpectatingClaim(actorId: String, key: UUID, requestHash: String, gameId: Option[GameId]): IO[Unit]

  /** Adopt a live showcase game found on boot as the table's current game (ADR-005 §11 crash recovery). Repairs the
    * colour too: when the row still names the resumed human's own colour as "next", the claim transaction that should
    * have advanced it never committed, so it advances now; when it already differs, nothing changes. Idempotent.
    */
  def adoptShowcaseGame(gameId: GameId, humanColor: Side): IO[ShowcaseTableRecord]

  /** Forget `gameId` as the current game once its terminal transaction is durable (ADR-005 §7 barrier 4). Answers
    * whether the row named that game — `false` for a repeat, which is how a duplicate completion stays harmless.
    */
  def clearShowcaseGame(gameId: GameId): IO[Boolean]

object ShowcaseStore:

  /** How long a claim record replays for before it reads as a fresh claim (ADR-005 §5). Mirrors the SQL default. */
  val ClaimRetention: java.time.Duration = java.time.Duration.ofHours(24)

  /** The stored form of a colour (`white` / `black`), shared by the V6 CHECK constraints and this decoder. */
  def colorName(side: Side): String = side.toString.toLowerCase

  def parseColor(name: String): Option[Side] = Side.values.find(colorName(_) == name)
