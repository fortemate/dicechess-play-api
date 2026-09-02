package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{Principal, WebhookCapability}
import dicechess.play.store.{
  CreatedWebhookSetup as StoredCreatedSetup,
  ManagedPendingWebhookSetup as StoredPendingSetup,
  ManagedWebhookRegistration as StoredRegistration,
  ManagedWebhookSlot as StoredSlot,
  WebhookActivationFailure,
  WebhookActivationFailureReason,
  WebhookActivationAttempt,
  WebhookActor,
  WebhookActorKind,
  WebhookBudgetDecision,
  WebhookBudgetKind,
  WebhookDeletion,
  WebhookManagementConflict,
  WebhookManagementResult,
  WebhookManagementStore,
  WebhookRequestContext,
  WebhookSetupKind,
  WebhookSetupTerminalStatus,
  WebhookStatsStore,
  NewWebhookSetup
}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.Json
import org.http4s.Status

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** Safe transport outcomes for staged endpoint verification. Implementations must not carry a raw exception, URL or
  * response body in these values: the reason is persisted as a security event by the management store.
  */
enum WebhookVerificationFailure(val auditReason: WebhookActivationFailureReason):
  case UrlRejected       extends WebhookVerificationFailure(WebhookActivationFailureReason.UrlRejected)
  case TimedOut          extends WebhookVerificationFailure(WebhookActivationFailureReason.TimedOut)
  case Unreachable       extends WebhookVerificationFailure(WebhookActivationFailureReason.Unreachable)
  case HttpStatus        extends WebhookVerificationFailure(WebhookActivationFailureReason.HttpStatus)
  case OversizedBody     extends WebhookVerificationFailure(WebhookActivationFailureReason.OversizedBody)
  case MalformedResponse extends WebhookVerificationFailure(WebhookActivationFailureReason.MalformedResponse)
  case ProofMismatch     extends WebhookVerificationFailure(WebhookActivationFailureReason.ProofMismatch)

/** Dedicated, DNS-pinned outbound primitive. The concrete transport signs `rawBody`, applies one end-to-end timeout,
  * refuses redirects and returns at most the bounded response bytes.
  */
trait ManagedWebhookVerifier:
  def validate(url: String): IO[Either[WebhookVerificationFailure, Unit]]
  def post(
      url: String,
      secret: String,
      rawBody: Array[Byte],
      timeout: FiniteDuration
  ): IO[Either[WebhookVerificationFailure, Array[Byte]]]

object ManagedWebhookVerifier:
  /** Adapt the same DNS-pinned signed transport used for ordinary delivery to staged verification. */
  def apply(transport: WebhookTransport): ManagedWebhookVerifier = new ManagedWebhookVerifier:
    def validate(url: String): IO[Either[WebhookVerificationFailure, Unit]] =
      WebhookSecurity
        .resolvePublicHttps(url)
        .map(_.bimap(_ => WebhookVerificationFailure.UrlRejected, _ => ()))

    def post(
        url: String,
        secret: String,
        rawBody: Array[Byte],
        timeout: FiniteDuration
    ): IO[Either[WebhookVerificationFailure, Array[Byte]]] =
      transport
        .postSigned(url, secret, rawBody, timeout)
        .map:
          case WebhookTransport.Outcome.Ok(body)          => Right(body)
          case WebhookTransport.Outcome.PolicyRejected(_) => Left(WebhookVerificationFailure.UrlRejected)
          case WebhookTransport.Outcome.OversizedBody     => Left(WebhookVerificationFailure.OversizedBody)
          case WebhookTransport.Outcome.HttpStatus(_)     => Left(WebhookVerificationFailure.HttpStatus)
          case WebhookTransport.Outcome.TimedOut          => Left(WebhookVerificationFailure.TimedOut)
          case WebhookTransport.Outcome.Unreachable       => Left(WebhookVerificationFailure.Unreachable)

