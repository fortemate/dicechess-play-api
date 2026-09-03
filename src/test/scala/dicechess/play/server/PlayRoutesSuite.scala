package dicechess.play.server

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import dicechess.engine.search.{BotRegistry, SearchAlgorithm}
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.{EngineOps, GameRoom}
import dicechess.play.wire.Codecs.given
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.Method.*
import org.http4s.{Status, Uri}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.dsl.io.*
import org.http4s.client.websocket.{WSConnectionHighLevel, WSFrame, WSRequest}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.{JdkHttpClient, JdkWSClient}
import org.http4s.websocket.WebSocketFrame
import scodec.bits.ByteVector

import scala.concurrent.duration.*

class PlayRoutesSuite extends munit.CatsEffectSuite:

  // The dice source is a fresh CSPRNG-seeded commit-reveal, so greedy-vs-greedy game length is
  // not deterministic — a passive line can drag to the draw cap. Bound the wire test by having
  // White resign once it has played a few real turns, so it always reaches a terminal quickly.
  private val WhiteTurnCap = 8

  // Principal.guest requires a UUID (dicechess-play-api#14) — these stand in for two distinct guests.
  private val WhiteId = "11111111-1111-1111-1111-111111111111"
  private val BlackId = "22222222-2222-2222-2222-222222222222"

  private def greedy: SearchAlgorithm =
    BotRegistry.getAlgorithm("greedy").getOrElse(sys.error("greedy bot not registered"))

  /** An Ember server on an ephemeral port; yields the bound port. A short reconnect grace keeps the disconnect test
    * fast — a dropped player forfeits shortly after the socket closes rather than after the production grace.
    */
  private def server: Resource[IO, Int] =
    for
      registry <- Resource.eval(GameRegistry.create(disconnectGrace = 500.millis))
      srv      <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        // Ember's default graceful-shutdown drain is 30s; bound it so the resource releases promptly
        // even if a WebSocket close handshake races with shutdown at the end of the test.
        .withShutdownTimeout(1.second)
        .withHttpWebSocketApp(wsb => PlayRoutes(registry, wsb).orNotFound)
        .build
    yield srv.address.getPort

  test("create over HTTP, then two WebSocket clients play to a terminal"):
    val resources =
      for
        port <- server
        http <- JdkHttpClient.simple[IO]
        ws   <- JdkWSClient.simple[IO]
      yield (port, http, ws)

    resources.use: bundle =>
      val (port, http, ws) = bundle
      val httpBase         = Uri.unsafeFromString(s"http://127.0.0.1:$port")
      val wsBase           = Uri.unsafeFromString(s"ws://127.0.0.1:$port")
      for
        created  <- http.expect[CreatedGame](POST(CreateGame(Some(WhiteId), Some(BlackId)), httpBase / "games"))
        snapshot <- http.expect[PublicGameState](httpBase / "games" / created.gameId)
        // Don't assert the active seat: the opening roll may have no legal move and auto-pass to Black.
        _        = assertEquals(snapshot.status, GameStatus.Active)
        _        = assert(created.commit.nonEmpty, "the dice commitment must be published at creation")
        whiteUri = wsBase / "games" / created.gameId / "ws" +? ("token" -> tokenOf(created, Seat.White))
        blackUri = wsBase / "games" / created.gameId / "ws" +? ("token" -> tokenOf(created, Seat.Black))
        ended <- (
          ws.connectHighLevel(WSRequest(whiteUri)).use(playSeat(_, Seat.White)),
          ws.connectHighLevel(WSRequest(blackUri)).use(playSeat(_, Seat.Black))
        ).parTupled
          .timeoutTo(20.seconds, IO.raiseError(RuntimeException("no terminal over the wire")))
      yield assert(ended._1 && ended._2, "both clients should observe GameEnded")

  test("POST /games records the optional time control (default 10+10), echoed in the snapshot"):
    val resources =
      for
        port <- server
        http <- JdkHttpClient.simple[IO]
      yield (port, http)

    resources.use: (port, http) =>
      val base = Uri.unsafeFromString(s"http://127.0.0.1:$port")
      for
        dflt   <- http.expect[CreatedGame](POST(CreateGame(Some(WhiteId), Some(BlackId)), base / "games"))
        dState <- http.expect[PublicGameState](base / "games" / dflt.gameId)
        timed  <- http.expect[CreatedGame](
          POST(CreateGame(Some(WhiteId), Some(BlackId), Some(TimeControl.Fischer(300, 3))), base / "games")
        )
        tState <- http.expect[PublicGameState](base / "games" / timed.gameId)
        // Unlimited is still reachable — it just has to be asked for now (rabestro/dicechess-play#99).
        endless <- http.expect[CreatedGame](
          POST(CreateGame(Some(WhiteId), Some(BlackId), Some(TimeControl.Unlimited)), base / "games")
        )
        eState <- http.expect[PublicGameState](base / "games" / endless.gameId)
      yield
        assertEquals(dState.timeControl, TimeControl.Default) // absent field -> a clock, never Unlimited
        assertEquals(tState.timeControl, TimeControl.Fischer(300, 3))
        assertEquals(eState.timeControl, TimeControl.Unlimited)

  test("GET /games lists live games with players, most action first; finished games leave it"):
    val resources =
      for
        port <- server
        http <- JdkHttpClient.simple[IO]
        ws   <- JdkWSClient.simple[IO]
      yield (port, http, ws)

    resources.use: (port, http, ws) =>
      val httpBase = Uri.unsafeFromString(s"http://127.0.0.1:$port")
      val wsBase   = Uri.unsafeFromString(s"ws://127.0.0.1:$port")

      def listing: IO[LiveGames] = http.expect[LiveGames](httpBase / "games")

      for
        created <- http.expect[CreatedGame](POST(CreateGame(Some(WhiteId), Some(BlackId)), httpBase / "games"))
        listed  <- listing
        entry = listed.games.find(_.gameId == created.gameId).getOrElse(fail("created game not listed"))
        // Who plays is public: two anonymous humans here (bots would carry their names).
        _ = assertEquals(
          entry.players,
          Some(Players(PublicPlayer(PlayerKind.Human, None), PublicPlayer(PlayerKind.Human, None)))
        )
        _ = assertEquals(listed.total, listed.games.size) // under the cap the total IS the page
        _ = assert(entry.dfen.startsWith("rnbqkbnr/"), "the listing carries the position for mini-boards")
        // End the game over the wire; the room is evicted and the listing must drop it.
        whiteUri = wsBase / "games" / created.gameId / "ws" +? ("token" -> tokenOf(created, Seat.White))
        _ <- ws.connectHighLevel(WSRequest(whiteUri)).use(conn => resign(conn) *> terminalOver(conn).void)
        _ <- listing
          .iterateUntil(_.games.forall(_.gameId != created.gameId))
          .timeoutTo(10.seconds, IO.raiseError(RuntimeException("the finished game never left the listing")))
      yield ()

  test("GET /games/{id}/moves serves the full legal-move tree for the pending roll"):
    val resources =
      for
        port <- server
        http <- JdkHttpClient.simple[IO]
      yield (port, http)

    resources.use: (port, http) =>
      val base = Uri.unsafeFromString(s"http://127.0.0.1:$port")
      for
        created <- http.expect[CreatedGame](POST(CreateGame(Some(WhiteId), Some(BlackId)), base / "games"))
        // Creation alone never rolls (the room waits for Begin via a WS attach or the seed grace), so the tree is
        // empty while no roll is pending — and the endpoint says so instead of 404ing.
        idle    <- http.expect[GameMoves](base / "games" / created.gameId / "moves")
        missing <- http.status(GET(base / "games" / "nope" / "moves"))
      yield
        assertEquals(idle.dicePending, false)
        assertEquals(idle.legalMoves, MoveTree.empty)
        assertEquals(missing, Status.NotFound)

  test("GET /games/{id}/moves carries the pending roll's tree once the game rolls"):
    val resources =
      for
        port <- server
        http <- JdkHttpClient.simple[IO]
        ws   <- JdkWSClient.simple[IO]
      yield (port, http, ws)

    resources.use: (port, http, ws) =>
      val httpBase = Uri.unsafeFromString(s"http://127.0.0.1:$port")
      val wsBase   = Uri.unsafeFromString(s"ws://127.0.0.1:$port")

      // Poll until a roll with a real decision is pending (auto-passes flip `dicePending` only transiently).
      def pollMovable(uri: Uri): IO[GameMoves] =
        http
          .expect[GameMoves](uri)
          .flatMap: moves =>
            if moves.dicePending && moves.legalMoves.children.nonEmpty then IO.pure(moves)
            else IO.sleep(100.millis) *> pollMovable(uri)

      for
        created <- http.expect[CreatedGame](POST(CreateGame(Some(WhiteId), Some(BlackId)), httpBase / "games"))
        whiteUri = wsBase / "games" / created.gameId / "ws" +? ("token" -> tokenOf(created, Seat.White))
        blackUri = wsBase / "games" / created.gameId / "ws" +? ("token" -> tokenOf(created, Seat.Black))
        moves <- (
          ws.connectHighLevel(WSRequest(whiteUri)),
          ws.connectHighLevel(WSRequest(blackUri))
        ).tupled.use: (white, black) =>
          // Seed both seats (opens the opening-roll gate), then wait for a movable roll while both sockets stay
          // attached (a dropped seat would forfeit after the test server's short reconnect grace).
          send(white, GameCommand.SubmitSeed("client-seed-White-0123456789")) *>
            send(black, GameCommand.SubmitSeed("client-seed-Black-0123456789")) *>
            pollMovable(httpBase / "games" / created.gameId / "moves")
              .timeoutTo(10.seconds, IO.raiseError(RuntimeException("no movable roll became pending")))
      yield
        // The served tree is exactly the engine's enumeration for the DFEN the endpoint itself reported.
        val expected = EngineOps.parse(moves.dfen) match
          case Left(error)  => fail(s"endpoint dfen must parse: $error")
          case Right(state) =>
            MoveTree.fromPaths(EngineOps.legalMovePaths(state).map(_.map(EngineOps.toUci)))
        assertEquals(moves.legalMoves, expected)

  test("POST /games rejects a malformed body with 400, not 500"):
    val resources =
      for
        port <- server
        http <- JdkHttpClient.simple[IO]
      yield (port, http)

    resources.use: (port, http) =>
      val games = Uri.unsafeFromString(s"http://127.0.0.1:$port") / "games"
      for
        missingFields <- http.status(POST(io.circe.Json.obj(), games))
        notJson       <- http.status(POST("not json", games))
      yield
        assertEquals(missingFields, Status.BadRequest)
        assertEquals(notJson, Status.BadRequest)

  test("POST /games rejects a non-UUID white/black id with 400 (dicechess-play-api#14)"):
    val resources =
      for
        port <- server
        http <- JdkHttpClient.simple[IO]
      yield (port, http)

    resources.use: (port, http) =>
      val games = Uri.unsafeFromString(s"http://127.0.0.1:$port") / "games"
      for
        empty <- http.status(POST(CreateGame(Some(""), Some(BlackId)), games))
        // A value containing `:` would otherwise produce an ambiguous, colon-joined external_id.
        colonJoined <- http.status(POST(CreateGame(Some("guest:not-a-uuid"), Some(BlackId)), games))
        garbage     <- http.status(POST(CreateGame(Some(WhiteId), Some("not-a-uuid")), games))
        valid       <- http.status(POST(CreateGame(Some(WhiteId), Some(BlackId)), games))
      yield
        assertEquals(empty, Status.BadRequest)
        assertEquals(colonJoined, Status.BadRequest)
        assertEquals(garbage, Status.BadRequest)
        assertEquals(valid, Status.Created)

  test("a WebSocket upgrade with an invalid join token is forbidden"):
    val resources =
      for
        port <- server
        http <- JdkHttpClient.simple[IO]
      yield (port, http)

    resources.use: (port, http) =>
      val httpBase = Uri.unsafeFromString(s"http://127.0.0.1:$port")
      for
        created <- http.expect[CreatedGame](POST(CreateGame(Some(WhiteId), Some(BlackId)), httpBase / "games"))
        // A plain GET (no upgrade) with a bogus token still exercises the auth gate before the upgrade.
        wsPath = httpBase / "games" / created.gameId / "ws"
        status <- http.status(GET(wsPath +? ("token" -> "not-a-real-token")))
      yield assertEquals(status, Status.Forbidden)

  test("a player who disconnects and does not reconnect forfeits after the grace"):
    val resources =
      for
        port <- server
        http <- JdkHttpClient.simple[IO]
        ws   <- JdkWSClient.simple[IO]
      yield (port, http, ws)

    resources.use: (port, http, ws) =>
      val httpBase = Uri.unsafeFromString(s"http://127.0.0.1:$port")
      val wsBase   = Uri.unsafeFromString(s"ws://127.0.0.1:$port")
      for
        created <- http.expect[CreatedGame](POST(CreateGame(Some(WhiteId), Some(BlackId)), httpBase / "games"))
        whiteUri = wsBase / "games" / created.gameId / "ws" +? ("token" -> tokenOf(created, Seat.White))
        specUri  = wsBase / "games" / created.gameId / "ws" // tokenless spectator watches
        over <- ws
          .connectHighLevel(WSRequest(specUri))
          .use { spectator =>
            // The spectator is attached before White joins, so it observes the terminal either way.
            val whiteDisconnects = ws.connectHighLevel(WSRequest(whiteUri)).use(_ => IO.unit)
            (terminalOver(spectator), whiteDisconnects).parTupled.map(_._1)
          }
          .timeoutTo(20.seconds, IO.raiseError(RuntimeException("a disconnect did not end the game")))
      yield assertEquals(over.termination, Termination.Resign)

  test("the GameEnded frame carries the revealed seed that opens the commitment"):
    val resources =
      for
        port <- server
        http <- JdkHttpClient.simple[IO]
        ws   <- JdkWSClient.simple[IO]
      yield (port, http, ws)

    resources.use: (port, http, ws) =>
      val httpBase = Uri.unsafeFromString(s"http://127.0.0.1:$port")
      val wsBase   = Uri.unsafeFromString(s"ws://127.0.0.1:$port")
      for
        created <- http.expect[CreatedGame](POST(CreateGame(Some(WhiteId), Some(BlackId)), httpBase / "games"))
        whiteUri = wsBase / "games" / created.gameId / "ws" +? ("token" -> tokenOf(created, Seat.White))
        specUri  = wsBase / "games" / created.gameId / "ws" // tokenless spectator watches for the terminal
        ended <- ws
          .connectHighLevel(WSRequest(specUri))
          .use { spectator =>
            // White joins then drops; the forfeit ends the game, so the spectator sees a live GameEnded.
            val whiteDisconnects = ws.connectHighLevel(WSRequest(whiteUri)).use(_ => IO.unit)
            (terminalGameEnded(spectator), whiteDisconnects).parTupled.map(_._1)
          }
          .timeoutTo(20.seconds, IO.raiseError(RuntimeException("no GameEnded over the wire")))
      yield
        assert(ended.seed.nonEmpty, "the GameEnded frame must carry the revealed seed")
        assertEquals(sha256Hex(ended.seed.getOrElse(fail("expected a revealed seed"))), created.commit)
        // Neither seat seeded (white only connected then dropped), so each client seed is the seat's external-id
        // fallback — assert the exact pair, which also catches a white/black swap.
        assertEquals(ended.clientSeeds, Some(ClientSeeds(s"guest:$WhiteId", s"guest:$BlackId")))

  test("clientFrames interleaves keep-alive pings into a quiet game"):
    // A room that is created but never started stays quiet after its initial Snapshot, so the only
    // further frames are heartbeats — proving the ping stream keeps an idle-but-live socket flowing.
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      .create(Map(Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black")), dice)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          PlayRoutes
            .clientFrames(room, keepAlive = 40.millis)
            .take(4)
            .compile
            .toList
            .timeoutTo(5.seconds, IO.raiseError(RuntimeException("no frames within the deadline")))
      }
      .map: frames =>
        assert(frames.exists(_.isInstanceOf[WebSocketFrame.Ping]), s"expected a ping among $frames")

  test("clientFrames terminates with a Close frame when the game ends"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      .create(Map(Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black")), dice)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          val streamFrames = PlayRoutes
            .clientFrames(room, keepAlive = 1.second)
            .compile
            .toList

          val playAndFinish = room.submit(Seat.White, GameCommand.Resign)

          (streamFrames, playAndFinish)
            .parMapN((frames, _) => frames)
            .timeoutTo(5.seconds, IO.raiseError(RuntimeException("clientFrames did not complete")))
      }
      .map: frames =>
        assert(frames.nonEmpty, "frames must not be empty")
        assertEquals(
          frames.last,
          WebSocketFrame.Close(ByteVector.fromShort(1000.toShort)),
          "the final frame in clientFrames must be WebSocketFrame.Close(1000)"
        )

  private def tokenOf(created: CreatedGame, seat: Seat): String =
    created.tokens.find(_.seat == seat).map(_.token).getOrElse(sys.error(s"no join token for $seat"))

  /** Read a connection until the game reaches a terminal state, from either the live event or a late snapshot. */
  private def terminalOver(conn: WSConnectionHighLevel[IO]): IO[GameOver] =
    conn.receiveStream
      .collect { case WSFrame.Text(txt, _) => txt }
      .map(txt => decode[GameEvent](txt).toOption.flatMap(terminalOf))
      .unNone
      .compile
      .lastOrError

  private def terminalOf(event: GameEvent): Option[GameOver] = event match
    case GameEvent.GameEnded(_, over, _, _) => Some(over)
    case GameEvent.Snapshot(_, ps, _)       =>
      ps.status match
        case GameStatus.Ended(over) => Some(over)
        case GameStatus.Active      => None
    case _ => None

  /** Read a connection until the live `GameEnded` event arrives, returning it (with its revealed seed). */
  private def terminalGameEnded(conn: WSConnectionHighLevel[IO]): IO[GameEvent.GameEnded] =
    conn.receiveStream
      .collect { case WSFrame.Text(txt, _) => txt }
      .map(txt => decode[GameEvent](txt).toOption)
      .unNone
      .collect { case e: GameEvent.GameEnded => e }
      .compile
      .lastOrError

  /** SHA-256 of a hex-encoded seed, hex-encoded — to check a reveal against its commitment. */
  private def sha256Hex(hexSeed: String): String =
    val bytes = hexSeed.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toArray
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /** Drive one seat over the wire with the greedy bot; complete when GameEnded arrives. Submits a client dice seed
    * first (post-commit entropy), which is also what opens the room's opening-roll gate.
    */
  private def playSeat(conn: WSConnectionHighLevel[IO], seat: Seat): IO[Boolean] =
    (Ref.of[IO, Long](-1L), Ref.of[IO, Int](0)).flatMapN: (handled, turns) =>
      send(conn, GameCommand.SubmitSeed(s"client-seed-$seat-0123456789")) *>
        conn.receiveStream
          .evalMap(frame => handle(conn, seat, handled, turns, frame))
          .takeThrough(ended => !ended)
          .compile
          .lastOrError

  private def handle(
      conn: WSConnectionHighLevel[IO],
      seat: Seat,
      handled: Ref[IO, Long],
      turns: Ref[IO, Int],
      frame: WSFrame
  ): IO[Boolean] =
    frame match
      case WSFrame.Text(txt, _) =>
        decode[GameEvent](txt) match
          case Right(GameEvent.GameEnded(_, _, _, _)) => IO.pure(true)
          case Right(event)                           => maybeAct(conn, seat, handled, turns, event).as(false)
          case Left(_)                                => IO.pure(false)
      case _ => IO.pure(false)

  private def maybeAct(
      conn: WSConnectionHighLevel[IO],
      seat: Seat,
      handled: Ref[IO, Long],
      turns: Ref[IO, Int],
      event: GameEvent
  ): IO[Unit] =
    turnFor(seat, event) match
      case None                  => IO.unit
      case Some((version, dfen)) =>
        handled
          .modify(last => if version > last then (version, true) else (last, false))
          .flatMap: fresh =>
            if !fresh then IO.unit
            else
              turns
                .updateAndGet(_ + 1)
                .flatMap: n =>
                  if seat == Seat.White && n > WhiteTurnCap then resign(conn)
                  else move(conn, dfen)

  private def move(conn: WSConnectionHighLevel[IO], dfen: String): IO[Unit] =
    EngineOps.parse(dfen) match
      case Left(_)      => resign(conn)
      case Right(state) =>
        greedy.findBestMove(state) match
          // No legal move: the server auto-passes the turn, so just wait — don't resign.
          case None      => IO.unit
          case Some(seq) => send(conn, GameCommand.SubmitTurn(seq.moves.map(EngineOps.toUci)))

  private def resign(conn: WSConnectionHighLevel[IO]): IO[Unit] = send(conn, GameCommand.Resign)

  private def send(conn: WSConnectionHighLevel[IO], command: GameCommand): IO[Unit] =
    conn.send(WSFrame.Text((command: GameCommand).asJson.noSpaces))

  private def turnFor(seat: Seat, event: GameEvent): Option[(Long, String)] = event match
    case GameEvent.DiceRolled(v, s, _, dfen, _, _) if s == seat                  => Some((v, dfen))
    case GameEvent.Snapshot(v, ps, _) if ps.dicePending && ps.activeSeat == seat => Some((v, ps.dfen))
    case _                                                                       => None
