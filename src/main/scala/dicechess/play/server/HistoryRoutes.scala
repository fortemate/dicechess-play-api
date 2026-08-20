package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.store.{ArchivedGame, GameArchive, GameArchiveStore, TurnRecord, UserStore}
import dicechess.play.wire.Codecs.given
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.`Cache-Control`
import org.http4s.{CacheDirective, HttpRoutes}

import java.time.Instant
import scala.concurrent.duration.*

/** One archived turn on the public wire — the same fields `GameArchive` persisted, `activeColor` promoted from its
  * stored "w"/"b" convention to the `Seat` the rest of this resource family (`GET /games/{id}`) already uses.
  */
final case class HistoryTurn(
    turnNumber: Long,
    activeColor: Seat,
    dice: List[Int],
    moves: List[String],
    fenAfter: String
) derives Codec.AsObject

/** The dice-fairness reveal: `commit` is always present (published at creation, never secret); `seed` and `clientSeeds`
  * are populated once the game has ended — always, for a game recorded here at all, since the archive only ever holds
  * finished games.
  */
final case class HistoryFairness(commit: Option[String], seed: Option[String], clientSeeds: Option[ClientSeeds])
    derives Codec.AsObject

/** `GET /games/{id}/history`'s response (#178): a finished game's full replay, players anonymized exactly like the live
  * wire's `Players`. `result`/`termination` are white-POV/neutral — there is no requester identity here, unlike
  * `PlayerRoutes`'s own POV-reframed `PlayerGame`. `timeControl` is the structured ADT (matching the live
  * `PublicGameState`, NOT `PlayerGame`'s stringified `game_results` column) so a replay page can reuse the exact same
  * rendering the live board already has for the same game.
  */
final case class GameHistory(
    gameId: String,
    players: Players,
    rated: Boolean,
    timeControl: TimeControl,
    result: Int,
    termination: String,
    finishedAt: Instant,
    initialDfen: String,
    turns: List[HistoryTurn],
    fairness: HistoryFairness
) derives Codec.AsObject

/** Public, unauthenticated replay for a finished game (#178) — the read side of the durable archive `GameArchive`
  * writes (#177). Mounted only when persistence is configured, like `PlayerRoutes`/`LeaderboardRoutes`: without a
  * database there is no `game_archive` to read.
  *
  * '''Caching.''' The GAME is immutable — turns, dice, seeds and result can never change again — but the seat faces are
  * not: they resolve the players' CURRENT nicknames, so the response as a whole is no longer eternal. It is therefore
  * `public, max-age=5m` and deliberately NOT `immutable`.
  *
  * The binding constraint is account deletion, not rename staleness. `deleteUser` is a hard DELETE, and that is exactly
  * how #237 anonymises history: the `user:<uuid>` left in the archive stops resolving to anyone. A `public, immutable,
  * max-age=1y` response carrying a nickname would let a shared cache keep serving that name for a year after the
  * account was deleted, which makes the anonymisation promise false in practice — and production sits behind a CDN, so
  * this is not hypothetical. Five minutes still absorbs the burst a freshly shared replay link produces, while bounding
  * how long a deleted player's name can outlive the deletion.
  */
object HistoryRoutes:

  /** Short on purpose — see the caching note in the object's own doc: the seat faces are live-resolved. */
  private val RevealedMaxAge: FiniteDuration = 5.minutes

  def apply(archive: GameArchiveStore, users: UserStore): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "games" / id / "history" =>
        // `game_archive.game_id` is a `uuid` column, so a non-UUID path segment would otherwise reach the store's
        // `::uuid` cast and blow up as a database error instead of the plain "no such game" this route means to say —
        // same non-distinction PlayRoutes' in-memory lookup already makes for free (a garbage id just misses the map).
        Either.catchOnly[IllegalArgumentException](java.util.UUID.fromString(id)) match
          case Left(_)  => NotFound()
          case Right(_) =>
            archive
              .archiveFor(GameId(id))
              .flatMap:
                case None                                    => NotFound()
                case Some(ArchivedGame(payload, finishedAt)) =>
                  GameArchive.decode(payload) match
                    // The row was written by this same server — a decode failure here means the write/read shapes
                    // have drifted, a bug to surface loudly, not a client-facing 404 (the game plainly did finish
                    // and archive).
                    case Left(failure) => InternalServerError(s"corrupt archive row for $id: ${failure.getMessage}")
                    case Right(record) =>
                      // One lookup for the two seats. A replay is a public page, so this resolves ACCOUNT ids only —
                      // `nicknamesByExternalId` has no guest path, which is what keeps a claimed guest id nameless here
                      // even though its owner has an account (#236).
                      val faces = users
                        .nicknamesByExternalId(List(record.whiteExternalId, record.blackExternalId))
                      faces.flatMap { nicknames =>
                        val body = GameHistory(
                          gameId = id,
                          players = Players(
                            PublicPlayer.ofExternalId(record.whiteExternalId, nicknames),
                            PublicPlayer.ofExternalId(record.blackExternalId, nicknames)
                          ),
                          rated = record.rated,
                          timeControl = record.timeControl,
                          result = record.result,
                          termination = record.termination,
                          finishedAt = finishedAt,
                          initialDfen = record.initialDfen,
                          turns = record.turns.map(historyTurn),
                          fairness = HistoryFairness(
                            commit = record.commit,
                            seed = Some(record.serverSeed),
                            clientSeeds = Some(ClientSeeds(record.clientSeedWhite, record.clientSeedBlack))
                          )
                        )
                        val cacheControl =
                          `Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(RevealedMaxAge))
                        Ok(body).map(_.putHeaders(cacheControl))
                      }

  private def historyTurn(t: TurnRecord): HistoryTurn =
    HistoryTurn(t.turnNumber, seatOf(t.activeColor), t.dice, t.moves, t.fenAfter)

  /** `TurnRecord.activeColor` is always exactly "w" or "b" (see its own doc) — this is the one place that convention
    * gets promoted to the public `Seat` the rest of the wire uses.
    */
  private def seatOf(activeColor: String): Seat = if activeColor == "w" then Seat.White else Seat.Black
