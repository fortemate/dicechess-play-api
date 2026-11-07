package dicechess.play.rating

import cats.effect.{ExitCode, IO, IOApp}
import dicechess.play.core.RatingCategory
import dicechess.play.server.{LeaderRow, LeaderboardRoutes}
import dicechess.play.store.{GameResultRow, LeaderboardEntry, LeaderboardStore, PgGameStore}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant

/** Owner-facing E.1 (#120) report runner — NOT a public endpoint. Loads every rated decided game from `game_results`
  * (via the same store the server uses) and prints [[StrengthReport.render]] or [[StrengthReport.renderMarkdown]]:
  * pairwise SPRT verdicts plus the Bradley-Terry pool ranking.
  *
  * Run: `mise run ladder:report [elo0 elo1 alpha beta]` (positional defaults 0 20 0.05 0.05)
  * `mise run ladder:report -- [out=path.md] [format=markdown|text] [category=blitz] [iterations=1000] [elo0=0] [elo1=20] [alpha=0.05] [beta=0.05]`
  * with `PLAY_DB_URL` set — read-only against the database. All analysis logic lives in the separately unit-tested
  * `Sprt`/`BradleyTerry`/`StrengthReport`; this file is a thin shell (and is name-excluded from coverage like
  * `Main.scala`).
  */
