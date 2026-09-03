package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{
  BotEvent,
  GameId,
  GameResult,
  MoveTree,
  PlayerKind,
  Players,
  Principal,
  PublicPlayer,
  Seat,
  Seek,
  Termination,
  TimeControl
}
import dicechess.play.dice.DiceSource
import dicechess.play.store.BotSeatPolicy
import dicechess.play.wire.Codecs.given
import fs2.Stream
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.headers.{Authorization, `Retry-After`}
import org.http4s.implicits.*
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}

import scala.concurrent.duration.*

class BotRoutesSuite extends munit.CatsEffectSuite:

  private val movableDice = new DiceSource:
    def commit: String                                                       = "fixed-commit"
    def reveal: String                                                       = "fixed-reveal"
    def roll(ply: Long, clientSeedW: String, clientSeedB: String): List[Int] = List(1, 2, 3)

  private def appWith(
      limiter: AnonMintLimiter,
      maxPendingPerBot: Int = Challenges.DefaultMaxPendingPerBot,
      registerLimit: Int = 100,
      diceSource: () => IO[DiceSource] = () => DiceSource.newCommitReveal()
  ): IO[(HttpApp[IO], GameRegistry)] =
    for
      bots            <- dicechess.play.store.BotStore.inMemory
      auth            <- BotAuth.fromSpec("acme|alice|tok-alice,acme|bob|tok-bob,acme|carol|tok-carol", bots)
      events          <- BotEvents.create
      registry        <- GameRegistry.create(diceSource = diceSource)
      challenges      <- Challenges.create(events, registry, maxPendingPerBot = maxPendingPerBot)
      lobby           <- Lobby.create(registry)
      registerLimiter <- AnonMintLimiter.create(limit = registerLimit)
      // LobbyRoutes rides along so the human↔bot seek flows can be exercised end-to-end over HTTP.
      routes = BotRoutes(
        auth,
        challenges,
        events,
        registry,
        lobby,
        BotRoutes.MintLimiters(limiter, registerLimiter)
      ) <+> LobbyRoutes(lobby)
    yield (routes.orNotFound, registry)

  private def app: IO[HttpApp[IO]] = AnonMintLimiter.create(limit = 100).flatMap(appWith(_)).map(_._1)

  private def request(method: Method, uri: Uri, token: Option[String]): Request[IO] =
    val base = Request[IO](method, uri)
    token.fold(base)(t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t))))

  test("ndjson interleaves keep-alive newlines into an idle stream"):
    // An idle event stream (a bot waiting for challenges) must still produce periodic bytes so neither
    // the ember server's read-idle nor the client's timeout drops the long-lived stream.
    BotRoutes
      .ndjson[BotEvent](Stream.never[IO], keepAlive = 50.millis)
      .take(2)
      .compile
      .toList
      .timeoutTo(5.seconds, IO.raiseError(RuntimeException("no keep-alive within the deadline")))
      .map(bytes => assertEquals(new String(bytes.toArray, "UTF-8"), "\n\n"))

  test("POST /bot/anon mints a token that then authenticates"):
    app.flatMap: service =>
      for
        created <- service.run(Request[IO](Method.POST, uri"/bot/anon?name=Tester")).flatMap(_.as[AnonBot])
        account <- service.run(request(Method.GET, uri"/bot/account", Some(created.token))).flatMap(_.as[BotAccount])
      yield
        assertEquals(created.team, "anon")
        assert(created.id.startsWith("bot:team:anon:tester-"), created.id)
        assertEquals(account.id, created.id) // the minted token authenticates as the same identity

  test("POST /bot/anon is rate-limited per client (429 + Retry-After)"):
    AnonMintLimiter
      .create(limit = 2)
      .flatMap(appWith(_))
      .map(_._1)
      .flatMap: service =>
        val mint = Request[IO](Method.POST, uri"/bot/anon")
        for
          s1 <- service.run(mint).map(_.status)
          s2 <- service.run(mint).map(_.status)
          r3 <- service.run(mint)
        yield
          assertEquals(s1, Status.Created)
          assertEquals(s2, Status.Created)
          assertEquals(r3.status, Status.TooManyRequests)
          assert(r3.headers.get[`Retry-After`].isDefined, "a 429 must carry Retry-After")

  test("POST /bot/register mints a durable identity that authenticates; the slug rules gate it"):
    app.flatMap: service =>
      for
        created <- service
          .run(Request[IO](Method.POST, uri"/bot/register").withEntity(RegisterBot("dragons", "smaug")))
          .flatMap(_.as[BotRegistered])
        account <- service.run(request(Method.GET, uri"/bot/account", Some(created.token))).flatMap(_.as[BotAccount])
        badSlug <- service
          .run(Request[IO](Method.POST, uri"/bot/register").withEntity(RegisterBot("Dragons", "smaug")))
          .map(_.status)
        reserved <- service
          .run(Request[IO](Method.POST, uri"/bot/register").withEntity(RegisterBot("house", "smaug")))
          .map(_.status)
        taken <- service
          .run(Request[IO](Method.POST, uri"/bot/register").withEntity(RegisterBot("dragons", "smaug")))
          .map(_.status)
        static <- service
          .run(Request[IO](Method.POST, uri"/bot/register").withEntity(RegisterBot("acme", "alice")))
          .map(_.status)
      yield
        assertEquals(created.id, "bot:team:dragons:smaug")
        assertEquals(account.id, created.id) // the once-shown token authenticates as the claimed identity
        assertEquals(badSlug, Status.BadRequest)
        assertEquals(reserved, Status.BadRequest)
        assertEquals(taken, Status.Conflict)
        assertEquals(static, Status.Conflict) // the static roster can't be impersonated

  test("POST /bot/register is rate-limited per client"):
    AnonMintLimiter
      .create(limit = 100)
      .flatMap(appWith(_, registerLimit = 1))
      .map(_._1)
      .flatMap: service =>
        for
          first  <- service.run(Request[IO](Method.POST, uri"/bot/register").withEntity(RegisterBot("dragons", "one")))
          second <- service.run(Request[IO](Method.POST, uri"/bot/register").withEntity(RegisterBot("dragons", "two")))
        yield
          assertEquals(first.status, Status.Created)
          assertEquals(second.status, Status.TooManyRequests)
          assert(second.headers.get[`Retry-After`].isDefined, "a 429 must carry Retry-After")

  test("POST /bot/token rotates a registered token; anon and static callers get 403"):
    app.flatMap: service =>
      for
        created <- service
          .run(Request[IO](Method.POST, uri"/bot/register").withEntity(RegisterBot("dragons", "smaug")))
          .flatMap(_.as[BotRegistered])
        rotated <- service
          .run(request(Method.POST, uri"/bot/token", Some(created.token)))
          .flatMap(_.as[RotatedToken])
        oldDead  <- service.run(request(Method.GET, uri"/bot/account", Some(created.token))).map(_.status)
        newAlive <- service.run(request(Method.GET, uri"/bot/account", Some(rotated.token))).flatMap(_.as[BotAccount])
        anon     <- service.run(Request[IO](Method.POST, uri"/bot/anon")).flatMap(_.as[AnonBot])
        anonNo   <- service.run(request(Method.POST, uri"/bot/token", Some(anon.token))).map(_.status)
        staticNo <- service.run(request(Method.POST, uri"/bot/token", Some("tok-alice"))).map(_.status)
      yield
        assertEquals(oldDead, Status.Unauthorized) // the rotated-away token is gone immediately
        assertEquals(newAlive.id, created.id)      // the identity itself is unchanged
        assertEquals(anonNo, Status.Forbidden)
        assertEquals(staticNo, Status.Forbidden)

  test("POST /bot/ladder/join and /leave toggle a registered bot; anon and static callers get 403"):
    app.flatMap: service =>
      for
        created <- service
          .run(Request[IO](Method.POST, uri"/bot/register").withEntity(RegisterBot("dragons", "smaug")))
          .flatMap(_.as[BotRegistered])
        joined <- service
          .run(request(Method.POST, uri"/bot/ladder/join", Some(created.token)))
          .flatMap(_.as[LadderStatus])
        left <- service
          .run(request(Method.POST, uri"/bot/ladder/leave", Some(created.token)))
          .flatMap(_.as[LadderStatus])
        anon     <- service.run(Request[IO](Method.POST, uri"/bot/anon")).flatMap(_.as[AnonBot])
        anonNo   <- service.run(request(Method.POST, uri"/bot/ladder/join", Some(anon.token))).map(_.status)
        staticNo <- service.run(request(Method.POST, uri"/bot/ladder/join", Some("tok-alice"))).map(_.status)
      yield
        assertEquals(joined, LadderStatus(onLadder = true))
        assertEquals(left, LadderStatus(onLadder = false))
        assertEquals(anonNo, Status.Forbidden)
        assertEquals(staticNo, Status.Forbidden)

  test(
    "POST /bot/open-to-humans sets/clears description atomically, /leave keeps it; bad body 400; anon and static 403"
  ):
    app.flatMap: service =>
      for
        created <- service
          .run(Request[IO](Method.POST, uri"/bot/register").withEntity(RegisterBot("dragons", "smaug")))
          .flatMap(_.as[BotRegistered])
        opened <- service
          .run(
            request(Method.POST, uri"/bot/open-to-humans", Some(created.token))
              .withEntity(SetOpenToHumans(Some("aggressive + book")))
          )
          .flatMap(_.as[OpenToHumans])
        closed <- service
          .run(request(Method.POST, uri"/bot/open-to-humans/leave", Some(created.token)))
          .flatMap(_.as[OpenToHumans])
        reopened <- service
          .run(request(Method.POST, uri"/bot/open-to-humans", Some(created.token)))
          .flatMap(_.as[OpenToHumans])
        badBody <- service
          .run(
            request(Method.POST, uri"/bot/open-to-humans", Some(created.token))
              .withEntity(io.circe.Json.obj("description" -> io.circe.Json.fromInt(123)))
          )
          .map(_.status)
        tooLong <- service
          .run(
            request(Method.POST, uri"/bot/open-to-humans", Some(created.token))
              .withEntity(SetOpenToHumans(Some("x".repeat(201))))
          )
          .map(_.status)
        anon     <- service.run(Request[IO](Method.POST, uri"/bot/anon")).flatMap(_.as[AnonBot])
        anonNo   <- service.run(request(Method.POST, uri"/bot/open-to-humans", Some(anon.token))).map(_.status)
        staticNo <- service.run(request(Method.POST, uri"/bot/open-to-humans", Some("tok-alice"))).map(_.status)
      yield
        assertEquals(opened, OpenToHumans(openToHumans = true, description = Some("aggressive + book")))
        assertEquals(closed, OpenToHumans(openToHumans = false, description = Some("aggressive + book")))
        assertEquals(reopened, OpenToHumans(openToHumans = true, description = None))
        assertEquals(badBody, Status.BadRequest)
        assertEquals(tooLong, Status.BadRequest)
        assertEquals(anonNo, Status.Forbidden)
        assertEquals(staticNo, Status.Forbidden)

  test("GET/POST /bot/capacity: registration declares 1, a declaration round-trips, out of range 400, anon/static 403"):
    app.flatMap: service =>
      for
        created <- service
          .run(Request[IO](Method.POST, uri"/bot/register").withEntity(RegisterBot("dragons", "smaug")))
          .flatMap(_.as[BotRegistered])
        initial <- service.run(request(Method.GET, uri"/bot/capacity", Some(created.token))).flatMap(_.as[Capacity])
        raised  <- service
          .run(request(Method.POST, uri"/bot/capacity", Some(created.token)).withEntity(SetCapacity(4)))
          .flatMap(_.as[Capacity])
        reread <- service.run(request(Method.GET, uri"/bot/capacity", Some(created.token))).flatMap(_.as[Capacity])
        // Opening to humans must not change the declaration, only the ladder's share of it.
        _         <- service.run(request(Method.POST, uri"/bot/open-to-humans", Some(created.token)))
        inCatalog <- service.run(request(Method.GET, uri"/bot/capacity", Some(created.token))).flatMap(_.as[Capacity])
        tooLow    <- service
          .run(request(Method.POST, uri"/bot/capacity", Some(created.token)).withEntity(SetCapacity(0)))
          .map(_.status)
        tooHigh <- service
          .run(
            request(Method.POST, uri"/bot/capacity", Some(created.token))
              .withEntity(SetCapacity(BotSeatPolicy.MaxDeclarableConcurrentGames + 1))
          )
          .map(_.status)
        badBody <- service
          .run(request(Method.POST, uri"/bot/capacity", Some(created.token)).withEntity(io.circe.Json.obj()))
          .map(_.status)
        anon     <- service.run(Request[IO](Method.POST, uri"/bot/anon")).flatMap(_.as[AnonBot])
        anonNo   <- service.run(request(Method.GET, uri"/bot/capacity", Some(anon.token))).map(_.status)
        staticNo <- service.run(request(Method.GET, uri"/bot/capacity", Some("tok-alice"))).map(_.status)
      yield
        assertEquals(
          initial,
          Capacity(BotSeatPolicy.DefaultMaxConcurrentGames, openToHumans = false, ladderAllowance = 1, activeGames = 0)
        )
        assertEquals(raised, Capacity(4, openToHumans = false, ladderAllowance = 4, activeGames = 0))
        assertEquals(reread, raised, "the write's own answer must match a fresh read")
        assertEquals(inCatalog, Capacity(4, openToHumans = true, ladderAllowance = 3, activeGames = 0))
        assertEquals(tooLow, Status.BadRequest)
        assertEquals(tooHigh, Status.BadRequest)
        assertEquals(badBody, Status.BadRequest)
        // Neither has a row to declare on, and both stay unbounded — the house bot must face every visitor at once.
        assertEquals(anonNo, Status.Forbidden)
        assertEquals(staticNo, Status.Forbidden)

  test("GET /bot/capacity reports the games in flight, so a low limit is legible rather than looking ignored (#189)"):
    AnonMintLimiter.create(limit = 100).flatMap(appWith(_)).flatMap { (service, registry) =>
      for
        created <- service
          .run(Request[IO](Method.POST, uri"/bot/register").withEntity(RegisterBot("dragons", "smaug")))
          .flatMap(_.as[BotRegistered])
        _        <- registry.create(Principal.Bot("dragons", "smaug"), Principal.Bot("acme", "alice"))
        inFlight <- service.run(request(Method.GET, uri"/bot/capacity", Some(created.token))).flatMap(_.as[Capacity])
      yield assertEquals(inFlight.activeGames, 1)
    }

  test("an unknown / no Bearer token is unauthorized"):
    app.flatMap: service =>
      for
        noAuth   <- service.run(Request[IO](Method.GET, uri"/bot/account")).map(_.status)
        badToken <- service.run(request(Method.GET, uri"/bot/account", Some("nope"))).map(_.status)
      yield
        assertEquals(noAuth, Status.Unauthorized)
        assertEquals(badToken, Status.Unauthorized)

  private def challengeBobAsAlice(service: HttpApp[IO]): IO[ChallengeCreated] =
    service
      .run(request(Method.POST, uri"/bot/challenge", Some("tok-alice")).withEntity(ChallengeTarget("acme", "bob")))
      .flatMap(_.as[ChallengeCreated])

  /** Alice challenges Bob and Bob accepts; yields the seated game's id. */
  private def seatedGame(service: HttpApp[IO]): IO[String] =
    for
      challenge <- challengeBobAsAlice(service)
      accepted  <- service.run(request(Method.POST, uri"/bot/challenge" / challenge.id / "accept", Some("tok-bob")))
      game      <- accepted.as[BotGame]
    yield game.gameId

  test("GET /bot/account returns the bot identity for a valid token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/account", Some("tok-alice"))))
      .flatMap: resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[BotAccount].map(a => assertEquals(a, BotAccount("acme", "alice", "bot:team:acme:alice")))

  test("GET /bot/account is 401 without a token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/account", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("GET /bot/stream/event is 401 without a token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/stream/event", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("POST /bot/challenge creates a challenge from the authenticated bot"):
    app
      .flatMap(challengeBobAsAlice)
      .map: challenge =>
        assertEquals(challenge.challenger, Principal.Bot("acme", "alice"))
        assertEquals(challenge.target, Principal.Bot("acme", "bob"))
        assert(challenge.id.nonEmpty)
        // Nobody holds an account stream in this app — advisory only, the entry is still pending and pollable.
        assertEquals(challenge.targetOnline, false)

  test("POST /bot/challenge is 401 without a token"):
    app
      .flatMap(_.run(request(Method.POST, uri"/bot/challenge", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("a bot challenging itself is a 400"):
    app
      .flatMap(
        _.run(request(Method.POST, uri"/bot/challenge", Some("tok-alice")).withEntity(ChallengeTarget("acme", "alice")))
      )
      .map(r => assertEquals(r.status, Status.BadRequest))

  test("the pending-challenge cap answers 429"):
    AnonMintLimiter
      .create(limit = 100)
      .flatMap(appWith(_, maxPendingPerBot = 1))
      .map(_._1)
      .flatMap: service =>
        for
          first <- service.run(
            request(Method.POST, uri"/bot/challenge", Some("tok-alice")).withEntity(ChallengeTarget("acme", "bob"))
          )
          overflow <- service.run(
            request(Method.POST, uri"/bot/challenge", Some("tok-alice")).withEntity(ChallengeTarget("acme", "carol"))
          )
        yield
          assertEquals(first.status, Status.Created)
          assertEquals(overflow.status, Status.TooManyRequests)

  test("GET /bot/challenges lists pending challenges as in/out for the caller"):
    app.flatMap: service =>
      for
        created <- challengeBobAsAlice(service)
        alice <- service.run(request(Method.GET, uri"/bot/challenges", Some("tok-alice"))).flatMap(_.as[BotChallenges])
        bob   <- service.run(request(Method.GET, uri"/bot/challenges", Some("tok-bob"))).flatMap(_.as[BotChallenges])
        carol <- service.run(request(Method.GET, uri"/bot/challenges", Some("tok-carol"))).flatMap(_.as[BotChallenges])
      yield
        assertEquals(alice.out.map(_.id), List(created.id)) // the challenger watches its outgoing challenge
        assertEquals(alice.in, Nil)
        assertEquals(bob.in.map(_.id), List(created.id)) // the target discovers it by polling — no stream needed
        assertEquals(bob.out, Nil)
        assertEquals(carol, BotChallenges(Nil, Nil)) // uninvolved bots see nothing

  test("GET /bot/challenges is 401 without a token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/challenges", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("GET /bot/games lists only the games the caller is seated in"):
    app.flatMap: service =>
      for
        gameId <- seatedGame(service)
        alice  <- service.run(request(Method.GET, uri"/bot/games", Some("tok-alice"))).flatMap(_.as[BotGames])
        bob    <- service.run(request(Method.GET, uri"/bot/games", Some("tok-bob"))).flatMap(_.as[BotGames])
        carol  <- service.run(request(Method.GET, uri"/bot/games", Some("tok-carol"))).flatMap(_.as[BotGames])
      yield
        // Both players recover the game (and their seat) with no stream held — the post-restart resume path.
        assertEquals(alice.games.map(g => (g.gameId, g.seat)), List((gameId, Seat.White)))
        assertEquals(bob.games.map(g => (g.gameId, g.seat)), List((gameId, Seat.Black)))
        assertEquals(carol.games, Nil)

  test("GET /bot/games is 401 without a token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/games", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  // The two bot-facing creation paths deliberately disagree on what "no time control given" means, because only one
  // of them can seat a human (rabestro/dicechess-play#99).
  /** The regression #279 shipped: a bot written against the pre-`rated` contract sends a body without that field, and
    * because circe's Scala 3 derivation ignores default values it got a 400 — while the published OpenAPI described
    * `rated` as optional. Sent as raw JSON on purpose: `.withEntity(BotCreateSeek())` encodes the field and so cannot
    * catch this, which is why the existing seek tests all passed through the outage.
    */
  test("POST /bot/seeks accepts a body that omits `rated`, as every bot predating #279 sends"):
    app.flatMap: service =>
      val preRatedBody = io.circe.parser.parse("""{"timeControl":{"Unlimited":{}}}""").toOption.get
      for
        res  <- service.run(request(Method.POST, uri"/bot/seeks", Some("tok-alice")).withEntity(preRatedBody))
        open <- service.run(Request[IO](Method.GET, uri"/lobby/seeks")).flatMap(_.as[List[Seek]])
      yield
        assertEquals(res.status, Status.Created)
        assertEquals(open.map(_.rated), List(false), "an omitted flag means casual, not a rejected request")

  test("POST /bot/seeks without a time control gets a clock — a human may sit down there"):
    app.flatMap: service =>
      for
        _    <- service.run(request(Method.POST, uri"/bot/seeks", Some("tok-alice")).withEntity(BotCreateSeek()))
        open <- service.run(Request[IO](Method.GET, uri"/lobby/seeks")).flatMap(_.as[List[Seek]])
      yield assertEquals(open.map(_.timeControl), List(TimeControl.Default))

  test("POST /bot/challenge carries `rated` onto the offer, and echoes it back"):
    app.flatMap: service =>
      for
        created <- service
          .run(
            request(Method.POST, uri"/bot/challenge", Some("tok-alice"))
              // A control is now part of asking for rated (#280): `Unlimited`, still this route's default, has no
              // rating scale to land on.
              .withEntity(
                ChallengeTarget("acme", "bob", Some(TimeControl.Fischer(300, 3)), rated = true)
              )
          )
          .flatMap(_.as[ChallengeCreated])
        // The target polls: it must see the offer is rated BEFORE deciding to accept (#282).
        pending <- service.run(request(Method.GET, uri"/bot/challenges", Some("tok-bob"))).flatMap(_.as[BotChallenges])
      yield
        assert(created.rated, "the create response must echo the choice back to the challenger")
        assertEquals(pending.in.map(_.rated), List(true))

  /** The #279 trap, guarded for this new field too: a bot written before #282 sends a challenge body with no `rated`
    * key at all. Raw JSON on purpose — `.withEntity(ChallengeTarget(...))` always encodes the field and so cannot catch
    * a codec that has stopped honouring the default.
    */
  test("POST /bot/challenge accepts a body that omits `rated`, and treats it as casual"):
    app.flatMap: service =>
      val preRatedBody = io.circe.parser.parse("""{"team":"acme","name":"bob"}""").toOption.get
      for
        res     <- service.run(request(Method.POST, uri"/bot/challenge", Some("tok-alice")).withEntity(preRatedBody))
        created <- res.as[ChallengeCreated]
      yield
        assertEquals(res.status, Status.Created)
        assert(!created.rated, "an omitted flag means casual, not a rejected request")

  test("POST /bot/challenge without a time control stays Unlimited — bot-vs-bot, no human waits on it"):
    app.flatMap: service =>
      challengeBobAsAlice(service).map: challenge =>
        // Corpus and self-play runs want a clockless board, and a forced clock would flag the heavy search bots.
        assertEquals(challenge.timeControl, TimeControl.Unlimited: TimeControl)

  test("a bot posts a seek a human can see and accept — and both find their game"):
    AnonMintLimiter
      .create(limit = 100)
      .flatMap(appWith(_))
      .flatMap: (service, registry) =>
        for
          created <- service
            .run(request(Method.POST, uri"/bot/seeks", Some("tok-alice")).withEntity(BotCreateSeek()))
            .flatMap(_.as[CreatedSeek])
          // The lobby shows WHO offers the game: a bot, by its public team-qualified name.
          open <- service.run(Request[IO](Method.GET, uri"/lobby/seeks")).flatMap(_.as[List[Seek]])
          _ = assertEquals(
            open.map(s => (s.id, s.kind, s.name)),
            List((created.seekId, PlayerKind.Bot, Some("acme alice")))
          )
          // A guest accepts over the existing lobby route and receives its seat token.
          matched <- service
            .run(
              Request[IO](Method.POST, uri"/lobby/seeks" / created.seekId / "accept")
                .withEntity(AcceptSeek(Some("33333333-3333-3333-3333-333333333333")))
            )
            .flatMap(_.as[SeekMatch])
          // The bot needs no token: the game appears in its listing, seated White (the seek creator's seat).
          games <- service.run(request(Method.GET, uri"/bot/games", Some("tok-alice"))).flatMap(_.as[BotGames])
          // And the public snapshot tells everyone who plays: a named bot vs an anonymous human.
          players <- registry
            .get(GameId(matched.gameId))
            .flatMap(_.fold(IO.raiseError(RuntimeException("game vanished")))(_.snapshot))
            .map(_.players)
        yield
          assert(matched.token.nonEmpty)
          val botGame = games.games.head
          assertEquals(botGame.gameId, matched.gameId)
          assert(botGame.seat == Seat.White || botGame.seat == Seat.Black)
          assertNotEquals(matched.seat, botGame.seat, "the accepting guest and the bot must hold opposite seats")
          val expectedPlayers = if botGame.seat == Seat.White then
            Players(PublicPlayer(PlayerKind.Bot, Some("acme alice")), PublicPlayer(PlayerKind.Human, None))
          else Players(PublicPlayer(PlayerKind.Human, None), PublicPlayer(PlayerKind.Bot, Some("acme alice")))
          assertEquals(players, Some(expectedPlayers))

  test("a bot accepts a guest seek; the guest's status poll delivers its token"):
    app.flatMap: service =>
      for
        created <- service
          .run(
            Request[IO](Method.POST, uri"/lobby/seeks")
              .withEntity(CreateSeek(Some("44444444-4444-4444-4444-444444444444")))
          )
          .flatMap(_.as[CreatedSeek])
        accepted <- service.run(request(Method.POST, uri"/bot/seeks" / created.seekId / "accept", Some("tok-bob")))
        game     <- accepted.as[BotGame]
        status   <- service
          .run(Request[IO](Method.GET, uri"/lobby/seeks" / created.seekId +? ("secret" -> created.secret)))
          .flatMap(_.as[SeekState])
        // The direct accept response (BotGame) carries no seat — the bot reads it from its games listing.
        botGames <- service.run(request(Method.GET, uri"/bot/games", Some("tok-bob"))).flatMap(_.as[BotGames])
      yield
        assertEquals(accepted.status, Status.Created)
        assert(game.gameId.nonEmpty)
        assertEquals(status.matched, true)
        assertEquals(status.gameId, Some(game.gameId))
        assert(status.token.exists(_.nonEmpty), "the guest creator must get its seat token via the poll")
        val botSeat           = botGames.games.find(_.gameId == game.gameId).get.seat
        val expectedGuestSeat = if botSeat == Seat.White then Seat.Black else Seat.White
        assertEquals(
          status.seat,
          Some(expectedGuestSeat),
          "the guest creator and the accepting bot must hold opposite seats"
        )

  test("a bot cannot accept its own seek, and its open seeks are capped"):
    app.flatMap: service =>
      def post = service
        .run(request(Method.POST, uri"/bot/seeks", Some("tok-carol")).withEntity(BotCreateSeek()))
      for
        created  <- post.flatMap(_.as[CreatedSeek])
        own      <- service.run(request(Method.POST, uri"/bot/seeks" / created.seekId / "accept", Some("tok-carol")))
        _        <- post *> post // seeks 2 and 3 — the default cap
        overflow <- post
      yield
        assertEquals(own.status, Status.BadRequest)
        assertEquals(overflow.status, Status.TooManyRequests)

  test("a finished game leaves the listing (the player index is evicted with the room)"):
    app.flatMap: service =>
      def pollEmpty: IO[Unit] =
        service
          .run(request(Method.GET, uri"/bot/games", Some("tok-alice")))
          .flatMap(_.as[BotGames])
          .flatMap(listed => if listed.games.isEmpty then IO.unit else IO.sleep(50.millis) *> pollEmpty)
      for
        gameId <- seatedGame(service)
        before <- service.run(request(Method.GET, uri"/bot/games", Some("tok-alice"))).flatMap(_.as[BotGames])
        _ = assertEquals(before.games.map(_.gameId), List(gameId))
        _ <- service.run(request(Method.POST, uri"/bot/game" / gameId / "resign", Some("tok-alice")))
        // Eviction runs on the room-result fiber, so it lands shortly after the resign — poll until it does.
        _ <- pollEmpty.timeoutTo(5.seconds, IO.raiseError(RuntimeException("the finished game was never evicted")))
      yield ()

  test("the challenged bot accepts and receives a game id"):
    app.flatMap: service =>
      for
        challenge <- challengeBobAsAlice(service)
        accepted  <- service.run(request(Method.POST, uri"/bot/challenge" / challenge.id / "accept", Some("tok-bob")))
        _ = assertEquals(accepted.status, Status.Created)
        game <- accepted.as[BotGame]
      yield assert(game.gameId.nonEmpty)

  test("a non-target accepting is forbidden"):
    app.flatMap: service =>
      for
        challenge <- challengeBobAsAlice(service)
        // Alice is the challenger, not the challenged bot — she cannot accept.
        resp <- service.run(request(Method.POST, uri"/bot/challenge" / challenge.id / "accept", Some("tok-alice")))
      yield assertEquals(resp.status, Status.Forbidden)

  test("accepting an unknown challenge is 404"):
    app
      .flatMap(_.run(request(Method.POST, uri"/bot/challenge" / "nope" / "accept", Some("tok-bob"))))
      .map(r => assertEquals(r.status, Status.NotFound))

  test("accepting without a token is 401"):
    app
      .flatMap(_.run(request(Method.POST, uri"/bot/challenge" / "x" / "accept", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("a seated bot can open its game event stream"):
    app.flatMap: service =>
      seatedGame(service).flatMap: gameId =>
        service
          .run(request(Method.GET, uri"/bot/game/stream" / gameId, Some("tok-alice")))
          .map(r => assertEquals(r.status, Status.Ok))

  test("a bot not seated in the game cannot stream it"):
    app.flatMap: service =>
      seatedGame(service).flatMap: gameId =>
        service
          .run(request(Method.GET, uri"/bot/game/stream" / gameId, Some("tok-carol")))
          .map(r => assertEquals(r.status, Status.NotFound))

  test("streaming an unknown game is 404"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/game/stream" / "nope", Some("tok-alice"))))
      .map(r => assertEquals(r.status, Status.NotFound))

  test("a move before any roll is refused synchronously (409 not your turn)"):
    app.flatMap: service =>
      seatedGame(service).flatMap: gameId =>
        // No seeds submitted, so the opening roll is still gated: no turn is pending for anyone.
        service
          .run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some("tok-alice")).withEntity(BotMove(List("e2e4")))
          )
          .flatMap: resp =>
            assertEquals(resp.status, Status.Conflict)
            resp.as[MoveOutcome].map(o => assertEquals(o, MoveOutcome(applied = false, reason = Some("not your turn"))))

  test("a move submit answers the verdict synchronously: 409 with the reason, then 200 with the version"):
    AnonMintLimiter
      .create(limit = 100)
      .flatMap(appWith(_))
      .flatMap: (service, registry) =>
        /** Any root-to-leaf walk of the tree — a complete legal turn by construction. */
        def leafPath(tree: MoveTree): List[String] =
          tree.children.headOption match
            case None              => Nil
            case Some((uci, next)) => uci :: leafPath(next)

        // Poll the room until a roll with a real decision is pending (auto-passes advance on their own).
        def movable(gameId: String): IO[(Seat, MoveTree)] =
          registry
            .get(GameId(gameId))
            .flatMap:
              case None       => IO.raiseError(RuntimeException("game vanished"))
              case Some(room) =>
                (room.snapshot, room.legalMoves).flatMapN: (snap, moves) =>
                  if moves.dicePending && moves.legalMoves.children.nonEmpty then
                    IO.pure((snap.activeSeat, moves.legalMoves))
                  else IO.sleep(50.millis) *> movable(gameId)

        def seed(gameId: String, token: String, seed: String): IO[Status] =
          service
            .run(request(Method.POST, uri"/bot/game" / gameId / "seed", Some(token)).withEntity(BotSeed(seed)))
            .map(_.status)

        for
          gameId <- seatedGame(service)
          // Both seats seed via the API — this opens the gate and rolls the first turn immediately.
          _                  <- seed(gameId, "tok-alice", "alice-client-seed-0001")
          _                  <- seed(gameId, "tok-bob", "bob-client-seed-00001")
          (activeSeat, tree) <- movable(gameId).timeoutTo(
            10.seconds,
            IO.raiseError(RuntimeException("no movable roll"))
          )
          // Alice challenged, so she is White; the mover's token follows the active seat.
          mover     = if activeSeat == Seat.White then "tok-alice" else "tok-bob"
          offTurner = if activeSeat == Seat.White then "tok-bob" else "tok-alice"
          offTurn <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some(offTurner)).withEntity(BotMove(leafPath(tree)))
          )
          illegal <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some(mover)).withEntity(BotMove(List("a1a1")))
          )
          applied <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some(mover)).withEntity(BotMove(leafPath(tree)))
          )
          offTurnBody <- offTurn.as[MoveOutcome]
          illegalBody <- illegal.as[MoveOutcome]
          appliedBody <- applied.as[MoveOutcome]
        yield
          assertEquals(offTurn.status, Status.Conflict)
          assertEquals(offTurnBody.reason, Some("not your turn"))
          assertEquals(illegal.status, Status.Conflict)
          assertEquals(illegalBody, MoveOutcome(applied = false, reason = Some("illegal turn")))
          assertEquals(applied.status, Status.Ok)
          assertEquals(appliedBody.applied, true)
          assert(
            appliedBody.version.exists(_ > 0L),
            s"the applied verdict must carry the TurnPlayed version: $appliedBody"
          )

  test("a seated bot can submit a dice seed (accepted; folded in before the opening roll)"):
    app.flatMap: service =>
      seatedGame(service).flatMap: gameId =>
        service
          .run(
            request(Method.POST, uri"/bot/game" / gameId / "seed", Some("tok-alice"))
              .withEntity(BotSeed("alice-client-seed-0001"))
          )
          .map(r => assertEquals(r.status, Status.Accepted))

  test("a seated bot can resign"):
    app.flatMap: service =>
      seatedGame(service).flatMap: gameId =>
        service
          .run(request(Method.POST, uri"/bot/game" / gameId / "resign", Some("tok-bob")))
          .map(r => assertEquals(r.status, Status.Accepted))

  test("move on an unknown game is 404"):
    app
      .flatMap(
        _.run(
          request(Method.POST, uri"/bot/game" / "nope" / "move", Some("tok-alice")).withEntity(BotMove(List("e2e4")))
        )
      )
      .map(r => assertEquals(r.status, Status.NotFound))

  test("bot can accept draw via BotMove(acceptDraw = true) on its turn"):
    AnonMintLimiter
      .create(limit = 100)
      .flatMap(appWith(_, diceSource = () => IO.pure(movableDice)))
      .flatMap: (service, registry) =>
        def leafPath(tree: MoveTree): List[String] =
          tree.children.headOption match
            case None              => Nil
            case Some((uci, next)) => uci :: leafPath(next)

        def nextWithOffer(gameId: String, afterVersion: Long): IO[(Seat, Long)] =
          registry
            .get(GameId(gameId))
            .flatMap:
              case None       => IO.raiseError(RuntimeException("game vanished"))
              case Some(room) =>
                room.snapshot.flatMap: snap =>
                  if snap.version >= afterVersion && snap.drawOffer.exists(_.pending)
                  then IO.pure((snap.activeSeat, snap.version))
                  else IO.sleep(25.millis) *> nextWithOffer(gameId, afterVersion)

        def nextMovable(gameId: String, afterVersion: Long): IO[(Seat, MoveTree, Long)] =
          registry
            .get(GameId(gameId))
            .flatMap:
              case None       => IO.raiseError(RuntimeException("game vanished"))
              case Some(room) =>
                (room.snapshot, room.legalMoves).flatMapN: (snap, moves) =>
                  if snap.version >= afterVersion && moves.dicePending && moves.legalMoves.children.nonEmpty then
                    IO.pure((snap.activeSeat, moves.legalMoves, snap.version))
                  else IO.sleep(25.millis) *> nextMovable(gameId, afterVersion)

        def seed(gameId: String, token: String, seed: String): IO[Status] =
          service
            .run(request(Method.POST, uri"/bot/game" / gameId / "seed", Some(token)).withEntity(BotSeed(seed)))
            .map(_.status)

        for
          gameId                <- seatedGame(service)
          _                     <- seed(gameId, "tok-alice", "alice-client-seed-0001")
          _                     <- seed(gameId, "tok-bob", "bob-client-seed-00001")
          room                  <- registry.get(GameId(gameId)).map(_.getOrElse(fail("room vanished")))
          (activeSeat, tree, _) <- nextMovable(gameId, 0L).timeoutTo(
            10.seconds,
            IO.raiseError(RuntimeException("no movable roll"))
          )
          mover = if activeSeat == Seat.White then "tok-alice" else "tok-bob"
          // Mover offers draw alongside move
          offered <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some(mover))
              .withEntity(BotMove(leafPath(tree), offerDraw = true))
          )
          _ = assertEquals(offered.status, Status.Ok)
          offeredBody <- offered.as[MoveOutcome]
          v1 = offeredBody.version.get
          // Wait for opponent's pre-roll gate with draw offer pending
          (opponentSeat, _) <- nextWithOffer(gameId, v1).timeoutTo(
            10.seconds,
            IO.raiseError(RuntimeException("no offer arrived for opponent"))
          )
          opponentToken = if opponentSeat == Seat.White then "tok-alice" else "tok-bob"
          // Opponent accepts draw via BotMove(acceptDraw = Some(true))
          accepted <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some(opponentToken))
              .withEntity(BotMove(Nil, acceptDraw = Some(true)))
          )
          acceptedBody <- accepted.as[MoveOutcome]
          res          <- room.result
        yield
          assertEquals(accepted.status, Status.Ok)
          assertEquals(acceptedBody.applied, true)
          assertEquals(res.result, GameResult.Draw)
          assertEquals(res.termination, Termination.Draw)

  test("bot can accept or decline draw via dedicated /draw/accept and /draw/decline endpoints"):
    AnonMintLimiter
      .create(limit = 100)
      .flatMap(appWith(_, diceSource = () => IO.pure(movableDice)))
      .flatMap: (service, registry) =>
        def leafPath(tree: MoveTree): List[String] =
          tree.children.headOption match
            case None              => Nil
            case Some((uci, next)) => uci :: leafPath(next)

        def nextMovable(gameId: String, afterVersion: Long): IO[(Seat, MoveTree, Long)] =
          registry
            .get(GameId(gameId))
            .flatMap:
              case None       => IO.raiseError(RuntimeException("game vanished"))
              case Some(room) =>
                (room.snapshot, room.legalMoves).flatMapN: (snap, moves) =>
                  if snap.version >= afterVersion && moves.dicePending && moves.legalMoves.children.nonEmpty then
                    IO.pure((snap.activeSeat, moves.legalMoves, snap.version))
                  else IO.sleep(25.millis) *> nextMovable(gameId, afterVersion)

        def nextWithOffer(gameId: String, afterVersion: Long): IO[(Seat, Long)] =
          registry
            .get(GameId(gameId))
            .flatMap:
              case None       => IO.raiseError(RuntimeException("game vanished"))
              case Some(room) =>
                room.snapshot.flatMap: snap =>
                  if snap.version >= afterVersion && snap.drawOffer.exists(_.pending)
                  then IO.pure((snap.activeSeat, snap.version))
                  else IO.sleep(25.millis) *> nextWithOffer(gameId, afterVersion)

        def seed(gameId: String, token: String, seed: String): IO[Status] =
          service
            .run(request(Method.POST, uri"/bot/game" / gameId / "seed", Some(token)).withEntity(BotSeed(seed)))
            .map(_.status)

        for
          gameId                <- seatedGame(service)
          _                     <- seed(gameId, "tok-alice", "alice-client-seed-0001")
          _                     <- seed(gameId, "tok-bob", "bob-client-seed-00001")
          room                  <- registry.get(GameId(gameId)).map(_.getOrElse(fail("room vanished")))
          (activeSeat, tree, _) <- nextMovable(gameId, 0L).timeoutTo(
            10.seconds,
            IO.raiseError(RuntimeException("no movable roll"))
          )
          mover = if activeSeat == Seat.White then "tok-alice" else "tok-bob"
          // 1. Mover offers draw
          offered1 <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some(mover))
              .withEntity(BotMove(leafPath(tree), offerDraw = true))
          )
          offeredBody1 <- offered1.as[MoveOutcome]
          v1 = offeredBody1.version.get
          // Wait for opponent's pre-roll gate with draw offer pending
          (oppSeat, _) <- nextWithOffer(gameId, v1).timeoutTo(
            10.seconds,
            IO.raiseError(RuntimeException("no offer arrived for opponent 1"))
          )
          oppToken = if oppSeat == Seat.White then "tok-alice" else "tok-bob"
          // 2. Opponent declines via POST /bot/game/{id}/draw/decline -> reveals dice
          declined <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "draw" / "decline", Some(oppToken))
          )
          declinedBody <- declined.as[MoveOutcome]
          _  = assertEquals(declined.status, Status.Ok)
          _  = assertEquals(declinedBody.applied, true)
          v2 = declinedBody.version.get
          // Wait for next movable roll
          (mover2Seat, tree2, _) <- nextMovable(gameId, v2).timeoutTo(
            10.seconds,
            IO.raiseError(RuntimeException("no movable roll arrived after decline"))
          )
          mover2Token = if mover2Seat == Seat.White then "tok-alice" else "tok-bob"
          // 3. Mover 2 plays move and offers draw back
          offered2 <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some(mover2Token))
              .withEntity(BotMove(leafPath(tree2), offerDraw = true))
          )
          offeredBody2 <- offered2.as[MoveOutcome]
          v3 = offeredBody2.version.get
          // Wait for receiver's pre-roll gate with draw offer
          (receiverSeat, _) <- nextWithOffer(gameId, v3).timeoutTo(
            10.seconds,
            IO.raiseError(RuntimeException("no offer arrived for receiver"))
          )
          receiverToken = if receiverSeat == Seat.White then "tok-alice" else "tok-bob"
          // 4. Receiver accepts via POST /bot/game/{id}/draw/accept
          accepted <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "draw" / "accept", Some(receiverToken))
          )
          acceptedBody <- accepted.as[MoveOutcome]
          res          <- room.result
        yield
          assertEquals(accepted.status, Status.Ok)
          assertEquals(acceptedBody.applied, true)
          assertEquals(res.result, GameResult.Draw)
          assertEquals(res.termination, Termination.Draw)

  test("bot can decline draw via POST /bot/game/{id}/move with acceptDraw: false"):
    AnonMintLimiter
      .create(limit = 100)
      .flatMap(appWith(_, diceSource = () => IO.pure(movableDice)))
      .flatMap: (service, registry) =>
        def leafPath(tree: MoveTree): List[String] =
          tree.children.headOption match
            case None              => Nil
            case Some((uci, next)) => uci :: leafPath(next)

        def nextMovable(gameId: String, afterVersion: Long): IO[(Seat, MoveTree, Long)] =
          registry
            .get(GameId(gameId))
            .flatMap:
              case None       => IO.raiseError(RuntimeException("game vanished"))
              case Some(room) =>
                (room.snapshot, room.legalMoves).flatMapN: (snap, moves) =>
                  if snap.version >= afterVersion && moves.dicePending && moves.legalMoves.children.nonEmpty then
                    IO.pure((snap.activeSeat, moves.legalMoves, snap.version))
                  else IO.sleep(25.millis) *> nextMovable(gameId, afterVersion)

        def nextWithOffer(gameId: String, afterVersion: Long): IO[(Seat, Long)] =
          registry
            .get(GameId(gameId))
            .flatMap:
              case None       => IO.raiseError(RuntimeException("game vanished"))
              case Some(room) =>
                room.snapshot.flatMap: snap =>
                  if snap.version >= afterVersion && snap.drawOffer.exists(_.pending)
                  then IO.pure((snap.activeSeat, snap.version))
                  else IO.sleep(25.millis) *> nextWithOffer(gameId, afterVersion)

        def seed(gameId: String, token: String, seed: String): IO[Status] =
          service
            .run(request(Method.POST, uri"/bot/game" / gameId / "seed", Some(token)).withEntity(BotSeed(seed)))
            .map(_.status)

        for
          gameId                <- seatedGame(service)
          _                     <- seed(gameId, "tok-alice", "alice-client-seed-0001")
          _                     <- seed(gameId, "tok-bob", "bob-client-seed-00001")
          room                  <- registry.get(GameId(gameId)).map(_.getOrElse(fail("room vanished")))
          (activeSeat, tree, _) <- nextMovable(gameId, 0L).timeoutTo(
            10.seconds,
            IO.raiseError(RuntimeException("no movable roll"))
          )
          mover = if activeSeat == Seat.White then "tok-alice" else "tok-bob"
          // 1. Mover offers draw
          offered <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some(mover))
              .withEntity(BotMove(leafPath(tree), offerDraw = true))
          )
          offeredBody <- offered.as[MoveOutcome]
          v1 = offeredBody.version.get
          (oppSeat, _) <- nextWithOffer(gameId, v1).timeoutTo(
            10.seconds,
            IO.raiseError(RuntimeException("no offer arrived for opponent"))
          )
          oppToken = if oppSeat == Seat.White then "tok-alice" else "tok-bob"
          // 2. Opponent declines via POST /bot/game/{id}/move with acceptDraw: false
          declined <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some(oppToken))
              .withEntity(BotMove(acceptDraw = Some(false)))
          )
          declinedBody <- declined.as[MoveOutcome]
          _ = assertEquals(declined.status, Status.Ok)
          _ = assertEquals(declinedBody.applied, true)
          snap <- room.snapshot
        yield
          assertEquals(snap.drawOffer, None)
          assertEquals(snap.dicePending, true)
          assertEquals(snap.activeSeat, oppSeat)

  test("resign without a token is 401"):
    app
      .flatMap(_.run(request(Method.POST, uri"/bot/game" / "x" / "resign", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  /** Registering while signed in records the owner in the same INSERT (#253) — the wiring lives in the ROUTE (it
    * resolves the session), so `BotAuth.register` alone cannot prove it.
    */
  test("POST /bot/register under a session records the owner; without one the bot stays unowned"):
    val secret = "test-session-secret"
    for
      bots       <- dicechess.play.store.BotStore.inMemory
      auth       <- BotAuth.fromSpec("", bots)
      events     <- BotEvents.create
      registry   <- GameRegistry.create()
      challenges <- Challenges.create(events, registry)
      lobby      <- Lobby.create(registry)
      limiter    <- AnonMintLimiter.create(limit = 100)
      accounts   <- cats.effect.Ref.of[IO, Map[String, dicechess.play.store.UserAccount]](Map.empty)
      users   = OwnerStubUsers(accounts)
      session = AuthSession(users, secret)
      user  <- users.upsertOnLogin("google", "sub-bot-owner", None, IO.pure("BotOwner"))
      token <- session.sign(user)
      routes = BotRoutes(
        auth,
        challenges,
        events,
        registry,
        lobby,
        BotRoutes.MintLimiters(limiter, limiter),
        session = Some(session)
      ).orNotFound
      signedIn <- routes.run(
        Request[IO](Method.POST, uri"/bot/register")
          .withEntity(RegisterBot("owned", "byme"))
          .addCookie(org.http4s.RequestCookie(AuthSession.SessionCookieName, token))
      )
      anonymous <- routes.run(Request[IO](Method.POST, uri"/bot/register").withEntity(RegisterBot("owned", "nobody")))
      owned     <- bots.ratingOf("owned", "byme")
      unowned   <- bots.ratingOf("owned", "nobody")
    yield
      assertEquals(signedIn.status, Status.Created)
      assertEquals(anonymous.status, Status.Created)
      assertEquals(
        owned.flatMap(_.ownerExternalId),
        Some(dicechess.play.core.Principal.User(user.id).externalId),
        "a signed-in author should not need a follow-up claim"
      )
      assertEquals(unowned.flatMap(_.ownerExternalId), None, "the bot API keeps working with no account at all")

  test(
    "clientIp trusts CF-Connecting-IP / X-Forwarded-For from private/loopback proxies, but ignores from public peers"
  ):
    import com.comcast.ip4s.*
    import org.typelevel.ci.*

    def withRemote(req: Request[IO], ip: IpAddress): Request[IO] =
      req.withAttribute(
        Request.Keys.ConnectionInfo,
        Request.Connection(
          local = SocketAddress(ip"127.0.0.1", port"8080"),
          remote = SocketAddress(ip, port"54321"),
          secure = false
        )
      )

    val loopbackPeer = withRemote(Request[IO](Method.GET, uri"/bot/account"), ip"127.0.0.1")
      .putHeaders(org.http4s.Header.Raw(ci"CF-Connecting-IP", "203.0.113.195"))
    assertEquals(BotRoutes.clientIp(loopbackPeer), "203.0.113.195", "trusts CF-Connecting-IP behind loopback/tunnel")

    val privateBridgePeer = withRemote(Request[IO](Method.GET, uri"/bot/account"), ip"172.18.0.4")
      .putHeaders(org.http4s.Header.Raw(ci"X-Forwarded-For", "198.51.100.42, 172.18.0.4"))
    assertEquals(BotRoutes.clientIp(privateBridgePeer), "198.51.100.42", "trusts X-Forwarded-For behind private bridge")

    val publicPeer = withRemote(Request[IO](Method.GET, uri"/bot/account"), ip"198.51.100.99")
      .putHeaders(
        org.http4s.Header.Raw(ci"CF-Connecting-IP", "1.2.3.4"),
        org.http4s.Header.Raw(ci"X-Forwarded-For", "5.6.7.8")
      )
    assertEquals(BotRoutes.clientIp(publicPeer), "198.51.100.99", "ignores spoofed headers from direct public peer")

/** Just enough `UserStore` for the session check in the register route. */
final private class OwnerStubUsers(ref: cats.effect.Ref[IO, Map[String, dicechess.play.store.UserAccount]])
    extends dicechess.play.store.UserStore:
  import dicechess.play.store.*
  def upsertOnLogin(p: String, s: String, e: Option[String], n: IO[String]): IO[UserAccount] =
    (n, IO.realTimeInstant).flatMapN { (nickname, now) =>
      ref.modify { users =>
        val user = UserAccount(java.util.UUID.randomUUID().toString, nickname, now, Some(now), isActive = true)
        (users.updated(s, user), user)
      }
    }
  def userById(id: String): IO[Option[UserAccount]]                        = ref.get.map(_.values.find(_.id == id))
  def byNickname(nickname: String): IO[Option[UserAccount]]                = IO.pure(None)
  def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.raiseError(AssertionError("unused"))
  def linkGuest(userId: String, guestId: String): IO[GuestLink]            = IO.raiseError(AssertionError("unused"))
  def guestsOf(userId: String): IO[List[String]]                           = IO.pure(Nil)
  def deleteUser(userId: String): IO[Boolean]                              = IO.raiseError(AssertionError("unused"))
