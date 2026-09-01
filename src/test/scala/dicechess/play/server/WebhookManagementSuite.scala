package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.{Principal, WebhookCapability}
import dicechess.play.store.{
  CreatedWebhookSetup,
  DeliveryOutcome,
  DeliveryStatsWindow,
  LastFailure,
  ManagedPendingWebhookSetup as StoredPendingSetup,
  ManagedWebhookRegistration as StoredRegistration,
  ManagedWebhookSlot as StoredSlot,
  NewWebhookSetup,
  OutcomeCount,
  WebhookActivationFailure,
  WebhookActivationFailureReason,
  WebhookActivationAttempt,
  WebhookActivationLease,
  WebhookAdminAuthorityRefresh,
  WebhookActor,
  WebhookBudgetDecision,
  WebhookBudgetKind,
  WebhookDeletion,
  WebhookManagementConflict,
  WebhookManagementResult,
  WebhookManagementStore,
  WebhookRequestContext,
  WebhookSetupKind,
  WebhookSetupTerminalStatus,
  WebhookStats,
  WebhookStatsStore
}
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.Status

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

class WebhookManagementSuite extends CatsEffectSuite:

  private val Bot: Principal.Bot = Principal.Bot("acme", "greedy")
  private val Owner              = ManagedWebhookActor.Owner("owner-1")
  private val RevisionId         = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val NextRevisionId     = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val SetupId            = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val RegistrationId     = UUID.fromString("30000000-0000-0000-0000-000000000001")
  private val LeaseId            = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val CandidateUrl       = "https://bot.example/turn"
  private val CandidateSecret    = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
  private val CreatedAt          = Instant.parse("2026-09-01T10:00:00Z")
  private val ExpiresAt          = CreatedAt.plusSeconds(15.minutes.toSeconds)
  private val VerificationAt     = Instant.parse("2026-09-01T10:01:00Z")
  private val VerificationDelay  = 3.seconds

  private val PendingSetup = StoredPendingSetup(
    SetupId,
    WebhookSetupKind.Create,
    CandidateUrl,
    CreatedAt,
    ExpiresAt,
    canActivate = true
  )

  private val Registration = StoredRegistration(
    RegistrationId,
    CandidateUrl,
    VerificationAt,
    List(WebhookCapability.Draws),
    Some(LastFailure(VerificationAt.plusSeconds(10), "safe persisted failure"))
  )

  private val CurrentSlot = StoredSlot(
    RevisionId,
    Some(Registration),
    Some(PendingSetup)
  )

  private val ActivatedSlot = StoredSlot(
    NextRevisionId,
    Some(Registration),
    None
  )

  private val ActivationLease = WebhookActivationLease(
    LeaseId,
    SetupId,
    Bot.team,
    Bot.name,
    WebhookSetupKind.Create,
    RevisionId,
    CandidateUrl,
    CandidateSecret,
    attemptNumber = 1,
    ExpiresAt,
    ExpiresAt.plusSeconds(5)
  )

  private val Config = WebhookManagement.Config(
    verificationTimeout = VerificationDelay,
    adminAuthorityGeneration = "admin-generation",
    setupCreatesPerWindow = 7,
    activationsPerActorBotWindow = 11,
    activationsPerSourceIpWindow = 13
  )

  private def revision(id: UUID): String     = s"whrev_$id"
  private def setup(id: UUID): String        = s"whs_$id"
  private def registration(id: UUID): String = s"whr_$id"

  final private case class BudgetCall(
      kind: WebhookBudgetKind,
      key: String,
      limit: Int,
      window: FiniteDuration
  )

  final private case class StoreObserved(
      creates: List[NewWebhookSetup] = Nil,
      acquireCount: Int = 0,
      completeCount: Int = 0,
      failureReasons: List[String] = Nil,
      cancellations: Int = 0,
      capabilityUpdates: List[List[WebhookCapability]] = Nil,
      deleteCount: Int = 0,
      budgets: List[BudgetCall] = Nil
  )

  final private case class StoreAnswers(
      slot: WebhookManagementResult[StoredSlot],
      create: NewWebhookSetup => WebhookManagementResult[CreatedWebhookSetup],
      acquire: WebhookManagementResult[WebhookActivationLease],
      complete: WebhookManagementResult[StoredSlot],
      failActivation: WebhookManagementResult[WebhookActivationFailure],
      cancel: WebhookManagementResult[StoredSlot],
      updateCapabilities: WebhookManagementResult[StoredSlot],
      delete: WebhookManagementResult[WebhookDeletion],
      budget: (WebhookBudgetKind, Instant) => WebhookBudgetDecision
  )

  private def defaultAnswers(
      slot: WebhookManagementResult[StoredSlot] = WebhookManagementResult.Applied(CurrentSlot),
      create: NewWebhookSetup => WebhookManagementResult[CreatedWebhookSetup] = setup =>
        WebhookManagementResult.Applied(
          CreatedWebhookSetup(setup.setupId, setup.kind, setup.secret, setup.expiresAt, NextRevisionId)
        ),
      acquire: WebhookManagementResult[WebhookActivationLease] = WebhookManagementResult.Applied(ActivationLease),
      complete: WebhookManagementResult[StoredSlot] = WebhookManagementResult.Applied(ActivatedSlot),
      failActivation: WebhookManagementResult[WebhookActivationFailure] = WebhookManagementResult.Applied(
        WebhookActivationFailure(CurrentSlot, attemptsExhausted = false)
      ),
      cancel: WebhookManagementResult[StoredSlot] = WebhookManagementResult.Applied(CurrentSlot),
      updateCapabilities: WebhookManagementResult[StoredSlot] = WebhookManagementResult.Applied(CurrentSlot),
      delete: WebhookManagementResult[WebhookDeletion] = WebhookManagementResult.Applied(
        WebhookDeletion(CurrentSlot, changed = true)
      ),
      budget: (WebhookBudgetKind, Instant) => WebhookBudgetDecision = (_, _) => WebhookBudgetDecision.Allowed(99)
  ): StoreAnswers =
    StoreAnswers(
      slot,
      create,
      acquire,
      complete,
      failActivation,
      cancel,
      updateCapabilities,
      delete,
      budget
    )

  final private class StubStore(
      answers: StoreAnswers,
      val observed: Ref[IO, StoreObserved]
  ) extends WebhookManagementStore:
    def webhookSlot(
        team: String,
        name: String,
        actor: WebhookActor,
        now: Instant,
        context: WebhookRequestContext
    ): IO[WebhookManagementResult[StoredSlot]] =
      IO.pure(answers.slot)

    def createWebhookSetup(
        team: String,
        name: String,
        actor: WebhookActor,
        expectedRevision: UUID,
        setup: NewWebhookSetup,
        context: WebhookRequestContext
    ): IO[WebhookManagementResult[CreatedWebhookSetup]] =
      observed.update(state => state.copy(creates = state.creates :+ setup)).as(answers.create(setup))

    def acquireWebhookActivation(
        bot: Principal.Bot,
        actor: WebhookActor,
        attempt: WebhookActivationAttempt,
        context: WebhookRequestContext
    ): IO[WebhookManagementResult[WebhookActivationLease]] =
      observed.update(state => state.copy(acquireCount = state.acquireCount + 1)).as(answers.acquire)

    def completeWebhookActivation(
        actor: WebhookActor,
        lease: WebhookActivationLease,
        verifiedAt: Instant,
        context: WebhookRequestContext
    ): IO[WebhookManagementResult[StoredSlot]] =
      observed.update(state => state.copy(completeCount = state.completeCount + 1)).as(answers.complete)

    def failWebhookActivation(
        actor: WebhookActor,
        lease: WebhookActivationLease,
        reason: WebhookActivationFailureReason,
        now: Instant,
        context: WebhookRequestContext
    ): IO[WebhookManagementResult[WebhookActivationFailure]] =
      observed
        .update(state => state.copy(failureReasons = state.failureReasons :+ reason.wireName))
        .as(answers.failActivation)

    def cancelWebhookSetup(
        team: String,
        name: String,
        actor: WebhookActor,
        setupId: UUID,
        expectedRevision: UUID,
        now: Instant,
        context: WebhookRequestContext
    ): IO[WebhookManagementResult[StoredSlot]] =
      observed.update(state => state.copy(cancellations = state.cancellations + 1)).as(answers.cancel)

    def updateWebhookCapabilities(
        team: String,
        name: String,
        actor: WebhookActor,
        expectedRevision: UUID,
        capabilities: List[WebhookCapability],
        now: Instant,
        context: WebhookRequestContext
    ): IO[WebhookManagementResult[StoredSlot]] =
      observed
        .update(state => state.copy(capabilityUpdates = state.capabilityUpdates :+ capabilities))
        .as(answers.updateCapabilities)

    def deleteManagedWebhook(
        team: String,
        name: String,
        actor: WebhookActor,
        expectedRevision: UUID,
        now: Instant,
        context: WebhookRequestContext
    ): IO[WebhookManagementResult[WebhookDeletion]] =
      observed.update(state => state.copy(deleteCount = state.deleteCount + 1)).as(answers.delete)

    def refreshAdminWebhookAuthority(
        liveAuthorityGeneration: String,
        context: WebhookRequestContext
    ): IO[WebhookAdminAuthorityRefresh] = IO.pure(WebhookAdminAuthorityRefresh(authoritative = true, 0))

    def consumeWebhookVerificationBudget(
        kind: WebhookBudgetKind,
        key: String,
        limit: Int,
        window: FiniteDuration,
        now: Instant
    ): IO[WebhookBudgetDecision] =
      observed
        .update(state => state.copy(budgets = state.budgets :+ BudgetCall(kind, key, limit, window)))
        .as(answers.budget(kind, now))

  final private case class PostCall(
      url: String,
      secret: String,
      rawBody: Vector[Byte],
      timeout: FiniteDuration
  )

  final private case class VerifierObserved(
      validations: List[String] = Nil,
      posts: List[PostCall] = Nil
  )

  private type PostAnswer =
    (String, String, Array[Byte], FiniteDuration) => Either[WebhookVerificationFailure, Array[Byte]]

  final private class StubVerifier(
      validationAnswer: Either[WebhookVerificationFailure, Unit],
      postAnswer: PostAnswer,
      val observed: Ref[IO, VerifierObserved]
  ) extends ManagedWebhookVerifier:
    def validate(url: String): IO[Either[WebhookVerificationFailure, Unit]] =
      observed.update(state => state.copy(validations = state.validations :+ url)).as(validationAnswer)

    def post(
        url: String,
        secret: String,
        rawBody: Array[Byte],
        timeout: FiniteDuration
    ): IO[Either[WebhookVerificationFailure, Array[Byte]]] =
      observed
        .update(state => state.copy(posts = state.posts :+ PostCall(url, secret, rawBody.toVector, timeout)))
        .as(postAnswer(url, secret, rawBody, timeout))

  final private class StubStatsStore(
      answer: WebhookStats,
      val requests: Ref[IO, List[(String, String)]]
  ) extends WebhookStatsStore:
    def recordDelivery(
        team: String,
        name: String,
        outcome: DeliveryOutcome,
        elapsed: FiniteDuration,
        at: Instant
    ): IO[Unit] = IO.unit

    def statsFor(team: String, name: String, now: Instant): IO[WebhookStats] =
      requests.update(_ :+ (team, name)).as(answer)

  final private case class Fixture(
      management: WebhookManagement,
      store: StubStore,
      verifier: StubVerifier,
      stats: StubStatsStore
  )

  private def validProof(
      url: String,
      secret: String,
      rawBody: Array[Byte],
      timeout: FiniteDuration
  ): Either[WebhookVerificationFailure, Array[Byte]] =
    val _ = (url, timeout)
    for
      json  <- parse(new String(rawBody, UTF_8)).leftMap(_ => WebhookVerificationFailure.MalformedResponse)
      nonce <- json.hcursor.get[String]("nonce").leftMap(_ => WebhookVerificationFailure.MalformedResponse)
    yield s"{\"nonce\":${nonce.asJson.noSpaces},\"proof\":\"${WebhookSecurity.activationProof(secret, rawBody)}\"}"
      .getBytes(UTF_8)

  private def fixture(
      answers: StoreAnswers = defaultAnswers(),
      validationAnswer: Either[WebhookVerificationFailure, Unit] = Right(()),
      postAnswer: PostAnswer = validProof,
      statsAnswer: WebhookStats = WebhookStats.empty,
      enabled: Boolean = true
  ): IO[Fixture] =
    for
      storeObserved    <- Ref.of[IO, StoreObserved](StoreObserved())
      verifierObserved <- Ref.of[IO, VerifierObserved](VerifierObserved())
      statsRequests    <- Ref.of[IO, List[(String, String)]](Nil)
      store      = StubStore(answers, storeObserved)
      verifier   = StubVerifier(validationAnswer, postAnswer, verifierObserved)
      stats      = StubStatsStore(statsAnswer, statsRequests)
      management = WebhookManagement(store, stats, Option.when(enabled)(verifier), Config)
    yield Fixture(management, store, verifier, stats)

  private def activate(
      management: WebhookManagement,
      actor: ManagedWebhookActor = Owner,
      stillAuthorized: IO[Boolean] = IO.pure(true),
      sourceIp: String = "203.0.113.9"
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookSlot]] =
    management.activate(
      Bot,
      actor,
      revision(RevisionId),
      setup(SetupId),
      "request-activate",
      sourceIp,
      stillAuthorized
    )

  private def assertFailure[A](
      result: Either[ManagedWebhookFailure, A],
      status: Status,
      code: String
  ): ManagedWebhookFailure =
    result match
      case Left(failure) =>
        assertEquals(failure.status, status)
        assertEquals(failure.code, code)
        failure
      case Right(value) => fail(s"expected $status/$code, got success: $value")

  test("the session-management gate defaults off and validates its dedicated timeout before startup"):
    assertEquals(WebhookManagement.Config.fromValues(None, None, Set.empty), Right(None))
    val enabled = WebhookManagement.Config
      .fromValues(Some("true"), None, Set("00000000-0000-0000-0000-000000000001"))
      .flatMap(_.toRight("feature unexpectedly disabled"))
    assertEquals(enabled.map(_.verificationTimeout), Right(10.seconds))
    assertEquals(
      enabled.map(config =>
        (
          config.setupCreatesPerWindow,
          config.activationsPerActorBotWindow,
          config.activationsPerSourceIpWindow
        )
      ),
      Right((5, 10, 30))
    )
    List("0", "31", "not-a-number").foreach: invalid =>
      assert(
        WebhookManagement.Config.fromValues(Some("true"), Some(invalid), Set.empty).isLeft,
        s"timeout $invalid must fail startup validation"
      )

  test("production verification budgets accept only stricter limits"):
    def parsed(
        setupCreates: Option[String] = None,
        actorBotActivations: Option[String] = None,
        sourceIpActivations: Option[String] = None
    ) =
      WebhookManagement.Config
        .fromValues(
          Some("true"),
          None,
          Set.empty,
          setupCreates,
          actorBotActivations,
          sourceIpActivations
        )
        .flatMap(_.toRight("feature unexpectedly disabled"))

    assertEquals(
      parsed(Some("1"), Some("1"), Some("1")).map(config =>
        (
          config.setupCreatesPerWindow,
          config.activationsPerActorBotWindow,
          config.activationsPerSourceIpWindow
        )
      ),
      Right((1, 1, 1))
    )
    assertEquals(
      parsed(Some("5"), Some("10"), Some("30")).map(config =>
        (
          config.setupCreatesPerWindow,
          config.activationsPerActorBotWindow,
          config.activationsPerSourceIpWindow
        )
      ),
      Right((5, 10, 30))
    )

    List(
      parsed(setupCreates = Some("0")),
      parsed(setupCreates = Some("6")),
      parsed(actorBotActivations = Some("0")),
      parsed(actorBotActivations = Some("11")),
      parsed(sourceIpActivations = Some("0")),
      parsed(sourceIpActivations = Some("31")),
      parsed(sourceIpActivations = Some("not-a-number"))
    ).foreach(result => assert(result.isLeft, s"non-stricter budget must fail startup validation: $result"))

    assert(
      WebhookManagement.Config
        .fromValues(None, None, Set.empty, setupCreatesPerWindow = Some("6"))
        .isLeft,
      "an invalid dormant budget must fail startup even while the feature flag is off"
    )

  test("opaque revision and setup identifiers must exactly match their canonical wire form"):
    val upperRevision = "whrev_AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
    val upperSetup    = "whs_BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB"
    for
      f              <- fixture()
      revisionResult <- f.management.createSetup(
        Bot,
        Owner,
        upperRevision,
        ManagedWebhookSetupRequest.Create(CandidateUrl, Nil),
        "request-upper-revision",
        "203.0.113.9"
      )
      setupResult <- f.management.cancelSetup(
        Bot,
        Owner,
        revision(RevisionId),
        upperSetup,
        "request-upper-setup"
      )
      observed <- f.store.observed.get
    yield
      assertFailure(revisionResult, Status.BadRequest, "malformed_request")
      assertFailure(setupResult, Status.NotFound, "setup_not_found")
      assertEquals(observed.creates, Nil)
      assertEquals(observed.cancellations, 0)

  test("activation emits the exact verification-v2 body and accepts the domain-separated golden proof"):
    val goldenBody =
      """{"type":"verification","version":2,"bot":{"team":"acme","name":"greedy"},"setupId":"whs_test","revision":"whrev_test","nonce":"AAECAwQFBgcICQoLDA0ODw"}"""
    val goldenBytes = goldenBody.getBytes(UTF_8)
    assertEquals(
      WebhookSecurity.sign(CandidateSecret, 1756728000L, goldenBytes),
      "99f91b462250d95ec39f942844622eed87620c07b022c11a0b65f5380d123803"
    )
    assertEquals(
      WebhookSecurity.activationProof(CandidateSecret, goldenBytes),
      "408d1e2804bdc90036333e6475523904113d009a6e971711e7500f8eb6314947"
    )

    for
      f        <- fixture()
      result   <- activate(f.management)
      observed <- f.verifier.observed.get
      stored   <- f.store.observed.get
    yield
      assertEquals(
        result,
        Right(
          ManagedWebhookSlot(
            revision(NextRevisionId),
            Some(
              ManagedWebhookRegistration(
                registration(RegistrationId),
                CandidateUrl,
                VerificationAt,
                List(WebhookCapability.Draws),
                Some(LastDeliveryFailure(VerificationAt.plusSeconds(10), "safe persisted failure"))
              )
            ),
            None
          )
        )
      )
      assertEquals(observed.posts.size, 1)
      val post  = observed.posts.head
      val body  = new String(post.rawBody.toArray, UTF_8)
      val nonce = parse(body).toOption.flatMap(_.hcursor.get[String]("nonce").toOption).getOrElse(fail("missing nonce"))
      assert(nonce.matches("[A-Za-z0-9_-]{22}"))
      assertEquals(
        body,
        s"""{"type":"verification","version":2,"bot":{"team":"acme","name":"greedy"},"setupId":"${setup(
            SetupId
          )}","revision":"${revision(RevisionId)}","nonce":"$nonce"}"""
      )
      assertEquals(post.url, CandidateUrl)
      assertEquals(post.secret, CandidateSecret)
      assertEquals(post.timeout, VerificationDelay)
      assertEquals(stored.completeCount, 1)
      assertEquals(stored.failureReasons, Nil)

  test("wrong and reflected request proofs are rejected as proof_mismatch without committing"):
    val responders: List[PostAnswer] = List(
      (_, _, rawBody, _) => responseWithProof(rawBody, "0" * 64),
      (_, secret, rawBody, _) => responseWithProof(rawBody, WebhookSecurity.sign(secret, 1756728000L, rawBody))
    )

    responders.traverse_ { responder =>
      for
        f          <- fixture(postAnswer = responder)
        authorized <- Ref.of[IO, Int](0)
        result     <- activate(f.management, stillAuthorized = authorized.updateAndGet(_ + 1).as(true))
        stored     <- f.store.observed.get
        checks     <- authorized.get
      yield
        assertFailure(result, Status.UnprocessableEntity, "webhook_verification_failed")
        assertEquals(stored.failureReasons, List("proof_mismatch"))
        assertEquals(stored.completeCount, 0)
        assertEquals(checks, 0, "authority must be rechecked only after a valid proof")
    }

  test("safe verifier failures map to one public error and persist only their enum reason"):
    WebhookVerificationFailure.values.toList.traverse_ { transportFailure =>
      for
        f      <- fixture(postAnswer = (_, _, _, _) => Left(transportFailure))
        result <- activate(f.management)
        stored <- f.store.observed.get
      yield
        assertFailure(result, Status.UnprocessableEntity, "webhook_verification_failed")
        assertEquals(stored.failureReasons, List(transportFailure.auditReason.wireName))
        assertEquals(stored.completeCount, 0)
    }

  test("a failed activation returns terminal 410 only when that failure exhausts the setup"):
    List(
      false -> (Status.UnprocessableEntity -> "webhook_verification_failed"),
      true  -> (Status.Gone                -> "setup_attempts_exhausted")
    ).traverse_ { case (attemptsExhausted, (expectedStatus, expectedCode)) =>
      val failed = WebhookManagementResult.Applied(
        WebhookActivationFailure(CurrentSlot, attemptsExhausted)
      )
      for
        f <- fixture(
          answers = defaultAnswers(failActivation = failed),
          postAnswer = (_, _, _, _) => Left(WebhookVerificationFailure.TimedOut)
        )
        result <- activate(f.management)
        stored <- f.store.observed.get
      yield
        assertFailure(result, expectedStatus, expectedCode)
        assertEquals(stored.failureReasons, List(WebhookVerificationFailure.TimedOut.auditReason.wireName))
        assertEquals(stored.completeCount, 0)
    }

  test("management reads remain available while setup and activation fail closed without a verifier"):
    for
      f       <- fixture(enabled = false)
      read    <- f.management.read(Bot, Owner)
      created <- f.management.createSetup(
        Bot,
        Owner,
        revision(RevisionId),
        ManagedWebhookSetupRequest.Create(CandidateUrl, Nil),
        "request-create",
        "203.0.113.9"
      )
      activated <- activate(f.management)
      stored    <- f.store.observed.get
      verified  <- f.verifier.observed.get
    yield
      assert(read.isRight)
      assertFailure(created, Status.ServiceUnavailable, "webhook_verification_unavailable")
      assertFailure(activated, Status.ServiceUnavailable, "webhook_verification_unavailable")
      assertEquals(stored.budgets, Nil)
      assertEquals(stored.creates, Nil)
      assertEquals(stored.acquireCount, 0)
      assertEquals(verified, VerifierObserved())

  test("create and update canonicalize capabilities and reject unknown or reserved names before persistence"):
    for
      f       <- fixture()
      created <- f.management.createSetup(
        Bot,
        Owner,
        revision(RevisionId),
        ManagedWebhookSetupRequest.Create(CandidateUrl, List("draws", "draws")),
        "request-create",
        "203.0.113.9"
      )
      updated <- f.management.updateCapabilities(
        Bot,
        Owner,
        revision(RevisionId),
        List("draws", "draws"),
        "request-update"
      )
      reserved <- f.management.updateCapabilities(
        Bot,
        Owner,
        revision(RevisionId),
        List("doubling"),
        "request-reserved"
      )
      unknown <- f.management.createSetup(
        Bot,
        Owner,
        revision(RevisionId),
        ManagedWebhookSetupRequest.Create(CandidateUrl, List("future-capability")),
        "request-unknown",
        "203.0.113.9"
      )
      stored   <- f.store.observed.get
      verified <- f.verifier.observed.get
    yield
      assert(created.isRight)
      assert(updated.isRight)
      val reservedFailure = assertFailure(reserved, Status.UnprocessableEntity, "capability_rejected")
      assertEquals(reservedFailure.detail, "webhook capability is not available: doubling")
      val unknownFailure = assertFailure(unknown, Status.UnprocessableEntity, "capability_rejected")
      assertEquals(unknownFailure.detail, "unknown webhook capability: future-capability")
      assertEquals(stored.creates.map(_.capabilities), List(List(WebhookCapability.Draws)))
      assertEquals(stored.capabilityUpdates, List(List(WebhookCapability.Draws)))
      assertEquals(verified.validations, List(CandidateUrl))

  test("a stale CAS result carries the exact current redacted slot"):
    for
      f      <- fixture(answers = defaultAnswers(cancel = WebhookManagementResult.Stale(CurrentSlot)))
      result <- f.management.cancelSetup(
        Bot,
        Owner,
        revision(RevisionId),
        setup(SetupId),
        "request-cancel"
      )
    yield
      val failure = assertFailure(result, Status.PreconditionFailed, "stale_webhook_revision")
      val current = failure.current.getOrElse(fail("stale response did not include current state"))
      assertEquals(current.revision, revision(RevisionId))
      assertEquals(current.registration.map(_.registrationId), Some(registration(RegistrationId)))
      assertEquals(current.pendingSetup.map(_.setupId), Some(setup(SetupId)))
      val encoded = current.asJson.noSpaces
      assert(!encoded.contains("\"secret\""), encoded)
      assert(!encoded.contains(CandidateSecret), encoded)

  test("setup and activation budgets short-circuit before validation, leasing, or verification"):
    val limited: (WebhookBudgetKind, Instant) => WebhookBudgetDecision =
      (_, _) => WebhookBudgetDecision.Limited(17)

    for
      setupFixture <- fixture(answers = defaultAnswers(budget = limited))
      setupResult  <- setupFixture.management.createSetup(
        Bot,
        Owner,
        revision(RevisionId),
        ManagedWebhookSetupRequest.Create(CandidateUrl, Nil),
        "request-create",
        "198.51.100.4"
      )
      setupStored   <- setupFixture.store.observed.get
      setupVerifier <- setupFixture.verifier.observed.get
      actorFixture  <- fixture(
        answers = defaultAnswers(
          budget = (kind, _) =>
            if kind == WebhookBudgetKind.ActivationActorBot then WebhookBudgetDecision.Limited(17)
            else WebhookBudgetDecision.Allowed(1)
        )
      )
      actorResult <- activate(actorFixture.management)
      actorStored <- actorFixture.store.observed.get
      ipFixture   <- fixture(
        answers = defaultAnswers(
          budget = (kind, _) =>
            if kind == WebhookBudgetKind.ActivationSourceIp then WebhookBudgetDecision.Limited(17)
            else WebhookBudgetDecision.Allowed(1)
        )
      )
      ipResult <- activate(ipFixture.management, sourceIp = "198.51.100.4")
      ipStored <- ipFixture.store.observed.get
    yield
      assertEquals(
        assertFailure(setupResult, Status.TooManyRequests, "webhook_verification_rate_limited").retryAfterSeconds,
        Some(17L)
      )
      assertEquals(setupStored.budgets.map(_.kind), List(WebhookBudgetKind.SetupActorBot))
      assertEquals(setupStored.budgets.map(_.limit), List(Config.setupCreatesPerWindow))
      assertEquals(setupStored.budgets.map(_.window), List(Config.budgetWindow))
      assert(setupStored.budgets.forall(_.key.matches("[0-9a-f]{64}")))
      assert(!setupStored.budgets.exists(_.key.contains("owner-1")))
      assertEquals(setupStored.creates, Nil)
      assertEquals(setupVerifier.validations, Nil)

      assertEquals(
        assertFailure(actorResult, Status.TooManyRequests, "webhook_verification_rate_limited").retryAfterSeconds,
        Some(17L)
      )
      assertEquals(actorStored.budgets.map(_.kind), List(WebhookBudgetKind.ActivationActorBot))
      assertEquals(actorStored.acquireCount, 0)

      assertEquals(
        assertFailure(ipResult, Status.TooManyRequests, "webhook_verification_rate_limited").retryAfterSeconds,
        Some(17L)
      )
      assertEquals(
        ipStored.budgets.map(_.kind),
        List(WebhookBudgetKind.ActivationActorBot, WebhookBudgetKind.ActivationSourceIp)
      )
      assertEquals(
        ipStored.budgets.map(_.limit),
        List(Config.activationsPerActorBotWindow, Config.activationsPerSourceIpWindow)
      )
      assert(ipStored.budgets.forall(_.key.matches("[0-9a-f]{64}")))
      assert(!ipStored.budgets.exists(_.key.contains("198.51.100.4")))
      assertEquals(ipStored.acquireCount, 0)

  test("DB authority and revision preflight fail before budgets, DNS validation, or verification"):
    val cases = List[(WebhookManagementResult[StoredSlot], Status, String)](
      (WebhookManagementResult.AuthorityChanged, Status.Forbidden, "bot_not_owned"),
      (
        WebhookManagementResult.Applied(CurrentSlot.copy(revision = NextRevisionId)),
        Status.PreconditionFailed,
        "stale_webhook_revision"
      )
    )

    cases.traverse_ { case (slotAnswer, expectedStatus, expectedCode) =>
      for
        createFixture <- fixture(answers = defaultAnswers(slot = slotAnswer))
        createResult  <- createFixture.management.createSetup(
          Bot,
          Owner,
          revision(RevisionId),
          ManagedWebhookSetupRequest.Create(CandidateUrl, Nil),
          "request-create-preflight",
          "198.51.100.4"
        )
        createStored     <- createFixture.store.observed.get
        createVerifier   <- createFixture.verifier.observed.get
        activateFixture  <- fixture(answers = defaultAnswers(slot = slotAnswer))
        activateResult   <- activate(activateFixture.management)
        activateStored   <- activateFixture.store.observed.get
        activateVerifier <- activateFixture.verifier.observed.get
      yield
        assertFailure(createResult, expectedStatus, expectedCode)
        assertEquals(createStored.budgets, Nil)
        assertEquals(createStored.creates, Nil)
        assertEquals(createVerifier, VerifierObserved())

        assertFailure(activateResult, expectedStatus, expectedCode)
        assertEquals(activateStored.budgets, Nil)
        assertEquals(activateStored.acquireCount, 0)
        assertEquals(activateVerifier, VerifierObserved())
    }

  test("authority is rechecked after proof and revocation fails the lease without committing"):
    List[(ManagedWebhookActor, String)](
      ManagedWebhookActor.Owner("owner-1") -> "bot_not_owned",
      ManagedWebhookActor.Admin("admin-1") -> "admin_required"
    ).traverse_ { case (actor, code) =>
      for
        f          <- fixture()
        rechecks   <- Ref.of[IO, Int](0)
        result     <- activate(f.management, actor, rechecks.updateAndGet(_ + 1).as(false))
        stored     <- f.store.observed.get
        verifier   <- f.verifier.observed.get
        checkCount <- rechecks.get
      yield
        assertFailure(result, Status.Forbidden, code)
        assertEquals(verifier.posts.size, 1)
        assertEquals(checkCount, 1)
        assertEquals(stored.failureReasons, List("authority_changed"))
        assertEquals(stored.completeCount, 0)
    }

  test("store conflicts, terminal states, and generic errors have stable public mappings"):
    val cases: List[(WebhookManagementResult[StoredSlot], Status, String)] =
      List(
        (WebhookManagementResult.BotNotFound, Status.NotFound, "bot_not_found"),
        (WebhookManagementResult.SetupNotFound, Status.NotFound, "setup_not_found")
      ) ++ WebhookManagementConflict.values.toList.map(reason =>
        (WebhookManagementResult.Conflict(reason), Status.Conflict, reason.code)
      ) ++ List(
        WebhookSetupTerminalStatus.Activated         -> "setup_consumed",
        WebhookSetupTerminalStatus.Cancelled         -> "setup_cancelled",
        WebhookSetupTerminalStatus.Expired           -> "setup_expired",
        WebhookSetupTerminalStatus.Invalidated       -> "setup_invalidated",
        WebhookSetupTerminalStatus.AttemptsExhausted -> "setup_attempts_exhausted"
      ).map { case (terminal, code) =>
        (WebhookManagementResult.SetupTerminal(terminal), Status.Gone, code)
      }

    cases.traverse_ { case (storedResult, status, code) =>
      for
        f      <- fixture(answers = defaultAnswers(cancel = storedResult))
        result <- f.management.cancelSetup(
          Bot,
          Owner,
          revision(RevisionId),
          setup(SetupId),
          "request-cancel"
        )
      yield assertFailure(result, status, code)
    }

  test("store authority changes retain the owner/admin public distinction"):
    List[(ManagedWebhookActor, String)](
      ManagedWebhookActor.Owner("owner-1") -> "bot_not_owned",
      ManagedWebhookActor.Admin("admin-1") -> "admin_required"
    ).traverse_ { case (actor, code) =>
      for
        f      <- fixture(answers = defaultAnswers(cancel = WebhookManagementResult.AuthorityChanged))
        result <- f.management.cancelSetup(
          Bot,
          actor,
          revision(RevisionId),
          setup(SetupId),
          "request-cancel"
        )
      yield assertFailure(result, Status.Forbidden, code)
    }

  test("stats are bot-history scoped while registrationId names only the current generation"):
    val history = WebhookStats(
      DeliveryStatsWindow(
        totalDeliveries = 5,
        outcomes = List(OutcomeCount("applied", 4), OutcomeCount("timed_out", 1)),
        p50Ms = Some(100),
        p90Ms = Some(500),
        p99Ms = Some(500)
      ),
      DeliveryStatsWindow(
        totalDeliveries = 9,
        outcomes = List(OutcomeCount("applied", 7), OutcomeCount("timed_out", 2)),
        p50Ms = Some(100),
        p90Ms = Some(1000),
        p99Ms = Some(1000)
      ),
      Some(LastFailure(VerificationAt.plusSeconds(20), "the endpoint timed out"))
    )

    for
      active         <- fixture(statsAnswer = history)
      activeResult   <- active.management.stats(Bot, Owner)
      activeRequests <- active.stats.requests.get
      inactive       <- fixture(
        answers = defaultAnswers(slot = WebhookManagementResult.Applied(CurrentSlot.copy(registration = None))),
        statsAnswer = history
      )
      inactiveResult <- inactive.management.stats(Bot, Owner)
    yield
      val activeStats = activeResult.getOrElse(fail("stats unexpectedly failed"))
      assertEquals(activeStats.scope, "bot_history")
      assertEquals(activeStats.registrationId, Some(registration(RegistrationId)))
      assertEquals(activeStats.last24h.totalDeliveries, 5L)
      assertEquals(activeStats.last24h.outcomes.map(_.outcome), List("applied", "timed_out"))
      assertEquals(activeStats.last7d.totalDeliveries, 9L)
      assertEquals(activeStats.lastFailure.map(_.reason), Some("safe persisted failure"))
      assertEquals(activeRequests, List(Bot.team -> Bot.name))

      val inactiveStats = inactiveResult.getOrElse(fail("stats unexpectedly failed"))
      assertEquals(inactiveStats.scope, "bot_history")
      assertEquals(inactiveStats.registrationId, None)
      assertEquals(inactiveStats.last7d.totalDeliveries, 9L)
      assertEquals(inactiveStats.lastFailure, None, "historical failures must not be attributed to an empty slot")

  private def responseWithProof(
      rawBody: Array[Byte],
      proof: String
  ): Either[WebhookVerificationFailure, Array[Byte]] =
    parse(new String(rawBody, UTF_8))
      .leftMap(_ => WebhookVerificationFailure.MalformedResponse)
      .flatMap(_.hcursor.get[String]("nonce").leftMap(_ => WebhookVerificationFailure.MalformedResponse))
      .map(nonce => s"{\"nonce\":${nonce.asJson.noSpaces},\"proof\":\"$proof\"}".getBytes(UTF_8))
