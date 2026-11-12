package dicechess.play.store

import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.game.EngineOps
import dicechess.play.store.LaboratoryStorage.*
import dicechess.play.wire.LaboratoryCodecs
import dicechess.play.wire.LaboratoryCodecs.given
import io.circe.syntax.*

private[store] object LaboratoryFixtures:
  def snapshot(phase: Phase = Phase.AwaitingFirstRoll): GameSnapshot =
    val pairA                        = DicePair(1, 4)
    val pairB                        = DicePair(2, 5)
    val (offers, revealed, selected) = phase match
      case Phase.AwaitingPlayers     => (Nil, 0, None)
      case Phase.AwaitingFirstRoll   => (List(pairA, pairB), 0, None)
      case Phase.AwaitingSecondRoll  => (List(pairA, pairB), 1, None)
      case Phase.AwaitingPairChoice  => (List(pairA, pairB), 2, None)
      case Phase.PlayingSelectedPair => (List(pairA, pairB), 2, Some(1))
      case Phase.Ended               => (Nil, 0, None)
    val started = phase != Phase.AwaitingPlayers
    val ended   = phase == Phase.Ended
    val state   = State(
      ProtocolVersion,
      Provenance(RulesVersion.value, DiceAlgorithm),
      EngineOps.InitialDfen,
      PendingOffers(phase, offers, revealed, selected),
      Option.when(started)(ClientSeeds("white-seed", "black-seed")),
      Vector.empty,
      1_800_000_000_000L,
      if started then Set(Seat.White, Seat.Black) else Set.empty,
      fairnessRevealed = false
    )
    val position = EngineOps.parse(EngineOps.InitialDfen).toOption.get
    val dfen     = if selected.isDefined then
      EngineOps.serialize(EngineOps.withDice(position, selected.toList.flatMap(i => offers(i).values)))
    else EngineOps.serialize(position)
    GameSnapshot(
      version = 10,
      dfen = dfen,
      players = Map(
        Seat.White -> Principal.Guest("00000000-0000-0000-0000-000000000001"),
        Seat.Black -> Principal.Guest("00000000-0000-0000-0000-000000000002")
      ),
      seatTokens = Map(Seat.White -> "private-white-token", Seat.Black -> "private-black-token"),
      serverSeed = "ab" * 32,
      clientSeeds = Map.empty,
      started = started,
      ply = 0,
      pending = phase == Phase.PlayingSelectedPair,
      status = if ended then GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted)) else GameStatus.Active,
      timeControl = TimeControl.Unlimited,
      remainingMs = Map.empty,
      lastRoll = Nil,
      turns = Vector.empty,
      rated = Some(false),
      ladder = Some(false),
      origin = Some(GameOrigin.Direct),
      ratedRequested = Some(false),
      ratingDomain = Some(RatingDomain.Casual),
      variant = Some(TwoPairs),
      lab = Some(state)
    )

