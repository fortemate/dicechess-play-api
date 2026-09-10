package dicechess.play.server

import cats.data.NonEmptyList
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.Stream
import fs2.io.net.tls.TLSContext
import fs2.io.net.{Network, Socket, SocketOption}
import munit.CatsEffectSuite
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.{Extension, GeneralName, GeneralNames}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.http4s.client.Client
import org.http4s.headers.Location
import org.http4s.{Request, Response, Status, Uri}
import org.typelevel.ci.CIString

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}
import java.math.BigInteger
import java.net.InetAddress
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
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class WebhookTransportSuite extends CatsEffectSuite:
  import WebhookTransport.Outcome

  private val port   = Port.fromInt(8443).getOrElse(fail("test port must be valid"))
  private val ip     = IpAddress.fromString("1.1.1.1").getOrElse(fail("test IP must be valid"))
  private val target = ResolvedWebhookTarget(
    Uri.unsafeFromString("https://bot.example:8443/hook?opaque=value"),
    "bot.example",
    port,
    NonEmptyList.one(ip)
  )

  private def resolved: WebhookTransport.Resolver = _ => IO.pure(Right(target))

  private def withClient(client: Client[IO], resolver: WebhookTransport.Resolver = resolved): WebhookTransport =
    WebhookTransport.from(resolver, _ => Resource.pure(client))

  test("the pinned socket group connects to the validated IP, never the original hostname"):
    for
      connected <- Ref.of[IO, Option[SocketAddress[IpAddress]]](None)
      group = PinnedSocketGroup(
        target.originalHost,
        target.port,
        target.selectedAddress,
        (address, _: List[SocketOption]) =>
          Resource.eval(
            connected.set(Some(address)) *> IO.raiseError[Socket[IO]](RuntimeException("stop after capture"))
          )
      )
      original = SocketAddress(Host.fromString(target.originalHost).getOrElse(fail("valid host")), target.port)
      _       <- group.connectPinned(original, Nil).use(_ => IO.unit).attempt
      address <- connected.get
    yield assertEquals(address, Some(SocketAddress(ip, port)))

  test("the pinned socket group refuses a request whose original authority changed"):
    val group = PinnedSocketGroup(
      target.originalHost,
      target.port,
      target.selectedAddress,
      (_: SocketAddress[IpAddress], _: List[SocketOption]) =>
        Resource.eval(IO.raiseError[Socket[IO]](RuntimeException("must not connect")))
    )
    val changed = SocketAddress(Host.fromString("other.example").getOrElse(fail("valid host")), target.port)
    group
      .connectPinned(changed, Nil)
      .use(_ => IO.unit)
      .attempt
      .map: result =>
        assert(result.isLeft)

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
    val hostname = "bot.example"
    val contexts = tlsContexts(hostname)
    tlsFixture(contexts.server).use: fixture =>
      val pinnedIp       = IpAddress.fromString("127.0.0.1").getOrElse(fail("loopback IP"))
      val pinnedPort     = Port.fromInt(fixture.port).getOrElse(fail("fixture port"))
      val resolvedTarget = ResolvedWebhookTarget(
        Uri.unsafeFromString(s"https://$hostname:${fixture.port}/hook"),
        hostname,
        pinnedPort,
        NonEmptyList.one(pinnedIp)
      )
      val tls       = TLSContext.Builder.forAsync[IO].fromSSLContext(contexts.client)
      val transport = WebhookTransport.from(
        _ => IO.pure(Right(resolvedTarget)),
        target => WebhookTransport.pinnedClient(Network[IO], tls, target)
      )
      for
        outcome  <- transport.postSigned(resolvedTarget.uri.renderString, "secret", "{}", 2.seconds)
        observed <- fixture.observed.timeout(2.seconds)
      yield
        assert(outcome.isInstanceOf[Outcome.Ok], s"trusted hostname certificate must succeed: $outcome")
        assertEquals(observed.sni, List(hostname))
        assertEquals(observed.host, s"$hostname:${fixture.port}")

  test("real TLS rejects a certificate that does not match the original hostname"):
    val contexts = tlsContexts("bot.example")
    tlsFixture(contexts.server).use: fixture =>
      val hostname       = "other.example"
      val pinnedIp       = IpAddress.fromString("127.0.0.1").getOrElse(fail("loopback IP"))
      val pinnedPort     = Port.fromInt(fixture.port).getOrElse(fail("fixture port"))
      val resolvedTarget = ResolvedWebhookTarget(
        Uri.unsafeFromString(s"https://$hostname:${fixture.port}/hook"),
        hostname,
        pinnedPort,
        NonEmptyList.one(pinnedIp)
      )
      val tls       = TLSContext.Builder.forAsync[IO].fromSSLContext(contexts.client)
      val transport = WebhookTransport.from(
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
            Resource
              .make(serve.flatMap(observed.complete).handleError(_ => ()).start)(_.cancel)
              .as(TlsFixture(server.getLocalPort, observed.get))
