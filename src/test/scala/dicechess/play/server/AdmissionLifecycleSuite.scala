package dicechess.play.server

import dicechess.play.core.*
import dicechess.play.store.BotStore

/** Lifecycle test suite verifying startup reconciliation, capacity reduction under load, non-featured bots, unbounded
  * participants, and boot configuration validation for Issue #45.
  */
class AdmissionLifecycleSuite extends munit.CatsEffectSuite:

  private val featuredBot: Principal.Bot  = Principal.Bot("rpi3", "hunter-book")
  private val standardBot: Principal.Bot  = Principal.Bot("acme", "standard")
  private val humanGuest: Principal.Guest = Principal.Guest("11111111-1111-1111-1111-111111111111")
  private val staticBot: Principal.Bot    = Principal.Bot("house", "stockfish")

  private val showcaseConfig: ShowcaseConfig = ShowcaseConfig(
    enabled = true,
    featuredBot = Some(featuredBot),
    reservedSeats = 1
  )

  test("startup reconciliation: rebuilds purpose-specific occupancy from resumed rooms before traffic starts"):
    for
      bots  <- BotStore.inMemory
      _     <- bots.register(featuredBot.team, featuredBot.name, "hash-featured")
      _     <- bots.setMaxConcurrentGames(featuredBot.team, featuredBot.name, 3)
      guard <- AdmissionGuard.create(bots, showcaseConfig)
      // Simulate startup recovery of 1 showcase game and 1 general game from snapshot storage
      resumedGames = List(
        (GameId("g-showcase"), List(featuredBot, humanGuest), GameOrigin.Showcase),
        (GameId("g-ladder"), List(featuredBot, Principal.Bot("opp", "one")), GameOrigin.Ladder)
      )
      reconciledCount <- guard.reconcile(resumedGames)
      diag            <- guard.diagnostics(featuredBot)
      // Attempting a second showcase game must fail immediately because 1 showcase seat is occupied
      showcaseAttempt <- guard.acquire(List(featuredBot), AdmissionPurpose.Showcase)
      // Attempting a general game must succeed because 1 general seat is still open (allowance is 2, occupancy is 1)
      genAttempt1 <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
      // Attempting a second general game must fail because general allowance (2) is reached
      genAttempt2 <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
    yield
      assertEquals(reconciledCount, 2)
      assertEquals(diag.map(_.showcaseOccupancy), Some(1))
      assertEquals(diag.map(_.generalOccupancy), Some(1))
      assertEquals(diag.map(_.totalOccupancy), Some(2))
      assert(showcaseAttempt.isLeft && showcaseAttempt.left.exists(_.isInstanceOf[AdmissionGuard.AdmissionError.Busy]))
      assert(genAttempt1.isRight)
      assert(genAttempt2.isLeft && genAttempt2.left.exists(_.isInstanceOf[AdmissionGuard.AdmissionError.Busy]))

  test("capacity reduction under load: never terminates active games; blocks new admissions until load subsides"):
    for
      bots  <- BotStore.inMemory
      _     <- bots.register(featuredBot.team, featuredBot.name, "hash-featured")
      _     <- bots.setMaxConcurrentGames(featuredBot.team, featuredBot.name, 3)
      guard <- AdmissionGuard.create(bots, showcaseConfig)
      // Fill both general slots
      t1 <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
      _  <- t1.toOption.get.commit(GameId("g1"))
      t2 <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
      _  <- t2.toOption.get.commit(GameId("g2"))
      // Capacity is reduced to 2 (meaning 1 general slot and 1 showcase slot)
      _                <- bots.setMaxConcurrentGames(featuredBot.team, featuredBot.name, 2)
      diagOverCapacity <- guard.diagnostics(featuredBot)
      // General attempts must be blocked because occupancy (2) >= new general allowance (1)
      attemptOver <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
      // One active game finishes and deregisters
      _                 <- guard.releaseGame(GameId("g1"))
      diagAfterRelease1 <- guard.diagnostics(featuredBot)
      // Still at general allowance (1/1), so new general attempt is still blocked
      attemptAtLimit <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
      // Second active game finishes and deregisters
      _                 <- guard.releaseGame(GameId("g2"))
      diagAfterRelease2 <- guard.diagnostics(featuredBot)
      // Now occupancy is 0/1, so new general attempt succeeds
      attemptUnder <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
    yield
      assertEquals(diagOverCapacity.map(_.generalAllowance), Some(1))
      assertEquals(diagOverCapacity.map(_.generalOccupancy), Some(2))
      assert(attemptOver.isLeft && attemptOver.left.exists(_.isInstanceOf[AdmissionGuard.AdmissionError.Busy]))
      assertEquals(diagAfterRelease1.map(_.generalOccupancy), Some(1))
      assert(attemptAtLimit.isLeft && attemptAtLimit.left.exists(_.isInstanceOf[AdmissionGuard.AdmissionError.Busy]))
      assertEquals(diagAfterRelease2.map(_.generalOccupancy), Some(0))
      assert(attemptUnder.isRight)

  test("non-featured registered bots: respect declared capacity without showcase partition"):
    for
      bots  <- BotStore.inMemory
      _     <- bots.register(standardBot.team, standardBot.name, "hash-standard")
      _     <- bots.setMaxConcurrentGames(standardBot.team, standardBot.name, 2)
      guard <- AdmissionGuard.create(bots, showcaseConfig)
      // Both slots are general (no showcase reservation)
      diagInitial <- guard.diagnostics(standardBot)
      t1          <- guard.acquire(List(standardBot), AdmissionPurpose.Direct)
      _           <- t1.toOption.get.commit(GameId("s1"))
      t2          <- guard.acquire(List(standardBot), AdmissionPurpose.Direct)
      _           <- t2.toOption.get.commit(GameId("s2"))
      t3          <- guard.acquire(List(standardBot), AdmissionPurpose.Direct)
      // Showcase requests for non-featured bot are refused
      showcaseAttempt <- guard.acquire(List(standardBot), AdmissionPurpose.Showcase)
    yield
      assertEquals(diagInitial.map(_.isFeatured), Some(false))
      assertEquals(diagInitial.map(_.showcaseReservedSeats), Some(0))
      assertEquals(diagInitial.map(_.generalAllowance), Some(2))
      assert(t1.isRight)
      assert(t2.isRight)
      assert(t3.isLeft && t3.left.exists(_.isInstanceOf[AdmissionGuard.AdmissionError.Busy]))
      assert(showcaseAttempt.isLeft && showcaseAttempt.left.exists(_.isInstanceOf[AdmissionGuard.AdmissionError.Busy]))

  test("unbounded participants: human guests and static bots are never refused"):
    for
      bots  <- BotStore.inMemory
      guard <- AdmissionGuard.create(bots, showcaseConfig)
      // Humans and unregistered bots acquire unbounded tickets
      t1           <- guard.acquire(List(humanGuest), AdmissionPurpose.Direct)
      t2           <- guard.acquire(List(staticBot), AdmissionPurpose.Direct)
      t3           <- guard.acquire(List(humanGuest, staticBot), AdmissionPurpose.Direct)
      admitsHuman  <- guard.admits(humanGuest, AdmissionPurpose.Direct)
      admitsStatic <- guard.admits(staticBot, AdmissionPurpose.Direct)
    yield
      assert(t1.isRight)
      assert(t2.isRight)
      assert(t3.isRight)
      assert(admitsHuman)
      assert(admitsStatic)

  test("showcase configuration boot validation: invalid reservation values fail fast"):
    // When enabled, reserved seats must strictly be 1
    val invalidSeats = ShowcaseConfig.fromValues(
      enabledRaw = Some("true"),
      teamRaw = Some("rpi3"),
      nameRaw = Some("hunter-book"),
      reservedSeatsRaw = Some("2")
    )
    val zeroSeats = ShowcaseConfig.fromValues(
      enabledRaw = Some("true"),
      teamRaw = Some("rpi3"),
      nameRaw = Some("hunter-book"),
      reservedSeatsRaw = Some("0")
    )
    val missingTeam = ShowcaseConfig.fromValues(
      enabledRaw = Some("true"),
      teamRaw = None,
      nameRaw = Some("hunter-book"),
      reservedSeatsRaw = Some("1")
    )
    val validConfig = ShowcaseConfig.fromValues(
      enabledRaw = Some("true"),
      teamRaw = Some("rpi3"),
      nameRaw = Some("hunter-book"),
      reservedSeatsRaw = Some("1")
    )
    val disabledConfig = ShowcaseConfig.fromValues(
      enabledRaw = Some("false"),
      teamRaw = None,
      nameRaw = None,
      reservedSeatsRaw = None
    )

    assert(invalidSeats.isLeft, "SHOWCASE_RESERVED_SEATS != 1 must fail configuration validation")
    assert(zeroSeats.isLeft, "SHOWCASE_RESERVED_SEATS = 0 must fail configuration validation")
    assert(missingTeam.isLeft, "SHOWCASE_ENABLED=true without bot team must fail validation")
    assert(validConfig.isRight, "Valid configuration must succeed")
    assertEquals(validConfig.map(_.reservedSeats), Right(1))
    assertEquals(disabledConfig, Right(ShowcaseConfig.Disabled))
