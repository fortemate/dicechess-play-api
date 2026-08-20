package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{GameId, Principal}
import dicechess.play.store.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Status, Uri}

import java.time.Instant

/** The "my games" wire (#151, #173) and "my opponents" wire (#174) over a stub store — the SQL itself is covered
  * against real Postgres in `PgGameStoreSuite`; here the subject is the HTTP layer: identity validation, query-param
  * parsing/validation (`before`/`vs`/`result`/`limit`), response shape (pinned as JSON), POV result mapping, opponent
  * anonymisation, and the no-enumeration-signal privacy property.
  */
class PlayerRoutesSuite extends munit.CatsEffectSuite:

  private val bob   = Principal.Bot("acme", "bob")
  private val alice = Principal.Bot("acme", "alice")

  /** A faithful in-memory re-implementation of `playerGamesPage`'s filtering/pagination semantics (participant match,
    * `before`/`vs`/`result`, `limit`+`hasMore`) — NOT a passthrough — so tests read as ordinary fixture-data assertions
    * instead of argument-capture plumbing, and so a POV-logic mistake shared between this stub and the real route would
    * show up as a disagreement with `PgGameStoreSuite` rather than passing here by coincidence.
    */
  private def stubResults(
      recent: Map[String, List[GameResultRow]],
      opponents: Map[String, List[OpponentAggregateRow]]
  ): GameResultsStore = new GameResultsStore:
    def recentResultsFor(externalId: String, limit: Int): IO[List[GameResultRow]] = IO.pure(Nil)
    def finishedRatedSince(since: Instant): IO[List[GameResultRow]]               = IO.pure(Nil)

    def playerGamesPage(
        externalIds: List[String],
        before: Option[Instant],
        opponent: Option[OpponentFilter],
        result: Option[PovResultFilter],
        limit: Int
    ): IO[GameResultsStore.Page] =
      val filtered = externalIds.flatMap(recent.getOrElse(_, Nil)).distinct.filter { row =>
        val requesterIsWhite = externalIds.contains(row.whiteExternalId)
        val opponentId       = if requesterIsWhite then row.blackExternalId else row.whiteExternalId
        val beforeOk         = before.forall(row.finishedAt.isBefore)
        val opponentOk       = opponent.forall:
          case OpponentFilter.Bot(id)   => opponentId == id
          case OpponentFilter.HumanOnly => !opponentId.startsWith("bot:team:")
        val pov: Option[PovResultFilter] = row.result match
          case Some(0)                       => Some(PovResultFilter.Draw)
          case Some(1) if requesterIsWhite   => Some(PovResultFilter.Win)
          case Some(-1) if !requesterIsWhite => Some(PovResultFilter.Win)
          case Some(_)                       => Some(PovResultFilter.Loss)
          case None                          => None
        val resultOk = result.forall(pov.contains)
        beforeOk && opponentOk && resultOk
      }
      val sorted = filtered.sortBy(_.finishedAt).reverse
      IO.pure(GameResultsStore.Page(sorted.take(limit), hasMore = sorted.size > limit))

    def opponentsFor(externalIds: List[String]): IO[List[OpponentAggregateRow]] =
      IO.pure(externalIds.flatMap(opponents.getOrElse(_, Nil)))

  /** A `UserStore` that resolves the given account ids to nicknames and nothing else. `nicknamesByExternalId` is the
    * only method these routes touch; the rest exist to satisfy the trait and fail loudly if a route ever grows a
    * dependency on them without a test noticing.
    */
  private def stubUsers(nicknames: Map[String, String]): UserStore = new UserStore:
    override def nicknamesByExternalId(externalIds: List[String]): IO[Map[String, String]] =
      IO.pure(nicknames.view.filterKeys(externalIds.contains).toMap)
    def upsertOnLogin(p: String, s: String, e: Option[String], n: IO[String]): IO[UserAccount] =
      IO.raiseError(AssertionError("unused"))
    def userById(id: String): IO[Option[UserAccount]]                        = IO.pure(None)
    def byNickname(nickname: String): IO[Option[UserAccount]]                = IO.pure(None)
    def ratingOf(userId: String): IO[Option[UserRating]]                     = IO.pure(None)
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.raiseError(AssertionError("unused"))
    def linkGuest(userId: String, guestId: String): IO[GuestLink]            = IO.raiseError(AssertionError("unused"))
    def guestsOf(userId: String): IO[List[String]]                           = IO.pure(Nil)
    def deleteUser(userId: String): IO[Boolean]                              = IO.raiseError(AssertionError("unused"))

  private def app(
      recent: Map[String, List[GameResultRow]] = Map.empty,
      opponents: Map[String, List[OpponentAggregateRow]] = Map.empty,
      nicknames: Map[String, String] = Map.empty
  ): HttpApp[IO] =
    PlayerRoutes(stubResults(recent, opponents), stubUsers(nicknames)).orNotFound

  private val at = Instant.parse("2026-07-16T12:00:00Z")

  private def row(
      id: String,
      white: String,
      black: String,
      result: Option[Int],
      rated: Boolean = false,
      termination: String = "resign",
      timeControl: String = "Fischer(300,3)",
      finishedAt: Instant = at
  ): GameResultRow =
    GameResultRow(GameId(id), white, black, result, termination, rated, timeControl, "ab12", None, false, finishedAt)

  private def get(path: String)(service: HttpApp[IO]) =
    service.run(Request[IO](Method.GET, Uri.unsafeFromString(path)))

  test("GET /players/{guestId}/games returns the requester's recent games with POV results and anonymised opponents"):
    val guestId  = "0197f0a0-0000-7000-8000-000000000001"
    val guestExt = s"guest:$guestId"
    // Distinct, decreasing timestamps — g-1 newest — so this test also pins the newest-first ordering, not just
    // the POV/anonymisation shape (a shared timestamp across all rows would leave ordering unverified).
    val games = List(
      row("g-1", guestExt, bob.externalId, result = Some(1), finishedAt = at.plusSeconds(3)), // guest wins as White
      row("g-2", bob.externalId, guestExt, result = Some(1), finishedAt = at.plusSeconds(2)), // guest loses as Black
      row("g-3", "guest:other-uuid", guestExt, result = Some(-1), finishedAt = at.plusSeconds(1)), // wins vs a HUMAN
      row("g-4", guestExt, bob.externalId, result = Some(0), termination = "draw_agreement", finishedAt = at)
    )
    val service  = app(recent = Map(guestExt -> games))
    val expected = parse(
      s"""{"games":[
             {"gameId":"g-1","seat":"White","opponent":{"kind":"Bot","name":"acme bob","rating":null},"result":"win",
              "rated":false,"termination":"resign","timeControl":"Fischer(300,3)","finishedAt":"2026-07-16T12:00:03Z"},
             {"gameId":"g-2","seat":"Black","opponent":{"kind":"Bot","name":"acme bob","rating":null},"result":"loss",
              "rated":false,"termination":"resign","timeControl":"Fischer(300,3)","finishedAt":"2026-07-16T12:00:02Z"},
             {"gameId":"g-3","seat":"Black","opponent":{"kind":"Human","name":null,"rating":null},"result":"win",
              "rated":false,"termination":"resign","timeControl":"Fischer(300,3)","finishedAt":"2026-07-16T12:00:01Z"},
             {"gameId":"g-4","seat":"White","opponent":{"kind":"Bot","name":"acme bob","rating":null},"result":"draw",
              "rated":false,"termination":"draw_agreement","timeControl":"Fischer(300,3)",
              "finishedAt":"2026-07-16T12:00:00Z"}
           ],"hasMore":false}"""
    ).toOption.get
    get(s"/players/$guestId/games")(service).flatMap: resp =>
      assertEquals(resp.status, Status.Ok)
      resp
        .as[Json]
        .map: body =>
          assertEquals(body, expected, "no raw external id may appear for the human opponent")

  test("GET /players/{guestId}/games is 400 for a malformed guest id"):
    get("/players/not-a-uuid/games")(app())
      .map(resp => assertEquals(resp.status, Status.BadRequest))

  test("GET /players/{guestId}/games is 400, not 404, for a non-numeric `limit`"):
    val guestId = "0197f0a0-0000-7000-8000-000000000004"
    get(s"/players/$guestId/games?limit=abc")(app())
      .map(resp => assertEquals(resp.status, Status.BadRequest))

  test("GET /players/{guestId}/games is 200 with an empty list for a well-formed but unknown guest id"):
    // Deliberate: an unknown-but-valid uuid must be indistinguishable from a known one with zero games — this
    // endpoint leaks no signal about which guest ids have ever played.
    val guestId = "0197f0a0-0000-7000-8000-000000000002"
    get(s"/players/$guestId/games")(app())
      .flatMap: resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map(body => assertEquals(body, parse("""{"games":[],"hasMore":false}""").toOption.get))

  test("GET /players/{guestId}/games clamps an over-large `limit` and reports `hasMore`"):
    val guestId  = "0197f0a0-0000-7000-8000-000000000003"
    val guestExt = s"guest:$guestId"
    val games    = (1 to 250).map(n => row(s"g-$n", guestExt, bob.externalId, result = Some(0))).toList
    get(s"/players/$guestId/games?limit=99999")(app(recent = Map(guestExt -> games)))
      .flatMap: resp =>
        assertEquals(resp.status, Status.Ok)
        resp
          .as[Json]
          .map: body =>
            assertEquals(body.hcursor.downField("games").values.map(_.size), Some(200), "clamped to the hard cap")
            assertEquals(body.hcursor.get[Boolean]("hasMore").toOption, Some(true), "250 games past a 200-cap")

  test("GET /players/{guestId}/games reports `hasMore: false` when every game fits in the page"):
    val guestId  = "0197f0a0-0000-7000-8000-000000000005"
    val guestExt = s"guest:$guestId"
    val games    = List(row("g-1", guestExt, bob.externalId, result = Some(0)))
    get(s"/players/$guestId/games")(app(recent = Map(guestExt -> games)))
      .flatMap: resp =>
        resp.as[Json].map(body => assertEquals(body.hcursor.get[Boolean]("hasMore").toOption, Some(false)))

  test("GET /players/{guestId}/games `before` keeps only strictly older games, and rejects a malformed timestamp"):
    val guestId  = "0197f0a0-0000-7000-8000-000000000006"
    val guestExt = s"guest:$guestId"
    val older    = Instant.parse("2026-07-15T00:00:00Z")
    val newer    = Instant.parse("2026-07-17T00:00:00Z")
    val games    = List(
      row("g-old", guestExt, bob.externalId, result = Some(0), finishedAt = older),
      row("g-at", guestExt, bob.externalId, result = Some(0), finishedAt = at),
      row("g-new", guestExt, bob.externalId, result = Some(0), finishedAt = newer)
    )
    val service = app(recent = Map(guestExt -> games))
    for
      onlyOlder <- get(s"/players/$guestId/games?before=2026-07-16T12:00:00Z")(service).flatMap(_.as[Json])
      badBefore <- get(s"/players/$guestId/games?before=not-a-timestamp")(service)
    yield
      assertEquals(
        onlyOlder.hcursor.downField("games").values.map(_.flatMap(_.hcursor.get[String]("gameId").toOption)),
        Some(Vector("g-old")),
        "strictly older than `before` only"
      )
      assertEquals(badBefore.status, Status.BadRequest)

  test("GET /players/{guestId}/games `vs=<team>/<name>` keeps only games against that bot"):
    val guestId  = "0197f0a0-0000-7000-8000-000000000007"
    val guestExt = s"guest:$guestId"
    val games    = List(
      row("g-bob", guestExt, bob.externalId, result = Some(0)),
      row("g-alice", guestExt, alice.externalId, result = Some(0)),
      row("g-human", guestExt, "guest:other-uuid", result = Some(0))
    )
    get(s"/players/$guestId/games?vs=acme/bob")(app(recent = Map(guestExt -> games)))
      .flatMap(_.as[Json])
      .map: body =>
        assertEquals(
          body.hcursor.downField("games").values.map(_.flatMap(_.hcursor.get[String]("gameId").toOption)),
          Some(Vector("g-bob"))
        )

  test("GET /players/{guestId}/games `vs=human` keeps only games against non-bot opponents"):
    val guestId  = "0197f0a0-0000-7000-8000-000000000008"
    val guestExt = s"guest:$guestId"
    val games    = List(
      row("g-bob", guestExt, bob.externalId, result = Some(0)),
      row("g-human", guestExt, "guest:other-uuid", result = Some(0))
    )
    get(s"/players/$guestId/games?vs=human")(app(recent = Map(guestExt -> games)))
      .flatMap(_.as[Json])
      .map: body =>
        assertEquals(
          body.hcursor.downField("games").values.map(_.flatMap(_.hcursor.get[String]("gameId").toOption)),
          Some(Vector("g-human"))
        )

  test("GET /players/{guestId}/games is 400 for a `vs` that is neither 'human' nor '<team>/<name>'"):
    val guestId = "0197f0a0-0000-7000-8000-000000000009"
    get(s"/players/$guestId/games?vs=not-a-bot-key")(app())
      .map(resp => assertEquals(resp.status, Status.BadRequest))

  test("GET /players/{guestId}/games `result` filters by the requester's own POV, and rejects an unknown value"):
    val guestId  = "0197f0a0-0000-7000-8000-000000000010"
    val guestExt = s"guest:$guestId"
    val games    = List(
      row("g-win-white", guestExt, bob.externalId, result = Some(1)),  // guest is White and wins
      row("g-win-black", bob.externalId, guestExt, result = Some(-1)), // guest is Black and wins
      row("g-loss", guestExt, bob.externalId, result = Some(-1)),      // guest is White and loses
      row("g-draw", guestExt, bob.externalId, result = Some(0))
    )
    val service = app(recent = Map(guestExt -> games))
    for
      wins    <- get(s"/players/$guestId/games?result=win")(service).flatMap(_.as[Json])
      badKind <- get(s"/players/$guestId/games?result=nonsense")(service)
    yield
      assertEquals(
        wins.hcursor.downField("games").values.map(_.flatMap(_.hcursor.get[String]("gameId").toOption).toSet),
        Some(Set("g-win-white", "g-win-black"))
      )
      assertEquals(badKind.status, Status.BadRequest)

  test("GET /players/{guestId}/opponents itemises bots and collapses every human opponent into one row"):
    val guestId  = "0197f0a0-0000-7000-8000-000000000011"
    val guestExt = s"guest:$guestId"
    val rows     = List(
      OpponentAggregateRow(Some(bob.externalId), games = 30, wins = 12, draws = 3, losses = 15, lastPlayedAt = at),
      OpponentAggregateRow(None, games = 5, wins = 2, draws = 0, losses = 3, lastPlayedAt = at)
    )
    get(s"/players/$guestId/opponents")(app(opponents = Map(guestExt -> rows)))
      .flatMap(_.as[Json])
      .map: body =>
        val expected = parse(s"""{"opponents":[
          {"opponent":{"kind":"Bot","name":"acme bob","rating":null},"team":"acme","botName":"bob",
           "games":30,"wins":12,"draws":3,"losses":15,"lastPlayedAt":"2026-07-16T12:00:00Z"},
          {"opponent":{"kind":"Human","name":null,"rating":null},"team":null,"botName":null,
           "games":5,"wins":2,"draws":0,"losses":3,"lastPlayedAt":"2026-07-16T12:00:00Z"}
        ]}""").toOption.get
        assertEquals(body, expected)

  test("GET /players/{guestId}/opponents is 400 for a malformed guest id"):
    get("/players/not-a-uuid/opponents")(app())
      .map(resp => assertEquals(resp.status, Status.BadRequest))

  test("GET /players/{guestId}/opponents is 200 with an empty list for a well-formed but unknown guest id"):
    val guestId = "0197f0a0-0000-7000-8000-000000000012"
    get(s"/players/$guestId/opponents")(app())
      .flatMap: resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map(body => assertEquals(body, parse("""{"opponents":[]}""").toOption.get))

  test("a games page names a registered opponent"):
    val guest   = "guest:0192f000-0000-7000-8000-000000000001"
    val account = "user:0192f000-0000-7000-8000-0000000000aa"
    val app0    = app(
      recent = Map(guest -> List(row("g1", white = guest, black = account, result = Some(1)))),
      nicknames = Map(account -> "QuietRook")
    )
    app0
      .run(Request[IO](Method.GET, Uri.unsafeFromString(s"/players/${guest.drop("guest:".length)}/games")))
      .flatMap(_.as[io.circe.Json])
      .map: json =>
        val opponent = json.hcursor.downField("games").downArray.downField("opponent")
        assertEquals(opponent.get[String]("kind").toOption, Some("Human"))
        assertEquals(opponent.get[Option[String]]("name").toOption, Some(Some("QuietRook")))

  test("a games page leaves a guest opponent anonymous even when accounts are resolvable"):
    val me    = "guest:0192f000-0000-7000-8000-000000000001"
    val other = "guest:0192f000-0000-7000-8000-000000000002"
    // The map deliberately contains a name for the OTHER guest's id: the store can never produce that, and this pins
    // that the route does not invent one either.
    val app0 = app(
      recent = Map(me -> List(row("g2", white = me, black = other, result = Some(1)))),
      nicknames = Map(other -> "ShouldNeverAppear")
    )
    app0
      .run(Request[IO](Method.GET, Uri.unsafeFromString(s"/players/${me.drop("guest:".length)}/games")))
      .flatMap(_.as[io.circe.Json])
      .map: json =>
        val opponent = json.hcursor.downField("games").downArray.downField("opponent")
        assertEquals(opponent.get[String]("kind").toOption, Some("Human"))
        assertEquals(opponent.get[Option[String]]("name").toOption, Some(None))
