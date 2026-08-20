package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{PublicPlayer, Principal, RatingCategory, Seat}
import dicechess.play.rating.{Glicko, Glicko2}
import dicechess.play.store.{
  BotStore,
  RatedIdentity,
  RatingStore,
  ResultTally,
  GameResultRow,
  GameResultsStore,
  LeaderboardEntry,
  LeaderboardStore,
  PlayerLeaderboardEntry,
  UserAccount,
  UserStore
}
import dicechess.play.wire.Codecs.given
import io.circe.Codec
import org.http4s.{HttpRoutes, Response}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*

import java.time.Instant

/** One public leaderboard row: rank is 1-based within this response. W-D-L counts rated, decided games only — the
  * ladder record, not lifetime activity.
  */
final case class LeaderRow(
    rank: Int,
    // "bot" | "player" (#249). Bots and accounts share ONE Glicko scale, so a merged board is honest — but the default
    // response stays bots-only and byte-compatible apart from this added field, so the SPA is not broken by the change.
    kind: String,
    // Absent for an account: a person has no team, only the nickname carried in `name`.
    team: Option[String],
    name: String,
    rating: Double,
    rd: Double,
    onLadder: Boolean,
    games: Int,
    wins: Int,
    draws: Int,
    losses: Int
) derives Codec.AsObject

/** The public board. Provisional entrants (RD above the convergence threshold) are absent by policy (#119) — the same
  * rule for accounts as for bots.
  *
  * `category` (#280) echoes which scale these rows are on, including when the request named none: a client that renders
  * tabs must be able to tell what it is displaying without re-deriving the server's default.
  */
final case class Leaderboard(category: String, leaders: List[LeaderRow]) derives Codec.AsObject

/** One recent game from the profiled bot's point of view. `opponent` is a public face — bots by team-qualified name,
  * humans anonymous — NEVER a raw external id: a guest's stable uuid would let anyone correlate an anonymous player
  * across games, which the rest of the public wire deliberately prevents (see `PublicPlayer`).
  */
final case class RecentGame(
    gameId: String,
    seat: Seat,
    opponent: PublicPlayer,
    result: String, // "win" | "draw" | "loss" | "unknown", from the profiled bot's POV
    rated: Boolean,
    termination: String,
    finishedAt: Instant
) derives Codec.AsObject

/** One participant's standing on ONE scale (#280): the rating, its deviation, whether it has converged, and the rated
  * W-D-L that produced it — all four scoped to the same category, because a rating from one speed beside a record from
  * every speed is two questions answered as one.
  *
  * A profile carries one of these per category it has ACTUALLY been rated in. A speed the participant has never played
  * is absent rather than present at 1500 ± 350: the tables are sparse (V21) precisely so that absence can mean "not
  * measured" instead of "measured as average".
  */
final case class CategoryRating(
    category: String,
    rating: Double,
    rd: Double,
    provisional: Boolean,
    games: Int,
    wins: Int,
    draws: Int,
    losses: Int
) derives Codec.AsObject

/** A bot's public profile: the rating summary, its recent games, and its aggregate record against every opponent it has
  * played (#182) — one row per other bot (head-to-head) plus one collapsed row for every human/guest opponent combined
  * ("record vs humans"). Unlike `games`/`wins`/`draws`/`losses` above (rated, decided games only — the ladder record),
  * `opponents` counts every game, rated or casual: a guest game is always casual (`GameRegistry.isRated`), so a
  * rated-only tally would always read zero against humans. Unlike the board, a provisional bot IS visible here
  * (flagged) — hiding it entirely would make `POST /bot/ladder/join` feel like a black hole for a fresh bot's owner
  * checking on their entrant.
  *
  * `totalGames` (#279) is the same rated-vs-all split spelled out as a number rather than left implicit: with rated
  * play now player-chosen rather than curated, a bot's own casual record against humans can be non-trivial, and `games`
  * alone would then read as "0 games" beside a non-empty `recent` list.
  *
  * '''`ratings` vs the scalars (#280).''' `ratings` is the real answer now: one entry per category this bot has been
  * rated in. The scalar `rating`/`rd`/`provisional`/`games`/`wins`/`draws`/`losses` stay, and now describe
  * [[RatingCategory.Default]] rather than a single all-speeds scale. Keeping the names and re-pointing them is
  * deliberate on both counts: the SPA renders them today and would show `undefined` the moment they vanished, and
  * pointing them at the retired scale instead would leave the wire's most-read field on the one number the follow-up
  * migration deletes. For every participant that exists today the two readings are the same number anyway — the
  * per-category scales were seeded from the shared one and essentially all traffic is Blitz.
  */
