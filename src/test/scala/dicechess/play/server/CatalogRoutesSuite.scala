package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{Principal, Seat, Side, TimeControl}
import dicechess.play.store.{
  BotCatalogListing,
  BotCatalogState,
  BotCatalogStore,
  BotRating,
  BotSeatPolicy,
  BotStore,
  GameStore,
  WebhookStore
}
import io.circe.Json
import io.circe.parser.{decode, parse}
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.{HttpApp, HttpRoutes, Method, Request, Status, Uri}

import scala.concurrent.duration.*

/** The public bot-catalog wire (ADR-0014, E2/E3/E4) over stub stores — the SQL behind the listing is covered against
  * real Postgres in `PgGameStoreSuite`, and the webhook delivery mechanics (signing, timeouts, size caps) in
  * `WebhooksSuite`; here the subject is the HTTP layer: the response shapes (pinned as JSON), the provisional flag
  * derived from RD, the wake route's gating (catalog membership, feature-disabled, rate limit) plus its liveness
  * outcome against a scripted endpoint, and play-bot's gating (validation, the 1-active-game limit, catalog membership,
  * rate limit) plus its seat assignment against a real `GameRegistry`.
  */
class CatalogRoutesSuite extends munit.CatsEffectSuite:

  private val allowAll: String => IO[Either[String, Uri]] =
    url => IO.pure(Uri.fromString(url).left.map(_ => "not a valid URL"))

  private val webhookConfig = Webhooks.Config(timeout = 5.seconds)

  private val guestA = "11111111-1111-1111-1111-111111111111"

  /** `declared` holds the capacities of registered bots (#189). A bot absent from it has no row and is therefore
    * unbounded — which is what every pre-#189 test in this file relies on.
    */
  private def stubBots(open: Set[(String, String)], declared: Map[(String, String), Int]): BotStore =
    new UnownedBotStore:
      def authenticate(tokenHash: String): IO[Option[Principal.Bot]]                               = IO.pure(None)
      def rotate(team: String, name: String, newTokenHash: String): IO[Boolean]                    = IO.pure(false)
      def ratingOf(team: String, name: String): IO[Option[BotRating]]                              = IO.pure(None)
      def setOnLadder(team: String, name: String, onLadder: Boolean): IO[Option[BotRating]]        = IO.pure(None)
      def onLadderCandidates: IO[List[BotSeatPolicy]]                                              = IO.pure(Nil)
      def setMaxConcurrentGames(team: String, name: String, limit: Int): IO[Option[BotSeatPolicy]] = IO.pure(None)
      def seatPolicyOf(team: String, name: String): IO[Option[BotSeatPolicy]]                      =
        IO.pure(
          declared
            .get((team, name))
            .map(BotSeatPolicy(Principal.Bot(team, name), _, openToHumans = open.contains((team, name))))
        )
      def openToHumans(team: String, name: String, description: Option[String]): IO[Option[BotCatalogState]] =
        IO.pure(None)
      def closeToHumans(team: String, name: String): IO[Option[BotCatalogState]] = IO.pure(None)
      def openToHumansBots: IO[List[Principal.Bot]] = IO.pure(open.toList.map(Principal.Bot(_, _)))

  private def stubCatalog(listings: List[BotCatalogListing]): BotCatalogStore = new BotCatalogStore:
    def catalogBots: IO[List[BotCatalogListing]] = IO.pure(listings)

  private def request(method: Method, uri: String): Request[IO] = Request[IO](method, Uri.unsafeFromString(uri))

  private def app(
      registry: GameRegistry,
      listings: List[BotCatalogListing] = Nil,
      open: Set[(String, String)] = Set.empty,
      webhooks: Option[Webhooks] = None,
      wakeLimiter: Option[AnonMintLimiter] = None,
      playBotLimiter: Option[AnonMintLimiter] = None,
      declared: Map[(String, String), Int] = Map.empty
  ): IO[HttpRoutes[IO]] =
    (
      wakeLimiter.fold(AnonMintLimiter.create())(IO.pure),
      playBotLimiter.fold(AnonMintLimiter.create())(IO.pure)
    ).mapN((wake, playBot) =>
      CatalogRoutes(stubCatalog(listings), stubBots(open, declared), webhooks, registry, wake, playBot)
    )

  private def freshRegistry: IO[GameRegistry] = GameRegistry.create(store = GameStore.noop)

  /** A fake bot endpoint that only ever answers the ownership/wake handshake — echoes whatever nonce it is sent, real
    * `yourTurn` delivery is out of scope here (covered end-to-end in `WebhooksSuite`).
    */
  private val echoingEndpoint: HttpApp[IO] = HttpApp[IO] { req =>
    req.bodyText.compile.string.flatMap { body =>
      decode[WebhookVerification](body) match
        case Right(v) => Ok(Json.obj("nonce" -> v.nonce.asJson))
        case Left(_)  => BadRequest()
    }
  }

  private val deadEndpoint: HttpApp[IO] = HttpApp[IO](_ => InternalServerError())

  test("GET /lobby/bots returns catalog cards, derives provisional from RD, and pins the wire shape"):
    val listings = List(
      BotCatalogListing("acme", "alice", 1720.5, 85.0, Some("aggressive + book"), maxConcurrentGames = 4),
      // RD above the threshold: provisional, but still listed. A neither-busy declaration (1, no active games).
      BotCatalogListing("acme", "fresh", 1500.0, 350.0, None, maxConcurrentGames = 1)
    )
    val expected = parse(
      """{"bots":[
           {"team":"acme","name":"alice","rating":1720.5,"rd":85.0,"provisional":false,"description":"aggressive + book","available":true},
           {"team":"acme","name":"fresh","rating":1500.0,"rd":350.0,"provisional":true,"description":null,"available":true}
         ]}"""
    ).toOption.get
    for
      registry <- freshRegistry
      routes   <- app(registry, listings)
      resp     <- routes.orNotFound.run(request(Method.GET, "/lobby/bots"))
      body     <- resp.as[Json]
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(body, expected, "the catalog shape is a contract — pin it")

  test("GET /lobby/bots flags available:false for a bot at its declared capacity, true once room is left (#224)"):
    val listings = List(
      BotCatalogListing("acme", "alice", 1720.5, 85.0, None, maxConcurrentGames = 1),
      BotCatalogListing("acme", "bob", 1600.0, 90.0, None, maxConcurrentGames = 2)
    )
    for
      registry <- freshRegistry
      // Both bots play one game each: alice's single slot is spent, bob still has one of his two free.
      _      <- registry.create(Principal.Bot("acme", "alice"), Principal.Bot("filler", "one"))
      _      <- registry.create(Principal.Bot("acme", "bob"), Principal.Bot("filler", "two"))
      routes <- app(registry, listings)
      resp   <- routes.orNotFound.run(request(Method.GET, "/lobby/bots"))
      body   <- resp.as[BotCatalog]
    yield
      assertEquals(body.bots.find(_.name == "alice").map(_.available), Some(false))
      assertEquals(body.bots.find(_.name == "bob").map(_.available), Some(true))

  test("GET /lobby/bots is an empty list when no bot is open to humans"):
    for
      registry <- freshRegistry
      routes   <- app(registry)
      resp     <- routes.orNotFound.run(request(Method.GET, "/lobby/bots"))
      body     <- resp.as[Json]
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(body.hcursor.downField("bots").values.map(_.size), Some(0))

  test("POST /lobby/bots/{team}/{name}/wake is 404 for a name outside the catalog"):
    for
      registry <- freshRegistry
      routes   <- app(registry, open = Set(("acme", "alice")))
      resp     <- routes.orNotFound.run(request(Method.POST, "/lobby/bots/acme/ghost/wake"))
    yield assertEquals(resp.status, Status.NotFound)

  test("POST /lobby/bots/{team}/{name}/wake is 503 when webhooks are disabled on the server"):
    for
      registry <- freshRegistry
      routes   <- app(registry, open = Set(("acme", "alice")), webhooks = None)
      resp     <- routes.orNotFound.run(request(Method.POST, "/lobby/bots/acme/alice/wake"))
    yield assertEquals(resp.status, Status.ServiceUnavailable)

  test(
    "POST /lobby/bots/{team}/{name}/wake is busy:true, NOT 503, for a busy bot on a server with webhooks disabled (#224)"
  ):
    // The capacity check runs before the webhooks match (CatalogRoutes.scala), specifically so busy is a per-bot
    // fact independent of whether the feature flag is on at all — this pins that ordering against regressing back
    // to a 503, which the plain "webhooks disabled" test above can't catch (that one uses an unbounded bot).
    for
      registry <- freshRegistry
      _        <- registry.create(Principal.Bot("acme", "alice"), Principal.Bot("filler", "one"))
      routes   <- app(
        registry,
        open = Set(("acme", "alice")),
        webhooks = None,
        declared = Map(("acme", "alice") -> 1)
      )
      resp <- routes.orNotFound.run(request(Method.POST, "/lobby/bots/acme/alice/wake"))
      body <- resp.as[Wake]
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(body, Wake(alive = false, busy = true))

  test("POST /lobby/bots/{team}/{name}/wake is alive:true when the endpoint echoes the nonce"):
    for
      registry <- freshRegistry
      store    <- WebhookStore.inMemory
      outcome  <- Webhooks.create(registry, store, Client.fromHttpApp(echoingEndpoint), webhookConfig, allowAll).use {
        webhooks =>
          for
            _      <- webhooks.register(Principal.Bot("acme", "alice"), "https://bot.example/hook")
            routes <- app(registry, open = Set(("acme", "alice")), webhooks = Some(webhooks))
            resp   <- routes.orNotFound.run(request(Method.POST, "/lobby/bots/acme/alice/wake"))
            body   <- resp.as[Wake]
          yield (resp.status, body)
      }
    yield
      assertEquals(outcome._1, Status.Ok)
      assertEquals(outcome._2, Wake(alive = true))

  test("POST /lobby/bots/{team}/{name}/wake is alive:false for a catalog bot with no registered webhook"):
    for
      registry <- freshRegistry
      store    <- WebhookStore.inMemory
      outcome  <- Webhooks.create(registry, store, Client.fromHttpApp(deadEndpoint), webhookConfig, allowAll).use {
        webhooks =>
          // Deliberately not registered: a catalog-eligible bot whose webhook was never (or no longer) wired.
          for
            routes <- app(registry, open = Set(("acme", "alice")), webhooks = Some(webhooks))
            resp   <- routes.orNotFound.run(request(Method.POST, "/lobby/bots/acme/alice/wake"))
            body   <- resp.as[Wake]
          yield (resp.status, body)
      }
    yield
      assertEquals(outcome._1, Status.Ok)
      assertEquals(outcome._2, Wake(alive = false))

  test(
    "POST /lobby/bots/{team}/{name}/wake is busy:true without probing a bot at its declared capacity (#224)"
  ):
    for
      registry <- freshRegistry
      store    <- WebhookStore.inMemory
      probes   <- cats.effect.Ref.of[IO, Int](0)
      // The probe would answer alive:true if it ran at all — this test's whole point is that it must not run.
      countingEndpoint = HttpApp[IO] { req =>
        probes.update(_ + 1) *> req.bodyText.compile.string.flatMap { body =>
          decode[WebhookVerification](body) match
            case Right(v) => Ok(Json.obj("nonce" -> v.nonce.asJson))
            case Left(_)  => BadRequest()
        }
      }
      outcome <-
        Webhooks.create(registry, store, Client.fromHttpApp(countingEndpoint), webhookConfig, allowAll).use { webhooks =>
          for
            _ <- webhooks.register(Principal.Bot("acme", "alice"), "https://bot.example/hook")
            // `register` itself probes the endpoint once, for the ownership handshake — reset the counter after it,
            // so what remains counts only calls the `wake` route makes.
            _ <- probes.set(0)
            // Declares one slot and immediately spends it — the bot is busy before the request ever arrives.
            _      <- registry.create(Principal.Bot("acme", "alice"), Principal.Bot("filler", "one"))
            routes <- app(
              registry,
              open = Set(("acme", "alice")),
              webhooks = Some(webhooks),
              declared = Map(("acme", "alice") -> 1)
            )
            resp      <- routes.orNotFound.run(request(Method.POST, "/lobby/bots/acme/alice/wake"))
            body      <- resp.as[Wake]
            probeRuns <- probes.get
          yield (resp.status, body, probeRuns)
        }
    yield
      assertEquals(outcome._1, Status.Ok)
      assertEquals(outcome._2, Wake(alive = false, busy = true))
      assertEquals(outcome._3, 0, "a busy bot must never be woken — the whole point of checking capacity first")

  test("POST /lobby/bots/{team}/{name}/wake ignores capacity for a bot with no declared row (static/anonymous, #224)"):
    for
      registry <- freshRegistry
      store    <- WebhookStore.inMemory
      outcome  <- Webhooks.create(registry, store, Client.fromHttpApp(echoingEndpoint), webhookConfig, allowAll).use {
        webhooks =>
          for
            _ <- webhooks.register(Principal.Bot("acme", "alice"), "https://bot.example/hook")
            // No entry in `declared`: seatPolicyOf answers None, same as a static/anonymous identity — unbounded.
            routes <- app(registry, open = Set(("acme", "alice")), webhooks = Some(webhooks))
            resp   <- routes.orNotFound.run(request(Method.POST, "/lobby/bots/acme/alice/wake"))
            body   <- resp.as[Wake]
          yield (resp.status, body)
      }
    yield
      assertEquals(outcome._1, Status.Ok)
      assertEquals(outcome._2, Wake(alive = true, busy = false))

  test("POST /lobby/bots/{team}/{name}/wake is 429 once the rate limit is spent"):
    for
      registry <- freshRegistry
      store    <- WebhookStore.inMemory
      outcome  <- Webhooks.create(registry, store, Client.fromHttpApp(echoingEndpoint), webhookConfig, allowAll).use {
        webhooks =>
          for
            _       <- webhooks.register(Principal.Bot("acme", "alice"), "https://bot.example/hook")
            limiter <- AnonMintLimiter.create(limit = 1)
            routes  <- app(
              registry,
              open = Set(("acme", "alice")),
              webhooks = Some(webhooks),
              wakeLimiter = Some(limiter)
            )
            first  <- routes.orNotFound.run(request(Method.POST, "/lobby/bots/acme/alice/wake"))
            second <- routes.orNotFound.run(request(Method.POST, "/lobby/bots/acme/alice/wake"))
          yield (first.status, second.status)
      }
    yield
      assertEquals(outcome._1, Status.Ok)
      assertEquals(outcome._2, Status.TooManyRequests)

  private def playBotBody(
      guestId: String = guestA,
      team: String = "acme",
      name: String = "alice",
      timeControl: TimeControl = TimeControl.Fischer(300, 5),
      preferredColor: Option[Side] = None
  ): Request[IO] =
    request(Method.POST, "/lobby/play-bot").withEntity(PlayBot(Some(guestId), team, name, timeControl, preferredColor))

  test("POST /lobby/play-bot seats the guest on its preferred color and the bot on the other"):
    val guestB = "22222222-2222-2222-2222-222222222222"
    for
      registry <- freshRegistry
      routes   <- app(registry, open = Set(("acme", "alice")))
      white    <- routes.orNotFound
        .run(playBotBody(guestId = guestA, preferredColor = Some(Side.White)))
        .flatMap(_.as[SeekMatch])
      // A different guest: the first guest's now-active game must not block this one, and this pins the OTHER branch
      // of seatAssignment in the same test.
      black <- routes.orNotFound
        .run(playBotBody(guestId = guestB, preferredColor = Some(Side.Black)))
        .flatMap(_.as[SeekMatch])
    yield
      assertEquals(white.seat, Seat.White)
      assert(white.gameId.nonEmpty && white.token.nonEmpty)
      assertEquals(black.seat, Seat.Black)

  test("POST /lobby/play-bot picks a seat when no color is preferred"):
    for
      registry <- freshRegistry
      routes   <- app(registry, open = Set(("acme", "alice")))
      resp     <- routes.orNotFound.run(playBotBody(preferredColor = None))
      body     <- resp.as[SeekMatch]
    yield
      assertEquals(resp.status, Status.Created)
      assert(body.seat == Seat.White || body.seat == Seat.Black, s"expected a playing seat, got ${body.seat}")

  test("POST /lobby/play-bot is 400 for an unlimited time control"):
    for
      registry <- freshRegistry
      routes   <- app(registry, open = Set(("acme", "alice")))
      resp     <- routes.orNotFound.run(playBotBody(timeControl = TimeControl.Unlimited))
    yield assertEquals(resp.status, Status.BadRequest)

  test("POST /lobby/play-bot is 400 for a guestId that isn't a UUID"):
    for
      registry <- freshRegistry
      routes   <- app(registry, open = Set(("acme", "alice")))
      resp     <- routes.orNotFound.run(playBotBody(guestId = "not-a-uuid"))
    yield assertEquals(resp.status, Status.BadRequest)

  test("POST /lobby/play-bot is 400 for a malformed body"):
    for
      registry <- freshRegistry
      routes   <- app(registry, open = Set(("acme", "alice")))
      resp     <- routes.orNotFound.run(
        request(Method.POST, "/lobby/play-bot").withEntity(Json.obj("guestId" -> Json.fromInt(123)))
      )
    yield assertEquals(resp.status, Status.BadRequest)

  test("POST /lobby/play-bot is 404 for a name outside the catalog"):
    for
      registry <- freshRegistry
      routes   <- app(registry, open = Set(("acme", "alice")))
      resp     <- routes.orNotFound.run(playBotBody(team = "acme", name = "ghost"))
    yield assertEquals(resp.status, Status.NotFound)

  test("POST /lobby/play-bot is 409 for a guest that already has an active game — checked before catalog membership"):
    for
      registry <- freshRegistry
      routes   <- app(registry, open = Set(("acme", "alice")))
      first    <- routes.orNotFound.run(playBotBody()).map(_.status)
      // Targets a name outside the catalog: still 409, not 404 — the active-game gate runs first.
      second <- routes.orNotFound.run(playBotBody(team = "acme", name = "ghost")).map(_.status)
    yield
      assertEquals(first, Status.Created)
      assertEquals(second, Status.Conflict)

  test("POST /lobby/play-bot is 409 once the bot is at its declared capacity — an answer, not a silent board (#189)"):
    val guestB = "22222222-2222-2222-2222-222222222222"
    for
      registry <- freshRegistry
      routes   <- app(registry, open = Set(("acme", "alice")), declared = Map(("acme", "alice") -> 1))
      first    <- routes.orNotFound.run(playBotBody(guestId = guestA)).map(_.status)
      // A different guest, so the refusal can only be the bot's own limit, not the one-game-per-guest rule.
      second <- routes.orNotFound.run(playBotBody(guestId = guestB)).map(_.status)
    yield
      assertEquals(first, Status.Created)
      assertEquals(second, Status.Conflict)

  test("POST /lobby/play-bot still seats a second visitor when the bot declared room for both (#189)"):
    val guestB = "22222222-2222-2222-2222-222222222222"
    for
      registry <- freshRegistry
      routes   <- app(registry, open = Set(("acme", "alice")), declared = Map(("acme", "alice") -> 2))
      first    <- routes.orNotFound.run(playBotBody(guestId = guestA)).map(_.status)
      second   <- routes.orNotFound.run(playBotBody(guestId = guestB)).map(_.status)
    yield
      assertEquals(first, Status.Created)
      assertEquals(second, Status.Created)

  test("POST /lobby/play-bot is 404, not 409, for a name outside the catalog even while another bot is full (#189)"):
    for
      registry <- freshRegistry
      routes   <- app(registry, open = Set(("acme", "alice")), declared = Map(("acme", "alice") -> 1))
      _        <- routes.orNotFound.run(playBotBody(guestId = guestA)).map(_.status)
      // Catalog membership is checked before capacity, so an unknown name must not leak whether anyone is busy.
      unknown <- routes.orNotFound
        .run(playBotBody(guestId = "22222222-2222-2222-2222-222222222222", name = "ghost"))
        .map(_.status)
    yield assertEquals(unknown, Status.NotFound)

  test("POST /lobby/play-bot is 429 once the rate limit is spent"):
    for
      registry <- freshRegistry
      limiter  <- AnonMintLimiter.create(limit = 1)
      routes   <- app(registry, open = Set(("acme", "alice")), playBotLimiter = Some(limiter))
      first    <- routes.orNotFound.run(playBotBody()).map(_.status)
      second   <- routes.orNotFound.run(playBotBody(guestId = "22222222-2222-2222-2222-222222222222")).map(_.status)
    yield
      assertEquals(first, Status.Created)
      assertEquals(second, Status.TooManyRequests)
