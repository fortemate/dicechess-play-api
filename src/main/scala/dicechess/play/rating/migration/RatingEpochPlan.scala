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

  /** Validate deserialized or externally constructed plans before staging/activation. The ledger's source-byte and
    * artifact contents must still be verified by the shell; a digest is a binding, not a substitute for that evidence.
    */
  def validate(plan: Plan): Either[String, Unit] = validateConfig(plan.config).flatMap { _ =>
    val c       = plan.counts
    val numbers = Vector(c.sourceRows, c.competitiveGames, c.trainingGames, c.excludedRows, c.unknownRows, c.resetRows)
    val expectedActive =
      plan.mode == Mode.ApprovedReset || (c.unknownRows == 0 && c.competitiveGames + c.trainingGames > 0)
    val sorted        = plan.rows.sortBy(p => (p.externalId, p.category.wireName, p.domain.wireName))
    val projectedBots = plan.rows
      .filter(p => p.domain == RatingDomain.Competitive && Principal.fromBotExternalId(p.externalId).isDefined)
      .map(_.externalId)
      .toSet
    val botIdentities = projectedBots ++ plan.trainingBotIdentities
    val validRows     = plan.rows.forall(p =>
      p.externalId.nonEmpty && validGlicko(p.glicko) && p.games > 0 && p.wins >= 0 && p.draws >= 0 && p.losses >= 0 &&
        p.games.toLong == p.wins.toLong + p.draws.toLong + p.losses.toLong && !p.lastPlayedAt.isAfter(
          plan.config.cutoff
        ) &&
        (p.domain == RatingDomain.Competitive || p.domain == RatingDomain.Training) &&
        (if p.domain == RatingDomain.Training then userId(p.externalId).isDefined
         else
           userId(p.externalId).isDefined || Principal
             .fromBotExternalId(p.externalId)
             .exists(bot => !RatingPolicy.isAnonymous(bot)))
    )
    if numbers.exists(_ < 0) || c.sourceRows > plan.config.maxRows ||
      c.sourceRows != c.competitiveGames + c.trainingGames + c.excludedRows + c.unknownRows + c.resetRows
    then Left("invalid plan counts")
    else if !validRows || sorted != plan.rows || plan.rows
        .map(p => (p.externalId, p.category, p.domain))
        .distinct
        .size != plan.rows.size
    then Left("invalid projection rows")
    else if plan.identityIncarnations.keySet != botIdentities ||
      plan.identityIncarnations.exists { case (id, incarnation) =>
        Principal.fromBotExternalId(id).isEmpty || !canonicalUuid(incarnation)
      } ||
      plan.trainingBotIdentities.size.toLong > c.trainingGames
    then Left("projection bot identities lack exact historical incarnation bindings")
    else if !validDigest(plan.ledgerSha256) || plan.projectionSha256 != projectionDigest(plan.rows) then
      Left("plan digest mismatch")
    else if plan.mode == Mode.ApprovedReset &&
      (plan.config.approvedReset.isEmpty || plan.rows.nonEmpty || plan.identityIncarnations.nonEmpty || plan.trainingBotIdentities.nonEmpty || c.resetRows != c.sourceRows)
    then Left("reset must have approval and an empty projection")
    else if plan.mode == Mode.Rebuild && (plan.config.approvedReset.isDefined || c.resetRows != 0) then
      Left("rebuild/reset mode mismatch")
    else if plan.mode == Mode.Rebuild &&
      (plan.rows.filter(_.domain == RatingDomain.Competitive).map(_.games.toLong).sum != 2L * c.competitiveGames ||
        plan.rows.filter(_.domain == RatingDomain.Training).map(_.games.toLong).sum != c.trainingGames)
    then Left("projection game counts do not reconcile")
    else if plan.activatable != expectedActive then Left("activation flag contradicts plan decisions")
    else Right(())
  }

  private def principal(seat: SourceSeat): Option[Principal] = seat.kind match
    case ParticipantKind.Bot   => Principal.fromBotExternalId(seat.externalId)
    case ParticipantKind.Human => userId(seat.externalId).map(Principal.User(_))
    case ParticipantKind.Guest =>
      Option
        .when(seat.externalId.startsWith("guest:"))(seat.externalId.stripPrefix("guest:"))
        .flatMap(id => Principal.guest(id).toOption)

  private def continuity(seat: SourceSeat): Boolean = seat.continuity.exists { evidence =>
    validDigest(evidence.artifactSha256) &&
    (seat.kind != ParticipantKind.Bot || evidence.incarnation.exists(canonicalUuid))
  }

  private def assess(game: SourceGame, cutoff: Instant): (Decision, Vector[String]) =
    def unknown(reason: String) = (Decision.Unknown, Vector(reason))
    def exclude(reason: String) = (Decision.Excluded, Vector(reason))
    if game.gameId.trim.isEmpty then unknown("missing_game_id")
    else if game.finishedAt.isAfter(cutoff) then exclude("after_cutoff")
    else if game.white.externalId == game.black.externalId then unknown("self_pairing")
    else if game.termination == "aborted" || game.result.flatMap(RatingBatch.scores).isEmpty then
      exclude("no_sporting_result")
    else
      (game.classification, principal(game.white), principal(game.black), TimeControl.parse(game.timeControl)) match
        case (None, _, _, _) => unknown("classification_not_recorded")
        case (Some(evidence), _, _, _) if evidence.policyVersion != RatingPolicy.Matrix.version =>
          unknown("legacy_classification")
        case (Some(evidence), _, _, _) if !validDigest(evidence.artifactSha256) =>
          unknown("classification_evidence_missing")
        case (_, None, _, _) | (_, _, None, _)                         => unknown("identity_kind_or_shape_unknown")
        case (_, _, _, None)                                           => unknown("time_control_unparseable")
        case (Some(evidence), Some(white), Some(black), Some(control)) =>
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
          else if !game.applied || game.outcome == RatingOutcome.Pending then unknown("application_incomplete")
          else if game.outcome == RatingOutcome.Skipped then
            if game.skipReason.exists(_.trim.nonEmpty) then
              (Decision.Excluded, Vector("recorded_skip", game.skipReason.get))
            else unknown("skip_reason_missing")
          else if game.outcome != RatingOutcome.Applied then unknown("application_not_proven")
          else if !continuity(game.white) || !continuity(game.black) then unknown("identity_continuity_unknown")
          else if expected.domain == RatingDomain.Competitive then (Decision.Competitive, Vector.empty)
          else
            game.ownership match
              case None                                              => unknown("historical_ownership_unknown")
              case Some(owner) if !validDigest(owner.artifactSha256) => unknown("historical_ownership_unknown")
              case Some(owner) if owner.ownBot                       => exclude("own_bot")
              case Some(_)                                           =>
                game.reference match
                  case CapturedTrainingReference.NotRecorded         => unknown("training_reference_not_recorded")
                  case CapturedTrainingReference.Unavailable         => exclude("training_reference_unavailable")
                  case CapturedTrainingReference.Recorded(reference) =>
                    val sourceValid = reference.source match
                      case TrainingReferenceSource.BotRating              => true
                      case TrainingReferenceSource.Anchor(version, epoch) => version.trim.nonEmpty && epoch > 0
                    if !sourceValid || !validGlicko(reference.glicko) then unknown("training_reference_invalid")
                    else (Decision.Training, Vector.empty)

  /** Structural failures (limits/order) return Left and the caller must discard any streamed ledger. Unknown evidence
    * yields a nonactivatable Rebuild plan. ApprovedReset is a separate empty projection, never a partial rebuild.
    */
  def build(
      config: Config,
      source: Iterator[SourceGame],
      onLedger: LedgerEntry => Unit = _ => ()
  ): Either[String, Plan] = validateConfig(config).flatMap { _ =>
    val mode         = if config.approvedReset.isDefined then Mode.ApprovedReset else Mode.Rebuild
    val seen         = mutable.HashSet.empty[String]
    val incarnations = mutable.HashMap.empty[String, String]
    val trainingBots = mutable.HashSet.empty[String]
    val states       = mutable.HashMap.empty[(String, RatingCategory, RatingDomain), ProjectionRow]
    val ledgerHash   = digest()
    val counts       = mutable.HashMap.from(Decision.values.map(_ -> 0L))
    var sourceRows   = 0L
    var previous: Option[(Instant, String)] = None
    var failure: Option[String]             = None
    var group                               = Vector.empty[SourceGame]

    def emit(game: SourceGame, decision: Decision, reasons: Vector[String]): Unit =
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

    def updateSeat(
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

    def applyGame(game: SourceGame, decision: Decision): Boolean =
      val category                 = RatingCategory.ofStored(game.timeControl).get
      val (whiteScore, blackScore) = RatingBatch.scores(game.result.get).get
      val domain = if decision == Decision.Competitive then RatingDomain.Competitive else RatingDomain.Training
      def current(seat: SourceSeat): Glicko =
        states.get((seat.externalId, category, domain)).fold(Glicko.Initial)(_.glicko)
      if decision == Decision.Competitive then
        val white     = current(game.white)
        val black     = current(game.black)
        val whiteNext = Glicko2.update(white, List(Glicko2.Result(black, whiteScore)))
        val blackNext = Glicko2.update(black, List(Glicko2.Result(white, blackScore)))
        if !validGlicko(whiteNext) || !validGlicko(blackNext) then false
        else
          updateSeat(game, game.white, category, domain, whiteNext, whiteScore)
          updateSeat(game, game.black, category, domain, blackNext, blackScore)
          true
      else
        val humanIsWhite = game.white.kind == ParticipantKind.Human
        val human        = if humanIsWhite then game.white else game.black
        val score        = if humanIsWhite then whiteScore else blackScore
        val old          = states.get((human.externalId, category, domain))
        val state        = old.fold(TrainingState.Initial)(p =>
          TrainingState(
            p.glicko.rating,
            p.glicko.deviation,
            p.glicko.volatility,
            p.games,
            p.wins,
            p.draws,
            p.losses,
            Some(p.lastPlayedAt)
          )
        )
        val reference = game.reference match
          case CapturedTrainingReference.Recorded(value) => value.glicko
          case _ => throw new IllegalStateException("assessed training row lacks its recorded reference")
        val next = TrainingEstimate.update(state, reference, humanIsWhite, score, game.finishedAt).glicko
        if !validGlicko(next) then false
        else
          updateSeat(game, human, category, domain, next, score)
          true

    def flush(): Unit =
      val assessed = group.map(g => (g, assess(g, config.cutoff)))
      val mutating = assessed.filter { case (_, (d, _)) => d == Decision.Competitive || d == Decision.Training }
      val evidence = mutating.flatMap(_._1.orderEvidence)
      val ordered  = mutating.size <= 1 ||
        (evidence.size == mutating.size && evidence.forall(e => e.sequence >= 0 && validDigest(e.artifactSha256)) &&
          evidence.map(_.sequence).distinct.size == evidence.size && evidence.map(_.artifactSha256).distinct.size == 1)
      val sorted = if ordered then
        assessed.sortBy { case (g, _) => (g.orderEvidence.fold(Long.MaxValue)(_.sequence), g.gameId) }
      else assessed
      sorted.foreach { case (game, (decision, reasons)) =>
        if mode == Mode.ApprovedReset then
          emit(game, Decision.Reset, Vector("approved_reset", config.approvedReset.get.reason))
        else if seen.contains(game.gameId) then emit(game, Decision.Unknown, Vector("duplicate_game_id"))
        else
          seen.add(game.gameId)
          val changesIncarnation = Vector(game.white, game.black).filter(_.kind == ParticipantKind.Bot).exists { seat =>
            seat.continuity.flatMap(_.incarnation).exists(i => incarnations.get(seat.externalId).exists(_ != i))
          }
          if (decision == Decision.Competitive || decision == Decision.Training) && !ordered then
            emit(game, Decision.Unknown, Vector("tied_application_order_unknown"))
          else if (decision == Decision.Competitive || decision == Decision.Training) && changesIncarnation then
            emit(game, Decision.Unknown, Vector("multiple_bot_incarnations"))
          else if decision == Decision.Competitive || decision == Decision.Training then
            if applyGame(game, decision) then
              Vector(game.white, game.black).filter(_.kind == ParticipantKind.Bot).foreach { seat =>
                incarnations.update(seat.externalId, seat.continuity.flatMap(_.incarnation).get)
                if decision == Decision.Training then trainingBots.add(seat.externalId)
              }
              emit(game, decision, reasons)
            else emit(game, Decision.Unknown, Vector("nonfinite_projection"))
          else emit(game, decision, reasons)
      }
      group = Vector.empty

    while failure.isEmpty && source.hasNext do
      val game = source.next()
      sourceRows += 1
      if sourceRows > config.maxRows then failure = Some("source row limit exceeded")
      else if previous.exists { case (at, id) =>
          game.finishedAt.isBefore(at) || (game.finishedAt == at && game.gameId.compareTo(id) < 0)
        }
      then failure = Some("source must be ordered by finishedAt and gameId")
      else
        if group.headOption.exists(_.finishedAt != game.finishedAt) then flush()
        group :+= game
        if group.size > config.maxTieGroup then failure = Some("source tie-group limit exceeded")
        previous = Some((game.finishedAt, game.gameId))
    if failure.isEmpty then flush()
    failure match
      case Some(problem) => Left(problem)
      case None          =>
        val rows   = states.values.toVector.sortBy(p => (p.externalId, p.category.wireName, p.domain.wireName))
        val totals = Counts(
          sourceRows,
          counts(Decision.Competitive),
          counts(Decision.Training),
          counts(Decision.Excluded),
          counts(Decision.Unknown),
          counts(Decision.Reset)
        )
        val projectionHash = projectionDigest(rows)
        val activatable    =
          mode == Mode.ApprovedReset || (totals.unknownRows == 0 && totals.competitiveGames + totals.trainingGames > 0)
        Right(
          Plan(
            config,
            mode,
            rows,
            totals,
            hex(ledgerHash.digest()),
            projectionHash,
            activatable,
            incarnations.toMap,
            trainingBots.toSet
          )
        )
  }
