package dicechess.play.rating.migration

import dicechess.play.core.{
  GameOrigin,
  ParticipantKind,
  Principal,
  RatingCategory,
  RatingDomain,
  RatingOutcome,
  RatingPolicy,
  TimeControl
}
import dicechess.play.rating.{
  CapturedTrainingReference,
  Glicko,
  Glicko2,
  TrainingEstimate,
  TrainingReference,
  TrainingReferenceSource,
  TrainingState
}
import dicechess.play.rating.migration.RatingEpochPlan.*
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

import java.time.Instant

class RatingEpochPlanSuite extends FunSuite:
  private val evidence   = "a" * 64
  private val sourceHash = "b" * 64
  private val start      = Instant.parse("2026-01-01T00:00:00Z")
  private val config     = Config(sourceHash, 2, "synthetic-target-v2", start.plusSeconds(86400 * 10))
  private val botA       = SourceSeat(
    "bot:team:synthetic:a",
    ParticipantKind.Bot,
    Some(IdentityContinuity(evidence, Some("10000000-0000-0000-0000-000000000001")))
  )
  private val botB = SourceSeat(
    "bot:team:synthetic:b",
    ParticipantKind.Bot,
    Some(IdentityContinuity(evidence, Some("10000000-0000-0000-0000-000000000002")))
  )
  private val userA =
    SourceSeat("user:00000000-0000-0000-0000-000000000001", ParticipantKind.Human, Some(IdentityContinuity(evidence)))
  private val userB =
    SourceSeat("user:00000000-0000-0000-0000-000000000002", ParticipantKind.Human, Some(IdentityContinuity(evidence)))
  private val reference = CapturedTrainingReference.Recorded(
    TrainingReference(TrainingReferenceSource.Anchor("synthetic-recorded-v1", 1), Glicko(1700, 50, 0.06))
  )

  final private case class Pairing(origin: GameOrigin = GameOrigin.Ladder, ladder: Boolean = true)

  private def game(
      id: String = "one",
      white: SourceSeat = botA,
      black: SourceSeat = botB,
      at: Instant = start,
      control: String = "Fischer(300,3)",
      pairing: Pairing = Pairing(),
      result: Option[Int] = Some(1)
  ): SourceGame =
    def principal(seat: SourceSeat): Principal = seat.kind match
      case ParticipantKind.Bot   => Principal.fromBotExternalId(seat.externalId).get
      case ParticipantKind.Human => Principal.User(Principal.fromUserExternalId(seat.externalId).get)
      case ParticipantKind.Guest => Principal.Guest(seat.externalId.stripPrefix("guest:"))
    val classification = RatingPolicy.classify(
      RatingPolicy.Matrix,
      principal(white),
      principal(black),
      true,
      TimeControl.parse(control).get,
      pairing.origin,
      pairing.ladder
    )
    SourceGame(
      id,
      at,
      control,
      white,
      black,
      result,
      "king_captured",
      pairing.origin,
      pairing.ladder,
      true,
      RatingOutcome.Applied,
      Some(ClassificationEvidence(2, true, classification.rated, classification.domain, evidence)),
      Some(OwnershipEvidence(false, evidence)),
      reference
    )

  private def plan(games: SourceGame*): Plan                          = build(config, games.iterator).toOption.get
  private def ledger(games: SourceGame*): (Plan, Vector[LedgerEntry]) =
    val entries = Vector.newBuilder[LedgerEntry]
    val output  = build(config, games.iterator, entries += _).toOption.get
    (output, entries.result())

  test("competitive replay updates both seats simultaneously and records reconcilable counters"):
    val input  = game()
    val output = plan(input)
    assert(output.activatable)
    assertEquals(output.counts, Counts(1, 1, 0, 0, 0, 0))
    val expectedWhite = Glicko2.update(Glicko.Initial, List(Glicko2.Result(Glicko.Initial, 1)))
    val expectedBlack = Glicko2.update(Glicko.Initial, List(Glicko2.Result(Glicko.Initial, 0)))
    assertEquals(output.rows.map(_.glicko), Vector(expectedWhite, expectedBlack))
    assertEquals(output.rows.map(p => (p.games, p.wins, p.draws, p.losses)), Vector((1, 1, 0, 0), (1, 0, 0, 1)))
    assertEquals(validate(output), Right(()))

  test("training is one way, uses the captured reference, seat advantage and idle inflation"):
    val first    = game("one", userA, botA)
    val second   = game("two", botA, userA, start.plusSeconds(86400 * 2), result = Some(0))
    val output   = plan(first, second)
    val opponent = reference match
      case CapturedTrainingReference.Recorded(ref) => ref.glicko
      case _                                       => fail("fixture reference missing")
    val afterFirst  = TrainingEstimate.update(TrainingState.Initial, opponent, true, 1, start)
    val afterSecond = TrainingEstimate.update(afterFirst, opponent, false, 0.5, second.finishedAt)
    assertEquals(output.rows.size, 1)
    val row = output.rows.head
    assertEquals(row.externalId, userA.externalId)
    assertEquals(row.domain, RatingDomain.Training)
    assertEquals(row.glicko, afterSecond.glicko)
    assertEquals((row.games, row.wins, row.draws, row.losses), (2, 1, 1, 0))
    assertEquals(row.lastPlayedAt, second.finishedAt)
    assertEquals(validate(output), Right(()))

  test("competitive and training categories are independent and never borrow another speed"):
    val output = plan(
      game("a", userA, userB),
      game("b", userA, botA, start.plusSeconds(1), "Fischer(60,0)"),
      game("c", userA, botA, start.plusSeconds(2), "Fischer(600,10)")
    )
    assertEquals(
      output.rows.filter(_.externalId == userA.externalId).map(p => (p.category, p.domain)).toSet,
      Set(
        (RatingCategory.Blitz, RatingDomain.Competitive),
        (RatingCategory.Bullet, RatingDomain.Training),
        (RatingCategory.Rapid, RatingDomain.Training)
      )
    )
    assertEquals(validate(output), Right(()))

  test("missing legacy classification, continuity, ownership and reference are unknown, not invented"):
    val training = game(white = userA)
    val inputs   = Vector(
      game().copy(classification = None),
      game().copy(classification = game().classification.map(_.copy(policyVersion = 0))),
      game().copy(white = botA.copy(continuity = None)),
      game().copy(white = botA.copy(continuity = Some(IdentityContinuity(evidence)))),
      training.copy(ownership = None),
      training.copy(reference = CapturedTrainingReference.NotRecorded)
    )
    inputs.foreach { input =>
      val (output, entries) = ledger(input)
      assertEquals(output.counts.unknownRows, 1L)
      assert(!output.activatable)
      assertEquals(output.rows, Vector.empty)
      assertEquals(entries.head.decision, Decision.Unknown)
    }

  test("pending and legacy numeric absence never mutate the new state"):
    Vector(
      game().copy(applied = false, outcome = RatingOutcome.Pending),
      game().copy(outcome = RatingOutcome.Legacy),
      game(white = userA).copy(applied = false, outcome = RatingOutcome.Casual)
    ).foreach { input =>
      val output = plan(input)
      assertEquals(output.counts.unknownRows, 1L)
      assertEquals(output.rows, Vector.empty)
      assert(!output.activatable)
    }

  test("known exclusions have explicit ledger reasons"):
    val ownBot     = game(white = userA).copy(ownership = Some(OwnershipEvidence(true, evidence)))
    val direct     = game(pairing = Pairing(GameOrigin.Legacy, ladder = false))
    val missingRef = game(white = userA).copy(reference = CapturedTrainingReference.Unavailable)
    val skipped    = game().copy(outcome = RatingOutcome.Skipped, skipReason = Some("synthetic prior skip"))
    Vector(
      ownBot,
      direct,
      missingRef,
      skipped,
      game().copy(result = None),
      game().copy(termination = "aborted"),
      game(at = config.cutoff.plusSeconds(1))
    ).foreach { input =>
      val (output, entries) = ledger(input)
      assertEquals(output.counts.excludedRows, 1L)
      assert(entries.head.reasons.nonEmpty)
      assertEquals(output.rows, Vector.empty)
    }

  test("classification mismatches cannot override the matrix"):
    val direct = game(pairing = Pairing(GameOrigin.Legacy, ladder = false))
    val output = plan(
      direct.copy(classification = direct.classification.map(_.copy(rated = true, domain = RatingDomain.Competitive)))
    )
    assertEquals(output.counts.unknownRows, 1L)
    assert(!output.activatable)

  test("multiple bot incarnations cannot be merged into a current external identity"):
    val later = game("two", at = start.plusSeconds(1)).copy(white =
      botA.copy(continuity = Some(IdentityContinuity(evidence, Some("10000000-0000-0000-0000-000000000099"))))
    )
    val output = plan(game(), later)
    assertEquals(output.counts.competitiveGames, 1L)
    assertEquals(output.counts.unknownRows, 1L)
    assert(!output.activatable)

  test("tied mutating games need audited order rather than inventing a timestamp tie break"):
    val first    = game("a")
    val second   = game("b", result = Some(-1))
    val unproven = plan(first, second)
    assertEquals(unproven.counts.unknownRows, 2L)
    assertEquals(unproven.rows, Vector.empty)
    val (ordered, entries) = ledger(
      first.copy(orderEvidence = Some(OrderEvidence(2, evidence))),
      second.copy(orderEvidence = Some(OrderEvidence(1, evidence)))
    )
    assert(ordered.activatable)
    assertEquals(entries.map(_.gameId), Vector("b", "a"))
    assertEquals(validate(ordered), Right(()))
    assertEquals(
      plan(
        first.copy(orderEvidence = Some(OrderEvidence(1, evidence))),
        second.copy(orderEvidence = Some(OrderEvidence(1, evidence)))
      ).counts.unknownRows,
      2L
    )

  test("duplicate ids are unknown and cannot double count a successful reconstruction"):
    val output = plan(game(), game(at = start.plusSeconds(1)))
    assertEquals(output.counts.competitiveGames, 1L)
    assertEquals(output.counts.unknownRows, 1L)
    assertEquals(output.rows.map(_.games), Vector(1, 1))
    assert(!output.activatable)

  test("nonfinite or nonpositive reference states are never used for arithmetic"):
    Vector(
      Glicko(Double.NaN, 50, 0.06),
      Glicko(Double.PositiveInfinity, 50, 0.06),
      Glicko(1500, 0, 0.06),
      Glicko(1500, -1, 0.06),
      Glicko(1500, 50, 0)
    ).foreach { invalid =>
      val input = game(white = userA).copy(reference =
        CapturedTrainingReference.Recorded(TrainingReference(TrainingReferenceSource.BotRating, invalid))
      )
      assertEquals(plan(input).counts.unknownRows, 1L)
    }

  test("reset is a separate approved empty projection and never disguises a partial rebuild"):
    val approval = ApprovedReset("Synthetic documented restart", "synthetic-owner", evidence)
    val entries  = Vector.newBuilder[LedgerEntry]
    val output   = build(
      config.copy(approvedReset = Some(approval)),
      Iterator(game(), game("two", at = start.plusSeconds(1)).copy(classification = None)),
      entries += _
    ).toOption.get
    assertEquals(output.mode, Mode.ApprovedReset)
    assertEquals(output.rows, Vector.empty)
    assertEquals(output.counts.resetRows, 2L)
    assert(output.activatable)
    assert(entries.result().forall(_.decision == Decision.Reset))
    assertEquals(validate(output), Right(()))
    assert(
      build(config.copy(approvedReset = Some(approval.copy(artifactSha256 = "unverified"))), Iterator.empty).isLeft
    )

  test("limits, unknown versions and unsorted inputs fail without producing an activatable plan"):
    assert(
      build(config.copy(maxRows = 1, maxTieGroup = 1), Iterator(game(), game("two", at = start.plusSeconds(1)))).isLeft
    )
    assert(build(config.copy(maxTieGroup = 1), Iterator(game("a"), game("b"))).isLeft)
    assert(build(config, Iterator(game("b"), game("a"))).isLeft)
    assert(build(config, Iterator(game(at = start.plusSeconds(1)), game())).isLeft)
    assert(build(config.copy(modelVersion = "unknown"), Iterator.empty).isLeft)
    assert(!plan().activatable)

  test("planning leaves source values unchanged and produces stable digests and JSON round trips"):
    val inputs      = Vector(game("a"), game("b", at = start.plusSeconds(1)))
    val bytesBefore = inputs.map(_.asJson.noSpaces)
    val first       = build(config, inputs.iterator).toOption.get
    val second      = build(config, inputs.iterator).toOption.get
    assertEquals(first, second)
    assertEquals(inputs.map(_.asJson.noSpaces), bytesBefore)
    assertEquals(decode[SourceGame](inputs.head.asJson.noSpaces), Right(inputs.head))
    assertEquals(decode[Plan](first.asJson.noSpaces), Right(first))
    assertEquals(projectionDigest(first.rows), first.projectionSha256)
    assertEquals(planDigest(first), planDigest(second))
    assertNotEquals(planDigest(first), planDigest(first.copy(config = config.copy(targetEpoch = 3))))

  test("validation refuses tampered activation, rows, counters and digests"):
    val valid = plan(game())
    assert(validate(valid.copy(rows = valid.rows.map(_.copy(glicko = Glicko.Initial)))).isLeft)
    assert(validate(valid.copy(counts = valid.counts.copy(unknownRows = 1))).isLeft)
    assert(validate(valid.copy(ledgerSha256 = "edited")).isLeft)
    val badRow     = valid.rows.head.copy(games = 99)
    val editedRows = valid.rows.updated(0, badRow)
    assert(validate(valid.copy(rows = editedRows, projectionSha256 = projectionDigest(editedRows))).isLeft)
    val unknown = plan(game().copy(classification = None))
    assert(validate(unknown.copy(activatable = true)).isLeft)
    assert(validate(valid.copy(mode = Mode.ApprovedReset)).isLeft)

  test("reference JSON rejects an invalid recorded state rather than assigning a live fallback"):
    val invalid = game(white = userA).copy(reference =
      CapturedTrainingReference.Recorded(TrainingReference(TrainingReferenceSource.BotRating, Glicko(1500, -1, 0.06)))
    )
    assert(decode[SourceGame](invalid.asJson.noSpaces).isLeft)

  test("same external identity in both seats cannot update a projection twice"):
    val input             = game(black = botA)
    val (output, entries) = ledger(input)
    assertEquals(output.rows, Vector.empty)
    assertEquals(output.counts.unknownRows, 1L)
    assertEquals(entries.head.reasons, Vector("self_pairing"))
    assert(!output.activatable)

  test("a database source binding versions the manifest without changing the audited cutoff or rating arithmetic"):
    val games     = Vector(game())
    val original  = build(config.copy(databaseSourceSha256 = Some("1" * 64)), games.iterator).toOption.get
    val refreshed = build(config.copy(databaseSourceSha256 = Some("2" * 64)), games.iterator).toOption.get
    assertEquals(original.rows, refreshed.rows)
    assertEquals(original.config.cutoff, refreshed.config.cutoff)
    assertNotEquals(planDigest(original), planDigest(refreshed))
    assert(build(config.copy(databaseSourceSha256 = Some("not-a-digest")), games.iterator).isLeft)

  test("UUID aliases cannot split ledger identities from database projection identities"):
    val alias = "0-0-0-0-1"
    assertEquals(java.util.UUID.fromString(alias).toString, userA.externalId.stripPrefix("user:"))
    val humanGame = game(white = userA, black = userB)
    val inputs    = Vector(
      humanGame.copy(white = userA.copy(externalId = s"user:$alias")),
      game().copy(white = botA.copy(continuity = Some(IdentityContinuity(evidence, Some(alias)))))
    )
    inputs.foreach { input =>
      val output = plan(input)
      assertEquals(output.counts.unknownRows, 1L)
      assert(!output.activatable)
      assertEquals(output.rows, Vector.empty)
    }
    val valid   = plan(humanGame)
    val aliased = valid.rows
      .map(p => if p.externalId == userA.externalId then p.copy(externalId = s"user:$alias") else p)
      .sortBy(p => (p.externalId, p.category.wireName, p.domain.wireName))
    assert(validate(valid.copy(rows = aliased, projectionSha256 = projectionDigest(aliased))).isLeft)
