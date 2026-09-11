package dicechess.play.rating

import dicechess.play.core.RatingCategory
import dicechess.play.rating.RatingReplay.{Game, GlickoSnapshot, Participant, Seat}
import io.circe.syntax.*

import java.security.MessageDigest
import java.time.Instant
import scala.util.Random

/** The synthetic replay corpus (#145): a deterministic, self-consistent stand-in for the production export with the
  * same JSONL shape, every row class the ledger distinguishes, and NO production data. `generate` is the single source
  * of truth; the committed files under `src/test/resources/rating-replay/` are its output (written on one machine, on
  * the project's JDK — ratings differ in their last ulps across architectures, and `Double.toString` is only
  * shortest-representation from JDK 19) and `RatingReplaySuite` pins both their SHA-256 and their agreement with the
  * generator, so a change here is a deliberate fixture version bump.
  *
  * What the corpus contains, by design:
  *   - five bots of fixed true strength on one scale (Blitz 5+3), one of which (`fx/delta`) stops playing early
  *     (inactive offsets) and one of which (`fx/ghost`) has since been deleted (`resolvable_now = false`);
  *   - one human account that plays rated games against `fx/alpha` (human lag) and against `fx/beta`, a bot it owns
  *     (the own-bot skip); one guest that plays casual games only;
  *   - a Rapid strand (10+10) so per-category state is exercised, an `Unlimited` casual row, a rated self-play row, a
  *     draw, timeouts, an aborted row, two rated rows the batch of the day skipped under a rule that no longer exists,
  *     and two rated rows the batch had not reached (pending);
  *   - recorded before/after numbers from index [[RecordedFrom]] on, computed with the same one-game Glicko-2 step the
  *     batch uses (τ = 0.3, per-category from 1500), exactly ONE deliberately corrupted recorded value
  *     ([[CorruptedIndex]]) so the mismatch path is provably exercised, and a block of rows whose recorded levels are
  *     uniformly shifted ([[LevelShiftedRows]]) so the step-only verdict is too.
  */
