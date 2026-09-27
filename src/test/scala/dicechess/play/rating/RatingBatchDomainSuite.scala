package dicechess.play.rating

import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.EngineOps
import dicechess.play.store.*
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.util.ExecutionContexts
import doobie.util.fragment.Fragment
import doobie.util.transactor.Transactor
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import java.sql.SQLException
import java.time.Instant

class RatingBatchDomainSuite extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def store(pg: PostgreSQLContainer) =
    PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))

  /** The store plus a raw transactor on the same database, for the arrangements no store method makes (#189): a bot's
    * stored rating moving between the game and the drain, a row as it was written before references were captured, a
    * row put back into the queue.
    */
  private def storeAndXa(pg: PostgreSQLContainer, anchorSet: AnchorSet = AnchorSet.Default) =
    for
      db        <- PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password), anchorSet)
      connectEC <- ExecutionContexts.fixedThreadPool[IO](2)
      xa        <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = "org.postgresql.Driver",
        url = pg.jdbcUrl,
        user = pg.username,
        pass = pg.password,
        connectEC = connectEC
      )
    yield (db, xa)

  private def matrixBatch(db: PgGameStore, anchorSet: AnchorSet = AnchorSet.Default): IO[RatingBatch] =
    RatingBatch.create(
      botStore = db,
      userStore = db,
      ratingStore = db,
      resultsStore = db,
      config = RatingBatch.Config.Default,
      policy = RatingPolicy.Matrix,
      anchorSet = anchorSet
    )

  private def legacyBatch(db: PgGameStore): IO[RatingBatch] =
    RatingBatch.create(
      botStore = db,
      userStore = db,
      ratingStore = db,
      resultsStore = db,
      config = RatingBatch.Config.Default,
      policy = RatingPolicy.Legacy,
      anchorSet = AnchorSet.Default
    )

  /** A row as it was written BEFORE #146 gave rows a domain: rated, and stored with `rating_domain = 'legacy'` (the
    * snapshot carries no domain, and `finishedGameOf` defaults it). Its seats are a human and a bot, which is exactly
    * the shape a participant-kind guess would misread as training.
    */
  private def preClassificationFixture(
      human: Principal,
      bot: Principal,
      result: GameResult = GameResult.Win(Side.White)
  ): GameSnapshot =
    trainingFixture(human, bot, result = result).copy(
      rated = Some(true),
      ratingDomain = None,
      ratingPolicyVersion = None
    )

  private def trainingFixture(
      human: Principal,
      bot: Principal,
      result: GameResult = GameResult.Win(Side.White),
      timeControl: TimeControl = TimeControl.Fischer(300, 3), // Blitz
      humanIsWhite: Boolean = true
  ): GameSnapshot =
    val (white, black) = if humanIsWhite then (human, bot) else (bot, human)
    GameSnapshot(
      version = 3L,
      dfen = EngineOps.InitialDfen,
      players = Map(Seat.White -> white, Seat.Black -> black),
      seatTokens = Map(Seat.White -> "tok-w", Seat.Black -> "tok-b"),
      serverSeed = "ab12cd34",
      clientSeeds = Map.empty,
      started = true,
      ply = 2L,
      pending = false,
      status = GameStatus.Ended(GameOver(result, Termination.Resign)),
      timeControl = timeControl,
      remainingMs = Map(Seat.White -> 1000L, Seat.Black -> 1000L),
      lastRoll = Nil,
      turns = Vector.empty,
      rated = Some(false),
      ladder = Some(false),
      ratedRequested = Some(true),
      ratingDomain = Some(RatingDomain.Training),
      ratingPolicyVersion = Some(RatingPolicy.Matrix.version)
    )

  private def competitiveFixture(
      white: Principal,
      black: Principal,
      result: GameResult = GameResult.Win(Side.White),
      timeControl: TimeControl = TimeControl.Fischer(300, 3),
      ladder: Boolean = false
  ): GameSnapshot =
    GameSnapshot(
      version = 3L,
      dfen = EngineOps.InitialDfen,
      players = Map(Seat.White -> white, Seat.Black -> black),
      seatTokens = Map(Seat.White -> "tok-w", Seat.Black -> "tok-b"),
      serverSeed = "ab12cd34",
      clientSeeds = Map.empty,
      started = true,
      ply = 2L,
      pending = false,
      status = GameStatus.Ended(GameOver(result, Termination.Resign)),
      timeControl = timeControl,
      remainingMs = Map(Seat.White -> 1000L, Seat.Black -> 1000L),
      lastRoll = Nil,
      turns = Vector.empty,
      rated = Some(true),
      ladder = Some(ladder),
      ratedRequested = Some(true),
      ratingDomain = Some(RatingDomain.Competitive),
      ratingPolicyVersion = Some(RatingPolicy.Matrix.version)
    )

  test(
    "under matrix policy, human-vs-bot game updates user_training_ratings and leaves user_ratings and bot_ratings untouched"
  ):
    withContainers { pg =>
      store(pg).use { db =>
        for
          player <- db.upsertOnLogin("google", "sub-dom-training-1", None, IO.pure("DomTrainer"))
          human = Principal.User(player.id)
          _ <- db.register("anchor", "random", "hash-anchor-random")
          bot = Principal.Bot("anchor", "random")
          gameId <- GameId.random
          _ <- db.save(gameId, trainingFixture(human, bot, result = GameResult.Win(Side.White), humanIsWhite = true))
          _ <- matrixBatch(db).flatMap(_.tick)
          trainingState   <- db.trainingStateOf(player.id, RatingCategory.Blitz)
          userCompRatings <- db.categoryRatingsOf(RatedIdentity.User(player.id))
          botCompRatings  <- db.categoryRatingsOf(RatedIdentity.Bot("anchor", "random"))
          changeOpt       <- db.ratingChangeFor(gameId)
        yield
          assertEquals(trainingState.games, 1)
          assertEquals(trainingState.wins, 1)
          assert(
            trainingState.rating > 1500.0,
            s"Human won against anchor, rating should increase: ${trainingState.rating}"
          )
          assert(trainingState.isProvisional, "1 game must be provisional")

          assertEquals(userCompRatings, Map.empty[RatingCategory, Glicko], "user_ratings must remain empty")

          assertEquals(botCompRatings, Map.empty[RatingCategory, Glicko], "bot_ratings must remain empty")

          assert(changeOpt.isDefined)
          val change = changeOpt.get
          assert(change.applied)
          assertEquals(change.outcome, RatingOutcome.Applied)
          assertEquals(change.reason, None)
          assertEquals(change.domain, Some(RatingDomain.Training))
      }
    }

  test(
    "under matrix policy, human-vs-human competitive game updates user_ratings and leaves user_training_ratings untouched"
  ):
    withContainers { pg =>
      store(pg).use { db =>
        for
          p1 <- db.upsertOnLogin("google", "sub-dom-hvh-1", None, IO.pure("HvhAlice"))
          p2 <- db.upsertOnLogin("google", "sub-dom-hvh-2", None, IO.pure("HvhBob"))
          h1 = Principal.User(p1.id)
          h2 = Principal.User(p2.id)
          gameId  <- GameId.random
          _       <- db.save(gameId, competitiveFixture(h1, h2, result = GameResult.Win(Side.White)))
          _       <- matrixBatch(db).flatMap(_.tick)
          p1Comp  <- db.categoryRatingOf(RatedIdentity.User(p1.id), RatingCategory.Blitz)
          p2Comp  <- db.categoryRatingOf(RatedIdentity.User(p2.id), RatingCategory.Blitz)
          p1Train <- db.trainingStatesOf(p1.id)
          p2Train <- db.trainingStatesOf(p2.id)
        yield
          // Competitive ratings updated
          assert(p1Comp.rating > 1500.0, s"Winner gained rating: $p1Comp")
          assert(p2Comp.rating < 1500.0, s"Loser lost rating: $p2Comp")

          // Training ratings untouched
          assertEquals(p1Train, Map.empty[RatingCategory, TrainingState])
          assertEquals(p2Train, Map.empty[RatingCategory, TrainingState])
      }
    }

  test("under matrix policy, human playing against their own bot is skipped with reason"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          owner <- db.upsertOnLogin("google", "sub-dom-ownbot-1", None, IO.pure("OwnBotUser"))
          human = Principal.User(owner.id)
          _ <- db.register("myteam", "mybot", "hash-mybot", owner = Some(human.externalId))
          bot = Principal.Bot("myteam", "mybot")
          gameId <- GameId.random
          _      <- db.save(gameId, trainingFixture(human, bot))
          _      <- matrixBatch(db).flatMap(_.tick)
          train  <- db.trainingStateOf(owner.id, RatingCategory.Blitz)
          change <- db.ratingChangeFor(gameId)
        yield
          assertEquals(train.games, 0, "Own bot game must not count toward training")
          assert(change.isDefined)
          assertEquals(change.get.outcome, RatingOutcome.Skipped)
          assertEquals(change.get.reason, Some("a player's game against their own bot is never rated"))
      }
    }

  test("under matrix policy, unscheduled bot-vs-bot challenge is skipped without moving ratings"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          _ <- db.register("bvb", "bot1", "hash-bvb-1")
          _ <- db.register("bvb", "bot2", "hash-bvb-2")
          b1: Principal.Bot = Principal.Bot("bvb", "bot1")
          b2: Principal.Bot = Principal.Bot("bvb", "bot2")
          gameId    <- GameId.random
          _         <- db.save(gameId, competitiveFixture(b1, b2, ladder = false))
          _         <- matrixBatch(db).flatMap(_.tick)
          b1Ratings <- db.categoryRatingsOf(RatedIdentity.of(b1))
          b2Ratings <- db.categoryRatingsOf(RatedIdentity.of(b2))
          change    <- db.ratingChangeFor(gameId)
        yield
          assertEquals(b1Ratings, Map.empty[RatingCategory, Glicko])
          assertEquals(b2Ratings, Map.empty[RatingCategory, Glicko])
          assert(change.isDefined)
          assertEquals(change.get.outcome, RatingOutcome.Skipped)
          assertEquals(
            change.get.reason,
            Some("unpaired bot-vs-bot challenge carries no canonical rating under matrix policy")
          )
      }
    }

  test("under matrix policy, Rapid game against Blitz-only anchor bot is skipped and never borrows Blitz"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          player <- db.upsertOnLogin("google", "sub-dom-rapid-1", None, IO.pure("RapidTrainer"))
          human = Principal.User(player.id)
          _ <- db.register("anchor", "random", "hash-anchor-random")
          bot = Principal.Bot("anchor", "random")
          gameId <- GameId.random
          // Fischer 900+10 is Rapid
          rapidTc = TimeControl.Fischer(900, 10)
          _      <- db.save(gameId, trainingFixture(human, bot, timeControl = rapidTc))
          _      <- matrixBatch(db).flatMap(_.tick)
          train  <- db.trainingStateOf(player.id, RatingCategory.Rapid)
          change <- db.ratingChangeFor(gameId)
        yield
          assertEquals(train.games, 0, "Rapid game with no Rapid bot reference must not update training state")
          assert(change.isDefined)
          assertEquals(change.get.outcome, RatingOutcome.Skipped)
          assertEquals(change.get.reason, Some("no reference bot rating for anchor/random in category 'rapid'"))
      }
    }

  test("under matrix policy, a pre-#146 legacy row keeps its competitive meaning and never becomes training"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          player <- db.upsertOnLogin("google", "sub-dom-legacy-1", None, IO.pure("LegacyPlayer"))
          human = Principal.User(player.id)
          _ <- db.register("anchor", "random", "hash-anchor-random")
          bot = Principal.Bot("anchor", "random")
          gameId   <- GameId.random
          _        <- db.save(gameId, preClassificationFixture(human, bot))
          _        <- matrixBatch(db).flatMap(_.tick)
          training <- db.trainingStateOf(player.id, RatingCategory.Blitz)
          userComp <- db.categoryRatingsOf(RatedIdentity.User(player.id))
          botComp  <- db.categoryRatingsOf(RatedIdentity.Bot("anchor", "random"))
          change   <- db.ratingChangeFor(gameId)
        yield
          assertEquals(training.games, 0, "a legacy row must not be reinterpreted as a training game")
          assert(userComp.contains(RatingCategory.Blitz), "the legacy row still moves the human's competitive rating")
          assert(botComp.contains(RatingCategory.Blitz), "the legacy row still moves the bot's competitive rating")
          assertEquals(change.flatMap(_.domain), None, "the stored domain stays legacy")
          assertEquals(change.map(_.outcome), Some(RatingOutcome.Applied))
      }
    }

  test("under the legacy policy, a training-domain row is skipped rather than applied competitively"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          player <- db.upsertOnLogin("google", "sub-dom-rollback-1", None, IO.pure("RollbackPlayer"))
          human = Principal.User(player.id)
          _ <- db.register("anchor", "greedy", "hash-anchor-greedy")
          bot = Principal.Bot("anchor", "greedy")
          gameId   <- GameId.random
          _        <- db.save(gameId, trainingFixture(human, bot))
          _        <- legacyBatch(db).flatMap(_.tick)
          training <- db.trainingStateOf(player.id, RatingCategory.Blitz)
          userComp <- db.categoryRatingsOf(RatedIdentity.User(player.id))
          botComp  <- db.categoryRatingsOf(RatedIdentity.Bot("anchor", "greedy"))
          change   <- db.ratingChangeFor(gameId)
        yield
          assertEquals(userComp, Map.empty[RatingCategory, Glicko], "a rollback must not move competitive ratings")
          assertEquals(botComp, Map.empty[RatingCategory, Glicko], "a rollback must not move bot ratings")
          assertEquals(training.games, 0, "the legacy policy applies no training update either")
          assertEquals(change.map(_.outcome), Some(RatingOutcome.Skipped))
          assertEquals(change.flatMap(_.reason), Some("training-domain row is not applied under the legacy policy"))
      }
    }

  test("a training game re-delivered to the store moves the counters exactly once"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          player <- db.upsertOnLogin("google", "sub-dom-replay-1", None, IO.pure("ReplayPlayer"))
          human = Principal.User(player.id)
          _ <- db.register("anchor", "random", "hash-anchor-random")
          bot = Principal.Bot("anchor", "random")
          gameId <- GameId.random
          _      <- db.save(gameId, trainingFixture(human, bot))
          _      <- matrixBatch(db).flatMap(_.tick)
          once   <- db.trainingStateOf(player.id, RatingCategory.Blitz)
          // The same completed game handed to the store a second time — an overlapping tick or a retry after a
          // timeout. The competitive path survives this because it writes absolute ratings; these counters increment.
          replay = RatingUpdate(
            identity = RatedIdentity.User(player.id),
            category = RatingCategory.Blitz,
            before = once.glicko,
            after = once.glicko
          )
          _     <- db.applyTrainingUpdate(gameId, replay, Seat.Black, 1500.0, 1.0, java.time.Instant.now())
          twice <- db.trainingStateOf(player.id, RatingCategory.Blitz)
        yield
          assertEquals(once.games, 1)
          assertEquals(twice.games, 1, "a re-delivered game must not be counted twice")
          assertEquals(twice.wins, once.wins)
          assertEquals(twice.rating, once.rating)
      }
    }

  test("training games are absent from the competitive W-D-L record of both the human and the bot"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          player <- db.upsertOnLogin("google", "sub-dom-record-1", None, IO.pure("RecordPlayer"))
          human = Principal.User(player.id)
          _ <- db.register("anchor", "aggressive", "hash-anchor-aggressive")
          bot = Principal.Bot("anchor", "aggressive")
          gameId     <- GameId.random
          _          <- db.save(gameId, trainingFixture(human, bot))
          _          <- matrixBatch(db).flatMap(_.tick)
          training   <- db.trainingStateOf(player.id, RatingCategory.Blitz)
          humanTally <- db.categoryTalliesFor(human.externalId)
          botTally   <- db.categoryTalliesFor(bot.externalId)
        yield
          assertEquals(training.games, 1, "the training update itself did happen")
          assertEquals(humanTally, Map.empty[RatingCategory, ResultTally], "a training win is not a competitive win")
          assertEquals(botTally, Map.empty[RatingCategory, ResultTally], "a training loss is not on the bot's record")
      }
    }

  // ── #189: the training reference is captured when the row is queued ───────────────────────────────────────────

  /** A synthetic calibration, so these tests pin the capture mechanism rather than any production anchor value. */
  private def fixtureAnchors(version: String, epoch: Int, identity: String, targetElo: Double): AnchorSet =
    AnchorSet(
      version = version,
      epoch = epoch,
      category = RatingCategory.Blitz.wireName,
      anchors = List(Anchor(identity, targetElo, 1.0, "synthetic anchor for the #189 capture tests"))
    )

  /** The Glicko-2 state an anchor target stands for — the same mapping `TrainingEstimate.resolveBotReference` uses. */
  private def anchorGlicko(targetElo: Double): Glicko =
    Glicko(1500.0 + targetElo, TrainingEstimate.AnchorDeviation, Glicko.Initial.volatility)

  /** A user's first training estimate against `reference`. The first game has no idle inflation to apply, so the game
    * time does not enter the numbers.
    */
  private def firstGameAgainst(reference: Glicko, humanIsWhite: Boolean, score: Double): TrainingState =
    TrainingEstimate.update(TrainingState.Initial, reference, humanIsWhite, score, gameTime = Instant.EPOCH)

  private def assertSameEstimate(actual: TrainingState, expected: TrainingState)(using munit.Location): Unit =
    assertEqualsDouble(actual.rating, expected.rating, 1e-9)
    assertEqualsDouble(actual.deviation, expected.deviation, 1e-9)
    assertEqualsDouble(actual.volatility, expected.volatility, 1e-9)
    assertEquals(actual.games, expected.games)

  private def setBlitzRating(xa: Transactor[IO], bot: Principal.Bot, glicko: Glicko): IO[Unit] =
    sql"""INSERT INTO play.bot_ratings (team, name, category, rating, rd, vol)
          VALUES (${bot.team}, ${bot.name}, ${RatingCategory.Blitz.wireName},
                  ${glicko.rating}, ${glicko.deviation}, ${glicko.volatility})
          ON CONFLICT (team, name, category)
          DO UPDATE SET rating = EXCLUDED.rating, rd = EXCLUDED.rd, vol = EXCLUDED.vol""".update.run
      .transact(xa)
      .void

  /** What a row written before V11 carries: no reference columns at all. */
  private def forgetReference(xa: Transactor[IO], gameId: GameId): IO[Unit] =
    sql"""UPDATE play.game_results
          SET training_reference_source = NULL, training_reference_anchor_set = NULL,
              training_reference_anchor_epoch = NULL, training_reference_rating = NULL,
              training_reference_rd = NULL, training_reference_vol = NULL
          WHERE game_id = ${gameId.value}::uuid""".update.run.transact(xa).void

  /** A re-drain: the applied row goes back to the head of the queue and the estimate it produced is forgotten. */
  private def requeue(xa: Transactor[IO], gameId: GameId, userId: String): IO[Unit] =
    (sql"""UPDATE play.game_results
           SET rating_applied_at = NULL, rating_outcome = 'pending', rating_skip_reason = NULL,
               white_rating_before = NULL, white_rating_after = NULL,
               black_rating_before = NULL, black_rating_after = NULL
           WHERE game_id = ${gameId.value}::uuid""".update.run *>
      sql"DELETE FROM play.user_training_ratings WHERE user_id = $userId::uuid".update.run).transact(xa).void

  /** Whether PostgreSQL refuses `assignments` on the game's row with a CHECK violation (SQLSTATE 23514). */
  private def refusedByCheck(xa: Transactor[IO], gameId: GameId, assignments: Fragment): IO[Boolean] =
    (fr"UPDATE play.game_results SET" ++ assignments ++ fr"WHERE game_id = ${gameId.value}::uuid").update.run
      .transact(xa)
      .attempt
      .map {
        case Left(error: SQLException) => error.getSQLState == "23514"
        case _                         => false
      }

  test("save captures a training row's anchor reference with its set version and epoch; other rows record none"):
    val anchors = fixtureAnchors("fixture-v1", 7, "fixture/anchor", targetElo = 100.0)
    withContainers { pg =>
      storeAndXa(pg, anchors).use { (db, _) =>
        for
          player <- db.upsertOnLogin("google", "sub-ref-anchor-1", None, IO.pure("RefAnchor"))
          rival  <- db.upsertOnLogin("google", "sub-ref-anchor-2", None, IO.pure("RefAnchorRival"))
          human = Principal.User(player.id)
          training    <- GameId.random
          competitive <- GameId.random
          _           <- db.save(training, trainingFixture(human, Principal.Bot("fixture", "anchor")))
          _           <- db.save(competitive, competitiveFixture(human, Principal.User(rival.id)))
          trainingRef <- db.trainingReferenceOf(training)
          otherRef    <- db.trainingReferenceOf(competitive)
          unknownRef  <- GameId.random.flatMap(db.trainingReferenceOf)
        yield
          assertEquals(
            trainingRef,
            CapturedTrainingReference.Recorded(
              TrainingReference(TrainingReferenceSource.Anchor("fixture-v1", 7), anchorGlicko(100.0))
            ),
            "captured by save itself, before any batch has run"
          )
          assertEquals(otherRef, CapturedTrainingReference.NotRecorded)
          assertEquals(unknownRef, CapturedTrainingReference.NotRecorded)
      }
    }

  test("a drain delayed past a change in the bot's rating applies the rating the game faced"):
    val bot: Principal.Bot = Principal.Bot("acme", "delayed")
    val faced              = Glicko(1700.0, 80.0, 0.06)
    val moved              = Glicko(1300.0, 60.0, 0.06)
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        for
          player <- db.upsertOnLogin("google", "sub-ref-delay-1", None, IO.pure("RefDelay"))
          _      <- db.register(bot.team, bot.name, "hash-acme-delayed")
          _      <- setBlitzRating(xa, bot, faced)
          gameId <- GameId.random
          _      <- db.save(gameId, trainingFixture(Principal.User(player.id), bot))
          // Ladder games applied between the end of the game and the drain move the bot's stored rating.
          _        <- setBlitzRating(xa, bot, moved)
          _        <- matrixBatch(db).flatMap(_.tick)
          state    <- db.trainingStateOf(player.id, RatingCategory.Blitz)
          change   <- db.ratingChangeFor(gameId)
          captured <- db.trainingReferenceOf(gameId)
        yield
          assertEquals(
            captured,
            CapturedTrainingReference.Recorded(TrainingReference(TrainingReferenceSource.BotRating, faced))
          )
          assertSameEstimate(state, firstGameAgainst(faced, humanIsWhite = true, score = 1.0))
          assertNotEquals(state.rating, firstGameAgainst(moved, humanIsWhite = true, score = 1.0).rating)
          assertEquals(
            change.flatMap(_.black),
            Some(SeatRatingChange(faced.rating, faced.rating)),
            "the row records the reference it was applied against"
          )
      }
    }

  test("an anchor-set version bump does not move a queued game's reference"):
    val queuedWith         = fixtureAnchors("fixture-v1", 1, "fixture/anchor-bump", targetElo = 100.0)
    val drainedBy          = fixtureAnchors("fixture-v2", 2, "fixture/anchor-bump", targetElo = -300.0)
    val bot: Principal.Bot = Principal.Bot("fixture", "anchor-bump")
    withContainers { pg =>
      storeAndXa(pg, queuedWith).use { (db, _) =>
        for
          player <- db.upsertOnLogin("google", "sub-ref-bump-1", None, IO.pure("RefBump"))
          _      <- db.register(bot.team, bot.name, "hash-fixture-anchor-bump")
          gameId <- GameId.random
          _      <- db.save(
            gameId,
            trainingFixture(Principal.User(player.id), bot, result = GameResult.Win(Side.Black), humanIsWhite = false)
          )
          // The batch that reaches the row has been rebuilt with the next anchor set.
          _        <- matrixBatch(db, drainedBy).flatMap(_.tick)
          state    <- db.trainingStateOf(player.id, RatingCategory.Blitz)
          captured <- db.trainingReferenceOf(gameId)
        yield
          assertEquals(
            captured,
            CapturedTrainingReference.Recorded(
              TrainingReference(TrainingReferenceSource.Anchor("fixture-v1", 1), anchorGlicko(100.0))
            )
          )
          assertSameEstimate(state, firstGameAgainst(anchorGlicko(100.0), humanIsWhite = false, score = 1.0))
          assertNotEquals(
            state.rating,
            firstGameAgainst(anchorGlicko(-300.0), humanIsWhite = false, score = 1.0).rating
          )
      }
    }

  test("re-draining a training row reproduces the same numbers after the bot's rating has moved"):
    val bot: Principal.Bot = Principal.Bot("acme", "redrain")
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        for
          player       <- db.upsertOnLogin("google", "sub-ref-redrain-1", None, IO.pure("RefRedrain"))
          _            <- db.register(bot.team, bot.name, "hash-acme-redrain")
          _            <- setBlitzRating(xa, bot, Glicko(1650.0, 90.0, 0.06))
          gameId       <- GameId.random
          _            <- db.save(gameId, trainingFixture(Principal.User(player.id), bot, result = GameResult.Draw))
          _            <- matrixBatch(db).flatMap(_.tick)
          first        <- db.trainingStateOf(player.id, RatingCategory.Blitz)
          firstChange  <- db.ratingChangeFor(gameId)
          _            <- setBlitzRating(xa, bot, Glicko(1200.0, 50.0, 0.06))
          _            <- requeue(xa, gameId, player.id)
          _            <- matrixBatch(db).flatMap(_.tick)
          second       <- db.trainingStateOf(player.id, RatingCategory.Blitz)
          secondChange <- db.ratingChangeFor(gameId)
        yield
          assertEquals(first.games, 1)
          assertSameEstimate(second, first)
          assertEquals(secondChange, firstChange)
      }
    }

  test("a training row queued before references were captured applies from current state and gains none"):
    val bot: Principal.Bot = Principal.Bot("acme", "pre-capture")
    val current            = Glicko(1450.0, 65.0, 0.06)
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        for
          player <- db.upsertOnLogin("google", "sub-ref-pre-1", None, IO.pure("RefPreCapture"))
          _      <- db.register(bot.team, bot.name, "hash-acme-pre-capture")
          _      <- setBlitzRating(xa, bot, Glicko(1550.0, 70.0, 0.06))
          gameId <- GameId.random
          _ <- db.save(gameId, trainingFixture(Principal.User(player.id), bot, result = GameResult.Win(Side.Black)))
          _ <- forgetReference(xa, gameId)
          before <- db.trainingReferenceOf(gameId)
          _      <- setBlitzRating(xa, bot, current)
          _      <- matrixBatch(db).flatMap(_.tick)
          state  <- db.trainingStateOf(player.id, RatingCategory.Blitz)
          after  <- db.trainingReferenceOf(gameId)
        yield
          assertEquals(before, CapturedTrainingReference.NotRecorded)
          // Exactly what #149 did for every row: the bot's stored rating at drain time.
          assertSameEstimate(state, firstGameAgainst(current, humanIsWhite = true, score = 0.0))
          assertEquals(after, CapturedTrainingReference.NotRecorded, "no provenance is invented after the fact")
      }
    }

  test("a bot with no reference when the game was queued stays skipped even if it is rated before the drain"):
    val bot: Principal.Bot = Principal.Bot("acme", "unrated")
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        for
          player   <- db.upsertOnLogin("google", "sub-ref-unrated-1", None, IO.pure("RefUnrated"))
          _        <- db.register(bot.team, bot.name, "hash-acme-unrated")
          gameId   <- GameId.random
          _        <- db.save(gameId, trainingFixture(Principal.User(player.id), bot))
          captured <- db.trainingReferenceOf(gameId)
          _        <- setBlitzRating(xa, bot, Glicko(1500.0, 120.0, 0.06))
          _        <- matrixBatch(db).flatMap(_.tick)
          state    <- db.trainingStateOf(player.id, RatingCategory.Blitz)
          change   <- db.ratingChangeFor(gameId)
        yield
          assertEquals(captured, CapturedTrainingReference.Unavailable)
          assertEquals(state.games, 0)
          assertEquals(change.map(_.outcome), Some(RatingOutcome.Skipped))
          assertEquals(change.flatMap(_.reason), Some("no reference bot rating for acme/unrated in category 'blitz'"))
      }
    }

  test("the schema refuses a reference on a non-training row, an unknown source, or a source with the wrong fields"):
    withContainers { pg =>
      storeAndXa(pg).use { (db, xa) =>
        for
          player      <- db.upsertOnLogin("google", "sub-ref-check-1", None, IO.pure("RefCheck"))
          rival       <- db.upsertOnLogin("google", "sub-ref-check-2", None, IO.pure("RefCheckRival"))
          training    <- GameId.random
          competitive <- GameId.random
          _           <- db.save(training, trainingFixture(Principal.User(player.id), Principal.Bot("acme", "checked")))
          _           <- db.save(competitive, competitiveFixture(Principal.User(player.id), Principal.User(rival.id)))
          onCompetitive <- refusedByCheck(xa, competitive, fr"training_reference_source = 'unavailable'")
          unknownSource <- refusedByCheck(xa, training, fr"training_reference_source = 'guess'")
          anchorNoSet   <- refusedByCheck(
            xa,
            training,
            fr"""training_reference_source = 'anchor', training_reference_rating = 1500,
                 training_reference_rd = 50, training_reference_vol = 0.06"""
          )
          ratingWithEpoch <- refusedByCheck(
            xa,
            training,
            fr"""training_reference_source = 'bot_rating', training_reference_anchor_epoch = 1,
                 training_reference_rating = 1500, training_reference_rd = 50, training_reference_vol = 0.06"""
          )
          unavailableWithNumbers <- refusedByCheck(
            xa,
            training,
            fr"training_reference_source = 'unavailable', training_reference_rating = 1500"
          )
          stillCaptured <- db.trainingReferenceOf(training)
        yield
          assert(onCompetitive, "only a training row carries a reference")
          assert(unknownSource, "the source vocabulary is closed")
          assert(anchorNoSet, "an anchor reference names its set and epoch")
          assert(ratingWithEpoch, "a stored-rating reference names no anchor epoch")
          assert(unavailableWithNumbers, "a missing reference carries no numbers")
          assertEquals(
            stillCaptured,
            CapturedTrainingReference.Unavailable,
            "every refused write left the row as saved"
          )
      }
    }
