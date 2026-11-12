package dicechess.play.store

import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.ConnectionIO
import doobie.Fragment
import doobie.implicits.*
import doobie.postgres.implicits.*
import munit.CatsEffectSuite
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.testcontainers.utility.DockerImageName

import java.util.UUID

/** Real-Postgres coverage for the transactional lifetime tally maintained by V14. */
class LeaderboardLifetimeCountsSuite extends CatsEffectSuite with TestContainerForAll:
  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private val a = "bot:team:lifetime:a"
  private val b = "bot:team:lifetime:b"
  private val c = "bot:team:lifetime:c"

  private def withStore[A](action: PgGameStore => IO[A]): IO[A] =
    withContainers { pg =>
      PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password)).use { db =>
        val reset = sql"TRUNCATE play.game_results".update.run.void.transact(db.xa)
        reset *> action(db)
      }
    }

  private def insert(
      db: PgGameStore,
      white: String = a,
      black: String = b,
      result: Option[Int] = Some(1),
      outcome: String = "legacy",
      domain: String = "competitive",
      timeControl: String = "Fischer(300,3)"
  ): IO[UUID] =
    val id         = UUID.randomUUID()
    val skipReason = Option.when(outcome == "skipped")("fixture")
    sql"""INSERT INTO play.game_results
         (game_id, white_external_id, black_external_id, result, termination, rated, time_control,
          server_seed, rating_outcome, rating_domain, rating_skip_reason)
         VALUES ($id, $white, $black, $result, 'resign', true, $timeControl, 'fixture', $outcome, $domain, $skipReason)""".update.run
      .transact(db.xa)
      .as(id)

  private def countsSql: ConnectionIO[List[(String, String, Long, Long, Long)]] =
    sql"""SELECT participant_external_id, category, wins, draws, losses
         FROM play.leaderboard_lifetime_counts WHERE wins + draws + losses > 0
         ORDER BY participant_external_id, category"""
      .query[(String, String, Long, Long, Long)]
      .to[List]

  private def counts(db: PgGameStore): IO[List[(String, String, Long, Long, Long)]] =
    countsSql.transact(db.xa)

  /** Independent source-of-truth aggregate, deliberately written as the original two-seat UNION ALL oracle. */
  private def oracle(db: PgGameStore): IO[List[(String, String, Long, Long, Long)]] =
    sql"""SELECT participant_external_id, category,
                sum((score = 1)::int)::bigint, sum((score = 0)::int)::bigint, sum((score = -1)::int)::bigint
         FROM (
           SELECT white_external_id AS participant_external_id, category, result AS score
           FROM play.game_results
           WHERE rating_outcome IN ('applied','legacy') AND rating_domain <> 'training'
             AND result IS NOT NULL AND category IS NOT NULL
           UNION ALL
           SELECT black_external_id, category, -result
           FROM play.game_results
           WHERE rating_outcome IN ('applied','legacy') AND rating_domain <> 'training'
             AND result IS NOT NULL AND category IS NOT NULL
         ) seats
         GROUP BY participant_external_id, category ORDER BY participant_external_id, category"""
      .query[(String, String, Long, Long, Long)]
      .to[List]
      .transact(db.xa)

  private def assertMatchesOracle(db: PgGameStore): IO[Unit] =
    (counts(db), oracle(db)).tupled.map { case (actual, expected) => assertEquals(actual, expected) }

  test("eligibility transitions, corrections, reclassification, deletion and duplicate updates preserve the oracle") {
    withStore { db =>
      for
        pending <- insert(db, outcome = "pending")
        _       <- assertMatchesOracle(db)
        _       <- sql"UPDATE play.game_results SET rating_outcome='applied' WHERE game_id=$pending".update.run
          .transact(db.xa)
        once <- counts(db)
        _    <- sql"UPDATE play.game_results SET rating_outcome='applied' WHERE game_id=$pending".update.run
          .transact(db.xa)
        twice <- counts(db)
        _     <- IO(assertEquals(twice, once))
        _     <- sql"UPDATE play.game_results SET result=0 WHERE game_id=$pending".update.run.transact(db.xa)
        _     <- assertMatchesOracle(db)
        _     <-
          sql"UPDATE play.game_results SET white_external_id=$c, time_control='Fischer(600,0)' WHERE game_id=$pending".update.run
            .transact(db.xa)
        moved <- counts(db)
        _     <- IO(assert(!moved.exists(row => row._1 == a && row._2 == "blitz" && row._3 + row._4 + row._5 > 0)))
        _     <- assertMatchesOracle(db)
        _     <- sql"UPDATE play.game_results SET rating_domain='training' WHERE game_id=$pending".update.run
          .transact(db.xa)
        _ <- assertMatchesOracle(db)
        _ <-
          sql"UPDATE play.game_results SET rating_domain='competitive', rating_outcome='skipped', rating_skip_reason='fixture' WHERE game_id=$pending".update.run
            .transact(db.xa)
        _ <- assertMatchesOracle(db)
        _ <- sql"DELETE FROM play.game_results WHERE game_id=$pending".update.run.transact(db.xa)
        _ <- assertMatchesOracle(db)
      yield ()
    }
  }

  test("the aggregate excludes noneligible and uncategorized rows, and TRUNCATE clears the projection") {
    withStore { db =>
      for
        _             <- insert(db)
        _             <- insert(db, outcome = "pending")
        _             <- insert(db, outcome = "skipped")
        _             <- insert(db, outcome = "applied", domain = "training")
        _             <- insert(db, result = None, outcome = "applied")
        _             <- insert(db, outcome = "legacy", timeControl = "unknown")
        _             <- assertMatchesOracle(db)
        _             <- sql"TRUNCATE play.game_results".update.run.transact(db.xa)
        _             <- assertMatchesOracle(db)
        afterTruncate <- counts(db)
      yield assertEquals(afterTruncate, Nil)
    }
  }

  test("bulk writes and self-games account for both seats, with statement rollback restoring counters") {
    withStore { db =>
      def tx: ConnectionIO[Unit] =
        sql"""INSERT INTO play.game_results
             (game_id, white_external_id, black_external_id, result, termination, rated, time_control, server_seed,
              rating_outcome, rating_domain)
             VALUES (${UUID
            .randomUUID()}, $a, $b, 1, 'resign', true, 'Fischer(300,3)', 'fixture', 'legacy', 'competitive'),
                    (${UUID
            .randomUUID()}, $b, $a, 0, 'draw', true, 'Fischer(300,3)', 'fixture', 'applied', 'competitive'),
                    (${UUID
            .randomUUID()}, $c, $c, -1, 'resign', true, 'Fischer(600,0)', 'fixture', 'legacy', 'competitive'),
                    (${UUID
            .randomUUID()}, $a, $c, NULL, 'abandoned', false, 'Fischer(300,3)', 'fixture', 'legacy', 'competitive')""".update.run.void *>
          sql"""UPDATE play.game_results SET result=1 WHERE white_external_id=$a AND black_external_id=$b""".update.run.void
      val rollbackError = new RuntimeException("rollback fixture")
      for
        _      <- tx.transact(db.xa)
        _      <- assertMatchesOracle(db)
        before <- counts(db)
        failed <- (tx *> countsSql.map(current => assertNotEquals(current, before)) *>
          rollbackError.raiseError[ConnectionIO, Unit])
          .transact(db.xa)
          .attempt
        after <- counts(db)
        _     <- IO(assertEquals(failed, Left(rollbackError)))
        _     <- IO(assertEquals(after, before))
      yield ()
    }
  }

  test("an upsert correction updates both seats once even when retried") {
    withStore { db =>
      for
        id <- insert(db)
        correction = sql"""INSERT INTO play.game_results
             (game_id, white_external_id, black_external_id, result, termination, rated, time_control,
              server_seed, rating_outcome, rating_domain)
             VALUES ($id, $a, $b, 0, 'draw', true, 'Fischer(300,3)', 'fixture', 'legacy', 'competitive')
             ON CONFLICT (game_id) DO UPDATE SET result=EXCLUDED.result""".update.run.transact(db.xa)
        _     <- correction
        _     <- assertMatchesOracle(db)
        once  <- counts(db)
        _     <- correction
        twice <- counts(db)
      yield
        assertEquals(once, List((a, "blitz", 0L, 1L, 0L), (b, "blitz", 0L, 1L, 0L)))
        assertEquals(twice, once)
    }
  }

  test("concurrent inserts sharing opposite-seat tally keys match the source oracle") {
    withStore { db =>
      for
        _ <- (0 until 16).toList.parTraverse_ { index =>
          val (white, black, result) = if index % 2 == 0 then (a, b, Some(1)) else (b, a, Some(-1))
          insert(db, white, black, result, "applied")
        }
        _ <- assertMatchesOracle(db)
      yield ()
    }
  }

  test("the live bot and player board queries work with no game history privileges") {
    withStore { db =>
      val role   = s"lifetime_reader_${UUID.randomUUID().toString.replace("-", "")}"
      val user   = UUID.randomUUID()
      val access =
        Fragment.const(s"CREATE ROLE $role NOLOGIN").update.run *>
          sql"""INSERT INTO play.bots(team,name,token_hash) VALUES ('lifetime','board-bot','lifetime-fixture')""".update.run *>
          sql"""INSERT INTO play.bot_ratings(team,name,category,rating,rd) VALUES ('lifetime','board-bot','blitz',1600,80)""".update.run *>
          sql"INSERT INTO play.users(id,nickname) VALUES ($user,'Lifetime Player')".update.run *>
          sql"INSERT INTO play.user_ratings(user_id,category,rating,rd) VALUES ($user,'blitz',1550,90)".update.run *>
          Fragment.const(s"GRANT USAGE ON SCHEMA play TO $role").update.run *>
          Fragment
            .const(
              s"GRANT SELECT ON play.bots, play.bot_ratings, play.users, play.user_ratings, " +
                s"play.rating_epoch_control, play.leaderboard_lifetime_counts, play.rating_epoch_counts TO $role"
            )
            .update
            .run
      val restricted = for
        _     <- Fragment.const(s"SET LOCAL ROLE $role").update.run
        bots  <- db.leaderboardSql(dicechess.play.core.RatingCategory.Blitz, 110, 200, None)
        users <- db.playerLeaderboardSql(dicechess.play.core.RatingCategory.Blitz, 110, 200, None)
      yield (bots, users)
      for (bots, players) <- (access *> restricted).transact(db.xa)
      yield
        assertEquals(bots.map(_.name), List("board-bot"))
        assertEquals(players.map(_.nickname), List("Lifetime Player"))
    }
  }

  test("V14 backfills eligible legacy rows that existed before the migration") {
    withContainers { pg =>
      val id      = UUID.randomUUID()
      val schema  = s"lifetime_migration_${UUID.randomUUID().toString.replace("-", "")}"
      val migrate = (target: Option[String]) =>
        IO.blocking {
          val config = Flyway
            .configure()
            .dataSource(pg.jdbcUrl, pg.username, pg.password)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
          target.fold(config)(version => config.target(MigrationVersion.fromVersion(version))).load().migrate()
          ()
        }
      val seed = IO.blocking {
        val connection = java.sql.DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
        try
          val statement = connection.prepareStatement(
            s"""INSERT INTO $schema.game_results
              (game_id,white_external_id,black_external_id,result,termination,rated,time_control,server_seed,
               rating_outcome,rating_domain)
              VALUES (?,?,?,1,'resign',true,'Fischer(300,3)','fixture','legacy','legacy')"""
          )
          try
            statement.setObject(1, id)
            statement.setString(2, a)
            statement.setString(3, b)
            statement.executeUpdate()
          finally statement.close()
        finally connection.close()
      }
      val read = IO.blocking {
        val connection = java.sql.DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
        try
          val statement = connection.prepareStatement(
            s"""SELECT participant_external_id, category, wins, draws, losses
               FROM $schema.leaderboard_lifetime_counts ORDER BY participant_external_id, category"""
          )
          try
            val rows   = statement.executeQuery()
            val result = List.newBuilder[(String, String, Long, Long, Long)]
            while rows.next() do
              result += ((rows.getString(1), rows.getString(2), rows.getLong(3), rows.getLong(4), rows.getLong(5)))
            rows.close()
            result.result()
          finally statement.close()
        finally connection.close()
      }
      for
        _      <- migrate(Some("13"))
        _      <- seed
        _      <- migrate(None)
        actual <- read
      yield assertEquals(actual, List((a, "blitz", 1L, 0L, 0L), (b, "blitz", 0L, 0L, 1L)))
    }
  }
