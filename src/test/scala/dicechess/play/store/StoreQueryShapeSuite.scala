package dicechess.play.store

import scala.io.Source
import scala.util.Using

/** A rule about the SQL this store is allowed to contain, checked by reading the source rather than by running it.
  *
  * There is exactly one rule here so far, and it exists because the thing it forbids took production down (#335).
  * `play.rating_category(text)` is not inlinable — its body ends in a sub-SELECT, and PostgreSQL only inlines a SQL
  * function whose body is a single expression — so calling it inside a `WHERE` costs a real function call per row. On
  * the production corpus that turned the leaderboard's 123 ms aggregate into 28 seconds, against a 5-second timeout.
  *
  * Nothing that runs the queries can catch this: `game_results` holds a handful of rows in every suite, where 338k
  * function calls become a few dozen and finish instantly. The failure mode is proportional to data this repository has
  * no fixture for, so what is pinned instead is the mechanism — every reader filters the stored `category` column, and
  * none of them mentions the function.
  *
  * The function itself is not going anywhere: it is what generates that column, and `PgGameStoreSuite` runs it against
  * the Scala `RatingCategory` so the two implementations of one rule cannot drift.
  */
class StoreQueryShapeSuite extends munit.FunSuite:

  /** Read from the working directory, which sbt sets to the project root. A missing file FAILS rather than yielding an
    * empty string that would make every assertion below vacuously true — a silent pass is exactly the outcome this
    * suite exists to prevent.
    */
  private def source(path: String): String =
    Using(Source.fromFile(path, "UTF-8"))(_.mkString)
      .fold(error => fail(s"cannot read $path — this suite reads the store's source to check it: $error"), identity)

  test("no store query calls rating_category() per row — that is the #335 outage (the stored category column is read)"):
    val store     = source("src/main/scala/dicechess/play/store/PgGameStore.scala")
    val callSites = store.linesIterator.zipWithIndex
      .filter((line, _) => line.contains("rating_category("))
      .map((line, index) => s"${index + 1}: ${line.trim}")
      .toList
    assertEquals(
      callSites,
      Nil,
      "filter the stored `category` column instead: the function is not inlinable and runs once per scanned row"
    )

  test("the baseline migration stores the column and generates it from the rating_category function"):
    val migration = source("src/main/resources/db/migration/V1__initial_schema.sql")
    assert(
      migration.contains("GENERATED ALWAYS AS (rating_category(time_control)) STORED"),
      "V1 must keep generating `category` from `rating_category`, or the column and the function drift apart"
    )

  test("the baseline migration preserves non-cascading outbox FK and bots capacity constraint"):
    val migration = source("src/main/resources/db/migration/V1__initial_schema.sql")
    assert(
      migration.contains("game_id            uuid PRIMARY KEY REFERENCES games (id),"),
      "outbox must NOT cascade on delete, preserving snapshots with pending/parked payloads"
    )
    assert(
      migration.contains(
        "CONSTRAINT bots_max_concurrent_games_range CHECK (max_concurrent_games >= 1 AND max_concurrent_games <= 32)"
      ),
      "bots capacity must be bounded between 1 and 32 via CHECK constraint"
    )