object LadderReportMain extends IOApp:

  enum Format:
    case Text, Markdown

  final case class Options(
      config: StrengthReport.Config,
      category: RatingCategory,
      out: Option[Path],
      format: Format,
      saveSnapshot: Boolean = false
  )

  def parseOptions(args: List[String]): Options =
    val hasKeyVal = args.exists(_.contains('='))
    if hasKeyVal then
      val pairs = args.flatMap { arg =>
        arg.split("=", 2) match
          case Array(k, v) => Some(k.trim.toLowerCase -> v.trim)
          case _           => None
      }.toMap

      // The validated parser (#181): an absent, unparseable or out-of-range knob falls back to its default, and an
      // inverted elo0/elo1 pair falls back as a pair instead of producing verdicts that look valid and mean nothing.
      val config = StrengthReport.Config.fromValues(
        elo0Raw = pairs.get("elo0"),
        elo1Raw = pairs.get("elo1"),
        alphaRaw = pairs.get("alpha"),
        betaRaw = pairs.get("beta"),
        bootstrapIterationsRaw = pairs.get("iterations").orElse(pairs.get("bootstrap")),
        windowDaysRaw = pairs.get("window").orElse(pairs.get("windowdays"))
      )

      val category     = pairs.get("category").flatMap(RatingCategory.fromWireName).getOrElse(RatingCategory.Default)
      val out          = pairs.get("out").map(Paths.get(_))
      val saveSnapshot = pairs
        .get("savesnapshot")
        .orElse(pairs.get("savedb"))
        .orElse(pairs.get("snapshot"))
        .exists(v => v == "true" || v == "1" || v == "yes")
      val format = pairs.get("format").map(_.toLowerCase) match
        case Some("markdown") | Some("md")                           => Format.Markdown
        case Some("text") | Some("txt")                              => Format.Text
        case _ if out.exists(_.getFileName.toString.endsWith(".md")) => Format.Markdown
        case _                                                       => Format.Text

      Options(config, category, out, format, saveSnapshot)
    else
      val config = StrengthReport.Config.fromValues(
        elo0Raw = args.headOption,
        elo1Raw = args.lift(1),
        alphaRaw = args.lift(2),
        betaRaw = args.lift(3),
        bootstrapIterationsRaw = None
      )
      Options(config, RatingCategory.Default, None, Format.Text, saveSnapshot = false)

  private[rating] def renderReport(report: StrengthReport, options: Options): String =
    options.format match
      case Format.Markdown => StrengthReport.renderMarkdown(report, options.config)
      case Format.Text     => StrengthReport.render(report, options.config)

  private[rating] def writeReport(content: String, options: Options): IO[Unit] =
    options.out match
      case Some(path) =>
        IO.blocking {
          val parent = path.getParent
          if parent != null then Files.createDirectories(parent)
          Files.writeString(path, content, UTF_8)
        } *> IO.println(s"[ladder-report] wrote ${options.format.toString.toLowerCase} report to $path")
      case None =>
        IO.println(content)

  private[rating] def resolveEffectiveAnchor(anchorSet: AnchorSet, category: RatingCategory): AnchorSet =
    if anchorSet.category == category.wireName then anchorSet
    else if anchorSet == AnchorSet.Default then AnchorSet.unanchored(category.wireName)
    else anchorSet

  private[rating] def parsePlayerKey(player: String): (Option[String], String) =
    val idx = player.indexOf('/')
    if idx >= 0 then (Some(player.substring(0, idx)), player.substring(idx + 1))
    else (None, player)

  private[rating] def assembleLeaders(
      existingBots: List[LeaderboardEntry],
      epoch: Int
  ): List[LeaderRow] =
    val eligible = existingBots.filter(_.rd <= Glicko2.ProvisionalDeviationThreshold)
    val sorted   = eligible
      .map(e => LeaderboardRoutes.botRow(e, Some(epoch)))
      .sortBy(row => (-row.orderingValue.getOrElse(Glicko2.conservativeRating(row.rating, row.rd)), row.rd, row.name))
    sorted.zipWithIndex.map { (row, index) =>
      row.copy(rank = index + 1)
    }

  private[rating] def resolveCutoff(corpusCutoff: Option[Instant], appliedCutoff: Option[Instant]): Instant =
    (corpusCutoff, appliedCutoff) match
      case (Some(cc), Some(ac)) => if cc.isBefore(ac) then cc else ac
      case (Some(cc), None)     => cc
      case (None, Some(ac))     => ac
      case (None, None)         => Instant.now()

  private def logSaveResult(
      saveResult: LeaderboardStore.SnapshotSaveResult,
      category: RatingCategory,
      cutoff: Instant
  ): IO[Unit] =
    val msg =
      if saveResult.inserted then
        s"[ladder-report] saved new bot strength snapshot (id=${saveResult.id}) for category ${category.wireName}"
      else
        s"[ladder-report] retained existing immutable snapshot (id=${saveResult.id}) for category ${category.wireName} at cutoff $cutoff"
    IO.println(msg)

  private def doPublishSnapshot(
      store: PgGameStore,
      options: Options,
      rows: List[GameResultRow],
      effectiveAnchor: AnchorSet
  ): IO[Unit] =
    val categoryRows = rows.filter(r => RatingCategory.ofStored(r.timeControl).contains(options.category))
    val corpusCutoff = categoryRows.flatMap(r => Option(r.finishedAt)).maxOption
    for
      appliedPair <- store.latestAppliedCutoff(options.category)
      cutoff = resolveCutoff(corpusCutoff, appliedPair._1)
      existingBots <- store.leaderboard(
        options.category,
        maxRd = Glicko2.ProvisionalDeviationThreshold,
        limit = 500,
        asOfCutoff = Some(cutoff)
      )
      leaders = assembleLeaders(existingBots, effectiveAnchor.epoch)
      saveResult <- store.saveBotStrengthSnapshot(
        category = options.category,
        epoch = effectiveAnchor.epoch,
        anchorSetVersion = effectiveAnchor.version,
        cutoff = cutoff,
        leaders = leaders
      )
      _ <- logSaveResult(saveResult, options.category, cutoff)
    yield ()

  private[rating] def publishSnapshot(
      store: PgGameStore,
      options: Options,
      rows: List[GameResultRow],
      report: StrengthReport
  ): IO[Unit] =
    if !options.saveSnapshot then IO.unit
    else
      val effectiveAnchor = resolveEffectiveAnchor(options.config.anchorSet, options.category)
      if !report.anchored.isCalibrated || effectiveAnchor.epoch < 1 then
        IO.println(
          s"[ladder-report] snapshot publication refused: category ${options.category.wireName} is uncalibrated or lacks scale anchors (epoch ${effectiveAnchor.epoch})"
        )
      else doPublishSnapshot(store, options, rows, effectiveAnchor)

  def run(args: List[String]): IO[ExitCode] =
    val options = parseOptions(args)
    PgGameStore.configFromEnv match
      case None =>
        IO.println("[ladder-report] PLAY_DB_URL unset: nothing to report on").as(ExitCode.Error)
      case Some(dbConfig) =>
        PgGameStore
          .resource(dbConfig)
          .use { store =>
            for
              rows <- store.finishedRatedSince(Instant.EPOCH) // every rated game ever — the corpus IS the input
              report  = StrengthReport.build(rows, options.category, options.config)
              content = renderReport(report, options)
              _ <- writeReport(content, options)
              _ <- publishSnapshot(store, options, rows, report)
            yield ()
          }
          .as(ExitCode.Success)
