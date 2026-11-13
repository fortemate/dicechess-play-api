package dicechess.play.server

import cats.effect.{Deferred, IO}
import cats.effect.std.Queue
import dicechess.play.core.{GameId, Principal, Seat}
import dicechess.play.game.GameRoom
import fs2.{Pipe, Pull, Stream}
import io.circe.Json
import io.circe.parser.parse
import org.http4s.{HttpRoutes, Request}
import org.http4s.dsl.io.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import scodec.bits.ByteVector

import scala.concurrent.duration.*

/** Laboratory-only first-message authentication. Capabilities never enter upgrade URLs or headers. No room
  * subscription, presence or command processing starts before the bounded authentication succeeds.
  */
object LaboratoryWebSocket:
  private val Refused                = WebSocketFrame.Close(ByteVector.fromShort(1008.toShort))
  private val MaxAuthenticationBytes = 1024

  final private case class Authorized(room: GameRoom, seat: Seat, guest: String)
  private type Decision = Either[WebSocketFrame.Close, Authorized]

  def apply(
      registry: PlayRegistry,
      wsb: WebSocketBuilder2[IO],
      session: Option[AuthSession] = None,
      authenticationTimeout: FiniteDuration = 5.seconds
  ): HttpRoutes[IO] = HttpRoutes.of[IO]:
    // The explicit spectator route remains public. Every seat URL must be query-free; in particular
    // the old token query is rejected rather than silently falling back to classic authentication.
    case req @ GET -> Root / "games" / id / "ws" if req.uri.query.renderString != "mode=spectator" =>
      if req.uri.query.renderString.nonEmpty then BadRequest("laboratory seat sockets require a query-free URL")
      else
        registry
          .get(GameId(id))
          .flatMap:
            case None    => NotFound()
            case Some(_) =>
              for
                decision     <- Deferred[IO, Decision]
                privateQueue <- Queue.unbounded[IO, WebSocketFrame]
                outgoing = Stream
                  .eval(
                    decision.get.timeoutTo(
                      authenticationTimeout,
                      decision.complete(Left(Refused)).void *> decision.get
                    )
                  )
                  .flatMap:
                    case Left(close)       => Stream.emit(close)
                    case Right(authorized) =>
                      val acknowledgement = WebSocketFrame.Text(
                        Json
                          .obj("LabAuthenticated" -> Json.obj("seat" -> Json.fromString(authorized.seat.toString)))
                          .noSpaces
                      )
                      Stream.emit(acknowledgement) ++ PlayRoutes.clientFrames(
                        authorized.room,
                        PlayRoutes.DefaultKeepAlive,
                        Stream.fromQueueUnterminated(privateQueue)
                      )
                // Authenticate before buffering any fragmented application message. Browser clients send one text
                // message per command, so the laboratory transport deliberately requires complete text frames.
                response <- wsb
                  .withDefragment(false)
                  .build(
                    outgoing,
                    receive(registry, session, req, GameId(id), decision, privateQueue)
                  )
              yield response

  private def credentials(frame: WebSocketFrame): Option[(String, String)] = frame match
    case WebSocketFrame.Text(text, true) if text.length <= MaxAuthenticationBytes =>
      if text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MaxAuthenticationBytes then None
      else
        for
          json  <- parse(text).toOption
          root  <- json.asObject.filter(_.keys.toSet == Set("LabAuthenticate"))
          body  <- root("LabAuthenticate").flatMap(_.asObject).filter(_.keys.toSet == Set("token", "guest"))
          token <- body("token").flatMap(_.asString).filter(_.matches("[A-Za-z0-9_-]{1,256}"))
          guest <- body("guest").flatMap(_.asString).filter(Principal.guest(_).isRight)
        yield (token, guest)
    case _ => None

  private def authorize(registry: PlayRegistry, id: GameId, frame: WebSocketFrame): IO[Decision] =
    credentials(frame) match
      case None                 => IO.pure(Left(Refused))
      case Some((token, guest)) =>
        // Re-read after authentication: a room can expire while the first message is pending.
        registry.get(id).map(_.flatMap(room => room.seatFor(token).map(Authorized(room, _, guest))).toRight(Refused))

  private def receive(
      registry: PlayRegistry,
      session: Option[AuthSession],
      request: Request[IO],
      id: GameId,
      decision: Deferred[IO, Decision],
      privateQueue: Queue[IO, WebSocketFrame]
  ): Pipe[IO, WebSocketFrame, Unit] =
    def first(in: Stream[IO, WebSocketFrame]): Pull[IO, Unit, Unit] = in.pull.uncons1.flatMap:
      case Some((_: WebSocketFrame.Ping | _: WebSocketFrame.Pong, tail)) => first(tail)
      case Some((frame, tail))                                           =>
        val authenticated = Stream
          .eval(authorize(registry, id, frame))
          .flatMap: result =>
            Stream
              .eval(decision.complete(result))
              .flatMap:
                case false => Stream.never[IO]
                case true  =>
                  result match
                    case Left(_)           => Stream.never[IO]
                    case Right(authorized) =>
                      Stream.eval(
                        PlayRoutes
                          .claimSeatQuietly(registry, session, request, id, authorized.seat, Some(authorized.guest)) *>
                          authorized.room
                            .drawOfferArmedStatus(authorized.seat)
                            .flatMap(status => privateQueue.offer(PlayRoutes.armedFrame(status)))
                      ) >> tail.through(PlayRoutes.fromClient(authorized.room, authorized.seat, Some(privateQueue)))
        authenticated.pull.echo
      case None => Pull.eval(decision.complete(Left(Refused)).void)
    in => first(in).stream
