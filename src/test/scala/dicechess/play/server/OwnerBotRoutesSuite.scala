package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.store.{BotStore, GuestLink, NicknameUpdate, UserAccount, UserStore}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, RequestCookie, Status}
import org.http4s.headers.Authorization

import java.util.UUID

/** The owner's bot surface (#253, #254): claiming, listing, releasing, and driving a bot's own settings from the
  * owner's session instead of the bot's Bearer token.
  *
  * Built against a REAL in-memory `BotAuth` rather than a stub — the feature is the interaction of two credentials, so
  * stubbing the bot half away would test nothing.
  */
class OwnerBotRoutesSuite extends munit.CatsEffectSuite:

  private val Secret = "test-session-secret"

  final private class StubUsers(ref: Ref[IO, Map[String, UserAccount]]) extends UserStore:
    def upsertOnLogin(p: String, sub: String, e: Option[String], n: IO[String]): IO[UserAccount] =
      (n, IO.realTimeInstant).flatMapN { (nickname, now) =>
        ref.modify { users =>
          users.get(sub) match
            case Some(existing) => (users, existing)
            case None           =>
              val user = UserAccount(UUID.randomUUID().toString, nickname, now, Some(now), isActive = true)
              (users.updated(sub, user), user)
        }
      }
    def userById(id: String): IO[Option[UserAccount]]                        = ref.get.map(_.values.find(_.id == id))
    def byNickname(nickname: String): IO[Option[UserAccount]]                = IO.pure(None)
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.raiseError(AssertionError("unused"))
    def linkGuest(userId: String, guestId: String): IO[GuestLink]            = IO.raiseError(AssertionError("unused"))
    def guestsOf(userId: String): IO[List[String]]                           = IO.pure(Nil)
    def deleteUser(userId: String): IO[Boolean]                              = IO.raiseError(AssertionError("unused"))

  private def withBots: IO[(HttpApp[IO], UserAccount, String, BotAuth, StubUsers)] =
    for
      accounts <- Ref.of[IO, Map[String, UserAccount]](Map.empty)
      store    <- BotStore.inMemory
      auth     <- BotAuth.fromSpec("", store)
      registry <- GameRegistry.create()
      users   = StubUsers(accounts)
      session = AuthSession(users, Secret)
      user  <- users.upsertOnLogin("google", "sub-owner", None, IO.pure("OwnerNick"))
      token <- session.sign(user)
    yield (OwnerBotRoutes(session, auth, registry).orNotFound, user, token, auth, users)

  private def claim(token: Option[String], botToken: Option[String]): Request[IO] =
    val base        = Request[IO](Method.POST, uri"/me/bots/claim")
    val withSession = token.fold(base)(t => base.addCookie(RequestCookie(AuthSession.SessionCookieName, t)))
    botToken.fold(withSession)(b => withSession.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, b))))

  test("claiming a bot requires BOTH a session and the bot's own token"):
    for
      (app, _, session, auth, _) <- withBots
      registered                 <- auth.register("acme", "alice")
      botToken = registered.toOption.map(_._1).getOrElse(fail("registration failed"))
      noSession <- app.run(claim(None, Some(botToken)))
      noBot     <- app.run(claim(Some(session), None))
      both      <- app.run(claim(Some(session), Some(botToken)))
      mine      <- both.as[MyBots]
    yield
      assertEquals(noSession.status, Status.Unauthorized, "a bot token alone has nobody to claim it for")
      assertEquals(noBot.status, Status.Unauthorized, "a session alone would let anyone claim any bot")
      assertEquals(both.status, Status.Ok)
      assertEquals(mine.bots.map(b => (b.team, b.name)), List(("acme", "alice")))

  test("re-claiming your own bot is idempotent; another account's bot is a 409, never a takeover"):
    for
      (app, _, session, auth, users) <- withBots
      registered                     <- auth.register("acme", "mine")
      botToken = registered.toOption.map(_._1).getOrElse(fail("registration failed"))
      _     <- app.run(claim(Some(session), Some(botToken)))
      again <- app.run(claim(Some(session), Some(botToken)))
      // A second account, holding the very same bot token: possession is not enough to steal attribution.
      rival      <- users.upsertOnLogin("google", "sub-rival-owner", None, IO.pure("RivalOwner"))
      rivalToken <- AuthSession(users, Secret).sign(rival)
      stolen     <- app.run(claim(Some(rivalToken), Some(botToken)))
    yield
      assertEquals(again.status, Status.Ok, "a retry must not be an error")
      assertEquals(stolen.status, Status.Conflict)

  test("an anonymous bot cannot be owned — it has no row"):
    for
      (app, _, session, auth, _) <- withBots
      anon                       <- auth.mintAnon(Some("scratch"))
      res                        <- app.run(claim(Some(session), Some(anon._1)))
    yield assertEquals(res.status, Status.NotFound, "only a registered identity can be owned")

  test("releasing makes the bot claimable by another account, and only its owner may release it"):
    for
      (app, _, session, auth, users) <- withBots
      registered                     <- auth.register("acme", "handover")
      botToken = registered.toOption.map(_._1).getOrElse(fail("registration failed"))
      _          <- app.run(claim(Some(session), Some(botToken)))
      rival      <- users.upsertOnLogin("google", "sub-next-owner", None, IO.pure("NextOwner"))
      rivalToken <- AuthSession(users, Secret).sign(rival)
      // Not yours is 403, not 404 (#254): hiding existence would protect nothing, since the PUBLIC
      // GET /bots/{team}/{name} already answers whether a registered bot exists.
      notYours <- app.run(
        Request[IO](Method.DELETE, uri"/me/bots/acme/handover")
          .addCookie(RequestCookie(AuthSession.SessionCookieName, rivalToken))
      )
      released <- app.run(
        Request[IO](Method.DELETE, uri"/me/bots/acme/handover")
          .addCookie(RequestCookie(AuthSession.SessionCookieName, session))
      )
      empty     <- released.as[MyBots]
      reclaimed <- app.run(claim(Some(rivalToken), Some(botToken)))
    yield
      assertEquals(notYours.status, Status.Forbidden)
      assertEquals(released.status, Status.Ok)
      assertEquals(empty.bots, Nil, "the released bot leaves the owner's list")
      assertEquals(reclaimed.status, Status.Ok, "release is the explicit half of a transfer")

  test("GET /me/bots lists only the caller's bots"):
    for
      (app, _, session, auth, users) <- withBots
      a                              <- auth.register("acme", "first")
      b                              <- auth.register("acme", "second")
      _                              <- app.run(claim(Some(session), a.toOption.map(_._1)))
      rival                          <- users.upsertOnLogin("google", "sub-other-owner", None, IO.pure("OtherOwner"))
      rivalToken                     <- AuthSession(users, Secret).sign(rival)
      _                              <- app.run(claim(Some(rivalToken), b.toOption.map(_._1)))
      mine                           <- app
        .run(Request[IO](Method.GET, uri"/me/bots").addCookie(RequestCookie(AuthSession.SessionCookieName, session)))
        .flatMap(_.as[MyBots])
      other <- app
        .run(Request[IO](Method.GET, uri"/me/bots").addCookie(RequestCookie(AuthSession.SessionCookieName, rivalToken)))
        .flatMap(_.as[MyBots])
    yield
      assertEquals(mine.bots.map(_.name), List("first"))
      assertEquals(other.bots.map(_.name), List("second"))

  // ── Owner-session management (#254) ──────────────────────────────────────────

  private def signed(method: Method, path: String, token: String): Request[IO] =
    Request[IO](method, org.http4s.Uri.unsafeFromString(path))
      .addCookie(RequestCookie(AuthSession.SessionCookieName, token))

  /** An owned bot, ready for the management routes. */
  private def ownedFixture: IO[(HttpApp[IO], String, BotAuth, String, StubUsers)] =
    for
      (app, _, session, auth, users) <- withBots
      registered                     <- auth.register("acme", "worker")
      botToken = registered.toOption.map(_._1).getOrElse(fail("registration failed"))
      _ <- app.run(claim(Some(session), Some(botToken)))
    yield (app, session, auth, botToken, users)

  test("rotation by the owner requires the bot's name echoed, and really invalidates the old token"):
    for
      (app, session, auth, botToken, _) <- ownedFixture
      wrong <- app.run(signed(Method.POST, "/me/bots/acme/worker/token", session).withEntity(ConfirmRotation("nope")))
      stillValid <- auth.authenticate(botToken)
      rotated    <- app.run(
        signed(Method.POST, "/me/bots/acme/worker/token", session).withEntity(ConfirmRotation("WORKER"))
      )
      fresh   <- rotated.as[RotatedToken]
      oldDead <- auth.authenticate(botToken)
      newLive <- auth.authenticate(fresh.token)
    yield
      assertEquals(wrong.status, Status.BadRequest, "a mis-click must not take a running bot offline")
      assert(stillValid.isDefined, "the refused rotation left the token working")
      assertEquals(rotated.status, Status.Ok)
      assertEquals(oldDead, None, "the old credential stops authenticating immediately")
      assertEquals(newLive.map(_.externalId), Some(dicechess.play.core.Principal.Bot("acme", "worker").externalId))

  test("the owner drives the ladder, catalog and capacity from a session — same semantics as the bot's own routes"):
    for
      (app, session, auth, _, _) <- ownedFixture
      joined                     <- app.run(signed(Method.POST, "/me/bots/acme/worker/ladder/join", session))
      opened                     <- app.run(
        signed(Method.POST, "/me/bots/acme/worker/open-to-humans", session)
          .withEntity(SetOpenToHumans(Some("plays anyone")))
      )
      capped   <- app.run(signed(Method.POST, "/me/bots/acme/worker/capacity", session).withEntity(SetCapacity(4)))
      capacity <- app.run(signed(Method.GET, "/me/bots/acme/worker/capacity", session)).flatMap(_.as[Capacity])
      state    <- auth.ratingOf(dicechess.play.core.Principal.Bot("acme", "worker"))
      listed   <- app.run(signed(Method.GET, "/me/bots", session)).flatMap(_.as[MyBots])
      left     <- app.run(signed(Method.POST, "/me/bots/acme/worker/ladder/leave", session))
      closed   <- app.run(signed(Method.POST, "/me/bots/acme/worker/open-to-humans/leave", session))
      after    <- auth.ratingOf(dicechess.play.core.Principal.Bot("acme", "worker"))
    yield
      assertEquals(joined.status, Status.Ok)
      assertEquals(opened.status, Status.Ok)
      assertEquals(capped.status, Status.Ok)
      assertEquals(capacity.maxConcurrentGames, 4)
      assert(state.exists(_.onLadder), "the ladder flag really moved")
      assertEquals(listed.bots.map(b => (b.onLadder, b.openToHumans)), List((true, true)))
      assertEquals(left.status, Status.Ok)
      assertEquals(closed.status, Status.Ok)
      assert(after.exists(!_.onLadder))

  test("management is owner-only: another account is 403, an unknown bot is 404, no session is 401"):
    for
      (app, session, auth, _, users) <- ownedFixture
      rival                          <- users.upsertOnLogin("google", "sub-not-owner", None, IO.pure("NotOwner"))
      rivalToken                     <- AuthSession(users, Secret).sign(rival)
      // The bot exists and is someone else's: 403 says exactly that, and leaks nothing the public profile does not.
      forbidden <- app.run(signed(Method.POST, "/me/bots/acme/worker/ladder/join", rivalToken))
      unknown   <- app.run(signed(Method.POST, "/me/bots/acme/ghost/ladder/join", session))
      anonymous <- app.run(Request[IO](Method.POST, uri"/me/bots/acme/worker/ladder/join"))
      // The bot's OWN Bearer route must keep working untouched — this PR adds a door, it does not move one.
      viaToken <- auth.setOnLadder(dicechess.play.core.Principal.Bot("acme", "worker"), onLadder = true)
    yield
      assertEquals(forbidden.status, Status.Forbidden)
      assertEquals(unknown.status, Status.NotFound)
      assertEquals(anonymous.status, Status.Unauthorized)
      assert(viaToken.exists(_.onLadder), "the bot's own path is unaffected")
