package dicechess.play.rating

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.EngineOps
import dicechess.play.store.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.*

/** The rating batch against a real PostgreSQL (#119): the claim queue, the exactly-once transaction, the skip paths,
  * and the property the design exists for — a "restart" (a brand-new batch instance over the same store) neither
  * re-applies old games nor misses new ones, because all state lives in the database.
  *
  * The suite shares one database across its tests (`TestContainerForAll`, no per-test reset), so each test uses its own
  * team namespace and asserts on its own game ids, never on global queue emptiness.
  */
class RatingBatchSuite extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def store(pg: PostgreSQLContainer) =
    PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))

  private def batch(db: PgGameStore): IO[RatingBatch] =
    StrengthCache.create.flatMap(cache =>
      RatingBatch.create(
        botStore = db,
        userStore = db,
        ratingStore = db,
        resultsStore = db,
        config = RatingBatch.Config.Default,
        strengthCache = cache
      )
    )

  private def endedFixture(
      white: Principal,
      black: Principal,
      rated: Boolean,
      result: GameResult = GameResult.Win(Side.White),
      termination: Termination = Termination.Resign,
      ladder: Boolean = false,
      // Blitz by the #280 mapping (321 s estimated) — the ladder's own control, so every test that does not care
      // about categories still exercises the one the platform actually plays on.
      timeControl: TimeControl = TimeControl.Fischer(300, 3)
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
      status = GameStatus.Ended(GameOver(result, termination)),
      timeControl = timeControl,
      remainingMs = Map(Seat.White -> 1000L, Seat.Black -> 1000L),
      lastRoll = Nil,
      turns = Vector.empty,
      rated = Some(rated),
      ladder = Some(ladder)
    )

  /** One ladder-scheduler game (#190) that `loser` loses — the shape the auto-park streak counts (`ladder = true`, the
    * marker that replaced CRN pairing's `pairingId` for this purpose).
    */
  private def saveLostLadderGame(
      db: PgGameStore,
      loser: Principal.Bot,
      winner: Principal.Bot,
      termination: Termination
  ): IO[Unit] =
    GameId.random.flatMap(id =>
      db.save(id, endedFixture(loser, winner, rated = true, GameResult.Win(Side.Black), termination, ladder = true))
    )

  private def registerPair(db: PgGameStore, team: String): IO[(Principal.Bot, Principal.Bot)] =
    for
      _ <- db.register(team, "alice", s"hash-$team-alice")
      _ <- db.register(team, "bob", s"hash-$team-bob")
    yield (Principal.Bot(team, "alice"), Principal.Bot(team, "bob"))

  private def stillQueued(db: PgGameStore, id: GameId): IO[Boolean] =
    db.unappliedRatedGames(1000).map(_.exists(_.gameId.value == id.value))

  test("a rated game shifts both ratings, shrinks both deviations, and is applied exactly once"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb1")
          id           <- GameId.random
          _            <- db.save(id, endedFixture(alice, bob, rated = true)) // alice (White) wins
          _            <- batch(db).flatMap(_.tick)
          aliceR       <- db.ratingOf("rb1", "alice").map(_.getOrElse(fail("alice missing")))
          bobR         <- db.ratingOf("rb1", "bob").map(_.getOrElse(fail("bob missing")))
          queued       <- stillQueued(db, id)
          _       <- batch(db).flatMap(_.tick) // second tick: nothing left to apply for this game
          aliceR2 <- db.ratingOf("rb1", "alice").map(_.getOrElse(fail("alice missing")))
        yield
          assert(aliceR.glickoRating > 1500.0, s"the winner must gain: ${aliceR.glickoRating}")
          assert(bobR.glickoRating < 1500.0, s"the loser must lose: ${bobR.glickoRating}")
          assert(aliceR.glickoRd < 350.0 && bobR.glickoRd < 350.0, "playing must shrink both RDs")
          assert(!queued, "an applied game must leave the queue")
          assertEquals(aliceR2, aliceR, "a second tick must not re-apply the same game")
      }
    }

  test("a casual game never enters the queue and changes no rating"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb2")
          id           <- GameId.random
          _            <- db.save(id, endedFixture(alice, bob, rated = false))
          queued       <- stillQueued(db, id)
          _            <- batch(db).flatMap(_.tick)
          aliceR       <- db.ratingOf("rb2", "alice")
        yield
          assert(!queued, "a casual game must not be queued for rating")
          assertEquals(aliceR, Some(BotRating.initial), "a casual game must leave the rating untouched")
      }
    }

  test("restart safety: a fresh batch instance re-applies nothing and picks up new games (#119)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb3")
          id1          <- GameId.random
          _            <- db.save(id1, endedFixture(alice, bob, rated = true))
          _            <- batch(db).flatMap(_.tick) // "before the restart"
          afterFirst   <- db.ratingOf("rb3", "alice").map(_.getOrElse(fail("alice missing")))
          // The "restart": a brand-new batch over the same store — no in-memory cursor to lose (#119's design).
          restarted <- batch(db)
          _         <- restarted.tick
          replayed  <- db.ratingOf("rb3", "alice").map(_.getOrElse(fail("alice missing")))
          _ = assertEquals(replayed, afterFirst, "a restarted batch must not re-apply an already-stamped game")
          id2    <- GameId.random
          _      <- db.save(id2, endedFixture(bob, alice, rated = true)) // bob (White) wins the second game
          _      <- restarted.tick
          queued <- stillQueued(db, id2)
          bobR   <- db.ratingOf("rb3", "bob").map(_.getOrElse(fail("bob missing")))
        yield
          assert(!queued, "the restarted batch must apply the new game")
          assert(
            bobR.glickoRating > 1500.0,
            s"bob won one of two: his second result must lift him: ${bobR.glickoRating}"
          )
      }
    }

  test("a rated game with a non-bot participant is stamped applied without touching any rating"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, _) <- registerPair(db, "rb4")
          id         <- GameId.random
          _          <- db.save(id, endedFixture(Principal.User("rb4-human"), alice, rated = true))
          _          <- batch(db).flatMap(_.tick)
          queued     <- stillQueued(db, id)
          aliceR     <- db.ratingOf("rb4", "alice")
          change     <- db.ratingChangeFor(id)
        yield
          assert(!queued, "an unappliable game must still be stamped, or it clogs the queue head forever")
          assertEquals(aliceR, Some(BotRating.initial), "no rating may change on a skipped game")
          // A skipped game is a FINAL "nobody's rating moved", not a not-yet: a client polling `applied` has to stop
          // here rather than wait for numbers that will never be written (#296).
          assertEquals(change, Some(GameRatingChange(applied = true, white = None, black = None)))
      }
    }

  test("an applied game records what it did to each seat's rating, not just the state it left behind (#296)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb-delta")
          id           <- GameId.random
          _            <- db.save(id, endedFixture(alice, bob, rated = true)) // alice (White) wins
          _            <- batch(db).flatMap(_.tick)
          change       <- db.ratingChangeFor(id).map(_.getOrElse(fail("a saved game must have a result row")))
          aliceR       <- db.ratingOf("rb-delta", "alice").map(_.getOrElse(fail("alice missing")))
          bobR         <- db.ratingOf("rb-delta", "bob").map(_.getOrElse(fail("bob missing")))
        yield
          assert(change.applied)
          val white = change.white.getOrElse(fail("the winner's movement must be recorded"))
          val black = change.black.getOrElse(fail("the loser's movement must be recorded"))
          // `before` is only knowable while the batch holds it — the instant the transaction commits, the bot's row
          // carries `after` and the pre-game number is gone. That is the whole reason it is written here.
          assertEquals(white.before, Glicko.Initial.rating, "before is the PRE-game rating of the winning seat")
          assertEquals(black.before, Glicko.Initial.rating)
          assertEquals(white.after, aliceR.glickoRating, "after is exactly what landed on the participant's own row")
          assertEquals(black.after, bobR.glickoRating)
          // The property the reported bug violated: a client reading THIS row can never show a winner losing rating.
          assert(white.after > white.before, s"a win can only raise a rating: $white")
          assert(black.after < black.before, s"a loss can only lower one: $black")
      }
    }

  test("a game the batch has not reached yet reports not-applied rather than a guessed delta (#296)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb-pending")
          id           <- GameId.random
          _            <- db.save(id, endedFixture(alice, bob, rated = true))
          pending      <- db.ratingChangeFor(id)
          unknown      <- GameId.random.flatMap(db.ratingChangeFor)
        yield
          assertEquals(
            pending,
            Some(GameRatingChange(applied = false, white = None, black = None)),
            "a finished-but-unapplied game is a temporary 'not yet' — the state a client polls through"
          )
          assertEquals(unknown, None, "an id with no result row is a missing game, not an unapplied one")
      }
    }

  test("a rated game between UNREGISTERED bots is stamped applied without crashing"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          id <- GameId.random
          _ <- db.save(id, endedFixture(Principal.Bot("rb5-ghost", "x"), Principal.Bot("rb5-ghost", "y"), rated = true))
          _ <- batch(db).flatMap(_.tick)
          queued <- stillQueued(db, id)
        yield assert(!queued, "a game between unregistered bots must be stamped and skipped")
      }
    }

  test("rated self-play is stamped applied without a rating change"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, _) <- registerPair(db, "rb6")
          id         <- GameId.random
          _          <- db.save(id, endedFixture(alice, alice, rated = true))
          _          <- batch(db).flatMap(_.tick)
          queued     <- stillQueued(db, id)
          aliceR     <- db.ratingOf("rb6", "alice")
        yield
          assert(!queued, "self-play must be stamped and skipped")
          assertEquals(aliceR, Some(BotRating.initial), "self-play carries no rating information")
      }
    }

  test("unappliedRatedGames returns only rated, unapplied rows, oldest finished first"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb7")
          idOld        <- GameId.random
          _            <- db.save(idOld, endedFixture(alice, bob, rated = true))
          _            <- IO.sleep(20.millis) // distinguishable finished_at (DB-generated)
          idNew        <- GameId.random
          _            <- db.save(idNew, endedFixture(bob, alice, rated = true))
          idCasual     <- GameId.random
          _            <- db.save(idCasual, endedFixture(alice, bob, rated = false))
          _            <- db.markRatingApplied(idOld)
          queue        <- db
            .unappliedRatedGames(1000)
            .map(_.map(_.gameId.value).filter(Set(idOld, idNew, idCasual).map(_.value)))
        yield assertEquals(queue, List(idNew.value), "applied and casual rows must be excluded")
      }
    }

  test("the batch parks a bot that lost its last several ladder games on the clock (#150)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb8")
          _            <- db.setOnLadder("rb8", "alice", onLadder = true)
          _            <- db.setOnLadder("rb8", "bob", onLadder = true)
          _            <- List
            .fill(RatingBatch.Config.DefaultLadderTimeoutParkGames)(())
            .traverse_(_ => saveLostLadderGame(db, loser = alice, winner = bob, Termination.Timeout))
          _           <- batch(db).flatMap(_.tick)
          aliceRating <- db.ratingOf("rb8", "alice")
          bobRating   <- db.ratingOf("rb8", "bob")
        yield
          assertEquals(aliceRating.map(_.onLadder), Some(false), "alice timed out every game in the window")
          assertEquals(bobRating.map(_.onLadder), Some(true), "banking timeout wins must never park the winner")
      }
    }

  test("the batch forgives fewer timeouts than the threshold — a transient blip must not park a bot"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb9")
          _            <- db.setOnLadder("rb9", "alice", onLadder = true)
          _            <- List
            .fill(RatingBatch.Config.DefaultLadderTimeoutParkGames - 1)(())
            .traverse_(_ => saveLostLadderGame(db, loser = alice, winner = bob, Termination.Timeout))
          _           <- batch(db).flatMap(_.tick)
          aliceRating <- db.ratingOf("rb9", "alice")
        yield assertEquals(
          aliceRating.map(_.onLadder),
          Some(true),
          s"the default threshold is ${RatingBatch.Config.DefaultLadderTimeoutParkGames} games, not one fewer"
        )
      }
    }

  test("the batch never parks a bot that keeps answering — repeated normal losses are not a timeout streak"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb10")
          _            <- db.setOnLadder("rb10", "alice", onLadder = true)
          _            <- List
            .fill(RatingBatch.Config.DefaultLadderTimeoutParkGames)(())
            .traverse_(_ => saveLostLadderGame(db, loser = alice, winner = bob, Termination.KingCaptured))
          _           <- batch(db).flatMap(_.tick)
          aliceRating <- db.ratingOf("rb10", "alice")
        yield assertEquals(aliceRating.map(_.onLadder), Some(true), "a weak-but-live bot stays on the ladder")
      }
    }

  test("a game between two accounts moves both ratings — one transaction across two rows of the users table"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          winner        <- db.upsertOnLogin("google", "sub-rb-uvu-w", None, IO.pure("RbWinner"))
          loser         <- db.upsertOnLogin("google", "sub-rb-uvu-l", None, IO.pure("RbLoser"))
          id            <- GameId.random
          _             <- db.save(id, endedFixture(Principal.User(winner.id), Principal.User(loser.id), rated = true))
          strengthCache <- StrengthCache.create
          _             <- RatingBatch
            .create(
              botStore = db,
              userStore = db,
              ratingStore = db,
              resultsStore = db,
              config = RatingBatch.Config.Default,
              strengthCache = strengthCache
            )
            .flatMap(_.tick)
          winnerRating <- db.ratingOf(winner.id)
          loserRating  <- db.ratingOf(loser.id)
          queued       <- stillQueued(db, id)
        yield
          assert(winnerRating.exists(_.glickoRating > 1500), s"the winner must gain: $winnerRating")
          assert(loserRating.exists(_.glickoRating < 1500), s"the loser must lose: $loserRating")
          assert(winnerRating.exists(_.glickoRd < 350), "a played game must shrink the deviation")
          assert(!queued, "the game must be stamped applied in the same transaction as the two writes")
      }
    }

  test("a rated game also moves the category scale its own time control belongs to, and only that one (#280)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb-cat1")
          blitzGame    <- GameId.random
          _            <- db.save(blitzGame, endedFixture(alice, bob, rated = true)) // 5+3 → Blitz
          _            <- batch(db).flatMap(_.tick)
          aliceBlitz   <- db.categoryRatingOf(RatedIdentity.of(alice), RatingCategory.Blitz)
          bobBlitz     <- db.categoryRatingOf(RatedIdentity.of(bob), RatingCategory.Blitz)
          aliceRapid   <- db.categoryRatingOf(RatedIdentity.of(alice), RatingCategory.Rapid)
          aliceBullet  <- db.categoryRatingOf(RatedIdentity.of(alice), RatingCategory.Bullet)
          aliceShared  <- db.ratingOf("rb-cat1", "alice").map(_.getOrElse(fail("alice missing")))
        yield
          assert(aliceBlitz.rating > 1500.0, s"the winner must gain on the Blitz scale: $aliceBlitz")
          assert(bobBlitz.rating < 1500.0, s"the loser must lose on the Blitz scale: $bobBlitz")
          assert(aliceBlitz.deviation < 350.0, "playing must shrink the category deviation too")
          assertEquals(aliceRapid, Glicko.Initial, "a Blitz game must leave the Rapid scale untouched")
          assertEquals(aliceBullet, Glicko.Initial, "and the Bullet one")
          // The transitional invariant phase 2 relies on: with both scales starting from the same fresh state, a
          // participant that only ever plays one category reads identically on both. Divergence is exactly the
          // traffic that is NOT in the seeded category.
          assertEquals(aliceBlitz.rating, aliceShared.glickoRating, "one category of traffic keeps the two in step")
      }
    }

  test("the category scales are independent: a Rapid game moves neither the Blitz nor the Bullet one (#280)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb-cat2")
          blitzGame    <- GameId.random
          _            <- db.save(blitzGame, endedFixture(alice, bob, rated = true)) // 5+3 → Blitz
          _            <- batch(db).flatMap(_.tick)
          blitzBefore  <- db.categoryRatingOf(RatedIdentity.of(alice), RatingCategory.Blitz)
          rapidGame    <- GameId.random
          // 10+10 → Rapid, and alice LOSES it: had the two scales been one, the Blitz number would move down here.
          _ <- db.save(
            rapidGame,
            endedFixture(
              alice,
              bob,
              rated = true,
              result = GameResult.Win(Side.Black),
              timeControl = TimeControl.Fischer(600, 10)
            )
          )
          _          <- batch(db).flatMap(_.tick)
          blitzAfter <- db.categoryRatingOf(RatedIdentity.of(alice), RatingCategory.Blitz)
          rapidAfter <- db.categoryRatingOf(RatedIdentity.of(alice), RatingCategory.Rapid)
        yield
          assertEquals(blitzAfter, blitzBefore, "a Rapid result must not touch the Blitz scale")
          assert(rapidAfter.rating < 1500.0, s"the Rapid loss must land on the Rapid scale: $rapidAfter")
      }
    }

  test("an uncategorised row already in the queue is stamped applied and moves nothing (#280)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb-cat3")
          id           <- GameId.random
          // A game marked rated with a clockless control cannot be CREATED any more — `GameRegistry.isRated` degrades
          // it — so this row is what the queue held at the moment #280's cutover deployed. Saving the snapshot
          // directly is the only way to reproduce it, and the batch must drain it rather than choke on it.
          _      <- db.save(id, endedFixture(alice, bob, rated = true, timeControl = TimeControl.Unlimited))
          _      <- batch(db).flatMap(_.tick)
          shared <- db.ratingOf("rb-cat3", "alice").map(_.getOrElse(fail("alice missing")))
          blitz  <- db.categoryRatingOf(RatedIdentity.of(alice), RatingCategory.Blitz)
          rapid  <- db.categoryRatingOf(RatedIdentity.of(alice), RatingCategory.Rapid)
          bullet <- db.categoryRatingOf(RatedIdentity.of(alice), RatingCategory.Bullet)
          queued <- stillQueued(db, id)
        yield
          assertEquals(shared, BotRating.initial, "no scale may move for a game that belongs to none")
          assertEquals(blitz, Glicko.Initial, "an unbounded game belongs on no category scale")
          assertEquals(rapid, Glicko.Initial)
          assertEquals(bullet, Glicko.Initial)
          // Stamped, not left pending: an unstamped row would sit at the head of the queue forever, and this one can
          // never become ratable.
          assert(!queued, "the skip must still claim the row")
      }
    }

  test("an account's category rating lands in user_ratings, the same rule as a bot's (#280)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          winner      <- db.upsertOnLogin("google", "sub-rb-cat-w", None, IO.pure("RbCatWinner"))
          loser       <- db.upsertOnLogin("google", "sub-rb-cat-l", None, IO.pure("RbCatLoser"))
          id          <- GameId.random
          _           <- db.save(id, endedFixture(Principal.User(winner.id), Principal.User(loser.id), rated = true))
          _           <- batch(db).flatMap(_.tick)
          winnerBlitz <- db.categoryRatingOf(RatedIdentity.User(winner.id), RatingCategory.Blitz)
          loserBlitz  <- db.categoryRatingOf(RatedIdentity.User(loser.id), RatingCategory.Blitz)
          winnerRapid <- db.categoryRatingOf(RatedIdentity.User(winner.id), RatingCategory.Rapid)
        yield
          assert(winnerBlitz.rating > 1500.0, s"the winner must gain: $winnerBlitz")
          assert(loserBlitz.rating < 1500.0, s"the loser must lose: $loserBlitz")
          assertEquals(winnerRapid, Glicko.Initial, "an unplayed category stays fresh — the row is simply absent")
      }
    }

  test("an account vs any two REGISTERED bots rates both games — no curation is needed any more (#279)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          player     <- db.upsertOnLogin("google", "sub-rb-uvb", None, IO.pure("RbMixed"))
          _          <- db.register("rb-mixed", "first", "hash-rb-first")
          _          <- db.register("rb-mixed", "second", "hash-rb-second")
          firstGame  <- GameId.random
          secondGame <- GameId.random
          me = Principal.User(player.id)
          _             <- db.save(firstGame, endedFixture(me, Principal.Bot("rb-mixed", "first"), rated = true))
          _             <- db.save(secondGame, endedFixture(me, Principal.Bot("rb-mixed", "second"), rated = true))
          strengthCache <- StrengthCache.create
          _             <- RatingBatch
            .create(
              botStore = db,
              userStore = db,
              ratingStore = db,
              resultsStore = db,
              config = RatingBatch.Config.Default,
              strengthCache = strengthCache
            )
            .flatMap(_.tick)
          playerRating <- db.ratingOf(player.id)
          first        <- db.ratingOf("rb-mixed", "first")
          second       <- db.ratingOf("rb-mixed", "second")
          firstQueued  <- stillQueued(db, firstGame)
          secondQueued <- stillQueued(db, secondGame)
        yield
          // Two wins for the human: the shift is larger than a single-win test would show, which is itself evidence
          // both games moved the rating rather than one silently sitting out.
          assert(
            playerRating.exists(_.glickoRating > 1520),
            s"two rated wins must move the rating twice: $playerRating"
          )
          assert(first.exists(_.glickoRating < 1500), s"the first opponent lost a rated game: $first")
          assert(second.exists(_.glickoRating < 1500), s"the second opponent lost a rated game too: $second")
          assert(!firstQueued && !secondQueued, "both rows are stamped applied")
      }
    }

  test("a guest participant is never rated, and a deleted account's game is skipped rather than crashing the tick"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          ghost     <- db.upsertOnLogin("google", "sub-rb-ghost", None, IO.pure("RbGhost"))
          _         <- db.register("rb-guest", "opponent", "hash-rb-guest-opponent")
          guestGame <- GameId.random
          ghostGame <- GameId.random
          bot = Principal.Bot("rb-guest", "opponent")
          _ <- db.save(
            guestGame,
            endedFixture(Principal.Guest("0197f0a0-0000-7000-8000-0000000c0248"), bot, rated = true)
          )
          _ <- db.save(ghostGame, endedFixture(Principal.User(ghost.id), bot, rated = true))
          // The account plays a rated game and is then deleted: its user: id lingers in game_results forever (#237).
          _             <- db.deleteUser(ghost.id)
          strengthCache <- StrengthCache.create
          _             <- RatingBatch
            .create(
              botStore = db,
              userStore = db,
              ratingStore = db,
              resultsStore = db,
              config = RatingBatch.Config.Default,
              strengthCache = strengthCache
            )
            .flatMap(_.tick)
          botRating   <- db.ratingOf("rb-guest", "opponent")
          guestQueued <- stillQueued(db, guestGame)
          ghostQueued <- stillQueued(db, ghostGame)
        yield
          assertEquals(
            botRating.map(_.glickoRating),
            Some(1500.0),
            "neither a guest nor a vanished account may move a bot's rating"
          )
          assert(!guestQueued, "the guest game is stamped applied, not left at the head of the queue")
          assert(!ghostQueued, "an unresolvable participant is a skip, not a poisoned row that halts the batch")
      }
    }

  test("a game against the player's OWN bot is not rated — the rule #248 could not reach until #253"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          owner <- db.upsertOnLogin("google", "sub-rb-owner", None, IO.pure("RbOwner"))
          // Owned by the very player it faces — no curation needed to reach this rule at all any more (#279).
          _       <- db.register("rb-own", "mine", "hash-rb-own", owner = Some(Principal.User(owner.id).externalId))
          _       <- db.register("rb-own", "theirs", "hash-rb-theirs")
          ownGame <- GameId.random
          strangerGame <- GameId.random
          me = Principal.User(owner.id)
          _             <- db.save(ownGame, endedFixture(me, Principal.Bot("rb-own", "mine"), rated = true))
          _             <- db.save(strangerGame, endedFixture(me, Principal.Bot("rb-own", "theirs"), rated = true))
          strengthCache <- StrengthCache.create
          _             <- RatingBatch
            .create(
              botStore = db,
              userStore = db,
              ratingStore = db,
              resultsStore = db,
              config = RatingBatch.Config.Default,
              strengthCache = strengthCache
            )
            .flatMap(_.tick)
          mine     <- db.ratingOf("rb-own", "mine")
          theirs   <- db.ratingOf("rb-own", "theirs")
          player   <- db.ratingOf(owner.id)
          ownStill <- stillQueued(db, ownGame)
        yield
          assertEquals(
            mine.map(_.glickoRating),
            Some(1500.0),
            "beating your own bot must move nothing — that is farming with extra steps"
          )
          assert(theirs.exists(_.glickoRating < 1500), s"someone else's bot still counts: $theirs")
          // The player gained from the stranger's bot only, so a single win's worth — not two.
          assert(player.exists(_.glickoRating > 1500), s"the legitimate game still rated: $player")
          assert(!ownStill, "the skipped game is stamped applied, not left clogging the queue")
      }
    }

  test("a tick that applies a rated game also warms the strength cache with a report that includes it (#181)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob)  <- registerPair(db, "rb11")
          id            <- GameId.random
          _             <- db.save(id, endedFixture(alice, bob, rated = true)) // alice (White) wins
          strengthCache <- StrengthCache.create
          before        <- strengthCache.get
          _             <- RatingBatch
            .create(
              botStore = db,
              userStore = db,
              ratingStore = db,
              resultsStore = db,
              config = RatingBatch.Config.Default,
              strengthCache = strengthCache
            )
            .flatMap(_.tick)
          after <- strengthCache.get
        yield
          assertEquals(before, None, "the cache starts cold")
          assert(after.isDefined, "a tick that applied a game must warm the cache")
          assert(
            after.exists(_.pairwise.exists(p => p.perspective == "rb11/alice" || p.opponent == "rb11/alice")),
            "the refreshed report must include the game the same tick just applied"
          )
      }
    }

  test(
    "a batchSize of 1 forces drainQueue's multi-page recursion, and one outer tick still applies every page " +
      "and warms the cache with all of them (#181)"
  ):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob)  <- registerPair(db, "rb12")
          id1           <- GameId.random
          id2           <- GameId.random
          _             <- db.save(id1, endedFixture(alice, bob, rated = true))
          _             <- IO.sleep(20.millis) // distinguishable finished_at (DB-generated), same as the rb7 test
          _             <- db.save(id2, endedFixture(bob, alice, rated = true))
          strengthCache <- StrengthCache.create
          onePerPage = RatingBatch.Config.Default.copy(batchSize = 1)
          _ <- RatingBatch
            .create(
              botStore = db,
              userStore = db,
              ratingStore = db,
              resultsStore = db,
              config = onePerPage,
              strengthCache = strengthCache
            )
            .flatMap(_.tick)
          queued1 <- stillQueued(db, id1)
          queued2 <- stillQueued(db, id2)
          report  <- strengthCache.get
        yield
          assert(!queued1 && !queued2, "one outer tick must drain every page, not just the first")
          assert(
            report.exists(_.pairwise.exists(p => p.perspective == "rb12/alice" || p.opponent == "rb12/alice")),
            "the cache refresh must run once at the end, after every page's games are already applied"
          )
      }
    }

