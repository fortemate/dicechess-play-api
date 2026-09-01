package dicechess.play.server

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import dicechess.engine.search.BotRegistry
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.{BotConnection, GameRoom}
import dicechess.play.store.{BotWebhook, DeliveryOutcome, GameStore, WebhookStats, WebhookStatsStore, WebhookStore}
import fs2.Stream
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, HttpRoutes, Response, Status, Uri}
import org.typelevel.ci.CIString

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** The webhook service end-to-end (#104): the ownership handshake, and full games where the "bot" is an HTTP endpoint
  * that verifies every delivery's HMAC and answers with moves walked from the envelope's own `legalMoves`.
  *
  * The endpoint is an `HttpApp` served to the dispatcher through `Client.fromHttpApp` — real http4s request/response
  * semantics (headers, bodies, status codes) with zero sockets, so the games are deterministic and port-collision free.
  * One handshake test runs over a REAL ember server + ember client to smoke the actual network stack; the production
  * URL policy itself is covered by `WebhookSecuritySuite`, so these tests inject a parse-only `checkUrl`.
  */
class WebhooksSuite extends munit.CatsEffectSuite:

  override def munitIOTimeout: Duration = 3.minutes

  /** Parse-only URL check: the tests' endpoints live on fake hosts (`Client.fromHttpApp` never resolves anything). */
  private val allowAll: String => IO[Either[String, Uri]] =
    url => IO.pure(Uri.fromString(url).left.map(_ => "not a valid URL"))

  private val config = Webhooks.Config(timeout = 5.seconds, scanEvery = 50.millis)

  private def service(
      registry: GameRegistry,
      store: WebhookStore,
      client: Client[IO],
      checkUrl: String => IO[Either[String, Uri]] = allowAll
  ): cats.effect.Resource[IO, Webhooks] = Webhooks.create(registry, store, client, config, checkUrl)

  private val seed = "0123456789abcdef" // the 16-char minimum a seat must contribute

  /** Root-to-leaf walk of the legal-move tree: any such path is a complete legal turn (max-micro-moves rule). */
  private def firstPath(tree: MoveTree): List[String] =
    tree.children.toList.minByOption(_._1) match
      case None                => Nil
      case Some((move, child)) => move :: firstPath(child)

  /** A deterministic store-side pause immediately before the registration fence. Reaching `fenceReached` proves the old
    * endpoint response has already arrived and decoded; the test then changes the registration before allowing the
    * current-generation check to continue. A second gate pauses the stale retry before its fresh `get`, making the
    * absence of any old-generation room mutation directly observable rather than timing-dependent.
    */
  final private class ControlledWebhookStore(
      current: Ref[IO, Option[BotWebhook]],
      staleSeen: Ref[IO, Boolean],
      val reads: Ref[IO, List[Option[UUID]]],
      val acceptedEnqueues: Ref[IO, List[UUID]],
      val fenceReached: Deferred[IO, Unit],
      val releaseFence: Deferred[IO, Unit],
      val rereadReached: Deferred[IO, Unit],
      val releaseReread: Deferred[IO, Unit],
      val rereadReturned: Deferred[IO, Unit]
  ) extends WebhookStore:
    def put(webhook: BotWebhook): IO[Unit] = current.set(Some(webhook))

    def get(team: String, name: String): IO[Option[BotWebhook]] =
      staleSeen.get.flatMap { afterStale =>
        val pause =
          (rereadReached.complete(()).attempt.void *> releaseReread.get).whenA(afterStale)
        pause *> current.get.flatTap { hook =>
          reads.update(_ :+ hook.map(_.registrationId)) *>
            rereadReturned.complete(()).attempt.void.whenA(afterStale)
        }
      }

    def delete(team: String, name: String): IO[Boolean] =
      current.modify(existing => (None, existing.nonEmpty))

    def enqueueIfCurrent[A](team: String, name: String, registrationId: UUID)(enqueue: IO[A]): IO[Option[A]] =
      fenceReached.complete(()).attempt.void *>
        releaseFence.get *>
        current.get.flatMap:
          case Some(hook) if hook.registrationId == registrationId =>
            acceptedEnqueues.update(_ :+ registrationId) *> enqueue.map(Some(_))
          case _ =>
            staleSeen.set(true).as(None)

  private object ControlledWebhookStore:
    def create(initial: BotWebhook): IO[ControlledWebhookStore] =
      for
        current          <- Ref.of[IO, Option[BotWebhook]](Some(initial))
        staleSeen        <- Ref.of[IO, Boolean](false)
        reads            <- Ref.of[IO, List[Option[UUID]]](Nil)
        acceptedEnqueues <- Ref.of[IO, List[UUID]](Nil)
        fenceReached     <- Deferred[IO, Unit]
        releaseFence     <- Deferred[IO, Unit]
        rereadReached    <- Deferred[IO, Unit]
        releaseReread    <- Deferred[IO, Unit]
        rereadReturned   <- Deferred[IO, Unit]
      yield ControlledWebhookStore(
        current,
        staleSeen,
        reads,
        acceptedEnqueues,
        fenceReached,
        releaseFence,
        rereadReached,
        releaseReread,
        rereadReturned
      )

  final private class FenceStatsStore(
      val calls: Ref[IO, List[(UUID, DeliveryOutcome)]],
      val staleRecorded: Deferred[IO, Unit],
      val appliedRecorded: Deferred[IO, Unit]
  ) extends WebhookStatsStore:
    def recordDelivery(
        team: String,
        name: String,
        outcome: DeliveryOutcome,
        elapsed: FiniteDuration,
        at: Instant
    ): IO[Unit] = IO.unit

    override def recordDeliveryFor(
        team: String,
        name: String,
        registrationId: UUID,
        outcome: DeliveryOutcome,
        elapsed: FiniteDuration,
        at: Instant
    ): IO[Unit] =
      calls.update(_ :+ (registrationId, outcome)) *>
        staleRecorded.complete(()).attempt.void.whenA(outcome == DeliveryOutcome.StaleRegistration) *>
        appliedRecorded.complete(()).attempt.void.whenA(outcome == DeliveryOutcome.Applied)

    def statsFor(team: String, name: String, now: Instant): IO[WebhookStats] = IO.pure(WebhookStats.empty)

  private object FenceStatsStore:
    def create: IO[FenceStatsStore] =
      for
        calls           <- Ref.of[IO, List[(UUID, DeliveryOutcome)]](Nil)
        staleRecorded   <- Deferred[IO, Unit]
        appliedRecorded <- Deferred[IO, Unit]
      yield FenceStatsStore(calls, staleRecorded, appliedRecorded)

  /** The scripted bot endpoint: echoes verification nonces, and answers `yourTurn` envelopes with the first legal path
    * — after verifying the delivery's signature against the registered secrets (a bad MAC is a 401 and counted). When
    * the envelope's inline tree is elided (over the cap), it falls back to the room's full tree — the in-process
    * stand-in for the documented `GET /games/{id}/moves` fetch.
    */
  private def botEndpoint(
      secrets: Ref[IO, List[String]],
      registry: GameRegistry,
      delivered: Ref[IO, Int],
      badSignatures: Ref[IO, Int]
  ): HttpApp[IO] =
    HttpApp[IO] { req =>
      req.bodyText.compile.string.flatMap { body =>
        decode[WebhookVerification](body) match
          case Right(v) if v.`type` == "verification" =>
            // The registrant cannot know the secret yet (it is disclosed only after this handshake succeeds),
            // so the echo is unauthenticated by design.
            Ok(Json.obj("nonce" -> v.nonce.asJson))
          case _ =>
            val ts  = req.headers.get(CIString(WebhookSecurity.TimestampHeader)).map(_.head.value).getOrElse("")
            val sig = req.headers.get(CIString(WebhookSecurity.SignatureHeader)).map(_.head.value).getOrElse("")
            secrets.get.flatMap { keys =>
              val signedOk = ts.toLongOption.exists(t => keys.exists(k => WebhookSecurity.sign(k, t, body) == sig))
              if !signedOk then badSignatures.update(_ + 1).as(Response[IO](Status.Unauthorized))
              else
                decode[WebhookEnvelope](body) match
                  case Left(_)         => IO.pure(Response[IO](Status.BadRequest))
                  case Right(envelope) =>
                    val moves = envelope.state.legalMoves.filter(_.children.nonEmpty) match
                      case Some(tree) => IO.pure(firstPath(tree))
                      case None       =>
                        registry
                          .get(GameId(envelope.gameId))
                          .flatMap(_.fold(IO.pure(MoveTree.empty))(_.legalMoves.map(_.legalMoves)))
                          .map(firstPath)
                    delivered.update(_ + 1) *> moves.flatMap(m => Ok(BotMove(m).asJson))
            }
      }
    }

  private val OldRegistrationId     = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val CurrentRegistrationId = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val OldSecret             = "old-secret-" + ("a" * 53)
  private val CurrentSecret         = "current-secret-" + ("b" * 49)

  final private case class RegistrationMutation(
      label: String,
      current: Option[BotWebhook]
  )

  /** Answers a legal move and records which generation's secret authenticated each request. The registration is placed
    * directly in the store, so this endpoint never receives the legacy ownership handshake.
    */
  private def fencedEndpoint(
      registry: GameRegistry,
      generations: Ref[IO, List[UUID]]
  ): HttpApp[IO] =
    HttpApp[IO] { req =>
      req.bodyText.compile.string.flatMap { body =>
        val timestamp  = req.headers.get(CIString(WebhookSecurity.TimestampHeader)).flatMap(_.head.value.toLongOption)
        val signature  = req.headers.get(CIString(WebhookSecurity.SignatureHeader)).map(_.head.value)
        val generation = timestamp.flatMap { ts =>
          signature.flatMap { actual =>
            List(OldRegistrationId -> OldSecret, CurrentRegistrationId -> CurrentSecret)
              .find((_, secret) => WebhookSecurity.sign(secret, ts, body) == actual)
              .map(_._1)
          }
        }

        (generation, decode[WebhookEnvelope](body)) match
          case (Some(registrationId), Right(envelope)) =>
            val moves = envelope.state.legalMoves.filter(_.children.nonEmpty) match
              case Some(tree) => IO.pure(firstPath(tree))
              case None       =>
                registry
                  .get(GameId(envelope.gameId))
                  .flatMap(_.fold(IO.pure(MoveTree.empty))(_.legalMoves.map(_.legalMoves)))
                  .map(firstPath)
            generations.update(_ :+ registrationId) *> moves.flatMap(path => Ok(BotMove(path).asJson))
          case _ =>
            IO.pure(Response[IO](Status.Unauthorized))
      }
    }

  private def awaitWhiteTurn(room: GameRoom): IO[PublicGameState] =
    def loop: IO[PublicGameState] =
      room.snapshot.flatMap { state =>
        val ready = state.status == GameStatus.Active && state.activeSeat == Seat.White && state.dicePending &&
          state.legalMoves.exists(_.children.nonEmpty)
        if ready then IO.pure(state) else IO.cede *> loop
      }
    loop.timeoutTo(5.seconds, IO.raiseError(new RuntimeException("deterministic White turn never became ready")))

  private def verifyRegistrationFence(mutation: RegistrationMutation): IO[Unit] =
    val webhookBot: Principal.Bot = Principal.Bot("hooks", s"fence-${mutation.label}")
    val opponent: Principal.Bot   = Principal.Bot("acme", s"opponent-${mutation.label}")
    val oldHook                   = BotWebhook(
      webhookBot.team,
      webhookBot.name,
      "https://old.example/hook",
      OldSecret,
      Instant.EPOCH,
      registrationId = OldRegistrationId
    )
    val movableDice = new DiceSource:
      def roll(ply: Long, clientSeedW: String, clientSeedB: String): List[Int] = List(1, 2, 3)
      def commit: String                                                       = "fence-commit"
      def reveal: String                                                       = "fence-seed"

    for
      registry    <- GameRegistry.create(store = GameStore.noop)
      store       <- ControlledWebhookStore.create(oldHook)
      stats       <- FenceStatsStore.create
      generations <- Ref.of[IO, List[UUID]](Nil)
      made        <- registry.createWithDice(webhookBot, opponent, movableDice)
      (_, room) = made.toOption.get
      _       <- room.submit(Seat.White, GameCommand.SubmitSeed(seed))
      _       <- room.submit(Seat.Black, GameCommand.SubmitSeed(seed))
      initial <- awaitWhiteTurn(room)
      _       <- Webhooks
        .create(
          registry,
          store,
          Client.fromHttpApp(fencedEndpoint(registry, generations)),
          config,
          allowAll,
          stats
        )
        .use { webhooks =>
          webhooks.statsLoop.background.use { _ =>
            for
              _ <- webhooks.attachSweep
              _ <- store.fenceReached.get.timeoutTo(
                5.seconds,
                IO.raiseError(new RuntimeException(s"${mutation.label}: old response never reached the fence"))
              )
              _ <- mutation.current.fold(store.delete(webhookBot.team, webhookBot.name).void)(store.put)
              _ <- store.releaseFence.complete(())
              _ <- store.rereadReached.get.timeoutTo(
                5.seconds,
                IO.raiseError(new RuntimeException(s"${mutation.label}: stale delivery did not request a fresh read"))
              )
              _ <- stats.staleRecorded.get.timeoutTo(
                5.seconds,
                IO.raiseError(new RuntimeException(s"${mutation.label}: stale_registration telemetry was not recorded"))
              )
              fencedState    <- room.snapshot
              fencedEnqueues <- store.acceptedEnqueues.get
              _              <- IO {
                assertEquals(
                  fencedState.version,
                  initial.version,
                  s"${mutation.label}: the old response changed the room before the current registration was read"
                )
                assert(fencedState.dicePending, s"${mutation.label}: the old response consumed the pending roll")
                assertEquals(fencedEnqueues, Nil, s"${mutation.label}: old-generation work reached the room queue")
              }
              _ <- store.releaseReread.complete(())
              _ <- mutation.current match
                case Some(_) =>
                  stats.appliedRecorded.get.timeoutTo(
                    5.seconds,
                    IO.raiseError(
                      new RuntimeException(s"${mutation.label}: current registration did not apply its move")
                    )
                  )
                case None =>
                  store.rereadReturned.get.timeoutTo(
                    5.seconds,
                    IO.raiseError(
                      new RuntimeException(s"${mutation.label}: delete retry did not observe no registration")
                    )
                  )
              finalState      <- room.snapshot
              reads           <- store.reads.get
              enqueues        <- store.acceptedEnqueues.get
              deliveryCalls   <- stats.calls.get
              usedGenerations <- generations.get
              _               <- IO {
                assert(
                  reads.size >= 3,
                  s"${mutation.label}: expected attach, delivery, and stale retry reads; got $reads"
                )
                assertEquals(reads.last, mutation.current.map(_.registrationId))
                assert(
                  deliveryCalls.contains(OldRegistrationId -> DeliveryOutcome.StaleRegistration),
                  s"${mutation.label}: missing old-generation stale outcome: $deliveryCalls"
                )
                mutation.current match
                  case Some(_) =>
                    assertEquals(enqueues, List(CurrentRegistrationId))
                    assertEquals(usedGenerations, List(OldRegistrationId, CurrentRegistrationId))
                    assert(
                      deliveryCalls.contains(CurrentRegistrationId -> DeliveryOutcome.Applied),
                      s"${mutation.label}: current generation was not recorded as applied: $deliveryCalls"
                    )
                    assert(
                      finalState.version > initial.version,
                      s"${mutation.label}: current generation did not change the room"
                    )
                  case None =>
                    assertEquals(enqueues, Nil)
                    assertEquals(usedGenerations, List(OldRegistrationId))
                    assertEquals(finalState.version, initial.version)
              }
              _ <- room.submit(Seat.White, GameCommand.Resign)
              _ <- room.result.timeoutTo(
                5.seconds,
                IO.raiseError(new RuntimeException(s"${mutation.label}: room did not stop after cleanup"))
              )
            yield ()
          }
        }
    yield ()

  // ── ownership handshake ──────────────────────────────────────────────────────

  test("register stores the webhook only after the endpoint echoes the nonce"):
    for
      registry  <- GameRegistry.create(store = GameStore.noop)
      store     <- WebhookStore.inMemory
      secrets   <- Ref.of[IO, List[String]](Nil)
      delivered <- Ref.of[IO, Int](0)
      badSig    <- Ref.of[IO, Int](0)
      bot: Principal.Bot = Principal.Bot("hooks", "alpha")
      result <- service(registry, store, Client.fromHttpApp(botEndpoint(secrets, registry, delivered, badSig)))
        .use(_.register(bot, "https://bot.example/hook"))
      stored <- store.get("hooks", "alpha")
    yield
      val hook = result.getOrElse(fail(s"registration must succeed, got $result"))
      assertEquals(hook.url, "https://bot.example/hook")
      assert(hook.secret.matches("[0-9a-f]{64}"), "the signing secret is 32 random bytes, hex")
      assertEquals(stored.map(_.secret), Some(hook.secret))

  test("an endpoint that echoes the wrong nonce (or none) is rejected and nothing is stored"):
    val wrongNonce = HttpApp[IO](_ => Ok(Json.obj("nonce" -> "not-what-was-sent".asJson)))
    val noJson     = HttpApp[IO](_ => Ok("pong"))
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      bot: Principal.Bot = Principal.Bot("hooks", "alpha")
      first  <- service(registry, store, Client.fromHttpApp(wrongNonce)).use(_.register(bot, "https://x.example"))
      second <- service(registry, store, Client.fromHttpApp(noJson)).use(_.register(bot, "https://x.example"))
      stored <- store.get("hooks", "alpha")
    yield
      assert(first.left.exists(_.contains("nonce")), s"wrong echo must fail with the reason, got $first")
      assert(second.isLeft, s"a non-JSON echo must fail, got $second")
      assertEquals(stored, None)

  test("a dead endpoint fails registration with a reason, not an exception"):
    val dead = Client[IO](_ => cats.effect.Resource.eval(IO.raiseError(new java.net.ConnectException("refused"))))
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      result   <- service(registry, store, dead).use(
        _.register(Principal.Bot("hooks", "alpha"), "https://gone.example/hook")
      )
      stored <- store.get("hooks", "alpha")
    yield
      assert(result.isLeft)
      assertEquals(stored, None)

  test("the registration deadline includes URL policy and DNS resolution"):
    val unused = Client[IO](_ => Resource.eval(IO.raiseError(RuntimeException("client must not run"))))
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      result   <- Webhooks
        .create(
          registry,
          store,
          unused,
          Webhooks.Config(timeout = 30.millis),
          checkUrl = _ => IO.never
        )
        .use(_.register(Principal.Bot("hooks", "dns-timeout"), "https://never.example/hook"))
      stored <- store.get("hooks", "dns-timeout")
    yield
      assert(result.left.exists(_.contains("could not reach")), s"expected a bounded timeout result, got $result")
      assertEquals(stored, None)

  test("an oversized legacy response releases the client with an error instead of draining it"):
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      errored  <- Ref.of[IO, Boolean](false)
      client = Client[IO] { _ =>
        Resource.makeCase(
          IO.pure(Response[IO](Status.Ok, body = Stream.constant(0.toByte).covary[IO]))
        ) { (_, exitCase) =>
          errored.set(exitCase match
            case Resource.ExitCase.Errored(_) => true
            case _                            => false)
        }
      }
      result <- service(registry, store, client)
        .use(_.register(Principal.Bot("hooks", "oversized"), "https://oversized.example/hook"))
      errorExit <- errored.get
      stored    <- store.get("hooks", "oversized")
    yield
      assert(result.left.exists(_.contains("oversized")), s"expected an oversized response, got $result")
      assert(errorExit, "the response resource must see an error exit and skip its success-path drain")
      assertEquals(stored, None)

  test("registration enforces the real URL policy when constructed with it — a private target never gets a POST"):
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      calls    <- Ref.of[IO, Int](0)
      counting = Client.fromHttpApp(HttpApp[IO](_ => calls.update(_ + 1) *> Ok("")))
      // production checkUrl by default
      result <- Webhooks
        .create(registry, store, counting, config)
        .use(_.register(Principal.Bot("hooks", "alpha"), "https://192.168.10.3/hook"))
      posted <- calls.get
    yield
      assertEquals(result, Left("host resolves to a non-public address"))
      assertEquals(posted, 0, "the guard must reject BEFORE any request is made")

  test("the handshake round-trips over a real socket server and client (network-stack smoke)"):
    val endpoint = HttpRoutes
      .of[IO] { case req @ POST -> Root / "hook" =>
        req.bodyText.compile.string.flatMap { body =>
          decode[WebhookVerification](body) match
            case Right(v) => Ok(Json.obj("nonce" -> v.nonce.asJson))
            case Left(_)  => BadRequest()
        }
      }
      .orNotFound
    val resources = for
      server <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withShutdownTimeout(1.second)
        .withHttpApp(endpoint)
        .build
      client <- EmberClientBuilder.default[IO].build
    yield (server, client)
    resources.use { (server, client) =>
      for
        registry <- GameRegistry.create(store = GameStore.noop)
        store    <- WebhookStore.inMemory
        url = s"http://127.0.0.1:${server.address.getPort}/hook"
        result <- service(registry, store, client).use(_.register(Principal.Bot("hooks", "alpha"), url))
      yield assert(result.isRight, s"real-socket handshake must succeed, got $result")
    }

  test("late old-registration responses are fenced across replace, rotate, and delete; current retries still apply"):
    val mutations = List(
      RegistrationMutation(
        "replace",
        Some(
          BotWebhook(
            "hooks",
            "fence-replace",
            "https://current.example/hook",
            CurrentSecret,
            Instant.EPOCH,
            registrationId = CurrentRegistrationId
          )
        )
      ),
      RegistrationMutation(
        "rotate",
        Some(
          BotWebhook(
            "hooks",
            "fence-rotate",
            "https://old.example/hook",
            CurrentSecret,
            Instant.EPOCH,
            registrationId = CurrentRegistrationId
          )
        )
      ),
      RegistrationMutation("delete", None)
    )

    mutations.traverse_(verifyRegistrationFence)

  // ── delivery: full games ─────────────────────────────────────────────────────

  test("two webhook-driven seats play a full game to a natural end, every delivery HMAC-verified"):
    for
      registry  <- GameRegistry.create(store = GameStore.noop)
      store     <- WebhookStore.inMemory
      secrets   <- Ref.of[IO, List[String]](Nil)
      delivered <- Ref.of[IO, Int](0)
      badSig    <- Ref.of[IO, Int](0)
      alpha: Principal.Bot = Principal.Bot("hooks", "alpha")
      beta: Principal.Bot  = Principal.Bot("hooks", "beta")
      // The service stays open for the whole game: its runners are supervised by the Resource now.
      over <- service(registry, store, Client.fromHttpApp(botEndpoint(secrets, registry, delivered, badSig)))
        .use: webhooks =>
          for
            hookA <- webhooks.register(alpha, "https://bots.example/hook").map(_.toOption.get)
            hookB <- webhooks.register(beta, "https://bots.example/hook").map(_.toOption.get)
            _     <- secrets.set(List(hookA.secret, hookB.secret))
            made  <- registry.create(alpha, beta, TimeControl.Unlimited)
            (_, room) = made.toOption.get
            _    <- room.submit(Seat.White, GameCommand.SubmitSeed(seed))
            _    <- room.submit(Seat.Black, GameCommand.SubmitSeed(seed))
            _    <- webhooks.attachSweep // both seats get runners; the game then drives itself
            over <- room.result
              .timeoutTo(150.seconds, IO.raiseError(new RuntimeException("webhook game never ended")))
          yield over
      turns    <- delivered.get
      rejected <- badSig.get
    yield
      assert(turns >= 2, s"both seats must have been served turns over the webhook, got $turns")
      assertEquals(rejected, 0, "every delivery must carry a valid signature")
      assert(
        over.termination == Termination.KingCaptured || over.termination == Termination.Draw,
        s"a webhook-vs-webhook game must end on the board, got $over"
      )

  test("a dead endpoint forfeits on the clock without hanging the room"):
    val dead = Client[IO](_ => cats.effect.Resource.eval(IO.raiseError(new java.net.ConnectException("refused"))))
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      silent = Principal.Bot("hooks", "silent")
      // Registered directly at the store seam: `register` would (rightly) refuse a dead endpoint's handshake,
      // and this test is about a webhook that DIED AFTER registration.
      _ <- store.put(BotWebhook("hooks", "silent", "https://gone.example/hook", "s" * 64, Instant.EPOCH))
      opponent = Principal.Bot("acme", "greedy")
      made <- registry.create(silent, opponent, TimeControl.SuddenDeath(2))
      (_, room) = made.toOption.get
      _ <- room.submit(Seat.White, GameCommand.SubmitSeed(seed))
      _ <- room.submit(Seat.Black, GameCommand.SubmitSeed(seed))
      driver = BotConnection(opponent, Seat.Black, BotRegistry.getAlgorithm("greedy").get)
      over <- service(registry, store, dead).use: webhooks =>
        driver
          .run(room)
          .background
          .use: _ =>
            webhooks.attachSweep *>
              room.result.timeoutTo(
                20.seconds,
                IO.raiseError(new RuntimeException("the room hung instead of flagging the dead webhook"))
              )
    yield
      assertEquals(over.termination, Termination.Timeout)
      assertEquals(over.result, GameResult.Win(Side.Black), "the webhook seat (White) must lose on time")

  test("garbage and non-200 responses submit nothing — the game stays untouched for the clock to decide"):
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      // The endpoint signals when it has answered, so the assertion waits for the delivery to have actually
      // happened instead of sleeping and racing it (review).
      answered <- cats.effect.Deferred[IO, Unit]
      garbage  = Client.fromHttpApp(HttpApp[IO](_ => answered.complete(()).attempt *> Ok("this is not a move")))
      noisy    = Principal.Bot("hooks", "noisy")
      opponent = Principal.Bot("acme", "driven")
      _    <- store.put(BotWebhook("hooks", "noisy", "https://noise.example/hook", "s" * 64, Instant.EPOCH))
      made <- registry.create(noisy, opponent, TimeControl.Unlimited)
      (_, room) = made.toOption.get
      // Black must be played, not idle (#176). This test needs ONE actionable turn for the webhook seat (White), but
      // White's opening roll has no legal move whenever it contains neither a pawn nor a knight — from the start
      // position nothing else can move — and the room then auto-passes to Black. With an idle Black and no clock
      // (Unlimited) the game deadlocks there forever and the delivery never happens: `(4/6)^3 ≈ 30%` of runs, which
      // is the flake #140 and this issue both mistook for fiber starvation. Driving Black keeps play moving until
      // White does get an actionable roll. Same `BotConnection` pattern as the dead-webhook test above.
      driver = BotConnection(opponent, Seat.Black, BotRegistry.getAlgorithm("greedy").get)
      state <- service(registry, store, garbage).use: webhooks =>
        driver
          .run(room)
          .background
          .use: _ =>
            for
              _ <- room.submit(Seat.White, GameCommand.SubmitSeed(seed))
              _ <- room.submit(Seat.Black, GameCommand.SubmitSeed(seed))
              _ <- webhooks.attachSweep
              // Bounded per house convention, and back to 30s from #140's 150s. The width was never the fix: a
              // deadlocked game never delivers at any ceiling, which is why both recorded failures landed exactly ON
              // the bound (30.008s here, 150.022s in CI) rather than somewhere under it. With Black driven the
              // delivery arrives in ~2s, so 30s is ~15x headroom.
              //
              // If this ever fails again, read the message before touching the number: it reports whose turn the room
              // is actually on. `activeSeat=Black` means the deadlock above is back (something stopped driving Black);
              // `activeSeat=White` with the bound crossed would be genuine starvation, and THAT is when contention is
              // worth investigating — not before.
              _ <- answered.get.timeoutTo(
                30.seconds,
                room.snapshot.flatMap: s =>
                  IO.raiseError(
                    new RuntimeException(
                      s"delivery never reached the endpoint (activeSeat=${s.activeSeat}, dicePending=${s.dicePending})"
                    )
                  )
              )
              state <- room.snapshot
              // Clean up, and WAIT for it: `submit` only offers to the room's inbox (`GameRoom.submit` is
              // `inbox.offer`, unlike `submitTurn` which awaits a verdict), so returning here without awaiting the
              // terminal state would let both scopes close while the room is still Active — leaving its detached
              // writer fiber and idle-deadline timer running for the rest of the JVM. In a suite whose whole problem
              // is background work competing for CPU, leaking one live room per run is the last thing we want.
              _ <- room.submit(Seat.White, GameCommand.Resign)
              _ <- room.result.timeoutTo(
                10.seconds,
                IO.raiseError(new RuntimeException("the room never reached a terminal state after Resign"))
              )
            yield state
    yield
      assertEquals(state.status, GameStatus.Active)
      assert(state.dicePending, "an unparseable answer must leave the pending roll unanswered")

  test("the client deadlines derived from a window sit above it, idle furthest out"):
    val config = Webhooks.Config(timeout = 120.seconds)
    assert(config.clientTimeout > config.timeout, "a client cut at or below the window would pre-empt post's timeout")
    assert(config.clientIdleTimeout > config.clientTimeout, "the connection is idle for as long as the bot thinks")

  // ── delivery telemetry (#225) ────────────────────────────────────────────────

  /** A `WebhookStatsStore` that captures every `recordDelivery` call instead of persisting anything — the seam these
    * tests use to prove `deliverTurn` classifies and enqueues correctly, without a database.
    */
  private def capturingStats
      : IO[(Ref[IO, List[(String, String, DeliveryOutcome, FiniteDuration)]], WebhookStatsStore)] =
    Ref.of[IO, List[(String, String, DeliveryOutcome, FiniteDuration)]](Nil).map { calls =>
      val store = new WebhookStatsStore:
        def recordDelivery(
            team: String,
            name: String,
            outcome: DeliveryOutcome,
            elapsed: FiniteDuration,
            at: Instant
        ): IO[Unit] = calls.update(_ :+ (team, name, outcome, elapsed))
        def statsFor(team: String, name: String, now: Instant): IO[WebhookStats] = IO.pure(WebhookStats.empty)
      (calls, store)
    }

  test("a dead endpoint's delivery attempt is recorded as Unreachable telemetry, not silently lost"):
    val dead = Client[IO](_ => cats.effect.Resource.eval(IO.raiseError(new java.net.ConnectException("refused"))))
    for
      registry            <- GameRegistry.create(store = GameStore.noop)
      store               <- WebhookStore.inMemory
      (calls, statsStore) <- capturingStats
      silent = Principal.Bot("hooks", "silent-stats")
      _ <- store.put(BotWebhook("hooks", "silent-stats", "https://gone.example/hook", "s" * 64, Instant.EPOCH))
      opponent = Principal.Bot("acme", "greedy-stats")
      made <- registry.create(silent, opponent, TimeControl.Unlimited)
      (_, room) = made.toOption.get
      _ <- room.submit(Seat.White, GameCommand.SubmitSeed(seed))
      _ <- room.submit(Seat.Black, GameCommand.SubmitSeed(seed))
      // Black is driven, White is not (#176's gotcha): the opening roll misses a pawn/knight ~30% of the time, and
      // with an idle Black and no clock (Unlimited) that would deadlock the room before White ever sees a turn.
      driver = BotConnection(opponent, Seat.Black, BotRegistry.getAlgorithm("greedy").get)
      recorded <- Webhooks.create(registry, store, dead, config, allowAll, statsStore).use { webhooks =>
        (driver.run(room).background, webhooks.statsLoop.background).tupled.use { _ =>
          webhooks.attachSweep *>
            calls.get
              .iterateUntil(_.nonEmpty)
              .timeoutTo(15.seconds, IO.raiseError(new RuntimeException("delivery telemetry never arrived")))
        }
      }
    yield
      assert(recorded.forall { case (t, n, _, _) => t == "hooks" && n == "silent-stats" })
      assert(
        recorded.exists { case (_, _, outcome, _) => outcome == DeliveryOutcome.Unreachable },
        s"expected an Unreachable record, got: $recorded"
      )

  /** Drives the game and the stats drain loop side by side, then polls the captured calls for a record matching `done`
    * — shared by every telemetry test below so each stays focused on its own scenario setup.
    */
  private def awaitDelivery(
      driver: BotConnection,
      room: dicechess.play.game.GameRoom,
      webhooks: Webhooks,
      calls: Ref[IO, List[(String, String, DeliveryOutcome, FiniteDuration)]],
      done: List[(String, String, DeliveryOutcome, FiniteDuration)] => Boolean
  ): IO[List[(String, String, DeliveryOutcome, FiniteDuration)]] =
    (driver.run(room).background, webhooks.statsLoop.background).tupled.use { _ =>
      webhooks.attachSweep *>
        calls.get
          .iterateUntil(done)
          .timeoutTo(15.seconds, IO.raiseError(new RuntimeException(s"expected telemetry never arrived: $calls")))
    }

  test("a successful delivery is recorded as Applied telemetry"):
    for
      registry         <- GameRegistry.create(store = GameStore.noop)
      store            <- WebhookStore.inMemory
      secrets          <- Ref.of[IO, List[String]](Nil)
      delivered        <- Ref.of[IO, Int](0)
      badSig           <- Ref.of[IO, Int](0)
      calls_statsStore <- capturingStats
      (calls, statsStore)       = calls_statsStore
      webhookBot: Principal.Bot = Principal.Bot("hooks", "applied-stats")
      opponent: Principal.Bot   = Principal.Bot("acme", "driven-stats")
      resources                 = Webhooks.create(
        registry,
        store,
        Client.fromHttpApp(botEndpoint(secrets, registry, delivered, badSig)),
        config,
        allowAll,
        statsStore
      )
      recorded <- resources.use { webhooks =>
        for
          hook <- webhooks.register(webhookBot, "https://bots.example/hook").map(_.toOption.get)
          _    <- secrets.set(List(hook.secret))
          made <- registry.create(webhookBot, opponent, TimeControl.Unlimited)
          room = made.toOption.get._2
          _ <- room.submit(Seat.White, GameCommand.SubmitSeed(seed))
          _ <- room.submit(Seat.Black, GameCommand.SubmitSeed(seed))
          driver = BotConnection(opponent, Seat.Black, BotRegistry.getAlgorithm("greedy").get)
          recorded <- awaitDelivery(driver, room, webhooks, calls, _.exists(_._3 == DeliveryOutcome.Applied))
        yield recorded
      }
    yield assert(
      recorded.exists { case (t, n, outcome, _) =>
        t == "hooks" && n == "applied-stats" && outcome == DeliveryOutcome.Applied
      },
      s"expected an Applied record for hooks/applied-stats, got: $recorded"
    )

  test("webhook bot with 'draws' capability receives drawDecision and can accept draw"):
    def drawAcceptingBot: HttpApp[IO] =
      HttpApp[IO] { req =>
        req.bodyText.compile.string.flatMap { body =>
          decode[WebhookEnvelope](body) match
            case Right(envelope) if envelope.`type` == "drawDecision" =>
              Ok(BotMove(moves = Nil, acceptDraw = Some(true)).asJson)
            case Right(envelope) if envelope.`type` == "yourTurn" =>
              val moves = envelope.state.legalMoves.filter(_.children.nonEmpty) match
                case Some(tree) => firstPath(tree)
                case None       => Nil
              Ok(BotMove(moves).asJson)
            case _ => IO.pure(Response[IO](Status.BadRequest))
        }
      }

    def humanPlaysLoop(room: GameRoom): IO[Unit] =
      (room.snapshot, room.legalMoves).flatMapN { (snap, moves) =>
        if snap.status != GameStatus.Active then IO.unit
        else if snap.activeSeat == Seat.White && moves.dicePending && moves.legalMoves.children.nonEmpty then
          val path = firstPath(moves.legalMoves)
          room.submitTurn(Seat.White, path, offerDraw = true) *>
            IO.sleep(50.millis) *>
            humanPlaysLoop(room)
        else IO.sleep(25.millis) *> humanPlaysLoop(room)
      }

    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      webhookBot = Principal.Bot("hooks", "draw-accepter")
      _ <- store.put(
        BotWebhook(
          "hooks",
          "draw-accepter",
          "https://bot.example/hook",
          "secret" * 8,
          Instant.EPOCH,
          capabilities = List(WebhookCapability.Draws)
        )
      )
      human = Principal.Guest("human-123")
      made <- registry.create(human, webhookBot, TimeControl.Unlimited)
      (_, room) = made.toOption.get
      _ <- room.submit(Seat.White, GameCommand.SubmitSeed(seed))
      _ <- room.submit(Seat.Black, GameCommand.SubmitSeed(seed))
      resources = service(registry, store, Client.fromHttpApp(drawAcceptingBot))
      res <- resources.use { webhooks =>
        humanPlaysLoop(room).background.use { _ =>
          webhooks.attachSweep *>
            room.result.timeoutTo(10.seconds, IO.raiseError(new RuntimeException("game never ended in draw")))
        }
      }
    yield
      assertEquals(res.result, GameResult.Draw)
      assertEquals(res.termination, Termination.Draw)

  test("webhook bot without 'draws' capability has draw auto-declined and receives yourTurn with dice"):
    def regularBot(registry: GameRegistry, receivedEvents: Ref[IO, List[String]]): HttpApp[IO] =
      HttpApp[IO] { req =>
        req.bodyText.compile.string.flatMap { body =>
          decode[WebhookEnvelope](body) match
            case Right(envelope) =>
              val moves = envelope.state.legalMoves.filter(_.children.nonEmpty) match
                case Some(tree) => IO.pure(firstPath(tree))
                case None       =>
                  registry
                    .get(GameId(envelope.gameId))
                    .flatMap(_.fold(IO.pure(MoveTree.empty))(_.legalMoves.map(_.legalMoves)))
                    .map(firstPath)
              receivedEvents.update(envelope.`type` :: _) *> moves.flatMap(m => Ok(BotMove(m).asJson))
            case Left(_) => IO.pure(Response[IO](Status.BadRequest))
        }
      }

    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      webhookBot = Principal.Bot("hooks", "regular-bot")
      _ <- store.put(
        BotWebhook(
          "hooks",
          "regular-bot",
          "https://bot.example/hook",
          "secret" * 8,
          Instant.EPOCH,
          capabilities = Nil // No 'draws' capability -> auto-decline
        )
      )
      human       = Principal.Guest("human-456")
      movableDice = new DiceSource:
        def roll(ply: Long, clientSeedW: String, clientSeedB: String): List[Int] = List(1, 2, 3)
        def commit: String                                                       = "c0"
        def reveal: String                                                       = "seed"
      made <- registry.createWithDice(human, webhookBot, movableDice)
      (_, room) = made.toOption.get
      _         <- room.submit(Seat.White, GameCommand.SubmitSeed(seed))
      _         <- room.submit(Seat.Black, GameCommand.SubmitSeed(seed))
      eventsRef <- Ref.of[IO, List[String]](Nil)
      resources = service(registry, store, Client.fromHttpApp(regularBot(registry, eventsRef)))
      _ <- resources.use { webhooks =>
        for
          _     <- webhooks.attachSweep
          _     <- IO.sleep(50.millis)
          moves <- room.legalMoves
          path = firstPath(moves.legalMoves)
          _ <- room.submitTurn(Seat.White, path, offerDraw = true)
          _ <- eventsRef.get
            .map(_.contains("yourTurn"))
            .iterateUntil(identity)
            .timeoutTo(10.seconds, IO.raiseError(new RuntimeException("bot never received yourTurn")))
        yield ()
      }
      events <- eventsRef.get
    yield
      // Bot only received 'yourTurn' (never 'drawDecision'), and auto-decline allowed bot to play
      assert(events.contains("yourTurn"))
      assert(!events.contains("drawDecision"))

  test("webhook bot responds with offerDraw: true, triggering DrawOffered event on opponent turn"):
    def drawOfferingBot(registry: GameRegistry): HttpApp[IO] =
      HttpApp[IO] { req =>
        req.bodyText.compile.string.flatMap { body =>
          decode[WebhookEnvelope](body) match
            case Right(envelope) =>
              val moves = envelope.state.legalMoves.filter(_.children.nonEmpty) match
                case Some(tree) => IO.pure(firstPath(tree))
                case None       =>
                  registry
                    .get(GameId(envelope.gameId))
                    .flatMap(_.fold(IO.pure(MoveTree.empty))(_.legalMoves.map(_.legalMoves)))
                    .map(firstPath)
              moves.flatMap(m => Ok(BotMove(m, offerDraw = true).asJson))
            case Left(_) => IO.pure(Response[IO](Status.BadRequest))
        }
      }

    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      webhookBot = Principal.Bot("hooks", "draw-offerer")
      _ <- store.put(BotWebhook("hooks", "draw-offerer", "https://bot.example/hook", "secret" * 8, Instant.EPOCH))
      human = Principal.Guest("human-789")
      made <- registry.create(webhookBot, human, TimeControl.Unlimited)
      (_, room) = made.toOption.get
      _ <- room.submit(Seat.White, GameCommand.SubmitSeed(seed))
      _ <- room.submit(Seat.Black, GameCommand.SubmitSeed(seed))
      driver    = BotConnection(human, Seat.Black, BotRegistry.getAlgorithm("greedy").get)
      resources = service(registry, store, Client.fromHttpApp(drawOfferingBot(registry)))
      event <- resources.use { webhooks =>
        driver.run(room).background.use { _ =>
          val offered = room.subscribe.collectFirst { case e: GameEvent.DrawOffered => e }.compile.lastOrError
          webhooks.attachSweep *>
            offered.timeoutTo(10.seconds, IO.raiseError(new RuntimeException("DrawOffered event never emitted")))
        }
      }
    yield assertEquals(event.by, Seat.White)