/** ADR-004's state machine and verification protocol, shared by both HTTP route roots. */
final class WebhookManagement(
    store: WebhookManagementStore,
    statsStore: WebhookStatsStore,
    verifier: Option[ManagedWebhookVerifier],
    config: WebhookManagement.Config
) extends SessionWebhookService:
  import WebhookManagement.*

  def read(
      bot: Principal.Bot,
      actor: ManagedWebhookActor
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookSlot]] =
    now.flatMap(at =>
      store
        .webhookSlot(bot.team, bot.name, storedActor(actor), at, WebhookRequestContext(None))
        .map(mapResult(_, actor)(toWire))
    )

  def createSetup(
      bot: Principal.Bot,
      actor: ManagedWebhookActor,
      expectedRevision: String,
      request: ManagedWebhookSetupRequest,
      requestId: String,
      sourceIp: String
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookSetupCreated]] =
    val _ = sourceIp // Setup creation is budgeted by actor+bot; source IP applies to activation.
    verifier match
      case None       => IO.pure(Left(unavailable))
      case Some(live) =>
        parseRevision(expectedRevision) match
          case None           => IO.pure(Left(malformedRevision))
          case Some(revision) =>
            validatedSetup(request).flatMap:
              case Left(failure)                    => IO.pure(Left(failure))
              case Right((kind, url, capabilities)) =>
                now.flatMap { at =>
                  preflight(bot, actor, revision, at, requestId).flatMap:
                    case Left(failure) => IO.pure(Left(failure))
                    case Right(_)      =>
                      consumeBudget(
                        WebhookBudgetKind.SetupActorBot,
                        budgetKey(actor, bot),
                        config.setupCreatesPerWindow,
                        at
                      ).flatMap:
                        case Some(limited) => IO.pure(Left(limited))
                        case None          =>
                          url
                            .traverse(candidate =>
                              live
                                .validate(candidate)
                                .timeoutTo(
                                  config.verificationTimeout,
                                  IO.pure(Left(WebhookVerificationFailure.TimedOut))
                                )
                            )
                            .flatMap:
                              case Some(Left(_)) => IO.pure(Left(urlRejected))
                              case _             =>
                                for
                                  secret  <- WebhookSecurity.randomHex(SecretBytes)
                                  setupId <- IO(UUID.randomUUID())
                                  created = NewWebhookSetup(
                                    setupId,
                                    kind,
                                    url,
                                    secret,
                                    capabilities,
                                    at,
                                    at.plusMillis(config.setupTtl.toMillis)
                                  )
                                  stored <- store.createWebhookSetup(
                                    bot.team,
                                    bot.name,
                                    storedActor(actor),
                                    revision,
                                    created,
                                    context(requestId)
                                  )
                                yield mapResult(stored, actor)(toWire)
                }

  def activate(
      bot: Principal.Bot,
      actor: ManagedWebhookActor,
      expectedRevision: String,
      setupId: String,
      requestId: String,
      sourceIp: String,
      stillAuthorized: IO[Boolean]
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookSlot]] =
    (verifier, parseRevision(expectedRevision), parseOpaque(SetupPrefix, setupId)) match
      case (None, _, _)                      => IO.pure(Left(unavailable))
      case (_, None, _)                      => IO.pure(Left(malformedRevision))
      case (_, _, None)                      => IO.pure(Left(setupNotFound))
      case (Some(live), Some(rev), Some(id)) =>
        now.flatMap { at =>
          preflight(bot, actor, rev, at, requestId).flatMap:
            case Left(failure) => IO.pure(Left(failure))
            case Right(_)      =>
              consumeActivationBudgets(actor, bot, sourceIp, at).flatMap:
                case Some(limited) => IO.pure(Left(limited))
                case None          =>
                  for
                    leaseId  <- IO(UUID.randomUUID())
                    acquired <- store.acquireWebhookActivation(
                      bot,
                      storedActor(actor),
                      WebhookActivationAttempt(
                        id,
                        rev,
                        leaseId,
                        at,
                        at.plusMillis((config.verificationTimeout + LeaseGrace).toMillis)
                      ),
                      context(requestId)
                    )
                    answer <- acquired match
                      case WebhookManagementResult.Applied(lease) =>
                        verify(live, bot, lease.setupId, lease.revision, lease.candidateUrl, lease.candidateSecret)
                          .flatMap:
                            case Left(failure) =>
                              now.flatMap(failedAt =>
                                store
                                  .failWebhookActivation(
                                    storedActor(actor),
                                    lease,
                                    failure.auditReason,
                                    failedAt,
                                    context(requestId)
                                  )
                                  .map:
                                    case WebhookManagementResult.Applied(WebhookActivationFailure(_, true)) =>
                                      Left(terminal(WebhookSetupTerminalStatus.AttemptsExhausted))
                                    case WebhookManagementResult.Applied(WebhookActivationFailure(_, false)) =>
                                      Left(verificationFailed)
                                    case other => mapFailure(other, actor)
                              )
                            case Right(()) =>
                              stillAuthorized.flatMap:
                                case false =>
                                  now.flatMap(failedAt =>
                                    store
                                      .failWebhookActivation(
                                        storedActor(actor),
                                        lease,
                                        WebhookActivationFailureReason.AuthorityChanged,
                                        failedAt,
                                        context(requestId)
                                      )
                                      .as(Left(authorityChanged(actor)))
                                  )
                                case true =>
                                  now.flatMap(verifiedAt =>
                                    store
                                      .completeWebhookActivation(
                                        storedActor(actor),
                                        lease,
                                        verifiedAt,
                                        context(requestId)
                                      )
                                      .map(mapResult(_, actor)(toWire))
                                  )
                      case other => IO.pure(mapFailure(other, actor))
                  yield answer
        }

  def cancelSetup(
      bot: Principal.Bot,
      actor: ManagedWebhookActor,
      expectedRevision: String,
      setupId: String,
      requestId: String
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookSlot]] =
    withRevisionAndSetup(expectedRevision, setupId) { (revision, id) =>
      now.flatMap(at =>
        store
          .cancelWebhookSetup(
            bot.team,
            bot.name,
            storedActor(actor),
            id,
            revision,
            at,
            context(requestId)
          )
          .map(mapResult(_, actor)(toWire))
      )
    }

  def updateCapabilities(
      bot: Principal.Bot,
      actor: ManagedWebhookActor,
      expectedRevision: String,
      capabilities: List[String],
      requestId: String
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookSlot]] =
    (parseRevision(expectedRevision), WebhookCapability.parseSelection(capabilities)) match
      case (None, _)                     => IO.pure(Left(malformedRevision))
      case (_, Left(reason))             => IO.pure(Left(capabilityRejected(reason)))
      case (Some(rev), Right(selection)) =>
        now.flatMap(at =>
          store
            .updateWebhookCapabilities(
              bot.team,
              bot.name,
              storedActor(actor),
              rev,
              selection,
              at,
              context(requestId)
            )
            .map(mapResult(_, actor)(toWire))
        )

  def delete(
      bot: Principal.Bot,
      actor: ManagedWebhookActor,
      expectedRevision: String,
      requestId: String
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookSlot]] =
    parseRevision(expectedRevision) match
      case None           => IO.pure(Left(malformedRevision))
      case Some(revision) =>
        now.flatMap(at =>
          store
            .deleteManagedWebhook(
              bot.team,
              bot.name,
              storedActor(actor),
              revision,
              at,
              context(requestId)
            )
            .map(mapResult(_, actor)((deletion: WebhookDeletion) => toWire(deletion.slot)))
        )

  def stats(
      bot: Principal.Bot,
      actor: ManagedWebhookActor
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookDeliveryStats]] =
    now.flatMap { at =>
      store
        .webhookSlot(bot.team, bot.name, storedActor(actor), at, WebhookRequestContext(None))
        .flatMap:
          case WebhookManagementResult.Applied(slot) =>
            statsStore.statsFor(bot.team, bot.name, at).map { stats =>
              Right(
                ManagedWebhookDeliveryStats(
                  scope = "bot_history",
                  registrationId = slot.registration.map(reg => registration(reg.registrationId)),
                  last24h = window(stats.last24h),
                  last7d = window(stats.last7d),
                  lastFailure = slot.registration
                    .flatMap(_.lastFailure)
                    .map(failure => LastDeliveryFailure(failure.at, failure.reason))
                )
              )
            }
          case other => IO.pure(mapFailure(other, actor))
    }

  private def validatedSetup(
      request: ManagedWebhookSetupRequest
  ): IO[Either[ManagedWebhookFailure, (WebhookSetupKind, Option[String], List[WebhookCapability])]] =
    request match
      case ManagedWebhookSetupRequest.Create(url, requested) =>
        IO.pure(
          WebhookCapability
            .parseSelection(requested)
            .leftMap(capabilityRejected)
            .map((WebhookSetupKind.Create, Some(url), _))
        )
      case ManagedWebhookSetupRequest.ReplaceUrl(url) =>
        IO.pure(Right((WebhookSetupKind.ReplaceUrl, Some(url), Nil)))
      case ManagedWebhookSetupRequest.RotateSecret(_) =>
        IO.pure(Right((WebhookSetupKind.RotateSecret, None, Nil)))

  private def verify(
      live: ManagedWebhookVerifier,
      bot: Principal.Bot,
      setupId: UUID,
      revision: UUID,
      url: String,
      secret: String
  ): IO[Either[WebhookVerificationFailure, Unit]] =
    for
      nonce <- WebhookSecurity.randomBase64Url(NonceBytes)
      rawBody = Json
        .obj(
          "type"     -> "verification".asJson,
          "version"  -> 2.asJson,
          "bot"      -> Json.obj("team" -> bot.team.asJson, "name" -> bot.name.asJson),
          "setupId"  -> setup(setupId).asJson,
          "revision" -> revisionWire(revision).asJson,
          "nonce"    -> nonce.asJson
        )
        .noSpaces
        .getBytes(UTF_8)
      response <- live.post(url, secret, rawBody, config.verificationTimeout)
    yield response.flatMap(bytes => verifyProof(secret, rawBody, nonce, bytes))

  private def verifyProof(
      secret: String,
      rawBody: Array[Byte],
      nonce: String,
      response: Array[Byte]
  ): Either[WebhookVerificationFailure, Unit] =
    parse(new String(response, UTF_8)).leftMap(_ => WebhookVerificationFailure.MalformedResponse).flatMap { json =>
      val cursor = json.hcursor
      val exact  = json.asObject.exists(_.keys.toSet == Set("nonce", "proof"))
      (cursor.get[String]("nonce"), cursor.get[String]("proof")) match
        case (Right(echo), Right(proof))
            if exact && echo == nonce && proof.matches("[0-9a-f]{64}") &&
              WebhookSecurity.constantTimeEquals(WebhookSecurity.activationProof(secret, rawBody), proof) =>
          Right(())
        case _ => Left(WebhookVerificationFailure.ProofMismatch)
    }

  private def consumeActivationBudgets(
      actor: ManagedWebhookActor,
      bot: Principal.Bot,
      sourceIp: String,
      at: Instant
  ): IO[Option[ManagedWebhookFailure]] =
    consumeBudget(
      WebhookBudgetKind.ActivationActorBot,
      budgetKey(actor, bot),
      config.activationsPerActorBotWindow,
      at
    ).flatMap:
      case limited @ Some(_) => IO.pure(limited)
      case None              =>
        consumeBudget(
          WebhookBudgetKind.ActivationSourceIp,
          digest(sourceIp),
          config.activationsPerSourceIpWindow,
          at
        )

  /** Cheap DB-authoritative guard before a request can spend a verification budget or cause DNS/network I/O. The
    * mutating store operation still repeats the authority and CAS checks in its transaction.
    */
  private def preflight(
      bot: Principal.Bot,
      actor: ManagedWebhookActor,
      expectedRevision: UUID,
      at: Instant,
      requestId: String
  ): IO[Either[ManagedWebhookFailure, StoredSlot]] =
    store
      .webhookSlot(bot.team, bot.name, storedActor(actor), at, context(requestId))
      .map:
        case WebhookManagementResult.Applied(slot) if slot.revision == expectedRevision => Right(slot)
        case WebhookManagementResult.Applied(slot)                                      => Left(stale(toWire(slot)))
        case other                                                                      => mapFailure(other, actor)

  private def consumeBudget(
      kind: WebhookBudgetKind,
      key: String,
      limit: Int,
      at: Instant
  ): IO[Option[ManagedWebhookFailure]] =
    store
      .consumeWebhookVerificationBudget(kind, key, limit, config.budgetWindow, at)
      .map:
        case WebhookBudgetDecision.Allowed(_)                 => None
        case WebhookBudgetDecision.Limited(retryAfterSeconds) =>
          Some(rateLimited(math.max(1L, retryAfterSeconds)))

  private def withRevisionAndSetup[A](revision: String, setupId: String)(
      use: (UUID, UUID) => IO[Either[ManagedWebhookFailure, A]]
  ): IO[Either[ManagedWebhookFailure, A]] =
    (parseRevision(revision), parseOpaque(SetupPrefix, setupId)) match
      case (None, _)             => IO.pure(Left(malformedRevision))
      case (_, None)             => IO.pure(Left(setupNotFound))
      case (Some(rev), Some(id)) => use(rev, id)

  private def storedActor(actor: ManagedWebhookActor): WebhookActor = actor match
    case ManagedWebhookActor.Owner(id) => WebhookActor(WebhookActorKind.Owner, Principal.User(id).externalId)
    case ManagedWebhookActor.Admin(id) => WebhookActor(WebhookActorKind.Admin, id, config.adminAuthorityGeneration)

  private def mapResult[A, B](
      stored: WebhookManagementResult[A],
      actor: ManagedWebhookActor
  )(convert: A => B): Either[ManagedWebhookFailure, B] =
    stored match
      case WebhookManagementResult.Applied(value)        => Right(convert(value))
      case WebhookManagementResult.BotNotFound           => Left(botNotFound)
      case WebhookManagementResult.Stale(current)        => Left(stale(toWire(current)))
      case WebhookManagementResult.Conflict(reason)      => Left(conflict(reason))
      case WebhookManagementResult.SetupNotFound         => Left(setupNotFound)
      case WebhookManagementResult.SetupTerminal(status) => Left(terminal(status))
      case WebhookManagementResult.AuthorityChanged      => Left(authorityChanged(actor))

  private def mapFailure[A, B](
      stored: WebhookManagementResult[A],
      actor: ManagedWebhookActor
  ): Either[ManagedWebhookFailure, B] =
    mapResult(stored, actor)(_ => throw new AssertionError("unexpected applied webhook-management result"))

  private def toWire(stored: StoredSlot): ManagedWebhookSlot =
    ManagedWebhookSlot(
      revisionWire(stored.revision),
      stored.registration.map(toWire),
      stored.pendingSetup.map(toWire)
    )

  private def toWire(stored: StoredRegistration): ManagedWebhookRegistration =
    ManagedWebhookRegistration(
      registration(stored.registrationId),
      stored.url,
      stored.verifiedAt,
      stored.capabilities,
      stored.lastFailure.map(failure => LastDeliveryFailure(failure.at, failure.reason))
    )

  private def toWire(stored: StoredPendingSetup): ManagedPendingWebhookSetup =
    ManagedPendingWebhookSetup(
      setup(stored.setupId),
      stored.kind.wireName,
      stored.candidateUrl,
      stored.createdAt,
      stored.expiresAt,
      stored.canActivate
    )

  private def toWire(stored: StoredCreatedSetup): ManagedWebhookSetupCreated =
    ManagedWebhookSetupCreated(
      setup(stored.setupId),
      stored.kind.wireName,
      stored.secret,
      stored.expiresAt,
      revisionWire(stored.revision)
    )

  private def window(stored: dicechess.play.store.DeliveryStatsWindow): DeliveryWindow =
    DeliveryWindow(
      stored.totalDeliveries,
      stored.outcomes.map(outcome => DeliveryOutcomeCount(outcome.outcome, outcome.count)),
      stored.p50Ms,
      stored.p90Ms,
      stored.p99Ms
    )

