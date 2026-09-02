package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.{Principal, WebhookCapability}
import dicechess.play.store.{BotStore, GuestLink, NicknameUpdate, UserAccount, UserStore}
import io.circe.Json
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{Header, Headers, HttpApp, MediaType, Method, Request, RequestCookie, Response, Status, Uri}
import org.typelevel.ci.*

import java.time.Instant
import java.util.UUID

/** HTTP contract for ADR-004's two session-backed webhook-management roots. A real [[AuthSession]] and [[BotAuth]]
  * exercise the live account, ownership and administrator gates; the state machine is stubbed at its route seam so
  * these tests stay focused on authorization, request strictness, redaction and response headers.
  */
class SessionWebhookRoutesSuite extends munit.CatsEffectSuite:

  private val SessionSecret = "test-session-webhook-secret"
  private val AllowedOrigin = "https://play.jc.id.lv"
  private val Revision      = "whrev_start"
  private val NextRevision  = "whrev_next"
  private val CreatedAt     = Instant.parse("2026-09-01T10:00:00Z")
  private val ExpiresAt     = Instant.parse("2026-09-01T10:15:00Z")

  private val EmptySlot  = ManagedWebhookSlot(Revision, registration = None, pendingSetup = None)
  private val ActiveSlot = ManagedWebhookSlot(
    NextRevision,
    registration = Some(
      ManagedWebhookRegistration(
        "whr_active",
        "https://bot.example/webhook",
        CreatedAt,
        List(WebhookCapability.Draws),
        lastFailure = None
      )
    ),
    pendingSetup = None
  )
  private val CreatedSetup = ManagedWebhookSetupCreated(
    "whs_created",
    "create",
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    ExpiresAt,
    NextRevision
  )
  private val Stats = ManagedWebhookDeliveryStats(
    scope = "bot_history",
    registrationId = Some("whr_active"),
    last24h = DeliveryWindow(2L, List(DeliveryOutcomeCount("applied", 2L)), Some(100L), Some(200L), Some(200L)),
    last7d = DeliveryWindow(3L, List(DeliveryOutcomeCount("applied", 3L)), Some(100L), Some(200L), Some(500L)),
    lastFailure = None
  )

  final private class StubUsers(ref: Ref[IO, Map[String, UserAccount]]) extends UserStore:
    def upsertOnLogin(provider: String, subject: String, email: Option[String], nickname: IO[String]): IO[UserAccount] =
      (nickname, IO.realTimeInstant).flatMapN { (name, now) =>
        ref.modify { users =>
          users.get(subject) match
            case Some(existing) => (users, existing)
            case None           =>
              val user = UserAccount(UUID.randomUUID().toString, name, now, Some(now), isActive = true)
              (users.updated(subject, user), user)
        }
      }

    def userById(id: String): IO[Option[UserAccount]]                        = ref.get.map(_.values.find(_.id == id))
    def byNickname(nickname: String): IO[Option[UserAccount]]                = IO.pure(None)
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.raiseError(AssertionError("unused"))
    def linkGuest(userId: String, guestId: String): IO[GuestLink]            = IO.raiseError(AssertionError("unused"))
    def guestsOf(userId: String): IO[List[String]]                           = IO.pure(Nil)
    def deleteUser(userId: String): IO[Boolean]                              = IO.raiseError(AssertionError("unused"))

  private enum Call:
    case Read(bot: Principal.Bot, actor: ManagedWebhookActor)
    case Create(
        bot: Principal.Bot,
        actor: ManagedWebhookActor,
        revision: String,
        request: ManagedWebhookSetupRequest
    )
    case Activate(bot: Principal.Bot, actor: ManagedWebhookActor, revision: String, setupId: String)
    case Cancel(bot: Principal.Bot, actor: ManagedWebhookActor, revision: String, setupId: String)
    case Capabilities(
        bot: Principal.Bot,
        actor: ManagedWebhookActor,
        revision: String,
        capabilities: List[String]
    )
    case Delete(bot: Principal.Bot, actor: ManagedWebhookActor, revision: String)
    case Stats(bot: Principal.Bot, actor: ManagedWebhookActor)

  final private class StubService(
      val calls: Ref[IO, List[Call]],
      updateAnswer: Either[ManagedWebhookFailure, ManagedWebhookSlot] = Right(ActiveSlot)
  ) extends SessionWebhookService:

    def read(bot: Principal.Bot, actor: ManagedWebhookActor) =
      calls.update(_ :+ Call.Read(bot, actor)).as(Right(EmptySlot))

    def createSetup(
        bot: Principal.Bot,
        actor: ManagedWebhookActor,
        expectedRevision: String,
        request: ManagedWebhookSetupRequest,
        requestId: String,
        sourceIp: String
    ) = calls.update(_ :+ Call.Create(bot, actor, expectedRevision, request)).as(Right(CreatedSetup))

    def activate(
        bot: Principal.Bot,
        actor: ManagedWebhookActor,
        expectedRevision: String,
        setupId: String,
        requestId: String,
        sourceIp: String,
        stillAuthorized: IO[Boolean]
    ) = calls.update(_ :+ Call.Activate(bot, actor, expectedRevision, setupId)).as(Right(ActiveSlot))

    def cancelSetup(
        bot: Principal.Bot,
        actor: ManagedWebhookActor,
        expectedRevision: String,
        setupId: String,
        requestId: String
    ) = calls.update(_ :+ Call.Cancel(bot, actor, expectedRevision, setupId)).as(Right(EmptySlot))

    def updateCapabilities(
        bot: Principal.Bot,
        actor: ManagedWebhookActor,
        expectedRevision: String,
        capabilities: List[String],
        requestId: String
    ) = calls
      .update(_ :+ Call.Capabilities(bot, actor, expectedRevision, capabilities))
      .as(updateAnswer)

    def delete(
        bot: Principal.Bot,
        actor: ManagedWebhookActor,
        expectedRevision: String,
        requestId: String
    ) = calls.update(_ :+ Call.Delete(bot, actor, expectedRevision)).as(Right(EmptySlot))

    def stats(bot: Principal.Bot, actor: ManagedWebhookActor) =
      calls.update(_ :+ Call.Stats(bot, actor)).as(Right(Stats))

  final private case class Fixture(
      app: HttpApp[IO],
      owner: UserAccount,
      ownerCookie: String,
      otherCookie: String,
      admin: UserAccount,
      adminCookie: String,
      service: StubService
  )

  private def fixture(
      updateAnswer: Either[ManagedWebhookFailure, ManagedWebhookSlot] = Right(ActiveSlot),
      originSpec: String = AllowedOrigin
  ): IO[Fixture] =
    for
      accounts <- Ref.of[IO, Map[String, UserAccount]](Map.empty)
      calls    <- Ref.of[IO, List[Call]](Nil)
      botStore <- BotStore.inMemory
      auth     <- BotAuth.fromSpec("", botStore)
      users   = StubUsers(accounts)
      session = AuthSession(users, SessionSecret)
      owner      <- users.upsertOnLogin("google", "owner-subject", None, IO.pure("Owner"))
      other      <- users.upsertOnLogin("google", "other-subject", None, IO.pure("Other"))
      admin      <- users.upsertOnLogin("google", "admin-subject", None, IO.pure("Admin"))
      registered <- auth.register("acme", "alice", Some(Principal.User(owner.id).externalId))
      _ = assert(registered.isRight, "fixture bot registration failed")
      ownerCookie <- session.sign(owner)
      otherCookie <- session.sign(other)
      adminCookie <- session.sign(admin)
      service = StubService(calls, updateAnswer)
      app     = SessionWebhookRoutes(
        session,
        auth,
        Set(admin.id),
        Cors.allowedOrigins(originSpec),
        service
      ).orNotFound
    yield Fixture(app, owner, ownerCookie, otherCookie, admin, adminCookie, service)

  private def request(method: Method, path: String, cookie: Option[String]): Request[IO] =
    cookie.fold(Request[IO](method, Uri.unsafeFromString(path)))(token =>
      Request[IO](method, Uri.unsafeFromString(path))
        .addCookie(RequestCookie(AuthSession.SessionCookieName, token))
    )

  private def mutation(
      method: Method,
      path: String,
      cookie: String,
      body: Option[String],
      origin: Option[String] = Some(AllowedOrigin),
      csrf: Option[String] = Some("1"),
      ifMatch: Option[String] = Some(s"\"$Revision\""),
      jsonContentType: Boolean = true
  ): Request[IO] =
    val base     = request(method, path, Some(cookie))
    val withBody = body.fold(base): raw =>
      val encoded = base.withEntity(raw)(using org.http4s.EntityEncoder.stringEncoder[IO])
      if jsonContentType then encoded.withContentType(org.http4s.headers.`Content-Type`(MediaType.application.json))
      else encoded
    List(
      origin.map(Header.Raw(ci"Origin", _)),
      csrf.map(Header.Raw(ci"X-DiceChess-CSRF", _)),
      ifMatch.map(Header.Raw(ci"If-Match", _))
    ).flatten.foldLeft(withBody)(_.putHeaders(_))

  private def header(response: Response[IO], name: CIString): Option[String] =
    response.headers.get(name).map(_.head.value)

  private def appendRawHeader(request: Request[IO], header: Header.Raw): Request[IO] =
    request.withHeaders(Headers(request.headers.headers :+ header))

  private def code(response: Response[IO]): IO[String] =
    response.as[Json].map(_.hcursor.get[String]("code").getOrElse("<missing>"))

  test("the owner/admin roots use their distinct live authorization gates"):
    for
      f              <- fixture()
      ownerOk        <- f.app.run(request(Method.GET, "/me/bots/acme/alice/webhook", Some(f.ownerCookie)))
      other          <- f.app.run(request(Method.GET, "/me/bots/acme/alice/webhook", Some(f.otherCookie)))
      ownerAnonymous <- f.app.run(request(Method.GET, "/me/bots/acme/alice/webhook", None))
      ownerUnknown   <- f.app.run(request(Method.GET, "/me/bots/acme/ghost/webhook", Some(f.ownerCookie)))
      adminOk        <- f.app.run(request(Method.GET, "/admin/bots/acme/alice/webhook", Some(f.adminCookie)))
      nonAdmin       <- f.app.run(request(Method.GET, "/admin/bots/acme/alice/webhook", Some(f.ownerCookie)))
      adminAnonymous <- f.app.run(request(Method.GET, "/admin/bots/acme/alice/webhook", None))
      adminUnknown   <- f.app.run(request(Method.GET, "/admin/bots/acme/ghost/webhook", Some(f.adminCookie)))
      calls          <- f.service.calls.get
    yield
      assertEquals(ownerOk.status, Status.Ok)
      assertEquals(other.status, Status.Forbidden)
      assertEquals(ownerAnonymous.status, Status.Unauthorized)
      assertEquals(ownerUnknown.status, Status.NotFound)
      assertEquals(adminOk.status, Status.Ok)
      assertEquals(nonAdmin.status, Status.Forbidden)
      assertEquals(adminAnonymous.status, Status.Unauthorized)
      assertEquals(adminUnknown.status, Status.NotFound)
      assertEquals(
        calls,
        List(
          Call.Read(Principal.Bot("acme", "alice"), ManagedWebhookActor.Owner(f.owner.id)),
          Call.Read(Principal.Bot("acme", "alice"), ManagedWebhookActor.Admin(f.admin.id))
        ),
        "unauthorized and unknown targets must not reach the state service"
      )

  test("GET returns an explicitly null redacted slot with a strong ETag and no-store on both roots"):
    fixture().flatMap { f =>
      List(
        ("/me/bots/acme/alice/webhook", f.ownerCookie),
        ("/admin/bots/acme/alice/webhook", f.adminCookie)
      ).traverse_ { case (path, cookie) =>
        f.app
          .run(request(Method.GET, path, Some(cookie)))
          .flatMap: response =>
            response
              .as[Json]
              .map: json =>
                assertEquals(response.status, Status.Ok)
                assertEquals(header(response, ci"ETag"), Some(s"\"$Revision\""))
                assertEquals(header(response, ci"Cache-Control"), Some("no-store"))
                assertEquals(json.hcursor.get[String]("revision"), Right(Revision))
                assertEquals(json.hcursor.downField("registration").focus, Some(Json.Null))
                assertEquals(json.hcursor.downField("pendingSetup").focus, Some(Json.Null))
                assert(!json.noSpaces.contains("secret"), "a read must never reveal candidate or active credentials")
      }
    }

  test("mutations require an exact allowed Origin, CSRF signal, JSON and a single strong If-Match"):
    fixture().flatMap: f =>
      val path            = "/me/bots/acme/alice/webhook/setups"
      val body            = Some("""{"kind":"create","url":"https://bot.example/webhook","capabilities":[]}""")
      val duplicateOrigin = appendRawHeader(
        mutation(Method.POST, path, f.ownerCookie, body),
        Header.Raw(ci"Origin", AllowedOrigin)
      )
      val duplicateContentType = appendRawHeader(
        mutation(Method.POST, path, f.ownerCookie, body),
        Header.Raw(ci"Content-Type", "application/json")
      )
      val cases = List(
        (mutation(Method.POST, path, f.ownerCookie, body, origin = None), Status.Forbidden, "csrf_origin_rejected"),
        (
          mutation(Method.POST, path, f.ownerCookie, body, origin = Some("https://evil.example")),
          Status.Forbidden,
          "csrf_origin_rejected"
        ),
        (duplicateOrigin, Status.Forbidden, "csrf_origin_rejected"),
        (mutation(Method.POST, path, f.ownerCookie, body, csrf = None), Status.Forbidden, "csrf_origin_rejected"),
        (
          mutation(Method.POST, path, f.ownerCookie, body, csrf = Some("true")),
          Status.Forbidden,
          "csrf_origin_rejected"
        ),
        (
          mutation(Method.POST, path, f.ownerCookie, body, csrf = Some("1, 1")),
          Status.Forbidden,
          "csrf_origin_rejected"
        ),
        (
          mutation(
            Method.POST,
            "/me/bots/acme/unknown/webhook/setups",
            f.ownerCookie,
            body,
            origin = None
          ),
          Status.Forbidden,
          "csrf_origin_rejected"
        ),
        (
          mutation(Method.POST, path, f.ownerCookie, body, ifMatch = None),
          Status.PreconditionRequired,
          "webhook_revision_required"
        ),
        (mutation(Method.POST, path, f.ownerCookie, body, ifMatch = Some("*")), Status.BadRequest, "malformed_request"),
        (
          mutation(Method.POST, path, f.ownerCookie, body, ifMatch = Some(s"W/\"$Revision\"")),
          Status.BadRequest,
          "malformed_request"
        ),
        (
          mutation(Method.POST, path, f.ownerCookie, body, ifMatch = Some(s"\"$Revision\", \"whrev_other\"")),
          Status.BadRequest,
          "malformed_request"
        ),
        (
          mutation(Method.POST, path, f.ownerCookie, body, jsonContentType = false),
          Status.UnsupportedMediaType,
          "malformed_request"
        ),
        (duplicateContentType, Status.UnsupportedMediaType, "malformed_request")
      )
      cases.traverse_ { (req, expectedStatus, expectedCode) =>
        f.app.run(req).flatMap(response => code(response).map(actual => (response, actual))).map { (response, actual) =>
          assertEquals(response.status, expectedStatus)
          assertEquals(actual, expectedCode)
          assertEquals(header(response, ci"Cache-Control"), Some("no-store"))
          assertEquals(response.contentType.map(_.mediaType), Some(MediaType.unsafeParse("application/problem+json")))
        }
      } *> f.service.calls.get.map(calls => assertEquals(calls, Nil, "rejected requests must not reach the service"))

  test("Origin null is rejected even when an operator accidentally lists the literal value"):
    for
      f        <- fixture(originSpec = s"null,$AllowedOrigin")
      response <- f.app.run(
        mutation(
          Method.POST,
          "/me/bots/acme/alice/webhook/setups",
          f.ownerCookie,
          Some("""{"kind":"create","url":"https://bot.example/webhook","capabilities":[]}"""),
          origin = Some("null")
        )
      )
      responseCode <- code(response)
      calls        <- f.service.calls.get
    yield
      assertEquals(response.status, Status.Forbidden)
      assertEquals(responseCode, "csrf_origin_rejected")
      assertEquals(calls, Nil)

  test("setup cancellation rejects a request body before reaching the service"):
    for
      f        <- fixture()
      response <- f.app.run(
        mutation(
          Method.DELETE,
          "/me/bots/acme/alice/webhook/setups/whs_created",
          f.ownerCookie,
          Some("{}")
        )
      )
      responseCode <- code(response)
      calls        <- f.service.calls.get
    yield
      assertEquals(response.status, Status.BadRequest)
      assertEquals(responseCode, "malformed_request")
      assertEquals(calls, Nil)

  test("setup bodies are exact discriminated variants and confirmations retain their JSON types"):
    fixture().flatMap: f =>
      val path  = "/admin/bots/acme/alice/webhook/setups"
      val valid = List(
        """{"kind":"create","url":"https://bot.example/new","capabilities":["draws"]}""",
        """{"kind":"replaceUrl","url":"https://bot.example/replaced","confirmSecretRotation":true}""",
        """{"kind":"rotateSecret","cutoverMode":"dualKey","confirm":"alice"}"""
      )
      val invalid = List(
        """{"kind":"create","url":"https://bot.example/new","capabilities":[],"confirm":true}""",
        """{"kind":"replaceUrl","url":"https://bot.example/replaced","confirmSecretRotation":false}""",
        """{"kind":"replaceUrl","url":"https://bot.example/replaced","confirmSecretRotation":"true"}""",
        """{"kind":"rotateSecret","cutoverMode":"immediate","confirm":"alice"}""",
        """{"kind":"rotateSecret","cutoverMode":"dualKey","confirm":"Alice"}"""
      )
      valid.traverse_(raw =>
        f.app
          .run(mutation(Method.POST, path, f.adminCookie, Some(raw)))
          .map(r => assertEquals(r.status, Status.Created))
      ) *>
        invalid.traverse_(raw =>
          f.app
            .run(mutation(Method.POST, path, f.adminCookie, Some(raw)))
            .map(r => assertEquals(r.status, Status.BadRequest))
        ) *>
        f.service.calls.get.map: calls =>
          assertEquals(
            calls.collect { case Call.Create(_, _, revision, body) => (revision, body) },
            List(
              (Revision, ManagedWebhookSetupRequest.Create("https://bot.example/new", List("draws"))),
              (Revision, ManagedWebhookSetupRequest.ReplaceUrl("https://bot.example/replaced")),
              (Revision, ManagedWebhookSetupRequest.RotateSecret("alice"))
            )
          )

  test("activation and deletion require exact typed confirmations before they reach the service"):
    fixture().flatMap: f =>
      val activatePath = "/me/bots/acme/alice/webhook/setups/whs_created/activate"
      val deletePath   = "/me/bots/acme/alice/webhook"
      for
        falseActivation <- f.app.run(
          mutation(Method.POST, activatePath, f.ownerCookie, Some("""{"secretStored":false}"""))
        )
        stringActivation <- f.app.run(
          mutation(Method.POST, activatePath, f.ownerCookie, Some("""{"secretStored":"true"}"""))
        )
        extraActivation <- f.app.run(
          mutation(Method.POST, activatePath, f.ownerCookie, Some("""{"secretStored":true,"extra":1}"""))
        )
        activated   <- f.app.run(mutation(Method.POST, activatePath, f.ownerCookie, Some("""{"secretStored":true}""")))
        wrongDelete <- f.app.run(mutation(Method.DELETE, deletePath, f.ownerCookie, Some("""{"confirm":"Alice"}""")))
        deleted     <- f.app.run(mutation(Method.DELETE, deletePath, f.ownerCookie, Some("""{"confirm":"alice"}""")))
        calls       <- f.service.calls.get
      yield
        assertEquals(falseActivation.status, Status.BadRequest)
        assertEquals(stringActivation.status, Status.BadRequest)
        assertEquals(extraActivation.status, Status.BadRequest)
        assertEquals(activated.status, Status.Ok)
        assertEquals(wrongDelete.status, Status.BadRequest)
        assertEquals(deleted.status, Status.Ok)
        assertEquals(
          calls,
          List(
            Call
              .Activate(Principal.Bot("acme", "alice"), ManagedWebhookActor.Owner(f.owner.id), Revision, "whs_created"),
            Call.Delete(Principal.Bot("acme", "alice"), ManagedWebhookActor.Owner(f.owner.id), Revision)
          )
        )

  test("the setup secret is returned once with no-cache, no-store, Location and its new ETag"):
    fixture().flatMap: f =>
      val path = "/me/bots/acme/alice/webhook/setups"
      f.app
        .run(
          mutation(
            Method.POST,
            path,
            f.ownerCookie,
            Some("""{"kind":"create","url":"https://bot.example/new","capabilities":[]}""")
          )
        )
        .flatMap: response =>
          response
            .as[Json]
            .map: json =>
              assertEquals(response.status, Status.Created)
              assertEquals(header(response, ci"Cache-Control"), Some("no-store"))
              assertEquals(header(response, ci"Pragma"), Some("no-cache"))
              assertEquals(header(response, ci"ETag"), Some(s"\"$NextRevision\""))
              assertEquals(header(response, ci"Location"), Some(s"$path/${CreatedSetup.setupId}"))
              assertEquals(json.hcursor.get[String]("secret"), Right(CreatedSetup.secret))
              assertEquals(json.hcursor.get[String]("revision"), Right(NextRevision))

  test("a stale If-Match is a redacted 412 problem carrying the authoritative slot and ETag"):
    val current = ActiveSlot.copy(
      pendingSetup = Some(
        ManagedPendingWebhookSetup(
          "whs_pending",
          "replaceUrl",
          "https://bot.example/candidate",
          CreatedAt,
          ExpiresAt,
          canActivate = true
        )
      )
    )
    val stale = ManagedWebhookFailure(
      Status.PreconditionFailed,
      "stale_webhook_revision",
      "Webhook revision changed",
      "Read the current state and retry intentionally.",
      current = Some(current)
    )
    fixture(Left(stale)).flatMap: f =>
      f.app
        .run(
          mutation(
            Method.PATCH,
            "/admin/bots/acme/alice/webhook/capabilities",
            f.adminCookie,
            Some("""{"capabilities":["draws"]}""")
          )
        )
        .flatMap: response =>
          response
            .as[Json]
            .map: json =>
              assertEquals(response.status, Status.PreconditionFailed)
              assertEquals(
                response.contentType.map(_.mediaType),
                Some(MediaType.unsafeParse("application/problem+json"))
              )
              assertEquals(header(response, ci"Cache-Control"), Some("no-store"))
              assertEquals(header(response, ci"ETag"), Some(s"\"$NextRevision\""))
              assertEquals(json.hcursor.get[String]("code"), Right("stale_webhook_revision"))
              assertEquals(json.hcursor.downField("current").get[String]("revision"), Right(NextRevision))
              assertEquals(
                json.hcursor.downField("current").downField("registration").get[String]("registrationId"),
                Right("whr_active")
              )
              assertEquals(
                json.hcursor.downField("current").downField("pendingSetup").get[String]("setupId"),
                Right("whs_pending")
              )
              assert(!json.noSpaces.toLowerCase.contains("secret"), "the current slot in a 412 must remain redacted")

  test("stats are explicitly bot-history scoped and keep owner/admin actors distinct"):
    fixture().flatMap: f =>
      val targets = List(
        ("/me/bots/acme/alice/webhook/stats", f.ownerCookie),
        ("/admin/bots/acme/alice/webhook/stats", f.adminCookie)
      )
      targets.traverse_ { (path, cookie) =>
        f.app
          .run(request(Method.GET, path, Some(cookie)))
          .flatMap: response =>
            response
              .as[Json]
              .map: json =>
                assertEquals(response.status, Status.Ok)
                assertEquals(header(response, ci"Cache-Control"), Some("no-store"))
                assertEquals(json.hcursor.get[String]("scope"), Right("bot_history"))
                assertEquals(json.hcursor.get[String]("registrationId"), Right("whr_active"))
                assertEquals(json.hcursor.downField("last24h").get[Long]("totalDeliveries"), Right(2L))
      } *> f.service.calls.get.map: calls =>
        assertEquals(
          calls,
          List(
            Call.Stats(Principal.Bot("acme", "alice"), ManagedWebhookActor.Owner(f.owner.id)),
            Call.Stats(Principal.Bot("acme", "alice"), ManagedWebhookActor.Admin(f.admin.id))
          )
        )
