package dicechess.play.store

import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.RatingCategory
import doobie.Fragment
import doobie.implicits.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

/** Check the actual serving fragments: provenance must use index endpoints rather than aggregate history. */
class LeaderboardProvenanceSuite extends CatsEffectSuite with TestContainerForAll:
  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  test("lifetime and epoch provenance use two index endpoints without a history aggregate") {
    withContainers { pg =>
      PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password)).use { db =>
        def plan(query: Fragment): IO[String] =
          (sql"SET LOCAL enable_seqscan = off".update.run *>
            (Fragment.const("EXPLAIN (FORMAT JSON) ") ++ query).query[String].unique).transact(db.xa)

        for
          lifetime <- plan(db.lifetimeProvenanceQuery(RatingCategory.Blitz))
          epoch    <- plan(db.epochProvenanceQuery(RatingCategory.Blitz, 42))
        yield
          List(lifetime, epoch).foreach { value =>
            assert(!value.contains("\"Node Type\": \"Aggregate\""), value)
            assertEquals("\"Node Type\": \"Limit\"".r.findAllIn(value).length, 2, value)
          }
          assert(lifetime.contains("game_results_lifetime_cutoff_idx"), lifetime)
          assert(lifetime.contains("game_results_lifetime_computed_idx"), lifetime)
          assert(epoch.contains("game_results_live_epoch_cutoff_idx"), epoch)
          assert(epoch.contains("game_results_epoch_computed_idx"), epoch)
      }
    }
  }
