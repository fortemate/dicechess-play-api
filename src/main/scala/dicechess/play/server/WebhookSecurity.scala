package dicechess.play.server

import cats.data.NonEmptyList
import cats.effect.IO
import com.comcast.ip4s.{IpAddress, Port}
import org.http4s.Uri

import java.net.InetAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The security primitives of webhook delivery (F.2, #104; design: ADR-0013) — pure of any game or store concern so
  * both are testable without a network:
  *
  *   - '''Signing''': every outbound POST carries `X-DiceChess-Signature: HMAC-SHA256(secret, "ts.body")` (hex) and
  *     `X-DiceChess-Timestamp: ts` (epoch seconds). The bot recomputes the MAC with its copy of the secret and rejects
  *     stale timestamps (±5 minutes is the documented window) — authenticity and replay resistance in two headers.
  *   - '''SSRF guard''': play-api POSTs to owner-supplied URLs, which is an outbound request forgery surface — the
  *     policy is HTTPS-only to a host NONE of whose freshly-resolved addresses is non-public (loopback, RFC1918,
  *     link-local — the `169.254.169.254` metadata endpoint lives there — IPv6 ULA, CGNAT, multicast/broadcast,
  *     unspecified). Resolution happens AT SEND TIME on every delivery, never cached. [[WebhookTransport]] connects to
  *     an address from that exact validated result while retaining this URI's hostname for HTTP Host, TLS SNI, and
  *     certificate verification. That closes the policy-check/connect DNS-rebinding window instead of assuming TLS
  *     alone closes it. Redirects are not followed, so the check cannot be laundered through a public 302.
  */
object WebhookSecurity:

  val SignatureHeader = "X-DiceChess-Signature"
  val TimestampHeader = "X-DiceChess-Timestamp"

  /** Domain separation for the staged activation proof (ADR-004). Keeping the newline in one constant avoids a subtly
    * incompatible implementation at either call site.
    */
  private val ActivationProofPrefix = "dicechess-webhook-activate-v2\n".getBytes(UTF_8)

  /** Hex HMAC-SHA256 of `"<timestampEpochSeconds>.<body>"` under `secret` — the `X-DiceChess-Signature` value. */
  def sign(secret: String, timestampEpochSeconds: Long, body: String): String =
    sign(secret, timestampEpochSeconds, body.getBytes(UTF_8))

  /** Byte-preserving form used by verification-v2: the signature binds the exact body sent on the wire, without a
    * decode/re-encode step.
    */
  def sign(secret: String, timestampEpochSeconds: Long, rawBody: Array[Byte]): String =
    hmac(secret, s"$timestampEpochSeconds.".getBytes(UTF_8) ++ rawBody)

  /** Lowercase HMAC-SHA256 proof returned by a verification-v2 endpoint. The exact raw request bytes are bound to the
    * proof; callers must never parse and re-serialize the JSON before invoking this function.
    */
  def activationProof(secret: String, rawRequestBody: Array[Byte]): String =
    hmac(secret, ActivationProofPrefix ++ rawRequestBody)

  /** Constant-time comparison for secret-derived lowercase hex values. Length is included in the comparison without an
    * early return so a malformed response cannot turn proof checking into a useful timing oracle.
    */
  def constantTimeEquals(expected: String, supplied: String): Boolean =
    MessageDigest.isEqual(expected.getBytes(UTF_8), supplied.getBytes(UTF_8))

  private def hmac(secret: String, bytes: Array[Byte]): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(bytes).map(b => f"${b & 0xff}%02x").mkString

  /** `n` random bytes, hex-encoded — webhook secrets (32 bytes) and verification nonces (16 bytes). */
  def randomHex(n: Int): IO[String] = IO:
    val bytes = new Array[Byte](n)
    SecureRandom().nextBytes(bytes)
    bytes.map(b => f"${b & 0xff}%02x").mkString

  /** At least 128 random bits, encoded as unpadded base64url for verification-v2. */
  def randomBase64Url(n: Int): IO[String] = IO:
    val bytes = new Array[Byte](n)
    SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

  /** The production URL policy and the addresses it validated in the same DNS lookup. The URI deliberately retains its
    * original hostname: [[WebhookTransport]] changes only the TCP destination, never the HTTP/TLS identity.
    */
  def resolvePublicHttps(url: String): IO[Either[WebhookUrlFailure, ResolvedWebhookTarget]] =
    resolvePublicHttps(url, systemLookup)

  /** Test seam for deterministic DNS answers, including mixed public/private rebinding responses. */
  private[server] def resolvePublicHttps(
      url: String,
      lookup: String => IO[List[InetAddress]]
  ): IO[Either[WebhookUrlFailure, ResolvedWebhookTarget]] =
    Uri.fromString(url) match
      case Left(_)    => IO.pure(Left(WebhookUrlFailure.InvalidUrl))
      case Right(uri) =>
        if !uri.scheme.contains(Uri.Scheme.https) then IO.pure(Left(WebhookUrlFailure.HttpsRequired))
        else if uri.userInfo.nonEmpty then IO.pure(Left(WebhookUrlFailure.UserInfoForbidden))
        else if uri.fragment.nonEmpty then IO.pure(Left(WebhookUrlFailure.FragmentForbidden))
        else
          (uri.host.map(_.value), Port.fromInt(uri.port.getOrElse(443))) match
            case (None, _)                => IO.pure(Left(WebhookUrlFailure.MissingHost))
            case (_, None)                => IO.pure(Left(WebhookUrlFailure.InvalidPort))
            case (Some(host), Some(port)) =>
              lookup(host).attempt.map:
                case Left(_)          => Left(WebhookUrlFailure.HostDoesNotResolve(host))
                case Right(addresses) =>
                  NonEmptyList.fromList(addresses) match
                    case None => Left(WebhookUrlFailure.HostDoesNotResolve(host))
                    case Some(resolved) if !resolved.forall(isPublic) =>
                      // Do not name the rejected address: a split-horizon resolver must not become an internal-DNS
                      // oracle through caller-visible policy errors.
                      Left(WebhookUrlFailure.NonPublicAddress)
                    case Some(resolved) =>
                      Right(
                        ResolvedWebhookTarget(
                          uri = uri,
                          originalHost = host,
                          port = port,
                          addresses = resolved.map(IpAddress.fromInetAddress)
                        )
                      )

  /** Compatibility view used by the existing bot-token surface. New outbound calls should keep the resolved target and
    * pass it to [[WebhookTransport]], rather than discard it and trigger a second DNS lookup.
    */
  def checkPublicHttps(url: String): IO[Either[String, Uri]] =
    resolvePublicHttps(url).map(_.left.map(_.message).map(_.uri))

  private def systemLookup(host: String): IO[List[InetAddress]] =
    IO.interruptibleMany(InetAddress.getAllByName(host).toList)

  /** Whether an address is routable-public. Java's `isSiteLocalAddress` covers RFC1918 for IPv4 (and the deprecated
    * IPv6 fec0::/10); the rest of the special-use registries need their own checks — including ranges that LOOK
    * unroutable but aren't on a modern Linux host (review): the kernel happily treats 0.0.0.0/8 as local and, since
    * 2019, routes 240.0.0.0/4 as ordinary unicast, so "reserved" is not "unreachable". IPv4-mapped IPv6 literals need
    * no case of their own: `InetAddress` parses `::ffff:a.b.c.d` into an `Inet4Address`, so they take the IPv4 branch
    * naturally.
    */
  private[server] def isPublic(address: InetAddress): Boolean =
    val bytes      = address.getAddress
    val specialUse = bytes.length match
      case 4  => isSpecialUseIpv4(bytes)
      case 16 => isSpecialUseIpv6(bytes)
      case _  => false
    !(address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
      address.isSiteLocalAddress || address.isMulticastAddress || specialUse)

  private def isSpecialUseIpv4(bytes: Array[Byte]): Boolean =
    def b(i: Int): Int = bytes(i) & 0xff
    val zeroNet        = b(0) == 0                                  // 0.0.0.0/8 — local on Linux
    val cgnat          = b(0) == 100 && (b(1) & 0xc0) == 64         // 100.64.0.0/10
    val ietfProtocol   = b(0) == 192 && b(1) == 0 && b(2) == 0      // 192.0.0.0/24
    val testNets       = (b(0) == 192 && b(1) == 0 && b(2) == 2) || // 192.0.2.0/24 TEST-NET-1
      (b(0) == 198 && b(1) == 51 && b(2) == 100) || // 198.51.100.0/24 TEST-NET-2
      (b(0) == 203 && b(1) == 0 && b(2) == 113) // 203.0.113.0/24 TEST-NET-3
    val benchmarking   = b(0) == 198 && (b(1) & 0xfe) == 18         // 198.18.0.0/15
    val as112          = (b(0) == 192 && b(1) == 31 && b(2) == 196) ||
      (b(0) == 192 && b(1) == 175 && b(2) == 48)
    val amt          = b(0) == 192 && b(1) == 52 && b(2) == 193
    val relayAnycast = b(0) == 192 && b(1) == 88 && b(2) == 99
    val classE       = (b(0) & 0xf0) == 0xf0 // 240.0.0.0/4, broadcast included
    zeroNet || cgnat || ietfProtocol || testNets || benchmarking || as112 || amt || relayAnycast || classE

  private def isSpecialUseIpv6(bytes: Array[Byte]): Boolean =
    def b(i: Int): Int = bytes(i) & 0xff
    // IPv6 public destinations must be global unicast (2000::/3), then must not belong to any IANA special-purpose
    // block inside that space. This also rejects translation, discard-only, ULA, link-local and future-reserved
    // top-level space without relying on platform-specific Inet6Address classifiers.
    val notGlobalUnicast = (b(0) & 0xe0) != 0x20
    val ietfAssignments  = b(0) == 0x20 && b(1) == 0x01 && (b(2) & 0xfe) == 0           // 2001::/23
    val documentation    = b(0) == 0x20 && b(1) == 0x01 && b(2) == 0x0d && b(3) == 0xb8 // 2001:db8::/32
    val sixToFour        = b(0) == 0x20 && b(1) == 0x02                                 // 2002::/16
    val as112            = b(0) == 0x26 && b(1) == 0x20 && b(2) == 0x00 && b(3) == 0x4f && b(4) == 0x80 &&
      b(5) == 0x00 // 2620:4f:8000::/48
    val documentationV2  = b(0) == 0x3f && b(1) == 0xff && (b(2) & 0xf0) == 0           // 3fff::/20
    notGlobalUnicast || ietfAssignments || documentation || sixToFour || as112 || documentationV2

/** One HTTPS destination whose DNS result has passed the complete public-address policy. `uri` and `originalHost`
  * remain unchanged so an exact-IP connection can still authenticate the intended virtual host.
  */
final case class ResolvedWebhookTarget(
    uri: Uri,
    originalHost: String,
    port: Port,
    addresses: NonEmptyList[IpAddress]
):
  def selectedAddress: IpAddress = addresses.head

/** Caller-safe URL-policy failures. These contain neither raw transport exceptions nor resolved addresses. */
enum WebhookUrlFailure:
  case InvalidUrl
  case HttpsRequired
  case UserInfoForbidden
  case FragmentForbidden
  case MissingHost
  case InvalidPort
  case HostDoesNotResolve(host: String)
  case NonPublicAddress

  def message: String = this match
    case InvalidUrl               => "not a valid URL"
    case HttpsRequired            => "webhook URL must use https"
    case UserInfoForbidden        => "webhook URL must not contain userinfo"
    case FragmentForbidden        => "webhook URL must not contain a fragment"
    case MissingHost              => "webhook URL must have a host"
    case InvalidPort              => "webhook URL has an invalid port"
    case HostDoesNotResolve(host) => s"host does not resolve: $host"
    case NonPublicAddress         => "host resolves to a non-public address"