object RatingReplayFixture:

  val Version: String = "synthetic-v1"

  /** First game index whose row carries recorded numbers — everything before is the "pre-#296" era. */
  val RecordedFrom: Int = 60

  /** Index of the one row whose recorded white `rating_after` is shifted by +5.0. */
  val CorruptedIndex: Int = 150

  /** Rows in this range have BOTH seats' recorded before and after shifted by [[LevelShift]]: the levels are off, the
    * steps are intact — the `match_step_only` verdict a uniformly offset pool produces.
    */
  val LevelShiftedRows: Range = 300 until 310
  val LevelShift: Double      = 0.5

  private val start = Instant.parse("2026-07-01T00:00:00Z")

  private val Alpha = "bot:team:fx:alpha"
  private val Beta  = "bot:team:fx:beta"
  private val Gamma = "bot:team:fx:gamma"
  private val Delta = "bot:team:fx:delta"
  private val Ghost = "bot:team:fx:ghost"
  private val Human = "human:0123456789abcdef"
  private val Guest = "guest:fedcba9876543210"

  private val strength =
    Map(
      Alpha -> 1800.0,
      Beta  -> 1600.0,
      Gamma -> 1400.0,
      Delta -> 1600.0,
      Ghost -> 1500.0,
      Human -> 1700.0,
      Guest -> 1500.0
    )

  final private case class Spec(
      white: String,
      black: String,
      timeControl: String = "Fischer(300,3)",
      rated: Boolean = true,
      result: Option[Int] = None, // None = sample from strengths
      termination: String = "king_captured",
      pending: Boolean = false,
      ownerRelation: Option[String] = None,
      /** The batch of the day stamped the row without moving a rating, for a reason today's rules no longer have. */
      recordedSkipped: Boolean = false
  )

  /** The scripted sequence: bot-bot rounds with the special rows spliced in at fixed indices. */
  private def specs(rnd: Random): Vector[Spec] =
    val bots   = Vector(Alpha, Beta, Gamma, Delta)
    val rounds = Vector.newBuilder[Spec]
    var i      = 0
    while i < 400 do
      val a = bots(rnd.nextInt(bots.size))
      var b = bots(rnd.nextInt(bots.size))
      while b == a do b = bots(rnd.nextInt(bots.size))
      // delta retires after the 120th scripted game — nothing of it afterwards.
      if i > 120 && (a == Delta || b == Delta) then ()
      else
        rounds += Spec(a, b, termination = if rnd.nextInt(40) == 0 then "timeout" else "king_captured")
        i += 1
    val base                            = rounds.result()
    val special: Map[Int, Vector[Spec]] = Map(
      20 -> Vector(Spec(Alpha, Alpha)), // rated self-play (pre-numeric era)
      30 -> Vector(Spec(Guest, Alpha, rated = false), Spec(Alpha, Guest, rated = false)), // guest casual
      70 -> (0 until 6).toVector.map(k =>
        Spec(if k % 2 == 0 then Ghost else Beta, if k % 2 == 0 then Beta else Ghost)
      ), // deleted bot, numeric era
      90  -> Vector(Spec(Alpha, Beta, result = Some(0), termination = "draw_agreement")),
      100 -> (0 until 10).toVector.map(k =>
        Spec(if k % 2 == 0 then Human else Alpha, if k % 2 == 0 then Alpha else Human)
      ),
      110 -> Vector(
        Spec(Human, Beta, ownerRelation = Some("white_owns_black")),
        Spec(Beta, Human, ownerRelation = Some("black_owns_white")),
        Spec(Human, Beta, ownerRelation = Some("white_owns_black"))
      ),
      120 -> Vector(Spec(Alpha, Gamma, timeControl = "Unlimited", rated = false)),
      // Two rated human games the batch of the day skipped (the retired operator-curated roster rule) that today's
      // rules would apply: `replay_applied_recorded_skipped`. Against delta, so no later game of either seat on this
      // scale is disturbed.
      121 -> Vector(Spec(Human, Delta, recordedSkipped = true), Spec(Delta, Human, recordedSkipped = true)),
      130 -> (0 until 12).toVector.map(k =>
        Spec(if k % 2 == 0 then Alpha else Gamma, if k % 2 == 0 then Gamma else Alpha, timeControl = "Fischer(600,10)")
      ),
      140 -> Vector(Spec(Beta, Gamma, rated = false, result = Some(0), termination = "aborted")),
      200 -> (0 until 4).toVector.map(k =>
        Spec(if k % 2 == 0 then Human else Gamma, if k % 2 == 0 then Gamma else Human, timeControl = "Fischer(600,10)")
      )
    )
    val out = Vector.newBuilder[Spec]
    base.zipWithIndex.foreach { case (spec, idx) =>
      special.get(idx).foreach(out ++= _)
      out += spec
    }
    out += Spec(Alpha, Beta, pending = true)
    out += Spec(Gamma, Alpha, pending = true)
    out.result()

  private def sample(rnd: Random, white: String, black: String): Int =
    val pWhite = 1.0 / (1.0 + math.pow(10.0, (strength(black) - strength(white)) / 400.0))
    if rnd.nextDouble() < pWhite then 1 else -1

  private def kindOf(id: String): String =
    if id.startsWith("bot:") then "bot" else if id.startsWith("human:") then "human" else "guest"

  /** The corpus rows and the participant snapshot, both deterministic for `seed`. */
  def generate(seed: Long = 20260911L): (Vector[Game], Vector[Participant]) =
    val rnd        = new Random(seed)
    val plan       = specs(rnd)
    val state      = scala.collection.mutable.HashMap.empty[(String, RatingCategory), Glicko]
    val games      = Vector.newBuilder[Game]
    var appliedSeq = 0L
    plan.zipWithIndex.foreach { case (spec, idx) =>
      val finishedAt = start.plusSeconds(idx.toLong * 3600L) // one game an hour: ~19 days, so inactivity is reachable
      val aborted    = spec.termination == "aborted"
      val result     = if aborted then None else Some(spec.result.getOrElse(sample(rnd, spec.white, spec.black)))
      val rated      = spec.rated && !aborted
      val category   = RatingCategory.ofStored(spec.timeControl)
      val applied    = rated && !spec.pending
      val appliedAt  = Option.when(applied)(finishedAt.plusSeconds(40L))
      if applied then appliedSeq += 1
      // The batch of the day: both seats resolvable (ghost still registered then), own-bot and self-play skipped.
      val eligible = applied && !spec.recordedSkipped && spec.white != spec.black && spec.ownerRelation.isEmpty &&
        category.isDefined && kindOf(spec.white) != "guest" && kindOf(spec.black) != "guest"
      val numeric          = eligible && idx >= RecordedFrom
      val (wb, wa, bb, ba) =
        if eligible then
          val cat    = category.get
          val scores = RatingBatch.scores(result.get).get
          val w0     = state.getOrElse((spec.white, cat), Glicko.Initial)
          val b0     = state.getOrElse((spec.black, cat), Glicko.Initial)
          val w1     = Glicko2.update(w0, List(Glicko2.Result(b0, scores._1)))
          val b1     = Glicko2.update(b0, List(Glicko2.Result(w0, scores._2)))
          state.update((spec.white, cat), w1)
          state.update((spec.black, cat), b1)
          (w0.rating, w1.rating, b0.rating, b1.rating)
        else (0.0, 0.0, 0.0, 0.0)
      val corrupt = if idx == CorruptedIndex then 5.0 else 0.0
      val shift   = if LevelShiftedRows.contains(idx) then LevelShift else 0.0
      games += Game(
        gameId = f"00000000-0000-4000-8000-${idx + 1}%012d",
        finishedAt = finishedAt,
        ratingAppliedAt = appliedAt,
        seqFinished = idx.toLong + 1,
        seqApplied = Option.when(applied)(appliedSeq),
        category = category.map(_.wireName),
        timeControl = spec.timeControl,
        result = result,
        termination = spec.termination,
        rated = rated,
        origin = if idx < 50 then "legacy" else "ladder",
        ladder = idx >= 50 && kindOf(spec.white) == "bot" && kindOf(spec.black) == "bot",
        pairingId = None,
        applied = applied,
        numericRecorded = numeric,
        white = Seat(
          spec.white,
          kindOf(spec.white),
          spec.white != Ghost,
          Option.when(numeric)(wb + shift),
          Option.when(numeric)(wa + corrupt + shift)
        ),
        black = Seat(
          spec.black,
          kindOf(spec.black),
          spec.black != Ghost,
          Option.when(numeric)(bb + shift),
          Option.when(numeric)(ba + shift)
        ),
        ownerRelation = spec.ownerRelation,
        hasArchive = idx >= 10,
        archiveSportingEligible = Option.when(idx >= 10)(!aborted)
      )
    }
    val participants = Vector(Alpha, Beta, Gamma, Delta).map { id =>
      Participant(
        kind = "bot",
        id = id,
        ratings = state.collect { case ((pid, cat), g) if pid == id => cat.wireName -> GlickoSnapshot.of(g) }.toMap,
        onLadder = Some(id != Delta),
        owner = Option.when(id == Beta)(Human)
      )
    } :+ Participant(
      kind = "human",
      id = Human,
      ratings = state.collect { case ((pid, cat), g) if pid == Human => cat.wireName -> GlickoSnapshot.of(g) }.toMap,
      isActive = Some(true)
    )
    (games.result(), participants)

  def gamesJsonl(games: Vector[Game]): String            = games.map(_.asJson.noSpaces).mkString("", "\n", "\n")
  def participantsJsonl(ps: Vector[Participant]): String = ps.map(_.asJson.noSpaces).mkString("", "\n", "\n")

  def sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")).map(b => f"$b%02x").mkString
