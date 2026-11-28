package dicechess.play.server

import dicechess.play.core.*
import dicechess.play.server.LiveGamesListing.{Order, Participants, Query}

/** The rules of the public games listing (#332), on plain values: the order, each filter, the search, the paging and
  * the parsing of the request. The route itself is covered in [[PlayRoutesSuite]].
  */
class LiveGamesListingSuite extends munit.FunSuite:

  private val Blitz  = TimeControl.Fischer(300, 3)
  private val Bullet = TimeControl.Fischer(60, 1)
  private val Rapid  = TimeControl.Fischer(600, 10)

  private def bot(name: String, rating: Option[Double] = None): PublicPlayer =
    PublicPlayer(PlayerKind.Bot, Some(name), rating)
  private def human(name: Option[String] = None, rating: Option[Double] = None): PublicPlayer =
    PublicPlayer(PlayerKind.Human, name, rating)

  private def game(
      id: String,
      white: PublicPlayer,
      black: PublicPlayer,
      version: Long = 0,
      timeControl: TimeControl = Blitz
  ): LiveGame =
    LiveGame(id, Some(Players(white, black)), timeControl, Seat.White, dicePending = false, None, version, "dfen")

  private def ids(listing: LiveGames): List[String] = listing.games.map(_.gameId)

  private def parsed(params: (String, String)*): Query =
    LiveGamesListing.parse(params.toMap).fold(error => fail(s"unexpected 400: $error"), identity)

  // ── A request without parameters ────────────────────────────────────────────────────────────

  test("no parameters parse to the defaults: activity order, no filters, the first full page"):
    assertEquals(parsed(), Query(Order.Activity, Participants.All, None, None, 50, 0))
    assertEquals(parsed(), Query())

  test("the default request answers as before the parameters existed: version descending, capped, uncapped total"):
    val live = (1 to 60).toList.map(n => game(s"g$n", human(), bot("b", Some(n.toDouble)), version = (n * 7) % 61))
    // The listing before #332, verbatim.
    val before = LiveGames(live.sortBy(-_.version).take(50), live.size)
    assertEquals(LiveGamesListing.page(live, Query()), before)
    // Parameters this listing does not know change nothing, as they never did.
    assertEquals(LiveGamesListing.page(live, parsed("cache" -> "1")), before)

  test("the default request does not look at ratings"):
    val live = List(
      game("strong", bot("a", Some(2000)), bot("b", Some(2000)), version = 1),
      game("busy", human(), human(), version = 9)
    )
    assertEquals(ids(LiveGamesListing.page(live, Query())), List("busy", "strong"))

  // ── Order by rating ─────────────────────────────────────────────────────────────────────────

  test("order=rating puts the highest sum of both seats' ratings first"):
    val live = List(
      game("mid", bot("a", Some(1500)), bot("b", Some(1500))),
      game("top", bot("a", Some(1900)), bot("b", Some(1200))),
      game("low", bot("a", Some(1000)), bot("b", Some(1000)))
    )
    assertEquals(ids(LiveGamesListing.page(live, Query(order = Order.Rating))), List("top", "mid", "low"))

  test("a seat without a rating counts lowest: it adds nothing to the sum"):
    val live = List(
      game("unrated", human(), human(), version = 99),
      game("one-seat", bot("a", Some(1700)), human()),
      game("both-seats", bot("a", Some(900)), bot("b", Some(900))),
      LiveGame("no-players", None, Blitz, Seat.White, dicePending = false, None, 100, "dfen")
    )
    assertEquals(
      ids(LiveGamesListing.page(live, Query(order = Order.Rating))),
      List("both-seats", "one-seat", "no-players", "unrated")
    )
    assertEquals(LiveGamesListing.ratingSum(live(1)), 1700.0)
    assertEquals(LiveGamesListing.ratingSum(live(3)), 0.0)

  test("equal sums: two rated seats before one, then the game further along, then the game id"):
    val live = List(
      game("d", bot("a", Some(1500)), bot("b", Some(1500)), version = 3),
      game("c", bot("a", Some(1500)), bot("b", Some(1500)), version = 3),
      game("alone", bot("a", Some(3000)), human(), version = 50),
      game("further", bot("a", Some(1400)), bot("b", Some(1600)), version = 8)
    )
    val ordered = ids(LiveGamesListing.page(live, Query(order = Order.Rating)))
    assertEquals(ordered, List("further", "c", "d", "alone"))
    // Total and stable: the order does not depend on the order the games arrive in.
    live.permutations.foreach: shuffled =>
      assertEquals(ids(LiveGamesListing.page(shuffled, Query(order = Order.Rating))), ordered)

  test("order=activity breaks equal versions by game id, so the order is total"):
    val live = List(game("b", human(), human(), 4), game("a", human(), human(), 4), game("c", human(), human(), 5))
    live.permutations.foreach: shuffled =>
      assertEquals(ids(LiveGamesListing.page(shuffled, Query())), List("c", "a", "b"))

  // ── Filters ─────────────────────────────────────────────────────────────────────────────────

  private val mixed = List(
    game("bots", bot("house alpha", Some(1600)), bot("house beta", Some(1500)), version = 4, Bullet),
    game("human-bot", human(Some("Magnus"), Some(1800)), bot("house alpha", Some(1600)), version = 3, Blitz),
    game("humans", human(Some("Anna")), human(), version = 2, Rapid),
    game("guests", human(), human(), version = 1, TimeControl.Unlimited),
    LiveGame("no-players", None, TimeControl.PerMove(30), Seat.White, dicePending = false, None, 0, "dfen")
  )

  test("players=human keeps the games with at least one human seat"):
    val listing = LiveGamesListing.page(mixed, Query(participants = Participants.Human))
    assertEquals(ids(listing), List("human-bot", "humans", "guests"))
    assertEquals(listing.total, 3)

  test("players=bots keeps the games where both seats are bots"):
    val listing = LiveGamesListing.page(mixed, Query(participants = Participants.Bots))
    assertEquals(ids(listing), List("bots"))
    assertEquals(listing.total, 1)

  test("a game whose players are unknown has neither a human nor two bots"):
    val unknown = mixed.filter(_.gameId == "no-players")
    assertEquals(LiveGamesListing.page(unknown, Query(participants = Participants.Human)).total, 0)
    assertEquals(LiveGamesListing.page(unknown, Query(participants = Participants.Bots)).total, 0)
    assertEquals(LiveGamesListing.page(unknown, Query()).total, 1)

  test("category keeps the games whose time control counts on that scale"):
    def of(category: RatingCategory) = ids(LiveGamesListing.page(mixed, Query(category = Some(category))))
    assertEquals(of(RatingCategory.Bullet), List("bots"))
    assertEquals(of(RatingCategory.Blitz), List("human-bot"))
    assertEquals(of(RatingCategory.Rapid), List("humans"))

  test("a control that counts on no scale (Unlimited, PerMove) matches no category"):
    val uncategorised = mixed.filter(g => RatingCategory.of(g.timeControl).isEmpty)
    assertEquals(uncategorised.map(_.gameId), List("guests", "no-players"))
    RatingCategory.values.foreach: category =>
      assertEquals(LiveGamesListing.page(uncategorised, Query(category = Some(category))).total, 0)

  // ── Search ──────────────────────────────────────────────────────────────────────────────────

  test("q finds a part of either seat's name, ignoring case and the spaces around it"):
    assertEquals(ids(LiveGamesListing.page(mixed, parsed("q" -> "  ALPHA "))), List("bots", "human-bot"))
    assertEquals(ids(LiveGamesListing.page(mixed, parsed("q" -> "ann"))), List("humans"))
    assertEquals(ids(LiveGamesListing.page(mixed, parsed("q" -> "beta"))), List("bots"))
    assertEquals(LiveGamesListing.page(mixed, parsed("q" -> "nobody")), LiveGames(Nil, 0))

  test("an empty or blank q is no search at all"):
    assertEquals(parsed("q" -> "").search, None)
    assertEquals(parsed("q" -> "   ").search, None)
    assertEquals(LiveGamesListing.page(mixed, parsed("q" -> " ")).total, mixed.size)

  test("a guest has no name, so no search finds a game of guests"):
    assertEquals(ids(LiveGamesListing.page(mixed, parsed("q" -> "a"))), List("bots", "human-bot", "humans"))

  test("filters, search and order combine; total counts what the filters keep"):
    val query   = parsed("players" -> "human", "category" -> "blitz", "q" -> "house", "order" -> "rating")
    val listing = LiveGamesListing.page(mixed, query)
    assertEquals(ids(listing), List("human-bot"))
    assertEquals(listing.total, 1)

  // ── Paging ──────────────────────────────────────────────────────────────────────────────────

  private val many = (1 to 120).toList.map(n => game(f"g$n%03d", bot("a", Some(n.toDouble)), human(), version = n))

  test("limit and offset page through the whole listing without overlap or gap"):
    val byRating = Query(order = Order.Rating)
    val pages    = List(0, 50, 100, 150).map(offset => LiveGamesListing.page(many, byRating.copy(offset = offset)))
    assertEquals(pages.map(_.games.size), List(50, 50, 20, 0))
    assert(pages.forall(_.total == 120), "total is the whole listing on every page")
    assertEquals(pages.flatMap(ids), (120 to 1 by -1).toList.map(n => f"g$n%03d"))
    assertEquals(pages.flatMap(ids).distinct.size, 120)

  test("paging boundaries: the last game, the first offset past the end, a page of nothing"):
    assertEquals(ids(LiveGamesListing.page(many, Query(limit = 1, offset = 119))), List("g001"))
    assertEquals(LiveGamesListing.page(many, Query(limit = 1, offset = 120)), LiveGames(Nil, 120))
    assertEquals(LiveGamesListing.page(many, Query(limit = 50, offset = Int.MaxValue)), LiveGames(Nil, 120))
    // limit=0 asks for the count alone.
    assertEquals(LiveGamesListing.page(many, Query(limit = 0)), LiveGames(Nil, 120))
    assertEquals(ids(LiveGamesListing.page(many, Query(limit = 2))), List("g120", "g119"))

  test("total under a filter counts the games the filter keeps, not the page and not the whole listing"):
    val live    = many ++ List(game("bots", bot("a"), bot("b")))
    val listing = LiveGamesListing.page(live, Query(participants = Participants.Human, limit = 10, offset = 115))
    assertEquals(listing.total, 120)
    assertEquals(listing.games.size, 5)

  // ── Parsing ─────────────────────────────────────────────────────────────────────────────────

  test("every parameter parses, and the explicit defaults equal the absent ones"):
    assertEquals(
      parsed(
        "order"    -> "rating",
        "players"  -> "bots",
        "category" -> "rapid",
        "q"        -> " Bob ",
        "limit"    -> "25",
        "offset"   -> "75"
      ),
      Query(Order.Rating, Participants.Bots, Some(RatingCategory.Rapid), Some("bob"), 25, 75)
    )
    assertEquals(parsed("order" -> "activity", "players" -> "all", "limit" -> "50", "offset" -> "0"), Query())
    assertEquals(parsed("players" -> "human").participants, Participants.Human)
    assertEquals(parsed("limit" -> "0").limit, 0)

  test("an unrecognised or out-of-range value is refused, naming the parameter"):
    val refused = List(
      "order"    -> "elo",
      "players"  -> "humans",
      "category" -> "classical",
      "category" -> "",
      "limit"    -> "51",
      "limit"    -> "-1",
      "limit"    -> "many",
      "offset"   -> "-1",
      "offset"   -> "1.5",
      "offset"   -> "99999999999"
    )
    refused.foreach: (name, value) =>
      LiveGamesListing.parse(Map(name -> value)) match
        case Left(error) => assert(error.startsWith(s"$name: '$value' must be"), error)
        case Right(q)    => fail(s"$name=$value was accepted as $q")
