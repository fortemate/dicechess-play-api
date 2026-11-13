package dicechess.play

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import dicechess.play.server.{Cors, HealthRoutes, LaboratoryConfig, LaboratoryRegistry, LaboratoryRoutes}
import dicechess.play.store.{LaboratoryStore, PgLaboratoryStore}
import fs2.Stream
import org.http4s.{HttpApp, Request, Response, Status}
import org.http4s.headers.Origin
import org.http4s.implicits.*
import org.http4s.server.middleware.CORS
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.Method
import org.typelevel.ci.*

/** Separate composition root: no ordinary store, outbound client, bot service or rating/training worker is built. */
object LaboratoryRuntime:
  private[play] def registry(config: LaboratoryConfig, store: LaboratoryStore): Resource[IO, LaboratoryRegistry] =
    Resource
      .make(LaboratoryRegistry.create(store, joinWindow = config.joinWindow, admissionOpen = config.admissionOpen))(
        _.shutdown
      )
      .evalTap(_.resume.void)

  private[play] def httpApp(
      registry: LaboratoryRegistry,
      config: LaboratoryConfig,
      wsb: WebSocketBuilder2[IO],
      version: String
  ): HttpApp[IO] =
    val allowed              = Cors.allowedOrigins(config.frontendOrigin)
    val routes               = (HealthRoutes(version) <+> LaboratoryRoutes(registry, wsb)).orNotFound
    val guarded: HttpApp[IO] = cats.data.Kleisli: (req: Request[IO]) =>
      val origins = req.headers.headers.filter(_.name == ci"Origin").map(_.value)
      val trusted = origins match
        case Nil           => true // Native clients still need their seat capabilities; no ambient session exists here.
        case origin :: Nil => Origin.parse(origin).toOption.exists(allowed.allows)
        case _             => false
      if trusted then routes(req) else IO.pure(Response[IO](Status.Forbidden))
    CORS.policy
      .withAllowOriginHeader(allowed.allows)
      .withAllowMethodsIn(Set(Method.GET, Method.POST, Method.OPTIONS))
      .withAllowHeadersIn(Set(ci"content-type", ci"x-laboratory-seat-token"))
      .withAllowCredentials(false)(guarded)

  def run(config: LaboratoryConfig): IO[Unit] =
    PgLaboratoryStore
      .resource(config.database)
      .use: store =>
        registry(config, store).use: rooms =>
          val pruning = Stream
            .awakeEvery[IO](config.pruneInterval)
            .evalMap(_ => rooms.pruneExpired(config.pruneBatchSize))
            .compile
            .drain
          val serving = EmberServerBuilder
            .default[IO]
            .withHost(config.bindHost)
            .withPort(config.port)
            .withHttpWebSocketApp(wsb => httpApp(rooms, config, wsb, sys.env.getOrElse("APP_VERSION", "dev")))
            .build
            .useForever
          IO.println("[laboratory] isolated runtime; ordinary services unavailable") *>
            (serving, pruning).parTupled.void
