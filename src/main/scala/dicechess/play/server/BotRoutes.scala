package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{Challenge, Clocks, GameCommand, GameId, GameStatus, Principal, Seat, TimeControl}
import dicechess.play.game.GameRoom
import dicechess.play.store.BotSeatPolicy
import dicechess.play.wire.Codecs.given
import fs2.Stream
import io.circe.derivation.ConfiguredCodec
import io.circe.{Codec, Encoder}
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.{Authorization, `Content-Type`, `Retry-After`, `WWW-Authenticate`}
import org.http4s.{AuthScheme, Challenge as HttpChallenge, Credentials, HttpRoutes, MediaType, Request, Response}
import org.typelevel.ci.*

import scala.concurrent.duration.*

final case class BotAccount(team: String, name: String, id: String) derives Codec.AsObject

/** `POST /bot/challenge` body. `rated` (#282) is the challenger's choice and rides on the offer, so the target sees it
  * in `GET /bot/challenges` before accepting. `ConfiguredCodec` so its default survives a body that omits it — the trap
  * #279 fell into (see the `Configuration` given in `wire/Codecs.scala`).
  */
final case class ChallengeTarget(
    team: String,
    name: String,
    timeControl: Option[TimeControl] = None,
    rated: Boolean = false
) derives ConfiguredCodec
final case class BotGame(gameId: String) derives Codec.AsObject

/** `POST /bot/game/{id}/move` body. Optional `offerDraw` piggybacks on the completed turn (#327). Optional `acceptDraw`
  * accepts an incoming draw offer without moving. `ConfiguredCodec` so defaults survive bodies that omit them.
  */
final case class BotMove(
    moves: List[String] = Nil,
    offerDraw: Boolean = false,
    acceptDraw: Option[Boolean] = None
) derives ConfiguredCodec
final case class BotSeed(seed: String) derives Codec.AsObject

/** Credentials returned by `POST /bot/anon`: the Bearer token plus the minted anonymous identity. */
final case class AnonBot(token: String, team: String, name: String, id: String) derives Codec.AsObject

/** A registration request: the durable identity to claim. Both parts must be lowercase slugs. */
final case class RegisterBot(team: String, name: String) derives Codec.AsObject

/** Credentials returned by `POST /bot/register`: the Bearer token (shown exactly once — only its hash is stored) plus
  * the claimed durable identity.
  */
final case class BotRegistered(token: String, team: String, name: String, id: String) derives Codec.AsObject

/** The fresh token from `POST /bot/token` (rotation). Shown exactly once; the old token is already invalid. */
final case class RotatedToken(token: String) derives Codec.AsObject

/** The caller's rating-ladder state after `POST /bot/ladder/join` or `/leave`. */
final case class LadderStatus(onLadder: Boolean) derives Codec.AsObject

/** `POST /bot/open-to-humans` body — optional: a catalog description to set while opening the bot (ADR-0014). */
final case class SetOpenToHumans(description: Option[String] = None) derives Codec.AsObject:
  /** Cap the free-text blurb before it reaches the `text` column or a catalog card: unchanged when within the limit, a
    * `Left` message otherwise, which the route turns into a 400.
    */
  def validate: Either[String, SetOpenToHumans] =
    if description.exists(_.length > SetOpenToHumans.MaxDescriptionLength) then
      Left(s"description must be at most ${SetOpenToHumans.MaxDescriptionLength} characters")
    else Right(this)

object SetOpenToHumans:
  /** Upper bound on a catalog description — room for a sentence or two, short enough to keep catalog cards tidy. */
  val MaxDescriptionLength = 200

/** The catalog opt-in state returned by `POST /bot/open-to-humans[/leave]` (ADR-0014). */
final case class OpenToHumans(openToHumans: Boolean, description: Option[String]) derives Codec.AsObject

/** `POST /bot/capacity` body (#189): how many games this bot is willing to hold at once. */
final case class SetCapacity(maxConcurrentGames: Int) derives Codec.AsObject:
  /** Range-check before the write — the `bots` table's own check constraint is a backstop, not the validation, and a
    * constraint violation would surface as a 500 rather than the 400 this deserves.
    */
  def validate: Either[String, SetCapacity] =
    if BotSeatPolicy.isDeclarable(maxConcurrentGames) then Right(this)
    else
      Left(
        s"maxConcurrentGames must be between ${BotSeatPolicy.DefaultMaxConcurrentGames} " +
          s"and ${BotSeatPolicy.MaxDeclarableConcurrentGames}"
      )

