package dicechess.play.server

import cats.effect.{Async, IO, Resource}
import fs2.io.net.tls.TLSContext
import fs2.io.net.{Network, WebhookPinnedNetwork}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.{Header, MediaType, Method, Request, Response}
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

/** Rebinding-safe outbound webhook HTTP. One call performs one fresh policy/DNS resolution and connects to an IP from
  * that exact result. The request URI is never rewritten, so Ember keeps the original hostname for HTTP Host, TLS SNI,
  * and certificate endpoint verification while [[fs2.io.net.WebhookPinnedNetwork]] changes only the TCP destination.
  * (Ember 0.23.34+ takes its connections from the implicit `Network`; the former `withSocketGroup` hook is a documented
  * no-op there, see #193.)
  *
  * A new one-request Ember pool is scoped to each call. This intentionally gives DNS pinning an obvious lifetime and
  * rules out connection reuse across independently validated results. The system TLS context is shared by the
  * long-lived [[WebhookTransport]] resource, so the per-call client does not rebuild trust material.
  *
  * '''Known cost, accepted deliberately.''' Turn delivery therefore pays a fresh TCP and TLS handshake every time,
  * where the previous shared pool kept a connection alive between turns of the same game. Those two extra round trips
  * are charged to the mover's clock (`deliver` sizes its timeout from the seat's remaining time), so on a distant
  * endpoint they cost a bot real thinking budget and, at a very low clock, can turn a delivery that used to finish into
  * `Outcome.TimedOut`. Keep-alive can be restored without weakening the guarantee — memoise the client on
  * `(originalHost, port, selectedAddress)` and reuse it only when THIS call's fresh `resolvePublicHttps` returns the
  * same pinned address — but that is a pool with its own lifetime and eviction rules, so it is deliberately not part of
  * the initial rollout. Measure delivery latency after enabling the flag before deciding it is needed.
  */
trait WebhookTransport:
  import WebhookTransport.Outcome

  /** Sign and POST the exact body bytes. `timeout` bounds the whole operation: DNS, connect, TLS, response headers, and
    * the bounded response-body read.
    */
  def postSigned(
      url: String,
      secret: String,
      body: Array[Byte],
      timeout: FiniteDuration
  ): IO[Outcome]

  final def postSigned(url: String, secret: String, body: String, timeout: FiniteDuration): IO[Outcome] =
    postSigned(url, secret, body.getBytes(UTF_8), timeout)

object WebhookTransport:

  /** Safe, exhaustive result vocabulary. No case retains a raw exception, URL, response object, or unbounded body. */
  enum Outcome:
    case Ok(body: Array[Byte])
    case PolicyRejected(failure: WebhookUrlFailure)
    case OversizedBody
    case HttpStatus(code: Int)
    case TimedOut
    case Unreachable

  private[server] type Resolver      = String => IO[Either[WebhookUrlFailure, ResolvedWebhookTarget]]
  private[server] type ClientFactory = ResolvedWebhookTarget => Resource[IO, Client[IO]]

  private val MaxResponseBytes = 65536L

  /** Raised from inside `Client.run(...).use` when the response exceeds the cap. The error exit case tells Ember not to
    * drain an attacker-controlled remainder before releasing the connection; the outer boundary immediately maps the
    * sentinel back to the public, detail-free outcome.
    */
  private case object OversizedResponse extends RuntimeException with NoStackTrace

  /** Production transport with one shared system trust context and an exact-IP Ember client per request. */
  def resource: Resource[IO, WebhookTransport] =
    val network = Network[IO]
    network.tlsContext.systemResource.map { tlsContext =>
      from(WebhookSecurity.resolvePublicHttps, target => pinnedClient(network, tlsContext, target))
    }

  /** Deterministic seam for policy, timeout, request, redirect, and response-limit tests. */
  private[server] def from(resolver: Resolver, clientFactory: ClientFactory): WebhookTransport =
    new Live(resolver, clientFactory)

  private[server] def pinnedClient(
      network: Network[IO],
      tlsContext: TLSContext[IO],
      target: ResolvedWebhookTarget
  ): Resource[IO, Client[IO]] =
    // The pinned network is passed explicitly rather than left to implicit resolution: the ambient `Network[IO]` in
    // fs2's companion would type-check just as well, and that silent fallback is exactly the failure this guards.
    val pinned = WebhookPinnedNetwork(network, target.originalHost, target.port, target.selectedAddress)
    EmberClientBuilder
      .default[IO](using Async[IO], pinned)
      .withTLSContext(tlsContext)
      // The outer timeout is the single end-to-end deadline. Ember's narrower defaults must not undercut a gameplay
      // delivery timeout, nor leave DNS and body consumption outside a different timer.
      .withTimeout(Duration.Inf)
      .withIdleConnectionTime(Duration.Inf)
      .withMaxTotal(1)
      .withMaxPerKey(_ => 1)
      .build

  final private class Live(resolver: Resolver, clientFactory: ClientFactory) extends WebhookTransport:
    def postSigned(
        url: String,
        secret: String,
        body: Array[Byte],
        timeout: FiniteDuration
    ): IO[Outcome] =
      val payload = body.clone()
      val attempt = resolver(url).flatMap:
        case Left(failure) => IO.pure(Outcome.PolicyRejected(failure))
        case Right(target) =>
          IO.realTime.map(_.toSeconds).flatMap { timestamp =>
            val signature = WebhookSecurity.sign(secret, timestamp, payload)
            val request   = Request[IO](Method.POST, target.uri)
              .withEntity(payload)
              .withContentType(`Content-Type`(MediaType.application.json))
              .putHeaders(
                Header.Raw(CIString(WebhookSecurity.SignatureHeader), signature),
                Header.Raw(CIString(WebhookSecurity.TimestampHeader), timestamp.toString)
              )
            clientFactory(target).flatMap(_.run(request)).use(readResponse)
          }

      attempt
        .timeoutTo(timeout, IO.pure(Outcome.TimedOut))
        .handleError:
          case OversizedResponse   => Outcome.OversizedBody
          case _: TimeoutException => Outcome.TimedOut
          case _                   => Outcome.Unreachable

    private def readResponse(response: Response[IO]): IO[Outcome] =
      response.body
        .take(MaxResponseBytes + 1)
        .compile
        .to(Array)
        .flatMap: bytes =>
          if bytes.length > MaxResponseBytes then IO.raiseError(OversizedResponse)
          else if response.status.code == 200 then IO.pure(Outcome.Ok(bytes))
          else IO.pure(Outcome.HttpStatus(response.status.code))
