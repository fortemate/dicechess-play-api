package dicechess.play.rating

import dicechess.play.core.{Principal, RatingCategory}
import dicechess.play.store.GameResultRow

/** The E.1 (#120) strength report over `game_results`: pairwise SPRT verdicts on CRN pairs plus a Bradley-Terry pool
  * ranking — assembled purely from rows, so the whole pipeline is unit-testable without a database. The thin
  * `LadderReportMain` runner just loads rows and prints [[StrengthReport.render]].
  *
  * CRN pairs are historical only (#190 dropped the mirrored-pair mechanism that created them); the grouping logic below
  * is otherwise unchanged and still scores any it finds, exactly as it always has — new rows simply never form one and
  * degrade to trinomial singles, which every part of this pipeline already treats as first-class.
  */
final case class StrengthReport(
    /** The rating scale this report covers (#280). Carried on the report itself, not passed alongside it: the report is
      * CACHED (`StrengthCache`) and read back by a route that has no other way to learn which category produced it, and
      * `excludedRows` now counts every other category's games — a number nobody can interpret without this.
      */
    category: RatingCategory,
    pairwise: List[StrengthReport.Pairwise],
    ranking: List[BradleyTerry.Ranked],
    completePairs: Int,
    singles: Int,
    excludedRows: Int
)

