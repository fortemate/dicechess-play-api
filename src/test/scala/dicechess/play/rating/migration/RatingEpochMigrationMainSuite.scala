package dicechess.play.rating.migration

import dicechess.play.core.{GameOrigin, ParticipantKind, RatingDomain, RatingOutcome}
import dicechess.play.rating.{CapturedTrainingReference, Glicko, TrainingReference, TrainingReferenceSource}
import dicechess.play.rating.migration.RatingEpochMigrationMain.*
import dicechess.play.rating.migration.RatingEpochPlan.*
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import java.util.Comparator

/** Only synthetic, temporary local files. These tests never construct a database transactor. */
class RatingEpochMigrationMainSuite extends FunSuite:
  private def digest(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
  private def temporary[A](test: Path => A): A =
    val root = Files.createTempDirectory("rating-epoch-synthetic-")
    try test(root)
    finally
      val files = Files.walk(root)
      try files.sorted(Comparator.reverseOrder()).forEach(path => Files.delete(path))
      finally files.close()

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

  test("plan verifies source bytes and accepted evidence, exports a ledger and reproducible immutable digest"):
    temporary { root =>
      val sample = fixture(root)
      val plan   = writePlan(sample.files)
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
      assertEquals(reconstruct(sample.configValue, sample.files.source, sample.files.artifacts), plan)
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
      intercept[RuntimeException](writePlan(sample.files))
      assert(!Files.exists(sample.files.out.resolve("plan.json")))
    }

  test("a hash without a present matching artifact is not accepted as historical proof"):
    temporary { root =>
      val sample = fixture(root)
      Files.delete(sample.artifact)
      intercept[RuntimeException](writePlan(sample.files))
      assert(!Files.exists(sample.files.out.resolve("plan.json")))
      Files.writeString(sample.artifact, "{}", UTF_8)
      intercept[RuntimeException](writePlan(sample.files))
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
      intercept[RuntimeException](reconstruct(config, sample.files.source, sample.files.artifacts))
    }

  test("source hashing includes whitespace and trailing newline bytes"):
    temporary { root =>
      val sample = fixture(root)
      val bytes  = Array(' '.toByte) ++ Files.readAllBytes(sample.files.source)
      Files.write(sample.files.source, bytes)
      intercept[RuntimeException](reconstruct(sample.configValue, sample.files.source, sample.files.artifacts))
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
      val plan   = reconstruct(config, sample.files.source, sample.files.artifacts)
      assertEquals(plan.mode, Mode.ApprovedReset)
      assert(plan.activatable)
      assertEquals(plan.rows, Vector.empty)
      intercept[RuntimeException](
        reconstruct(
          config.copy(approvedReset = Some(approval.copy(approvedBy = "another-reviewer"))),
          sample.files.source,
          sample.files.artifacts
        )
      )
      intercept[RuntimeException](
        reconstruct(config.copy(targetEpoch = 3), sample.files.source, sample.files.artifacts)
      )
      intercept[RuntimeException](
        reconstruct(config.copy(anchorSetVersion = "different-target"), sample.files.source, sample.files.artifacts)
      )
      intercept[RuntimeException](
        reconstruct(config.copy(sourceSha256 = "0" * 64), sample.files.source, sample.files.artifacts)
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
      val plan = writePlan(sample.files)
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
        reconstruct(sample.configValue, sample.files.source, sample.files.artifacts, onArtifactRead = _ => reads += 1)
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
      intercept[RuntimeException](
        reconstruct(
          sample.configValue.copy(sourceSha256 = digest(sourceBytes)),
          sample.files.source,
          sample.files.artifacts
        )
      )
    }

  test("a participant plan larger than one MiB can be loaded for staging"):
    temporary { root =>
      val sample   = fixture(root)
      val original = reconstruct(sample.configValue, sample.files.source, sample.files.artifacts)
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
      assertEquals(readPlan(path), large)
    }
