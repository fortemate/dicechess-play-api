package dicechess.play.rating

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import dicechess.play.rating.RatingEvaluation.{ModelSpec, Windows}
import dicechess.play.rating.RatingModels.{BradleyTerrySpec, GlickoSpec, WhrSpec}
import io.circe.parser.decode
import io.circe.syntax.*

import java.io.FileInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.time.{Instant, LocalDate}
import java.util.zip.GZIPInputStream
import scala.io.Source

/** Owner-facing runner for [[RatingEvaluation]] (#148) — not wired into the server. Reads the corpus export (JSONL,
  * optionally gzipped), runs every requested model chronologically, and writes `evaluation.json` and `evaluation.txt`
  * into an output directory. Never touches a database.
  *
  * Run: `mise run rating:evaluate -- corpus=<games.jsonl[.gz]> out=<dir> [models=grid|baseline] [model=<spec> ...]
  * [report-windows=training,validation[,test]] [validation-from=2026-08-17] [test-from=2026-09-01]
  * [constants=<id>,<id>] [category=blitz] [bootstrap=1000] [seed=42] [synthetic=true] [synthetic-seeds=50]
  * [revision=<git sha>]`.
  *
  * `model=` may repeat; its vocabulary is `glicko[:tau=<t>][:idle=<elo per sqrt day>][:period=game|day][:anchor=<id>]`,
  * `bt[:window=<days>]` and `whr:w=<elo per sqrt day>`. `models=grid` expands to the preregistered grid with the
  * production Glicko-2 first (the baseline every other model is compared with); `models=baseline` is that one model.
  * The test window is reported only when `report-windows` names it — the operational half of the preregistration's
  * single look at the test interval. Every option is validated as a value: an unknown key or an out-of-vocabulary value
  * is a refusal with a message and `ExitCode.Error`, never a silent default.
  */
