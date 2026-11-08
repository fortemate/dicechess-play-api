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
  RatingBatch,
  TrainingEstimate,
  TrainingReference,
  TrainingReferenceSource,
  TrainingState
}
import io.circe.{Codec, Decoder, Encoder}
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.syntax.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.collection.mutable

/** Pure, fail-closed matrix-epoch reconstruction (#151). Evidence is supplied by an audited export, never resolved from
  * current ownership, bot registrations, ratings or anchors. The caller verifies the immutable source-byte hash. Input
  * must be ascending (finishedAt, gameId); tied mutating rows additionally need audited application order. Memory is
  * bounded by maxRows seen ids, maxTieGroup source rows and the resulting participant/category states. Source rows and
  * their decisions are streamed to onLedger rather than retained in the plan.
  */
object RatingEpochPlan:
  val InputVersion    = 1
  val DecisionVersion = "matrix-epoch-plan-v1"
  val ModelVersion    = "glicko2-one-game-training-v1"

  given Configuration                                                    = Configuration.default
  private def enumCodec[A](values: List[A], name: A => String): Codec[A] = Codec.from(
    Decoder.decodeString.emap(raw => values.find(name(_) == raw).toRight(s"unknown enum value: $raw")),
    Encoder.encodeString.contramap(name)
  )
  given Codec[ParticipantKind]           = enumCodec(ParticipantKind.values.toList, _.wireName)
  given Codec[RatingCategory]            = enumCodec(RatingCategory.values.toList, _.wireName)
  given Codec[RatingDomain]              = enumCodec(RatingDomain.values.toList, _.wireName)
  given Codec[RatingOutcome]             = enumCodec(RatingOutcome.values.toList, _.wireName)
  given Codec[GameOrigin]                = enumCodec(GameOrigin.valuesList, _.wireName)
  given Codec[Glicko]                    = ConfiguredCodec.derived
  given Codec[TrainingReferenceSource]   = ConfiguredCodec.derived
  given Codec[TrainingReference]         = ConfiguredCodec.derived
  given Codec[CapturedTrainingReference] =
    val raw: Codec[CapturedTrainingReference] = ConfiguredCodec.derived
    Codec.from(
      raw.emap {
        case value @ CapturedTrainingReference.Recorded(reference) =>
          val validSource = reference.source match
            case TrainingReferenceSource.BotRating              => true
            case TrainingReferenceSource.Anchor(version, epoch) => version.trim.nonEmpty && epoch > 0
          Either.cond(validSource && validGlicko(reference.glicko), value, "invalid captured training reference")
        case value => Right(value)
      },
      raw
    )

  final case class ApprovedReset(reason: String, approvedBy: String, artifactSha256: String) derives ConfiguredCodec
  final case class Config(
      sourceSha256: String,
      targetEpoch: Int,
      anchorSetVersion: String,
      cutoff: Instant,
      inputVersion: Int = InputVersion,
      decisionVersion: String = DecisionVersion,
      modelVersion: String = ModelVersion,
      policyVersion: Int = 2,
      maxRows: Int = 1000000,
      maxTieGroup: Int = 1000,
      approvedReset: Option[ApprovedReset] = None,
      databaseSourceSha256: Option[String] = None
  ) derives ConfiguredCodec

  /** An artifact attests identity continuity for THIS game. A bot also needs its actual historical incarnation. An
    * arbitrary current incarnation or ownership generation is not historical continuity evidence.
    */
  final case class IdentityContinuity(artifactSha256: String, incarnation: Option[String] = None)
      derives ConfiguredCodec
  final case class SourceSeat(externalId: String, kind: ParticipantKind, continuity: Option[IdentityContinuity])
      derives ConfiguredCodec
  final case class ClassificationEvidence(
      policyVersion: Int,
      requestedRated: Boolean,
      rated: Boolean,
      domain: RatingDomain,
      artifactSha256: String
  ) derives ConfiguredCodec
  final case class OwnershipEvidence(ownBot: Boolean, artifactSha256: String) derives ConfiguredCodec
  final case class OrderEvidence(sequence: Long, artifactSha256: String) derives ConfiguredCodec
  final case class SourceGame(
      gameId: String,
      finishedAt: Instant,
      timeControl: String,
      white: SourceSeat,
      black: SourceSeat,
      result: Option[Int],
      termination: String,
      origin: GameOrigin,
      ladder: Boolean,
      applied: Boolean,
      outcome: RatingOutcome,
      classification: Option[ClassificationEvidence],
      ownership: Option[OwnershipEvidence],
      reference: CapturedTrainingReference = CapturedTrainingReference.NotRecorded,
      orderEvidence: Option[OrderEvidence] = None,
      skipReason: Option[String] = None
  ) derives ConfiguredCodec

  enum Mode derives ConfiguredCodec:
    case Rebuild, ApprovedReset

  enum Decision derives ConfiguredCodec:
    case Competitive, Training, Excluded, Unknown, Reset

  final case class LedgerEntry(
      gameId: String,
      finishedAt: Instant,
      category: Option[RatingCategory],
      decision: Decision,
      reasons: Vector[String],
      sourceSha256: String
  ) derives ConfiguredCodec
  final case class ProjectionRow(
      externalId: String,
      category: RatingCategory,
      domain: RatingDomain,
      glicko: Glicko,
      games: Int,
      wins: Int,
      draws: Int,
      losses: Int,
      lastPlayedAt: Instant
  ) derives ConfiguredCodec
  final case class Counts(
      sourceRows: Long,
      competitiveGames: Long,
      trainingGames: Long,
      excludedRows: Long,
      unknownRows: Long,
      resetRows: Long
  ) derives ConfiguredCodec
  final case class Plan(
      config: Config,
      mode: Mode,
      rows: Vector[ProjectionRow],
      counts: Counts,
      ledgerSha256: String,
      projectionSha256: String,
      activatable: Boolean,
      identityIncarnations: Map[String, String],
      trainingBotIdentities: Set[String]
  ) derives ConfiguredCodec

  private def digest(): MessageDigest                                     = MessageDigest.getInstance("SHA-256")
  private def hex(bytes: Array[Byte]): String                             = bytes.map(b => f"${b & 0xff}%02x").mkString
  private def append(hash: MessageDigest, fields: Iterable[String]): Unit = fields.foreach { field =>
    val bytes = field.getBytes(UTF_8)
    hash.update(s"${bytes.length}:".getBytes(UTF_8))
    hash.update(bytes)
  }
  private def hash(fields: Iterable[String]): String =
    val result = digest()
    append(result, fields)
    hex(result.digest())
  private def validDigest(value: String): Boolean   = value.matches("[0-9a-f]{64}")
  private def canonicalUuid(value: String): Boolean =
    scala.util.Try(UUID.fromString(value).toString).toOption.contains(value)
  private def userId(externalId: String): Option[String] =
    Principal.fromUserExternalId(externalId).filter(canonicalUuid)
  private def validGlicko(value: Glicko): Boolean =
    value.rating.isFinite && value.deviation.isFinite && value.deviation > 0 &&
      value.volatility.isFinite && value.volatility > 0

  private def validateConfig(config: Config): Either[String, Unit] =
    if !validDigest(config.sourceSha256) || config.databaseSourceSha256.exists(value => !validDigest(value)) then
      Left("invalid source SHA-256")
    else if config.inputVersion != InputVersion || config.decisionVersion != DecisionVersion ||
      config.modelVersion != ModelVersion || config.policyVersion != RatingPolicy.Matrix.version
    then Left("unsupported input, decision, model or policy version")
    else if config.targetEpoch <= 0 || config.anchorSetVersion.trim.isEmpty then
      Left("target epoch and anchor version required")
    else if config.maxRows <= 0 || config.maxTieGroup <= 0 || config.maxTieGroup > config.maxRows then
      Left("invalid source/tie-group limits")
    else if config.approvedReset.exists(a =>
        a.reason.trim.isEmpty || a.approvedBy.trim.isEmpty || !validDigest(a.artifactSha256)
      )
    then Left("reset requires a reason, approver and approval artifact SHA-256")
    else Right(())

  /** Canonical row digest and outer immutable-plan digest. The outer digest covers all provenance and counts;
    * activatable is derived and is checked independently, so it is normalized out of the outer digest.
    */
  private def canonical(value: io.circe.Json): String = io.circe.Printer.noSpaces.copy(sortKeys = true).print(value)
  def projectionDigest(rows: Vector[ProjectionRow]): String = hash(rows.map(row => canonical(row.asJson)))
  def planDigest(plan: Plan): String                        =
    // Sets have no order. Normalize the only set field as well as JSON object keys before hashing.
    val json = plan
      .copy(activatable = false)
      .asJson
      .mapObject(_.add("trainingBotIdentities", plan.trainingBotIdentities.toVector.sorted.asJson))
    hash(Vector(canonical(json)))

  private def ensure(condition: Boolean, problem: String): Either[String, Unit] =
    Either.cond(condition, (), problem)

  private def activatable(mode: Mode, counts: Counts): Boolean =
    mode == Mode.ApprovedReset || (counts.unknownRows == 0 && counts.competitiveGames + counts.trainingGames > 0)

  private def validProjectionIdentity(row: ProjectionRow): Boolean = row.domain match
    case RatingDomain.Training    => userId(row.externalId).isDefined
    case RatingDomain.Competitive =>
      userId(row.externalId).isDefined || Principal
        .fromBotExternalId(row.externalId)
        .exists(bot => !RatingPolicy.isAnonymous(bot))
    case _ => false

  private def validProjectionRow(row: ProjectionRow, cutoff: Instant): Boolean =
    row.externalId.nonEmpty && validGlicko(
      row.glicko
    ) && row.games > 0 && row.wins >= 0 && row.draws >= 0 && row.losses >= 0 &&
      row.games.toLong == row.wins.toLong + row.draws.toLong + row.losses.toLong &&
      !row.lastPlayedAt.isAfter(cutoff) && validProjectionIdentity(row)

  private def validateCounts(plan: Plan): Either[String, Unit] =
    val c       = plan.counts
    val numbers = Vector(c.sourceRows, c.competitiveGames, c.trainingGames, c.excludedRows, c.unknownRows, c.resetRows)
    ensure(
      !numbers.exists(_ < 0) && c.sourceRows <= plan.config.maxRows &&
        c.sourceRows == c.competitiveGames + c.trainingGames + c.excludedRows + c.unknownRows + c.resetRows,
      "invalid plan counts"
    )

  private def validateRows(plan: Plan): Either[String, Unit] =
    val sorted     = plan.rows.sortBy(p => (p.externalId, p.category.wireName, p.domain.wireName))
    val identities = plan.rows.map(p => (p.externalId, p.category, p.domain))
    ensure(
      plan.rows.forall(validProjectionRow(_, plan.config.cutoff)) && sorted == plan.rows &&
        identities.distinct.size == plan.rows.size,
      "invalid projection rows"
    )

  private def validateBotBindings(plan: Plan): Either[String, Unit] =
    val projectedBots = plan.rows
      .filter(p =>
        p.domain == RatingDomain.Competitive &&
          Principal.fromBotExternalId(p.externalId).isDefined
      )
      .map(_.externalId)
      .toSet
    val identities    = projectedBots ++ plan.trainingBotIdentities
    val validBindings = plan.identityIncarnations.forall { case (id, incarnation) =>
      Principal.fromBotExternalId(id).isDefined && canonicalUuid(incarnation)
    }
    ensure(
      plan.identityIncarnations.keySet == identities && validBindings &&
        plan.trainingBotIdentities.size.toLong <= plan.counts.trainingGames,
      "projection bot identities lack exact historical incarnation bindings"
    )

  private def validateDigests(plan: Plan): Either[String, Unit] =
    ensure(
      validDigest(plan.ledgerSha256) && plan.projectionSha256 == projectionDigest(plan.rows),
      "plan digest mismatch"
    )

  private def validateMode(plan: Plan): Either[String, Unit] = plan.mode match
    case Mode.ApprovedReset =>
      ensure(
        plan.config.approvedReset.isDefined && plan.rows.isEmpty && plan.identityIncarnations.isEmpty &&
          plan.trainingBotIdentities.isEmpty && plan.counts.resetRows == plan.counts.sourceRows,
        "reset must have approval and an empty projection"
      )
    case Mode.Rebuild =>
      ensure(plan.config.approvedReset.isEmpty && plan.counts.resetRows == 0, "rebuild/reset mode mismatch")

  private def validateProjectionCounts(plan: Plan): Either[String, Unit] = plan.mode match
    case Mode.ApprovedReset => Right(())
    case Mode.Rebuild       =>
      val competitive = plan.rows.filter(_.domain == RatingDomain.Competitive).map(_.games.toLong).sum
      val training    = plan.rows.filter(_.domain == RatingDomain.Training).map(_.games.toLong).sum
      ensure(
        competitive == 2L * plan.counts.competitiveGames && training == plan.counts.trainingGames,
        "projection game counts do not reconcile"
      )

  /** Validate deserialized or externally constructed plans before staging/activation. The ledger's source-byte and
    * artifact contents must still be verified by the shell; a digest is a binding, not a substitute for that evidence.
    */
  def validate(plan: Plan): Either[String, Unit] =
    for
      _ <- validateConfig(plan.config)
      _ <- validateCounts(plan)
      _ <- validateRows(plan)
      _ <- validateBotBindings(plan)
      _ <- validateDigests(plan)
      _ <- validateMode(plan)
      _ <- validateProjectionCounts(plan)
      _ <- ensure(plan.activatable == activatable(plan.mode, plan.counts), "activation flag contradicts plan decisions")
    yield ()

  private def principal(seat: SourceSeat): Option[Principal] = seat.kind match
    case ParticipantKind.Bot   => Principal.fromBotExternalId(seat.externalId)
    case ParticipantKind.Human => userId(seat.externalId).map(Principal.User(_))
    case ParticipantKind.Guest =>
      Option
        .when(seat.externalId.startsWith("guest:"))(seat.externalId.stripPrefix("guest:"))
        .flatMap(id => Principal.guest(id).toOption)

  final private case class BotBinding(externalId: String, incarnation: String)
  final private case class Application(category: RatingCategory, scores: (Double, Double), bots: Vector[BotBinding])

  private def continuity(seat: SourceSeat): Option[Vector[BotBinding]] =
    seat.continuity.filter(e => validDigest(e.artifactSha256)).flatMap { evidence =>
      if seat.kind == ParticipantKind.Bot then
        evidence.incarnation.filter(canonicalUuid).map(id => Vector(BotBinding(seat.externalId, id)))
      else Some(Vector.empty)
    }

  private enum Assessment:
    case Unchanged(decision: Decision, reasons: Vector[String])
    case Competitive(application: Application)
    case Training(application: Application, reference: TrainingReference)

    def mutation: Option[Application] = this match
      case Competitive(value) => Some(value)
      case Training(value, _) => Some(value)
      case Unchanged(_, _)    => None

  private def unknown(reason: String): Assessment = Assessment.Unchanged(Decision.Unknown, Vector(reason))
  private def exclude(reason: String): Assessment = Assessment.Unchanged(Decision.Excluded, Vector(reason))

  private def application(
      game: SourceGame,
      category: RatingCategory,
      scores: (Double, Double)
  ): Option[Application] =
    for
      white <- continuity(game.white)
      black <- continuity(game.black)
    yield Application(category, scores, white ++ black)

  private def validTrainingReference(reference: TrainingReference): Boolean =
    val validSource = reference.source match
      case TrainingReferenceSource.BotRating              => true
      case TrainingReferenceSource.Anchor(version, epoch) => version.trim.nonEmpty && epoch > 0
    validSource && validGlicko(reference.glicko)

  private def assessReference(reference: CapturedTrainingReference, application: Application): Assessment =
    reference match
      case CapturedTrainingReference.NotRecorded     => unknown("training_reference_not_recorded")
      case CapturedTrainingReference.Unavailable     => exclude("training_reference_unavailable")
      case CapturedTrainingReference.Recorded(value) =>
        if validTrainingReference(value) then Assessment.Training(application, value)
        else unknown("training_reference_invalid")

  private def assessTraining(game: SourceGame, application: Application): Assessment = game.ownership match
    case None                                              => unknownOwnership
    case Some(owner) if !validDigest(owner.artifactSha256) => unknownOwnership
    case Some(owner) if owner.ownBot                       => exclude("own_bot")
    case Some(_)                                           => assessReference(game.reference, application)

  private def unknownOwnership: Assessment = unknown("historical_ownership_unknown")

  private def assessSkipped(game: SourceGame): Assessment = game.skipReason.filter(_.trim.nonEmpty) match
    case Some(reason) => Assessment.Unchanged(Decision.Excluded, Vector("recorded_skip", reason))
    case None         => unknown("skip_reason_missing")

  private def assessApplication(
      game: SourceGame,
      domain: RatingDomain,
      category: RatingCategory,
      scores: (Double, Double)
  ): Assessment =
    if !game.applied || game.outcome == RatingOutcome.Pending then unknown("application_incomplete")
    else if game.outcome == RatingOutcome.Skipped then assessSkipped(game)
    else if game.outcome != RatingOutcome.Applied then unknown("application_not_proven")
    else
      application(game, category, scores) match
        case None                                              => unknown("identity_continuity_unknown")
        case Some(value) if domain == RatingDomain.Competitive => Assessment.Competitive(value)
        case Some(value)                                       => assessTraining(game, value)

  private def assessClassified(
      game: SourceGame,
      evidence: ClassificationEvidence,
      white: Principal,
      black: Principal,
      control: TimeControl,
      scores: (Double, Double)
  ): Assessment =
    val expected = RatingPolicy.classify(
      RatingPolicy.Matrix,
      white,
      black,
      evidence.requestedRated,
      control,
      game.origin,
      game.ladder
    )
    if expected.rated != evidence.rated || expected.domain != evidence.domain then
      unknown("classification_matrix_mismatch")
    else if expected.domain == RatingDomain.Casual then exclude("matrix_casual")
    else
      RatingCategory.of(control) match
        case Some(category) => assessApplication(game, expected.domain, category, scores)
        case None           => unknown("time_control_uncategorized")

  private def assessClassification(game: SourceGame, scores: (Double, Double)): Assessment =
    (game.classification, principal(game.white), principal(game.black), TimeControl.parse(game.timeControl)) match
      case (None, _, _, _) => unknown("classification_not_recorded")
      case (Some(evidence), _, _, _) if evidence.policyVersion != RatingPolicy.Matrix.version =>
        unknown("legacy_classification")
      case (Some(evidence), _, _, _) if !validDigest(evidence.artifactSha256) =>
        unknown("classification_evidence_missing")
      case (_, None, _, _) | (_, _, None, _)                         => unknown("identity_kind_or_shape_unknown")
      case (_, _, _, None)                                           => unknown("time_control_unparseable")
      case (Some(evidence), Some(white), Some(black), Some(control)) =>
        assessClassified(game, evidence, white, black, control, scores)

  private def assess(game: SourceGame, cutoff: Instant): Assessment =
    if game.gameId.trim.isEmpty then unknown("missing_game_id")
    else if game.finishedAt.isAfter(cutoff) then exclude("after_cutoff")
    else if game.white.externalId == game.black.externalId then unknown("self_pairing")
    else if game.termination == "aborted" then exclude("no_sporting_result")
    else
      game.result.flatMap(RatingBatch.scores) match
        case None         => exclude("no_sporting_result")
        case Some(scores) => assessClassification(game, scores)

  /** Structural failures (limits/order) return Left and the caller must discard any streamed ledger. Unknown evidence
    * yields a nonactivatable Rebuild plan. ApprovedReset is a separate empty projection, never a partial rebuild.
    */
  def build(
      config: Config,
      source: Iterator[SourceGame],
      onLedger: LedgerEntry => Unit = _ => ()
  ): Either[String, Plan] =
    buildChecked(config, source.map(Right(_)), onLedger)

  private[migration] def buildChecked(
      config: Config,
      source: Iterator[Either[String, SourceGame]],
      onLedger: LedgerEntry => Unit = _ => ()
  ): Either[String, Plan] =
    validateConfig(config).flatMap(_ => new PlanBuilder(config, onLedger).runChecked(source))

  final private class PlanBuilder(config: Config, onLedger: LedgerEntry => Unit):
    private val mode         = if config.approvedReset.isDefined then Mode.ApprovedReset else Mode.Rebuild
    private val seen         = mutable.HashSet.empty[String]
    private val incarnations = mutable.HashMap.empty[String, String]
    private val trainingBots = mutable.HashSet.empty[String]
    private val states       = mutable.HashMap.empty[(String, RatingCategory, RatingDomain), ProjectionRow]
    private val ledgerHash   = digest()
    private val counts       = mutable.HashMap.from(Decision.values.map(_ -> 0L))
    private var sourceRows   = 0L
    private var previous: Option[(Instant, String)] = None
    private var failure: Option[String]             = None
    private var group                               = Vector.empty[SourceGame]

    private def emit(game: SourceGame, decision: Decision, reasons: Vector[String]): Unit =
      val entry = LedgerEntry(
        game.gameId,
        game.finishedAt,
        RatingCategory.ofStored(game.timeControl),
        decision,
        reasons,
        hash(Vector(canonical(game.asJson)))
      )
      counts.update(decision, counts(decision) + 1L)
      append(
        ledgerHash,
        Vector(
          entry.gameId,
          entry.finishedAt.toString,
          entry.category.fold("")(_.wireName),
          entry.decision.toString
        ) ++ entry.reasons :+ entry.sourceSha256
      )
      onLedger(entry)

    private def updateSeat(
        game: SourceGame,
        seat: SourceSeat,
        category: RatingCategory,
        domain: RatingDomain,
        next: Glicko,
        score: Double
    ): Unit =
      val key = (seat.externalId, category, domain)
      val old = states.get(key)
      states.update(
        key,
        ProjectionRow(
          seat.externalId,
          category,
          domain,
          next,
          old.fold(1)(_.games + 1),
          old.fold(0)(_.wins) + (if score == 1.0 then 1 else 0),
          old.fold(0)(_.draws) + (if score == 0.5 then 1 else 0),
          old.fold(0)(_.losses) + (if score == 0.0 then 1 else 0),
          game.finishedAt
        )
      )

    private def current(seat: SourceSeat, category: RatingCategory, domain: RatingDomain): Glicko =
      states.get((seat.externalId, category, domain)).fold(Glicko.Initial)(_.glicko)

    private def applyCompetitive(game: SourceGame, category: RatingCategory, scores: (Double, Double)): Boolean =
      val (whiteScore, blackScore) = scores
      val white                    = current(game.white, category, RatingDomain.Competitive)
      val black                    = current(game.black, category, RatingDomain.Competitive)
      val whiteNext                = Glicko2.update(white, List(Glicko2.Result(black, whiteScore)))
      val blackNext                = Glicko2.update(black, List(Glicko2.Result(white, blackScore)))
      if !validGlicko(whiteNext) || !validGlicko(blackNext) then false
      else
        updateSeat(game, game.white, category, RatingDomain.Competitive, whiteNext, whiteScore)
        updateSeat(game, game.black, category, RatingDomain.Competitive, blackNext, blackScore)
        true

    private def trainingState(row: ProjectionRow): TrainingState =
      TrainingState(
        row.glicko.rating,
        row.glicko.deviation,
        row.glicko.volatility,
        row.games,
        row.wins,
        row.draws,
        row.losses,
        Some(row.lastPlayedAt)
      )

    private def applyTraining(
        game: SourceGame,
        application: Application,
        reference: TrainingReference
    ): Boolean =
      val category     = application.category
      val humanIsWhite = game.white.kind == ParticipantKind.Human
      val human        = if humanIsWhite then game.white else game.black
      val score        = if humanIsWhite then application.scores._1 else application.scores._2
      val state        =
        states.get((human.externalId, category, RatingDomain.Training)).fold(TrainingState.Initial)(trainingState)
      val next = TrainingEstimate.update(state, reference.glicko, humanIsWhite, score, game.finishedAt).glicko
      if !validGlicko(next) then false
      else
        updateSeat(game, human, category, RatingDomain.Training, next, score)
        true

    private def changesIncarnation(application: Application): Boolean =
      application.bots.exists { binding =>
        incarnations.get(binding.externalId).exists(_ != binding.incarnation)
      }

    private def recordBotBindings(application: Application, decision: Decision): Unit =
      application.bots.foreach { binding =>
        incarnations.update(binding.externalId, binding.incarnation)
        if decision == Decision.Training then trainingBots.add(binding.externalId)
      }

    private def applyKnownGame(game: SourceGame, application: Application, decision: Decision)(
        applied: => Boolean
    ): Unit =
      if applied then
        recordBotBindings(application, decision)
        emit(game, decision, Vector.empty)
      else emit(game, Decision.Unknown, Vector("nonfinite_projection"))

    private def processNewGame(game: SourceGame, assessment: Assessment, ordered: Boolean): Unit =
      assessment match
        case _ if assessment.mutation.isDefined && !ordered =>
          emit(game, Decision.Unknown, Vector("tied_application_order_unknown"))
        case _ if assessment.mutation.exists(changesIncarnation) =>
          emit(game, Decision.Unknown, Vector("multiple_bot_incarnations"))
        case Assessment.Competitive(value) =>
          applyKnownGame(game, value, Decision.Competitive)(applyCompetitive(game, value.category, value.scores))
        case Assessment.Training(value, reference) =>
          applyKnownGame(game, value, Decision.Training)(applyTraining(game, value, reference))
        case Assessment.Unchanged(decision, reasons) => emit(game, decision, reasons)

    private def processAssessed(game: SourceGame, assessment: Assessment, ordered: Boolean): Unit =
      config.approvedReset match
        case Some(approval)                     => emit(game, Decision.Reset, Vector("approved_reset", approval.reason))
        case None if seen.contains(game.gameId) => emit(game, Decision.Unknown, Vector("duplicate_game_id"))
        case None                               =>
          seen.add(game.gameId)
          processNewGame(game, assessment, ordered)

    private def orderedMutations(assessed: Vector[(SourceGame, Assessment)]): Boolean =
      val mutating = assessed.filter { case (_, assessment) => assessment.mutation.isDefined }
      val evidence = mutating.flatMap(_._1.orderEvidence)
      mutating.size <= 1 || (evidence.size == mutating.size &&
        evidence.forall(e => e.sequence >= 0 && validDigest(e.artifactSha256)) &&
        evidence.map(_.sequence).distinct.size == evidence.size && evidence.map(_.artifactSha256).distinct.size == 1)

    private def flush(): Unit =
      val assessed = group.map(game => (game, assess(game, config.cutoff)))
      val ordered  = orderedMutations(assessed)
      val sorted   = if ordered then
        assessed.sortBy { case (game, _) => (game.orderEvidence.fold(Long.MaxValue)(_.sequence), game.gameId) }
      else assessed
      sorted.foreach { case (game, assessment) => processAssessed(game, assessment, ordered) }
      group = Vector.empty

    private def outOfOrder(game: SourceGame): Boolean = previous.exists { case (at, id) =>
      game.finishedAt.isBefore(at) || (game.finishedAt == at && game.gameId.compareTo(id) < 0)
    }

    private def appendGame(game: SourceGame): Unit =
      if group.headOption.exists(_.finishedAt != game.finishedAt) then flush()
      group :+= game
      if group.size > config.maxTieGroup then failure = Some("source tie-group limit exceeded")
      previous = Some((game.finishedAt, game.gameId))

    private def accept(game: SourceGame): Unit =
      sourceRows += 1
      if sourceRows > config.maxRows then failure = Some("source row limit exceeded")
      else if outOfOrder(game) then failure = Some("source must be ordered by finishedAt and gameId")
      else appendGame(game)

    private def completedPlan(): Plan =
      val rows   = states.values.toVector.sortBy(p => (p.externalId, p.category.wireName, p.domain.wireName))
      val totals = Counts(
        sourceRows,
        counts(Decision.Competitive),
        counts(Decision.Training),
        counts(Decision.Excluded),
        counts(Decision.Unknown),
        counts(Decision.Reset)
      )
      Plan(
        config,
        mode,
        rows,
        totals,
        hex(ledgerHash.digest()),
        projectionDigest(rows),
        activatable(mode, totals),
        incarnations.toMap,
        trainingBots.toSet
      )

    def runChecked(source: Iterator[Either[String, SourceGame]]): Either[String, Plan] =
      while failure.isEmpty && source.hasNext do
        source.next() match
          case Left(problem) => failure = Some(problem)
          case Right(game)   => accept(game)
      if failure.isEmpty then flush()
      failure match
        case Some(problem) => Left(problem)
        case None          => Right(completedPlan())
