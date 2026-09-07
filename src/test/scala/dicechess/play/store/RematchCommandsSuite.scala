package dicechess.play.store

import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.EngineOps
import doobie.{Fragment, Transactor}
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.util.ExecutionContexts
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import java.time.Instant
import java.util.UUID

class RematchCommandsSuite extends CatsEffectSuite with TestContainerForAll:
  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private val seats                          = Set(Seat.White, Seat.Black)
  private def store(pg: PostgreSQLContainer) =
    PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))
  private def rawXa(pg: PostgreSQLContainer) =
    for
      ec <- ExecutionContexts.fixedThreadPool[IO](2)
      xa <- HikariTransactor.newHikariTransactor[IO]("org.postgresql.Driver", pg.jdbcUrl, pg.username, pg.password, ec)
    yield xa

  private def fixture: GameSnapshot =
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

  private def source(db: PgGameStore): IO[GameId]                   = GameId.random.flatTap(db.save(_, fixture))
  private def session(result: RematchCommandResult): RematchSession =
    result.state.session.getOrElse(fail("expected a retained rematch session"))
  private def command(db: PgGameStore, id: GameId, seat: Seat, action: RematchAction): IO[RematchCommandResult] =
    db.rematchCommands.command(id, seat, UUID.randomUUID(), action)
  private def receiptCount(id: GameId, xa: Transactor[IO]): IO[Long] =
    sql"SELECT count(*) FROM play.rematch_commands WHERE source_game_id = ${id.value}::uuid"
      .query[Long]
      .unique
      .transact(xa)
  private def expire(id: GameId, xa: Transactor[IO]): IO[Unit] =
    sql"UPDATE play.rematch_sessions SET deadline_at = clock_timestamp() WHERE source_game_id = ${id.value}::uuid".update.run
      .transact(xa)
      .void

  test("crossed proposals from independent stores persist exactly two consents, even with the same request UUID"):
    withContainers { pg =>
      (store(pg), store(pg), rawXa(pg)).tupled.use { (first, second, xa) =>
        for
          id <- source(first)
          request = UUID.randomUUID()
          replies <- (
            first.rematchCommands.command(id, Seat.White, request, RematchAction.Propose),
            second.rematchCommands.command(id, Seat.Black, request, RematchAction.Propose)
          ).parTupled
          current  <- first.rematchCommands.current(id)
          receipts <- receiptCount(id, xa)
        yield
          assertEquals(replies._1.error, None)
          assertEquals(replies._2.error, None)
          assertEquals(
            Set(session(replies._1).phase, session(replies._2).phase),
            Set(RematchPhase.Offered, RematchPhase.Starting)
          )
          assertEquals(current.session.map(_.phase), Some(RematchPhase.Starting))
          assertEquals(current.session.map(_.consents), Some(seats))
          assertEquals(current.session.map(_.version), Some(2L))
          assertEquals(receipts, 2L)
      }
    }

  test("proposal near the original deadline grants a full response window; self repeats never renew or accept it"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          id <- source(db)
          _  <- sql"""UPDATE play.rematch_sessions SET ended_at = clock_timestamp() - interval '14 seconds',
                    deadline_at = clock_timestamp() + interval '1 second'
                    WHERE source_game_id = ${id.value}::uuid""".update.run.transact(xa)
          original <- db.rematches.session(id).map(_.get)
          offered  <- command(db, id, Seat.White, RematchAction.Propose)
          repeats  <- List.fill(8)(command(db, id, Seat.White, RematchAction.Propose)).parSequence
          self     <- command(db, id, Seat.White, RematchAction.Accept)
          current  <- db.rematchCommands.current(id)
        yield
          assertEquals(offered.error, None)
          assertEquals(session(offered).deadlineAt, offered.state.serverNow.plusSeconds(15))
          assert(session(offered).deadlineAt.isAfter(original.deadlineAt.plusSeconds(10)))
          assert(repeats.forall(_.error.isEmpty))
          assert(repeats.forall(r => session(r) == session(offered)))
          assertEquals(self.error, Some("invalid_transition"))
          assertEquals(current.session, Some(session(offered)))
          assertEquals(session(self).consents, Set(Seat.White))
      }
    }

  test("accept, cancel and decline races choose one durable outcome without reopening the source"):
    withContainers { pg =>
      (store(pg), store(pg)).tupled.use { (first, second) =>
        (1 to 8).toList.traverse_ { _ =>
          for
            id      <- source(first)
            _       <- command(first, id, Seat.White, RematchAction.Propose)
            replies <- List(
              command(first, id, Seat.Black, RematchAction.Accept),
              command(second, id, Seat.White, RematchAction.Cancel),
              command(second, id, Seat.Black, RematchAction.Decline)
            ).parSequence
            current <- first.rematchCommands.current(id).map(_.session.get)
            retry   <- command(first, id, Seat.Black, RematchAction.Propose)
          yield
            assertEquals(replies.count(_.error.isEmpty), 1)
            assertEquals(current.version, 2L)
            assert(Set(RematchPhase.Starting, RematchPhase.Closed)(current.phase))
            assertEquals(session(retry), current)
            if current.phase == RematchPhase.Starting then
              assertEquals(current.consents, seats)
              assertEquals(current.closedReason, None)
              assertEquals(retry.error, None)
            else
              assert(Set(RematchCloseReason.Cancelled, RematchCloseReason.Declined)(current.closedReason.get))
              assertEquals(retry.error, Some("rematch_closed"))
        }
      }
    }

  test("reads and every command expire due opportunities without waiting for a background job"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val reads = List(false, true).traverse_ { offered =>
          for
            id     <- source(db)
            _      <- command(db, id, Seat.White, RematchAction.Propose).whenA(offered)
            _      <- expire(id, xa)
            first  <- db.rematchCommands.current(id)
            second <- db.rematchCommands.current(id)
          yield
            assertEquals(first.session.map(_.phase), Some(RematchPhase.Closed))
            assertEquals(first.session.flatMap(_.closedReason), Some(RematchCloseReason.Expired))
            assertEquals(first.session, second.session)
            assert(!first.serverNow.isBefore(first.session.get.deadlineAt))
        }
        val commands = (for
          offered <- List(false, true)
          action  <- RematchAction.values.toList
        yield (offered, action)).traverse_ { (offered, action) =>
          for
            id       <- source(db)
            _        <- command(db, id, Seat.White, RematchAction.Propose).whenA(offered)
            _        <- expire(id, xa)
            response <- command(db, id, Seat.Black, action)
          yield
            assertEquals(response.error, Some("rematch_closed"))
            assertEquals(session(response).closedReason, Some(RematchCloseReason.Expired))
            assertEquals(session(response).version, if offered then 2L else 1L)
        }
        reads *> commands
      }
    }

  test("request UUID reuse with another action is a conflict and preserves the original command"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          id <- source(db)
          request = UUID.randomUUID()
          proposed <- db.rematchCommands.command(id, Seat.White, request, RematchAction.Propose)
          conflict <- db.rematchCommands.command(id, Seat.White, request, RematchAction.Cancel)
          replay   <- db.rematchCommands.command(id, Seat.White, request, RematchAction.Propose)
          receipts <- receiptCount(id, xa)
        yield
          assertEquals(conflict.error, Some("request_id_conflict"))
          assertEquals(session(conflict), session(proposed))
          assertEquals(replay.error, None)
          assertEquals(session(replay), session(proposed))
          assertEquals(receipts, 1L)
      }
    }

  test("replay of a valid command returns current closed state after restart; a fresh request cannot reopen it"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          id <- source(db)
          request = UUID.randomUUID()
          _      <- db.rematchCommands.command(id, Seat.White, request, RematchAction.Propose)
          _      <- db.rematches.closeUncommittedOnRestart
          replay <- store(pg).use(_.rematchCommands.command(id, Seat.White, request, RematchAction.Propose))
          fresh  <- command(db, id, Seat.White, RematchAction.Propose)
        yield
          assertEquals(replay.error, None)
          assertEquals(session(replay).phase, RematchPhase.Closed)
          assertEquals(session(replay).closedReason, Some(RematchCloseReason.Restart))
          assertEquals(fresh.error, Some("rematch_closed"))
          assertEquals(session(fresh), session(replay))
      }
    }

  test("receipt recognition and source authorization survive operational snapshot retention"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val ended = fixture
        for
          id <- GameId.random
          _  <- db.save(id, ended)
          request = UUID.randomUUID()
          offered  <- db.rematchCommands.command(id, Seat.White, request, RematchAction.Propose)
          _        <- db.markDelivered(id)
          _        <- db.pruneOnce(Instant.now().plusSeconds(60), 1000)
          gameRows <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid".query[Long].unique.transact(xa)
          replay   <- store(pg).use(_.rematchCommands.command(id, Seat.White, request, RematchAction.Propose))
          conflict <- command(db, id, Seat.White, RematchAction.Accept)
          retained <- receiptCount(id, xa)
        yield
          assertEquals(gameRows, 0L)
          assertEquals(replay.error, None)
          assertEquals(session(replay), session(offered))
          assertEquals(session(replay).source.guestSeat(ended.seatTokens(Seat.White)), Some(Seat.White))
          assertEquals(conflict.error, Some("invalid_transition"))
          assertEquals(retained, 2L)
      }
    }

  test("receipt expiry after 24 hours cannot reopen a terminal source or restore consent"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          id <- source(db)
          request = UUID.randomUUID()
          _      <- db.rematchCommands.command(id, Seat.White, request, RematchAction.Propose)
          _      <- command(db, id, Seat.White, RematchAction.Cancel)
          closed <- db.rematchCommands.current(id).map(_.session.get)
          _      <- sql"""UPDATE play.rematch_commands SET recorded_at = clock_timestamp() - interval '25 hours'
                    WHERE source_game_id = ${id.value}::uuid""".update.run.transact(xa)
          retry    <- db.rematchCommands.command(id, Seat.White, request, RematchAction.Propose)
          receipts <- receiptCount(id, xa)
        yield
          assertEquals(retry.error, Some("rematch_closed"))
          assertEquals(session(retry), closed)
          assertEquals(session(retry).successorId, None)
          assertEquals(receipts, 1L)
      }
    }

  test("a failed command receipt insert rolls back the state transition; retry applies once"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          id <- source(db)
          request = UUID.randomUUID()
          name    = "rematch_command_failure_" + id.value.replace("-", "")
          add     = Fragment.const(
            s"ALTER TABLE play.rematch_commands ADD CONSTRAINT $name CHECK (source_game_id <> '${id.value}'::uuid)"
          )
          drop = Fragment.const(s"ALTER TABLE play.rematch_commands DROP CONSTRAINT $name")
          failed <- (add.update.run.transact(xa) *>
            db.rematchCommands.command(id, Seat.White, request, RematchAction.Propose).attempt)
            .guarantee(drop.update.run.transact(xa).void)
          untouched <- db.rematches.session(id).map(_.get)
          before    <- receiptCount(id, xa)
          retry     <- db.rematchCommands.command(id, Seat.White, request, RematchAction.Propose)
          after     <- receiptCount(id, xa)
        yield
          assert(failed.isLeft)
          assertEquals(untouched.phase, RematchPhase.Available)
          assertEquals(untouched.version, 0L)
          assertEquals(before, 0L)
          assertEquals(retry.error, None)
          assertEquals(session(retry).version, 1L)
          assertEquals(after, 1L)
      }
    }

  test("missing sources and spectator commands have stable errors without command receipts"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          missingId <- GameId.random
          missing   <- command(db, missingId, Seat.White, RematchAction.Propose)
          id        <- source(db)
          spectator <- command(db, id, Seat.Spectator, RematchAction.Propose)
          receipts  <- receiptCount(id, xa)
        yield
          assertEquals(missing.error, Some("game_not_found"))
          assertEquals(missing.state.session, None)
          assertEquals(spectator.error, Some("not_participant"))
          assertEquals(session(spectator).phase, RematchPhase.Available)
          assertEquals(receipts, 0L)
      }
    }

  test("creation scan includes committed unpublished successors but excludes active or archived successors"):
    withContainers { pg =>
      store(pg).use { db =>
        def committed: IO[(GameId, RematchSuccessor)] =
          val ended = fixture
          for
            id       <- GameId.random
            _        <- db.save(id, ended)
            _        <- command(db, id, Seat.White, RematchAction.Propose)
            accepted <- command(db, id, Seat.Black, RematchAction.Accept)
            nextId   <- GameId.random
            initial = ended.copy(
              version = 0,
              started = false,
              ply = 0,
              status = GameStatus.Active,
              seatTokens = Map(Seat.White -> UUID.randomUUID().toString, Seat.Black -> UUID.randomUUID().toString),
              serverSeed = "b" * 64,
              remainingMs = Map(Seat.White -> 300000L, Seat.Black -> 300000L)
            )
            result    <- db.rematches.commitSuccessor(id, session(accepted).version, nextId, initial)
            successor <- result match
              case RematchCommit.Committed(game) => IO.pure(game)
              case _ => IO.raiseError(new IllegalStateException("successor was not committed"))
          yield id -> successor
        for
          active   <- committed
          archived <- committed
          before   <- db.rematchCommands.pendingCreation(None, 100)
          now      <- IO.realTimeInstant
          _        <- db.saveRematch(
            active._2.gameId,
            active._2.initialSnapshot.copy(started = true, version = 1),
            RematchStartup(RematchStartupPhase.Active, seats, Some(now))
          )
          _ <- db.saveRematch(
            archived._2.gameId,
            archived._2.initialSnapshot.copy(
              version = 1,
              status = GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted))
            ),
            RematchStartup(RematchStartupPhase.Aborted, Set.empty, None)
          )
          after   <- db.rematchCommands.pendingCreation(None, 100)
          history <- db.archiveFor(archived._2.gameId)
        yield
          val included  = before.flatMap(_.toOption).map(_.sourceId).toSet
          val remaining = after.flatMap(_.toOption).map(_.sourceId).toSet
          assert(included(active._1))
          assert(included(archived._1))
          assert(!remaining(active._1))
          assert(!remaining(archived._1))
          assert(history.isDefined)
      }
    }

  test("creation scan returns corrupt rows independently and keyset pagination continues after their IDs"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val cursor  = GameId("ffffffff-ffff-ffff-ffff-fffffffffff0")
        val corrupt = GameId("ffffffff-ffff-ffff-ffff-fffffffffff1")
        val healthy = GameId("ffffffff-ffff-ffff-ffff-fffffffffff2")
        for
          _ <- List(corrupt, healthy).traverse_ { id =>
            db.save(id, fixture) *>
              command(db, id, Seat.White, RematchAction.Propose) *>
              command(db, id, Seat.Black, RematchAction.Accept).void
          }
          sourceJson <-
            sql"SELECT source::text FROM play.rematch_sessions WHERE source_game_id = ${corrupt.value}::uuid"
              .query[String]
              .unique
              .transact(xa)
          _ <- (for
            _ <-
              sql"UPDATE play.rematch_sessions SET source = '{}'::jsonb WHERE source_game_id = ${corrupt.value}::uuid".update.run
                .transact(xa)
            batch <- db.rematchCommands.pendingCreation(Some(cursor), 100)
            first <- db.rematchCommands.pendingCreation(Some(cursor), 1)
            lastId = first.last.fold(_.rowId, _.sourceId)
            second <- db.rematchCommands.pendingCreation(Some(lastId), 1)
            end    <- db.rematchCommands.pendingCreation(Some(second.last.fold(_.rowId, _.sourceId)), 1)
          yield
            assertEquals(batch.map(_.fold(_.rowId, _.sourceId)), List(corrupt, healthy))
            assertEquals(batch.head.left.toOption.map(_.rowId), Some(corrupt))
            assertEquals(batch.last.toOption.map(_.phase), Some(RematchPhase.Starting))
            assertEquals(first.size, 1)
            assertEquals(lastId, corrupt)
            assertEquals(second.flatMap(_.toOption).map(_.sourceId), List(healthy))
            assertEquals(end, Nil)
          ).guarantee(
            sql"UPDATE play.rematch_sessions SET source = $sourceJson::jsonb WHERE source_game_id = ${corrupt.value}::uuid".update.run
              .transact(xa)
              .void
          )
        yield ()
      }
    }
