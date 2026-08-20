package dicechess.play.server

import cats.effect.IO
import cats.effect.std.Console
import com.auth0.jwk.{JwkProvider, JwkProviderBuilder}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.circe.parser.parse

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.interfaces.RSAPublicKey

/** A verified Google identity (#233, ADR-0017). `subject` is Google's stable `sub` claim — THE identity key, the only
  * field `upsertOnLogin` matches on. `email` is a display attribute for the owner's own profile view and is `None`
  * unless Google marked it verified: an unverified address is a string anyone can type into an account, so it never
  * enters our database — but, because the key is `sub` and not the email (the deliberate divergence from the analytics
  * donor), an unverified email downgrades to "no email on file" instead of refusing the login outright.
  */
final case class GoogleIdentity(subject: String, email: Option[String])

/** The seam `AuthRoutes` talks to: building the authorize redirect and turning a callback `code` into a verified
  * identity. A trait so the routes' whole flow — state check, cookie issuance, upsert — is testable with a stub while
  * the live implementation owns every outbound Google call.
  */
trait GoogleIdentityProvider:
  def authorizeUrl(state: String): String
  def identityFor(code: String): IO[GoogleIdentity]

object GoogleAuth:

  /** The Google OAuth client, complete or absent — partial configuration is a deployment mistake worth a loud boot
    * warning (see [[configFromEnv]]), not a feature half-on.
    */
  final case class Config(clientId: String, clientSecret: String, redirectUri: String)

  private val ClientIdVar     = "GOOGLE_CLIENT_ID"
  private val ClientSecretVar = "GOOGLE_CLIENT_SECRET"
  private val RedirectUriVar  = "GOOGLE_REDIRECT_URI"

  /** All three vars or nothing, following the repo's "absence silently disables the feature" idiom — except that a
    * PARTIAL configuration warns on stderr: someone clearly tried to enable sign-in, and the alternative is the
    * AGENTS.md failure mode where the server boots clean and the feature just never exists.
    */
  def configFromEnv: IO[Option[Config]] =
    IO(List(ClientIdVar, ClientSecretVar, RedirectUriVar).map(v => sys.env.get(v).filter(_.nonEmpty))).flatMap {
      case List(Some(id), Some(secret), Some(redirect)) => IO.pure(Some(Config(id, secret, redirect)))
      case values if values.exists(_.nonEmpty)          =>
        val missing = List(ClientIdVar, ClientSecretVar, RedirectUriVar)
          .zip(values)
          .collect { case (name, None) => name }
        Console[IO]
          .errorln(s"[play][auth] Google sign-in DISABLED: ${missing.mkString(", ")} unset while others are set")
          .as(None)
      case _ => IO.pure(None)
    }

  private val GoogleAuthUrl  = "https://accounts.google.com/o/oauth2/v2/auth"
  private val GoogleTokenUrl = "https://oauth2.googleapis.com/token"
  private val GoogleCertsUrl = "https://www.googleapis.com/oauth2/v3/certs"
  private val GoogleIssuers  = List("https://accounts.google.com", "accounts.google.com")

  /** Finite timeouts on every outbound Google call so a slow upstream degrades one login, never the server. */
  private val HttpTimeout = java.time.Duration.ofSeconds(10)

  private def enc(value: String): String = URLEncoder.encode(value, UTF_8)

  /** The live provider. Both the HTTP client and the JWKS provider (which caches Google's signing keys internally) are
    * built once per server, not per login.
    */
  def live(config: Config): GoogleIdentityProvider = new GoogleIdentityProvider:

    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(HttpTimeout).build()

    private val jwkProvider: JwkProvider =
      JwkProviderBuilder(URI.create(GoogleCertsUrl).toURL).timeouts(10000, 10000).build()

    def authorizeUrl(state: String): String =
      s"$GoogleAuthUrl?client_id=${enc(config.clientId)}" +
        s"&redirect_uri=${enc(config.redirectUri)}" +
        s"&response_type=code" +
        s"&scope=${enc("openid email profile")}" +
        s"&state=${enc(state)}"

    def identityFor(code: String): IO[GoogleIdentity] =
      exchangeCode(code)
        .flatMap { tokenJson =>
          IO.fromOption(tokenJson.hcursor.get[String]("id_token").toOption)(
            RuntimeException("Google token response had no id_token")
          )
        }
        .flatMap(verifyIdToken)

    /** Exchange the authorization `code` for Google's token response. Every form field is percent-encoded — Google
      * codes/URLs contain characters that would otherwise corrupt an `application/x-www-form-urlencoded` body.
      */
    private def exchangeCode(code: String): IO[io.circe.Json] = IO.blocking {
      val form = List(
        "code"          -> code,
        "client_id"     -> config.clientId,
        "client_secret" -> config.clientSecret,
        "redirect_uri"  -> config.redirectUri,
        "grant_type"    -> "authorization_code"
      ).map((k, v) => s"${enc(k)}=${enc(v)}").mkString("&")

      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(GoogleTokenUrl))
        .timeout(HttpTimeout)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build()

      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      if response.statusCode() != 200 then
        throw RuntimeException(s"Google token exchange returned HTTP ${response.statusCode()}")
      parse(response.body()).fold(err => throw err, identity)
    }

    /** Verify the `id_token` locally: RS256 signature against the JWKS key the token names, issuer pinned to Google,
      * audience pinned to our client id. Identity comes from the verified token's own claims — never from a separate,
      * unauthenticated profile call.
      */
    private def verifyIdToken(idToken: String): IO[GoogleIdentity] = IO.blocking {
      val decoded   = JWT.decode(idToken)
      val publicKey = jwkProvider.get(decoded.getKeyId).getPublicKey.asInstanceOf[RSAPublicKey]
      val verified  = JWT
        .require(Algorithm.RSA256(publicKey, null))
        .withIssuer(GoogleIssuers*)
        .withAudience(config.clientId)
        .build()
        .verify(idToken)

      val subject = Option(verified.getSubject).filter(_.nonEmpty).getOrElse {
        throw RuntimeException("Google id_token has no subject")
      }
      val emailVerified =
        Option(verified.getClaim("email_verified").asBoolean()).exists(_.booleanValue())
      val email = Option(verified.getClaim("email").asString()).filter(_ => emailVerified)
      GoogleIdentity(subject, email)
    }
