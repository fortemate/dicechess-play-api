package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

import java.net.InetAddress

/** The webhook signing and SSRF policy (#104), hermetically: the HMAC against an independently-computed vector, and the
  * address policy against IP literals (no DNS round trips — `InetAddress` parses literals without resolving).
  */
class WebhookSecuritySuite extends CatsEffectSuite:

  test("sign matches an independently computed HMAC-SHA256 vector (python hmac)"):
    val signature = WebhookSecurity.sign("test-webhook-secret", 1752750000L, """{"hello":true}""")
    assertEquals(signature, "5f4fbf105bab278dc6205788389e09884bd554b1f866ca11ccc9ce97ddd9b3f6")

  test("sign is sensitive to every input — secret, timestamp, and body"):
    val base = WebhookSecurity.sign("secret", 1L, "body")
    assertEquals(base, WebhookSecurity.sign("secret", 1L, "body"), "deterministic")
    assertNotEquals(base, WebhookSecurity.sign("other", 1L, "body"))
    assertNotEquals(base, WebhookSecurity.sign("secret", 2L, "body"))
    assertNotEquals(base, WebhookSecurity.sign("secret", 1L, "tampered"))
    // The "ts.body" framing must not be ambiguous: shifting the dot must change the MAC.
    assertNotEquals(WebhookSecurity.sign("secret", 12L, "3.x"), WebhookSecurity.sign("secret", 1L, "23.x"))

  test("verification-v2 request signature and domain-separated proof match the published golden vector"):
    val secret = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    val body   =
      """{"type":"verification","version":2,"bot":{"team":"acme","name":"greedy"},"setupId":"whs_test","revision":"whrev_test","nonce":"AAECAwQFBgcICQoLDA0ODw"}"""
    val bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    assertEquals(
      WebhookSecurity.sign(secret, 1756728000L, bytes),
      "99f91b462250d95ec39f942844622eed87620c07b022c11a0b65f5380d123803"
    )
    assertEquals(
      WebhookSecurity.activationProof(secret, bytes),
      "408d1e2804bdc90036333e6475523904113d009a6e971711e7500f8eb6314947"
    )

  test("randomHex mints distinct values of the requested width"):
    for
      a <- WebhookSecurity.randomHex(32)
      b <- WebhookSecurity.randomHex(32)
    yield
      assertEquals(a.length, 64)
      assert(a.matches("[0-9a-f]{64}"))
      assertNotEquals(a, b)

  // ── URL policy ───────────────────────────────────────────────────────────────

  private def allRejected(urls: List[String]): IO[Unit] =
    urls.traverse_ : url =>
      WebhookSecurity.checkPublicHttps(url).map(r => assert(r.isLeft, s"$url must be rejected, got $r"))

  test("non-https and malformed URLs are rejected before any resolution"):
    allRejected(
      List(
        "http://example.com/hook",
        "ftp://example.com/hook",
        "not a url at all",
        "https://"
      )
    )

  test("userinfo and fragments are rejected before DNS resolution"):
    for
      calls <- Ref.of[IO, Int](0)
      lookup = (_: String) => calls.update(_ + 1).as(List(InetAddress.getByName("1.1.1.1")))
      userInfo <- WebhookSecurity.resolvePublicHttps("https://user:pass@example.com/hook", lookup)
      fragment <- WebhookSecurity.resolvePublicHttps("https://example.com/hook#private", lookup)
      count    <- calls.get
    yield
      assertEquals(userInfo, Left(WebhookUrlFailure.UserInfoForbidden))
      assertEquals(fragment, Left(WebhookUrlFailure.FragmentForbidden))
      assertEquals(count, 0)

  test("one non-public answer rejects the complete DNS result"):
    val lookup = (_: String) => IO.pure(List(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("127.0.0.1")))
    WebhookSecurity
      .resolvePublicHttps("https://bot.example/hook", lookup)
      .map: result =>
        assertEquals(result, Left(WebhookUrlFailure.NonPublicAddress))

  test("resolved target retains the original URI identity and every validated IP"):
    val lookup = (_: String) => IO.pure(List(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("8.8.8.8")))
    WebhookSecurity
      .resolvePublicHttps("https://Bot.Example:8443/hook?opaque=value", lookup)
      .map:
        case Left(failure) => fail(s"public addresses rejected: $failure")
        case Right(target) =>
          assertEquals(target.uri.renderString, "https://Bot.Example:8443/hook?opaque=value")
          assertEquals(target.originalHost, "Bot.Example")
          assertEquals(target.port.value, 8443)
          assertEquals(target.addresses.toList.map(_.toString), List("1.1.1.1", "8.8.8.8"))

  test("every non-public address family from the ADR list is rejected (IP literals, no DNS)"):
    allRejected(
      List(
        "https://127.0.0.1/hook",         // loopback
        "https://10.1.2.3/hook",          // RFC1918
        "https://172.16.0.9/hook",        // RFC1918
        "https://192.168.10.3/hook",      // RFC1918
        "https://169.254.169.254/hook",   // link-local — the cloud metadata endpoint
        "https://0.0.0.0/hook",           // unspecified
        "https://100.64.0.1/hook",        // CGNAT
        "https://255.255.255.255/hook",   // limited broadcast
        "https://224.0.0.1/hook",         // multicast
        "https://[::1]/hook",             // IPv6 loopback
        "https://[fc00::1]/hook",         // IPv6 ULA
        "https://[fd12:3456::1]/hook",    // IPv6 ULA (fd side of fc00::/7)
        "https://[fe80::1]/hook",         // IPv6 link-local
        "https://[::ffff:10.0.0.1]/hook", // IPv4-mapped RFC1918
        "https://localhost/hook"          // resolves to loopback without network
      )
    )

  test("special-use ranges that a modern kernel can still route are rejected too (review)"):
    allRejected(
      List(
        "https://0.0.0.1/hook",           // 0.0.0.0/8 — behaves as local on Linux
        "https://192.0.0.1/hook",         // 192.0.0.0/24 IETF protocol assignments
        "https://192.0.2.1/hook",         // TEST-NET-1
        "https://198.51.100.1/hook",      // TEST-NET-2
        "https://203.0.113.1/hook",       // TEST-NET-3
        "https://198.18.0.1/hook",        // benchmarking 198.18.0.0/15
        "https://198.19.255.1/hook",      // benchmarking, upper half
        "https://192.31.196.1/hook",      // AS112-v4
        "https://192.52.193.1/hook",      // AMT
        "https://192.88.99.2/hook",       // relay anycast
        "https://192.175.48.1/hook",      // direct-delegation AS112
        "https://240.0.0.1/hook",         // 240.0.0.0/4 — routable unicast on Linux since 2019
        "https://[64:ff9b::7f00:1]/hook", // NAT64-mapped loopback
        "https://[100::1]/hook",          // IPv6 discard-only
        "https://[2001:2::1]/hook",       // IPv6 benchmarking inside 2001::/23
        "https://[2001:db8::1]/hook",     // IPv6 documentation range
        "https://[2002:7f00:1::1]/hook",  // 6to4 embedding loopback
        "https://[2620:4f:8000::1]/hook", // direct-delegation AS112-v6
        "https://[3fff::1]/hook",         // IPv6 documentation v2
        "https://[4000::1]/hook"          // outside allocated global-unicast 2000::/3
      )
    )

  test("a public IP literal passes and keeps the parsed Uri"):
    WebhookSecurity
      .checkPublicHttps("https://1.1.1.1/hook")
      .map:
        case Right(uri) => assertEquals(uri.renderString, "https://1.1.1.1/hook")
        case Left(why)  => fail(s"public literal rejected: $why")

  test("isPublic agrees with the classification of well-known literals"):
    def addr(s: String): InetAddress = InetAddress.getByName(s)
    assert(WebhookSecurity.isPublic(addr("1.1.1.1")))
    assert(WebhookSecurity.isPublic(addr("2606:4700:4700::1111")))
    assert(!WebhookSecurity.isPublic(addr("192.168.0.1")))
    assert(!WebhookSecurity.isPublic(addr("fd00::1")))
