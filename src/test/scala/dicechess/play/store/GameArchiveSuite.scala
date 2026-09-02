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
    assertEquals(record.result, Some(1))
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

  private def showcase(snapshot: GameSnapshot): GameSnapshot = snapshot.copy(origin = Some(GameOrigin.Showcase))

  test("a technically aborted SHOWCASE game is archived with its full history but no sporting result (#47)"):
    val fixture = showcase(snapshot(ended(GameResult.Draw, Termination.Aborted)))
    val entry   = GameArchive.entry(fixture).getOrElse(fail("an aborted showcase game must produce an archive entry"))
    val c       = entry.payload.hcursor
    assertEquals(entry.origin, GameOrigin.Showcase)
    assert(!entry.sportingEligible, "a technical abort is never a sporting result")
    assert(c.downField("result").focus.exists(_.isNull), "no fabricated draw: the abort's placeholder result is null")
    assertEquals(c.get[String]("termination").toOption, Some("aborted"))
    assertEquals(c.get[String]("origin").toOption, Some("showcase"))
    assertEquals(c.get[Boolean]("sporting_eligible").toOption, Some(false))
    assertEquals(c.downField("turns").downN(1).get[List[Int]]("dice").toOption, Some(List(2, 3, 6)), "moves and dice")
    assertEquals(c.downField("players").get[String]("black").toOption, Some("bot:team:house:greedy"), "participants")
    assertEquals(c.downField("time_control").downField("Fischer").get[Int]("initialSeconds").toOption, Some(300))
    assertEquals(c.downField("fairness").get[String]("server_seed").toOption, Some("ab12cd34"), "fairness material")

  test("a finished showcase game is a sporting result that carries its origin (#47)"):
    val entry = GameArchive
      .entry(showcase(snapshot(ended(GameResult.Win(Side.Black), Termination.Timeout))))
      .getOrElse(fail("a finished game must produce an archive entry"))
    assertEquals(entry.origin, GameOrigin.Showcase)
    assert(entry.sportingEligible)
    assertEquals(entry.payload.hcursor.get[Int]("result").toOption, Some(-1))
    assertEquals(entry.payload.hcursor.get[Boolean]("sporting_eligible").toOption, Some(true))

  test("an aborted game OUTSIDE the showcase is still not archived — the pre-#47 rule is unchanged for it"):
    assertEquals(GameArchive.entry(snapshot(ended(GameResult.Draw, Termination.Aborted))), None)
    assertEquals(
      GameArchive.entry(snapshot(ended(GameResult.Draw, Termination.Aborted)).copy(origin = Some(GameOrigin.Lobby))),
      None
    )

  test("a snapshot without an origin archives as legacy, and a ladder-flagged one as ladder (#47)"):
    val legacy = GameArchive.entry(snapshot(ended(GameResult.Draw, Termination.Draw))).getOrElse(fail("no entry"))
    assertEquals(legacy.origin, GameOrigin.Legacy)
    assertEquals(legacy.payload.hcursor.get[String]("origin").toOption, Some("legacy"))
    val ladder = GameArchive
      .entry(snapshot(ended(GameResult.Draw, Termination.Draw)).copy(ladder = Some(true)))
      .getOrElse(fail("no entry"))
    assertEquals(ladder.origin, GameOrigin.Ladder)

  test("decode round-trips the aborted showcase shape: result None, origin and eligibility as written (#47)"):
    val json =
      GameArchive.payload(showcase(snapshot(ended(GameResult.Draw, Termination.Aborted)))).getOrElse(fail("no payload"))
    val record = GameArchive.decode(json).getOrElse(fail(s"decode must succeed for its own payload: $json"))
    assertEquals(record.result, None)
    assertEquals(record.termination, "aborted")
    assertEquals(record.origin, GameOrigin.Showcase)
    assertEquals(record.sportingEligible, false)
    assertEquals(record.turns.size, 2)

  test("pre-#47 archive JSON without origin or sporting_eligible decodes to legacy, eligible, with its integer result"):
    val json = io.circe.parser
      .parse("""{
        "started_at": 1782000000000,
        "rated": false,
        "time_control": {"Fischer": {"initialSeconds": 300, "incrementSeconds": 3}},
        "result": -1,
        "termination": "timeout",
        "players": {"white": "guest:w-uuid", "black": "bot:team:house:greedy"},
        "initial_dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "turns": [],
        "fairness": {"commit": null, "server_seed": "ab12cd34", "client_seeds": {"white": "w", "black": "b"}}
      }""")
      .getOrElse(fail("parse failed"))
    val record = GameArchive.decode(json).getOrElse(fail("decode must succeed for pre-#47 archive JSON"))
    assertEquals(record.result, Some(-1))
    assertEquals(record.origin, GameOrigin.Legacy)
    assertEquals(record.sportingEligible, true)
