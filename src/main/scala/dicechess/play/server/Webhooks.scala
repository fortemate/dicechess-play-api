package dicechess.play.server

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.{Console, Queue, Supervisor}
import cats.syntax.all.*
import dicechess.play.core.{GameEvent, GameId, GameStatus, Principal, PublicGameState, Seat, WebhookCapability}
import dicechess.play.game.GameRoom
import dicechess.play.store.{BotWebhook, DeliveryOutcome, WebhookStatsStore, WebhookStore}
import dicechess.play.wire.Codecs.given
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.headers.`Content-Type`
import org.http4s.{Header, MediaType, Method, Request, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

/** The turn envelope POSTed to a bot's webhook: the existing wire vocabulary verbatim — `state` is the same
  * `PublicGameState` every snapshot and `GET /games/{id}` serve (dfen with the pending dice pool, clocks, inline
  * `legalMoves` under the same cap — `null` means fetch `GET /games/{id}/moves`), plus which seat the bot holds. The
  * expected response is the move-endpoint's own request shape: `{"moves":["e2e4",...]}`.
  */
final case class WebhookEnvelope(`type`: String, gameId: String, seat: Seat, state: PublicGameState)
    derives Codec.AsObject

/** The ownership handshake POSTed at registration: the endpoint must answer `200 {"nonce":"<same value>"}` before any
  * game data is ever sent to the URL.
  */
final case class WebhookVerification(`type`: String, nonce: String) derives Codec.AsObject

final private case class WebhookNonceEcho(nonce: String) derives Codec.AsObject

/** Synchronous webhook delivery (F.2, #104; design: ADR-0013): when it is a registered bot's turn, the server POSTs the
  * game state to the bot's verified callback URL and applies the HTTP response body as the move — one component
  * covering the ownership handshake (`register`) and the delivery loop (`loop`).
  *
  * '''Single-writer respected''': the per-game runner is an ordinary room subscriber that feeds `submitTurn` — a
  * command source exactly like a WebSocket player or a polling bot, never a second writer.
  *
  * '''Reliability is the clock''': every attempt is bounded by `min(config, the mover's remaining clock)`, and the
  * clock — never a queue — is the recovery budget. An answer the bot actually gave (garbage, a 4xx, an empty `moves`, a
  * refusal) is final: the runner does nothing and the room's own deadline forfeits the game exactly as it would for a
  * polling bot that stopped polling. A delivery that never reached the bot at all — a 5xx from something in front of
  * it, our own timeout, a connection that never completed — is retried in place on a backoff for as long as the seat's
  * clock allows (#119; see [[Webhooks.Config.retryBackoff]] for the incident that named the schedule). Still no
  * dead-letter and no dispatcher state: a retry lives entirely in the per-(game, seat) runner's own stack.
  *
  * '''Delivery rate is structurally bounded''': a bot receives at most one POST per turn of a game it is seated in,
  * games are bounded by the scheduler's pair cap and the challenge flow — there is no queue an attacker could pump. The
  * registration endpoint (the only caller-triggered outbound POST) carries its own per-IP limiter in the routes.
  *
  * The scan loop discovers rooms via `registry.list` (cheap: an in-memory map), so webhook runners attach for games
  * however they started — challenge, seek, ladder scheduler — and re-attach automatically after a restart's `resume`.
  * Deliveries re-read the registration per turn, so a `DELETE /bot/webhook` or a re-register (new URL/secret) takes
  * effect at the next turn, not the next game.
  */
final class Webhooks private (
    registry: GameRegistry,
    store: WebhookStore,
    client: Client[IO],
    checkUrl: String => IO[Either[String, Uri]],
    transport: Option[WebhookTransport],
    config: Webhooks.Config,
    attached: Ref[IO, Set[(GameId, Seat)]],
    runners: Supervisor[IO],
    stats: WebhookStatsStore,
    deliveryEvents: Queue[IO, Webhooks.DeliveryEvent]
):
  import Webhooks.*

  // ── registration (ownership handshake) ──────────────────────────────────────

  /** Verify ownership of `url` and store the registration: mint a fresh secret and nonce, POST the verification
    * envelope (signed — the shape every future delivery will have), and require `200 {"nonce": <echo>}` back. Only then
    * is the webhook stored; the secret is returned to the caller exactly once. Errors are values for the routes to
    * answer 422 with.
    */
  def register(
      bot: Principal.Bot,
      url: String,
      capabilities: List[WebhookCapability] = Nil
  ): IO[Either[String, BotWebhook]] =
    WebhookCapability.canonicalizeSelection(capabilities) match
      case Left(reason)    => IO.pure(Left(reason))
      case Right(selected) =>
        for
          secret <- WebhookSecurity.randomHex(SecretBytes)
          nonce  <- WebhookSecurity.randomHex(NonceBytes)
          body = WebhookVerification("verification", nonce).asJson.noSpaces
          // `post` owns the single fresh URL-policy/DNS resolution and its end-to-end deadline. A separate preflight
          // here would both resolve twice in production and leave the first DNS lookup outside that deadline.
          answer <- postDetailed(url, secret, body, config.timeout)
          stored <- answer match
            // URL-policy reasons are part of the legacy POST /bot/webhook 422 contract and must remain verbatim.
            // Only failures from the remote verification attempt carry the historical "verification failed" prefix.
            case PostOutcome.PolicyRejected(reason) => IO.pure(Left(reason))
            case PostOutcome.OversizedBody          =>
              IO.pure(Left(s"verification failed: $OversizedBodyMessage"))
            case PostOutcome.HttpStatus(code) =>
              IO.pure(Left(s"verification failed: endpoint answered HTTP $code"))
            case PostOutcome.TimedOut | PostOutcome.Unreachable =>
              IO.pure(Left("verification failed: could not reach the endpoint"))
            case PostOutcome.Ok(echoed) =>
              decode[WebhookNonceEcho](echoed) match
                case Right(WebhookNonceEcho(`nonce`)) =>
                  IO.realTime.flatMap { now =>
                    val hook =
                      BotWebhook(bot.team, bot.name, url, secret, Instant.ofEpochMilli(now.toMillis), selected)
                    store.put(hook).as(Right(hook))
                  }
                case Right(_) => IO.pure(Left("verification failed: endpoint echoed a different nonce"))
                case Left(_)  => IO.pure(Left("verification failed: endpoint did not answer {\"nonce\": ...}"))
        yield stored

  def info(bot: Principal.Bot): IO[Option[BotWebhook]] = store.get(bot.team, bot.name)

  def remove(bot: Principal.Bot): IO[Boolean] = store.delete(bot.team, bot.name)

  /** Liveness probe for the human catalog (E3, ADR-0014): does the bot's registered webhook still answer? Reuses the
    * exact `verification` envelope every implementation of this protocol already understands — the runtime library's
    * handshake answers it unconditionally and unsigned (it must, since a fresh registration has no shared secret yet),
    * so a wake probe never touches game state or needs a valid signature. The POST itself is what "wakes" a
    * scale-to-zero endpoint (e.g. Cloud Run): merely reaching it forces a cold start before a human commits to a game.
    * `false` for no registration, a network failure, a non-200, or a wrong/garbled echo — the caller only needs yes/no.
    *
    * `timeout` defaults to the per-turn window, which is what a catalog wake wants (a cold start may take that long).
    * The showcase readiness probe (#46) passes a much shorter one: it runs on a timer against a bot that is supposed to
    * be warm, and a table must not sit unadvertised for a whole turn window while one probe waits.
    */
  def wake(bot: Principal.Bot, timeout: FiniteDuration = config.timeout): IO[Boolean] =
    store
      .get(bot.team, bot.name)
      .flatMap:
        case None       => IO.pure(false)
        case Some(hook) =>
          WebhookSecurity.randomHex(NonceBytes).flatMap { nonce =>
            val body = WebhookVerification("verification", nonce).asJson.noSpaces
            post(hook.url, hook.secret, body, timeout).map:
              case Left(_)       => false
              case Right(answer) =>
                decode[WebhookNonceEcho](answer) match
                  case Right(WebhookNonceEcho(echoed)) => echoed == nonce
                  case Left(_)                         => false
          }

  // ── delivery ────────────────────────────────────────────────────────────────

  /** Scan → attach → sleep, forever. Scoped to the server by the caller (`.background`), like the other loops. */
  def loop: IO[Nothing] =
    (attachSweep.handleErrorWith(e => Console[IO].errorln(s"[play][webhook] sweep failed: $e")) *>
      IO.sleep(config.scanEvery)).foreverM

  /** Drains `deliveryEvents` and persists each into its histogram cell (#225) — deliberately off the turn path:
    * `deliverTurn` only ever `tryOffer`s (non-blocking) into the queue and moves on, so a slow or failing stats write
    * can never delay a turn or the room's own clock. Scoped to the server by the caller (`.background`), same as
    * `loop`; a single write failure is logged and the loop continues; the event itself is simply dropped, same
    * "best-effort telemetry, never load-bearing" posture as the drop-on-overflow path in `recordDelivery`.
    */
  def statsLoop: IO[Nothing] =
    deliveryEvents.take.flatMap { event =>
      stats
        .recordDeliveryFor(
          event.team,
          event.name,
          event.registrationId,
          event.outcome,
          event.elapsed,
          event.at
        )
        .handleErrorWith(e => Console[IO].errorln(s"[play][webhook] stats write failed: $e"))
    }.foreverM

  /** One sweep (exposed for tests): attach a runner for every (live game, seat) held by a bot with a registered webhook
    * that doesn't have one yet. The loop is the only caller, so attachment never races itself.
    */
  def attachSweep: IO[Unit] =
    registry.list.flatMap(_.traverse_ { (id, room) =>
      room.hasEnded.flatMap:
        case true  => IO.unit
        case false =>
          room.seating.flatMap(_.toList.traverse_ {
            case (seat, bot: Principal.Bot) =>
              attached.get.flatMap: live =>
                if live.contains((id, seat)) then IO.unit
                else
                  store.get(bot.team, bot.name).flatMap {
                    case None    => IO.unit
                    case Some(_) =>
                      // Supervised, not `.start`-detached (review): the runners belong to the service's own
                      // lifecycle, so releasing the `Webhooks` resource cancels every in-flight runner instead
                      // of leaving them running after shutdown — the same "nothing silently detached" doctrine
                      // the other background loops follow.
                      attached.update(_ + ((id, seat))) *>
                        runners
                          .supervise(run(id, room, seat, bot).guarantee(attached.update(_ - ((id, seat)))))
                          .void
                  }
            case _ => IO.unit
          })
    })

  /** The per-(game, seat) runner: an ordinary subscriber that reacts to "your move" and "draw decision" events until
    * the game ends. The subscription's snapshot-then-live overlap can show one version twice — `lastVersion` dedupes,
    * and it advances to whatever state each delivery actually saw, so a turn that was already answered from a fresher
    * snapshot isn't re-answered when its own event arrives.
    */
  private def run(id: GameId, room: GameRoom, seat: Seat, bot: Principal.Bot): IO[Unit] =
    Ref.of[IO, Long](-1L).flatMap { lastVersion =>
      room.subscribe
        .evalMap { event =>
          val actionable = event match
            case GameEvent.Snapshot(v, state, _) =>
              val isPreRoll = state.status == GameStatus.Active && !state.dicePending &&
                state.drawOffer.exists(_.pending) && state.activeSeat == seat
              val isTurn = state.status == GameStatus.Active && state.dicePending && state.activeSeat == seat
              Option.when(isPreRoll || isTurn)(v)
            case GameEvent.DrawOffered(v, by) =>
              Option.when(by != seat)(v)
            case GameEvent.DiceRolled(v, rolledFor, _, _, _, _) =>
              Option.when(rolledFor == seat)(v)
            case _ => None
          actionable match
            case None    => IO.unit
            case Some(v) =>
              lastVersion.get.flatMap: last =>
                if v <= last then IO.unit
                else
                  deliver(id, room, seat, bot, lastVersion)
                    .handleErrorWith(e => Console[IO].errorln(s"[play][webhook] game ${id.value}: delivery died: $e"))
        }
        .compile
        .drain
    }

  /** One delivery attempt: re-read the registration (rotation-aware), re-check against a FRESH snapshot that it is
    * still this seat's decision or turn, POST the envelope (drawDecision or yourTurn), and feed the answer to the room.
    *
    * `retries` counts the transport retries already spent on THIS decision — 0 on the first attempt. It selects the
    * next backoff step and is reported in the log line. Nothing else carries across attempts on purpose: every attempt
    * re-reads the registration and a fresh snapshot, so it re-derives `min(remaining clock, config.timeout)` and stops
    * itself the moment the turn or the pending decision is gone (the seat moved, the game ended, the clock forfeited).
    */
  private def deliver(
      id: GameId,
      room: GameRoom,
      seat: Seat,
      bot: Principal.Bot,
      lastVersion: Ref[IO, Long],
      retries: Int = 0
  ): IO[Unit] =
    (store.get(bot.team, bot.name), room.snapshot).flatMapN {
      case (None, _)           => IO.unit // deleted mid-game: stop delivering, exactly as documented on DELETE
      case (Some(hook), state) =>
        val isOurPreRoll = state.status == GameStatus.Active && !state.dicePending &&
          state.drawOffer.exists(_.pending) && state.activeSeat == seat
        val isOurTurn = state.status == GameStatus.Active && state.dicePending && state.activeSeat == seat

        if isOurPreRoll then
          if !hook.capabilities.contains(WebhookCapability.Draws) then
            // Preserve legacy bot behavior: bots that did not opt in cannot answer a
            // draw decision, so decline before revealing dice and continue with yourTurn.
            store
              .enqueueIfCurrent(hook.team, hook.name, hook.registrationId)(
                room.enqueueDrawResponse(seat, accept = false)
              )
              .flatMap:
                case Some(await) => await.void
                case None        => IO.cede *> deliver(id, room, seat, bot, lastVersion)
          else
          // Keep the decision independent of the roll: capable bots decide before
          // receiving dice in a drawDecision payload.
          {
            val body    = WebhookEnvelope("drawDecision", id.value, seat, state).asJson.noSpaces
            val budget  = state.clocks.map(c => (if seat == Seat.White then c.white else c.black).millis)
            val timeout = budget.fold(config.timeout)(_.min(config.timeout)).max(1.millisecond)
            for
              started <- IO.monotonic
              attempt <- postDetailed(hook.url, hook.secret, body, timeout)
              elapsed <- IO.monotonic.map(_ - started)
              plan = retryPlan(attempt, budget, elapsed, retries)
              outcome <- classifyDrawDecision(id, seat, room, bot, hook, attempt, plan)
              _       <- lastVersion.set(state.version).unlessA(outcome == DeliveryOutcome.StaleRegistration)
              _       <- recordDelivery(bot, hook.registrationId, outcome, elapsed)
              _       <- deliverAgain(id, room, seat, bot, lastVersion, outcome, plan, retries)
            yield ()
          }
        else if isOurTurn then {
          val body    = WebhookEnvelope("yourTurn", id.value, seat, state).asJson.noSpaces
          val budget  = state.clocks.map(c => (if seat == Seat.White then c.white else c.black).millis)
          val timeout = budget.fold(config.timeout)(_.min(config.timeout)).max(1.millisecond)
          for
            started <- IO.monotonic
            attempt <- postDetailed(hook.url, hook.secret, body, timeout)
            elapsed <- IO.monotonic.map(_ - started)
            plan = retryPlan(attempt, budget, elapsed, retries)
            outcome <- classifyTurn(id, seat, room, bot, hook, attempt, plan)
            _       <- lastVersion.set(state.version).unlessA(outcome == DeliveryOutcome.StaleRegistration)
            _       <- recordDelivery(bot, hook.registrationId, outcome, elapsed)
            _       <- deliverAgain(id, room, seat, bot, lastVersion, outcome, plan, retries)
          yield ()
        } else IO.unit
    }

  /** Is this failure worth another attempt, and after how long? Only the transport-level ones are: a 5xx from whatever
    * sits in front of the bot, our own timeout, a connection that never completed. In all three the bot said nothing,
    * so the decision is still genuinely pending and one more request may well produce the move — which is exactly what
    * did not happen on 2026-09-06 (see [[Config.retryBackoff]]). Everything else is the bot's own answer, or our own
    * URL policy, and stays final.
    *
    * `budget` is the seat's clock as of the snapshot this attempt was built from, so `budget - elapsed` is what the
    * clock has left now. Below [[Config.retryClockFloor]] a further attempt would only race the room's own forfeit, so
    * the loop stops and lets the clock decide, exactly as before. An `Unlimited` game has no `budget`; there the room's
    * anti-abandonment deadline ends the turn and the next `deliver` finds nothing to do.
    */
  private def retryPlan(
      attempt: PostOutcome,
      budget: Option[FiniteDuration],
      elapsed: FiniteDuration,
      retries: Int
  ): Option[RetryStep] =
    val transient = attempt match
      case PostOutcome.HttpStatus(code)                   => code >= 500
      case PostOutcome.TimedOut | PostOutcome.Unreachable => true
      case _                                              => false
    val clockAllows = budget.forall(_ - elapsed > config.retryClockFloor)
    Option
      .when(transient && clockAllows)(config.retryBackoff.lift(retries).orElse(config.retryBackoff.lastOption))
      .flatten
      .map(RetryStep(_, retries + 1))

  /** The single "deliver again?" decision both envelope types share, and the one place the two reasons to re-deliver
    * are kept from stacking: a stale registration is re-delivered IMMEDIATELY by the pre-#119 fence path (the answer
    * belonged to a generation that no longer exists — the current one has not been asked yet), and a transport retry
    * sleeps its backoff step first. A stale outcome therefore consumes the retry plan rather than adding to it, and
    * starts the fresh generation's attempt counter at zero: its first request is its first attempt.
    */
  private def deliverAgain(
      id: GameId,
      room: GameRoom,
      seat: Seat,
      bot: Principal.Bot,
      lastVersion: Ref[IO, Long],
      outcome: DeliveryOutcome,
      plan: Option[RetryStep],
      retries: Int
  ): IO[Unit] =
    if outcome == DeliveryOutcome.StaleRegistration then IO.cede *> deliver(id, room, seat, bot, lastVersion)
    else plan.traverse_(step => IO.sleep(step.delay) *> deliver(id, room, seat, bot, lastVersion, retries + 1))

  /** The one log line a planned retry emits, in place of the terminal "(clock decides)" the same failure used to print:
    * same reason text, different ending, so `grep` over a game's log reads as the sequence of attempts it was.
    */
  private def retrying(id: GameId, bot: Principal.Bot, reason: String, step: RetryStep): IO[Unit] =
    // Whole seconds are what the configured schedule is made of; the millisecond form exists so a compressed
    // schedule (the tests') does not log every step as the "0s" that truncation would otherwise produce.
    val delay = if step.delay >= 1.second then s"${step.delay.toSeconds}s" else s"${step.delay.toMillis}ms"
    Console[IO].errorln(
      s"[play][webhook] game ${id.value} ${bot.externalId}: $reason — retrying in $delay (attempt ${step.attempt})"
    )

  private def classifyDrawDecision(
      id: GameId,
      seat: Seat,
      room: GameRoom,
      bot: Principal.Bot,
      hook: BotWebhook,
      attempt: PostOutcome,
      plan: Option[RetryStep]
  ): IO[DeliveryOutcome] =
    def failed(reason: String, outcome: DeliveryOutcome): IO[DeliveryOutcome] =
      Console[IO].errorln(s"[play][webhook] game ${id.value} ${bot.externalId}: $reason (clock decides)").as(outcome)

    def ifCurrent(value: IO[DeliveryOutcome]): IO[DeliveryOutcome] =
      store
        .enqueueIfCurrent(hook.team, hook.name, hook.registrationId)(IO.unit)
        .flatMap:
          case None    => IO.pure(DeliveryOutcome.StaleRegistration)
          case Some(_) => value

    def respond(accept: Boolean): IO[Option[GameRoom.TurnVerdict]] =
      store
        .enqueueIfCurrent(hook.team, hook.name, hook.registrationId)(room.enqueueDrawResponse(seat, accept))
        .flatMap(_.traverse(identity))

    def declineThen(reason: String, outcome: DeliveryOutcome): IO[DeliveryOutcome] =
      respond(accept = false).flatMap:
        case None    => IO.pure(DeliveryOutcome.StaleRegistration)
        case Some(_) => failed(reason, outcome)

    /** A transport failure the runner will retry must leave the offer PENDING — declining it here would answer on
      * behalf of a bot that never spoke, and there would be nothing left for the retry to deliver. Without a plan the
      * pre-#119 behaviour stands: decline, reveal the dice, let the clock decide.
      */
    def transient(reason: String, outcome: DeliveryOutcome): IO[DeliveryOutcome] =
      plan match
        case Some(step) => ifCurrent(retrying(id, bot, reason, step).as(outcome))
        case None       => declineThen(reason, outcome)

    def decision(accept: Boolean): IO[DeliveryOutcome] =
      respond(accept).flatMap:
        case None                                       => IO.pure(DeliveryOutcome.StaleRegistration)
        case Some(GameRoom.TurnVerdict.Applied(_, _))   => IO.pure(DeliveryOutcome.Applied)
        case Some(GameRoom.TurnVerdict.Refused(reason)) => failed(s"refused: $reason", DeliveryOutcome.Refused)

    attempt match
      case PostOutcome.Ok(answer) =>
        decode[BotMove](answer) match
          case Left(_) =>
            // Garbled response to drawDecision: decline draw offer and proceed to dice reveal
            declineThen(
              "unparseable drawDecision response",
              DeliveryOutcome.Garbled
            )
          case Right(botMove) if botMove.resign =>
            enqueueResign(seat, room, hook, failed)
          case Right(botMove) if botMove.acceptDraw.contains(true) =>
            decision(accept = true)
          case Right(_) =>
            // Any other response: decline draw offer and proceed to dice reveal
            decision(accept = false)
      case PostOutcome.OversizedBody =>
        declineThen(
          OversizedBodyMessage,
          DeliveryOutcome.OversizedBody
        )
      case PostOutcome.HttpStatus(code) =>
        transient(
          s"endpoint answered HTTP $code",
          DeliveryOutcome.HttpStatus(code)
        )
      case PostOutcome.TimedOut =>
        transient(CouldNotReachEndpointMessage, DeliveryOutcome.TimedOut)
      case PostOutcome.Unreachable =>
        transient(CouldNotReachEndpointMessage, DeliveryOutcome.Unreachable)
      case PostOutcome.PolicyRejected(reason) =>
        declineThen(reason, DeliveryOutcome.Unreachable)

  private def classifyTurn(
      id: GameId,
      seat: Seat,
      room: GameRoom,
      bot: Principal.Bot,
      hook: BotWebhook,
      attempt: PostOutcome,
      plan: Option[RetryStep]
  ): IO[DeliveryOutcome] =
    def failed(reason: String, outcome: DeliveryOutcome): IO[DeliveryOutcome] =
      Console[IO].errorln(s"[play][webhook] game ${id.value} ${bot.externalId}: $reason (clock decides)").as(outcome)

    def ifCurrent(value: IO[DeliveryOutcome]): IO[DeliveryOutcome] =
      store
        .enqueueIfCurrent(hook.team, hook.name, hook.registrationId)(IO.unit)
        .flatMap:
          case None    => IO.pure(DeliveryOutcome.StaleRegistration)
          case Some(_) => value

    /** Nothing to undo on a turn — the roll simply stays unanswered — so a planned retry only swaps the terminal log
      * line for the retry one. The registration fence still runs: an answer from a replaced generation is stale, and
      * the fresh generation's own delivery supersedes the retry (see `deliverAgain`).
      */
    def transient(reason: String, outcome: DeliveryOutcome): IO[DeliveryOutcome] =
      plan match
        case Some(step) => ifCurrent(retrying(id, bot, reason, step).as(outcome))
        case None       => ifCurrent(failed(reason, outcome))

    def submit(move: BotMove): IO[DeliveryOutcome] =
      store
        .enqueueIfCurrent(hook.team, hook.name, hook.registrationId)(
          room.enqueueTurn(seat, move.moves, offerDraw = move.offerDraw)
        )
        .flatMap:
          case None        => IO.pure(DeliveryOutcome.StaleRegistration)
          case Some(await) =>
            await.flatMap:
              case GameRoom.TurnVerdict.Applied(_, _)   => IO.pure(DeliveryOutcome.Applied)
              case GameRoom.TurnVerdict.Refused(reason) => failed(s"refused: $reason", DeliveryOutcome.Refused)

    attempt match
      case PostOutcome.Ok(answer) =>
        decode[BotMove](answer) match
          case Left(_)                          => ifCurrent(failed("unparseable response", DeliveryOutcome.Garbled))
          case Right(botMove) if botMove.resign =>
            enqueueResign(seat, room, hook, failed)
          case Right(botMove) if botMove.moves.isEmpty =>
            ifCurrent(
              Console[IO]
                .errorln(s"[play][webhook] game ${id.value} ${bot.externalId}: declined (empty moves)")
                .as(DeliveryOutcome.Declined)
            )
          case Right(botMove) =>
            submit(botMove)
      case PostOutcome.OversizedBody =>
        ifCurrent(failed(OversizedBodyMessage, DeliveryOutcome.OversizedBody))
      case PostOutcome.HttpStatus(code) =>
        transient(s"endpoint answered HTTP $code", DeliveryOutcome.HttpStatus(code))
      case PostOutcome.TimedOut               => transient(CouldNotReachEndpointMessage, DeliveryOutcome.TimedOut)
      case PostOutcome.Unreachable            => transient(CouldNotReachEndpointMessage, DeliveryOutcome.Unreachable)
      case PostOutcome.PolicyRejected(reason) => ifCurrent(failed(reason, DeliveryOutcome.Unreachable))

  /** The one resignation path every delivery answer shares (ADR 006 §3.3): `resign: true` wins over every other member
    * of the body, is applied even when the answer's `version` or `decisionId` is stale (resignation is not a decision
    * step), and is recorded as its own `Resigned` outcome so operators can tell a deliberate concession from a fault.
    * The reserved doubling deliveries (`doubleOpportunity`, `doubleDecision`; play-api #61/#62) must route a
    * `resign: true` answer here before reading `offerDouble` or `acceptDouble`, exactly as `classifyTurn` and
    * `classifyDrawDecision` do before reading `moves` and `acceptDraw`.
    */
  private def enqueueResign(
      seat: Seat,
      room: GameRoom,
      hook: BotWebhook,
      failed: (String, DeliveryOutcome) => IO[DeliveryOutcome]
  ): IO[DeliveryOutcome] =
    store
      .enqueueIfCurrent(hook.team, hook.name, hook.registrationId)(room.enqueueResign(seat))
      .flatMap:
        case None        => IO.pure(DeliveryOutcome.StaleRegistration)
        case Some(await) =>
          await.flatMap:
            case GameRoom.TurnVerdict.Applied(_, _)   => IO.pure(DeliveryOutcome.Resigned)
            case GameRoom.TurnVerdict.Refused(reason) => failed(s"refused: $reason", DeliveryOutcome.Refused)

  /** Fire-and-forget into the drain queue (#225) — `tryOffer` never blocks a turn on a slow or backed-up stats writer.
    * Overflow (the queue is bounded, matching this class's own "delivery rate is structurally bounded" doctrine) drops
    * the event with one log line rather than either blocking or silently losing it unremarked.
    */
  private def recordDelivery(
      bot: Principal.Bot,
      registrationId: UUID,
      outcome: DeliveryOutcome,
      elapsed: FiniteDuration
  ): IO[Unit] =
    IO.realTime.map(t => Instant.ofEpochMilli(t.toMillis)).flatMap { at =>
      deliveryEvents.tryOffer(DeliveryEvent(bot.team, bot.name, registrationId, outcome, elapsed, at)).flatMap {
        accepted =>
          Console[IO]
            .errorln(
              s"[play][webhook] delivery-stats queue full — dropped a ${DeliveryOutcome.key(outcome)} record " +
                s"for ${bot.externalId}"
            )
            .unlessA(accepted)
      }
    }

  /** The narrow legacy shape used by `wake`: `post` is a thin view over [[postDetailed]], preserving every pre-#225
    * string. Registration calls the typed form directly so it can keep URL-policy failures verbatim while prefixing
    * only failures from the remote ownership handshake.
    */
  private def post(url: String, secret: String, body: String, timeout: FiniteDuration): IO[Either[String, String]] =
    postDetailed(url, secret, body, timeout).map(_.legacy)

  /** One signed POST with the full security posture: the URL re-passes the guard (fresh resolve at send time — the
    * anti-rebinding property), the body is signed with the per-bot secret, redirects are never followed (no redirect
    * middleware on the client), and the response read is size-capped. Returns the FULL typed outcome — `deliverTurn`
    * needs to distinguish `TimedOut` from `Unreachable` for delivery telemetry (#225), a distinction `post`'s legacy
    * `Either[String, String]` deliberately erases (both read as the same caller-visible "could not reach the endpoint"
    * — see [[PostOutcome.legacy]] for why: the reason must not become a connectivity oracle against internal hosts).
    */
  private def postDetailed(url: String, secret: String, body: String, timeout: FiniteDuration): IO[PostOutcome] =
    transport match
      case Some(pinned) =>
        pinned
          .postSigned(url, secret, body, timeout)
          .map:
            case WebhookTransport.Outcome.Ok(bytes) =>
              PostOutcome.Ok(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
            case WebhookTransport.Outcome.PolicyRejected(failure) => PostOutcome.PolicyRejected(failure.message)
            case WebhookTransport.Outcome.OversizedBody           => PostOutcome.OversizedBody
            case WebhookTransport.Outcome.HttpStatus(code)        => PostOutcome.HttpStatus(code)
            case WebhookTransport.Outcome.TimedOut                => PostOutcome.TimedOut
            case WebhookTransport.Outcome.Unreachable             => PostOutcome.Unreachable
      case None => legacyPostDetailed(url, secret, body, timeout)

  /** Compatibility transport seam for hermetic tests. Production always supplies [[WebhookTransport]]. */
  private def legacyPostDetailed(
      url: String,
      secret: String,
      body: String,
      timeout: FiniteDuration
  ): IO[PostOutcome] =
    val attempt: IO[Either[PostOutcome, (Int, Array[Byte])]] = checkUrl(url).flatMap:
      case Left(reason) => IO.pure(Left(PostOutcome.PolicyRejected(reason)))
      case Right(uri)   =>
        IO.realTime.map(_.toSeconds).flatMap { ts =>
          val request = Request[IO](Method.POST, uri)
            .withEntity(body)
            .withContentType(`Content-Type`(MediaType.application.json))
            .putHeaders(
              Header.Raw(CIString(WebhookSecurity.SignatureHeader), WebhookSecurity.sign(secret, ts, body)),
              Header.Raw(CIString(WebhookSecurity.TimestampHeader), ts.toString)
            )
          client
            .run(request)
            .use { response =>
              // One byte past the cap is read so an oversized body is REJECTED, not silently truncated
              // (review): a truncated prefix that happens to parse must never pass for the real answer.
              response.body
                .take(MaxResponseBytes + 1)
                .compile
                .to(Array)
                .flatMap: bytes =>
                  if bytes.length > MaxResponseBytes then IO.raiseError(OversizedResponse)
                  else IO.pure(Right((response.status.code, bytes)))
            }
        }
    // The one deadline starts before URL policy and DNS resolution. Oversized responses fail inside `use`, so an
    // Ember-like response finalizer sees an error and closes rather than draining an unbounded remainder.
    attempt
      .timeout(timeout)
      .attempt
      .flatMap:
        case Right(Left(outcome))       => IO.pure(outcome)
        case Right(Right((200, bytes))) =>
          IO.pure(PostOutcome.Ok(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)))
        case Right(Right((code, _))) => IO.pure(PostOutcome.HttpStatus(code))
        case Left(OversizedResponse) => IO.pure(PostOutcome.OversizedBody)
        case Left(error)             =>
          // The transport detail (exception messages embed resolved addresses and distinguish refused
          // from timed-out) goes to the server log only; the caller-visible reason stays generic so a
          // 422 can't be used as a connectivity oracle against internal hosts (review). The TYPE returned
          // here — TimedOut vs Unreachable — is server-internal telemetry (#225) and crosses no such
          // boundary, so it may distinguish what the logged/caller-visible text deliberately does not.
          Console[IO].errorln("[play][webhook] POST failed").as {
            error match
              case _: java.util.concurrent.TimeoutException => PostOutcome.TimedOut
              case _                                        => PostOutcome.Unreachable
          }

object Webhooks:

  /** Webhook secrets are HMAC keys: 32 bytes matches the SHA-256 block-derived key advice. Nonces only need to be
    * unguessable within one handshake.
    */
  private val SecretBytes = 32
  private val NonceBytes  = 16

  /** Stable caller-visible text shared by timeout and transport failures to avoid exposing a connectivity oracle. */
  private val CouldNotReachEndpointMessage = "could not reach the endpoint"
  private val OversizedBodyMessage         = "endpoint answered with an oversized body"

  /** Response-read cap: a `{"moves":[...]}` answer is bytes, not megabytes — the cap bounds what a hostile endpoint can
    * make the server buffer. A truncated body simply fails JSON decoding and is treated as garbage.
    */
  private val MaxResponseBytes = 65536L

  /** Internal signal used to force an error exit from the HTTP response resource before mapping to a safe outcome. */
  private case object OversizedResponse extends RuntimeException with NoStackTrace

  /** The typed detail behind one POST attempt (#225) — see [[Webhooks.postDetailed]]. */
  private enum PostOutcome:
    case Ok(body: String)
    case OversizedBody
    case HttpStatus(code: Int)
    case TimedOut
    case Unreachable
    case PolicyRejected(reason: String) // checkUrl's own re-check failed; `reason` is its exact, existing message

  private object PostOutcome:
    extension (outcome: PostOutcome)
      /** The pre-#225 shape, reproduced exactly: same cases, same strings, string-for-string. `TimedOut` and
        * `Unreachable` collapse to the identical caller-visible text on purpose — see `postDetailed`'s doc.
        */
      def legacy: Either[String, String] = outcome match
        case Ok(body)               => Right(body)
        case OversizedBody          => Left(OversizedBodyMessage)
        case HttpStatus(code)       => Left(s"endpoint answered HTTP $code")
        case TimedOut               => Left(CouldNotReachEndpointMessage)
        case Unreachable            => Left(CouldNotReachEndpointMessage)
        case PolicyRejected(reason) => Left(reason)

  /** One delivery, queued for the stats drain loop (#225) — plain data, no `IO` inside, so `tryOffer`ing it is O(1) and
    * never itself a source of backpressure on the delivering fiber.
    */
  final private case class DeliveryEvent(
      team: String,
      name: String,
      registrationId: UUID,
      outcome: DeliveryOutcome,
      elapsed: FiniteDuration,
      at: Instant
  )

  /** Bounded generously relative to real traffic — delivery rate is structurally bounded by live games in progress
    * (this class's own doc), so overflow should never happen in practice; the bound exists purely so a stuck stats
    * writer degrades to dropped telemetry, never to unbounded memory growth.
    */
  private val DeliveryEventQueueCapacity = 512

  /** One scheduled retry of a transport failure: how long to wait, and which attempt it will be (1-based, for the log).
    * Produced by [[Webhooks.retryPlan]] and consumed by [[Webhooks.deliverAgain]] — a value rather than a bare duration
    * so the classifier's log line and the runner's sleep can never disagree about the attempt number.
    */
  final private case class RetryStep(delay: FiniteDuration, attempt: Int)

  /** @param timeout
    *   the per-turn window: the longest a delivery may take, before the mover's remaining clock caps it further.
    * @param scanEvery
    *   how often the dispatcher re-scans for turns it owes a delivery.
    * @param retryBackoff
    *   the wait before each successive retry of a TRANSPORT failure, the last entry repeating for every further
    *   attempt; empty disables retries. See [[Config.DefaultRetryBackoff]].
    * @param retryClockFloor
    *   stop retrying once the seat's clock holds less than this. See [[Config.DefaultRetryClockFloor]].
    */
  final case class Config(
      timeout: FiniteDuration,
      scanEvery: FiniteDuration = 2.seconds,
      retryBackoff: List[FiniteDuration] = Config.DefaultRetryBackoff,
      retryClockFloor: FiniteDuration = Config.DefaultRetryClockFloor
  ):

    /** The shared HTTP client's own deadlines must sit ABOVE the per-turn window, or they — not this config — decide
      * when a delivery dies. Ember's defaults are 45 s (`timeout`, the header-receive cut) and 60 s
      * (`idleConnectionTime`), both below a budget a bot may legitimately spend on one turn, which is exactly how a
      * configured 210 s silently became 45 s in production (#188). The headroom keeps [[post]]'s own `.timeout` the
      * first deadline to fire, so a slow bot is logged as a slow bot rather than as a transport failure.
      *
      * These govern the SHARED client, which since ADR-004 serves ingest delivery and the hermetic test fallback in
      * [[legacyPostDetailed]]. Production webhook delivery goes through [[WebhookTransport]] instead, which builds its
      * own DNS-pinned client with both deadlines at `Duration.Inf` and relies solely on the caller's end-to-end
      * `.timeout`. The two paths therefore agree on the property that matters — this config is the first deadline to
      * fire — by different means.
      */
    def clientTimeout: FiniteDuration = timeout + Config.ClientTimeoutHeadroom

    /** Idle headroom is the larger of the two: the connection is idle for precisely as long as the bot is thinking. */
    def clientIdleTimeout: FiniteDuration = timeout + Config.ClientIdleHeadroom

  object Config:

    private val ClientTimeoutHeadroom: FiniteDuration = 10.seconds
    private val ClientIdleHeadroom: FiniteDuration    = 30.seconds

    /** Why transport failures are retried at all, recorded here so nobody re-derives it: on 2026-09-06 the featured
      * showcase bot `rpi3/hunter-book` lost game `d78dbdc1` on time with ~4:50 left on its clock because ONE `yourTurn`
      * delivery came back `502` from the Cloudflare tunnel in front of it — the connector logged `Unable to reach the
      * origin service … EOF`, i.e. the origin had closed an idle connection before answering. The bot was healthy and
      * had answered the previous three plies in 0–7 s, but nothing re-triggers a delivery while it is the bot's own
      * turn (`run` reacts to game events, and the state cannot change until this seat moves), so the human waited five
      * minutes for a win on time. Rare per delivery — 3 × `http_502` against 10 855 applied deliveries in 24 h — and
      * certain per game once it happens.
      *
      * Front-loaded, so the common case (one dropped connection) costs the bot ~2 s of its clock, then widening so an
      * endpoint that is genuinely down is not hammered for the length of a long clock. The LAST entry repeats for every
      * further attempt, which makes this list read as "2 s, 5 s, 10 s, 20 s, then every 30 s". An empty list disables
      * transport retries and restores the pre-#119 single-attempt behaviour.
      */
    val DefaultRetryBackoff: List[FiniteDuration] = List(2.seconds, 5.seconds, 10.seconds, 20.seconds, 30.seconds)

    /** The clock floor that ends the retry loop. Below this the room's own forfeit is about to fire, and a further
      * attempt could only race it — worse, it could land a move on a clock that has already expired. Stopping here
      * hands the turn back to exactly the pre-#119 behaviour: the clock decides.
      */
    val DefaultRetryClockFloor: FiniteDuration = 3.seconds

    /** Same split as `LadderScheduler.Config.fromValues`: the raw value comes in, only a strictly positive integer
      * enables the feature — a zero/negative/garbled timeout is treated as absent rather than busy-looping or disabling
      * deliveries silently at runtime.
      */
    def fromValues(timeoutSecondsRaw: Option[String]): Option[Config] =
      timeoutSecondsRaw.filter(_.nonEmpty).flatMap(_.toIntOption).filter(_ > 0).map(s => Config(s.seconds))

  /** Opt-in by env, the same "absence disables" idiom as ingest/ladder/rating: `WEBHOOK_TIMEOUT_SECONDS` both enables
    * webhooks (routes + dispatcher) and bounds each delivery attempt; the effective per-turn timeout is additionally
    * capped by the mover's remaining clock.
    *
    * Sizing it is a deployment decision, and it is a *cap*, not a promise: what a given bot actually gets is
    * `min(its remaining clock, this cap, whatever its own hosting allows)`. It bounds one ATTEMPT: since #119 a
    * transport failure may be retried inside the same turn, so what bounds a turn end to end is still the seat's clock
    * — this value only decides how long any single request may hang before it counts as failed.
    *
    * The floor is what the engine's `TimeManager` legitimately asks for — on Fischer(600,10) its target reaches ~57 s
    * once `movesToGo` bottoms out, and ~68 s on a clock the increment has grown (observed in production). Configure
    * below that and correct bots get truncated mid-thought.
    *
    * The intermediaries in front of the bots are deliberately NOT the ceiling here, because they differ per bot and
    * belong to their authors: a Cloudflare-proxied endpoint is cut at 100 s, an OCI API Gateway at 60 s, an AWS API
    * Gateway at 29 s by default. A bot behind a narrower limit hits it first and its proxy answers — which this server
    * logs as `endpoint answered HTTP …`, a diagnosable outcome, unlike the silent truncation an under-sized cap
    * produces. This value is the only deadline production delivery has: [[WebhookTransport]] gives its per-request
    * client `Duration.Inf` and lets this `.timeout` decide, so a 120 s cap means holding a connection open for up to
    * ~120 s. The derived [[Config.clientTimeout]]/[[Config.clientIdleTimeout]] headroom still applies to the shared
    * client behind [[legacyPostDetailed]] and to ingest delivery.
    *
    * 120 s is the deployed choice: it clears the engine's hard cap at a 600 s clock and keeps this server from being
    * the binding constraint for bots whose path allows more (Cloud Run 300 s, Azure 230 s). An operator whose bots all
    * sit behind a narrower proxy can configure less and lose nothing.
    */
  def configFromEnv: Option[Config] = Config.fromValues(sys.env.get("WEBHOOK_TIMEOUT_SECONDS"))

  /** A `Resource` because the service OWNS its per-game runner fibers (a `Supervisor`): releasing it cancels every
    * in-flight runner, so webhook delivery can never outlive the server that started it.
    *
    * `stats` defaults to [[WebhookStatsStore.noop]] — every existing caller (tests, and any deployment that hasn't
    * wired persistence) gets a `Webhooks` that classifies deliveries and drains the queue exactly as if telemetry were
    * on, only the writes themselves go nowhere. `Main` is the one caller that passes the real, Postgres-backed store.
    */
  def create(
      registry: GameRegistry,
      store: WebhookStore,
      client: Client[IO],
      config: Config,
      checkUrl: String => IO[Either[String, Uri]] = WebhookSecurity.checkPublicHttps,
      stats: WebhookStatsStore = WebhookStatsStore.noop,
      transport: Option[WebhookTransport] = None
  ): Resource[IO, Webhooks] =
    Supervisor[IO](await = false).evalMap { runners =>
      (Ref.of[IO, Set[(GameId, Seat)]](Set.empty), Queue.bounded[IO, DeliveryEvent](DeliveryEventQueueCapacity))
        .mapN(new Webhooks(registry, store, client, checkUrl, transport, config, _, runners, stats, _))
    }
