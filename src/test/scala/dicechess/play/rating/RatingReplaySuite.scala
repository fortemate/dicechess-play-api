package dicechess.play.rating

import dicechess.play.core.RatingCategory
import dicechess.play.rating.RatingReplay.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.time.Instant

/** Pure — no IO, no Docker: the synthetic corpus in, ledger and summary out. Three things are pinned: the committed
  * fixture files hash to the published SHA-256 and agree with [[RatingReplayFixture.generate]] up to floating-point
  * ulps, the replay reproduces the fixture's own recorded numbers except for the one corrupted row, and every verdict
  * class and skip reason the ledger distinguishes is reached by at least one row.
  *
  * Two resolutions are exercised throughout. `Lenient` sees the corpus as the batch of the day did (every bot
  * registered) and is the reproduction baseline; `Current` refuses the since-deleted bot and shows how one refused seat
  * cascades into its opponents' chains — which is the very effect the production report has to separate from
  * arithmetic.
  *
  * Regenerating the fixture on purpose: run with `RATING_REPLAY_WRITE_FIXTURE=1` once, then update the hashes below.
  */
class RatingReplaySuite extends munit.FunSuite:

  private val resources = Paths.get("src/test/resources/rating-replay")

  private val (games, participants) = RatingReplayFixture.generate()

  private val gamesJsonl        = RatingReplayFixture.gamesJsonl(games)
  private val participantsJsonl = RatingReplayFixture.participantsJsonl(participants)

  // Published fixture hashes — part of the fixture's version; a change here is a deliberate `synthetic-vN` bump.
  private val GamesSha256        = "a7f468bb304dd46cb58e6c7ab00bd03a48cdb9cedc0a47dbbb6e6674ad351a70"
  private val ParticipantsSha256 = "d829ee32989341a40d87c438ba0d50bd43abad62efc401d2dce7f2c9d4a2ad59"

  override def beforeAll(): Unit =
    if sys.env.get("RATING_REPLAY_WRITE_FIXTURE").contains("1") then
      // The committed bytes depend on `Double.toString`, whose shortest-representation contract only holds from JDK 19
      // (JDK-4511638); the project pins Temurin 25. Refuse to write a fixture an older runtime would render differently.
      assert(
        Runtime.version().feature() >= 19,
        s"write the fixture on JDK 19 or newer (the project's Temurin 25), not ${Runtime.version()}"
      )
      Files.createDirectories(resources)
      Files.writeString(resources.resolve(s"${RatingReplayFixture.Version}.games.jsonl"), gamesJsonl, UTF_8)
      Files.writeString(
        resources.resolve(s"${RatingReplayFixture.Version}.participants.jsonl"),
        participantsJsonl,
        UTF_8
      )
      Files.writeString(
        resources.resolve(s"${RatingReplayFixture.Version}.sha256"),
        s"${RatingReplayFixture.sha256(gamesJsonl)}  ${RatingReplayFixture.Version}.games.jsonl\n" +
          s"${RatingReplayFixture.sha256(participantsJsonl)}  ${RatingReplayFixture.Version}.participants.jsonl\n",
        UTF_8
      )

  private val Human = "human:0123456789abcdef"
  private val Ghost = "bot:team:fx:ghost"
  private val Delta = "bot:team:fx:delta"

  private val lenientConfig = Config(resolution = Resolution.Lenient)
  private val lenient       = replay(games, lenientConfig)
  private val current       = replay(games, Config())
  private val summary       = summarize(lenient, participants, lenientConfig)

  private def count(outcomes: Vector[Outcome], verdict: Verdict): Int = outcomes.count(_.verdict == verdict)

  test("the committed fixture files hash to the published digests"):
    // The digests pin the COMMITTED bytes, so they hold on every platform; whether those bytes still say what the
    // generator says is the next test's job.
    val committedGames = Files.readString(resources.resolve(s"${RatingReplayFixture.Version}.games.jsonl"), UTF_8)
    val committedParticipants =
      Files.readString(resources.resolve(s"${RatingReplayFixture.Version}.participants.jsonl"), UTF_8)
    assertEquals(RatingReplayFixture.sha256(committedGames), GamesSha256)
    assertEquals(RatingReplayFixture.sha256(committedParticipants), ParticipantsSha256)
    val manifest = Files.readString(resources.resolve(s"${RatingReplayFixture.Version}.sha256"), UTF_8)
    assert(manifest.contains(GamesSha256) && manifest.contains(ParticipantsSha256), "sha256 manifest out of date")

  test("the committed fixture files are the generator's output, up to floating-point ulps across architectures"):
    // `Math.exp`/`log` are 1-ulp-tolerant intrinsics, so an aarch64 machine and an x86-64 runner print the last digits
    // of a rating differently (the same reason `BradleyTerrySuite` compares its golden vector within 1e-9), and over a
    // chain of hundreds of dependent updates the gap grows to ~1e-9 (row 325 of this fixture: 1.0e-9 on the CI runner).
    // Every non-numeric field must be identical; every rating within 1e-6 — the replay's own tolerance, six orders of
    // magnitude below anything a rating means.
    val committedGames = Files
      .readString(resources.resolve(s"${RatingReplayFixture.Version}.games.jsonl"), UTF_8)
      .linesIterator
      .map(line => io.circe.parser.decode[Game](line).fold(e => fail(s"undecodable game row '$line': $e"), identity))
      .toVector
    assertEquals(committedGames.size, games.size)
    committedGames.zip(games).foreach { (committed, generated) =>
      def strip(g: Game) = g.copy(
        white = g.white.copy(ratingBefore = None, ratingAfter = None),
        black = g.black.copy(ratingBefore = None, ratingAfter = None)
      )
      assertEquals(strip(committed), strip(generated), s"row ${generated.seqFinished}")
      def close(a: Option[Double], b: Option[Double], what: String): Unit = (a, b) match
        case (Some(x), Some(y)) => assertEqualsDouble(x, y, 1e-6, s"row ${generated.seqFinished} $what")
        case _                  => assertEquals(a, b, s"row ${generated.seqFinished} $what")
      close(committed.white.ratingBefore, generated.white.ratingBefore, "white before")
      close(committed.white.ratingAfter, generated.white.ratingAfter, "white after")
      close(committed.black.ratingBefore, generated.black.ratingBefore, "black before")
      close(committed.black.ratingAfter, generated.black.ratingAfter, "black after")
    }
    val committedParticipants = Files
      .readString(resources.resolve(s"${RatingReplayFixture.Version}.participants.jsonl"), UTF_8)
      .linesIterator
      .map(line =>
        io.circe.parser.decode[Participant](line).fold(e => fail(s"undecodable participant row '$line': $e"), identity)
      )
      .toVector
    assertEquals(committedParticipants.map(_.copy(ratings = Map.empty)), participants.map(_.copy(ratings = Map.empty)))
    committedParticipants.zip(participants).foreach { (committed, generated) =>
      assertEquals(committed.ratings.keySet, generated.ratings.keySet, committed.id)
      committed.ratings.foreach { (cat, snap) =>
        val other = generated.ratings(cat)
        assertEqualsDouble(snap.rating, other.rating, 1e-6, s"${committed.id} $cat rating")
        assertEqualsDouble(snap.rd, other.rd, 1e-6, s"${committed.id} $cat rd")
        assertEqualsDouble(snap.vol, other.vol, 1e-6, s"${committed.id} $cat vol")
      }
    }

  test("the fixture round-trips through the JSONL codecs"):
    val decoded = gamesJsonl.linesIterator.map(line => io.circe.parser.decode[Game](line)).toList
    assert(decoded.forall(_.isRight), decoded.collectFirst { case Left(e) => e.getMessage }.getOrElse(""))
    assertEquals(decoded.collect { case Right(g) => g }, games.toList)
    val ps = participantsJsonl.linesIterator.map(line => io.circe.parser.decode[Participant](line)).toList
    assertEquals(ps.collect { case Right(p) => p }, participants.toList)

  test("seen as the batch of the day saw it, the replay reproduces every recorded step except the corrupted row"):
    assertEquals(count(lenient, Verdict.Mismatch), 1, "exactly the corrupted row must mismatch")
    val corrupted = lenient.find(_.verdict == Verdict.Mismatch).get
    assertEquals(corrupted.game.seqFinished, RatingReplayFixture.CorruptedIndex.toLong + 1)
    assertEqualsDouble(corrupted.afterDiff.get, 5.0, 1e-9)
    assertEqualsDouble(corrupted.beforeDiff.get, 0.0, 1e-9)
    // A data problem, not an arithmetic one: the formula from the recorded befores disagrees with the recorded after
    // on this row only.
    assertEquals(corrupted.formulaHolds, Some(false))
    assertEquals(summary.integrity.formulaMismatches, 1L)
    assertEquals(summary.integrity.formulaChecked, games.count(_.numericRecorded).toLong)
    assert(count(lenient, Verdict.Match) > 100, s"most numeric rows must match: ${count(lenient, Verdict.Match)}")
    // The uniformly shifted block: levels off by exactly the shift, steps intact, arithmetic intact.
    val shifted = lenient.filter(_.verdict == Verdict.MatchStepOnly)
    assertEquals(shifted.size, RatingReplayFixture.LevelShiftedRows.size)
    shifted.foreach { o =>
      assertEqualsDouble(o.beforeDiff.get, RatingReplayFixture.LevelShift, 1e-9)
      assertEqualsDouble(o.stepDiff.get, 0.0, 1e-9)
      assertEquals(o.formulaHolds, Some(true))
    }

  test("today's resolution refuses a deleted bot's recorded games, and the refusal cascades into its opponents"):
    // 6 ghost games sit in the numeric era: today's batch cannot resolve the bot, so they are recorded-but-refused …
    assertEquals(count(current, Verdict.ReplaySkippedRecordedApplied), 6)
    assertEquals(count(lenient, Verdict.ReplaySkippedRecordedApplied), 0)
    // … and every later game of the opponent that DID get those updates in production now starts from a different
    // pre-game rating, so the chain mismatches from there on although the arithmetic per row still holds.
    val cascade =
      current.filter(o => o.verdict == Verdict.Mismatch && o.game.seqFinished != RatingReplayFixture.CorruptedIndex + 1)
    assert(cascade.nonEmpty, "the refused seat must break its opponent's chain")
    assert(cascade.forall(_.beforeDiff.exists(_ > 0.0)), "cascade rows are lineage breaks: the pre-game rating differs")
    // The lenient baseline has no such breaks: its only formula failure is the corrupted row.
    assert(lenient.filter(_.verdict == Verdict.Mismatch).forall(_.beforeDiff.contains(0.0)))
    val currentSummary = summarize(current, participants, Config())
    // Glicko-2 forgets: hundreds of later games shrink the trace, but the final state still carries it.
    assert(
      currentSummary.finals.exists(f => f.id == "bot:team:fx:beta" && f.ratingDiff.exists(d => math.abs(d) > 1e-6))
    )

  test("every ledger class and every skip reason is reached"):
    // Coverage across both resolutions: the refused deleted bot exists only under `Current`, while the uniformly
    // shifted block reads as step-only just under `Lenient` (under `Current` the refusal's cascade moves its steps too).
    Verdict.values.foreach(v => assert(count(current, v) + count(lenient, v) > 0, s"no row reached verdict ${v.label}"))
    val reasons = current.flatMap(_.decision.reason).toSet
    // Unlimited is casual by construction (`GameRegistry.isRated`) and the aborted row is casual too, so neither the
    // uncategorised nor the no-result skip can reach the queue in this corpus; `decide` still has them (next test).
    assertEquals(reasons, Set(SkipReason.Unresolvable, SkipReason.SelfPlay, SkipReason.OwnBot))
    assertEquals(count(current, Verdict.Pending), 2)
    assertEquals(count(current, Verdict.SkipConsistent), 3, "the three own-bot games")
    assertEquals(count(current, Verdict.ReplayAppliedRecordedSkipped), 2, "the two roster-era human games")
    assertEquals(
      count(current, Verdict.UnverifiablePreNumeric),
      games.count(g => g.rated && g.applied && !g.numericRecorded && g.seqFinished <= RatingReplayFixture.RecordedFrom)
    )
    assertEquals(count(current, Verdict.Casual), games.count(!_.rated))

  test("decide mirrors RatingBatch.applyGame's decision tree, including branches the fixture queue never reaches"):
    val base = games.find(g => g.rated && g.white.kind == "bot" && g.black.kind == "bot" && g.ownerRelation.isEmpty).get
    assertEquals(decide(base.copy(result = None), Resolution.Current), Left(SkipReason.NoResult))
    assertEquals(decide(base.copy(timeControl = "Unlimited"), Resolution.Current), Left(SkipReason.Uncategorised))
    assertEquals(decide(base.copy(black = base.white), Resolution.Current), Left(SkipReason.SelfPlay))
    assertEquals(
      decide(base.copy(ownerRelation = Some("white_owns_black")), Resolution.Current),
      Left(SkipReason.OwnBot)
    )
    assertEquals(
      decide(base.copy(white = base.white.copy(kind = "guest")), Resolution.Lenient),
      Left(SkipReason.Unresolvable),
      "a guest never resolves, even leniently"
    )
    assertEquals(
      decide(base.copy(white = base.white.copy(resolvableNow = false)), Resolution.Current),
      Left(SkipReason.Unresolvable)
    )
    assert(decide(base.copy(white = base.white.copy(resolvableNow = false)), Resolution.Lenient).isRight)
    assert(decide(base, Resolution.Current).isRight)

  test("following the record reproduces the batch of the day regardless of today's rules"):
    val followed = replay(games, Config(followRecorded = true)) // current resolution, yet the ghost rows are applied
    assertEquals(count(followed, Verdict.ReplaySkippedRecordedApplied), 0)
    assertEquals(count(followed, Verdict.ReplayAppliedRecordedSkipped), 0)
    assertEquals(count(followed, Verdict.SkipConsistent), 5, "three own-bot games and two roster-era games")
    assertEquals(count(followed, Verdict.Mismatch), 1)
    assertEquals(followed.flatMap(_.decision.reason).toSet -- Set(SkipReason.SelfPlay), Set(SkipReason.RecordedSkip))
    val followedSummary = summarize(followed, participants, Config(followRecorded = true))
    followedSummary.finals
      .filter(_.snapshot.isDefined)
      .foreach(f => assertEqualsDouble(f.ratingDiff.get, 0.0, 1e-9, f.id))
    assert(followedSummary.histograms.exists(h => h.category == "blitz" && h.kind == "step"))
    val step = followedSummary.histograms.find(h => h.category == "blitz" && h.kind == "step").get
    assertEquals(step.bins.map(_.count).sum, step.rows)
    assertEquals(step.bins.last.count, 0L, "no step is off by more than 100")

  test("both orders agree on a corpus the batch applied strictly in finish order"):
    val finished = replay(games, lenientConfig.copy(order = Order.Finished))
    assertEquals(finished.map(_.game.gameId), lenient.map(_.game.gameId))
    assertEquals(summary.integrity.displacedRows, 0L)

  test("the summary's tallies reconcile with the corpus"):
    assertEquals(summary.games, games.size.toLong)
    assertEquals(summary.classes.map(_.count).sum, games.size.toLong)
    assertEquals(summary.integrity.pendingRows, 2L)
    assertEquals(summary.integrity.duplicateGameIds, 0L)
    assertEquals(summary.integrity.oneSidedNumeric, 0L)
    assertEquals(summary.integrity.numericWithoutStamp, 0L)
    assertEquals(summary.integrity.categoryColumnDisagreements, 0L)
    assertEquals(summary.numericRecordedSince, games.find(_.numericRecorded).map(_.finishedAt))

  test("the replayed final state equals the snapshot except where today's rules apply games the batch skipped"):
    // The corrupted row only corrupts what was RECORDED on it, not the state that fed the next game, and pending rows
    // stay out of the comparison, so every identity's final replayed rating equals the table snapshot — except the
    // two seats of the roster-era games, which the replay applies and the batch of the day did not.
    val exempt = Set((Human, "blitz"), (Delta, "blitz"))
    summary.finals.filter(_.snapshot.isDefined).foreach { f =>
      if exempt.contains((f.id, f.category)) then assert(math.abs(f.ratingDiff.get) > 0.1, s"${f.id} must differ")
      else assertEqualsDouble(f.ratingDiff.get, 0.0, 1e-9, s"${f.id}/${f.category}")
    }
    assert(summary.finals.exists(f => f.id == Ghost && f.snapshot.isEmpty), "ghost has replayed state but no snapshot")

  test("pair-delta sums, drift series and inactive offsets are computed per category"):
    assertEquals(summary.deltaSums.map(_.category).toSet, Set("blitz", "rapid"))
    val blitz = summary.deltaSums.find(_.category == "blitz").get
    // The recorded sum covers numeric rows only and carries the +5.0 corruption; the replayed one covers every applied
    // game. Neither is zero: Glicko-2 steps are not zero-sum.
    assert(blitz.recordedGames < blitz.replayedGames)
    assertNotEquals(blitz.replayedPairDeltaSum, 0.0)
    assert(summary.drift.nonEmpty && summary.deltaSeries.nonEmpty)
    assertEquals(summary.deltaSeries.filter(_.category == "blitz").last.games, blitz.replayedGames)
    val delta = summary.inactiveOffsets.find(o => o.id == Delta && o.category == "blitz")
    assert(delta.isDefined, "delta retired early and must appear as inactive")
    assert(delta.get.offset.isDefined)
    assert(
      !summary.inactiveOffsets.exists(o => o.id == "bot:team:fx:alpha" && o.category == "blitz"),
      "alpha plays blitz to the end"
    )
    assert(
      summary.inactiveOffsets.exists(o => o.id == "bot:team:fx:alpha" && o.category == "rapid"),
      "the rapid strand ends early"
    )

  test("the human identity's lag record is present per category"):
    val human = summary.humans.filter(_.id == Human)
    assertEquals(human.map(_.category).toSet, Set("blitz", "rapid"))
    val blitz = human.find(_.category == "blitz").get
    assertEquals(blitz.games, 12L, "10 games against alpha plus the two roster-era games the replay applies")
    assertEqualsDouble(blitz.surplus, blitz.actualScore - blitz.expectedScore, 1e-12)
    assert(blitz.meanOpponentRating > 1500.0)

  test("the single-scale era model seeds a category from the shared state at the switch"):
    val switchAt = games(RatingReplayFixture.RecordedFrom).finishedAt
    val history  = replay(games, lenientConfig.copy(scale = Scale.SingleUntil(switchAt)))
    // Before the switch the fixture's Rapid strand does not exist, so the first Rapid game after it must start from
    // the identity's Blitz-fed single state rather than from 1500 …
    val firstRapid = history.find(o => o.decision == Decision.Applied && o.category.contains(RatingCategory.Rapid)).get
    assertNotEquals(firstRapid.white.get.before.rating, 1500.0)
    // … while a per-category replay of the same corpus starts Rapid fresh.
    val fresh = lenient.find(o => o.decision == Decision.Applied && o.category.contains(RatingCategory.Rapid)).get
    assertEqualsDouble(fresh.white.get.before.rating, 1500.0, 1e-12)

  test("the τ switch applies the earlier constant only before the switch instant"):
    val tau = Tau(after = 0.3, before = 0.5, switchAt = Some(Instant.parse("2026-07-02T00:00:00Z")))
    assertEqualsDouble(tau.at(Instant.parse("2026-07-01T23:59:59Z")), 0.5, 0.0)
    assertEqualsDouble(tau.at(Instant.parse("2026-07-02T00:00:00Z")), 0.3, 0.0)
    val early = replay(games, lenientConfig.copy(tau = tau))
    assert(count(early, Verdict.Mismatch) > 1, "a different τ before the switch must move recorded-era numbers")

  test("a seeded-category list restarts every other category at 1500 after the switch"):
    val switchAt        = games(RatingReplayFixture.RecordedFrom).finishedAt
    val blitzOnlyConfig = lenientConfig.copy(scale = Scale.SingleUntil(switchAt, Set(RatingCategory.Blitz)))
    val blitzOnly       = replay(games, blitzOnlyConfig)
    val firstRapid      =
      blitzOnly.find(o => o.decision == Decision.Applied && o.category.contains(RatingCategory.Rapid)).get
    assertEqualsDouble(firstRapid.white.get.before.rating, 1500.0, 1e-12, "rapid restarts fresh")
    assertEqualsDouble(firstRapid.black.get.before.rating, 1500.0, 1e-12, "rapid restarts fresh")
    val firstBlitzAfter =
      blitzOnly
        .find(o =>
          o.decision == Decision.Applied && o.game.processedAt.isAfter(switchAt) && o.category
            .contains(RatingCategory.Blitz)
        )
        .get
    assertNotEquals(firstBlitzAfter.white.get.before.rating, 1500.0, "blitz carries the shared state over")
    val blitzSummary = summarize(blitzOnly, participants, blitzOnlyConfig)
    assert(blitzSummary.config.scale.endsWith(":blitz"), blitzSummary.config.scale)

  test("following the record skips a numeric row that has no result or no scale, with its own reason"):
    val numeric = games.find(g => g.numericRecorded && g.white.kind == "bot" && g.black.kind == "bot").get
    val crafted =
      games.map(g => if g.gameId == numeric.gameId then g.copy(result = None, termination = "aborted") else g)
    val followed = replay(crafted, Config(followRecorded = true))
    val outcome  = followed.find(_.game.gameId == numeric.gameId).get
    assertEquals(outcome.decision, Decision.Skipped(SkipReason.RecordedWithoutOutcome))
    assertEquals(outcome.verdict, Verdict.ReplaySkippedRecordedApplied)

  test("a numeric row with one side missing is a mismatch and an integrity finding, never a match"):
    val numeric = games.find(g => g.numericRecorded && g.white.kind == "bot" && g.black.kind == "bot").get
    val crafted =
      games.map(g => if g.gameId == numeric.gameId then g.copy(black = g.black.copy(ratingAfter = None)) else g)
    val outcomes = replay(crafted, lenientConfig)
    assertEquals(outcomes.find(_.game.gameId == numeric.gameId).get.verdict, Verdict.Mismatch)
    assertEquals(summarize(outcomes, participants, lenientConfig).integrity.oneSidedNumeric, 1L)

  test("ledger entries carry the decision, the reason, both seats' states and the recorded numbers"):
    val applied = lenient.find(_.verdict == Verdict.Match).get
    val entry   = ledgerEntry(applied)
    assertEquals(entry.decision, "applied")
    assertEquals(entry.reason, None)
    assertEquals(entry.verdict, "match")
    assertEquals(entry.whiteBefore.map(_.rating), applied.white.map(_.before.rating))
    assertEquals(entry.recordedWhiteAfter, applied.game.white.ratingAfter)
    assertEquals(entry.stepDiff, applied.stepDiff)
    val skipped = ledgerEntry(current.find(_.decision.reason.contains(SkipReason.Unresolvable)).get)
    assertEquals(skipped.decision, "skipped")
    assertEquals(skipped.reason, Some(SkipReason.Unresolvable))
    assertEquals(skipped.whiteBefore, None)
    val casual = ledgerEntry(lenient.find(_.verdict == Verdict.Casual).get)
    assertEquals((casual.decision, casual.verdict, casual.formulaHolds), ("casual", "casual", None))
    // Every entry encodes to one JSON object per line, the ledger's file format.
    assert(!io.circe.syntax.EncoderOps(entry).asJson.noSpaces.contains("\n"))

  test("render names the history knobs it ran with"):
    val config = Config(
      followRecorded = true,
      tau = Tau(after = 0.3, before = 0.5, switchAt = Some(Instant.parse("2026-07-02T00:00:00Z")))
    )
    val text = render(summarize(replay(games, config), participants, config))
    assert(text.contains("eligibility=recorded"))
    assert(text.contains("switch=2026-07-02T00:00:00Z"))

  test("render copes with an empty corpus and spells out the absences"):
    val empty = render(summarize(Vector.empty, Nil, Config()))
    assert(empty.contains("corpus: 0 rows"))
    assert(empty.contains("none"), "empty mismatch and human sections read 'none'")
    assert(empty.contains("integrity"))

  test("the daily series never reopens a closed day when a row was applied out of finish order"):
    // Move one applied row's finish two days back while keeping its apply stamp (and so its fold position): the fold
    // visits it after its new finish day has already closed — the shape a displaced production row has.
    val victim    = games.find(g => g.rated && g.applied && g.seqFinished > 200).get
    val displaced = games.map(g =>
      if g.gameId == victim.gameId then g.copy(finishedAt = g.finishedAt.minusSeconds(2L * 86400L)) else g
    )
    val summary = summarize(replay(displaced, lenientConfig), participants, lenientConfig)
    for cat <- List("blitz", "rapid") do
      val days = summary.drift.filter(_.category == cat).map(_.day)
      assertEquals(days, days.distinct, s"$cat: each day appears once")
      assertEquals(days, days.sorted, s"$cat: days are emitted in order")
      val series = summary.deltaSeries.filter(_.category == cat).map(_.day)
      assertEquals(series, series.distinct.sorted)
    // The row still counts: the pool means include it on the latest open day rather than dropping it.
    assertEquals(summary.games, games.size.toLong)

  test("a recorded pair present for one seat only counts as one-sided whether it is the before or the after"):
    val numeric = games.filter(g => g.numericRecorded).take(2)
    val crafted = games.map(g =>
      if g.gameId == numeric(0).gameId then g.copy(white = g.white.copy(ratingBefore = None))
      else if g.gameId == numeric(1).gameId then g.copy(black = g.black.copy(ratingAfter = None))
      else g
    )
    val outcomes = replay(crafted, lenientConfig)
    assertEquals(summarize(outcomes, participants, lenientConfig).integrity.oneSidedNumeric, 2L)
    numeric.foreach(n => assertEquals(outcomes.find(_.game.gameId == n.gameId).get.verdict, Verdict.Mismatch))

  test("the runner's options are validated as values: explicit nonsense is refused, absent knobs take defaults"):
    val ok = RatingReplayMain.parseOptions(
      List(
        "corpus=c.jsonl.gz",
        "out=o",
        "participants=p.jsonl",
        "ledger=true",
        "order=finished",
        "scale=single-until:2026-08-16T15:15:00Z:blitz",
        "resolution=lenient",
        "eligibility=recorded",
        "tau=0.3",
        "tau-before=0.5",
        "tau-switch-at=2026-08-16T15:15:00Z",
        "tolerance=1e-6",
        "inactive-days=10",
        "revision=abc123"
      )
    )
    val options = ok.fold(problem => fail(problem), identity)
    assertEquals(options.config.order, Order.Finished)
    assertEquals(
      options.config.scale,
      Scale.SingleUntil(Instant.parse("2026-08-16T15:15:00Z"), Set(RatingCategory.Blitz))
    )
    assertEquals(options.config.resolution, Resolution.Lenient)
    assert(options.config.followRecorded && options.ledger)
    assertEquals(options.config.tau, Tau(0.3, 0.5, Some(Instant.parse("2026-08-16T15:15:00Z"))))
    assertEquals(options.config.inactiveAfterDays, 10)
    assertEquals(options.revision, Some("abc123"))
    val defaults = RatingReplayMain.parseOptions(List("corpus=c.jsonl", "out=o")).fold(fail(_), identity)
    assertEquals(defaults.config, Config())
    assertEquals((defaults.participants, defaults.ledger, defaults.revision), (None, false, None))
    // Every refusal names the offending option; none of these silently becomes a default.
    val refused = List(
      List("out=o"),
      List("corpus=c", "out=o", "order=finishd"),
      List("corpus=c", "out=o", "resolution=strict"),
      List("corpus=c", "out=o", "eligibility=whatever"),
      List("corpus=c", "out=o", "scale=single-until:yesterday"),
      List("corpus=c", "out=o", "scale=single-until:2026-08-16T15:15:00Z:bullet,turbo"),
      List("corpus=c", "out=o", "tau=-1"),
      List("corpus=c", "out=o", "tau-switch-at=2026-08-16"),
      List("corpus=c", "out=o", "tolerance=abc"),
      List("corpus=c", "out=o", "inactive-days=0"),
      List("corpus=c", "out=o", "ledger=yes"),
      List("corpus=c", "out=o", "colour=blue"),
      List("corpus=c", "out=o", "out=p"),
      List("corpus=c", "out=o", "bare")
    )
    refused.foreach(args => assert(RatingReplayMain.parseOptions(args).isLeft, s"must refuse $args"))
    assert(RatingReplayMain.parseOptions(List("corpus=c", "out=o", "order=finishd")).swap.exists(_.contains("finishd")))

  test("a summary carries the provenance the runner attaches, and render prints it"):
    val provenance = Provenance(
      corpusFile = "games.jsonl.gz",
      corpusSha256 = "f8d86bf4",
      participantsFile = Some("participants.jsonl"),
      participantsSha256 = Some("8fa76bcd"),
      codeRevision = Some("5104d7b"),
      javaRuntime = "Temurin 25",
      osArch = "Mac OS X aarch64",
      scalaVersion = "3.9.0",
      ranAt = Instant.parse("2026-09-11T20:00:00Z")
    )
    val text = render(summary.copy(provenance = Some(provenance)))
    assert(text.contains("inputs: games.jsonl.gz sha256 f8d86bf4"))
    assert(text.contains("run: revision 5104d7b; Temurin 25"))
    assert(!render(summary).contains("inputs:"), "the pure summary has no provenance to print")

  test("render is stable text with the headline tallies"):
    val text = render(summary)
    assert(text.startsWith("=== Rating replay (#145) ==="))
    assert(text.contains("mismatch") && text.contains("pair-delta sums") && text.contains("integrity"))