/** The auto-park check's failure handling, over fakes — no container. */
class RatingBatchResilienceSuite extends CatsEffectSuite:

  private val alice: Principal.Bot = Principal.Bot("acme", "alice")
  private val bob: Principal.Bot   = Principal.Bot("acme", "bob")

  private val aliceTimedOut: GameResultRow = GameResultRow(
    GameId("11111111-1111-1111-1111-111111111111"),
    alice.externalId,
    bob.externalId,
    Some(-1),
    "timeout",
    rated = true,
    timeControl = "Fischer(300,3)",
    serverSeed = "seed",
    pairingId = None,
    ladder = true,
    finishedAt = java.time.Instant.EPOCH
  )

  /** Hands the batch exactly one queued game, then reports the queue drained. */
  private def oneGameQueue(queue: Ref[IO, List[GameResultRow]]): RatingStore = new RatingStore:
    def unappliedRatedGames(limit: Int): IO[List[GameResultRow]]                              = queue.getAndSet(Nil)
    def markRatingApplied(gameId: GameId): IO[Unit]                                           = IO.unit
    def applyRatingUpdate(gameId: GameId, white: RatingUpdate, black: RatingUpdate): IO[Unit] = IO.unit
    def ratingChangeFor(gameId: GameId): IO[Option[GameRatingChange]]                         = IO.pure(None)
    def categoryRatingOf(identity: RatedIdentity, category: RatingCategory): IO[Glicko]       =
      IO.pure(Glicko.Initial)
    def categoryRatingsOf(identity: RatedIdentity): IO[Map[RatingCategory, Glicko]] = IO.pure(Map.empty)

  private val unreachableResults: GameResultsStore = new GameResultsStore:
    def recentResultsFor(externalId: String, limit: Int): IO[List[GameResultRow]] =
      IO.raiseError(new RuntimeException("connection pool exhausted"))
    def finishedRatedSince(since: java.time.Instant): IO[List[GameResultRow]] = IO.pure(Nil)
    def playerGamesPage(
        externalIds: List[String],
        before: Option[java.time.Instant],
        opponent: Option[OpponentFilter],
        result: Option[PovResultFilter],
        limit: Int
    ): IO[GameResultsStore.Page] = IO.pure(GameResultsStore.Page(Nil, hasMore = false))
    def opponentsFor(externalIds: List[String]): IO[List[OpponentAggregateRow]] = IO.pure(Nil)

  test("a history query that fails mid-check is logged and never aborts the tick around it"):
    for
      bots          <- BotStore.inMemory
      _             <- bots.register("acme", "alice", "hash-alice")
      _             <- bots.register("acme", "bob", "hash-bob")
      _             <- bots.setOnLadder("acme", "alice", onLadder = true)
      _             <- bots.setOnLadder("acme", "bob", onLadder = true)
      queue         <- Ref.of[IO, List[GameResultRow]](List(aliceTimedOut))
      strengthCache <- StrengthCache.create
      batch         <- RatingBatch.create(
        botStore = bots,
        userStore = noUsers,
        ratingStore = oneGameQueue(queue),
        resultsStore = unreachableResults,
        config = RatingBatch.Config.Default,
        strengthCache = strengthCache
      )
      // The rating for this row has already committed by the time the check runs, so raising here would abort the rest
      // of the page for unrelated bots and buy nothing — the row is stamped and never returns to the queue.
      _           <- batch.tick
      aliceRating <- bots.ratingOf("acme", "alice")
    yield assertEquals(aliceRating.map(_.onLadder), Some(true), "a check that never completed must park nobody")

  /** A store with no accounts — for the bot-only suites, where reaching a user path would be the bug. */
  private val noUsers: UserStore = new UserStore:
    def upsertOnLogin(p: String, s: String, e: Option[String], n: IO[String]): IO[UserAccount] =
      IO.raiseError(AssertionError("unused"))
    def userById(id: String): IO[Option[UserAccount]]                        = IO.pure(None)
    def byNickname(nickname: String): IO[Option[UserAccount]]                = IO.pure(None)
    def ratingOf(userId: String): IO[Option[UserRating]]                     = IO.pure(None)
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.raiseError(AssertionError("unused"))
    def linkGuest(userId: String, guestId: String): IO[GuestLink]            = IO.raiseError(AssertionError("unused"))
    def guestsOf(userId: String): IO[List[String]]                           = IO.pure(Nil)
    def deleteUser(userId: String): IO[Boolean]                              = IO.raiseError(AssertionError("unused"))

  /** Counts calls to `finishedRatedSince` rather than serving real rows — the strength refresh's own cost the cache
    * exists to bound, made observable without a container (#181).
    */
  private def countingResults(counter: Ref[IO, Int]): GameResultsStore = new GameResultsStore:
    def recentResultsFor(externalId: String, limit: Int): IO[List[GameResultRow]] = IO.pure(Nil)
    def finishedRatedSince(since: java.time.Instant): IO[List[GameResultRow]]     = counter.update(_ + 1).as(Nil)
    def playerGamesPage(
        externalIds: List[String],
        before: Option[java.time.Instant],
        opponent: Option[OpponentFilter],
        result: Option[PovResultFilter],
        limit: Int
    ): IO[GameResultsStore.Page] = IO.pure(GameResultsStore.Page(Nil, hasMore = false))
    def opponentsFor(externalIds: List[String]): IO[List[OpponentAggregateRow]] = IO.pure(Nil)

  test("a cold cache is warmed even with nothing to apply, but a warm one is not refreshed again for free (#181)"):
    for
      bots          <- BotStore.inMemory
      refreshCount  <- Ref.of[IO, Int](0)
      emptyQueue    <- Ref.of[IO, List[GameResultRow]](Nil)
      strengthCache <- StrengthCache.create
      batch         <- RatingBatch.create(
        botStore = bots,
        userStore = noUsers,
        ratingStore = oneGameQueue(emptyQueue),
        resultsStore = countingResults(refreshCount),
        config = RatingBatch.Config.Default,
        strengthCache = strengthCache
      )
      _      <- batch.tick
      countA <- refreshCount.get
      _      <- batch.tick
      countB <- refreshCount.get
    yield
      assertEquals(countA, 1, "a cold cache must be warmed even when the drain applied nothing")
      assertEquals(countB, 1, "a warm cache must not be refreshed again by a tick that applied nothing new")

  /** The two ends of the #215 knob, both without a clock: the default interval is far longer than the test, so the
    * second rebuild is unreachable; a zero interval is always elapsed, so every applying tick rebuilds.
    */
  private def countedRebuilds(config: RatingBatch.Config, ticks: Int): IO[Int] =
    for
      bots          <- BotStore.inMemory
      _             <- bots.register("acme", "alice", "hash-alice")
      _             <- bots.register("acme", "bob", "hash-bob")
      refreshCount  <- Ref.of[IO, Int](0)
      queue         <- Ref.of[IO, List[GameResultRow]](Nil)
      strengthCache <- StrengthCache.create
      batch         <- RatingBatch.create(
        bots,
        noUsers,
        oneGameQueue(queue),
        countingResults(refreshCount),
        config,
        strengthCache
      )
      // Refilled before each tick, so every tick genuinely applies a game — the pre-#215 trigger, fired repeatedly.
      _     <- (queue.set(List(aliceTimedOut)) *> batch.tick).replicateA_(ticks)
      count <- refreshCount.get
    yield count

  test("games landing inside the refresh interval do not each trigger a full-corpus rebuild (#215)"):
    countedRebuilds(RatingBatch.Config.Default, ticks = 4).map: count =>
      assertEquals(
        count,
        1,
        "only the cold-cache warm-up may rebuild; the other three ticks are inside the 15-minute interval"
      )

  test("a zero refresh interval asks for the pre-#215 cadence back: every applying tick rebuilds (#215)"):
    countedRebuilds(RatingBatch.Config.Default.copy(strengthRefreshInterval = Duration.Zero), ticks = 4).map: count =>
      assertEquals(count, 4, "with no interval to wait out, every tick that applied a game must rebuild")

