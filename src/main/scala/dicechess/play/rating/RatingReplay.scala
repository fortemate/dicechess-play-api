package dicechess.play.rating

import dicechess.play.core.RatingCategory
import io.circe.derivation.{Configuration, ConfiguredCodec}

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.collection.mutable

/** Chronological replay of the CURRENT rating implementation over an exported `game_results` corpus (#145) — the
  * evidence step of the stable-rating epic (fortemate-internal#168, ADR 008) that has to come before any model choice
  * or migration: it re-applies [[Glicko2.update]] game by game exactly as [[RatingBatch]] does, then tallies where the
  * replayed numbers agree with what production recorded on each row and where they do not. Pure: corpus in, ledger and
  * summary out; [[RatingReplayMain]] is the file-reading shell.
  *
  * '''Input is a corpus file, not the database.''' The corpus is a versioned, immutable JSONL export (one object per
  * `game_results` row, see [[RatingReplay.Game]]) in which human and guest identities are HMAC pseudonyms, so the same
  * file can be replayed anywhere without carrying account identifiers. Bots keep their public `team/name` ids. The
  * synthetic fixture under `src/test/resources/rating-replay/` has the same shape and is the only corpus this
  * repository ships; the production corpus and its hashes live in the private knowledge base.
  *
  * '''What is replayed and what is merely recorded.''' Eligibility mirrors `RatingBatch.applyGame` rule for rule
  * (unresolvable participant, self-play, no definite result, a player against a bot they own, uncategorised control),
  * each with the batch's own skip reason so tallies can be read against its logs. Two knobs let the replay stand in for
  * the implementation as it was, not only as it is: [[Scale.SingleUntil]] models the one-scale-then-per-category
  * history the corpus shows (category tables were seeded from the single Glicko-2 state, see `V2`'s narrative), and
  * [[Tau]] models the τ = 0.5 → 0.3 change (#169). Neither is inferred from the data by this code — they are inputs,
  * and the report says which were used.
  *
  * '''Verdicts, per game.''' A row that recorded both seats' before/after ratings is compared against the replayed pair
  * at `tolerance`: [[Verdict.Match]] when the levels agree, [[Verdict.MatchStepOnly]] when only the step (after −
  * before) does — a whole pool sitting a constant away from the replay reproduces every step, since a Glicko-2 update
  * depends on rating differences — and [[Verdict.Mismatch]] otherwise. A row the batch stamped without numbers is
  * either [[Verdict.UnverifiablePreNumeric]] — it predates the row-level recording (#296), so nothing can be checked —
  * or a skip, which is [[Verdict.SkipConsistent]] when the replay skips it too and
  * [[Verdict.ReplayAppliedRecordedSkipped]] otherwise. A recorded update the replay refuses is
  * [[Verdict.ReplaySkippedRecordedApplied]]; a rated row the batch had not reached at the cutoff is
  * [[Verdict.Pending]]; a casual row never enters the queue and is [[Verdict.Casual]]. The report distinguishes
  * arithmetic (does the formula reproduce the recorded step from the recorded pre-game ratings?) from data lineage
  * (does the chain of before/after values hold in the order the batch applied them?) because ADR 008 asks for exactly
  * that separation: mathematical behaviour versus data bugs.
  */
