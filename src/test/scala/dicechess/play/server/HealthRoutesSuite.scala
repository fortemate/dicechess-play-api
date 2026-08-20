package dicechess.play.server

import cats.effect.IO
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{Method, Request, Status}

class HealthRoutesSuite extends munit.CatsEffectSuite:

  private val routes = HealthRoutes("1.2.3").orNotFound

  test("GET /health reports ok with the build version"):
    routes
      .run(Request[IO](Method.GET, uri"/health"))
      .flatMap: resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Health].map(h => assertEquals(h, Health("ok", "1.2.3")))

  test("GET /version returns the build version"):
    routes
      .run(Request[IO](Method.GET, uri"/version"))
      .flatMap: resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[String].map(v => assertEquals(v, "1.2.3"))