final case class BotProfile(
    team: String,
    name: String,
    rating: Double,
    rd: Double,
    provisional: Boolean,
    onLadder: Boolean,
    games: Int,
    wins: Int,
    draws: Int,
    losses: Int,
    totalGames: Int,
    ratings: List[CategoryRating],
    opponents: List[PlayerOpponent],
    recent: List[RecentGame]
) derives Codec.AsObject

/** An account's PUBLIC profile (#249) — deliberately the same shape as [[BotProfile]] so the SPA renders both with one
  * component, minus what only a bot has (`team`, `onLadder`).
  *
  * What is absent is the point: no email, no account uuid, and no trace of which guest identities this player claimed.
  * `/me/games` merges that history for its owner; folding it in HERE would retroactively deanonymise every anonymous
  * game those ids ever played, which is exactly the promise #236 made. So the record below counts `user:` games only. A
  * provisional player IS visible here (flagged), matching the bot profile: hiding a fresh account from its own page
  * would make signing up feel like a black hole.
  *
  * `games`/`wins`/`draws`/`losses` are RATED, decided games only (the rating record); `totalGames` (#279) is every
  * decided game, rated or casual. The split matters more here than on the bot profile: rated is now the PLAYER's own
  * choice at creation (no more operator-curated roster), so an active account can easily have `totalGames > 0` while
  * `games` stays `0` — and a page that only had the rated number used to read as "you have never played," which for a
  * signed-in visitor who just finished a casual game was simply false.
  */
final case class PlayerProfile(
    nickname: String,
    rating: Double,
    rd: Double,
    provisional: Boolean,
    games: Int,
    wins: Int,
    draws: Int,
    losses: Int,
    totalGames: Int,
    // Per category (#280), with the scalars above describing `RatingCategory.Default` — see [[BotProfile]] for why the
    // two coexist and why the old names were re-pointed rather than removed.
    ratings: List[CategoryRating],
    opponents: List[PlayerOpponent],
    recent: List[RecentGame]
) derives Codec.AsObject

/** Public, unauthenticated read API over the rating ladder (D.2, #103): the leaderboard and per-bot profiles. Pure
  * reads — the data is produced elsewhere (scheduler #102 plays the games, rating batch #119 maintains
  * `bots.glicko_*`). Mounted only when persistence is configured: without the database there is neither a bots table
  * nor a `game_results` projection to read.
  */
