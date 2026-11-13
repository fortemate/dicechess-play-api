package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.GameId
import dicechess.play.wire.Codecs.given
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.typelevel.ci.*

/** Explicit adapter for the isolated API. Main's classic route assembly never installs this adapter or its registry. */
object LaboratoryRoutes:
  private val SeatTokenHeader                  = ci"X-Laboratory-Seat-Token"
  private given Encoder[LaboratoryRematchView] = deriveEncoder[LaboratoryRematchView]
    .mapJson(_.mapObject(_.filter(!_._2.isNull)))

  def apply(
      registry: LaboratoryRegistry,
      wsb: WebSocketBuilder2[IO],
      session: Option[AuthSession] = None
  ): HttpRoutes[IO] =
    PlayRoutes(registry, wsb, session) <+> HttpRoutes.of[IO] {
      case GET -> Root / "games" / id / "transcript" =>
        registry
          .transcript(GameId(id))
          .flatMap:
            case None         => NotFound()
            case Some(record) => Ok(record.payload)

      case req @ POST -> Root / "games" / id / "rematch" =>
        val tokens = req.headers.headers.filter(_.name == SeatTokenHeader).map(_.value)
        // A required non-simple header prevents ambient-cookie CSRF; no token is accepted in a URL or response for
        // the opposite seat. The laboratory deployment separately owns its explicit CORS origin allow-list (#293).
        if tokens.size != 1 || tokens.head.isEmpty || tokens.head.length > 256 then Forbidden()
        else
          req.body
            .take(1025)
            .compile
            .toVector
            .flatMap: bytes =>
              val consent = if bytes.size > 1024 then None
              else
                parse(new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)).toOption
                  .flatMap(_.hcursor.get[Boolean]("consent").toOption)
              consent match
                case None           => BadRequest("expected a bounded consent request")
                case Some(accepted) =>
                  registry
                    .rematch(GameId(id), tokens.head, accepted)
                    .flatMap:
                      case Left(_)     => Forbidden()
                      case Right(view) => Ok(view.asJson)
    }
