package dicechess.play.server

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.comcast.ip4s.*
import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.dice.{DiceSource, LaboratoryDiceSource}
import dicechess.play.game.{EngineOps, GameRoom}
import dicechess.play.store.PgLaboratoryStore
import dicechess.play.wire.Codecs.given
import dicechess.play.wire.LaboratoryCodecs.given
import io.circe.Json
import io.circe.syntax.*
import org.http4s.Method.*
import org.http4s.{Header, Status, Uri}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkHttpClient
import org.testcontainers.utility.DockerImageName
import org.typelevel.ci.*

import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicInteger

class LaboratoryRoutesSuite extends munit.CatsEffectSuite with TestContainerForAll:
  private val whiteId   = "11111111-1111-1111-1111-111111111111"
  private val blackId   = "22222222-2222-2222-2222-222222222222"
  private val whiteSeed = "alice-client-seed-0123456789"
  private val blackSeed = "bob-client-seed-01234567890"

  final private case class RestartProof(id: GameId, command: Command, receipt: Receipt, moves: GameMoves)

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def store(pg: PostgreSQLContainer): Resource[IO, PgLaboratoryStore] =
    PgLaboratoryStore.resource(PgLaboratoryStore.Config(pg.jdbcUrl, pg.username, pg.password))

  private lazy val openingSeed: Array[Byte] =
    val initial = EngineOps.parse(EngineOps.InitialDfen).fold(error => fail(error), identity)
    (1 to 100).iterator
      .map(n => Array.fill[Byte](32)(n.toByte))
      .find: bytes =>
        val source = LaboratoryDiceSource.fromHexSeed(DiceSource.commitReveal(bytes).reveal).toOption.get
        source
          .offers(0, whiteSeed, blackSeed)
          .toOption
          .exists(_.values.exists(pair => EngineOps.legalMovePaths(EngineOps.withDice(initial, pair.values)).nonEmpty))
      .getOrElse(fail("could not find a deterministic seed with a playable opening pair"))

  private def registry(db: PgLaboratoryStore): Resource[IO, LaboratoryRegistry] =
    val seedNumber                     = AtomicInteger(0)
    val nextDice: () => IO[DiceSource] = () =>
      IO.delay:
        val number = seedNumber.getAndIncrement()
        val bytes  = if number == 0 then openingSeed else Array.fill[Byte](32)((100 + number).toByte)
        DiceSource.commitReveal(bytes)
    Resource.make(
      LaboratoryRegistry.create(
        db,
        tuning = GameRoom.RoomTuning(disconnectGrace = 30.seconds, seedGrace = 100.millis),
        diceSource = nextDice,
        joinWindow = 30.seconds
      )
    )(_.shutdown)

  private def server(reg: LaboratoryRegistry): Resource[IO, Int] =
    EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"0")
      .withShutdownTimeout(1.second)
      .withHttpWebSocketApp(wsb => LaboratoryRoutes(reg, wsb).orNotFound)
      .build
      .map(_.address.getPort)

  private def base(port: Int): Uri = Uri.unsafeFromString(s"http://127.0.0.1:$port")

  private def createBody: Json =
    CreateGame(
      Some(whiteId),
      Some(blackId),
      Some(TimeControl.Fischer(60, 5)),
      Some(TwoPairs),
      Some(ProtocolVersion),
      Some(false)
    ).asJson

  private def create(http: org.http4s.client.Client[IO], uri: Uri): IO[CreatedGame] =
    http.expect[CreatedGame](POST(createBody, uri / "games"))

  private def tokenOf(created: CreatedGame, seat: Seat): String =
    created.tokens.find(_.seat == seat).map(_.token).getOrElse(fail(s"missing $seat seat token"))

  private def awaitPhase(room: GameRoom, expected: Phase, attempts: Int = 100): IO[PublicGameState] =
    room.snapshot.flatMap: state =>
      if state.lab.exists(_.turn.phase == expected) then IO.pure(state)
      else if attempts <= 0 then IO.raiseError(RuntimeException(s"laboratory phase did not reach $expected"))
      else IO.sleep(10.millis) *> awaitPhase(room, expected, attempts - 1)

  private def withReadySeats[A](room: GameRoom)(use: PublicGameState => IO[A]): IO[A] =
    (room.connection(Seat.White), room.connection(Seat.Black)).tupled.use: _ =>
      for
        _     <- room.submit(Seat.White, GameCommand.SubmitSeed(whiteSeed))
        _     <- room.submit(Seat.Black, GameCommand.SubmitSeed(blackSeed))
        state <- awaitPhase(room, Phase.AwaitingFirstRoll)
        value <- use(state)
      yield value

  private def accepted(room: GameRoom, seat: Seat, action: Action, id: String): IO[Receipt] =
    room.snapshot.flatMap: state =>
      room
        .submitLaboratory(seat, Command(id, state.version, action))
        .flatMap(result => IO.fromEither(result.leftMap(error => RuntimeException(s"unexpected rejection: $error"))))

  test("POST validates laboratory mode and creates a private durable AwaitingPlayers room"):
    withContainers { pg =>
      store(pg).use: db =>
        registry(db).use: reg =>
          server(reg).use: port =>
            JdkHttpClient
              .simple[IO]
              .use: http =>
                val uri         = base(port)
                val validFields = List(
                  "white"              -> Json.fromString(whiteId),
                  "black"              -> Json.fromString(blackId),
                  "labProtocolVersion" -> Json.fromInt(ProtocolVersion)
                )
                val modeCases = List(
                  Json.obj(validFields*),
                  Json.obj(
                    (validFields :+ ("variant" -> Json.obj(
                      "id"       -> Json.fromString("classic-3d6"),
                      "revision" -> Json.fromInt(1)
                    )))*
                  ),
                  Json.obj((validFields :+ ("variant" -> Json.Null))*),
                  Json.obj((validFields.filterNot(_._1 == "labProtocolVersion") :+ ("variant" -> TwoPairs.asJson))*),
                  Json.obj((validFields :+ ("variant" -> TwoPairs.asJson) :+ ("labProtocolVersion" -> Json.Null))*),
                  Json
                    .obj((validFields :+ ("variant" -> TwoPairs.asJson) :+ ("labProtocolVersion" -> Json.fromInt(2)))*),
                  Json.obj((validFields :+ ("variant" -> TwoPairs.asJson) :+ ("rated" -> Json.fromBoolean(true)))*),
                  Json.obj(
                    (validFields :+ ("variant" -> TwoPairs.asJson) :+ ("timeControl" -> (TimeControl
                      .Fischer(0, 0): TimeControl).asJson))*
                  )
                )
                for
                  rejected  <- modeCases.traverse(body => http.status(POST(body, uri / "games")))
                  created   <- create(http, uri)
                  listing   <- http.expect[LiveGames](uri / "games")
                  snapshot  <- http.expect[PublicGameState](uri / "games" / created.gameId)
                  stored    <- db.load(GameId(created.gameId))
                  invalidId <- http.status(GET(uri / "games" / "invalid-game-id"))
                yield
                  assert(rejected.forall(_ == Status.BadRequest), s"unexpected creation statuses: $rejected")
                  assertEquals(invalidId, Status.NotFound)
                  assertEquals(created.variant, Some(TwoPairs))
                  assertEquals(created.labProtocolVersion, Some(ProtocolVersion))
                  assert(created.provenance.exists(_.diceAlgorithm == DiceAlgorithm))
                  assert(created.commit.nonEmpty)
                  assertEquals(created.tokens.map(_.seat).toSet, Set(Seat.White, Seat.Black))
                  assertEquals(listing.total, 0)
                  assertEquals(listing.games, Nil)
                  assertEquals(snapshot.lab.map(_.turn.phase), Some(Phase.AwaitingPlayers))
                  assertEquals(snapshot.lab.map(_.turn.offers), Some(Nil))
                  assert(snapshot.seed.isEmpty)
                  assertEquals(stored.map(_.effectiveVariant), Some(TwoPairs))
                  assertEquals(stored.flatMap(_.lab.map(_.turn.phase)), Some(Phase.AwaitingPlayers))
    }

  test("typed stages persist, survive registry restart, and expose only the selected move tree"):
    withContainers { pg =>
      store(pg).use: db =>
        val firstRun = registry(db).use: firstRegistry =>
          server(firstRegistry).use: firstPort =>
            JdkHttpClient
              .simple[IO]
              .use: http =>
                for
                  created <- create(http, base(firstPort))
                  id = GameId(created.gameId)
                  room  <- firstRegistry.get(id).map(_.getOrElse(fail("created room missing")))
                  proof <- withReadySeats(room): _ =>
                    for
                      firstReceipt <- accepted(room, Seat.White, Action.RollFirst, "http-first")
                      afterFirst   <- db.load(id).map(_.get)
                      hiddenState  <- http.expect[PublicGameState](base(firstPort) / "games" / created.gameId)
                      hiddenMoves  <- http.expect[GameMoves](base(firstPort) / "games" / created.gameId / "moves")
                      _ = assertEquals(afterFirst.lab.get.turn.revealedOfferCount, 1)
                      _ = assert(afterFirst.lab.get.receipts.exists(_.command.commandId == firstReceipt.commandId))
                      _ = assertEquals(hiddenState.lab.map(_.turn.offers.size), Some(1))
                      _ = assertEquals(hiddenMoves.dicePending, false)
                      _ = assertEquals(hiddenMoves.legalMoves, MoveTree.empty)
                      secondReceipt <- accepted(room, Seat.White, Action.RollSecond, "http-second")
                      afterSecond   <- db.load(id).map(_.get)
                      choiceState   <- awaitPhase(room, Phase.AwaitingPairChoice)
                      twoOffers     <- http.expect[PublicGameState](base(firstPort) / "games" / created.gameId)
                      pairs = choiceState.lab.get.turn.offers
                      initial <- IO.fromEither(
                        EngineOps.parse(choiceState.dfen).leftMap(message => new RuntimeException(message))
                      )
                      chosenIndex = pairs.zipWithIndex
                        .collectFirst {
                          case (pair, index)
                              if EngineOps.legalMovePaths(EngineOps.withDice(initial, pair.values)).nonEmpty =>
                            index
                        }
                        .getOrElse(fail("fixture seed must offer a playable pair"))
                      choiceReceipt   <- accepted(room, Seat.White, Action.ChoosePair(chosenIndex), "http-choice")
                      selected        <- awaitPhase(room, Phase.PlayingSelectedPair)
                      selectedMoves   <- http.expect[GameMoves](base(firstPort) / "games" / created.gameId / "moves")
                      durableSelected <- db.load(id).map(_.get)
                      _ = assertEquals(afterSecond.lab.get.turn.revealedOfferCount, 2)
                      _ = assert(afterSecond.lab.get.receipts.exists(_.command.commandId == secondReceipt.commandId))
                      _ = assertEquals(twoOffers.lab.map(_.turn.offers.size), Some(2))
                      _ = assertEquals(selected.lab.map(_.turn.selectedPairIndex), Some(Some(chosenIndex)))
                      _ = assert(selectedMoves.dicePending)
                      _ = assert(selectedMoves.legalMoves.children.nonEmpty)
                      _ = assertEquals(selectedMoves.version, selected.version)
                      _ = assertEquals(durableSelected.version, selected.version)
                      _ = assert(
                        durableSelected.lab.get.receipts.exists(r =>
                          r.command.commandId == choiceReceipt.commandId && r.appliedVersion == choiceReceipt.appliedVersion
                        )
                      )
                    yield RestartProof(
                      id,
                      Command("http-choice", afterSecond.version, Action.ChoosePair(chosenIndex)),
                      choiceReceipt,
                      selectedMoves
                    )
                yield proof

        firstRun.flatMap: proof =>
          // Releasing the first registry after the connection scope stops its writer before the durable restart.
          registry(db).use: resumed =>
            for
              expectedCount <- db.loadActive.map(_.size)
              restoredCount <- resumed.resume
              room          <- resumed.get(proof.id).map(_.getOrElse(fail("resumed room missing")))
              _ = assertEquals(restoredCount, expectedCount)
              _ <- server(resumed).use: port =>
                JdkHttpClient
                  .simple[IO]
                  .use: http =>
                    (room.connection(Seat.White), room.connection(Seat.Black)).tupled.use: _ =>
                      for
                        retry <- room.submitLaboratory(Seat.White, proof.command)
                        _ = assertEquals(retry, Right(proof.receipt))
                        afterRetry <- db.load(proof.id).map(_.get)
                        state      <- room.snapshot
                        apiState   <- http.expect[PublicGameState](base(port) / "games" / proof.id.value)
                        apiMoves   <- http.expect[GameMoves](base(port) / "games" / proof.id.value / "moves")
                        _ = assertEquals(afterRetry.version, proof.moves.version)
                        _ = assertEquals(state.lab.map(_.turn.phase), Some(Phase.PlayingSelectedPair))
                        _ = assertEquals(apiState.version, proof.moves.version)
                        _ = assertEquals(apiState.lab.map(_.turn.phase), Some(Phase.PlayingSelectedPair))
                        _ = assertEquals(apiMoves.version, proof.moves.version)
                        _ = assertEquals(apiMoves.dfen, proof.moves.dfen)
                        _ = assertEquals(apiMoves.legalMoves, proof.moves.legalMoves)
                        selected <- EngineOps
                          .parse(state.dfen)
                          .leftMap(message => new RuntimeException(message))
                          .liftTo[IO]
                        path = EngineOps.legalMovePaths(selected).head.map(EngineOps.toUci)
                        completed <- room.submitLaboratory(
                          Seat.White,
                          Command("http-play", state.version, Action.SubmitTurn(path))
                        )
                        savedTurn <- db.load(proof.id).map(_.get)
                        _ = assert(completed.isRight)
                        _ = assertEquals(savedTurn.ply, 1L)
                        _ = assertEquals(savedTurn.turns.head.moves, path)
                        _ = assert(savedTurn.turns.head.labChoice.exists(_.offers.size == 2))
                        _          <- room.submit(Seat.Black, GameCommand.Resign)
                        _          <- awaitPhase(room, Phase.Ended)
                        transcript <- http.expect[Json](base(port) / "games" / proof.id.value / "transcript")
                        finalState <- http.expect[PublicGameState](base(port) / "games" / proof.id.value)
                        finalMoves <- http.expect[GameMoves](base(port) / "games" / proof.id.value / "moves")
                        _ = assert(transcript.hcursor.downField("turns").focus.exists(_.asArray.exists(_.size == 1)))
                        _ = assert(transcript.hcursor.downField("fairness").get[String]("serverSeed").isRight)
                        _ = assertEquals(finalState.lab.map(_.turn.phase), Some(Phase.Ended))
                        _ = assertEquals(finalMoves.dicePending, false)
                      yield ()
            yield ()
    }

  test("terminal transcript is sanitized and HTTP rematch requires both seat capabilities"):
    withContainers { pg =>
      store(pg).use: db =>
        registry(db).use: reg =>
          server(reg).use: port =>
            JdkHttpClient
              .simple[IO]
              .use: http =>
                val uri = base(port)
                for
                  created <- create(http, uri)
                  id = GameId(created.gameId)
                  room <- reg.get(id).map(_.getOrElse(fail("created room missing")))
                  _    <- withReadySeats(room): _ =>
                    room.submit(Seat.White, GameCommand.Resign) *> awaitPhase(room, Phase.Ended).void
                  transcript   <- http.expect[Json](uri / "games" / created.gameId / "transcript")
                  missingToken <- http.status(
                    POST(Json.obj("consent" -> Json.True), uri / "games" / created.gameId / "rematch")
                  )
                  waitWhite  <- http.expect[Json](rematchRequest(uri, created.gameId, tokenOf(created, Seat.White)))
                  madeBlack  <- http.expect[Json](rematchRequest(uri, created.gameId, tokenOf(created, Seat.Black)))
                  againWhite <- http.expect[Json](rematchRequest(uri, created.gameId, tokenOf(created, Seat.White)))
                  successor  <- db.successorOf(id)
                  savedNext  <- successor.traverse(db.load).map(_.flatten)
                yield
                  assertEquals(missingToken, Status.Forbidden)
                  assertEquals(transcript.hcursor.get[Int]("schemaVersion"), Right(1))
                  assert(transcript.hcursor.downField("fairness").get[String]("serverSeed").isRight)
                  assert(transcript.hcursor.downField("fairness").get[Json]("clientSeeds").isRight)
                  assert(transcript.hcursor.downField("seatTokens").succeeded == false)
                  assert(transcript.hcursor.downField("receipts").succeeded == false)
                  assertEquals(waitWhite.hcursor.get[String]("phase"), Right("awaiting_consent"))
                  val nextId = madeBlack.hcursor.get[String]("gameId").toOption.get
                  assertEquals(againWhite.hcursor.get[String]("gameId"), Right(nextId))
                  assertEquals(madeBlack.hcursor.get[String]("seat"), Right("White"))
                  assertEquals(againWhite.hcursor.get[String]("seat"), Right("Black"))
                  assert(madeBlack.hcursor.get[String]("token").isRight)
                  assert(againWhite.hcursor.get[String]("token").isRight)
                  assertEquals(successor.map(_.value), Some(nextId))
                  assertEquals(savedNext.flatMap(_.lab.map(_.turn.phase)), Some(Phase.AwaitingPlayers))
                  assertEquals(savedNext.map(_.effectiveVariant), Some(TwoPairs))
                  assertEquals(savedNext.map(_.players(Seat.White)), Some(Principal.Guest(blackId)))
                  assertEquals(savedNext.map(_.players(Seat.Black)), Some(Principal.Guest(whiteId)))
    }

  private def rematchRequest(uri: Uri, id: String, token: String): org.http4s.Request[IO] =
    POST(Json.obj("consent" -> Json.True), uri / "games" / id / "rematch")
      .putHeaders(Header.Raw(ci"X-Laboratory-Seat-Token", token))
