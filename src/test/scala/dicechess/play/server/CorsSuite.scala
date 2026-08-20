package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
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
      "content-type", // every JSON body
      "authorization" // POST /me/bots/claim — the session says who, the bot's token proves control
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

  test("an allow-list echoes a configured origin and omits the header for others"):
    val restricted = app("https://play.jc.id.lv,http://localhost:5173")
    for
      allowed <- restricted.run(Request[IO](Method.GET, uri"/health").putHeaders(origin("https://play.jc.id.lv")))
      denied  <- restricted.run(Request[IO](Method.GET, uri"/health").putHeaders(origin("https://evil.example")))
    yield
      assertEquals(allowOrigin(allowed.headers), Some("https://play.jc.id.lv"))
      assertEquals(allowOrigin(denied.headers), None)