object StrengthReport:

  /** SPRT of one unordered bot matchup, from `perspective`'s side (the lexicographically smaller external id — fixed,
    * so re-runs render identically).
    */
  final case class Pairwise(
      perspective: String,
      opponent: String,
      pairs: Sprt.Pentanomial,
      singles: Sprt.Trinomial,
      result: Sprt.Result
  )

  final case class Config(
      elo0: Double = 0.0,
      elo1: Double = 20.0,
      alpha: Double = 0.05,
      beta: Double = 0.05,
      bootstrapIterations: Int = 1000,
      seed: Long = 42L
  )

  object Config:
    val Default: Config = Config()

    /** Parse from explicit optional raw values (#181) — every knob falls back to [[Default]] on an absent or
      * unparseable value; unlike `RatingBatch`/`LadderScheduler`, there is no primary on/off var here, because the
      * `/strength` route's own persistence gate already covers enable/disable, so every one of these is a tuning knob,
      * never a switch. `seed` is deliberately not among them: it governs bootstrap reproducibility, not statistical
      * trust, so a deployment has no legitimate reason to change it — see `StrengthCache`.
      *
      * `alpha`/`beta` are filtered to the open interval `(0, 1)`: they are error rates that feed `math.log` in
      * [[Sprt.test]], so `0` or `1` would silently produce an infinite or `NaN` LLR forever rather than fail loudly.
      * `elo0`/`elo1` are validated together, not independently — see [[eloPair]].
      */
    def fromValues(
        elo0Raw: Option[String],
        elo1Raw: Option[String],
        alphaRaw: Option[String],
        betaRaw: Option[String],
        bootstrapIterationsRaw: Option[String]
    ): Config =
      val (elo0, elo1) = eloPair(elo0Raw, elo1Raw)
      Config(
        elo0 = elo0,
        elo1 = elo1,
        alpha = alphaRaw.flatMap(_.toDoubleOption).filter(a => a > 0.0 && a < 1.0).getOrElse(Default.alpha),
        beta = betaRaw.flatMap(_.toDoubleOption).filter(b => b > 0.0 && b < 1.0).getOrElse(Default.beta),
        bootstrapIterations =
          bootstrapIterationsRaw.flatMap(_.toIntOption).filter(_ > 0).getOrElse(Default.bootstrapIterations)
      )

    /** `elo0`/`elo1` are [[Sprt.test]]'s ordered H0/H1 bounds ("stronger by <= elo0" vs "stronger by >= elo1"): parsing
      * them independently — each falling back to its own default on its own — can silently produce an inverted or
      * degenerate pair (e.g. only `STRENGTH_ELO0=30` set combines with the untouched `elo1` default of `20` into
      * `elo0 > elo1`). [[Sprt.test]] accepts that pair without error, but its alpha/beta error-rate guarantee no longer
      * holds — the verdict would look valid and mean nothing.
      *
      * Each side still falls back to its OWN default independently when unset, non-finite (guards a literal
      * `"Infinity"`, which `toDoubleOption` parses), or unparseable — a deliberate, sensible partial override (e.g.
      * only `STRENGTH_ELO0=5`, narrowing the gap against the untouched `elo1` default) is valid and kept. Only the
      * resulting PAIR is rejected — as a pair, back to both complete defaults — when it fails `elo0 < elo1`.
      */
    private def eloPair(elo0Raw: Option[String], elo1Raw: Option[String]): (Double, Double) =
      val elo0 = elo0Raw.flatMap(_.toDoubleOption).filter(_.isFinite).getOrElse(Default.elo0)
      val elo1 = elo1Raw.flatMap(_.toDoubleOption).filter(_.isFinite).getOrElse(Default.elo1)
      if elo0 < elo1 then (elo0, elo1) else (Default.elo0, Default.elo1)

    def configFromEnv: Config =
      fromValues(
        sys.env.get("STRENGTH_ELO0"),
        sys.env.get("STRENGTH_ELO1"),
        sys.env.get("STRENGTH_ALPHA"),
        sys.env.get("STRENGTH_BETA"),
        sys.env.get("STRENGTH_BOOTSTRAP_ITERATIONS")
      )

  /** A usable observation: both seats are registered-bot ids and the game is decided. */
  final private case class BotGame(white: Principal.Bot, black: Principal.Bot, whiteScore: Double)

  /** `category` scopes the fold to one rating scale (#280): a report that mixed speeds would be comparing a bot's 1+1
    * results with its 10+10 ones and calling the mixture its strength — exactly the conflation per-category ratings
    * exist to end. Rows from other categories join `excludedRows` rather than being dropped silently, so the count
    * still reconciles against the corpus.
    *
    * In practice the caller passes the LADDER's category, and in practice that changes nothing about today's numbers:
    * the ladder plays one control and it is essentially the entire rated corpus.
    */
  def build(
      rows: List[GameResultRow],
      category: RatingCategory,
      config: Config = Config()
  ): StrengthReport =
    val (usable, excluded) = rows.partitionMap { row =>
      (
        Principal.fromBotExternalId(row.whiteExternalId),
        Principal.fromBotExternalId(row.blackExternalId),
        row.result
      ) match
        case (Some(white), Some(black), Some(result))
            if white != black && RatingCategory.ofStored(row.timeControl).contains(category) =>
          val whiteScore = result match
            case 1  => 1.0
            case -1 => 0.0
            case _  => 0.5
          Left(row.pairingId -> BotGame(white, black, whiteScore))
        case _ => Right(())
    }

    // CRN groups: a pairing id shared by exactly two usable games of the same unordered matchup is a complete pair
    // (ONE pentanomial observation); everything else degrades to per-game trinomial singles.
    val (paired, unpaired) = usable.partition(_._1.isDefined)
    val groups             = paired.groupBy(_._1).values.map(_.map(_._2)).toList
    // Strictly a COLOUR-SWAPPED pair: same two bots with seats exchanged — the pentanomial observation model
    // assumes the swap (that's what cancels colour bias inside the pair), so a hypothetical same-colour "pair"
    // must degrade to singles rather than pollute the pair statistics.
    val (completePairs, brokenGroups) = groups.partition {
      case List(g1, g2) => g1.white == g2.black && g1.black == g2.white
      case _            => false
    }
    val singleGames = unpaired.map(_._2) ++ brokenGroups.flatten

    def key(a: Principal.Bot, b: Principal.Bot): (Principal.Bot, Principal.Bot) =
      if a.externalId <= b.externalId then (a, b) else (b, a)

    val pentaByPair = completePairs
      .groupBy(games => key(games.head.white, games.head.black))
      .map { case ((perspective, opponent), pairs) =>
        val bins = pairs.groupBy { games =>
          val score = games.map(g => if g.white == perspective then g.whiteScore else 1.0 - g.whiteScore).sum
          (score * 2).round.toInt // 0..4
        }
        def n(i: Int): Long = bins.getOrElse(i, Nil).size.toLong
        (perspective, opponent) -> Sprt.Pentanomial(n(0), n(1), n(2), n(3), n(4))
      }

    val triByPair = singleGames
      .groupBy(game => key(game.white, game.black))
      .map { case ((perspective, opponent), games) =>
        val scores = games.map(g => if g.white == perspective then g.whiteScore else 1.0 - g.whiteScore)
        (perspective, opponent) -> Sprt.Trinomial(
          losses = scores.count(_ == 0.0),
          draws = scores.count(_ == 0.5),
          wins = scores.count(_ == 1.0)
        )
      }

    val pairwise = (pentaByPair.keySet ++ triByPair.keySet).toList
      .map { matchup =>
        val (perspective, opponent) = matchup
        val penta                   = pentaByPair.getOrElse(matchup, Sprt.Pentanomial.Empty)
        val tri                     = triByPair.getOrElse(matchup, Sprt.Trinomial.Empty)
        Pairwise(
          display(perspective),
          display(opponent),
          penta,
          tri,
          Sprt.test(penta, tri, config.elo0, config.elo1, config.alpha, config.beta)
        )
      }
      .sortBy(p => (-p.result.observations, p.perspective, p.opponent))

    // Bootstrap resampling units mirror the observation structure: a complete pair travels as one unit.
    val bootstrapGroups: Seq[Seq[BradleyTerry.Game]] =
      completePairs.map(_.map(toBtGame)) ++ singleGames.map(g => List(toBtGame(g)))

    StrengthReport(
      category = category,
      pairwise = pairwise,
      ranking = BradleyTerry.rankedWithBootstrap(bootstrapGroups, config.bootstrapIterations, config.seed),
      completePairs = completePairs.size,
      singles = singleGames.size,
      excludedRows = excluded.size
    )

  private def toBtGame(game: BotGame): BradleyTerry.Game =
    (display(game.white), display(game.black), game.whiteScore)

  private def display(bot: Principal.Bot): String = s"${bot.team}/${bot.name}"

  /** `String.format` pinned to `Locale.ROOT`: the report must render identically everywhere (dot decimals), not follow
    * whatever locale the operator's JVM happens to boot with — `f""` interpolators use the default locale.
    */
  private def line(pattern: String, args: Any*): String =
    String.format(java.util.Locale.ROOT, pattern, args.map(_.asInstanceOf[Object])*)

  /** The owner-facing plain-text rendering (the runner prints this verbatim). */
  def render(report: StrengthReport, config: Config): String =
    val header = List(
      s"=== Dice Chess ladder — strength report (${report.category.wireName}) ===",
      line(
        "observations: %d CRN pairs + %d singles (excluded rows: %d, other categories included)",
        report.completePairs,
        report.singles,
        report.excludedRows
      ),
      line(
        "SPRT hypotheses: H0 \"stronger by <= %.0f elo\" vs H1 \">= %.0f elo\", alpha=beta=%.2f",
        config.elo0,
        config.elo1,
        config.alpha
      ),
      ""
    )
    val pairLines = report.pairwise.map { p =>
      val verdict = p.result.verdict match
        case Sprt.Verdict.AcceptH1 => s"ACCEPT H1 — ${p.perspective} is stronger"
        case Sprt.Verdict.AcceptH0 => s"ACCEPT H0 — ${p.perspective} is not stronger"
        case Sprt.Verdict.Continue => "CONTINUE — need more games"
      val pen = p.pairs
      line(
        "%-18s vs %-18s pairs[%d,%d,%d,%d,%d] singles(w/d/l %d/%d/%d)  LLR %+.2f in [%.2f, %.2f]  %s",
        p.perspective,
        p.opponent,
        pen.n0,
        pen.n1,
        pen.n2,
        pen.n3,
        pen.n4,
        p.singles.wins,
        p.singles.draws,
        p.singles.losses,
        p.result.llr,
        p.result.lower,
        p.result.upper,
        verdict
      )
    }
    val rankLines = report.ranking.zipWithIndex.map { (r, i) =>
      val los = r.losVsNext.map(v => line("  LOS vs next %.1f%%", v * 100)).getOrElse("")
      line("%2d. %-18s %+7.1f  [%+7.1f, %+7.1f]%s", i + 1, r.player, r.elo, r.ciLow, r.ciHigh, los)
    }
    (header ++ List("--- Pairwise SPRT (pentanomial on CRN pairs) ---") ++ pairLines ++
      List("", "--- Pool ranking (Bradley-Terry, relative elo, bootstrap 95% CI) ---") ++ rankLines)
      .mkString("\n")
