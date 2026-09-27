package dicechess.play.rating

import cats.effect.{ExitCode, IO, IOApp}
import dicechess.play.core.RatingCategory
import dicechess.play.store.PgGameStore

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
      format: Format
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

      val category = pairs.get("category").flatMap(RatingCategory.fromWireName).getOrElse(RatingCategory.Default)
      val out      = pairs.get("out").map(Paths.get(_))
      val format   = pairs.get("format").map(_.toLowerCase) match
        case Some("markdown") | Some("md")                           => Format.Markdown
        case Some("text") | Some("txt")                              => Format.Text
        case _ if out.exists(_.getFileName.toString.endsWith(".md")) => Format.Markdown
        case _                                                       => Format.Text

      Options(config, category, out, format)
    else
      val config = StrengthReport.Config.fromValues(
        elo0Raw = args.headOption,
        elo1Raw = args.lift(1),
        alphaRaw = args.lift(2),
        betaRaw = args.lift(3),
        bootstrapIterationsRaw = None
      )
      Options(config, RatingCategory.Default, None, Format.Text)

  def run(args: List[String]): IO[ExitCode] =
    val options = parseOptions(args)
    PgGameStore.configFromEnv match
      case None =>
        IO.println("[ladder-report] PLAY_DB_URL unset: nothing to report on").as(ExitCode.Error)
      case Some(dbConfig) =>
        PgGameStore
          .resource(dbConfig)
          .use { store =>
            store
              .finishedRatedSince(Instant.EPOCH) // every rated game ever — the corpus IS the input
              .map(StrengthReport.build(_, options.category, options.config))
              .flatMap { report =>
                val content = options.format match
                  case Format.Markdown => StrengthReport.renderMarkdown(report, options.config)
                  case Format.Text     => StrengthReport.render(report, options.config)

                options.out match
                  case Some(path) =>
                    IO.blocking {
                      val parent = path.getParent
                      if parent != null then Files.createDirectories(parent)
                      Files.writeString(path, content, UTF_8)
                    } *> IO.println(s"[ladder-report] wrote ${options.format.toString.toLowerCase} report to $path")
                  case None =>
                    IO.println(content)
              }
          }
          .as(ExitCode.Success)
