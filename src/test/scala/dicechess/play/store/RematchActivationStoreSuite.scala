package dicechess.play.store

import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.EngineOps
import doobie.Fragment
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import doobie.util.ExecutionContexts
import io.circe.Json
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import java.util.UUID

class RematchActivationStoreSuite extends CatsEffectSuite with TestContainerForAll:
  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private val seats = Set(Seat.White, Seat.Black)

  private def store(pg: PostgreSQLContainer) =
    PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))

  private def rawXa(pg: PostgreSQLContainer) =
    for
      ec <- ExecutionContexts.fixedThreadPool[IO](2)
      xa <- HikariTransactor.newHikariTransactor[IO]("org.postgresql.Driver", pg.jdbcUrl, pg.username, pg.password, ec)
    yield xa

  private def source: GameSnapshot =
    GameSnapshot(
      version = 4,
      dfen = EngineOps.InitialDfen,
      players = Map(
        Seat.White -> Principal.Guest(UUID.randomUUID().toString),
        Seat.Black -> Principal.User(UUID.randomUUID().toString)
      ),
      seatTokens = Map(Seat.White -> UUID.randomUUID().toString, Seat.Black -> UUID.randomUUID().toString),
      serverSeed = "a" * 64,
      clientSeeds = Map.empty,
      started = true,
      ply = 2,
      pending = false,
      status = GameStatus.Ended(GameOver(GameResult.Win(Side.White), Termination.Resign)),
      timeControl = TimeControl.Fischer(300, 3),
      remainingMs = Map(Seat.White -> 180000L, Seat.Black -> 200000L),
      lastRoll = Nil,
      turns = Vector.empty,
      rated = Some(false),
      ladder = Some(false),
      origin = Some(GameOrigin.Direct)
    )

  private def initial(s: GameSnapshot): GameSnapshot =
    s.copy(
      version = 0,
      started = false,
      ply = 0,
      status = GameStatus.Active,
      seatTokens = Map(Seat.White -> UUID.randomUUID().toString, Seat.Black -> UUID.randomUUID().toString),
      serverSeed = "b" * 64,
      remainingMs = Map(Seat.White -> 300000L, Seat.Black -> 300000L)
    )

  private def setup(db: PgGameStore): IO[(GameId, RematchSuccessor)] =
    val fixture = source
    for
      sourceId <- GameId.random
      _        <- db.save(sourceId, fixture)
      offered  <- db.rematches.advance(sourceId, 0, RematchChange.Offer(Seat.White))
      accepted <- offered match
        case RematchWrite.Applied(session) =>
          db.rematches.advance(sourceId, session.version, RematchChange.Accept(Seat.Black))
        case _ => IO.raiseError(new IllegalStateException("offer was not applied"))
      acceptedSession <- accepted match
        case RematchWrite.Applied(session) => IO.pure(session)
        case _                             => IO.raiseError(new IllegalStateException("accept was not applied"))
      successorId <- GameId.random
      successor   <- db.rematches.commitSuccessor(sourceId, acceptedSession.version, successorId, initial(fixture))
      game        <- successor match
        case RematchCommit.Committed(value) => IO.pure(value)
        case _                              => IO.raiseError(new IllegalStateException("successor was not committed"))
    yield sourceId -> game

  private def rowState(
      id: GameId,
      xa: doobie.Transactor[IO]
  ): IO[(String, Boolean, Boolean, Option[Json], Long, Long, Option[java.time.Instant])] =
    sql"""SELECT r.startup_phase, r.joined_white, r.joined_black, g.snapshot,
                 (SELECT count(*) FROM play.game_results WHERE game_id = ${id.value}::uuid),
                 (SELECT count(*) FROM play.game_archive WHERE game_id = ${id.value}::uuid),
                 r.activated_at
            FROM play.rematch_successors r
            LEFT JOIN play.games g ON g.id = r.game_id
           WHERE r.game_id = ${id.value}::uuid"""
      .query[(String, Boolean, Boolean, Option[Json], Long, Long, Option[java.time.Instant])]
      .unique
      .transact(xa)

  test("activation atomically persists the started snapshot and startup, including an exact retry"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          (_, successor) <- setup(db)
          startedAt      <- IO.realTimeInstant
          started = successor.initialSnapshot.copy(started = true, version = 1)
          next    = RematchStartup(RematchStartupPhase.Active, seats, Some(startedAt))
          _         <- db.saveRematch(successor.gameId, started, next)
          first     <- rowState(successor.gameId, xa)
          _         <- db.saveRematch(successor.gameId, started, next)
          retry     <- rowState(successor.gameId, xa)
          recovered <- db.rematches.successor(successor.gameId)
        yield
          assertEquals(first._1, "active")
          assertEquals(first._2, true)
          assertEquals(first._3, true)
          assertEquals(first._4.flatMap(_.hcursor.get[Boolean]("started").toOption), Some(true))
          assertEquals(first._5, 0L)
          assertEquals(first._6, 0L)
          assert(first._7.exists(at => !at.isBefore(successor.committedAt) && at.isBefore(successor.joinDeadlineAt)))
          assertEquals(retry, first)
          assertEquals(recovered.map(_.startup.phase), Some(RematchStartupPhase.Active))
      }
    }

  test("a games constraint failure rolls back startup activation with the initial snapshot intact"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          (_, successor) <- setup(db)
          constraint = "rematch_activation_games_" + successor.gameId.value.replace("-", "")
          add        = Fragment.const(
            s"ALTER TABLE play.games ADD CONSTRAINT $constraint CHECK (id <> '${successor.gameId.value}'::uuid OR NOT ((snapshot ->> 'started')::boolean))"
          )
          drop = Fragment.const(s"ALTER TABLE play.games DROP CONSTRAINT $constraint")
          startedAt <- IO.realTimeInstant
          attempted <- (add.update.run.transact(xa) *> db
            .saveRematch(
              successor.gameId,
              successor.initialSnapshot.copy(started = true, version = 1),
              RematchStartup(RematchStartupPhase.Active, seats, Some(startedAt))
            )
            .attempt)
            .guarantee(drop.update.run.transact(xa).void)
          state <- rowState(successor.gameId, xa)
          live  <- db.rematches.successor(successor.gameId)
        yield
          assert(attempted.isLeft)
          assertEquals(state._1, "awaiting_joins")
          assertEquals(state._2, false)
          assertEquals(state._3, false)
          assertEquals(state._4.flatMap(_.hcursor.get[Boolean]("started").toOption), Some(false))
          assertEquals(live.map(_.startup), Some(successor.startup))
      }
    }

  test("terminal abort persists one result and archive, with an idempotent aborted startup"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          (_, successor) <- setup(db)
          aborted = successor.initialSnapshot.copy(
            version = 1,
            status = GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted))
          )
          next = RematchStartup(RematchStartupPhase.Aborted, Set.empty, None)
          _     <- db.saveRematch(successor.gameId, aborted, next)
          first <- rowState(successor.gameId, xa)
          _     <- db.saveRematch(successor.gameId, aborted, next)
          retry <- rowState(successor.gameId, xa)
        yield
          assertEquals(first._1, "aborted")
          assert(first._4.exists(_.hcursor.downField("status").focus.nonEmpty))
          assertEquals(first._5, 1L)
          assertEquals(first._6, 1L)
          assertEquals(retry, first)
      }
    }

  test("activation rejects an expired committed window after deadline and commit time are moved together"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          (_, successor) <- setup(db)
          _              <- sql"""UPDATE play.rematch_successors
                    SET committed_at = now() - interval '30 seconds',
                        join_deadline_at = now() - interval '15 seconds'
                    WHERE game_id = ${successor.gameId.value}::uuid""".update.run.transact(xa)
          startedAt <- IO.realTimeInstant
          attempted <- db
            .saveRematch(
              successor.gameId,
              successor.initialSnapshot.copy(started = true, version = 1),
              RematchStartup(RematchStartupPhase.Active, seats, Some(startedAt))
            )
            .attempt
          state <- rowState(successor.gameId, xa)
        yield
          attempted match
            case Left(error: RematchTransitionRejected) =>
              assertEquals(error.gameId, successor.gameId)
              assert(error.deadlineExpired)
            case other => fail(s"expected deadline rejection, got $other")
          assertEquals(state._1, "awaiting_joins")
          assertEquals(state._2, false)
          assertEquals(state._3, false)
          assertEquals(state._4.flatMap(_.hcursor.get[Boolean]("started").toOption), Some(false))
          assertEquals(state._5, 0L)
          assertEquals(state._6, 0L)
      }
    }
