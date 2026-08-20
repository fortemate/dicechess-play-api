package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{GameId, Principal, RatingCategory}
import dicechess.play.rating.Glicko
import dicechess.play.store.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Status}

import java.time.Instant

/** The public leaderboard/profile wire (D.2, #103) over stub stores — the SQL behind the seams is covered against real
  * Postgres in `PgGameStoreSuite`; here the subject is the HTTP layer: response shapes (pinned as JSON), rank
  * assignment, provisional visibility policy (hidden on the board, flagged on the profile), POV result mapping, and the
  * anonymisation of human opponents.
  */
class LeaderboardRoutesSuite extends munit.CatsEffectSuite:

  private val alice = Principal.Bot("acme", "alice")
  private val bob   = Principal.Bot("acme", "bob")

  private def stubBots(known: Map[(String, String), BotRating]): BotStore = new UnownedBotStore:
    def authenticate(tokenHash: String): IO[Option[Principal.Bot]]                  = IO.pure(None)
    def rotate(team: String, name: String, newTokenHash: String): IO[Boolean]       = IO.pure(false)
    def ratingOf(team: String, name: String): IO[Option[BotRating]]                 = IO.pure(known.get((team, name)))
    def setOnLadder(team: String, name: String, on: Boolean): IO[Option[BotRating]] = IO.pure(None)
    def onLadderCandidates: IO[List[BotSeatPolicy]]                                 = IO.pure(Nil)
    def seatPolicyOf(team: String, name: String): IO[Option[BotSeatPolicy]]         = IO.pure(None)
    def setMaxConcurrentGames(team: String, name: String, n: Int): IO[Option[BotSeatPolicy]] = IO.pure(None)
    def openToHumans(team: String, name: String, description: Option[String]): IO[Option[BotCatalogState]] =
      IO.pure(None)
    def closeToHumans(team: String, name: String): IO[Option[BotCatalogState]] = IO.pure(None)
    def openToHumansBots: IO[List[Principal.Bot]]                              = IO.pure(Nil)

  private def stubBoard(
      entries: Map[RatingCategory, List[LeaderboardEntry]],
      players: Map[RatingCategory, List[PlayerLeaderboardEntry]],
      // Deliberately independent of `categoryTallies`, which is itself independent of `recent`/`opponents`: a stub
      // that derived one from another would prove nothing about the route actually reading a distinct value (#279).
      // No default here — `app` below is the one caller and always supplies its own default.
      totals: Map[String, Int],
      // The rated record, split by category (#280) — the profiles' scalar W-D-L is the default category's entry of
      // this map, not a second read.
      categoryTallies: Map[String, Map[RatingCategory, ResultTally]]
  ): LeaderboardStore =
    new LeaderboardStore:
      def leaderboard(category: RatingCategory, maxRd: Double, limit: Int): IO[List[LeaderboardEntry]] =
        IO.pure(entries.getOrElse(category, Nil).filter(_.rd <= maxRd).take(limit))
      def playerLeaderboard(
          category: RatingCategory,
          maxRd: Double,
          limit: Int
      ): IO[List[PlayerLeaderboardEntry]] =
        IO.pure(players.getOrElse(category, Nil).filter(_.rd <= maxRd).take(limit))
      def categoryTalliesFor(externalId: String): IO[Map[RatingCategory, ResultTally]] =
        IO.pure(categoryTallies.getOrElse(externalId, Map.empty))
      def totalGamesFor(externalId: String): IO[Int] = IO.pure(totals.getOrElse(externalId, 0))

  /** The per-category rating seam the profiles read (#280). Only `categoryRatingsOf` is reachable from these routes. */
  private def stubRatings(known: Map[RatedIdentity, Map[RatingCategory, Glicko]]): RatingStore = new RatingStore:
    def unappliedRatedGames(limit: Int): IO[List[GameResultRow]]                              = IO.pure(Nil)
    def applyRatingUpdate(gameId: GameId, white: RatingUpdate, black: RatingUpdate): IO[Unit] = IO.unit
    def markRatingApplied(gameId: GameId): IO[Unit]                                           = IO.unit
    def ratingChangeFor(gameId: GameId): IO[Option[GameRatingChange]]                         = IO.pure(None)
    def categoryRatingOf(identity: RatedIdentity, category: RatingCategory): IO[Glicko]       =
      IO.pure(known.getOrElse(identity, Map.empty).getOrElse(category, Glicko.Initial))
    def categoryRatingsOf(identity: RatedIdentity): IO[Map[RatingCategory, Glicko]] =
      IO.pure(known.getOrElse(identity, Map.empty))

  private def stubResults(
      recent: Map[String, List[GameResultRow]],
      opponents: Map[String, List[OpponentAggregateRow]]
  ): GameResultsStore = new GameResultsStore:
    def recentResultsFor(externalId: String, limit: Int): IO[List[GameResultRow]] =
      IO.pure(recent.getOrElse(externalId, Nil).take(limit))
    def finishedRatedSince(since: Instant): IO[List[GameResultRow]] = IO.pure(Nil)
    def playerGamesPage(
        externalIds: List[String],
        before: Option[Instant],
        opponent: Option[OpponentFilter],
        result: Option[PovResultFilter],
        limit: Int
    ): IO[GameResultsStore.Page] = IO.pure(GameResultsStore.Page(Nil, hasMore = false))
    def opponentsFor(externalIds: List[String]): IO[List[OpponentAggregateRow]] =
      IO.pure(externalIds.flatMap(opponents.getOrElse(_, Nil)))

  /** Accounts, for the human half of the board and the public profile (#249). Only the two reads those surfaces make
    * are implemented; anything else would be a test reaching where it should not.
    */
  private def stubUsers(accounts: List[UserAccount], ratings: Map[String, UserRating]): UserStore = new UserStore:
    def upsertOnLogin(p: String, s: String, e: Option[String], n: IO[String]): IO[UserAccount] =
      IO.raiseError(AssertionError("unused"))
    def userById(id: String): IO[Option[UserAccount]]         = IO.pure(accounts.find(_.id == id))
    def byNickname(nickname: String): IO[Option[UserAccount]] =
      IO.pure(accounts.find(_.nickname.equalsIgnoreCase(nickname)))
    def ratingOf(userId: String): IO[Option[UserRating]]                     = IO.pure(ratings.get(userId))
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.raiseError(AssertionError("unused"))
    def linkGuest(userId: String, guestId: String): IO[GuestLink]            = IO.raiseError(AssertionError("unused"))
    def guestsOf(userId: String): IO[List[String]]                           = IO.raiseError(AssertionError("unused"))
    def deleteUser(userId: String): IO[Boolean]                              = IO.raiseError(AssertionError("unused"))

  private def app(
      bots: Map[(String, String), BotRating] = Map.empty,
      entries: List[LeaderboardEntry] = Nil,
      totals: Map[String, Int] = Map.empty,
      recent: Map[String, List[GameResultRow]] = Map.empty,
      opponents: Map[String, List[OpponentAggregateRow]] = Map.empty,
      players: List[PlayerLeaderboardEntry] = Nil,
      accounts: List[UserAccount] = Nil,
      playerRatings: Map[String, UserRating] = Map.empty,
      // `entries`/`players` above are the DEFAULT category's board; these two add the other scales, so a test can
      // assert that `?category=` actually selects one (#280).
      entriesByCategory: Map[RatingCategory, List[LeaderboardEntry]] = Map.empty,
      playersByCategory: Map[RatingCategory, List[PlayerLeaderboardEntry]] = Map.empty,
      ratings: Map[RatedIdentity, Map[RatingCategory, Glicko]] = Map.empty,
      categoryTallies: Map[String, Map[RatingCategory, ResultTally]] = Map.empty
  ): HttpApp[IO] =
    LeaderboardRoutes(
      stubBots(bots),
      stubBoard(
        Map(RatingCategory.Default -> entries) ++ entriesByCategory,
        Map(RatingCategory.Default -> players) ++ playersByCategory,
        totals,
        categoryTallies
      ),
      stubResults(recent, opponents),
      stubRatings(ratings),
      users = Some(stubUsers(accounts, playerRatings))
    ).orNotFound

  private val at = Instant.parse("2026-07-16T12:00:00Z")

  private def row(
      id: String,
      white: String,
      black: String,
      result: Option[Int],
      rated: Boolean = true,
      termination: String = "resign"
  ): GameResultRow =
    GameResultRow(GameId(id), white, black, result, termination, rated, "Fischer(300,3)", "ab12", None, false, at)

  test("GET /leaderboard ranks converged bots and pins the wire shape"):
    val entries = List(
      LeaderboardEntry("acme", "alice", 1720.5, 85.2, onLadder = true, ResultTally(30, 2, 10)),
      LeaderboardEntry("acme", "bob", 1480.0, 100.0, onLadder = false, ResultTally(10, 2, 30)),
      LeaderboardEntry("acme", "fresh", 1500.0, 350.0, onLadder = true, ResultTally.Empty) // provisional: filtered
    )
    val service  = app(entries = entries)
    val expected = parse(
      // `kind` is the one field #249 added. Everything else is byte-identical, and `/leaderboard` with no `?kind=`
      // still answers bots only — the SPA's existing call must keep working.
      """{"category":"blitz","leaders":[
           {"rank":1,"kind":"bot","team":"acme","name":"alice","rating":1720.5,"rd":85.2,"onLadder":true,
            "games":42,"wins":30,"draws":2,"losses":10},
           {"rank":2,"kind":"bot","team":"acme","name":"bob","rating":1480.0,"rd":100.0,"onLadder":false,
            "games":42,"wins":10,"draws":2,"losses":30}
         ]}"""
    ).toOption.get
    service.run(Request[IO](Method.GET, uri"/leaderboard")).flatMap { resp =>
      assertEquals(resp.status, Status.Ok)
      resp.as[Json].map(assertEquals(_, expected, "the public board shape is a contract — pin it"))
    }

  test("GET /leaderboard ranks by the conservative estimate rating − 2·RD, not the raw rating (#169)"):
    val entries = List(
      // The store hands these back streak-first, as a raw-rating sort would; the route's own re-sort must flip them:
      // 1580 − 2·105 = 1370 for the streaker against 1560 − 2·45 = 1470 for the steadier estimate.
      LeaderboardEntry("acme", "streaker", 1580.0, 105.0, onLadder = true, ResultTally(12, 0, 3)),
      LeaderboardEntry("acme", "steady", 1560.0, 45.0, onLadder = true, ResultTally(30, 5, 20))
    )
    app(entries = entries).run(Request[IO](Method.GET, uri"/leaderboard")).flatMap(_.as[Leaderboard]).map { board =>
      assertEquals(board.leaders.map(_.name), List("steady", "streaker"))
      assertEquals(board.leaders.map(_.rank), List(1, 2))
    }

  test("GET /bots/{team}/{name} returns the profile with POV results and an anonymised human opponent"):
    val aliceId = alice.externalId
    val games   = List(
      row("g-1", aliceId, bob.externalId, result = Some(1)), // alice wins as White vs a bot
      row("g-2", bob.externalId, aliceId, result = Some(1)), // bob wins as White: alice loses as Black
      row("g-3", "guest:secret-uuid", aliceId, result = Some(-1), rated = false), // alice wins as Black vs a HUMAN
      row("g-4", aliceId, bob.externalId, result = Some(0), termination = "draw_agreement")
    )
    val opponents = List(
      OpponentAggregateRow(Some(bob.externalId), games = 5, wins = 2, draws = 1, losses = 2, lastPlayedAt = at),
      OpponentAggregateRow(None, games = 4, wins = 3, draws = 0, losses = 1, lastPlayedAt = at)
    )
    val service = app(
      bots = Map(("acme", "alice") -> BotRating(1650.0, 95.0, 0.058, onLadder = true, None)),
      // Two scales, one of them provisional: the profile must report each on its own terms rather than collapsing
      // them (#280). Blitz is the default, so it is also what the scalar fields above describe.
      ratings = Map(
        RatedIdentity.Bot("acme", "alice") -> Map(
          RatingCategory.Blitz -> Glicko(1650.0, 95.0, 0.058),
          RatingCategory.Rapid -> Glicko(1512.0, 180.0, 0.06)
        )
      ),
      categoryTallies = Map(
        aliceId -> Map(RatingCategory.Blitz -> ResultTally(20, 3, 7), RatingCategory.Rapid -> ResultTally(1, 0, 2))
      ),
      // Deliberately not games.size (4) or the tally's 30 — a distinct number pins that this is its OWN read, not
      // one derived from either.
      totals = Map(aliceId -> 34),
      recent = Map(aliceId -> games),
      opponents = Map(aliceId -> opponents)
    )
    val expected = parse(
      s"""{"team":"acme","name":"alice","rating":1650.0,"rd":95.0,"provisional":false,"onLadder":true,
           "games":30,"wins":20,"draws":3,"losses":7,"totalGames":34,
           "ratings":[
             {"category":"blitz","rating":1650.0,"rd":95.0,"provisional":false,
              "games":30,"wins":20,"draws":3,"losses":7},
             {"category":"rapid","rating":1512.0,"rd":180.0,"provisional":true,
              "games":3,"wins":1,"draws":0,"losses":2}
           ],
           "opponents":[
             {"opponent":{"kind":"Bot","name":"acme bob","rating":null},"team":"acme","botName":"bob",
              "games":5,"wins":2,"draws":1,"losses":2,"lastPlayedAt":"2026-07-16T12:00:00Z"},
             {"opponent":{"kind":"Human","name":null,"rating":null},"team":null,"botName":null,
              "games":4,"wins":3,"draws":0,"losses":1,"lastPlayedAt":"2026-07-16T12:00:00Z"}
           ],
           "recent":[
             {"gameId":"g-1","seat":"White","opponent":{"kind":"Bot","name":"acme bob","rating":null},"result":"win",
              "rated":true,"termination":"resign","finishedAt":"2026-07-16T12:00:00Z"},
             {"gameId":"g-2","seat":"Black","opponent":{"kind":"Bot","name":"acme bob","rating":null},"result":"loss",
              "rated":true,"termination":"resign","finishedAt":"2026-07-16T12:00:00Z"},
             {"gameId":"g-3","seat":"Black","opponent":{"kind":"Human","name":null,"rating":null},"result":"win",
              "rated":false,"termination":"resign","finishedAt":"2026-07-16T12:00:00Z"},
             {"gameId":"g-4","seat":"White","opponent":{"kind":"Bot","name":"acme bob","rating":null},"result":"draw",
              "rated":true,"termination":"draw_agreement","finishedAt":"2026-07-16T12:00:00Z"}
           ]}"""
    ).toOption.get
    service.run(Request[IO](Method.GET, uri"/bots/acme/alice")).flatMap { resp =>
      assertEquals(resp.status, Status.Ok)
      resp.as[Json].map { body =>
        assertEquals(body, expected, "no raw external id may appear for the human opponent")
      }
    }

  test("a provisional bot is absent from the board but visible — flagged — on its own profile"):
    val service = app(
      bots = Map(("acme", "newbie") -> BotRating(1500.0, 350.0, 0.06, onLadder = true, None)),
      entries = List(LeaderboardEntry("acme", "newbie", 1500.0, 350.0, onLadder = true, ResultTally.Empty)),
      ratings = Map(RatedIdentity.Bot("acme", "newbie") -> Map(RatingCategory.Blitz -> Glicko.Initial))
    )
    for
      board   <- service.run(Request[IO](Method.GET, uri"/leaderboard")).flatMap(_.as[Json])
      profile <- service.run(Request[IO](Method.GET, uri"/bots/acme/newbie")).flatMap(_.as[Json])
    yield
      assertEquals(board.hcursor.downField("leaders").values.map(_.size), Some(0), "provisional: off the board")
      assertEquals(profile.hcursor.get[Boolean]("provisional").toOption, Some(true), "but flagged on the profile")

  test("?category= selects the scale, defaults to the ladder's, and rejects a name that is not one (#280)"):
    val blitz   = List(LeaderboardEntry("acme", "blitzer", 1700.0, 60.0, onLadder = true, ResultTally(10, 0, 2)))
    val rapid   = List(LeaderboardEntry("acme", "slowpoke", 1650.0, 70.0, onLadder = true, ResultTally(4, 1, 1)))
    val service = app(entries = blitz, entriesByCategory = Map(RatingCategory.Rapid -> rapid))
    for
      byDefault <- service.run(Request[IO](Method.GET, uri"/leaderboard")).flatMap(_.as[Leaderboard])
      explicit  <- service.run(Request[IO](Method.GET, uri"/leaderboard?category=blitz")).flatMap(_.as[Leaderboard])
      asRapid   <- service.run(Request[IO](Method.GET, uri"/leaderboard?category=rapid")).flatMap(_.as[Leaderboard])
      asBullet  <- service.run(Request[IO](Method.GET, uri"/leaderboard?category=bullet")).flatMap(_.as[Leaderboard])
      bad       <- service.run(Request[IO](Method.GET, uri"/leaderboard?category=classical"))
    yield
      assertEquals(byDefault.category, "blitz", "an unqualified request answers on the ladder's own scale")
      assertEquals(byDefault.leaders.map(_.name), List("blitzer"))
      assertEquals(explicit, byDefault, "naming the default must be indistinguishable from omitting it")
      assertEquals(asRapid.category, "rapid")
      assertEquals(asRapid.leaders.map(_.name), List("slowpoke"), "each scale is its own population")
      assertEquals(asBullet.leaders, Nil, "a category nobody has played is empty, not an error")
      // The `kind` precedent: a typo must not be indistinguishable from a speed nobody has played yet.
      assertEquals(bad.status, Status.BadRequest)

  test("?category= also selects which scale the human half of a merged board comes from (#280)"):
    val service = app(
      entries = Nil,
      players = List(PlayerLeaderboardEntry("BlitzOnly", 1600.0, 90.0, ResultTally(9, 1, 5))),
      playersByCategory =
        Map(RatingCategory.Bullet -> List(PlayerLeaderboardEntry("BulletOnly", 1580.0, 95.0, ResultTally(3, 0, 1))))
    )
    for
      blitz  <- service.run(Request[IO](Method.GET, uri"/leaderboard?kind=all")).flatMap(_.as[Leaderboard])
      bullet <- service
        .run(Request[IO](Method.GET, uri"/leaderboard?kind=all&category=bullet"))
        .flatMap(_.as[Leaderboard])
    yield
      assertEquals(blitz.leaders.map(_.name), List("BlitzOnly"))
      assertEquals(bullet.leaders.map(_.name), List("BulletOnly"))

  test("a profile reports a category it has never played by ABSENCE, not by a 1500 nobody measured (#280)"):
    val service = app(
      bots = Map(("acme", "onespeed") -> BotRating(1700.0, 60.0, 0.05, onLadder = true, None)),
      ratings = Map(RatedIdentity.Bot("acme", "onespeed") -> Map(RatingCategory.Blitz -> Glicko(1700.0, 60.0, 0.05))),
      categoryTallies =
        Map(Principal.Bot("acme", "onespeed").externalId -> Map(RatingCategory.Blitz -> ResultTally(8, 0, 2)))
    )
    service.run(Request[IO](Method.GET, uri"/bots/acme/onespeed")).flatMap(_.as[BotProfile]).map { profile =>
      assertEquals(profile.ratings.map(_.category), List("blitz"), "only the scale it has actually been rated on")
      assertEquals(profile.rating, 1700.0, "the scalars describe the default category")
      assert(!profile.provisional)
    }

  test("a bot with no rated game anywhere is still a 200 with an empty ratings list, not a 404 (#280)"):
    val service = app(bots = Map(("acme", "unplayed") -> BotRating(1500.0, 350.0, 0.06, onLadder = false, None)))
    service.run(Request[IO](Method.GET, uri"/bots/acme/unplayed")).flatMap { resp =>
      assertEquals(resp.status, Status.Ok, "registration is what makes a bot exist, not having played")
      resp.as[BotProfile].map { profile =>
        assertEquals(profile.ratings, Nil)
        assertEquals(profile.rating, 1500.0, "the default-category scalar falls back to the fresh state")
        assert(profile.provisional, "which is provisional, because nothing has been measured")
      }
    }

  test("GET /bots/{team}/{name} is 404 for an unregistered identity"):
    app().run(Request[IO](Method.GET, uri"/bots/ghost/nobody")).map(resp => assertEquals(resp.status, Status.NotFound))

  // ── Human ratings on the public surfaces (#249) ──────────────────────────────

  private val playerEntries = List(
    PlayerLeaderboardEntry("SwiftRook7", rating = 1600.0, rd = 90.0, tally = ResultTally(9, 1, 5)),
    PlayerLeaderboardEntry("Provisional1", rating = 1900.0, rd = 300.0, tally = ResultTally(1, 0, 0))
  )

  test("?kind=players lists accounts with no team, and ?kind=all ranks both populations on the one shared scale"):
    val botEntries = List(
      LeaderboardEntry("acme", "alice", 1720.5, 85.2, onLadder = true, ResultTally(30, 2, 10)),
      LeaderboardEntry("acme", "bob", 1480.0, 100.0, onLadder = false, ResultTally(10, 2, 30))
    )
    val service = app(entries = botEntries, players = playerEntries)
    for
      players <- service.run(Request[IO](Method.GET, uri"/leaderboard?kind=players")).flatMap(_.as[Leaderboard])
      all     <- service.run(Request[IO](Method.GET, uri"/leaderboard?kind=all")).flatMap(_.as[Leaderboard])
      bad     <- service.run(Request[IO](Method.GET, uri"/leaderboard?kind=humans"))
    yield
      assertEquals(players.leaders.map(_.name), List("SwiftRook7"), "a provisional account is hidden, like a bot")
      assertEquals(players.leaders.map(_.kind), List("player"))
      assertEquals(players.leaders.flatMap(_.team), Nil, "a person has no team")
      assert(!players.leaders.exists(_.onLadder), "there is no ladder for people")
      // Conservative estimates: 1550.1 (alice) > 1420 (the account) > 1280 (bob): interleaved, ONE scale.
      assertEquals(all.leaders.map(_.name), List("alice", "SwiftRook7", "bob"))
      assertEquals(all.leaders.map(_.rank), List(1, 2, 3), "rank is assigned across both populations, not per kind")
      assertEquals(bad.status, Status.BadRequest, "an unrecognised kind must not silently read as an empty board")

  test("GET /players/by-nickname/{nickname} mirrors the bot profile and leaks no private field"):
    val account = UserAccount("0197f0a0-0000-7000-8000-000000000249", "SwiftRook7", at, Some(at), isActive = true)
    val me      = Principal.User(account.id).externalId
    val service = app(
      totals = Map(me -> 19), // deliberately distinct from the rated tally's 15, same reasoning as the bot profile
      recent = Map(me -> List(row("g-p1", me, alice.externalId, result = Some(1)))),
      opponents = Map(me -> List(OpponentAggregateRow(Some(alice.externalId), 3, 2, 0, 1, at))),
      accounts = List(account),
      playerRatings = Map(account.id -> UserRating(1600.0, 90.0, 0.06)),
      ratings = Map(RatedIdentity.User(account.id) -> Map(RatingCategory.Blitz -> Glicko(1600.0, 90.0, 0.06))),
      categoryTallies = Map(me -> Map(RatingCategory.Blitz -> ResultTally(9, 1, 5)))
    )
    for
      resp    <- service.run(Request[IO](Method.GET, uri"/players/by-nickname/swiftrook7"))
      body    <- resp.as[Json]
      profile <- service
        .run(Request[IO](Method.GET, uri"/players/by-nickname/SwiftRook7"))
        .flatMap(_.as[PlayerProfile])
      missing <- service.run(Request[IO](Method.GET, uri"/players/by-nickname/NoSuchPlayer"))
    yield
      assertEquals(resp.status, Status.Ok, "the lookup is case-insensitive, like the uniqueness rule behind it")
      assertEquals(profile.nickname, "SwiftRook7")
      assertEquals(profile.rating, 1600.0)
      assertEquals(profile.provisional, false)
      assertEquals((profile.games, profile.wins, profile.draws, profile.losses), (15, 9, 1, 5))
      assertEquals(profile.totalGames, 19, "totalGames must be its own read, not derived from the rated tally")
      assertEquals(profile.recent.map(_.result), List("win"))
      assertEquals(profile.opponents.flatMap(_.botName), List("alice"))
      // The privacy promise is about what is ABSENT, so assert on the keys, not just the happy path.
      val keys = body.hcursor.keys.map(_.toList).getOrElse(Nil)
      assert(!keys.contains("id"), s"the account uuid must never reach a public wire type: $keys")
      assert(!keys.contains("email"), s"email is owner-only: $keys")
      assert(!keys.contains("guests"), s"the claimed-guest set is owner-only (#236): $keys")
      assert(!body.noSpaces.contains(account.id), "not even embedded in another field")
      assertEquals(missing.status, Status.NotFound)

  test("a deactivated account is indistinguishable from a missing one"):
    val blocked = UserAccount("0197f0a0-0000-7000-8000-00000000024b", "GoneNick", at, Some(at), isActive = false)
    app(accounts = List(blocked))
      .run(Request[IO](Method.GET, uri"/players/by-nickname/GoneNick"))
      .map(resp => assertEquals(resp.status, Status.NotFound, "the API must not confirm a blocked nickname exists"))

  test("?kind=all re-limits the merged board to MaxBoardSize, returning the true top N across populations (#289)"):
    // Produce MaxBoardSize + 1 entries per population: the merged board must be MaxBoardSize, not 2*(MaxBoardSize+1).
    // Use very high ratings for bots and slightly lower for players, so the top board is interleaved.
    val n     = LeaderboardStore.MaxBoardSize + 1
    val botEs =
      (1 to n).toList.map(i => LeaderboardEntry("x", s"b$i", 2000.0 - i, 50.0, onLadder = true, ResultTally.Empty))
    val playerEs = (1 to n).toList.map(i => PlayerLeaderboardEntry(s"p$i", 2000.5 - i, 50.0, ResultTally.Empty))
    val service  = app(entries = botEs, players = playerEs)
    service.run(Request[IO](Method.GET, uri"/leaderboard?kind=all")).flatMap(_.as[Leaderboard]).map { board =>
      assertEquals(
        board.leaders.length,
        LeaderboardStore.MaxBoardSize,
        s"merged ?kind=all must be capped at MaxBoardSize (${LeaderboardStore.MaxBoardSize}), not 2*(MaxBoardSize+1)"
      )
      assertEquals(
        board.leaders.map(_.rank),
        (1 to LeaderboardStore.MaxBoardSize).toList,
        "ranks are contiguous from 1"
      )
    }