/** The declared-capacity state returned by `GET`/`POST /bot/capacity` (#189).
  *
  * `ladderAllowance` and `activeGames` are what make a low limit legible: without them a bot that is rarely paired
  * cannot tell "I said one game at a time and I am playing it" apart from "the server is ignoring me".
  */
final case class Capacity(
    maxConcurrentGames: Int,
    openToHumans: Boolean,
    ladderAllowance: Int,
    activeGames: Int
) derives Codec.AsObject

/** A bot's open-seek offer: the time control and whether it plays for rating — the identity comes from the Bearer
  * token.
  *
  * `rated` (#279) is the bot's own choice when posting the seek — never inferred from being a bot. A durably registered
  * bot is never anonymous, but an anon-team bot (`POST /bot/anon`) IS anonymous per `GameRegistry.isAnonymous` and gets
  * silently downgraded to casual by `Lobby.create`, same as a guest creator.
  *
  * `ConfiguredCodec`, not `Codec.AsObject`: `rated`'s default has to survive a body that omits it, or every bot written
  * before #279 breaks. See the `Configuration` given in `wire/Codecs.scala`.
  */
final case class BotCreateSeek(timeControl: Option[TimeControl] = None, rated: Boolean = false) derives ConfiguredCodec

/** The challenge-create response: the pending challenge's fields plus whether the target currently holds an account
  * stream. Advisory — an offline target can still discover the challenge via `GET /bot/challenges` until it expires.
  */
final case class ChallengeCreated(
    id: String,
    challenger: Principal,
    target: Principal,
    timeControl: TimeControl,
    rated: Boolean,
    targetOnline: Boolean
) derives Codec.AsObject

/** The caller's pending challenges: addressed to it (`in` — accept/decline by id) and created by it (`out`). */
final case class BotChallenges(in: List[Challenge], out: List[Challenge]) derives Codec.AsObject

/** A live game the caller is seated in — enough to decide whether to act; fetch `GET /games/{id}` for the position and
  * `GET /games/{id}/moves` for the legal-move tree.
  */
final case class BotActiveGame(
    gameId: String,
    seat: Seat,
    activeSeat: Seat,
    dicePending: Boolean,
    timeControl: TimeControl,
    clocks: Option[Clocks],
    version: Long
) derives Codec.AsObject

final case class BotGames(games: List[BotActiveGame]) derives Codec.AsObject

/** The synchronous verdict on a submitted turn: `applied` with the `TurnPlayed` version (200), or refused with the same
  * reason the stream's `Rejected` carries (409). A fire-and-forget bot simply ignores the body.
  */
final case class MoveOutcome(applied: Boolean, version: Option[Long] = None, reason: Option[String] = None)
    derives Codec.AsObject

/** The third-party Bot API (Lichess-shaped): identity, the per-bot event stream, the challenge lifecycle, and the game
  * play surface (game event stream + move/resign).
  */
