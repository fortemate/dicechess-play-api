package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.GameRoom
import dicechess.play.rating.{Anchor, AnchorSet, RatingBatch}
import dicechess.play.store.*
import io.circe.Json
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, RequestCookie, Status, Uri}
import org.testcontainers.utility.DockerImageName

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.duration.*

/** Connected acceptance: a real room writes its result to isolated PostgreSQL, the real batch drains it, and HTTP reads
  * expose the same numbers. No result rows or rating movements are fabricated by this suite.
  */
class RatingAcceptanceSuite extends munit.CatsEffectSuite with TestContainerForAll:

  override val munitIOTimeout: FiniteDuration = 2.minutes

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private val anchors = AnchorSet("acceptance-fixture", 1, "blitz", List(Anchor("fixture/reference", 0.0)))

  private def read(app: HttpApp[IO], path: String, cookie: Option[RequestCookie] = None): IO[Json] =
    val request = Request[IO](Method.GET, Uri.unsafeFromString(path))
    app
      .run(cookie.fold(request)(request.addCookie))
      .flatMap: response =>
        assertEquals(response.status, Status.Ok, path)
        response.as[Json]

  private def writeArtifact(name: String, json: Json): IO[Unit] =
    IO.blocking:
      val directory = Path.of("target", "rating-acceptance")
      Files.createDirectories(directory)
      Files.writeString(directory.resolve(s"$name.json"), json.spaces2)
      ()

  private def finish(
      registry: GameRegistry,
      db: PgGameStore,
      white: Principal,
      black: Principal,
      control: TimeControl
  ): IO[GameId] =
    for
      created    <- registry.create(white, black, control, requestedRated = true)
      (id, room) <- IO.fromEither(created.left.map(RuntimeException(_)))
      _          <- room.resign(Seat.Black).timeout(10.seconds)
      _          <- room.result.timeout(10.seconds)
      _          <- db.ratingChangeFor(id).iterateUntil(_.isDefined).timeout(10.seconds)
    yield id

  private def restoreProof(pg: PostgreSQLContainer): IO[Unit] =
    def run(arguments: String*): IO[String] = IO.blocking:
      val result = pg.container.execInContainer(arguments*)
      assertEquals(result.getExitCode, 0, result.getStderr)
      result.getStdout.trim

    def query(database: String, sql: String): IO[String] =
      run("psql", "-X", "-A", "-t", "-U", pg.username, "-d", database, "-c", sql)

    def digest(database: String, tables: List[String]): IO[List[String]] =
      tables.traverse: table =>
        query(
          database,
          s"SELECT count(*),md5(COALESCE(string_agg(to_jsonb(t)::text,'' ORDER BY to_jsonb(t)::text),'')) FROM $table t"
        )
          .map(value => s"$table:$value")

    val tablesSql = "SELECT schemaname||'.'||tablename FROM pg_tables WHERE schemaname IN ('play','public') ORDER BY 1"
    for
      _ <- query(
        pg.databaseName,
        "SET play.rating_epoch_admin = 'on'; UPDATE play.rating_epoch_control SET paused=true, revision=revision+1 WHERE singleton"
      )
      tablesText <- query(pg.databaseName, tablesSql)
      tables = tablesText.linesIterator.filter(_.nonEmpty).toList
      before <- digest(pg.databaseName, tables)
      _      <- run("pg_dump", "-U", pg.username, "-d", pg.databaseName, "-Fc", "-f", "/tmp/acceptance.dump")
      _      <- run("createdb", "-U", pg.username, "acceptance_restore")
      _      <- run(
        "pg_restore",
        "-U",
        pg.username,
        "-d",
        "acceptance_restore",
        "--clean",
        "--if-exists",
        "--no-owner",
        "--no-privileges",
        "/tmp/acceptance.dump"
      )
      restoredTables <- query("acceptance_restore", tablesSql)
      restored       <- digest("acceptance_restore", tables)
      paused         <- query("acceptance_restore", "SELECT paused FROM play.rating_epoch_control WHERE singleton")
      _              <- IO:
        assertEquals(restoredTables, tablesText)
        assertEquals(restored, before, "all rows in every application table survive the full logical restore")
        assertEquals(paused, "t", "the restored database stays fenced")
      _ <- writeArtifact(
        "logical-restore",
        Json.obj(
          "tables"         -> Json.fromInt(tables.size),
          "rowDigests"     -> Json.arr(before.map(Json.fromString)*),
          "restoredPaused" -> Json.True
        )
      )
    yield ()

  private def snapshotFailureProof(pg: PostgreSQLContainer, db: PgGameStore, app: HttpApp[IO]): IO[Unit] =
    def command(sql: String): IO[Unit] = IO.blocking:
      val result = pg.container.execInContainer(
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        pg.username,
        "-d",
        pg.databaseName,
        "-c",
        sql
      )
      assertEquals(result.getExitCode, 0, result.getStderr)

    val cutoff  = Instant.parse("2026-01-01T00:00:00Z")
    val install = """CREATE FUNCTION play.acceptance_fail_publication() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN IF NEW.is_current THEN RAISE EXCEPTION 'injected publication failure'; END IF; RETURN NEW; END $$;
      CREATE TRIGGER acceptance_fail_publication BEFORE UPDATE ON play.bot_strength_snapshots
      FOR EACH ROW EXECUTE FUNCTION play.acceptance_fail_publication();"""
    val remove  = """DROP TRIGGER acceptance_fail_publication ON play.bot_strength_snapshots;
      DROP FUNCTION play.acceptance_fail_publication();"""
    for
      _        <- db.saveBotStrengthSnapshot(RatingCategory.Blitz, 1, anchors.version, cutoff, Nil)
      original <- read(app, "/leaderboard/snapshots/blitz/latest")
      failed   <- (command(install) *> db
        .saveBotStrengthSnapshot(RatingCategory.Blitz, 1, anchors.version, cutoff.plusSeconds(1), Nil)
        .attempt).guarantee(command(remove))
      after <- read(app, "/leaderboard/snapshots/blitz/latest")
      _     <- IO:
        assert(failed.isLeft, "a refresh whose pointer publication fails must fail visibly")
        assertEquals(after, original, "failed refresh keeps the previous public snapshot intact")
      _       <- db.saveBotStrengthSnapshot(RatingCategory.Blitz, 1, anchors.version, cutoff.plusSeconds(1), Nil)
      retried <- read(app, "/leaderboard/snapshots/blitz/latest")
      _       <- IO(assertNotEquals(retried, original, "removing the injected failure lets a retry publish"))
      _       <- writeArtifact("snapshot-refresh-retained", after)
    yield ()

  test("created competitive and training games retain domain, category, movement and history across HTTP reads"):
    withContainers: pg =>
      PgGameStore
        .resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password), anchors)
        .use: db =>
          for
            alice    <- db.upsertOnLogin("fixture", "acceptance-alice", None, IO.pure("AcceptanceAlice"))
            bob      <- db.upsertOnLogin("fixture", "acceptance-bob", None, IO.pure("AcceptanceBob"))
            _        <- db.register("fixture", "reference", "fixture-hash")
            registry <- GameRegistry.create(
              store = db,
              tuning = GameRoom.RoomTuning(seedGrace = 10.millis),
              ratingPolicy = RatingPolicy.Matrix
            )
            batch <- RatingBatch.create(db, db, db, db, RatingBatch.Config.Default, RatingPolicy.Matrix, anchors)
            session = new AuthSession(db, java.util.UUID.randomUUID().toString)
            token <- session.sign(alice)
            cookie = RequestCookie(AuthSession.SessionCookieName, token)
            app    = (RatingRoutes(db) <+> LeaderboardRoutes(db, db, db, db, Some(db)) <+>
              HistoryRoutes(db, db) <+> MeRoutes(session, db, db)).orNotFound
            competitive <- finish(
              registry,
              db,
              Principal.User(alice.id),
              Principal.User(bob.id),
              TimeControl.Fischer(60, 1)
            )
            pending  <- read(app, s"/games/${competitive.value}/rating")
            _        <- IO(assertEquals(pending.hcursor.get[Boolean]("applied"), Right(false)))
            _        <- writeArtifact("competitive-pending", pending)
            _        <- batch.tick
            movement <- read(app, s"/games/${competitive.value}/rating")
            profile  <- read(app, "/players/by-nickname/AcceptanceAlice")
            board    <- read(app, "/leaderboard?kind=all&category=bullet")
            history  <- read(app, s"/games/${competitive.value}/history")
            ownGames <- read(app, "/me/games", Some(cookie))
            _        <- IO:
              assertEquals(movement.hcursor.get[String]("ratingDomain"), Right("competitive"))
              assertEquals(movement.hcursor.get[String]("outcome"), Right("applied"))
              val after = movement.hcursor.downField("white").get[Double]("after").toOption.get
              assert(after > movement.hcursor.downField("white").get[Double]("before").toOption.get)
              val ratings = profile.hcursor.get[List[Json]]("ratings").toOption.get
              assertEquals(ratings.size, 1)
              assertEquals(ratings.head.hcursor.get[String]("category"), Right("bullet"))
              assertEquals(ratings.head.hcursor.get[Double]("rating"), Right(after))
              assertEquals(ratings.head.hcursor.get[Int]("games"), Right(1))
              assertEquals(profile.hcursor.get[Int]("games"), Right(0), "default Blitz stays untouched")
              assertEquals(board.hcursor.get[String]("category"), Right("bullet"))
              assertEquals(board.hcursor.get[List[Json]]("leaders"), Right(Nil), "new ratings stay provisional")
              assertEquals(history.hcursor.get[Boolean]("rated"), Right(true))
              assertEquals(ownGames.hcursor.get[List[Json]]("games").toOption.get.size, 1)
            _ <- List(
              "competitive-rating"    -> movement,
              "competitive-profile"   -> profile,
              "competitive-board"     -> board,
              "competitive-history"   -> history,
              "competitive-own-games" -> ownGames
            )
              .traverse_((name, json) => writeArtifact(name, json))
            training <- finish(
              registry,
              db,
              Principal.User(alice.id),
              Principal.Bot("fixture", "reference"),
              TimeControl.Fischer(300, 3)
            )
            trainingPending <- read(app, s"/games/${training.value}/rating")
            pendingResponse <- app.run(
              Request[IO](Method.GET, Uri.unsafeFromString(s"/games/${training.value}/rating"))
            )
            _ <- IO:
              assertEquals(trainingPending.hcursor.get[Boolean]("applied"), Right(false))
              assertEquals(trainingPending.hcursor.get[String]("outcome"), Right("pending"))
              assertEquals(trainingPending.hcursor.get[String]("ratingDomain"), Right("training"))
              assertEquals(
                trainingPending.hcursor.downField("trainingReference").get[String]("source"),
                Right("anchor")
              )
              assert(
                pendingResponse.headers.headers
                  .exists(h => h.name.toString.equalsIgnoreCase("Cache-Control") && h.value == "no-store")
              )
            _              <- batch.tick
            trained        <- read(app, s"/games/${training.value}/rating")
            trainedProfile <- read(app, "/players/by-nickname/AcceptanceAlice")
            trainedHistory <- read(app, s"/games/${training.value}/history")
            _              <- IO:
              assertEquals(trained.hcursor.get[String]("ratingDomain"), Right("training"))
              assertEquals(trained.hcursor.get[String]("outcome"), Right("applied"))
              assertEquals(trained.hcursor.downField("trainingReference").get[String]("source"), Right("anchor"))
              assertEquals(
                trained.hcursor.downField("trainingReference").focus,
                trainingPending.hcursor.downField("trainingReference").focus
              )
              val states = trainedProfile.hcursor.get[List[Json]]("training").toOption.get
              assertEquals(states.size, 1)
              assertEquals(states.head.hcursor.get[String]("category"), Right("blitz"))
              assertEquals(
                states.head.hcursor.get[Double]("rating"),
                trained.hcursor.downField("white").get[Double]("after")
              )
              assertEquals(trainedHistory.hcursor.get[Boolean]("rated"), Right(false))
              assertEquals(
                trainedProfile.hcursor.get[List[Json]]("ratings"),
                profile.hcursor.get[List[Json]]("ratings")
              )
            _ <- List(
              "training-rating"  -> trained,
              "training-profile" -> trainedProfile,
              "training-history" -> trainedHistory
            ).traverse_((name, json) => writeArtifact(name, json))
            _        <- batch.tick
            repeated <- read(app, s"/games/${training.value}/rating")
            _        <- IO(
              assertEquals(repeated, trained, "refresh and a repeated drain preserve the game's recorded movement")
            )
            _ <- snapshotFailureProof(pg, db, app)
            _ <- restoreProof(pg)
          yield ()
