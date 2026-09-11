package dicechess.play.game

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.store.*
import dicechess.play.wire.Codecs.given
import io.circe.syntax.*
import io.circe.parser.decode
import munit.CatsEffectSuite

import java.time.Instant
import scala.concurrent.duration.*

class PublicRematchStartupSuite extends CatsEffectSuite:
  private val epoch   = Instant.parse("2026-09-07T12:00:00Z")
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

  private def makeRoom(time: Ref[IO, Instant], saves: Ref[IO, List[RematchStartup]]) =
    for
      dice <- IO.fromEither(DiceSource.fromHexSeed(initial.serverSeed).leftMap(new RuntimeException(_)))
      made <- GameRoom.restore(
        initial,
        dice,
        persistence = GameRoom.RoomPersistence(durability = Durability.required(_ => IO.unit)),
        initialJoin = Some(
          GameRoom.InitialJoinGate(
            epoch.plusSeconds(15),
            RematchStartup(RematchStartupPhase.AwaitingJoins, Set.empty, None),
            (_, startup) => saves.update(_ :+ startup),
            time.get
          )
        )
      )
    yield made.toOption.get

  test("pending and active startup states are projected without private join identities"):
    for
      time    <- Ref.of[IO, Instant](epoch)
      saves   <- Ref.of[IO, List[RematchStartup]](Nil)
      r       <- makeRoom(time, saves)
      ws      <- r.subscribe.collect { case GameEvent.Snapshot(_, state, _) => state }.take(1).compile.lastOrError.start
      wsState <- ws.joinWithNever
      _       <- r.start
      pending <- r.snapshot
      active  <- r.connection(Seat.White).use(_ => r.connection(Seat.Black).use(_ => r.snapshot))
      _       <- r.abort
      pendingJson = pending.asJson
      activeJson  = active.asJson
    yield
      assertEquals(
        pending.rematchStartup,
        Some(PublicRematchStartup(PublicRematchStartupPhase.AwaitingJoins, Some(epoch.plusSeconds(15))))
      )
      assertEquals(active.rematchStartup, Some(PublicRematchStartup(PublicRematchStartupPhase.Active)))
      assertEquals(wsState.rematchStartup, pending.rematchStartup)
      assert(!pendingJson.noSpaces.contains("joined"))
      assert(!pendingJson.noSpaces.contains("white-token"))
      assertEquals(
        pendingJson.hcursor.downField("rematchStartup").downField("phase").as[String],
        Right("awaiting_joins")
      )
      assertEquals(
        pendingJson.hcursor.downField("rematchStartup").downField("joinDeadlineAt").as[String],
        Right("2026-09-07T12:00:15Z")
      )
      assertEquals(
        activeJson.hcursor.downField("rematchStartup").as[io.circe.Json].map(_.noSpaces),
        Right("{\"phase\":\"active\"}")
      )

  test("aborted startup is public and ordinary games omit the optional field"):
    for
      time    <- Ref.of[IO, Instant](epoch)
      saves   <- Ref.of[IO, List[RematchStartup]](Nil)
      r       <- makeRoom(time, saves)
      aborted <- r.connection(Seat.White).use { _ =>
        time.set(epoch.plusSeconds(15)) *> r.start *> r.result *> r.snapshot
      }
      ordinaryDice <- IO.fromEither(DiceSource.fromHexSeed(initial.serverSeed).leftMap(new RuntimeException(_)))
      ordinary     <- GameRoom
        .restore(
          initial.copy(players = Map(Seat.White -> Principal.Guest("w"), Seat.Black -> Principal.User("b"))),
          ordinaryDice,
          persistence = GameRoom.RoomPersistence(durability = Durability.BestEffort)
        )
        .flatMap(_.left.map(error => new RuntimeException(error.toString)).liftTo[IO])
        .flatMap(_.snapshot)
      _ <- r.abort
    yield
      assertEquals(aborted.rematchStartup, Some(PublicRematchStartup(PublicRematchStartupPhase.Aborted)))
      assertEquals(ordinary.rematchStartup, None)
      assert(!ordinary.asJson.hcursor.downField("rematchStartup").succeeded)

  test("missing rematchStartup remains backward-decodable"):
    val json = PublicGameState(
      1L,
      "fen",
      Seat.White,
      dicePending = false,
      GameStatus.Active,
      TimeControl.Unlimited,
      None,
      "commit",
      None,
      None
    ).asJson.mapObject(_.remove("rematchStartup"))
    assertEquals(decode[PublicGameState](json.noSpaces).map(_.rematchStartup), Right(None))

  test("an existing subscriber receives one active Snapshot only after the activation write commits"):
    for
      entered     <- Deferred[IO, Unit]
      release     <- Deferred[IO, Unit]
      initialSeen <- Deferred[IO, Unit]
      activeSeen  <- Deferred[IO, PublicGameState]
      saved       <- Ref.of[IO, List[GameSnapshot]](Nil)
      dice        <- IO.fromEither(DiceSource.fromHexSeed(initial.serverSeed).leftMap(new RuntimeException(_)))
      made        <- GameRoom.restore(
        initial,
        dice,
        tuning = GameRoom.RoomTuning(seedGrace = 1.minute),
        persistence = GameRoom.RoomPersistence(durability = Durability.required(_ => IO.unit)),
        initialJoin = Some(
          GameRoom.InitialJoinGate(
            epoch.plusSeconds(15),
            RematchStartup(RematchStartupPhase.AwaitingJoins, Set.empty, None),
            (snapshot, startup) =>
              (entered.complete(()).void *> release.get).whenA(startup.phase == RematchStartupPhase.Active) *>
                saved.update(_ :+ snapshot),
            IO.pure(epoch)
          )
        )
      )
      r = made.toOption.get
      _ <- (for
        observer <- r.subscribe
          .evalMap {
            case GameEvent.Snapshot(_, state, _)
                if state.rematchStartup.exists(_.phase == PublicRematchStartupPhase.AwaitingJoins) =>
              initialSeen.complete(()).void
            case GameEvent.Snapshot(version, state, _)
                if state.rematchStartup.exists(_.phase == PublicRematchStartupPhase.Active) =>
              saved.get.flatMap { snapshots =>
                IO(assert(snapshots.exists(s => s.started && s.version == version))) *> activeSeen.complete(state).void
              }
            case _ => IO.unit
          }
          .compile
          .drain
          .start
        _ <- (for
          _ <- initialSeen.get.timeout(1.second)
          _ <- r.connection(Seat.White).use { _ =>
            for
              black     <- r.connection(Seat.Black).allocated.start
              _         <- entered.get.timeout(1.second)
              early     <- activeSeen.tryGet
              _         <- IO(assertEquals(early, None, "activation must not be published before durable commit"))
              _         <- release.complete(())
              connected <- black.joinWithNever.timeout(1.second)
              state     <- activeSeen.get.timeout(1.second).guarantee(connected._2)
              snapshots <- saved.get
            yield
              assertEquals(state.version, 1L)
              assert(!state.dicePending, "the activation Snapshot must not wait for client seeds or the opening roll")
              assertEquals(state.rematchStartup, Some(PublicRematchStartup(PublicRematchStartupPhase.Active)))
              assertEquals(snapshots.count(_.started), 1)
          }
        yield ()).guarantee(release.complete(()).void *> observer.cancel)
      yield ()).guarantee(release.complete(()).void *> r.stopForRestart)
    yield ()
