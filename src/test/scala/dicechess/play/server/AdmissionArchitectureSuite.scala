package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.*
import dicechess.play.store.{BotCatalogListing, BotCatalogStore, BotStore}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.{Method, Request, Status, Uri}

import scala.concurrent.duration.*

/** Architecture test suite preventing silent bypass of the central [[AdmissionGuard]] boundary.
  *
  * Verifies that all 6 entry paths:
  *   1. LadderScheduler (ladder auto-pairing)
  *   2. CatalogRoutes (POST /lobby/play-bot)
  *   3. Lobby (POST /lobby/accept)
  *   4. Challenges (POST /bot/challenge/{id}/accept)
  *   5. Showcase (table claim with showcase origin)
  *   6. Direct GameRegistry creation
  * pass through or are tracked by [[AdmissionGuard]], and correctly refuse traffic when capacity is saturated.
  */
class AdmissionArchitectureSuite extends munit.CatsEffectSuite:

  private val featuredBot: Principal.Bot  = Principal.Bot("rpi3", "hunter-book")
  private val opponentBot: Principal.Bot  = Principal.Bot("other", "challenger")
  private val humanGuest: Principal.Guest = Principal.Guest("11111111-1111-1111-1111-111111111111")

  private val showcaseConfig: ShowcaseConfig = ShowcaseConfig(
    enabled = true,
    featuredBot = Some(featuredBot),
    reservedSeats = 1
  )

  private def harness(limit: Int = 3): IO[(BotStore, GameRegistry, AdmissionGuard, SeatGuard)] =
    for
      bots     <- BotStore.inMemory
      _        <- bots.register(featuredBot.team, featuredBot.name, "hash-featured")
      _        <- bots.setMaxConcurrentGames(featuredBot.team, featuredBot.name, limit)
      _        <- bots.openToHumans(featuredBot.team, featuredBot.name, None)
      guard    <- AdmissionGuard.create(bots, showcaseConfig)
      registry <- GameRegistry.create()
      _        <- registry.attachAdmissionGuard(guard)
      seatGuard = SeatGuard(guard.withRegistry(registry))
    yield (bots, registry, guard, seatGuard)

  test("Path 1 - LadderScheduler: routes through AdmissionGuard and is rejected when general capacity is reached"):
    harness().flatMap { (bots, registry, guard, seatGuard) =>
      for
        // Consume both general seats
        t1        <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
        _         <- t1.toOption.get.commit(GameId("g1"))
        t2        <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
        _         <- t2.toOption.get.commit(GameId("g2"))
        events    <- BotEvents.create
        scheduler <- LadderScheduler.create(
          bots,
          registry,
          events,
          config = LadderScheduler.Config(1.second, 10, TimeControl.Unlimited),
          guard = Some(seatGuard)
        )
        // Ladder scheduler startPair must fail to admit
        _          <- scheduler.startPair(featuredBot, opponentBot)
        roomsAfter <- registry.activeGamesFor(featuredBot)
      yield assertEquals(roomsAfter, 0, "Ladder scheduler must not bypass AdmissionGuard when general seats are full")
    }

  test("Path 2 - CatalogRoutes: routes through AdmissionGuard and returns 409 Conflict when busy"):
    harness().flatMap { (bots, registry, guard, seatGuard) =>
      val listing = BotCatalogListing(
        team = featuredBot.team,
        name = featuredBot.name,
        rating = 1800.0,
        rd = 60.0,
        description = None,
        maxConcurrentGames = 3
      )
      val catalogStore = new BotCatalogStore:
        def catalogBots: IO[List[BotCatalogListing]] = IO.pure(List(listing))

      for
        // Consume both general seats
        t1           <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
        _            <- t1.toOption.get.commit(GameId("g1"))
        t2           <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
        _            <- t2.toOption.get.commit(GameId("g2"))
        wakeLimit    <- AnonMintLimiter.create()
        playBotLimit <- AnonMintLimiter.create()
        routes = CatalogRoutes(
          catalogStore,
          bots,
          webhooks = None,
          registry,
          wakeLimit,
          playBotLimit,
          session = None,
          guard = Some(seatGuard)
        )
        req = Request[IO](Method.POST, Uri.unsafeFromString("/lobby/play-bot"))
          .withEntity(
            PlayBot(
              guestId = Some("11111111-1111-1111-1111-111111111111"),
              team = featuredBot.team,
              name = featuredBot.name,
              timeControl = TimeControl.Fischer(300, 3)
            )
          )
        resp <- routes.orNotFound.run(req)
      yield assertEquals(
        resp.status,
        Status.Conflict,
        "Catalog play-bot must return 409 Conflict when bot is at capacity"
      )
    }

  test("Path 3 - Lobby: routes through AdmissionGuard and returns Left(Busy) without consuming seek"):
    harness().flatMap { (_, registry, guard, seatGuard) =>
      for
        lobby <- Lobby.create(
          registry,
          admitBoth = (a, b) => seatGuard.admitsBoth(a, b, SeatGuard.Purpose.Direct),
          admissionGuard = Some(guard)
        )
        // Featured bot posts an open seek
        posted <- lobby.create(
          featuredBot,
          TimeControl.Unlimited,
          rated = false
        )
        seekId = posted.toOption.get._1.id
        // Spend both general seats
        t1 <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
        _  <- t1.toOption.get.commit(GameId("g1"))
        t2 <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
        _  <- t2.toOption.get.commit(GameId("g2"))
        // Accepter attempts to accept
        accepted <- lobby.accept(seekId, humanGuest)
        // Seek must still remain open in lobby
        openSeeks <- lobby.list
      yield
        assertEquals(accepted, Left(Lobby.Rejected.Busy), "Lobby.accept must return Busy when bot is at capacity")
        assertEquals(openSeeks.map(_.id), List(seekId), "Seek must remain standing when rejected as Busy")
    }

  test("Path 4 - Challenges: routes through AdmissionGuard and returns Left(Busy) without consuming challenge"):
    harness().flatMap { (_, registry, guard, seatGuard) =>
      for
        events     <- BotEvents.create
        challenges <- Challenges.create(
          events,
          registry,
          admitBoth = (a, b) => seatGuard.admitsBoth(a, b, SeatGuard.Purpose.Direct),
          admissionGuard = Some(guard)
        )
        created <- challenges.create(opponentBot, featuredBot, TimeControl.Unlimited, rated = false)
        challengeId = created.toOption.get.challenge.id
        // Spend both general seats
        t1 <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
        _  <- t1.toOption.get.commit(GameId("g1"))
        t2 <- guard.acquire(List(featuredBot), AdmissionPurpose.Direct)
        _  <- t2.toOption.get.commit(GameId("g2"))
        // Featured bot accepts
        accepted <- challenges.accept(featuredBot, challengeId)
        pending  <- challenges.listFor(featuredBot)
      yield
        assertEquals(
          accepted,
          Left(Challenges.Rejected.Busy),
          "Challenges.accept must return Busy when bot is at capacity"
        )
        assertEquals(pending._1.map(_.id), List(challengeId), "Challenge must remain pending when rejected as Busy")
    }

  test("Path 5 - Showcase: table claim routes through AdmissionGuard and enforces singleton showcase occupancy"):
    harness().flatMap { (_, registry, guard, _) =>
      for
        // First showcase admission succeeds
        s1 <- guard.admitAndCreate(
          registry,
          featuredBot,
          humanGuest,
          TimeControl.Unlimited,
          origin = GameOrigin.Showcase
        )
        // Second showcase admission fails
        s2 <- guard.admitAndCreate(
          registry,
          featuredBot,
          Principal.Guest("22222222-2222-2222-2222-222222222222"),
          TimeControl.Unlimited,
          origin = GameOrigin.Showcase
        )
      yield
        assert(s1.isRight, "First showcase claim must succeed")
        assert(
          s2.isLeft && s2.left.exists(_.isInstanceOf[AdmissionGuard.AdmissionError.Busy]),
          "Second showcase claim must be Busy"
        )
    }

  test("Path 6 - Direct Registry creation: automatically tracked by AdmissionGuard lifecycle hooks"):
    harness().flatMap { (_, registry, guard, _) =>
      for
        // Create room directly on registry
        c1 <- registry.create(featuredBot, opponentBot, TimeControl.Unlimited, origin = GameOrigin.Direct)
        gameId = c1.toOption.get._1
        diag1 <- guard.diagnostics(featuredBot)
        // Deregister room
        _     <- registry.deregister(gameId, List(featuredBot, opponentBot))
        diag2 <- guard.diagnostics(featuredBot)
      yield
        assertEquals(diag1.map(_.generalOccupancy), Some(1), "Direct room creation must be tracked by AdmissionGuard")
        assertEquals(diag2.map(_.generalOccupancy), Some(0), "Deregistration must release occupancy in AdmissionGuard")
    }

  test("Lobby: successful accept commits admission in AdmissionGuard"):
    harness().flatMap { (_, registry, guard, seatGuard) =>
      for
        lobby <- Lobby.create(
          registry,
          admitBoth = (a, b) => seatGuard.admitsBoth(a, b, SeatGuard.Purpose.Direct),
          admissionGuard = Some(guard)
        )
        posted <- lobby.create(featuredBot, TimeControl.Unlimited, rated = false)
        seekId = posted.toOption.get._1.id
        accepted <- lobby.accept(seekId, humanGuest)
        diag     <- guard.diagnostics(featuredBot)
      yield
        assert(accepted.isRight, "Lobby accept should succeed")
        assertEquals(diag.map(_.generalOccupancy), Some(1))
    }

  test("Challenges: successful accept commits admission in AdmissionGuard"):
    harness().flatMap { (_, registry, guard, seatGuard) =>
      for
        events     <- BotEvents.create
        challenges <- Challenges.create(
          events,
          registry,
          admitBoth = (a, b) => seatGuard.admitsBoth(a, b, SeatGuard.Purpose.Direct),
          admissionGuard = Some(guard)
        )
        created <- challenges.create(opponentBot, featuredBot, TimeControl.Unlimited, rated = false)
        challengeId = created.toOption.get.challenge.id
        accepted     <- challenges.accept(featuredBot, challengeId)
        diagFeatured <- guard.diagnostics(featuredBot)
      yield
        assert(accepted.isRight, "Challenge accept should succeed")
        assertEquals(diagFeatured.map(_.generalOccupancy), Some(1))
    }