/** Pure parsing/config/streak logic — no container. */
class RatingBatchPureSuite extends munit.FunSuite:

  private val alice: Principal.Bot = Principal.Bot("acme", "alice")
  private val bob: Principal.Bot   = Principal.Bot("acme", "bob")

  private def row(
      white: Principal.Bot,
      black: Principal.Bot,
      result: Int,
      termination: String,
      ladder: Boolean,
      at: Long,
      gameId: String
  ): GameResultRow =
    GameResultRow(
      GameId(gameId),
      white.externalId,
      black.externalId,
      Some(result),
      termination,
      rated = true,
      timeControl = "Fischer(300,3)",
      serverSeed = "seed",
      pairingId = None,
      ladder = ladder,
      finishedAt = java.time.Instant.EPOCH.plusSeconds(at)
    )

  test("isTimeoutLossFor is seat-aware, ignores wins, and never matches a bot that did not play"):
    val carol: Principal.Bot = Principal.Bot("acme", "carol")
    assert(RatingBatch.isTimeoutLossFor(row(alice, bob, -1, "timeout", ladder = true, 1, "g1"), alice))
    assert(RatingBatch.isTimeoutLossFor(row(bob, alice, 1, "timeout", ladder = true, 2, "g2"), alice))
    assert(
      !RatingBatch.isTimeoutLossFor(row(alice, bob, 1, "timeout", ladder = true, 3, "g3"), alice),
      "flagging the OPPONENT is a win, not a loss"
    )
    assert(!RatingBatch.isTimeoutLossFor(row(alice, bob, -1, "king_captured", ladder = true, 4, "g4"), alice))
    assert(!RatingBatch.isTimeoutLossFor(row(alice, bob, -1, "timeout", ladder = true, 5, "g5"), carol))

  test("shouldPark needs the whole streak: one timeout loss is forgiven, two are not"):
    val one = List(row(bob, alice, result = 1, "timeout", ladder = true, at = 100, "g1"))
    val two = row(bob, alice, result = 1, "timeout", ladder = true, at = 200, "g2") :: one
    assert(!RatingBatch.shouldPark(one, alice, parkGames = 2), "a single loss is a blip, not a dead bot")
    assert(RatingBatch.shouldPark(two, alice, parkGames = 2))
    assert(!RatingBatch.shouldPark(two, bob, parkGames = 2), "the bot banking those wins must never be parked")

  test("shouldPark counts only losses on the clock — a game the bot actually answered breaks the streak"):
    val answered = row(bob, alice, result = 1, "king_captured", ladder = true, at = 300, "g3")
    val streak   = List(
      row(bob, alice, result = 1, "timeout", ladder = true, at = 200, "g2"),
      row(bob, alice, result = 1, "timeout", ladder = true, at = 100, "g1")
    )
    assert(RatingBatch.shouldPark(streak, alice, parkGames = 2))
    assert(
      !RatingBatch.shouldPark(answered :: streak, alice, parkGames = 2),
      "the newer answered game displaces the older timeout from the window"
    )

  test("shouldPark ignores non-ladder games — a casual or challenge timeout can never park a bot"):
    val casual = List(
      row(bob, alice, result = 1, "timeout", ladder = false, at = 401, "casual-1"),
      row(alice, bob, result = -1, "timeout", ladder = false, at = 400, "casual-2")
    )
    val ladderLoss = List(row(bob, alice, result = 1, "timeout", ladder = true, at = 100, "g1"))
    assert(
      !RatingBatch.shouldPark(casual ++ ladderLoss, alice, parkGames = 2),
      "three timeout losses, but only one of them from a ladder game"
    )

  test("parkScanLimit is bounded but never narrows to the bare ladder-only need"):
    assertEquals(
      RatingBatch.parkScanLimit(RatingBatch.Config.DefaultLadderTimeoutParkGames),
      GameResultsStore.DefaultRecentLimit
    )
    assertEquals(RatingBatch.parkScanLimit(100), 400)
    assert(
      RatingBatch.parkScanLimit(RatingBatch.Config.DefaultLadderTimeoutParkGames) >
        RatingBatch.Config.DefaultLadderTimeoutParkGames,
      "the window must leave room for casual games interleaved with the ladder games"
    )

  test("Principal.fromBotExternalId accepts only the canonical bot:team:<team>:<name> shape"):
    assertEquals(
      Principal.fromBotExternalId("bot:team:acme:alice"),
      Some(Principal.Bot("acme", "alice")): Option[Principal.Bot]
    )
    assertEquals(Principal.fromBotExternalId("guest:0198-uuid"), None)
    assertEquals(Principal.fromBotExternalId("user:42"), None)
    assertEquals(
      Principal.fromBotExternalId("bot:greedy"),
      None,
      "legacy bot:<algorithm> ids are not registered identities"
    )
    assertEquals(Principal.fromBotExternalId("bot:team:acme:"), None, "an empty name must not parse")
    assertEquals(Principal.fromBotExternalId("bot:team::alice"), None, "an empty team must not parse")

  test("scores maps the white-POV result vocabulary and nothing else"):
    assertEquals(RatingBatch.scores(1), Some((1.0, 0.0)))
    assertEquals(RatingBatch.scores(0), Some((0.5, 0.5)))
    assertEquals(RatingBatch.scores(-1), Some((0.0, 1.0)))
    assertEquals(RatingBatch.scores(2), None)

  test(
    "a non-positive or unparseable interval disables the batch; a bad batch size or park games falls back to the default"
  ):
    assertEquals(RatingBatch.Config.fromValues(Some("0"), None, None), None)
    assertEquals(RatingBatch.Config.fromValues(Some("-5"), None, None), None)
    assertEquals(RatingBatch.Config.fromValues(Some("junk"), None, None), None)
    assertEquals(RatingBatch.Config.fromValues(None, Some("50"), None), None)
    assertEquals(
      RatingBatch.Config.fromValues(Some("60"), Some("0"), None).map(_.batchSize),
      Some(RatingBatch.Config.DefaultBatchSize)
    )
    assertEquals(
      RatingBatch.Config.fromValues(Some("60"), None, Some("0")).map(_.ladderTimeoutParkGames),
      Some(RatingBatch.Config.DefaultLadderTimeoutParkGames)
    )
    assertEquals(
      RatingBatch.Config.fromValues(Some("45"), Some("7"), Some("3")),
      Some(RatingBatch.Config(45.seconds, 7, 3, RatingBatch.Config.DefaultStrengthRefreshInterval))
    )

  test("the strength refresh interval parses, defaults when absent or negative, and accepts an explicit zero (#215)"):
    assertEquals(
      RatingBatch.Config.fromValues(Some("60"), None, None, Some("300")).map(_.strengthRefreshInterval),
      Some(300.seconds)
    )
    assertEquals(
      RatingBatch.Config.fromValues(Some("60"), None, None, None).map(_.strengthRefreshInterval),
      Some(RatingBatch.Config.DefaultStrengthRefreshInterval)
    )
    assertEquals(
      RatingBatch.Config.fromValues(Some("60"), None, None, Some("junk")).map(_.strengthRefreshInterval),
      Some(RatingBatch.Config.DefaultStrengthRefreshInterval)
    )
    assertEquals(
      RatingBatch.Config.fromValues(Some("60"), None, None, Some("-1")).map(_.strengthRefreshInterval),
      Some(RatingBatch.Config.DefaultStrengthRefreshInterval),
      "a negative interval is meaningless; unlike zero it cannot be read as a deliberate 'every tick'"
    )
    assertEquals(
      RatingBatch.Config.fromValues(Some("60"), None, None, Some("0")).map(_.strengthRefreshInterval),
      Some(Duration.Zero),
      "zero is the one way back to the pre-#215 cadence and must survive parsing"
    )

  test("planRefresh holds a rebuild back until the interval has passed, without forgetting the games that landed"):
    val cold  = RatingBatch.RefreshState.Initial
    val fresh = RatingBatch.planRefresh(appliedAny = true, cold = true, now = 10.seconds, interval = 15.minutes)(cold)
    assertEquals(fresh, (RatingBatch.RefreshState(Some(10.seconds), pending = false), true))

    val (deferred, rebuildNow) =
      RatingBatch.planRefresh(appliedAny = true, cold = false, now = 70.seconds, interval = 15.minutes)(fresh._1)
    assertEquals(rebuildNow, false, "a game landing a minute after the last rebuild must not trigger another")
    assertEquals(deferred.pending, true, "but the batch must remember that the report is now stale")
    assertEquals(deferred.lastRefreshAt, Some(10.seconds), "a deferred rebuild must not restart the interval")

  test("planRefresh rebuilds a stale report on a later idle tick, so a quiet ladder cannot strand the last games"):
    val stale              = RatingBatch.RefreshState(Some(10.seconds), pending = true)
    val (next, rebuildNow) =
      RatingBatch.planRefresh(appliedAny = false, cold = false, now = 16.minutes, interval = 15.minutes)(stale)
    assertEquals(rebuildNow, true, "the interval has passed and games are waiting — applying nothing this tick is fine")
    assertEquals(next, RatingBatch.RefreshState(Some(16.minutes), pending = false))

  test("planRefresh leaves a clean report alone forever: an elapsed interval alone is not a reason to rebuild"):
    val clean              = RatingBatch.RefreshState(Some(10.seconds), pending = false)
    val (next, rebuildNow) =
      RatingBatch.planRefresh(appliedAny = false, cold = false, now = 3.hours, interval = 15.minutes)(clean)
    assertEquals(rebuildNow, false, "nothing has changed the corpus, so the cached report is still exactly right")
    assertEquals(next, clean)

  test("planRefresh always warms a cold cache, whatever the interval says (#181)"):
    val warm               = RatingBatch.RefreshState(Some(10.seconds), pending = false)
    val (next, rebuildNow) =
      RatingBatch.planRefresh(appliedAny = false, cold = true, now = 11.seconds, interval = 15.minutes)(warm)
    assertEquals(rebuildNow, true, "an empty cache answers /strength with nothing at all — that outranks the interval")
    assertEquals(next, RatingBatch.RefreshState(Some(11.seconds), pending = false))

