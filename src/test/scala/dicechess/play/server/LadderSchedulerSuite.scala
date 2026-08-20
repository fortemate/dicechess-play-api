package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.engine.search.{BotRegistry, SearchAlgorithm}
import dicechess.play.core.*
import dicechess.play.game.{BotConnection, GameRoom}
import dicechess.play.store.{BotSeatPolicy, BotStore}

import scala.concurrent.duration.*

/** The pairing scheduler (#102): server-chosen games between on-ladder bots, one per tick, bounded by a concurrency
  * cap. `tick` (a single scheduling attempt) is the unit under test throughout — the `.foreverM` `scheduler()` wrapper,
  * started once at boot (same idiom as `Lobby.sweeper`/`Challenges.sweeper`), is not itself exercised here.
  *
  * Tests below use `TimeControl.Unlimited` for the scheduler's own games purely for test speed/simplicity; production's
  * default `LadderScheduler.Config.Default` uses `Fischer`, never `Unlimited`/`PerMove` (see that object's doc
  * comment).
  */
class LadderSchedulerSuite extends munit.CatsEffectSuite:

  private val alice: Principal.Bot = Principal.Bot("acme", "alice")
  private val bob: Principal.Bot   = Principal.Bot("acme", "bob")
  private val carol: Principal.Bot = Principal.Bot("acme", "carol")
  private val dave: Principal.Bot  = Principal.Bot("acme", "dave")

  /** Registration gives a bot the conservative declared capacity — one game at a time (#189) — so a test that wants a
    * bot in several simultaneous games has to say so, exactly as a real bot author would. `openToHumans` puts the bot
    * in the human catalog too, which reserves part of that declaration away from the ladder.
    */
  private def joinLadder(
      botStore: BotStore,
      bots: List[Principal.Bot],
      maxConcurrentGames: Int = BotSeatPolicy.DefaultMaxConcurrentGames,
      openToHumans: Boolean = false
  ): IO[Unit] =
    bots.traverse_(bot =>
      botStore.register(bot.team, bot.name, s"hash-${bot.name}") *>
        botStore.setOnLadder(bot.team, bot.name, true) *>
        botStore.setMaxConcurrentGames(bot.team, bot.name, maxConcurrentGames) *>
        botStore.openToHumans(bot.team, bot.name, None).whenA(openToHumans).void
    )

  private def harness(
      config: LadderScheduler.Config = LadderScheduler.Config.Default
  ): IO[(BotStore, GameRegistry, BotEvents, LadderScheduler)] =
    for
      botStore  <- BotStore.inMemory
      registry  <- GameRegistry.create()
      events    <- BotEvents.create
      scheduler <- LadderScheduler.create(botStore, registry, events, config)
    yield (botStore, registry, events, scheduler)

  /** Drive a room to completion with a bot on each seat. */
  private def playToEnd(room: GameRoom, white: Principal, black: Principal, algorithm: SearchAlgorithm): IO[Unit] =
    val whiteConn = BotConnection(white, Seat.White, algorithm)
    val blackConn = BotConnection(black, Seat.Black, algorithm)
    (whiteConn.run(room).background, blackConn.run(room).background).tupled
      .use(_ => room.start *> room.result)
      .void
      .timeoutTo(20.seconds, IO.raiseError(RuntimeException("game did not finish in time")))

  test("a tick is a no-op when fewer than two bots are on the ladder"):
    harness().flatMap { case (botStore, registry, _, scheduler) =>
      for
        _         <- scheduler.tick
        emptyPool <- registry.list
        _         <- joinLadder(botStore, List(alice))
        _         <- scheduler.tick
        onePool   <- registry.list
      yield
        assertEquals(emptyPool, Nil, "no bots on the ladder at all")
        assertEquals(onePool, Nil, "exactly one bot on the ladder still can't form a pair")
    }

  test("a tick with two on-ladder bots starts one game and pushes gameStart to both"):
    harness(LadderScheduler.Config(interval = 1.hour, maxConcurrentGames = 4, timeControl = TimeControl.Unlimited))
      .flatMap { case (botStore, registry, events, scheduler) =>
        for
          _ <- joinLadder(botStore, List(alice, bob))
          // parTupled, not tupled: both subscriptions must register concurrently, before tick publishes — sequencing
          // them would let alice's blocking collect start before bob's subscription exists, missing her publish.
          result <- (
            events.stream(alice).take(1).compile.toVector,
            events.stream(bob).take(1).compile.toVector
          ).parTupled.background
            .use(waiting => IO.sleep(150.millis) *> scheduler.tick *> waiting.flatMap(_.embedNever))
            .timeoutTo(5.seconds, IO.raiseError(RuntimeException("gameStart not delivered to both bots")))
          (aliceGot, bobGot) = result
          games <- registry.list
        yield
          assertEquals(games.size, 1, "one tick starts exactly one game")
          assertEquals(aliceGot.collect { case e: BotEvent.GameStart => e }.size, 1, s"alice got: $aliceGot")
          assertEquals(bobGot.collect { case e: BotEvent.GameStart => e }.size, 1, s"bob got: $bobGot")
      }

  test("the concurrency cap blocks a new game while one is in flight, and frees up once it ends"):
    val greedy = BotRegistry.getAlgorithm("greedy").get
    harness(LadderScheduler.Config(interval = 1.hour, maxConcurrentGames = 1, timeControl = TimeControl.Unlimited))
      .flatMap { case (botStore, registry, _, scheduler) =>
        for
          _          <- joinLadder(botStore, List(alice, bob, carol, dave))
          _          <- scheduler.tick
          afterFirst <- registry.list
          _ = assertEquals(afterFirst.size, 1, "the first tick must start exactly one game")
          _               <- scheduler.tick // at cap: must be a no-op
          afterSecondTick <- registry.list
          _ = assertEquals(afterSecondTick.size, 1, "a tick at the concurrency cap must not start a second game")
          List((_, room)) = afterFirst
          players <- room.seating
          _       <- playToEnd(room, players(Seat.White), players(Seat.Black), greedy)
          firstIds = afterFirst.map(_._1).toSet
          // Once the game concludes, the cap frees up and a tick starts a new one — watch for a game id that wasn't
          // the original, NOT for the registry's total size: an ended room lingers only until its own eviction fiber
          // fires, independently of this scheduler's own bookkeeping, so the total count can transiently dip below
          // (or never actually reach) old-count + new-count.
          grew <- (scheduler.tick *> registry.list)
            .iterateUntil(_.exists((id, _) => !firstIds.contains(id)))
            .timeoutTo(10.seconds, IO.raiseError(RuntimeException("cap never freed up after the in-flight game ended")))
        yield assert(
          grew.exists((id, _) => !firstIds.contains(id)),
          "a freed-up cap must allow a new game to start"
        )
      }

  test("prefers not to immediately re-pick the same pair when a third bot is on the ladder"):
    val allThree: Set[Principal] = Set(alice, bob, carol)
    harness(LadderScheduler.Config(interval = 1.hour, maxConcurrentGames = 3, timeControl = TimeControl.Unlimited))
      .flatMap { case (botStore, registry, _, scheduler) =>
        for
          // Two concurrent games over three bots means one of them plays both, so the declaration has to allow it.
          _          <- joinLadder(botStore, List(alice, bob, carol), maxConcurrentGames = 2)
          _          <- scheduler.tick
          firstGames <- registry.list
          _ = assertEquals(firstGames.size, 1)
          firstPair <- firstGames.head._2.seating.map(_.values.toSet)
          unpaired = allThree -- firstPair
          _        = assertEquals(unpaired.size, 1, s"expected exactly one bot left unpaired, got $unpaired")
          _        <- scheduler.tick
          allGames <- registry.list
          _        = assertEquals(allGames.size, 2, "the second tick must start a second game (cap allows it)")
          firstIds = firstGames.map(_._1).toSet
          newGames = allGames.filterNot((id, _) => firstIds.contains(id))
          _        = assertEquals(newGames.size, 1)
          secondPair <- newGames.head._2.seating.map(_.values.toSet)
        yield assert(
          unpaired.subsetOf(secondPair),
          s"the bot left out of the first pair ($unpaired) should appear in the second pair ($secondPair) " +
            "rather than an immediate repeat"
        )
      }

  test("anti-repeat is symmetric: no two adjacent ticks ever re-form the identical pair (#117 review)"):
    // With exactly 3 bots, `recent` (one entry per bot) can never have all three pairwise combinations excluded at
    // once — proof: excluding all of {A,B},{A,C},{B,C} needs recent(A)=B (or recent(B)=A), recent(A)=C (or
    // recent(C)=A), and recent(B)=C (or recent(C)=B); recent(A) can't simultaneously be B and C, and whichever
    // pairing set recent(C)=A also sets recent(A)=C, contradicting the first — so at least one combination always
    // survives the filter, making "no adjacent repeat" a hard guarantee here, not just a probability.
    // Cap comfortably above the 6 ticks below: none of these pairs is ever played to completion, so the cap must
    // never bind here — that concurrency behavior has its own dedicated test above.
    harness(LadderScheduler.Config(interval = 1.hour, maxConcurrentGames = 10, timeControl = TimeControl.Unlimited))
      .flatMap { case (botStore, registry, _, scheduler) =>
        def newPair(beforeIds: Set[GameId]): IO[Set[Principal]] =
          registry.list.flatMap { after =>
            after.collectFirst { case (id, room) if !beforeIds.contains(id) => room } match
              case Some(room) => room.seating.map(_.values.toSet)
              case None       => IO.raiseError(RuntimeException("tick did not start a new pair"))
          }

        for
          // Six ticks and nothing ever finishes, so each of the three bots accumulates games: the declared capacity
          // has to clear the ceiling too, or the pool would empty out and later ticks would start nothing.
          _     <- joinLadder(botStore, List(alice, bob, carol), maxConcurrentGames = 10)
          pairs <- (1 to 6).toList.foldLeftM(List.empty[Set[Principal]]) { (acc, _) =>
            for
              before <- registry.list.map(_.map(_._1).toSet)
              _      <- scheduler.tick
              pair   <- newPair(before)
            yield acc :+ pair
          }
        yield
          val repeats = pairs.sliding(2).count(w => w.size == 2 && w.head == w(1))
          assertEquals(repeats, 0, s"a pair repeated on two adjacent ticks: $pairs")
      }

  // ── Declared per-bot capacity (#189) ─────────────────────────────────────────

  test("a bot already at its declared capacity is skipped, and a free pair is paired instead (#189)"):
    // The server-wide cap is deliberately wide open here: whatever bounds the second tick must be the bots' own
    // declarations, not the scheduler's ceiling — that ceiling has its own test above.
    harness(LadderScheduler.Config(interval = 1.hour, maxConcurrentGames = 10, timeControl = TimeControl.Unlimited))
      .flatMap { case (botStore, registry, _, scheduler) =>
        for
          _          <- joinLadder(botStore, List(alice, bob, carol, dave))
          _          <- scheduler.tick
          firstGames <- registry.list
          _ = assertEquals(firstGames.size, 1)
          firstPair <- firstGames.head._2.seating.map(_.values.toSet)
          _         <- scheduler.tick
          allGames  <- registry.list
          _        = assertEquals(allGames.size, 2, "two bots are busy, but the other two are free to be paired")
          firstIds = firstGames.map(_._1).toSet
          secondPair <- allGames.filterNot((id, _) => firstIds.contains(id)).head._2.seating.map(_.values.toSet)
        yield assertEquals(
          firstPair.intersect(secondPair),
          Set.empty[Principal],
          s"a bot declared for one game at a time must not appear in both pairs: $firstPair then $secondPair"
        )
      }

  test("with every on-ladder bot at its declared capacity, further ticks are a no-op (#189)"):
    harness(LadderScheduler.Config(interval = 1.hour, maxConcurrentGames = 10, timeControl = TimeControl.Unlimited))
      .flatMap { case (botStore, registry, _, scheduler) =>
        for
          _     <- joinLadder(botStore, List(alice, bob))
          _     <- scheduler.tick
          _     <- scheduler.tick
          _     <- scheduler.tick
          games <- registry.list
        yield assertEquals(
          games.size,
          1,
          "both bots declared one game at a time, so the ladder has nobody left to pair — even well under its own cap"
        )
      }

  test("an open-to-humans bot keeps a slot for a person: the ladder only spends limit - 1 (#189)"):
    // Same four bots and the same declaration of 2 in both halves; the only difference is the catalog opt-in, so any
    // difference in how many games the ladder starts is the reservation and nothing else.
    def gamesAfterThreeTicks(openToHumans: Boolean): IO[Int] =
      harness(LadderScheduler.Config(interval = 1.hour, maxConcurrentGames = 10, timeControl = TimeControl.Unlimited))
        .flatMap { case (botStore, registry, _, scheduler) =>
          joinLadder(botStore, List(alice, bob, carol, dave), maxConcurrentGames = 2, openToHumans = openToHumans) *>
            List.fill(3)(scheduler.tick).sequence_ *>
            registry.list.map(_.size)
        }

    (gamesAfterThreeTicks(openToHumans = false), gamesAfterThreeTicks(openToHumans = true)).mapN {
      (closed, openToHumans) =>
        assertEquals(closed, 3, "a declaration of 2 that nobody else can claim lets the ladder fill both slots")
        assertEquals(
          openToHumans,
          2,
          "in the catalog, each bot gives the ladder only one of its two slots, so the pool is exhausted a game sooner"
        )
    }

  // ── Config.fromValues: env-var validation (#117 review) ──────────────────────

  test("a non-positive or unparseable interval disables the scheduler regardless of the cap"):
    assertEquals(LadderScheduler.Config.fromValues(Some("0"), None), None)
    assertEquals(LadderScheduler.Config.fromValues(Some("-5"), None), None)
    assertEquals(LadderScheduler.Config.fromValues(Some("not-a-number"), None), None)
    assertEquals(LadderScheduler.Config.fromValues(Some(""), None), None)
    assertEquals(LadderScheduler.Config.fromValues(None, Some("2")), None)

  test("a non-positive or unparseable cap falls back to the default rather than disabling the scheduler"):
    val withZeroCap     = LadderScheduler.Config.fromValues(Some("60"), Some("0"))
    val withNegativeCap = LadderScheduler.Config.fromValues(Some("60"), Some("-3"))
    val withJunkCap     = LadderScheduler.Config.fromValues(Some("60"), Some("nope"))
    val withNoCap       = LadderScheduler.Config.fromValues(Some("60"), None)
    List(withZeroCap, withNegativeCap, withJunkCap, withNoCap).foreach { config =>
      assertEquals(
        config.map(_.maxConcurrentGames),
        Some(LadderScheduler.Config.DefaultMaxConcurrentGames)
      )
    }

  test("valid positive values parse through unchanged"):
    val config = LadderScheduler.Config.fromValues(Some("45"), Some("7"))
    assertEquals(config.map(_.interval), Some(45.seconds))
    assertEquals(config.map(_.maxConcurrentGames), Some(7))
    assertEquals(config.map(_.timeControl), Some(LadderScheduler.Config.DefaultTimeControl))

  /** The pin behind `RatingCategory.Default` (#280). That constant is what an unqualified `/leaderboard` answers on and
    * what `/strength` reports; it is Blitz because that is the ladder's own control, and it is written as a literal
    * rather than derived so a public API's default answer cannot be repointed by changing an operational knob.
    *
    * Which leaves exactly one way for the two to drift apart — moving the ladder — and this is the test that fails when
    * someone does. Failing here is the point: the fix is to re-take the default-category decision deliberately, not to
    * make this assertion pass.
    */
  test("the ladder's control still categorises to RatingCategory.Default — move one, re-decide the other (#280)"):
    assertEquals(
      RatingCategory.of(LadderScheduler.Config.DefaultTimeControl),
      Some(RatingCategory.Default),
      "the leaderboard's default category no longer matches the ladder it was chosen for"
    )
