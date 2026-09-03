package dicechess.play.server

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.game.GameRoom
import dicechess.play.wire.Codecs.given
import fs2.{Pipe, Stream}
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.{HttpRoutes, Request}

import scala.concurrent.duration.*

/** `POST /games` (friend-by-link). `white`/`black` are the anonymous fallback (#235): a signed-in caller is seated from
  * the session on BOTH sides (the creator holds both join tokens and hands one to a friend — token possession, not the
  * label, is what grants the seat) and the fields are ignored; without a session both are required.
  */
final case class CreateGame(
    white: Option[String] = None,
    black: Option[String] = None,
    timeControl: Option[TimeControl] = None
) derives Codec.AsObject
final case class SeatToken(seat: Seat, token: String) derives Codec.AsObject

/** The created game plus a per-seat join token: the creator distributes each token to the player who should hold that
  * seat, and the WebSocket upgrade authorizes the seat from the token (never a trusted `?seat=` param).
  */
final case class CreatedGame(gameId: String, commit: String, tokens: List[SeatToken]) derives Codec.AsObject

/** One live game in the public listing — who plays, how far along it is, and the position itself (`dfen`, so a lobby
  * can render live mini-boards without a per-game round trip). The legal-move tree is deliberately not carried here.
  */
final case class LiveGame(
    gameId: String,
    players: Option[Players],
    timeControl: TimeControl,
    activeSeat: Seat,
    dicePending: Boolean,
    clocks: Option[Clocks],
    version: Long,
    dfen: String
) derives Codec.AsObject

/** The public games listing: up to the cap of live games (most action first) plus the uncapped total. */
final case class LiveGames(games: List[LiveGame], total: Int) derives Codec.AsObject

