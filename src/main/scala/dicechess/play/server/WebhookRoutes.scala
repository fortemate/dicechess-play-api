package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.Principal
import dicechess.play.store.{DeliveryStatsWindow, WebhookStatsStore, WebhookStats as StoredWebhookStats}
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.`Retry-After`
import org.http4s.{HttpRoutes, Response}

import java.time.Instant

/** `POST /bot/webhook` body: the callback URL to verify and register, plus optional capabilities (e.g. `["draws"]`). */
final case class RegisterWebhook(url: String, capabilities: Option[List[String]] = None) derives Codec.AsObject

/** The successful registration: the per-bot signing secret, shown exactly once (like a registered bot's token). */
final case class WebhookCreated(url: String, secret: String, capabilities: Option[List[String]] = None)
    derives Codec.AsObject

/** `GET /bot/webhook`: the current registration's public face — the secret is never shown again. */
final case class WebhookInfo(url: String, verifiedAt: java.time.Instant, capabilities: Option[List[String]] = None)
    derives Codec.AsObject

/** One outcome's share of a `GET /bot/webhook/stats` window (#225). */
final case class DeliveryOutcomeCount(outcome: String, count: Long) derives Codec.AsObject

/** One time window of `GET /bot/webhook/stats` (#225): counts by outcome, plus percentiles approximated from the
  * latency histogram — see `docs/reference/webhooks.md` for what the bucket resolution means in practice.
  */
final case class DeliveryWindow(
    totalDeliveries: Long,
    outcomes: List[DeliveryOutcomeCount],
    p50Ms: Option[Long],
    p90Ms: Option[Long],
    p99Ms: Option[Long]
) derives Codec.AsObject

/** The most recent delivery that was a genuine fault — `None` if the bot has none (no deliveries yet, or every one so
  * far succeeded or was a clean decline).
  */
final case class LastDeliveryFailure(at: Instant, reason: String) derives Codec.AsObject

/** `GET /bot/webhook/stats` (#225): the wire shape mirrors the store's aggregated `WebhookStats` field-for-field — kept
  * as its own type (rather than deriving `Codec` on the store shape directly) so the wire contract doesn't move just
  * because the internal histogram representation does, same as `BotCatalogListing` → `CatalogBot` elsewhere.
  */
final case class WebhookDeliveryStats(
    last24h: DeliveryWindow,
    last7d: DeliveryWindow,
    lastFailure: Option[LastDeliveryFailure]
) derives Codec.AsObject

/** The webhook registration surface of the Bot API (F.2, #104): register (with the ownership handshake), inspect,
  * remove. A REGISTERED-bot perk like token rotation and the ladder — anonymous and static bots are refused: the
  * callback URL and signing secret belong to a durable identity, not an ephemeral token.
  *
  * The whole surface answers 503 when webhooks are disabled on the server (`WEBHOOK_TIMEOUT_SECONDS` unset) — the
  * endpoints exist so the failure is explicit, but nothing can be registered that would never fire.
  */
object WebhookRoutes:

  /** @param stats
    *   the delivery-telemetry read seam (#225) — `None` without persistence, same idiom as the leaderboard/catalog:
    *   `GET /bot/webhook/stats` answers 404 rather than being silently absent, since a caller hitting a real,
    *   documented path deserves an explicit reason. Deliberately NOT gated by `webhooks` (whether the feature is
    *   currently enabled): stats are history, and history can outlive a config toggle.
    */
  def apply(
      auth: BotAuth,
      webhooks: Option[Webhooks],
      limiter: AnonMintLimiter,
      stats: Option[WebhookStatsStore] = None
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case req @ POST -> Root / "bot" / "webhook" =>
        withService(webhooks): service =>
          BotRoutes.withBot(auth, req): bot =>
            withRegistered(auth, bot):
              // Rate-limited per IP AFTER the auth/registered gates: registration is the one endpoint where the
              // caller makes this server POST outward (the verification handshake), so its budget must not be
              // consumable by anonymous or unregistered callers at all.
              limiter
                .attempt(BotRoutes.clientIp(req))
                .flatMap:
                  case Left(retryAfter) =>
                    TooManyRequests("webhook registration rate limit exceeded — retry later")
                      .map(_.putHeaders(`Retry-After`.unsafeFromLong(math.max(1L, retryAfter.toSeconds))))
                  case Right(()) =>
                    req
                      .attemptAs[RegisterWebhook]
                      .value
                      .flatMap:
                        case Left(failure) => BadRequest(failure.message)
                        case Right(body)   =>
                          val caps = body.capabilities.getOrElse(Nil)
                          service
                            .register(bot, body.url, caps)
                            .flatMap:
                              case Right(hook) =>
                                val outCaps = Option.when(hook.capabilities.nonEmpty)(hook.capabilities)
                                Created(WebhookCreated(hook.url, hook.secret, outCaps))
                              case Left(reason) => UnprocessableEntity(reason)

      case req @ GET -> Root / "bot" / "webhook" =>
        withService(webhooks): service =>
          BotRoutes.withBot(auth, req): bot =>
            service
              .info(bot)
              .flatMap:
                case Some(hook) =>
                  val outCaps = Option.when(hook.capabilities.nonEmpty)(hook.capabilities)
                  Ok(WebhookInfo(hook.url, hook.verifiedAt, outCaps))
                case None => NotFound()

      case req @ DELETE -> Root / "bot" / "webhook" =>
        withService(webhooks): service =>
          BotRoutes.withBot(auth, req): bot =>
            service.remove(bot).flatMap(removed => if removed then NoContent() else NotFound())

      // Delivery telemetry (#225) — the report-it-back half of #189's load contract. Not gated on `webhooks` being
      // enabled: an author troubleshooting a bot they just DISABLED still wants to see its recent history.
      case req @ GET -> Root / "bot" / "webhook" / "stats" =>
        BotRoutes.withBot(auth, req): bot =>
          withRegistered(auth, bot):
            stats match
              case None        => NotFound("webhook delivery stats need persistence")
              case Some(store) =>
                IO.realTime
                  .map(t => Instant.ofEpochMilli(t.toMillis))
                  .flatMap(now => store.statsFor(bot.team, bot.name, now))
                  .flatMap(s => Ok(toWire(s)))

  private def toWire(stats: StoredWebhookStats): WebhookDeliveryStats =
    def window(w: DeliveryStatsWindow): DeliveryWindow =
      DeliveryWindow(
        w.totalDeliveries,
        w.outcomes.map(oc => DeliveryOutcomeCount(oc.outcome, oc.count)),
        w.p50Ms,
        w.p90Ms,
        w.p99Ms
      )
    WebhookDeliveryStats(
      window(stats.last24h),
      window(stats.last7d),
      stats.lastFailure.map(f => LastDeliveryFailure(f.at, f.reason))
    )

  private def withService(webhooks: Option[Webhooks])(f: Webhooks => IO[Response[IO]]): IO[Response[IO]] =
    webhooks match
      case Some(service) => f(service)
      case None          => ServiceUnavailable("webhooks are not enabled on this server")

  /** Registered bots only — the same `ratingOf`-backed distinction `rotate`/`setOnLadder` use: a row in the bot store
    * is what makes an identity durable enough to own a callback URL.
    */
  private def withRegistered(auth: BotAuth, bot: Principal.Bot)(f: => IO[Response[IO]]): IO[Response[IO]] =
    auth
      .ratingOf(bot)
      .flatMap:
        case Some(_) => f
        case None    => Forbidden("only a registered bot can use webhooks")
