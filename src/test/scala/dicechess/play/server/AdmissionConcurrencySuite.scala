package dicechess.play.server

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.store.{BotStore, GameSnapshot, GameStore}

import scala.concurrent.duration.*

/** Deterministic concurrency test suite verifying atomic admission guarantees for Issue #45.
  *
  * Invariant for featured bot (declared capacity 3, reserved showcase seats 1):
  *   - Non-showcase occupancy O_gen <= 2
  *   - Showcase occupancy O_show <= 1
  *   - Total combined occupancy O_tot <= 3
  *   - No borrowing rule: showcase slot is never lent to general traffic
  *   - Rollback on failure, cancellation, or lease expiry is atomic and provable
  */
class AdmissionConcurrencySuite extends munit.CatsEffectSuite:

  private val featuredBot: Principal.Bot     = Principal.Bot("rpi3", "hunter-book")
  private val showcaseConfig: ShowcaseConfig = ShowcaseConfig(
    enabled = true,
    featuredBot = Some(featuredBot),
    reservedSeats = 1
  )

  /** Showcase rooms are refused over a store that does not claim durability (#47). The admission invariants under test
    * are independent of durability, so the harness uses an in-memory store that claims it and keeps nothing.
    */
  private val durableInMemory: GameStore = new GameStore:
    override def durable: Boolean                          = true
    def save(id: GameId, snapshot: GameSnapshot): IO[Unit] = IO.unit
    def loadActive: IO[List[(GameId, GameSnapshot)]]       = IO.pure(Nil)

  private def harness(limit: Int = 3): IO[(BotStore, GameRegistry, AdmissionGuard)] =
    for
      bots     <- BotStore.inMemory
      _        <- bots.register(featuredBot.team, featuredBot.name, "hash-featured")
      _        <- bots.setMaxConcurrentGames(featuredBot.team, featuredBot.name, limit)
      registry <- GameRegistry.create(store = durableInMemory)
      guard    <- AdmissionGuard.create(
        bots,
        showcaseConfig,
        leaseTimeout = 2.seconds,
        registry = Some(registry)
      )
      _ <- registry.attachAdmissionGuard(guard)
    yield (bots, registry, guard)

  test("general-only concurrency: exactly 2 out of N concurrent requests succeed when limit=3, reserved=1"):
    harness().flatMap { (_, registry, guard) =>
      for
        gate <- Deferred[IO, Unit]
        attempts = (1 to 10).toList
        results <- attempts
          .parTraverse { n =>
            gate.get *>
              guard.admitAndCreate(
                registry,
                featuredBot,
                Principal.Bot("filler", s"opp-$n"),
                TimeControl.Unlimited,
                origin = GameOrigin.Catalog
              )
          }
          .start
          .flatMap(fiber => gate.complete(()) *> fiber.joinWithNever)
        succeeded = results.count(_.isRight)
        busy      = results.count {
          case Left(AdmissionGuard.AdmissionError.Busy(_)) => true
          case _                                           => false
        }
        diag <- guard.diagnostics(featuredBot)
      yield
        assertEquals(succeeded, 2, "only 2 general slots are permitted when 1 is reserved for showcase")
        assertEquals(busy, 8, "remaining 8 concurrent attempts must receive Busy")
        assertEquals(diag.map(_.generalOccupancy), Some(2))
        assertEquals(diag.map(_.showcaseOccupancy), Some(0))
        assertEquals(diag.map(_.totalOccupancy), Some(2))
    }

  test("showcase-only concurrency: exactly 1 out of N concurrent showcase requests succeeds"):
    harness().flatMap { (_, registry, guard) =>
      for
        gate <- Deferred[IO, Unit]
        attempts = (1 to 5).toList
        results <- attempts
          .parTraverse { n =>
            gate.get *>
              guard.admitAndCreate(
                registry,
                featuredBot,
                Principal.Guest(s"guest-$n"),
                TimeControl.Unlimited,
                origin = GameOrigin.Showcase
              )
          }
          .start
          .flatMap(fiber => gate.complete(()) *> fiber.joinWithNever)
        succeeded = results.count(_.isRight)
        busy      = results.count {
          case Left(AdmissionGuard.AdmissionError.Busy(_)) => true
          case _                                           => false
        }
        diag <- guard.diagnostics(featuredBot)
      yield
        assertEquals(succeeded, 1, "only 1 showcase slot is permitted")
        assertEquals(busy, 4, "remaining 4 concurrent showcase attempts must receive Busy")
        assertEquals(diag.map(_.showcaseOccupancy), Some(1))
        assertEquals(diag.map(_.generalOccupancy), Some(0))
        assertEquals(diag.map(_.totalOccupancy), Some(1))
    }

  test("mixed concurrency: 10 general + 5 showcase requests preserve O_gen <= 2, O_show <= 1, O_tot <= 3"):
    harness().flatMap { (_, registry, guard) =>
      for
        gate <- Deferred[IO, Unit]
        generalAttempts = (1 to 10).toList.map { n =>
          gate.get *>
            guard.admitAndCreate(
              registry,
              featuredBot,
              Principal.Bot("filler", s"gen-$n"),
              TimeControl.Unlimited,
              origin = GameOrigin.Lobby
            )
        }
        showcaseAttempts = (1 to 5).toList.map { n =>
          gate.get *>
            guard.admitAndCreate(
              registry,
              featuredBot,
              Principal.Guest(s"show-$n"),
              TimeControl.Unlimited,
              origin = GameOrigin.Showcase
            )
        }
        fiber   <- (generalAttempts ++ showcaseAttempts).parSequence.start
        _       <- gate.complete(())
        results <- fiber.joinWithNever
        generalSuccess  = results.take(10).count(_.isRight)
        showcaseSuccess = results.drop(10).count(_.isRight)
        diag <- guard.diagnostics(featuredBot)
      yield
        assertEquals(generalSuccess, 2, "general paths must never exceed their 2 slots (no borrowing showcase slot)")
        assertEquals(showcaseSuccess, 1, "showcase path must never exceed its 1 reserved slot")
        assertEquals(generalSuccess + showcaseSuccess, 3, "total occupancy must equal 3")
        assertEquals(diag.map(_.generalOccupancy), Some(2))
        assertEquals(diag.map(_.showcaseOccupancy), Some(1))
        assertEquals(diag.map(_.totalOccupancy), Some(3))
    }

  test("rollback on room creation failure: failed creation releases reservation immediately"):
    harness().flatMap { (_, _, guard) =>
      for
        // First attempt fails inside the action
        res1 <- guard.admit[Unit](List(featuredBot), AdmissionPurpose.Direct) { _ =>
          IO.pure(Left("simulated storage failure"))
        }
        diagAfterFail <- guard.diagnostics(featuredBot)
        // Subsequent general admissions must still have both slots available
        t1 <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
        t2 <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
        t3 <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
        _  <- t1.toOption.get.commit(GameId("g1"))
        _  <- t2.toOption.get.commit(GameId("g2"))
      yield
        assertEquals(res1, Left(AdmissionGuard.AdmissionError.Failed("simulated storage failure")))
        assertEquals(
          diagAfterFail.map(_.generalOccupancy),
          Some(0),
          "failed action must release provisional reservation"
        )
        assert(t1.isRight, "slot 1 must be free")
        assert(t2.isRight, "slot 2 must be free")
        assert(
          t3.isLeft && t3.left.exists(_.isInstanceOf[AdmissionGuard.AdmissionError.Busy]),
          "slot 3 is busy (reserved for showcase)"
        )
    }

  test("initial snapshot failure releases admission without registering or starting a room"):
    for
      bots <- BotStore.inMemory
      _    <- bots.register(featuredBot.team, featuredBot.name, "hash-featured")
      _    <- bots.setMaxConcurrentGames(featuredBot.team, featuredBot.name, 3)
      failingStore = new GameStore:
        def save(id: GameId, snapshot: GameSnapshot): IO[Unit] =
          IO.raiseError(RuntimeException("initial snapshot failed"))
        def loadActive: IO[List[(GameId, GameSnapshot)]] = IO.pure(Nil)
      registry <- GameRegistry.create(store = failingStore)
      guard    <- AdmissionGuard.create(bots, showcaseConfig, registry = Some(registry))
      _        <- registry.attachAdmissionGuard(guard)
      result   <- guard
        .admitAndCreate(
          registry,
          featuredBot,
          Principal.Bot("filler", "persist-failure"),
          TimeControl.Unlimited,
          GameOrigin.Direct
        )
        .attempt
      rooms <- registry.list
      diag  <- guard.diagnostics(featuredBot)
    yield
      assert(result.left.exists(_.getMessage.contains("initial snapshot failed")))
      assertEquals(rooms, Nil)
      assertEquals(diag.map(_.generalOccupancy), Some(0))

  test("cancellation during registration aborts the room and releases admission"):
    for
      bots       <- BotStore.inMemory
      _          <- bots.register(featuredBot.team, featuredBot.name, "hash-featured")
      _          <- bots.setMaxConcurrentGames(featuredBot.team, featuredBot.name, 3)
      registry   <- GameRegistry.create()
      guard      <- AdmissionGuard.create(bots, showcaseConfig, registry = Some(registry))
      _          <- registry.attachAdmissionGuard(guard)
      registered <- Deferred[IO, Unit]
      hold       <- Deferred[IO, Unit]
      _          <- registry.onRegister((_, _, _) => registered.complete(()).void *> hold.get)
      fiber      <- guard
        .admitAndCreate(
          registry,
          featuredBot,
          Principal.Bot("filler", "cancelled-registration"),
          TimeControl.Unlimited,
          GameOrigin.Direct
        )
        .start
      _     <- registered.get
      _     <- fiber.cancel
      rooms <- registry.list
      diag  <- guard.diagnostics(featuredBot)
    yield
      assertEquals(rooms, Nil)
      assertEquals(diag.map(_.generalOccupancy), Some(0))

  test("lease expiry after room creation aborts and deregisters the orphan"):
    for
      bots     <- BotStore.inMemory
      _        <- bots.register(featuredBot.team, featuredBot.name, "hash-featured")
      _        <- bots.setMaxConcurrentGames(featuredBot.team, featuredBot.name, 3)
      registry <- GameRegistry.create(resolveNicknames = _ => IO.sleep(60.millis).as(Map.empty))
      guard    <- AdmissionGuard.create(
        bots,
        showcaseConfig,
        leaseTimeout = 20.millis,
        registry = Some(registry)
      )
      _      <- registry.attachAdmissionGuard(guard)
      result <- guard.admitAndCreate(
        registry,
        featuredBot,
        Principal.Bot("filler", "expired-creation"),
        TimeControl.Unlimited,
        GameOrigin.Direct
      )
      rooms <- registry.list
      diag  <- guard.diagnostics(featuredBot)
    yield
      assert(
        result.left.exists(_.message == "reservation lease expired before room creation completed"),
        s"expected expired admission failure, got $result"
      )
      assertEquals(rooms, Nil)
      assertEquals(diag.map(_.generalOccupancy), Some(0))

  test("rollback on fiber cancellation: cancelled fiber releases reservation immediately"):
    harness().flatMap { (_, _, guard) =>
      for
        started <- Deferred[IO, Unit]
        proceed <- Deferred[IO, Unit]
        fiber   <- guard
          .admit[Unit](List(featuredBot), AdmissionPurpose.Direct) { ticket =>
            started.complete(()) *> proceed.get *> ticket.commit(GameId("should-not-run")).as(Right((GameId("g0"), ())))
          }
          .start
        _                  <- started.get
        diagDuringInFlight <- guard.diagnostics(featuredBot)
        _                  <- fiber.cancel
        diagAfterCancel    <- guard.diagnostics(featuredBot)
        // Both general slots should be acquirable after cancellation
        t1 <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
        t2 <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
      yield
        assertEquals(diagDuringInFlight.map(_.generalOccupancy), Some(1))
        assertEquals(
          diagAfterCancel.map(_.generalOccupancy),
          Some(0),
          "cancellation must release provisional reservation"
        )
        assert(t1.isRight)
        assert(t2.isRight)
    }

  test("lease timeout expiry: expired provisional tickets do not permanently consume capacity"):
    for
      bots <- BotStore.inMemory
      _    <- bots.register(featuredBot.team, featuredBot.name, "hash-featured")
      _    <- bots.setMaxConcurrentGames(featuredBot.team, featuredBot.name, 3)
      // Guard with very short lease timeout
      shortGuard <- AdmissionGuard.create(bots, showcaseConfig, leaseTimeout = 50.millis)
      // Acquire 2 general tickets but do not commit them
      t1               <- shortGuard.acquire(List(featuredBot), AdmissionPurpose.Direct)
      t2               <- shortGuard.acquire(List(featuredBot), AdmissionPurpose.Direct)
      busyBeforeExpire <- shortGuard.acquire(List(featuredBot), AdmissionPurpose.Direct)
      _                <- IO.sleep(100.millis)
      // After expiry, acquiring general slots must succeed again
      t3 <- shortGuard.acquire(List(featuredBot), AdmissionPurpose.Direct)
      t4 <- shortGuard.acquire(List(featuredBot), AdmissionPurpose.Direct)
    yield
      assert(t1.isRight)
      assert(t2.isRight)
      assert(
        busyBeforeExpire.isLeft && busyBeforeExpire.left.exists(_.isInstanceOf[AdmissionGuard.AdmissionError.Busy])
      )
      assert(t3.isRight, "slot should be free after lease timeout expires")
      assert(t4.isRight, "second slot should be free after lease timeout expires")
