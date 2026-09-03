package dicechess.play.server

import cats.data.NonEmptyList
import cats.effect.IO
import dicechess.play.core.*
import dicechess.play.wire.Codecs.given
import io.circe.Codec
import io.circe.derivation.ConfiguredCodec
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.{`Cache-Control`, `Content-Type`, `If-None-Match`, `Retry-After`, ETag, Origin}
import org.http4s.{CacheDirective, EntityTag, HttpRoutes, MediaType, Request, Response, Status}
import org.typelevel.ci.CIStringSyntax

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/** `POST /showcase/claim` body (ADR-005 §10). `guestId` is the anonymous fallback (#235): a signed-in caller is seated
  * from the session and the field is ignored; without a session it is required. `clientEntropy` is the visitor's
  * optional dice seed, folded into the new room exactly as a `SubmitSeed` over the socket would be.
  *
  * `ConfiguredCodec` so that an empty object `{}` — a signed-in caller with nothing to say — decodes.
  */
final case class ShowcaseClaimRequest(guestId: Option[String] = None, clientEntropy: Option[String] = None)
    derives ConfiguredCodec

/** The configured featured bot as the homepage shows it. `displayName` is the same face `PublicPlayer` renders for a
  * bot; a separate presentation name is a later feature (#11).
  */
final case class ShowcaseBotView(team: String, name: String, displayName: String) derives Codec.AsObject

/** The fixed clock, spelled out so the homepage renders "5+3" without knowing the structured `TimeControl` form. */
final case class ShowcaseTimeControlView(initialSeconds: Int, incrementSeconds: Int, display: String)
    derives Codec.AsObject

/** The current game, for spectating: the same public fields the `/games` listing carries, plus which seat the human
  * holds. No token, no identity beyond the public faces `players` already renders everywhere.
  */
final case class ShowcaseGameView(
    gameId: String,
    players: Option[Players],
    humanSeat: Seat,
    activeSeat: Seat,
    dicePending: Boolean,
    clocks: Option[Clocks],
    version: Long,
    dfen: String,
    status: GameStatus
) derives Codec.AsObject

/** Where to watch. `wsUrl` is a relative reference against the API origin (`/games/{id}/ws`); the SPA already knows
  * that origin, and the server deliberately does not guess its own public hostname.
  */
final case class ShowcaseSpectatorView(wsUrl: String) derives Codec.AsObject

/** `GET /showcase`. `status` is one of `unavailable`, `open`, `live`, `finishing`; `reason` is present only when
  * unavailable and is one of a few coarse words (`disabled`, `maintenance`, `bot_unavailable`) — never an
  * infrastructure detail.
  */
final case class ShowcaseView(
    status: String,
    featuredBot: Option[ShowcaseBotView],
    timeControl: ShowcaseTimeControlView,
    nextHumanColor: Option[Side],
    currentGame: Option[ShowcaseGameView],
    spectator: Option[ShowcaseSpectatorView],
    reason: Option[String]
) derives Codec.AsObject

/** The winner's answer — the ONLY response on this surface that carries a credential, and it is sent `no-store`. */
final case class ShowcaseClaimed(outcome: String, gameId: String, seat: Seat, seatToken: String, wsUrl: String)
    derives Codec.AsObject

/** Everyone else's answer: the game to watch, if it still has a room, and why this caller is not playing. */
final case class ShowcaseSpectating(
    outcome: String,
    reason: String,
    gameId: Option[String],
    spectatorWsUrl: Option[String]
) derives Codec.AsObject

/** RFC 7807 problem details for the claim surface. Same shape as the staged webhook API's problems. */
final case class ShowcaseProblem(status: Int, code: String, title: String, detail: String, instance: String)
    derives Codec.AsObject

/** The homepage's singleton showcase table (ADR-005 §10, #46): one public read and one atomic claim.
  *
  * Mounted only when `SHOWCASE_ENABLED=true`. The read is unauthenticated and uncacheable. The claim authenticates the
  * caller as the session account or a stable guest id, requires an `Idempotency-Key`, rate-limits per IP and per actor,
  * and hands the coordinator a request fingerprint so a reused key with a different body is refused.
  */
