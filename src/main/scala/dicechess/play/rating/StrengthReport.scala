package dicechess.play.rating

import dicechess.play.core.{Principal, RatingCategory}
import dicechess.play.rating.GraphConnectivity.AdmissionStatus
import dicechess.play.store.GameResultRow

import java.time.Instant

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
    ranking: List[AnchoredStrength.RankedBot],
    completePairs: Int,
    singles: Int,
    excludedRows: Int,
    anchored: AnchoredStrength.Result
) {
  def this(
      category: RatingCategory,
      pairwise: List[StrengthReport.Pairwise],
      ranking: List[AnchoredStrength.RankedBot],
      completePairs: Int,
      singles: Int,
      excludedRows: Int
  ) = this(
    category,
    pairwise,
    ranking,
    completePairs,
    singles,
    excludedRows,
    AnchoredStrength.Result(
      epoch = 1,
      anchorSetVersion = "v1.0",
      admitted = ranking,
      provisional = Nil,
      disconnected = Nil,
      connectivity = GraphConnectivity.Result(Map.empty, Map.empty, Map.empty, Set.empty, Set.empty, Nil)
    )
  )
}

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
      seed: Long = 42L,
      anchorSet: AnchorSet = AnchorSet.Default,
      windowDays: Option[Int] = None,
      minGamesForAdmission: Int = GraphConnectivity.DefaultMinGames,
      minOpponentsForAdmission: Int = GraphConnectivity.DefaultMinOpponents
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
        bootstrapIterationsRaw: Option[String],
        windowDaysRaw: Option[String] = None
    ): Config =
      val (elo0, elo1) = eloPair(elo0Raw, elo1Raw)
      Config(
        elo0 = elo0,
        elo1 = elo1,
        alpha = alphaRaw.flatMap(_.toDoubleOption).filter(a => a > 0.0 && a < 1.0).getOrElse(Default.alpha),
        beta = betaRaw.flatMap(_.toDoubleOption).filter(b => b > 0.0 && b < 1.0).getOrElse(Default.beta),
        bootstrapIterations =
          bootstrapIterationsRaw.flatMap(_.toIntOption).filter(_ > 0).getOrElse(Default.bootstrapIterations),
        windowDays = windowDaysRaw.flatMap(_.toIntOption).filter(_ > 0)
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
        sys.env.get("STRENGTH_BOOTSTRAP_ITERATIONS"),
        sys.env.get("STRENGTH_WINDOW_DAYS")
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
    // If windowDays is specified, filter rows to trailing window from the latest game
    val (activeRows, windowExcluded) = config.windowDays match
      case Some(days) =>
        val latest = rows.flatMap(r => Option(r.finishedAt)).maxOption
        latest match
          case Some(maxTime) =>
            val cutoff = maxTime.minus(java.time.Duration.ofDays(days.toLong))
            rows.partition(r => Option(r.finishedAt).exists(!_.isBefore(cutoff)))
          case None => (rows, Nil)
      case None => (rows, Nil)

    val (usable, excluded) = activeRows.partitionMap { row =>
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

    val effectiveAnchorSet =
      if config.anchorSet.category == category.wireName then config.anchorSet
      else if config.anchorSet == AnchorSet.Default then AnchorSet.unanchored(category.wireName)
      else
        throw new IllegalArgumentException(
          s"AnchorSet category '${config.anchorSet.category}' does not match report category '${category.wireName}'"
        )

    val anchoredConfig = AnchoredStrength.Config(
      anchorSet = effectiveAnchorSet,
      minGamesForAdmission = config.minGamesForAdmission,
      minOpponentsForAdmission = config.minOpponentsForAdmission,
      bootstrapIterations = config.bootstrapIterations,
      seed = config.seed
    )

    val anchoredResult = AnchoredStrength.evaluate(bootstrapGroups, anchoredConfig)

    StrengthReport(
      category = category,
      pairwise = pairwise,
      ranking = anchoredResult.allConnected,
      completePairs = completePairs.size,
      singles = singleGames.size,
      excludedRows = excluded.size + windowExcluded.size,
      anchored = anchoredResult
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

    val poolHeader = if report.anchored.admitted.nonEmpty then
      val calib = if report.anchored.isCalibrated then "" else " (uncalibrated scale)"
      List(
        "",
        s"--- Pool ranking (Bradley-Terry, anchor set ${report.anchored.anchorSetVersion}, epoch ${report.anchored.epoch}$calib) ---"
      )
    else List("", "--- Pool ranking (Bradley-Terry, relative elo, bootstrap 95% CI) ---")

    val rankRows  = if report.anchored.admitted.nonEmpty then report.anchored.admitted else report.ranking
    val rankLines = rankRows.zipWithIndex.map { (r, i) =>
      val badge =
        if r.isAnchor then " [Anchor]"
        else if report.anchored.admitted.isEmpty && r.status.isInstanceOf[AdmissionStatus.Provisional] then
          " [Provisional]"
        else ""
      val los = r.losVsNext.map(v => line("  LOS vs next %.1f%%", v * 100)).getOrElse("")
      line("%2d. %-18s%-15s %+7.1f  [%+7.1f, %+7.1f]%s", i + 1, r.player, badge, r.elo, r.ciLow, r.ciHigh, los)
    }

    val provisionalLines = if report.anchored.provisional.nonEmpty then
      List(
        "",
        s"--- Provisional bots (< ${config.minGamesForAdmission} games or < ${config.minOpponentsForAdmission} opponents) ---"
      ) ++
        report.anchored.provisional.map { r =>
          line(
            " -  %-18s %+7.1f  [%+7.1f, %+7.1f]  (%d games, %d opponents)",
            r.player,
            r.elo,
            r.ciLow,
            r.ciHigh,
            r.games,
            r.opponents
          )
        }
    else Nil

    val disconnectedLines = if report.anchored.disconnected.nonEmpty then
      List("", "--- Disconnected bots (no path to anchor set) ---") ++
        report.anchored.disconnected.map { r =>
          line(" ?  %-18s (%d games, %d opponents)", r.player, r.games, r.opponents)
        }
    else Nil

    (header ++ List("--- Pairwise SPRT (pentanomial on CRN pairs) ---") ++ pairLines ++
      poolHeader ++ rankLines ++ provisionalLines ++ disconnectedLines)
      .mkString("\n")

  /** Owner-facing Markdown rendering formatted for the internal knowledge base (Quartz / GitHub Flavored Markdown). */
  def renderMarkdown(report: StrengthReport, config: Config, generatedAt: Instant = Instant.now()): String =
    val frontmatter = List(
      "---",
      "title: Bot Strength Report",
      s"description: Retrospective Bradley-Terry pool ranking and pairwise SPRT verdicts (${report.category.wireName})",
      "draft: false",
      "tags:",
      "  - bots",
      "  - ratings",
      "  - reports",
      "---",
      ""
    )

    val windowInfo =
      config.windowDays.fold("- **Window:** All history (static control)")(w => s"- **Window:** Trailing $w days")
    val scaleInfo =
      if report.anchored.isCalibrated then
        s"- **Scale epoch:** `${report.anchored.epoch}` (Anchor Set `${report.anchored.anchorSetVersion}`)"
      else
        s"- **Scale epoch:** `${report.anchored.epoch}` (Anchor Set `${report.anchored.anchorSetVersion}`, uncalibrated)"
    val summary = List(
      "# Bot Strength Report",
      "",
      "> Retrospective statistical strength report across all registered bots on the Dice Chess ladder.",
      "> Uses the Bradley-Terry model with bootstrap confidence intervals and Sequential Probability Ratio Tests (SPRT).",
      "",
      s"- **Rating scale:** `${report.category.wireName}`",
      scaleInfo,
      windowInfo,
      line(
        "- **Observations:** %d complete pairs, %d singles (excluded rows: %d)",
        report.completePairs,
        report.singles,
        report.excludedRows
      ),
      line(
        "- **SPRT hypotheses:** H0 \"stronger by <= %.0f elo\" vs H1 \">= %.0f elo\" (alpha = beta = %.2f)",
        config.elo0,
        config.elo1,
        config.alpha
      ),
      line("- **Bootstrap resampling:** %d iterations", config.bootstrapIterations),
      s"- **Generated at:** `${generatedAt.toString}`",
      ""
    )

    val rankingHeader = List(
      "## Pool Ranking (Bradley-Terry)",
      "",
      "| Rank | Bot | Relative Elo | 95% Confidence Interval | LOS vs Next |",
      "|:---:|:---|:---:|:---:|:---:|"
    )
    val rankEntries = if report.anchored.admitted.nonEmpty then report.anchored.admitted else report.ranking
    val rankingRows = rankEntries.zipWithIndex.map { (r, i) =>
      val anchorBadge =
        if r.isAnchor then " `[Anchor]`"
        else if report.anchored.admitted.isEmpty && r.status.isInstanceOf[AdmissionStatus.Provisional] then
          " `[Provisional]`"
        else ""
      val los = r.losVsNext.map(v => line("%.1f%%", v * 100)).getOrElse("—")
      line("| %d | `%s`%s | %+.1f | [%+.1f, %+.1f] | %s |", i + 1, r.player, anchorBadge, r.elo, r.ciLow, r.ciHigh, los)
    }

    val provisionalSection = if report.anchored.provisional.nonEmpty then
      List(
        "",
        "### Provisional Bots",
        "",
        "> [!note]",
        s"> These bots have fewer than ${config.minGamesForAdmission} games or fewer than ${config.minOpponentsForAdmission} distinct opponents. Their estimates are provisional.",
        "",
        "| Bot | Relative Elo | 95% Confidence Interval | Games | Opponents |",
        "|:---|:---:|:---:|:---:|:---:|"
      ) ++ report.anchored.provisional.map { r =>
        line("| `%s` | %+.1f | [%+.1f, %+.1f] | %d | %d |", r.player, r.elo, r.ciLow, r.ciHigh, r.games, r.opponents)
      }
    else Nil

    val disconnectedSection = if report.anchored.disconnected.nonEmpty then
      List(
        "",
        "### Disconnected Bots",
        "",
        "> [!warning]",
        "> These bots do not have a comparison path to the anchor set and do not receive a global ladder rank.",
        "",
        "| Bot | Games | Opponents |",
        "|:---|:---:|:---:|"
      ) ++ report.anchored.disconnected.map { r =>
        line("| `%s` | %d | %d |", r.player, r.games, r.opponents)
      }
    else Nil

    val sprtHeader = List(
      "",
      "## Pairwise Matchups (SPRT)",
      "",
      "| Matchup | Verdict | Pairs [0..4] | Singles (W/D/L) | LLR | Bounds |",
      "|:---|:---|:---:|:---:|:---:|:---:|"
    )
    val sprtRows = report.pairwise.map { p =>
      val verdict = p.result.verdict match
        case Sprt.Verdict.AcceptH1 => s"✅ **Accept H1** (`${p.perspective}` is stronger)"
        case Sprt.Verdict.AcceptH0 => s"❌ **Accept H0** (`${p.perspective}` is not stronger)"
        case Sprt.Verdict.Continue => "⏳ **Continue** (need more games)"
      val pen        = p.pairs
      val pairsStr   = s"[${pen.n0}, ${pen.n1}, ${pen.n2}, ${pen.n3}, ${pen.n4}]"
      val singlesStr = s"${p.singles.wins} / ${p.singles.draws} / ${p.singles.losses}"
      val boundsStr  = line("[%.2f, %.2f]", p.result.lower, p.result.upper)
      line(
        "| `%s` vs `%s` | %s | %s | %s | %+.2f | %s |",
        p.perspective,
        p.opponent,
        verdict,
        pairsStr,
        singlesStr,
        p.result.llr,
        boundsStr
      )
    }

    (frontmatter ++ summary ++ rankingHeader ++ rankingRows ++ provisionalSection ++ disconnectedSection ++ sprtHeader ++ sprtRows)
      .mkString("\n")
