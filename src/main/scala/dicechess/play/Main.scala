package dicechess.play

import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.*
import dicechess.play.server.HealthRoutes
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server

/** Boots the authoritative HTTP/WebSocket server. */
object Main extends IOApp.Simple:

  private val host    = host"0.0.0.0"
  private val port    = Port.fromString(sys.env.getOrElse("PORT", "8080")).getOrElse(port"8080")
  val version: String = sys.env.getOrElse("APP_VERSION", "dev")

  def serverResource: Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(HealthRoutes(version).orNotFound)
      .build

  override def run: IO[Unit] =
    serverResource.useForever
