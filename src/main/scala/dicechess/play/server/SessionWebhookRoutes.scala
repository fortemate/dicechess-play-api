package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.Principal
import io.circe.Decoder
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.{Origin, `Content-Type`}
import org.http4s.{Header, HttpRoutes, MediaType, Request, Response, Status}
import org.typelevel.ci.CIString

import java.util.UUID

/** Cookie-session owner/admin webhook management (ADR-004). Both roots use this single handler so authorization is the
  * only intentional difference; state transitions, status codes and redaction cannot drift between them.
  */
object SessionWebhookRoutes:

  private val CsrfHeader        = CIString("X-DiceChess-CSRF")
  private val IfMatchHeader     = CIString("If-Match")
  private val OriginHeader      = CIString("Origin")
  private val ContentTypeHeader = CIString("Content-Type")
  private val ETagHeader        = CIString("ETag")
  private val CacheControl      = Header.Raw(CIString("Cache-Control"), "no-store")
  private val ProblemType       = `Content-Type`(MediaType.unsafeParse("application/problem+json"))
  private val StrongRevision    = "\"(whrev_[A-Za-z0-9_-]+)\"".r

  def apply(
      session: AuthSession,
      auth: BotAuth,
      admins: Set[String],
      allowedOrigins: Cors.AllowedOrigins,
      service: SessionWebhookService
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      // Owner root
      case req @ GET -> Root / "me" / "bots" / team / name / "webhook" =>
        withOwner(session, auth, req, team, name)(read(service, req, _))
      case req @ GET -> Root / "me" / "bots" / team / name / "webhook" / "stats" =>
        withOwner(session, auth, req, team, name)(stats(service, req, _))
      case req @ POST -> Root / "me" / "bots" / team / name / "webhook" / "setups" =>
        withMutationOrigin(allowedOrigins, req)(
          withOwner(session, auth, req, team, name)(create(service, allowedOrigins, req, _))
        )
      case req @ POST -> Root / "me" / "bots" / team / name / "webhook" / "setups" / setupId / "activate" =>
        withMutationOrigin(allowedOrigins, req)(
          withOwner(session, auth, req, team, name)(activate(service, allowedOrigins, req, _, setupId))
        )
      case req @ DELETE -> Root / "me" / "bots" / team / name / "webhook" / "setups" / setupId =>
        withMutationOrigin(allowedOrigins, req)(
          withOwner(session, auth, req, team, name)(cancel(service, allowedOrigins, req, _, setupId))
        )
      case req @ PATCH -> Root / "me" / "bots" / team / name / "webhook" / "capabilities" =>
        withMutationOrigin(allowedOrigins, req)(
          withOwner(session, auth, req, team, name)(capabilities(service, allowedOrigins, req, _))
        )
      case req @ DELETE -> Root / "me" / "bots" / team / name / "webhook" =>
        withMutationOrigin(allowedOrigins, req)(
          withOwner(session, auth, req, team, name)(delete(service, allowedOrigins, req, _))
        )

      // Administrator root
      case req @ GET -> Root / "admin" / "bots" / team / name / "webhook" =>
        withAdmin(session, auth, admins, req, team, name)(read(service, req, _))
      case req @ GET -> Root / "admin" / "bots" / team / name / "webhook" / "stats" =>
        withAdmin(session, auth, admins, req, team, name)(stats(service, req, _))
      case req @ POST -> Root / "admin" / "bots" / team / name / "webhook" / "setups" =>
        withMutationOrigin(allowedOrigins, req)(
          withAdmin(session, auth, admins, req, team, name)(create(service, allowedOrigins, req, _))
        )
      case req @ POST -> Root / "admin" / "bots" / team / name / "webhook" / "setups" / setupId / "activate" =>
        withMutationOrigin(allowedOrigins, req)(
          withAdmin(session, auth, admins, req, team, name)(
            activate(service, allowedOrigins, req, _, setupId)
          )
        )
      case req @ DELETE -> Root / "admin" / "bots" / team / name / "webhook" / "setups" / setupId =>
        withMutationOrigin(allowedOrigins, req)(
          withAdmin(session, auth, admins, req, team, name)(cancel(service, allowedOrigins, req, _, setupId))
        )
      case req @ PATCH -> Root / "admin" / "bots" / team / name / "webhook" / "capabilities" =>
        withMutationOrigin(allowedOrigins, req)(
          withAdmin(session, auth, admins, req, team, name)(capabilities(service, allowedOrigins, req, _))
        )
      case req @ DELETE -> Root / "admin" / "bots" / team / name / "webhook" =>
        withMutationOrigin(allowedOrigins, req)(
          withAdmin(session, auth, admins, req, team, name)(delete(service, allowedOrigins, req, _))
        )

  final private case class Authorized(
      bot: Principal.Bot,
      actor: ManagedWebhookActor,
      stillAuthorized: IO[Boolean]
  )

  private def withOwner(
      session: AuthSession,
      auth: BotAuth,
      req: Request[IO],
      team: String,
      name: String
  )(action: Authorized => IO[Response[IO]]): IO[Response[IO]] =
    session
      .userFor(req)
      .flatMap:
        case None       => problem(req, authenticationRequired)
        case Some(user) =>
          val bot: Principal.Bot = Principal.Bot(team, name)
          auth
            .ratingOf(bot)
            .flatMap:
              case None         => problem(req, botNotFound)
              case Some(rating) =>
                val ownerId = Principal.User(user.id).externalId
                if !rating.ownerExternalId.contains(ownerId) then problem(req, botNotOwned)
                else
                  val recheck = session
                    .userFor(req)
                    .flatMap:
                      case Some(current) if current.id == user.id =>
                        auth.ratingOf(bot).map(_.exists(_.ownerExternalId.contains(ownerId)))
                      case _ => IO.pure(false)
                  action(Authorized(bot, ManagedWebhookActor.Owner(user.id), recheck))

  private def withAdmin(
      session: AuthSession,
      auth: BotAuth,
      admins: Set[String],
      req: Request[IO],
      team: String,
      name: String
  )(action: Authorized => IO[Response[IO]]): IO[Response[IO]] =
    session
      .userFor(req)
      .flatMap:
        case None                           => problem(req, authenticationRequired)
        case Some(user) if !admins(user.id) => problem(req, adminRequired)
        case Some(user)                     =>
          val bot: Principal.Bot = Principal.Bot(team, name)
          auth
            .ratingOf(bot)
            .flatMap:
              case None    => problem(req, botNotFound)
              case Some(_) =>
                val recheck = session.userFor(req).map(_.exists(current => current.id == user.id && admins(current.id)))
                action(Authorized(bot, ManagedWebhookActor.Admin(user.id), recheck))

  private def read(
      service: SessionWebhookService,
      req: Request[IO],
      authorized: Authorized
  ): IO[Response[IO]] =
    service.read(authorized.bot, authorized.actor).flatMap(result(req, _)(slotResponse))

  private def stats(
      service: SessionWebhookService,
      req: Request[IO],
      authorized: Authorized
  ): IO[Response[IO]] =
    service.stats(authorized.bot, authorized.actor).flatMap(result(req, _)(value => Ok(value).map(noStore)))

  private def create(
      service: SessionWebhookService,
      origins: Cors.AllowedOrigins,
      req: Request[IO],
      authorized: Authorized
  ): IO[Response[IO]] =
    mutationWithJson[ManagedWebhookSetupRequest](origins, req).flatMap:
      case Left(response)          => IO.pure(response)
      case Right((revision, body)) =>
        val confirmationOk = body match
          case ManagedWebhookSetupRequest.RotateSecret(confirm) => confirm == authorized.bot.name
          case _                                                => true
        if !confirmationOk then problem(req, confirmationMismatch("confirm must exactly match the bot name"))
        else
          requestId.flatMap { id =>
            service
              .createSetup(
                authorized.bot,
                authorized.actor,
                revision,
                body,
                id,
                BotRoutes.clientIp(req)
              )
              .flatMap:
                case Left(failure) => problem(req, failure)
                case Right(value)  =>
                  Created(value).map { response =>
                    noStore(response)
                      .putHeaders(
                        Header.Raw(CIString("Pragma"), "no-cache"),
                        etag(value.revision),
                        Header.Raw(CIString("Location"), s"${req.uri.path.renderString}/${value.setupId}")
                      )
                  }
          }

  private def activate(
      service: SessionWebhookService,
      origins: Cors.AllowedOrigins,
      req: Request[IO],
      authorized: Authorized,
      setupId: String
  ): IO[Response[IO]] =
    mutationWithJson[ActivateManagedWebhook](origins, req).flatMap:
      case Left(response)       => IO.pure(response)
      case Right((revision, _)) =>
        requestId.flatMap(id =>
          service
            .activate(
              authorized.bot,
              authorized.actor,
              revision,
              setupId,
              id,
              BotRoutes.clientIp(req),
              authorized.stillAuthorized
            )
            .flatMap(result(req, _)(slotResponse))
        )

  private def cancel(
      service: SessionWebhookService,
      origins: Cors.AllowedOrigins,
      req: Request[IO],
      authorized: Authorized,
      setupId: String
  ): IO[Response[IO]] =
    mutationWithoutBody(origins, req).flatMap:
      case Left(response)  => IO.pure(response)
      case Right(revision) =>
        requestId.flatMap(id =>
          service
            .cancelSetup(authorized.bot, authorized.actor, revision, setupId, id)
            .flatMap(result(req, _)(slotResponse))
        )

  private def capabilities(
      service: SessionWebhookService,
      origins: Cors.AllowedOrigins,
      req: Request[IO],
      authorized: Authorized
  ): IO[Response[IO]] =
    mutationWithJson[UpdateManagedWebhookCapabilities](origins, req).flatMap:
      case Left(response)          => IO.pure(response)
      case Right((revision, body)) =>
        requestId.flatMap(id =>
          service
            .updateCapabilities(authorized.bot, authorized.actor, revision, body.capabilities, id)
            .flatMap(result(req, _)(slotResponse))
        )

  private def delete(
      service: SessionWebhookService,
      origins: Cors.AllowedOrigins,
      req: Request[IO],
      authorized: Authorized
  ): IO[Response[IO]] =
    mutationWithJson[DeleteManagedWebhook](origins, req).flatMap:
      case Left(response)          => IO.pure(response)
      case Right((revision, body)) =>
        if body.confirm != authorized.bot.name then
          problem(req, confirmationMismatch("confirm must exactly match the bot name"))
        else
          requestId.flatMap(id =>
            service
              .delete(authorized.bot, authorized.actor, revision, id)
              .flatMap(result(req, _)(slotResponse))
          )

  private def mutationWithJson[A: Decoder](
      origins: Cors.AllowedOrigins,
      req: Request[IO]
  ): IO[Either[Response[IO], (String, A)]] =
    mutationGuard(origins, req) match
      case Left(failure)   => problem(req, failure).map(Left(_))
      case Right(revision) =>
        if !hasExactlyOneJsonContentType(req) then problem(req, unsupportedMediaType).map(Left(_))
        else
          req
            .attemptAs[A]
            .value
            .flatMap:
              case Left(_)      => problem(req, malformedRequest).map(Left(_))
              case Right(value) => IO.pure(Right((revision, value)))

  private def mutationWithoutBody(
      origins: Cors.AllowedOrigins,
      req: Request[IO]
  ): IO[Either[Response[IO], String]] =
    mutationGuard(origins, req) match
      case Left(failure)   => problem(req, failure).map(Left(_))
      case Right(revision) =>
        req.body
          .take(1)
          .compile
          .last
          .flatMap:
            case Some(_) => problem(req, malformedRequest).map(Left(_))
            case None    => IO.pure(Right(revision))

  private def mutationGuard(
      origins: Cors.AllowedOrigins,
      req: Request[IO]
  ): Either[ManagedWebhookFailure, String] =
    val originOk = originAllowed(origins, req)
    val csrfOk   = csrfAccepted(req)
    if !originOk || !csrfOk then Left(csrfRejected)
    else
      req.headers.get(IfMatchHeader).map(_.toList.map(_.value)) match
        case None                              => Left(revisionRequired)
        case Some(List(StrongRevision(value))) => Right(value)
        case Some(_)                           => Left(malformedIfMatch)

  /** Reject ambient-cookie mutations before even resolving the session or target bot. This makes the CSRF contract fail
    * closed independently of whether a path happens to name a real or owned bot; the deeper guard is retained so future
    * route wiring cannot accidentally bypass it.
    */
  private def withMutationOrigin(
      origins: Cors.AllowedOrigins,
      req: Request[IO]
  )(action: => IO[Response[IO]]): IO[Response[IO]] =
    if originAllowed(origins, req) && csrfAccepted(req) then action
    else problem(req, csrfRejected)

  private def originAllowed(origins: Cors.AllowedOrigins, req: Request[IO]): Boolean =
    origins.isExplicitlyConfigured &&
      (req.headers.get(OriginHeader).map(_.toList.map(_.value)) match
        case Some(List(value)) => Origin.parse(value).toOption.exists(origins.allows)
        case _                 => false)

  private def hasExactlyOneJsonContentType(req: Request[IO]): Boolean =
    req.headers.get(ContentTypeHeader).exists(_.toList.size == 1) &&
      req.contentType.exists(_.mediaType == MediaType.application.json)

  private def csrfAccepted(req: Request[IO]): Boolean =
    req.headers.get(CsrfHeader).exists(_.toList.map(_.value) == List("1"))

  private def requestId: IO[String] = IO(UUID.randomUUID().toString)

  private def result[A](req: Request[IO], value: Either[ManagedWebhookFailure, A])(
      success: A => IO[Response[IO]]
  ): IO[Response[IO]] =
    value.fold(problem(req, _), success)

  private def slotResponse(slot: ManagedWebhookSlot): IO[Response[IO]] =
    Ok(slot).map(response => noStore(response).putHeaders(etag(slot.revision)))

  private def problem(req: Request[IO], failure: ManagedWebhookFailure): IO[Response[IO]] =
    val body = ManagedWebhookProblem(
      failure.status,
      failure.code,
      failure.title,
      failure.detail,
      req.uri.path.renderString,
      failure.current,
      failure.retryAfterSeconds
    )
    val base         = noStore(Response[IO](failure.status).withEntity(body.asJson).withContentType(ProblemType))
    val withRevision = failure.current.fold(base)(slot => base.putHeaders(etag(slot.revision)))
    IO.pure(
      failure.retryAfterSeconds.fold(withRevision)(seconds =>
        withRevision.putHeaders(Header.Raw(CIString("Retry-After"), math.max(1L, seconds).toString))
      )
    )

  private def noStore(response: Response[IO]): Response[IO] = response.putHeaders(CacheControl)
  private def etag(revision: String): Header.Raw            = Header.Raw(ETagHeader, s"\"$revision\"")

  private val authenticationRequired =
    ManagedWebhookFailure(Status.Unauthorized, "authentication_required", "Authentication required", "Sign in first.")
  private val botNotFound =
    ManagedWebhookFailure(Status.NotFound, "bot_not_found", "Bot not found", "No registered bot exists at this path.")
  private val botNotOwned =
    ManagedWebhookFailure(Status.Forbidden, "bot_not_owned", "Bot is not owned", "You do not own this bot.")
  private val adminRequired =
    ManagedWebhookFailure(
      Status.Forbidden,
      "admin_required",
      "Administrator required",
      "Administrator access is required."
    )
  private val csrfRejected =
    ManagedWebhookFailure(
      Status.Forbidden,
      "csrf_origin_rejected",
      "Request origin rejected",
      "Use an allowed Origin and X-DiceChess-CSRF: 1."
    )
  private val revisionRequired =
    ManagedWebhookFailure(
      Status.PreconditionRequired,
      "webhook_revision_required",
      "Webhook revision required",
      "Read the webhook state and send its strong ETag in If-Match."
    )
  private val malformedIfMatch =
    ManagedWebhookFailure(
      Status.BadRequest,
      "malformed_request",
      "Malformed request",
      "If-Match must contain exactly one strong webhook revision."
    )
  private val unsupportedMediaType =
    ManagedWebhookFailure(
      Status.UnsupportedMediaType,
      "malformed_request",
      "JSON required",
      "Content-Type must be application/json."
    )
  private val malformedRequest =
    ManagedWebhookFailure(
      Status.BadRequest,
      "malformed_request",
      "Malformed request",
      "The JSON body does not match the required shape."
    )
  private def confirmationMismatch(detail: String) =
    ManagedWebhookFailure(Status.BadRequest, "confirmation_mismatch", "Confirmation does not match", detail)
