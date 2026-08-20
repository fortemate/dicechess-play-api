package dicechess.play.store

import dicechess.play.core.*
import dicechess.play.game.EngineOps

/** The archive payload builder: field round-trip, the same do-not-archive rules as `PlaysiteIngest`, and the
  * fairness-block fallback for a seat that never submitted a client seed.
  */
class GameArchiveSuite extends munit.FunSuite:

  private def snapshot(
      status: GameStatus,
      clientSeeds: Map[Seat, String] = Map(Seat.White -> "white-seed", Seat.Black -> "black-seed"),
      rated: Option[Boolean] = Some(true)
  ): GameSnapshot =
    GameSnapshot(
      version = 9L,
      dfen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      players = Map(Seat.White -> Principal.Guest("w-uuid"), Seat.Black -> Principal.Bot("house", "greedy")),
      seatTokens = Map(Seat.White -> "tok-w", Seat.Black -> "tok-b"),
      serverSeed = "ab12cd34",
      clientSeeds = clientSeeds,
      started = true,
      ply = 2L,
      pending = false,
      status = status,
      timeControl = TimeControl.Fischer(300, 3),
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
      createdAtEpochMs = Some(1_782_000_000_000L),
      rated = rated
    )

  private def ended(result: GameResult, termination: Termination) = GameStatus.Ended(GameOver(result, termination))

  test("a finished game's payload round-trips every field"):
    val json   = GameArchive.payload(snapshot(ended(GameResult.Win(Side.White), Termination.KingCaptured)))
    val fields = json.getOrElse(fail("a finished game must produce a payload"))
    val c      = fields.hcursor
    assertEquals(c.get[Boolean]("rated").toOption, Some(true))
    assertEquals(c.get[Int]("result").toOption, Some(1))
    assertEquals(c.get[String]("termination").toOption, Some("king_captured"))
    assertEquals(c.downField("players").get[String]("white").toOption, Some("guest:w-uuid"))
    assertEquals(c.downField("players").get[String]("black").toOption, Some("bot:team:house:greedy"))
    assertEquals(c.get[String]("initial_dfen").toOption, Some(EngineOps.InitialDfen))
    assertEquals(c.downField("time_control").downField("Fischer").get[Int]("initialSeconds").toOption, Some(300))
    val turns = c.downField("turns")
    assertEquals(turns.downN(0).get[List[Int]]("dice").toOption, Some(List(1, 1, 4)))
    assertEquals(turns.downN(0).get[List[String]]("moves").toOption, Some(List("e2e4")))
    assertEquals(turns.downN(0).get[Long]("thinking_time_ms").toOption, Some(1523L))
    assertEquals(turns.downN(1).get[List[String]]("moves").toOption, Some(Nil)) // the pass
    assertEquals(turns.downN(1).get[String]("active_color").toOption, Some("b"))
    assertEquals(turns.downN(1).get[Long]("thinking_time_ms").toOption, Some(0L))
    val fairness = c.downField("fairness")
    assertEquals(fairness.get[String]("server_seed").toOption, Some("ab12cd34"))
    assert(fairness.get[String]("commit").toOption.exists(_.nonEmpty), "commit must be computed from the server seed")
    assertEquals(fairness.downField("client_seeds").get[String]("white").toOption, Some("white-seed"))
    assertEquals(fairness.downField("client_seeds").get[String]("black").toOption, Some("black-seed"))

  test("a seat that never submitted a client seed falls back to its own external id (matches actual dice usage)"):
    val json =
      GameArchive.payload(snapshot(ended(GameResult.Draw, Termination.Draw), clientSeeds = Map(Seat.White -> "w-only")))
    val c = json.getOrElse(fail("a finished game must produce a payload")).hcursor.downField("fairness")
    assertEquals(c.downField("client_seeds").get[String]("white").toOption, Some("w-only"))
    assertEquals(c.downField("client_seeds").get[String]("black").toOption, Some("bot:team:house:greedy"))

  test("an active game is never archived"):
    assertEquals(GameArchive.payload(snapshot(GameStatus.Active)), None)

  test("an aborted game is never archived (mirrors PlaysiteIngest — no sporting result)"):
    assertEquals(GameArchive.payload(snapshot(ended(GameResult.Draw, Termination.Aborted))), None)

  test("decode recovers exactly what payload wrote — the write/read pair round-trips (#178)"):
    val fixture = snapshot(ended(GameResult.Win(Side.White), Termination.KingCaptured))
    val json    = GameArchive.payload(fixture).getOrElse(fail("a finished game must produce a payload"))
    val record  = GameArchive.decode(json).getOrElse(fail(s"decode must succeed for its own payload: $json"))
    assertEquals(record.rated, true)
    assertEquals(record.timeControl, TimeControl.Fischer(300, 3))
    assertEquals(record.result, 1)
    assertEquals(record.termination, "king_captured")
    assertEquals(record.whiteExternalId, "guest:w-uuid")
    assertEquals(record.blackExternalId, "bot:team:house:greedy")
    assertEquals(record.initialDfen, EngineOps.InitialDfen)
    assertEquals(record.turns.map(t => (t.turnNumber, t.moves)), List((1L, List("e2e4")), (2L, Nil)))
    assertEquals(record.turns.map(_.thinkingTimeMs), List(Some(1523L), Some(0L)))
    assert(record.commit.exists(_.nonEmpty))
    assertEquals(record.serverSeed, "ab12cd34")
    assertEquals(record.clientSeedWhite, "white-seed")
    assertEquals(record.clientSeedBlack, "black-seed")

  test("a malformed snapshot missing a seat produces no archive row (mirrors PgGameStore.finishedGameOf)"):
    val malformed = snapshot(ended(GameResult.Win(Side.White), Termination.KingCaptured))
      .copy(players = Map(Seat.White -> Principal.Guest("w-uuid"))) // Black seat missing
    assertEquals(GameArchive.payload(malformed), None)

  test("a snapshot with no rated key archives as rated=false"):
    val json = GameArchive.payload(snapshot(ended(GameResult.Win(Side.Black), Termination.Resign), rated = None))
    val c    = json.getOrElse(fail("a finished game must produce a payload")).hcursor
    assertEquals(c.get[Boolean]("rated").toOption, Some(false)) // None resolves to false, same as game_results

  test("pre-existing archive JSON without thinking_time_ms decodes with thinkingTimeMs = None"):
    val json = io.circe.parser
      .parse("""{
        "started_at": 1782000000000,
        "rated": true,
        "time_control": {"Fischer": {"initialSeconds": 300, "incrementSeconds": 3}},
        "result": 1,
        "termination": "king_captured",
        "players": {"white": "guest:w-uuid", "black": "bot:team:house:greedy"},
        "initial_dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "turns": [
          {"turn_number": 1, "active_color": "w", "dice": [1, 1, 4], "moves": ["e2e4"], "fen_after": "fen-1"},
          {"turn_number": 2, "active_color": "b", "dice": [2, 3, 6], "moves": [], "fen_after": "fen-2"}
        ],
        "fairness": {
          "commit": "commit-hex",
          "server_seed": "ab12cd34",
          "client_seeds": {"white": "white-seed", "black": "black-seed"}
        }
      }""")
      .getOrElse(fail("parse failed"))
    val record = GameArchive.decode(json).getOrElse(fail("decode must succeed for legacy archive JSON"))
    assertEquals(record.turns.map(_.thinkingTimeMs), List(None, None))
