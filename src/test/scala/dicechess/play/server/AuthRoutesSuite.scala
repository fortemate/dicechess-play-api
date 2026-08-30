package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.{GameId, RatingCategory}
import dicechess.play.rating.Glicko
import dicechess.play.store.{
  GameRatingChange,
  GameResultRow,
  GuestLink,
  LeaderboardEntry,
  LeaderboardStore,
  NicknameUpdate,
  PlayerLeaderboardEntry,
  RatedIdentity,
  RatingStore,
  RatingUpdate,
  ResultTally,
  UserAccount,
  UserStore
}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.headers.{Location, `Retry-After`}
import org.http4s.implicits.*
import org.http4s.{Method, Request, RequestCookie, ResponseCookie, SameSite, Status, Uri}

import java.util.UUID
import scala.concurrent.duration.*

/** The sign-in surface (#233), exercised without Google or Postgres: a stub identity provider stands in for the OAuth
  * round-trip and an in-memory `UserStore` for persistence, so what's under test is exactly this server's own security
  * behaviour — the CSRF state check, the cookie attributes, and the rule that a session is never trusted without a
  * live, active user row behind it.
  */
class AuthRoutesSuite extends munit.CatsEffectSuite:

  private val FrontendUrl = "https://play.example"
  private val Secret      = "test-session-secret"

  /** `/auth/me` reads its per-category ratings through these two seams (#280). Empty everywhere, which is exactly a
    * fresh account: no category row means the fresh state, so the default-category scalars still read 1500 / 350 /
    * provisional — the same answer this suite asserted before the split.
    */
  private val noRatings: RatingStore = new RatingStore:
    def unappliedRatedGames(limit: Int): IO[List[GameResultRow]]                              = IO.pure(Nil)
    def applyRatingUpdate(gameId: GameId, white: RatingUpdate, black: RatingUpdate): IO[Unit] = IO.unit
    def markRatingApplied(gameId: GameId): IO[Unit]                                           = IO.unit
    def ratingChangeFor(gameId: GameId): IO[Option[GameRatingChange]]                         = IO.pure(None)
    def categoryRatingOf(identity: RatedIdentity, category: RatingCategory): IO[Glicko]       = IO.pure(Glicko.Initial)
    def categoryRatingsOf(identity: RatedIdentity): IO[Map[RatingCategory, Glicko]]           = IO.pure(Map.empty)

  private val noBoard: LeaderboardStore = new LeaderboardStore:
    def leaderboard(category: RatingCategory, maxRd: Double, limit: Int): IO[List[LeaderboardEntry]] = IO.pure(Nil)
    def playerLeaderboard(
        category: RatingCategory,
        maxRd: Double,
        limit: Int
    ): IO[List[PlayerLeaderboardEntry]]                                              = IO.pure(Nil)
    def categoryTalliesFor(externalId: String): IO[Map[RatingCategory, ResultTally]] = IO.pure(Map.empty)
    def totalGamesFor(externalId: String): IO[Int]                                   = IO.pure(0)

  private val stubGoogle: GoogleIdentityProvider = new GoogleIdentityProvider:
    def authorizeUrl(state: String): String           = s"https://google.example/auth?state=$state"
    def identityFor(code: String): IO[GoogleIdentity] =
      if code == "good-code" then IO.pure(GoogleIdentity("sub-1", Some("player@example.com")))
      else IO.raiseError(RuntimeException(s"exchange failed for '$code': secret detail"))

  /** Just enough store for these routes: upsert keyed by (provider, subject), reads by id. The unused members raise — a
    * test reaching them is a test gone wrong, not a fixture gap to paper over.
    */
  final private class StubUsers(ref: Ref[IO, Map[String, UserAccount]]) extends UserStore:
    def upsertOnLogin(
        provider: String,
        subject: String,
        email: Option[String],
        freshNickname: IO[String]
    ): IO[UserAccount] =
      (freshNickname, IO.realTimeInstant).flatMapN { (nickname, now) =>
        ref.modify { users =>
          users.get(s"$provider:$subject") match
            case Some(existing) => (users, existing)
            case None           =>
              val user = UserAccount(UUID.randomUUID().toString, nickname, now, Some(now), isActive = true)
              (users.updated(s"$provider:$subject", user), user)
        }
      }
    def userById(id: String): IO[Option[UserAccount]]         = ref.get.map(_.values.find(_.id == id))
    def byNickname(nickname: String): IO[Option[UserAccount]] =
      ref.get.map(_.values.find(_.nickname.equalsIgnoreCase(nickname)))
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] =
      ref.modify { users =>
        users.find(_._2.id == userId) match
          case None => (users, NicknameUpdate.UserNotFound)
          case _ if users.values.exists(u => u.id != userId && u.nickname.equalsIgnoreCase(nickname)) =>
            (users, NicknameUpdate.Taken)
          case Some((key, user)) => (users.updated(key, user.copy(nickname = nickname)), NicknameUpdate.Updated)
      }
    def linkGuest(userId: String, guestId: String): IO[GuestLink] = IO.raiseError(AssertionError("unused"))
    def guestsOf(userId: String): IO[List[String]]                = IO.raiseError(AssertionError("unused"))
    def deleteUser(userId: String): IO[Boolean]                   =
      ref.modify { users =>
        users.find(_._2.id == userId) match
          case None           => (users, false)
          case Some((key, _)) => (users.removed(key), true)
      }

  private def fixture: IO[(StubUsers, AuthSession, org.http4s.HttpApp[IO])] =
    Ref.of[IO, Map[String, UserAccount]](Map.empty).map { ref =>
      val store   = StubUsers(ref)
      val session = AuthSession(store, Secret)
      (store, session, AuthRoutes(session, stubGoogle, store, noRatings, noBoard, FrontendUrl, Set.empty).orNotFound)
    }

  private def cookieOf(cookies: List[ResponseCookie], name: String): ResponseCookie =
    cookies.find(_.name == name).getOrElse(fail(s"expected a '$name' cookie, got ${cookies.map(_.name)}"))

  private def patchNickname(token: Option[String], name: String): Request[IO] =
    val base = Request[IO](Method.PATCH, uri"/auth/me").withEntity(NicknameChange(name))
    token.fold(base)(t => base.addCookie(RequestCookie(AuthSession.SessionCookieName, t)))

  private def callbackRequest(code: String, state: String, cookieState: Option[String]): Request[IO] =
    val base = Request[IO](
      Method.GET,
      Uri.unsafeFromString(s"/auth/callback?code=$code&state=$state")
    )
    cookieState.fold(base)(s => base.addCookie(RequestCookie(AuthSession.StateCookieName, s)))

  test("GET /auth/login redirects to Google and plants a hardened state cookie"):
    for
      (_, _, app) <- fixture
      res         <- app.run(Request[IO](Method.GET, uri"/auth/login"))
    yield
      assertEquals(res.status, Status.SeeOther)
      val location = res.headers.get[Location].getOrElse(fail("no Location")).uri.renderString
      assert(location.startsWith("https://google.example/auth?state="), location)
      val state = cookieOf(res.cookies, AuthSession.StateCookieName)
      assert(state.httpOnly && state.secure, "state cookie must be HttpOnly + Secure")
      assertEquals(state.sameSite, Some(SameSite.Lax))
      assert(location.endsWith(state.content), "the redirect state and the cookie state must be the same value")

  test("the callback without a code answers 400"):
    for
      (_, _, app) <- fixture
      res         <- app.run(Request[IO](Method.GET, uri"/auth/callback"))
    yield assertEquals(res.status, Status.BadRequest)

  test("the callback with a mismatched state answers 400 and clears the state cookie"):
    for
      (_, _, app) <- fixture
      res         <- app.run(callbackRequest("good-code", "state-a", Some("state-b")))
    yield
      assertEquals(res.status, Status.BadRequest)
      assertEquals(cookieOf(res.cookies, AuthSession.StateCookieName).maxAge, Some(0L))

  test("the callback with no state cookie at all answers 400"):
    for
      (_, _, app) <- fixture
      res         <- app.run(callbackRequest("good-code", "state-a", None))
    yield assertEquals(res.status, Status.BadRequest)

  test("a successful callback creates the account, signs the session, and returns to the SPA"):
    for
      (store, _, app) <- fixture
      res             <- app.run(callbackRequest("good-code", "state-1", Some("state-1")))
      sessionCookie = cookieOf(res.cookies, AuthSession.SessionCookieName)
      me <- app.run(
        Request[IO](Method.GET, uri"/auth/me")
          .addCookie(RequestCookie(AuthSession.SessionCookieName, sessionCookie.content))
      )
      meResp <- me.as[MeResponse]
      user   <- store.userById(meResp.id)
    yield
      assertEquals(res.status, Status.SeeOther)
      assertEquals(res.headers.get[Location].map(_.uri.renderString), Some(FrontendUrl))
      assert(sessionCookie.httpOnly && sessionCookie.secure, "session cookie must be HttpOnly + Secure")
      assertEquals(sessionCookie.sameSite, Some(SameSite.Lax))
      assertEquals(sessionCookie.path, Some("/"))
      assert(sessionCookie.domain.isEmpty, "host-only on purpose — no Domain attribute")
      assertEquals(cookieOf(res.cookies, AuthSession.StateCookieName).maxAge, Some(0L), "state cookie is cleared")
      assertEquals(me.status, Status.Ok)
      assertEquals(user.map(_.nickname), Some(meResp.nickname))
      assertEquals(
        Nicknames.validate(meResp.nickname),
        Right(meResp.nickname),
        "the auto-generated nickname must self-validate"
      )
      assert(user.exists(_.isActive))

  test("a failed Google exchange answers a generic 500 without the diagnostic detail"):
    for
      (_, _, app) <- fixture
      res         <- app.run(callbackRequest("bad-code", "state-1", Some("state-1")))
      body        <- res.bodyText.compile.string
    yield
      assertEquals(res.status, Status.InternalServerError)
      // The Circe entity codec (same import as every other route here) renders a String body as a JSON string.
      assertEquals(body, "\"Authentication failed\"")
      assert(!body.contains("secret detail"), "upstream error detail must never reach the client")

  test("GET /auth/me without a session answers 401"):
    for
      (_, _, app) <- fixture
      res         <- app.run(Request[IO](Method.GET, uri"/auth/me"))
    yield assertEquals(res.status, Status.Unauthorized)

  test("GET /auth/me exposes admin only for the allowlisted account"):
    for
      accounts <- Ref.of[IO, Map[String, UserAccount]](Map.empty)
      store   = StubUsers(accounts)
      session = AuthSession(store, Secret)
      admin      <- store.upsertOnLogin("google", "sub-admin-flag", None, IO.pure("AdminFlag"))
      nonAdmin   <- store.upsertOnLogin("google", "sub-user-flag", None, IO.pure("UserFlag"))
      adminToken <- session.sign(admin)
      userToken  <- session.sign(nonAdmin)
      app = AuthRoutes(session, stubGoogle, store, noRatings, noBoard, FrontendUrl, Set(admin.id)).orNotFound
      adminResult <- app.run(
        Request[IO](Method.GET, uri"/auth/me").addCookie(RequestCookie(AuthSession.SessionCookieName, adminToken))
      )
      userResult <- app.run(
        Request[IO](Method.GET, uri"/auth/me").addCookie(RequestCookie(AuthSession.SessionCookieName, userToken))
      )
      adminBody <- adminResult.as[MeResponse]
      userBody  <- userResult.as[MeResponse]
    yield
      assertEquals(adminResult.status, Status.Ok)
      assertEquals(userResult.status, Status.Ok)
      assertEquals(adminBody.admin, true)
      assertEquals(userBody.admin, false)

  test("GET /auth/me carries the owner's per-category ratings, and the scalars describe the default one (#280)"):
    for
      accounts <- Ref.of[IO, Map[String, UserAccount]](Map.empty)
      store   = StubUsers(accounts)
      session = AuthSession(store, Secret)
      user  <- store.upsertOnLogin("google", "sub-me-categories", None, IO.pure("CatNick"))
      token <- session.sign(user)
      rated = new RatingStore:
        def unappliedRatedGames(limit: Int): IO[List[GameResultRow]]                              = IO.pure(Nil)
        def applyRatingUpdate(gameId: GameId, white: RatingUpdate, black: RatingUpdate): IO[Unit] = IO.unit
        def markRatingApplied(gameId: GameId): IO[Unit]                                           = IO.unit
        def ratingChangeFor(gameId: GameId): IO[Option[GameRatingChange]]                         = IO.pure(None)
        def categoryRatingOf(identity: RatedIdentity, category: RatingCategory): IO[Glicko] = IO.pure(Glicko.Initial)
        def categoryRatingsOf(identity: RatedIdentity): IO[Map[RatingCategory, Glicko]]     =
          IO.pure(
            Map(
              RatingCategory.Blitz  -> Glicko(1612.0, 88.0, 0.06),
              RatingCategory.Bullet -> Glicko(1490.0, 140.0, 0.06)
            )
          )
      board = new LeaderboardStore:
        def leaderboard(category: RatingCategory, maxRd: Double, limit: Int): IO[List[LeaderboardEntry]] = IO.pure(Nil)
        def playerLeaderboard(
            category: RatingCategory,
            maxRd: Double,
            limit: Int
        ): IO[List[PlayerLeaderboardEntry]]                                              = IO.pure(Nil)
        def categoryTalliesFor(externalId: String): IO[Map[RatingCategory, ResultTally]] =
          IO.pure(Map(RatingCategory.Blitz -> ResultTally(11, 2, 5), RatingCategory.Bullet -> ResultTally(1, 0, 3)))
        def totalGamesFor(externalId: String): IO[Int] = IO.pure(0)
      app = AuthRoutes(session, stubGoogle, store, rated, board, FrontendUrl, Set.empty).orNotFound
      res <- app.run(
        Request[IO](Method.GET, uri"/auth/me").addCookie(RequestCookie(AuthSession.SessionCookieName, token))
      )
      body <- res.as[MeResponse]
    yield
      assertEquals(body.rating, 1612.0, "the scalars are the default category, not a mixture of the two")
      assertEquals(body.rd, 88.0)
      assertEquals(body.provisional, false)
      assertEquals(body.games, 18)
      assertEquals(body.ratings.map(_.category), List("bullet", "blitz"), "enum order, so a client's tabs are stable")
      // Bullet is the honest counter-case: converged at Blitz, still settling at Bullet — one flag per scale.
      assertEquals(body.ratings.find(_.category == "bullet").map(_.provisional), Some(true))
      assertEquals(body.ratings.find(_.category == "bullet").map(_.games), Some(4))
      assert(!body.ratings.exists(_.category == "rapid"), "a speed never played is absent, not 1500")

  test("GET /auth/me with a token signed by the wrong secret answers 401"):
    for
      (store, _, app) <- fixture
      user            <- store.upsertOnLogin("google", "sub-forged", None, IO.pure("ForgedNick"))
      forged          <- AuthSession(store, "some-other-secret").sign(user)
      res             <- app.run(
        Request[IO](Method.GET, uri"/auth/me").addCookie(RequestCookie(AuthSession.SessionCookieName, forged))
      )
    yield assertEquals(res.status, Status.Unauthorized)

  test("a valid session for a vanished or deactivated user answers 401 — the token is never enough"):
    for
      (store, session, _) <- fixture
      user                <- store.upsertOnLogin("google", "sub-gone", None, IO.pure("GoneNick"))
      token               <- session.sign(user)
      // A parallel store that never heard of the user stands in for deletion-after-sign-in.
      empty <- Ref.of[IO, Map[String, UserAccount]](Map.empty).map(StubUsers(_))
      goneApp = AuthRoutes(
        AuthSession(empty, Secret),
        stubGoogle,
        empty,
        noRatings,
        noBoard,
        FrontendUrl,
        Set.empty
      ).orNotFound
      gone <- goneApp.run(
        Request[IO](Method.GET, uri"/auth/me").addCookie(RequestCookie(AuthSession.SessionCookieName, token))
      )
      // And one where the user exists but was deactivated.
      inactive <- Ref
        .of[IO, Map[String, UserAccount]](Map("google:sub-gone" -> user.copy(isActive = false)))
        .map(StubUsers(_))
      inactiveApp = AuthRoutes(
        AuthSession(inactive, Secret),
        stubGoogle,
        inactive,
        noRatings,
        noBoard,
        FrontendUrl,
        Set.empty
      ).orNotFound
      off <- inactiveApp.run(
        Request[IO](Method.GET, uri"/auth/me").addCookie(RequestCookie(AuthSession.SessionCookieName, token))
      )
    yield
      assertEquals(gone.status, Status.Unauthorized)
      assertEquals(off.status, Status.Unauthorized)

  test("PATCH /auth/me without a session answers 401"):
    for
      (_, _, app) <- fixture
      res         <- app.run(patchNickname(None, "FreshNick1"))
    yield assertEquals(res.status, Status.Unauthorized)

  test("PATCH /auth/me validates the format before touching the store"):
    for
      (store, session, app) <- fixture
      user                  <- store.upsertOnLogin("google", "sub-patch-format", None, IO.pure("FormatNick"))
      token                 <- session.sign(user)
      short                 <- app.run(patchNickname(Some(token), "ab"))
      reserved              <- app.run(patchNickname(Some(token), "Admin"))
      digits                <- app.run(patchNickname(Some(token), "1stPlace"))
      garbled               <- app.run(
        Request[IO](Method.PATCH, uri"/auth/me")
          .addCookie(RequestCookie(AuthSession.SessionCookieName, token))
          .withEntity("not json")
      )
      kept <- store.userById(user.id)
    yield
      assertEquals(short.status, Status.BadRequest)
      assertEquals(reserved.status, Status.BadRequest)
      assertEquals(digits.status, Status.BadRequest)
      assertEquals(garbled.status, Status.BadRequest)
      assertEquals(kept.map(_.nickname), Some("FormatNick"), "a rejected rename must change nothing")

  test("PATCH /auth/me renames on success and answers 409 when the name is taken"):
    for
      (store, session, app) <- fixture
      user                  <- store.upsertOnLogin("google", "sub-patch-a", None, IO.pure("PatchNickA"))
      _                     <- store.upsertOnLogin("google", "sub-patch-b", None, IO.pure("PatchNickB"))
      token                 <- session.sign(user)
      renamed               <- app.run(patchNickname(Some(token), "PatchNickA2"))
      body                  <- renamed.as[MeResponse]
      taken                 <- app.run(patchNickname(Some(token), "patchnickb"))
      stored                <- store.userById(user.id)
    yield
      assertEquals(renamed.status, Status.Ok)
      assertEquals(body.nickname, "PatchNickA2")
      assertEquals(taken.status, Status.Conflict, "'patchnickb' collides case-insensitively with PatchNickB")
      assertEquals(stored.map(_.nickname), Some("PatchNickA2"))

  // The store's own rename guard (#275) — cooldown and hold — is exercised against real Postgres in
  // PgGameStoreSuite; here only the route's mapping of each outcome to an HTTP response is under test, via a store
  // that forwards everything to `StubUsers` except a fixed `updateNickname` answer.
  final private class FixedRename(inner: StubUsers, result: NicknameUpdate) extends UserStore:
    export inner.{upsertOnLogin, userById, byNickname, linkGuest, guestsOf, deleteUser}
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.pure(result)

  test("PATCH /auth/me answers 409 with the SAME body for Held as for Taken — no oracle for a vacated name"):
    for
      (store, session, _) <- fixture
      user                <- store.upsertOnLogin("google", "sub-patch-held", None, IO.pure("HeldPatchNick"))
      token               <- session.sign(user)
      heldApp = AuthRoutes(
        session,
        stubGoogle,
        FixedRename(store, NicknameUpdate.Held),
        noRatings,
        noBoard,
        FrontendUrl,
        Set.empty
      ).orNotFound
      takenApp = AuthRoutes(
        session,
        stubGoogle,
        FixedRename(store, NicknameUpdate.Taken),
        noRatings,
        noBoard,
        FrontendUrl,
        Set.empty
      ).orNotFound
      held      <- heldApp.run(patchNickname(Some(token), "AnyName1"))
      taken     <- takenApp.run(patchNickname(Some(token), "AnyName1"))
      heldBody  <- held.bodyText.compile.string
      takenBody <- taken.bodyText.compile.string
    yield
      assertEquals(held.status, Status.Conflict)
      assertEquals(taken.status, Status.Conflict)
      assertEquals(heldBody, takenBody, "Held must be indistinguishable from Taken to the caller")

  test("PATCH /auth/me answers 429 with Retry-After when the cooldown (#275) is still active"):
    for
      (store, session, _) <- fixture
      user                <- store.upsertOnLogin("google", "sub-patch-cooldown", None, IO.pure("CooldownNick"))
      token               <- session.sign(user)
      app = AuthRoutes(
        session,
        stubGoogle,
        FixedRename(store, NicknameUpdate.CooldownActive(3600.seconds)),
        noRatings,
        noBoard,
        FrontendUrl,
        Set.empty
      ).orNotFound
      res <- app.run(patchNickname(Some(token), "AnyName2"))
    yield
      assertEquals(res.status, Status.TooManyRequests)
      assertEquals(res.headers.get[`Retry-After`].map(_.retry), Some(Right(3600L)))

  test("PATCH /auth/me racing an account deletion answers 401, not 404"):
    for
      (sessionStore, session, _) <- fixture
      user                       <- sessionStore.upsertOnLogin("google", "sub-patch-race", None, IO.pure("RaceNick"))
      token                      <- session.sign(user)
      // The session store still knows the user (the cookie check passes); the routes' store no longer does —
      // the same two-store technique as the GET test, standing in for a deletion between check and write.
      empty <- Ref.of[IO, Map[String, UserAccount]](Map.empty).map(StubUsers(_))
      app = AuthRoutes(session, stubGoogle, empty, noRatings, noBoard, FrontendUrl, Set.empty).orNotFound
      res <- app.run(patchNickname(Some(token), "FreshNick1"))
    yield assertEquals(res.status, Status.Unauthorized)

  test("POST /auth/logout expires the session cookie"):
    for
      (_, _, app) <- fixture
      res         <- app.run(Request[IO](Method.POST, uri"/auth/logout"))
    yield
      assertEquals(res.status, Status.Ok)
      assertEquals(cookieOf(res.cookies, AuthSession.SessionCookieName).maxAge, Some(0L))

  // ── DELETE /auth/me (#237) ───────────────────────────────────────────────────

  private def deleteAccount(token: Option[String], confirm: String): Request[IO] =
    val base = Request[IO](Method.DELETE, uri"/auth/me").withEntity(DeleteAccount(confirm))
    token.fold(base)(t => base.addCookie(RequestCookie(AuthSession.SessionCookieName, t)))

  test("DELETE /auth/me without a session answers 401"):
    for
      (_, _, app) <- fixture
      res         <- app.run(deleteAccount(None, "AnyNick"))
    yield assertEquals(res.status, Status.Unauthorized)

  test("DELETE /auth/me refuses a confirmation that is not the account's own nickname"):
    for
      (store, session, app) <- fixture
      user                  <- store.upsertOnLogin("google", "sub-del-guard", None, IO.pure("GuardNick"))
      token                 <- session.sign(user)
      wrong                 <- app.run(deleteAccount(Some(token), "SomeoneElse"))
      garbled               <- app.run(
        Request[IO](Method.DELETE, uri"/auth/me")
          .addCookie(RequestCookie(AuthSession.SessionCookieName, token))
          .withEntity("not json")
      )
      alive <- store.userById(user.id)
    yield
      assertEquals(wrong.status, Status.BadRequest)
      assertEquals(garbled.status, Status.BadRequest)
      assert(alive.isDefined, "a refused confirmation must leave the account intact")

  test("DELETE /auth/me deletes the account, expires the session, and stays deleted"):
    for
      (store, session, app) <- fixture
      user                  <- store.upsertOnLogin("google", "sub-del", None, IO.pure("DelNick"))
      token                 <- session.sign(user)
      // Case-insensitive on purpose: the display casing is the player's, and forcing an exact echo would only make a
      // deliberate action fail for a reason nobody could see.
      res  <- app.run(deleteAccount(Some(token), "delnick"))
      gone <- store.userById(user.id)
      // The signed token is still cryptographically valid — the user row behind it is what makes it useless.
      after  <- app.run(signedGet("/auth/me", token))
      repeat <- app.run(deleteAccount(Some(token), "delnick"))
    yield
      assertEquals(res.status, Status.NoContent)
      assertEquals(cookieOf(res.cookies, AuthSession.SessionCookieName).maxAge, Some(0L))
      assertEquals(gone, None)
      assertEquals(after.status, Status.Unauthorized, "a valid token for a deleted account is not a session")
      assertEquals(repeat.status, Status.Unauthorized, "the second attempt has no session left to act on")

  private def signedGet(path: String, token: String): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString(path))
      .addCookie(RequestCookie(AuthSession.SessionCookieName, token))
