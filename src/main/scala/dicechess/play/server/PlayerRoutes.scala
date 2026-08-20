package dicechess.play.server

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import cats.effect.IO
import dicechess.play.core.{Principal, PublicPlayer, Seat}
import dicechess.play.store.{
  GameResultRow,
  GameResultsStore,
  OpponentAggregateRow,
  OpponentFilter,
  PovResultFilter,
  UserStore
}
import dicechess.play.wire.Codecs.given
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.{HttpRoutes, ParseFailure, QueryParamDecoder}

import java.time.Instant

/** One finished game from the requesting guest's own point of view — the same POV reframing and opponent anonymisation
  * as `LeaderboardRoutes.RecentGame`, generalised to any participant instead of only registered bots.
  */
final case class PlayerGame(
    gameId: String,
    seat: Seat,
    opponent: PublicPlayer,
    result: String, // "win" | "draw" | "loss" | "unknown", from the requesting guest's POV
    rated: Boolean,
    termination: String,
    timeControl: String,
    finishedAt: Instant
) derives Codec.AsObject

/** `hasMore` lets the client tell "that's everything" from "there's another page" without a `COUNT(*)` or a second
  * request — see `GameResultsStore.Page` (#173).
  */
final case class PlayerGames(games: List[PlayerGame], hasMore: Boolean) derives Codec.AsObject

/** One opponent bucket: a specific registered bot, or the collapsed "every human/guest opponent" row. `opponent`
  * mirrors `PlayerGame.opponent`'s shape (so the client's existing opponent-rendering logic applies unchanged);
  * `team`/`botName` are present ONLY for a bot row — they are the machine-readable key for building a
  * `?vs=<team>/<name>` games-filter link, which a display name alone can't safely round-trip (a team or bot name could
  * itself contain a space). Shared by two callers of the same `opponentsFor` query (#174), which is symmetric in its
  * `externalId` parameter: a guest's own opponents (`GET /players/{guestId}/opponents`) and a bot's own opponents,
  * including its record against humans (`GET /bots/{team}/{name}`, #182).
  */
final case class PlayerOpponent(
    opponent: PublicPlayer,
    team: Option[String],
    botName: Option[String],
    games: Int,
    wins: Int,
    draws: Int,
    losses: Int,
    lastPlayedAt: Instant
) derives Codec.AsObject

final case class PlayerOpponents(opponents: List[PlayerOpponent]) derives Codec.AsObject

/** The wire face for any non-bot opponent — collapses every human/guest identity to one anonymous marker.
  * Package-visible so `LeaderboardRoutes` can reuse it for a bot's own "record vs humans" row (#182).
  */
private[server] val AnonymousHuman: PublicPlayer = PublicPlayer.of(Principal.Guest(""))

/** Reframe an aggregate row into the public wire shape — `team`/`botName` populated only for a specific bot, `None` for
  * the collapsed anonymous-humans bucket (see `PlayerOpponent`'s own doc). Package-visible: shared with
  * `LeaderboardRoutes` (#182), which queries the same aggregate from a bot's own point of view.
  */
private[server] def playerOpponent(row: OpponentAggregateRow): PlayerOpponent =
  val bot = row.botExternalId.flatMap(Principal.fromBotExternalId)
  PlayerOpponent(
    opponent = bot.fold(AnonymousHuman)(PublicPlayer.of),
    team = bot.map(_.team),
    botName = bot.map(_.name),
    games = row.games,
    wins = row.wins,
    draws = row.draws,
    losses = row.losses,
    lastPlayedAt = row.lastPlayedAt
  )

