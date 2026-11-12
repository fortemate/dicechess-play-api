package dicechess.play.rating

import cats.effect.{ExitCode, IO, IOApp}
import dicechess.play.core.RatingCategory
import dicechess.play.server.{LeaderRow, LeaderboardRoutes}
import dicechess.play.store.{GameResultsStore, LeaderboardEntry, LeaderboardStore, PgGameStore}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant

/** Owner-facing E.1 (#120) report runner — NOT a public endpoint. Loads every rated decided game from `game_results`
  * (via the same store the server uses) and prints [[StrengthReport.render]] or [[StrengthReport.renderMarkdown]]:
  * pairwise SPRT verdicts plus the Bradley-Terry pool ranking.
  *
  * Run: `mise run ladder:report [elo0 elo1 alpha beta]` (positional defaults 0 20 0.05 0.05)
  * `mise run ladder:report -- [out=path.md] [format=markdown|text] [category=blitz] [iterations=1000] [elo0=0] [elo1=20] [alpha=0.05] [beta=0.05]`
  * `whiteadvantage=<elo>` sets the color offset; `maxciwidth=<elo>` supplies the approved uncertainty policy. Without
  * an explicit width policy all estimates remain provisional. These options affect only the internal report. Run with
  * `PLAY_DB_URL` set — read-only against the database. All analysis logic lives in the separately unit-tested
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

  def parseOptions(args: List[String]): Either[String, Options] =
    if args.exists(_.contains('=')) then
      val pairs = args.flatMap { arg =>
        arg.split("=", 2) match
          case Array(k, v) => Some(k.trim.toLowerCase -> v.trim)
          case _           => None
      }.toMap
      keyValueOptions(pairs)
    else
      StrengthReport.Config
        .fromValues(
          elo0Raw = args.headOption,
          elo1Raw = args.lift(1),
          alphaRaw = args.lift(2),
          betaRaw = args.lift(3),
          bootstrapIterationsRaw = None
        )
        .map(config => Options(config, RatingCategory.Default, None, Format.Text, saveSnapshot = false))

  private def keyValueOptions(pairs: Map[String, String]): Either[String, Options] =
    val category     = pairs.get("category").flatMap(RatingCategory.fromWireName).getOrElse(RatingCategory.Default)
    val saveSnapshot = pairs
      .get("savesnapshot")
      .orElse(pairs.get("savedb"))
      .orElse(pairs.get("snapshot"))
      .exists(v => v == "true" || v == "1" || v == "yes")
    for
      config <- StrengthReport.Config.fromValues(
        elo0Raw = pairs.get("elo0"),
        elo1Raw = pairs.get("elo1"),
        alphaRaw = pairs.get("alpha"),
        betaRaw = pairs.get("beta"),
        bootstrapIterationsRaw = pairs.get("iterations").orElse(pairs.get("bootstrap")),
        windowDaysRaw = pairs.get("window").orElse(pairs.get("windowdays")),
        modelPolicy = StrengthReport.Config.ModelPolicyInput(pairs.get("whiteadvantage"), pairs.get("maxciwidth"))
      )
      out <- scala.util.Try(pairs.get("out").map(Paths.get(_))).toEither.left.map(_ => "out must be a valid file path")
    yield Options(config, category, out, parseFormat(pairs.get("format"), out), saveSnapshot)

  private def parseFormat(raw: Option[String], out: Option[Path]): Format =
    raw.map(_.toLowerCase) match
      case Some("markdown") | Some("md")                                                        => Format.Markdown
      case Some("text") | Some("txt")                                                           => Format.Text
      case _ if out.exists(path => Option(path.getFileName).exists(_.toString.endsWith(".md"))) => Format.Markdown
      case _                                                                                    => Format.Text

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
      corpusCutoff: Option[Instant],
      effectiveAnchor: AnchorSet
  ): IO[Unit] =
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
      source: GameResultsStore.ReportSource,
      report: StrengthReport
  ): IO[Unit] =
    if !options.saveSnapshot then IO.unit
    else
      val effectiveAnchor = resolveEffectiveAnchor(options.config.anchorSet, options.category)
      if !report.anchored.isCalibrated || effectiveAnchor.epoch < 1 then
        IO.println(
          s"[ladder-report] snapshot publication refused: category ${options.category.wireName} is uncalibrated or lacks scale anchors (epoch ${effectiveAnchor.epoch})"
        )
      else doPublishSnapshot(store, options, source.categoryCutoff, effectiveAnchor)

  def run(args: List[String]): IO[ExitCode] =
    IO.defer {
      parseOptions(args) match
        case Left(error)    => IO.println(s"[ladder-report] $error").as(ExitCode.Error)
        case Right(options) => runWithOptions(options)
    }

  private def runWithOptions(options: Options): IO[ExitCode] =
    PgGameStore.configFromEnv match
      case None           => IO.println("[ladder-report] PLAY_DB_URL unset: nothing to report on").as(ExitCode.Error)
      case Some(dbConfig) =>
        PgGameStore.resource(dbConfig).use { store =>
          store
            .strengthReportSource(options.category, options.config.windowDays)
            .flatMap {
              case Left(error) =>
                IO.println(s"[ladder-report] invalid report source: ${error.message}").as(ExitCode.Error)
              case Right(source) => renderAndPublish(store, options, source)
            }
        }

  private[rating] def buildReport(
      source: GameResultsStore.ReportSource,
      options: Options
  ): BradleyTerry.FitResult[StrengthReport] =
    StrengthReport
      .build(source.rows, options.category, options.config)
      .map(report => report.copy(excludedRows = Math.addExact(report.excludedRows, source.excludedRows)))

  private def renderAndPublish(
      store: PgGameStore,
      options: Options,
      source: GameResultsStore.ReportSource
  ): IO[ExitCode] =
    buildReport(source, options) match
      case Left(error)   => IO.println(s"[ladder-report] strength fit failed: ${error.message}").as(ExitCode.Error)
      case Right(report) =>
        writeReport(renderReport(report, options), options) *> publishSnapshot(store, options, source, report)
          .as(ExitCode.Success)