object RatingReplay:

  /** The corpus is snake_case JSON with nullable fields, the same conventions as the Bot API wire. */
  given Configuration = Configuration.default.withSnakeCaseMemberNames.withDefaults

  /** One seat of one game as exported: the (pseudonymous) identity, its kind (`bot`, `human`, `guest`), whether it
    * resolves to rating state in the tables AS OF the export (a deleted bot or account does not), and the rating the
    * batch recorded on the row for this seat, when it recorded any.
    */
  final case class Seat(
      id: String,
      kind: String,
      resolvableNow: Boolean,
      ratingBefore: Option[Double],
      ratingAfter: Option[Double]
  ) derives ConfiguredCodec

  /** One `game_results` row. `seqFinished`/`seqApplied` are the export's own row numbers in `finished_at` and
    * `rating_applied_at` order, so a replay is reproducible without re-sorting timestamps that may tie.
    * `numericRecorded` is `rating_after IS NOT NULL` on the row; `applied` is `rating_applied_at IS NOT NULL` — the two
    * genuinely differ (a skipped game is applied without numbers). `ownerRelation` is `white_owns_black` or
    * `black_owns_white` when one seat is an account and the other a bot it owns, resolved by the export against the
    * `bots` table; the replay never derives ownership from names.
    */
  final case class Game(
      gameId: String,
      finishedAt: Instant,
      ratingAppliedAt: Option[Instant],
      seqFinished: Long,
      seqApplied: Option[Long],
      category: Option[String],
      timeControl: String,
      result: Option[Int],
      termination: String,
      rated: Boolean,
      origin: String,
      ladder: Boolean,
      pairingId: Option[String],
      applied: Boolean,
      numericRecorded: Boolean,
      white: Seat,
      black: Seat,
      ownerRelation: Option[String],
      hasArchive: Boolean,
      archiveSportingEligible: Option[Boolean]
  ) derives ConfiguredCodec:
    /** The instant the batch acted on this row, or the finish for a row it had not reached — the clock the era knobs
      * ([[Scale]], [[Tau]]) are read against, since both describe the implementation that PROCESSED the game.
      */
    def processedAt: Instant = ratingAppliedAt.getOrElse(finishedAt)

  /** A stored Glicko-2 triple, as the per-category tables hold it (`rating`, `rd`, `vol`). */
  final case class GlickoSnapshot(rating: Double, rd: Double, vol: Double) derives ConfiguredCodec

  object GlickoSnapshot:
    def of(g: Glicko): GlickoSnapshot = GlickoSnapshot(g.rating, g.deviation, g.volatility)

  /** One participant as exported: the identity, its kind, and the rating state the tables held at the cutoff — what the
    * replayed final state is compared against. Bots carry more (team, name, ladder flag, owner pseudonym); the
    * comparison needs only these.
    */
  final case class Participant(
      kind: String,
      id: String,
      ratings: Map[String, GlickoSnapshot] = Map.empty,
      onLadder: Option[Boolean] = None,
      owner: Option[String] = None,
      isActive: Option[Boolean] = None
  ) derives ConfiguredCodec

  /** In which order the corpus is folded: `Applied` is the order the batch actually processed rows (its own
    * `rating_applied_at` stamps; a casual or still-pending row sits at its finish instant), `Finished` is pure game
    * chronology. The batch drains its queue in `finished_at` order, so the two differ only where a backlog or a tie
    * reordered rows — and the report counts those (`Integrity.displacedRows`).
    */
  enum Order:
    case Applied, Finished

  /** Which scale a game's update lands on. `PerCategory` is the current implementation (#280): one state per speed,
    * fresh at `Glicko.Initial`. `SingleUntil(switchAt, seeded)` is the history: one state per identity regardless of
    * speed until `switchAt`, after which each category in `seeded` starts from that single state on the identity's
    * first game there and every other category starts fresh — the cutover the production corpus shows (Blitz carried
    * the shared rating over, Rapid restarted at 1500). `seeded = all` seeds every category.
    */
  enum Scale:
    case PerCategory
    case SingleUntil(switchAt: Instant, seeded: Set[RatingCategory] = RatingCategory.values.toSet)

  /** How a seat's identity is resolved. `Current` is what today's batch would do — a bot or account no longer in the
    * tables has no rating state and its game is skipped. `Lenient` treats every `bot:team:` id as the registered bot it
    * was when the game was played, which is how the batch of the day saw it; it cannot fabricate an account, so humans
    * still need to resolve.
    */
  enum Resolution:
    case Current, Lenient

  /** The volatility constant per era: `before` until `switchAt`, `after` from then on (both `after` when there is no
    * switch). #169 moved τ from 0.5 to 0.3 for future updates only, so a faithful replay of the early corpus needs
    * both.
    */
  final case class Tau(
      after: Double = Glicko2.DefaultTau,
      before: Double = Glicko2.DefaultTau,
      switchAt: Option[Instant] = None
  ):
    def at(instant: Instant): Double = switchAt match
      case Some(switch) if instant.isBefore(switch) => before
      case _                                        => after

  final case class Config(
      order: Order = Order.Applied,
      scale: Scale = Scale.PerCategory,
      resolution: Resolution = Resolution.Current,
      tau: Tau = Tau(),
      /** Absolute rating difference at or below which a replayed value counts as reproducing the recorded one. The
        * recorded columns are `double precision` written from the same arithmetic, so agreement is exact up to platform
        * ulps; 1e-6 leaves room for an aarch64/x86 `exp`/`log` disagreement without hiding a real drift.
        */
      tolerance: Double = 1e-6,
      /** Days without a game after which an identity counts as inactive for the drift and offset tables. */
      inactiveAfterDays: Int = 7,
      /** Follow the batch of the day instead of today's rules for every row it stamped in the numeric era: apply
        * exactly the rows it recorded numbers for, skip exactly the rows it stamped without numbers. Rows before the
        * numeric era, pending rows and casual rows still go through `resolution`. This is the mode that isolates
        * arithmetic and lineage from eligibility-rule changes: with it, every remaining difference is a number the
        * production batch computed differently, not a game it chose differently.
        */
      followRecorded: Boolean = false
  )

  /** Why a queued row moved no rating — the batch's own vocabulary (`RatingBatch.applyGame`), reused verbatim. */
  object SkipReason:
    val Unresolvable  = "a participant has no rating state (a guest, an unregistered bot, or a deleted account)"
    val SelfPlay      = "self-play carries no rating information"
    val NoResult      = "no definite result"
    val OwnBot        = "a player's game against their own bot is never rated"
    val Uncategorised = "uncategorised time control belongs to no rating scale"

    /** `followRecorded` only: the batch of the day stamped the row without numbers, so the replay skips it too. */
    val RecordedSkip = "stamped without a rating movement by the batch of the day (followed as recorded)"

    /** `followRecorded` only: numbers were recorded on a row that has no result or no scale — nothing to replay. */
    val RecordedWithoutOutcome = "numbers recorded on a row without a definite result or a rating scale"

  enum Decision:
    /** Both seats updated. */
    case Applied

    /** `rated = false`: never enters the batch's queue. */
    case Casual

    /** Queued and stamped, but no rating moved. */
    case Skipped(why: String)

    def label: String = this match
      case Applied    => "applied"
      case Casual     => "casual"
      case Skipped(_) => "skipped"

    def reason: Option[String] = this match
      case Skipped(why) => Some(why)
      case _            => None

  enum Verdict:
    case Match, MatchStepOnly, Mismatch, UnverifiablePreNumeric, SkipConsistent, ReplaySkippedRecordedApplied,
      ReplayAppliedRecordedSkipped, Pending, Casual

    def label: String = this match
      case Match                        => "match"
      case MatchStepOnly                => "match_step_only"
      case Mismatch                     => "mismatch"
      case UnverifiablePreNumeric       => "unverifiable_pre_numeric"
      case SkipConsistent               => "skip_consistent"
      case ReplaySkippedRecordedApplied => "replay_skipped_recorded_applied"
      case ReplayAppliedRecordedSkipped => "replay_applied_recorded_skipped"
      case Pending                      => "pending"
      case Casual                       => "casual"

  final case class SeatOutcome(id: String, before: Glicko, after: Glicko, score: Double, expected: Double)

  /** One replayed row: the decision, both seats' replayed step when applied, the verdict against the recorded numbers,
    * and — for a numeric row — how far the replayed pre-game and post-game ratings sit from the recorded ones
    * (`beforeDiff`/`afterDiff`, max over both seats), how far the replayed STEP (after − before) sits from the recorded
    * step (`stepDiff`), and whether the recorded step itself is reproduced by the formula from the recorded pre-game
    * ratings (`formulaHolds`, `None` when there was nothing to check). A Glicko-2 step depends on rating DIFFERENCES,
    * so a pool whose every rating sits a constant away from the replay reproduces every step while matching no level:
    * that is [[Verdict.MatchStepOnly]], and it is why `stepDiff` is judged separately. The row never stored RD or
    * volatility, so the formula check borrows the replay's; it isolates arithmetic only while the replayed chain is
    * intact — a lineage break that also moved the deviation fails it too, which is why the report reads it alongside
    * `beforeDiff`.
    */
  final case class Outcome(
      game: Game,
      category: Option[RatingCategory],
      decision: Decision,
      white: Option[SeatOutcome],
      black: Option[SeatOutcome],
      verdict: Verdict,
      beforeDiff: Option[Double],
      afterDiff: Option[Double],
      stepDiff: Option[Double],
      formulaHolds: Option[Boolean]
  )

  // ── the fold ───────────────────────────────────────────────────────────────

  /** The scale a game counts on, from the STORED control exactly as the batch reads it — never from the export's
    * `category` column, which is the database's own view of the same rule and is carried for cross-checking only.
    */
  def categoryOf(game: Game): Option[RatingCategory] = RatingCategory.ofStored(game.timeControl)

  private def resolves(seat: Seat, resolution: Resolution): Boolean = seat.kind match
    case "bot"   => resolution == Resolution.Lenient || seat.resolvableNow
    case "human" => seat.resolvableNow
    case _       => false

  /** `RatingBatch.applyGame`'s decision tree for one QUEUED (rated) row, as a pure function of the corpus row. */
  def decide(game: Game, resolution: Resolution): Either[String, (RatingCategory, Double, Double)] =
    if !resolves(game.white, resolution) || !resolves(game.black, resolution) then Left(SkipReason.Unresolvable)
    else if game.white.id == game.black.id then Left(SkipReason.SelfPlay)
    else
      game.result.flatMap(RatingBatch.scores) match
        case None                           => Left(SkipReason.NoResult)
        case Some((whiteScore, blackScore)) =>
          if game.ownerRelation.isDefined then Left(SkipReason.OwnBot)
          else
            categoryOf(game) match
              case None           => Left(SkipReason.Uncategorised)
              case Some(category) => Right((category, whiteScore, blackScore))

  /** Replay the whole corpus. Games are folded in `config.order`; the result keeps that order. */
  def replay(games: Seq[Game], config: Config): Vector[Outcome] =
    val ordered = config.order match
      case Order.Finished => games.sortBy(_.seqFinished)
      // The stamp instant merges casual and pending rows (which have none) into their finish position; the export's
      // finish sequence breaks ties deterministically.
      case Order.Applied => games.sortBy(g => (g.processedAt.getEpochSecond, g.processedAt.getNano, g.seqFinished))
    val numericSince = games.iterator.filter(_.numericRecorded).map(_.finishedAt).minOption
    val state        = new State(config.scale)
    val out          = Vector.newBuilder[Outcome]
    ordered.foreach { game =>
      out += step(game, state, config, numericSince)
    }
    out.result()

  /** Rating state keyed by identity and scale (`None` = the historical single scale). Mutable and private to one
    * [[replay]] call, so the function stays pure from outside.
    */
  final private class State(scale: Scale):
    private val ratings = mutable.HashMap.empty[(String, Option[RatingCategory]), Glicko]

    private def key(id: String, category: RatingCategory, at: Instant): (String, Option[RatingCategory]) =
      scale match
        case Scale.PerCategory                                       => (id, Some(category))
        case Scale.SingleUntil(switchAt, _) if at.isBefore(switchAt) => (id, None)
        case Scale.SingleUntil(_, _)                                 => (id, Some(category))

    /** The pre-game state, seeding a category from the single scale on first use after the switch when the scale model
      * says that category carried the shared rating over; every other absence is the fresh `Glicko.Initial`.
      */
    def read(id: String, category: RatingCategory, at: Instant): Glicko =
      val k = key(id, category, at)
      ratings.get(k) match
        case Some(g) => g
        case None    =>
          val seeded = (k._2, scale) match
            case (Some(cat), Scale.SingleUntil(_, seededCategories)) if seededCategories.contains(cat) =>
              ratings.getOrElse((id, None), Glicko.Initial)
            case _ => Glicko.Initial
          ratings.update(k, seeded)
          seeded

    def write(id: String, category: RatingCategory, at: Instant, glicko: Glicko): Unit =
      ratings.update(key(id, category, at), glicko)

  /** The decision for one queued row under `config`: the batch of the day's own, when following the record inside the
    * numeric era, and today's rules ([[decide]]) everywhere else.
    */
  private def decideUnder(
      game: Game,
      config: Config,
      numericSince: Option[Instant]
  ): Either[String, (RatingCategory, Double, Double)] =
    val numericEra = numericSince.exists(since => !game.finishedAt.isBefore(since))
    if config.followRecorded && numericEra && game.applied then
      if game.numericRecorded then
        (categoryOf(game), game.result.flatMap(RatingBatch.scores)) match
          case (Some(cat), Some((whiteScore, blackScore))) => Right((cat, whiteScore, blackScore))
          case _                                           => Left(SkipReason.RecordedWithoutOutcome)
      else Left(SkipReason.RecordedSkip)
    else decide(game, config.resolution)

  private def step(game: Game, state: State, config: Config, numericSince: Option[Instant]): Outcome =
    val category = categoryOf(game)
    if !game.rated then Outcome(game, category, Decision.Casual, None, None, Verdict.Casual, None, None, None, None)
    else
      decideUnder(game, config, numericSince) match
        case Left(reason) =>
          val decision = Decision.Skipped(reason)
          val verdict  = verdictFor(game, decision, numericSince)
          Outcome(game, category, decision, None, None, verdict, None, None, None, None)
        case Right((cat, whiteScore, blackScore)) =>
          apply(game, cat, whiteScore, blackScore, state, config, numericSince)

  /** Update both seats from their PRE-game states — the batch's simultaneous treatment — and judge the row. */
  private def apply(
      game: Game,
      category: RatingCategory,
      whiteScore: Double,
      blackScore: Double,
      state: State,
      config: Config,
      numericSince: Option[Instant]
  ): Outcome =
    val at          = game.processedAt
    val tau         = config.tau.at(at)
    val whiteBefore = state.read(game.white.id, category, at)
    val blackBefore = state.read(game.black.id, category, at)
    val whiteAfter  = Glicko2.update(whiteBefore, List(Glicko2.Result(blackBefore, whiteScore)), tau)
    val blackAfter  = Glicko2.update(blackBefore, List(Glicko2.Result(whiteBefore, blackScore)), tau)
    state.write(game.white.id, category, at, whiteAfter)
    state.write(game.black.id, category, at, blackAfter)
    val white      = SeatOutcome(game.white.id, whiteBefore, whiteAfter, whiteScore, expected(whiteBefore, blackBefore))
    val black      = SeatOutcome(game.black.id, blackBefore, blackAfter, blackScore, expected(blackBefore, whiteBefore))
    val comparison = if game.numericRecorded then compare(game, white, black, tau, config.tolerance) else None
    val verdict    =
      if !game.numericRecorded then verdictFor(game, Decision.Applied, numericSince)
      // A numeric row that cannot be compared (a recorded value missing on one side) is a data inconsistency, not a
      // reproduction — `Integrity.oneSidedNumeric` counts it too.
      else comparison.fold(Verdict.Mismatch)(_.verdict(config.tolerance))
    Outcome(
      game,
      Some(category),
      Decision.Applied,
      Some(white),
      Some(black),
      verdict,
      comparison.map(_.before),
      comparison.map(_.after),
      comparison.map(_.step),
      comparison.map(_.formulaHolds)
    )

  /** The replayed step of one numeric row held against the recorded one — see [[Outcome]] for the three distances. */
  final private case class Comparison(before: Double, after: Double, step: Double, formulaHolds: Boolean):
    def verdict(tolerance: Double): Verdict =
      if math.max(before, after) <= tolerance then Verdict.Match
      else if step <= tolerance then Verdict.MatchStepOnly
      else Verdict.Mismatch

  private def compare(
      game: Game,
      white: SeatOutcome,
      black: SeatOutcome,
      tau: Double,
      tolerance: Double
  ): Option[Comparison] =
    for
      rwb <- game.white.ratingBefore
      rwa <- game.white.ratingAfter
      rbb <- game.black.ratingBefore
      rba <- game.black.ratingAfter
    yield
      val before = math.max(math.abs(rwb - white.before.rating), math.abs(rbb - black.before.rating))
      val after  = math.max(math.abs(rwa - white.after.rating), math.abs(rba - black.after.rating))
      val step   = math.max(
        math.abs((rwa - rwb) - (white.after.rating - white.before.rating)),
        math.abs((rba - rbb) - (black.after.rating - black.before.rating))
      )
      // The formula check: the recorded step from the RECORDED pre-game ratings, with the replay's RD and volatility
      // (the row never stored those). Holding while `before` differs means the arithmetic is right and the lineage
      // is not; failing while `before` matches means the opposite.
      val recordedWhite = Glicko(rwb, white.before.deviation, white.before.volatility)
      val recordedBlack = Glicko(rbb, black.before.deviation, black.before.volatility)
      val fw            = Glicko2.update(recordedWhite, List(Glicko2.Result(recordedBlack, white.score)), tau)
      val fb            = Glicko2.update(recordedBlack, List(Glicko2.Result(recordedWhite, black.score)), tau)
      Comparison(before, after, step, math.abs(fw.rating - rwa) <= tolerance && math.abs(fb.rating - rba) <= tolerance)

  /** The verdict for a rated row WITHOUT a full numeric comparison (skips, pre-numeric rows, pending rows). */
  private def verdictFor(game: Game, decision: Decision, numericSince: Option[Instant]): Verdict =
    if !game.applied then Verdict.Pending
    else if game.numericRecorded then
      // Only reachable for a replay skip: an applied replay of a numeric row is judged by its diffs in `step`.
      Verdict.ReplaySkippedRecordedApplied
    else if numericSince.forall(since => game.finishedAt.isBefore(since)) then Verdict.UnverifiablePreNumeric
    else
      decision match
        case Decision.Applied => Verdict.ReplayAppliedRecordedSkipped
        case _                => Verdict.SkipConsistent

  /** Glickman's expected score E(μ, μj, φj) on the public scale — the paper's own formula (with g(φj) damping the
    * opponent's uncertainty), kept here rather than exposed from [[Glicko2]] because only the report needs it.
    */
  private def expected(player: Glicko, opponent: Glicko): Double =
    val scale = 173.7178
    val mu    = (player.rating - 1500.0) / scale
    val muJ   = (opponent.rating - 1500.0) / scale
    val phiJ  = opponent.deviation / scale
    val g     = 1.0 / math.sqrt(1.0 + 3.0 * phiJ * phiJ / (math.Pi * math.Pi))
    1.0 / (1.0 + math.exp(-g * (mu - muJ)))

  // ── the report ─────────────────────────────────────────────────────────────

  final case class ConfigSummary(
      order: String,
      scale: String,
      resolution: String,
      tauAfter: Double,
      tauBefore: Double,
      tauSwitchAt: Option[Instant],
      tolerance: Double,
      inactiveAfterDays: Int,
      followRecorded: Boolean
  ) derives ConfiguredCodec

  final case class ClassCount(category: String, verdict: String, count: Long) derives ConfiguredCodec
  final case class SkipCount(reason: String, count: Long, recordedNumeric: Long) derives ConfiguredCodec

  /** The rows whose level the replay did not reproduce, per category and verdict (`mismatch` or `match_step_only`): how
    * far off, whether the per-row arithmetic still held, and when they start and end.
    */
  final case class MismatchStat(
      category: String,
      verdict: String,
      count: Long,
      maxBeforeDiff: Double,
      maxAfterDiff: Double,
      maxStepDiff: Double,
      formulaHolds: Long,
      firstFinishedAt: Option[Instant],
      lastFinishedAt: Option[Instant]
  ) derives ConfiguredCodec

  /** Σ over applied games of (Δwhite + Δblack): Glicko-2 steps are not zero-sum, and this is the number ADR 008 asks
    * for, per category, both as replayed and as production recorded it (numeric rows only).
    */
  final case class DeltaSums(
      category: String,
      replayedGames: Long,
      replayedPairDeltaSum: Double,
      recordedGames: Long,
      recordedPairDeltaSum: Double,
      /** The replayed sum over exactly the rows that carry recorded numbers — the like-for-like comparison. */
      replayedOnRecordedRows: Double
  ) derives ConfiguredCodec

  /** Cumulative replayed pair-delta sum at the end of each UTC day, per category. Days are UTC dates of `finishedAt`
    * and strictly increasing; a row the batch applied out of finish order counts on the latest open day.
    */
  final case class DeltaPoint(day: String, category: String, games: Long, cumulativePairDeltaSum: Double)
      derives ConfiguredCodec

  /** The pool at the end of one UTC day: identities that played that day (`active`), identities rated on this scale
    * whose last game is older than `inactiveAfterDays` (`inactive`), and everyone rated on the scale.
    */
  final case class DriftPoint(
      day: String,
      category: String,
      activeCount: Int,
      activeMean: Option[Double],
      inactiveCount: Int,
      inactiveMean: Option[Double],
      allCount: Int,
      allMean: Double
  ) derives ConfiguredCodec

  /** An identity that stopped playing on a scale: its frozen rating, the active pool's mean the day it last played, and
    * the active pool's mean at the cutoff — `offset` is how far the live scale moved after it left.
    */
  final case class InactiveOffset(
      id: String,
      category: String,
      games: Long,
      lastFinishedAt: Instant,
      rating: Double,
      rd: Double,
      activeMeanThen: Option[Double],
      activeMeanAtCutoff: Option[Double],
      offset: Option[Double]
  ) derives ConfiguredCodec

  /** One human identity on one scale: volume, final state, and how the actual score compares with what the ratings
    * predicted along the way — a positive `surplus` means the account kept beating its own rating, i.e. the rating
    * lagged.
    */
  final case class HumanLag(
      id: String,
      category: String,
      games: Long,
      firstFinishedAt: Instant,
      lastFinishedAt: Instant,
      finalRating: Double,
      finalRd: Double,
      gamesToConverge: Option[Int],
      meanOpponentRating: Double,
      actualScore: Double,
      expectedScore: Double,
      surplus: Double
  ) derives ConfiguredCodec

  /** Replayed final state versus the table snapshot at the cutoff, per identity and scale — over the rows the batch had
    * stamped by then; a pending row is replayed but cannot be in the snapshot, so it is left out here.
    */
  final case class FinalDiff(
      id: String,
      category: String,
      games: Long,
      replayed: GlickoSnapshot,
      snapshot: Option[GlickoSnapshot],
      ratingDiff: Option[Double]
  ) derives ConfiguredCodec

  /** How far the replayed numbers sit from the recorded ones on numeric rows the replay applied, per category: `level`
    * is max(|before|, |after|) over both seats, `step` is the max |Δ| difference. Bins are cumulative-exclusive upper
    * bounds (a row lands in the first bin whose `upTo` it does not exceed), so the same row appears once.
    */
  final case class DiffBin(upTo: Double, count: Long) derives ConfiguredCodec
  final case class DiffHistogram(category: String, kind: String, rows: Long, bins: List[DiffBin])
      derives ConfiguredCodec

  /** Structural checks that need no rating arithmetic. `oneSidedNumeric` counts rows where either recorded pair (before
    * or after) is present for one seat only — the rows [[Outcome]] can only call a mismatch; `displacedRows` counts
    * applied rated rows whose position in apply order differs from their position in finish order; `formulaMismatches`
    * counts numeric rows whose recorded step is NOT reproduced from the recorded pre-game ratings (see
    * [[Outcome.formulaHolds]]).
    */
  final case class Integrity(
      rows: Long,
      duplicateGameIds: Long,
      oneSidedNumeric: Long,
      numericWithoutStamp: Long,
      pendingRows: Long,
      displacedRows: Long,
      formulaChecked: Long,
      formulaMismatches: Long,
      categoryColumnDisagreements: Long
  ) derives ConfiguredCodec

  /** Where a report came from (#145's "hashes, code revision, environment"): the exact input bytes, the code that
    * replayed them and the runtime it ran on. Filled by the runner — the pure [[summarize]] knows none of it — so an
    * archived `summary.json` identifies its inputs without the shell transcript that produced it.
    */
  final case class Provenance(
      corpusFile: String,
      corpusSha256: String,
      participantsFile: Option[String],
      participantsSha256: Option[String],
      /** The implementation revision, when the operator names it (`revision=<git sha>`); a jar carries none. */
      codeRevision: Option[String],
      javaRuntime: String,
      osArch: String,
      scalaVersion: String,
      ranAt: Instant
  ) derives ConfiguredCodec

  final case class Summary(
      config: ConfigSummary,
      provenance: Option[Provenance] = None,
      games: Long,
      firstFinishedAt: Option[Instant],
      lastFinishedAt: Option[Instant],
      numericRecordedSince: Option[Instant],
      classes: List[ClassCount],
      skips: List[SkipCount],
      mismatches: List[MismatchStat],
      histograms: List[DiffHistogram],
      deltaSums: List[DeltaSums],
      deltaSeries: List[DeltaPoint],
      drift: List[DriftPoint],
      inactiveOffsets: List[InactiveOffset],
      humans: List[HumanLag],
      finals: List[FinalDiff],
      integrity: Integrity
  ) derives ConfiguredCodec

  /** One ledger line per row: the exclusion/provenance record. */
  final case class LedgerEntry(
      gameId: String,
      seqFinished: Long,
      seqApplied: Option[Long],
      finishedAt: Instant,
      category: Option[String],
      decision: String,
      reason: Option[String],
      verdict: String,
      whiteId: String,
      blackId: String,
      whiteBefore: Option[GlickoSnapshot],
      whiteAfter: Option[GlickoSnapshot],
      blackBefore: Option[GlickoSnapshot],
      blackAfter: Option[GlickoSnapshot],
      recordedWhiteBefore: Option[Double],
      recordedWhiteAfter: Option[Double],
      recordedBlackBefore: Option[Double],
      recordedBlackAfter: Option[Double],
      beforeDiff: Option[Double],
      afterDiff: Option[Double],
      stepDiff: Option[Double],
      formulaHolds: Option[Boolean]
  ) derives ConfiguredCodec

  def ledgerEntry(o: Outcome): LedgerEntry =
    LedgerEntry(
      gameId = o.game.gameId,
      seqFinished = o.game.seqFinished,
      seqApplied = o.game.seqApplied,
      finishedAt = o.game.finishedAt,
      category = o.category.map(_.wireName),
      decision = o.decision.label,
      reason = o.decision.reason,
      verdict = o.verdict.label,
      whiteId = o.game.white.id,
      blackId = o.game.black.id,
      whiteBefore = o.white.map(s => GlickoSnapshot.of(s.before)),
      whiteAfter = o.white.map(s => GlickoSnapshot.of(s.after)),
      blackBefore = o.black.map(s => GlickoSnapshot.of(s.before)),
      blackAfter = o.black.map(s => GlickoSnapshot.of(s.after)),
      recordedWhiteBefore = o.game.white.ratingBefore,
      recordedWhiteAfter = o.game.white.ratingAfter,
      recordedBlackBefore = o.game.black.ratingBefore,
      recordedBlackAfter = o.game.black.ratingAfter,
      beforeDiff = o.beforeDiff,
      afterDiff = o.afterDiff,
      stepDiff = o.stepDiff,
      formulaHolds = o.formulaHolds
    )

  private def dayOf(instant: Instant): String = LocalDate.ofInstant(instant, ZoneOffset.UTC).toString

  private def categoryLabel(c: Option[RatingCategory]): String = c.map(_.wireName).getOrElse("none")

  private def mean(values: Iterable[Double]): Option[Double] =
    if values.isEmpty then None else Some(values.sum / values.size)

  /** Build the report from replayed outcomes (in fold order) and the participant snapshot. */
  def summarize(outcomes: Vector[Outcome], participants: Seq[Participant], config: Config): Summary =
    val games = outcomes.map(_.game)

    val classes = outcomes
      .groupBy(o => (categoryLabel(o.category), o.verdict.label))
      .map { case ((cat, verdict), os) => ClassCount(cat, verdict, os.size.toLong) }
      .toList
      .sortBy(c => (c.category, c.verdict))

    val skips = outcomes
      .collect { case o if o.decision.reason.isDefined => o }
      .groupBy(_.decision.reason.get)
      .map { case (reason, os) => SkipCount(reason, os.size.toLong, os.count(_.game.numericRecorded).toLong) }
      .toList
      .sortBy(-_.count)

    val mismatches = outcomes
      .filter(o => o.verdict == Verdict.Mismatch || o.verdict == Verdict.MatchStepOnly)
      .groupBy(o => (categoryLabel(o.category), o.verdict.label))
      .map { case ((cat, verdict), os) =>
        MismatchStat(
          cat,
          verdict,
          os.size.toLong,
          os.flatMap(_.beforeDiff).maxOption.getOrElse(0.0),
          os.flatMap(_.afterDiff).maxOption.getOrElse(0.0),
          os.flatMap(_.stepDiff).maxOption.getOrElse(0.0),
          os.count(_.formulaHolds.contains(true)).toLong,
          os.map(_.game.finishedAt).minOption,
          os.map(_.game.finishedAt).maxOption
        )
      }
      .toList
      .sortBy(m => (m.category, m.verdict))

    val applied = outcomes.filter(_.decision == Decision.Applied)

    val histograms =
      val bounds = List(1e-6, 1e-4, 1e-2, 0.1, 1.0, 10.0, 100.0, Double.PositiveInfinity)
      def histogram(cat: String, kind: String, values: Vector[Double]): DiffHistogram =
        val counts = bounds.map(upTo => DiffBin(upTo, values.count(v => v <= upTo).toLong))
        // Cumulative counts → per-bin counts, so each row is in exactly one bin.
        val perBin =
          counts.zip(0L +: counts.map(_.count)).map { case (bin, below) => bin.copy(count = bin.count - below) }
        DiffHistogram(cat, kind, values.size.toLong, perBin)
      applied
        .filter(o => o.game.numericRecorded && o.beforeDiff.isDefined)
        .groupBy(o => categoryLabel(o.category))
        .toList
        .sortBy(_._1)
        .flatMap { case (cat, os) =>
          List(
            histogram(cat, "level", os.map(o => math.max(o.beforeDiff.get, o.afterDiff.get))),
            histogram(cat, "step", os.flatMap(_.stepDiff))
          )
        }

    def pairDelta(o: Outcome): Double =
      (for w <- o.white; b <- o.black yield (w.after.rating - w.before.rating) + (b.after.rating - b.before.rating))
        .getOrElse(0.0)

    def recordedPairDelta(g: Game): Option[Double] =
      for
        wb <- g.white.ratingBefore; wa <- g.white.ratingAfter
        bb <- g.black.ratingBefore; ba <- g.black.ratingAfter
      yield (wa - wb) + (ba - bb)

    val deltaSums = (applied.map(o => categoryLabel(o.category)) ++ games
      .filter(_.numericRecorded)
      .map(g => categoryLabel(categoryOf(g)))).distinct.sorted.map { cat =>
      val replayed = applied.filter(o => categoryLabel(o.category) == cat)
      val recorded =
        games.filter(g => g.numericRecorded && categoryLabel(categoryOf(g)) == cat).flatMap(recordedPairDelta)
      DeltaSums(
        cat,
        replayed.size.toLong,
        replayed.map(pairDelta).sum,
        recorded.size.toLong,
        recorded.sum,
        replayed.filter(_.game.numericRecorded).map(pairDelta).sum
      )
    }

    // Daily series: cumulative pair-delta sum and the pool drift, per category, at the end of each UTC day.
    val deltaSeries = List.newBuilder[DeltaPoint]
    val drift       = List.newBuilder[DriftPoint]
    val cumulative  = mutable.HashMap.empty[String, (Long, Double)]
    val current     = mutable.HashMap.empty[(String, RatingCategory), Glicko]
    // The state the tables could hold at the cutoff: everything the batch had stamped. Rows it had not reached
    // (pending) are replayed for the ledger but cannot be in the snapshot, so they stay out of this map.
    val settled             = mutable.HashMap.empty[(String, RatingCategory), Glicko]
    val settledGames        = mutable.HashMap.empty[(String, RatingCategory), Long]
    val lastPlayed          = mutable.HashMap.empty[(String, RatingCategory), Instant]
    val gamesCount          = mutable.HashMap.empty[(String, RatingCategory), Long]
    val playedToday         = mutable.HashSet.empty[(String, RatingCategory)]
    val activeMeanByDay     = mutable.HashMap.empty[(String, String), Option[Double]] // (day, category) -> active mean
    var day: Option[String] = None

    def closeDay(d: String): Unit =
      val cats = current.keysIterator.map(_._2).toSet
      cats.toList.sortBy(_.wireName).foreach { cat =>
        val all      = current.iterator.filter(_._1._2 == cat).toList
        val active   = all.filter(kv => playedToday.contains(kv._1)).map(_._2.rating)
        val dayEnd   = LocalDate.parse(d).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant
        val inactive = all
          .filter(kv => lastPlayed(kv._1).plusSeconds(config.inactiveAfterDays.toLong * 86400L).isBefore(dayEnd))
          .map(_._2.rating)
        val activeMean = mean(active)
        activeMeanByDay.update((d, cat.wireName), activeMean)
        drift += DriftPoint(
          d,
          cat.wireName,
          active.size,
          activeMean,
          inactive.size,
          mean(inactive),
          all.size,
          all.map(_._2.rating).sum / all.size
        )
        val (n, sum) = cumulative.getOrElse(cat.wireName, (0L, 0.0))
        deltaSeries += DeltaPoint(d, cat.wireName, n, sum)
      }
      playedToday.clear()

    // Human lag accumulators.
    final case class HumanAcc(
        var games: Long,
        var first: Instant,
        var last: Instant,
        var actual: Double,
        var expected: Double,
        var opponentSum: Double,
        var converged: Option[Int],
        var finalState: Glicko
    )
    val humans = mutable.HashMap.empty[(String, RatingCategory), HumanAcc]

    applied.foreach { o =>
      val cat = o.category.get
      val d   = dayOf(o.game.finishedAt)
      // The day cursor only ever advances. Under `Order.Applied` the fold order is the batch's, and a displaced row
      // (`Integrity.displacedRows`) can carry a `finishedAt` from a day already closed; it is attributed to the latest
      // open day rather than reopening a closed one, so every `(day, category)` appears once and in order.
      day match
        case Some(prev) if LocalDate.parse(prev).isBefore(LocalDate.parse(d)) =>
          // Close every day between prev and d that had no games too, so the series has no gaps.
          var cursor = LocalDate.parse(prev)
          val target = LocalDate.parse(d)
          while cursor.isBefore(target) do
            closeDay(cursor.toString)
            cursor = cursor.plusDays(1)
          day = Some(d)
        case None => day = Some(d)
        case _    => ()
      val (n, sum) = cumulative.getOrElse(cat.wireName, (0L, 0.0))
      cumulative.update(cat.wireName, (n + 1, sum + pairDelta(o)))
      List((o.white, o.game.white, o.black), (o.black, o.game.black, o.white)).foreach {
        case (Some(seat), exported, Some(opponent)) =>
          val k = (seat.id, cat)
          current.update(k, seat.after)
          if o.game.applied then
            settled.update(k, seat.after)
            settledGames.update(k, settledGames.getOrElse(k, 0L) + 1)
          lastPlayed.update(k, o.game.finishedAt)
          gamesCount.update(k, gamesCount.getOrElse(k, 0L) + 1)
          playedToday += k
          if exported.kind == "human" then
            val acc = humans.getOrElseUpdate(
              k,
              HumanAcc(0L, o.game.finishedAt, o.game.finishedAt, 0.0, 0.0, 0.0, None, seat.after)
            )
            acc.games += 1
            acc.last = o.game.finishedAt
            acc.actual += seat.score
            acc.expected += seat.expected
            acc.opponentSum += opponent.before.rating
            acc.finalState = seat.after
            if acc.converged.isEmpty && seat.after.deviation <= Glicko2.ProvisionalDeviationThreshold then
              acc.converged = Some(acc.games.toInt)
        case _ => ()
      }
    }
    day.foreach(closeDay)

    val cutoff          = games.map(_.finishedAt).maxOption
    val inactiveOffsets = current.toList
      .filter { case (k, _) =>
        cutoff.exists(c => lastPlayed(k).plusSeconds(config.inactiveAfterDays.toLong * 86400L).isBefore(c))
      }
      .map { case ((id, cat), g) =>
        val last     = lastPlayed((id, cat))
        val thenMean = activeMeanByDay.get((dayOf(last), cat.wireName)).flatten
        val nowMean  = day.flatMap(d => activeMeanByDay.get((d, cat.wireName)).flatten)
        InactiveOffset(
          id,
          cat.wireName,
          gamesCount((id, cat)),
          last,
          g.rating,
          g.deviation,
          thenMean,
          nowMean,
          for t <- thenMean; n <- nowMean yield n - t
        )
      }
      .sortBy(o => (o.category, o.lastFinishedAt))

    val humanLags = humans.toList
      .map { case ((id, cat), acc) =>
        HumanLag(
          id,
          cat.wireName,
          acc.games,
          acc.first,
          acc.last,
          acc.finalState.rating,
          acc.finalState.deviation,
          acc.converged,
          acc.opponentSum / acc.games,
          acc.actual,
          acc.expected,
          acc.actual - acc.expected
        )
      }
      .sortBy(h => (h.id, h.category))

    val snapshotByKey = participants.flatMap(p => p.ratings.map { case (cat, s) => (p.id, cat) -> s }).toMap
    val finals        = settled.toList
      .map { case ((id, cat), g) =>
        val snap = snapshotByKey.get((id, cat.wireName))
        FinalDiff(
          id,
          cat.wireName,
          settledGames((id, cat)),
          GlickoSnapshot.of(g),
          snap,
          snap.map(s => g.rating - s.rating)
        )
      }
      .sortBy(f => (f.category, -f.replayed.rating))

    val appliedRated = games.filter(g => g.rated && g.applied)
    val byApplied    = appliedRated.sortBy(g => (g.seqApplied.getOrElse(Long.MaxValue), g.seqFinished)).map(_.gameId)
    val byFinished   = appliedRated.sortBy(_.seqFinished).map(_.gameId)
    val displaced    = byApplied.iterator.zip(byFinished.iterator).count { case (a, b) => a != b }
    val checked      = outcomes.flatMap(_.formulaHolds)
    val integrity    = Integrity(
      rows = games.size.toLong,
      duplicateGameIds = (games.size - games.map(_.gameId).distinct.size).toLong,
      oneSidedNumeric = games
        .count(g =>
          g.white.ratingAfter.isDefined != g.black.ratingAfter.isDefined ||
            g.white.ratingBefore.isDefined != g.black.ratingBefore.isDefined
        )
        .toLong,
      numericWithoutStamp = games.count(g => g.numericRecorded && !g.applied).toLong,
      pendingRows = games.count(g => g.rated && !g.applied).toLong,
      displacedRows = displaced.toLong,
      formulaChecked = checked.size.toLong,
      formulaMismatches = checked.count(!_).toLong,
      categoryColumnDisagreements = games.count(g => g.category != categoryOf(g).map(_.wireName)).toLong
    )

    Summary(
      config = ConfigSummary(
        order = config.order.toString.toLowerCase,
        scale = config.scale match
          case Scale.PerCategory             => "per-category"
          case Scale.SingleUntil(at, seeded) =>
            s"single-until:$at:${seeded.toList.map(_.wireName).sorted.mkString(",")}",
        resolution = config.resolution.toString.toLowerCase,
        tauAfter = config.tau.after,
        tauBefore = config.tau.before,
        tauSwitchAt = config.tau.switchAt,
        tolerance = config.tolerance,
        inactiveAfterDays = config.inactiveAfterDays,
        followRecorded = config.followRecorded
      ),
      games = games.size.toLong,
      firstFinishedAt = games.map(_.finishedAt).minOption,
      lastFinishedAt = cutoff,
      numericRecordedSince = games.filter(_.numericRecorded).map(_.finishedAt).minOption,
      classes = classes,
      skips = skips,
      mismatches = mismatches,
      histograms = histograms,
      deltaSums = deltaSums.toList,
      deltaSeries = deltaSeries.result(),
      drift = drift.result(),
      inactiveOffsets = inactiveOffsets,
      humans = humanLags,
      finals = finals,
      integrity = integrity
    )

  /** The owner-facing plain-text rendering: the tallies a reader needs first, `Locale.ROOT` so it renders identically
    * everywhere (the same rule as `StrengthReport.render`).
    */
  def render(summary: Summary): String =
    def line(pattern: String, args: Any*): String =
      String.format(java.util.Locale.ROOT, pattern, args.map(_.asInstanceOf[Object])*)
    val header = List(
      "=== Rating replay (#145) ===",
      line(
        "corpus: %d rows, %s .. %s; numeric rows recorded since %s",
        summary.games,
        summary.firstFinishedAt.map(_.toString).getOrElse("-"),
        summary.lastFinishedAt.map(_.toString).getOrElse("-"),
        summary.numericRecordedSince.map(_.toString).getOrElse("-")
      ),
      line(
        "config: order=%s scale=%s resolution=%s eligibility=%s tau=%s/%s switch=%s tolerance=%s",
        summary.config.order,
        summary.config.scale,
        summary.config.resolution,
        if summary.config.followRecorded then "recorded" else "rules",
        summary.config.tauBefore.toString,
        summary.config.tauAfter.toString,
        summary.config.tauSwitchAt.map(_.toString).getOrElse("-"),
        summary.config.tolerance.toString
      ),
      ""
    )
    val provenanceLines = summary.provenance.toList.flatMap(p =>
      List(
        line(
          "inputs: %s sha256 %s; participants %s sha256 %s",
          p.corpusFile,
          p.corpusSha256,
          p.participantsFile.getOrElse("-"),
          p.participantsSha256.getOrElse("-")
        ),
        line(
          "run: revision %s; %s; %s; Scala %s; at %s",
          p.codeRevision.getOrElse("unknown"),
          p.javaRuntime,
          p.osArch,
          p.scalaVersion,
          p.ranAt.toString
        ),
        ""
      )
    )
    val classLines =
      "--- verdicts by category ---" :: summary.classes.map(c => line("%-8s %-34s %8d", c.category, c.verdict, c.count))
    val skipLines = "" :: "--- replay skips (numeric rows among them) ---" :: summary.skips.map(s =>
      line("%8d (%d)  %s", s.count, s.recordedNumeric, s.reason)
    )
    val mismatchLines = "" :: "--- level not reproduced (mismatch / match_step_only) ---" :: (
      if summary.mismatches.isEmpty then List("none")
      else
        summary.mismatches.map(m =>
          line(
            "%-8s %-16s %8d  max|before| %.6f  max|after| %.6f  max|step| %.6f  formula holds in %d  %s .. %s",
            m.category,
            m.verdict,
            m.count,
            m.maxBeforeDiff,
            m.maxAfterDiff,
            m.maxStepDiff,
            m.formulaHolds,
            m.firstFinishedAt.map(_.toString).getOrElse("-"),
            m.lastFinishedAt.map(_.toString).getOrElse("-")
          )
        )
    )
    val histogramLines = "" :: "--- |replayed − recorded| on numeric rows, rows per bin (≤ bound) ---" ::
      summary.histograms.map(h =>
        line(
          "%-8s %-5s %8d  %s",
          h.category,
          h.kind,
          h.rows,
          h.bins.map(b => line("%s:%d", if b.upTo.isInfinite then "inf" else b.upTo.toString, b.count)).mkString("  ")
        )
      )
    val deltaLines = "" :: "--- pair-delta sums Σ(Δwhite+Δblack) ---" :: summary.deltaSums.map(d =>
      line(
        "%-8s replayed %+.2f over %d games; recorded %+.2f over %d numeric rows, replayed %+.2f on those rows",
        d.category,
        d.replayedPairDeltaSum,
        d.replayedGames,
        d.recordedPairDeltaSum,
        d.recordedGames,
        d.replayedOnRecordedRows
      )
    )
    val humanLines = "" :: "--- human identities ---" :: (
      if summary.humans.isEmpty then List("none")
      else
        summary.humans.map(h =>
          line(
            "%s %-6s games %d  final %.1f ± %.1f  converged after %s  mean opp %.1f  score %.1f vs expected %.2f (surplus %+.2f)",
            h.id,
            h.category,
            h.games,
            h.finalRating,
            h.finalRd,
            h.gamesToConverge.map(_.toString).getOrElse("never"),
            h.meanOpponentRating,
            h.actualScore,
            h.expectedScore,
            h.surplus
          )
        )
    )
    val finalLines = "" :: "--- final state vs snapshot (rating diff) ---" :: summary.finals.map(f =>
      line(
        "%-8s %-40s %7d  %8.2f ± %6.2f  snapshot %s  diff %s",
        f.category,
        f.id,
        f.games,
        f.replayed.rating,
        f.replayed.rd,
        f.snapshot.map(s => line("%8.2f", s.rating)).getOrElse("-"),
        f.ratingDiff.map(d => line("%+.4f", d)).getOrElse("-")
      )
    )
    val i              = summary.integrity
    val integrityLines = List(
      "",
      "--- integrity ---",
      line(
        "duplicates %d  one-sided numeric %d  numeric without stamp %d  pending %d  displaced %d  formula mismatches %d of %d  category column disagreements %d",
        i.duplicateGameIds,
        i.oneSidedNumeric,
        i.numericWithoutStamp,
        i.pendingRows,
        i.displacedRows,
        i.formulaMismatches,
        i.formulaChecked,
        i.categoryColumnDisagreements
      )
    )
    (header ++ provenanceLines ++ classLines ++ skipLines ++ mismatchLines ++ histogramLines ++ deltaLines ++
      humanLines ++ finalLines ++ integrityLines)
      .mkString("\n")