object ShowcaseRoutes:

  /** Per-IP claim budget per minute — a flash crowd on the homepage clicks once, a script hammers. */
  val ClaimsPerIpPerMinute: Int = 60

  /** Per-actor claim budget per minute; retries of one key count, so this is generous for a human and tight for a loop.
    */
  val ClaimsPerActorPerMinute: Int = 20

  /** How long a `503` asks the caller to wait before asking again. */
  val UnavailableRetryAfterSeconds: Long = 5L

  /** The most a claim body may weigh: two optional short fields. Ember buffers no limit of its own, and this endpoint
    * is public, so the body is capped before it is buffered.
    */
  val MaxClaimBodyBytes: Int = 4096

  private val ProblemType = `Content-Type`(MediaType.unsafeParse("application/problem+json"))
  private val NoStore     = `Cache-Control`(NonEmptyList.one(CacheDirective.`no-store`))

  /** The winner's credential must not land in any shared cache. */
  private val NoStorePrivate =
    `Cache-Control`(NonEmptyList.of(CacheDirective.`no-store`, CacheDirective.`private`()))

  /** The read is polled by every homepage visitor and changes on every move: no cache may ever hold it. */
  private val Uncacheable =
    `Cache-Control`(
      NonEmptyList.of(CacheDirective.`no-store`, CacheDirective.`no-cache`(), CacheDirective.`must-revalidate`)
    )

  private val IdempotencyKeyHeader = ci"Idempotency-Key"
  private val CsrfHeader           = ci"X-DiceChess-CSRF"

  private val FixedTimeControlView: ShowcaseTimeControlView = ShowcaseTable.FixedTimeControl match
    case TimeControl.Fischer(initial, increment) =>
      ShowcaseTimeControlView(initial, increment, s"${initial / 60}+$increment")
    case other => ShowcaseTimeControlView(0, 0, other.toString)

  final case class Failure(
      status: Status,
      code: String,
      title: String,
      detail: String,
      retryAfterSeconds: Option[Long] = None
  )

  private val missingIdempotencyKey = Failure(
    Status.BadRequest,
    "missing_idempotency_key",
    "Idempotency-Key required",
    "Send an Idempotency-Key header carrying a UUID and reuse it for retries of this claim."
  )
  private val invalidIdempotencyKey = Failure(
    Status.BadRequest,
    "invalid_idempotency_key",
    "Idempotency-Key must be a UUID",
    "The Idempotency-Key header must be a single UUID."
  )
  private val unsupportedMediaType = Failure(
    Status.UnsupportedMediaType,
    "malformed_request",
    "JSON required",
    "Content-Type must be application/json."
  )
  private val requestTooLarge = Failure(
    Status.PayloadTooLarge,
    "request_too_large",
    "Request body too large",
    s"A claim body may not exceed $MaxClaimBodyBytes bytes."
  )
  private val malformedRequest = Failure(
    Status.BadRequest,
    "malformed_request",
    "Malformed request",
    "The JSON body does not match the required shape."
  )
  private val guestRequired = Failure(
    Status.BadRequest,
    "guest_required",
    "Identity required",
    "Sign in, or send a stable guestId (a UUID) in the body."
  )
  private val invalidGuestId = Failure(
    Status.BadRequest,
    "invalid_guest_id",
    "Invalid guest id",
    "guestId must be a UUID."
  )
  private val csrfRejected = Failure(
    Status.Forbidden,
    "csrf_origin_rejected",
    "Request origin rejected",
    "A session-authenticated claim must carry X-DiceChess-CSRF: 1 and come from an Origin listed in " +
      "PLAY_CORS_ORIGINS; without an allow-list, claim as a guest."
  )
  private val idempotencyConflict = Failure(
    Status.Conflict,
    "idempotency_conflict",
    "Idempotency-Key reused",
    "This Idempotency-Key was already used for a different claim request."
  )
  private def rateLimited(retryAfter: FiniteDuration) = Failure(
    Status.TooManyRequests,
    "rate_limited",
    "Too many claims",
    "Claim rate limit exceeded — retry later.",
    Some(math.max(1L, retryAfter.toSeconds))
  )
  private def unavailable(reason: ShowcaseTable.UnavailableReason) = Failure(
    Status.ServiceUnavailable,
    "showcase_unavailable",
    "Showcase table unavailable",
    s"The showcase table is not accepting claims right now (${reason.public}).",
    Some(UnavailableRetryAfterSeconds)
  )

  def apply(
      table: ShowcaseTable,
      session: Option[AuthSession],
      allowedOrigins: Cors.AllowedOrigins,
      ipLimiter: AnonMintLimiter,
      actorLimiter: AnonMintLimiter
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case req @ GET -> Root / "showcase" =>
        table.view.flatMap(view => respondView(req, view))

      case req @ POST -> Root / "showcase" / "claim" =>
        claim(req, table, session, allowedOrigins, ipLimiter, actorLimiter)

  // ── GET /showcase ────────────────────────────────────────────────────────────

  private[server] def render(view: ShowcaseTable.View): ShowcaseView =
    ShowcaseView(
      status = view.status.wireName,
      featuredBot = view.featuredBot.map(bot => ShowcaseBotView(bot.team, bot.name, s"${bot.team} ${bot.name}")),
      timeControl = FixedTimeControlView,
      nextHumanColor = view.nextHumanColor,
      currentGame = view.currentGame.map { game =>
        ShowcaseGameView(
          gameId = game.id.value,
          players = game.state.players,
          humanSeat = ShowcaseTable.seatOf(game.humanColor),
          activeSeat = game.state.activeSeat,
          dicePending = game.state.dicePending,
          clocks = game.state.clocks,
          version = game.state.version,
          dfen = game.state.dfen,
          status = game.state.status
        )
      },
      spectator = view.currentGame.map(game => ShowcaseSpectatorView(spectatorPath(game.id))),
      reason = view.reason
    )

  /** Weak ETag over the rendered body, so a poller that already holds this exact state gets a `304` — the one bit of
    * caching the uncacheable read allows, because it is a revalidation the server itself answers.
    */
  private def respondView(req: Request[IO], view: ShowcaseTable.View): IO[Response[IO]] =
    val body    = render(view).asJson
    val tag     = EntityTag(digest(body.noSpaces).take(32), EntityTag.Weak)
    val matches = req.headers
      .get[`If-None-Match`]
      .exists(_.tags.fold(true)(_.exists(_.tag == tag.tag)))
    if matches then IO.pure(Response[IO](Status.NotModified).putHeaders(Uncacheable, ETag(tag)))
    else Ok(body).map(_.putHeaders(Uncacheable, ETag(tag)))

  // ── POST /showcase/claim ─────────────────────────────────────────────────────

  /** Checks run cheapest-first, the same order every open endpoint here follows: the per-IP budget before any header or
    * body work, the header and body before the session read (a database round-trip), the per-actor budget before the
    * coordinator is asked anything.
    */
  private def claim(
      req: Request[IO],
      table: ShowcaseTable,
      session: Option[AuthSession],
      allowedOrigins: Cors.AllowedOrigins,
      ipLimiter: AnonMintLimiter,
      actorLimiter: AnonMintLimiter
  ): IO[Response[IO]] =
    ipLimiter
      .attempt(BotRoutes.clientIp(req))
      .flatMap:
        case Left(retryAfter) => problem(req, rateLimited(retryAfter))
        case Right(())        =>
          idempotencyKey(req) match
            case Left(failure) => problem(req, failure)
            case Right(key)    =>
              decodeBody(req).flatMap:
                case Left(failure) => problem(req, failure)
                case Right(body)   =>
                  resolveActor(req, session, allowedOrigins, body).flatMap:
                    case Left(failure) => problem(req, failure)
                    case Right(actor)  =>
                      actorLimiter
                        .attempt(actor.externalId)
                        .flatMap:
                          case Left(retryAfter) => problem(req, rateLimited(retryAfter))
                          case Right(())        =>
                            table
                              .claim(actor, key, requestHash(actor, body), body.clientEntropy)
                              .flatMap(respondClaim(req, _))

  private def idempotencyKey(req: Request[IO]): Either[Failure, UUID] =
    req.headers.get(IdempotencyKeyHeader).map(_.toList.map(_.value.trim)) match
      case None            => Left(missingIdempotencyKey)
      case Some(List(raw)) => Try(UUID.fromString(raw)).toOption.toRight(invalidIdempotencyKey)
      case Some(_)         => Left(invalidIdempotencyKey)

  /** An absent or empty body is a valid claim (a signed-in caller has nothing to add); a present body must be JSON of
    * the request shape, and no larger than [[MaxClaimBodyBytes]] — read with a cap, never buffered whole first.
    */
  private def decodeBody(req: Request[IO]): IO[Either[Failure, ShowcaseClaimRequest]] =
    // `body`/`bodyText`, not `as[String]`: with the circe entity codecs in scope, `as[String]` resolves to the JSON
    // decoder for a JSON *string* and refuses both an object and an empty body.
    req.body
      .take(MaxClaimBodyBytes.toLong + 1)
      .compile
      .to(Array)
      .map: bytes =>
        if bytes.length > MaxClaimBodyBytes then Left(requestTooLarge)
        else
          val text = new String(bytes, StandardCharsets.UTF_8)
          if text.trim.isEmpty then Right(ShowcaseClaimRequest())
          else if !req.contentType.exists(_.mediaType == MediaType.application.json) then Left(unsupportedMediaType)
          else decode[ShowcaseClaimRequest](text).left.map(_ => malformedRequest)

  /** The session wins; the body's guest id is only the anonymous fallback (#235). A session-authenticated claim is a
    * cookie-authenticated state change that hands out a credential, so it needs the CSRF proof the staged webhook API
    * already requires: `X-DiceChess-CSRF: 1` AND an `Origin` on the deployment's allow-list. Without an allow-list
    * there is no trusted origin to compare against — `Cors.AllowedOrigins` documents that the empty configuration means
    * public credential-less CORS, never "trust every origin for a cookie-authenticated mutation" — so the session path
    * is refused and the visitor claims as a guest instead.
    */
  private def resolveActor(
      req: Request[IO],
      session: Option[AuthSession],
      allowedOrigins: Cors.AllowedOrigins,
      body: ShowcaseClaimRequest
  ): IO[Either[Failure, Principal]] =
    AuthSession
      .principalFor(session, req)
      .map:
        case Some(user) => if csrfAccepted(req, allowedOrigins) then Right(user) else Left(csrfRejected)
        case None       =>
          body.guestId match
            case None     => Left(guestRequired)
            case Some(id) => Principal.guest(id).left.map(_ => invalidGuestId)

  private def csrfAccepted(req: Request[IO], allowedOrigins: Cors.AllowedOrigins): Boolean =
    val header = req.headers.get(CsrfHeader).exists(_.toList.map(_.value.trim) == List("1"))
    val origin = allowedOrigins.isExplicitlyConfigured && req.headers.get[Origin].exists(allowedOrigins.allows)
    header && origin

  /** The fingerprint an `Idempotency-Key` is bound to: the actor and the body fields that could change the claim. A
    * retry with the same key and the same fingerprint replays; a different fingerprint is a conflict.
    */
  private[server] def requestHash(actor: Principal, body: ShowcaseClaimRequest): String =
    digest(s"${actor.externalId} ${body.guestId.getOrElse("")} ${body.clientEntropy.getOrElse("")}")

  private def respondClaim(req: Request[IO], outcome: ShowcaseTable.ClaimOutcome): IO[Response[IO]] =
    outcome match
      case ShowcaseTable.ClaimOutcome.Claimed(gameId, color, token) =>
        Ok(
          ShowcaseClaimed(
            outcome = "claimed",
            gameId = gameId.value,
            seat = ShowcaseTable.seatOf(color),
            seatToken = token,
            wsUrl = s"${spectatorPath(gameId)}?token=$token"
          ).asJson
        ).map(_.putHeaders(NoStorePrivate))
      case ShowcaseTable.ClaimOutcome.Spectating(reason, gameId, watchable) =>
        Ok(
          ShowcaseSpectating(
            outcome = "spectating",
            reason = reason.wireName,
            gameId = gameId.map(_.value),
            spectatorWsUrl = if watchable then gameId.map(spectatorPath) else None
          ).asJson
        ).map(_.putHeaders(NoStore))
      case ShowcaseTable.ClaimOutcome.Conflict            => problem(req, idempotencyConflict)
      case ShowcaseTable.ClaimOutcome.Unavailable(reason) => problem(req, unavailable(reason))

  private def problem(req: Request[IO], failure: Failure): IO[Response[IO]] =
    val body = ShowcaseProblem(
      failure.status.code,
      failure.code,
      failure.title,
      failure.detail,
      req.uri.path.renderString
    )
    val base = Response[IO](failure.status).withEntity(body.asJson).withContentType(ProblemType).putHeaders(NoStore)
    IO.pure(
      failure.retryAfterSeconds.fold(base)(seconds => base.putHeaders(`Retry-After`.unsafeFromLong(seconds)))
    )

  private def spectatorPath(gameId: GameId): String = s"/games/${gameId.value}/ws"

  private def digest(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"$b%02x")
      .mkString
