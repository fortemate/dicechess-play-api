package dicechess.play.store

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.{EngineOps, GameRoom}
import dicechess.play.dice.DiceSource
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.util.ExecutionContexts
import doobie.Fragment
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import java.time.Instant
import java.util.UUID

class RematchStoreSuite extends CatsEffectSuite with TestContainerForAll:
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

  private def initial(source: GameSnapshot, swap: Boolean = false): GameSnapshot =
    source.copy(
      version = 0,
      started = false,
      ply = 0,
      status = GameStatus.Active,
      players = if swap then source.players.map((seat, p) => seat.opponent.get -> p) else source.players,
      seatTokens = Map(Seat.White -> UUID.randomUUID().toString, Seat.Black -> UUID.randomUUID().toString),
      serverSeed = "b" * 64,
      remainingMs = Map(Seat.White -> 300000L, Seat.Black -> 300000L)
    )

  private def applied(result: RematchWrite): RematchSession = result match
    case RematchWrite.Applied(s) => s
    case _                       => fail("expected a persisted transition")
  private def committed(result: RematchCommit): RematchSuccessor = result match
    case RematchCommit.Committed(g) => g
    case RematchCommit.Existing(g)  => g
    case _                          => fail("expected a committed successor")
  private def start(db: PgGameStore, source: GameSnapshot): IO[(GameId, RematchSession)] =
    for
      id       <- GameId.random
      _        <- db.save(id, source)
      offered  <- db.rematches.advance(id, 0, RematchChange.Offer(Seat.White)).map(applied)
      accepted <- db.rematches.advance(id, offered.version, RematchChange.Accept(Seat.Black)).map(applied)
    yield id -> accepted

  test("terminal capture freezes final humans and conditions; source capabilities survive snapshot retention"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val ended = fixture
        for
          id <- GameId.random
          _  <- db.save(
            id,
            ended.copy(
              status = GameStatus.Active,
              players = Map(Seat.White -> ended.players(Seat.White), Seat.Black -> ended.players(Seat.White))
            )
          )
          before    <- db.rematches.session(id)
          _         <- db.save(id, ended)
          saved     <- db.rematches.session(id).map(_.get)
          _         <- db.save(id, ended.copy(timeControl = TimeControl.PerMove(2)))
          unchanged <- db.rematches.session(id)
          _         <- db.markDelivered(id)
          _         <- db.pruneOnce(Instant.now().plusSeconds(60), 1000)
          exists    <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid".query[Long].unique.transact(xa)
          restored  <- store(pg).use(_.rematches.session(id))
        yield
          assertEquals(before, None)
          assertEquals(saved.source.conditions, RematchConditions(ended.timeControl, false))
          assertEquals(saved.source.players, ended.players)
          assertEquals(saved.deadlineAt, saved.endedAt.plusSeconds(15))
          assertEquals(unchanged, Some(saved))
          assertEquals(exists, 0L)
          assertEquals(restored, Some(saved))
          assertEquals(saved.source.guestSeat(ended.seatTokens(Seat.White)), Some(Seat.White))
          assertEquals(saved.source.guestSeat(ended.seatTokens(Seat.Black)), None)
          assertEquals(saved.source.guestSeat(ended.players(Seat.White).externalId), None)
          assert(!saved.source.guestTokenHashes.values.toSet.contains(ended.seatTokens(Seat.White)))
      }
    }

  test("source capture rejects showcase, ladder, bots, aborts, unclaimed seats, duplicate identities and rated guests"):
    withContainers { pg =>
      store(pg).use { db =>
        val s       = fixture
        val invalid = List(
          s.copy(origin = Some(GameOrigin.Showcase)),
          s.copy(ladder = Some(true)),
          s.copy(origin = Some(GameOrigin.Ladder)),
          s.copy(rated = Some(true)),
          s.copy(players = s.players.updated(Seat.Black, Principal.Bot("house", "greedy"))),
          s.copy(players = s.players.updated(Seat.Black, s.players(Seat.White))),
          s.copy(players = s.players - Seat.Black),
          s.copy(seatTokens = Map(Seat.White -> "same", Seat.Black -> "same")),
          s.copy(status = GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted)))
        )
        invalid
          .traverse { snap =>
            GameId.random.flatMap(id => db.save(id, snap) *> db.rematches.session(id))
          }
          .map(found => assert(found.forall(_.isEmpty)))
      }
    }

  test("offers and acceptance use version fences; self-accept and late retries cannot extend a deadline"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          id     <- GameId.random
          _      <- db.save(id, fixture)
          first  <- db.rematches.advance(id, 0, RematchChange.Offer(Seat.White)).map(applied)
          repeat <- db.rematches.advance(id, first.version, RematchChange.Offer(Seat.White))
          self   <- db.rematches.advance(id, first.version, RematchChange.Accept(Seat.White))
          _      <- sql"""UPDATE play.rematch_sessions SET deadline_at = clock_timestamp() - interval '1 second'
                     WHERE source_game_id = ${id.value}::uuid""".update.run.transact(xa)
          late  <- db.rematches.advance(id, first.version, RematchChange.Accept(Seat.Black)).map(applied)
          retry <- db.rematches.advance(id, late.version, RematchChange.Offer(Seat.Black))
        yield
          assertEquals(repeat, RematchWrite.Conflict(first))
          assertEquals(self, RematchWrite.Conflict(first))
          assertEquals(late.phase, RematchPhase.Closed)
          assertEquals(late.closedReason, Some(RematchCloseReason.Expired))
          assertEquals(retry, RematchWrite.Conflict(late))
      }
    }

  test(
    "concurrent commits through different stores persist one game and one colour assignment; retries recover own access"
  ):
    withContainers { pg =>
      (store(pg), store(pg), rawXa(pg)).tupled.use { (db, other, xa) =>
        val source = fixture
        for
          started <- start(db, source)
          (id, accepted) = started
          candidates <- List.fill(12)(()).traverse(_ => GameId.random)
          results    <- candidates.zipWithIndex.parTraverse { (gameId, index) =>
            val target = if index % 2 == 0 then db else other
            target.rematches.commitSuccessor(id, accepted.version, gameId, initial(source, index % 2 == 0))
          }
          winner = committed(results.head)
          count <- sql"SELECT count(*) FROM play.rematch_successors WHERE source_game_id = ${id.value}::uuid"
            .query[Long]
            .unique
            .transact(xa)
          snapshotCount <- (fr"SELECT count(*) FROM play.games WHERE" ++
            doobie.util.fragments.in(fr"id::text", cats.data.NonEmptyList.fromListUnsafe(candidates.map(_.value))))
            .query[Long]
            .unique
            .transact(xa)
          retried <- other.rematches.commitSuccessor(id, -1, id, source)
          active  <- db.loadActive
          pending <- db.rematches.pendingStartup(None, 500)
        yield
          assertEquals(results.count { case RematchCommit.Committed(_) => true; case _ => false }, 1)
          assert(results.forall(r => committed(r) == winner))
          assertEquals(count, 1L)
          assertEquals(snapshotCount, 1L)
          assertEquals(retried, RematchCommit.Existing(winner))
          assertEquals(winner.joinDeadlineAt, winner.committedAt.plusSeconds(15))
          assert(!active.exists(_._1 == winner.gameId), "ordinary boot must not accidentally start awaiting-joins")
          assert(pending.exists(_.gameId == winner.gameId))
          val own = winner.seatAccess(source.players(Seat.White)).get
          assertEquals(winner.initialSnapshot.players(own._1), source.players(Seat.White))
          assertEquals(winner.seatAccess(Principal.Guest(UUID.randomUUID().toString)), None)
          assert(!winner.toString.contains(own._2))
      }
    }

  test("failure after both inserts rolls back initial snapshot, successor record and source progress together"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val source = fixture
        for
          started <- start(db, source)
          (id, accepted) = started
          next <- GameId.random
          constraint = "rematch_fail_" + id.value.replace("-", "")
          add        = Fragment.const(
            s"ALTER TABLE play.rematch_sessions ADD CONSTRAINT $constraint CHECK (source_game_id <> '${id.value}'::uuid OR phase <> 'matched')"
          )
          drop = Fragment.const(s"ALTER TABLE play.rematch_sessions DROP CONSTRAINT $constraint")
          failed <- (add.update.run
            .transact(xa) *> db.rematches.commitSuccessor(id, accepted.version, next, initial(source)).attempt)
            .guarantee(drop.update.run.transact(xa).void)
          initialCount <- sql"SELECT count(*) FROM play.games WHERE id = ${next.value}::uuid"
            .query[Long]
            .unique
            .transact(xa)
          orphan    <- db.rematches.successor(next)
          unchanged <- db.rematches.session(id)
          retry     <- db.rematches.commitSuccessor(id, accepted.version, next, initial(source))
        yield
          assert(failed.isLeft)
          assertEquals(initialCount, 0L)
          assertEquals(orphan, None)
          assertEquals(unchanged, Some(accepted))
          assertEquals(committed(retry).gameId, next)
      }
    }

  test("invalid initial snapshots and existing IDs cannot overwrite a game or consume an accepted source"):
    withContainers { pg =>
      store(pg).use { db =>
        val s    = fixture
        val good = initial(s)
        for
          started <- start(db, s)
          (id, accepted) = started
          next <- GameId.random
          bad = List(
            good.copy(started = true),
            good.copy(timeControl = TimeControl.PerMove(1)),
            good.copy(remainingMs = s.remainingMs),
            good.copy(origin = Some(GameOrigin.Showcase)),
            good.copy(players = good.players.updated(Seat.White, Principal.Guest(UUID.randomUUID().toString)))
          )
          rejected        <- bad.traverse(db.rematches.commitSuccessor(id, accepted.version, next, _))
          _               <- db.save(next, s)
          duplicate       <- db.rematches.commitSuccessor(id, accepted.version, next, good).attempt
          unchanged       <- db.rematches.session(id)
          collisionSource <- db.rematches.session(next)
        yield
          assert(rejected.forall(_ == RematchCommit.Rejected))
          assertEquals(duplicate.toOption, Some(RematchCommit.Rejected))
          assertEquals(unchanged, Some(accepted))
          assertEquals(collisionSource.get.source.players, s.players)
      }
    }

  test("successive rematches keep root conditions while recording the new final seat assignment and credentials"):
    withContainers { pg =>
      store(pg).use { db =>
        val s = fixture
        for
          started <- start(db, s)
          (a, accepted) = started
          b    <- GameId.random
          game <- db.rematches.commitSuccessor(a, accepted.version, b, initial(s, swap = true)).map(committed)
          _    <- db.rematches.updateStartup(
            b,
            0,
            RematchStartup(RematchStartupPhase.Active, seats, Some(game.committedAt.plusMillis(1)))
          )
          _ <- db.save(
            b,
            game.initialSnapshot.copy(started = true, status = s.status, timeControl = TimeControl.PerMove(1))
          )
          nextSource <- db.rematches.session(b).map(_.get)
          aSource    <- db.rematches.session(a).map(_.get)
        yield
          assertEquals(nextSource.rootId, a)
          assertEquals(nextSource.source.conditions, aSource.source.conditions)
          assertEquals(nextSource.source.players, game.initialSnapshot.players)
          assertEquals(nextSource.source.guestSeat(game.initialSnapshot.seatTokens(Seat.Black)), Some(Seat.Black))
          assertEquals(nextSource.source.guestSeat(s.seatTokens(Seat.White)), None)
      }
    }

  test("restart closes only uncommitted opportunities and retained committed identity survives a new store"):
    withContainers { pg =>
      store(pg).use { db =>
        val s = fixture
        for
          uncommitted   <- start(db, s)
          accepted      <- start(db, fixture)
          next          <- GameId.random
          committedGame <- db.rematches
            .commitSuccessor(
              accepted._1,
              accepted._2.version,
              next,
              initial(s).copy(players = accepted._2.source.players)
            )
            .map(committed)
          _         <- store(pg).use(_.rematches.closeUncommittedOnRestart)
          closed    <- db.rematches.session(uncommitted._1).map(_.get)
          reopen    <- db.rematches.advance(uncommitted._1, closed.version, RematchChange.Offer(Seat.White))
          preserved <- store(pg).use(_.rematches.successor(next))
          mapped    <- db.rematches.session(accepted._1).map(_.get)
        yield
          assertEquals(closed.phase, RematchPhase.Closed)
          assertEquals(closed.closedReason, Some(RematchCloseReason.Restart))
          assertEquals(reopen, RematchWrite.Conflict(closed))
          assertEquals(preserved, Some(committedGame))
          assertEquals(mapped.successorId, Some(next))
      }
    }

  test("startup versions preserve join history, reject premature activation and cannot reset activation"):
    withContainers { pg =>
      store(pg).use { db =>
        val s = fixture
        for
          started <- start(db, s)
          (id, accepted) = started
          next <- GameId.random
          game <- db.rematches.commitSuccessor(id, accepted.version, next, initial(s)).map(committed)
          bad  <- db.rematches.updateStartup(
            next,
            0,
            RematchStartup(RematchStartupPhase.Active, Set(Seat.White), Some(game.committedAt.plusMillis(1)))
          )
          joined <- db.rematches.updateStartup(
            next,
            0,
            RematchStartup(RematchStartupPhase.AwaitingJoins, Set(Seat.White), None)
          )
          stale <- db.rematches.updateStartup(
            next,
            0,
            RematchStartup(RematchStartupPhase.AwaitingJoins, Set(Seat.Black), None)
          )
          active <- db.rematches.updateStartup(
            next,
            1,
            RematchStartup(RematchStartupPhase.Active, seats, Some(game.committedAt.plusMillis(1)))
          )
          reset <- db.rematches.updateStartup(next, 2, RematchStartup(RematchStartupPhase.AwaitingJoins, seats, None))
          recovered <- db.rematches.successor(next).map(_.get)
          normal    <- db.loadActive
        yield
          assert(!bad)
          assert(joined)
          assert(!stale)
          assert(active)
          assert(!reset)
          assertEquals(recovered.startup.joined, seats)
          assertEquals(recovered.startupVersion, 2L)
          assert(normal.exists(_._1 == next))
      }
    }

  test("a pruned historical game ID remains reserved even without a rematch session"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val s = fixture
        for
          old      <- GameId.random
          _        <- db.save(old, s.copy(players = s.players.updated(Seat.Black, Principal.Bot("history", "bot"))))
          _        <- db.markDelivered(old)
          _        <- db.pruneOnce(Instant.now().plusSeconds(60), 1000)
          absent   <- sql"SELECT count(*) FROM play.games WHERE id = ${old.value}::uuid".query[Long].unique.transact(xa)
          started  <- start(db, s)
          rejected <- db.rematches.commitSuccessor(started._1, started._2.version, old, initial(s))
          existing <- db.archiveFor(old)
        yield
          assertEquals(absent, 0L)
          assertEquals(rejected, RematchCommit.Rejected)
          assert(existing.isDefined)
      }
    }

  test("two unrelated accepted sources cannot claim the same successor ID"):
    withContainers { pg =>
      store(pg).use { db =>
        val s = fixture
        for
          a       <- start(db, s)
          b       <- start(db, s)
          next    <- GameId.random
          results <- (
            db.rematches.commitSuccessor(a._1, a._2.version, next, initial(s)),
            db.rematches.commitSuccessor(b._1, b._2.version, next, initial(s, swap = true))
          ).parTupled
          sources <- List(a._1, b._1).traverse(db.rematches.session)
        yield
          val values = List(results._1, results._2)
          assertEquals(values.count(_ == RematchCommit.Rejected), 1)
          assertEquals(sources.flatten.count(_.successorId.contains(next)), 1)
      }
    }

  test("closure racing with commit cannot reopen a source or leave an orphan successor"):
    withContainers { pg =>
      store(pg).use { db =>
        val s = fixture
        for
          started <- start(db, s)
          (id, accepted) = started
          cancelled <- db.rematches.advance(id, accepted.version, RematchChange.Close(RematchCloseReason.Cancelled))
          next      <- GameId.random
          _         <- (
            db.rematches.commitSuccessor(id, accepted.version, next, initial(s)),
            db.rematches.advance(id, accepted.version, RematchChange.Close(RematchCloseReason.TechnicalFailure))
          ).parTupled
          terminal  <- db.rematches.session(id).map(_.get)
          successor <- db.rematches.successor(next)
        yield
          assertEquals(cancelled, RematchWrite.Conflict(accepted), "acceptance disables unilateral cancellation")
          assert(Set(RematchPhase.Matched, RematchPhase.Closed)(terminal.phase))
          assertEquals(terminal.successorId.isDefined, successor.isDefined)
      }
    }

  test("startup aborted before the terminal snapshot is recoverable, with bounded keyset reads"):
    withContainers { pg =>
      store(pg).use { db =>
        val s = fixture
        for
          started <- start(db, s)
          next    <- GameId.random
          game    <- db.rematches.commitSuccessor(started._1, started._2.version, next, initial(s)).map(committed)
          _       <- db.rematches.updateStartup(next, 0, RematchStartup(RematchStartupPhase.Aborted, Set.empty, None))
          pending <- db.rematches.pendingStartup(None, 500)
          first   <- db.rematches.pendingStartup(None, 1)
          rest    <- db.rematches.pendingStartup(first.headOption.map(_.gameId), 500)
          _       <- db.save(
            next,
            game.initialSnapshot.copy(status = GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted)))
          )
          finished <- db.rematches.pendingStartup(None, 500)
          retained <- db.rematches.session(started._1)
        yield
          assert(pending.exists(_.gameId == next))
          assertEquals(first.size, 1)
          assertEquals(first.map(_.gameId) ++ rest.map(_.gameId), pending.map(_.gameId))
          assert(!finished.exists(_.gameId == next))
          assertEquals(retained.flatMap(_.successorId), Some(next))
      }
    }

  test("the creation seam accepts real GameRoom initial snapshots for every supported time-control shape"):
    withContainers { pg =>
      store(pg).use { db =>
        List(TimeControl.Unlimited, TimeControl.SuddenDeath(300), TimeControl.Fischer(300, 3), TimeControl.PerMove(2))
          .traverse_ { control =>
            val s = fixture.copy(timeControl = control)
            for
              started  <- start(db, s)
              next     <- GameId.random
              captured <- Ref.of[IO, Option[GameSnapshot]](None)
              dice     <- DiceSource.newCommitReveal()
              room     <- GameRoom
                .create(
                  s.players,
                  dice,
                  timeControl = control,
                  origin = GameOrigin.Direct,
                  persist = snapshot => captured.update(_.orElse(Some(snapshot)))
                )
                .map(_.fold(error => fail(error), identity))
              _ <- (for
                snapshot <- captured.get.map(_.get)
                result   <- db.rematches.commitSuccessor(started._1, started._2.version, next, snapshot)
              yield assertEquals(committed(result).initialSnapshot.timeControl, control)).guarantee(room.abort)
            yield ()
          }
      }
    }
