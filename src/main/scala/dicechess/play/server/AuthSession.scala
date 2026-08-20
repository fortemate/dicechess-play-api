package dicechess.play.server

import cats.effect.IO
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import dicechess.play.core.Principal
import dicechess.play.store.{UserAccount, UserStore}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.{Request, Response, ResponseCookie, SameSite, Status}

import java.util.Date
import scala.util.Try

/** The cookie session behind player accounts (#233, ADR-0017) — the one deliberate exception to this API's "no ambient
  * credentials" rule (`Cors.scala`): an account carries privileges (bots, later tournaments), and an HttpOnly cookie is
  * the one place browser script cannot read a credential from.
  *
  * The session is an HMAC-signed JWT carried in the `access_token` cookie. The JWT proves only WHO — existence and
  * `is_active` are re-read from the database on every authenticated request, so deactivation or deletion revokes access
  * immediately; the signed token is never trusted for authorization state. Cookies are host-only (no `Domain`):
  * play.jc.id.lv and play-api.jc.id.lv are same-site, so `SameSite=Lax` lets the SPA's credentialed fetches and the
  * WebSocket handshake carry the cookie while cross-site pages cannot. `Secure` is unconditional — browsers treat
  * `http://localhost` as a secure context, so local development still works.
  */
final class AuthSession(store: UserStore, secret: String):

  private val algorithm: Algorithm  = Algorithm.HMAC256(secret)
  private val verifier: JWTVerifier = JWT.require(algorithm).build()

  def sign(user: UserAccount): IO[String] =
    IO.realTimeInstant.map { now =>
      JWT
        .create()
        .withSubject(user.id)
        .withExpiresAt(Date.from(now.plusSeconds(AuthSession.SessionTtlSeconds)))
        .sign(algorithm)
    }

  /** The signed subject, or `None` for any failure (bad signature, expired, malformed) — deliberately one bucket: the
    * caller answers 401 either way and the reason is not the client's business.
    */
  private def subjectOf(token: String): Option[String] =
    Try(verifier.verify(token)).toOption.flatMap(decoded => Option(decoded.getSubject)).filter(_.nonEmpty)

  /** The live account behind a request's session cookie, if any. `None` covers every rung — no cookie, bad token,
    * vanished user, deactivated user — because each of them means the same thing to a route: not signed in.
    */
  def userFor(req: Request[IO]): IO[Option[UserAccount]] =
    req.cookies.find(_.name == AuthSession.SessionCookieName).map(_.content).flatMap(subjectOf) match
      case None     => IO.pure(None)
      case Some(id) => store.userById(id).map(_.filter(_.isActive))

  /** The session gate every account-facing route starts with: a live account, or one 401. It lives here rather than
    * being repeated per route file for the same reason the bot-management helpers are shared — the "not signed in"
    * wording is part of the contract clients read, and two copies would eventually answer differently.
    */
  def withUser(req: Request[IO])(f: UserAccount => IO[Response[IO]]): IO[Response[IO]] =
    userFor(req).flatMap(_.fold(IO.pure(AuthSession.notSignedIn))(f))

  def sessionCookie(token: String): ResponseCookie =
    AuthSession.cookie(AuthSession.SessionCookieName, token, AuthSession.SessionTtlSeconds)

  def expiredSessionCookie: ResponseCookie = AuthSession.expired(AuthSession.SessionCookieName)

object AuthSession:

  /** The single 401 body for a missing or unusable session, so the two owner surfaces cannot answer differently. */
  val notSignedIn: Response[IO] = Response[IO](Status.Unauthorized).withEntity("Not signed in")

  val SessionCookieName: String = "access_token"
  val StateCookieName: String   = "oauth_state"

  /** The signed-in `Principal.User` behind a request, when sessions are enabled at all — `None` covers both "auth is
    * not configured on this deployment" and "this request carries no usable session", because a route treats them the
    * same way: fall back to the anonymous path.
    */
  def principalFor(session: Option[AuthSession], req: Request[IO]): IO[Option[Principal.User]] =
    session.fold(IO.pure(Option.empty[Principal.User]))(_.userFor(req).map(_.map(u => Principal.User(u.id))))

  /** The acting principal for a game-start request (#235, ADR-0017): **the session wins; the body is only ever a guest
    * fallback.** A signed-in caller is seated as `Principal.User` no matter what the body says — accepting a
    * body-supplied `user:` id would let anyone impersonate any account, so there is deliberately no way to express one.
    * With no session, the body's bare guest UUID goes through `Principal.guest` exactly as before; absent too → `Left`,
    * the route's 400.
    *
    * Both `Left` branches carry the `field` prefix themselves, so a caller answers with the message verbatim rather
    * than prefixing it again (which read as `creator: creator is required…` for the missing-field branch).
    */
  def actingPrincipal(
      session: Option[AuthSession],
      req: Request[IO],
      bodyGuestId: Option[String],
      field: String
  ): IO[Either[String, Principal]] =
    principalFor(session, req).map {
      case Some(user) => Right(user)
      case None       =>
        bodyGuestId match
          case None     => Left(s"$field is required when not signed in")
          case Some(id) => Principal.guest(id).left.map(err => s"$field: $err")
    }

  /** 30 days — long enough that a casual player never re-logs, revocable anyway via the per-request DB read. */
  private val SessionTtlSeconds: Long = 30L * 24L * 3600L

  /** 10 minutes — the login → Google → callback round-trip, not a session. */
  private val StateTtlSeconds: Long = 600L

  private val SessionSecretVar = "PLAY_SESSION_SECRET"
  private val FrontendUrlVar   = "PLAY_FRONTEND_URL"
  private val DefaultFrontend  = "https://play.jc.id.lv"

  /** Where login/callback/logout send the browser back to. Always defined — the default is production, and local
    * development overrides it (`PLAY_FRONTEND_URL=http://localhost:5173`).
    */
  def frontendUrlFromEnv: IO[String] =
    IO(sys.env.get(FrontendUrlVar).filter(_.nonEmpty).getOrElse(DefaultFrontend))

  /** The HMAC key for session JWTs, or `None` (feature off). Absence is the switch; there is no fallback secret on
    * purpose — a well-known default would sign valid sessions for anyone who read the source.
    */
  def secretFromEnv: IO[Option[String]] =
    IO(sys.env.get(SessionSecretVar).filter(_.nonEmpty))

  def stateCookie(state: String): ResponseCookie = cookie(StateCookieName, state, StateTtlSeconds)

  def expiredStateCookie: ResponseCookie = expired(StateCookieName)

  private def cookie(name: String, value: String, ttlSeconds: Long): ResponseCookie =
    ResponseCookie(
      name = name,
      content = value,
      maxAge = Some(ttlSeconds),
      path = Some("/"),
      sameSite = Some(SameSite.Lax),
      httpOnly = true,
      secure = true
    )

  private def expired(name: String): ResponseCookie =
    ResponseCookie(
      name = name,
      content = "",
      maxAge = Some(0L),
      path = Some("/"),
      sameSite = Some(SameSite.Lax),
      httpOnly = true,
      secure = true
    )