object RatingEvaluationMain extends IOApp:

  final case class Options(
      corpus: Path,
      out: Path,
      specs: List[ModelSpec],
      config: RatingEvaluation.Config,
      synthetic: Boolean,
      syntheticSeeds: Int,
      revision: Option[String]
  )

  def run(args: List[String]): IO[ExitCode] =
    parseOptions(args) match
      case Left(problem)  => IO.println(s"[evaluate] $problem").as(ExitCode.Error) <* IO.println(Usage)
      case Right(options) => evaluate(options)

  private def evaluate(options: Options): IO[ExitCode] =
    IO.blocking(readAll[RatingReplay.Game](options.corpus)).flatMap {
      case Left(problem) => IO.println(s"[evaluate] $problem").as(ExitCode.Error)
      case Right(rows)   =>
        for
          _         <- IO.println(s"[evaluate] ${rows.size} rows; running ${options.specs.size} models")
          report    <- IO.blocking(RatingEvaluation.evaluate(rows, options.specs, options.config))
          synthetic <-
            if options.synthetic then
              IO.blocking(
                RatingEvaluationFixture
                  .evaluate(RatingEvaluationFixture.Stationary, options.specs, 1 to options.syntheticSeeds)
              ).map(Some(_))
            else IO.pure(Option.empty[RatingEvaluationFixture.Summary])
          provenance <- IO.blocking(provenanceOf(options))
          complete = report.copy(provenance = Some(provenance), synthetic = synthetic)
          outDir <- IO.blocking(Files.createDirectories(options.out))
          _      <- IO.blocking(Files.writeString(outDir.resolve("evaluation.json"), complete.asJson.spaces2, UTF_8))
          rendered = RatingEvaluation.render(complete)
          _ <- IO.blocking(Files.writeString(outDir.resolve("evaluation.txt"), rendered + "\n", UTF_8))
          _ <- IO.println(rendered)
          _ <- IO.println(s"[evaluate] wrote ${outDir.toAbsolutePath}")
        yield ExitCode.Success
    }

  private val Usage: String =
    "[evaluate] usage: corpus=<games.jsonl[.gz]> out=<dir> [models=grid|baseline] [model=<spec> ...] " +
      "[report-windows=training,validation[,test]] [validation-from=<date>] [test-from=<date>] " +
      "[constants=<id>,...] [category=blitz] [bootstrap=1000] [seed=42] [synthetic=true] [synthetic-seeds=50] " +
      "[revision=<git sha>]"

  private val Corpus         = "corpus"
  private val Out            = "out"
  private val Models         = "models"
  private val ModelKey       = "model"
  private val ReportWindows  = "report-windows"
  private val ValidationFrom = "validation-from"
  private val TestFrom       = "test-from"
  private val Constants      = "constants"
  private val Category       = "category"
  private val Bootstrap      = "bootstrap"
  private val Seed           = "seed"
  private val Synthetic      = "synthetic"
  private val SyntheticSeeds = "synthetic-seeds"
  private val Revision       = "revision"

  private val Known: Set[String] = Set(
    Corpus,
    Out,
    Models,
    ModelKey,
    ReportWindows,
    ValidationFrom,
    TestFrom,
    Constants,
    Category,
    Bootstrap,
    Seed,
    Synthetic,
    SyntheticSeeds,
    Revision
  )

  /** The identities preregistered as constant-strength on the 2026-09-10 corpus. */
  val PreregisteredConstants: Set[String] =
    Set("bot:team:rabestro:java-baseline", "bot:team:cloudflare:greedy", "bot:team:lab:rawboard-1")

  /** The anchor pinned by the `glicko:anchor=<id>` variant: a settled state at the scale's centre. */
  val AnchorState: Glicko = Glicko(1500.0, 50.0, 0.06)

  /** The preregistered validation grid; the production Glicko-2 comes first as the baseline. */
  val Grid: List[ModelSpec] = List(
    ModelSpec.Glicko(GlickoSpec()),
    ModelSpec.Glicko(GlickoSpec(tau = 0.1)),
    ModelSpec.Glicko(GlickoSpec(tau = 0.6)),
    ModelSpec.Glicko(GlickoSpec(tau = 1.2)),
    ModelSpec.Glicko(GlickoSpec(idleEloPerSqrtDay = 10.0)),
    ModelSpec.Glicko(GlickoSpec(idleEloPerSqrtDay = 30.0)),
    ModelSpec.Glicko(GlickoSpec(daily = true)),
    ModelSpec.BradleyTerry(BradleyTerrySpec()),
    ModelSpec.BradleyTerry(BradleyTerrySpec(Some(7))),
    ModelSpec.BradleyTerry(BradleyTerrySpec(Some(14))),
    ModelSpec.Whr(WhrSpec(10.0)),
    ModelSpec.Whr(WhrSpec(30.0)),
    ModelSpec.Whr(WhrSpec(100.0)),
    ModelSpec.Glicko(GlickoSpec(anchors = Map("bot:team:rabestro:java-baseline" -> AnchorState)))
  )

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
        .collectFirst { case (k, vs) if k != ModelKey && vs.sizeIs > 1 => k }
        .toLeft(())
        .left
        .map(k => s"option '$k' given more than once")
      options = pairs.filter(_._1 != ModelKey).toMap
      corpus <- options.get(Corpus).filter(_.nonEmpty).toRight(s"$Corpus=<games.jsonl[.gz]> is required")
      out    <- options.get(Out).filter(_.nonEmpty).toRight(s"$Out=<dir> is required")
      preset <- options.get(Models).fold(Right(Nil): Either[String, List[ModelSpec]]) {
        case "grid"     => Right(Grid)
        case "baseline" => Right(Grid.take(1))
        case other      => Left(s"$Models must be grid or baseline, got '$other'")
      }
      explicit <- pairs.filter(_._1 == ModelKey).map(_._2).traverse(modelSpec)
      specs = preset ++ explicit
      _       <- Either.cond(specs.nonEmpty, (), s"name at least one model ($Models=grid or $ModelKey=<spec>)")
      windows <- options.get(ReportWindows).fold(Right(RatingEvaluation.Config().reportedWindows)) { raw =>
        raw.split(',').toList.map(_.trim).traverse { w =>
          Either.cond(Windows.All.contains(w), w, s"$ReportWindows names an unknown window '$w'")
        }
      }
      validationFrom <- options
        .get(ValidationFrom)
        .fold(Right(Windows.Preregistered.validationFrom))(date(ValidationFrom))
      testFrom <- options.get(TestFrom).fold(Right(Windows.Preregistered.testFrom))(date(TestFrom))
      _        <- Either.cond(validationFrom.isBefore(testFrom), (), s"$ValidationFrom must precede $TestFrom")
      constants = options.get(Constants).fold(PreregisteredConstants)(_.split(',').map(_.trim).filter(_.nonEmpty).toSet)
      bootstrap <- options.get(Bootstrap).fold(Right(1000))(positiveInt(Bootstrap))
      seed      <- options
        .get(Seed)
        .fold(Right(42L))(raw => raw.toLongOption.toRight(s"$Seed must be an integer, got '$raw'"))
      synthetic <- options.get(Synthetic).fold(Right(false))(flag(Synthetic))
      seeds     <- options.get(SyntheticSeeds).fold(Right(50))(positiveInt(SyntheticSeeds))
    yield Options(
      corpus = Paths.get(corpus),
      out = Paths.get(out),
      specs = specs,
      config = RatingEvaluation.Config(
        windows = Windows(validationFrom, testFrom),
        constants = constants,
        category = options.getOrElse(Category, "blitz"),
        reportedWindows = windows,
        bootstrapResamples = bootstrap,
        seed = seed
      ),
      synthetic = synthetic,
      syntheticSeeds = seeds,
      revision = options.get(Revision).filter(_.nonEmpty)
    )

  /** `glicko[:tau=..][:idle=..][:period=game|day][:anchor=<id>]`, `bt[:window=<days>]`, `whr:w=<elo>`. */
  def modelSpec(raw: String): Either[String, ModelSpec] =
    val parts  = raw.split(':').toList
    val family = parts.head
    val params = parts.tail.traverse { part =>
      part.split("=", 2) match
        case Array(k, v) => Right(k -> v)
        case _           => Left(s"model parameter '$part' is not key=value")
    }
    params.flatMap { kvs =>
      family match
        case "glicko" => glickoSpec(kvs)
        case "bt"     => bradleyTerrySpec(kvs)
        case "whr"    => whrSpec(kvs)
        case other    => Left(s"unknown model family '$other' (glicko, bt, whr)")
    }

  private def glickoSpec(kvs: List[(String, String)]): Either[String, ModelSpec] =
    kvs
      .foldLeftM(GlickoSpec()) { case (spec, (key, value)) =>
        key match
          case "tau"    => positive("tau")(value).map(t => spec.copy(tau = t))
          case "idle"   => nonNegative("idle")(value).map(c => spec.copy(idleEloPerSqrtDay = c))
          case "period" =>
            value match
              case "game" => Right(spec.copy(daily = false))
              case "day"  => Right(spec.copy(daily = true))
              case other  => Left(s"period must be game or day, got '$other'")
          case "anchor" =>
            // The anchor id is `bot:team:<team>:<name>`, which contains the spec separator; it is re-joined here from the
            // pieces the caller can only give as `anchor=bot`, `team`, ... — so the runner accepts the id with '/' in
            // place of ':' instead: `anchor=bot/team/rabestro/java-baseline`.
            Right(spec.copy(anchors = spec.anchors.updated(value.replace('/', ':'), AnchorState)))
          case other => Left(s"unknown glicko parameter '$other' (tau, idle, period, anchor)")
      }
      .map(ModelSpec.Glicko(_))

  private def bradleyTerrySpec(kvs: List[(String, String)]): Either[String, ModelSpec] =
    kvs
      .foldLeftM(BradleyTerrySpec()) { case (spec, (key, value)) =>
        key match
          case "window" => positiveInt("window")(value).map(n => spec.copy(windowDays = Some(n)))
          case other    => Left(s"unknown bt parameter '$other' (window)")
      }
      .map(ModelSpec.BradleyTerry(_))

  private def whrSpec(kvs: List[(String, String)]): Either[String, ModelSpec] =
    kvs
      .foldLeftM(Option.empty[Double]) { case (_, (key, value)) =>
        key match
          case "w"   => positive("w")(value).map(Some(_))
          case other => Left(s"unknown whr parameter '$other' (w)")
      }
      .flatMap(_.toRight("whr needs w=<elo per sqrt day>"))
      .map(w => ModelSpec.Whr(WhrSpec(w)))

  private def flag(name: String)(raw: String): Either[String, Boolean] = raw match
    case "true" | "1"  => Right(true)
    case "false" | "0" => Right(false)
    case other         => Left(s"$name must be true or false, got '$other'")

  private def positive(name: String)(raw: String): Either[String, Double] =
    raw.toDoubleOption.filter(v => v > 0.0 && v.isFinite).toRight(s"$name must be a positive number, got '$raw'")

  private def nonNegative(name: String)(raw: String): Either[String, Double] =
    raw.toDoubleOption.filter(v => v >= 0.0 && v.isFinite).toRight(s"$name must be a non-negative number, got '$raw'")

  private def positiveInt(name: String)(raw: String): Either[String, Int] =
    raw.toIntOption.filter(_ > 0).toRight(s"$name must be a positive integer, got '$raw'")

  private def date(name: String)(raw: String): Either[String, LocalDate] =
    Either
      .catchOnly[java.time.format.DateTimeParseException](LocalDate.parse(raw))
      .left
      .map(_ => s"$name must be an ISO date such as 2026-08-17, got '$raw'")

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
      participantsFile = None,
      participantsSha256 = None,
      codeRevision = options.revision,
      javaRuntime = s"${System.getProperty("java.vendor")} ${System.getProperty("java.runtime.version")}",
      osArch = s"${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
      scalaVersion = scala.util.Properties.versionNumberString,
      ranAt = Instant.now()
    )

  private def sha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = new java.security.DigestInputStream(new FileInputStream(path.toFile), digest)
    try
      val buffer = new Array[Byte](1 << 16)
      while stream.read(buffer) != -1 do ()
    finally stream.close()
    digest.digest().map(b => f"$b%02x").mkString
