package dicechess.play.rating.migration

import cats.effect.{ExitCode, IO, IOApp}
import dicechess.play.rating.migration.RatingEpochPlan.*
import dicechess.play.rating.migration.RatingEpochPlan.given
import dicechess.play.store.{PgGameStore, PgRatingEpochMigration}
import doobie.Transactor
import io.circe.derivation.ConfiguredCodec
import io.circe.parser.decode
import io.circe.syntax.*

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, LinkOption, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.security.{DigestInputStream, MessageDigest}
import java.util.UUID
import scala.util.control.NonFatal

/** Explicit operator CLI for rating epochs (#151). `plan` reads immutable local JSONL and evidence artifacts only.
  * Database commands use a DriverManager transactor without Flyway or server startup; the operator installs schema
  * separately. No credentials or SQL exception text are printed. Every output directory must be new.
  */
object RatingEpochMigrationMain extends IOApp:
  private val Utf8             = StandardCharsets.UTF_8
  private val MaxDocumentBytes = 1024 * 1024
  private val MaxPlanBytes     = 512 * 1024 * 1024
  private val MaxLineChars     = 1024 * 1024
  private val Roles            = Set("classification", "continuity", "ownership", "order", "reset")

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
  final private case class InputFailure(problem: String) extends RuntimeException(problem)

  def parseOptions(args: List[String]): Either[String, Options] =
    val keys = Map(
      "plan"     -> Set("config", "source", "artifacts", "out"),
      "probe"    -> Set.empty[String],
      "pause"    -> Set("revision"),
      "resume"   -> Set("revision"),
      "stage"    -> Set("plan", "source", "artifacts", "revision", "source-sha256", "plan-sha256"),
      "activate" -> Set("run", "revision", "plan-sha256"),
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
  private def refuse(problem: String): Nothing   = throw InputFailure(problem)

  private def document(path: Path, limit: Int = MaxDocumentBytes): String =
    if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
      refuse("input document is missing or is not a regular file")
    val input = Files.newInputStream(path)
    try
      val bytes = input.readNBytes(limit + 1)
      if bytes.length > limit then refuse("input document exceeds byte limit")
      Utf8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString
    finally input.close()

  private def json[A: io.circe.Decoder](path: Path): A =
    decode[A](document(path)).fold(_ => refuse("input document does not match its versioned JSON contract"), identity)

  final private class ArtifactVerifier(dir: Path, onRead: Path => Unit):
    final private case class Verified(document: EvidenceArtifact, gameIds: Set[String])
    private val cache = new java.util.LinkedHashMap[String, Verified](32, 0.75f, true)

    private def load(expectedHash: String): Verified =
      if !expectedHash.matches("[0-9a-f]{64}") then refuse("invalid evidence artifact SHA-256")
      Option(cache.get(expectedHash)).getOrElse {
        val path = dir.resolve(s"$expectedHash.json")
        onRead(path)
        val bytes = document(path).getBytes(Utf8)
        if sha256(bytes) != expectedHash then refuse("evidence artifact bytes do not match their SHA-256")
        val evidence = decode[EvidenceArtifact](new String(bytes, Utf8))
          .fold(_ => refuse("invalid evidence artifact document"), identity)
        val verified = Verified(evidence, evidence.gameIds.toSet)
        cache.put(expectedHash, verified)
        if cache.size() > 32 then cache.remove(cache.keySet().iterator().next())
        verified
      }

    def verify(expectedHash: String, role: String, gameId: Option[String], epoch: Int): EvidenceArtifact =
      val verified = load(expectedHash)
      val evidence = verified.document
      if evidence.formatVersion != 1 || evidence.acceptedBy.trim.isEmpty || evidence.statement.trim.isEmpty ||
        evidence.roles.isEmpty || evidence.roles.exists(r => !Roles.contains(r)) || !evidence.roles.contains(role)
      then refuse("evidence artifact has no accepted assertion for the referenced use")
      if gameId.exists(id => !verified.gameIds.contains(id)) then
        refuse("evidence artifact does not cover the source game")
      if role == "reset" && !evidence.targetEpoch.contains(epoch) then
        refuse("reset approval does not cover the target epoch")
      evidence

  private def verifyGame(game: SourceGame, verifier: ArtifactVerifier, epoch: Int): Unit =
    game.classification.foreach(e => verifier.verify(e.artifactSha256, "classification", Some(game.gameId), epoch))
    Vector(game.white, game.black)
      .flatMap(_.continuity)
      .foreach(e => verifier.verify(e.artifactSha256, "continuity", Some(game.gameId), epoch))
    game.ownership.foreach(e => verifier.verify(e.artifactSha256, "ownership", Some(game.gameId), epoch))
    game.orderEvidence.foreach(e => verifier.verify(e.artifactSha256, "order", Some(game.gameId), epoch))

  final private class JsonLines(reader: BufferedReader):
    private var cached: Option[String]     = None
    private var ended                      = false
    private var line                       = 0L
    private def readLine(): Option[String] =
      val text = new StringBuilder
      var next = reader.read()
      if next < 0 then None
      else
        while next >= 0 && next != '\n' do
          if text.length >= MaxLineChars then refuse("source JSONL line exceeds character limit")
          text.append(next.toChar)
          next = reader.read()
        Some(text.toString.stripSuffix("\r"))
    val iterator: Iterator[SourceGame] = new Iterator[SourceGame]:
      def hasNext: Boolean =
        if cached.isEmpty && !ended then
          cached = readLine()
          ended = cached.isEmpty
        !ended
      def next(): SourceGame =
        if !hasNext then throw new NoSuchElementException("source exhausted")
        val value = cached.get
        cached = None
        line += 1
        decode[SourceGame](value).fold(_ => refuse(s"invalid source JSONL row at line $line"), identity)

  /** Recompute all decisions from exact input bytes. A source hash or artifact mismatch never yields a plan. */
  def reconstruct(
      config: Config,
      source: Path,
      artifacts: Path,
      onLedger: LedgerEntry => Unit = _ => (),
      onArtifactRead: Path => Unit = _ => ()
  ): Plan =
    val verifier = new ArtifactVerifier(artifacts, onArtifactRead)
    config.approvedReset.foreach { approval =>
      val evidence = verifier.verify(approval.artifactSha256, "reset", None, config.targetEpoch)
      if evidence.acceptedBy != approval.approvedBy || evidence.statement != approval.reason then
        refuse("reset approval identity/reason differs from its artifact")
      if !evidence.sourceSha256.contains(config.sourceSha256) || !evidence.modelVersion.contains(config.modelVersion) ||
        !evidence.anchorSetVersion.contains(config.anchorSetVersion) || !evidence.scope.contains(
          "all-rating-domains-and-categories"
        ) ||
        evidence.databaseSourceSha256 != config.databaseSourceSha256
      then refuse("reset approval does not bind the source, model, anchors and full rating scope")
    }
    if !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) then
      refuse("source JSONL is missing or is not a regular file")
    val hash    = MessageDigest.getInstance("SHA-256")
    val input   = new DigestInputStream(Files.newInputStream(source), hash)
    val decoder =
      Utf8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val reader = new BufferedReader(new InputStreamReader(input, decoder))
    try
      val rows = new JsonLines(reader).iterator.map { game =>
        verifyGame(game, verifier, config.targetEpoch)
        game
      }
      val plan = build(config, rows, onLedger).fold(refuse, identity)
      if hex(hash.digest()) != config.sourceSha256 then refuse("source JSONL bytes do not match config.sourceSha256")
      validate(plan).fold(refuse, identity)
      plan
    finally reader.close()

  /** Reserve a new directory before writing. On failure only temporary files created by this call are removed; existing
    * files/directories are never replaced. A failed export never publishes a plan.json.
    */
  def writePlan(files: PlanFiles): Plan =
    val config = json[Config](files.config)
    Files.createDirectory(files.out)
    val ledger     = files.out.resolve("ledger.jsonl.tmp")
    val planFile   = files.out.resolve("plan.json.tmp")
    val digestFile = files.out.resolve("plan.sha256.tmp")
    try
      val writer = Files.newBufferedWriter(ledger, Utf8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      val plan   = try
        reconstruct(
          config,
          files.source,
          files.artifacts,
          entry =>
            writer.write(entry.asJson.noSpaces)
            writer.newLine()
        )
      finally writer.close()
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
      plan
    catch
      case NonFatal(error) =>
        Vector(ledger, planFile, digestFile).foreach(p => Files.deleteIfExists(p))
        // The directory can contain published auxiliary files after a failed rename; retain them for inspection.
        val contents = Files.list(files.out)
        val empty    = try !contents.findAny().isPresent
        finally contents.close()
        if empty then Files.delete(files.out)
        throw error

  def readPlan(path: Path): Plan =
    val plan = decode[Plan](document(path, MaxPlanBytes)).fold(_ => refuse("invalid plan JSON"), identity)
    validate(plan).fold(refuse, identity)
    plan

  private def verifyPlan(options: Options): Plan =
    val values = options.values
    val plan   = readPlan(Paths.get(values("plan")))
    if !plan.config.databaseSourceSha256.contains(values("source-sha256")) then
      refuse("plan must bind the exact probed database source fingerprint before staging")
    if planDigest(plan) != values("plan-sha256") then refuse("plan digest differs from the accepted immutable plan")
    val rebuilt = reconstruct(plan.config, Paths.get(values("source")), Paths.get(values("artifacts")))
    if planDigest(rebuilt) != planDigest(plan) then refuse("plan does not reproduce from its source/evidence artifacts")
    plan

  def run(args: List[String]): IO[ExitCode] = parseOptions(args) match
    case Left(problem)  => IO.println(s"[rating-epoch] $problem").as(ExitCode.Error)
    case Right(options) =>
      val operation = if options.command == "plan" then
        IO.blocking {
          writePlan(
            PlanFiles(
              Paths.get(options.values("config")),
              Paths.get(options.values("source")),
              Paths.get(options.values("artifacts")),
              Paths.get(options.values("out"))
            )
          )
        }.flatMap(plan =>
          IO.println(
            s"[rating-epoch] plan=${planDigest(plan)} activatable=${plan.activatable} rows=${plan.rows.size} unknown=${plan.counts.unknownRows}"
          ).as(if plan.activatable then ExitCode.Success else ExitCode(3))
        )
      else database(options).as(ExitCode.Success)
      operation.handleErrorWith {
        case InputFailure(problem)                   => IO.println(s"[rating-epoch] $problem").as(ExitCode.Error)
        case PgRatingEpochMigration.Refusal(problem) => IO.println(s"[rating-epoch] $problem").as(ExitCode.Error)
        case NonFatal(_)                             =>
          IO.println("[rating-epoch] operation failed; no success is implied; inspect the isolated operator run")
            .as(ExitCode.Error)
        case other => IO.raiseError(other)
      }

  private def database(options: Options): IO[Unit] =
    PgGameStore.configFromEnv match
      case None         => IO.raiseError(InputFailure("PLAY_DB_URL is required for database commands"))
      case Some(config) =>
        val xa =
          Transactor.fromDriverManager[IO]("org.postgresql.Driver", config.url, config.user, config.password, None)
        val executor = PgRatingEpochMigration.fromTransactor(xa)
        val values   = options.values
        def revision = values("revision").toLong
        def runId    = UUID.fromString(values("run"))
        options.command match
          case "probe"  => executor.probe.flatMap(value => IO.println(value.asJson.noSpaces))
          case "pause"  => executor.setPaused(revision, true).flatMap(value => IO.println(value.asJson.noSpaces))
          case "resume" => executor.setPaused(revision, false).flatMap(value => IO.println(value.asJson.noSpaces))
          case "stage"  =>
            IO.blocking(verifyPlan(options))
              .flatMap(plan => executor.stage(plan, revision, values("source-sha256")))
              .flatMap(value => IO.println(value.asJson.noSpaces))
          case "activate" =>
            executor
              .activate(runId, revision, values("plan-sha256"))
              .flatMap(value => IO.println(value.asJson.noSpaces))
          case "rollback" => executor.rollback(runId, revision).flatMap(value => IO.println(value.asJson.noSpaces))
          case "status"   => executor.status(runId).flatMap(value => IO.println(value.asJson.noSpaces))
          case _          => IO.raiseError(InputFailure("unsupported database command"))
