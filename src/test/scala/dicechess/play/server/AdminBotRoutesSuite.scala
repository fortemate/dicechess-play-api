package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.rating.Glicko2
import dicechess.play.store.{
  AdminBotStore,
  AdminBotListing,
  BotCatalogState,
  BotRating,
  BotStore,
  GuestLink,
  NicknameUpdate,
  UserAccount,
  UserRating,
  UserStore
}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, RequestCookie, Status, Uri}

import java.util.UUID

/** The admin bot surface (#273/#313): any registered bot, no bot token, writes through [[AdminBotStore]] and an
  * inventory read that deliberately does not write. The store double records mutations, because "the audited path was
  * taken, by this admin" is the property the routes must uphold; whether the audit row itself lands transactionally is
  * `PgGameStoreSuite`'s job.
  */
class AdminBotRoutesSuite extends munit.CatsEffectSuite:

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
    def ratingOf(userId: String): IO[Option[UserRating]]                     = IO.pure(None)
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.raiseError(AssertionError("unused"))
    def linkGuest(userId: String, guestId: String): IO[GuestLink]            = IO.raiseError(AssertionError("unused"))
    def guestsOf(userId: String): IO[List[String]]                           = IO.pure(Nil)
    def deleteUser(userId: String): IO[Boolean]                              = IO.raiseError(AssertionError("unused"))

  /** Records every mutation as `(adminUserId, team, name, what)`; knows exactly one bot. The canned states echo the
    * write so the suite can assert the response wire shape came from the store's answer, not from the request.
    */
  final private class StubAdminStore(
      known: (String, String),
      inventory: List[AdminBotListing],
      val calls: Ref[IO, List[(String, String, String, String)]]
  ) extends AdminBotStore:
    private def answer[A](team: String, name: String, state: A): IO[Option[A]] =
      IO.pure(Option.when((team, name) == known)(state))
    def adminBots: IO[List[AdminBotListing]] = IO.pure(inventory)
    def adminSetOnLadder(adminUserId: String, team: String, name: String, onLadder: Boolean): IO[Option[BotRating]] =
      calls.update(_ :+ (adminUserId, team, name, s"ladder=$onLadder")) *>
        answer(team, name, BotRating.initial.copy(onLadder = onLadder))
    def adminOpenToHumans(
        adminUserId: String,
        team: String,
        name: String,
        description: Option[String]
    ): IO[Option[BotCatalogState]] =
      calls.update(_ :+ (adminUserId, team, name, s"open desc=$description")) *>
        answer(team, name, BotCatalogState(openToHumans = true, description))
    def adminCloseToHumans(adminUserId: String, team: String, name: String): IO[Option[BotCatalogState]] =
      calls.update(_ :+ (adminUserId, team, name, "close")) *>
        answer(team, name, BotCatalogState(openToHumans = false, Some("kept")))
    def adminSetDescription(
        adminUserId: String,
        team: String,
        name: String,
        description: Option[String]
    ): IO[Option[BotCatalogState]] =
      calls.update(_ :+ (adminUserId, team, name, s"describe desc=$description")) *>
        answer(team, name, BotCatalogState(openToHumans = false, description))
    def adminRotate(adminUserId: String, team: String, name: String, newTokenHash: String): IO[Boolean] =
      calls.update(_ :+ (adminUserId, team, name, s"rotate hash=$newTokenHash")) *>
        IO.pure((team, name) == known)

  /** One admin account, one plain account, one known bot (`acme/alice`). */
  private def fixture: IO[(HttpApp[IO], String, String, UserAccount, StubAdminStore)] =
    for
      accounts <- Ref.of[IO, Map[String, UserAccount]](Map.empty)
      calls    <- Ref.of[IO, List[(String, String, String, String)]](Nil)
      botStore <- BotStore.inMemory
      auth     <- BotAuth.fromSpec("", botStore)
      users = StubUsers(accounts)
      store = StubAdminStore(
        ("acme", "alice"),
        List(
          AdminBotListing("acme", "alice", 1650.0, 80.0, onLadder = true, openToHumans = true, Some("ready"), true),
          AdminBotListing(
            "orphaned",
            "hidden",
            1500.0,
            350.0,
            onLadder = false,
            openToHumans = false,
            None,
            owned = false
          )
        ),
        calls
      )
      session = AuthSession(users, Secret)
      admin       <- users.upsertOnLogin("google", "sub-admin", None, IO.pure("AdminNick"))
      plain       <- users.upsertOnLogin("google", "sub-plain", None, IO.pure("PlainNick"))
      adminCookie <- session.sign(admin)
      plainCookie <- session.sign(plain)
      app = AdminBotRoutes(session, auth, Set(admin.id), store).orNotFound
    yield (app, adminCookie, plainCookie, admin, store)

  /** Raw-string bodies on purpose, sent as `application/json` bytes: with `CirceEntityCodec.given` in scope a plain
    * `withEntity(string)` would pick the CIRCE encoder and ship a quoted JSON *string*, not the object it spells.
    */
  private def request(method: Method, path: String, cookie: Option[String], body: Option[String] = None): Request[IO] =
    val base     = Request[IO](method, Uri.unsafeFromString(path))
    val withBody = body.fold(base): raw =>
      base
        .withEntity(raw)(using org.http4s.EntityEncoder.stringEncoder[IO])
        .withContentType(org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))
    cookie.fold(withBody)(t => withBody.addCookie(RequestCookie(AuthSession.SessionCookieName, t)))

  test("the gate protects inventory and mutations: no session is 401, a signed-in non-admin is 403"):
    for
      (app, _, plainCookie, _, store) <- fixture
      anonymousInventory              <- app.run(request(Method.GET, "/admin/bots", None))
      nonAdminInventory               <- app.run(request(Method.GET, "/admin/bots", Some(plainCookie)))
      anonymousMutation               <- app.run(request(Method.POST, "/admin/bots/acme/alice/ladder/leave", None))
      nonAdminMutation <- app.run(request(Method.POST, "/admin/bots/acme/alice/ladder/leave", Some(plainCookie)))
      recorded         <- store.calls.get
    yield
      assertEquals(anonymousInventory.status, Status.Unauthorized)
      assertEquals(
        nonAdminInventory.status,
        Status.Forbidden,
        "it exists and you are signed in — but you are not listed"
      )
      assertEquals(anonymousMutation.status, Status.Unauthorized)
      assertEquals(nonAdminMutation.status, Status.Forbidden)
      assertEquals(recorded, Nil, "a refused caller must never create an audit candidate")

  test("the admin inventory returns the full registry, including a provisional closed bot, without an audit write"):
    for
      (app, adminCookie, _, _, store) <- fixture
      response                        <- app.run(request(Method.GET, "/admin/bots", Some(adminCookie)))
      body                            <- response.as[AdminBots]
      recorded                        <- store.calls.get
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(body.bots.map(_.name), List("alice", "hidden"))
      val hidden = body.bots.find(_.name == "hidden").getOrElse(fail("the invisible registered bot is missing"))
      assertEquals(hidden.onLadder, false)
      assertEquals(hidden.openToHumans, false)
      assert(
        hidden.rd > Glicko2.ProvisionalDeviationThreshold,
        "the fresh 350 RD bot is still provisional and absent from the leaderboard"
      )
      // The flag the client must not have to derive: the threshold is the server's, as it is on every other
      // rating-bearing wire type. Asserted on BOTH bots, so the field cannot be a constant that happens to look right.
      assertEquals(hidden.provisional, true, "rd 350 is provisional — one of the two reasons it is publicly invisible")
      val alice = body.bots.find(_.name == "alice").getOrElse(fail("the converged bot is missing"))
      assertEquals(alice.provisional, false, "rd 80 has converged")
      assertEquals(hidden.owned, false)
      assertEquals(recorded, Nil, "inventory reads are not admin actions")

  test("ladder join/leave answer the shared LadderStatus shape and record the acting admin"):
    for
      (app, adminCookie, _, admin, store) <- fixture
      joined   <- app.run(request(Method.POST, "/admin/bots/acme/alice/ladder/join", Some(adminCookie)))
      status   <- joined.as[LadderStatus]
      ghost    <- app.run(request(Method.POST, "/admin/bots/acme/ghost/ladder/leave", Some(adminCookie)))
      recorded <- store.calls.get
    yield
      assertEquals(joined.status, Status.Ok)
      assertEquals(status.onLadder, true)
      assertEquals(ghost.status, Status.NotFound, "absence stays 404, exactly like the owner surface")
      assertEquals(
        recorded,
        List((admin.id, "acme", "alice", "ladder=true"), (admin.id, "acme", "ghost", "ladder=false"))
      )

  test("catalog writes share one description contract: blank body clears, over-long is a 400, describe never opens"):
    for
      (app, adminCookie, _, admin, store) <- fixture
      opened                              <- app.run(
        request(
          Method.POST,
          "/admin/bots/acme/alice/open-to-humans",
          Some(adminCookie),
          Some("""{"description":"a blurb"}""")
        )
      )
      openBody <- opened.as[OpenToHumans]
      tooLong = s"""{"description":"${"x" * (SetOpenToHumans.MaxDescriptionLength + 1)}"}"""
      overLong  <- app.run(request(Method.PUT, "/admin/bots/acme/alice/description", Some(adminCookie), Some(tooLong)))
      described <- app.run(
        request(
          Method.PUT,
          "/admin/bots/acme/alice/description",
          Some(adminCookie),
          Some("""{"description":"retired — token lost"}""")
        )
      )
      descBody    <- described.as[OpenToHumans]
      cleared     <- app.run(request(Method.POST, "/admin/bots/acme/alice/open-to-humans", Some(adminCookie)))
      clearedBody <- cleared.as[OpenToHumans]
      closed      <- app.run(request(Method.POST, "/admin/bots/acme/alice/open-to-humans/leave", Some(adminCookie)))
      recorded    <- store.calls.get
    yield
      assertEquals(opened.status, Status.Ok)
      assertEquals(openBody, OpenToHumans(openToHumans = true, Some("a blurb")))
      assertEquals(overLong.status, Status.BadRequest, "the shared validator gates every door identically")
      assertEquals(
        clearedBody,
        OpenToHumans(openToHumans = true, None),
        "a blank body means no description, and clears any previous one"
      )
      assertEquals(described.status, Status.Ok)
      assertEquals(
        descBody,
        OpenToHumans(openToHumans = false, Some("retired — token lost")),
        "describe reports the flag wherever it was — it opened nothing"
      )
      assertEquals(closed.status, Status.Ok)
      assertEquals(
        recorded.map(_._4),
        List("open desc=Some(a blurb)", "describe desc=Some(retired — token lost)", "open desc=None", "close"),
        "the over-long body must be rejected before the store is ever reached"
      )
      assert(recorded.forall(_._1 == admin.id))

  test("rotation demands the echoed name, hands the plaintext back once, and the store sees only a hash"):
    for
      (app, adminCookie, _, _, store) <- fixture
      unconfirmed                     <- app.run(
        request(Method.POST, "/admin/bots/acme/alice/token", Some(adminCookie), Some("""{"confirm":"wrong"}"""))
      )
      rotated <- app.run(
        request(Method.POST, "/admin/bots/acme/alice/token", Some(adminCookie), Some("""{"confirm":"alice"}"""))
      )
      token <- rotated.as[RotatedToken]
      ghost <- app.run(
        request(Method.POST, "/admin/bots/acme/ghost/token", Some(adminCookie), Some("""{"confirm":"ghost"}"""))
      )
      recorded <- store.calls.get
    yield
      assertEquals(
        unconfirmed.status,
        Status.BadRequest,
        "rotation takes a running bot offline — the echo is the guard"
      )
      assertEquals(rotated.status, Status.Ok)
      assert(token.token.matches("[0-9a-f]{32}"), "the minting policy is BotAuth's, shared with every other door")
      assertEquals(ghost.status, Status.NotFound)
      val hashes = recorded.collect { case (_, "acme", "alice", what) if what.startsWith("rotate hash=") => what }
      assertEquals(hashes.length, 1, "the unconfirmed attempt must never reach the store")
      assert(
        hashes.head == s"rotate hash=${java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(token.token.getBytes("UTF-8"))
            .map(b => f"${b & 0xff}%02x")
            .mkString}",
        "the store receives the SHA-256 of the returned plaintext — never the plaintext itself"
      )

  test("the allowlist parser keeps well-formed uuids, canonicalizes case, and drops garbage without failing"):
    val ok = UUID.randomUUID().toString
    AdminBotRoutes
      .adminsFromSpec(s" ${ok.toUpperCase} ,, not-a-uuid , ")
      .map: admins =>
        assertEquals(admins, Set(ok), "uppercase input must match the lowercase uuid text an account row carries")

  test("a rejected allowlist entry is reported by position — its value never reaches the log"):
    // The accident this guards: PLAY_SESSION_SECRET or a bot token pasted into the wrong env line. Naming the entry
    // would copy that secret into the deploy log, where it outlives the mistake.
    val secret  = "s3cret-pasted-into-the-wrong-variable"
    val warning = AdminBotRoutes.malformedWarning(List(2, 3), total = 3)
    assert(!warning.contains(secret), "a rejected value must never be echoed")
    assert(warning.contains("position(s) 2, 3 of 3"), "position is what an operator needs to find the bad entry")