object BotRoutes:

  private val bearerChallenge = `WWW-Authenticate`(HttpChallenge("Bearer", "dicechess-bot"))
  private val ndjsonType      = MediaType.unsafeParse("application/x-ndjson")

  /** ndjson keep-alive cadence — under the ember server's 60s read-idle so an idle stream (a bot waiting for a
    * challenge, or the gap between turns) isn't dropped at either end. The blank line is ignored by clients.
    */
  private val KeepAlive: FiniteDuration = 25.seconds

  /** How long a move submit waits for the writer's verdict before degrading to the legacy fire-and-forget 202. The
    * writer answers in microseconds; this only bites if the room fiber is wedged — never hang the HTTP call on it.
    */
  private val VerdictTimeout: FiniteDuration = 5.seconds

  private object NameParam extends OptionalQueryParamDecoderMatcher[String]("name")

  def apply(
      auth: BotAuth,
      challenges: Challenges,
      events: BotEvents,
      registry: GameRegistry,
      lobby: Lobby,
      limiter: AnonMintLimiter,
      registerLimiter: AnonMintLimiter,
      // Optional because the bot API predates accounts and must keep working without them: with a session present,
      // registering while signed in records the owner in the same INSERT instead of needing a follow-up claim (#253).
      session: Option[AuthSession] = None
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      // Zero-registration self-service: mint an ephemeral, unranked anonymous bot token for testing.
      // The only un-gated route, so it carries its own per-IP rate limit.
      case req @ POST -> Root / "bot" / "anon" :? NameParam(name) =>
        limiter
          .attempt(clientIp(req))
          .flatMap:
            case Left(retryAfter) =>
              TooManyRequests("anonymous mint rate limit exceeded — retry later")
                .map(_.putHeaders(`Retry-After`.unsafeFromLong(math.max(1L, retryAfter.toSeconds))))
            case Right(()) =>
              auth.mintAnon(name).flatMap((token, bot) => Created(AnonBot(token, bot.team, bot.name, bot.externalId)))

      // Durable self-service identity (#70): unlike /bot/anon, the identity survives server restarts, so together
      // with GET /bot/games a registered bot resumes its games after a deploy instead of forfeiting them. Un-gated
      // like the anon mint, with its own (stricter) per-IP budget.
      case req @ POST -> Root / "bot" / "register" =>
        registerLimiter
          .attempt(clientIp(req))
          .flatMap:
            case Left(retryAfter) =>
              TooManyRequests("registration rate limit exceeded — retry later")
                .map(_.putHeaders(`Retry-After`.unsafeFromLong(math.max(1L, retryAfter.toSeconds))))
            case Right(()) =>
              req
                .attemptAs[RegisterBot]
                .value
                .flatMap:
                  case Left(failure) => BadRequest(failure.message)
                  case Right(body)   =>
                    AuthSession
                      .principalFor(session, req)
                      .flatMap(owner => auth.register(body.team, body.name, owner.map(_.externalId)))
                      .flatMap:
                        case Right((token, bot)) =>
                          Created(BotRegistered(token, bot.team, bot.name, bot.externalId))
                        case Left(BotAuth.RegisterRejected.InvalidSlug) =>
                          BadRequest("team and name must be lowercase slugs: [a-z0-9][a-z0-9-]*, at most 32 chars")
                        case Left(BotAuth.RegisterRejected.ReservedTeam) =>
                          BadRequest("this team is reserved")
                        case Left(BotAuth.RegisterRejected.Taken) =>
                          Conflict("this bot identity is already taken")

      // Rotate the caller's token: the old one stops authenticating immediately, the new one is shown exactly once.
      // Registered bots only — anon tokens are re-minted, static ones live in the server env.
      case req @ POST -> Root / "bot" / "token" =>
        withBot(auth, req): bot =>
          auth
            .rotate(bot)
            .flatMap:
              case Some(token) => Ok(RotatedToken(token))
              case None        => Forbidden("only a registered bot can rotate its token")

      case req @ GET -> Root / "bot" / "account" =>
        withBot(auth, req)(bot => Ok(BotAccount(bot.team, bot.name, bot.externalId)))

      // Join/leave the rating ladder (#100). Registered bots only — static (house) and anonymous bots have no row
      // to opt in; same "not a registered identity" gate as token rotation.
      case req @ POST -> Root / "bot" / "ladder" / "join" =>
        withBot(auth, req)(bot => setLadder(auth, bot, onLadder = true))

      case req @ POST -> Root / "bot" / "ladder" / "leave" =>
        withBot(auth, req)(bot => setLadder(auth, bot, onLadder = false))

      // Catalog opt-in (ADR-0014, #152): a registered bot advertises itself as playable by humans. Optional body sets
      // the catalog description. Independent of the ladder — a bot can be on either, both, or neither.
      case req @ POST -> Root / "bot" / "open-to-humans" =>
        withBot(auth, req)(bot => openToHumans(auth, req, bot))
      case req @ POST -> Root / "bot" / "open-to-humans" / "leave" =>
        withBot(auth, req)(bot => closeToHumans(auth, bot))

      // Declared capacity (#189): the bot's half of the load contract — how many games it is willing to hold at once,
      // the counterpart of the per-turn window the server publishes. Enforced when a game is seated, never by holding
      // back a delivery inside a running game (that would burn the bot's own clock). Registered bots only: a static or
      // anonymous identity has no row to declare on, and stays unbounded.
      case req @ GET -> Root / "bot" / "capacity" =>
        withBot(auth, req)(bot => respondCapacity(auth.seatPolicyOf(bot), registry, bot))
      case req @ POST -> Root / "bot" / "capacity" =>
        withBot(auth, req)(bot => setCapacity(auth, registry, req, bot))

      case req @ GET -> Root / "bot" / "stream" / "event" =>
        withBot(auth, req): bot =>
          Ok(ndjson(events.stream(bot))).map(_.withContentType(`Content-Type`(ndjsonType)))

      case req @ POST -> Root / "bot" / "challenge" =>
        withBot(auth, req): bot =>
          req
            .attemptAs[ChallengeTarget]
            .value
            .flatMap:
              case Left(failure) => BadRequest(failure.message)
              case Right(target) =>
                challenges
                  .create(
                    bot,
                    Principal.Bot(target.team, target.name),
                    // Bot-vs-bot only (the target is a `Principal.Bot`), so no human can be left waiting on a
                    // clockless board — this path keeps the old `Unlimited` default on purpose. Corpus and
                    // self-play runs want a board with no clock, and imposing `TimeControl.Default` here would
                    // start flagging the heavy search bots that currently think for as long as they need.
                    target.timeControl.getOrElse(TimeControl.Unlimited),
                    rated = target.rated
                  )
                  .flatMap:
                    case Right(created) =>
                      val ch = created.challenge
                      Created(
                        ChallengeCreated(
                          ch.id,
                          ch.challenger,
                          ch.target,
                          ch.timeControl,
                          ch.rated,
                          created.targetOnline
                        )
                      )
                    case Left(Challenges.CreateRejected.SelfChallenge) =>
                      BadRequest("a bot cannot challenge itself")
                    case Left(Challenges.CreateRejected.TooManyPending) =>
                      TooManyRequests("too many pending challenges — accept, decline, or let them expire")

      // The polling counterpart of the account stream: pending challenges involving the caller. `in` entries are
      // claimable by id; an `out` entry vanishing means it was accepted (see GET /bot/games), declined, or expired.
      case req @ GET -> Root / "bot" / "challenges" =>
        withBot(auth, req): bot =>
          challenges.listFor(bot).flatMap((in, out) => Ok(BotChallenges(in, out)))

      // The polling counterpart of `GameStart` — and the post-restart recovery path: every live game the caller is
      // seated in, whether or not it ever saw the start event. The registry serves this from a per-player index, so
      // the cost is O(the caller's games), not O(every game on the node).
      case req @ GET -> Root / "bot" / "games" =>
        withBot(auth, req): bot =>
          registry
            .gamesFor(bot)
            .flatMap(_.traverse { (id, room) =>
              (seatOf(room, bot), room.snapshot).mapN: (seat, s) =>
                // A just-ended room can linger until the registry evicts it; a listing is for live games only.
                seat
                  .filter(_ => s.status == GameStatus.Active)
                  .map: st =>
                    BotActiveGame(id.value, st, s.activeSeat, s.dicePending, s.timeControl, s.clocks, s.version)
            })
            .flatMap(games => Ok(BotGames(games.flatten)))

      // How bots meet humans (#72): post a standing public offer in the SAME lobby guests use. Hold it by polling the
      // capability-gated GET /lobby/seeks/{id}?secret= (bot seeks get a lazier TTL, sized for a poll-only bot); once
      // matched, the game also shows up in GET /bot/games — no seat token needed, bots are seated by principal.
      case req @ POST -> Root / "bot" / "seeks" =>
        withBot(auth, req): bot =>
          req
            .attemptAs[BotCreateSeek]
            .value
            .flatMap:
              case Left(failure) => BadRequest(failure.message)
              case Right(body)   =>
                lobby
                  .create(bot, body.timeControl.getOrElse(TimeControl.Default), rated = body.rated)
                  .flatMap:
                    case Right((seek, secret))                       => Created(CreatedSeek(seek.id, secret))
                    case Left(Lobby.CreateRejected.TooManyOpenSeeks) =>
                      TooManyRequests("too many open seeks — cancel one or let them expire")

      // The mirror flow: a bot accepts an open (human- or bot-created) seek.
      case req @ POST -> Root / "bot" / "seeks" / id / "accept" =>
        withBot(auth, req): bot =>
          lobby
            .accept(id, bot)
            .flatMap:
              case Right(m)                          => Created(BotGame(m.gameId))
              case Left(Lobby.Rejected.NotFound)     => NotFound()
              case Left(Lobby.Rejected.AlreadyTaken) => Conflict()
              case Left(Lobby.Rejected.OwnSeek)      => BadRequest("cannot accept your own seek")
              case Left(Lobby.Rejected.Busy)         =>
                Conflict("a side is at its concurrent-game limit — the seek is still open")
              // Reachable: an anon-team bot (`POST /bot/anon`) authenticates through this same Bearer flow and is
              // anonymous per `GameRegistry.isAnonymous`, so it can hit this branch trying to accept a rated seek.
              case Left(Lobby.Rejected.RequiresAccount) => Forbidden("that seek requires a registered opponent")
              case Left(Lobby.Rejected.Failed(error))   => BadRequest(error)

      case req @ POST -> Root / "bot" / "challenge" / id / "accept" =>
        withBot(auth, req): bot =>
          challenges
            .accept(bot, id)
            .flatMap:
              case Right(gameId)                      => Created(BotGame(gameId))
              case Left(Challenges.Rejected.NotFound) => NotFound()
              case Left(Challenges.Rejected.NotYours) => Forbidden()
              // Still pending: accept it again once a game finishes, or let it expire (#189).
              case Left(Challenges.Rejected.Busy) =>
                Conflict("a side is at its concurrent-game limit — the challenge is still pending")
              case Left(Challenges.Rejected.Failed(why)) => InternalServerError(why)

      case req @ POST -> Root / "bot" / "challenge" / id / "decline" =>
        withBot(auth, req): bot =>
          challenges
            .decline(bot, id)
            .flatMap:
              case Right(())                          => Ok()
              case Left(Challenges.Rejected.NotFound) => NotFound()
              case Left(Challenges.Rejected.NotYours) => Forbidden()
              // Unreachable: declining seats nobody, so the capacity check never runs on this path.
              case Left(Challenges.Rejected.Busy)        => InternalServerError("capacity refusal on decline")
              case Left(Challenges.Rejected.Failed(why)) => InternalServerError(why)

      case req @ GET -> Root / "bot" / "game" / "stream" / id =>
        withBot(auth, req): bot =>
          seated(registry, id, bot): (room, _) =>
            Ok(ndjson(room.subscribe)).map(_.withContentType(`Content-Type`(ndjsonType)))

      case req @ POST -> Root / "bot" / "game" / id / "seed" =>
        withBot(auth, req): bot =>
          req
            .attemptAs[BotSeed]
            .value
            .flatMap:
              case Left(failure) => BadRequest(failure.message)
              case Right(body)   =>
                // Provably-fair dice (#13): contribute post-commit entropy before the opening roll. Fire-and-forget;
                // a too-late, duplicate, or malformed seed is dropped (or surfaces as `Rejected` on the stream).
                seated(registry, id, bot)((room, seat) =>
                  room.submit(seat, GameCommand.SubmitSeed(body.seed)) *> Accepted()
                )

      case req @ POST -> Root / "bot" / "game" / id / "move" =>
        withBot(auth, req): bot =>
          req
            .attemptAs[BotMove]
            .value
            .flatMap:
              case Left(failure) => BadRequest(failure.message)
              case Right(move)   =>
                // Synchronous verdict (the writer processes a command in microseconds — validation is a cache lookup):
                // 200 applied with the TurnPlayed version, 409 refused with the stream Rejected's reason. The stream
                // events are unchanged; a wedged writer degrades to the old fire-and-forget 202 instead of hanging.
                seated(registry, id, bot)((room, seat) =>
                  val action = move.acceptDraw match
                    case Some(accept) => room.respondDraw(seat, accept)
                    case None         => room.submitTurn(seat, move.moves, offerDraw = move.offerDraw)
                  action
                    .flatMap:
                      case GameRoom.TurnVerdict.Applied(version) =>
                        Ok(MoveOutcome(applied = true, version = Some(version)))
                      case GameRoom.TurnVerdict.Refused(reason) =>
                        Conflict(MoveOutcome(applied = false, reason = Some(reason)))
                    .timeoutTo(VerdictTimeout, Accepted())
                )

      case req @ POST -> Root / "bot" / "game" / id / "draw" / ("accept" | "yes") =>
        withBot(auth, req): bot =>
          seated(registry, id, bot)((room, seat) =>
            room
              .respondDraw(seat, accept = true)
              .flatMap:
                case GameRoom.TurnVerdict.Applied(version) =>
                  Ok(MoveOutcome(applied = true, version = Some(version)))
                case GameRoom.TurnVerdict.Refused(reason) =>
                  Conflict(MoveOutcome(applied = false, reason = Some(reason)))
              .timeoutTo(VerdictTimeout, Accepted())
          )

      case req @ POST -> Root / "bot" / "game" / id / "draw" / ("decline" | "no") =>
        withBot(auth, req): bot =>
          seated(registry, id, bot)((room, seat) =>
            room
              .respondDraw(seat, accept = false)
              .flatMap:
                case GameRoom.TurnVerdict.Applied(version) =>
                  Ok(MoveOutcome(applied = true, version = Some(version)))
                case GameRoom.TurnVerdict.Refused(reason) =>
                  Conflict(MoveOutcome(applied = false, reason = Some(reason)))
              .timeoutTo(VerdictTimeout, Accepted())
          )

      case req @ POST -> Root / "bot" / "game" / id / "resign" =>
        withBot(auth, req): bot =>
          seated(registry, id, bot)((room, seat) => room.submit(seat, GameCommand.Resign) *> Accepted())

  /** Run `action` against the room and the caller's seat in it; 404 if no such game or the bot isn't seated there. */
  private def seated(registry: GameRegistry, id: String, bot: Principal.Bot)(
      action: (GameRoom, Seat) => IO[Response[IO]]
  ): IO[Response[IO]] =
    registry
      .get(GameId(id))
      .flatMap:
        case None       => NotFound()
        case Some(room) =>
          seatOf(room, bot).flatMap:
            case None       => NotFound()
            case Some(seat) => action(room, seat)

  private def seatOf(room: GameRoom, bot: Principal): IO[Option[Seat]] =
    room.seating.map(_.collectFirst { case (seat, principal) if principal == bot => seat })

  private[server] def setLadder(auth: BotAuth, bot: Principal.Bot, onLadder: Boolean): IO[Response[IO]] =
    auth
      .setOnLadder(bot, onLadder)
      .flatMap:
        case Some(rating) => Ok(LadderStatus(rating.onLadder))
        case None         => Forbidden("only a registered bot can join the rating ladder")

  /** `POST /bot/open-to-humans` — opt in and (re)set the catalog description in one atomic write. A blank body means
    * "no description" (and clears any previous one); any other body is parsed, and a malformed or over-long one is a
    * 400. Unlike the required-body routes, the body is read directly rather than via `attemptAs` + `Content-Length`:
    * the description is optional, and `Content-Length` is absent on chunked/HTTP-2 requests, so keying off it would
    * either 400 a legitimate empty body or silently drop a chunked one. Reading the bytes is transport-independent.
    */
  private[server] def openToHumans(auth: BotAuth, req: Request[IO], bot: Principal.Bot): IO[Response[IO]] =
    catalogDescription(req).flatMap:
      case Left(message)      => BadRequest(message)
      case Right(description) => respondCatalog(auth.openToHumans(bot, description))

  /** The optional catalog-description body, shared by every door that writes one (bot, owner, admin — #273): a blank
    * body means "no description" (and clears any previous one); anything else is parsed and length-validated, `Left`
    * being the 400 message. One parser so the doors cannot drift on what a description is allowed to be.
    */
  private[server] def catalogDescription(req: Request[IO]): IO[Either[String, Option[String]]] =
    req.bodyText.compile.string.map: raw =>
      if raw.isBlank then Right(None)
      else
        io.circe.parser.decode[SetOpenToHumans](raw) match
          case Left(_)     => Left("invalid JSON body")
          case Right(body) => body.validate.map(_.description)

  /** `POST /bot/open-to-humans/leave` — opt out, leaving the description intact. */
  private[server] def closeToHumans(auth: BotAuth, bot: Principal.Bot): IO[Response[IO]] =
    respondCatalog(auth.closeToHumans(bot))

  /** `POST /bot/capacity` — declare the limit. A required body, unlike `open-to-humans`: there is no sensible "empty
    * body" reading of a number, and silently re-applying the default would be indistinguishable from a typo.
    */
  private[server] def setCapacity(
      auth: BotAuth,
      registry: GameRegistry,
      req: Request[IO],
      bot: Principal.Bot
  ): IO[Response[IO]] =
    req
      .attemptAs[SetCapacity]
      .value
      .flatMap:
        case Left(failure) => BadRequest(failure.message)
        case Right(body)   =>
          body.validate match
            case Left(message) => BadRequest(message)
            case Right(valid)  =>
              respondCapacity(auth.setMaxConcurrentGames(bot, valid.maxConcurrentGames), registry, bot)

  /** Shared reply for both capacity routes: the declaration, what the ladder may take of it, and how much is in use
    * right now — or 403 for a non-registered caller (no row to declare on). The active count is read after the policy
    * so a fresh declaration is reported against a current count, not a stale one.
    */
  private[server] def respondCapacity(
      policy: IO[Option[BotSeatPolicy]],
      registry: GameRegistry,
      bot: Principal.Bot
  ): IO[Response[IO]] =
    policy.flatMap:
      case None    => Forbidden("only a registered bot can declare a concurrent-game limit")
      case Some(p) =>
        registry
          .activeGamesFor(bot)
          .flatMap(active => Ok(Capacity(p.maxConcurrentGames, p.openToHumans, p.ladderAllowance, active)))

  /** Shared reply: the resulting catalog state as `OpenToHumans`, or 403 for a non-registered caller (no row to flag).
    */
  private[server] def respondCatalog(state: IO[Option[dicechess.play.store.BotCatalogState]]): IO[Response[IO]] =
    state.flatMap:
      case Some(s) => Ok(OpenToHumans(s.openToHumans, s.description))
      case None    => Forbidden("only a registered bot can open itself to human games")

  /** Run `f` with the authenticated bot, or answer 401 with a Bearer challenge. Package-visible so sibling route
    * modules of the same Bot API surface (`WebhookRoutes`) authenticate identically instead of re-deriving this.
    */
  private[server] def withBot(auth: BotAuth, req: Request[IO])(f: Principal.Bot => IO[Response[IO]]): IO[Response[IO]] =
    asBot(auth, req).flatMap:
      case Some(bot) => f(bot)
      case None      => Unauthorized(bearerChallenge)

  /** The authenticated bot for a request, if its Bearer token is valid (static or live anonymous). */
  /** Package-visible so the owner surface can require BOTH credentials on one request (#253): `MeRoutes` needs the
    * bot's identity from its Bearer token while the session says who is claiming it.
    */
  private[server] def asBot(auth: BotAuth, req: Request[IO]): IO[Option[Principal.Bot]] =
    bearer(req) match
      case None        => IO.pure(None)
      case Some(token) => auth.authenticate(token).map(_.collect { case bot: Principal.Bot => bot })

  private def bearer(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) =>
      token
    }

  /** Client IP for rate-limiting. Behind the Cloudflare tunnel or a private reverse proxy, the socket peer is a
    * loopback or private bridge address, so the real client is in `CF-Connecting-IP` (or `X-Forwarded-For`). If a
    * request arrives directly from an untrusted public peer, forwarding headers are ignored to prevent spoofing.
    */
  private[server] def clientIp(req: Request[IO]): String =
    val isTrusted = req.remoteAddr.forall(ip => ip.isLoopback || ip.isPrivate)
    val forwarded =
      if isTrusted then
        req.headers
          .get(ci"CF-Connecting-IP")
          .map(_.head.value.trim)
          .orElse(req.headers.get(ci"X-Forwarded-For").map(_.head.value.split(',').head.trim))
      else None

    forwarded
      .orElse(req.remoteAddr.map(_.toString))
      .filter(_.nonEmpty)
      .getOrElse("unknown")

  private[server] def ndjson[A: Encoder](
      events: Stream[IO, A],
      keepAlive: FiniteDuration = KeepAlive
  ): Stream[IO, Byte] =
    val lines      = events.map(_.asJson.noSpaces + "\n")
    val keepAlives = Stream.awakeEvery[IO](keepAlive).as("\n") // blank line; clients drop it
    lines.mergeHaltL(keepAlives).through(fs2.text.utf8.encode)
