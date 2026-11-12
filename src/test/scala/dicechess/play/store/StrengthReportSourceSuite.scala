package dicechess.play.store

import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.ExecutionContexts
import doobie.util.fragment.Fragment
import doobie.postgres.circe.json.implicits.*
import io.circe.Json
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import java.time.Instant
import java.util.UUID

class StrengthReportSourceSuite extends CatsEffectSuite with TestContainerForAll:
  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def resources(pg: PostgreSQLContainer) =
    for
      db <- PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))
      ec <- ExecutionContexts.fixedThreadPool[IO](2)
      xa <- HikariTransactor.newHikariTransactor[IO]("org.postgresql.Driver", pg.jdbcUrl, pg.username, pg.password, ec)
    yield (db, xa)

  final private case class Fixture(id: UUID, at: Instant, rated: Boolean, control: String)

  private def insert(xa: doobie.Transactor[IO], f: Fixture, result: Option[Int] = Some(1)): IO[Unit] =
    sql"""INSERT INTO play.game_results(game_id, white_external_id, black_external_id, result, termination,
              rated, time_control, server_seed, finished_at)
          VALUES (${f.id}::uuid, 'human:white', 'human:black', $result, 'resign', ${f.rated}, ${f.control},
              'fixture-seed', ${f.at})""".update.run.transact(xa).void

  private def load(
      db: PgGameStore,
      category: RatingCategory,
      windowDays: Option[Int]
  ): IO[GameResultsStore.ReportSource] =
    db.strengthReportSource(category, windowDays)
      .map(_.fold(error => fail(s"unexpected source validation error: ${error.message}"), identity))

  private def reset(xa: doobie.Transactor[IO]): IO[Unit] =
    sql"TRUNCATE TABLE play.game_results CASCADE".update.run.transact(xa).void

  test("window uses global rated maximum, includes its edge, counts exclusions, and retains non-model-filtered rows"):
    withContainers { pg =>
      resources(pg).use { (db, xa) =>
        val anchor   = Instant.parse("2026-09-30T00:00:00Z")
        val lower    = anchor.minusSeconds(10L * 86400L)
        val fixtures = List(
          Fixture(UUID.randomUUID(), lower, true, "Fischer(300,3)"),
          Fixture(UUID.randomUUID(), lower.minusNanos(1000), true, "Fischer(300,3)"),
          Fixture(UUID.randomUUID(), lower.plusSeconds(1), true, "Fischer(300,3)"),
          Fixture(UUID.randomUUID(), lower.plusSeconds(1), true, "Fischer(300,3)"),
          Fixture(UUID.randomUUID(), anchor.minusSeconds(1), true, "Fischer(300,3)"),
          Fixture(
            UUID.randomUUID(),
            anchor,
            true,
            "Fischer(600,10)"
          ), // Newest other category anchors the global window.
          Fixture(UUID.randomUUID(), anchor.minusSeconds(2), true, "Unlimited"), // Unknown category also counts.
          Fixture(UUID.randomUUID(), anchor.plusSeconds(1), false, "Fischer(300,3)"),
          Fixture(UUID.randomUUID(), Instant.EPOCH, true, "Fischer(300,3)")
        )
        for
          _        <- reset(xa)
          _        <- fixtures.traverse_(f => insert(xa, f, if f.id == fixtures(4).id then None else Some(1)))
          baseline <- db.finishedRatedSince(Instant.EPOCH)
          source   <- load(db, RatingCategory.Blitz, Some(10))
        yield
          val expected = baseline.filter(row => row.timeControl == "Fischer(300,3)" && !row.finishedAt.isBefore(lower))
          val expectedCutoff = baseline.filter(_.timeControl == "Fischer(300,3)").map(_.finishedAt).maxOption
          assertEquals(source.rows.map(_.gameId).toSet, expected.map(_.gameId).toSet)
          assertEquals(source.excludedRows, baseline.size - expected.size)
          assertEquals(source.categoryCutoff, expectedCutoff)
          assertEquals(source.rows.size, 4)
          assert(source.rows.exists(_.result.isEmpty), "source must retain a human/undecided eligible record")
          assertEquals(source.rows.count(_.finishedAt == lower), 1)
          assertEquals(source.rows.count(_.finishedAt == lower.plusSeconds(1)), 2)
      }
    }

  test("full history scopes to category, empty table has no phantom rows, and non-positive windows fail"):
    withContainers { pg =>
      resources(pg).use { (db, xa) =>
        val fixtures = List(
          Fixture(UUID.randomUUID(), Instant.parse("2026-10-01T00:00:00Z"), true, "Fischer(60,1)"),
          Fixture(UUID.randomUUID(), Instant.parse("2026-10-02T00:00:00Z"), true, "Fischer(300,3)"),
          Fixture(UUID.randomUUID(), Instant.parse("2026-10-03T00:00:00Z"), true, "Fischer(600,10)")
        )
        for
          _           <- reset(xa)
          _           <- fixtures.traverse_(insert(xa, _))
          baseline    <- db.finishedRatedSince(Instant.EPOCH)
          full        <- load(db, RatingCategory.Bullet, None)
          huge        <- load(db, RatingCategory.Bullet, Some(Int.MaxValue))
          _           <- reset(xa)
          empty       <- load(db, RatingCategory.Bullet, None)
          emptyWindow <- load(db, RatingCategory.Bullet, Some(14))
          zero        <- db.strengthReportSource(RatingCategory.Bullet, Some(0))
          negative    <- db.strengthReportSource(RatingCategory.Bullet, Some(-1))
        yield
          val expected = baseline.filter(_.timeControl == "Fischer(60,1)")
          assertEquals(full.rows.map(_.gameId).toSet, expected.map(_.gameId).toSet)
          assertEquals(full.excludedRows, baseline.size - expected.size)
          assertEquals(full.categoryCutoff, expected.map(_.finishedAt).maxOption)
          assertEquals(huge, full)
          assertEquals(empty.rows, Nil)
          assertEquals(empty.excludedRows, 0)
          assertEquals(empty.categoryCutoff, None)
          assertEquals(emptyWindow, empty)
          assertEquals(zero, Left(GameResultsStore.ReportSourceError.InvalidWindow))
          assertEquals(negative, Left(GameResultsStore.ReportSourceError.InvalidWindow))
      }
    }

  test("newest uncategorized rated row anchors the window even when every requested-category row is older"):
    withContainers { pg =>
      resources(pg).use { (db, xa) =>
        val old      = Instant.parse("2026-08-01T00:00:00Z")
        val fixtures = List(
          Fixture(UUID.randomUUID(), old, true, "Fischer(300,3)"),
          Fixture(UUID.randomUUID(), old.minusSeconds(1), true, "Fischer(300,3)"),
          Fixture(UUID.randomUUID(), old.plusSeconds(20L * 86400L), true, "Unlimited")
        )
        for
          _      <- reset(xa)
          _      <- fixtures.traverse_(insert(xa, _))
          source <- load(db, RatingCategory.Blitz, Some(10))
        yield
          assertEquals(source.rows, Nil)
          assertEquals(source.excludedRows, 3)
          assertEquals(source.categoryCutoff, Some(old))
      }
    }

  test("unrepresentable generated category is excluded and cannot advance the report category cutoff"):
    withContainers { pg =>
      resources(pg).use { (db, xa) =>
        val old      = Instant.parse("2026-08-01T00:00:00Z")
        val fixtures = List(
          Fixture(UUID.randomUUID(), old, true, "Fischer(600,10)"),
          Fixture(UUID.randomUUID(), old.plusSeconds(20L * 86400L), true, "SuddenDeath(2147483648)")
        )
        for
          _      <- reset(xa)
          _      <- fixtures.traverse_(insert(xa, _))
          full   <- load(db, RatingCategory.Rapid, None)
          window <- load(db, RatingCategory.Rapid, Some(10))
        yield
          assertEquals(full.rows.map(_.gameId.value).toSet, Set(fixtures.head.id.toString))
          assertEquals(full.excludedRows, 1)
          assertEquals(full.categoryCutoff, Some(old))
          assertEquals(window.rows, Nil)
          assertEquals(window.excludedRows, 2)
          assertEquals(window.categoryCutoff, Some(old))
      }
    }

  test("window query uses the category/finished composite index with both predicates"):
    withContainers { pg =>
      resources(pg).use { (db, xa) =>
        val cutoff  = Instant.parse("2026-10-01T00:00:00Z")
        val fixture = Fixture(UUID.randomUUID(), cutoff.plusSeconds(1), true, "Fischer(300,3)")
        val query   = Fragment.const("EXPLAIN (FORMAT JSON) ") ++
          db.strengthReportRowsQuery(RatingCategory.Blitz, Some(cutoff))
        for
          _    <- reset(xa)
          _    <- insert(xa, fixture)
          plan <- (sql"SET LOCAL enable_seqscan = off".update.run *> query.query[Json].unique).transact(xa)
        yield
          val rendered = plan.noSpaces
          assert(rendered.contains("game_results_rated_category_finished_idx"), rendered)
          assert(rendered.contains("Index Cond"), rendered)
          assert(rendered.contains("category"), rendered)
          assert(rendered.contains("finished_at"), rendered)
          assert(!rendered.contains("Aggregate"), rendered)
      }
    }
