package dicechess.play.server

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import org.http4s.{Method, Uri}
import org.http4s.headers.Origin
import org.http4s.server.middleware.{CORS, CORSPolicy}
import org.typelevel.ci.{CIString, CIStringSyntax}

/** Cross-origin policy for the browser play-site.
  *
  * Historically this API was anonymous-first and used no cookies: join/Bearer tokens travel explicitly in the
  * URL/query/`Authorization` header. ADR-0017 (#233) adds the one deliberate exception — the account session cookie
  * (see `AuthSession`) — so an explicit origin allow-list now also enables `Access-Control-Allow-Credentials`, which
  * the SPA's credentialed fetches require.
  *
  * The empty/unset default still allows any origin, and deliberately WITHOUT credentials: a wildcard-plus-credentials
  * policy would let any page on the web read the API as whoever is signed in, which is exactly the leak CORS exists to
  * prevent (`*` precludes it at the spec level too). Allow-all therefore remains safe precisely because it stays
  * credential-less — a deployment that enables sign-in must also pin `PLAY_CORS_ORIGINS` (e.g.
  * `https://fortemate.com,https://play.jc.id.lv,http://localhost:5173`).
  */
object Cors:

  private val EnvVar = "PLAY_CORS_ORIGINS"

  /** Parsed `PLAY_CORS_ORIGINS` value shared by the browser CORS middleware and server-side origin guards.
    *
    * CORS is a browser response policy, not an authorization check. Session-backed mutation routes can therefore use
    * [[allows]] themselves and reject a missing or unlisted `Origin` before performing any work. They should also
    * require [[isExplicitlyConfigured]]: the historical empty configuration means "public, credential-less CORS", not
    * "trust every origin for a cookie-authenticated mutation".
    */
  final class AllowedOrigins private (private val values: Set[String]):

    /** Whether the deployment supplied at least one trusted origin. */
    def isExplicitlyConfigured: Boolean = values.nonEmpty

    /** Exact match against one concrete scheme/host/port origin. Opaque `Origin: null` is never trusted for an
      * ambient-cookie request, even if an operator accidentally lists the literal word in the environment.
      */
    def allows(origin: Origin): Boolean = origin match
      case concrete @ Origin.HostList(hosts) if hosts.tail.isEmpty => values.contains(render(concrete))
      case _                                                       => false

  /** A parsed allow-list together with the entries that were thrown away, so a caller can tell "the operator asked for
    * nothing" apart from "the operator asked for something unusable".
    */
  final case class ParsedOrigins(allowed: AllowedOrigins, rejected: List[String]):

    /** The dangerous case: the operator supplied entries and NONE survived parsing. Silently treating this as "unset"
      * would swap a restricted, credentialed policy for the public allow-all one.
      */
    def isEntirelyUnusable: Boolean = rejected.nonEmpty && !allowed.isExplicitlyConfigured

  object AllowedOrigins:
    def parse(spec: String): AllowedOrigins = parseDetailed(spec).allowed

    /** Same parse, but keeps the rejected entries. Pure, so tests can assert the diagnosis without an environment. */
    def parseDetailed(spec: String): ParsedOrigins =
      val (rejected, accepted) = spec
        .split(',')
        .iterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .toList
        .partitionMap(raw => browserOrigin(raw).toRight(raw))
      ParsedOrigins(new AllowedOrigins(accepted.toSet), rejected)

  /** Read and parse the trusted browser origins without turning them into middleware yet.
    *
    * A rejected entry is reported, and a spec none of whose entries is usable aborts the boot. The alternative — the
    * empty set — is not a smaller version of the operator's intent: [[policy]] reads it as the historical "no
    * allow-list configured" case and serves credential-less allow-all, so one typo would silently trade a locked-down
    * credentialed policy for a public one, break every cookie-authenticated browser call, and leave the staged webhook
    * routes unmounted. `PLAY_CORS_ORIGINS` unset or blank still means allow-all; only a non-empty, wholly unusable
    * value is an error.
    */
  def allowedOriginsFromEnv: IO[AllowedOrigins] =
    IO(sys.env.getOrElse(EnvVar, "")).flatMap { spec =>
      val parsed = AllowedOrigins.parseDetailed(spec)
      val report = Console[IO]
        .errorln(s"[play][cors] ignoring unusable $EnvVar entries: ${parsed.rejected.mkString(", ")}")
        .whenA(parsed.rejected.nonEmpty)
      val abort = IO
        .raiseError[Unit](
          new IllegalArgumentException(
            s"$EnvVar lists only unusable origins (${parsed.rejected.mkString(", ")}); " +
              "each entry must be one concrete scheme://host[:port] origin. " +
              s"Leave $EnvVar unset for the credential-less allow-all default."
          )
        )
        .whenA(parsed.isEntirelyUnusable)
      report *> abort.as(parsed.allowed)
    }

  /** Parse a comma-separated origin allow-list for a server-side origin guard. */
  def allowedOrigins(spec: String): AllowedOrigins = AllowedOrigins.parse(spec)

  /** Build the policy from `PLAY_CORS_ORIGINS` (empty/unset → allow all, credential-less). */
  def fromEnv: IO[CORSPolicy] = allowedOriginsFromEnv.map(policy)

  /** The methods and headers a credentialed policy must enumerate. `*` is illegal alongside
    * `Access-Control-Allow-Credentials`, and http4s enforces that by answering a preflight with NO `Access-Control-*`
    * headers at all rather than emitting an invalid combination — see [[policy]].
    *
    * Kept to what the browser client actually sends: JSON bodies (`content-type`), the route set's
    * GET/POST/PUT/PATCH/DELETE, and — since #253's claim reached a client — `authorization`. Add to these lists when a
    * new method or request header appears on a browser path, or the preflight for it will be refused.
    *
    * `authorization` was absent on the reasoning that the Bearer tier is server-to-server and never preflighted by a
    * browser. `POST /me/bots/claim` is the exception that reasoning did not anticipate: it needs BOTH credentials on
    * one request — the session cookie says who is claiming, the bot's own Bearer token proves control of it (#253) — so
    * the one route where a browser must send `Authorization` is the one route whose whole point is that a session alone
    * is not enough. Widening the list grants no new authority: the origin check runs first, so a page that is not in
    * `PLAY_CORS_ORIGINS` gets no `Access-Control-*` headers whatever it asks for, and the routes that read this header
    * validated it exactly as before.
    *
    * PUT joined the list for `PUT /admin/bots/{team}/{name}/description` (#312). Recorded because the ORDER matters:
    * the verb was chosen for the operation — an idempotent replacement, safe to retry — and this list was then widened
    * to serve it. Not the reverse. A **session-gated** route is where the whitelist bites hardest: its cookie is minted
    * by the browser sign-in flow, so a browser is the only client that holds one in practice. A script presenting the
    * same cookie bypasses CORS entirely — nothing here forbids that, and an operator debugging with `curl` will find it
    * works — but no product path does it, so a verb missing from this list takes the route away from every real caller
    * while the server-side tests stay green, because the refusal happens in the browser.
    *
    * `idempotency-key` joined for `POST /showcase/claim` (ADR-005 §5, #46): the header is mandatory there, and a
    * browser on the homepage sends it with the session cookie when the visitor is signed in, so its preflight has to
    * name it.
    */
  private val CredentialedMethods: Set[Method] =
    Set(Method.GET, Method.POST, Method.PUT, Method.PATCH, Method.DELETE, Method.OPTIONS)

  private val CredentialedHeaders: Set[CIString] =
    Set(ci"content-type", ci"authorization", ci"if-match", ci"x-dicechess-csrf", ci"idempotency-key")

  /** Response headers the browser client must be able to read. `etag` carries the `If-Match` revision the staged
    * webhook API requires on every mutation, `retry-after` the verification budget's reset, and `location` the
    * canonical path of a freshly created setup — none of the three is CORS-safelisted, so a page that cannot see them
    * cannot follow the documented contract. `cache-control` and `pragma`, also set on those responses, are safelisted
    * and need no entry here.
    */
  private val ExposedHeaders: Set[CIString] = Set(ci"etag", ci"retry-after", ci"location")

  /** Build a policy from a comma-separated origin allow-list. An empty/blank spec allows any origin without
    * credentials; a non-empty list restricts origins AND lets responses carry credentials (the session cookie).
    *
    * The two branches cannot share one `base`: allow-all may use `*` for methods and headers precisely because it is
    * credential-less, while the credentialed branch must enumerate them (see [[CredentialedMethods]]). Reaching for
    * `withAllowMethodsAll`/`withAllowHeadersAll` in the credentialed branch is what took production down — plain GETs
    * kept their headers, so the API looked healthy while every preflighted POST was blocked by the browser.
    */
  def policy(spec: String): CORSPolicy =
    policy(AllowedOrigins.parse(spec))

  /** Build the middleware from the same parsed origin set used by server-side session mutation guards. */
  def policy(allowed: AllowedOrigins): CORSPolicy =
    if !allowed.isExplicitlyConfigured then
      CORS.policy.withAllowMethodsAll.withAllowHeadersAll.withAllowOriginAll.withExposeHeadersIn(ExposedHeaders)
    else
      CORS.policy
        .withAllowOriginHeader(allowed.allows)
        .withAllowMethodsIn(CredentialedMethods)
        .withAllowHeadersIn(CredentialedHeaders)
        .withExposeHeadersIn(ExposedHeaders)
        .withAllowCredentials(true)

  /** Render an `Origin` to its header form (`scheme://host[:port]`) for matching against the allow-list. */
  private def render(origin: Origin): String = Origin.headerInstance.value(origin)

  /** The subset of `Origin` values a browser can actually send: exactly one concrete host, a lower-case scheme and
    * host, no path, and no explicitly written default port.
    *
    * `Origin.parse` is far more permissive than the header it models — it accepts `HTTPS://OK.example` verbatim, folds
    * a trailing path into the host (`https://ok.example/hook` parses with `host = "ok.example/hook"`), and keeps `:443`
    * where the URL spec's origin serialization elides it. Since [[AllowedOrigins.allows]] matches the browser's
    * rendered header exactly, and a browser always sends the canonical form, every such entry would be stored and then
    * match nothing at all — the operator gets a policy that refuses their own site, with no diagnostic anywhere. They
    * are the same silent misconfiguration as an unparseable entry, so they are reported the same way rather than
    * accepted into the set.
    */
  private def browserOrigin(raw: String): Option[String] =
    Origin.parse(raw).toOption match
      case Some(origin @ Origin.HostList(hosts)) if hosts.tail.isEmpty =>
        val host        = hosts.head
        val scheme      = host.scheme.value
        val defaultPort = if scheme == "https" then 443 else if scheme == "http" then 80 else -1
        Option.when(
          scheme == scheme.toLowerCase &&
            host.host.value == host.host.value.toLowerCase &&
            nameableHost(host.host) &&
            !host.port.contains(defaultPort)
        )(render(origin))
      case _ => None

  /** Hosts within the `reg-name` grammar are still not all reachable names: `Origin.parse` happily accepts a wildcard
    * host such as `*.fortemate.com`, which no browser can ever send, so it would be stored as a live entry that matches
    * nothing while making the allow-list look configured — the credentialed policy mounts, the staged webhook routes
    * mount, and every real request is refused. Address literals are structurally fine and skip the check; a registered
    * name must look like one, which still admits `localhost`, punycode, hyphens and underscores.
    */
  private val NameableHost = "^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$".r

  private def nameableHost(host: Uri.Host): Boolean = host match
    case _: Uri.Ipv4Address | _: Uri.Ipv6Address => true
    case regName                                 => NameableHost.matches(regName.value)
