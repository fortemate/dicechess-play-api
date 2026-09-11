package dicechess.play.rating

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import dicechess.play.core.RatingCategory
import io.circe.parser.decode
import io.circe.syntax.*

import java.io.{BufferedWriter, FileInputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
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
  * [eligibility=rules|recorded] [tau=0.3] [tau-before=0.5 tau-switch-at=<instant>] [tolerance=1e-6] [inactive-days=7]
  * [revision=<git sha>]`.
  *
  * Every option is validated as a value ([[RatingReplayMain.parseOptions]]): an explicit value the runner does not
  * understand is a refusal with a message and `ExitCode.Error`, never a silent default — a report for settings the
  * operator did not ask for is worse than no report. A corpus row that does not decode is refused the same way, with
  * its line number. The summary carries [[RatingReplay.Provenance]]: the SHA-256 of the input bytes, the revision the
  * operator names, and the runtime — so an archived report identifies what produced it. All the replay logic lives in
  * the separately unit-tested [[RatingReplay]]; this file is the shell, name-excluded from coverage like `Main.scala`.
  */
object RatingReplayMain extends IOApp:

  /** What one invocation asked for, fully validated. */
  final case class Options(
      corpus: Path,
      out: Path,
      participants: Option[Path],
      ledger: Boolean,
      revision: Option[String],
      config: RatingReplay.Config
  )

  def run(args: List[String]): IO[ExitCode] =
    parseOptions(args) match
      case Left(problem) =>
        IO.println(s"[replay] $problem").as(ExitCode.Error) <* IO.println(Usage)
      case Right(options) => replay(options)

  private def replay(options: Options): IO[ExitCode] =
    val loaded = for
      games        <- IO.blocking(readAll[RatingReplay.Game](options.corpus))
      participants <- options.participants.fold(IO.pure(Right(Nil): Either[String, List[RatingReplay.Participant]]))(
        p => IO.blocking(readAll[RatingReplay.Participant](p))
      )
    yield (games, participants).tupled
    loaded.flatMap {
      case Left(problem)                => IO.println(s"[replay] $problem").as(ExitCode.Error)
      case Right((games, participants)) =>
        for
          _          <- IO.println(s"[replay] ${games.size} rows, ${participants.size} participants; replaying")
          outcomes   <- IO.blocking(RatingReplay.replay(games, options.config))
          provenance <- IO.blocking(provenanceOf(options))
          summary    <- IO.blocking(
            RatingReplay.summarize(outcomes, participants, options.config).copy(provenance = Some(provenance))
          )
          outDir <- IO.blocking(Files.createDirectories(options.out))
          _      <- IO.blocking(Files.writeString(outDir.resolve("summary.json"), summary.asJson.spaces2, UTF_8))
          rendered = RatingReplay.render(summary)
          _ <- IO.blocking(Files.writeString(outDir.resolve("summary.txt"), rendered + "\n", UTF_8))
          _ <- IO.blocking(writeLedger(outDir.resolve("ledger.jsonl"), outcomes)).whenA(options.ledger)
          _ <- IO.println(rendered)
          _ <- IO.println(s"[replay] wrote ${outDir.toAbsolutePath}")
        yield ExitCode.Success
    }

  private val Usage: String =
    "[replay] usage: corpus=<games.jsonl[.gz]> out=<dir> [participants=<participants.jsonl>] [ledger=true] " +
      "[order=applied|finished] [scale=per-category|single-until:<instant>[:blitz,rapid]] " +
      "[resolution=current|lenient] [eligibility=rules|recorded] " +
      "[tau=0.3] [tau-before=0.5 tau-switch-at=<instant>] [tolerance=1e-6] [inactive-days=7] [revision=<git sha>]"

  // Option keys, named once so the vocabulary, the parser and the messages cannot drift.
  private val Corpus        = "corpus"
  private val Out           = "out"
  private val Participants  = "participants"
  private val Ledger        = "ledger"
  private val OrderKey      = "order"
  private val ScaleKey      = "scale"
  private val ResolutionKey = "resolution"
  private val Eligibility   = "eligibility"
  private val TauKey        = "tau"
  private val TauBefore     = "tau-before"
  private val TauSwitchAt   = "tau-switch-at"
  private val Tolerance     = "tolerance"
  private val InactiveDays  = "inactive-days"
  private val Revision      = "revision"

  private val Known: Set[String] = Set(
    Corpus,
    Out,
    Participants,
    Ledger,
    OrderKey,
    ScaleKey,
    ResolutionKey,
    Eligibility,
    TauKey,
    TauBefore,
    TauSwitchAt,
    Tolerance,
    InactiveDays,
    Revision
  )

  /** `key=value` arguments to validated [[Options]]. Pure, so the refusals are testable without running anything: an
    * argument without `=`, an unknown key, a duplicate key, or an explicit value that is not in the option's vocabulary
    * is a `Left` naming it. Absent optional keys take the replay's own defaults ([[RatingReplay.Config]]).
    */
  def parseOptions(args: List[String]): Either[String, Options] =
    for
      pairs <- args.traverse { arg =>
        arg.split("=", 2) match
          case Array(key, value) if key.trim.nonEmpty => Right(key.trim -> value.trim)
          case _                                      => Left(s"expected key=value, got '$arg'")
      }
      _ <- pairs.map(_._1).find(!Known.contains(_)).toLeft(()).left.map(k => s"unknown option '$k'")
      _ <- pairs
        .groupBy(_._1)
        .collectFirst { case (k, vs) if vs.sizeIs > 1 => k }
        .toLeft(())
        .left
        .map(k => s"option '$k' given more than once")
      options = pairs.toMap
      corpus <- options.get(Corpus).filter(_.nonEmpty).toRight(s"$Corpus=<games.jsonl[.gz]> is required")
      out    <- options.get(Out).filter(_.nonEmpty).toRight(s"$Out=<dir> is required")
      ledger <- options.get(Ledger).fold(Right(false))(flag)
      order  <- options.get(OrderKey).fold(Right(RatingReplay.Order.Applied)) {
        case "applied"  => Right(RatingReplay.Order.Applied)
        case "finished" => Right(RatingReplay.Order.Finished)
        case other      => Left(s"order must be applied or finished, got '$other'")
      }
      scale      <- options.get(ScaleKey).fold(Right(RatingReplay.Scale.PerCategory))(scaleOf)
      resolution <- options.get(ResolutionKey).fold(Right(RatingReplay.Resolution.Current)) {
        case "current" => Right(RatingReplay.Resolution.Current)
        case "lenient" => Right(RatingReplay.Resolution.Lenient)
        case other     => Left(s"resolution must be current or lenient, got '$other'")
      }
      followRecorded <- options.get(Eligibility).fold(Right(false)) {
        case "rules"    => Right(false)
        case "recorded" => Right(true)
        case other      => Left(s"eligibility must be rules or recorded, got '$other'")
      }
      tauAfter  <- options.get(TauKey).fold(Right(Glicko2.DefaultTau))(positive(TauKey))
      tauBefore <- options.get(TauBefore).fold(Right(tauAfter))(positive(TauBefore))
      tauSwitch <- options.get(TauSwitchAt).traverse(instant(TauSwitchAt))
      tolerance <- options.get(Tolerance).fold(Right(1e-6))(positive(Tolerance))
      inactive  <- options.get(InactiveDays).fold(Right(7)) { raw =>
        raw.toIntOption.filter(_ > 0).toRight(s"$InactiveDays must be a positive integer, got '$raw'")
      }
    yield Options(
      corpus = Paths.get(corpus),
      out = Paths.get(out),
      participants = options.get(Participants).filter(_.nonEmpty).map(Paths.get(_)),
      ledger = ledger,
      revision = options.get(Revision).filter(_.nonEmpty),
      config = RatingReplay.Config(
        order = order,
        scale = scale,
        resolution = resolution,
        tau = RatingReplay.Tau(after = tauAfter, before = tauBefore, switchAt = tauSwitch),
        tolerance = tolerance,
        inactiveAfterDays = inactive,
        followRecorded = followRecorded
      )
    )

  private def flag(raw: String): Either[String, Boolean] = raw match
    case "true" | "1"  => Right(true)
    case "false" | "0" => Right(false)
    case other         => Left(s"ledger must be true or false, got '$other'")

  private def positive(name: String)(raw: String): Either[String, Double] =
    raw.toDoubleOption.filter(v => v > 0.0 && v.isFinite).toRight(s"$name must be a positive number, got '$raw'")

  private def instant(name: String)(raw: String): Either[String, Instant] =
    Either
      .catchOnly[java.time.format.DateTimeParseException](Instant.parse(raw))
      .left
      .map(_ => s"$name must be an ISO-8601 UTC instant such as 2026-08-16T15:15:00Z, got '$raw'")

  /** `per-category`, `single-until:<instant>` (every category seeded from the shared state) or
    * `single-until:<instant>:blitz,rapid` (only the listed ones, the rest start fresh). The instant is UTC and ends in
    * `Z`, so the list, when present, is whatever follows the `:` after it.
    */
  private def scaleOf(raw: String): Either[String, RatingReplay.Scale] =
    if raw == "per-category" then Right(RatingReplay.Scale.PerCategory)
    else if raw.startsWith("single-until:") then
      val spec             = raw.stripPrefix("single-until:")
      val zEnd             = spec.indexOf('Z') + 1
      val (at, categories) =
        if zEnd > 0 && zEnd < spec.length && spec(zEnd) == ':' then (spec.take(zEnd), Some(spec.drop(zEnd + 1)))
        else (spec, None)
      for
        switchAt <- instant("scale=single-until")(at)
        seeded   <- categories.fold(Right(RatingCategory.values.toSet): Either[String, Set[RatingCategory]]) { list =>
          list
            .split(',')
            .toList
            .traverse(name => RatingCategory.fromWireName(name).toRight(s"scale names an unknown category '$name'"))
            .map(_.toSet)
        }
      yield RatingReplay.Scale.SingleUntil(switchAt, seeded)
    else Left(s"scale must be per-category or single-until:<instant>[:categories], got '$raw'")

  /** Every non-empty line of a plain or gzipped UTF-8 JSONL file, decoded as it is read so only the typed rows are
    * kept: the production corpus is a few hundred thousand rows — comfortably in memory as objects, not as a second
    * copy of the text — and the replay needs a sort before it can start anyway. The first undecodable line refuses the
    * whole file, by number: a corpus with one bad row is not a corpus minus one row.
    */
  private def readAll[A: io.circe.Decoder](path: Path): Either[String, List[A]] =
    val raw    = new FileInputStream(path.toFile)
    val stream = if path.toString.endsWith(".gz") then new GZIPInputStream(raw) else raw
    val source = Source.fromInputStream(stream, UTF_8.name)
    try
      source
        .getLines()
        .zipWithIndex
        .filter(_._1.nonEmpty)
        .toList
        .traverse { (line, index) =>
          decode[A](line).left.map(error => s"$path line ${index + 1} does not decode: ${error.getMessage}")
        }
    finally source.close()

  private def provenanceOf(options: Options): RatingReplay.Provenance =
    RatingReplay.Provenance(
      corpusFile = options.corpus.getFileName.toString,
      corpusSha256 = sha256(options.corpus),
      participantsFile = options.participants.map(_.getFileName.toString),
      participantsSha256 = options.participants.map(sha256),
      codeRevision = options.revision,
      javaRuntime = s"${System.getProperty("java.vendor")} ${System.getProperty("java.runtime.version")}",
      osArch = s"${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
      scalaVersion = scala.util.Properties.versionNumberString,
      ranAt = Instant.now()
    )

  /** The digest of the file's bytes as given — a gzipped corpus hashes as the `.gz`, matching the `SHA256SUMS` next to
    * it, so a report and its archive can be compared without unpacking anything.
    */
  private def sha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = new java.security.DigestInputStream(new FileInputStream(path.toFile), digest)
    try
      val buffer = new Array[Byte](1 << 16)
      while stream.read(buffer) != -1 do ()
    finally stream.close()
    digest.digest().map(b => f"$b%02x").mkString

  private def writeLedger(path: Path, outcomes: Vector[RatingReplay.Outcome]): Unit =
    val writer: BufferedWriter = Files.newBufferedWriter(path, UTF_8)
    try
      outcomes.foreach { o =>
        writer.write(RatingReplay.ledgerEntry(o).asJson.noSpaces)
        writer.newLine()
      }
    finally writer.close()
