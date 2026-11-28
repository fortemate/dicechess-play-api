package dicechess.play.server

import dicechess.play.core.{PlayerKind, PublicPlayer, RatingCategory}

import java.util.Locale

/** How the public games listing (`GET /games`) is ordered, filtered and paged (#332).
  *
  * Pure on purpose: the route collects the live games and this object decides which of them a request sees, so every
  * rule here is tested without a server, a room or a clock.
  *
  * A request that names none of the parameters is answered exactly as before the parameters existed: the most active
  * games first, at most [[MaxPageSize]] of them, with `total` counting every live game.
  */
object LiveGamesListing:

  /** The largest page, and the page a request gets when it names no `limit`. Bounds the payload of an unauthenticated,
    * poll-heavy endpoint; the rest of the listing is reached by paging, not by a larger page.
    */
  val MaxPageSize: Int = 50

  /** `?order=`. */
  enum Order:
    /** The game further along first (`version` descending): the listing's original order, and the default. */
    case Activity

    /** The highest sum of both seats' ratings first; see [[byRating]]. */
    case Rating

  /** `?players=`: who sits in the game. */
  enum Participants:
    case All

    /** At least one seat is a human. */
    case Human

    /** Both seats are bots. */
    case Bots

  /** One parsed request. The defaults are the request without parameters. */
  final case class Query(
      order: Order = Order.Activity,
      participants: Participants = Participants.All,
      category: Option[RatingCategory] = None,
      search: Option[String] = None,
      limit: Int = MaxPageSize,
      offset: Int = 0
  )

  /** Query parameters → [[Query]]. An unrecognised value is a 400 rather than a silent fallback, for the reason the
    * leaderboard gives: a typo must not look like a listing that happens to be empty. Parameters this listing does not
    * know are ignored, as they always were.
    */
  def parse(params: Map[String, String]): Either[String, Query] =
    for
      order <- params.get("order") match
        case None | Some("activity") => Right(Order.Activity)
        case Some("rating")          => Right(Order.Rating)
        case Some(other)             => Left(s"order: '$other' must be 'activity' or 'rating'")
      participants <- params.get("players") match
        case None | Some("all") => Right(Participants.All)
        case Some("human")      => Right(Participants.Human)
        case Some("bots")       => Right(Participants.Bots)
        case Some(other)        => Left(s"players: '$other' must be 'all', 'human', or 'bots'")
      category <- params.get("category") match
        case None        => Right(None)
        case Some(value) =>
          RatingCategory
            .fromWireName(value)
            .map(Some(_))
            .toRight(
              s"category: '$value' must be ${RatingCategory.values.map(c => s"'${c.wireName}'").mkString(", ")}"
            )
      limit  <- intParam(params, "limit", MaxPageSize, max = MaxPageSize)
      offset <- intParam(params, "offset", 0, max = Int.MaxValue)
    yield Query(order, participants, category, params.get("q").map(normalise).filter(_.nonEmpty), limit, offset)

  private def intParam(params: Map[String, String], name: String, default: Int, max: Int): Either[String, Int] =
    params.get(name) match
      case None        => Right(default)
      case Some(value) =>
        value.toIntOption
          .filter(n => n >= 0 && n <= max)
          .toRight(
            if max == Int.MaxValue then s"$name: '$value' must be a non-negative integer"
            else s"$name: '$value' must be an integer from 0 to $max"
          )

  /** Case and the spaces around what was typed do not matter to the search. */
  private def normalise(text: String): String = text.trim.toLowerCase(Locale.ROOT)

  private def seats(game: LiveGame): List[PublicPlayer] =
    game.players.toList.flatMap(players => List(players.white, players.black))

  /** The sum the rating order compares. A seat without a rating adds nothing, which is the lowest a seat can count. */
  private[server] def ratingSum(game: LiveGame): Double = seats(game).flatMap(_.rating).sum

  private def ratedSeats(game: LiveGame): Int = seats(game).count(_.rating.isDefined)

  /** The original order, made total: equal versions are ordered by game id, so that pages do not overlap or skip. */
  val byActivity: Ordering[LiveGame] =
    Ordering.by[LiveGame, Long](_.version).reverse.orElseBy(_.gameId)

  /** Highest sum of both seats' ratings first. Equal sums: two rated seats come before one seat that carries the same
    * number alone, then the game further along, then the game id — a total order, the same on every request. It is the
    * play client's own rule for the featured game, so the first game of this order is the game the client features.
    */
  val byRating: Ordering[LiveGame] =
    Ordering
      .by[LiveGame, Double](ratingSum)(using Ordering.Double.TotalOrdering)
      .reverse
      .orElse(Ordering.by[LiveGame, Int](ratedSeats).reverse)
      .orElse(byActivity)

  /** Whether the filters of `query` keep `game`. A game whose players are unknown has neither a human nor two bots. */
  private[server] def matches(game: LiveGame, query: Query): Boolean =
    val players  = seats(game)
    val whoPlays = query.participants match
      case Participants.All   => true
      case Participants.Human => players.exists(_.kind == PlayerKind.Human)
      case Participants.Bots  => players.size == 2 && players.forall(_.kind == PlayerKind.Bot)
    whoPlays &&
    query.category.forall(RatingCategory.of(game.timeControl).contains) &&
    // A guest has no name, so no search finds one: the `players=human` filter is the way to them.
    query.search.forall(wanted => players.exists(_.name.exists(normalise(_).contains(wanted))))

  /** The page `query` asks for out of all the live games. `total` counts the games the filters keep — every live game
    * when there are no filters — and never depends on `order`, `limit` or `offset`, so it is what paging runs against.
    */
  def page(live: List[LiveGame], query: Query): LiveGames =
    val kept    = live.filter(matches(_, query))
    val ordered = kept.sorted(using if query.order == Order.Rating then byRating else byActivity)
    LiveGames(ordered.drop(query.offset).take(query.limit), kept.size)
