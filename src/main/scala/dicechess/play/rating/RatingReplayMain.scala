package dicechess.play.rating

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import dicechess.play.core.RatingCategory
import io.circe.parser.decode
import io.circe.syntax.*

import java.io.{BufferedWriter, FileInputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.zip.GZIPInputStream
import scala.io.Source

/** Owner-facing runner for [[RatingReplay]] (#145) — NOT a public endpoint and not wired into the server. Reads a
  * corpus export (JSONL, optionally gzipped) plus its participant snapshot, replays the current rating implementation
  * over it, and writes `summary.json`, `summary.txt` and (optionally) the per-row `ledger.jsonl` into an output
  * directory. Never touches a database: the corpus IS the input, so a production replay runs against an immutable,
  * hashed export and a synthetic one against the fixture in `src/test/resources/rating-replay/`.
  *
  * Run: `mise run rating:replay -- corpus=<games.jsonl[.gz]> participants=<participants.jsonl> out=<dir> [ledger=true]
  * [order=applied|finished] [scale=per-category|single-until:<instant>[:blitz,rapid]] [resolution=current|lenient]
  * [eligibility=rules|recorded] [tau=0.3] [tau-before=0.5 tau-switch-at=<instant>] [tolerance=1e-6] [inactive-days=7]`.
  * All the logic lives in the separately unit-tested [[RatingReplay]]; this file is a thin shell, name-excluded from
  * coverage like `Main.scala`.
  */
object RatingReplayMain extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    val options = args.flatMap { arg =>
      arg.split("=", 2) match
        case Array(key, value) => Some(key.trim -> value.trim)
        case _                 => None
    }.toMap

    (options.get("corpus"), options.get("out")) match
      case (Some(corpus), Some(out)) =>
        val config = configFrom(options)
        for
          games        <- IO.blocking(readAll[RatingReplay.Game](Paths.get(corpus)))
          participants <- options
            .get("participants")
            .fold(IO.pure(List.empty[RatingReplay.Participant]))(p =>
              IO.blocking(readAll[RatingReplay.Participant](Paths.get(p)))
            )
          _        <- IO.println(s"[replay] ${games.size} rows, ${participants.size} participants; replaying")
          outcomes <- IO.blocking(RatingReplay.replay(games, config))
          summary  <- IO.blocking(RatingReplay.summarize(outcomes, participants, config))
          outDir   <- IO.blocking(Files.createDirectories(Paths.get(out)))
          _        <- IO.blocking(Files.writeString(outDir.resolve("summary.json"), summary.asJson.spaces2, UTF_8))
          rendered = RatingReplay.render(summary)
          _ <- IO.blocking(Files.writeString(outDir.resolve("summary.txt"), rendered + "\n", UTF_8))
          _ <- IO
            .blocking(writeLedger(outDir.resolve("ledger.jsonl"), outcomes))
            .whenA(options.get("ledger").exists(v => v == "true" || v == "1"))
          _ <- IO.println(rendered)
          _ <- IO.println(s"[replay] wrote ${outDir.toAbsolutePath}")
        yield ExitCode.Success
      case _ =>
        IO.println(
          "[replay] usage: corpus=<games.jsonl[.gz]> out=<dir> [participants=<participants.jsonl>] [ledger=true] " +
            "[order=applied|finished] [scale=per-category|single-until:<instant>[:blitz,rapid]] " +
            "[resolution=current|lenient] [eligibility=rules|recorded] " +
            "[tau=0.3] [tau-before=0.5 tau-switch-at=<instant>] [tolerance=1e-6] [inactive-days=7]"
        ).as(ExitCode.Error)

  private def configFrom(options: Map[String, String]): RatingReplay.Config =
    val order = options.get("order") match
      case Some("finished") => RatingReplay.Order.Finished
      case _                => RatingReplay.Order.Applied
    // `single-until:<instant>` seeds every category from the shared state; `single-until:<instant>:blitz,rapid` only
    // the listed ones (the rest start fresh).
    val scale = options.get("scale") match
      case Some(s) if s.startsWith("single-until:") =>
        val spec = s.stripPrefix("single-until:")
        val zEnd =
          spec.indexOf('Z') + 1 // the instant is UTC and ends in `Z`; anything after a following `:` is the list
        val (at, categories) =
          if zEnd > 0 && zEnd < spec.length && spec(zEnd) == ':' then (spec.take(zEnd), Some(spec.drop(zEnd + 1)))
          else (spec, None)
        val seeded = categories match
          case Some(list) => list.split(',').toList.flatMap(RatingCategory.fromWireName).toSet
          case None       => RatingCategory.values.toSet
        RatingReplay.Scale.SingleUntil(Instant.parse(at), seeded)
      case _ => RatingReplay.Scale.PerCategory
    val resolution = options.get("resolution") match
      case Some("lenient") => RatingReplay.Resolution.Lenient
      case _               => RatingReplay.Resolution.Current
    val tauAfter  = options.get("tau").flatMap(_.toDoubleOption).getOrElse(Glicko2.DefaultTau)
    val tauBefore = options.get("tau-before").flatMap(_.toDoubleOption).getOrElse(tauAfter)
    val tauSwitch = options.get("tau-switch-at").map(Instant.parse)
    RatingReplay.Config(
      order = order,
      scale = scale,
      resolution = resolution,
      tau = RatingReplay.Tau(after = tauAfter, before = tauBefore, switchAt = tauSwitch),
      tolerance = options.get("tolerance").flatMap(_.toDoubleOption).getOrElse(1e-6),
      inactiveAfterDays = options.get("inactive-days").flatMap(_.toIntOption).getOrElse(7),
      followRecorded = options.get("eligibility").contains("recorded")
    )

  private def parse[A: io.circe.Decoder](line: String): A =
    decode[A](line).fold(error => throw new IllegalArgumentException(s"corpus line does not decode: $error"), identity)

  /** Every non-empty line of a plain or gzipped UTF-8 JSONL file, decoded as it is read so only the typed rows are
    * kept: the production corpus is a few hundred thousand rows — comfortably in memory as objects, not as a second
    * copy of the text — and the replay needs a sort before it can start anyway.
    */
  private def readAll[A: io.circe.Decoder](path: Path): List[A] =
    val raw    = new FileInputStream(path.toFile)
    val stream = if path.toString.endsWith(".gz") then new GZIPInputStream(raw) else raw
    val source = Source.fromInputStream(stream, UTF_8.name)
    try source.getLines().filter(_.nonEmpty).map(parse[A]).toList
    finally source.close()

  private def writeLedger(path: Path, outcomes: Vector[RatingReplay.Outcome]): Unit =
    val writer: BufferedWriter = Files.newBufferedWriter(path, UTF_8)
    try
      outcomes.foreach { o =>
        writer.write(RatingReplay.ledgerEntry(o).asJson.noSpaces)
        writer.newLine()
      }
    finally writer.close()