class LaboratorySnapshotSuite extends munit.FunSuite:
  import LaboratoryFixtures.snapshot

  private val interrupted = List(
    Phase.AwaitingPlayers,
    Phase.AwaitingFirstRoll,
    Phase.AwaitingSecondRoll,
    Phase.AwaitingPairChoice,
    Phase.PlayingSelectedPair,
    Phase.Ended
  )

  test("every interrupted laboratory stage validates and round-trips through its private snapshot codec"):
    interrupted.foreach { phase =>
      val original = snapshot(phase)
      val encoded  = LaboratoryCodecs.encodeValidated(original)
      assert(encoded.isRight, s"$phase: $encoded")
      val decoded = encoded.toOption.get.as[GameSnapshot]
      assertEquals(decoded, Right(original))
    }

  test("known legacy classic records keep their missing variant and laboratory fallback"):
    val classic = snapshot().copy(
      variant = None,
      lab = None,
      started = false,
      players = Map.empty,
      seatTokens = Map.empty,
      serverSeed = "aa",
      clientSeeds = Map.empty
    )
    val encoded = LaboratoryCodecs.encodeValidated(classic).toOption.get
    val legacy  = encoded.mapObject(_.remove("variant").remove("lab"))
    assertEquals(legacy.as[GameSnapshot].map(_.effectiveVariant), Right(Classic))
    assertEquals(LaboratoryCodecs.variantOfLegacyClassicRecord(legacy), Right(Classic))
    assert(LaboratoryCodecs.variantOfLegacyClassicRecord(legacy.mapObject(_.add("variant", io.circe.Json.Null))).isLeft)

  test("explicit unsupported or missing laboratory identity is rejected"):
    val good        = LaboratoryCodecs.encodeValidated(snapshot()).toOption.get
    val badRevision =
      good.mapObject(_.add("variant", io.circe.Json.obj("id" -> "choice-2x2d6".asJson, "revision" -> 2.asJson)))
    val unknown = good.mapObject(_.add("variant", io.circe.Json.obj("id" -> "unknown".asJson, "revision" -> 1.asJson)))
    val missingLab = good.mapObject(_.add("variant", TwoPairs.asJson).remove("lab"))
    assert(badRevision.as[GameSnapshot].isLeft)
    assert(unknown.as[GameSnapshot].isLeft)
    assert(good.mapObject(_.add("variant", io.circe.Json.Null)).as[GameSnapshot].isLeft)
    assert(missingLab.as[GameSnapshot].isLeft)

  test("malformed generated and revealed offers fail through the typed encoder"):
    val bad = snapshot().copy(lab =
      snapshot().lab.map(s =>
        s.copy(turn =
          s.turn.copy(phase = Phase.AwaitingSecondRoll, generatedOffers = List(DicePair(7, 1)), revealedOfferCount = 1)
        )
      )
    )
    assert(LaboratoryCodecs.encodeValidated(bad).isLeft)

  private def completed(snapshot0: GameSnapshot, pass: Boolean = false): GameSnapshot =
    val choice = HistoryChoice(List(DicePair(1, 4), DicePair(2, 5)), 1)
    val turn   = TurnRecord(
      0,
      "w",
      choice.offers(choice.selectedPairIndex).values,
      if pass then Nil else List("e2e4"),
      EngineOps.InitialDfen,
      labChoice = Some(choice)
    )
    snapshot0.copy(turns = Vector(turn))

  test("public transcript has the exact schema and never exposes private recovery data or unrevealed offers"):
    val value    = snapshot(Phase.AwaitingSecondRoll)
    val json     = LaboratoryStorage.transcript(value).toOption.get
    val expected = Set(
      "schemaVersion",
      "variant",
      "provenance",
      "timeControl",
      "initialDfen",
      "status",
      "completeness",
      "turns",
      "currentTurn",
      "fairness"
    )
    assertEquals(json.asObject.get.keys.toSet, expected)
    val rendered = json.noSpaces
    List(
      "private-white-token",
      "private-black-token",
      "white-seed",
      "black-seed",
      "ab" * 32,
      "00000000-0000-0000-0000-000000000001",
      "00000000-0000-0000-0000-000000000002"
    ).foreach { secret =>
      assert(!rendered.contains(secret), s"transcript exposed $secret")
    }
    assertEquals(json.hcursor.downField("currentTurn").get[List[DicePair]]("offers").map(_.size), Right(1))
    assert(!rendered.contains("2,5"))

  test("completed transcript retains both offers, the selected pair, and pass turns"):
    val value  = completed(snapshot(), pass = true)
    val json   = LaboratoryStorage.transcript(value).toOption.get
    val record = json.hcursor.downField("turns").downN(0)
    assertEquals(record.get[List[DicePair]]("offers").toOption.get, List(DicePair(1, 4), DicePair(2, 5)))
    assertEquals(record.get[Int]("selectedPairIndex"), Right(1))
    assertEquals(record.get[List[Int]]("dice"), Right(List(2, 5)))
    assertEquals(record.get[List[String]]("moves"), Right(Nil))
    assertEquals(LaboratoryStorage.validateSnapshot(value), Right(value))

  test("terminal fairness may be revealed for an abort while its transcript remains incomplete"):
    val aborted = snapshot(Phase.Ended)
    val hidden  = LaboratoryStorage.transcript(aborted).toOption.get
    assertEquals(hidden.hcursor.get[String]("completeness"), Right("incomplete"))
    assertEquals(hidden.hcursor.get[io.circe.Json]("fairness"), Right(io.circe.Json.Null))
    val sporting = aborted.copy(
      status = GameStatus.Ended(GameOver(GameResult.Draw, Termination.Draw)),
      lab = aborted.lab.map(s => s.copy(fairnessRevealed = true))
    )
    val revealed = LaboratoryStorage.transcript(sporting).toOption.get
    assertEquals(revealed.hcursor.get[String]("completeness"), Right("complete"))
    assert(revealed.hcursor.get[io.circe.Json]("fairness").toOption.exists(_ != io.circe.Json.Null))
    // The approved export contract explicitly permits a revealed abort, but never calls it complete.
    val revealedAbort = sporting.copy(status = GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted)))
    val abortReveal   = LaboratoryStorage.transcript(revealedAbort).toOption.get
    assertEquals(abortReveal.hcursor.get[String]("completeness"), Right("incomplete"))
    assert(abortReveal.hcursor.get[io.circe.Json]("fairness").toOption.exists(_ != io.circe.Json.Null))
    assert(LaboratoryTranscript.decode(abortReveal).isRight)
    val active = snapshot().copy(lab = snapshot().lab.map(_.copy(fairnessRevealed = true)))
    assert(LaboratoryStorage.transcript(active).isLeft)

  test("rated and bot laboratory snapshots fail with typed validation errors"):
    val rated = snapshot().copy(rated = Some(true))
    val bot   = snapshot().copy(players = snapshot().players.updated(Seat.Black, Principal.Bot("fixture", "bot")))
    assert(LaboratoryCodecs.encodeValidated(rated).isLeft)
    assert(LaboratoryCodecs.encodeValidated(bot).isLeft)

  test("public export decoding checks schema, revision, fairness evidence and completeness"):
    val terminal = snapshot(Phase.Ended)
    val complete = terminal.copy(
      status = GameStatus.Ended(GameOver(GameResult.Draw, Termination.Draw)),
      lab = terminal.lab.map(_.copy(fairnessRevealed = true))
    )
    val json = LaboratoryStorage.transcript(complete).toOption.get
    assert(LaboratoryTranscript.decode(json).isRight)
    val wrongCommit = json.deepMerge(io.circe.Json.obj("fairness" -> io.circe.Json.obj("commit" -> ("00" * 32).asJson)))
    val wrongSeed = json.deepMerge(io.circe.Json.obj("fairness" -> io.circe.Json.obj("serverSeed" -> "not-hex".asJson)))
    val wrongSchema   = json.deepMerge(io.circe.Json.obj("schemaVersion" -> 2.asJson))
    val wrongRevision = json.deepMerge(io.circe.Json.obj("variant" -> io.circe.Json.obj("revision" -> 2.asJson)))
    List(wrongCommit, wrongSeed, wrongSchema, wrongRevision).foreach(value =>
      assert(LaboratoryTranscript.decode(value).isLeft)
    )
    val active      = LaboratoryStorage.transcript(snapshot(Phase.AwaitingSecondRoll)).toOption.get
    val earlyReveal =
      active.deepMerge(io.circe.Json.obj("fairness" -> json.hcursor.get[io.circe.Json]("fairness").toOption.get))
    assert(LaboratoryTranscript.decode(earlyReveal).isLeft)
    assert(LaboratoryTranscript.decode(active.deepMerge(io.circe.Json.obj("completeness" -> "complete".asJson))).isLeft)

  test("malformed command receipts fail through the typed snapshot encoder"):
    val malformed = AcceptedCommand(Seat.White, Command("receipt-1", 4, Action.RollFirst), 4)
    val value     = snapshot().copy(lab = snapshot().lab.map(s => s.copy(receipts = Vector(malformed))))
    assert(LaboratoryCodecs.encodeValidated(value).isLeft)

  test("updates reject changed settings, provenance, variant, and rewritten history or receipts"):
    val before                               = completed(snapshot())
    def changed(next: GameSnapshot): Boolean = LaboratoryStorage.validateUpdate(before, next).isLeft
    assert(changed(before.copy(timeControl = TimeControl.Fischer(60, 0))))
    assert(changed(before.copy(variant = Some(Classic))))
    assert(
      changed(before.copy(lab = before.lab.map(s => s.copy(provenance = s.provenance.copy(rulesVersion = "changed")))))
    )
    assert(changed(before.copy(turns = Vector.empty)))
    val accepted    = AcceptedCommand(Seat.White, Command("receipt-1", 4, Action.RollFirst), 5)
    val withReceipt = before.copy(lab = before.lab.map(s => s.copy(receipts = Vector(accepted))))
    assert(
      LaboratoryStorage
        .validateUpdate(withReceipt, withReceipt.copy(lab = withReceipt.lab.map(s => s.copy(receipts = Vector.empty))))
        .isLeft
    )
