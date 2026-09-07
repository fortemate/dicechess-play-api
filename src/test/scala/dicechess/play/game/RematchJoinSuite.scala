package dicechess.play.game

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.store.*
import munit.CatsEffectSuite

import java.time.Instant
import scala.concurrent.duration.*

class RematchJoinSuite extends CatsEffectSuite:
  private val epoch   = Instant.parse("2026-09-07T12:00:00Z")
  private val seats   = Set(Seat.White, Seat.Black)
  private val initial = GameSnapshot(
    0,
    EngineOps.InitialDfen,
    Map(Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black")),
    Map(Seat.White -> "white-token", Seat.Black            -> "black-token"),
    "a" * 64,
    Map.empty,
    false,
    0,
    false,
    GameStatus.Active,
    TimeControl.PerMove(1),
    Map.empty,
    Nil,
    Vector.empty
  )

  private def room: Resource[IO, (GameRoom, Ref[IO, Instant], Ref[IO, List[(GameSnapshot, RematchStartup)]])] =
    Resource.make {
      for
        time  <- Ref.of[IO, Instant](epoch)
        saves <- Ref.of[IO, List[(GameSnapshot, RematchStartup)]](Nil)
        dice  <- IO.fromEither(DiceSource.fromHexSeed(initial.serverSeed).leftMap(new RuntimeException(_)))
        made  <- GameRoom.restore(
          initial,
          dice,
          seedGrace = 20.millis,
          disconnectGrace = 20.millis,
          durability = Durability.required(_ => IO.unit),
          initialJoin = Some(
            GameRoom.InitialJoinGate(
              epoch.plusSeconds(15),
              RematchStartup(RematchStartupPhase.AwaitingJoins, Set.empty, None),
              (snapshot, state) => saves.update(_ :+ (snapshot -> state)),
              time.get
            )
          )
        )
        r = made.toOption.get
      yield (r, time, saves)
    } { case (r, _, _) => r.abort }

  test("Begin, seeds and short PerMove clocks cannot start a rematch before both seats join") {
    room.use { (r, _, saves) =>
      r.connection(Seat.White).use { _ =>
        for
          _       <- r.start
          _       <- r.submit(Seat.White, GameCommand.SubmitSeed("white-seed-123456"))
          _       <- r.submit(Seat.Black, GameCommand.SubmitSeed("black-seed-123456"))
          _       <- r.submit(Seat.White, GameCommand.Resign)
          _       <- IO.sleep(80.millis)
          before  <- r.snapshot
          waiting <- r.awaitingJoinDeadline
          _       <- r.connection(Seat.Black).use { _ =>
            for
              _       <- r.start.replicateA_(3)
              _       <- IO.sleep(40.millis)
              after   <- r.snapshot
              written <- saves.get
            yield
              assert(after.dicePending)
              assertEquals(
                written.count { case (s, st) => s.started && s.ply == 0 && st.phase == RematchStartupPhase.Active },
                1
              )
          }
        yield
          assertEquals(before.status, GameStatus.Active)
          assert(!before.dicePending)
          assertEquals(waiting, Some(epoch.plusSeconds(15)))
      }
    }
  }

  test("a join at the exact deadline loses to the sole currently connected seat") {
    room.use { (r, time, saves) =>
      r.connection(Seat.White).use { _ =>
        time.set(epoch.plusSeconds(15)) *> r.connection(Seat.Black).use { _ =>
          for
            result  <- r.result
            written <- saves.get
          yield
            assertEquals(result, GameOver(GameResult.Win(Side.White), Termination.Timeout))
            assertEquals(written.count(_._1.ended), 1)
            assert(written.forall(!_._1.started))
        }
      }
    }
  }

  test("historical joins do not activate a disconnected player or forfeit through ordinary grace") {
    room.use { (r, time, saves) =>
      for
        _ <- r.connection(Seat.White).use(_ => IO.unit)
        _ <- IO.sleep(50.millis)
        _ <- r.connection(Seat.Black).use { _ =>
          for
            pending <- r.awaitingJoinDeadline
            _       <- time.set(epoch.plusSeconds(15))
            _       <- r.start
            result  <- r.result
            written <- saves.get
          yield
            assert(pending.isDefined)
            assertEquals(result, GameOver(GameResult.Win(Side.Black), Termination.Timeout))
            assertEquals(written.last._2.joined, seats)
            assert(written.forall(!_._1.started))
        }
      yield ()
    }
  }

  test("neither connected at expiry is a technical abort even when a seat connected earlier") {
    room.use { (r, time, _) =>
      r.connection(Seat.White).use(_ => IO.unit) *>
        time.set(epoch.plusSeconds(15)) *> r.start *> r.result.map { result =>
          assertEquals(result.termination, Termination.Aborted)
        }
    }
  }

  test("multiple tabs count as one connected seat; reconnect within the original window activates once") {
    room.use { (r, time, saves) =>
      for
        first     <- r.connection(Seat.White).allocated
        second    <- r.connection(Seat.White).allocated
        _         <- first._2
        connected <- r.seatConnected(Seat.White)
        _         <- time.set(epoch.plusSeconds(14))
        _ <- r.connection(Seat.Black).use(_ => r.awaitingJoinDeadline.map(deadline => assertEquals(deadline, None)))
        _ <- second._2
        result  <- r.result.timeout(1.second)
        written <- saves.get
      yield
        assert(connected)
        assertEquals(result.termination, Termination.Resign)
        assertEquals(written.count(_._1.ended), 1)
    }
  }

  test("crossing the deadline between dequeue and presence handling expires against the earlier connections") {
    for
      clock   <- Ref.of[IO, IO[Instant]](IO.pure(epoch))
      joined  <- Ref.of[IO, Boolean](false)
      waiting <- Deferred[IO, Unit]
      reads   <- Ref.of[IO, Int](0)
      dice    <- IO.fromEither(DiceSource.fromHexSeed(initial.serverSeed).leftMap(new RuntimeException(_)))
      made    <- GameRoom.restore(
        initial,
        dice,
        durability = Durability.required(_ => IO.unit),
        initialJoin = Some(
          GameRoom.InitialJoinGate(
            epoch.plusSeconds(15),
            RematchStartup(RematchStartupPhase.AwaitingJoins, Set.empty, None),
            (_, state) => joined.set(state.joined.contains(Seat.White)),
            clock.get.flatten.flatTap(_ => joined.get.ifM(waiting.complete(()).void, IO.unit))
          )
        )
      )
      r = made.toOption.get
      _ <- r
        .connection(Seat.White)
        .use { _ =>
          waiting.get *> clock.set(
            reads.getAndUpdate(_ + 1).map(n => if n == 0 then epoch.plusMillis(14999) else epoch.plusSeconds(15))
          ) *>
            r.connection(Seat.Black).use { _ =>
              r.result.map { result =>
                assertEquals(result, GameOver(GameResult.Win(Side.White), Termination.Timeout))
              }
            }
        }
        .guarantee(r.abort)
    yield ()
  }
