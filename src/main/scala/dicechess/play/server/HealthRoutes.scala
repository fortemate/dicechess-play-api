package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.RulesVersion
import io.circe.Codec
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*

final case class Health(status: String, version: String, rulesVersion: String) derives Codec.AsObject

/** Liveness, server build and rules dependency versions (`GET /health`); `GET /version` stays plain text. */
object HealthRoutes:

  def apply(version: String): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "health"  => Ok(Health("ok", version, RulesVersion.value))
      case GET -> Root / "version" => Ok(version)
