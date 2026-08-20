package dicechess.play.store

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.Main
import dicechess.play.core.*
import dicechess.play.game.{EngineOps, GameRoom}
import dicechess.play.rating.{Glicko, Glicko2}
import dicechess.play.server.GameRegistry
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.*
import doobie.util.ExecutionContexts
import doobie.util.fragment.Fragment
import munit.CatsEffectSuite
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.testcontainers.utility.DockerImageName

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** Persistence against a real PostgreSQL (testcontainers): the store round-trip, and the property the whole feature
  * exists for — a live game, its fixed roll included, survives a "crash" (a brand-new registry over the same store).
  */
class PgGameStoreSuite extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def store(pg: PostgreSQLContainer) =
    PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))

  private def snapshotFixture(status: GameStatus): GameSnapshot =
    GameSnapshot(
      version = 3L,
      dfen = EngineOps.InitialDfen,
      players = Map(Seat.White -> Principal.Guest("w-1"), Seat.Black -> Principal.Bot("house", "greedy")),
      seatTokens = Map(Seat.White -> "tok-w", Seat.Black -> "tok-b"),
      serverSeed = "ab12cd34",
      clientSeeds = Map(Seat.White -> "white-seed-0123456789ab"),
      started = true,
      ply = 2L,
      pending = true,
      status = status,
      timeControl = TimeControl.Fischer(300, 3),
      remainingMs = Map(Seat.White -> 295000L, Seat.Black -> 300000L),
      lastRoll = List(2, 3, 6),
      turns = Vector(TurnRecord(1L, "w", List(1, 1, 4), List("e2e4"), "fen-after"))
    )

  /** An ended snapshot with a distinct pair of players — `game_results` (#98) tests need their own participant
    * namespace, since this suite shares one database across every test in the file (`TestContainerForAll`, no per-test
    * reset) and `finishedRatedSince` in particular scans every row, not just a chosen participant's.
    */
  private def endedResultFixture(
      white: Principal,
      black: Principal,
      rated: Boolean = false,
      ladder: Boolean = false,
      result: GameResult = GameResult.Win(Side.White),
      termination: Termination = Termination.Resign
  ): GameSnapshot =
    snapshotFixture(GameStatus.Ended(GameOver(result, termination)))
      .copy(players = Map(Seat.White -> white, Seat.Black -> black), rated = Some(rated), ladder = Some(ladder))

  test("a snapshot round-trips through jsonb, and upserts replace by game id"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          id  <- GameId.random
          _   <- db.save(id, snapshotFixture(GameStatus.Active))
          _   <- db.save(id, snapshotFixture(GameStatus.Active).copy(version = 4L, ply = 3L))
          all <- db.loadActive
        yield
          val (loadedId, snap) = all.find(_._1.value == id.value).getOrElse(fail("saved game not loaded"))
          assertEquals(loadedId.value, id.value)
          assertEquals(snap, snapshotFixture(GameStatus.Active).copy(version = 4L, ply = 3L))
      }
    }

  test("bot identities round-trip: register once, authenticate by hash, rotate atomically"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          claimed  <- db.register("dragons", "smaug", "hash-1")
          dupe     <- db.register("dragons", "smaug", "hash-other")
          found    <- db.authenticate("hash-1")
          unknown  <- db.authenticate("hash-none")
          rotated  <- db.rotate("dragons", "smaug", "hash-2")
          oldDead  <- db.authenticate("hash-1")
          newAlive <- db.authenticate("hash-2")
          ghost    <- db.rotate("dragons", "nobody", "hash-3")
        yield
          assert(claimed, "a fresh identity must register")
          assert(!dupe, "the primary key must make the second claim lose")
          assertEquals(found, Some(Principal.Bot("dragons", "smaug")): Option[Principal.Bot])
          assertEquals(unknown, None)
          assert(rotated, "rotation of a registered identity must succeed")
          assertEquals(oldDead, None)
          assertEquals(newAlive, Some(Principal.Bot("dragons", "smaug")): Option[Principal.Bot])
          assert(!ghost, "rotating an unregistered identity must report false")
      }
    }

  test("bot rating state: fresh registration is provisional, on_ladder toggles atomically, unregistered is None"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          _       <- db.register("dragons", "smaug", "hash-1")
          initial <- db.ratingOf("dragons", "smaug")
          joined  <- db.setOnLadder("dragons", "smaug", true)
          reread  <- db.ratingOf("dragons", "smaug")
          left    <- db.setOnLadder("dragons", "smaug", false)
          ghost   <- db.setOnLadder("dragons", "nobody", true)
          unknown <- db.ratingOf("dragons", "nobody")
        yield
          assertEquals(initial, Some(BotRating.initial))
          assertEquals(joined, Some(BotRating.initial.copy(onLadder = true)))
          assertEquals(reread, joined, "the RETURNING result must match a fresh read, not just the pre-update state")
          assertEquals(left, Some(BotRating.initial))
          assertEquals(ghost, None, "toggling an unregistered identity must report None")
          assertEquals(unknown, None)
      }
    }

  test(
    "onLadderCandidates lists only registered bots currently opted in, each with its declared capacity (#102, #189)"
  ):
    withContainers { pg =>
      store(pg).use { db =>
        // A dedicated team/hash namespace: this suite shares one database across all tests (TestContainerForAll,
        // no per-test reset), so a name or token hash reused from another test in this file would collide on the
        // token_hash unique constraint — and a plain equality assertion on the candidate list would be fragile
        // against whatever else in the file happens to be on_ladder. Both are avoided here.
        for
          _        <- db.register("ladder-suite", "on-bot", "hash-ladder-on")
          _        <- db.register("ladder-suite", "off-bot", "hash-ladder-off")
          _        <- db.setOnLadder("ladder-suite", "on-bot", true)
          _        <- db.setMaxConcurrentGames("ladder-suite", "on-bot", 3)
          onLadder <- db.onLadderCandidates
        yield
          val bots = onLadder.map(_.bot)
          assert(bots.contains(Principal.Bot("ladder-suite", "on-bot")), s"expected on-bot in $bots")
          assert(!bots.contains(Principal.Bot("ladder-suite", "off-bot")), s"expected off-bot absent from $bots")
          assertEquals(
            onLadder.find(_.bot == Principal.Bot("ladder-suite", "on-bot")).map(_.maxConcurrentGames),
            Some(3),
            "the candidate pool must carry each bot's declared capacity, not a default"
          )
      }
    }

  test("declared capacity: registration defaults to 1, a declaration round-trips, unregistered -> None (#189)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          _       <- db.register("capacity-suite", "bot", "hash-capacity")
          initial <- db.seatPolicyOf("capacity-suite", "bot")
          raised  <- db.setMaxConcurrentGames("capacity-suite", "bot", 4)
          reread  <- db.seatPolicyOf("capacity-suite", "bot")
          // Opening to humans must shape the ladder's share of the SAME declaration, not the declaration itself.
          _       <- db.openToHumans("capacity-suite", "bot", None)
          opened  <- db.seatPolicyOf("capacity-suite", "bot")
          ghost   <- db.setMaxConcurrentGames("capacity-suite", "nobody", 2)
          unknown <- db.seatPolicyOf("capacity-suite", "nobody")
        yield
          assertEquals(initial.map(_.maxConcurrentGames), Some(BotSeatPolicy.DefaultMaxConcurrentGames))
          assertEquals(initial.map(_.ladderAllowance), Some(1))
          assertEquals(raised.map(_.maxConcurrentGames), Some(4))
          assertEquals(reread, raised, "the RETURNING result must match a fresh read")
          assertEquals(opened.map(_.maxConcurrentGames), Some(4))
          assertEquals(opened.map(_.ladderAllowance), Some(3), "an open-to-humans bot keeps one slot for a person")
          assertEquals(ghost, None, "declaring for an unregistered identity must report None")
          assertEquals(unknown, None)
      }
    }

  test(
    "openToHumans/closeToHumans round-trip the description atomically; the pool lists only opted-in; unregistered -> None (ADR-0014)"
  ):
    withContainers { pg =>
      store(pg).use { db =>
        for
          _          <- db.register("catalog-suite", "on-bot", "hash-catalog-on")
          _          <- db.register("catalog-suite", "off-bot", "hash-catalog-off")
          opened     <- db.openToHumans("catalog-suite", "on-bot", Some("aggressive + book"))
          pool       <- db.openToHumansBots
          closed     <- db.closeToHumans("catalog-suite", "on-bot")
          poolAfter  <- db.openToHumansBots
          cleared    <- db.openToHumans("catalog-suite", "on-bot", None)
          ghostOpen  <- db.openToHumans("catalog-suite", "nobody", Some("x"))
          ghostClose <- db.closeToHumans("catalog-suite", "nobody")
        yield
          assertEquals(opened, Some(BotCatalogState(openToHumans = true, Some("aggressive + book"))))
          assert(pool.contains(Principal.Bot("catalog-suite", "on-bot")), s"expected on-bot in $pool")
          assert(!pool.contains(Principal.Bot("catalog-suite", "off-bot")), s"expected off-bot absent from $pool")
          assertEquals(
            closed,
            Some(BotCatalogState(openToHumans = false, Some("aggressive + book"))),
            "close keeps the description"
          )
          assert(!poolAfter.contains(Principal.Bot("catalog-suite", "on-bot")), "a closed bot leaves the pool")
          assertEquals(
            cleared,
            Some(BotCatalogState(openToHumans = true, None)),
            "re-open with None clears description"
          )
          assertEquals(ghostOpen, None, "opening an unregistered identity yields None")
          assertEquals(ghostClose, None, "closing an unregistered identity yields None")
      }
    }

  test("catalogBots lists open bots with their rating summary + description, and omits closed ones (ADR-0014, E2)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          _       <- db.register("cat2", "shown", "hash-cat2-shown")
          _       <- db.register("cat2", "hidden", "hash-cat2-hidden")
          _       <- db.openToHumans("cat2", "shown", Some("monte-carlo, 3-move book"))
          listing <- db.catalogBots
        yield
          assertEquals(
            listing.find(l => l.team == "cat2" && l.name == "shown"),
            Some(
              BotCatalogListing(
                "cat2",
                "shown",
                1500.0,
                350.0,
                Some("monte-carlo, 3-move book"),
                maxConcurrentGames = BotSeatPolicy.DefaultMaxConcurrentGames
              )
            ),
            "a freshly registered open bot lists at the initial rating, its description, and the default declared capacity"
          )
          assert(!listing.exists(_.name == "hidden"), s"a bot not open to humans must be absent, got $listing")
      }
    }

  test("catalogBots carries a raised declared capacity (#189, #224)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          _       <- db.register("cat2", "roomy", "hash-cat2-roomy")
          _       <- db.openToHumans("cat2", "roomy", None)
          _       <- db.setMaxConcurrentGames("cat2", "roomy", 5)
          listing <- db.catalogBots
        yield assertEquals(
          listing.find(_.name == "roomy").map(_.maxConcurrentGames),
          Some(5),
          "the listing must reflect a capacity raised after registration, not just the default"
        )
      }
    }

  test("webhook registration round-trips, re-register replaces url+secret, delete reports truth (#104)"):
    withContainers { pg =>
      store(pg).use { db =>
        val at = java.time.Instant.parse("2026-07-17T12:00:00Z")
        for
          // A webhook row requires its bot identity (FK to bots) — dedicated namespace, same reasoning as above.
          _        <- db.register("webhook-suite", "pusher", "hash-webhook-pusher")
          none     <- db.get("webhook-suite", "pusher")
          _        <- db.put(BotWebhook("webhook-suite", "pusher", "https://fn.example/turn", "secret-1", at))
          first    <- db.get("webhook-suite", "pusher")
          _        <- db.put(BotWebhook("webhook-suite", "pusher", "https://fn2.example/turn", "secret-2", at))
          replaced <- db.get("webhook-suite", "pusher")
          removed  <- db.delete("webhook-suite", "pusher")
          gone     <- db.get("webhook-suite", "pusher")
          again    <- db.delete("webhook-suite", "pusher")
        yield
          assertEquals(none, None)
          assertEquals(first, Some(BotWebhook("webhook-suite", "pusher", "https://fn.example/turn", "secret-1", at)))
          assertEquals(
            replaced,
            Some(BotWebhook("webhook-suite", "pusher", "https://fn2.example/turn", "secret-2", at)),
            "a re-register must replace the URL and the secret together"
          )
          assertEquals(removed, true)
          assertEquals(gone, None)
          assertEquals(again, false, "deleting an absent registration must report false, not lie")
      }
    }

  test("recordDelivery upserts the histogram cell, and statsFor splits it into the 24h/7d windows (#225)"):
    withContainers { pg =>
      store(pg).use { db =>
        val now       = Instant.parse("2026-08-02T12:00:00Z")
        val within24h = now.minusSeconds(3600)          // 1h ago — in both windows
        val within7d  = now.minusSeconds(3 * 24 * 3600) // 3 days ago — in the 7d window only
        val outside7d = now.minusSeconds(8 * 24 * 3600) // 8 days ago — in neither
        for
          _ <- db.register("stats-suite", "delivery-bot", "hash-stats-delivery")
          // Two deliveries in the same hour land in the SAME cell — proving the upsert accumulates, not overwrites.
          _       <- db.recordDelivery("stats-suite", "delivery-bot", DeliveryOutcome.Applied, 10.millis, within24h)
          _       <- db.recordDelivery("stats-suite", "delivery-bot", DeliveryOutcome.Applied, 10.millis, within24h)
          _       <- db.recordDelivery("stats-suite", "delivery-bot", DeliveryOutcome.TimedOut, 2.seconds, within7d)
          _       <- db.recordDelivery("stats-suite", "delivery-bot", DeliveryOutcome.Applied, 10.millis, outside7d)
          stats   <- db.statsFor("stats-suite", "delivery-bot", now)
          nothing <- db.statsFor("stats-suite", "nobody", now)
        yield
          assertEquals(stats.last24h.totalDeliveries, 2L, "only the two within24h deliveries are in the 24h window")
          assertEquals(stats.last24h.outcomes, List(OutcomeCount("applied", 2)))
          assertEquals(
            stats.last7d.totalDeliveries,
            3L,
            "the 7d window adds the timed_out delivery but still excludes the 8-day-old one"
          )
          assertEquals(
            stats.last7d.outcomes.sortBy(_.outcome),
            List(OutcomeCount("applied", 2), OutcomeCount("timed_out", 1))
          )
          assertEquals(nothing, WebhookStats.empty, "a bot with no recorded deliveries reports the empty windows")
      }
    }

  test(
    "recordDelivery sets last_failure_at/reason on a fault, but a clean Applied/Declined never overwrites it (#225)"
  ):
    withContainers { pg =>
      store(pg).use { db =>
        val firstFault  = Instant.parse("2026-08-01T10:00:00Z")
        val secondFault = Instant.parse("2026-08-01T11:00:00Z")
        val laterClean  = Instant.parse("2026-08-01T12:00:00Z")
        for
          _ <- db.register("stats-suite", "failure-bot", "hash-stats-failure")
          // last_failure_at/reason live on the bot_webhooks row itself (V13) — a real delivery only ever happens
          // once a webhook is registered (deliverTurn's own guard), so the test mirrors that precondition.
          _       <- db.put(BotWebhook("stats-suite", "failure-bot", "https://fn.example/turn", "secret", firstFault))
          initial <- db.statsFor("stats-suite", "failure-bot", laterClean)
          _ <- db.recordDelivery("stats-suite", "failure-bot", DeliveryOutcome.HttpStatus(503), 50.millis, firstFault)
          oneFault <- db.statsFor("stats-suite", "failure-bot", laterClean)
          _        <- db.recordDelivery("stats-suite", "failure-bot", DeliveryOutcome.TimedOut, 30.seconds, secondFault)
          _        <- db.recordDelivery("stats-suite", "failure-bot", DeliveryOutcome.Applied, 10.millis, laterClean)
          _        <- db.recordDelivery("stats-suite", "failure-bot", DeliveryOutcome.Declined, 10.millis, laterClean)
          finalRow <- db.statsFor("stats-suite", "failure-bot", laterClean)
        yield
          assertEquals(initial.lastFailure, None, "no deliveries yet — nothing to report")
          assertEquals(oneFault.lastFailure, Some(LastFailure(firstFault, "the endpoint answered HTTP 503")))
          assertEquals(
            finalRow.lastFailure,
            Some(LastFailure(secondFault, "the server's own delivery window expired with no response")),
            "the LATEST fault must win, and neither the later Applied nor the later Declined may overwrite it"
          )
      }
    }

  test("a webhook row cannot exist without its bot identity — the FK rejects strangers (#104)"):
    withContainers { pg =>
      store(pg).use { db =>
        val at = java.time.Instant.parse("2026-07-17T12:00:00Z")
        db.put(BotWebhook("webhook-suite", "never-registered", "https://fn.example", "s", at)).attempt.map {
          // Precisely the FK violation (SQLSTATE 23503), not just any store failure (review).
          case Left(e: java.sql.SQLException) => assertEquals(e.getSQLState, "23503", e.toString)
          case Left(other)                    => fail(s"expected a foreign-key SQLException, got $other")
          case Right(()) => fail("a webhook for an unregistered identity must be rejected by the FK")
        }
      }
    }

  test("finishing a game inserts exactly one game_results row with the expected fields (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-white-1")
        val black = Principal.Bot("b2-team", "b2-bot-1")
        for
          id <- GameId.random
          _  <- db.save(
            id,
            endedResultFixture(white, black, rated = true, ladder = true)
          )
          rows <- db.recentResultsFor(white.externalId)
        yield
          val row = rows.find(_.gameId.value == id.value).getOrElse(fail(s"row for $id not found in $rows"))
          assertEquals(row.whiteExternalId, white.externalId)
          assertEquals(row.blackExternalId, black.externalId)
          assertEquals(row.result, Some(1), "white won: white-POV result must be 1")
          assertEquals(row.termination, "resign")
          assert(row.rated)
          assertEquals(row.timeControl, TimeControl.Fischer(300, 3).toString)
          assertEquals(row.serverSeed, "ab12cd34")
          assertEquals(row.ladder, true, "the ladder marker (#190) must round-trip through the database")
          assertEquals(row.pairingId, None, "new rows never set pairing_id — it stays for historical CRN rows only")
      }
    }

  test("an active (not yet ended) game does not get a game_results row (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-white-active")
        val black = Principal.Guest("b2-black-active")
        for
          id <- GameId.random
          _  <- db.save(
            id,
            snapshotFixture(GameStatus.Active).copy(players = Map(Seat.White -> white, Seat.Black -> black))
          )
          rows <- db.recentResultsFor(white.externalId)
        yield assert(rows.forall(_.gameId.value != id.value), s"an active game must not appear in game_results: $rows")
      }
    }

  test("recentResultsFor finds a game whichever seat the participant sat, newest first (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b2-recent-participant")
        val opponent1   = Principal.Guest("b2-recent-opp1")
        val opponent2   = Principal.Bot("b2-team", "b2-recent-opp2")
        for
          idAsWhite <- GameId.random
          _         <- db.save(idAsWhite, endedResultFixture(participant, opponent1)) // participant seated White
          // A short, deterministic gap: finished_at defaults to the DB's own now(), and the "newest first" ordering
          // this test checks needs the two inserts to land at genuinely distinguishable timestamps.
          _         <- IO.sleep(20.millis)
          idAsBlack <- GameId.random
          _         <- db.save(idAsBlack, endedResultFixture(opponent2, participant)) // participant seated Black
          rows      <- db.recentResultsFor(participant.externalId)
        yield assertEquals(
          rows.map(_.gameId.value),
          List(idAsBlack.value, idAsWhite.value),
          s"expected newest first: $rows"
        )
      }
    }

  test("finishedRatedSince returns only rated games finished strictly after the cursor (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val before = Principal.Guest("b2-since-w1")
        for
          idBefore <- GameId.random
          _        <- db.save(idBefore, endedResultFixture(before, Principal.Guest("b2-since-b1"), rated = true))
          // The cursor is the row's OWN database-generated finished_at, not a JVM-side Instant.now(): comparing a
          // local clock against Postgres's own now() would make this boundary assertion depend on the two clocks
          // being in sync, which isn't guaranteed (#98 review).
          beforeRow <- db
            .recentResultsFor(before.externalId)
            .map(_.find(_.gameId.value == idBefore.value).getOrElse(fail("row not found right after saving it")))
          cursor = beforeRow.finishedAt
          // A short, deterministic gap so the next inserts' own finished_at lands strictly after the cursor.
          _            <- IO.sleep(20.millis)
          idAfterRated <- GameId.random
          _            <- db.save(
            idAfterRated,
            endedResultFixture(Principal.Guest("b2-since-w2"), Principal.Guest("b2-since-b2"), rated = true)
          )
          idAfterCasual <- GameId.random
          _             <- db.save(
            idAfterCasual,
            endedResultFixture(Principal.Guest("b2-since-w3"), Principal.Guest("b2-since-b3"), rated = false)
          )
          since <- db.finishedRatedSince(cursor)
        yield
          val ids = since.map(_.gameId.value).toSet
          assert(!ids.contains(idBefore.value), "a game AT the cursor must be excluded (strictly after)")
          assert(ids.contains(idAfterRated.value), "a rated game finished after the cursor must be included")
          assert(!ids.contains(idAfterCasual.value), "a casual (non-rated) game must be excluded regardless of timing")
      }
    }

  test("recentResultsFor does not double-count a self-played game (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        // GameRegistry.create itself doesn't forbid seating the same principal on both sides (only its
        // Lobby/Challenges callers do) — a UNION ALL of the white/black subqueries would otherwise return this
        // game twice.
        val soloPlayer = Principal.Guest("b2-self-play")
        for
          id   <- GameId.random
          _    <- db.save(id, endedResultFixture(soloPlayer, soloPlayer))
          rows <- db.recentResultsFor(soloPlayer.externalId)
        yield assertEquals(rows.count(_.gameId.value == id.value), 1, s"expected exactly one row, got $rows")
      }
    }

  test("saving the same ended snapshot twice still inserts exactly one game_results row (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-idempotent-white")
        val black = Principal.Guest("b2-idempotent-black")
        for
          id <- GameId.random
          fixture = endedResultFixture(white, black)
          _    <- db.save(id, fixture)
          _    <- db.save(id, fixture) // re-save: same game id, ON CONFLICT (game_id) DO NOTHING must hold
          rows <- db.recentResultsFor(white.externalId)
        yield assertEquals(rows.count(_.gameId.value == id.value), 1, s"expected exactly one row, got $rows")
      }
    }

  test("finishing a game inserts a game_archive row whose payload round-trips (#177)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-archive-white")
        val black = Principal.Bot("b2-team", "b2-archive-bot")
        for
          id      <- GameId.random
          _       <- db.save(id, endedResultFixture(white, black, rated = true))
          archive <- db.archiveFor(id)
        yield
          val payload = archive.getOrElse(fail(s"no game_archive row for $id")).payload
          val c       = payload.hcursor
          assert(c.get[Boolean]("rated").toOption.contains(true))
          assertEquals(c.downField("players").get[String]("white").toOption, Some(white.externalId))
          assertEquals(c.downField("players").get[String]("black").toOption, Some(black.externalId))
          assertEquals(
            c.downField("turns").downN(0).get[List[String]]("moves").toOption,
            Some(List("e2e4")),
            s"the turn recorded on the fixture snapshot must round-trip: $payload"
          )
          assert(
            c.downField("fairness").get[String]("commit").toOption.exists(_.nonEmpty),
            s"the fairness block must be present: $payload"
          )
      }
    }

  test("an active (not yet ended) game does not get a game_archive row (#177)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          id      <- GameId.random
          _       <- db.save(id, snapshotFixture(GameStatus.Active))
          archive <- db.archiveFor(id)
        yield assertEquals(archive, None)
      }
    }

  test("an aborted game does not get a game_archive row, unlike game_results (#177)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-archive-aborted-white")
        val black = Principal.Guest("b2-archive-aborted-black")
        for
          id      <- GameId.random
          _       <- db.save(id, endedResultFixture(white, black, termination = Termination.Aborted))
          archive <- db.archiveFor(id)
          results <- db.recentResultsFor(white.externalId)
        yield
          assertEquals(archive, None, "an aborted game has no sporting outcome and must not be archived")
          assert(
            results.exists(_.gameId.value == id.value),
            "unlike the archive, game_results DOES keep an aborted game as an operational row"
          )
      }
    }

  test("saving the same ended snapshot twice still inserts exactly one game_archive row (#177)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-archive-idempotent-white")
        val black = Principal.Guest("b2-archive-idempotent-black")
        for
          id <- GameId.random
          fixture = endedResultFixture(white, black)
          _       <- db.save(id, fixture)
          _       <- db.save(id, fixture) // re-save: same game id, ON CONFLICT (game_id) DO NOTHING must hold
          archive <- db.archiveFor(id)
        yield assert(archive.isDefined, "expected exactly one (unconflicted) game_archive row")
      }
    }

  /** A second, unpooled connection to the SAME database, used only to forge the pre-#177 state the backfill exists to
    * repair: an ended game whose snapshot is on disk but whose archive row is missing. No production path ever deletes
    * an archive row, so there is no store method for it — and forging it is the only way to test the repair.
    */
  private def rawXa(pg: PostgreSQLContainer) =
    for
      connectEC <- ExecutionContexts.fixedThreadPool[IO](2)
      xa        <- HikariTransactor
        .newHikariTransactor[IO]("org.postgresql.Driver", pg.jdbcUrl, pg.username, pg.password, connectEC)
    yield xa

  test("the backfill stamps finished_at from the game's own finish time, not the backfill time (#199)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b4-backfill-when-white")
        val black = Principal.Bot("b4-team", "b4-backfill-when-bot")
        for
          id <- GameId.random
          _  <- db.save(id, endedResultFixture(white, black, rated = true))
          // Forge the pre-#177 state, and age the game a week so "now()" and "the real finish time" cannot be
          // confused for each other — this is the specific bug #199 exists to avoid.
          realFinish = Instant.parse("2026-07-24T10:00:00Z")
          _ <- sql"DELETE FROM play.game_archive WHERE game_id = ${id.value}::uuid".update.run.transact(xa)
          _ <- sql"UPDATE play.game_results SET finished_at = $realFinish WHERE game_id = ${id.value}::uuid".update.run
            .transact(xa)
          batch   <- db.backfillArchive(after = None, limit = 500)
          archive <- db.archiveFor(id)
        yield
          assert(batch.inserted >= 1, s"the forged row must be back-filled: $batch")
          val row = archive.getOrElse(fail(s"no game_archive row for $id after the backfill"))
          assertEquals(
            row.finishedAt,
            realFinish,
            "finished_at must come from game_results, NOT the column's DEFAULT now() — GET /games/{id}/history " +
              "serves this field straight to the replay page"
          )
      }
    }

  test("a back-filled payload is identical to the one written natively at game end (#199)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b4-backfill-same-white")
        val black = Principal.Bot("b4-team", "b4-backfill-same-bot")
        for
          id     <- GameId.random
          _      <- db.save(id, endedResultFixture(white, black, rated = true))
          native <- db.archiveFor(id)
          _      <- sql"DELETE FROM play.game_archive WHERE game_id = ${id.value}::uuid".update.run.transact(xa)
          _      <- db.backfillArchive(after = None, limit = 500)
          filled <- db.archiveFor(id)
        yield assertEquals(
          filled.map(_.payload),
          native.map(_.payload),
          "the backfill reuses GameArchive.payload, so the row must be byte-identical — no second code path to drift"
        )
      }
    }

  test("the backfill is idempotent: a second pass over the same games inserts nothing (#199)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b4-backfill-twice-white")
        val black = Principal.Guest("b4-backfill-twice-black")
        for
          id     <- GameId.random
          _      <- db.save(id, endedResultFixture(white, black))
          _      <- sql"DELETE FROM play.game_archive WHERE game_id = ${id.value}::uuid".update.run.transact(xa)
          first  <- db.backfillArchive(after = None, limit = 500)
          second <- db.backfillArchive(after = None, limit = 500)
        yield
          assert(first.inserted >= 1, s"the first pass must insert the forged row: $first")
          // `inserted`, not `scanned`: a second pass legitimately still SCANS the rows that can never be archived —
          // this suite shares one database across every test, and an aborted game (see the #177 test above) is a
          // permanent, correct skip. Idempotence means writing nothing new, not running out of rows to look at.
          assertEquals(second.inserted, 0, s"a second pass must write nothing new: $second")
      }
    }

  test("the cursor advances past a game it cannot convert, instead of re-scanning it forever (#199)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b4-backfill-corrupt-white")
        val black = Principal.Guest("b4-backfill-corrupt-black")
        for
          id <- GameId.random
          _  <- db.save(id, endedResultFixture(white, black))
          _  <- sql"DELETE FROM play.game_archive WHERE game_id = ${id.value}::uuid".update.run.transact(xa)
          // Corrupt the snapshot so `json.as[GameSnapshot]` fails: the row can never be converted, and a loop that
          // re-queried `NOT EXISTS` from the start would spin on it forever.
          _ <- sql"""UPDATE play.games SET snapshot = '{"not":"a snapshot"}'::jsonb
                     WHERE id = ${id.value}::uuid""".update.run.transact(xa)
          batch <- db.backfillArchive(after = None, limit = 500)
          // The batch that saw it must report a cursor at least as far as this game, so the next call starts beyond it.
          next <- db.backfillArchive(batch.lastId, limit = 500)
        yield
          assert(batch.skipped >= 1, s"the corrupt row must be counted as skipped, not inserted: $batch")
          assert(
            batch.lastId.exists(_.value >= id.value),
            s"the cursor must move past the unconvertible row: ${batch.lastId} vs $id"
          )
          assertEquals(next.scanned, 0, s"nothing may remain after the cursor: $next")
      }
    }

  /** Ages a finished game's operational rows past a retention cutoff. Production never back-dates anything, so there is
    * no store method for this — but without it every retention test would have to wait out a real interval.
    */
  private def ageGame(xa: doobie.Transactor[IO], id: GameId, at: Instant): IO[Unit] =
    (
      sql"UPDATE play.games SET updated_at = $at WHERE id = ${id.value}::uuid".update.run,
      sql"UPDATE play.outbox SET delivered_at = $at WHERE game_id = ${id.value}::uuid".update.run
    ).mapN((_, _) => ()).transact(xa)

  private val LongAgo: Instant  = Instant.parse("2020-01-01T00:00:00Z")
  private val PruneCut: Instant = Instant.parse("2020-06-01T00:00:00Z")

  /** Prunes until a batch removes nothing and returns that terminal batch — the same loop `Retention.drain` runs, and
    * the only state in which `RetentionSweep.retainedUnarchived` is measured rather than left at 0.
    */
  private def drainPrune(db: PgGameStore): IO[RetentionSweep] =
    db.pruneOnce(PruneCut, limit = 500)
      .flatMap(sweep => if sweep.removedAnything then drainPrune(db) else IO.pure(sweep))

  test("retention prunes a delivered client report past the cutoff and keeps a parked one (#212)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          deliveredId <- GameId.random
          parkedId    <- GameId.random
          payload = io.circe.Json.obj("id" -> io.circe.Json.fromString("irrelevant"))
          _ <- db.insertClientReport(deliveredId, payload)
          _ <- db.insertClientReport(parkedId, payload)
          _ <- db.clientReports.markDelivered(deliveredId)
          _ <-
            sql"UPDATE play.client_reports SET delivered_at = $LongAgo WHERE report_id = ${deliveredId.value}::uuid".update.run
              .transact(xa)
          _ <- db.clientReports.markParked(parkedId, "422 from the replay gate")
          // Back-date the parked row's delivered_at too (production leaves it NULL): with only the NULL check
          // protecting it, this test would pass even if the NOT failed_permanently guard were dropped.
          _ <-
            sql"UPDATE play.client_reports SET delivered_at = $LongAgo WHERE report_id = ${parkedId.value}::uuid".update.run
              .transact(xa)
          _             <- db.pruneOnce(PruneCut, limit = 500)
          deliveredLeft <- sql"SELECT count(*) FROM play.client_reports WHERE report_id = ${deliveredId.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
          parkedLeft <- sql"SELECT count(*) FROM play.client_reports WHERE report_id = ${parkedId.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
        yield
          assertEquals(deliveredLeft, 0, "a delivered report past the cutoff is dead weight")
          assertEquals(parkedLeft, 1, "a parked report is kept for manual inspection, not pruned")
      }
    }

  test("retention prunes an old ended game's delivered outbox row and its snapshot, keeping the archive (#179)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b5-prune-white")
        val black = Principal.Bot("b5-team", "b5-prune-bot")
        for
          id           <- GameId.random
          _            <- db.save(id, endedResultFixture(white, black, rated = true))
          _            <- ageGame(xa, id, LongAgo)
          _            <- db.pruneOnce(PruneCut, limit = 500)
          snapshotLeft <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
          outboxLeft <- sql"SELECT count(*) FROM play.outbox WHERE game_id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
          archive <- db.archiveFor(id)
          results <- db.recentResultsFor(white.externalId)
        yield
          assertEquals(outboxLeft, 0, "a delivered outbox row past the cutoff is dead weight")
          assertEquals(snapshotLeft, 0, "the ended snapshot is dead weight once the archive serves its history")
          assert(archive.isDefined, "the archive is permanent by contract and must survive the prune")
          assert(
            results.exists(_.gameId.value == id.value),
            "game_results is the list/rating projection and must survive the prune too"
          )
      }
    }

  test("a pruned game's history is still served from the archive — the whole point of #179"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b5-served-white")
        val black = Principal.Bot("b5-team", "b5-served-bot")
        for
          id      <- GameId.random
          _       <- db.save(id, endedResultFixture(white, black, rated = true))
          before  <- db.archiveFor(id)
          _       <- ageGame(xa, id, LongAgo)
          _       <- db.pruneOnce(PruneCut, limit = 500)
          after   <- db.archiveFor(id)
          gameRow <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid".query[Int].unique.transact(xa)
        yield
          assertEquals(gameRow, 0, "the snapshot must actually be gone, or this proves nothing")
          assertEquals(after.map(_.payload), before.map(_.payload), "replay must read identically after the prune")
      }
    }

  test("an ACTIVE game is never pruned, however old its row looks (#179)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          id <- GameId.random
          _  <- db.save(id, snapshotFixture(GameStatus.Active))
          // Back-date it far past the cutoff: only `status` may decide this, never age. Pruning a live snapshot would
          // forfeit a real game on the next boot, since resume reads WHERE status='active'.
          _ <- sql"UPDATE play.games SET updated_at = $LongAgo WHERE id = ${id.value}::uuid".update.run.transact(xa)
          _ <- db.pruneOnce(PruneCut, limit = 500)
          active <- db.loadActive
        yield assert(
          active.exists(_._1.value == id.value),
          "an active game must survive retention and still be resumable"
        )
      }
    }

  test("a parked outbox row and its snapshot both survive retention (#179)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b5-parked-white")
        val black = Principal.Guest("b5-parked-black")
        for
          id         <- GameId.random
          _          <- db.save(id, endedResultFixture(white, black))
          _          <- ageGame(xa, id, LongAgo)
          _          <- db.markParked(id, "422 from the replay gate")
          _          <- db.pruneOnce(PruneCut, limit = 500)
          outboxLeft <- sql"SELECT count(*) FROM play.outbox WHERE game_id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
          snapshotLeft <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
        yield
          assertEquals(outboxLeft, 1, "a parked row is kept for manual inspection, not pruned")
          assertEquals(
            snapshotLeft,
            1,
            "and the FK pins its snapshot too — the evidence for that inspection stays whole"
          )
      }
    }

  test("an unarchived ended game is retained and counted, never silently destroyed (#179)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b5-unarchived-white")
        val black = Principal.Guest("b5-unarchived-black")
        for
          id <- GameId.random
          _  <- db.save(id, endedResultFixture(white, black))
          _  <- ageGame(xa, id, LongAgo)
          // Forge the pre-#177 state: history exists ONLY in this snapshot. Pruning it would recreate exactly the loss
          // #199 had to repair, so the pass must refuse and say so.
          _ <- sql"DELETE FROM play.game_archive WHERE game_id = ${id.value}::uuid".update.run.transact(xa)
          // Drain to a terminal batch, exactly as `Retention.drain` does: `retainedUnarchived` is only measured on a
          // batch that removed nothing (see RetentionSweep), so reading it off a single call would depend on whether
          // some other test's aged row happened to still be prunable.
          sweep        <- drainPrune(db)
          snapshotLeft <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
        yield
          assertEquals(snapshotLeft, 1, "the only copy of this game's history must survive")
          assert(sweep.retainedUnarchived >= 1, s"and the refusal must be visible, not silent: $sweep")
      }
    }

  test("an aborted game's snapshot IS pruned — it has no history to preserve by design (#179)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b5-aborted-white")
        val black = Principal.Guest("b5-aborted-black")
        for
          id <- GameId.random
          _  <- db.save(id, endedResultFixture(white, black, termination = Termination.Aborted))
          _  <- ageGame(xa, id, LongAgo)
          // An aborted game never gets an archive row (GameArchive.payload excludes it), so the archive-exists guard
          // alone would retain it forever. The aborted carve-out is what lets it go.
          archive      <- db.archiveFor(id)
          _            <- db.pruneOnce(PruneCut, limit = 500)
          snapshotLeft <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
        yield
          assertEquals(archive, None, "precondition: an aborted game is never archived")
          // That its snapshot is gone IS the assertion: had the aborted carve-out been missing, the archive-exists
          // guard would have retained it. `sweep.retainedUnarchived` is deliberately not asserted here — it counts
          // table-wide, and this suite shares one database, so a row another test intentionally left behind (see the
          // unarchived test above) legitimately shows up in it.
          assertEquals(snapshotLeft, 0, "so it must be prunable without the unarchived guard blocking it")
      }
    }

  test("retention leaves anything newer than the cutoff completely alone (#179)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b5-fresh-white")
        val black = Principal.Guest("b5-fresh-black")
        for
          id <- GameId.random
          _  <- db.save(id, endedResultFixture(white, black))
          // Deliberately NOT aged: a just-finished game is exactly what an operator may still need.
          _            <- db.pruneOnce(PruneCut, limit = 500)
          snapshotLeft <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
          outboxLeft <- sql"SELECT count(*) FROM play.outbox WHERE game_id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
        yield
          // Only this game's own rows are asserted, for the same shared-database reason as the aborted test above.
          assertEquals(snapshotLeft, 1, "a fresh snapshot is untouched")
          assertEquals(outboxLeft, 1, "and so is its outbox row")
      }
    }

  test("playerGamesPage keyset-paginates: `before` returns only strictly older games, still newest first (#173)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-page-participant")
        val opponent    = Principal.Bot("b3-team", "b3-page-opponent")
        for
          idOldest  <- GameId.random
          _         <- db.save(idOldest, endedResultFixture(participant, opponent))
          _         <- IO.sleep(20.millis)
          idMiddle  <- GameId.random
          _         <- db.save(idMiddle, endedResultFixture(opponent, participant))
          middleRow <- db
            .playerGamesPage(List(participant.externalId), None, None, None, limit = 100)
            .map(_.games.find(_.gameId.value == idMiddle.value).getOrElse(fail("middle row not found")))
          _        <- IO.sleep(20.millis)
          idNewest <- GameId.random
          _        <- db.save(idNewest, endedResultFixture(participant, opponent))
          page <- db.playerGamesPage(List(participant.externalId), Some(middleRow.finishedAt), None, None, limit = 100)
        yield
          val ids = page.games.map(_.gameId.value)
          assert(!ids.contains(idNewest.value), "newer than `before` excluded")
          assert(!ids.contains(idMiddle.value), "AT `before` excluded (strictly older only)")
          assertEquals(ids, List(idOldest.value), s"expected only the oldest row, got $page")
      }
    }

  test("playerGamesPage reports `hasMore` exactly, without fetching the whole history (#173)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-hasmore-participant")
        val opponent    = Principal.Bot("b3-team", "b3-hasmore-opponent")
        for
          _         <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, opponent)))
          _         <- GameId.random.flatMap(db.save(_, endedResultFixture(opponent, participant)))
          _         <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, opponent)))
          fullPage  <- db.playerGamesPage(List(participant.externalId), None, None, None, limit = 3)
          shortPage <- db.playerGamesPage(List(participant.externalId), None, None, None, limit = 2)
        yield
          assertEquals(fullPage.hasMore, false, "exactly 3 rows fit a limit-3 page")
          assertEquals(shortPage.hasMore, true, "3 rows do not fit a limit-2 page")
      }
    }

  test("playerGamesPage `OpponentFilter.Bot` restricts to games against that one bot (#173)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-vsbot-participant")
        val botA        = Principal.Bot("b3-team", "b3-vsbot-a")
        val botB        = Principal.Bot("b3-team", "b3-vsbot-b")
        for
          idVsA <- GameId.random
          _     <- db.save(idVsA, endedResultFixture(participant, botA))
          idVsB <- GameId.random
          _     <- db.save(idVsB, endedResultFixture(botB, participant))
          page  <- db.playerGamesPage(
            List(participant.externalId),
            None,
            Some(OpponentFilter.Bot(botA.externalId)),
            None,
            limit = 100
          )
        yield assertEquals(page.games.map(_.gameId.value), List(idVsA.value))
      }
    }

  test("playerGamesPage `OpponentFilter.HumanOnly` restricts to games against non-bot opponents (#173)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-vshuman-participant")
        val bot         = Principal.Bot("b3-team", "b3-vshuman-bot")
        val human       = Principal.Guest("b3-vshuman-opponent")
        for
          idVsBot   <- GameId.random
          _         <- db.save(idVsBot, endedResultFixture(participant, bot))
          idVsHuman <- GameId.random
          _         <- db.save(idVsHuman, endedResultFixture(human, participant))
          page      <- db.playerGamesPage(
            List(participant.externalId),
            None,
            Some(OpponentFilter.HumanOnly),
            None,
            limit = 100
          )
        yield assertEquals(page.games.map(_.gameId.value), List(idVsHuman.value))
      }
    }

  test("playerGamesPage `result` filters by the participant's OWN point of view regardless of seat (#173)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-povresult-participant")
        val opponent    = Principal.Bot("b3-team", "b3-povresult-opponent")
        for
          idWinAsWhite <- GameId.random
          _ <- db.save(idWinAsWhite, endedResultFixture(participant, opponent, result = GameResult.Win(Side.White)))
          idWinAsBlack <- GameId.random
          // Stored white-POV: Black winning is result = -1, even though the PARTICIPANT (seated Black here) won.
          _ <- db.save(idWinAsBlack, endedResultFixture(opponent, participant, result = GameResult.Win(Side.Black)))
          idLoss <- GameId.random
          _      <- db.save(idLoss, endedResultFixture(participant, opponent, result = GameResult.Win(Side.Black)))
          wins   <- db.playerGamesPage(List(participant.externalId), None, None, Some(PovResultFilter.Win), limit = 100)
        yield assertEquals(
          wins.games.map(_.gameId.value).toSet,
          Set(idWinAsWhite.value, idWinAsBlack.value),
          "both wins returned regardless of which seat the participant sat; the loss excluded"
        )
      }
    }

  test("opponentsFor groups by specific bot and collapses every human opponent into one row (#174)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-opponents-participant")
        val bot         = Principal.Bot("b3-team", "b3-opponents-bot")
        val humanA      = Principal.Guest("b3-opponents-human-a")
        val humanB      = Principal.Guest("b3-opponents-human-b")
        for
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, bot)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(bot, participant)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, humanA)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(humanB, participant)))
          rows <- db.opponentsFor(List(participant.externalId))
          byBotKey = rows.map(r => r.botExternalId -> r.games).toMap
        yield
          assertEquals(byBotKey.get(Some(bot.externalId)), Some(2), s"both bot games grouped together: $rows")
          assertEquals(byBotKey.get(None), Some(2), s"both human opponents collapsed into one row: $rows")
          assertEquals(rows.size, 2, "exactly one bot row plus one collapsed human row")
      }
    }

  test("opponentsFor computes W-D-L from the participant's own POV regardless of which seat they sat (#174)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-opppov-participant")
        val bot         = Principal.Bot("b3-team", "b3-opppov-bot")
        for
          _ <- GameId.random.flatMap(
            db.save(_, endedResultFixture(participant, bot, result = GameResult.Win(Side.White)))
          )
          // Participant seated Black and won: stored white-POV result is Black winning, i.e. -1.
          _ <- GameId.random.flatMap(
            db.save(_, endedResultFixture(bot, participant, result = GameResult.Win(Side.Black)))
          )
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, bot, result = GameResult.Draw)))
          rows <- db.opponentsFor(List(participant.externalId))
          botRow = rows.find(_.botExternalId.contains(bot.externalId)).getOrElse(fail(s"no row for the bot: $rows"))
        yield
          assertEquals(botRow.games, 3)
          assertEquals(botRow.wins, 2, "both wins counted regardless of seat")
          assertEquals(botRow.draws, 1)
          assertEquals(botRow.losses, 0)
      }
    }

  test("opponentsFor excludes self-play — a game against yourself has no opponent to aggregate against (#174)"):
    withContainers { pg =>
      store(pg).use { db =>
        val soloPlayer = Principal.Guest("b3-selfplay-participant")
        for
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(soloPlayer, soloPlayer)))
          rows <- db.opponentsFor(List(soloPlayer.externalId))
        yield assertEquals(rows, Nil, s"self-play must not appear as an opponent row: $rows")
      }
    }

  test("opponentsFor orders most-played first (#174)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-oppsort-participant")
        val busyBot     = Principal.Bot("b3-team", "b3-oppsort-busy")
        val quietBot    = Principal.Bot("b3-team", "b3-oppsort-quiet")
        for
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, quietBot)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, busyBot)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(busyBot, participant)))
          rows <- db.opponentsFor(List(participant.externalId))
        yield assertEquals(
          rows.map(_.botExternalId),
          List(Some(busyBot.externalId), Some(quietBot.externalId)),
          s"busier opponent first: $rows"
        )
      }
    }

  test("opponentsFor is empty for a participant with no games (#174)"):
    withContainers { pg =>
      store(pg).use(db =>
        db.opponentsFor(List(Principal.Guest("b3-opponents-nobody").externalId)).map(assertEquals(_, Nil))
      )
    }

  test("opponentsFor works the same when the participant is a bot: opponents itemized, humans collapsed (#182)"):
    withContainers { pg =>
      store(pg).use { db =>
        val profiledBot = Principal.Bot("b3-team", "b3-opponents-profiled")
        val otherBot    = Principal.Bot("b3-team", "b3-opponents-other")
        val humanA      = Principal.Guest("b3-opponents-profiled-human-a")
        val humanB      = Principal.Guest("b3-opponents-profiled-human-b")
        for
          // A ladder game against another bot is rated; every guest game is casual (`GameRegistry.isRated`) —
          // mixing both here is the point: a bot's "record vs humans" must count the casual games too.
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(profiledBot, otherBot, rated = true)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(humanA, profiledBot, rated = false)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(profiledBot, humanB, rated = false)))
          rows <- db.opponentsFor(List(profiledBot.externalId))
          byBotKey = rows.map(r => r.botExternalId -> r.games).toMap
        yield
          assertEquals(byBotKey.get(Some(otherBot.externalId)), Some(1), s"the other bot itemized: $rows")
          assertEquals(byBotKey.get(None), Some(2), s"both unrated human games collapsed into one row: $rows")
          assertEquals(rows.size, 2, "exactly one bot row plus one collapsed human row")
      }
    }

  test("ended games are not resumed"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          id  <- GameId.random
          _   <- db.save(id, snapshotFixture(GameStatus.Ended(GameOver(GameResult.Draw, Termination.Draw))))
          all <- db.loadActive
        yield assert(all.forall(_._1.value != id.value), "an ended game must not appear in loadActive")
      }
    }

  test("the leaderboard lists converged bots best-first with their rated records and hides provisional ones (#103)"):
    withContainers { pg =>
      store(pg).use { db =>
        val strong: Principal.Bot = Principal.Bot("lb-suite", "strong")
        val weak: Principal.Bot   = Principal.Bot("lb-suite", "weak")
        for
          _ <- db.register("lb-suite", "strong", "hash-lb-strong")
          _ <- db.register("lb-suite", "weak", "hash-lb-weak")
          _ <- db.register("lb-suite", "fresh", "hash-lb-fresh") // untouched: RD 350 = provisional
          _ <- db.setOnLadder("lb-suite", "strong", true)
          // Converge both veterans' ratings. The stamped game id is random and matches no game_results row, so the
          // stamp inside applyRatingUpdate is a no-op — this is purely "set two bots' glicko state atomically".
          fakeId <- GameId.random
          _      <- db.applyRatingUpdate(
            fakeId,
            seeded(RatedIdentity.of(strong), 1700.0, 80.0),
            seeded(RatedIdentity.of(weak), 1400.0, 90.0)
          )
          // The rated record: strong beats weak once per colour, plus one draw; one casual win must not count.
          idA <- GameId.random
          _   <- db.save(idA, endedResultFixture(strong, weak, rated = true)) // strong wins as White
          idB <- GameId.random
          _   <- db.save(
            idB,
            endedResultFixture(weak, strong, rated = true, result = GameResult.Win(Side.Black))
          ) // strong wins as Black
          idC   <- GameId.random
          _     <- db.save(idC, endedResultFixture(strong, weak, rated = true, result = GameResult.Draw))
          idD   <- GameId.random
          _     <- db.save(idD, endedResultFixture(strong, weak, rated = false)) // casual: excluded from the tally
          board <- db.leaderboard(RatingCategory.Default, maxRd = 110.0).map(_.filter(_.team == "lb-suite"))
        yield
          assertEquals(board.map(_.name), List("strong", "weak"), "best conservative estimate first; 'fresh' hidden")
          val strongRow = board.head
          assertEquals(strongRow.tally, ResultTally(wins = 2, draws = 1, losses = 0))
          assert(strongRow.onLadder, "the on-ladder flag must ride along")
          assertEquals(board(1).tally, ResultTally(wins = 0, draws = 1, losses = 2))
          assert(!board(1).onLadder)
      }
    }

  test("the leaderboard orders by the conservative estimate rating − 2·RD, more-certain first on ties (#169)"):
    withContainers { pg =>
      store(pg).use { db =>
        val team = "lb169-suite"
        // Conservative scores: tieHot 1600−100=1500, tieCool 1560−60=1500, steady 1560−90=1470, streaker 1580−210=1370.
        // Raw rating would order tieHot, streaker, then steady/tieCool — the board must not.
        val states = List(
          ("streaker", 1580.0, 105.0), // the motivating case: highest-but-one raw rating, streak-inflated RD
          ("steady", 1560.0, 45.0),
          ("tie-hot", 1600.0, 50.0), // same conservative score as tie-cool, decided by the lower RD
          ("tie-cool", 1560.0, 30.0)
        )
        for
          _ <- states.traverse_((name, _, _) => db.register(team, name, s"hash-$name"))
          _ <- states.traverse_ { (name, rating, rd) =>
            // The stamped game id matches no game_results row, so this is purely "set the glicko state" — the same
            // idiom as the test above; the second seat is a throwaway self-overwrite of the first bot's own state.
            GameId.random.flatMap { fakeId =>
              val update = seeded(RatedIdentity.of(Principal.Bot(team, name)), rating, rd)
              db.applyRatingUpdate(fakeId, update, update)
            }
          }
          board <- db.leaderboard(RatingCategory.Default, maxRd = 110.0).map(_.filter(_.team == team))
        yield assertEquals(
          board.map(_.name),
          List("tie-cool", "tie-hot", "steady", "streaker"),
          "ordered by rating − 2·RD, ties to the lower RD — never by the raw rating"
        )
      }
    }

  test("leaderboard(limit = 1) returns at most 1 bot even when more are converged (#289)"):
    withContainers { pg =>
      store(pg).use { db =>
        val team = "lb289-bot-suite"
        // Use absurdly high ratings so alpha always ranks above every other fixture row in the suite,
        // guaranteeing it lands in the limit-1 result before any .filter — the shared container accumulates
        // rows from every test above, and a realistic rating would fall out of the LIMIT 1 window (#289).
        val bots = List(("alpha", 5000.0, 10.0), ("beta", 4900.0, 10.0))
        for
          _ <- bots.traverse_((name, _, _) => db.register(team, name, s"hash-$name-289"))
          _ <- bots.traverse_ { (name, rating, rd) =>
            GameId.random.flatMap { fakeId =>
              val update = seeded(RatedIdentity.of(Principal.Bot(team, name)), rating, rd)
              db.applyRatingUpdate(fakeId, update, update)
            }
          }
          board <- db.leaderboard(RatingCategory.Default, maxRd = 110.0, limit = 1)
        yield
          // alpha has the highest conservative estimate in the whole suite — it must be the first and only row.
          assertEquals(board.length, 1, "limit-1 must return exactly 1 row")
          assertEquals(board.head.name, "alpha", s"the sole row must be alpha, got ${board.head.name}")
          assertEquals(board.head.team, team)
      }
    }

  test("?kind=all re-limit: a limit-2 merged board returns the 2 highest-rated across both populations (#289)"):
    withContainers { pg =>
      store(pg).use { db =>
        // Ratings must exceed those in the limit-1 test above (alpha=5000/rd=10, conservative 4980) so that
        // best-bot and second-bot rank above alpha in the shared container and take both slots of LIMIT 2.
        // conservative = rating - 2*rd: best-bot 6160, second-bot 6060, best-player 6140, second-player 6040.
        val botTeam = "lb289-merge-bots"
        for
          _         <- db.register(botTeam, "best-bot", "hash-bbot-289")
          _         <- db.register(botTeam, "second-bot", "hash-sbot-289")
          accBest   <- db.upsertOnLogin("google", "sub-merge-best", None, IO.pure("BestPlayer"))
          accSecond <- db.upsertOnLogin("google", "sub-merge-second", None, IO.pure("SecondPlayer"))
          _         <- GameId.random.flatMap { id =>
            db.applyRatingUpdate(
              id,
              seeded(RatedIdentity.of(Principal.Bot(botTeam, "best-bot")), 6200.0, 20.0),  // conservative 6160
              seeded(RatedIdentity.of(Principal.Bot(botTeam, "second-bot")), 6100.0, 20.0) // conservative 6060
            )
          }
          _ <- GameId.random.flatMap { id =>
            db.applyRatingUpdate(
              id,
              seeded(RatedIdentity.User(accBest.id), 6180.0, 20.0),
              seeded(RatedIdentity.User(accSecond.id), 6080.0, 20.0)
            )
          }
          // SQL LIMIT 2 on each population: our 4 entries rank above everything else in the container.
          // bots window = [best-bot (6160), second-bot (6060)].
          // players window = [BestPlayer (6140), SecondPlayer (6040)].
          // The route-level re-limit after merging all 4 is verified by LeaderboardRoutesSuite — here we
          // confirm each store call returns exactly 2 rows with the correct ordering.
          bots    <- db.leaderboard(RatingCategory.Default, maxRd = 110.0, limit = 2)
          players <- db.playerLeaderboard(RatingCategory.Default, maxRd = 110.0, limit = 2)
        yield
          assertEquals(bots.length, 2, "LIMIT 2 on bots must return exactly 2 rows")
          assertEquals(bots.head.name, "best-bot", s"first bot must be best-bot, got ${bots.head.name}")
          assertEquals(bots(1).name, "second-bot", s"second bot must be second-bot, got ${bots(1).name}")
          assertEquals(players.length, 2, "LIMIT 2 on players must return exactly 2 rows")
          assertEquals(
            players.head.nickname,
            "BestPlayer",
            s"first player must be BestPlayer, got ${players.head.nickname}"
          )
          assertEquals(
            players(1).nickname,
            "SecondPlayer",
            s"second player must be SecondPlayer, got ${players(1).nickname}"
          )
      }
    }

  test("settledRatingsByExternalId batches bots and accounts, and hides provisional or guest ids (#290)"):
    withContainers { pg =>
      store(pg).use { db =>
        val veteran: Principal.Bot = Principal.Bot("sr-suite", "veteran")
        for
          _       <- db.register("sr-suite", "veteran", "hash-sr-vet")
          _       <- db.register("sr-suite", "fresh", "hash-sr-fresh") // untouched: RD 350 = provisional
          account <- db.upsertOnLogin("google", "sub-sr-1", None, IO.pure("SettledNick"))
          // Converge the veteran and the account in one atomic write; the stamped id matches no game_results row.
          fakeId <- GameId.random
          _      <- db.applyRatingUpdate(
            fakeId,
            seeded(RatedIdentity.of(veteran), 1642.0, 80.0),
            seeded(RatedIdentity.User(account.id), 1756.0, 90.0)
          )
          userExt  = Principal.User(account.id).externalId
          freshExt = Principal.Bot("sr-suite", "fresh").externalId
          ratings <- db.settledRatingsByExternalId(
            List(veteran.externalId, freshExt, userExt, "guest:11111111-1111-1111-1111-111111111111"),
            RatingCategory.Default,
            maxRd = 110.0
          )
        yield
          assertEquals(ratings.get(veteran.externalId), Some(1642.0))
          assertEquals(ratings.get(userExt), Some(1756.0))
          assertEquals(ratings.get(freshExt), None, "a provisional entrant stays hidden — the leaderboard's own rule")
          assertEquals(
            ratings.keySet.exists(_.startsWith("guest:")),
            false,
            "a guest id must never come back rated"
          )
      }
    }

  test("categoryTalliesFor counts rated decided games from either seat, and is empty for a stranger (#103)"):
    withContainers { pg =>
      store(pg).use { db =>
        val a = Principal.Bot("lb-tally", "a")
        val b = Principal.Bot("lb-tally", "b")
        for
          idA <- GameId.random
          _   <- db.save(idA, endedResultFixture(a, b, rated = true)) // a wins as White
          idB <- GameId.random
          _   <- db.save(idB, endedResultFixture(b, a, rated = true, result = GameResult.Win(Side.Black))) // a as Black
          idC <- GameId.random
          _        <- db.save(idC, endedResultFixture(a, b, rated = false)) // casual: excluded
          tallyA   <- db.categoryTalliesFor(a.externalId)
          tallyB   <- db.categoryTalliesFor(b.externalId)
          stranger <- db.categoryTalliesFor("bot:team:lb-tally:nobody")
        yield
          // The fixture plays 5+3, so everything lands under one key; the profile surfaces read exactly this way.
          assertEquals(tallyA, Map(RatingCategory.Blitz -> ResultTally(wins = 2, draws = 0, losses = 0)))
          assertEquals(tallyA.getOrElse(RatingCategory.Default, ResultTally.Empty).games, 2)
          assertEquals(tallyB, Map(RatingCategory.Blitz -> ResultTally(wins = 0, draws = 0, losses = 2)))
          assertEquals(stranger, Map.empty[RatingCategory, ResultTally])
          assertEquals(
            stranger.getOrElse(RatingCategory.Default, ResultTally.Empty),
            ResultTally.Empty,
            "an absent key reads as the empty record — what the removed single-category query used to return"
          )
      }
    }

  test("a live game — its fixed roll included — survives a crash and plays on with the same commitment"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          // Life before the crash: create a game, seed both seats, and see the opening roll land.
          registry1 <- GameRegistry.create(store = db)
          created   <- registry1.create(Principal.Guest("w-uuid"), Principal.Guest("b-uuid"))
          (id, room1) = created.toOption.getOrElse(fail("game creation failed"))
          _ <- room1.submit(Seat.White, GameCommand.SubmitSeed("white-client-seed-0001"))
          _ <- room1.submit(Seat.Black, GameCommand.SubmitSeed("black-client-seed-0001"))
          // Poll the public state instead of subscribing: a slow subscriber can miss the live roll event.
          _ <- room1.snapshot
            .flatTap(ps => IO.sleep(20.millis).unlessA(ps.dicePending))
            .iterateUntil(_.dicePending)
            .timeoutTo(10.seconds, IO.raiseError(RuntimeException("no opening roll")))
          before  <- room1.snapshot
          commit1 <- room1.diceCommit
          tokens1 = room1.joinTokens

          // The "crash": a brand-new registry over the same store, as a fresh process would build on boot.
          registry2 <- GameRegistry.create(store = db)
          resumed   <- registry2.resume
          _ = assert(resumed >= 1, "at least our live game must be resumed")
          room2   <- registry2.get(id).map(_.getOrElse(fail("resumed game not found in the registry")))
          after   <- room2.snapshot
          commit2 <- room2.diceCommit

          // The game still ends properly: the resumed room accepts commands and reveals the SAME committed seed.
          // Deterministic handshake: the subscriber's first pulled event (the initial Snapshot) proves registration,
          // so the resign can't race the subscription and the terminal event can't be missed.
          ready <- Deferred[IO, Unit]
          ended = room2.subscribe
            .evalTap(_ => ready.complete(()).void)
            .collectFirst { case e: GameEvent.GameEnded => e }
            .compile
            .lastOrError
          resign = ready.get *> room2.submit(Seat.White, GameCommand.Resign)
          terminal <- (ended, resign)
            .parMapN((e, _) => e)
            .timeoutTo(5.seconds, IO.raiseError(RuntimeException("no end")))
        yield
          assertEquals(after.dfen, before.dfen, "the pending roll (DFEN dice pool) must survive the crash")
          assertEquals(commit2, commit1, "the dice commitment must survive the crash")
          assertEquals(room2.joinTokens, tokens1, "seat tokens must survive so players can reconnect")
          assertEquals(
            sha256Hex(terminal.seed.getOrElse(fail("expected a revealed seed"))),
            commit1,
            "the revealed seed still opens the pre-crash commitment"
          )
          assertEquals(
            terminal.clientSeeds,
            Some(ClientSeeds("white-client-seed-0001", "black-client-seed-0001")),
            "the submitted client seeds survive the crash into the reveal"
          )
      }
    }

  // ── the production wiring: starting a game through the real resolvers ───────────────────────
  //
  // Every other test in this file calls a store method directly, and every registry test in
  // `GameRegistrySuite` passes stub resolvers — so the seam `Main` actually builds (registry over
  // `PgGameStore.nicknamesByExternalId`/`settledRatingsByExternalId`) was covered by nothing at all.
  // A rated game against a bot 500'd in production while both halves stayed green, which is exactly
  // what an untested seam looks like: the query works, the registry works, and nobody ever ran the
  // two together against a real database.

  /** `Main.registryFor` itself, not a copy of it: a restated wiring would keep passing after production stopped
    * resolving anything. Anything asserted through this is asserted about the path a player's first request takes.
    */
  private def wiredRegistry(db: PgGameStore): IO[GameRegistry] = Main.registryFor(db, Some(db))

  /** Fails with the registry's own message rather than a bare `None`: creation returns errors as values, and the
    * message is the whole diagnostic when this seam breaks.
    */
  private def startedRoom(created: Either[String, (GameId, GameRoom)]): (GameId, GameRoom) =
    created.fold(error => fail(s"room creation failed: $error"), identity)

  test("an account starts a rated game against a bot through the wiring Main builds, both seats named and rated"):
    withContainers { pg =>
      store(pg).use { db =>
        val bot: Principal.Bot = Principal.Bot("wiring-suite", "rated-opponent")
        for
          _       <- db.register("wiring-suite", "rated-opponent", "hash-wiring-rated")
          account <- db.upsertOnLogin("google", "sub-wiring-1", None, IO.pure("WiringNick"))
          // Both sides converge out of provisional, or the leaderboard's visibility rule hides them here.
          stampId <- GameId.random
          _       <- db.applyRatingUpdate(
            stampId,
            seeded(RatedIdentity.of(bot), 1480.0, 70.0),
            seeded(RatedIdentity.User(account.id), 1620.0, 60.0)
          )
          registry <- wiredRegistry(db)
          created  <- registry.create(
            Principal.User(account.id),
            bot,
            timeControl = TimeControl.Fischer(300, 3),
            requestedRated = true
          )
          state <- startedRoom(created)._2.snapshot
        yield
          assertEquals(state.rated, Some(true), "an account against a registered bot is rated when asked")
          assertEquals(state.players.map(_.white.name), Some(Some("WiringNick")))
          assertEquals(state.players.map(_.white.rating), Some(Some(1620.0)))
          assertEquals(state.players.map(_.black.name), Some(Some("wiring-suite rated-opponent")))
          assertEquals(state.players.map(_.black.rating), Some(Some(1480.0)))
      }
    }

  test("a guest starts a casual game against the same bot — one seat resolves, the anonymous one stays bare"):
    withContainers { pg =>
      store(pg).use { db =>
        // The other half of the mixed bag: `userIds` is empty here, so only the bots branch of
        // `settledRatingsByExternalId` builds a query at all.
        val bot: Principal.Bot = Principal.Bot("wiring-suite", "casual-opponent")
        for
          _       <- db.register("wiring-suite", "casual-opponent", "hash-wiring-casual")
          stampId <- GameId.random
          _       <- db.applyRatingUpdate(
            stampId,
            seeded(RatedIdentity.of(bot), 1390.0, 65.0),
            // No such account: the stamp lands nowhere, by design.
            seeded(RatedIdentity.User(UUID.randomUUID().toString), 1500.0, 350.0)
          )
          registry <- wiredRegistry(db)
          // Rated is REQUESTED and must still be refused: a guest identity is free to reset (#279).
          created <- registry.create(
            Principal.Guest("wiring-guest-casual"),
            bot,
            timeControl = TimeControl.Fischer(300, 3),
            requestedRated = true
          )
          state <- startedRoom(created)._2.snapshot
        yield
          assertEquals(state.rated, Some(false), "an anonymous seat forces casual regardless of the request")
          assertEquals(state.players.map(_.white.name), Some(None))
          assertEquals(state.players.map(_.white.rating), Some(None), "a guest must never carry a stable number")
          assertEquals(state.players.map(_.black.rating), Some(Some(1390.0)), "the bot's own rating is unaffected")
      }
    }

  test("two guests start a game through the wiring: neither resolver may build a query for an empty id set"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          registry <- wiredRegistry(db)
          created  <- registry.create(
            Principal.Guest("wiring-guest-a"),
            Principal.Guest("wiring-guest-b"),
            timeControl = TimeControl.Fischer(300, 3)
          )
          state <- startedRoom(created)._2.snapshot
        yield
          assertEquals(state.rated, Some(false))
          assertEquals(state.players.map(_.white.name), Some(None))
          assertEquals(state.players.map(_.black.name), Some(None))
      }
    }

  test("resume re-resolves seat faces through the wiring, so a live rated game survives a deploy still named"):
    withContainers { pg =>
      store(pg).use { db =>
        val bot: Principal.Bot = Principal.Bot("wiring-suite", "resume-opponent")
        for
          _       <- db.register("wiring-suite", "resume-opponent", "hash-wiring-resume")
          account <- db.upsertOnLogin("google", "sub-wiring-2", None, IO.pure("ResumeNick"))
          stampId <- GameId.random
          _       <- db.applyRatingUpdate(
            stampId,
            seeded(RatedIdentity.of(bot), 1710.0, 55.0),
            seeded(RatedIdentity.User(account.id), 1805.0, 50.0)
          )
          registry <- wiredRegistry(db)
          created  <- registry.create(
            Principal.User(account.id),
            bot,
            timeControl = TimeControl.Fischer(300, 3),
            requestedRated = true
          )
          (id, _) = startedRoom(created)
          // The deploy: a fresh registry over the same store, resolving every live game's seats in one pass.
          resumedRegistry <- wiredRegistry(db)
          _               <- resumedRegistry.resume
          room            <- resumedRegistry.get(id).map(_.getOrElse(fail("the live game was not resumed")))
          state           <- room.snapshot
        yield
          assertEquals(state.rated, Some(true), "the rated flag is snapshot state, not re-derived")
          assertEquals(state.players.map(_.white.name), Some(Some("ResumeNick")))
          assertEquals(state.players.map(_.white.rating), Some(Some(1805.0)))
          assertEquals(state.players.map(_.black.rating), Some(Some(1710.0)))
      }
    }

  // ── queries no test used to run ────────────────────────────────────────────────────────────
  //
  // A coverage pass over this file turned up three statements that shipped without ever being executed by anything:
  // `byNickname` (behind the public profile route), `totalGamesFor` (that route's counter), and the outbox's
  // `markRetry`. A never-executed query is the same exposure the seat-face resolvers had — it is not type-checked, so
  // the first thing to run it is a user.

  test("byNickname finds an account case-insensitively, and answers None for a nickname nobody holds"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          created <- db.upsertOnLogin("google", "sub-by-nickname-1", None, IO.pure("ByNickHolder"))
          exact   <- db.byNickname("ByNickHolder")
          // lower(nickname) is the expression V14's unique index is built on — the lookup must use the same one.
          shouted <- db.byNickname("BYNICKHOLDER")
          missing <- db.byNickname("NobodyHoldsThis")
        yield
          assertEquals(exact.map(_.id), Some(created.id))
          assertEquals(shouted.map(_.id), Some(created.id))
          assertEquals(missing, None)
      }
    }

  test("totalGamesFor counts decided games from either seat, casual ones included"):
    withContainers { pg =>
      store(pg).use { db =>
        val player   = Principal.Guest("total-games-player")
        val opponent = Principal.Guest("total-games-opponent")
        for
          rated   <- GameId.random
          casual  <- GameId.random
          asBlack <- GameId.random
          _       <- db.save(rated, endedResultFixture(player, opponent, rated = true))
          // The one clause that separates this from `resultTallyFor`: a casual game still counts here.
          _        <- db.save(casual, endedResultFixture(player, opponent))
          _        <- db.save(asBlack, endedResultFixture(opponent, player))
          mine     <- db.totalGamesFor(player.externalId)
          stranger <- db.totalGamesFor(Principal.Guest("total-games-stranger").externalId)
        yield
          assertEquals(mine, 3, "both seats count, rated or not")
          assertEquals(stranger, 0)
      }
    }

  test("markRetry pushes an outbox row past its next attempt, and records the attempt count and error"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("outbox-retry-white")
        val black = Principal.Guest("outbox-retry-black")
        // No store method reads `last_error` back — it exists for an operator looking at a stuck queue, which is
        // exactly why a test has to look where they would.
        for
          id  <- GameId.random
          _   <- db.save(id, endedResultFixture(white, black))
          _   <- db.due(500).map(rows => assert(rows.exists(_.gameId.value == id.value), "the row starts out due"))
          _   <- db.markRetry(id, attempts = 3, retryIn = 1.hour, error = "boom")
          due <- db.due(500)
          // A short backoff proves the interval is applied rather than merely non-null: this one is already elapsed.
          _        <- db.markRetry(id, attempts = 4, retryIn = 0.seconds, error = "boom again")
          dueAgain <- db.due(500)
          recorded <- sql"SELECT last_error FROM play.outbox WHERE game_id = ${id.value}::uuid"
            .query[Option[String]]
            .unique
            .transact(xa)
        yield
          assert(!due.exists(_.gameId.value == id.value), "a retried row must not be handed out before its time")
          assertEquals(
            dueAgain.find(_.gameId.value == id.value).map(_.attempts),
            Some(4),
            "the attempt count is what the deliverer's parking decision reads"
          )
          assertEquals(recorded, Some("boom again"), "the newest failure reason replaces the previous one")
      }
    }

  // ── UserStore (#232) — every test uses its own subject/nickname namespace: the suite shares one
  // database across all tests (TestContainerForAll, no per-test reset). ──────────────────────────

  test("first login creates an account with a fresh nickname; repeat login reuses the same account"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val storedEmail =
          sql"""SELECT email FROM play.user_identities
                WHERE provider = 'google' AND subject = 'sub-login-1'"""
            .query[Option[String]]
            .unique
            .transact(xa)
        for
          first     <- db.upsertOnLogin("google", "sub-login-1", Some("first@example.com"), IO.pure("LoginNick1"))
          again     <- db.upsertOnLogin("google", "sub-login-1", None, IO.pure("NeverUsed2"))
          kept      <- storedEmail
          _         <- db.upsertOnLogin("google", "sub-login-1", Some("renamed@example.com"), IO.pure("NeverUsed3"))
          refreshed <- storedEmail
          loaded    <- db.userById(first.id)
        yield
          assertEquals(first.nickname, "LoginNick1")
          assertEquals(again.id, first.id)
          assertEquals(again.nickname, "LoginNick1", "a repeat login must not rename the account")
          assert(again.lastLoginAt.nonEmpty, "repeat login must stamp last_login_at")
          assertEquals(kept, Some("first@example.com"), "a login without an email must not blank the stored one")
          assertEquals(refreshed, Some("renamed@example.com"), "a login with a new email refreshes the stored one")
          assert(loaded.exists(_.isActive), "accounts start active")
      }
    }

  test("a nickname collision at first login retries with the next candidate, case-insensitively"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          counter <- IO.ref(0)
          gen = counter.getAndUpdate(_ + 1).map(i => if i == 0 then "collidenick" else "CollideSecond")
          _     <- db.upsertOnLogin("google", "sub-collide-a", None, IO.pure("CollideNick"))
          other <- db.upsertOnLogin("google", "sub-collide-b", None, gen)
        yield assertEquals(other.nickname, "CollideSecond", "'collidenick' collides with 'CollideNick'")
      }
    }

  test("nickname updates enforce case-insensitive uniqueness but allow changing your own casing"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          a       <- db.upsertOnLogin("google", "sub-nick-a", None, IO.pure("NickHolderA"))
          b       <- db.upsertOnLogin("google", "sub-nick-b", None, IO.pure("NickHolderB"))
          taken   <- db.updateNickname(b.id, "nickholdera")
          recased <- db.updateNickname(b.id, "NICKHOLDERB")
          renamed <- db.updateNickname(b.id, "NickHolderB2")
          missing <- db.updateNickname(UUID.randomUUID().toString, "GhostNick")
          loaded  <- db.userById(b.id)
          holderA <- db.userById(a.id)
        yield
          assertEquals(taken, NicknameUpdate.Taken)
          assertEquals(recased, NicknameUpdate.Updated, "re-casing your own nickname must not self-collide")
          assertEquals(renamed, NicknameUpdate.Updated)
          assertEquals(missing, NicknameUpdate.UserNotFound)
          assertEquals(loaded.map(_.nickname), Some("NickHolderB2"))
          assertEquals(holderA.map(_.nickname), Some("NickHolderA"), "the rejected rename left account A untouched")
      }
    }

  // ── Nickname rename guard (#275) ────────────────────────────────────────────

  /** Far enough in the past that no cooldown or hold window (90 days) can still be open, without depending on the wall
    * clock the way `Instant.now().minusSeconds(...)` would.
    */
  private val LongPastRename: Instant = Instant.parse("2020-01-01T00:00:00Z")

  private def backdateCooldown(xa: doobie.Transactor[IO], userId: String, at: Instant): IO[Unit] =
    sql"UPDATE play.users SET nickname_changed_at = $at WHERE id = $userId::uuid".update.run.transact(xa).void

  private def expireHold(xa: doobie.Transactor[IO], nickname: String, at: Instant): IO[Unit] =
    sql"UPDATE play.released_nicknames SET expires_at = $at WHERE nickname_lower = lower($nickname)".update.run
      .transact(xa)
      .void

  test("a rename is blocked by its own 90-day cooldown, and allowed again once it has passed"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          user    <- db.upsertOnLogin("google", "sub-cooldown", None, IO.pure("CooldownStart"))
          first   <- db.updateNickname(user.id, "CooldownFirst")
          blocked <- db.updateNickname(user.id, "CooldownSecond")
          _       <- backdateCooldown(xa, user.id, LongPastRename)
          allowed <- db.updateNickname(user.id, "CooldownSecond")
          loaded  <- db.userById(user.id)
        yield
          assertEquals(first, NicknameUpdate.Updated)
          blocked match
            case NicknameUpdate.CooldownActive(retryAfter) =>
              assert(retryAfter.toDays >= 89, s"expected roughly a 90-day wait, got $retryAfter")
            case other => fail(s"expected CooldownActive, got $other")
          assertEquals(allowed, NicknameUpdate.Updated, "backdating the cooldown clock must lift the block")
          assertEquals(loaded.map(_.nickname), Some("CooldownSecond"))
      }
    }

  test("two concurrent renames by the SAME account cannot both dodge the cooldown (#275)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          user <- db.upsertOnLogin("google", "sub-race-cooldown", None, IO.pure("RaceCooldownStart"))
          // Genuinely concurrent: without `SELECT ... FOR UPDATE` on the account's own row, both transactions can read
          // `nickname_changed_at` as unset before either writes, and both would land `Updated` — a self-inflicted
          // double rename inside one 90-day window. With the row lock, the second one blocks until the first commits
          // and then sees the cooldown the first one just started.
          results <- (
            db.updateNickname(user.id, "RaceCooldownA"),
            db.updateNickname(user.id, "RaceCooldownB")
          ).parTupled
          loaded <- db.userById(user.id)
        yield
          val outcomes = List(results._1, results._2)
          assertEquals(outcomes.count(_ == NicknameUpdate.Updated), 1, s"exactly one rename may land: $outcomes")
          assert(
            outcomes.exists(_.isInstanceOf[NicknameUpdate.CooldownActive]),
            s"the loser must see its own fresh cooldown, not a silent no-op: $outcomes"
          )
          assert(
            loaded.exists(u => u.nickname == "RaceCooldownA" || u.nickname == "RaceCooldownB"),
            s"the winning rename must have actually applied: $loaded"
          )
      }
    }

  test("a pure case change is not a rename: it never starts the cooldown or touches the hold table"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          user                 <- db.upsertOnLogin("google", "sub-case-only", None, IO.pure("CaseOnlyNick"))
          recased              <- db.updateNickname(user.id, "caseonlynick")
          heldRightAfterRecase <- sql"""SELECT count(*) FROM play.released_nicknames
                                        WHERE nickname_lower = 'caseonlynick'""".query[Long].unique.transact(xa)
          // Only NOW does a real rename happen — its own vacated name legitimately lands in the hold table, which
          // must not be confused with the recase above having done it.
          renamed <- db.updateNickname(user.id, "CaseOnlyNick2")
        yield
          assertEquals(recased, NicknameUpdate.Updated)
          assertEquals(
            heldRightAfterRecase,
            0L,
            "a case-only recase must not release its own current name into the hold table"
          )
          // If the case-only recase had touched the cooldown, this immediately-following real rename would answer
          // CooldownActive instead.
          assertEquals(renamed, NicknameUpdate.Updated, "a case-only recase must not spend the real rename's cooldown")
      }
    }

  test("a vacated nickname is held against everyone but its own previous owner"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          original <- db.upsertOnLogin("google", "sub-hold-orig", None, IO.pure("HoldOriginal"))
          rival    <- db.upsertOnLogin("google", "sub-hold-rival", None, IO.pure("HoldRival"))
          vacated  <- db.updateNickname(original.id, "HoldRenamed") // frees "HoldOriginal" into the hold table
          grabbed  <- db.updateNickname(rival.id, "HoldOriginal")   // a stranger must not get it
          // The hold must not block the PREVIOUS owner from reclaiming it — only their own cooldown can; lift that to
          // isolate the hold check.
          _         <- backdateCooldown(xa, original.id, LongPastRename)
          reclaimed <- db.updateNickname(original.id, "HoldOriginal")
        yield
          assertEquals(vacated, NicknameUpdate.Updated)
          assertEquals(grabbed, NicknameUpdate.Held, "'HoldOriginal' was vacated moments ago — a stranger must wait")
          assertEquals(reclaimed, NicknameUpdate.Updated, "the previous owner is never blocked by their own hold")
      }
    }

  test("a nickname hold expires after 90 days, freeing the name for anyone"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          original <- db.upsertOnLogin("google", "sub-hold-exp-orig", None, IO.pure("HoldExpOriginal"))
          rival    <- db.upsertOnLogin("google", "sub-hold-exp-rival", None, IO.pure("HoldExpRival"))
          _        <- db.updateNickname(original.id, "HoldExpRenamed") // frees "HoldExpOriginal"
          tooSoon  <- db.updateNickname(rival.id, "HoldExpOriginal")
          _        <- expireHold(xa, "HoldExpOriginal", LongPastRename)
          later    <- db.updateNickname(rival.id, "HoldExpOriginal")
        yield
          assertEquals(tooSoon, NicknameUpdate.Held)
          assertEquals(later, NicknameUpdate.Updated, "an expired hold must free the name for anyone")
      }
    }

  test("a nickname on hold cannot be handed to a fresh registration either — the generator retries"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          original <- db.upsertOnLogin("google", "sub-hold-reg-orig", None, IO.pure("HoldRegOriginal"))
          _        <- db.updateNickname(original.id, "HoldRegRenamed") // frees "HoldRegOriginal" into the hold table
          counter  <- IO.ref(0)
          gen = counter.getAndUpdate(_ + 1).map(i => if i == 0 then "HoldRegOriginal" else "HoldRegFallback")
          registered <- db.upsertOnLogin("google", "sub-hold-reg-new", None, gen)
        yield assertEquals(
          registered.nickname,
          "HoldRegFallback",
          "'HoldRegOriginal' is on hold for its previous owner — a fresh account must not be handed it"
        )
      }
    }

  test("a rename is appended to nickname_history, and the record survives the account's own deletion (#275)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          user <- db.upsertOnLogin("google", "sub-history", None, IO.pure("HistoryStart"))
          _    <- db.updateNickname(user.id, "HistoryNext")
          rows <- sql"""SELECT old_nickname, new_nickname FROM play.nickname_history
                        WHERE user_id = ${user.id}::uuid ORDER BY changed_at"""
            .query[(String, String)]
            .to[List]
            .transact(xa)
          _        <- db.deleteUser(user.id)
          afterDel <- sql"""SELECT old_nickname, new_nickname FROM play.nickname_history
                            WHERE user_id = ${user.id}::uuid ORDER BY changed_at"""
            .query[(String, String)]
            .to[List]
            .transact(xa)
        yield
          assertEquals(rows, List(("HistoryStart", "HistoryNext")))
          assertEquals(afterDel, rows, "the audit trail must outlive the account it describes")
      }
    }

  test("a guest id is claimed exactly once — idempotent for its owner, terminal for everyone else"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          owner   <- db.upsertOnLogin("google", "sub-guest-owner", None, IO.pure("GuestOwner"))
          rival   <- db.upsertOnLogin("google", "sub-guest-rival", None, IO.pure("GuestRival"))
          guestId <- IO(UUID.randomUUID().toString)
          first   <- db.linkGuest(owner.id, guestId)
          again   <- db.linkGuest(owner.id, guestId)
          stolen  <- db.linkGuest(rival.id, guestId)
          ghost   <- db.linkGuest(UUID.randomUUID().toString, UUID.randomUUID().toString)
          linked  <- db.guestsOf(owner.id)
        yield
          assertEquals(first, GuestLink.Linked)
          assertEquals(again, GuestLink.Linked, "re-claiming your own guest id is idempotent, not an error")
          assertEquals(stolen, GuestLink.ClaimedByAnother)
          assertEquals(ghost, GuestLink.UserNotFound)
          assertEquals(linked, List(guestId))
      }
    }

  test("deleting an account cascades identities and guest links but leaves game history untouched"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          user    <- db.upsertOnLogin("google", "sub-delete", None, IO.pure("DeletedNick"))
          guestId <- IO(UUID.randomUUID().toString)
          _       <- db.linkGuest(user.id, guestId)
          gameId  <- GameId.random
          _       <- db.save(
            gameId,
            endedResultFixture(Principal.User(user.id), Principal.Bot("delete-team", "delete-bot"), rated = true)
          )
          deleted <- db.deleteUser(user.id)
          gone    <- db.userById(user.id)
          // The same Google subject signing in again gets a FRESH account (the identity row cascaded)
          // that can reuse the freed nickname — deletion must not squat names forever.
          relogin <- db.upsertOnLogin("google", "sub-delete", None, IO.pure("DeletedNick"))
          tally   <- db.categoryTalliesFor(Principal.User(user.id).externalId)
          reclaim <- db.linkGuest(relogin.id, guestId)
          missing <- db.deleteUser(user.id)
        yield
          assert(deleted)
          assertEquals(gone, None)
          assertNotEquals(relogin.id, user.id, "deletion severs the subject: re-login mints a new account")
          assertEquals(relogin.nickname, "DeletedNick")
          assertEquals(
            tally,
            Map(RatingCategory.Blitz -> ResultTally(1, 0, 0)),
            "game_results keeps the orphaned user: external id"
          )
          assertEquals(reclaim, GuestLink.Linked, "the guest link cascaded, so the id is claimable again")
          assert(!missing, "a second delete finds nothing")
      }
    }

  // ── Merged history over several identities (#236) ────────────────────────────

  test("a merged history reads games from every one of the requester's identities, self-play aside"):
    withContainers { pg =>
      store(pg).use { db =>
        // One account plus a guest id it claimed: three games, one of them account-vs-own-guest (self-play once
        // merged, so it has no opponent to aggregate and must not appear in the opponents breakdown).
        val account = Principal.User("0197f0a0-0000-7000-8000-00000000d236")
        val claimed = Principal.Guest("0197f0a0-0000-7000-8000-00000000d237")
        val bot     = Principal.Bot("merge-team", "merge-bot")
        val ids     = List(account.externalId, claimed.externalId)
        for
          asAccount   <- GameId.random
          asGuest     <- GameId.random
          selfPlay    <- GameId.random
          _           <- db.save(asAccount, endedResultFixture(account, bot))
          _           <- db.save(asGuest, endedResultFixture(bot, claimed))
          _           <- db.save(selfPlay, endedResultFixture(account, claimed))
          merged      <- db.playerGamesPage(ids, None, None, None, limit = 100)
          accountOnly <- db.playerGamesPage(List(account.externalId), None, None, None, limit = 100)
          opponents   <- db.opponentsFor(ids)
        yield
          // Sorted lists, not sets: the claim this test makes is "ONCE each", and a set cannot tell one row from two.
          // It could not, which is how the self-play row came to be returned twice (see the dedicated test below).
          assertEquals(
            merged.games.map(_.gameId.value).sorted,
            List(asAccount.value, asGuest.value, selfPlay.value).sorted,
            "both identities' games appear once each, self-play included (it is still my game)"
          )
          assertEquals(
            accountOnly.games.map(_.gameId.value).sorted,
            List(asAccount.value, selfPlay.value).sorted,
            "without the claim, the guest's game is not mine"
          )
          assertEquals(
            opponents.map(_.botExternalId),
            List(Some(bot.externalId)),
            "the bot is the only opponent — the self-play row has none once both seats are me"
          )
          assertEquals(opponents.map(_.games), List(2))
      }
    }

  test("a game with both seats mine is returned once, under every filter and in the page's own accounting"):
    withContainers { pg =>
      store(pg).use { db =>
        // Both branches of the page query match this row, because both of its ids are the requester's. A
        // friend-by-link game is the same shape in production: it records its creator on both sides.
        val account = Principal.User("0197f0a0-0000-7000-8000-00000000d294")
        val claimed = Principal.Guest("0197f0a0-0000-7000-8000-00000000d295")
        val ids     = List(account.externalId, claimed.externalId)
        for
          decided <- GameId.random
          drawn   <- GameId.random
          _       <- db.save(decided, endedResultFixture(account, claimed))
          // A draw is its own POV inverse (`-0 = 0`), so it is the one result filter both branches still match.
          _     <- db.save(drawn, endedResultFixture(account, claimed, result = GameResult.Draw))
          page  <- db.playerGamesPage(ids, None, None, None, limit = 100)
          draws <- db.playerGamesPage(ids, None, None, Some(PovResultFilter.Draw), limit = 100)
          // Neither seat is a bot, so this filter keeps the row on both branches too.
          humans   <- db.playerGamesPage(ids, None, Some(OpponentFilter.HumanOnly), None, limit = 100)
          firstOne <- db.playerGamesPage(ids, None, None, None, limit = 2)
        yield
          assertEquals(page.games.count(_.gameId.value == decided.value), 1, "one game, one row")
          assertEquals(draws.games.count(_.gameId.value == drawn.value), 1, "a drawn self-play game is not two draws")
          assertEquals(humans.games.count(_.gameId.value == drawn.value), 1)
          // The duplicate would have eaten a slot AND inflated the row count the `hasMore` decision reads, so a page
          // holding every game there is would still have claimed there were more.
          assertEquals(firstOne.games.map(_.gameId.value).sorted, List(decided.value, drawn.value).sorted)
          assertEquals(firstOne.hasMore, false, "both games fit in the page — there is nothing more to fetch")
      }
    }

  // ── Rating state for accounts ──────────────────────────────────────────────

  test("a fresh account starts on the same Glicko seeds as a fresh bot"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          user       <- db.upsertOnLogin("google", "sub-rating-seed", None, IO.pure("SeedNick"))
          userRating <- db.ratingOf(user.id)
          _          <- db.register("rating-team", "seed-bot", "hash-rating-seed")
          botRating  <- db.ratingOf("rating-team", "seed-bot")
          missing    <- db.ratingOf(UUID.randomUUID().toString)
        yield
          assertEquals(userRating, Some(UserRating.initial), "1500/350/0.06 — the same seeds V4 gave bots")
          assertEquals(
            userRating.map(_.glicko),
            botRating.map(_.glicko),
            "one shared scale: an account and a bot must start from the identical pure-math state"
          )
          assertEquals(missing, None)
      }
    }

  // ── Admin actions and their audit (#273) ───────────────────────────────────

  test("the admin inventory includes a provisional closed bot and writes no audit row"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val owner = "user:" + UUID.randomUUID().toString
        for
          registered <- db.register("admin-inventory", "invisible", "hash-admin-inventory", owner = Some(owner))
          inventory  <- db.adminBots
          audits     <- sql"""SELECT count(*) FROM play.admin_actions
                             WHERE team = 'admin-inventory' AND name = 'invisible'""".query[Long].unique.transact(xa)
        yield
          assert(registered)
          val hidden = inventory
            .find(bot => bot.team == "admin-inventory" && bot.name == "invisible")
            .getOrElse(fail("the registered bot must be visible to the administrator"))
          assertEquals(hidden.onLadder, false)
          assertEquals(hidden.openToHumans, false)
          assert(hidden.rd > Glicko2.ProvisionalDeviationThreshold, "a fresh bot is not on the public leaderboard")
          assertEquals(hidden.description, None)
          assertEquals(hidden.owned, true, "the read reveals self-service availability, not the owner's external id")
          assertEquals(audits, 0L, "an inventory read is not an admin action")
      }
    }

  /** The inventory is unpaginated, so its ORDER BY is the only structure an operator gets over the whole registry — and
    * nothing else pins it: the route suite asserts a stub's order, and the read above is order-independent.
    *
    * Filtered to this test's own team, deliberately: this suite shares one database across tests without resetting it,
    * so any assertion over the WHOLE list would depend on what its neighbours registered.
    */
  test("the admin inventory sorts by rating first, then team and name"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          _      <- db.register("admin-order", "bravo", "hash-admin-order-bravo")
          _      <- db.register("admin-order", "alpha", "hash-admin-order-alpha")
          _      <- db.register("admin-order", "strongest", "hash-admin-order-strong")
          fakeId <- GameId.random
          // The seeds are identical (1500 for all three), so the rating leg needs one forged value. It goes into
          // `bot_ratings` now (#280): the listing reads the default category, and a bot with no row there reads as
          // the fresh 1500 — which is exactly what the other two are.
          _ <- db.applyRatingUpdate(
            fakeId,
            seeded(RatedIdentity.Bot("admin-order", "strongest"), 1900.0, 60.0),
            seeded(RatedIdentity.Bot("admin-order", "alpha"), 1500.0, 350.0)
          )
          inventory <- db.adminBots
        yield
          val mine = inventory.filter(_.team == "admin-order").map(_.name)
          assertEquals(
            mine,
            List("strongest", "alpha", "bravo"),
            "rating descending first; the two tied at 1500 fall back to name"
          )
      }
    }

  test("an admin ladder change flips the flag, keeps the owner, and leaves one audit row per action"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val admin = UUID.randomUUID().toString
        val owner = "user:" + UUID.randomUUID().toString
        for
          _      <- db.register("admin-aud", "ladder-bot", "hash-admin-ladder")
          _      <- db.claimOwner("admin-aud", "ladder-bot", owner)
          joined <- db.adminSetOnLadder(admin, "admin-aud", "ladder-bot", onLadder = true)
          left   <- db.adminSetOnLadder(admin, "admin-aud", "ladder-bot", onLadder = false)
          rows   <- sql"""SELECT admin_user_id::text, action, detail FROM play.admin_actions
                          WHERE team = 'admin-aud' AND name = 'ladder-bot' ORDER BY id"""
            .query[(String, String, Option[String])]
            .to[List]
            .transact(xa)
        yield
          assertEquals(joined.map(_.onLadder), Some(true))
          assertEquals(left.map(_.onLadder), Some(false))
          assertEquals(
            left.flatMap(_.ownerExternalId),
            Some(owner),
            "an admin action must never move ownership — or the anti-farming rule would start applying to the admin"
          )
          assertEquals(rows, List((admin, "ladder.join", None), (admin, "ladder.leave", None)))
      }
    }

  test("an admin action that finds no bot answers None and writes no audit row"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val admin = UUID.randomUUID().toString
        for
          missing <- db.adminSetOnLadder(admin, "admin-aud", "ghost", onLadder = true)
          count   <- sql"""SELECT count(*) FROM play.admin_actions
                           WHERE team = 'admin-aud' AND name = 'ghost'"""
            .query[Long]
            .unique
            .transact(xa)
        yield
          assertEquals(missing, None)
          assertEquals(count, 0L, "the table records what happened, not what was attempted")
      }
    }

  test("admin catalog writes: describe edits the card without touching the open flag, and every write is audited"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val admin = UUID.randomUUID().toString
        for
          _       <- db.register("admin-aud", "catalog-bot", "hash-admin-catalog")
          opened  <- db.adminOpenToHumans(admin, "admin-aud", "catalog-bot", Some("first blurb"))
          renamed <- db.adminSetDescription(admin, "admin-aud", "catalog-bot", Some("second blurb"))
          closed  <- db.adminCloseToHumans(admin, "admin-aud", "catalog-bot")
          parked  <- db.adminSetDescription(admin, "admin-aud", "catalog-bot", Some("retired — token lost"))
          rows    <- sql"""SELECT admin_user_id::text, action, detail FROM play.admin_actions
                           WHERE team = 'admin-aud' AND name = 'catalog-bot' ORDER BY id"""
            .query[(String, String, Option[String])]
            .to[List]
            .transact(xa)
        yield
          assertEquals(opened, Some(BotCatalogState(openToHumans = true, Some("first blurb"))))
          assertEquals(
            renamed,
            Some(BotCatalogState(openToHumans = true, Some("second blurb"))),
            "describe must leave the open flag exactly where it was"
          )
          assertEquals(closed, Some(BotCatalogState(openToHumans = false, Some("second blurb"))))
          assertEquals(
            parked,
            Some(BotCatalogState(openToHumans = false, Some("retired — token lost"))),
            "the #273 shape: relabel a bot that is already closed, without reopening it"
          )
          assertEquals(
            rows,
            List(
              (admin, "catalog.open", Some("first blurb")),
              (admin, "catalog.describe", Some("second blurb")),
              (admin, "catalog.close", None),
              (admin, "catalog.describe", Some("retired — token lost"))
            )
          )
      }
    }

  test("an admin token rotation swaps the credential, keeps the owner, and its audit row carries no token material"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val admin = UUID.randomUUID().toString
        val owner = "user:" + UUID.randomUUID().toString
        for
          _       <- db.register("admin-aud", "rotate-bot", "hash-admin-rotate-old")
          _       <- db.claimOwner("admin-aud", "rotate-bot", owner)
          rotated <- db.adminRotate(admin, "admin-aud", "rotate-bot", "hash-admin-rotate-new")
          stale   <- db.authenticate("hash-admin-rotate-old")
          fresh   <- db.authenticate("hash-admin-rotate-new")
          rating  <- db.ratingOf("admin-aud", "rotate-bot")
          ghost   <- db.adminRotate(admin, "admin-aud", "ghost-rotate", "hash-admin-rotate-ghost")
          rows    <- sql"""SELECT admin_user_id::text, action, detail FROM play.admin_actions
                           WHERE team = 'admin-aud' AND name = 'rotate-bot' ORDER BY id"""
            .query[(String, String, Option[String])]
            .to[List]
            .transact(xa)
        yield
          assert(rotated)
          assertEquals(stale, None, "the old token stops authenticating immediately")
          assertEquals(fresh.map(_.name), Some("rotate-bot"))
          assertEquals(rating.flatMap(_.ownerExternalId), Some(owner), "rotation must not move ownership")
          assert(!ghost, "no such bot — nothing rotated")
          assertEquals(rows, List((admin, "token.rotate", None)), "never the token, never its hash")
      }
    }

  test("the player leaderboard hides provisional and inactive accounts, and counts rated games only"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          converged     <- db.upsertOnLogin("google", "sub-board-conv", None, IO.pure("BoardConverged"))
          provisional   <- db.upsertOnLogin("google", "sub-board-prov", None, IO.pure("BoardProvisional"))
          opponent      <- db.upsertOnLogin("google", "sub-board-opp", None, IO.pure("BoardOpponent"))
          ratedId       <- GameId.random
          casualId      <- GameId.random
          blockedGameId <- GameId.random
          _             <- db.save(
            ratedId,
            endedResultFixture(Principal.User(converged.id), Principal.User(opponent.id), rated = true)
          )
          _ <- db.save(
            casualId,
            endedResultFixture(Principal.User(converged.id), Principal.User(opponent.id), rated = false)
          )
          // Only a played game shrinks the deviation, so drive it through the same write the batch uses.
          _ <- db.applyRatingUpdate(
            gameId = ratedId,
            white = seeded(RatedIdentity.User(converged.id), 1650.0, 90.0),
            black = seeded(RatedIdentity.User(opponent.id), 1450.0, 95.0)
          )
          // A CONVERGED but deactivated account: the `is_active` half of the filter would otherwise be untested, and
          // the public board relies on it to keep a blocked player off the list.
          blocked <- db.upsertOnLogin("google", "sub-board-blocked", None, IO.pure("BoardBlocked"))
          _       <- db.applyRatingUpdate(
            gameId = blockedGameId,
            white = seeded(RatedIdentity.User(blocked.id), 1700.0, 85.0),
            black = seeded(RatedIdentity.User(opponent.id), 1450.0, 95.0)
          )
          _     <- sql"UPDATE play.users SET is_active = false WHERE id = ${blocked.id}::uuid".update.run.transact(xa)
          board <- db.playerLeaderboard(RatingCategory.Default, maxRd = Glicko2.ProvisionalDeviationThreshold)
        yield
          val mine   = Set("BoardConverged", "BoardProvisional", "BoardOpponent", "BoardBlocked")
          val listed = board.filter(row => mine.contains(row.nickname))
          assertEquals(
            listed.map(_.nickname),
            List("BoardConverged", "BoardOpponent"),
            s"provisional (rd 350) and deactivated accounts must both be absent: ${board.map(_.nickname)}"
          )
          assertEquals(listed.map(_.rating), List(1650.0, 1450.0), "best conservative estimate first")
          assertEquals(
            listed.find(_.nickname == "BoardConverged").map(_.tally),
            Some(ResultTally(1, 0, 0)),
            "the casual game must not appear in the rated W-D-L"
          )
          assertEquals(provisional.nickname, "BoardProvisional")
      }
    }

  test("ownership round-trips: set on register, claimed idempotently, refused for another account, released"):
    withContainers { pg =>
      store(pg).use { db =>
        val alice = "user:0197f0a0-0000-7000-8000-000000000253"
        val bob   = "user:0197f0a0-0000-7000-8000-000000000254"
        for
          _         <- db.register("own-team", "born-owned", "hash-own-born", owner = Some(alice))
          born      <- db.ratingOf("own-team", "born-owned")
          _         <- db.register("own-team", "adopted", "hash-own-adopted")
          unowned   <- db.ratingOf("own-team", "adopted")
          claimed   <- db.claimOwner("own-team", "adopted", alice)
          again     <- db.claimOwner("own-team", "adopted", alice)
          contested <- db.claimOwner("own-team", "adopted", bob)
          ghost     <- db.claimOwner("own-team", "no-such-bot", alice)
          notYours  <- db.releaseOwner("own-team", "adopted", bob)
          mine      <- db.botsOwnedBy(alice)
          released  <- db.releaseOwner("own-team", "adopted", alice)
          after     <- db.botsOwnedBy(alice)
          reclaimed <- db.claimOwner("own-team", "adopted", bob)
        yield
          assertEquals(born.flatMap(_.ownerExternalId), Some(alice), "registering while signed in needs no claim")
          assertEquals(unowned.flatMap(_.ownerExternalId), None, "unowned stays a first-class state")
          assertEquals(claimed, OwnerClaim.Claimed)
          assertEquals(again, OwnerClaim.Claimed, "a retry by the owner is idempotent")
          assertEquals(contested, OwnerClaim.ClaimedByAnother, "possession of the token is not a takeover")
          assertEquals(ghost, OwnerClaim.NotRegistered)
          assert(!notYours, "a wrong guess must not un-own someone else's bot")
          assertEquals(mine.map(_.name).sorted, List("adopted", "born-owned"))
          assert(released)
          assertEquals(after.map(_.name), List("born-owned"), "the released bot leaves the owner's list")
          assertEquals(reclaimed, OwnerClaim.Claimed, "release is what makes a transfer possible")
      }
    }

  /** One participant's rating forced to a chosen value through the production write path — the only way to set one
    * outside the batch. Both scales get it (#280): the shared columns because they are still written, and
    * `RatingCategory.Default` because that is what every reader in this suite now looks at.
    */
  private def seeded(identity: RatedIdentity, rating: Double, rd: Double): RatingUpdate =
    val after = Glicko(rating, rd, 0.05)
    RatingUpdate(identity, Glicko.Initial, after, Some(CategoryMove(RatingCategory.Default, Glicko.Initial, after)))

  private def sha256Hex(hexSeed: String): String =
    val bytes = hexSeed.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toArray
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  test("a category write for a participant that vanished mid-tick is a no-op, not a poisoned queue head (#280)"):
    withContainers { pg =>
      store(pg).use { db =>
        // The race `DELETE /auth/me` opens: the batch resolves a participant in one transaction and writes in the
        // next. `updateGlicko`'s UPDATE has always degraded to zero rows here; the per-category INSERT must too, or
        // the foreign key aborts the transaction, the game is never stamped, and — `drainQueue` having no per-row
        // recovery — it fails at the head of the queue on every tick from then on, for an account never coming back.
        val ghostId            = "0197f0a0-0000-7000-8000-0000000002b7"
        val bot: Principal.Bot = Principal.Bot("gone-team", "gone-bot")
        val move               = (before: Double, after: Double) =>
          Some(CategoryMove(RatingCategory.Blitz, Glicko(before, 200, 0.06), Glicko(after, 190, 0.06)))
        for
          _  <- db.register(bot.team, bot.name, "hash-gone-bot")
          id <- GameId.random
          _  <- db.save(id, endedResultFixture(Principal.User(ghostId), bot, rated = true))
          _  <- db.applyRatingUpdate(
            id,
            RatingUpdate(RatedIdentity.User(ghostId), Glicko.Initial, Glicko.Initial, move(1500, 1520)),
            RatingUpdate(RatedIdentity.of(bot), Glicko.Initial, Glicko.Initial, move(1500, 1480))
          )
          ghost    <- db.categoryRatingOf(RatedIdentity.User(ghostId), RatingCategory.Blitz)
          survivor <- db.categoryRatingOf(RatedIdentity.of(bot), RatingCategory.Blitz)
          change   <- db.ratingChangeFor(id)
        yield
          assertEquals(ghost, Glicko.Initial, "no row is written for an identity that has no parent row")
          assertEquals(survivor.rating, 1480.0, "the other seat's write still lands")
          assert(change.exists(_.applied), "and the game is stamped, so the queue moves on")
      }
    }

  // ── V21: per-category ratings (#280 phase 1, #329) ────────────────────────

  /** Lay the real migration chain down in a scratch schema on this suite's own container, optionally stopping at a
    * version.
    *
    * V21's backfill runs exactly once, at boot, over history that already exists — which the suite's own database can
    * never reproduce, having been migrated before a single game was played. Staging it here is what makes the shipped
    * SQL testable at all: migrate to V20, plant a history, then apply V21 to it. A second schema rather than a second
    * container because every migration is schema-unqualified, so Flyway can put the whole chain beside the suite's own
    * for the price of a `CREATE SCHEMA`.
    */
  private def migrateInto(pg: PostgreSQLContainer, schema: String, upTo: Option[String] = None): IO[Unit] =
    IO.blocking {
      val configured = Flyway
        .configure()
        .dataSource(pg.jdbcUrl, pg.username, pg.password)
        .schemas(schema)
        .createSchemas(true)
      upTo.fold(configured)(version => configured.target(MigrationVersion.fromVersion(version))).load().migrate()
      ()
    }

  test("the board and its record are scoped to one category — a Rapid game never counts on the Blitz board (#280)"):
    withContainers { pg =>
      store(pg).use { db =>
        val team                                                                   = "cat-board"
        val fast: Principal.Bot                                                    = Principal.Bot(team, "fast")
        val slow: Principal.Bot                                                    = Principal.Bot(team, "slow")
        def game(white: Principal.Bot, black: Principal.Bot, control: TimeControl) =
          GameId.random.flatMap(id =>
            db.save(id, endedResultFixture(white, black, rated = true).copy(timeControl = control))
          )
        for
          _     <- db.register(team, "fast", "hash-cat-board-fast")
          _     <- db.register(team, "slow", "hash-cat-board-slow")
          stamp <- GameId.random
          // Converged on BOTH scales, so neither board hides them and the difference below is the tally, not the filter.
          _ <- db.applyRatingUpdate(
            stamp,
            seeded(RatedIdentity.of(fast), 1700.0, 60.0),
            seeded(RatedIdentity.of(slow), 1400.0, 65.0)
          )
          stamp2 <- GameId.random
          rapidW = RatingUpdate(
            RatedIdentity.of(fast),
            Glicko.Initial,
            Glicko.Initial,
            Some(CategoryMove(RatingCategory.Rapid, Glicko.Initial, Glicko(1660.0, 70.0, 0.05)))
          )
          rapidB = RatingUpdate(
            RatedIdentity.of(slow),
            Glicko.Initial,
            Glicko.Initial,
            Some(CategoryMove(RatingCategory.Rapid, Glicko.Initial, Glicko(1440.0, 75.0, 0.05)))
          )
          _ <- db.applyRatingUpdate(stamp2, rapidW, rapidB)
          // Two Blitz wins for `fast`, one Rapid win for `slow` — three rated games, two different scales.
          _     <- game(fast, slow, TimeControl.Fischer(300, 3))
          _     <- game(fast, slow, TimeControl.SuddenDeath(300))
          _     <- game(slow, fast, TimeControl.Fischer(600, 10))
          blitz <- db.leaderboard(RatingCategory.Blitz, maxRd = 110.0).map(_.filter(_.team == team))
          rapid <- db.leaderboard(RatingCategory.Rapid, maxRd = 110.0).map(_.filter(_.team == team))
          split <- db.categoryTalliesFor(fast.externalId)
        yield
          assertEquals(blitz.map(_.name), List("fast", "slow"))
          assertEquals(
            blitz.find(_.name == "fast").map(_.tally),
            Some(ResultTally(wins = 2, draws = 0, losses = 0)),
            "the Blitz board must not see the Rapid loss"
          )
          assertEquals(
            rapid.find(_.name == "fast").map(_.tally),
            Some(ResultTally(wins = 0, draws = 0, losses = 1)),
            "and the Rapid board must not see the two Blitz wins"
          )
          assertEquals(blitz.find(_.name == "fast").map(_.rating), Some(1700.0), "each board reads its own scale")
          assertEquals(rapid.find(_.name == "fast").map(_.rating), Some(1660.0))
          assertEquals(
            split,
            Map(RatingCategory.Blitz -> ResultTally(2, 0, 0), RatingCategory.Rapid -> ResultTally(0, 0, 1)),
            "and the profile's own split agrees with both boards"
          )
      }
    }

  test("V21's rating_category is the same function as RatingCategory.ofStored, control for control"):
    withContainers { pg =>
      rawXa(pg).use { xa =>
        val schema = "mig_v21_fn"
        // Every control the platform can store, both boundaries from both sides, and the four ways a value can fail
        // to parse. The Scala side is asserted independently in `RatingCategorySuite`; what is under test here is
        // only that the SQL translation agrees with it — the one drift no compiler can catch.
        val controls = List(
          "Fischer(60,1)",
          "Fischer(180,2)",
          "Fischer(180,3)",
          "Fischer(300,3)",
          "Fischer(300,5)",
          "Fischer(600,5)",
          "Fischer(600,10)",
          "Fischer(900,10)",
          "Fischer(600,0)",
          "SuddenDeath(179)",
          "SuddenDeath(180)",
          "SuddenDeath(479)",
          "SuddenDeath(480)",
          "SuddenDeath(300)",
          "SuddenDeath(600)",
          // Estimates past Int.MaxValue: nothing validates the seconds a creation request asks for, and this is the
          // exact shape where an Int-arithmetic Scala side would silently disagree with the bigint SQL one.
          "Fischer(0,613566757)",
          "SuddenDeath(2147483647)",
          "Unlimited",
          "PerMove(30)",
          "Fischer(300, 3)",
          "Fischer(300)",
          "Blitz",
          ""
        )
        def sqlCategory(raw: String) =
          (fr"SELECT" ++ Fragment.const0(s"$schema.rating_category") ++ fr"($raw)")
            .query[Option[String]]
            .unique
        for
          _         <- migrateInto(pg, schema)
          fromSql   <- controls.traverse(raw => sqlCategory(raw).transact(xa))
          fromScala <- IO.pure(controls.map(RatingCategory.ofStored(_).map(_.wireName)))
        yield assertEquals(controls.zip(fromSql), controls.zip(fromScala), "SQL and Scala must bucket identically")
      }
    }

  test("generated category column agrees with Scala on every control, including uncategorised"):
    withContainers { pg =>
      rawXa(pg).use { xa =>
        val schema = "mig_category_check"
        val userId = "0197f0a0-0000-7000-8000-0000000002a1"
        def game(white: String, black: String, control: String, rated: Boolean, applied: Boolean) =
          sql"""INSERT INTO """ ++ Fragment.const(s"$schema.game_results") ++
            fr"""(game_id, white_external_id, black_external_id, result, termination, rated, time_control,
                  server_seed, rating_applied_at)
                 VALUES (gen_random_uuid(), $white, $black, 1, 'resign', $rated, $control, 'seed',
                         ${Option.when(applied)(Instant.EPOCH)})"""
        val bot   = (name: String) => s"bot:team:v21:$name"
        val human = s"user:$userId"
        val plant =
          for
            _ <- game(bot("mixed"), human, "Fischer(300,3)", rated = true, applied = true).update.run
            _ <- game(human, bot("mixed"), "SuddenDeath(300)", rated = true, applied = true).update.run
            _ <- game(bot("mixed"), human, "Fischer(600,10)", rated = true, applied = true).update.run
            _ <- game(bot("pending"), bot("mixed"), "Fischer(300,3)", rated = true, applied = false).update.run
            _ <- game(bot("pending"), bot("mixed"), "Fischer(300,3)", rated = false, applied = true).update.run
            _ <- game(bot("clockless"), bot("mixed"), "Unlimited", rated = true, applied = true).update.run
          yield ()
        val storedCategories =
          (fr"SELECT time_control, category FROM" ++ Fragment.const(s"$schema.game_results") ++
            fr"GROUP BY 1, 2 ORDER BY 1").query[(String, Option[String])].to[List]
        for
          _      <- migrateInto(pg, schema)
          _      <- plant.transact(xa)
          stored <- storedCategories.transact(xa)
        yield
          assertEquals(
            stored,
            stored.map((control, _) => control -> RatingCategory.ofStored(control).map(_.wireName)),
            "generated column must agree with Scala on every control, including the uncategorised one"
          )
          assert(
            stored.exists((_, category) => category.isEmpty),
            "the fixture must include an uncategorised control, or the NULL branch goes unchecked"
          )
      }
    }
