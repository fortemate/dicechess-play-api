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
  * '''Reliability is the clock''': delivery is single-attempt with a bounded timeout (`min(config, the mover's
  * remaining clock)`); on timeout / non-200 / garbage the runner does nothing — the room's own deadline forfeits the
  * game exactly as it would for a polling bot that stopped polling. No retries, no dead-letter, no dispatcher state.
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
              IO.pure(Left("verification failed: endpoint answered with an oversized body"))
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
    */
  def wake(bot: Principal.Bot): IO[Boolean] =
    store
      .get(bot.team, bot.name)
      .flatMap:
        case None       => IO.pure(false)
        case Some(hook) =>
          WebhookSecurity.randomHex(NonceBytes).flatMap { nonce =>
            val body = WebhookVerification("verification", nonce).asJson.noSpaces
            post(hook.url, hook.secret, body, config.timeout).map:
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
    */
  private def deliver(
      id: GameId,
      room: GameRoom,
      seat: Seat,
      bot: Principal.Bot,
      lastVersion: Ref[IO, Long]
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
              outcome <- classifyDrawDecision(id, seat, room, bot, hook, attempt)
              _       <- lastVersion.set(state.version).unlessA(outcome == DeliveryOutcome.StaleRegistration)
              _       <- recordDelivery(bot, hook.registrationId, outcome, elapsed)
              _       <- (IO.cede *> deliver(id, room, seat, bot, lastVersion))
                .whenA(outcome == DeliveryOutcome.StaleRegistration)
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
            outcome <- classifyTurn(id, seat, room, bot, hook, attempt)
            _       <- lastVersion.set(state.version).unlessA(outcome == DeliveryOutcome.StaleRegistration)
            _       <- recordDelivery(bot, hook.registrationId, outcome, elapsed)
            _       <- (IO.cede *> deliver(id, room, seat, bot, lastVersion))
              .whenA(outcome == DeliveryOutcome.StaleRegistration)
          yield ()
        } else IO.unit
    }

  private def classifyDrawDecision(
      id: GameId,
      seat: Seat,
      room: GameRoom,
      bot: Principal.Bot,
      hook: BotWebhook,
      attempt: PostOutcome
  ): IO[DeliveryOutcome] =
    def failed(reason: String, outcome: DeliveryOutcome): IO[DeliveryOutcome] =
      Console[IO].errorln(s"[play][webhook] game ${id.value} ${bot.externalId}: $reason (clock decides)").as(outcome)

    def respond(accept: Boolean): IO[Option[GameRoom.TurnVerdict]] =
      store
        .enqueueIfCurrent(hook.team, hook.name, hook.registrationId)(room.enqueueDrawResponse(seat, accept))
        .flatMap(_.traverse(identity))

    def declineThen(reason: String, outcome: DeliveryOutcome): IO[DeliveryOutcome] =
      respond(accept = false).flatMap:
        case None    => IO.pure(DeliveryOutcome.StaleRegistration)
        case Some(_) => failed(reason, outcome)

    def decision(accept: Boolean): IO[DeliveryOutcome] =
      respond(accept).flatMap:
        case None                                       => IO.pure(DeliveryOutcome.StaleRegistration)
        case Some(GameRoom.TurnVerdict.Applied(_))      => IO.pure(DeliveryOutcome.Applied)
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
          case Right(botMove) if botMove.acceptDraw.contains(true) =>
            decision(accept = true)
          case Right(_) =>
            // Any other response: decline draw offer and proceed to dice reveal
            decision(accept = false)
      case PostOutcome.OversizedBody =>
        declineThen(
          "endpoint answered with an oversized body",
          DeliveryOutcome.OversizedBody
        )
      case PostOutcome.HttpStatus(code) =>
        declineThen(
          s"endpoint answered HTTP $code",
          DeliveryOutcome.HttpStatus(code)
        )
      case PostOutcome.TimedOut =>
        declineThen(CouldNotReachEndpointMessage, DeliveryOutcome.TimedOut)
      case PostOutcome.Unreachable =>
        declineThen(CouldNotReachEndpointMessage, DeliveryOutcome.Unreachable)
      case PostOutcome.PolicyRejected(reason) =>
        declineThen(reason, DeliveryOutcome.Unreachable)

  private def classifyTurn(
      id: GameId,
      seat: Seat,
      room: GameRoom,
      bot: Principal.Bot,
      hook: BotWebhook,
      attempt: PostOutcome
  ): IO[DeliveryOutcome] =
    def failed(reason: String, outcome: DeliveryOutcome): IO[DeliveryOutcome] =
      Console[IO].errorln(s"[play][webhook] game ${id.value} ${bot.externalId}: $reason (clock decides)").as(outcome)

    def ifCurrent(value: IO[DeliveryOutcome]): IO[DeliveryOutcome] =
      store
        .enqueueIfCurrent(hook.team, hook.name, hook.registrationId)(IO.unit)
        .flatMap:
          case None    => IO.pure(DeliveryOutcome.StaleRegistration)
          case Some(_) => value

    def submit(move: BotMove): IO[DeliveryOutcome] =
      store
        .enqueueIfCurrent(hook.team, hook.name, hook.registrationId)(
          room.enqueueTurn(seat, move.moves, offerDraw = move.offerDraw)
        )
        .flatMap:
          case None        => IO.pure(DeliveryOutcome.StaleRegistration)
          case Some(await) =>
            await.flatMap:
              case GameRoom.TurnVerdict.Applied(_)      => IO.pure(DeliveryOutcome.Applied)
              case GameRoom.TurnVerdict.Refused(reason) => failed(s"refused: $reason", DeliveryOutcome.Refused)

    attempt match
      case PostOutcome.Ok(answer) =>
        decode[BotMove](answer) match
          case Left(_) => ifCurrent(failed("unparseable response", DeliveryOutcome.Garbled))
          case Right(botMove) if botMove.moves.isEmpty =>
            ifCurrent(
              Console[IO]
                .errorln(s"[play][webhook] game ${id.value} ${bot.externalId}: declined (empty moves)")
                .as(DeliveryOutcome.Declined)
            )
          case Right(botMove) =>
            submit(botMove)
      case PostOutcome.OversizedBody =>
        ifCurrent(failed("endpoint answered with an oversized body", DeliveryOutcome.OversizedBody))
      case PostOutcome.HttpStatus(code) =>
        ifCurrent(failed(s"endpoint answered HTTP $code", DeliveryOutcome.HttpStatus(code)))
      case PostOutcome.TimedOut    => ifCurrent(failed(CouldNotReachEndpointMessage, DeliveryOutcome.TimedOut))
      case PostOutcome.Unreachable => ifCurrent(failed(CouldNotReachEndpointMessage, DeliveryOutcome.Unreachable))
      case PostOutcome.PolicyRejected(reason) => ifCurrent(failed(reason, DeliveryOutcome.Unreachable))

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
        case OversizedBody          => Left("endpoint answered with an oversized body")
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

  /** @param timeout
    *   the per-turn window: the longest a delivery may take, before the mover's remaining clock caps it further.
    * @param scanEvery
    *   how often the dispatcher re-scans for turns it owes a delivery.
    */
  final case class Config(timeout: FiniteDuration, scanEvery: FiniteDuration = 2.seconds):

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
    * `min(its remaining clock, this cap, whatever its own hosting allows)`.
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
