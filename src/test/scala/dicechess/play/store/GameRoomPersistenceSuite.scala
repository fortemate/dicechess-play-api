package dicechess.play.store

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.engine.search.BotRegistry
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.{BotConnection, GameRoom}

import scala.concurrent.duration.*

/** The room's persistence contract, storage-agnostic: what gets written, and when — checked through an in-memory
  * `persist` callback (no database involved).
  */
class GameRoomPersistenceSuite extends munit.CatsEffectSuite:

  private def greedy = BotRegistry.getAlgorithm("greedy").get
  private def dice   = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
  private def seats  =
    Map[Seat, Principal](Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black"))

  /** Poll the persisted snapshots until `pred` holds — persistence runs before broadcast, so asserting on the store
    * side is race-free where subscribing to live events is not (a slow subscriber can miss the opening roll).
    */
  private def awaitWritten(written: Ref[IO, Vector[GameSnapshot]])(
      pred: Vector[GameSnapshot] => Boolean
  ): IO[Vector[GameSnapshot]] =
    written.get
      .flatTap(snaps => IO.sleep(20.millis).unlessA(pred(snaps)))
      .iterateUntil(pred)
      .timeoutTo(10.seconds, IO.raiseError(RuntimeException("expected snapshot was never persisted")))

  test("the creation row is durable before anyone plays: tokens, seed and version 0 are in the first snapshot"):
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRoom
        .create(seats, dice, persist = snap => written.update(_ :+ snap))
        .flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
          case Right(room) =>
            written.get.map { snaps =>
              val first = snaps.headOption.getOrElse(fail("the creation snapshot must be written"))
              assertEquals(first.version, 0L)
              assertEquals(first.seatTokens, room.joinTokens)
              assertEquals(first.serverSeed, dice.reveal)
              assert(!first.ended)
            }
        }
    }

  test("every published event is persisted, versions are monotonic, and the pending roll is durable"):
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRoom
        .create(seats, dice, seedGrace = 50.millis, persist = snap => written.update(_ :+ snap))
        .flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
          case Right(room) =>
            for
              _     <- room.start
              _     <- awaitWritten(written)(_.exists(_.pending)) // the grace force-start rolled, durably
              _     <- room.submit(Seat.White, GameCommand.Resign)
              snaps <- awaitWritten(written)(_.lastOption.exists(_.ended))
            yield
              val versions = snaps.map(_.version)
              assertEquals(versions, versions.sorted, "what a subscriber saw as version N must be durable as N")
              assert(snaps.last.ended, "the terminal snapshot must be marked ended")
              // The roll was persisted while pending: its dice pool sits in the DFEN's 7th field.
              assert(
                snaps.exists(s => s.pending && s.dfen.trim.split("\\s+").length == 7),
                "a snapshot with the rolled dice pool must be durable before the turn is played"
              )
        }
    }

  test("a full game records every completed turn: consecutive numbers, three dice, passes with no moves"):
    val white = BotConnection(Principal.Guest("white"), Seat.White, greedy)
    val black = BotConnection(Principal.Bot("acme", "greedy"), Seat.Black, greedy)
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRoom
        .create(seats, dice, persist = snap => written.update(_ :+ snap))
        .flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
          case Right(room) =>
            (white.run(room).background, black.run(room).background).tupled
              .use(_ => room.start *> room.result)
              .timeoutTo(20.seconds, IO.raiseError(RuntimeException("game did not finish in time")))
              *> written.get
        }
        .map { snaps =>
          val turns = snaps.last.turns
          assert(turns.nonEmpty, "a finished game must carry its turn history")
          assertEquals(turns.map(_.turnNumber), (1L to turns.size.toLong).toVector, "turns are consecutive from 1")
          assert(turns.forall(_.dice.size == 3), "every turn records its three dice")
          assert(turns.forall(t => t.activeColor == "w" || t.activeColor == "b"))
          assert(turns.forall(_.fenAfter.nonEmpty))
        }
    }

  test("create with rated=true persists a rated creation snapshot"):
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRoom
        .create(seats, dice, rated = true, persist = snap => written.update(_ :+ snap))
        .flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
          case Right(_)    =>
            written.get.map { snaps =>
              assert(snaps.headOption.exists(_.rated.contains(true)), "the creation snapshot must be marked rated")
            }
        }
    }

  test("create without rated defaults to a casual creation snapshot"):
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRoom
        .create(seats, dice, persist = snap => written.update(_ :+ snap))
        .flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
          case Right(_)    =>
            written.get.map { snaps =>
              assert(
                snaps.headOption.exists(_.rated.contains(false)),
                "omitting rated must default the snapshot to casual"
              )
            }
        }
    }

  test("restore carries the rated flag from a persisted snapshot into the rebuilt room"):
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRoom
        .create(seats, dice, rated = true, seedGrace = 50.millis, persist = snap => written.update(_ :+ snap))
        .flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
          case Right(room) =>
            for
              _       <- room.start
              pending <- awaitWritten(written)(_.exists(_.pending))
              snap = pending.filter(_.pending).last
              restoredDice <- IO.fromEither(DiceSource.fromHexSeed(snap.serverSeed).left.map(RuntimeException(_)))
              afterRestore <- Ref.of[IO, Vector[GameSnapshot]](Vector.empty)
              restored     <- GameRoom
                .restore(snap, restoredDice, persist = snap2 => afterRestore.update(_ :+ snap2))
                .flatMap {
                  case Left(error) => IO.raiseError(RuntimeException(s"restore failed: $error"))
                  case Right(r)    => IO.pure(r)
                }
              _     <- restored.submit(Seat.White, GameCommand.Resign)
              ended <- awaitWritten(afterRestore)(_.lastOption.exists(_.ended))
            yield assert(ended.last.rated.contains(true), "the restored room's terminal snapshot must still be rated")
        }
    }