object LeaderboardRoutes:

  /** Which populations a board response covers. */
  final private[server] case class Populations(bots: Boolean, players: Boolean)

  /** `?kind=` → populations. Default `bots` keeps the existing response for the existing caller; an unrecognised value
    * is a 400 rather than a silent fallback, so a typo cannot look like an empty board.
    */
  private[server] def parseKind(kind: Option[String]): Either[String, Populations] =
    kind match
      case None | Some("bots") => Right(Populations(bots = true, players = false))
      case Some("players")     => Right(Populations(bots = false, players = true))
      case Some("all")         => Right(Populations(bots = true, players = true))
      case Some(other)         => Left(s"kind: '$other' must be 'bots', 'players', or 'all'")

  /** `?category=` → which scale to rank on (#280). Absent means [[RatingCategory.Default]] — see there for why the
    * ladder's category is what an unqualified request gets. An unrecognised value is a 400 for exactly the reason a bad
    * `kind` is: every category is legitimately empty for a while after it opens, so a silent fallback would make a typo
    * indistinguishable from a speed nobody has played yet.
    */
  private[server] def parseCategory(category: Option[String]): Either[String, RatingCategory] =
    category match
      case None        => Right(RatingCategory.Default)
      case Some(value) =>
        RatingCategory
          .fromWireName(value)
          .toRight(s"category: '$value' must be ${RatingCategory.values.map(c => s"'${c.wireName}'").mkString(", ")}")

  /** Rank is assigned after merging, so both builders emit 0 and the caller overwrites it. */
  private def botRow(entry: LeaderboardEntry): LeaderRow =
    LeaderRow(
      rank = 0,
      kind = "bot",
      team = Some(entry.team),
      name = entry.name,
      rating = entry.rating,
      rd = entry.rd,
      onLadder = entry.onLadder,
      games = entry.tally.games,
      wins = entry.tally.wins,
      draws = entry.tally.draws,
      losses = entry.tally.losses
    )

  private def playerRow(entry: PlayerLeaderboardEntry): LeaderRow =
    LeaderRow(
      rank = 0,
      kind = "player",
      team = None,
      name = entry.nickname,
      // A person is never "on the ladder": that flag is the bot scheduler's, and there is no scheduler for people.
      onLadder = false,
      rating = entry.rating,
      rd = entry.rd,
      games = entry.tally.games,
      wins = entry.tally.wins,
      draws = entry.tally.draws,
      losses = entry.tally.losses
    )

  /** Recent games shown on a profile — a glance at current form, not a full history. */
  val RecentGamesShown: Int = 20

  /** `?kind=` selects which populations the board covers (#249). The default is `bots`, NOT `all`: the SPA already
    * calls `/leaderboard` and must keep getting exactly what it does today, so the merged view is opt-in.
    */
  private object KindParam extends OptionalQueryParamDecoderMatcher[String]("kind")

  /** `?category=` selects the scale (#280); absent is [[RatingCategory.Default]], the ladder's. */
  private object CategoryParam extends OptionalQueryParamDecoderMatcher[String]("category")

  /** One participant's per-category standing (#280): the categories it has been rated in, each with the W-D-L that
    * produced that rating, in enum order so a client's tabs never reshuffle.
    *
    * Keyed on the RATINGS, not on the tallies: a rating row exists exactly when the batch has applied a game on that
    * scale, which is the same condition a non-empty tally has. Driving it from the ratings means a category can never
    * appear with a record and no number to attach it to.
    */
  private[server] def categoryRatings(
      ratings: Map[RatingCategory, Glicko],
      tallies: Map[RatingCategory, ResultTally]
  ): List[CategoryRating] =
    RatingCategory.values.toList.flatMap: category =>
      ratings.get(category).map { glicko =>
        val tally = tallies.getOrElse(category, ResultTally(0, 0, 0))
        CategoryRating(
          category = category.wireName,
          rating = glicko.rating,
          rd = glicko.deviation,
          provisional = glicko.deviation > Glicko2.ProvisionalDeviationThreshold,
          games = tally.games,
          wins = tally.wins,
          draws = tally.draws,
          losses = tally.losses
        )
      }

  def apply(
      bots: BotStore,
      board: LeaderboardStore,
      results: GameResultsStore,
      ratings: RatingStore,
      users: Option[UserStore] = None
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "leaderboard" :? KindParam(kind) +& CategoryParam(category) =>
        (LeaderboardRoutes.parseKind(kind), LeaderboardRoutes.parseCategory(category)).tupled match
          case Left(error)                    => BadRequest(error)
          case Right((wantedFor, onCategory)) =>
            val maxRd    = Glicko2.ProvisionalDeviationThreshold
            val limit    = LeaderboardStore.MaxBoardSize
            val botRows  = if wantedFor.bots then board.leaderboard(onCategory, maxRd, limit) else IO.pure(Nil)
            val playRows =
              if wantedFor.players then board.playerLeaderboard(onCategory, maxRd, limit) else IO.pure(Nil)
            (botRows, playRows).flatMapN: (botEntries, playerEntries) =>
              // Ranked across both populations by the conservative estimate `rating − k·RD` (#169, the same key as
              // the stores' ORDER BY — see `Glicko2.ConservativeOrderingK`), because they ARE one scale — a separate
              // rank per kind would imply two currencies. Ties break exactly as each single-population query already
              // orders: more-certain (lower RD) first.
              // Re-limited after merge so `?kind=all` returns the true top N across both populations rather than up
              // to 2N — the same pattern `GameResultsStore.recentResultsFor` uses for its two-sided UNION (#289).
              val merged = (botEntries.map(LeaderboardRoutes.botRow) ++ playerEntries.map(LeaderboardRoutes.playerRow))
                .sortBy(row => (-Glicko2.conservativeRating(row.rating, row.rd), row.rd, row.name))
                .take(limit)
              val ranked = merged.zipWithIndex.map((row, index) => row.copy(rank = index + 1))
              Ok(Leaderboard(onCategory.wireName, ranked))

      // The account counterpart of `GET /bots/{team}/{name}`, keyed by the only public handle a person has. Not
      // `/players/{something}`: that shape is taken by the guest-history reads, whose path segment is a bare uuid.
      case GET -> Root / "players" / "by-nickname" / nickname =>
        users match
          // Without persistence there are no accounts at all; the whole route set is unmounted in that mode anyway
          // (see Main), so this only guards a caller that wired the routes without a user store.
          case None        => NotFound()
          case Some(store) =>
            store.byNickname(nickname).flatMap {
              case Some(account) if account.isActive => playerProfile(board, results, ratings, account)
              // A deactivated account is indistinguishable from a missing one, deliberately: the public API must not
              // confirm that a given nickname exists but is blocked.
              case _ => NotFound()
            }

      case GET -> Root / "bots" / team / name =>
        bots
          .ratingOf(team, name)
          .flatMap:
            // `ratingOf` is now only the EXISTENCE check and the source of `onLadder` — the numbers come from the
            // per-category tables below. It stays because a bot with no rated game anywhere still has to 200 with an
            // empty `ratings` list rather than 404.
            case None      => NotFound()
            case Some(bot) =>
              val identity   = RatedIdentity.Bot(team, name)
              val externalId = Principal.Bot(team, name).externalId
              (
                board.totalGamesFor(externalId),
                ratings.categoryRatingsOf(identity),
                board.categoryTalliesFor(externalId),
                results.recentResultsFor(externalId, RecentGamesShown),
                results.opponentsFor(List(externalId))
              ).flatMapN: (totalGames, byCategory, talliesByCategory, recent, opponents) =>
                // The scalar fields' record is a lookup in the split, not a second query for the same number: these
                // reads are sequential (`flatMapN` is FlatMap, not Parallel), and `game_results` has no index serving
                // the two-sided participant predicate, so each one costs a full scan.
                val default = byCategory.getOrElse(RatingCategory.Default, Glicko.Initial)
                val tally   = talliesByCategory.getOrElse(RatingCategory.Default, ResultTally.Empty)
                Ok(
                  BotProfile(
                    team = team,
                    name = name,
                    rating = default.rating,
                    rd = default.deviation,
                    provisional = default.deviation > Glicko2.ProvisionalDeviationThreshold,
                    onLadder = bot.onLadder,
                    games = tally.games,
                    wins = tally.wins,
                    draws = tally.draws,
                    losses = tally.losses,
                    totalGames = totalGames,
                    ratings = LeaderboardRoutes.categoryRatings(byCategory, talliesByCategory),
                    opponents = opponents.map(playerOpponent),
                    recent = recent.map(recentGame(externalId, _))
                  )
                )

  /** The account profile's own reads, scoped to the account's `user:` id ONLY — never its claimed guest ids (see
    * [[PlayerProfile]] for why that scoping is the privacy promise, not an oversight).
    */
  private def playerProfile(
      board: LeaderboardStore,
      results: GameResultsStore,
      ratings: RatingStore,
      account: UserAccount
  ): IO[Response[IO]] =
    val externalId = Principal.User(account.id).externalId
    (
      board.totalGamesFor(externalId),
      ratings.categoryRatingsOf(RatedIdentity.User(account.id)),
      board.categoryTalliesFor(externalId),
      results.recentResultsFor(externalId, RecentGamesShown),
      results.opponentsFor(List(externalId))
    ).flatMapN: (totalGames, byCategory, talliesByCategory, recent, opponents) =>
      val default = byCategory.getOrElse(RatingCategory.Default, Glicko.Initial)
      val tally   = talliesByCategory.getOrElse(RatingCategory.Default, ResultTally.Empty)
      Ok(
        PlayerProfile(
          nickname = account.nickname,
          rating = default.rating,
          rd = default.deviation,
          provisional = default.deviation > Glicko2.ProvisionalDeviationThreshold,
          games = tally.games,
          wins = tally.wins,
          draws = tally.draws,
          losses = tally.losses,
          totalGames = totalGames,
          ratings = LeaderboardRoutes.categoryRatings(byCategory, talliesByCategory),
          opponents = opponents.map(playerOpponent),
          recent = recent.map(recentGame(externalId, _))
        )
      )

  /** Reframe a stored white-POV row from the profiled bot's point of view. */
  private def recentGame(profiledExternalId: String, row: GameResultRow): RecentGame =
    val profiledIsWhite    = row.whiteExternalId == profiledExternalId
    val (seat, opponentId) =
      if profiledIsWhite then (Seat.White, row.blackExternalId) else (Seat.Black, row.whiteExternalId)
    val opponent = Principal.fromBotExternalId(opponentId) match
      case Some(bot) => PublicPlayer.of(bot)
      case None      => PublicPlayer.of(Principal.Guest("")) // any non-bot renders as the anonymous human face
    val result = row.result match
      case Some(0)                      => "draw"
      case Some(1) if profiledIsWhite   => "win"
      case Some(-1) if !profiledIsWhite => "win"
      case Some(_)                      => "loss"
      case None                         => "unknown"
    RecentGame(
      gameId = row.gameId.value,
      seat = seat,
      opponent = opponent,
      result = result,
      rated = row.rated,
      termination = row.termination,
      finishedAt = row.finishedAt
    )