/** Public, unauthenticated read API for a visitor's own finished games (#151) and per-opponent record (#174), with
  * keyset pagination and opponent/result filters on the games list (#173).
  *
  * "My Games" on the play site today lists only client-authoritative `/play` bot games (read from the browser's
  * IndexedDB), because the live surface (`/lobby` + `/live/[id]`) is server-authoritative and never writes locally — so
  * a visitor's lobby/live games, though durably recorded and ingested to analytics, never show up there. This exposes
  * the same recent-games read `LeaderboardRoutes` already serves for a registered bot's profile, keyed by a guest's own
  * external id instead.
  *
  * Identity: the path segment is the BARE uuid, matching the convention `POST /games` and `/lobby/seeks` already use
  * (`Principal.guest` wraps it into `guest:<uuid>` internally) — not the prefixed `external_id`, per #14. A guest uuid
  * is unguessable (uuidv7) and doubles as its holder's restore code (see the play SPA's `guestIdentity.ts`), so direct
  * lookup by the full id IS the visitor's own identity check: there is deliberately no listing/enumeration surface, and
  * an unknown-but-valid uuid returns an empty list (200), not 404 — the two are indistinguishable by design, so this
  * endpoint itself leaks no signal about which guest ids have ever played. The same holds for `?vs=<team>/<name>`: it
  * only ever accepts a bot key, never an opposing guest id (see `OpponentFilter`'s own doc for why).
  *
  * Deliberately out of scope: per-game replay (turns/snapshot). `GET /games/{id}` cannot serve a finished game either
  * (`GameRegistry` evicts a room from memory once it ends) — replay for a finished game has no endpoint anywhere today,
  * live or otherwise, and adding one is a separate, larger feature.
  *
  * Mounted only when persistence is configured, like `LeaderboardRoutes`: without a database there is no `game_results`
  * projection to read.
  */
