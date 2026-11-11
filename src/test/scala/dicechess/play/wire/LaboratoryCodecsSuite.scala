package dicechess.play.wire

import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.wire.LaboratoryCodecs.given
import io.circe.{Codec, Json}
import io.circe.parser.{decode, parse}
import io.circe.syntax.*

class LaboratoryCodecsSuite extends munit.FunSuite:

  private val first      = DicePair(1, 3)
  private val second     = DicePair(1, 4)
  private val provenance = Provenance(RulesVersion.value, DiceAlgorithm)

  private def json(source: String): Json          = parse(source).fold(error => fail(error.toString), identity)
  private def roundtrip[A: Codec](value: A): Unit = assertEquals(decode[A](value.asJson.noSpaces), Right(value))

  test("command examples pin the wrapper, zero-based choice and default draw flag"):
    val source =
      """{"LabCommand":{"commandId":"turn-1-choice","expectedVersion":3,"action":{"ChoosePair":{"pairIndex":1}}}}"""
    val value = CommandFrame(Command("turn-1-choice", 3, Action.ChoosePair(1)))
    assertEquals(decode[CommandFrame](source), Right(value))
    assertEquals(value.asJson, json(source))
    roundtrip(CommandFrame(Command("first", 1, Action.RollFirst)))
    roundtrip(CommandFrame(Command("second", 2, Action.RollSecond)))
    roundtrip(CommandFrame(Command("play", 4, Action.SubmitTurn(List("a2a4", "a1a3")))))
    assertEquals(
      decode[Action]("""{"SubmitTurn":{"moves":["e2e3","f1c4"]}}"""),
      Right(Action.SubmitTurn(List("e2e3", "f1c4")))
    )

  test("invalid indices, four-dice offers and non-dice values are refused"):
    List("[]", "[1]", "[1,2,3]", "[1,2,3,4]", "[0,2]", "[1,7]", "[1,2.5]").foreach: source =>
      assert(decode[DicePair](source).isLeft, source)
    assertEquals(first.asJson, json("[1,3]"))
    List(-1, 2).foreach: index =>
      assert(decode[Action](s"""{"ChoosePair":{"pairIndex":$index}}""").isLeft)

  test("the public phase cannot expose an unrevealed second offer or a premature choice"):
    val valid = List(
      PublicTurn(Phase.AwaitingPlayers, Nil, None),
      PublicTurn(Phase.AwaitingFirstRoll, Nil, None),
      PublicTurn(Phase.AwaitingSecondRoll, List(first), None),
      PublicTurn(Phase.AwaitingPairChoice, List(first, second), None),
      PublicTurn(Phase.PlayingSelectedPair, List(first, second), Some(1)),
      PublicTurn(Phase.Ended, List(first), None),
      PublicTurn(Phase.Ended, List(first, second), Some(0))
    )
    valid.foreach(roundtrip(_))
    assert(
      decode[PublicTurn]("""{"phase":"AwaitingSecondRoll","offers":[[1,3],[1,4]],"selectedPairIndex":null}""").isLeft
    )
    assert(decode[PublicTurn]("""{"phase":"AwaitingPairChoice","offers":[[1,3],[1,4]],"selectedPairIndex":0}""").isLeft)
    assert(
      decode[PublicTurn]("""{"phase":"PlayingSelectedPair","offers":[[1,3],[1,4]],"selectedPairIndex":null}""").isLeft
    )
    intercept[IllegalArgumentException]:
      PublicTurn(Phase.AwaitingSecondRoll, List(first, second), None).asJson

  test("equal offers keep distinct indices and unusable choices remain representable"):
    roundtrip(HistoryChoice(List(first, first), 0))
    roundtrip(HistoryChoice(List(first, first), 1))
    roundtrip(PublicTurn(Phase.PlayingSelectedPair, List(DicePair(5, 6), second), Some(0)))
    assert(decode[HistoryChoice]("""{"offers":[[1,3]],"selectedPairIndex":0}""").isLeft)

  test("legacy absence means classic only at the explicit old-record boundary"):
    assertEquals(LaboratoryCodecs.variantOfLegacyClassicRecord(json("{}")), Right(Classic))
    assertEquals(
      LaboratoryCodecs.variantOfLegacyClassicRecord(json("""{"variant":{"id":"choice-2x2d6","revision":1}}""")),
      Right(TwoPairs)
    )
    assert(LaboratoryCodecs.variantOfLegacyClassicRecord(json("""{"variant":null}""")).isLeft)
    List("""{"id":"choice-2x2d6","revision":2}""", """{"id":"unknown","revision":1}""").foreach: source =>
      assert(decode[VariantIdentity](source).isLeft)
    assert(
      decode[PublicState](
        """{"provenance":{"rulesVersion":"0.17.1","diceAlgorithm":"choice-2x2d6-hmac-sha256-v1"},"turn":{"phase":"AwaitingPlayers","offers":[],"selectedPairIndex":null}}"""
      ).isLeft
    )

  test("snapshot extension, event, receipt and refusal shapes round-trip independently of classic codecs"):
    val lab =
      PublicState(TwoPairs, ProtocolVersion, provenance, PublicTurn(Phase.AwaitingSecondRoll, List(first), None))
    roundtrip(lab)
    assert(decode[PublicState](lab.asJson.deepMerge(Json.obj("protocolVersion" -> 2.asJson)).noSpaces).isLeft)
    val event = TurnChangedFrame(TurnChanged(2, Seat.White, lab, EngineInitial, Some(Clocks(600000, 600000))))
    roundtrip(event)
    assertEquals(
      event.asJson.hcursor.downField("LabTurnChanged").downField("lab").downField("turn").get[Json]("offers"),
      Right(json("[[1,3]]"))
    )
    roundtrip(ReceiptFrame(Receipt("first", 2)))
    val refusal =
      RejectionFrame(Rejection(2, Some("second"), ErrorCode.StaleVersion, "fetch the current snapshot", true))
    roundtrip(refusal)
    assertEquals(refusal.asJson.hcursor.downField("LabRejected").get[String]("code"), Right("StaleVersion"))
    locally:
      import dicechess.play.wire.Codecs.given
      assert(decode[GameCommand](CommandFrame(Command("first", 1, Action.RollFirst)).asJson.noSpaces).isLeft)
      assertEquals(
        decode[GameCommand]("""{"SubmitTurn":{"moves":["e2e4"]}}"""),
        Right(GameCommand.SubmitTurn(List("e2e4")))
      )

  test("wire versions are safe in JavaScript and malformed command ids and moves cannot be submitted"):
    roundtrip(CommandFrame(Command("boundary", MaxWireVersion, Action.RollFirst)))
    List(-1L, MaxWireVersion + 1).foreach: version =>
      assert(decode[Command](s"""{"commandId":"roll","expectedVersion":$version,"action":{"RollFirst":{}}}""").isLeft)
    List("", "not a command id", "x" * 65).foreach: id =>
      assert(
        decode[Command](
          Json
            .obj("commandId" -> id.asJson, "expectedVersion" -> 0.asJson, "action" -> Action.RollFirst.asJson)
            .noSpaces
        ).isLeft
      )
    List("[]", "[\"e2e4\",\"a2a3\",\"g1f3\"]", "[\"z9z8\"]").foreach: moves =>
      assert(decode[Action](s"""{"SubmitTurn":{"moves":$moves}}""").isLeft)
    assert(decode[Provenance]("""{"rulesVersion":"0.17.1","diceAlgorithm":"unknown"}""").isLeft)

  private val EngineInitial = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
