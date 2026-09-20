package fs2.io.net

import cats.effect.{IO, Resource}
import com.comcast.ip4s.{GenSocketAddress, IpAddress, Port, SocketAddress}
import fs2.io.net.Network.AsyncNetwork

import java.util.concurrent.ThreadFactory

/** DNS-rebinding-safe connection pinning for one outbound webhook request, expressed as the `Network[IO]` that Ember
  * takes from implicit scope. It belongs to dicechess-play-api, not to fs2, despite the package it is declared in.
  *
  * '''Why this file is declared in package `fs2.io.net`.''' http4s 0.23.34 (http4s/http4s#7708, the fs2 3.13 bump)
  * turned `EmberClientBuilder.withSocketGroup` into a no-op that returns `this`; Ember now opens every connection
  * through `Network[F].connect`. fs2 3.13+ declares `Network` `sealed`, keeps its extension points (`UnsealedNetwork`,
  * `AsyncNetwork`) `private[fs2]`, and `Network.forAsyncAndDns` discards the `Dns` it is handed, so no supported hook
  * is left to redirect a connection to a pre-validated address. Declaring this class inside `fs2.io.net` is the
  * narrowest way to reach the package-private extension point.
  *
  * This is a compile-time dependency on fs2 internals, and that is the point: if fs2 renames or removes `AsyncNetwork`,
  * this file stops compiling. It cannot degrade into a silent no-op the way the deprecated builder method did.
  *
  * Semantics are those of the retired socket group. Ember derives HTTP `Host`, TLS SNI and endpoint identification from
  * the original request authority (`Util.mkClientTLSParameters` receives the hostname, not the socket it got), so only
  * the TCP destination changes. Every other capability of a `Network` is refused: this instance exists to make exactly
  * one kind of connection, and any other use is a programming error worth failing loudly.
  */
final class WebhookPinnedNetwork private (
    underlying: Network[IO],
    originalHost: String,
    originalPort: Port,
    pinnedAddress: IpAddress
) extends AsyncNetwork[IO]:

  override def connect(address: GenSocketAddress, options: List[SocketOption]): Resource[IO, Socket[IO]] =
    address match
      case requested: SocketAddress[?] if matchesOriginal(requested) =>
        underlying.connect(SocketAddress(pinnedAddress, originalPort), options)
      case _ =>
        Resource.eval(IO.raiseError(new IllegalArgumentException("webhook transport request target changed")))

  private def matchesOriginal(requested: SocketAddress[?]): Boolean =
    requested.port == originalPort && requested.host.toString.equalsIgnoreCase(originalHost)

  override def bind(address: GenSocketAddress, options: List[SocketOption]): Resource[IO, ServerSocket[IO]] =
    Resource.eval(IO.raiseError(unsupported))

  override def bindDatagramSocket(
      address: GenSocketAddress,
      options: List[SocketOption]
  ): Resource[IO, DatagramSocket[IO]] =
    Resource.eval(IO.raiseError(unsupported))

  override def socketGroup(threadCount: Int, threadFactory: ThreadFactory): Resource[IO, SocketGroup[IO]] =
    Resource.eval(IO.raiseError(unsupported))

  override def datagramSocketGroup(threadFactory: ThreadFactory): Resource[IO, DatagramSocketGroup[IO]] =
    Resource.eval(IO.raiseError(unsupported))

  private def unsupported: UnsupportedOperationException =
    new UnsupportedOperationException("webhook transport is client-only")

object WebhookPinnedNetwork:

  /** A `Network[IO]` that connects `originalHost:originalPort` to `pinnedAddress` and refuses everything else. */
  def apply(
      underlying: Network[IO],
      originalHost: String,
      originalPort: Port,
      pinnedAddress: IpAddress
  ): Network[IO] =
    new WebhookPinnedNetwork(underlying, originalHost, originalPort, pinnedAddress)
