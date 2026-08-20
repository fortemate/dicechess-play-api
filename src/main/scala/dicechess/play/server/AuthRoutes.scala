package dicechess.play.server

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.core.{Principal, RatingCategory}
import dicechess.play.rating.{Glicko, Glicko2}
import dicechess.play.store.{
  LeaderboardStore,
  NicknameUpdate,
  RatedIdentity,
  RatingStore,
  ResultTally,
  UserAccount,
  UserStore
}
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `Retry-After`}
import org.http4s.{HttpRoutes, Response, Status, Uri}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/** The owner's own view of their account. Deliberately minimal for now: email lives in `user_identities` and the store
  * exposes no accessor yet — the fuller profile shape (email, linked guests) arrives with #236, and per ADR-0017 email
  * would appear HERE only, never on any public wire type.
  */
final case class MeResponse(
    id: String,
    nickname: String,
    // The owner's own rating (#249). `provisional` is the same convergence rule the public board hides on, surfaced
    // here so a fresh account can see WHY it is not listed yet rather than concluding the feature is broken.
    //
    // These three describe `RatingCategory.Default` since #280, having previously described the one all-speeds scale —
    // the same deliberate re-pointing `BotProfile` documents at length, for the same reason: the deployed SPA reads
    // them, and the scale they used to name is the one being retired.
    rating: Double,
    rd: Double,
    provisional: Boolean,
    /** Rated, decided games in that same default category — the denominator behind `provisional`, which used to be
      * guessable from the public profile and now has to be stated, since it is per category.
      */
    games: Int,
    /** Every category this account has actually been rated in (#280), each with the record that produced it. */
    ratings: List[CategoryRating],
    /** Whether this live account is in `PLAY_ADMINS`' parsed uuid allowlist (#313). */
    admin: Boolean
) derives Codec.AsObject

/** `PATCH /auth/me`'s body (#234). One field on purpose — every future profile edit should arrive as its own reviewed
  * field, not ride an anything-goes map.
  */
final case class NicknameChange(nickname: String) derives Codec.AsObject

/** `DELETE /auth/me`'s body (#237): `confirm` must echo the account's own nickname. A typed statement of intent for the
  * one irreversible operation in this surface — see the route for why it is not about CSRF.
  */
final case class DeleteAccount(confirm: String) derives Codec.AsObject

/** Google sign-in (#233, ADR-0017), ported from the hardened dicechess-analytics PR #215 branch:
  *
  *   - `GET /auth/login` → 303 to Google, with a random `state` in a short-lived cookie (CSRF protection for the
  *     round-trip; compared constant-time on return).
  *   - `GET /auth/callback` → code exchange + local `id_token` verification (JWKS signature, issuer, audience — see
  *     `GoogleAuth`), upsert by `(google, sub)`, session cookie, 303 back to the SPA.
  *   - `GET /auth/me` / `POST /auth/logout` — the session's read and its end.
  *
  * Callback failures answer a generic 500 and log the detail server-side: the error chain names Google endpoints and
  * token internals that are diagnostic gold and phishing-page copy in equal measure.
  *
  * Everything here is mounted only when persistence AND the full auth config are present (`Main.scala`) — the
  * DB-only-seam idiom, so an undeployed feature is a 404, not a half-configured 500.
  */