object WebhookManagement:
  private val RevisionPrefix     = "whrev_"
  private val SetupPrefix        = "whs_"
  private val RegistrationPrefix = "whr_"
  private val SecretBytes        = 32
  private val NonceBytes         = 16
  private val LeaseGrace         = 5.seconds

  final case class Config(
      verificationTimeout: FiniteDuration,
      adminAuthorityGeneration: String,
      setupTtl: FiniteDuration = 15.minutes,
      budgetWindow: FiniteDuration = 15.minutes,
      setupCreatesPerWindow: Int = 5,
      activationsPerActorBotWindow: Int = 10,
      activationsPerSourceIpWindow: Int = 30
  )

  object Config:
    private val EnabledEnv             = "WEBHOOK_SESSION_MANAGEMENT_ENABLED"
    private val TimeoutEnv             = "WEBHOOK_VERIFICATION_TIMEOUT_SECONDS"
    private val SetupCreatesEnv        = "WEBHOOK_SETUP_CREATES_PER_WINDOW"
    private val ActorBotActivationsEnv = "WEBHOOK_ACTIVATIONS_PER_ACTOR_BOT_WINDOW"
    private val SourceIpActivationsEnv = "WEBHOOK_ACTIVATIONS_PER_SOURCE_IP_WINDOW"

    private val DefaultSetupCreates        = 5
    private val DefaultActorBotActivations = 10
    private val DefaultSourceIpActivations = 30

    def fromValues(
        enabled: Option[String],
        timeoutSeconds: Option[String],
        admins: Set[String],
        setupCreatesPerWindow: Option[String] = None,
        activationsPerActorBotWindow: Option[String] = None,
        activationsPerSourceIpWindow: Option[String] = None
    ): Either[String, Option[Config]] =
      val on = enabled.exists(_.equalsIgnoreCase("true"))
      for
        timeout      <- boundedInt(timeoutSeconds, TimeoutEnv, default = 10, maximum = 30)
        setupCreates <- boundedInt(
          setupCreatesPerWindow,
          SetupCreatesEnv,
          default = DefaultSetupCreates,
          maximum = DefaultSetupCreates
        )
        actorBotActivations <- boundedInt(
          activationsPerActorBotWindow,
          ActorBotActivationsEnv,
          default = DefaultActorBotActivations,
          maximum = DefaultActorBotActivations
        )
        sourceIpActivations <- boundedInt(
          activationsPerSourceIpWindow,
          SourceIpActivationsEnv,
          default = DefaultSourceIpActivations,
          maximum = DefaultSourceIpActivations
        )
      yield Option.when(on)(
        Config(
          verificationTimeout = timeout.seconds,
          adminAuthorityGeneration = digest(admins.toList.sorted.mkString("\n")),
          setupCreatesPerWindow = setupCreates,
          activationsPerActorBotWindow = actorBotActivations,
          activationsPerSourceIpWindow = sourceIpActivations
        )
      )

    private def boundedInt(
        raw: Option[String],
        env: String,
        default: Int,
        maximum: Int
    ): Either[String, Int] =
      raw match
        case None        => Right(default)
        case Some(value) =>
          value.toIntOption
            .filter(number => number >= 1 && number <= maximum)
            .toRight(s"$env must be an integer from 1 to $maximum")

    def fromEnv(admins: Set[String]): IO[Option[Config]] =
      IO.fromEither(
        fromValues(
          sys.env.get(EnabledEnv),
          sys.env.get(TimeoutEnv),
          admins,
          sys.env.get(SetupCreatesEnv),
          sys.env.get(ActorBotActivationsEnv),
          sys.env.get(SourceIpActivationsEnv)
        ).leftMap(new IllegalArgumentException(_))
      )

  private def now: IO[Instant]           = IO.realTimeInstant
  private def context(requestId: String) = WebhookRequestContext(Some(requestId))

  private def revisionWire(id: UUID): String = RevisionPrefix + id.toString
  private def setup(id: UUID): String        = SetupPrefix + id.toString
  private def registration(id: UUID): String = RegistrationPrefix + id.toString

  private def parseRevision(value: String): Option[UUID]               = parseOpaque(RevisionPrefix, value)
  private def parseOpaque(prefix: String, value: String): Option[UUID] =
    Option
      .when(value.startsWith(prefix))(value.drop(prefix.length))
      .flatMap(raw => Either.catchNonFatal(UUID.fromString(raw)).toOption.filter(_.toString == raw))

  private def digest(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def budgetKey(actor: ManagedWebhookActor, bot: Principal.Bot): String =
    digest(s"${actor.kind}\n${actor.userId}\n${bot.team}\n${bot.name}")

  private val botNotFound =
    ManagedWebhookFailure(Status.NotFound, "bot_not_found", "Bot not found", "No registered bot exists at this path.")
  private val malformedRevision =
    ManagedWebhookFailure(
      Status.BadRequest,
      "malformed_request",
      "Malformed request",
      "The webhook revision is malformed."
    )
  private val setupNotFound =
    ManagedWebhookFailure(Status.NotFound, "setup_not_found", "Setup not found", "No setup exists at this path.")
  private val unavailable =
    ManagedWebhookFailure(
      Status.ServiceUnavailable,
      "webhook_verification_unavailable",
      "Webhook verification unavailable",
      "Staged endpoint verification is not enabled on this server."
    )
  private val urlRejected =
    ManagedWebhookFailure(
      Status.UnprocessableEntity,
      "webhook_url_rejected",
      "Webhook URL rejected",
      "The URL did not pass the public HTTPS policy."
    )
  private val verificationFailed =
    ManagedWebhookFailure(
      Status.UnprocessableEntity,
      "webhook_verification_failed",
      "Webhook verification failed",
      "The endpoint did not return a valid verification-v2 proof."
    )
  private def capabilityRejected(reason: String) =
    ManagedWebhookFailure(Status.UnprocessableEntity, "capability_rejected", "Capability rejected", reason)
  private def stale(current: ManagedWebhookSlot) =
    ManagedWebhookFailure(
      Status.PreconditionFailed,
      "stale_webhook_revision",
      "Webhook state changed",
      "Read the current webhook state before retrying.",
      Some(current)
    )
  private def rateLimited(seconds: Long) =
    ManagedWebhookFailure(
      Status.TooManyRequests,
      "webhook_verification_rate_limited",
      "Webhook verification rate limited",
      "Retry after the verification budget resets.",
      retryAfterSeconds = Some(seconds)
    )
  private def authorityChanged(actor: ManagedWebhookActor) = actor match
    case ManagedWebhookActor.Owner(_) =>
      ManagedWebhookFailure(
        Status.Forbidden,
        "bot_not_owned",
        "Bot is not owned",
        "Webhook authority changed during verification."
      )
    case ManagedWebhookActor.Admin(_) =>
      ManagedWebhookFailure(
        Status.Forbidden,
        "admin_required",
        "Administrator required",
        "Administrator authority changed during verification."
      )
  private def conflict(reason: WebhookManagementConflict): ManagedWebhookFailure =
    val detail = reason match
      case WebhookManagementConflict.WebhookAlreadyRegistered => "A webhook is already registered for this bot."
      case WebhookManagementConflict.WebhookNotRegistered     => "No active webhook is registered for this bot."
      case WebhookManagementConflict.PendingSetupExists       => "Cancel or activate the pending setup first."
      case WebhookManagementConflict.ActivationInProgress     => "Another activation is already in progress."
      case WebhookManagementConflict.SetupActorMismatch => "Only the actor that created this setup may activate it."
      case WebhookManagementConflict.ReplacementUrlUnchanged =>
        "The replacement URL must differ from the active webhook URL."
    ManagedWebhookFailure(Status.Conflict, reason.code, "Webhook state conflict", detail)

  private def terminal(status: WebhookSetupTerminalStatus): ManagedWebhookFailure =
    val code = status match
      case WebhookSetupTerminalStatus.Activated         => "setup_consumed"
      case WebhookSetupTerminalStatus.Cancelled         => "setup_cancelled"
      case WebhookSetupTerminalStatus.Expired           => "setup_expired"
      case WebhookSetupTerminalStatus.Invalidated       => "setup_invalidated"
      case WebhookSetupTerminalStatus.AttemptsExhausted => "setup_attempts_exhausted"
    ManagedWebhookFailure(
      Status.Gone,
      code,
      "Webhook setup is no longer available",
      "Create a new setup from the current webhook state."
    )
