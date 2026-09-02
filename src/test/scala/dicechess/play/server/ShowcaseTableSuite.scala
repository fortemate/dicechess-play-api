package dicechess.play.server

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.server.ShowcaseHarness.*
import dicechess.play.server.ShowcaseTable.{ClaimOutcome, Phase, SpectatingReason, Status, UnavailableReason}
import dicechess.play.store.{ShowcaseClaimOutcome, ShowcaseTableRecord}

import java.util.UUID
import scala.concurrent.duration.*

/** The singleton table's state machine and claim contract (ADR-005 §3, §5, §6, §11; #46) over the in-memory harness:
  * first-claim linearisability, durable idempotency, colour alternation, rollback, completion, restart recovery and the
  * fail-closed reasons. The PostgreSQL half of the same contract is `PgShowcaseStoreSuite`.
  */
class ShowcaseTableSuite extends munit.CatsEffectSuite:

  private val guest1: Principal  = Principal.Guest("11111111-1111-1111-1111-111111111111")
  private val guest2: Principal  = Principal.Guest("22222222-2222-2222-2222-222222222222")
  private val account: Principal = Principal.User("33333333-3333-3333-3333-333333333333")

  private def key: IO[UUID] = IO(UUID.randomUUID())

  private def claimed(outcome: ClaimOutcome): ClaimOutcome.Claimed = outcome match
    case c: ClaimOutcome.Claimed => c
    case other                   => fail(s"expected a winning claim, got $other")

  private def resign(f: ShowcaseFixture, gameId: GameId, humanColor: Side): IO[Unit] =
    f.registry
      .get(gameId)
      .flatMap:
        case None       => IO.raiseError(RuntimeException(s"game ${gameId.value} not in the registry"))
        case Some(room) => room.submit(ShowcaseTable.seatOf(humanColor), GameCommand.Resign)

  test("the table starts unavailable and opens with White as the first human colour once reconciled"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          before <- t.currentPhase
          phase  <- t.reconcile
          view   <- t.view
        yield
          assertEquals(before, Phase.Unavailable(UnavailableReason.Reconciling))
          assertEquals(phase, Phase.Open(Side.White))
          assertEquals(view.status, Status.Open)
          assertEquals(view.nextHumanColor, Some(Side.White))
          assertEquals(view.currentGame, None)
          assertEquals(view.reason, None)
          assertEquals(view.featuredBot, Some(FeaturedBot))
      }
    }

  test("a bot that fails its readiness probe keeps the table unavailable, with an operator signal, until it answers"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _        <- f.ready.set(false)
          phase    <- t.reconcile
          view     <- t.view
          k        <- key
          refused  <- t.claim(guest1, k, "h", None)
          alerts   <- f.alerts.get
          _        <- f.ready.set(true)
          reopened <- t.reconcile
        yield
          assertEquals(phase, Phase.Unavailable(UnavailableReason.BotNotReady))
          assertEquals(view.status, Status.Unavailable)
          assertEquals(view.reason, Some("bot_unavailable"))
          assertEquals(refused, ClaimOutcome.Unavailable(UnavailableReason.BotNotReady))
          assert(alerts.exists(_.contains("readiness probe")), alerts.toString)
          assertEquals(reopened, Phase.Open(Side.White))
      }
    }

  test("without PostgreSQL persistence the table is permanently unavailable and refuses every claim"):
    fixture.flatMap { f =>
      f.table(withStore = false).use { t =>
        for
          phase   <- t.reconcile
          k       <- key
          refused <- t.claim(guest1, k, "h", None)
          again   <- t.reconcile
        yield
          assertEquals(phase, Phase.Unavailable(UnavailableReason.PersistenceMissing))
          assertEquals(refused, ClaimOutcome.Unavailable(UnavailableReason.PersistenceMissing))
          assertEquals(again, Phase.Unavailable(UnavailableReason.PersistenceMissing))
      }
    }

  test("a disabled showcase reads as unavailable(disabled) and names no bot"):
    fixture.flatMap { f =>
      f.table(config = ShowcaseConfig.Disabled).use { t =>
        (t.reconcile, t.view).mapN { (phase, view) =>
          assertEquals(phase, Phase.Unavailable(UnavailableReason.Disabled))
          assertEquals(view.reason, Some("disabled"))
          assertEquals(view.featuredBot, None)
        }
      }
    }

  test(
    "concurrent claims: exactly one wins the reserved seat, every loser spectates that same game, no loser is credentialed"
  ):
    fixture.flatMap { f =>
      f.table().use { t =>
        val visitors = (1 to 8)
          .map(i => Principal.Guest(s"$i$i$i$i$i$i$i$i-$i$i$i$i-$i$i$i$i-$i$i$i$i-$i$i$i$i$i$i$i$i$i$i$i$i"))
          .toList
        for
          _      <- t.reconcile
          gate   <- Deferred[IO, Unit]
          racing <- visitors
            .parTraverse(v => key.flatMap(k => gate.get *> t.claim(v, k, s"hash-${v.externalId}", None)))
            .start
          _       <- gate.complete(())
          results <- racing.joinWithNever
          winners = results.collect { case c: ClaimOutcome.Claimed => c }
          losers  = results.collect { case s: ClaimOutcome.Spectating => s }
          winner  = winners.headOption.getOrElse(fail(s"no winner among $results"))
          room    <- f.registry.get(winner.gameId).map(_.getOrElse(fail("the winner's room is not registered")))
          seating <- room.seating
          diag    <- f.guard.diagnostics(FeaturedBot)
          record  <- f.store.table.get
          claims  <- f.store.claims.get
          snap    <- f.games.snapshots.get.map(_.get(winner.gameId).getOrElse(fail("no creation snapshot")))
          phase   <- t.currentPhase
          view    <- t.view
        yield
          assertEquals(winners.size, 1, results.toString)
          assertEquals(losers.size, visitors.size - 1)
          assert(
            losers.forall(_ == ClaimOutcome.Spectating(SpectatingReason.AlreadyClaimed, Some(winner.gameId), true))
          )
          assertEquals(winner.color, Side.White)
          assertEquals(room.seatFor(winner.seatToken), Some(Seat.White), "the credential opens exactly the human seat")
          assertEquals(seating(Seat.Black), FeaturedBot)
          assertEquals(diag.map(_.showcaseOccupancy), Some(1))
          assertEquals(diag.map(_.generalOccupancy), Some(0))
          assertEquals(record, ShowcaseTableRecord(Side.Black, Some(winner.gameId)))
          assertEquals(claims.size, visitors.size, "every claim, won or lost, has a durable record")
          assertEquals(claims.values.count(_.outcome == ShowcaseClaimOutcome.Claimed), 1)
          assertEquals(snap.effectiveOrigin, GameOrigin.Showcase)
          assertEquals(snap.rated, Some(false))
          assertEquals(snap.timeControl, ShowcaseTable.FixedTimeControl)
          assertEquals(phase, Phase.Live(ShowcaseTable.LiveGame(winner.gameId, room, Side.White)))
          assertEquals(view.status, Status.Live)
          assertEquals(view.currentGame.map(_.id), Some(winner.gameId))
          assertEquals(view.nextHumanColor, Some(Side.Black))
      }
    }

  test("a same-key retry replays the committed outcome, and a reused key with another fingerprint is a conflict"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _         <- t.reconcile
          k1        <- key
          k2        <- key
          first     <- t.claim(guest1, k1, "body-1", None)
          replayed  <- t.claim(guest1, k1, "body-1", None)
          conflict  <- t.claim(guest1, k1, "body-2", None)
          lost      <- t.claim(guest2, k2, "body-3", None)
          lostAgain <- t.claim(guest2, k2, "body-3", None)
          claims    <- f.store.claims.get
          rooms     <- f.registry.list
        yield
          val won = claimed(first)
          assertEquals(replayed, won, "the winner is handed the same credential again while its game is on")
          assertEquals(conflict, ClaimOutcome.Conflict)
          assertEquals(lost, ClaimOutcome.Spectating(SpectatingReason.AlreadyClaimed, Some(won.gameId), true))
          assertEquals(lostAgain, lost)
          assertEquals(claims.size, 2, "retries and conflicts write no further records")
          assertEquals(rooms.size, 1, "retries create no second room")
      }
    }

  test("a signed-in account claims exactly like a guest"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _       <- t.reconcile
          k       <- key
          won     <- t.claim(account, k, "h", None).map(claimed)
          seating <- f.registry.get(won.gameId).flatMap(_.get.seating)
        yield assertEquals(seating(Seat.White), account)
      }
    }

  test("the human colour alternates across committed games, and the table reopens only after the game's durable end"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _        <- t.reconcile
          k1       <- key
          first    <- t.claim(guest1, k1, "h1", None).map(claimed)
          _        <- resign(f, first.gameId, first.color)
          reopened <- await(t.currentPhase)(_ == Phase.Open(Side.Black))
          record   <- f.store.table.get
          snap     <- f.games.snapshots.get.map(_(first.gameId))
          released <- f.guard.diagnostics(FeaturedBot)
          k2       <- key
          second   <- t.claim(guest2, k2, "h2", None).map(claimed)
          seating  <- f.registry.get(second.gameId).flatMap(_.get.seating)
        yield
          assertEquals(first.color, Side.White)
          assertEquals(reopened, Phase.Open(Side.Black))
          assertEquals(record, ShowcaseTableRecord(Side.Black, None))
          assertEquals(snap.status, GameStatus.Ended(GameOver(GameResult.Win(Side.Black), Termination.Resign)))
          assertEquals(released.map(_.showcaseOccupancy), Some(0), "the reserved seat is free before the table opens")
          assertEquals(second.color, Side.Black)
          assertEquals(seating(Seat.Black), guest2)
          assertEquals(seating(Seat.White), FeaturedBot)
      }
    }

  test(
    "a claim whose commit fails is rolled back: the room is aborted, the colour does not move, no credential leaves"
  ):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _       <- t.reconcile
          _       <- f.store.failCommit(true)
          k       <- key
          refused <- t.claim(guest1, k, "h", None)
          phase   <- t.currentPhase
          record  <- f.store.table.get
          claims  <- f.store.claims.get
          _       <- await(f.registry.list)(_.isEmpty)
          aborted <- f.games.snapshots.get.map(_.values.toList)
          alerts  <- f.alerts.get
          _       <- f.store.failCommit(false)
          healed  <- t.reconcile
          k2      <- key
          won     <- t.claim(guest1, k2, "h", None).map(claimed)
        yield
          refused match
            case ClaimOutcome.Unavailable(UnavailableReason.PersistenceFailure(_)) => ()
            case other => fail(s"expected a persistence failure, got $other")
          phase match
            case Phase.Unavailable(UnavailableReason.PersistenceFailure(_)) => ()
            case other => fail(s"expected the table to fail closed, got $other")
          assertEquals(record, ShowcaseTableRecord(Side.White, None), "a failed commit consumes no colour")
          assert(claims.isEmpty, "a failed commit leaves no claim record")
          assertEquals(aborted.size, 1)
          assertEquals(
            aborted.head.status,
            GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted)),
            "the compensated room is a technical abort, auditable in the store"
          )
          assert(alerts.exists(_.contains("PostgreSQL persistence failed")), alerts.toString)
          assertEquals(healed, Phase.Open(Side.White))
          assertEquals(won.color, Side.White, "the colour the failed claim did not consume is offered again")
      }
    }

  test(
    "claims during live and finishing spectate, and a duplicate completion cannot reopen the table twice or move the colour"
  ):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _      <- t.reconcile
          k1     <- key
          k2     <- key
          won    <- t.claim(guest1, k1, "h1", None).map(claimed)
          lost   <- t.claim(guest2, k2, "h2", None)
          _      <- resign(f, won.gameId, won.color)
          _      <- await(t.currentPhase)(_ == Phase.Open(Side.Black))
          _      <- t.onGameCompleted(won.gameId)
          _      <- t.onGameCompleted(won.gameId)
          phase  <- t.currentPhase
          record <- f.store.table.get
          k3     <- key
          retry  <- t.claim(guest1, k1, "h1", None)
          fresh  <- t.claim(guest2, k3, "h3", None).map(claimed)
        yield
          assertEquals(lost, ClaimOutcome.Spectating(SpectatingReason.AlreadyClaimed, Some(won.gameId), true))
          assertEquals(phase, Phase.Open(Side.Black))
          assertEquals(record, ShowcaseTableRecord(Side.Black, None))
          assertEquals(
            retry,
            ClaimOutcome.Spectating(SpectatingReason.GameEnded, Some(won.gameId), false),
            "after the game a winner's retry gets no credential"
          )
          assertEquals(fresh.color, Side.Black)
      }
    }

  test(
    "restart: the live showcase game is resumed as live, the colour is not advanced twice, the winner's retry still works"
  ):
    fixture.flatMap { f =>
      for
        (won, k1) <- f.table().use { t =>
          for
            _   <- t.reconcile
            k   <- key
            won <- t.claim(guest1, k, "h1", None).map(claimed)
          yield (won, k)
        }
        f2 <- f.restart
        _  <- f2.table().use { t2 =>
          for
            before <- t2.currentPhase
            phase  <- t2.reconcile
            view   <- t2.view
            record <- f2.store.table.get
            room   <- f2.registry.get(won.gameId).map(_.getOrElse(fail("the game was not resumed")))
            replay <- t2.claim(guest1, k1, "h1", None)
            k2     <- key
            lost   <- t2.claim(guest2, k2, "h2", None)
            diag   <- f2.guard.diagnostics(FeaturedBot)
          yield
            assertEquals(
              before,
              Phase.Unavailable(UnavailableReason.Reconciling),
              "nothing is served before reconciliation"
            )
            assertEquals(phase, Phase.Live(ShowcaseTable.LiveGame(won.gameId, room, Side.White)))
            assertEquals(view.status, Status.Live)
            assertEquals(view.currentGame.map(_.id), Some(won.gameId))
            assertEquals(view.nextHumanColor, Some(Side.Black))
            assertEquals(record, ShowcaseTableRecord(Side.Black, Some(won.gameId)))
            assertEquals(replay, won, "the durable seat token survives the restart")
            assertEquals(lost, ClaimOutcome.Spectating(SpectatingReason.AlreadyClaimed, Some(won.gameId), true))
            assertEquals(diag.map(_.showcaseOccupancy), Some(1), "the reserved seat is rebuilt from the resumed room")
        }
      yield ()
    }

  test("restart: adopting a live game whose claim transaction never committed advances the colour exactly once"):
    fixture.flatMap { f =>
      for
        won <- f.table().use(t => t.reconcile *> key.flatMap(k => t.claim(guest1, k, "h", None).map(claimed)))
        // The crash happened between the room's creation snapshot and the claim transaction: the row still says White.
        _  <- f.store.table.set(ShowcaseTableRecord(Side.White, None))
        f2 <- f.restart
        _  <- f2.table().use { t2 =>
          for
            phase  <- t2.reconcile
            record <- f2.store.table.get
            again  <- t2.reconcile
            same   <- f2.store.table.get
          yield
            assert(phase.isInstanceOf[Phase.Live], phase.toString)
            assertEquals(record, ShowcaseTableRecord(Side.Black, Some(won.gameId)))
            assertEquals(again, phase)
            assertEquals(same, record, "a second reconciliation does not advance the colour again")
        }
      yield ()
    }

  test("a stale current-game pointer left by a crash after the terminal commit is cleared and the table opens"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _ <- f.store.table.set(ShowcaseTableRecord(Side.Black, Some(GameId("00000000-0000-0000-0000-00000000dead"))))
          phase  <- t.reconcile
          record <- f.store.table.get
          alerts <- f.alerts.get
        yield
          assertEquals(phase, Phase.Open(Side.Black))
          assertEquals(record, ShowcaseTableRecord(Side.Black, None))
          assert(alerts.exists(_.contains("stale current game")), alerts.toString)
      }
    }

  test("two active showcase games in the store fail closed as unavailable with an operator signal"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _ <- f.games.snapshots.update(
            _ + (GameId("aaaaaaaa-0000-0000-0000-000000000001") -> orphanShowcaseSnapshot(guest1))
              + (GameId("aaaaaaaa-0000-0000-0000-000000000002") -> orphanShowcaseSnapshot(guest2))
          )
          phase   <- t.reconcile
          view    <- t.view
          k       <- key
          refused <- t.claim(guest1, k, "h", None)
          alerts  <- f.alerts.get
        yield
          assertEquals(phase, Phase.Unavailable(UnavailableReason.DuplicateActiveGames(2)))
          assertEquals(view.reason, Some("maintenance"), "the public reason never says how many or which")
          assertEquals(refused, ClaimOutcome.Unavailable(UnavailableReason.DuplicateActiveGames(2)))
          assert(alerts.exists(_.contains("2 active showcase games")), alerts.toString)
      }
    }

  test("an active showcase game the registry did not resume is irreconcilable and fails closed"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _ <- f.games.snapshots.update(
            _ + (GameId("aaaaaaaa-0000-0000-0000-000000000003") -> orphanShowcaseSnapshot(guest1))
          )
          phase  <- t.reconcile
          alerts <- f.alerts.get
        yield
          phase match
            case Phase.Unavailable(UnavailableReason.Irreconcilable(detail)) =>
              assert(detail.contains("not in the registry"), detail)
            case other => fail(s"expected irreconcilable, got $other")
          assert(alerts.exists(_.contains("irreconcilable")), alerts.toString)
      }
    }

  test("a stalled required write reads as unavailable and the table is live again once the store heals"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _    <- t.reconcile
          k    <- key
          won  <- t.claim(guest1, k, "h", None).map(claimed)
          room <- f.registry.get(won.gameId).map(_.get)
          _    <- f.games.failWrites(true)
          // A seed is the one write a seat can trigger before the dice roll; under the required mode it halts the room.
          _         <- room.submit(Seat.White, GameCommand.SubmitSeed("0123456789abcdef0123456789abcdef"))
          stalled   <- await(t.view)(_.status == Status.Unavailable)
          _         <- f.games.failWrites(false)
          recovered <- await(t.view)(_.status == Status.Live)
        yield
          assertEquals(stalled.reason, Some("maintenance"))
          assertEquals(stalled.currentGame, None, "a table that cannot move is not offered for watching either")
          assertEquals(recovered.currentGame.map(_.id), Some(won.gameId))
      }
    }

  test("a winner that never connects forfeits after the claim grace and the table reopens with the alternated colour"):
    fixture.flatMap { f =>
      f.table(claimGrace = 300.millis).use { t =>
        for
          _     <- t.reconcile
          k     <- key
          won   <- t.claim(guest1, k, "h", None).map(claimed)
          phase <- await(t.currentPhase)(_ == Phase.Open(Side.Black))
          snap  <- f.games.snapshots.get.map(_(won.gameId))
        yield
          assertEquals(phase, Phase.Open(Side.Black))
          assertEquals(snap.status, GameStatus.Ended(GameOver(GameResult.Win(Side.Black), Termination.Resign)))
      }
    }

  test("a winner that connects within the grace keeps its seat"):
    fixture.flatMap { f =>
      f.table(claimGrace = 300.millis).use { t =>
        for
          _     <- t.reconcile
          k     <- key
          won   <- t.claim(guest1, k, "h", None).map(claimed)
          room  <- f.registry.get(won.gameId).map(_.get)
          ended <- room.connection(Seat.White).use(_ => IO.sleep(700.millis) *> room.hasEnded)
          phase <- t.currentPhase
        yield
          assert(!ended)
          assert(phase.isInstanceOf[Phase.Live], phase.toString)
      }
    }

  test("the winner's client entropy is folded into the room as its dice seed"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _      <- t.reconcile
          k      <- key
          won    <- t.claim(guest1, k, "h", Some("fedcba9876543210fedcba9876543210")).map(claimed)
          seeded <- await(f.games.snapshots.get.map(_(won.gameId)))(_.clientSeeds.contains(Seat.White))
        yield assertEquals(seeded.clientSeeds.get(Seat.White), Some("fedcba9876543210fedcba9876543210"))
      }
    }
