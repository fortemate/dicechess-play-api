package dicechess.play.server

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.{EngineOps, GameRoom, Durability, RetryPolicy}
import dicechess.play.dice.DiceSource
import dicechess.play.store.*
import doobie.Fragment
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import doobie.util.ExecutionContexts
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

class RematchServiceSuite extends CatsEffectSuite with TestContainerForAll:
  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))
  private def fixture = GameSnapshot(
    0,
    EngineOps.InitialDfen,
    Map(
      Seat.White -> Principal.User(UUID.randomUUID().toString),
      Seat.Black -> Principal.User(UUID.randomUUID().toString)
    ),
    Map(Seat.White -> UUID.randomUUID().toString, Seat.Black -> UUID.randomUUID().toString),
    "a" * 64,
    Map.empty,
    true,
    2,
    false,
    GameStatus.Ended(GameOver(GameResult.Win(Side.White), Termination.Resign)),
    TimeControl.Fischer(300, 3),
    Map(Seat.White -> 200000L, Seat.Black -> 220000L),
    Nil,
    Vector.empty,
    rated = Some(true),
    ladder = Some(false),
    origin = Some(GameOrigin.Direct)
  )

  private def resources(pg: PostgreSQLContainer): Resource[IO, (PgGameStore, GameRegistry)] =
    for
      db  <- PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))
      reg <- Resource.make(GameRegistry.create(store = db))(_.list.flatMap(_.traverse_(_._2.stopForRestart)))
    yield (db, reg)

  private def accepted(db: PgGameStore, s: GameSnapshot): IO[GameId] =
    for
      id <- GameId.random
      _  <- db.save(id, s)
      _  <- db.rematches.advance(id, 0, RematchChange.Offer(Seat.White))
      _  <- db.rematches.advance(id, 1, RematchChange.Accept(Seat.Black))
    yield id

  for swap <- List(false, true) do
    test(
      s"independent colour branch $swap commits once across concurrent retries and preserves simultaneous human admission"
    ) {
      withContainers { pg =>
        resources(pg).use { (db, reg) =>
          val s = fixture
          for
            guard   <- AdmissionGuard.create(db, ShowcaseConfig.Disabled, registry = Some(reg))
            _       <- reg.attachAdmissionGuard(guard)
            other   <- reg.create(s.players(Seat.White), s.players(Seat.Black), TimeControl.Unlimited)
            draws   <- Ref.of[IO, Int](0)
            service <- RematchService.create(reg, db, swapColours = draws.update(_ + 1).as(swap))
            source  <- accepted(db, s)
            results <- List.fill(12)(service.create(source)).parSequence
            game = results.head.toOption.get
            r       <- reg.get(game.gameId).map(_.get)
            pending <- r.awaitingJoinDeadline
            count   <- draws.get
            rows    <- reg.list
          yield
            assert(other.isRight)
            assertEquals(results.distinct.size, 1)
            assertEquals(count, 1)
            assertEquals(rows.size, 2)
            assertEquals(pending, Some(game.joinDeadlineAt))
            assertEquals(game.initialSnapshot.players(Seat.White), s.players(if swap then Seat.Black else Seat.White))
            assertEquals(game.initialSnapshot.timeControl, s.timeControl)
            assertEquals(game.initialSnapshot.rated, Some(true))
            assertEquals(game.initialSnapshot.effectiveOrigin, GameOrigin.Direct)
            assert(game.initialSnapshot.seatTokens.values.toSet.intersect(s.seatTokens.values.toSet).isEmpty)
        }
      }
    }

  test("a lost commit response reads the same durable successor without another colour draw") {
    withContainers { pg =>
      resources(pg).use { (db, reg) =>
        val delegate = db.rematches
        val records  = new RematchStore:
          export delegate.{
            session,
            successor,
            successorRecords,
            advance,
            pendingStartup,
            updateStartup,
            closeUncommittedOnRestart
          }
          def commitSuccessor(id: GameId, v: Long, next: GameId, snapshot: GameSnapshot): IO[RematchCommit] =
            delegate.commitSuccessor(id, v, next, snapshot) *> IO.raiseError(
              new RuntimeException("response lost after commit")
            )
        for
          draws   <- Ref.of[IO, Int](0)
          service <- RematchService.create(reg, db, records = Some(records), swapColours = draws.update(_ + 1).as(true))
          source  <- accepted(db, fixture)
          first   <- service.create(source)
          retry   <- service.create(source)
          count   <- draws.get
        yield
          assert(first.isRight)
          assertEquals(first, retry)
          assertEquals(count, 1)
      }
    }
  }

  test("transient registration failure retries the same room and never republishes before hooks succeed") {
    withContainers { pg =>
      resources(pg).use { (db, reg) =>
        for
          attempts <- Ref.of[IO, Int](0)
          _        <- reg.onRegister { (id, _, _) =>
            attempts.updateAndGet(_ + 1).flatMap { n =>
              if n <= 2 then
                reg.get(id).flatMap { room =>
                  IO(assertEquals(room, None)) *> IO.raiseError(new RuntimeException("registration unavailable"))
                }
              else IO.unit
            }
          }
          source <- accepted(db, fixture)
          game   <- reg.rematches.get.create(source).map(_.toOption.get)
          retry  <- reg.rematches.get.create(source).map(_.toOption.get)
          count  <- attempts.get
          rooms  <- reg.list
        yield
          assertEquals(count, 3)
          assertEquals(game, retry)
          assertEquals(rooms.map(_._1), List(game.gameId))
      }
    }
  }

  test("registration failure at the original deadline produces readable technical history without rating or reroll") {
    withContainers { pg =>
      resources(pg).use { (db, reg) =>
        for
          time <- IO.realTimeInstant.flatMap(Ref.of[IO, Instant])
          _    <- reg.onRegister { (id, _, _) =>
            db.rematches.successor(id).flatMap { game =>
              time.set(game.get.joinDeadlineAt) *> IO.raiseError(new RuntimeException("registration failed"))
            }
          }
          service  <- RematchService.create(reg, db, now = time.get)
          source   <- accepted(db, fixture)
          game     <- service.create(source).map(_.toOption.get)
          archive  <- db.archiveFor(game.gameId)
          stored   <- db.rematches.successor(game.gameId).map(_.get)
          snapshot <- db.rematchSnapshot(game.gameId)
          rated    <- db.unappliedRatedGames(100)
          retry    <- service.create(source).map(_.toOption.get)
        yield
          assert(archive.isDefined)
          assertEquals(stored.startup.phase, RematchStartupPhase.Aborted)
          assertEquals(snapshot.status, GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted)))
          assert(!rated.exists(_.gameId == game.gameId))
          assertEquals(game.gameId, retry.gameId)
      }
    }
  }

  test(
    "restart after registration preserves the link and technically aborts the current snapshot including recorded joins"
  ) {
    withContainers { pg =>
      resources(pg).use { (db, reg) =>
        for
          source <- accepted(db, fixture)
          game   <- reg.rematches.get.create(source).map(_.toOption.get)
          r      <- reg.get(game.gameId).map(_.get)
          _      <- r.connection(Seat.White).use { _ =>
            r.submit(Seat.White, GameCommand.SubmitSeed("x")) *> IO.sleep(30.millis) *> r.stopForRestart
          }
          newer    <- db.rematchSnapshot(game.gameId)
          resumed  <- GameRegistry.create(store = db)
          count    <- resumed.resume
          stored   <- db.rematches.successor(game.gameId).map(_.get)
          terminal <- db.rematchSnapshot(game.gameId)
          link     <- db.rematches.session(source).map(_.get.successorId)
          again    <- resumed.resume
        yield
          assert(newer.version > 0)
          assertEquals(count, 0)
          assertEquals(again, 0)
          assertEquals(stored.startup.phase, RematchStartupPhase.Aborted)
          assertEquals(stored.startup.joined, Set(Seat.White))
          assertEquals(terminal.status, GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted)))
          assertEquals(link, Some(game.gameId))
      }
    }
  }

  test("restart preserves public active-rematch metadata without changing ordinary recovery or reopening joins") {
    withContainers { pg =>
      resources(pg).use { (db, reg) =>
        for
          source     <- accepted(db, fixture)
          game       <- reg.rematches.get.create(source).map(_.toOption.get)
          r          <- reg.get(game.gameId).map(_.get)
          _          <- r.connection(Seat.White).use(_ => r.connection(Seat.Black).use(_ => r.stopForRestart))
          snapshot   <- db.rematchSnapshot(game.gameId)
          stored     <- db.rematches.successor(game.gameId).map(_.get)
          ordinaryId <- GameId.random
          _          <- db.save(
            ordinaryId,
            snapshot.copy(seatTokens = Map(Seat.White -> "ordinary-white", Seat.Black -> "ordinary-black"))
          )
          resumed <- GameRegistry.create(store = db)
          _       <- (for
            count    <- resumed.resume
            active   <- resumed.get(game.gameId).map(_.get)
            ordinary <- resumed.get(ordinaryId).map(_.get)
            state    <- active.snapshot
            wsState  <- active.subscribe.collect { case GameEvent.Snapshot(_, s, _) => s }.take(1).compile.lastOrError
            gate     <- active.awaitingJoinDeadline
            ordinaryState <- ordinary.snapshot
            ordinaryGate  <- ordinary.awaitingJoinDeadline
          yield
            assertEquals(count, 2)
            assertEquals(state.rematchStartup, Some(PublicRematchStartup(PublicRematchStartupPhase.Active)))
            assertEquals(wsState.rematchStartup, state.rematchStartup)
            assertEquals(gate, None)
            assertEquals(ordinaryState.rematchStartup, None)
            assertEquals(ordinaryGate, None)
          ).guarantee(resumed.list.flatMap(_.traverse_(_._2.abort)))
        yield
          assert(snapshot.started)
          assertEquals(snapshot.ply, 0L)
          assertEquals(stored.startup.phase, RematchStartupPhase.Active)
      }
    }
  }

  test("inadmissible inherited rating settings close the source before allocating colours") {
    withContainers { pg =>
      resources(pg).use { (db, reg) =>
        for
          source  <- accepted(db, fixture.copy(timeControl = TimeControl.Unlimited))
          service <- RematchService.create(reg, db, swapColours = IO.raiseError(new RuntimeException("must not draw")))
          result  <- service.create(source)
          session <- db.rematches.session(source).map(_.get)
        yield
          assertEquals(result, Left("settings_unavailable"))
          assertEquals(session.phase, RematchPhase.Closed)
          assertEquals(session.successorId, None)
      }
    }
  }

  test("cancelled request cannot cancel registration or leave a room without its end watcher") {
    withContainers { pg =>
      resources(pg).use { (db, reg) =>
        for
          entered <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          _       <- reg.onRegister((_, _, _) => entered.complete(()).void *> release.get)
          source  <- accepted(db, fixture)
          request <- reg.rematches.get.create(source).start
          _       <- entered.get
          _       <- request.cancel
          _       <- release.complete(())
          game    <- reg.rematches.get.create(source).map(_.toOption.get)
          r       <- reg.get(game.gameId).map(_.get)
          _       <- r.abort
          _       <- IO.sleep(30.millis)
          gone    <- reg.get(game.gameId)
          indexed <- reg.gamesFor(game.initialSnapshot.players(Seat.White))
        yield
          assertEquals(gone, None)
          assertEquals(indexed, Nil)
      }
    }
  }

  test("hung metadata lookup times out before room construction and leaves only technical history") {
    withContainers { pg =>
      PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password)).use { db =>
        for
          entered <- Deferred[IO, Unit]
          expired <- Ref.of[IO, Boolean](false)
          reg     <- GameRegistry.create(store = db, resolveNicknames = _ => entered.complete(()).void *> IO.never)
          source  <- accepted(db, fixture)
          clock = db.rematches
            .session(source)
            .flatMap(s => db.rematches.successor(s.get.successorId.get))
            .flatMap(g =>
              expired.get.map(over => if over then g.get.joinDeadlineAt else g.get.joinDeadlineAt.minusMillis(50))
            )
          service <- RematchService.create(reg, db, now = clock)
          request <- service.create(source).start
          _       <- entered.get *> expired.set(true)
          game    <- request.joinWithNever.map(_.toOption.get)
          rooms   <- reg.list
          stored  <- db.rematches.successor(game.gameId).map(_.get)
          archive <- db.archiveFor(game.gameId)
        yield
          assertEquals(rooms, Nil)
          assertEquals(stored.startup.phase, RematchStartupPhase.Aborted)
          assert(archive.isDefined)
      }
    }
  }

  test("initial no-show produces one sporting result and one rating candidate for the connected seat") {
    withContainers { pg =>
      resources(pg).use { (db, reg) =>
        for
          time    <- IO.realTimeInstant.flatMap(Ref.of[IO, Instant])
          service <- RematchService.create(reg, db, now = time.get, swapColours = IO.pure(false))
          source  <- accepted(db, fixture)
          game    <- service.create(source).map(_.toOption.get)
          r       <- reg.get(game.gameId).map(_.get)
          _       <- r.connection(Seat.White).use { _ =>
            time.set(game.joinDeadlineAt) *> r.start *> r.result
              .map(result => assertEquals(result, GameOver(GameResult.Win(Side.White), Termination.Timeout)))
          }
          candidates <- db.unappliedRatedGames(100)
          archive    <- db.archiveFor(game.gameId)
          retry      <- service.create(source).map(_.toOption.get)
        yield
          assertEquals(candidates.count(_.gameId == game.gameId), 1)
          assert(archive.isDefined)
          assertEquals(retry.gameId, game.gameId)
      }
    }
  }

  test("activation committed with every response lost is confirmed durably before the writer continues") {
    withContainers { pg =>
      resources(pg).use { (db, reg) =>
        for
          source        <- accepted(db, fixture)
          game          <- reg.rematches.get.create(source).map(_.toOption.get)
          original      <- reg.get(game.gameId).map(_.get)
          _             <- original.stopForRestart
          attempts      <- Ref.of[IO, Int](0)
          confirmations <- Ref.of[IO, Int](0)
          dice          <- IO.fromEither(
            DiceSource.fromHexSeed(game.initialSnapshot.serverSeed).leftMap(new RuntimeException(_))
          )
          gate = GameRoom.InitialJoinGate(
            game.joinDeadlineAt,
            game.startup,
            (snapshot, startup) =>
              db.saveRematch(game.gameId, snapshot, startup) *>
                (attempts.update(_ + 1) *> IO.raiseError(new RuntimeException("activation response lost")))
                  .whenA(snapshot.started && !snapshot.ended),
            confirms =
              (snapshot, startup) => confirmations.update(_ + 1) *> db.confirmsRematch(game.gameId, snapshot, startup)
          )
          made <- GameRoom.restore(
            game.initialSnapshot,
            dice,
            initialJoin = Some(gate),
            durability = Durability.required(_ => IO.unit).copy(intermediate = RetryPolicy(Some(2), 5.millis, 5.millis))
          )
          r = made.toOption.get
          _ <- r
            .connection(Seat.White)
            .use(_ =>
              r.connection(Seat.Black).use { _ =>
                for
                  deadline <- r.awaitingJoinDeadline
                  saved    <- db.rematchSnapshot(game.gameId)
                  writes   <- attempts.get
                  reads    <- confirmations.get
                yield
                  assertEquals(deadline, None)
                  assert(saved.started)
                  assertEquals(writes, 2)
                  assertEquals(reads, 1)
              }
            )
            .guarantee(r.abort)
          terminal <- db.rematchSnapshot(game.gameId)
          archive  <- db.archiveFor(game.gameId)
        yield
          assertEquals(terminal.status, GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted)))
          assert(archive.isDefined)
      }
    }
  }

  test("batch resume metadata isolates corrupt active successors and distinguishes ordinary and missing games") {
    withContainers { pg =>
      val raw = for
        ec <- ExecutionContexts.fixedThreadPool[IO](2)
        xa <- HikariTransactor
          .newHikariTransactor[IO]("org.postgresql.Driver", pg.jdbcUrl, pg.username, pg.password, ec)
      yield xa
      (resources(pg), raw).tupled.use { case ((db, reg), xa) =>
        for
          games <- List.fill(2)(()).traverse { _ =>
            for
              source <- accepted(db, fixture)
              game   <- reg.rematches.get.create(source).map(_.toOption.get)
              room   <- reg.get(game.gameId).map(_.get)
              _      <- room.connection(Seat.White).use(_ => room.connection(Seat.Black).use(_ => room.stopForRestart))
            yield game
          }
          healthy = games.head
          corrupt = games.last
          ordinary <- GameId.random
          missing  <- GameId.random
          active   <- db.rematchSnapshot(healthy.gameId)
          _        <- db.save(
            ordinary,
            active.copy(seatTokens = Map(Seat.White -> "ordinary-white", Seat.Black -> "ordinary-black"))
          )
          _ <- (for
            _ <-
              sql"UPDATE play.rematch_successors SET initial_snapshot = ${Json.obj()} WHERE game_id = ${corrupt.gameId.value}::uuid".update.run
                .transact(xa)
            batch <- db.rematches.successorRecords(
              List(healthy.gameId, corrupt.gameId, ordinary, missing, healthy.gameId)
            )
            empty        <- db.rematches.successorRecords(Nil)
            ordinaryOnly <- db.rematches.successorRecords(List(ordinary, missing))
            _            <- IO {
              assertEquals(batch.size, 2)
              assertEquals(batch.flatMap(_.toOption).map(_.gameId), List(healthy.gameId))
              assertEquals(batch.flatMap(_.left.toOption).map(_.rowId), List(corrupt.gameId))
              assertEquals(empty, Nil)
              assertEquals(ordinaryOnly, Nil)
            }
            resumed <- GameRegistry.create(store = db)
            _       <- (for
              count <- resumed.resume
              bad   <- resumed.get(corrupt.gameId)
              good  <- resumed.get(healthy.gameId).map(_.get)
              state <- good.snapshot
              plain <- resumed.get(ordinary).map(_.get).flatMap(_.snapshot)
            yield
              assertEquals(count, 2)
              assertEquals(bad, None)
              assertEquals(state.rematchStartup, Some(PublicRematchStartup(PublicRematchStartupPhase.Active)))
              assertEquals(plain.rematchStartup, None)
            ).guarantee(resumed.list.flatMap(_.traverse_(_._2.stopForRestart)))
          yield ()).guarantee(
            sql"UPDATE play.rematch_successors SET initial_snapshot = ${corrupt.initialSnapshot.asJson} WHERE game_id = ${corrupt.gameId.value}::uuid".update.run
              .transact(xa)
              .void
          )
          _ <- games.traverse_(game =>
            db.save(game.gameId, active.copy(status = GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted))))
          )
          _ <- db.save(ordinary, active.copy(status = GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted))))
        yield ()
      }
    }
  }

  test("startup recovery isolates corrupt initial snapshots, corrupt current snapshots and missing game rows") {
    withContainers { pg =>
      val raw = for
        ec <- ExecutionContexts.fixedThreadPool[IO](2)
        xa <- HikariTransactor
          .newHikariTransactor[IO]("org.postgresql.Driver", pg.jdbcUrl, pg.username, pg.password, ec)
      yield xa
      (resources(pg), raw).tupled.use { case ((db, reg), xa) =>
        for
          games <- (1 to 4).toList.traverse { _ =>
            accepted(db, fixture).flatMap(id => reg.rematches.get.create(id).map(_.toOption.get))
          }
          _ <- reg.list.flatMap(_.traverse_(_._2.stopForRestart))
          badInitial = games(0)
          badCurrent = games(1)
          missing    = games(2)
          healthy    = games(3)
          _ <- (for
            _ <-
              sql"UPDATE play.rematch_successors SET initial_snapshot = ${Json.obj()} WHERE game_id = ${badInitial.gameId.value}::uuid".update.run
                .transact(xa)
            _ <-
              sql"UPDATE play.games SET snapshot = ${Json.obj()} WHERE id = ${badCurrent.gameId.value}::uuid".update.run
                .transact(xa)
            _        <- sql"DELETE FROM play.games WHERE id = ${missing.gameId.value}::uuid".update.run.transact(xa)
            records  <- db.rematches.pendingStartupRecords(None, 500)
            _        <- IO(assert(records.exists(_.left.toOption.exists(_.rowId == badInitial.gameId))))
            rebooted <- GameRegistry.create(store = db)
            constraint = "rematch_recovery_failure_" + healthy.gameId.value.replace("-", "")
            add        = Fragment.const(
              s"ALTER TABLE play.games ADD CONSTRAINT $constraint CHECK (id <> '${healthy.gameId.value}'::uuid OR status = 'active')"
            )
            drop = Fragment.const(s"ALTER TABLE play.games DROP CONSTRAINT $constraint")
            failedBoot <- (add.update.run.transact(xa) *> rebooted.resume.attempt)
              .guarantee(drop.update.run.transact(xa).void)
            _         <- IO(assert(failedBoot.isLeft, "unclassified storage errors must prevent transport admission"))
            _         <- rebooted.resume.timeout(3.seconds)
            archive   <- db.archiveFor(healthy.gameId)
            successor <- db.rematches.successor(healthy.gameId)
            _         <- IO {
              assert(archive.isDefined)
              assertEquals(successor.map(_.startup.phase), Some(RematchStartupPhase.Aborted))
            }
          yield ()).guarantee(
            sql"UPDATE play.rematch_successors SET initial_snapshot = ${badInitial.initialSnapshot.asJson} WHERE game_id = ${badInitial.gameId.value}::uuid".update.run
              .transact(xa)
              .void *>
              db.save(badCurrent.gameId, badCurrent.initialSnapshot) *>
              db.save(missing.gameId, missing.initialSnapshot) *>
              reg.rematches.get.recoverOnRestart
          )
        yield ()
      }
    }
  }