object PlayerRoutes:

  /** Default page size — same bound `GameResultsStore.recentResultsFor` already applies for bot profiles. */
  private val DefaultLimit = GameResultsStore.DefaultRecentLimit

  /** Hard cap on a client-requested `limit`: an unauthenticated endpoint must not let a caller demand an arbitrarily
    * expensive page.
    */
  private val MaxLimit = 200

  // Validating, not plain: a plain OptionalQueryParamDecoderMatcher's unapply fails the WHOLE route match on a
  // decode error (e.g. `?limit=abc`), which falls through to a misleading 404 instead of reporting the bad request.
  private object LimitParam extends OptionalValidatingQueryParamDecoderMatcher[Int]("limit")

  private given QueryParamDecoder[Instant] = QueryParamDecoder[String].emap: s =>
    try Right(Instant.parse(s))
    catch case e: java.time.format.DateTimeParseException => Left(ParseFailure(s"invalid instant '$s'", e.getMessage))
  private object BeforeParam extends OptionalValidatingQueryParamDecoderMatcher[Instant]("before")

  // Plain, not validating: any string is accepted here (an empty QueryParamDecoder[String] never fails), the
  // *meaning* of the string is validated afterward by parseVs/parseResult, uniformly with the other query errors.
  private object VsParam     extends OptionalQueryParamDecoderMatcher[String]("vs")
  private object ResultParam extends OptionalQueryParamDecoderMatcher[String]("result")

  def apply(results: GameResultsStore, users: UserStore): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "players" / guestId / "games" :? LimitParam(limit) +& BeforeParam(before) +& VsParam(vs) +&
          ResultParam(result) =>
        val validated = for
          guest     <- Principal.guest(guestId)
          parsed    <- parseValidated(limit, "limit")
          beforeAt  <- parseValidated(before, "before")
          vsFilter  <- parseVs(vs)
          povResult <- parseResult(result)
        yield (guest, parsed, beforeAt, vsFilter, povResult)
        validated match
          case Left(error)                                           => BadRequest(error)
          case Right((guest, parsed, beforeAt, vsFilter, povResult)) =>
            val bounded = parsed.fold(DefaultLimit)(requested => math.max(1, math.min(requested, MaxLimit)))
            results
              .playerGamesPage(List(guest.externalId), beforeAt, vsFilter, povResult, bounded)
              .flatMap { page =>
                // One lookup for the whole page rather than one per row.
                val opponentIds = page.games.flatMap(row => List(row.whiteExternalId, row.blackExternalId))
                users
                  .nicknamesByExternalId(opponentIds)
                  .flatMap: nicknames =>
                    Ok(
                      PlayerGames(
                        page.games.map(playerGame(Set(guest.externalId), _, nicknames)),
                        page.hasMore
                      )
                    )
              }

      case GET -> Root / "players" / guestId / "opponents" =>
        Principal.guest(guestId) match
          case Left(error)  => BadRequest(error)
          case Right(guest) =>
            results.opponentsFor(List(guest.externalId)).flatMap(rows => Ok(PlayerOpponents(rows.map(playerOpponent))))

  /** Surfaces a malformed validating query param as the same `Left(message)` shape `Principal.guest` already uses,
    * instead of `OptionalQueryParamDecoderMatcher`'s silent match failure (which would otherwise 404 the whole route
    * instead of reporting the bad request — see `LimitParam`/`BeforeParam` above).
    */
  private def parseValidated[A](param: Option[ValidatedNel[ParseFailure, A]], name: String): Either[String, Option[A]] =
    param match
      case None             => Right(None)
      case Some(Valid(v))   => Right(Some(v))
      case Some(Invalid(e)) => Left(s"$name: ${e.head.sanitized}")

  /** `?vs=human` collapses to `OpponentFilter.HumanOnly`; `?vs=<team>/<name>` resolves to a specific bot's `externalId`
    * (no existence check against the `bots` table — an unregistered team/name simply matches nothing, the same "trust
    * the caller, an unknown key is just empty" stance the guest-id lookup itself takes). Anything else is a 400: in
    * particular a bare guest id is never accepted here, by construction — there is no code path from an arbitrary
    * string to `OpponentFilter.Bot` other than the team/name split below.
    */
  private[server] def parseVs(vs: Option[String]): Either[String, Option[OpponentFilter]] =
    vs match
      case None          => Right(None)
      case Some("human") => Right(Some(OpponentFilter.HumanOnly))
      case Some(spec)    =>
        spec.split('/') match
          case Array(team, name) if team.nonEmpty && name.nonEmpty =>
            Right(Some(OpponentFilter.Bot(Principal.Bot(team, name).externalId)))
          case _ => Left(s"vs: '$spec' must be 'human' or '<team>/<name>'")

  private[server] def parseResult(result: Option[String]): Either[String, Option[PovResultFilter]] =
    result match
      case None         => Right(None)
      case Some("win")  => Right(Some(PovResultFilter.Win))
      case Some("draw") => Right(Some(PovResultFilter.Draw))
      case Some("loss") => Right(Some(PovResultFilter.Loss))
      case Some(other)  => Left(s"result: '$other' must be 'win', 'draw', or 'loss'")

  /** Reframe a stored white-POV row from the requester's point of view — the same transform as
    * `LeaderboardRoutes.recentGame`, generalised to any principal instead of only bots. `requesterIds` is a set because
    * a signed-in account is several identities at once (its own plus every claimed guest id, #236); the White seat
    * being one of them is what "I played White" means.
    */
  private[server] def playerGame(
      requesterIds: Set[String],
      row: GameResultRow,
      nicknames: Map[String, String]
  ): PlayerGame =
    val requesterIsWhite   = requesterIds.contains(row.whiteExternalId)
    val (seat, opponentId) =
      if requesterIsWhite then (Seat.White, row.blackExternalId) else (Seat.Black, row.whiteExternalId)
    // A registered opponent shows its nickname; a guest opponent stays anonymous, because `nicknames` has no guest
    // path (see `UserStore.nicknamesByExternalId`). An empty map reproduces the pre-#194 anonymous behaviour exactly.
    val opponent = PublicPlayer.ofExternalId(opponentId, nicknames)
    val result   = row.result match
      case Some(0)                       => "draw"
      case Some(1) if requesterIsWhite   => "win"
      case Some(-1) if !requesterIsWhite => "win"
      case Some(_)                       => "loss"
      case None                          => "unknown"
    PlayerGame(
      gameId = row.gameId.value,
      seat = seat,
      opponent = opponent,
      result = result,
      rated = row.rated,
      termination = row.termination,
      timeControl = row.timeControl,
      finishedAt = row.finishedAt
    )
