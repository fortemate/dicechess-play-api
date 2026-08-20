package dicechess.play.server

import cats.effect.{IO, Resource}
import dicechess.play.store.{
  BotStore,
  DeliveryOutcome,
  GameStore,
  LastFailure,
  WebhookStats,
  WebhookStatsStore,
  WebhookStore
}
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}

import scala.concurrent.duration.*

/** The `/bot/webhook` registration surface (#104): auth and registered-only gates, the handshake outcomes as HTTP
  * statuses, the whole surface going 503 when the feature is disabled, and the per-IP registration budget. The webhook
  * "endpoint" is an in-process `HttpApp` behind `Client.fromHttpApp`; the URL policy is `allowAll` except in the SSRF
  * case, which uses the production policy.
  */
class WebhookRoutesSuite extends CatsEffectSuite:

  private val allowAll: String => IO[Either[String, Uri]] =
    url => IO.pure(Uri.fromString(url).left.map(_ => "not a valid URL"))

  private val echoEndpoint: HttpApp[IO] =
    HttpApp[IO] { req =>
      req.bodyText.compile.string.flatMap { body =>
        decode[WebhookVerification](body) match
          case Right(v) => Ok(Json.obj("nonce" -> v.nonce.asJson))
          case Left(_)  => BadRequest()
      }
    }

  private def fixture(
      endpoint: HttpApp[IO] = echoEndpoint,
      checkUrl: Option[String => IO[Either[String, Uri]]] = None, // None → allowAll; Some for the SSRF case
      limit: Int = 100,
      stats: Option[WebhookStatsStore] = None
  ): Resource[IO, (org.http4s.HttpRoutes[IO], String, String, String)] =
    for
      bots     <- Resource.eval(BotStore.inMemory)
      auth     <- Resource.eval(BotAuth.fromSpec("house|greedy|static-token", bots))
      registry <- Resource.eval(GameRegistry.create(store = GameStore.noop))
      store    <- Resource.eval(WebhookStore.inMemory)
      webhooks <- Webhooks.create(
        registry,
        store,
        Client.fromHttpApp(endpoint),
        Webhooks.Config(timeout = 2.seconds),
        checkUrl.getOrElse(allowAll)
      )
      limiter    <- Resource.eval(AnonMintLimiter.create(limit = limit))
      registered <- Resource.eval(auth.register("hooks", "pusher").map(_.toOption.get._1))
      anon       <- Resource.eval(auth.mintAnon(None).map(_._1))
    yield (WebhookRoutes(auth, Some(webhooks), limiter, stats), registered, anon, "static-token")

  private def request(method: Method, token: Option[String], body: Option[Json] = None): Request[IO] =
    val base   = Request[IO](method, Uri.unsafeFromString("/bot/webhook"))
    val authed = token.fold(base)(t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t))))
    body.fold(authed)(authed.withEntity(_))

  private def statsRequest(token: Option[String]): Request[IO] =
    val base = Request[IO](Method.GET, Uri.unsafeFromString("/bot/webhook/stats"))
    token.fold(base)(t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t))))

  private val goodBody = Json.obj("url" -> "https://fn.example/hook".asJson)

  /** A `WebhookStatsStore` that answers `answer` to every `statsFor` call and never actually records anything — the
    * `GET /bot/webhook/stats` route is the only thing under test in the stats section below, not the store.
    */
  private def stubStats(answer: WebhookStats): WebhookStatsStore = new WebhookStatsStore:
    def recordDelivery(
        team: String,
        name: String,
        outcome: DeliveryOutcome,
        elapsed: FiniteDuration,
        at: java.time.Instant
    ): IO[Unit]                                                                        = IO.unit
    def statsFor(team: String, name: String, now: java.time.Instant): IO[WebhookStats] = IO.pure(answer)

  test("the whole surface answers 503 when webhooks are disabled on the server"):
    for
      bots    <- BotStore.inMemory
      auth    <- BotAuth.fromSpec("", bots)
      limiter <- AnonMintLimiter.create()
      routes = WebhookRoutes(auth, None, limiter)
      token   <- auth.register("hooks", "pusher").map(_.toOption.get._1)
      posted  <- routes.orNotFound.run(request(Method.POST, Some(token), Some(goodBody)))
      got     <- routes.orNotFound.run(request(Method.GET, Some(token)))
      deleted <- routes.orNotFound.run(request(Method.DELETE, Some(token)))
    yield
      assertEquals(posted.status, Status.ServiceUnavailable)
      assertEquals(got.status, Status.ServiceUnavailable)
      assertEquals(deleted.status, Status.ServiceUnavailable)

  test("no token is 401; anonymous and static bots are 403 — webhooks are a registered-bot perk"):
    fixture().use { (routes, _, anonToken, staticToken) =>
      for
        unauthed <- routes.orNotFound.run(request(Method.POST, None, Some(goodBody)))
        anon     <- routes.orNotFound.run(request(Method.POST, Some(anonToken), Some(goodBody)))
        static   <- routes.orNotFound.run(request(Method.POST, Some(staticToken), Some(goodBody)))
      yield
        assertEquals(unauthed.status, Status.Unauthorized)
        assertEquals(anon.status, Status.Forbidden)
        assertEquals(static.status, Status.Forbidden)
    }

  test("register → inspect → delete: 201 with the one-time secret, 200 info without it, 204, then 404s"):
    fixture().use { (routes, token, _, _) =>
      for
        created <- routes.orNotFound.run(request(Method.POST, Some(token), Some(goodBody)))
        body    <- created.as[Json]
        got     <- routes.orNotFound.run(request(Method.GET, Some(token)))
        info    <- got.as[Json]
        deleted <- routes.orNotFound.run(request(Method.DELETE, Some(token)))
        gone    <- routes.orNotFound.run(request(Method.GET, Some(token)))
        again   <- routes.orNotFound.run(request(Method.DELETE, Some(token)))
      yield
        assertEquals(created.status, Status.Created)
        assertEquals(body.hcursor.get[String]("url"), Right("https://fn.example/hook"))
        assert(body.hcursor.get[String]("secret").exists(_.matches("[0-9a-f]{64}")), s"one-time secret in $body")
        assertEquals(got.status, Status.Ok)
        assertEquals(info.hcursor.get[String]("url"), Right("https://fn.example/hook"))
        assert(info.hcursor.get[String]("secret").isLeft, "the secret must never be shown again")
        assertEquals(deleted.status, Status.NoContent)
        assertEquals(gone.status, Status.NotFound)
        assertEquals(again.status, Status.NotFound)
    }

  test("a failed handshake is 422 with the reason, and a malformed body is 400"):
    val wrongNonce = HttpApp[IO](_ => Ok(Json.obj("nonce" -> "different".asJson)))
    fixture(endpoint = wrongNonce).use { (routes, token, _, _) =>
      for
        refused <- routes.orNotFound.run(request(Method.POST, Some(token), Some(goodBody)))
        reason  <- refused.bodyText.compile.string
        bad     <- routes.orNotFound.run(request(Method.POST, Some(token), Some(Json.obj("nope" -> 1.asJson))))
      yield
        assertEquals(refused.status, Status.UnprocessableEntity)
        assert(reason.contains("verification failed"), reason)
        assertEquals(bad.status, Status.BadRequest)
    }

  test("the production URL policy turns an SSRF attempt into a 422 before any POST"):
    fixture(checkUrl = Some(WebhookSecurity.checkPublicHttps)).use { (routes, token, _, _) =>
      val privateTarget = Json.obj("url" -> "https://169.254.169.254/latest/meta-data".asJson)
      for
        refused <- routes.orNotFound.run(request(Method.POST, Some(token), Some(privateTarget)))
        reason  <- refused.bodyText.compile.string
      yield
        assertEquals(refused.status, Status.UnprocessableEntity)
        assert(reason.contains("non-public"), reason)
    }

  test("registration draws from a per-IP budget: the attempt after the limit is 429 with Retry-After"):
    fixture(limit = 2).use { (routes, token, _, _) =>
      val post = request(Method.POST, Some(token), Some(goodBody))
      for
        first   <- routes.orNotFound.run(post)
        second  <- routes.orNotFound.run(post)
        blocked <- routes.orNotFound.run(post)
      yield
        assertEquals(first.status, Status.Created)
        assertEquals(second.status, Status.Created)
        assertEquals(blocked.status, Status.TooManyRequests)
        assert(blocked.headers.get(org.typelevel.ci.CIString("Retry-After")).isDefined)
    }

  // ── GET /bot/webhook/stats (#225) ─────────────────────────────────────────────

  test("GET /bot/webhook/stats is 404 without persistence — the route exists but nothing backs it"):
    fixture(stats = None).use { (routes, token, _, _) =>
      routes.orNotFound.run(statsRequest(Some(token))).map(resp => assertEquals(resp.status, Status.NotFound))
    }

  test("GET /bot/webhook/stats answers even when webhooks are disabled server-wide — stats are history, not a toggle"):
    for
      bots    <- BotStore.inMemory
      auth    <- BotAuth.fromSpec("", bots)
      limiter <- AnonMintLimiter.create()
      token   <- auth.register("hooks", "pusher").map(_.toOption.get._1)
      routes = WebhookRoutes(auth, None, limiter, Some(stubStats(WebhookStats.empty)))
      resp <- routes.orNotFound.run(statsRequest(Some(token)))
    yield assertEquals(resp.status, Status.Ok)

  test("GET /bot/webhook/stats requires auth and a registered identity, same gate as the rest of the surface"):
    val answer = stubStats(WebhookStats.empty)
    fixture(stats = Some(answer)).use { (routes, _, anonToken, staticToken) =>
      for
        unauthed <- routes.orNotFound.run(statsRequest(None))
        anon     <- routes.orNotFound.run(statsRequest(Some(anonToken)))
        static   <- routes.orNotFound.run(statsRequest(Some(staticToken)))
      yield
        assertEquals(unauthed.status, Status.Unauthorized)
        assertEquals(anon.status, Status.Forbidden)
        assertEquals(static.status, Status.Forbidden)
    }

  test("GET /bot/webhook/stats returns the store's aggregated windows and last failure, mapped onto the wire shape"):
    val at     = java.time.Instant.parse("2026-08-01T10:00:00Z")
    val answer = WebhookStats(
      last24h = dicechess.play.store.DeliveryStatsWindow(
        totalDeliveries = 2,
        outcomes = List(dicechess.play.store.OutcomeCount("applied", 2)),
        p50Ms = Some(100),
        p90Ms = Some(100),
        p99Ms = Some(100)
      ),
      last7d = dicechess.play.store.DeliveryStatsWindow.empty,
      lastFailure = Some(LastFailure(at, "the endpoint answered HTTP 503"))
    )
    fixture(stats = Some(stubStats(answer))).use { (routes, token, _, _) =>
      for
        resp <- routes.orNotFound.run(statsRequest(Some(token)))
        body <- resp.as[Json]
      yield
        assertEquals(resp.status, Status.Ok)
        assertEquals(body.hcursor.downField("last24h").downField("totalDeliveries").as[Long], Right(2L))
        assertEquals(
          body.hcursor.downField("last24h").downField("outcomes").downArray.downField("outcome").as[String],
          Right("applied")
        )
        assertEquals(body.hcursor.downField("last7d").downField("totalDeliveries").as[Long], Right(0L))
        assertEquals(
          body.hcursor.downField("lastFailure").downField("reason").as[String],
          Right("the endpoint answered HTTP 503")
        )
    }