object AuthRoutes:

  private val secureRandom = SecureRandom()

  private def randomState: IO[String] = IO {
    val bytes = new Array[Byte](32)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  private def constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))

  private def redirect(target: String): Response[IO] =
    Response[IO](status = Status.SeeOther).putHeaders(Location(Uri.unsafeFromString(target)))

  def apply(
      session: AuthSession,
      google: GoogleIdentityProvider,
      store: UserStore,
      // The per-category rating reads `/auth/me` answers with (#280). Two seams rather than one because the numbers
      // and the records they came from live in different projections — the same split `LeaderboardRoutes` uses.
      ratings: RatingStore,
      board: LeaderboardStore,
      frontendUrl: String,
      admins: Set[String]
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "auth" / "login" =>
        randomState.map { state =>
          redirect(google.authorizeUrl(state)).addCookie(AuthSession.stateCookie(state))
        }

      case req @ GET -> Root / "auth" / "callback" =>
        val code       = req.uri.query.params.get("code")
        val state      = req.uri.query.params.get("state")
        val savedState = req.cookies.find(_.name == AuthSession.StateCookieName).map(_.content)

        (code, state, savedState) match
          case (Some(c), Some(s), Some(saved)) if constantTimeEquals(s, saved) =>
            val flow = for
              identity <- google.identityFor(c)
              user     <- store.upsertOnLogin("google", identity.subject, identity.email, Nicknames.fresh)
              token    <- session.sign(user)
            yield redirect(frontendUrl)
              .addCookie(session.sessionCookie(token))
              .addCookie(AuthSession.expiredStateCookie)

            flow.handleErrorWith { err =>
              Console[IO].errorln(s"[play][auth] OAuth callback failed: $err") *>
                IO.pure(Response[IO](Status.InternalServerError).withEntity("Authentication failed"))
            }
          case (None, _, _) => BadRequest("Missing authorization code")
          case _            =>
            // Missing or mismatched state ⇒ possible CSRF; refuse and clear the stale cookie.
            BadRequest("Invalid OAuth state").map(_.addCookie(AuthSession.expiredStateCookie))

      case req @ GET -> Root / "auth" / "me" =>
        session.userFor(req).flatMap {
          case None       => IO.pure(AuthSession.notSignedIn)
          case Some(user) => meResponse(ratings, board, user, user.nickname, admins)
        }

      // Rename (#234). Format rules live in Nicknames.validate; the store enforces uniqueness plus the rename guard
      // (#275): a 90-day cooldown since this account's last rename, and a 90-day hold on a name someone else vacated.
      // `UserNotFound` maps to 401, not 404 — it means the account vanished between the session check and the write
      // (a racing deletion), which is "you are no longer signed in" from the caller's point of view. `Held` answers the
      // SAME 409 body as `Taken`, deliberately: telling the caller "that name is on hold" rather than "that name is
      // taken" would itself be an oracle, revealing that the name was recently vacated.
      case req @ PATCH -> Root / "auth" / "me" =>
        session.userFor(req).flatMap {
          case None       => IO.pure(AuthSession.notSignedIn)
          case Some(user) =>
            req
              .attemptAs[NicknameChange]
              .value
              .flatMap {
                case Left(failure) => BadRequest(failure.message)
                case Right(body)   =>
                  Nicknames.validate(body.nickname) match
                    case Left(reason) => BadRequest(reason)
                    case Right(name)  =>
                      store.updateNickname(user.id, name).flatMap {
                        case NicknameUpdate.Updated => meResponse(ratings, board, user, name, admins)
                        case NicknameUpdate.Taken | NicknameUpdate.Held =>
                          IO.pure(Response[IO](Status.Conflict).withEntity("nickname already taken"))
                        case NicknameUpdate.UserNotFound =>
                          IO.pure(AuthSession.notSignedIn)
                        case NicknameUpdate.CooldownActive(retryAfter) =>
                          TooManyRequests("nickname changed recently — try again later")
                            .map(_.putHeaders(`Retry-After`.unsafeFromLong(math.max(1L, retryAfter.toSeconds))))
                      }
              }
        }

      // Self-service deletion (#237). GDPR-lite: registration does not ship without a way out.
      //
      // The body must echo the account's own nickname. Not CSRF protection — `SameSite=Lax` plus a non-simple method
      // already means no cross-site page can send this — but a guard against a mis-wired client irreversibly deleting
      // the wrong account: the one thing here that cannot be undone deserves an explicit statement of intent.
      //
      // History is deliberately NOT rewritten. `user_identities` and `user_guest_links` cascade away (V14), so the
      // `user:<uuid>` left in `game_results`/`game_archive` stops resolving to anything — anonymisation without
      // touching immutable records or the analytics rows already delivered. An active game is left to the room's own
      // disconnect grace, exactly as if the player had closed the tab; there is no special case for it.
      case req @ DELETE -> Root / "auth" / "me" =>
        session.userFor(req).flatMap {
          case None       => IO.pure(AuthSession.notSignedIn)
          case Some(user) =>
            req
              .attemptAs[DeleteAccount]
              .value
              .flatMap {
                case Left(failure) => BadRequest(failure.message)
                case Right(body)   =>
                  if !body.confirm.trim.equalsIgnoreCase(user.nickname) then
                    BadRequest("confirm must be your current nickname")
                  else
                    // The store's `false` (already gone — a racing delete) gets the same 204: the caller's goal is met
                    // either way, and a 404 would only invite a pointless retry.
                    store
                      .deleteUser(user.id)
                      .as(Response[IO](Status.NoContent).addCookie(session.expiredSessionCookie))
              }
        }

      case POST -> Root / "auth" / "logout" =>
        Ok("Signed out").map(_.addCookie(session.expiredSessionCookie))

  /** `/auth/me`'s body. A category the owner has never played simply has no entry (#280) — the per-category tables are
    * sparse, and an owner reading "1500, provisional" for a speed they have never touched would be told a measurement
    * that was never taken.
    *
    * The same `CategoryRating` the public profiles carry, records included, rather than a slimmer owner-only shape: the
    * SPA renders both surfaces, and one wire type is one component.
    */
  private def meResponse(
      ratings: RatingStore,
      board: LeaderboardStore,
      user: UserAccount,
      nickname: String,
      admins: Set[String]
  ): IO[Response[IO]] =
    val externalId = Principal.User(user.id).externalId
    (
      ratings.categoryRatingsOf(RatedIdentity.User(user.id)),
      board.categoryTalliesFor(externalId)
    ).flatMapN: (byCategory, talliesByCategory) =>
      val default      = byCategory.getOrElse(RatingCategory.Default, Glicko.Initial)
      val defaultTally = talliesByCategory.getOrElse(RatingCategory.Default, ResultTally.Empty)
      Ok(
        MeResponse(
          id = user.id,
          nickname = nickname,
          rating = default.rating,
          rd = default.deviation,
          provisional = default.deviation > Glicko2.ProvisionalDeviationThreshold,
          games = defaultTally.games,
          ratings = LeaderboardRoutes.categoryRatings(byCategory, talliesByCategory),
          admin = admins.contains(user.id)
        )
      )
