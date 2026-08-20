package dicechess.play.store

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.RatingCategory
import dicechess.play.rating.Glicko2
import doobie.hikari.HikariTransactor
import doobie.implicits.*
// The store maps java.time natively through the driver — the checker must read columns the same way, or it would
// describe a different mapping than production uses.
import doobie.implicits.javatimedrivernative.*
import doobie.util.ExecutionContexts
import doobie.util.Read
import doobie.util.fragment.Fragment
import doobie.util.transactor.Transactor
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import java.time.Instant
import java.util.UUID

/** Static checks for the queries this store ASSEMBLES rather than writes out — `Fragment` concatenation and
  * `Fragments.in`, driven by which filters a caller happened to pass.
  *
  * Everything a compiler can see in those is string concatenation: a malformed one is found by the database or by
  * nobody, and only for the exact filter combination that produced it. A behavioural test covers the combinations
  * someone thought to write; this suite prepares EVERY combination and asks PostgreSQL to describe it, which is a
  * different question — it needs no rows, no fixtures, and it fails on a query the caller would never have reached in a
  * test.
  *
  * `analysis` also compares the described columns against the Scala type each query reads into, so a column that
  * changes type or becomes nullable under a migration is caught here rather than at the first `NULL` in production.
  */
class PgQueryCheckSuite extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  /** The store (for the fragment builders, which need no connection) plus a raw transactor to describe them on. The
    * store resource is what runs Flyway, so the schema being described is the real migrated one.
    */
  private def storeAndXa(pg: PostgreSQLContainer) =
    for
      db        <- PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))
      connectEC <- ExecutionContexts.fixedThreadPool[IO](2)
      xa        <- HikariTransactor
        .newHikariTransactor[IO](
          driverClassName = "org.postgresql.Driver",
          url = pg.jdbcUrl,
          user = pg.username,
          pass = pg.password,
          connectEC = connectEC
        )
    yield (db, xa)

  /** Prepares `fragment` and returns every mismatch PostgreSQL reports against `A`, each labelled with `what` so a
    * failure names the offending combination instead of just a line number.
    *
    * EVERY finding counts — there is no exempt class. That is deliberate and it is the whole point of this helper:
    * doobie reports parameter types under exactly one class (`ParameterTypeError`), so exempting it to tolerate a
    * harmless widening would leave the suite with no parameter type checking at all, and a genuinely wrong binding — a
    * `String` into a `uuid` column, an `Instant` into a `text` one — reports under that same class.
    *
    * Keeping it strict is what forces the store to bind each parameter in the schema's own type rather than one the
    * driver happens to widen (`LIMIT` is `int8`, `game_results.result` is `int2`). If a future query trips a finding
    * that is genuinely fine, exempt THAT query with its reason — never the class.
    */
  private def misalignments[A: Read](what: String, fragment: Fragment, xa: Transactor[IO]): IO[List[String]] =
    fragment
      .query[A]
      .analysis
      .transact(xa)
      .map(
        _.alignmentErrors
          .map(error => s"$what: ${error.msg}")
      )
      // A fragment that does not even prepare throws rather than returning errors — same failure, better message.
      .handleError(error => List(s"$what: ${error.getMessage}"))

  private val accountIds = NonEmptyList.of(UUID.randomUUID().toString, UUID.randomUUID().toString)
  private val botIds     = NonEmptyList.of("bot:team:check-suite:one", "bot:team:check-suite:two")

  test("the seat-face resolvers prepare and align — the two queries every game creation runs"):
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        // One id and several: `Fragments.in` renders a different parameter list for each, and the seat resolvers are
        // called with both (one claimed seat vs both seats of a new game).
        val cases = List(NonEmptyList.one(accountIds.head), accountIds)
        for
          nicknames <- cases.flatTraverse: ids =>
            misalignments[(String, String)](s"nicknameFragment(${ids.size})", db.nicknameFragment(ids), xa)
          users <- cases.flatTraverse: ids =>
            misalignments[(String, Double)](
              s"settledUserRatingsFragment(${ids.size})",
              db.settledUserRatingsFragment(ids, RatingCategory.Default, Glicko2.ProvisionalDeviationThreshold),
              xa
            )
          bots <- List(NonEmptyList.one(botIds.head), botIds).flatTraverse: ids =>
            misalignments[(String, Double)](
              s"settledBotRatingsFragment(${ids.size})",
              db.settledBotRatingsFragment(ids, RatingCategory.Default, Glicko2.ProvisionalDeviationThreshold),
              xa
            )
        yield assertEquals(nicknames ++ users ++ bots, Nil)
      }
    }

  test("every history-page filter combination prepares and aligns"):
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        // The full matrix, because the assembly differs per combination: each present filter appends another
        // predicate, and `before`/`povResult` also add bound parameters on BOTH sides of the UNION.
        val befores   = List(None, Some(Instant.parse("2026-08-01T00:00:00Z")))
        val opponents =
          List(None, Some(OpponentFilter.Bot("bot:team:check-suite:one")), Some(OpponentFilter.HumanOnly))
        val results = None :: PovResultFilter.values.toList.map(Some(_))
        val ids     = List(NonEmptyList.one(accountIds.head), accountIds)
        val cases   = for
          identities <- ids
          before     <- befores
          opponent   <- opponents
          result     <- results
        yield (identities, before, opponent, result)
        cases
          .flatTraverse: (identities, before, opponent, result) =>
            val what = s"pagedFragment(ids=${identities.size}, before=${before.isDefined}, " +
              s"opponent=$opponent, result=$result)"
            misalignments[PgGameStore.ResultTuple](
              what,
              db.pagedFragment(identities, before, opponent, result, limit = 20),
              xa
            )
          .map: problems =>
            assertEquals(problems, Nil)
            assertEquals(cases.size, 48, "the matrix must actually cover every combination, not a subset of it")
      }
    }

  test("the opponent aggregate prepares and aligns for one identity and for a merged claim set"):
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        List(NonEmptyList.one(accountIds.head), accountIds)
          .flatTraverse: ids =>
            misalignments[(Option[String], Int, Int, Int, Int, Instant)](
              s"opponentAggregatesFragment(${ids.size})",
              db.opponentAggregatesFragment(ids),
              xa
            )
          .map(problems => assertEquals(problems, Nil))
      }
    }
