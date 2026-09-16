package dicechess.play.rating

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.EngineOps
import dicechess.play.store.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

class RatingBatchDomainSuite extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def store(pg: PostgreSQLContainer) =
    PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))

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
          _ <- db.register("house", "random", "hash-house-random")
          bot = Principal.Bot("house", "random")
          gameId <- GameId.random
          _ <- db.save(gameId, trainingFixture(human, bot, result = GameResult.Win(Side.White), humanIsWhite = true))
          _ <- matrixBatch(db).flatMap(_.tick)
          trainingState   <- db.trainingStateOf(player.id, RatingCategory.Blitz)
          userCompRatings <- db.categoryRatingsOf(RatedIdentity.User(player.id))
          botCompRatings  <- db.categoryRatingsOf(RatedIdentity.Bot("house", "random"))
          changeOpt       <- db.ratingChangeFor(gameId)
        yield
          // 1. Human training rating updated
          assertEquals(trainingState.games, 1)
          assertEquals(trainingState.wins, 1)
          assert(
            trainingState.rating > 1500.0,
            s"Human won against anchor, rating should increase: ${trainingState.rating}"
          )
          assert(trainingState.isProvisional, "1 game must be provisional")

          // 2. Strict domain isolation: human competitive ratings untouched
          assertEquals(userCompRatings, Map.empty[RatingCategory, Glicko], "user_ratings must remain empty")

          // 3. Strict domain isolation: bot competitive ratings untouched
          assertEquals(botCompRatings, Map.empty[RatingCategory, Glicko], "bot_ratings must remain empty")

          // 4. Stored game result row
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
          _ <- db.register("house", "random", "hash-house-random")
          bot = Principal.Bot("house", "random")
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
          assertEquals(change.get.reason, Some("no reference bot rating for house/random in category 'rapid'"))
      }
    }
