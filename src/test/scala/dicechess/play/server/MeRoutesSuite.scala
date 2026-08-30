package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.{GameId, Principal}
import dicechess.play.store.{
  GameResultRow,
  GameResultsStore,
  GuestLink,
  NicknameUpdate,
  OpponentAggregateRow,
  OpponentFilter,
  PovResultFilter,
  UserAccount,
  UserStore
}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, RequestCookie, Status}

import java.time.Instant
import java.util.UUID

/** The signed-in player's own surface (#236): claiming guest identities, and the merged history that produces.
  *
  * The store seams are stubbed so this stays Docker-free; the SQL that actually unions over several identities is
  * covered against real Postgres in `PgGameStoreSuite`. What is under test here is the route contract — who may claim
  * what, and which identity set a read is scoped to.
  */
class MeRoutesSuite extends munit.CatsEffectSuite:

  private val Secret = "test-session-secret"
  private val GuestA = "11111111-1111-1111-1111-111111111111"
  private val GuestB = "22222222-2222-2222-2222-222222222222"
  private val at     = Instant.parse("2026-08-03T10:00:00Z")

  /** Accounts plus their claims, and one deliberate "already taken" guest id. */
  final private class StubUsers(
      ref: Ref[IO, Map[String, UserAccount]],
      links: Ref[IO, Map[String, String]] // guestId -> userId
  ) extends UserStore:
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
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.raiseError(AssertionError("unused"))
    def linkGuest(userId: String, guestId: String): IO[GuestLink]            =
      ref.get.flatMap { users =>
        if !users.values.exists(_.id == userId) then IO.pure(GuestLink.UserNotFound)
        else
          links.modify { current =>
            current.get(guestId) match
              case Some(owner) if owner != userId => (current, GuestLink.ClaimedByAnother)
              case _                              => (current.updated(guestId, userId), GuestLink.Linked)
          }
      }
    def guestsOf(userId: String): IO[List[String]] =
      links.get.map(_.collect { case (guestId, owner) if owner == userId => guestId }.toList.sorted)
    def deleteUser(userId: String): IO[Boolean] = IO.raiseError(AssertionError("unused"))

  /** Records the identity set each read was scoped to — that scoping IS the feature under test. */
  final private class SpyResults(seen: Ref[IO, List[List[String]]], rows: List[GameResultRow]) extends GameResultsStore:
    def recentResultsFor(externalId: String, limit: Int): IO[List[GameResultRow]] = IO.pure(Nil)
    def finishedRatedSince(since: Instant): IO[List[GameResultRow]]               = IO.pure(Nil)
    def playerGamesPage(
        externalIds: List[String],
        before: Option[Instant],
        opponent: Option[OpponentFilter],
        result: Option[PovResultFilter],
        limit: Int
    ): IO[GameResultsStore.Page] =
      seen.update(_ :+ externalIds).as(GameResultsStore.Page(rows, hasMore = false))
    def opponentsFor(externalIds: List[String]): IO[List[OpponentAggregateRow]] =
      seen.update(_ :+ externalIds).as(Nil)

  private def resultRow(white: String, black: String): GameResultRow =
    GameResultRow(
      GameId(UUID.randomUUID().toString),
      white,
      black,
      Some(1),
      "resign",
      false,
      "300+3",
      "seed",
      None,
      false,
      at
    )

  private def fixture(
      rows: List[GameResultRow] = Nil
  ): IO[(HttpApp[IO], UserAccount, String, Ref[IO, List[List[String]]], StubUsers)] =
    for
      accounts <- Ref.of[IO, Map[String, UserAccount]](Map.empty)
      links    <- Ref.of[IO, Map[String, String]](Map.empty)
      seen     <- Ref.of[IO, List[List[String]]](Nil)
      users   = StubUsers(accounts, links)
      session = AuthSession(users, Secret)
      user  <- users.upsertOnLogin("google", "sub-me", None, IO.pure("MeNick"))
      token <- session.sign(user)
    yield (MeRoutes(session, users, SpyResults(seen, rows)).orNotFound, user, token, seen, users)

  private def signedIn(method: Method, path: String, token: String): Request[IO] =
    Request[IO](method, org.http4s.Uri.unsafeFromString(path))
      .addCookie(RequestCookie(AuthSession.SessionCookieName, token))

  test("every /me route answers 401 without a session"):
    for
      (app, _, _, _, _) <- fixture()
      claim             <- app.run(Request[IO](Method.POST, uri"/auth/me/guests").withEntity(ClaimGuest(GuestA)))
      list              <- app.run(Request[IO](Method.GET, uri"/auth/me/guests"))
      games             <- app.run(Request[IO](Method.GET, uri"/me/games"))
      opps              <- app.run(Request[IO](Method.GET, uri"/me/opponents"))
    yield
      assertEquals(claim.status, Status.Unauthorized)
      assertEquals(list.status, Status.Unauthorized)
      assertEquals(games.status, Status.Unauthorized)
      assertEquals(opps.status, Status.Unauthorized)

  test("claiming a guest id is idempotent for its owner and returns the full claim set"):
    for
      (app, _, token, _, _) <- fixture()
      first                 <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(GuestA)))
      body                  <- first.as[ClaimedGuests]
      again                 <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(GuestA)))
      second                <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(GuestB)))
      both                  <- second.as[ClaimedGuests]
      listed                <- app.run(signedIn(Method.GET, "/auth/me/guests", token)).flatMap(_.as[ClaimedGuests])
    yield
      assertEquals(first.status, Status.Ok)
      assertEquals(body.guests, List(GuestA))
      assertEquals(again.status, Status.Ok, "re-claiming your own id is idempotent, not a conflict")
      assertEquals(both.guests, List(GuestA, GuestB))
      assertEquals(listed.guests, List(GuestA, GuestB))

  test("a guest id already claimed by another account answers 409"):
    for
      (app, _, token, _, users) <- fixture()
      rival                     <- users.upsertOnLogin("google", "sub-rival", None, IO.pure("RivalNick"))
      _                         <- users.linkGuest(rival.id, GuestA)
      taken <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(GuestA)))
    yield assertEquals(taken.status, Status.Conflict)

  test("a malformed guest id is rejected before it can reach the store's uuid cast"):
    for
      (app, _, token, _, _) <- fixture()
      bad      <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest("not-a-uuid")))
      body     <- bad.bodyText.compile.string
      prefixed <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(s"guest:$GuestA")))
      listed   <- app.run(signedIn(Method.GET, "/auth/me/guests", token)).flatMap(_.as[ClaimedGuests])
    yield
      assertEquals(bad.status, Status.BadRequest)
      assert(body.contains("guestId"), body)
      assertEquals(prefixed.status, Status.BadRequest, "the bare uuid is the contract — never the prefixed form")
      assertEquals(listed.guests, Nil, "a rejected claim stores nothing")

  test("/me/games and /me/opponents are scoped to the account plus every claimed guest id"):
    for
      (app, user, token, seen, _) <- fixture()
      _      <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(GuestA)))
      _      <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(GuestB)))
      _      <- app.run(signedIn(Method.GET, "/me/games", token))
      _      <- app.run(signedIn(Method.GET, "/me/opponents", token))
      scopes <- seen.get
    yield
      val expected = Set(
        Principal.User(user.id).externalId,
        Principal.Guest(GuestA).externalId,
        Principal.Guest(GuestB).externalId
      )
      assertEquals(scopes.length, 2)
      scopes.foreach(scope => assertEquals(scope.toSet, expected))

  test("/me/games reframes each row from whichever of my identities held White"):
    val bot = Principal.Bot("acme", "alice").externalId
    // Built inline rather than via `fixture`: this case needs rows that involve BOTH identities, on opposite sides.
    for
      accounts <- Ref.of[IO, Map[String, UserAccount]](Map.empty)
      links    <- Ref.of[IO, Map[String, String]](Map.empty)
      seen     <- Ref.of[IO, List[List[String]]](Nil)
      users   = StubUsers(accounts, links)
      session = AuthSession(users, Secret)
      me    <- users.upsertOnLogin("google", "sub-pov", None, IO.pure("PovNick"))
      myTok <- session.sign(me)
      _     <- users.linkGuest(me.id, GuestA)
      rows = List(
        resultRow(Principal.User(me.id).externalId, bot),  // I was White as the account
        resultRow(bot, Principal.Guest(GuestA).externalId) // I was Black as my claimed guest
      )
      app2 = MeRoutes(session, users, SpyResults(seen, rows)).orNotFound
      games <- app2.run(signedIn(Method.GET, "/me/games", myTok)).flatMap(_.as[PlayerGames])
    yield
      assertEquals(games.games.map(_.seat.toString), List("White", "Black"))
      assertEquals(games.games.map(_.result), List("win", "loss"), "the POV follows whichever identity was mine")
      assert(games.games.forall(_.opponent.name.contains("acme alice")), games.games.map(_.opponent).toString)