object PlayRoutes:

  /** WebSocket heartbeat interval — comfortably under Ember's 60s read-idle timeout so the client's pong keeps the
    * connection alive between game events.
    */
  val DefaultKeepAlive: FiniteDuration = 25.seconds

  /** Cap on the public games listing: bounds the payload of an unauthenticated, poll-heavy endpoint. */
  private val MaxListedGames = 50

  private object TokenParam extends OptionalQueryParamDecoderMatcher[String]("token")

  /** The anonymous fallback for identifying whoever redeems a join token (#285): a browser with no account session has
    * only its stable per-visit guest uuid, and the WebSocket handshake carries no body to put it in. Validated as a
    * guest principal exactly like the equivalent body fields, so a malformed value is ignored rather than trusted.
    */
  private object GuestParam extends OptionalQueryParamDecoderMatcher[String]("guest")

  def apply(
      registry: GameRegistry,
      wsb: WebSocketBuilder2[IO],
      session: Option[AuthSession] = None,
      keepAlive: FiniteDuration = DefaultKeepAlive
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case req @ POST -> Root / "games" =>
        // attemptAs (not as): a malformed body is the client's fault, so answer 400, not 500.
        req
          .attemptAs[CreateGame]
          .value
          .flatMap:
            case Left(failure) => BadRequest(failure.message)
            case Right(body)   =>
              AuthSession.principalFor(session, req).flatMap { signedIn =>
                val seats: Either[String, (Principal, Principal)] = signedIn match
                  // Session wins (#235): the creator owns both seats until the share link is used, exactly as the
                  // anonymous flow already seats one guest id on both sides.
                  case Some(user) => Right((user, user))
                  case None       =>
                    def required(field: String, value: Option[String]) =
                      value
                        .toRight(s"$field: required when not signed in")
                        .flatMap(id => Principal.guest(id).left.map(err => s"$field: $err"))
                    for
                      white <- required("white", body.white)
                      black <- required("black", body.black)
                    yield (white, black)
                seats match
                  case Left(err)             => BadRequest(err)
                  case Right((white, black)) =>
                    registry
                      // Friend-by-link games are ALWAYS casual, and #282 decided this deliberately rather than
                      // leaving the default to speak for itself. Both seats belong to the same principal here (the
                      // creator, or — from the SPA's anonymous path — one guest id passed twice), because a friend is
                      // authorized by POSSESSING a seat token, never by being named. So the second player's identity
                      // is not known at creation and is never recorded at all: `game_results` stores the creator on
                      // both sides. Adding a `rated` flag would therefore stamp `rated = true` on a row the batch then
                      // skips as self-play — a lie in the column #279 exists to keep honest. Making these ratable
                      // needs identity-on-seat-claim first, which is a feature, not a flag.
                      .create(white, black, body.timeControl.getOrElse(TimeControl.Default), requestedRated = false)
                      .flatMap:
                        case Left(error)       => BadRequest(error)
                        case Right((id, room)) =>
                          val tokens = room.joinTokens.toList.map((seat, token) => SeatToken(seat, token))
                          room.diceCommit.flatMap(c => Created(CreatedGame(id.value, c, tokens)))
              }

      // Public like the per-game snapshot: everything here is already public information. Feeds the Watch page, the
      // lobby's "top game" preview, and the live-games counter; sorted by version (most action first) and capped —
      // nobody scrolls past MaxListedGames boards, and `total` still carries the real count.
      case GET -> Root / "games" =>
        registry.list
          .flatMap(_.traverse { (id, room) =>
            room.snapshot.map { s =>
              Option.when(s.status == GameStatus.Active):
                LiveGame(id.value, s.players, s.timeControl, s.activeSeat, s.dicePending, s.clocks, s.version, s.dfen)
            }
          })
          .flatMap { entries =>
            val live = entries.flatten.sortBy(-_.version)
            Ok(LiveGames(live.take(MaxListedGames), live.size))
          }

      case GET -> Root / "games" / id =>
        registry
          .get(GameId(id))
          .flatMap:
            case None       => NotFound()
            case Some(room) => room.snapshot.flatMap(Ok(_))

      // Public like the state snapshot above — legal moves are a pure function of the already-public DFEN. Always the
      // full tree: this is the fallback when the inline `legalMoves` on DiceRolled/Snapshot was elided by the cap,
      // and the primary source for polling bots.
      case GET -> Root / "games" / id / "moves" =>
        registry
          .get(GameId(id))
          .flatMap:
            case None       => NotFound()
            case Some(room) => room.legalMoves.flatMap(Ok(_))

      // The seat is resolved from a verified join token. A tokenless connection falls back to the session (#235):
      // a signed-in player occupying EXACTLY one seat reconnects to it — the fix for "lost the ?seat= URL, demoted
      // to spectator forever". Ambiguity (friend-by-link seats the creator on both sides until the link is used)
      // means the session cannot name a seat, so the token stays required there. Anything else spectates, as before.
      case req @ GET -> Root / "games" / id / "ws" :? TokenParam(token) +& GuestParam(guest) =>
        registry
          .get(GameId(id))
          .flatMap:
            case None       => NotFound()
            case Some(room) =>
              token match
                case Some(t) =>
                  room.seatFor(t) match
                    case None       => Forbidden()
                    case Some(seat) =>
                      // #285: redeeming a join token is the only moment the second player of a friend-by-link game is
                      // ever identifiable, so bind the seat to them before the socket opens. `claimSeat` is a no-op for
                      // every game that already has two distinct players, so this costs one seating read otherwise.
                      // The session wins over `guest` (the #235 idiom); a game whose seats already differ ignores both.
                      claimSeatQuietly(registry, session, req, GameId(id), seat, guest) *>
                        wsb.build(clientFrames(room, keepAlive), fromClient(room, seat))
                case None =>
                  AuthSession.principalFor(session, req).flatMap {
                    case None       => wsb.build(clientFrames(room, keepAlive), fromClient(room, Seat.Spectator))
                    case Some(user) =>
                      room.seating.flatMap { seats =>
                        val owned = seats.collect { case (seat, p) if p == user => seat }.toList
                        val seat  = owned match
                          case only :: Nil => only
                          case _           => Seat.Spectator
                        wsb.build(clientFrames(room, keepAlive), fromClient(room, seat))
                      }
                  }

  /** Who is redeeming a join token, for the seat-claim in #285: the account session if there is one, otherwise the
    * `guest` query param — the same "session wins, the anonymous id is only a fallback" order #235 established for the
    * game-start bodies. `None` means nobody identifiable, so nothing is claimed and the socket opens exactly as before.
    *
    * A malformed guest id is dropped rather than rejected: it would only ever cost the caller its own attribution, and
    * failing the WebSocket upgrade over it would break play for a client that got the parameter wrong.
    */
  private def claimerOf(
      session: Option[AuthSession],
      req: Request[IO],
      guest: Option[String]
  ): IO[Option[Principal]] =
    AuthSession.principalFor(session, req).map {
      case some @ Some(_) => some
      case None           => guest.flatMap(Principal.guest(_).toOption)
    }

  /** Attribution must never gate play. Resolving the claimer reads the session store and the claim itself resolves a
    * nickname, so both touch Postgres — and a valid join token has to keep opening its socket through an outage of
    * either. Availability-first, exactly like `GameRoom.persistQuietly`: log and carry on.
    *
    * The log line names the game and seat but never the identity: this runs on an unauthenticated-looking upgrade and a
    * failure is not a reason to write who was trying to sit down.
    */
  private def claimSeatQuietly(
      registry: GameRegistry,
      session: Option[AuthSession],
      req: Request[IO],
      id: GameId,
      seat: Seat,
      guest: Option[String]
  ): IO[Unit] =
    claimerOf(session, req, guest)
      .flatMap(_.traverse_(registry.claimSeat(id, seat, _)))
      .handleErrorWith: e =>
        Console[IO].errorln(s"[play][claim] game ${id.value} seat $seat not attributed: $e")

  /** Frames pushed to a client: the room's event feed merged with periodic WebSocket pings. The browser auto-replies
    * with a pong, and that inbound frame resets the server's read-idle timeout — so a quiet but live game (a player
    * thinking, or the opening dice auto-passing) is not dropped at the 60s idle deadline. Halts with the event feed
    * (which completes on the terminal event), appending an explicit WebSocket Close frame so the socket closes cleanly
    * when the game ends rather than idling out.
    */
  private[server] def clientFrames(room: GameRoom, keepAlive: FiniteDuration): Stream[IO, WebSocketFrame] =
    val events     = room.subscribe.map(event => WebSocketFrame.Text(event.asJson.noSpaces))
    val keepAlives = Stream.awakeEvery[IO](keepAlive).as(WebSocketFrame.Ping())
    events.mergeHaltL(keepAlives) ++ Stream.emit(WebSocketFrame.Close())

  private def fromClient(room: GameRoom, seat: Seat): Pipe[IO, WebSocketFrame, Unit] =
    in =>
      val commands = in.evalMap {
        // Spectators receive events but cannot submit commands.
        case WebSocketFrame.Text(txt, _) if seat.side.isDefined =>
          decode[GameCommand](txt).fold(_ => IO.unit, room.submit(seat, _))
        case _ => IO.unit
      }
      // Hold the seat's presence for the life of the socket. When it drops, the room starts the reconnect grace
      // timer (a no-op for spectators) — so a refresh or a brief blip no longer resigns the game outright.
      Stream.resource(room.connection(seat)) >> commands
