package dicechess.play.ingest

import dicechess.play.core.*
import dicechess.play.store.GameSnapshot
import io.circe.Json
import io.circe.syntax.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Maps a finished game to the analytics `GameIngest` wire JSON (snake_case, exactly the contract
  * `dicechess-analytics/api/IngestProtocol.scala` decodes and the play SPA's client mapper emits).
  *
  * Identity follows ADR-0003 / the phase-3b design: `source='playsite'`, game id `UUIDv5(URL_NS,
  * "playsite/game/<gameId>")` (idempotent re-sends; disjoint from client-mapped games), humans `guest:<uuid>` /
  * `user:<uuid>`, bots `bot:team:<team>:<name>`. Free games keep all stake fields null.
  */
object PlaysiteIngest:

  /** RFC 4122 URL namespace — the same namespace the play SPA and beturanga use for deterministic game ids. */
  private val UrlNamespace = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

  // All games currently start from the standard position (GameRegistry never passes a custom DFEN).
  private val StartFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  /** The analytics id for a play-api game — deterministic, so redelivery is idempotent (first-writer-wins). */
  def ingestId(id: GameId): UUID = uuidV5(UrlNamespace, s"playsite/game/${id.value}")

  /** The `GameIngest` payload for a finished game, or `None` when the game must not be ingested: still active, or
    * aborted (a server-side failure has no sporting result and would only pollute the corpus).
    */
  def payload(id: GameId, snapshot: GameSnapshot): Option[Json] =
    snapshot.status match
      case GameStatus.Active                                  => None
      case GameStatus.Ended(GameOver(_, Termination.Aborted)) => None
      case GameStatus.Ended(GameOver(result, termination))    =>
        Some(
          Json.obj(
            "id"                   -> ingestId(id).toString.asJson,
            "source"               -> "playsite".asJson,
            "mode"                 -> "classic".asJson,
            "result"               -> resultOf(result).asJson,
            "termination"          -> terminationOf(termination).asJson,
            "started_at"           -> snapshot.createdAtEpochMs.map(isoUtc).asJson,
            "time_initial_sec"     -> timeInitialSec(snapshot.timeControl).asJson,
            "time_increment_sec"   -> timeIncrementSec(snapshot.timeControl).asJson,
            "initial_stake_amount" -> Json.Null,
            "final_stake_amount"   -> Json.Null,
            "white_money_delta"    -> Json.Null,
            "black_money_delta"    -> Json.Null,
            "stake_currency"       -> Json.Null,
            "white_player"         -> snapshot.players.get(Seat.White).map(player).asJson,
            "black_player"         -> snapshot.players.get(Seat.Black).map(player).asJson,
            "initial_fen"          -> StartFen.asJson,
            "turns"                -> snapshot.turns.map(turn).asJson,
            "events"               -> Json.arr()
          )
        )

  /** White-POV result: 1 white won, -1 black won, 0 draw — the analytics convention. `private[play]`: reused verbatim
    * by `PgGameStore`'s `game_results` projection (#98), so the two never drift apart.
    */
  private[play] def resultOf(result: GameResult): Int = result match
    case GameResult.Win(Side.White) => 1
    case GameResult.Win(Side.Black) => -1
    case GameResult.Draw            => 0

  /** `game_termination_enum` members, for `payload()`'s own use (Aborted is filtered out before reaching this) — and
    * reused as-is by `game_results` (#98), which unlike the analytics corpus DOES record aborted games.
    */
  private[play] def terminationOf(termination: Termination): String = termination match
    case Termination.KingCaptured => "king_captured"
    case Termination.Timeout      => "timeout"
    case Termination.Resign       => "resign"
    case Termination.Draw         => "draw_agreement"
    case Termination.Aborted      => "aborted"

  private def timeInitialSec(timeControl: TimeControl): Option[Int] = timeControl match
    case TimeControl.SuddenDeath(initial)               => Some(initial)
    case TimeControl.Fischer(initial, _)                => Some(initial)
    case TimeControl.Unlimited | TimeControl.PerMove(_) => None

  private def timeIncrementSec(timeControl: TimeControl): Option[Int] = timeControl match
    case TimeControl.SuddenDeath(_)                     => Some(0)
    case TimeControl.Fischer(_, increment)              => Some(increment)
    case TimeControl.Unlimited | TimeControl.PerMove(_) => None

  private def player(principal: Principal): Json =
    val (username, playerType) = principal match
      case Principal.Guest(_)     => ("Guest", "guest")
      case Principal.User(_)      => ("Player", "human")
      case Principal.Bot(_, name) => (name, "bot")
    Json.obj(
      "external_id" -> principal.externalId.asJson,
      "username"    -> username.asJson,
      "player_type" -> playerType.asJson,
      "rating"      -> Json.Null
    )

  private def turn(record: dicechess.play.store.TurnRecord): Json =
    Json.fromJsonObject(dicechess.play.store.TurnRecord.json(record))

  // Instant.toString is ISO-8601 UTC with seconds always present — parses as an OffsetDateTime on the analytics side.
  private def isoUtc(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString

  /** RFC 4122 version-5 (SHA-1, name-based) UUID — the JDK only ships v3/v4. */
  private def uuidV5(namespace: UUID, name: String): UUID =
    val sha1 = MessageDigest.getInstance("SHA-1")
    sha1.update(uuidBytes(namespace))
    sha1.update(name.getBytes(UTF_8))
    val hash = sha1.digest()
    hash(6) = ((hash(6) & 0x0f) | 0x50).toByte // version 5
    hash(8) = ((hash(8) & 0x3f) | 0x80).toByte // IETF variant
    val msb = (0 until 8).foldLeft(0L)((acc, i) => (acc << 8) | (hash(i) & 0xffL))
    val lsb = (8 until 16).foldLeft(0L)((acc, i) => (acc << 8) | (hash(i) & 0xffL))
    UUID(msb, lsb)

  private def uuidBytes(uuid: UUID): Array[Byte] =
    val buffer = java.nio.ByteBuffer.allocate(16)
    buffer.putLong(uuid.getMostSignificantBits)
    buffer.putLong(uuid.getLeastSignificantBits)
    buffer.array()
