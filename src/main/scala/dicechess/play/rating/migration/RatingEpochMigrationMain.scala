package dicechess.play.rating.migration

import cats.effect.{ExitCode, IO, IOApp}
import cats.data.EitherT
import cats.syntax.all.*
import dicechess.play.rating.migration.RatingEpochPlan.*
import dicechess.play.rating.migration.RatingEpochPlan.given
import dicechess.play.store.{PgGameStore, PgRatingEpochMigration}
import doobie.Transactor
import io.circe.derivation.ConfiguredCodec
import io.circe.parser.decode
import io.circe.syntax.*

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, LinkOption, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.security.{DigestInputStream, MessageDigest}
import java.util.UUID
import scala.util.control.NonFatal

/** Explicit operator CLI for rating epochs (#151). `plan` reads immutable local JSONL and evidence artifacts only.
  * Database commands use a DriverManager transactor without Flyway or server startup; the operator installs schema
  * separately. No credentials or SQL exception text are printed. Every output directory must be new.
  */
object RatingEpochMigrationMain extends IOApp:
  private val Utf8               = StandardCharsets.UTF_8
  private val MaxDocumentBytes   = 1024 * 1024
  private val MaxPlanBytes       = 512 * 1024 * 1024
  private val MaxLineChars       = 1024 * 1024
  private val Roles              = Set("classification", "continuity", "ownership", "order", "reset")
  private val PlanSha256Option   = "plan-sha256"
  private val SourceSha256Option = "source-sha256"

  /** Human-reviewed evidence payload. The CLI verifies bytes, declared use and exact game scope; the evidence's factual
    * assertions must be vetted at human acceptance. Creating a hash is not an ownership/history proof.
    */
  final case class EvidenceArtifact(
      formatVersion: Int,
      acceptedBy: String,
      statement: String,
      roles: Vector[String],
      gameIds: Vector[String],
      targetEpoch: Option[Int],
      sourceSha256: Option[String] = None,
      modelVersion: Option[String] = None,
      anchorSetVersion: Option[String] = None,
      scope: Option[String] = None,
      databaseSourceSha256: Option[String] = None
  ) derives ConfiguredCodec

  final case class Options(command: String, values: Map[String, String])
  final case class PlanFiles(config: Path, source: Path, artifacts: Path, out: Path)

  def parseOptions(args: List[String]): Either[String, Options] =
    val keys = Map(
      "plan"     -> Set("config", "source", "artifacts", "out"),
      "probe"    -> Set.empty[String],
      "pause"    -> Set("revision"),
      "resume"   -> Set("revision"),
      "stage"    -> Set("plan", "source", "artifacts", "revision", SourceSha256Option, PlanSha256Option),
      "activate" -> Set("run", "revision", PlanSha256Option),
      "rollback" -> Set("run", "revision"),
      "status"   -> Set("run")
    )
    args match
      case command :: rest if keys.contains(command) =>
        val parsed =
          rest.map(_.split("=", 2)).collect { case Array(key, value) if key.nonEmpty && value.nonEmpty => key -> value }
        val values = parsed.toMap
        if parsed.size != rest.size then Left("arguments must be nonempty key=value pairs")
        else if parsed.map(_._1).distinct.size != parsed.size then Left("duplicate option")
        else if values.keySet != keys(command) then Left("missing or unsupported option for command")
        else if values.get("revision").exists(_.toLongOption.forall(_ < 0)) then
          Left("revision must be a nonnegative integer")
        else if values.get("run").exists(v => scala.util.Try(UUID.fromString(v)).isFailure) then
          Left("run must be a UUID")
        else if values.filter(_._1.endsWith("sha256")).values.exists(!_.matches("[0-9a-f]{64}")) then
          Left("invalid SHA-256 option")
        else Right(Options(command, values))
      case _ => Left("command must be plan, probe, pause, resume, stage, activate, rollback or status")

  private def hex(bytes: Array[Byte]): String    = bytes.map(b => f"${b & 0xff}%02x").mkString
  private def sha256(bytes: Array[Byte]): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
  private def ensure(condition: Boolean, problem: String): Either[String, Unit] =
    Either.cond(condition, (), problem)

  private def document(path: Path, limit: Int = MaxDocumentBytes): Either[String, String] =
    if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
      Left("input document is missing or is not a regular file")
    else
      val input = Files.newInputStream(path)
      try
        val bytes = input.readNBytes(limit + 1)
        if bytes.length > limit then Left("input document exceeds byte limit")
        else
          try
            Right(
              Utf8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString
            )
          catch case _: CharacterCodingException => Left("input document is not valid UTF-8")
      finally input.close()

  private def json[A: io.circe.Decoder](path: Path): Either[String, A] =
    document(path).flatMap(text =>
      decode[A](text).leftMap(_ => "input document does not match its versioned JSON contract")
    )

  final private class ArtifactVerifier(dir: Path, onRead: Path => Unit):
    final private case class Verified(document: EvidenceArtifact, gameIds: Set[String])
    private val cache = new java.util.LinkedHashMap[String, Verified](32, 0.75f, true)

    private def load(expectedHash: String): Either[String, Verified] =
      if !expectedHash.matches("[0-9a-f]{64}") then Left("invalid evidence artifact SHA-256")
      else
        Option(cache.get(expectedHash)) match
          case Some(verified) => Right(verified)
          case None           =>
            val path = dir.resolve(s"$expectedHash.json")
            onRead(path)
            for
              text <- document(path)
              _    <- ensure(
                sha256(text.getBytes(Utf8)) == expectedHash,
                "evidence artifact bytes do not match their SHA-256"
              )
              evidence <- decode[EvidenceArtifact](text).leftMap(_ => "invalid evidence artifact document")
            yield
              val verified = Verified(evidence, evidence.gameIds.toSet)
              cache.put(expectedHash, verified)
              if cache.size() > 32 then cache.remove(cache.keySet().iterator().next())
              verified

    def verify(
        expectedHash: String,
        role: String,
        gameId: Option[String],
        epoch: Int
    ): Either[String, EvidenceArtifact] =
      for
        verified <- load(expectedHash)
        evidence = verified.document
        _ <- ensure(
          evidence.formatVersion == 1 && evidence.acceptedBy.trim.nonEmpty && evidence.statement.trim.nonEmpty &&
            evidence.roles.nonEmpty && evidence.roles.forall(Roles.contains) && evidence.roles.contains(role),
          "evidence artifact has no accepted assertion for the referenced use"
        )
        _ <- ensure(gameId.forall(verified.gameIds.contains), "evidence artifact does not cover the source game")
        _ <- ensure(
          role != "reset" || evidence.targetEpoch.contains(epoch),
          "reset approval does not cover the target epoch"
        )
      yield evidence

  private def verifyGame(game: SourceGame, verifier: ArtifactVerifier, epoch: Int): Either[String, Unit] =
    val proofs = game.classification.toVector.map(_.artifactSha256 -> "classification") ++
      Vector(game.white, game.black).flatMap(_.continuity).map(_.artifactSha256 -> "continuity") ++
      game.ownership.toVector.map(_.artifactSha256 -> "ownership") ++
      game.orderEvidence.toVector.map(_.artifactSha256 -> "order")
    proofs.traverse_((hash, role) => verifier.verify(hash, role, Some(game.gameId), epoch).void)

  final private class JsonLines(reader: BufferedReader):
    private var ended                                      = false
    private var line                                       = 0L
    private def readLine(): Either[String, Option[String]] =
      val text = new StringBuilder
      var next = reader.read()
      if next < 0 then Right(None)
      else
        while next >= 0 && next != '\n' && text.length < MaxLineChars do
          text.append(next.toChar)
          next = reader.read()
        if next >= 0 && next != '\n' then Left("source JSONL line exceeds character limit")
        else Right(Some(text.toString.stripSuffix("\r")))

    private def next(): Either[String, Option[SourceGame]] =
      line += 1
      try
        readLine().flatMap(
          _.traverse(text => decode[SourceGame](text).leftMap(_ => s"invalid source JSONL row at line $line"))
        )
      catch case _: CharacterCodingException => Left(s"source JSONL is not valid UTF-8 at line $line")

    val iterator: Iterator[Either[String, SourceGame]] = Iterator.unfold(()) { _ =>
      if ended then None
      else
        next() match
          case Left(problem) =>
            ended = true
            Some(Left(problem) -> ())
          case Right(None) =>
            ended = true
            None
          case Right(Some(game)) => Some(Right(game) -> ())
    }

  private def verifyReset(config: Config, verifier: ArtifactVerifier): Either[String, Unit] =
    config.approvedReset.traverse_ { approval =>
      for
        evidence <- verifier.verify(approval.artifactSha256, "reset", None, config.targetEpoch)
        _        <- ensure(
          evidence.acceptedBy == approval.approvedBy && evidence.statement == approval.reason,
          "reset approval identity/reason differs from its artifact"
        )
        _ <- ensure(
          evidence.sourceSha256.contains(config.sourceSha256) && evidence.modelVersion.contains(config.modelVersion) &&
            evidence.anchorSetVersion.contains(config.anchorSetVersion) && evidence.scope.contains(
              "all-rating-domains-and-categories"
            ) &&
            evidence.databaseSourceSha256 == config.databaseSourceSha256,
          "reset approval does not bind the source, model, anchors and full rating scope"
        )
      yield ()
    }

  /** Recompute all decisions from exact input bytes. A source hash or artifact mismatch never yields a plan. */
  def reconstruct(
      config: Config,
      source: Path,
      artifacts: Path,
      onLedger: LedgerEntry => Unit = _ => (),
      onArtifactRead: Path => Unit = _ => ()
  ): Either[String, Plan] =
    val verifier = new ArtifactVerifier(artifacts, onArtifactRead)
    for
      _ <- verifyReset(config, verifier)
      _ <- ensure(
        Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS),
        "source JSONL is missing or is not a regular file"
      )
      plan <- reconstructSource(config, source, verifier, onLedger)
    yield plan

  private def reconstructSource(
      config: Config,
      source: Path,
      verifier: ArtifactVerifier,
      onLedger: LedgerEntry => Unit
  ): Either[String, Plan] =
    val hash    = MessageDigest.getInstance("SHA-256")
    val input   = new DigestInputStream(Files.newInputStream(source), hash)
    val decoder =
      Utf8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val reader = new BufferedReader(new InputStreamReader(input, decoder))
    try
      val rows = new JsonLines(reader).iterator.map(_.flatMap { game =>
        verifyGame(game, verifier, config.targetEpoch).as(game)
      })
      for
        plan <- buildChecked(config, rows, onLedger)
        _    <- ensure(hex(hash.digest()) == config.sourceSha256, "source JSONL bytes do not match config.sourceSha256")
        _    <- validate(plan)
      yield plan
    finally reader.close()

  /** Reserve a new directory before writing. On failure only temporary files created by this call are removed; existing
    * files/directories are never replaced. A failed export never publishes a plan.json.
    */
  def writePlan(files: PlanFiles): Either[String, Plan] =
    json[Config](files.config).flatMap(config => writeReservedPlan(files, config))

  private def cleanup(files: PlanFiles, temporary: Vector[Path]): Unit =
    temporary.foreach(p => Files.deleteIfExists(p))
    // Retain any published auxiliary files after a failed rename for inspection.
    val contents = Files.list(files.out)
    val empty    = try !contents.findAny().isPresent
    finally contents.close()
    if empty then Files.delete(files.out)

  private def writeReservedPlan(files: PlanFiles, config: Config): Either[String, Plan] =
    Files.createDirectory(files.out)
    val ledger     = files.out.resolve("ledger.jsonl.tmp")
    val planFile   = files.out.resolve("plan.json.tmp")
    val digestFile = files.out.resolve("plan.sha256.tmp")
    var published  = false
    try
      val writer = Files.newBufferedWriter(ledger, Utf8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      val result = try
        reconstruct(
          config,
          files.source,
          files.artifacts,
          entry =>
            writer.write(entry.asJson.noSpaces)
            writer.newLine()
        )
      finally writer.close()
      result.map { plan =>
        Files.writeString(
          planFile,
          plan.asJson.spaces2 + "\n",
          Utf8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE
        )
        Files.writeString(
          digestFile,
          planDigest(plan) + "\n",
          Utf8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE
        )
        Files.move(ledger, files.out.resolve("ledger.jsonl"), StandardCopyOption.ATOMIC_MOVE)
        Files.move(digestFile, files.out.resolve("plan.sha256"), StandardCopyOption.ATOMIC_MOVE)
        Files.move(planFile, files.out.resolve("plan.json"), StandardCopyOption.ATOMIC_MOVE)
        published = true
        plan
      }
    finally if !published then cleanup(files, Vector(ledger, planFile, digestFile))

  def readPlan(path: Path): Either[String, Plan] =
    for
      text <- document(path, MaxPlanBytes)
      plan <- decode[Plan](text).leftMap(_ => "invalid plan JSON")
      _    <- validate(plan)
    yield plan

  private[migration] def verifyPlan(options: Options): Either[String, Plan] =
    val values = options.values
    for
      plan <- readPlan(Paths.get(values("plan")))
      _    <- ensure(
        plan.config.databaseSourceSha256.contains(values(SourceSha256Option)),
        "plan must bind the exact probed database source fingerprint before staging"
      )
      _ <- ensure(planDigest(plan) == values(PlanSha256Option), "plan digest differs from the accepted immutable plan")
      rebuilt <- reconstruct(plan.config, Paths.get(values("source")), Paths.get(values("artifacts")))
      _ <- ensure(planDigest(rebuilt) == planDigest(plan), "plan does not reproduce from its source/evidence artifacts")
    yield plan

  def run(args: List[String]): IO[ExitCode] = parseOptions(args) match
    case Left(problem)  => IO.println(s"[rating-epoch] $problem").as(ExitCode.Error)
    case Right(options) =>
      val operation = if options.command == "plan" then
        EitherT(IO.blocking {
          writePlan(
            PlanFiles(
              Paths.get(options.values("config")),
              Paths.get(options.values("source")),
              Paths.get(options.values("artifacts")),
              Paths.get(options.values("out"))
            )
          )
        }).semiflatMap(plan =>
          IO.println(
            s"[rating-epoch] plan=${planDigest(plan)} activatable=${plan.activatable} rows=${plan.rows.size} unknown=${plan.counts.unknownRows}"
          ).as(if plan.activatable then ExitCode.Success else ExitCode(3))
        )
      else database(options).as(ExitCode.Success)
      operation.foldF(problem => IO.println(s"[rating-epoch] $problem").as(ExitCode.Error), IO.pure).handleErrorWith {
        case PgRatingEpochMigration.Refusal(problem) => IO.println(s"[rating-epoch] $problem").as(ExitCode.Error)
        case NonFatal(_)                             =>
          IO.println("[rating-epoch] operation failed; no success is implied; inspect the isolated operator run")
            .as(ExitCode.Error)
        case other => IO.raiseError(other)
      }

  private def database(options: Options): EitherT[IO, String, Unit] =
    PgGameStore.configFromEnv match
      case None         => EitherT.leftT[IO, Unit]("PLAY_DB_URL is required for database commands")
      case Some(config) =>
        val xa =
          Transactor.fromDriverManager[IO]("org.postgresql.Driver", config.url, config.user, config.password, None)
        val executor                                          = PgRatingEpochMigration.fromTransactor(xa)
        val values                                            = options.values
        def revision                                          = values("revision").toLong
        def runId                                             = UUID.fromString(values("run"))
        def lift(action: IO[Unit]): EitherT[IO, String, Unit] = EitherT.liftF(action)
        options.command match
          case "probe"  => lift(executor.probe.flatMap(value => IO.println(value.asJson.noSpaces)))
          case "pause"  => lift(executor.setPaused(revision, true).flatMap(value => IO.println(value.asJson.noSpaces)))
          case "resume" => lift(executor.setPaused(revision, false).flatMap(value => IO.println(value.asJson.noSpaces)))
          case "stage"  =>
            EitherT(IO.blocking(verifyPlan(options)))
              .semiflatMap(plan => executor.stage(plan, revision, values(SourceSha256Option)))
              .semiflatMap(value => IO.println(value.asJson.noSpaces))
          case "activate" =>
            lift(
              executor
                .activate(runId, revision, values(PlanSha256Option))
                .flatMap(value => IO.println(value.asJson.noSpaces))
            )
          case "rollback" =>
            lift(executor.rollback(runId, revision).flatMap(value => IO.println(value.asJson.noSpaces)))
          case "status" => lift(executor.status(runId).flatMap(value => IO.println(value.asJson.noSpaces)))
          case _        => EitherT.leftT[IO, Unit]("unsupported database command")
