package dicechess.play

import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.game.GameRoom
import dicechess.play.server.{LaboratoryConfig, LaboratoryRegistry}
import dicechess.play.store.PgLaboratoryStore
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager
import java.util.UUID
import scala.concurrent.duration.*

class LaboratoryLifecycleSuite extends munit.CatsEffectSuite with TestContainerForAll:
  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def config(pg: PostgreSQLContainer, admissionOpen: Boolean): LaboratoryConfig =
    val env = Map(
      "PLAY_RUNTIME_MODE"   -> "laboratory",
      "LAB_DB_URL"          -> pg.jdbcUrl,
      "LAB_DB_USER"         -> pg.username,
      "LAB_DB_PASSWORD"     -> pg.password,
      "LAB_FRONTEND_ORIGIN" -> "https://lab.example",
      "LAB_ADMISSION_OPEN"  -> admissionOpen.toString
    )
    LaboratoryConfig.fromEnv(env).fold(message => fail(message), identity)

  private def store(pg: PostgreSQLContainer): Resource[IO, PgLaboratoryStore] =
    PgLaboratoryStore.resource(PgLaboratoryStore.Config(pg.jdbcUrl, pg.username, pg.password))

  private def create(registry: LaboratoryRegistry): IO[(GameId, GameRoom)] =
    val white = Principal.Guest(UUID.randomUUID().toString)
    val black = Principal.Guest(UUID.randomUUID().toString)
    registry
      .createFriend(
        white,
        black,
        TimeControl.Fischer(60, 5),
        Some(TwoPairs),
        Some(ProtocolVersion),
        requestedRated = false
      )
      .flatMap(result => IO.fromEither(result.leftMap(message => new RuntimeException(message))))

  private def awaitPhase(room: GameRoom, expected: Phase, remaining: Int = 200): IO[PublicGameState] =
    room.snapshot.flatMap: state =>
      if state.lab.exists(_.turn.phase == expected) then IO.pure(state)
      else if remaining <= 0 then IO.raiseError(new RuntimeException(s"laboratory phase did not reach $expected"))
      else IO.sleep(10.millis) *> awaitPhase(room, expected, remaining - 1)

  private def finishSportingGame(room: GameRoom): IO[PublicGameState] =
    (room.connection(Seat.White), room.connection(Seat.Black)).tupled.use: _ =>
      for
        _     <- room.submit(Seat.White, GameCommand.SubmitSeed("white-lifecycle-seed-0123456789"))
        _     <- room.submit(Seat.Black, GameCommand.SubmitSeed("black-lifecycle-seed-0123456789"))
        _     <- awaitPhase(room, Phase.AwaitingFirstRoll)
        _     <- room.submit(Seat.White, GameCommand.Resign)
        ended <- awaitPhase(room, Phase.Ended)
      yield ended

  private def expire(pg: PostgreSQLContainer, id: GameId): IO[Int] = IO.blocking:
    val connection = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
    try
      val statement = connection.prepareStatement(
        """UPDATE laboratory.games SET created_at = clock_timestamp() - interval '2 minutes',
          expires_at = clock_timestamp() - interval '1 minute' WHERE game_id = ?::uuid"""
      )
      try
        statement.setString(1, id.value)
        statement.executeUpdate()
      finally statement.close()
    finally connection.close()

  test("closed admission preserves lookup of a finished game and blocks a two-seat rematch"):
    withContainers: pg =>
      store(pg).use: db =>
        for
          finished <- LaboratoryRuntime
            .registry(config(pg, admissionOpen = true), db)
            .use: registry =>
              for
                (id, room) <- create(registry)
                ended      <- finishSportingGame(room)
                saved      <- db.load(id).map(_.getOrElse(fail("finished laboratory game was not persisted")))
                _ = assertEquals(ended.lab.map(_.turn.phase), Some(Phase.Ended))
                _ = assertEquals(
                  saved.status,
                  GameStatus.Ended(GameOver(GameResult.Win(Side.Black), Termination.Resign))
                )
              yield id -> saved
          (id, source) = finished
          _ <- LaboratoryRuntime
            .registry(config(pg, admissionOpen = false), db)
            .use: closed =>
              for
                existing  <- closed.get(id)
                white     <- closed.rematch(id, source.seatTokens(Seat.White), consent = true)
                black     <- closed.rematch(id, source.seatTokens(Seat.Black), consent = true)
                successor <- db.successorOf(id)
                _ = assert(existing.isDefined, "a closed registry must still restore an existing finished game")
                _ = assert(white.isLeft, "closed admission refuses a new rematch even for its first source seat")
                _ = assert(black.isLeft, "the second source seat cannot create a rematch when admission is closed")
                _ = assertEquals(successor, None)
              yield ()
        yield ()

  test("pruning an expired live game closes subscriptions and removes game and transcript reads"):
    withContainers: pg =>
      store(pg).use: db =>
        LaboratoryRuntime
          .registry(config(pg, admissionOpen = true), db)
          .use: registry =>
            for
              (id, room) <- create(registry)
              attached   <- Deferred[IO, Unit]
              subscriber <- room.subscribe.evalTap(_ => attached.complete(()).void).compile.drain.start
              _          <- attached.get.timeout(2.seconds)
              before     <- db.load(id)
              changed    <- expire(pg, id)
              removed    <- registry.pruneExpired(100)
              byId       <- registry.get(id)
              snapshot   <- db.load(id)
              transcript <- registry.transcript(id)
              _          <- subscriber.joinWithNever.timeout(2.seconds)
            yield
              assert(before.isDefined)
              assertEquals(changed, 1)
              assertEquals(removed, 1)
              assertEquals(byId, None)
              assertEquals(snapshot, None)
              assertEquals(transcript, None)
