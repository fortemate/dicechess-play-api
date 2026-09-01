package dicechess.play.server

import cats.data.NonEmptyList
import cats.effect.{IO, Ref, Resource}
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.Stream
import fs2.io.net.{Socket, SocketOption}
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.headers.Location
import org.http4s.{Request, Response, Status, Uri}
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*

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
