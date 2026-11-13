package dicechess.play.server

import com.comcast.ip4s.{Host, Port}
import dicechess.play.store.PgLaboratoryStore
import java.net.URI
import scala.concurrent.duration.*
import scala.util.Try

enum RuntimeMode:
  case Classic, Laboratory

object RuntimeMode:
  def fromEnv(env: Map[String, String]): Either[String, RuntimeMode] =
    env.get("PLAY_RUNTIME_MODE").map(_.trim) match
      case None | Some("classic") =>
        Either.cond(
          !env.keys.exists(_.startsWith("LAB_")),
          RuntimeMode.Classic,
          "LAB_ settings require PLAY_RUNTIME_MODE=laboratory"
        )
      case Some("laboratory") => Right(RuntimeMode.Laboratory)
      case _                  => Left("PLAY_RUNTIME_MODE must be classic or laboratory")

/** A dedicated process has no defaults taken from the ordinary Play environment. Connection details never render. */
final case class LaboratoryConfig(
    database: PgLaboratoryStore.Config,
    frontendOrigin: String,
    bindHost: Host,
    port: Port,
    joinWindow: FiniteDuration,
    pruneInterval: FiniteDuration,
    pruneBatchSize: Int,
    admissionOpen: Boolean
)

object LaboratoryConfig:
  private val Settings = Set(
    "LAB_DB_URL",
    "LAB_DB_USER",
    "LAB_DB_PASSWORD",
    "LAB_FRONTEND_ORIGIN",
    "LAB_BIND_HOST",
    "LAB_PORT",
    "LAB_DB_POOL_SIZE",
    "LAB_RETENTION_DAYS",
    "LAB_JOIN_WINDOW_SECONDS",
    "LAB_PRUNE_INTERVAL_SECONDS",
    "LAB_PRUNE_BATCH_SIZE",
    "LAB_ADMISSION_OPEN"
  )
  private val ForbiddenPrefixes = List(
    "PLAY_DB_",
    "PLAY_BOT_",
    "RATING_",
    "TRAINING_",
    "INGEST_",
    "LADDER_",
    "SHOWCASE_",
    "WEBHOOK_",
    "GOOGLE_",
    "PLAY_SESSION",
    "PLAY_CORS_",
    "RETENTION_"
  )

  def fromEnv(env: Map[String, String]): Either[String, LaboratoryConfig] =
    def required(key: String): Either[String, String] =
      env.get(key).filter(_.trim.nonEmpty).toRight(s"$key is required")
    def number(key: String, default: Int, max: Int): Either[String, Int] =
      env
        .get(key)
        .fold[Either[String, Int]](Right(default))(
          _.toIntOption
            .filter(n => n > 0 && n <= max)
            .toRight(s"$key must be a positive integer no greater than $max")
        )
    for
      mode <- RuntimeMode.fromEnv(env)
      _    <- Either.cond(mode == RuntimeMode.Laboratory, (), "laboratory runtime must be explicitly selected")
      _    <- Either.cond(
        !env.keys.exists(key => key.startsWith("LAB_") && !Settings(key)),
        (),
        "unknown laboratory configuration setting"
      )
      _ <- Either.cond(
        !env.exists((key, value) => value.nonEmpty && ForbiddenPrefixes.exists(key.startsWith)),
        (),
        "ordinary service settings are forbidden in the laboratory process"
      )
      url <- required("LAB_DB_URL")
      _   <- Either.cond(
        validDatabaseUrl(url),
        (),
        "LAB_DB_URL must be a PostgreSQL JDBC URL without embedded credentials or session options"
      )
      user     <- required("LAB_DB_USER")
      password <- required("LAB_DB_PASSWORD")
      origin   <- required("LAB_FRONTEND_ORIGIN")
      parsed    = Cors.AllowedOrigins.parseDetailed(origin)
      webOrigin = origin.startsWith("https://") || origin.startsWith("http://")
      _ <- Either.cond(
        webOrigin && !origin.contains(',') && parsed.rejected.isEmpty && parsed.allowed.isExplicitlyConfigured,
        (),
        "LAB_FRONTEND_ORIGIN must be one concrete canonical browser origin"
      )
      host      <- Host.fromString(env.getOrElse("LAB_BIND_HOST", "127.0.0.1")).toRight("invalid LAB_BIND_HOST")
      port      <- Port.fromString(env.getOrElse("LAB_PORT", "8081")).filter(_.value > 0).toRight("invalid LAB_PORT")
      pool      <- number("LAB_DB_POOL_SIZE", 2, 32)
      days      <- number("LAB_RETENTION_DAYS", 30, 365)
      join      <- number("LAB_JOIN_WINDOW_SECONDS", 300, 86400)
      interval  <- number("LAB_PRUNE_INTERVAL_SECONDS", 3600, 86400)
      batch     <- number("LAB_PRUNE_BATCH_SIZE", 100, 1000)
      admission <- env.get("LAB_ADMISSION_OPEN") match
        case None | Some("true") => Right(true)
        case Some("false")       => Right(false)
        case _                   => Left("LAB_ADMISSION_OPEN must be true or false")
    yield LaboratoryConfig(
      PgLaboratoryStore.Config(url, user, password, pool, days.days),
      origin,
      host,
      port,
      join.seconds,
      interval.seconds,
      batch,
      admission
    )

  private def validDatabaseUrl(url: String): Boolean =
    if !url.startsWith("jdbc:postgresql://") then false
    else
      Try(URI.create(url.stripPrefix("jdbc:"))).toOption.exists: uri =>
        val keys = Option(uri.getRawQuery).toList
          .flatMap(_.split('&'))
          .map(_.takeWhile(_ != '=').toLowerCase(java.util.Locale.ROOT))
        uri.getHost != null && uri.getRawUserInfo == null && uri.getRawFragment == null &&
        Option(uri.getPath).exists(path => path.length > 1 && !path.drop(1).contains('/')) &&
        keys.forall(key =>
          Set("sslmode", "ssl", "connecttimeout", "sockettimeout", "applicationname", "loggerlevel")(key)
        )
