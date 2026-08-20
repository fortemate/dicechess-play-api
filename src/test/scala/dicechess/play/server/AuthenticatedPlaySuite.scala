package dicechess.play.server

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import dicechess.play.core.*
import dicechess.play.store.{
  BotCatalogListing,
  BotCatalogState,
  BotCatalogStore,
  BotRating,
  BotSeatPolicy,
  GameStore,
  GuestLink,
  NicknameUpdate,
  UserAccount,
  UserRating,
  UserStore
}
import dicechess.play.wire.Codecs.given
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.websocket.{WSFrame, WSRequest}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.{JdkHttpClient, JdkWSClient}
import org.http4s.{Header, Headers, HttpApp, Method, Request, RequestCookie, Status, Uri}
import org.typelevel.ci.*

import java.util.UUID
import scala.concurrent.duration.*

/** Identity on the game-start paths (#235, ADR-0017): **the session wins, the body is only ever a guest fallback.** The
  * property that matters most here is negative — a signed-in caller cannot be made to act as someone else by anything
  * in the request body, and no body field can ever name a `user:` principal.
  */
class AuthenticatedPlaySuite extends munit.CatsEffectSuite:

  private val Secret  = "test-session-secret"
  private val GuestId = "77777777-7777-7777-7777-777777777777"
  private val OtherId = "88888888-8888-8888-8888-888888888888"

  final private class StubUsers(ref: Ref[IO, Map[String, UserAccount]]) extends UserStore:
    def upsertOnLogin(
        provider: String,
        subject: String,
        email: Option[String],
        freshNickname: IO[String]
    ): IO[UserAccount] =
      (freshNickname, IO.realTimeInstant).flatMapN { (nickname, now) =>
        ref.modify { users =>
          users.get(subject) match
            case Some(existing) => (users, existing)
            case None           =>
              val user = UserAccount(UUID.randomUUID().toString, nickname, now, Some(now), isActive = true)
              (users.updated(subject, user), user)
        }
      }
    def userById(id: String): IO[Option[UserAccount]]         = ref.get.map(_.values.find(_.id == id))
    def byNickname(nickname: String): IO[Option[UserAccount]] =
      ref.get.map(_.values.find(_.nickname.equalsIgnoreCase(nickname)))
    def ratingOf(userId: String): IO[Option[UserRating]] =
      ref.get.map(_.values.find(_.id == userId).map(_ => UserRating.initial))
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.raiseError(AssertionError("unused"))
    def linkGuest(userId: String, guestId: String): IO[GuestLink]            = IO.raiseError(AssertionError("unused"))
    def guestsOf(userId: String): IO[List[String]]                           = IO.raiseError(AssertionError("unused"))
    def deleteUser(userId: String): IO[Boolean]                              = IO.raiseError(AssertionError("unused"))

  /** A registry plus routes wired with a real `AuthSession`, and one signed-in account's cookie. */
  private def fixture: IO[(GameRegistry, HttpApp[IO], HttpApp[IO], UserAccount, String)] =
    for
      registry <- GameRegistry.create()
      lobby    <- Lobby.create(registry)
      users    <- Ref.of[IO, Map[String, UserAccount]](Map.empty).map(StubUsers(_))
      session = AuthSession(users, Secret)
      user  <- users.upsertOnLogin("google", "sub-play", None, IO.pure("PlayNick"))
      token <- session.sign(user)
      // PlayRoutes needs a WebSocketBuilder2, which only exists inside a server; the HTTP half of the route set is
      // exercised through a plain HttpApp here and the WS seat-resolve path in PlayRoutesSuite's real server.
      lobbyApp = LobbyRoutes(lobby, Some(session)).orNotFound
      seekApp  = LobbyRoutes(lobby, None).orNotFound
    yield (registry, lobbyApp, seekApp, user, token)

  private val CatalogTeam    = "acme"
  private val CatalogBotName = "alice"

  /** `CatalogRoutes` needs the two bot seams; only the catalog membership and seat policy matter for `play-bot`, so
    * everything else answers "nothing" rather than pulling in a database.
    */
  private def catalogFixture: IO[(GameRegistry, HttpApp[IO], UserAccount, String)] =
    val bot: Principal.Bot = Principal.Bot(CatalogTeam, CatalogBotName)
    val bots               = new UnownedBotStore:
      def authenticate(tokenHash: String): IO[Option[Principal.Bot]]                               = IO.pure(None)
      def rotate(team: String, name: String, newTokenHash: String): IO[Boolean]                    = IO.pure(false)
      def ratingOf(team: String, name: String): IO[Option[BotRating]]                              = IO.pure(None)
      def setOnLadder(team: String, name: String, onLadder: Boolean): IO[Option[BotRating]]        = IO.pure(None)
      def onLadderCandidates: IO[List[BotSeatPolicy]]                                              = IO.pure(Nil)
      def setMaxConcurrentGames(team: String, name: String, limit: Int): IO[Option[BotSeatPolicy]] = IO.pure(None)
      def seatPolicyOf(team: String, name: String): IO[Option[BotSeatPolicy]]                      = IO.pure(None)
      def openToHumans(team: String, name: String, description: Option[String]): IO[Option[BotCatalogState]] =
        IO.pure(None)
      def closeToHumans(team: String, name: String): IO[Option[BotCatalogState]] = IO.pure(None)
      def openToHumansBots: IO[List[Principal.Bot]]                              = IO.pure(List(bot))
    val catalog = new BotCatalogStore:
      def catalogBots: IO[List[BotCatalogListing]] = IO.pure(Nil)
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      users    <- Ref.of[IO, Map[String, UserAccount]](Map.empty).map(StubUsers(_))
      session = AuthSession(users, Secret)
      user  <- users.upsertOnLogin("google", "sub-catalog", None, IO.pure("CatalogNick"))
      token <- session.sign(user)
      wake  <- AnonMintLimiter.create()
      plays <- AnonMintLimiter.create()
    yield (registry, CatalogRoutes(catalog, bots, None, registry, wake, plays, Some(session)).orNotFound, user, token)

  private def createSeek(app: HttpApp[IO], token: Option[String], creator: Option[String]) =
    val base = Request[IO](Method.POST, uri"/lobby/seeks").withEntity(CreateSeek(creator))
    app.run(token.fold(base)(t => base.addCookie(RequestCookie(AuthSession.SessionCookieName, t))))

  /** `Seek` is anonymized on the wire by design (`core/Seek.scala`: humans never leak an id), so the creator's actual
    * principal is asserted where it becomes observable — the seating of the game the accept produces.
    */
  private def creatorOf(registry: GameRegistry, app: HttpApp[IO], seekId: String, accepter: String): IO[Principal] =
    app
      .run(Request[IO](Method.POST, uri"/lobby/seeks" / seekId / "accept").withEntity(AcceptSeek(Some(accepter))))
      .flatMap(_.as[SeekMatch])
      .flatMap { m =>
        registry.get(GameId(m.gameId)).flatMap {
          case None       => IO.raiseError(AssertionError(s"game ${m.gameId} not found"))
          case Some(room) =>
            room.seating.map { seats =>
              seats.values.find(_ != Principal.Guest(accepter)).getOrElse(fail("no creator seat"))
            }
        }
      }

  test("a signed-in caller seeks as their account, and a body guest id cannot override it"):
    for
      (registry, app, _, user, token) <- fixture
      // The body names a completely different identity; the session must win.
      res     <- createSeek(app, Some(token), Some(GuestId))
      seek    <- res.as[CreatedSeek]
      creator <- creatorOf(registry, app, seek.seekId, OtherId)
    yield
      assertEquals(res.status, Status.Created)
      assertEquals(
        creator,
        Principal.User(user.id),
        "the seat must belong to the session's account, not the body's guest id"
      )

  test("without a session the body guest id is still honoured, and its absence is a 400"):
    for
      (registry, app, _, _, _) <- fixture
      anonymous                <- createSeek(app, None, Some(GuestId))
      seek                     <- anonymous.as[CreatedSeek]
      missing                  <- createSeek(app, None, None)
      reason                   <- missing.bodyText.compile.string
      malformed                <- createSeek(app, None, Some("not-a-uuid"))
      badReason                <- malformed.bodyText.compile.string
      creator                  <- creatorOf(registry, app, seek.seekId, OtherId)
    yield
      assertEquals(anonymous.status, Status.Created)
      assertEquals(missing.status, Status.BadRequest, "no session and no creator id is unusable")
      assertEquals(malformed.status, Status.BadRequest)
      // Both rejection branches name the field exactly once: the route answers the message verbatim rather than
      // prefixing one that already carries the field.
      assertEquals(reason, "\"creator is required when not signed in\"")
      assert(badReason.startsWith("\"creator: "), badReason)
      assert(!badReason.contains("creator: creator"), s"the field name must not be doubled: $badReason")
      assertEquals(creator, Principal.Guest(GuestId))

  test("a deployment without sign-in configured behaves exactly as before"):
    for
      (registry, _, noAuth, _, token) <- fixture
      // The cookie is valid, but this route set was built without a session: it must fall back to the body.
      res     <- createSeek(noAuth, Some(token), Some(GuestId))
      seek    <- res.as[CreatedSeek]
      creator <- creatorOf(registry, noAuth, seek.seekId, OtherId)
    yield
      assertEquals(res.status, Status.Created)
      assertEquals(creator, Principal.Guest(GuestId))

  test("a signed-in accepter takes their seat as their account, ignoring the body id"):
    for
      (registry, app, _, user, token) <- fixture
      // An anonymous guest posts the seek; the signed-in player accepts it while naming someone else in the body.
      created <- createSeek(app, None, Some(GuestId))
      seek    <- created.as[CreatedSeek]
      accept = Request[IO](Method.POST, uri"/lobby/seeks" / seek.seekId / "accept")
        .withEntity(AcceptSeek(Some(OtherId)))
        .addCookie(RequestCookie(AuthSession.SessionCookieName, token))
      matched <- app.run(accept).flatMap(_.as[SeekMatch])
      seats   <- registry.get(GameId(matched.gameId)).flatMap {
        case None       => IO.raiseError(AssertionError("game not found"))
        case Some(room) => room.seating
      }
    yield assertEquals(
      seats.values.toSet,
      Set[Principal](Principal.Guest(GuestId), Principal.User(user.id)),
      "the accepter must be the session's account, never the body's id"
    )

  test("POST /games seats a signed-in creator on both sides, and anonymously both ids are required"):
    (wsServer, JdkHttpClient.simple[IO]).tupled.use { case ((port, registry, user, token), http) =>
      val games = Uri.unsafeFromString(s"http://127.0.0.1:$port") / "games"
      val body  = CreateGame(Some(GuestId), Some(OtherId))
      for
        // A valid session plus a body naming two other identities: the session must own both seats.
        created <- http.expect[CreatedGame](
          Request[IO](Method.POST, games)
            .withEntity(body)
            .addCookie(RequestCookie(AuthSession.SessionCookieName, token))
        )
        seats <- registry.get(GameId(created.gameId)).flatMap {
          case None       => IO.raiseError(AssertionError("game not found"))
          case Some(room) => room.seating
        }
        // Anonymous, one field missing: the fallback still demands it.
        halfBody  <- http.status(Request[IO](Method.POST, games).withEntity(CreateGame(Some(GuestId), None)))
        anonymous <- http.status(Request[IO](Method.POST, games).withEntity(CreateGame(None, None)))
      yield
        assertEquals(
          seats.values.toSet,
          Set[Principal](Principal.User(user.id)),
          "both seats belong to the signed-in creator, not the body's ids"
        )
        assertEquals(halfBody, Status.BadRequest)
        assertEquals(anonymous, Status.BadRequest)
    }

  test("POST /lobby/play-bot seats a signed-in caller as their account, ignoring the body guest id"):
    for
      (registry, catalog, user, token) <- catalogFixture
      played                           <- catalog.run(
        Request[IO](Method.POST, uri"/lobby/play-bot")
          .withEntity(PlayBot(Some(GuestId), CatalogTeam, CatalogBotName, TimeControl.Fischer(300, 3)))
          .addCookie(RequestCookie(AuthSession.SessionCookieName, token))
      )
      matched <- played.as[SeekMatch]
      seats   <- registry.get(GameId(matched.gameId)).flatMap {
        case None       => IO.raiseError(AssertionError("game not found"))
        case Some(room) => room.seating
      }
      // Anonymous with no guest id at all: still a 400, the fallback is required.
      missing <- catalog.run(
        Request[IO](Method.POST, uri"/lobby/play-bot")
          .withEntity(PlayBot(None, CatalogTeam, CatalogBotName, TimeControl.Fischer(300, 3)))
      )
    yield
      assertEquals(played.status, Status.Created)
      assertEquals(
        seats.values.toSet,
        Set[Principal](Principal.User(user.id), Principal.Bot(CatalogTeam, CatalogBotName)),
        "the human seat must be the session's account, not the body's guest id"
      )
      assertEquals(missing.status, Status.BadRequest)

  // The rated-eligibility matrix for `Principal.User` (user-vs-user, user-vs-registered-bot, and the guest/anon-bot
  // exclusions) is already asserted exhaustively in `GameRegistrySuite`'s isRated section — the policy lit up as-is
  // when accounts arrived, which is exactly what #235 predicted, so it is verified there rather than duplicated here.

  // ── Tokenless WebSocket seat resolve (the lost-?seat= fix) ───────────────────

  /** An Ember server whose `PlayRoutes` carry a real session, plus one signed-in account's cookie. A live server is
    * required because seat resolution happens at the WebSocket upgrade, which needs a `WebSocketBuilder2`.
    */
  private def wsServer: Resource[IO, (Int, GameRegistry, UserAccount, String)] =
    for
      registry <- Resource.eval(GameRegistry.create(disconnectGrace = 500.millis))
      users    <- Resource.eval(Ref.of[IO, Map[String, UserAccount]](Map.empty).map(StubUsers(_)))
      session = AuthSession(users, Secret)
      user  <- Resource.eval(users.upsertOnLogin("google", "sub-ws", None, IO.pure("WsNick")))
      token <- Resource.eval(session.sign(user))
      srv   <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withShutdownTimeout(1.second)
        .withHttpWebSocketApp(wsb => PlayRoutes(registry, wsb, Some(session)).orNotFound)
        .build
    yield (srv.address.getPort, registry, user, token)

  private def sessionWs(uri: Uri, token: String): WSRequest =
    WSRequest(uri).withHeaders(Headers(Header.Raw(ci"Cookie", s"${AuthSession.SessionCookieName}=$token")))

  /** Whether the game is over, from the outside: a room that has ended is also **evicted** from the registry
    * (`GameRegistry.finish`), so absence is a terminal state here, not a failure — reading it as one made this a
    * CI-only flake, since locally the poll happened to observe `Ended` before the eviction landed.
    */
  private def isOver(registry: GameRegistry, id: String): IO[Boolean] =
    registry.get(GameId(id)).flatMap {
      case None       => IO.pure(true)
      case Some(room) => room.snapshot.map(_.status != GameStatus.Active)
    }

  /** Poll (never sleep-then-assert) until the game is over, or report `false` when the window closes — which is the
    * assertion itself for the ambiguous-seat case. The pause keeps this from becoming a hot loop for the full window.
    */
  private def awaitOver(registry: GameRegistry, id: String, within: FiniteDuration): IO[Boolean] =
    isOver(registry, id)
      .flatMap(over => if over then IO.pure(true) else IO.sleep(50.millis).as(false))
      .iterateUntil(identity)
      .timeoutTo(within, IO.pure(false))

  test("a signed-in player reconnects to their seat with no join token — the lost-URL fix"):
    (wsServer, JdkWSClient.simple[IO]).tupled.use { case ((port, registry, user, token), ws) =>
      val wsBase = Uri.unsafeFromString(s"ws://127.0.0.1:$port")
      for
        created <- registry.create(Principal.User(user.id), Principal.Guest(GuestId))
        gameId  <- IO.fromEither(created.left.map(e => AssertionError(e))).map(_._1.value)
        // No ?seat= token at all — only the session cookie. A spectator could not resign.
        _ <- ws
          .connectHighLevel(sessionWs(wsBase / "games" / gameId / "ws", token))
          .use(_.send(WSFrame.Text(GameCommand.Resign.asJson.noSpaces)))
        over <- awaitOver(registry, gameId, 10.seconds)
      yield assert(over, "the resign was accepted, so the seat was resolved from the session")
    }

  test("a signed-in player holding BOTH seats stays a spectator without a token"):
    (wsServer, JdkWSClient.simple[IO]).tupled.use { case ((port, registry, user, token), ws) =>
      val wsBase = Uri.unsafeFromString(s"ws://127.0.0.1:$port")
      val me     = Principal.User(user.id)
      for
        // The friend-by-link shape: the creator owns both seats until the share link is used, so the session cannot
        // name one seat — the join token stays the only way in.
        created <- registry.create(me, me)
        gameId  <- IO.fromEither(created.left.map(e => AssertionError(e))).map(_._1.value)
        _       <- ws
          .connectHighLevel(sessionWs(wsBase / "games" / gameId / "ws", token))
          .use(_.send(WSFrame.Text(GameCommand.Resign.asJson.noSpaces)))
        // Poll durable state rather than sleeping on a stream: the resign must have been ignored outright. The window
        // is the negative half of the test — it has to be long enough that an accepted resign would have landed.
        over <- awaitOver(registry, gameId, 2.seconds)
      yield assert(!over, "an ambiguous session must not be able to resign either seat")
    }
