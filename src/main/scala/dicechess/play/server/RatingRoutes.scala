package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.GameId
import dicechess.play.store.{GameRatingChange, RatingStore, SeatRatingChange}
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.`Cache-Control`
import org.http4s.{CacheDirective, HttpRoutes}

import scala.concurrent.duration.*

/** One seat's rating movement on the wire, on the public Glicko scale — the raw `Double`s the batch wrote, deliberately
  * unrounded: how many decimals a player sees is a presentation decision, and the rating that appears elsewhere on the
  * wire (`Players`, `/leaderboard`, `/players/by-nickname/{nick}`) is unrounded too. The delta is `after - before`; it
  * is not sent, so there is exactly one place it can be computed from.
  */
final case class SeatRatingChangeResponse(before: Double, after: Double) derives Codec.AsObject

/** `GET /games/{id}/rating`'s response (#296): what one finished game did to each seat's rating.
  *
  * `applied` is what a client polls on — the rating batch runs up to `RATING_INTERVAL_SECONDS` behind the game's end,
  * so "not yet" is a normal, temporary answer for a freshly finished game. Once `applied` is true the answer is final,
  * including the case where both seats are `null`: a game the batch deliberately skipped (casual, a guest seat, an
  * unregistered bot, self-play, a deleted account) moved nobody's rating, and that is an answer, not a not-yet.
  */
final case class GameRatingResponse(
    gameId: String,
    applied: Boolean,
    white: Option[SeatRatingChangeResponse],
    black: Option[SeatRatingChangeResponse]
) derives Codec.AsObject

/** Public, unauthenticated per-game rating movement (#296) — the read side of what `RatingBatch` records.
  *
  * '''Why a per-game endpoint at all.''' Before this, a client wanting to tell a player what a game did to their rating
  * had to diff the player's CURRENT rating against the rating frozen in the room at game start. That number is not this
  * game's: the batch applies games asynchronously, so a rematch typically starts before the previous game has been
  * applied, and the diff then reports the PREVIOUS game's change — a negative delta after a win, which Glicko-2 cannot
  * produce. Keyed by game, never by player: a game's own row is the only place the movement is unambiguous.
  *
  * Anonymous by construction, which is why it needs no authentication: it answers with two numbers per SEAT and never
  * names who sat there. A caller who already holds the game id learns nothing about identities from it — the seat faces
  * come from `GET /games/{id}` or `/games/{id}/history`, under those routes' own rules.
  *
  * Mounted only when persistence is configured, the same DB-only seam as `HistoryRoutes`/`LeaderboardRoutes`: without a
  * database there is no `game_results` projection to have recorded anything.
  */
object RatingRoutes:

  /** An applied row never changes again — the batch stamps a game exactly once and never revisits it — and, unlike a
    * replay, this response carries no live-resolved nickname that account deletion could strand in a cache (see
    * `HistoryRoutes`'s caching note). So it is genuinely cacheable for a long time. The pending answer is the opposite:
    * it is a "not yet" that a poller must be able to see change, so it must not be stored anywhere.
    */
  private val AppliedMaxAge: FiniteDuration = 365.days

  def apply(ratings: RatingStore): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "games" / id / "rating" =>
        // Same reason as `HistoryRoutes`: `game_results.game_id` is a `uuid` column, so a non-UUID segment would reach
        // the store's `::uuid` cast and surface as a database error instead of the plain "no such game" meant here.
        Either.catchOnly[IllegalArgumentException](java.util.UUID.fromString(id)) match
          case Left(_)  => NotFound()
          case Right(_) =>
            ratings.ratingChangeFor(GameId(id)).flatMap {
              // No result row: an unknown id, a game still in progress, or one this server only ever held in memory.
              case None         => NotFound()
              case Some(change) => Ok(responseFor(id, change)).map(_.putHeaders(cacheControl(change.applied)))
            }

  private def responseFor(id: String, change: GameRatingChange): GameRatingResponse =
    GameRatingResponse(id, change.applied, change.white.map(seat), change.black.map(seat))

  private def seat(change: SeatRatingChange): SeatRatingChangeResponse =
    SeatRatingChangeResponse(change.before, change.after)

  private def cacheControl(applied: Boolean): `Cache-Control` =
    if applied then `Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(AppliedMaxAge))
    else `Cache-Control`(CacheDirective.`no-store`)
