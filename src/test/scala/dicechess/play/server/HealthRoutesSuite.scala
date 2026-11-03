package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.RulesVersion
import io.circe.Json
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{Method, Request, Status}

class HealthRoutesSuite extends munit.CatsEffectSuite:

  private val routes = HealthRoutes("1.2.3").orNotFound

  test("GET /health anonymously reports the server build and resolved rules versions"):
    routes
      .run(Request[IO](Method.GET, uri"/health"))
      .flatMap: resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          assertEquals(
            body,
            Json.obj(
              "status"       -> Json.fromString("ok"),
              "version"      -> Json.fromString("1.2.3"),
              "rulesVersion" -> Json.fromString(RulesVersion.value)
            )
          )
        }

  test("GET /version returns the build version"):
    routes
      .run(Request[IO](Method.GET, uri"/version"))
      .flatMap: resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[String].map(v => assertEquals(v, "1.2.3"))
