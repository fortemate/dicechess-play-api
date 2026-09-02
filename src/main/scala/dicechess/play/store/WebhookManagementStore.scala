package dicechess.play.store

import cats.effect.IO
import dicechess.play.core.{Principal, WebhookCapability}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** The authenticated principal that performs a webhook control-plane operation. `authorityGeneration` is an opaque
  * generation supplied by the admin-authority configuration. Owner generations are read from `bots` inside the
  * transaction instead, so an ownership transfer cannot reuse an old setup.
  */
final case class WebhookActor(kind: WebhookActorKind, id: String, authorityGeneration: String = "")

enum WebhookActorKind(val wireName: String):
  case Owner  extends WebhookActorKind("owner")
  case Admin  extends WebhookActorKind("admin")
  case Bot    extends WebhookActorKind("bot")
  case System extends WebhookActorKind("system")

enum WebhookSetupKind(val wireName: String):
  case Create       extends WebhookSetupKind("create")
  case ReplaceUrl   extends WebhookSetupKind("replaceUrl")
  case RotateSecret extends WebhookSetupKind("rotateSecret")

enum WebhookSetupTerminalStatus(val wireName: String):
  case Activated         extends WebhookSetupTerminalStatus("activated")
  case Cancelled         extends WebhookSetupTerminalStatus("cancelled")
  case Expired           extends WebhookSetupTerminalStatus("expired")
  case Invalidated       extends WebhookSetupTerminalStatus("invalidated")
  case AttemptsExhausted extends WebhookSetupTerminalStatus("attempts_exhausted")

/** Safe, redacted view of an active registration. */
final case class ManagedWebhookRegistration(
    registrationId: UUID,
    url: String,
    verifiedAt: Instant,
    capabilities: List[WebhookCapability],
    lastFailure: Option[LastFailure]
)

/** Safe, redacted view of a staged candidate. */
final case class ManagedPendingWebhookSetup(
    setupId: UUID,
    kind: WebhookSetupKind,
    candidateUrl: String,
    createdAt: Instant,
    expiresAt: Instant,
    canActivate: Boolean
)

/** The authoritative bot-level webhook slot. Its revision exists even when both nullable children are absent. */
final case class ManagedWebhookSlot(
    revision: UUID,
    registration: Option[ManagedWebhookRegistration],
    pendingSetup: Option[ManagedPendingWebhookSetup]
)

/** Validated setup input. Shape-level validation belongs at the HTTP boundary; the store still enforces the active /
  * pending state machine and derives preserved fields for replace/rotate operations inside the transaction.
  */
final case class NewWebhookSetup(
    setupId: UUID,
    kind: WebhookSetupKind,
    requestedUrl: Option[String],
    secret: String,
    capabilities: List[WebhookCapability],
    createdAt: Instant,
    expiresAt: Instant
)

/** The only result that may expose a candidate secret. No read or retry method returns this type. */
final case class CreatedWebhookSetup(
    setupId: UUID,
    kind: WebhookSetupKind,
    secret: String,
    expiresAt: Instant,
    revision: UUID
)

/** Caller-selected activation identity and lease window. The store rebases the window onto its database clock before
  * persisting it, so this value carries intent rather than an authoritative timestamp.
  */
final case class WebhookActivationAttempt(
    setupId: UUID,
    expectedRevision: UUID,
    leaseId: UUID,
    requestedAt: Instant,
    leaseExpiresAt: Instant
)

/** Internal verification material returned only after a cross-instance activation lease is acquired. */
final case class WebhookActivationLease(
    leaseId: UUID,
    setupId: UUID,
    team: String,
    name: String,
    kind: WebhookSetupKind,
    revision: UUID,
    candidateUrl: String,
    candidateSecret: String,
    attemptNumber: Int,
    setupExpiresAt: Instant,
    leaseExpiresAt: Instant
)

final case class WebhookActivationFailure(slot: ManagedWebhookSlot, attemptsExhausted: Boolean)
final case class WebhookDeletion(slot: ManagedWebhookSlot, changed: Boolean)

/** One heartbeat's work. `invalidatedSetups` counts admin candidates scrubbed because their authority generation went
  * stale; `expiredSetups` counts candidates scrubbed because their TTL ran out, which is independent of authority and
  * therefore happens on every instance, authoritative or not.
  */
final case class WebhookAdminAuthorityRefresh(
    authoritative: Boolean,
    invalidatedSetups: Int,
    expiredSetups: Int = 0
)

/** Closed, persistence-safe reasons for an activation outcome. Raw transport exceptions and caller text cannot cross
  * this boundary into the audit stream.
  */
enum WebhookActivationFailureReason(val wireName: String):
  case UrlRejected       extends WebhookActivationFailureReason("url_rejected")
  case TimedOut          extends WebhookActivationFailureReason("timed_out")
  case Unreachable       extends WebhookActivationFailureReason("unreachable")
  case HttpStatus        extends WebhookActivationFailureReason("http_status")
  case OversizedBody     extends WebhookActivationFailureReason("oversized_body")
  case MalformedResponse extends WebhookActivationFailureReason("malformed_response")
  case ProofMismatch     extends WebhookActivationFailureReason("proof_mismatch")
  case AuthorityChanged  extends WebhookActivationFailureReason("authority_changed")

