package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{GameId, RatingCategory}
import dicechess.play.rating.Glicko
import dicechess.play.store.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.headers.`Cache-Control`
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Status}

/** The per-game rating wire (#296) over a stub `RatingStore` — what the batch actually records is covered against a
  * real database in `RatingBatchSuite`; here the subject is the HTTP layer: the three answers a poller has to tell
  * apart (missing game / not applied yet / applied), the wire shape, and the caching that follows from them.
  */
class RatingRoutesSuite extends munit.CatsEffectSuite:

  private def stubRatings(known: Map[String, GameRatingChange]): RatingStore = new RatingStore:
    def unappliedRatedGames(limit: Int): IO[List[GameResultRow]]                              = IO.pure(Nil)
    def applyRatingUpdate(gameId: GameId, white: RatingUpdate, black: RatingUpdate): IO[Unit] = IO.unit
    def markRatingApplied(gameId: GameId): IO[Unit]                                           = IO.unit
    def ratingChangeFor(gameId: GameId): IO[Option[GameRatingChange]] = IO.pure(known.get(gameId.value))
    def categoryRatingOf(identity: RatedIdentity, category: RatingCategory): IO[Glicko] = IO.pure(Glicko.Initial)
    def categoryRatingsOf(identity: RatedIdentity): IO[Map[RatingCategory, Glicko]]     = IO.pure(Map.empty)

  private def app(known: Map[String, GameRatingChange] = Map.empty): HttpApp[IO] =
    RatingRoutes(stubRatings(known)).orNotFound

  private val gameId = "11111111-2222-3333-4444-555555555555"

  private val applied = GameRatingChange(
    applied = true,
    white = Some(SeatRatingChange(before = 1775.6714474976957, after = 1797.2144251082318)),
    black = Some(SeatRatingChange(before = 1601.5, after = 1580.25))
  )

  test("GET /games/{id}/rating is 404 for an id with no result row"):
    app()
      .run(Request[IO](Method.GET, uri"/games/11111111-2222-3333-4444-555555555555/rating"))
      .map(resp => assertEquals(resp.status, Status.NotFound))

  test("GET /games/{id}/rating is 404 for a segment that is not a UUID, never a database error"):
    app()
      .run(Request[IO](Method.GET, uri"/games/not-a-uuid/rating"))
      .map(resp => assertEquals(resp.status, Status.NotFound))

  test("an applied game reports both seats' movement, unrounded, and pins the wire shape"):
    val expected = parse(
      s"""{"gameId":"$gameId","applied":true,
           "white":{"before":1775.6714474976957,"after":1797.2144251082318},
           "black":{"before":1601.5,"after":1580.25}}"""
    ).toOption.get
    app(Map(gameId -> applied))
      .run(Request[IO](Method.GET, uri"/games/11111111-2222-3333-4444-555555555555/rating"))
      .flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        assertEquals(
          resp.headers.get[`Cache-Control`].map(_.value),
          Some("public, max-age=31536000"),
          "an applied row never changes again, so it is worth caching hard"
        )
        resp.as[Json].map(assertEquals(_, expected, "the SPA reads these field names — pin them"))
      }

  test("a game the batch has not reached yet answers applied:false, and must not be cached"):
    val pending = GameRatingChange(applied = false, white = None, black = None)
    app(Map(gameId -> pending))
      .run(Request[IO](Method.GET, uri"/games/11111111-2222-3333-4444-555555555555/rating"))
      .flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        assertEquals(
          resp.headers.get[`Cache-Control`].map(_.value),
          Some("no-store"),
          "a poller has to be able to see this answer change"
        )
        resp.as[Json].map { body =>
          assertEquals(body.hcursor.get[Boolean]("applied").toOption, Some(false))
          assertEquals(body.hcursor.get[Option[Json]]("white").toOption.flatten, None)
        }
      }

  test("an applied game that moved nobody's rating says so, rather than looking like a pending one"):
    // Casual, a guest seat, self-play, an unregistered bot, a deleted account: the batch stamps the row and writes no
    // numbers. `applied` is what tells a client to stop polling — the absent seats are the final answer, not a not-yet.
    val skipped = GameRatingChange(applied = true, white = None, black = None)
    app(Map(gameId -> skipped))
      .run(Request[IO](Method.GET, uri"/games/11111111-2222-3333-4444-555555555555/rating"))
      .flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          assertEquals(body.hcursor.get[Boolean]("applied").toOption, Some(true))
          assertEquals(body.hcursor.get[Option[Json]]("black").toOption.flatten, None)
        }
      }

  test("the recorded movement is served verbatim — a winner's rating can never come back lower"):
    // The bug this endpoint exists for (#296): diffing a profile against a stale baseline produced a NEGATIVE delta
    // after a win. Here the two numbers come from the same row the batch wrote, so the sign is the game's own.
    val change = SeatRatingChange(before = Glicko.Initial.rating, after = 1512.75)
    app(Map(gameId -> GameRatingChange(applied = true, white = Some(change), black = None)))
      .run(Request[IO](Method.GET, uri"/games/11111111-2222-3333-4444-555555555555/rating"))
      .flatMap(_.as[Json])
      .map { body =>
        val white = body.hcursor.downField("white")
        assertEquals(white.get[Double]("before").toOption, Some(1500.0))
        assertEquals(white.get[Double]("after").toOption, Some(1512.75))
      }
