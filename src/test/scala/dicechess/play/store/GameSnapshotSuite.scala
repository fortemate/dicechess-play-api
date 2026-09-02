package dicechess.play.store

import dicechess.play.core.GameOrigin
import io.circe.parser.decode

/** Backward-compatible decoding of `GameSnapshot`: adding a field must never break decoding of a row persisted before
  * that field existed. `rated` is the concrete case that motivated this suite (caught in review on #97) — a defaulted
  * NON-Option field does not fall back to its default on a missing key; only `Option` fields do, via circe's own
  * `decodeOption`. Every future optional addition to this snapshot must follow the `Option` pattern, and this suite is
  * the regression guard for it.
  */
class GameSnapshotSuite extends munit.FunSuite:

  private val baseFields =
    """"version": 0,
      |"dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      |"players": {"White": {"Guest": {"id": "white"}}, "Black": {"Guest": {"id": "black"}}},
      |"seatTokens": {"White": "tok-w", "Black": "tok-b"},
      |"serverSeed": "aa",
      |"clientSeeds": {},
      |"started": false,
      |"ply": 0,
      |"pending": false,
      |"status": {"Active": {}},
      |"timeControl": {"Unlimited": {}},
      |"remainingMs": {},
      |"lastRoll": [],
      |"turns": []""".stripMargin

  test("a snapshot persisted before `rated`/`createdAtEpochMs` existed still decodes, as unrated"):
    decode[GameSnapshot](s"{$baseFields}") match
      case Left(error) => fail(s"a pre-existing snapshot must still decode, got: $error")
      case Right(snap) =>
        assertEquals(snap.rated, None, "a missing `rated` key must decode to None, never fail or silently default")
        assertEquals(snap.createdAtEpochMs, None)

  test("a snapshot with an explicit rated value decodes it"):
    decode[GameSnapshot](s"""{$baseFields, "rated": true}""") match
      case Left(error) => fail(s"decode failed: $error")
      case Right(snap) => assertEquals(snap.rated, Some(true))

  test("effectiveOrigin resolves a legacy row to an explicit default without reinterpreting the ladder flag (#47)"):
    def originOf(extra: String): GameOrigin =
      decode[GameSnapshot](if extra.isEmpty then s"{$baseFields}" else s"{$baseFields, $extra}") match
        case Left(error) => fail(s"decode failed: $error")
        case Right(snap) => snap.effectiveOrigin
    assertEquals(originOf(""), GameOrigin.Legacy, "no origin, no ladder flag: the game predates both concepts")
    assertEquals(originOf(""""ladder": true"""), GameOrigin.Ladder, "a pre-origin ladder game is still a ladder game")
    assertEquals(originOf(""""ladder": false"""), GameOrigin.Legacy)
    assertEquals(originOf(""""origin": null, "ladder": true"""), GameOrigin.Ladder, "an explicit null is 'absent'")
    assertEquals(originOf(""""origin": "showcase""""), GameOrigin.Showcase)
    assertEquals(
      originOf(""""origin": "catalog", "ladder": true"""),
      GameOrigin.Catalog,
      "a recorded origin wins over the flag — the flag keeps its own meaning, it does not define origin"
    )
