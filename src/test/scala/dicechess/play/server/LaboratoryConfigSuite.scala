package dicechess.play.server

import java.util.UUID

class LaboratoryConfigSuite extends munit.FunSuite:

  private def password: String = s"lab-test-${UUID.randomUUID()}"

  private def environment(secret: String = password): Map[String, String] = Map(
    "PLAY_RUNTIME_MODE"   -> "laboratory",
    "LAB_DB_URL"          -> "jdbc:postgresql://127.0.0.1:5432/laboratory",
    "LAB_DB_USER"         -> "laboratory",
    "LAB_DB_PASSWORD"     -> secret,
    "LAB_FRONTEND_ORIGIN" -> "https://lab.example"
  )

  test("an empty environment selects only the classic runtime"):
    assertEquals(RuntimeMode.fromEnv(Map.empty), Right(RuntimeMode.Classic))
    assert(RuntimeMode.fromEnv(Map("LAB_DB_URL" -> "")).isLeft)
    assertEquals(RuntimeMode.fromEnv(Map("PLAY_RUNTIME_MODE" -> "classic")), Right(RuntimeMode.Classic))
    assert(LaboratoryConfig.fromEnv(Map.empty).isLeft)

  test("laboratory mode requires explicit mode and all isolated connection and browser settings"):
    val complete = environment()
    val required = List("PLAY_RUNTIME_MODE", "LAB_DB_URL", "LAB_DB_USER", "LAB_DB_PASSWORD", "LAB_FRONTEND_ORIGIN")
    required.foreach: key =>
      val missing = complete.removed(key)
      assert(LaboratoryConfig.fromEnv(missing).isLeft, s"configuration should reject missing $key")

    assertEquals(RuntimeMode.fromEnv(environment().removed("PLAY_RUNTIME_MODE")).isLeft, true)
    assertEquals(LaboratoryConfig.fromEnv(complete).map(_.admissionOpen), Right(true))

  test("laboratory configuration rejects ordinary service prefixes"):
    val forbidden = List(
      "PLAY_DB_URL",
      "PLAY_BOT_ENABLED",
      "RATING_ENABLED",
      "TRAINING_ENABLED",
      "INGEST_URL",
      "LADDER_ENABLED",
      "SHOWCASE_ENABLED",
      "WEBHOOK_URL",
      "GOOGLE_CLIENT_ID",
      "PLAY_SESSION_SECRET",
      "PLAY_CORS_ORIGINS",
      "RETENTION_DAYS"
    )
    forbidden.foreach: key =>
      assert(LaboratoryConfig.fromEnv(environment() + (key -> "set")).isLeft, s"configuration should reject $key")

  test("booleans, ports and numeric limits fail closed"):
    val malformed = List(
      "LAB_ADMISSION_OPEN"         -> "TRUE",
      "LAB_ADMISSION_OPEN"         -> "1",
      "LAB_PORT"                   -> "not-a-port",
      "LAB_PORT"                   -> "0",
      "LAB_UNRECOGNIZED_OPTION"    -> "true",
      "LAB_DB_POOL_SIZE"           -> "0",
      "LAB_DB_POOL_SIZE"           -> "33",
      "LAB_RETENTION_DAYS"         -> "366",
      "LAB_JOIN_WINDOW_SECONDS"    -> "-1",
      "LAB_PRUNE_INTERVAL_SECONDS" -> "x",
      "LAB_PRUNE_BATCH_SIZE"       -> "1001"
    )
    malformed.foreach: (key, value) =>
      assert(
        LaboratoryConfig.fromEnv(environment() + (key -> value)).isLeft,
        s"configuration should reject $key=$value"
      )

  test("frontend origins must be a single canonical browser origin"):
    val invalid = List(
      "https://lab.example/path",
      "HTTPS://LAB.EXAMPLE",
      "https://lab.example:443",
      "https://lab.example,https://other.example",
      "ftp://lab.example",
      "null"
    )
    invalid.foreach: origin =>
      assert(
        LaboratoryConfig.fromEnv(environment() + ("LAB_FRONTEND_ORIGIN" -> origin)).isLeft,
        s"configuration should reject noncanonical frontend origin $origin"
      )

  test("database URLs reject embedded credentials, query options and non-PostgreSQL URLs without echoing them"):
    val embedded = password
    val invalid  = List(
      s"jdbc:postgresql://user:$embedded@127.0.0.1:5432/laboratory",
      "jdbc:postgresql://127.0.0.1:5432/laboratory?password=hidden",
      "jdbc:postgresql://127.0.0.1:5432/laboratory?currentSchema=play",
      "jdbc:mysql://127.0.0.1:3306/laboratory",
      "jdbc:postgresql://127.0.0.1:5432/laboratory#fragment"
    )
    invalid.foreach: url =>
      val result = LaboratoryConfig.fromEnv(environment() + ("LAB_DB_URL" -> url))
      assert(result.isLeft, "invalid JDBC URL should be rejected")
      assert(!result.swap.toOption.get.contains(url), "configuration error must not echo the URL")
      assert(!result.swap.toOption.get.contains(embedded), "configuration error must not expose embedded credentials")

  test("configuration rendering redacts the generated database password"):
    val secret = password
    val config = LaboratoryConfig.fromEnv(environment(secret)).fold(message => fail(message), identity)
    assert(!config.toString.contains(secret))
    assert(config.toString.contains("<private connection>"))

  test("connection logging options do not change the isolated database target"):
    val env = environment().updated("LAB_DB_URL", "jdbc:postgresql://127.0.0.1:5432/laboratory?loggerLevel=OFF")
    assert(LaboratoryConfig.fromEnv(env).isRight)
