package dicechess.play.ingest

import dicechess.play.core.*
import dicechess.play.store.{GameSnapshot, TurnRecord}

/** The analytics handoff mapper: identity, wire shape (snake_case `GameIngest`), and the do-not-ingest rules. */
class PlaysiteIngestSuite extends munit.FunSuite:

  private val gameId = GameId("0da0139f-b94f-44ae-8a03-ace33348ece5")

  private def snapshot(status: GameStatus, timeControl: TimeControl = TimeControl.Fischer(300, 3)): GameSnapshot =
    GameSnapshot(
      version = 9L,
      dfen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      players = Map(Seat.White -> Principal.Guest("w-uuid"), Seat.Black -> Principal.Bot("house", "greedy")),
      seatTokens = Map(Seat.White -> "tok-w", Seat.Black -> "tok-b"),
      serverSeed = "ab12cd34",
      clientSeeds = Map.empty,
      started = true,
      ply = 2L,
      pending = false,
      status = status,
      timeControl = timeControl,
      remainingMs = Map.empty,
      lastRoll = List(2, 3, 6),
      turns = Vector(
        TurnRecord(1L, "w", List(1, 1, 4), List("e2e4"), "fen-1", thinkingTimeMs = Some(1523L)),
        TurnRecord(
          2L,
          "b",
          List(2, 3, 6),
          Nil,
          "fen-2",
          thinkingTimeMs = Some(0L)
        ) // a forced pass: dice rolled, no legal move
      ),
      createdAtEpochMs = Some(1_782_000_000_000L)
    )

  private def ended(result: GameResult, termination: Termination) = GameStatus.Ended(GameOver(result, termination))

  test("the ingest id is the deterministic UUIDv5 the play SPA convention uses"):
    // Precomputed with Python's uuid.uuid5(URL_NAMESPACE, "playsite/game/<id>").
    assertEquals(PlaysiteIngest.ingestId(gameId).toString, "e9bd8292-185c-559c-81ec-8790d32797bc")

  test("a finished game maps to the GameIngest wire shape"):
    val json   = PlaysiteIngest.payload(gameId, snapshot(ended(GameResult.Win(Side.White), Termination.KingCaptured)))
    val fields = json.getOrElse(fail("a finished game must produce a payload"))
    val c      = fields.hcursor
    assertEquals(c.get[String]("id").toOption, Some("e9bd8292-185c-559c-81ec-8790d32797bc"))
    assertEquals(c.get[String]("source").toOption, Some("playsite"))
    assertEquals(c.get[String]("mode").toOption, Some("classic"))
    assertEquals(c.get[Int]("result").toOption, Some(1))
    assertEquals(c.get[String]("termination").toOption, Some("king_captured"))
    assertEquals(c.get[String]("started_at").toOption, Some("2026-06-21T00:00:00Z"))
    assertEquals(c.get[Int]("time_initial_sec").toOption, Some(300))
    assertEquals(c.get[Int]("time_increment_sec").toOption, Some(3))
    assertEquals(c.downField("white_player").get[String]("external_id").toOption, Some("guest:w-uuid"))
    assertEquals(c.downField("white_player").get[String]("player_type").toOption, Some("guest"))
    assertEquals(c.downField("black_player").get[String]("external_id").toOption, Some("bot:team:house:greedy"))
    assertEquals(c.downField("black_player").get[String]("player_type").toOption, Some("bot"))
    val turns = c.downField("turns")
    assertEquals(turns.downN(0).get[List[String]]("moves").toOption, Some(List("e2e4")))
    assertEquals(turns.downN(0).get[List[Int]]("dice").toOption, Some(List(1, 1, 4)))
    assertEquals(turns.downN(0).get[Long]("thinking_time_ms").toOption, Some(1523L))
    assertEquals(turns.downN(1).get[List[String]]("moves").toOption, Some(Nil)) // the pass
    assertEquals(turns.downN(1).get[String]("active_color").toOption, Some("b"))
    assertEquals(turns.downN(1).get[Long]("thinking_time_ms").toOption, Some(0L))

  test("results and terminations map to the analytics enums"):
    def terminationOf(result: GameResult, termination: Termination): (Option[Int], Option[String]) =
      val c = PlaysiteIngest.payload(gameId, snapshot(ended(result, termination))).get.hcursor
      (c.get[Int]("result").toOption, c.get[String]("termination").toOption)
    assertEquals(terminationOf(GameResult.Win(Side.Black), Termination.Resign), (Some(-1), Some("resign")))
    assertEquals(terminationOf(GameResult.Win(Side.White), Termination.Timeout), (Some(1), Some("timeout")))
    assertEquals(terminationOf(GameResult.Draw, Termination.Draw), (Some(0), Some("draw_agreement")))

  test("an active game is never ingested"):
    assertEquals(PlaysiteIngest.payload(gameId, snapshot(GameStatus.Active)), None)

  test("an aborted game is never ingested (no sporting result)"):
    assertEquals(PlaysiteIngest.payload(gameId, snapshot(ended(GameResult.Draw, Termination.Aborted))), None)

  test("unlimited and per-move controls carry no time fields"):
    val c = PlaysiteIngest
      .payload(gameId, snapshot(ended(GameResult.Draw, Termination.Draw), timeControl = TimeControl.Unlimited))
      .get
      .hcursor
    assertEquals(c.downField("time_initial_sec").focus.flatMap(_.asNull.map(_ => true)), Some(true))