enum WebhookManagementConflict(val code: String):
  case WebhookAlreadyRegistered extends WebhookManagementConflict("webhook_already_registered")
  case WebhookNotRegistered     extends WebhookManagementConflict("webhook_not_registered")
  case PendingSetupExists       extends WebhookManagementConflict("pending_setup_exists")
  case ActivationInProgress     extends WebhookManagementConflict("activation_in_progress")
  case SetupActorMismatch       extends WebhookManagementConflict("setup_actor_mismatch")
  case ReplacementUrlUnchanged  extends WebhookManagementConflict("replacement_url_unchanged")

/** Store outcomes map one-to-one to the session API's typed errors, without importing HTTP into persistence. */
enum WebhookManagementResult[+A]:
  case Applied(value: A)
  case BotNotFound
  case Stale(current: ManagedWebhookSlot)
  case Conflict(reason: WebhookManagementConflict)
  case SetupNotFound
  case SetupTerminal(status: WebhookSetupTerminalStatus)
  case AuthorityChanged

enum WebhookBudgetKind(val wireName: String):
  case SetupActorBot      extends WebhookBudgetKind("setup_actor_bot")
  case ActivationActorBot extends WebhookBudgetKind("activation_actor_bot")
  case ActivationSourceIp extends WebhookBudgetKind("activation_source_ip")

enum WebhookBudgetDecision:
  case Allowed(remaining: Int)
  case Limited(retryAfterSeconds: Long)

/** Correlates a persisted mutation with its request without making request metadata mandatory for legacy Bot API writes
  * and background expiry.
  */
final case class WebhookRequestContext(requestId: Option[String])

object WebhookManagementStore:
  val SetupTtl: FiniteDuration               = 15.minutes
  val TombstoneTtl: FiniteDuration           = 15.minutes
  val MaximumSetupAttempts: Int              = 5
  val DefaultBudgetWindow: Duration          = 15.minutes
  val AdminHeartbeatInterval: FiniteDuration = 5.seconds
  val AdminHeartbeatLiveness: FiniteDuration = 20.seconds

  /** How many bots one heartbeat may fence in a single sweep transaction — expired candidates and stale admin setups
    * share this budget, because they share the transaction. Every target costs an advisory lock and a row lock held
    * until commit, and each held fence blocks that bot's owner and admin control-plane operations, so the sweep must
    * not scale with the backlog. At [[AdminHeartbeatInterval]] this still drains far faster than [[SetupTtl]] can
    * create work.
    */
  val SweepBatchSize: Int = 25

/** Transactional storage boundary for ADR-004's staged owner/admin webhook control plane. Implementations must keep
  * each visible state transition, revision change, secret destruction and audit insert in one transaction.
  */
trait WebhookManagementStore:
  def webhookSlot(
      team: String,
      name: String,
      actor: WebhookActor,
      now: Instant,
      context: WebhookRequestContext
  ): IO[WebhookManagementResult[ManagedWebhookSlot]]

  def createWebhookSetup(
      team: String,
      name: String,
      actor: WebhookActor,
      expectedRevision: UUID,
      setup: NewWebhookSetup,
      context: WebhookRequestContext
  ): IO[WebhookManagementResult[CreatedWebhookSetup]]

  def acquireWebhookActivation(
      bot: Principal.Bot,
      actor: WebhookActor,
      attempt: WebhookActivationAttempt,
      context: WebhookRequestContext
  ): IO[WebhookManagementResult[WebhookActivationLease]]

  def completeWebhookActivation(
      actor: WebhookActor,
      lease: WebhookActivationLease,
      verifiedAt: Instant,
      context: WebhookRequestContext
  ): IO[WebhookManagementResult[ManagedWebhookSlot]]

  def failWebhookActivation(
      actor: WebhookActor,
      lease: WebhookActivationLease,
      reason: WebhookActivationFailureReason,
      now: Instant,
      context: WebhookRequestContext
  ): IO[WebhookManagementResult[WebhookActivationFailure]]

  def cancelWebhookSetup(
      team: String,
      name: String,
      actor: WebhookActor,
      setupId: UUID,
      expectedRevision: UUID,
      now: Instant,
      context: WebhookRequestContext
  ): IO[WebhookManagementResult[ManagedWebhookSlot]]

  def updateWebhookCapabilities(
      team: String,
      name: String,
      actor: WebhookActor,
      expectedRevision: UUID,
      capabilities: List[WebhookCapability],
      now: Instant,
      context: WebhookRequestContext
  ): IO[WebhookManagementResult[ManagedWebhookSlot]]

  def deleteManagedWebhook(
      team: String,
      name: String,
      actor: WebhookActor,
      expectedRevision: UUID,
      now: Instant,
      context: WebhookRequestContext
  ): IO[WebhookManagementResult[WebhookDeletion]]

  /** Heartbeat this process's admin-allowlist generation and, only when it is the sole live generation, invalidate
    * stale pending admin setups. Overlapping generations fail closed until the old heartbeat expires.
    */
  def refreshAdminWebhookAuthority(
      liveAuthorityGeneration: String,
      context: WebhookRequestContext
  ): IO[WebhookAdminAuthorityRefresh]

  /** Fixed-window persistent verification budget. `key` is an opaque, already-minimised dimension key. */
  def consumeWebhookVerificationBudget(
      kind: WebhookBudgetKind,
      key: String,
      limit: Int,
      window: FiniteDuration,
      now: Instant
  ): IO[WebhookBudgetDecision]
