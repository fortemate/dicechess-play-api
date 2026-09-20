package dicechess.play.server

import cats.data.NonEmptyList
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.Stream
import fs2.io.net.tls.TLSContext
import fs2.io.net.{Network, WebhookPinnedNetwork}
import munit.CatsEffectSuite
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.{Extension, GeneralName, GeneralNames}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.Location
import org.http4s.{Request, Response, Status, Uri}
import org.typelevel.ci.CIString

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}
import java.math.BigInteger
import java.net.{InetAddress, ServerSocket}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{KeyPairGenerator, KeyStore, SecureRandom}
import java.time.Instant
import java.util.Date
import javax.net.ssl.{
  ExtendedSSLSession,
  KeyManagerFactory,
  SNIHostName,
  SSLContext,
  SSLServerSocket,
  SSLSocket,
  TrustManagerFactory
}
import scala.annotation.nowarn
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class WebhookTransportSuite extends CatsEffectSuite:
  import WebhookTransport.Outcome

  /** `.invalid` is reserved by RFC 6761 to never resolve. The network-level tests below pin such a name to loopback: a
    * delivery can reach the fixture ONLY through the pinning, so a pinning that silently degrades into ordinary DNS
    * (the http4s 0.23.34 `withSocketGroup` no-op, #193) fails these tests instead of passing them.
    */
  private val hostname = "bot.invalid"
  private val loopback = IpAddress.fromString("127.0.0.1").getOrElse(fail("loopback IP"))

  private val port   = Port.fromInt(8443).getOrElse(fail("test port must be valid"))
  private val ip     = IpAddress.fromString("1.1.1.1").getOrElse(fail("test IP must be valid"))
  private val target = ResolvedWebhookTarget(
    Uri.unsafeFromString(s"https://$hostname:8443/hook?opaque=value"),
    hostname,
    port,
    NonEmptyList.one(ip)
  )

  private def resolved: WebhookTransport.Resolver = _ => IO.pure(Right(target))

  private def withClient(client: Client[IO], resolver: WebhookTransport.Resolver = resolved): WebhookTransport =
    WebhookTransport.from(resolver, _ => Resource.pure(client))

  test("the pinned network connects to the validated IP, never the original hostname"):
    loopbackListener.use: listenerPort =>
      val pinned    = WebhookPinnedNetwork(Network[IO], hostname, listenerPort, loopback)
      val requested = SocketAddress(Host.fromString(hostname).getOrElse(fail("valid host")), listenerPort)
      pinned
        .connect(requested)
        .use(socket => IO.pure(socket.peerAddress))
        .map(peer => assertEquals(peer, SocketAddress(loopback, listenerPort)))

  test("the pinned network refuses a request whose original authority changed"):
    val pinned  = WebhookPinnedNetwork(Network[IO], hostname, port, loopback)
    val changed = SocketAddress(Host.fromString("other.invalid").getOrElse(fail("valid host")), port)
    pinned
      .connect(changed)
      .use_
      .attempt
      .map: result =>
        assert(result.left.exists(_.isInstanceOf[IllegalArgumentException]), s"unexpected: $result")

  // fs2 3.13 deprecated the socket-group entry points; they still exist on `Network`, so Ember (or a future caller)
  // could still reach them. The pinned network must refuse them like every other non-connect capability, and proving
  // that means calling the deprecated methods on purpose. The suppression is scoped to this one test.
  @nowarn("cat=deprecation")
  private def serverSideCapabilities(pinned: Network[IO]): List[(String, Resource[IO, Any])] =
    List(
      "bind"                -> pinned.bind(),
      "bindDatagramSocket"  -> pinned.bindDatagramSocket(),
      "socketGroup"         -> pinned.socketGroup(),
      "datagramSocketGroup" -> pinned.datagramSocketGroup()
    )

  test("the pinned network is client-only"):
    val pinned = WebhookPinnedNetwork(Network[IO], hostname, port, loopback)
    serverSideCapabilities(pinned).traverse_ { (name, capability) =>
      capability.use_.attempt.map { result =>
        assert(result.left.exists(_.isInstanceOf[UnsupportedOperationException]), s"$name: unexpected $result")
      }
    }

  test("one delivery resolves once and cannot switch to a later rebinding answer"):
    val rebound = target.copy(addresses = NonEmptyList.one(IpAddress.fromString("127.0.0.1").getOrElse(fail("IP"))))
    for
      calls     <- Ref.of[IO, Int](0)
      connected <- Ref.of[IO, Option[IpAddress]](None)
      resolver      = (_: String) => calls.getAndUpdate(_ + 1).map(n => Right(if n == 0 then target else rebound))
      clientFactory = (resolved: ResolvedWebhookTarget) =>
        Resource
          .eval(connected.set(Some(resolved.selectedAddress)))
          .as(Client[IO](_ => Resource.pure(Response[IO](Status.Ok))))
      outcome <- WebhookTransport
        .from(resolver, clientFactory)
        .postSigned(target.uri.renderString, "secret", "{}", 1.second)
      count   <- calls.get
      address <- connected.get
    yield
      assert(outcome.isInstanceOf[Outcome.Ok])
      assertEquals(count, 1, "a delivery must have exactly one policy/DNS result")
      assertEquals(address, Some(ip), "the connection must use the address from that exact result")

  test("real TLS keeps the original hostname for SNI, Host, and certificate verification"):
    val contexts = tlsContexts(hostname)
    tlsFixture(contexts.server).use: fixture =>
      val resolvedTarget = loopbackTarget(hostname, fixture.port)
      val tls            = TLSContext.Builder.forAsync[IO].fromSSLContext(contexts.client)
      val transport      = WebhookTransport.from(
        _ => IO.pure(Right(resolvedTarget)),
        target => WebhookTransport.pinnedClient(Network[IO], tls, target)
      )
      for
        outcome <- transport.postSigned(resolvedTarget.uri.renderString, "secret", "{}", 2.seconds)
        // Assert the delivery before waiting on the fixture: an unpinned client never reaches loopback, and the
        // outcome names that failure more precisely than the fixture's timeout would.
        _        <- IO(assert(outcome.isInstanceOf[Outcome.Ok], s"pinned delivery must reach the fixture: $outcome"))
        observed <- fixture.observed.timeout(2.seconds)
      yield
        assertEquals(observed.sni, List(hostname))
        assertEquals(observed.host, s"$hostname:${fixture.port}")

  test("control: the same delivery through an unpinned Ember client cannot reach the fixture"):
    // Proves the previous test is load-bearing: with Ember left to resolve `bot.invalid` itself, the fixture is never
    // reached. If this test ever starts passing with Ok, the hostname resolves and the pinning tests prove nothing.
    val contexts = tlsContexts(hostname)
    tlsFixture(contexts.server).use: fixture =>
      val resolvedTarget = loopbackTarget(hostname, fixture.port)
      val tls            = TLSContext.Builder.forAsync[IO].fromSSLContext(contexts.client)
      val transport      = WebhookTransport.from(
        _ => IO.pure(Right(resolvedTarget)),
        _ => EmberClientBuilder.default[IO].withTLSContext(tls).build
      )
      transport
        .postSigned(resolvedTarget.uri.renderString, "secret", "{}", 2.seconds)
        .map(outcome =>
          assert(!outcome.isInstanceOf[Outcome.Ok], s"unpinned delivery must not reach loopback: $outcome")
        )

  test("real TLS rejects a certificate that does not match the original hostname"):
    val contexts = tlsContexts(hostname)
    tlsFixture(contexts.server).use: fixture =>
      val resolvedTarget = loopbackTarget("other.invalid", fixture.port)
      val tls            = TLSContext.Builder.forAsync[IO].fromSSLContext(contexts.client)
      val transport      = WebhookTransport.from(
        _ => IO.pure(Right(resolvedTarget)),
        target => WebhookTransport.pinnedClient(Network[IO], tls, target)
      )
      transport
        .postSigned(resolvedTarget.uri.renderString, "secret", "{}", 2.seconds)
        .map(outcome => assertEquals(outcome, Outcome.Unreachable))

  test("signed POST keeps the original URI and signs the exact bytes sent"):
    final case class Captured(uri: Uri, body: Array[Byte], timestamp: String, signature: String)

    val payload = Array[Byte](0x7b, 0x22, 0x78, 0x22, 0x3a, 0xc3.toByte, 0x28, 0x7d)
    for
      captured <- Ref.of[IO, Option[Captured]](None)
      client = Client[IO] { request =>
        Resource.eval(
          request.body.compile.to(Array).flatMap { bytes =>
            val timestamp = header(request, WebhookSecurity.TimestampHeader)
            val signature = header(request, WebhookSecurity.SignatureHeader)
            captured.set(Some(Captured(request.uri, bytes, timestamp, signature))) *>
              IO.pure(Response[IO](Status.Ok).withEntity("accepted"))
          }
        )
      }
      outcome <- withClient(client).postSigned("ignored by injected resolver", "secret", payload, 1.second)
      sent    <- captured.get
    yield
      outcome match
        case Outcome.Ok(bytes) => assertEquals(new String(bytes, UTF_8), "accepted")
        case other             => fail(s"unexpected outcome: $other")
      val request = sent.getOrElse(fail("request was not captured"))
      assertEquals(request.uri, target.uri, "the hostname URI must not be rewritten to the pinned IP")
      assertEquals(request.body.toList, payload.toList)
      assertEquals(
        request.signature,
        WebhookSecurity.sign("secret", request.timestamp.toLong, payload),
        "signature must bind the exact bytes on the wire"
      )

  test("the end-to-end timeout includes DNS resolution"):
    val neverResolve: WebhookTransport.Resolver = _ => IO.never
    val unused = Client[IO](_ => Resource.eval(IO.raiseError(RuntimeException("client must not run"))))
    withClient(unused, neverResolve)
      .postSigned("https://bot.example/hook", "secret", "{}", 30.millis)
      .map(outcome => assertEquals(outcome, Outcome.TimedOut))

  test("response reads are capped at 65536 bytes"):
    val endless = Client[IO](_ => Resource.pure(Response[IO](Status.Ok, body = Stream.constant(0.toByte).covary[IO])))
    withClient(endless)
      .postSigned("https://bot.example/hook", "secret", "{}", 1.second)
      .map(outcome => assertEquals(outcome, Outcome.OversizedBody))

  test("an oversized response releases the client with an error so Ember does not drain the remainder"):
    for
      errored <- Ref.of[IO, Boolean](false)
      client = Client[IO] { _ =>
        Resource.makeCase(
          IO.pure(Response[IO](Status.Ok, body = Stream.constant(0.toByte).covary[IO]))
        ) { (_, exitCase) =>
          errored.set(exitCase match
            case Resource.ExitCase.Errored(_) => true
            case _                            => false)
        }
      }
      outcome   <- withClient(client).postSigned("https://bot.example/hook", "secret", "{}", 1.second)
      errorExit <- errored.get
    yield
      assertEquals(outcome, Outcome.OversizedBody)
      assert(errorExit, "the response resource must not see a successful exit and drain the unbounded remainder")

  test("redirects are returned as status outcomes and never followed"):
    for
      calls <- Ref.of[IO, Int](0)
      client = Client[IO] { _ =>
        Resource.eval(
          calls
            .update(_ + 1)
            .as(
              Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString("https://other.example/hook")))
            )
        )
      }
      outcome <- withClient(client).postSigned("https://bot.example/hook", "secret", "{}", 1.second)
      count   <- calls.get
    yield
      assertEquals(outcome, Outcome.HttpStatus(302))
      assertEquals(count, 1)

  test("transport exceptions collapse to a safe enum without retaining raw details"):
    val failed = Client[IO](_ => Resource.eval(IO.raiseError(RuntimeException("sensitive transport detail"))))
    withClient(failed)
      .postSigned("https://bot.example/hook", "secret", "{}", 1.second)
      .map(outcome => assertEquals(outcome, Outcome.Unreachable))

  /** A policy result that names `host` but was validated to loopback — the shape a rebinding-safe delivery must honour.
    */
  private def loopbackTarget(host: String, fixturePort: Int): ResolvedWebhookTarget =
    ResolvedWebhookTarget(
      Uri.unsafeFromString(s"https://$host:$fixturePort/hook"),
      host,
      Port.fromInt(fixturePort).getOrElse(fail("fixture port")),
      NonEmptyList.one(loopback)
    )

  /** A bare loopback listener; connecting to it proves the TCP destination without any HTTP or TLS in the way. */
  private def loopbackListener: Resource[IO, Port] =
    Resource
      .make(IO.blocking(new ServerSocket(0, 1, InetAddress.getLoopbackAddress)))(s => IO.blocking(s.close()))
      .map(s => Port.fromInt(s.getLocalPort).getOrElse(fail("listener port")))

  private def header(request: Request[IO], name: String): String =
    request.headers.get(CIString(name)).map(_.head.value).getOrElse(fail(s"missing $name"))

  final private case class TlsContexts(server: SSLContext, client: SSLContext)
  final private case class ObservedTls(sni: List[String], host: String)
  final private case class TlsFixture(port: Int, observed: IO[ObservedTls])

  private def tlsContexts(hostname: String): TlsContexts =
    val keys = KeyPairGenerator.getInstance("RSA")
    keys.initialize(2048)
    val pair    = keys.generateKeyPair()
    val subject = X500Name(s"CN=$hostname")
    val now     = Instant.now()
    val builder = JcaX509v3CertificateBuilder(
      subject,
      BigInteger.valueOf(now.toEpochMilli),
      Date.from(now.minusSeconds(60)),
      Date.from(now.plusSeconds(3600)),
      subject,
      pair.getPublic
    )
    builder.addExtension(
      Extension.subjectAlternativeName,
      false,
      GeneralNames(GeneralName(GeneralName.dNSName, hostname))
    )
    val signer      = JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate)
    val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
    val password    = "fixture-only".toCharArray

    val keysStore = KeyStore.getInstance("PKCS12")
    keysStore.load(null, password)
    keysStore.setKeyEntry("fixture", pair.getPrivate, password, Array(certificate))
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagers.init(keysStore, password)

    val trustStore = KeyStore.getInstance("PKCS12")
    trustStore.load(null, password)
    trustStore.setCertificateEntry("fixture", certificate)
    val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagers.init(trustStore)

    val server = SSLContext.getInstance("TLS")
    server.init(keyManagers.getKeyManagers, null, SecureRandom())
    val client = SSLContext.getInstance("TLS")
    client.init(null, trustManagers.getTrustManagers, SecureRandom())
    TlsContexts(server, client)

  private def tlsFixture(context: SSLContext): Resource[IO, TlsFixture] =
    Resource
      .make(
        IO.blocking(
          context.getServerSocketFactory
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress)
            .asInstanceOf[SSLServerSocket]
        )
      )(server => IO.blocking(server.close()).handleError(_ => ()))
      .flatMap: server =>
        Resource
          .eval(Deferred[IO, ObservedTls])
          .flatMap: observed =>
            val serve = IO.blocking:
              val socket = server.accept().asInstanceOf[SSLSocket]
              try
                socket.startHandshake()
                val session = socket.getSession.asInstanceOf[ExtendedSSLSession]
                val sni     = session.getRequestedServerNames.asScala.collect:
                  case name: SNIHostName => name.getAsciiName
                val reader = BufferedReader(InputStreamReader(socket.getInputStream, UTF_8))
                val lines  = Iterator
                  .continually(reader.readLine())
                  .takeWhile(line => line != null && line.nonEmpty)
                  .toList
                val host = lines
                  .find(_.regionMatches(true, 0, "Host:", 0, 5))
                  .map(_.drop(5).trim)
                  .getOrElse("")
                val writer = OutputStreamWriter(socket.getOutputStream, UTF_8)
                writer.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
                writer.flush()
                ObservedTls(sni.toList, host)
              finally socket.close()
            // Close the listener BEFORE cancelling: `accept()` is a blocking call that only returns when a client
            // connects or the socket closes, and a cancelled fiber waits for it. A test in which nothing connects
            // (the unpinned control) would otherwise hang the fixture's release until munit's timeout.
            Resource
              .make(serve.flatMap(observed.complete).handleError(_ => ()).start)(fiber =>
                IO.blocking(server.close()).handleError(_ => ()) *> fiber.cancel
              )
              .as(TlsFixture(server.getLocalPort, observed.get))