/** The eligibility matrix for human ratings (#279, ADR-0017) — pure, so every rule is pinned without a database.
  *
  * Rated is player-chosen at game creation now, for any registered opponent — there is deliberately no curation gate to
  * test here any more (that roster never worked: see `applyGame`'s own doc for why). The one rule this matrix still
  * defends is the one the Glicko-2 scale itself cannot: a player's own bot, which colludes for free rating.
  */
class RatingEligibilitySuite extends munit.FunSuite:

  private val userA = "0197f0a0-0000-7000-8000-0000000000a1"
  private val userB = "0197f0a0-0000-7000-8000-0000000000b2"

  private def account(id: String): RatingBatch.Participant =
    RatingBatch.Participant.OfUser(id, UserRating.initial)

  private def bot(name: String, owner: Option[String] = None): RatingBatch.Participant =
    RatingBatch.Participant.OfBot(Principal.Bot("acme", name), BotRating.initial.copy(ownerExternalId = owner))

  test("two accounts are always eligible"):
    assertEquals(RatingBatch.ineligible(account(userA), account(userB)), None)

  test("two bots are always eligible against each other"):
    assertEquals(RatingBatch.ineligible(bot("alice"), bot("bob")), None)

  test("an account against any registered bot is eligible, in either seat — no curation gate any more"):
    assertEquals(RatingBatch.ineligible(account(userA), bot("alice")), None)
    assertEquals(RatingBatch.ineligible(bot("alice"), account(userA)), None)

  test("a player's own bot is never eligible, and the reason names it"):
    val own = bot("mine", owner = Some(Principal.User(userA).externalId))
    val why = RatingBatch.ineligible(account(userA), own)
    assert(why.exists(_.contains("their own bot")), why.toString)
    // ...and from the other seat, or the farm would just swap colours.
    assert(RatingBatch.ineligible(own, account(userA)).isDefined)

  test("someone else's bot stays eligible — ownership only excludes the owner"):
    val theirs = bot("theirs", owner = Some(Principal.User(userB).externalId))
    assertEquals(RatingBatch.ineligible(account(userA), theirs), None)
