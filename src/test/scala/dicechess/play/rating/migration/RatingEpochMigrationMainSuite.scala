package dicechess.play.rating.migration

import cats.effect.{ExitCode, IO, Resource}
import dicechess.play.core.{GameOrigin, ParticipantKind, RatingDomain, RatingOutcome}
import dicechess.play.rating.{CapturedTrainingReference, Glicko, TrainingReference, TrainingReferenceSource}
import dicechess.play.rating.migration.RatingEpochMigrationMain.*
import dicechess.play.rating.migration.RatingEpochPlan.*
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import java.util.Comparator

/** Only synthetic, temporary local files. These tests never construct a database transactor. */
class RatingEpochMigrationMainSuite extends CatsEffectSuite:
  private def digest(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
  private def temporary[A](test: Path => A): A =
    val root = Files.createTempDirectory("rating-epoch-synthetic-")
    try test(root)
    finally removeTemporary(root)

  private def removeTemporary(root: Path): Unit =
    val files = Files.walk(root)
    try files.sorted(Comparator.reverseOrder()).forEach(path => Files.delete(path))
    finally files.close()

  private def temporaryIO: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("rating-epoch-cli-synthetic-")))(root =>
      IO.blocking(removeTemporary(root))
    )

  final private case class Fixture(files: PlanFiles, configValue: Config, artifact: Path, games: Vector[SourceGame])
  private def fixture(root: Path): Fixture =
    val artifacts = Files.createDirectory(root.resolve("artifacts"))
    val evidence  = EvidenceArtifact(
      1,
      "synthetic-reviewer",
      "Synthetic fixture accepted for this target epoch.",
      Vector("classification", "continuity", "ownership", "order", "reset"),
      Vector("synthetic-1", "synthetic-2"),
      Some(2)
    )
    val evidenceBytes = (evidence.asJson.spaces2 + "\n").getBytes(UTF_8)
    val evidenceHash  = digest(evidenceBytes)
    val evidencePath  = artifacts.resolve(s"$evidenceHash.json")
    Files.write(evidencePath, evidenceBytes)
    val user = SourceSeat(
      "user:00000000-0000-0000-0000-000000000001",
      ParticipantKind.Human,
      Some(IdentityContinuity(evidenceHash))
    )
    val other = SourceSeat(
      "user:00000000-0000-0000-0000-000000000002",
      ParticipantKind.Human,
      Some(IdentityContinuity(evidenceHash))
    )
    val bot = SourceSeat(
      "bot:team:synthetic:reference",
      ParticipantKind.Bot,
      Some(IdentityContinuity(evidenceHash, Some("10000000-0000-0000-0000-000000000001")))
    )
    val instant     = Instant.parse("2026-01-01T00:00:00Z")
    val competitive = SourceGame(
      "synthetic-1",
      instant,
      "Fischer(300,3)",
      user,
      other,
      Some(1),
      "king_captured",
      GameOrigin.Legacy,
      false,
      true,
      RatingOutcome.Applied,
      Some(ClassificationEvidence(2, true, true, RatingDomain.Competitive, evidenceHash)),
      None
    )
    val training = competitive.copy(
      gameId = "synthetic-2",
      finishedAt = instant.plusSeconds(1),
      black = bot,
      classification = Some(ClassificationEvidence(2, true, false, RatingDomain.Training, evidenceHash)),
      ownership = Some(OwnershipEvidence(false, evidenceHash)),
      reference =
        CapturedTrainingReference.Recorded(TrainingReference(TrainingReferenceSource.BotRating, Glicko(1650, 50, 0.06)))
    )
    val games  = Vector(competitive, training)
    val source = root.resolve("source.jsonl")
    val bytes  = games.map(_.asJson.noSpaces).mkString("", "\n", "\n").getBytes(UTF_8)
    Files.write(source, bytes)
    val config =
      Config(digest(bytes), 2, "synthetic-target-v2", instant.plusSeconds(86400), maxRows = 100, maxTieGroup = 10)
    val configPath = root.resolve("config.json")
    Files.writeString(configPath, config.asJson.spaces2, UTF_8)
    Fixture(PlanFiles(configPath, source, artifacts, root.resolve("out")), config, evidencePath, games)

  private def rejectedExportRetries(problem: String)(corrupt: Fixture => Unit): Unit = temporary { root =>
    val sample = fixture(root)
    val config = Files.readAllBytes(sample.files.config)
    val source = Files.readAllBytes(sample.files.source)
    corrupt(sample)
    assertEquals(writePlan(sample.files), Left(problem))
    // An absent reservation also proves that no published plan or temporary ledger survived.
    assert(!Files.exists(sample.files.out))
    Files.write(sample.files.config, config)
    Files.write(sample.files.source, source)
    val retried = writePlan(sample.files).toOption.get
    assert(retried.activatable)
    assertEquals(readPlan(sample.files.out.resolve("plan.json")), Right(retried))
  }

  private def stageOptions(sample: Fixture, path: Path, plan: Plan): Options = Options(
    "stage",
    Map(
      "plan"          -> path.toString,
      "source"        -> sample.files.source.toString,
      "artifacts"     -> sample.files.artifacts.toString,
      "revision"      -> "0",
      "source-sha256" -> plan.config.databaseSourceSha256.get,
      "plan-sha256"   -> planDigest(plan)
    )
  )

  private def planCommand(files: PlanFiles): List[String] = List(
    "plan",
    s"config=${files.config}",
    s"source=${files.source}",
    s"artifacts=${files.artifacts}",
    s"out=${files.out}"
  )

  test("CLI returns an error exit code for malformed input without publishing a plan"):
    temporaryIO.use { root =>
      for
        sample <- IO.blocking {
          val sample = fixture(root)
          Files.writeString(sample.files.source, "{\n", UTF_8)
          sample
        }
        exit <- RatingEpochMigrationMain.run(planCommand(sample.files))
      yield
        assertEquals(exit, ExitCode.Error)
        assert(!Files.exists(sample.files.out))
    }

  test("CLI returns success for a fully verified activatable plan"):
    temporaryIO.use { root =>
      for
        sample <- IO.blocking(fixture(root))
        exit   <- RatingEpochMigrationMain.run(planCommand(sample.files))
      yield assertEquals(exit, ExitCode.Success)
    }

  test("CLI returns the inspection exit code for an unknown classification"):
    temporaryIO.use { root =>
      for
        sample <- IO.blocking {
          val sample  = fixture(root)
          val unknown = sample.games.head.copy(classification = None)
          val bytes   = (unknown.asJson.noSpaces + "\n").getBytes(UTF_8)
          Files.write(sample.files.source, bytes)
          Files.writeString(
            sample.files.config,
            sample.configValue.copy(sourceSha256 = digest(bytes)).asJson.noSpaces,
            UTF_8
          )
          sample
        }
        exit <- RatingEpochMigrationMain.run(planCommand(sample.files))
      yield
        assertEquals(exit, ExitCode(3))
        assert(Files.exists(sample.files.out.resolve("plan.json")))
    }

  test("CLI converts a genuine output reservation I/O failure to an error exit code"):
    temporaryIO.use { root =>
      for
        sample   <- IO.blocking(fixture(root))
        first    <- RatingEpochMigrationMain.run(planCommand(sample.files))
        before   <- IO.blocking(Files.readAllBytes(sample.files.out.resolve("plan.json")).toVector)
        repeated <- RatingEpochMigrationMain.run(planCommand(sample.files))
        after    <- IO.blocking(Files.readAllBytes(sample.files.out.resolve("plan.json")).toVector)
      yield
        assertEquals(first, ExitCode.Success)
        assertEquals(repeated, ExitCode.Error)
        assertEquals(after, before)
    }

  test("a malformed JSONL row reports its line and leaves the output reusable"):
    rejectedExportRetries("invalid source JSONL row at line 2") { sample =>
      Files.writeString(sample.files.source, sample.games.head.asJson.noSpaces + "\n{\n", UTF_8)
    }

  test("an oversized JSONL line leaves no partial export and can be corrected"):
    rejectedExportRetries("source JSONL line exceeds character limit") { sample =>
      Files.writeString(sample.files.source, " " * (1024 * 1024 + 1) + "\n", UTF_8)
    }

  test("malformed UTF-8 config is a domain error and the same output can be retried"):
    rejectedExportRetries("input document is not valid UTF-8") { sample =>
      Files.write(sample.files.config, Array(0xc3.toByte, 0x28.toByte))
    }

  test("malformed UTF-8 source is a domain error and removes the reserved export"):
    rejectedExportRetries("source JSONL is not valid UTF-8 at line 1") { sample =>
      Files.write(sample.files.source, Array(0xc3.toByte, 0x28.toByte))
    }

  test("oversized config documents are rejected before publishing or reserving output"):
    rejectedExportRetries("input document exceeds byte limit") { sample =>
      Files.writeString(sample.files.config, " " * (1024 * 1024 + 1), UTF_8)
    }

  test("invalid config JSON is a domain error and can be corrected"):
    rejectedExportRetries("input document does not match its versioned JSON contract") { sample =>
      Files.writeString(sample.files.config, "{", UTF_8)
    }

  test("semantically invalid config removes temporary export files and permits retry"):
    rejectedExportRetries("target epoch and anchor version required") { sample =>
      Files.writeString(sample.files.config, sample.configValue.copy(targetEpoch = 0).asJson.noSpaces, UTF_8)
    }

  test("plan loading distinguishes invalid JSON from invalid plan semantics"):
    temporary { root =>
      val sample = fixture(root)
      val path   = root.resolve("candidate.json")
      Files.writeString(path, "{", UTF_8)
      assertEquals(readPlan(path), Left("invalid plan JSON"))
      val plan = reconstruct(sample.configValue, sample.files.source, sample.files.artifacts).toOption.get
      Files.writeString(path, plan.copy(counts = plan.counts.copy(sourceRows = -1)).asJson.noSpaces, UTF_8)
      assertEquals(readPlan(path), Left("invalid plan counts"))
      Files.writeString(path, plan.asJson.noSpaces, UTF_8)
      assertEquals(readPlan(path), Right(plan))
    }

  test("stage verification reproduces the accepted immutable plan and binds its database fingerprint"):
    temporary { root =>
      val sample = fixture(root)
      val config = sample.configValue.copy(databaseSourceSha256 = Some("c" * 64))
      val plan   = reconstruct(config, sample.files.source, sample.files.artifacts).toOption.get
      val path   = root.resolve("accepted-plan.json")
      Files.writeString(path, plan.asJson.spaces2, UTF_8)
      val bytes   = Files.readAllBytes(path).toVector
      val options = stageOptions(sample, path, plan)
      assertEquals(verifyPlan(options), Right(plan))
      assertEquals(Files.readAllBytes(path).toVector, bytes)
      assertEquals(
        verifyPlan(options.copy(values = options.values.updated("plan-sha256", "0" * 64))),
        Left("plan digest differs from the accepted immutable plan")
      )
      assertEquals(
        verifyPlan(options.copy(values = options.values.updated("source-sha256", "0" * 64))),
        Left("plan must bind the exact probed database source fingerprint before staging")
      )
    }

  test("stage refuses a structurally valid edited projection even when its accepted digest matches"):
    temporary { root =>
      val sample   = fixture(root)
      val config   = sample.configValue.copy(databaseSourceSha256 = Some("c" * 64))
      val original = reconstruct(config, sample.files.source, sample.files.artifacts).toOption.get
      val rows     = original.rows.updated(
        0,
        original.rows.head.copy(
          glicko = original.rows.head.glicko.copy(rating = original.rows.head.glicko.rating + 1)
        )
      )
      val edited = original.copy(rows = rows, projectionSha256 = projectionDigest(rows))
      assertEquals(validate(edited), Right(()))
      val path = root.resolve("edited-plan.json")
      Files.writeString(path, edited.asJson.noSpaces, UTF_8)
      assertEquals(
        verifyPlan(stageOptions(sample, path, edited)),
        Left("plan does not reproduce from its source/evidence artifacts")
      )
    }

  test("plan verifies source bytes and accepted evidence, exports a ledger and reproducible immutable digest"):
    temporary { root =>
      val sample = fixture(root)
      val plan   = writePlan(sample.files).toOption.get
      assert(plan.activatable)
      assertEquals(plan.counts.sourceRows, 2L)
      assertEquals(plan.counts.trainingGames, 1L)
      assertEquals(plan.identityIncarnations.keySet, Set(sample.games(1).black.externalId))
      assertEquals(plan.trainingBotIdentities, plan.identityIncarnations.keySet)
      val stored = decode[Plan](Files.readString(sample.files.out.resolve("plan.json"), UTF_8)).toOption.get
      assertEquals(stored, plan)
      assertEquals(Files.readString(sample.files.out.resolve("plan.sha256"), UTF_8).trim, planDigest(plan))
      val ledger = Files.readAllLines(sample.files.out.resolve("ledger.jsonl"), UTF_8)
      assertEquals(ledger.size(), 2)
      assertEquals(reconstruct(sample.configValue, sample.files.source, sample.files.artifacts), Right(plan))
    }

  test("existing output directories are refused without changing their files"):
    temporary { root =>
      val sample = fixture(root)
      writePlan(sample.files)
      val bytes = Files.readAllBytes(sample.files.out.resolve("plan.json")).toVector
      intercept[java.nio.file.FileAlreadyExistsException](writePlan(sample.files))
      assertEquals(Files.readAllBytes(sample.files.out.resolve("plan.json")).toVector, bytes)
    }

  test("source hash mismatch never publishes a plan"):
    temporary { root =>
      val sample = fixture(root)
      Files.writeString(sample.files.config, sample.configValue.copy(sourceSha256 = "0" * 64).asJson.noSpaces, UTF_8)
      assertEquals(writePlan(sample.files), Left("source JSONL bytes do not match config.sourceSha256"))
      assert(!Files.exists(sample.files.out.resolve("plan.json")))
    }

  test("a hash without a present matching artifact is not accepted as historical proof"):
    temporary { root =>
      val sample = fixture(root)
      Files.delete(sample.artifact)
      assertEquals(writePlan(sample.files), Left("input document is missing or is not a regular file"))
      assert(!Files.exists(sample.files.out.resolve("plan.json")))
      Files.writeString(sample.artifact, "{}", UTF_8)
      assertEquals(writePlan(sample.files), Left("evidence artifact bytes do not match their SHA-256"))
      assert(!Files.exists(sample.files.out.resolve("plan.json")))
    }

  test("reviewed artifacts must cover the game and evidence role, not just be parseable JSON"):
    temporary { root =>
      val sample   = fixture(root)
      val artifact = EvidenceArtifact(
        1,
        "synthetic-reviewer",
        "Unrelated synthetic evidence.",
        Vector("order"),
        Vector("other-game"),
        None
      )
      val bytes = artifact.asJson.noSpaces.getBytes(UTF_8)
      val hash  = digest(bytes)
      Files.write(sample.files.artifacts.resolve(s"$hash.json"), bytes)
      val games = sample.games.map(g => g.copy(classification = g.classification.map(_.copy(artifactSha256 = hash))))
      val sourceBytes = games.map(_.asJson.noSpaces).mkString("", "\n", "\n").getBytes(UTF_8)
      Files.write(sample.files.source, sourceBytes)
      val config = sample.configValue.copy(sourceSha256 = digest(sourceBytes))
      assertEquals(
        reconstruct(config, sample.files.source, sample.files.artifacts),
        Left("evidence artifact has no accepted assertion for the referenced use")
      )
    }

  test("source hashing includes whitespace and trailing newline bytes"):
    temporary { root =>
      val sample = fixture(root)
      val bytes  = Array(' '.toByte) ++ Files.readAllBytes(sample.files.source)
      Files.write(sample.files.source, bytes)
      assertEquals(
        reconstruct(sample.configValue, sample.files.source, sample.files.artifacts),
        Left("source JSONL bytes do not match config.sourceSha256")
      )
    }

  test("an approved reset requires the actual artifact with matching target, reviewer and reason"):
    temporary { root =>
      val sample   = fixture(root)
      val evidence = decode[EvidenceArtifact](Files.readString(sample.artifact, UTF_8)).toOption.get.copy(
        sourceSha256 = Some(sample.configValue.sourceSha256),
        modelVersion = Some(sample.configValue.modelVersion),
        anchorSetVersion = Some(sample.configValue.anchorSetVersion),
        scope = Some("all-rating-domains-and-categories")
      )
      val approvalBytes = evidence.asJson.noSpaces.getBytes(UTF_8)
      val approvalHash  = digest(approvalBytes)
      Files.write(sample.files.artifacts.resolve(s"$approvalHash.json"), approvalBytes)
      val approval = ApprovedReset(
        evidence.statement,
        evidence.acceptedBy,
        approvalHash
      )
      val config = sample.configValue.copy(approvedReset = Some(approval))
      val plan   = reconstruct(config, sample.files.source, sample.files.artifacts).toOption.get
      assertEquals(plan.mode, Mode.ApprovedReset)
      assert(plan.activatable)
      assertEquals(plan.rows, Vector.empty)
      assertEquals(
        reconstruct(
          config.copy(approvedReset = Some(approval.copy(approvedBy = "another-reviewer"))),
          sample.files.source,
          sample.files.artifacts
        ),
        Left("reset approval identity/reason differs from its artifact")
      )
      assertEquals(
        reconstruct(config.copy(targetEpoch = 3), sample.files.source, sample.files.artifacts),
        Left("reset approval does not cover the target epoch")
      )
      assertEquals(
        reconstruct(config.copy(anchorSetVersion = "different-target"), sample.files.source, sample.files.artifacts),
        Left("reset approval does not bind the source, model, anchors and full rating scope")
      )
      assertEquals(
        reconstruct(config.copy(sourceSha256 = "0" * 64), sample.files.source, sample.files.artifacts),
        Left("reset approval does not bind the source, model, anchors and full rating scope")
      )
    }

  test("unknown evidence produces an inspectable but nonactivatable plan"):
    temporary { root =>
      val sample  = fixture(root)
      val unknown = sample.games.head.copy(classification = None)
      val bytes   = (unknown.asJson.noSpaces + "\n").getBytes(UTF_8)
      Files.write(sample.files.source, bytes)
      Files.writeString(
        sample.files.config,
        sample.configValue.copy(sourceSha256 = digest(bytes)).asJson.noSpaces,
        UTF_8
      )
      val plan = writePlan(sample.files).toOption.get
      assert(!plan.activatable)
      assertEquals(plan.counts.unknownRows, 1L)
      assert(Files.exists(sample.files.out.resolve("ledger.jsonl")))
    }

  test("command parsing rejects duplicates, unknown options, invalid CAS and malformed run IDs"):
    assert(parseOptions(List("probe")).isRight)
    assert(parseOptions(List("pause", "revision=4")).isRight)
    assert(parseOptions(List("pause", "revision=-1")).isLeft)
    assert(parseOptions(List("pause", "revision=1", "revision=2")).isLeft)
    assert(parseOptions(List("probe", "unexpected=value")).isLeft)
    assert(parseOptions(List("status", "run=not-a-uuid")).isLeft)
    assert(
      parseOptions(
        List("activate", "run=00000000-0000-0000-0000-000000000001", "revision=1", "plan-sha256=unverified")
      ).isLeft
    )

  test("shared proof artifacts are read once while role and game scope checks remain per reference"):
    temporary { root =>
      val sample = fixture(root)
      var reads  = 0
      val plan   =
        reconstruct(
          sample.configValue,
          sample.files.source,
          sample.files.artifacts,
          onArtifactRead = _ => reads += 1
        ).toOption.get
      assert(plan.activatable)
      assertEquals(reads, 1)
      val narrowed = decode[EvidenceArtifact](Files.readString(sample.artifact, UTF_8)).toOption.get
        .copy(gameIds = Vector("synthetic-1"))
      val bytes = narrowed.asJson.noSpaces.getBytes(UTF_8)
      val hash  = digest(bytes)
      Files.write(sample.files.artifacts.resolve(s"$hash.json"), bytes)
      val rows = sample.games.map(g =>
        g.copy(
          white = g.white.copy(continuity = g.white.continuity.map(_.copy(artifactSha256 = hash))),
          black = g.black.copy(continuity = g.black.continuity.map(_.copy(artifactSha256 = hash))),
          classification = g.classification.map(_.copy(artifactSha256 = hash)),
          ownership = g.ownership.map(_.copy(artifactSha256 = hash))
        )
      )
      val sourceBytes = rows.map(_.asJson.noSpaces).mkString("", "\n", "\n").getBytes(UTF_8)
      Files.write(sample.files.source, sourceBytes)
      assertEquals(
        reconstruct(
          sample.configValue.copy(sourceSha256 = digest(sourceBytes)),
          sample.files.source,
          sample.files.artifacts
        ),
        Left("evidence artifact does not cover the source game")
      )
    }

  test("a participant plan larger than one MiB can be loaded for staging"):
    temporary { root =>
      val sample   = fixture(root)
      val original = reconstruct(sample.configValue, sample.files.source, sample.files.artifacts).toOption.get
      val template = original.rows.find(_.domain == RatingDomain.Competitive).get
      val rows     = Vector.tabulate(10000) { index =>
        val id = f"user:00000000-0000-0000-0000-${index + 1}%012d"
        template.copy(
          externalId = id,
          games = 1,
          wins = if index % 2 == 0 then 1 else 0,
          draws = 0,
          losses = if index % 2 == 0 then 0 else 1
        )
      }
      val large = original.copy(
        config = original.config.copy(maxRows = 10000),
        rows = rows,
        counts = Counts(5000, 5000, 0, 0, 0, 0),
        projectionSha256 = projectionDigest(rows),
        identityIncarnations = Map.empty,
        trainingBotIdentities = Set.empty
      )
      assertEquals(validate(large), Right(()))
      val path = root.resolve("large-plan.json")
      Files.writeString(path, large.asJson.spaces2, UTF_8)
      assert(Files.size(path) > 1024 * 1024)
      assertEquals(readPlan(path), Right(large))
    }
