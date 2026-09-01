package dicechess.play.server

import cats.effect.{IO, Resource}
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.Stream
import fs2.io.net.tls.TLSContext
import fs2.io.net.{Network, Socket, SocketGroup, SocketOption}
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
  * and certificate endpoint verification while [[PinnedSocketGroup]] changes only the TCP destination.
  *
  * A new one-request Ember pool is scoped to each call. This intentionally gives DNS pinning an obvious lifetime and
  * rules out connection reuse across independently validated results. The system TLS context is shared by the
  * long-lived [[WebhookTransport]] resource, so the per-call client does not rebuild trust material.
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

  private def pinnedClient(
      network: Network[IO],
      tlsContext: TLSContext[IO],
      target: ResolvedWebhookTarget
  ): Resource[IO, Client[IO]] =
    val sockets = PinnedSocketGroup(
      target.originalHost,
      target.port,
      target.selectedAddress,
      (address, options) => connectExact(network, address, options)
    )
    EmberClientBuilder
      .default[IO]
      .withTLSContext(tlsContext)
      .withSocketGroup(sockets)
      // The outer timeout is the single end-to-end deadline. Ember's narrower defaults must not undercut a gameplay
      // delivery timeout, nor leave DNS and body consumption outside a different timer.
      .withTimeout(Duration.Inf)
      .withIdleConnectionTime(Duration.Inf)
      .withMaxTotal(1)
      .withMaxPerKey(_ => 1)
      .build

  /** http4s 0.23.30 currently resolves fs2-io 3.12, whose exact-IP operation still has the legacy `client` name. The
    * destination is already an `IpAddress`, so this performs no DNS lookup. Use `Network.connect` when fs2-io itself is
    * upgraded to 3.13.
    */
  private def connectExact(
      network: Network[IO],
      address: SocketAddress[IpAddress],
      options: List[SocketOption]
  ): Resource[IO, Socket[IO]] = network.client(address, options)

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

/** Socket group used by one request only. Ember derives TLS parameters from the original request key after this method
  * returns its socket; consequently, connecting to `pinnedAddress` does not replace the hostname used by SNI or
  * endpoint verification.
  */
final private[server] class PinnedSocketGroup private (
    originalHost: String,
    originalPort: Port,
    pinnedAddress: IpAddress,
    connect: (SocketAddress[IpAddress], List[SocketOption]) => Resource[IO, Socket[IO]]
) extends SocketGroup[IO]:

  /** Non-deprecated test seam; Ember invokes the deprecated SocketGroup method because that is the API exposed by
    * http4s 0.23.30.
    */
  private[server] def connectPinned(
      requested: SocketAddress[Host],
      options: List[SocketOption]
  ): Resource[IO, Socket[IO]] =
    if requested.port == originalPort && requested.host.toString.equalsIgnoreCase(originalHost) then
      connect(SocketAddress(pinnedAddress, originalPort), options)
    else Resource.eval(IO.raiseError(new IllegalArgumentException("webhook transport request target changed")))

  override def client(
      to: SocketAddress[Host],
      options: List[SocketOption]
  ): Resource[IO, Socket[IO]] = connectPinned(to, options)

  override def server(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Stream[IO, Socket[IO]] = Stream.raiseError(unsupported)

  override def serverResource(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Resource[IO, (SocketAddress[IpAddress], Stream[IO, Socket[IO]])] =
    Resource.eval(IO.raiseError(unsupported))

  private def unsupported: UnsupportedOperationException =
    new UnsupportedOperationException("webhook transport is client-only")

private[server] object PinnedSocketGroup:
  def apply(
      originalHost: String,
      originalPort: Port,
      pinnedAddress: IpAddress,
      connect: (SocketAddress[IpAddress], List[SocketOption]) => Resource[IO, Socket[IO]]
  ): PinnedSocketGroup =
    new PinnedSocketGroup(originalHost, originalPort, pinnedAddress, connect)
