package dicechess.play.wire

import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.wire.Codecs.given
import io.circe.{Codec, Json}
import io.circe.parser.{decode, parse}
import io.circe.syntax.*

class LaboratoryRoomWireSuite extends munit.FunSuite:

  private val first      = DicePair(1, 3)
  private val second     = DicePair(1, 4)
  private val provenance = Provenance(RulesVersion.value, DiceAlgorithm)
  private val lab        = PublicState(
    TwoPairs,
    ProtocolVersion,
    provenance,
    PublicTurn(Phase.AwaitingPairChoice, List(first, second), None)
  )

  private def json(source: String)                = parse(source).fold(error => fail(error.toString), identity)
  private def roundtrip[A: Codec](value: A): Unit = assertEquals(decode[A](value.asJson.noSpaces), Right(value))

  private val classic = PublicGameState(
    3L,
    "fen",
    Seat.White,
    dicePending = false,
    GameStatus.Active,
    TimeControl.Unlimited,
    clocks = None,
    commit = "00ff",
    seed = None,
    clientSeeds = None
  )

  test("classic snapshots and history keep their original JSON shape and decode legacy omissions"):
    val stateJson = classic.asJson
    assert(!stateJson.hcursor.downField("lab").succeeded)
    assertEquals(decode[PublicGameState](stateJson.noSpaces), Right(classic))
    assertEquals(decode[PublicGameState](stateJson.noSpaces), Right(classic.copy(lab = None)))

    val turn     = SnapshotTurn(Seat.White, List(1, 2, 6), List("e2e4"), "after")
    val turnJson = turn.asJson
    assert(!turnJson.hcursor.downField("labChoice").succeeded)
    assertEquals(decode[SnapshotTurn](turnJson.noSpaces), Right(turn))

  test("lab state, history choices and LabTurnChanged round-trip with pinned public fields"):
    val selected = lab.copy(turn = PublicTurn(Phase.PlayingSelectedPair, List(first, second), Some(1)))
    val choice   = HistoryChoice(List(first, second), 1)
    roundtrip(selected)
    roundtrip(choice)

    val turn        = SnapshotTurn(Seat.White, second.values, List("e2e4", "g1f3"), "after", Some(choice))
    val historyJson = turn.asJson
    assertEquals(historyJson.hcursor.get[HistoryChoice]("labChoice").toOption, Some(choice))
    assertEquals(decode[SnapshotTurn](historyJson.noSpaces), Right(turn))

    val event = GameEvent.LabTurnChanged(2, Seat.White, lab, "dfen", Some(Clocks(600000, 600000)))
    assertEquals(
      event.asJson,
      json(
        s"""{"LabTurnChanged":{"v":2,"seat":"White","lab":{"variant":{"id":"choice-2x2d6","revision":1},"protocolVersion":1,"provenance":{"rulesVersion":"${RulesVersion.value}","diceAlgorithm":"choice-2x2d6-hmac-sha256-v1"},"turn":{"phase":"AwaitingPairChoice","offers":[[1,3],[1,4]],"selectedPairIndex":null}},"dfen":"dfen","clocks":{"white":600000,"black":600000}}}"""
      )
    )
    roundtrip[GameEvent](event)

  test("lab projections reject explicit null, unsupported context and malformed pairs"):
    val classicJson = classic.asJson.deepDropNullValues
    assert(decode[PublicGameState](classicJson.mapObject(_.add("lab", io.circe.Json.Null)).noSpaces).isLeft)
    assert(
      decode[SnapshotTurn](
        SnapshotTurn(Seat.White, Nil, Nil, "after").asJson.mapObject(_.add("labChoice", io.circe.Json.Null)).noSpaces
      ).isLeft
    )

    val unsupported = lab.copy(protocolVersion = 2)
    intercept[IllegalArgumentException](unsupported.asJson)
    assert(decode[PublicState](lab.asJson.mapObject(_.add("protocolVersion", Json.fromInt(2))).noSpaces).isLeft)
    val invalidStage = lab.copy(turn = PublicTurn(Phase.AwaitingSecondRoll, List(first, second), None))
    intercept[IllegalArgumentException](invalidStage.asJson)
    val malformed = lab.asJson.hcursor
      .downField("turn")
      .downField("phase")
      .withFocus(_ => Json.fromString("AwaitingSecondRoll"))
      .top
      .get
    assert(decode[PublicState](malformed.noSpaces).isLeft)
    assert(decode[HistoryChoice]("""{"offers":[[1,3]],"selectedPairIndex":0}""").isLeft)
    assert(decode[HistoryChoice]("""{"offers":[[1,3],[1,9]],"selectedPairIndex":0}""").isLeft)
