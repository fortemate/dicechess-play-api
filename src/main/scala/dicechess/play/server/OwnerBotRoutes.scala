package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.Principal
import dicechess.play.store.{OwnerClaim, UserAccount}
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.{HttpRoutes, Request, Response, Status}

/** One of the account's own bots (#253) — identity, rating, and the flags its owner can act on. */
final case class MyBot(
    team: String,
    name: String,
    rating: Double,
    rd: Double,
    onLadder: Boolean,
    openToHumans: Boolean
) derives Codec.AsObject

final case class MyBots(bots: List[MyBot]) derives Codec.AsObject

/** `POST /me/bots/{team}/{name}/token`'s body (#254): `confirm` must echo the bot's own name. Rotation invalidates the
  * credential a RUNNING bot is authenticating with, so a mis-click takes it offline until its author redeploys — the
  * same reasoning that makes `DELETE /auth/me` ask for the nickname.
  */
final case class ConfirmRotation(confirm: String) derives Codec.AsObject

/** The signed-in owner's view of their bots (#253, #254, ADR-0017).
  *
  * Two credentials reach the same operations, and that is the point of this file. A bot drives itself with its Bearer
  * token through the `/bot` API; its owner drives it with a session cookie through the mirrored routes here. The Bearer
  * paths are untouched — the public Bot API contract must not shift under third-party authors — so this is a second
  * door, not a replacement. Both doors call the SAME helpers in [[BotRoutes]], so a semantic can never diverge between
  * them.
  *
  * Claiming lives here too rather than in `MeRoutes`, so the whole `/me/bots` … surface has one home: `MeRoutes` owns
  * identity and history, this owns bots.
  *
  * '''Not-yours is 403, not 404.''' Hiding a bot's existence from an authenticated owner would protect nothing — the
  * PUBLIC `GET /bots/{team}/{name}` already answers whether a registered bot exists, with its rating. So 404 means "no
  * such bot" and 403 means "it exists and is not yours", which is the more useful pair and leaks nothing new. (#253
  * shipped the release route as 404-for-both out of caution; this PR corrects it to match, for that reason.)
  */
object OwnerBotRoutes:

  def apply(session: AuthSession, auth: BotAuth, registry: GameRegistry): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      // Claiming needs BOTH credentials: the session says who is claiming, the bot's Bearer token proves control of it
      // (#253). Neither alone is enough — a session alone would let anyone claim any bot, and a token alone has nobody
      // to claim it for.
      case req @ POST -> Root / "me" / "bots" / "claim" =>
        session.withUser(req): user =>
          BotRoutes.asBot(auth, req).flatMap {
            case None      => IO.pure(Response[IO](Status.Unauthorized).withEntity("bot token required"))
            case Some(bot) =>
              auth.claimOwner(bot, Principal.User(user.id).externalId).flatMap {
                case OwnerClaim.Claimed          => myBots(auth, user)
                case OwnerClaim.ClaimedByAnother =>
                  IO.pure(Response[IO](Status.Conflict).withEntity("that bot already belongs to another account"))
                // A static-roster or anonymous caller: authenticated, but with no row to own.
                case OwnerClaim.NotRegistered =>
                  IO.pure(Response[IO](Status.NotFound).withEntity("only a registered bot can be owned"))
              }
          }

      case req @ GET -> Root / "me" / "bots" =>
        session.withUser(req)(myBots(auth, _))

      // Releasing is the explicit half of a transfer: the bot becomes claimable again, so handing it over does not
      // depend on who calls claim last. Session-only — the owner does not need the bot's token to let it go.
      case req @ DELETE -> Root / "me" / "bots" / team / name =>
        withOwnedBot(session, auth, req, team, name): (user, _) =>
          auth.releaseOwner(team, name, Principal.User(user.id).externalId).flatMap {
            case true  => myBots(auth, user)
            case false => IO.pure(Response[IO](Status.NotFound).withEntity("no such bot"))
          }

      // Rotation, guarded by the echoed name (see ConfirmRotation). The new token is shown exactly once, exactly as the
      // bot's own rotation route does.
      case req @ POST -> Root / "me" / "bots" / team / name / "token" =>
        withOwnedBot(session, auth, req, team, name): (_, bot) =>
          req
            .attemptAs[ConfirmRotation]
            .value
            .flatMap {
              case Left(failure)                                            => BadRequest(failure.message)
              case Right(body) if !body.confirm.trim.equalsIgnoreCase(name) =>
                BadRequest("confirm must be the bot's name — rotation takes a running bot offline")
              case Right(_) =>
                auth.rotate(bot).flatMap {
                  case Some(token) => Ok(RotatedToken(token))
                  case None        => Forbidden("only a registered bot can rotate its token")
                }
            }

      case req @ POST -> Root / "me" / "bots" / team / name / "ladder" / "join" =>
        withOwnedBot(session, auth, req, team, name)((_, bot) => BotRoutes.setLadder(auth, bot, onLadder = true))

      case req @ POST -> Root / "me" / "bots" / team / name / "ladder" / "leave" =>
        withOwnedBot(session, auth, req, team, name)((_, bot) => BotRoutes.setLadder(auth, bot, onLadder = false))

      case req @ POST -> Root / "me" / "bots" / team / name / "open-to-humans" =>
        withOwnedBot(session, auth, req, team, name)((_, bot) => BotRoutes.openToHumans(auth, req, bot))

      case req @ POST -> Root / "me" / "bots" / team / name / "open-to-humans" / "leave" =>
        withOwnedBot(session, auth, req, team, name)((_, bot) => BotRoutes.closeToHumans(auth, bot))

      case req @ GET -> Root / "me" / "bots" / team / name / "capacity" =>
        withOwnedBot(session, auth, req, team, name): (_, bot) =>
          BotRoutes.respondCapacity(auth.seatPolicyOf(bot), registry, bot)

      case req @ POST -> Root / "me" / "bots" / team / name / "capacity" =>
        withOwnedBot(session, auth, req, team, name)((_, bot) => BotRoutes.setCapacity(auth, registry, req, bot))

  /** The owner gate: a session, then ownership of THIS bot. `ratingOf` is the read that carries `ownerExternalId`, so
    * absence means "not a registered bot" (404) and a mismatch means "not yours" (403) — see the object's own doc for
    * why the two are told apart rather than collapsed.
    */
  private def withOwnedBot(
      session: AuthSession,
      auth: BotAuth,
      req: Request[IO],
      team: String,
      name: String
  )(action: (UserAccount, Principal.Bot) => IO[Response[IO]]): IO[Response[IO]] =
    session.withUser(req): user =>
      val bot: Principal.Bot = Principal.Bot(team, name)
      auth.ratingOf(bot).flatMap {
        case None         => IO.pure(Response[IO](Status.NotFound).withEntity("no such bot"))
        case Some(rating) =>
          if rating.ownerExternalId.contains(Principal.User(user.id).externalId) then action(user, bot)
          else IO.pure(Response[IO](Status.Forbidden).withEntity("you do not own that bot"))
      }

  private def myBots(auth: BotAuth, user: UserAccount): IO[Response[IO]] =
    auth
      .botsOwnedBy(Principal.User(user.id).externalId)
      .flatMap: owned =>
        Ok(
          MyBots(
            owned.map(b => MyBot(b.team, b.name, b.rating, b.rd, b.onLadder, b.openToHumans))
          )
        )
