package dicechess.play.server

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol as Lab
import dicechess.play.dice.DiceSource
import dicechess.play.game.{Durability, GameRoom}
import dicechess.play.store.{GameSnapshot, LaboratoryStorage}
import dicechess.play.wire.Codecs.given
import dicechess.play.wire.LaboratoryCodecs.given
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration.*

class LaboratoryWebSocketSuite extends munit.CatsEffectSuite:
  private val players = Map[Seat, Principal](
    Seat.White -> Principal.Guest("11111111-1111-1111-1111-111111111111"),
    Seat.Black -> Principal.Guest("22222222-2222-2222-2222-222222222222")
  )
  private def dice = DiceSource.commitReveal(Array.tabulate[Byte](32)(_.toByte))

  private def laboratory: Resource[IO, (GameRoom, Ref[IO, Vector[GameSnapshot]])] =
    Resource.make(for
      at       <- IO.realTimeInstant
      made     <- GameRoom.laboratorySnapshot(players, dice, TimeControl.Fischer(60, 5), at.plusSeconds(30))
      initial  <- IO.fromEither(made.leftMap(message => new RuntimeException(message)))
      writes   <- Ref.of[IO, Vector[GameSnapshot]](Vector(initial))
      restored <- GameRoom.restoreLaboratory(
        initial,
        GameRoom.RoomTuning(seedGrace = 5.seconds),
        GameRoom.RoomPersistence(
          next =>
            IO.fromEither(
              LaboratoryStorage
                .validateSnapshot(next)
                .leftMap(message => new RuntimeException(message))
            ) *> writes.update(_ :+ next),
          Durability.required(_ => IO.unit)
        )
      )
      room <- IO.fromEither(restored.leftMap(message => new RuntimeException(message)))
    yield room -> writes)((room, _) => room.stopForRestart)

  private def ready(room: GameRoom, left: Int = 100): IO[PublicGameState] = room.snapshot.flatMap: state =>
    if state.lab.exists(_.turn.phase == Lab.Phase.AwaitingFirstRoll) then IO.pure(state)
    else if left == 0 then IO.raiseError(new RuntimeException("laboratory did not become ready"))
    else IO.sleep(10.millis) *> ready(room, left - 1)

  private def send(room: GameRoom, queue: Queue[IO, WebSocketFrame], json: Json): IO[Unit] =
    Stream
      .emit[IO, WebSocketFrame](WebSocketFrame.Text(json.noSpaces))
      .through(PlayRoutes.fromClient(room, Seat.White, Some(queue)))
      .compile
      .drain

  private def receive(queue: Queue[IO, WebSocketFrame]): IO[Json] = queue.take.flatMap:
    case WebSocketFrame.Text(value, _) => IO.fromEither(parse(value))
    case _                             => IO.raiseError(new RuntimeException("unexpected private frame"))

  test("WebSocket laboratory commands acknowledge privately and duplicate requests resync without another write"):
    laboratory.use: (room, writes) =>
      (room.connection(Seat.White), room.connection(Seat.Black)).tupled.use: _ =>
        for
          _     <- room.submit(Seat.White, GameCommand.SubmitSeed("white-wire-seed-fixture"))
          _     <- room.submit(Seat.Black, GameCommand.SubmitSeed("black-wire-seed-fixture"))
          state <- ready(room)
          queue <- Queue.unbounded[IO, WebSocketFrame]
          command = Lab.Command("wire-first", state.version, Lab.Action.RollFirst)
          _        <- send(room, queue, Lab.CommandFrame(command).asJson)
          ack      <- receive(queue)
          snapshot <- receive(queue)
          receipt   = ack.hcursor.downField("LabCommandApplied")
          _         = assertEquals(receipt.get[String]("commandId").toOption, Some("wire-first"))
          public    = snapshot.hcursor.downField("Snapshot").downField("state")
          projected = public.downField("lab").downField("turn")
          _         = assertEquals(projected.get[String]("phase").toOption, Some("AwaitingSecondRoll"))
          _         = assertEquals(projected.get[List[List[Int]]]("offers").toOption.map(_.size), Some(1))
          _         = assertEquals(public.get[Option[String]]("seed").toOption, Some(None))
          before    <- writes.get
          _         <- send(room, queue, Lab.CommandFrame(command).asJson)
          duplicate <- receive(queue)
          fresh     <- receive(queue)
          after     <- writes.get
          _ = assertEquals(duplicate, ack)
          _ = assert(fresh.hcursor.downField("Snapshot").succeeded)
          _ = assertEquals(after.size, before.size)
          _ = assertEquals(after.last.version, before.last.version)
        yield ()

  test("malformed pair indices and classic SubmitTurn cannot bypass the laboratory version guard"):
    laboratory.use: (room, _) =>
      (room.connection(Seat.White), room.connection(Seat.Black)).tupled.use: _ =>
        for
          _      <- room.submit(Seat.White, GameCommand.SubmitSeed("white-wire-seed-fixture"))
          _      <- room.submit(Seat.Black, GameCommand.SubmitSeed("black-wire-seed-fixture"))
          _      <- ready(room)
          queue  <- Queue.unbounded[IO, WebSocketFrame]
          before <- room.snapshot
          malformed = Json.obj(
            "LabCommand" -> Json.obj(
              "commandId"       -> "bad-pair".asJson,
              "expectedVersion" -> before.version.asJson,
              "action"          -> Json.obj("ChoosePair" -> Json.obj("pairIndex" -> 9.asJson))
            )
          )
          _        <- send(room, queue, malformed)
          rejected <- receive(queue)
          _ = assertEquals(
            rejected.hcursor.downField("LabRejected").get[String]("code").toOption,
            Some("InvalidPairIndex")
          )
          afterMalformed <- room.snapshot
          _              <- send(room, queue, (GameCommand.SubmitTurn(List("e2e4")): GameCommand).asJson)
          legacy         <- receive(queue)
          afterLegacy    <- room.snapshot
          _ = assertEquals(legacy.hcursor.downField("LabRejected").get[String]("code").toOption, Some("InvalidCommand"))
          _ = assertEquals(afterLegacy.version, afterMalformed.version)
          _ = assertEquals(afterLegacy.lab.map(_.turn.offers), Some(Nil))
        yield ()

  test("a classic room refuses the laboratory envelope without advancing its dice or version"):
    Resource
      .make(
        GameRoom
          .create(players, dice)
          .flatMap(value => IO.fromEither(value.leftMap(message => new RuntimeException(message))))
      )(_.stopForRestart)
      .use: room =>
        for
          queue  <- Queue.unbounded[IO, WebSocketFrame]
          before <- room.snapshot
          _      <- send(
            room,
            queue,
            Lab.CommandFrame(Lab.Command("classic-refusal", before.version, Lab.Action.RollFirst)).asJson
          )
          refused <- receive(queue)
          _       <- receive(queue)
          after   <- room.snapshot
          _ = assertEquals(
            refused.hcursor.downField("LabRejected").get[String]("code").toOption,
            Some("InvalidCommand")
          )
          _ = assertEquals(after.version, before.version)
          _ = assertEquals(after.dfen, before.dfen)
        yield ()

  test("a terminal greeting leaves time to recover an original private command receipt"):
    laboratory.use: (room, _) =>
      (room.connection(Seat.White), room.connection(Seat.Black)).tupled.use: _ =>
        for
          _       <- room.submit(Seat.White, GameCommand.SubmitSeed("white-wire-seed-fixture"))
          _       <- room.submit(Seat.Black, GameCommand.SubmitSeed("black-wire-seed-fixture"))
          initial <- ready(room)
          command = Lab.Command("terminal-retry", initial.version, Lab.Action.RollFirst)
          accepted <- room.submitLaboratory(Seat.White, command)
          _ = assert(accepted.isRight)
          _        <- room.resign(Seat.Black)
          queue    <- Queue.unbounded[IO, WebSocketFrame]
          greeted  <- Deferred[IO, Unit]
          outgoing <- PlayRoutes
            .clientFrames(room, 10.seconds, Stream.fromQueueUnterminated(queue))
            .evalTap {
              case _: WebSocketFrame.Text => greeted.complete(()).void
              case _                      => IO.unit
            }
            .take(2)
            .compile
            .toList
            .start
          _      <- greeted.get
          _      <- IO.sleep(30.millis)
          _      <- send(room, queue, Lab.CommandFrame(command).asJson)
          frames <- outgoing.joinWithNever.timeout(1.second)
          payloads = frames.collect { case WebSocketFrame.Text(text, _) => parse(text).toOption.get }
          _        = assert(
            payloads.exists(
              _.hcursor.downField("LabCommandApplied").get[String]("commandId").toOption.contains("terminal-retry")
            )
          )
        yield ()
