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
import org.http4s.client.websocket.{WSConnection, WSConnectionHighLevel, WSFrame, WSRequest}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkWSClient
import org.http4s.websocket.WebSocketFrame
import scodec.bits.ByteVector

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

  private def routeServer(room: GameRoom, authenticationTimeout: FiniteDuration = 5.seconds): Resource[IO, Int] =
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
      .withHttpWebSocketApp(wsb =>
        (LaboratoryWebSocket(registry, wsb, authenticationTimeout = authenticationTimeout) <+>
          PlayRoutes(registry, wsb)).orNotFound
      )
      .build
      .map(_.address.getPort)

  private def authentication(token: String, guest: String): WSFrame.Text =
    WSFrame.Text(
      Json
        .obj(
          "LabAuthenticate" -> Json.obj("token" -> token.asJson, "guest" -> guest.asJson)
        )
        .noSpaces
    )

  private def expectPolicyClose(conn: WSConnection[IO]): IO[Unit] =
    conn.receiveStream
      .dropWhile {
        case _: WSFrame.Ping | _: WSFrame.Pong => true
        case _                                 => false
      }
      .take(1)
      .compile
      .lastOrError
      .timeout(2.seconds)
      .flatMap {
        case close: WSFrame.Close => IO(assertEquals(close.statusCode, 1008))
        case frame                => IO(fail(s"expected close 1008 before data, received $frame"))
      }

  private def awaitSeatConnection(room: GameRoom, seat: Seat, expected: Boolean, remaining: Int = 100): IO[Unit] =
    room
      .seatConnected(seat)
      .flatMap: connected =>
        if connected == expected then IO.unit
        else if remaining <= 0 then IO.raiseError(new RuntimeException(s"seat $seat did not reach presence=$expected"))
        else IO.sleep(10.millis) *> awaitSeatConnection(room, seat, expected, remaining - 1)

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

  test("only a valid first authentication frame attaches a seat and it is acknowledged before the feed"):
    laboratory.use: (room, writes) =>
      routeServer(room).use: port =>
        JdkWSClient
          .simple[IO]
          .use: ws =>
            val uri = Uri.unsafeFromString(s"ws://127.0.0.1:$port") / "games" / laboratoryGameId.value / "ws"
            for
              initial <- room.snapshot
              _       <- writes.get.map(value => assertEquals(value.size, 1))
              _       <- ws
                .connectHighLevel(WSRequest(uri))
                .use: white =>
                  for
                    _       <- awaitSeatConnection(room, Seat.White, expected = false)
                    pending <- room.snapshot
                    _ = assertEquals(pending.version, initial.version)
                    _ <- writes.get.map(value => assertEquals(value.size, 1))
                    _ <- white.send(authentication(room.joinTokens(Seat.White), "11111111-1111-1111-1111-111111111111"))
                    ack <- readJson(white)
                    _ = assertEquals(
                      ack.hcursor.downField("LabAuthenticated").get[String]("seat").toOption,
                      Some("White")
                    )
                    _ <- awaitPublicVersion(white, initial.version)
                    _ <- awaitSeatConnection(room, Seat.White, expected = true)
                  yield ()
              after <- room.snapshot
              _ = assertEquals(after.dfen, initial.dfen)
              _ = assertEquals(after.lab.map(_.turn.phase), Some(Lab.Phase.AwaitingPlayers))
            yield ()

  test("invalid, premature, and non-text first frames close with policy code and never attach presence"):
    laboratory.use: (room, writes) =>
      laboratory.use: (otherRoom, _) =>
        routeServer(room).use: port =>
          JdkWSClient
            .simple[IO]
            .use: ws =>
              val uri = Uri.unsafeFromString(s"ws://127.0.0.1:$port") / "games" / laboratoryGameId.value / "ws"
              val invalidFrames: List[WSFrame] = List(
                authentication("wrong-token", "11111111-1111-1111-1111-111111111111"),
                authentication(otherRoom.joinTokens(Seat.White), "11111111-1111-1111-1111-111111111111"),
                authentication(room.joinTokens(Seat.White), "invalid-guest"),
                WSFrame.Text("{\"LabAuthenticate\":{\"token\":\"x\"}}"),
                WSFrame.Text("{\"LabAuthenticate\":{\"token\":\"x\",\"guest\":\"x\",\"seat\":\"White\"}}"),
                WSFrame.Text("{"),
                WSFrame.Text("x" * 1100),
                WSFrame.Text("\"" + "\u20ac" * 400 + "\""),
                WSFrame.Binary(ByteVector.encodeAscii("not text").toOption.get),
                WSFrame.Text("{\"LabAuthenticate\":", last = false),
                WSFrame.Text((GameCommand.SubmitSeed("must-not-become-a-seed"): GameCommand).asJson.noSpaces)
              )
              for
                initial <- room.snapshot
                _       <- invalidFrames.traverse_(frame =>
                  ws.connect(WSRequest(uri))
                    .use: conn =>
                      conn.send(frame) *> expectPolicyClose(conn) *>
                        room.seatConnected(Seat.White).map(connected => assert(!connected))
                )
                after     <- room.snapshot
                persisted <- writes.get
                _ = assertEquals(after.version, initial.version)
                _ = assertEquals(persisted.size, 1)
              yield ()

  test("an idle deadline survives control pings and times out an unauthenticated connection"):
    laboratory.use: (room, _) =>
      routeServer(room, 250.millis).use: port =>
        JdkWSClient
          .simple[IO]
          .use: ws =>
            val uri = Uri.unsafeFromString(s"ws://127.0.0.1:$port") / "games" / laboratoryGameId.value / "ws"
            ws.connect(WSRequest(uri))
              .use: conn =>
                conn.send(WSFrame.Ping(ByteVector.empty)) *> expectPolicyClose(conn) *>
                  room.seatConnected(Seat.White).map(connected => assert(!connected))

  test("seat URLs reject legacy capability queries while the public spectator URL still works"):
    laboratory.use: (room, _) =>
      routeServer(room).use: port =>
        JdkWSClient
          .simple[IO]
          .use: ws =>
            val base   = Uri.unsafeFromString(s"ws://127.0.0.1:$port") / "games" / laboratoryGameId.value / "ws"
            val legacy = base +? ("token" -> room.joinTokens(Seat.White))
            val guest  = base +? ("guest" -> "11111111-1111-1111-1111-111111111111")
            val mixed  = base.withQueryParam("mode", "spectator").withQueryParam("token", room.joinTokens(Seat.White))
            for
              legacyResult <- ws.connectHighLevel(WSRequest(legacy)).use(_ => IO.unit).attempt
              guestResult  <- ws.connectHighLevel(WSRequest(guest)).use(_ => IO.unit).attempt
              mixedResult  <- ws.connectHighLevel(WSRequest(mixed)).use(_ => IO.unit).attempt
              _ = assert(legacyResult.isLeft, "a legacy seat token in the URL must be rejected")
              _ = assert(guestResult.isLeft, "a guest id in the URL must be rejected")
              _ = assert(mixedResult.isLeft, "spectator mode cannot be combined with a seat token")
              _ <- ws
                .connectHighLevel(WSRequest(base +? ("mode" -> "spectator")))
                .use: viewer =>
                  readJson(viewer).void
              _ <- room.seatConnected(Seat.White).map(connected => assert(!connected))
            yield ()

  test("a second authentication frame cannot switch the authenticated seat"):
    laboratory.use: (room, _) =>
      routeServer(room).use: port =>
        JdkWSClient
          .simple[IO]
          .use: ws =>
            val uri = Uri.unsafeFromString(s"ws://127.0.0.1:$port") / "games" / laboratoryGameId.value / "ws"
            val authenticatedWhite = for
              _ <- room.submit(Seat.White, GameCommand.SubmitSeed("white-auth-seat-seed-0123456789"))
              _ <- room.submit(Seat.Black, GameCommand.SubmitSeed("black-auth-seat-seed-0123456789"))
              _ <- ws
                .connectHighLevel(WSRequest(uri))
                .use: conn =>
                  for
                    _ <- conn.send(authentication(room.joinTokens(Seat.White), "11111111-1111-1111-1111-111111111111"))
                    ack <- readJson(conn)
                    _ = assertEquals(
                      ack.hcursor.downField("LabAuthenticated").get[String]("seat").toOption,
                      Some("White")
                    )
                    readyState <- ready(room)
                    _ <- conn.send(authentication(room.joinTokens(Seat.Black), "22222222-2222-2222-2222-222222222222"))
                    command = Lab.Command("auth-seat-barrier", readyState.version, Lab.Action.RollFirst)
                    _        <- conn.send(WSFrame.Text(Lab.CommandFrame(command).asJson.noSpaces))
                    advanced <- awaitRoomVersion(room, readyState.version)
                    frames   <- awaitReceiptAndSnapshot(conn, command.commandId, advanced.version)
                    _ = assert(
                      frames.exists(
                        _.hcursor
                          .downField("LabCommandApplied")
                          .get[String]("commandId")
                          .toOption
                          .contains(command.commandId)
                      ),
                      "the authenticated White seat should still be able to act after a duplicate auth frame"
                    )
                    _ <- awaitSeatConnection(room, Seat.White, expected = true)
                  yield ()
            yield ()
            room.connection(Seat.Black).use(_ => authenticatedWhite)

  test("a complete command in a non-final text frame cannot change the authenticated room"):
    laboratory.use: (room, writes) =>
      routeServer(room).use: port =>
        JdkWSClient
          .simple[IO]
          .use: ws =>
            val uri = Uri.unsafeFromString(s"ws://127.0.0.1:$port") / "games" / laboratoryGameId.value / "ws"
            room
              .connection(Seat.Black)
              .use: _ =>
                ws.connectHighLevel(WSRequest(uri))
                  .use: conn =>
                    for
                      _ <- room.submit(Seat.White, GameCommand.SubmitSeed("white-fragment-seed-0123456789"))
                      _ <- room.submit(Seat.Black, GameCommand.SubmitSeed("black-fragment-seed-0123456789"))
                      _ <- conn.send(
                        authentication(room.joinTokens(Seat.White), "11111111-1111-1111-1111-111111111111")
                      )
                      ack <- readJson(conn)
                      _ = assertEquals(
                        ack.hcursor.downField("LabAuthenticated").get[String]("seat").toOption,
                        Some("White")
                      )
                      before       <- ready(room)
                      beforeWrites <- writes.get
                      fragmented = Lab.Command("non-final-command", before.version, Lab.Action.RollFirst)
                      _ <- conn.send(WSFrame.Text(Lab.CommandFrame(fragmented).asJson.noSpaces, last = false))
                      // Finish the message with a continuation, then use an invalid-stage command as a processing barrier.
                      _ <- conn.send(WSFrame.Text("", last = true))
                      barrier = Lab.Command("after-fragment-barrier", before.version, Lab.Action.RollSecond)
                      _           <- conn.send(WSFrame.Text(Lab.CommandFrame(barrier).asJson.noSpaces))
                      frames      <- awaitPrivateRejection(conn, barrier.commandId)
                      after       <- room.snapshot
                      afterWrites <- writes.get
                      rejection = frames.last.hcursor.downField("LabRejected")
                      _         = assertEquals(rejection.get[String]("code").toOption, Some("WrongStage"))
                      _         = assertEquals(after.version, before.version)
                      _         = assertEquals(after.dfen, before.dfen)
                      _         = assertEquals(after.lab, before.lab)
                      _         = assertEquals(afterWrites.size, beforeWrites.size)
                      _         = assert(!frames.exists(_.hcursor.downField("LabCommandApplied").succeeded))
                    yield ()

  test("disconnecting before authentication leaves the room untouched"):
    laboratory.use: (room, writes) =>
      routeServer(room).use: port =>
        JdkWSClient
          .simple[IO]
          .use: ws =>
            val uri = Uri.unsafeFromString(s"ws://127.0.0.1:$port") / "games" / laboratoryGameId.value / "ws"
            for
              before <- room.snapshot
              _      <- ws
                .connectHighLevel(WSRequest(uri))
                .use: _ =>
                  IO.unit
              after     <- room.snapshot
              persisted <- writes.get
              _         <- awaitSeatConnection(room, Seat.White, expected = false)
              _ = assertEquals(after.version, before.version)
              _ = assertEquals(persisted.size, 1)
            yield ()

  test("the routed laboratory command receipt reaches only its token-authorized WebSocket"):
    laboratory.use: (room, _) =>
      routeServer(room).use: port =>
        JdkWSClient
          .simple[IO]
          .use: ws =>
            val base      = Uri.unsafeFromString(s"ws://127.0.0.1:$port") / "games" / laboratoryGameId.value / "ws"
            val whiteUri  = base
            val blackUri  = base
            val viewerUri = base +? ("mode" -> "spectator")
            ws.connectHighLevel(WSRequest(whiteUri))
              .use: white =>
                ws.connectHighLevel(WSRequest(blackUri))
                  .use: black =>
                    ws.connectHighLevel(WSRequest(viewerUri))
                      .use: viewer =>
                        for
                          _ <- white.send(
                            authentication(room.joinTokens(Seat.White), "11111111-1111-1111-1111-111111111111")
                          )
                          _ <- black.send(
                            authentication(room.joinTokens(Seat.Black), "22222222-2222-2222-2222-222222222222")
                          )
                          whiteAuth <- readJson(white)
                          blackAuth <- readJson(black)
                          _ = assertEquals(
                            whiteAuth.hcursor.downField("LabAuthenticated").get[String]("seat").toOption,
                            Some("White")
                          )
                          _ = assertEquals(
                            blackAuth.hcursor.downField("LabAuthenticated").get[String]("seat").toOption,
                            Some("Black")
                          )
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
