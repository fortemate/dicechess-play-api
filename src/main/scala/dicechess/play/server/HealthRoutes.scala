package dicechess.play.server

import cats.effect.IO
import io.circe.Codec
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*

final case class Health(status: String, version: String) derives Codec.AsObject

/** Liveness and build version — for deploy health checks (`GET /health`) and `GET /version`. */
object HealthRoutes:

  def apply(version: String): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "health"  => Ok(Health("ok", version))
      case GET -> Root / "version" => Ok(version)
