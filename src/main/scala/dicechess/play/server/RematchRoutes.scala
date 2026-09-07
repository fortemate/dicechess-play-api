package dicechess.play.server

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.store.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.Origin
import org.typelevel.ci.*
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Try

object RematchRoutes:
  private val SeatHeader = ci"X-Rematch-Seat-Token"
  private val CsrfHeader = ci"X-DiceChess-CSRF"
  private val MaxBody    = 1024L

  def resource(
      registry: GameRegistry,
      pg: Option[PgGameStore],
      session: Option[AuthSession],
      origins: Cors.AllowedOrigins,
      limits: RematchLimiter.Config = RematchLimiter.Config()
  ): Resource[IO, HttpRoutes[IO]] =
    for
      limiter     <- Resource.eval(RematchLimiter.create(limits))
      coordinator <- pg.traverse(RematchCoordinator.resource(registry, _))
    yield new Handler(pg, coordinator, session, origins, limiter).routes

  final private class Handler(
      pg: Option[PgGameStore],
      coordinator: Option[RematchCoordinator],
      session: Option[AuthSession],
      origins: Cors.AllowedOrigins,
      limiter: RematchLimiter
  ):
    private def guarded(req: Request[IO], rawId: String)(
        f: (PgGameStore, RematchCoordinator, GameId) => IO[Response[IO]]
    ) =
      limiter
        .attempt("ip:" + BotRoutes.clientIp(req), req.method == Method.POST)
        .flatMap {
          case false => IO.pure(throttled)
          case true  =>
            uuid(rawId) match
              case None     => IO.pure(problem("game_not_found"))
              case Some(id) =>
                (pg, coordinator) match
                  case (Some(db), Some(service)) => f(db, service, GameId(id.toString))
                  case _                         => IO.pure(problem("temporarily_unavailable"))
        }
        .handleErrorWith(_ => IO.pure(problem("temporarily_unavailable")))
        .map(_.putHeaders(Header.Raw(ci"Cache-Control", "no-store"), Header.Raw(ci"Pragma", "no-cache")))

    private def privateResponse(service: RematchCoordinator, state: RematchRead, seat: Seat, error: Option[String]) =
      state.session match
        case None    => IO.pure(problem("game_not_found"))
        case Some(s) =>
          service.successor(s).map { next =>
            val json = PrivateRematch.of(s, seat, state.serverNow, next).asJson
            error.fold(Response[IO](Status.Ok).withEntity(json))(problem(_, Some(json)))
          }

    private def participant(req: Request[IO], db: PgGameStore, id: GameId)(f: Seat => IO[Response[IO]]) =
      AuthSession.principalFor(session, req).flatMap { account =>
        val token = single(req, SeatHeader).filter(t => t.nonEmpty && t.length <= 512)
        if account.isEmpty && token.isEmpty then IO.pure(problem("authentication_required"))
        else
          db.rematches.session(id).flatMap {
            case None =>
              db.continuationSource(id).map {
                case None    => problem("game_not_found")
                case Some(_) => problem("not_participant")
              }
            case Some(s) =>
              val seat = account match
                case Some(user) => s.source.players.collectFirst { case (seat, owner) if owner == user => seat }
                case None       => token.flatMap(s.source.guestSeat)
              seat match
                case None             => IO.pure(problem("not_participant"))
                case Some(authorized) =>
                  limiter
                    .attempt("actor:" + s.source.players(authorized).externalId, req.method == Method.POST)
                    .flatMap(allowed => if allowed then f(authorized) else IO.pure(throttled))
          }
      }

    private def publicResponse(db: PgGameStore, service: RematchCoordinator, id: GameId): IO[Response[IO]] =
      def project(state: RematchRead): IO[Response[IO]] = state.session match
        case Some(s) =>
          service
            .successor(s)
            .map(next => Response[IO](Status.Ok).withEntity(PublicContinuation.of(s, state.serverNow, next)))
        case None =>
          db.continuationSource(id).flatMap {
            case None          => IO.pure(problem("game_not_found"))
            case Some(waiting) =>
              // The terminal save may have committed between the first read and the existence lookup.
              db.rematchCommands.current(id).flatMap { fresh =>
                fresh.session match
                  case Some(_) => project(fresh)
                  case None    =>
                    IO.pure(
                      Response[IO](Status.Ok).withEntity(
                        PublicContinuation(id.value, if waiting then "waiting" else "closed", fresh.serverNow)
                      )
                    )
              }
          }
      db.rematchCommands.current(id).flatMap(project)

    val routes: HttpRoutes[IO] = HttpRoutes.of[IO]:
      case req @ GET -> Root / "games" / id / "continuation" =>
        guarded(req, id)((db, service, game) => publicResponse(db, service, game))
      case req @ GET -> Root / "games" / id / "rematch" =>
        guarded(req, id) { (db, service, game) =>
          participant(req, db, game)(seat =>
            db.rematchCommands.current(game).flatMap(privateResponse(service, _, seat, None))
          )
        }
      case req @ POST -> Root / "games" / id / "rematch" =>
        guarded(req, id) { (db, service, game) =>
          if !csrf(req, origins) then IO.pure(problem("not_participant"))
          else
            participant(req, db, game) { seat =>
              body(req).flatMap {
                case None                      => IO.pure(problem("invalid_request"))
                case Some((requestId, action)) =>
                  // A disconnected requester cannot strand the durable second consent before worker ownership transfers.
                  IO.uncancelable { poll =>
                    db.rematchCommands
                      .command(game, seat, requestId, action)
                      .flatTap(_.state.session.traverse_(service.ensureCreation))
                      .flatMap(result => poll(privateResponse(service, result.state, seat, result.error)))
                  }
              }
            }
        }

  private def uuid(value: String): Option[UUID] =
    Option
      .when(value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))(value)
      .flatMap(v => Try(UUID.fromString(v)).toOption)

  private def single(req: Request[IO], name: CIString): Option[String] =
    req.headers.get(name).map(_.toList.map(_.value)) match
      case Some(value :: Nil) => Some(value)
      case _                  => None

  private def csrf(req: Request[IO], origins: Cors.AllowedOrigins): Boolean =
    single(req, CsrfHeader).contains("1") && origins.isExplicitlyConfigured &&
      req.headers.get[Origin].exists(origins.allows)

  private def body(req: Request[IO]): IO[Option[(UUID, RematchAction)]] =
    if !req.contentType.exists(_.mediaType == MediaType.application.json) then IO.pure(None)
    else
      req.body.take(MaxBody + 1).compile.to(Array).map { bytes =>
        if bytes.length > MaxBody then None
        else
          for
            json   <- parse(new String(bytes, StandardCharsets.UTF_8)).toOption
            obj    <- json.asObject.filter(_.keys.toSet == Set("requestId", "action"))
            id     <- obj("requestId").flatMap(_.asString).flatMap(uuid)
            action <- obj("action").flatMap(_.asString).flatMap(v => RematchAction.values.find(_.wire == v))
          yield (id, action)
      }

  private def problem(code: String, state: Option[Json] = None): Response[IO] =
    val status = code match
      case "invalid_request"         => Status.BadRequest
      case "authentication_required" => Status.Unauthorized
      case "not_participant"         => Status.Forbidden
      case "game_not_found"          => Status.NotFound
      case "rematch_closed"          => Status.Gone
      case "temporarily_unavailable" => Status.ServiceUnavailable
      case _                         => Status.Conflict
    val fields = List("error" -> Json.obj("code" -> Json.fromString(code))) ++ state.toList.map("state" -> _)
    Response[IO](status).withEntity(Json.obj(fields*))

  private def throttled: Response[IO] =
    Response[IO](Status.TooManyRequests)
      .withEntity(Json.obj("error" -> Json.obj("code" -> Json.fromString("rate_limited"))))
      .putHeaders(Header.Raw(ci"Retry-After", "60"))
