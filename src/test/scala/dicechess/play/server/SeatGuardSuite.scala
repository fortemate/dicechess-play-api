package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{Principal, TimeControl}
import dicechess.play.store.{BotSeatPolicy, BotStore}

/** Declared per-bot capacity (#189): the policy arithmetic, and the gate that turns it into a yes/no at seating.
  *
  * Games here are created and left running — nothing plays them out. That is deliberate: the subject is the *count* of
  * live rooms, and driving real games to completion would add engine time and dice-dependent flakiness to a test about
  * arithmetic. It also means `TimeControl.Unlimited` is safe here, unlike in tests that wait on a game to progress.
  */
class SeatGuardSuite extends munit.CatsEffectSuite:

  private val alice: Principal.Bot = Principal.Bot("acme", "alice")
  private val bob: Principal.Bot   = Principal.Bot("acme", "bob")
  private val house: Principal.Bot = Principal.Bot("house", "greedy")
  private val guest: Principal     = Principal.Guest("11111111-1111-1111-1111-111111111111")

  private def policy(limit: Int, openToHumans: Boolean): BotSeatPolicy =
    BotSeatPolicy(alice, limit, openToHumans)

  private def harness: IO[(BotStore, GameRegistry, SeatGuard)] =
    (BotStore.inMemory, GameRegistry.create()).mapN((bots, registry) => (bots, registry, SeatGuard(bots, registry)))

  private def register(bots: BotStore, bot: Principal.Bot, limit: Int, openToHumans: Boolean = false): IO[Unit] =
    bots.register(bot.team, bot.name, s"hash-${bot.team}-${bot.name}") *>
      bots.setMaxConcurrentGames(bot.team, bot.name, limit) *>
      bots.openToHumans(bot.team, bot.name, None).whenA(openToHumans).void

  /** Seat `bot` in `count` live games, each against a throwaway opponent that has no declared capacity of its own. */
  private def seat(registry: GameRegistry, bot: Principal.Bot, count: Int): IO[Unit] =
    (1 to count).toList.traverse_ { n =>
      registry
        .create(bot, Principal.Bot("filler", s"opponent-$n"), TimeControl.Unlimited)
        .flatMap:
          case Left(error) => IO.raiseError(RuntimeException(s"could not seat a game: $error"))
          case Right(_)    => IO.unit
    }

  test("the ladder's share of a declaration reserves a slot for a person, but never the last one"):
    assertEquals(policy(1, openToHumans = false).ladderAllowance, 1)
    assertEquals(policy(3, openToHumans = false).ladderAllowance, 3)
    assertEquals(policy(3, openToHumans = true).ladderAllowance, 2, "one slot is held back for a human")
    assertEquals(policy(2, openToHumans = true).ladderAllowance, 1)
    assertEquals(
      policy(1, openToHumans = true).ladderAllowance,
      1,
      "at a declaration of 1 there is nothing to reserve: the ladder may take the only slot and a human is told the " +
        "bot is busy, rather than the bot never being rated again"
    )

  test("only a value the bots table would accept is declarable"):
    assert(!BotSeatPolicy.isDeclarable(0), "zero would wedge a bot out of every game")
    assert(!BotSeatPolicy.isDeclarable(-1))
    assert(BotSeatPolicy.isDeclarable(BotSeatPolicy.DefaultMaxConcurrentGames))
    assert(BotSeatPolicy.isDeclarable(BotSeatPolicy.MaxDeclarableConcurrentGames))
    assert(!BotSeatPolicy.isDeclarable(BotSeatPolicy.MaxDeclarableConcurrentGames + 1))

  test("an identity with no registered row is unbounded — a static or anonymous bot cannot declare anything"):
    harness.flatMap { (_, registry, guard) =>
      for
        _       <- seat(registry, house, 3)
        admits  <- guard.admits(house, SeatGuard.Purpose.Direct)
        ladder  <- guard.admits(house, SeatGuard.Purpose.Ladder)
        report  <- guard.report(house)
        atGuest <- guard.admits(guest, SeatGuard.Purpose.Direct)
      yield
        assert(admits, "the house bot must keep facing every quickstart visitor at once")
        assert(ladder)
        assertEquals(report, None, "no row means nothing to report")
        assert(atGuest, "a human is not bounded by a bot's capacity contract")
    }

  test("a registered bot is admitted below its declaration and refused once it is spent"):
    harness.flatMap { (bots, registry, guard) =>
      for
        _       <- register(bots, alice, limit = 2)
        free    <- guard.admits(alice, SeatGuard.Purpose.Direct)
        _       <- seat(registry, alice, 1)
        partial <- guard.admits(alice, SeatGuard.Purpose.Direct)
        _       <- seat(registry, alice, 1)
        spent   <- guard.admits(alice, SeatGuard.Purpose.Direct)
      yield
        assert(free, "a bot with no games is below any declaration")
        assert(partial, "one of two declared slots is still free")
        assert(!spent, "a bot at its declaration must not be seated again")
    }

  test("an open-to-humans bot runs out of ladder allowance before it runs out of capacity"):
    harness.flatMap { (bots, registry, guard) =>
      for
        _      <- register(bots, alice, limit = 2, openToHumans = true)
        _      <- seat(registry, alice, 1)
        ladder <- guard.admits(alice, SeatGuard.Purpose.Ladder)
        direct <- guard.admits(alice, SeatGuard.Purpose.Direct)
      yield
        assert(!ladder, "the scheduler may not take the slot being held for a person")
        assert(direct, "that same slot is exactly what a human — or a challenger — may still take")
    }

  test("both sides are checked: a free bot cannot be seated against a full one"):
    harness.flatMap { (bots, registry, guard) =>
      for
        _    <- register(bots, alice, limit = 1)
        _    <- register(bots, bob, limit = 1)
        _    <- seat(registry, bob, 1)
        both <- guard.admitsBoth(alice, bob, SeatGuard.Purpose.Direct)
        solo <- guard.admits(alice, SeatGuard.Purpose.Direct)
      yield
        assert(solo, "alice herself is free")
        assert(!both, "but the table is not, because bob is full")
    }

  test("availableForLadder drops the candidates that are already at their ladder allowance"):
    harness.flatMap { (bots, registry, guard) =>
      for
        _         <- register(bots, alice, limit = 1)
        _         <- register(bots, bob, limit = 2)
        _         <- seat(registry, alice, 1)
        _         <- seat(registry, bob, 1)
        pool      <- bots.onLadderCandidates
        available <- guard.availableForLadder(List(BotSeatPolicy(alice, 1, false), BotSeatPolicy(bob, 2, false)))
      yield
        assertEquals(pool, Nil, "neither bot joined the ladder, so the store's own pool is empty")
        assertEquals(available, List(bob), "alice is spent at 1; bob still has one of two declared slots")
    }

  test("the report tells an author the declaration, the ladder's share of it, and what is in use right now"):
    harness.flatMap { (bots, registry, guard) =>
      for
        _      <- register(bots, alice, limit = 3, openToHumans = true)
        _      <- seat(registry, alice, 2)
        report <- guard.report(alice)
      yield
        assertEquals(report.map(_.policy.maxConcurrentGames), Some(3))
        assertEquals(report.map(_.policy.ladderAllowance), Some(2))
        assertEquals(report.map(_.activeGames), Some(2))
    }
