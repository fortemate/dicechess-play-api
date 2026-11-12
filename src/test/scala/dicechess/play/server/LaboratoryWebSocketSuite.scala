package dicechess.play.server

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import com.comcast.ip4s.*
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
import org.http4s.Uri
import org.http4s.client.websocket.{WSConnectionHighLevel, WSFrame, WSRequest}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkWSClient
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

  private val laboratoryGameId = GameId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

  private def routeServer(room: GameRoom): Resource[IO, Int] =
    val registry = new PlayRegistry:
      override def get(id: GameId): IO[Option[GameRoom]] = IO.pure(Option.when(id == laboratoryGameId)(room))
      override val list: IO[List[(GameId, GameRoom)]]    = IO.pure(Nil)
      override def claimSeat(id: GameId, seat: Seat, claimer: Principal): IO[Boolean] = IO.pure(false)
      override def createFriend(
          white: Principal,
          black: Principal,
          timeControl: TimeControl,
          variant: Option[Lab.VariantIdentity],
          protocolVersion: Option[Int],
          requestedRated: Boolean
      ): IO[Either[String, (GameId, GameRoom)]] = IO.pure(Left("unused in WebSocket test"))

    EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"0")
      .withShutdownTimeout(1.second)
      .withHttpWebSocketApp(wsb => PlayRoutes(registry, wsb).orNotFound)
      .build
      .map(_.address.getPort)

  private def readJson(conn: WSConnectionHighLevel[IO]): IO[Json] =
    conn.receiveStream
      .collect { case WSFrame.Text(value, _) => value }
      .take(1)
      .compile
      .lastOrError
      .flatMap(value => IO.fromEither(parse(value)))
      .timeout(5.seconds)

  private def publicVersion(json: Json): Option[Long] =
    json.hcursor
      .downField("Snapshot")
      .downField("state")
      .get[Long]("version")
      .toOption
      .orElse(json.hcursor.downField("Snapshot").get[Long]("v").toOption)
      .orElse(json.hcursor.downField("LabTurnChanged").get[Long]("v").toOption)

  private def awaitPublicVersion(
      conn: WSConnectionHighLevel[IO],
      version: Long,
      remaining: Int = 32
  ): IO[List[Json]] =
    if remaining <= 0 then IO.raiseError(new RuntimeException(s"WebSocket did not publish snapshot version $version"))
    else
      readJson(conn).flatMap: json =>
        if publicVersion(json).exists(_ >= version) then IO.pure(List(json))
        else awaitPublicVersion(conn, version, remaining - 1).map(json :: _)

  private def awaitRoomVersion(room: GameRoom, after: Long, remaining: Int = 100): IO[PublicGameState] =
    room.snapshot.flatMap: state =>
      if state.version > after then IO.pure(state)
      else if remaining <= 0 then IO.raiseError(new RuntimeException("laboratory command did not advance the room"))
      else IO.sleep(10.millis) *> awaitRoomVersion(room, after, remaining - 1)

  private def awaitReceiptAndSnapshot(
      conn: WSConnectionHighLevel[IO],
      commandId: String,
      version: Long,
      remaining: Int = 32,
      seenReceipt: Boolean = false,
      seenSnapshot: Boolean = false
  ): IO[List[Json]] =
    if seenReceipt && seenSnapshot then IO.pure(Nil)
    else if remaining <= 0 then
      IO.raiseError(new RuntimeException("originating WebSocket missed its receipt or snapshot"))
    else
      readJson(conn).flatMap: json =>
        val receipt = json.hcursor
          .downField("LabCommandApplied")
          .get[String]("commandId")
          .toOption
          .contains(commandId)
        val snapshot = publicVersion(json).exists(_ >= version)
        awaitReceiptAndSnapshot(
          conn,
          commandId,
          version,
          remaining - 1,
          seenReceipt || receipt,
          seenSnapshot || snapshot
        ).map(json :: _)

  private def awaitPrivateRejection(
      conn: WSConnectionHighLevel[IO],
      commandId: String,
      remaining: Int = 32
  ): IO[List[Json]] =
    if remaining <= 0 then IO.raiseError(new RuntimeException(s"WebSocket did not receive rejection for $commandId"))
    else
      readJson(conn).flatMap: json =>
        val rejected = json.hcursor.downField("LabRejected").get[String]("commandId").toOption.contains(commandId)
        if rejected then IO.pure(List(json))
        else awaitPrivateRejection(conn, commandId, remaining - 1).map(json :: _)

  test("the routed laboratory command receipt reaches only its token-authorized WebSocket"):
    laboratory.use: (room, _) =>
      routeServer(room).use: port =>
        JdkWSClient
          .simple[IO]
          .use: ws =>
            val base      = Uri.unsafeFromString(s"ws://127.0.0.1:$port") / "games" / laboratoryGameId.value / "ws"
            val whiteUri  = base +? ("token" -> room.joinTokens(Seat.White))
            val blackUri  = base +? ("token" -> room.joinTokens(Seat.Black))
            val viewerUri = base +? ("mode"  -> "spectator")
            ws.connectHighLevel(WSRequest(whiteUri))
              .use: white =>
                ws.connectHighLevel(WSRequest(blackUri))
                  .use: black =>
                    ws.connectHighLevel(WSRequest(viewerUri))
                      .use: viewer =>
                        for
                          whiteGreeting <- readJson(white)
                          blackGreeting <- readJson(black)
                          _ = assert(whiteGreeting.hcursor.downField("DrawOfferArmed").succeeded)
                          _ = assert(blackGreeting.hcursor.downField("DrawOfferArmed").succeeded)
                          _ <- white.send(
                            WSFrame.Text(
                              (GameCommand.SubmitSeed("white-route-seed-0123456789"): GameCommand).asJson.noSpaces
                            )
                          )
                          _ <- black.send(
                            WSFrame.Text(
                              (GameCommand.SubmitSeed("black-route-seed-0123456789"): GameCommand).asJson.noSpaces
                            )
                          )
                          opened <- ready(room)
                          _      <- (
                            awaitPublicVersion(white, opened.version),
                            awaitPublicVersion(black, opened.version),
                            awaitPublicVersion(viewer, opened.version)
                          ).parTupled
                          command = Lab.Command("route-private-receipt", opened.version, Lab.Action.RollFirst)
                          _        <- white.send(WSFrame.Text(Lab.CommandFrame(command).asJson.noSpaces))
                          advanced <- awaitRoomVersion(room, opened.version)
                          (whiteFrames, blackFrames, viewerFrames) <- (
                            awaitReceiptAndSnapshot(white, command.commandId, advanced.version),
                            awaitPublicVersion(black, advanced.version),
                            awaitPublicVersion(viewer, advanced.version)
                          ).parTupled
                          _ = assert(
                            whiteFrames.exists(
                              _.hcursor
                                .downField("LabCommandApplied")
                                .get[String]("commandId")
                                .toOption
                                .contains(command.commandId)
                            ),
                            "the seat token's connection should receive its laboratory receipt"
                          )
                          _ <- black.send(
                            WSFrame.Text(
                              Lab
                                .CommandFrame(
                                  Lab.Command("black-private-barrier", advanced.version, Lab.Action.RollFirst)
                                )
                                .asJson
                                .noSpaces
                            )
                          )
                          _ <- viewer.send(
                            WSFrame.Text(
                              Lab
                                .CommandFrame(
                                  Lab.Command("spectator-private-barrier", advanced.version, Lab.Action.RollFirst)
                                )
                                .asJson
                                .noSpaces
                            )
                          )
                          (blackBarrierFrames, viewerBarrierFrames) <- (
                            awaitPrivateRejection(black, "black-private-barrier"),
                            awaitPrivateRejection(viewer, "spectator-private-barrier")
                          ).parTupled
                          _ = assert(
                            !(blackFrames ++ blackBarrierFrames).exists(
                              _.hcursor
                                .downField("LabCommandApplied")
                                .get[String]("commandId")
                                .toOption
                                .contains(command.commandId)
                            ),
                            "the other token-authorized seat must not receive the private receipt"
                          )
                          _ = assert(
                            !(viewerFrames ++ viewerBarrierFrames).exists(
                              _.hcursor
                                .downField("LabCommandApplied")
                                .get[String]("commandId")
                                .toOption
                                .contains(command.commandId)
                            ),
                            "the spectator must not receive the private receipt"
                          )
                        yield ()

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
