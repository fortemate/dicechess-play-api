package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.headers.Origin
import org.http4s.implicits.*
import org.http4s.{Header, Headers, Method, Request, Status, Uri}
import org.typelevel.ci.*

class CorsSuite extends munit.CatsEffectSuite:

  private def app(spec: String) = Cors.policy(spec).apply(HealthRoutes("1.2.3").orNotFound)

  /** The browser-supplied `Origin` request header, set raw so the middleware parses it as a real request would. */
  private def origin(value: String): Header.Raw = Header.Raw(ci"Origin", value)

  private def allowOrigin(headers: Headers): Option[String] =
    headers.get(ci"Access-Control-Allow-Origin").map(_.head.value)

  /** A comma-separated CORS list header as a lower-cased set — the middleware chooses the casing and the order, and
    * neither is part of what a browser checks.
    */
  private def headerValues(headers: Headers, name: CIString): Set[String] =
    headers
      .get(name)
      .fold(Set.empty[String])(_.head.value.split(',').iterator.map(_.trim.toLowerCase).filter(_.nonEmpty).toSet)

  private def parsedOrigin(value: String): Origin =
    Origin.parse(value).fold(error => fail(error.sanitized), identity)

  test("the parsed allow-list is reusable as an exact server-side origin guard"):
    val allowed = Cors.allowedOrigins(" https://play.jc.id.lv, http://localhost:5173, https://play.jc.id.lv ")
    assert(allowed.isExplicitlyConfigured)
    assert(allowed.allows(parsedOrigin("https://play.jc.id.lv")))
    assert(allowed.allows(parsedOrigin("http://localhost:5173")))
    assert(!allowed.allows(parsedOrigin("https://evil.example")))

    val absent = Cors.allowedOrigins(" ,  ")
    assert(!absent.isExplicitlyConfigured)
    assert(!absent.allows(parsedOrigin("https://play.jc.id.lv")))

  test("opaque Origin null is never a trusted credentialed origin"):
    val opaqueOnly = Cors.allowedOrigins("null")
    val mixed      = Cors.allowedOrigins("null, https://play.jc.id.lv")

    assert(!opaqueOnly.isExplicitlyConfigured, "an opaque origin must not enable credentialed CORS or session routes")
    assert(!opaqueOnly.allows(Origin.Null))
    assert(mixed.isExplicitlyConfigured)
    assert(mixed.allows(parsedOrigin("https://play.jc.id.lv")))
    assert(!mixed.allows(Origin.Null))

  test("a normal GET from any origin gets Access-Control-Allow-Origin: * by default"):
    app("")
      .run(Request[IO](Method.GET, uri"/health").putHeaders(origin("https://play.jc.id.lv")))
      .map(resp => assertEquals(allowOrigin(resp.headers), Some("*")))

  test("an OPTIONS preflight is answered with the CORS headers"):
    val preflight = Request[IO](Method.OPTIONS, uri"/games")
      .putHeaders(origin("https://play.jc.id.lv"), Header.Raw(ci"Access-Control-Request-Method", "POST"))
    app("")
      .run(preflight)
      .map: resp =>
        assert(
          resp.status == Status.Ok || resp.status == Status.NoContent,
          s"unexpected preflight status ${resp.status}"
        )
        assertEquals(allowOrigin(resp.headers), Some("*"))
        assert(
          resp.headers.get(ci"Access-Control-Allow-Methods").isDefined,
          "preflight is missing Access-Control-Allow-Methods"
        )

  /** The regression behind a production outage: with `PLAY_CORS_ORIGINS` set, every browser POST that carries
    * `content-type: application/json` is preflighted, and the preflight came back with NO `Access-Control-*` headers at
    * all — so the browser blocked starting a game, creating a seek, and recording a finished game, while plain GETs
    * kept working and made the API look healthy. The credential-less policy above was covered; this branch was not.
    */
  test("an OPTIONS preflight is answered with the CORS headers under an allow-list too"):
    val preflight = Request[IO](Method.OPTIONS, uri"/lobby/play-bot")
      .putHeaders(
        origin("https://play.jc.id.lv"),
        Header.Raw(ci"Access-Control-Request-Method", "POST"),
        Header.Raw(ci"Access-Control-Request-Headers", "content-type")
      )
    app("https://play.jc.id.lv")
      .run(preflight)
      .map: resp =>
        // Assert the CONTENT, not merely the presence: a preflight that answered
        // `Access-Control-Allow-Methods: GET` would satisfy "the header exists" and still leave every
        // POST blocked, which is the outage this test exists to catch.
        assert(
          resp.status == Status.Ok || resp.status == Status.NoContent,
          s"unexpected preflight status ${resp.status}"
        )
        assertEquals(allowOrigin(resp.headers), Some("https://play.jc.id.lv"))
        assert(
          headerValues(resp.headers, ci"Access-Control-Allow-Methods").contains("post"),
          s"preflight does not allow POST: ${resp.headers.get(ci"Access-Control-Allow-Methods")}"
        )
        assert(
          headerValues(resp.headers, ci"Access-Control-Allow-Headers").contains("content-type"),
          s"preflight does not allow content-type: ${resp.headers.get(ci"Access-Control-Allow-Headers")}"
        )
        assertEquals(
          resp.headers.get(ci"Access-Control-Allow-Credentials").map(_.head.value),
          Some("true"),
          "a credentialed policy must say so on the preflight, not only on the actual response"
        )

  /** `CredentialedMethods` is a closed whitelist, so a route reached with a verb absent from it is browser-unusable
    * while every sibling works — and nothing server-side complains, because the refusal happens in the browser. That is
    * worst for a **session-gated** route: its cookie comes from the browser sign-in flow, so a browser is the only
    * client that holds one in practice, and `PUT /admin/bots/…/description` shipped in #273 against a list without PUT
    * and was never once called from the SPA (#312).
    *
    * Every verb a session surface uses is asserted here, so widening the surface without widening the list fails at the
    * point of the omission rather than in a browser console. Keep the list exhaustive: it is only worth what it covers,
    * and `PATCH /auth/me` was missing from the first cut, which would have let `Method.PATCH` be dropped from
    * `CredentialedMethods` with this test still green while nickname renames died in the browser.
    */
  test("the credentialed preflight allows every verb the session-gated surfaces use"):
    def preflight(method: String, path: Uri) =
      app("https://play.jc.id.lv").run(
        Request[IO](Method.OPTIONS, path).putHeaders(
          origin("https://play.jc.id.lv"),
          Header.Raw(ci"Access-Control-Request-Method", method),
          Header.Raw(ci"Access-Control-Request-Headers", "content-type")
        )
      )
    // (verb, a route that uses it). Between them these cover every method in `CredentialedMethods` except OPTIONS,
    // which is the preflight itself — so the list has no entry a session route does not justify, and no session route
    // a list entry does not serve.
    val used = List(
      ("GET", uri"/me/bots"),                             // every read
      ("POST", uri"/admin/bots/acme/alice/ladder/leave"), // every action, on all three doors
      ("PUT", uri"/admin/bots/acme/alice/description"),   // replaces the catalog description (#312)
      ("PATCH", uri"/auth/me"),                           // renames the account nickname (#234)
      ("DELETE", uri"/me/bots/acme/alice")                // releases an owned bot
    )
    used.traverse_ { (verb, path) =>
      preflight(verb, path).map: resp =>
        assert(
          headerValues(resp.headers, ci"Access-Control-Allow-Methods").contains(verb.toLowerCase),
          s"a session-gated route uses $verb, but the credentialed preflight does not advertise it: " +
            s"${resp.headers.get(ci"Access-Control-Allow-Methods")}"
        )
    }

  /** The same closed-whitelist trap on the OTHER axis, which the verb test above does not cover: `CredentialedHeaders`
    * is a whitelist too, and a request header missing from it is refused at the preflight just as silently.
    *
    * `POST /me/bots/claim` is the case that found it — it needs BOTH the session cookie and the bot's Bearer token on
    * one request (#253), so it is the only browser path that sends `Authorization`, and the owner UI could not perform
    * its central action until this list carried it.
    */
  test("the credentialed preflight allows every request header the session-gated surfaces send"):
    val needed = List(
      "content-type",     // every JSON body
      "authorization",    // POST /me/bots/claim — the session says who, the bot's token proves control
      "if-match",         // webhook mutations compare the opaque slot revision
      "x-dicechess-csrf", // webhook mutations and session showcase claims require the same-origin CSRF signal
      "idempotency-key"   // POST /showcase/claim (#46) — mandatory, and sent with the session cookie when signed in
    )
    app("https://play.jc.id.lv")
      .run(
        Request[IO](Method.OPTIONS, uri"/me/bots/claim").putHeaders(
          origin("https://play.jc.id.lv"),
          Header.Raw(ci"Access-Control-Request-Method", "POST"),
          Header.Raw(ci"Access-Control-Request-Headers", needed.mkString(", "))
        )
      )
      .map: resp =>
        val advertised = headerValues(resp.headers, ci"Access-Control-Allow-Headers")
        needed.foreach: header =>
          assert(
            advertised.contains(header),
            s"a session-gated route sends `$header`, but the credentialed preflight does not advertise it: " +
              s"${resp.headers.get(ci"Access-Control-Allow-Headers")}"
          )

  test("an allowed browser origin can read webhook revision, rate-limit and setup-location response headers"):
    app("https://play.jc.id.lv")
      .run(Request[IO](Method.GET, uri"/health").putHeaders(origin("https://play.jc.id.lv")))
      .map: resp =>
        val exposed = headerValues(resp.headers, ci"Access-Control-Expose-Headers")
        assert(exposed.contains("etag"), s"credentialed responses do not expose ETag: $exposed")
        assert(exposed.contains("retry-after"), s"credentialed responses do not expose Retry-After: $exposed")
        // The 201 from POST .../webhook/setups documents Location, and Location is not CORS-safelisted: without
        // this the browser client — the only caller these routes accept — cannot read the header the contract
        // promises it.
        assert(exposed.contains("location"), s"credentialed responses do not expose Location: $exposed")

  test("an allow-list echoes a configured origin and omits the header for others"):
    val restricted = app("https://play.jc.id.lv,http://localhost:5173")
    for
      allowed <- restricted.run(Request[IO](Method.GET, uri"/health").putHeaders(origin("https://play.jc.id.lv")))
      denied  <- restricted.run(Request[IO](Method.GET, uri"/health").putHeaders(origin("https://evil.example")))
    yield
      assertEquals(allowOrigin(allowed.headers), Some("https://play.jc.id.lv"))
      assertEquals(allowOrigin(denied.headers), None)

  test("unusable allow-list entries are reported, and a wholly unusable list is never read as 'unset'"):
    // `parse` keeps its lenient contract for the server-side guard; `parseDetailed` is what tells a caller the
    // difference between "the operator asked for nothing" and "the operator asked for something unusable".
    val mixed = Cors.AllowedOrigins.parseDetailed("https://play.jc.id.lv, fortemate.com, null")
    assert(mixed.allowed.isExplicitlyConfigured)
    assert(mixed.allowed.allows(parsedOrigin("https://play.jc.id.lv")))
    assertEquals(mixed.rejected, List("fortemate.com", "null"))
    assert(!mixed.isEntirelyUnusable, "one usable origin is still a usable allow-list")

    // `Origin.parse` is permissive where the header is not: it keeps `HTTPS` casing, folds a trailing path into the
    // host, and keeps an explicit `:443` that the URL spec's origin serialization elides. All three parse, so none is
    // "unparseable" — but a browser only ever sends the canonical form, so storing them would silently match nothing
    // and leave the operator staring at a policy that refuses their own site. They must be reported like any other
    // unusable entry.
    val unmatchable =
      Cors.AllowedOrigins.parseDetailed("https://ok.example/hook, HTTPS://OK.example, https://fortemate.com:443")
    assertEquals(
      unmatchable.rejected,
      List("https://ok.example/hook", "HTTPS://OK.example", "https://fortemate.com:443")
    )
    assert(!unmatchable.allowed.isExplicitlyConfigured)
    assert(unmatchable.isEntirelyUnusable)

    // A wildcard entry is the same class of mistake and the most tempting one to write, because it is what an
    // operator reaches for after seeing an allow-list. It parses as a perfectly ordinary reg-name, so without an
    // explicit check it would be stored, make the list look configured, mount the credentialed policy and the staged
    // webhook routes — and then match nothing, refusing every real subdomain it was meant to admit.
    val wildcard = Cors.AllowedOrigins.parseDetailed("https://*.fortemate.com")
    assertEquals(wildcard.rejected, List("https://*.fortemate.com"))
    assert(wildcard.isEntirelyUnusable, "a wildcard host must not pass for a configured allow-list")
    assert(!wildcard.allowed.allows(parsedOrigin("https://app.fortemate.com")))

    // The forms a browser does send stay accepted: a non-default port, an IPv4 and an IPv6 literal, punycode, and the
    // hyphens and underscores that appear in real host names.
    val canonical = Cors.AllowedOrigins.parseDetailed(
      "http://localhost:5173, https://fortemate.com, http://[::1]:5173, http://127.0.0.1:5173, " +
        "https://xn--80ak6aa92e.com, https://sub.a-b.example.com, https://fortemate_x.com"
    )
    assertEquals(canonical.rejected, Nil)
    assert(canonical.allowed.allows(parsedOrigin("http://127.0.0.1:5173")))
    assert(canonical.allowed.allows(parsedOrigin("https://xn--80ak6aa92e.com")))
    assert(canonical.allowed.allows(parsedOrigin("https://sub.a-b.example.com")))
    assert(canonical.allowed.allows(parsedOrigin("http://localhost:5173")))
    assert(canonical.allowed.allows(parsedOrigin("https://fortemate.com")))
    assert(canonical.allowed.allows(parsedOrigin("http://[::1]:5173")))

    val unusable = Cors.AllowedOrigins.parseDetailed("null, fortemate.com")
    assert(!unusable.allowed.isExplicitlyConfigured)
    assertEquals(unusable.rejected, List("null", "fortemate.com"))
    assert(
      unusable.isEntirelyUnusable,
      "an allow-list none of whose entries parse must be distinguishable from an absent one: the empty set " +
        "selects credential-less allow-all, so treating it as 'unset' turns one typo into a policy change"
    )

    val absent = Cors.AllowedOrigins.parseDetailed(" ,  ")
    assert(!absent.allowed.isExplicitlyConfigured)
    assertEquals(absent.rejected, Nil)
    assert(!absent.isEntirelyUnusable, "a blank value is a deliberate allow-all, not a mistake")
