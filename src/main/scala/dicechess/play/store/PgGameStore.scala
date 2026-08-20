package dicechess.play.store

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.effect.std.Console
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import doobie.util.fragments
import dicechess.play.core.{GameId, GameOver, GameStatus, Principal, RatingCategory, Seat, Termination}
import dicechess.play.ingest.PlaysiteIngest
import dicechess.play.rating.{Glicko, Glicko2}
import io.circe.Json
import io.circe.syntax.*
import org.flywaydb.core.Flyway

import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** Postgres-backed store. Deployed against a **dedicated `play` database** (analytics is an aggregator with its own
  * lifecycle; play state is operational and restores independently) — pointed at by `PLAY_DB_URL`, with Flyway owning
  * the `play` schema inside it. play-api reaches analytics only as an ordinary writer via `POST /api/games`, never
  * through the database.
  *
  * Every round trip is bounded by a timeout: the caller treats store trouble as a degradation, and a *hung* query —
  * unlike a failed one — would otherwise stall the game's writer fiber in a way `handleErrorWith` can't catch.
  */
final class PgGameStore private (xa: Transactor[IO])
    extends GameStore
    with OutboxStore
    with ClientReportStore
    with BotStore
    with AdminBotStore
    with GameResultsStore
    with GameArchiveStore
    with RetentionStore
    with RatingStore
    with LeaderboardStore
    with BotCatalogStore
    with WebhookStore
    with WebhookStatsStore
    with UserStore:
  import PgGameStore.{
    BootTimeout,
    ForeignKeyViolation,
    NicknameHold,
    NicknameRetries,
    RenameCooldown,
    SaveTimeout,
    UniqueViolation
  }

  /** Upsert the snapshot — and, in the SAME transaction, enqueue the finished game's analytics payload and write its
    * `game_results` and `game_archive` (#177) rows: the snapshot write and all three handoffs are atomic, so a crash
    * can't record a finished game that analytics, the ladder/rating projection, or the durable history record never
    * hears about.
    */
  def save(id: GameId, snapshot: GameSnapshot): IO[Unit] =
    val status = if snapshot.ended then "ended" else "active"
    val upsert =
      sql"""INSERT INTO play.games (id, status, snapshot)
            VALUES (${id.value}::uuid, $status, ${snapshot.asJson})
            ON CONFLICT (id) DO UPDATE
            SET status = EXCLUDED.status, snapshot = EXCLUDED.snapshot, updated_at = now()""".update.run
    val enqueue = PlaysiteIngest.payload(id, snapshot) match
      case None          => ().pure[ConnectionIO]
      case Some(payload) =>
        sql"""INSERT INTO play.outbox (game_id, payload)
              VALUES (${id.value}::uuid, $payload)
              ON CONFLICT (game_id) DO NOTHING""".update.run.void
    val finishedGame = PgGameStore.finishedGameOf(snapshot)
    // finishedGameOf returning None while the snapshot IS ended means players was missing a seat — a malformed
    // snapshot, not the normal "still active" case. The games-table write still goes through (it's the more
    // foundational record), but a gap here must be visible, not silent, same as loadActive's corrupt-row logging.
    val warnIfMalformed =
      Console[IO]
        .errorln(
          s"[play][store] ended game ${id.value} produced no game_results row: players=${snapshot.players.keySet}"
        )
        .whenA(snapshot.ended && finishedGame.isEmpty)
    val recordResult = finishedGame match
      case None     => ().pure[ConnectionIO]
      case Some(fg) =>
        sql"""INSERT INTO play.game_results
                (game_id, white_external_id, black_external_id, result, termination, rated, time_control,
                 server_seed, ladder)
              VALUES (${id.value}::uuid, ${fg.whiteExternalId}, ${fg.blackExternalId}, ${fg.result},
                      ${fg.termination}, ${fg.rated}, ${fg.timeControl}, ${fg.serverSeed}, ${fg.ladder})
              ON CONFLICT (game_id) DO NOTHING""".update.run.void
    val archive = GameArchive.payload(snapshot) match
      case None          => ().pure[ConnectionIO]
      case Some(payload) =>
        sql"""INSERT INTO play.game_archive (game_id, payload)
              VALUES (${id.value}::uuid, $payload)
              ON CONFLICT (game_id) DO NOTHING""".update.run.void
    warnIfMalformed *> (upsert *> enqueue *> recordResult *> archive).transact(xa).timeout(SaveTimeout)

  // ── OutboxStore ─────────────────────────────────────────────────────────────

  def due(limit: Int): IO[List[OutboxRow]] =
    sql"""SELECT game_id::text, payload, attempts FROM play.outbox
          WHERE delivered_at IS NULL AND NOT failed_permanently AND next_attempt_at <= now()
          ORDER BY next_attempt_at
          LIMIT ${limit.toLong}"""
      .query[(String, Json, Int)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map((id, payload, attempts) => OutboxRow(GameId(id), payload, attempts)))

  def markDelivered(gameId: GameId): IO[Unit] =
    sql"""UPDATE play.outbox SET delivered_at = now(), last_error = NULL
          WHERE game_id = ${gameId.value}::uuid""".update.run.transact(xa).void.timeout(SaveTimeout)

  def markRetry(gameId: GameId, attempts: Int, retryIn: FiniteDuration, error: String): IO[Unit] =
    sql"""UPDATE play.outbox
          SET attempts = $attempts, next_attempt_at = now() + make_interval(secs => ${retryIn.toSeconds.toDouble}),
              last_error = $error
          WHERE game_id = ${gameId.value}::uuid""".update.run.transact(xa).void.timeout(SaveTimeout)

  def markParked(gameId: GameId, error: String): IO[Unit] =
    sql"""UPDATE play.outbox
          SET failed_permanently = true, attempts = attempts + 1, last_error = $error
          WHERE game_id = ${gameId.value}::uuid""".update.run.transact(xa).void.timeout(SaveTimeout)

  // ── ClientReportStore ───────────────────────────────────────────────────────

  /** See [[ClientReportStore.insertClientReport]]. Same first-write-wins shape as the outbox enqueue in `save`, but the
    * key is the report's own idempotency UUID — a browser game never has a `games` row to reference.
    */
  def insertClientReport(id: GameId, payload: Json): IO[Boolean] =
    sql"""INSERT INTO play.client_reports (report_id, payload)
          VALUES (${id.value}::uuid, $payload)
          ON CONFLICT (report_id) DO NOTHING""".update.run
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_ == 1)

  /** See [[ClientReportStore.clientReports]] — a mirror of the OutboxStore methods above over `client_reports`, so one
    * `IngestDeliverer` drains each queue with identical semantics.
    */
  val clientReports: OutboxStore = new OutboxStore:
    def due(limit: Int): IO[List[OutboxRow]] =
      sql"""SELECT report_id::text, payload, attempts FROM play.client_reports
            WHERE delivered_at IS NULL AND NOT failed_permanently AND next_attempt_at <= now()
            ORDER BY next_attempt_at
            LIMIT ${limit.toLong}"""
        .query[(String, Json, Int)]
        .to[List]
        .transact(xa)
        .timeout(SaveTimeout)
        .map(_.map((id, payload, attempts) => OutboxRow(GameId(id), payload, attempts)))

    def markDelivered(gameId: GameId): IO[Unit] =
      sql"""UPDATE play.client_reports SET delivered_at = now(), last_error = NULL
            WHERE report_id = ${gameId.value}::uuid""".update.run.transact(xa).void.timeout(SaveTimeout)

    def markRetry(gameId: GameId, attempts: Int, retryIn: FiniteDuration, error: String): IO[Unit] =
      sql"""UPDATE play.client_reports
            SET attempts = $attempts, next_attempt_at = now() + make_interval(secs => ${retryIn.toSeconds.toDouble}),
                last_error = $error
            WHERE report_id = ${gameId.value}::uuid""".update.run.transact(xa).void.timeout(SaveTimeout)

    def markParked(gameId: GameId, error: String): IO[Unit] =
      sql"""UPDATE play.client_reports
            SET failed_permanently = true, attempts = attempts + 1, last_error = $error
            WHERE report_id = ${gameId.value}::uuid""".update.run.transact(xa).void.timeout(SaveTimeout)

  // ── GameArchiveStore ────────────────────────────────────────────────────────

  def archiveFor(id: GameId): IO[Option[ArchivedGame]] =
    sql"""SELECT payload, finished_at FROM play.game_archive WHERE game_id = ${id.value}::uuid"""
      .query[(Json, Instant)]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map((payload, finishedAt) => ArchivedGame(payload, finishedAt)))

  /** See [[GameArchiveStore.backfillArchive]]. `LEFT JOIN game_results` rather than an inner one so a game missing its
    * projection row still gets archived (falling back to `games.updated_at` for `finished_at`) instead of being
    * silently stranded — the archive is the durable record, and it should not depend on another projection being
    * intact.
    *
    * Each row is inserted in its own transaction, not one per batch: an interrupted run then leaves every row it
    * already converted committed, and the cursor simply restarts from the last `game_id` the caller logged.
    */
  def backfillArchive(after: Option[GameId], limit: Int): IO[ArchiveBackfillBatch] =
    val cursor = after.map(_.value).getOrElse(PgGameStore.ZeroUuid)
    sql"""SELECT g.id::text, g.snapshot, COALESCE(r.finished_at, g.updated_at)
          FROM play.games g
          LEFT JOIN play.game_results r ON r.game_id = g.id
          WHERE g.status = 'ended'
            AND g.id > $cursor::uuid
            AND NOT EXISTS (SELECT 1 FROM play.game_archive a WHERE a.game_id = g.id)
          ORDER BY g.id
          LIMIT ${limit.toLong}"""
      .query[(String, Json, Instant)]
      .to[List]
      .transact(xa)
      .timeout(PgGameStore.BackfillTimeout)
      .flatMap { rows =>
        rows
          .traverse { (id, json, finishedAt) =>
            PgGameStore.archivablePayload(json) match
              case Left(reason) =>
                // Never silently dropped, and the cursor still moves past it (see ArchiveBackfillBatch). The reason is
                // spelled out because a run over tens of thousands of rows is useless to an operator who cannot tell an
                // expected skip from one worth investigating.
                Console[IO].errorln(s"[play][backfill] game $id skipped: $reason").as(0)
              case Right(payload) =>
                sql"""INSERT INTO play.game_archive (game_id, payload, finished_at)
                      VALUES ($id::uuid, $payload, $finishedAt)
                      ON CONFLICT (game_id) DO NOTHING""".update.run
                  .transact(xa)
                  .timeout(PgGameStore.BackfillTimeout)
          }
          .map { inserts =>
            val inserted = inserts.sum
            ArchiveBackfillBatch(
              lastId = rows.lastOption.map((id, _, _) => GameId(id)),
              scanned = rows.size,
              inserted = inserted,
              skipped = rows.size - inserted
            )
          }
      }

  // ── RetentionStore ──────────────────────────────────────────────────────────

  /** See [[RetentionStore.pruneOnce]]. One transaction for the whole batch: the two deletes are ordered by the V2
    * foreign key (`outbox.game_id REFERENCES games(id)`, no `ON DELETE`), so a snapshot can only go once its outbox row
    * has, and doing both atomically means a crash can never leave a game whose outbox row is gone while the row that
    * needed it survives. Bounding the batch — rather than one giant statement — is what keeps that transaction short.
    *
    * Two rules make this safe to run against live data:
    *   - only `status = 'ended'` rows are ever considered, so a game in progress is untouchable regardless of age (boot
    *     resume reads `WHERE status='active'`, and pruning a live snapshot would forfeit a real game);
    *   - a snapshot is dropped only when its history is preserved elsewhere — an archive row exists, or the game was
    *     aborted and therefore has no history to serve by design (`GameArchive.payload` excludes exactly those). An
    *     ended, non-aborted game with no archive row is RETAINED and counted, never quietly destroyed.
    *
    * A parked outbox row (`failed_permanently`) is left alone for inspection, which by the FK also pins its snapshot —
    * the `NOT EXISTS (outbox)` guard below needs no special case for it.
    *
    * Delivered `client_reports` rows (#212) are pruned by the same rule as delivered outbox rows — the row has done its
    * job — and parked ones are likewise kept for inspection. They join this transaction for the summary count only:
    * with no FK anywhere, they have no ordering relationship with the other two deletes.
    */
  def pruneOnce(olderThan: Instant, limit: Int): IO[RetentionSweep] =
    val deleteOutbox =
      sql"""DELETE FROM play.outbox
            WHERE game_id IN (
              SELECT o.game_id FROM play.outbox o
              WHERE o.delivered_at IS NOT NULL
                AND NOT o.failed_permanently
                AND o.delivered_at < $olderThan
              ORDER BY o.game_id
              LIMIT ${limit.toLong}
            )""".update.run

    val deleteClientReports =
      sql"""DELETE FROM play.client_reports
            WHERE report_id IN (
              SELECT c.report_id FROM play.client_reports c
              WHERE c.delivered_at IS NOT NULL
                AND NOT c.failed_permanently
                AND c.delivered_at < $olderThan
              ORDER BY c.report_id
              LIMIT ${limit.toLong}
            )""".update.run

    val deleteSnapshots =
      sql"""DELETE FROM play.games
            WHERE id IN (
              SELECT g.id FROM play.games g
              LEFT JOIN play.game_results r ON r.game_id = g.id
              WHERE g.status = 'ended'
                AND g.updated_at < $olderThan
                AND NOT EXISTS (SELECT 1 FROM play.outbox o WHERE o.game_id = g.id)
                AND (
                  EXISTS (SELECT 1 FROM play.game_archive a WHERE a.game_id = g.id)
                  OR r.termination = 'aborted'
                )
              ORDER BY g.id
              LIMIT ${limit.toLong}
            )""".update.run

    // Counted, not deleted: the ended snapshots this pass refuses to touch because their history exists nowhere else.
    val countRetained =
      sql"""SELECT count(*) FROM play.games g
            LEFT JOIN play.game_results r ON r.game_id = g.id
            WHERE g.status = 'ended'
              AND g.updated_at < $olderThan
              AND NOT EXISTS (SELECT 1 FROM play.game_archive a WHERE a.game_id = g.id)
              AND COALESCE(r.termination, '') <> 'aborted'""".query[Int].unique

    // Only on a batch that removed nothing — see `RetentionSweep.retainedUnarchived`. This count is a whole-table
    // aggregate with no LIMIT, and `Retention.drain` reads it exclusively from the terminal batch, so computing it on
    // every page would scan the table once per page to throw the answer away (~47 wasted scans on the first real run).
    (deleteOutbox, deleteSnapshots, deleteClientReports)
      .flatMapN { (outboxDeleted, snapshotsDeleted, reportsDeleted) =>
        if outboxDeleted == 0 && snapshotsDeleted == 0 && reportsDeleted == 0 then
          countRetained.map(RetentionSweep(outboxDeleted, snapshotsDeleted, reportsDeleted, _))
        else RetentionSweep(outboxDeleted, snapshotsDeleted, reportsDeleted, 0).pure[ConnectionIO]
      }
      .transact(xa)
      .timeout(PgGameStore.BackfillTimeout)

  // ── BotStore ────────────────────────────────────────────────────────────────

  /** Claim the identity atomically: the primary key makes a concurrent double-register lose cleanly. */
  def register(team: String, name: String, tokenHash: String, owner: Option[String]): IO[Boolean] =
    sql"""INSERT INTO play.bots (team, name, token_hash, owner_external_id)
          VALUES ($team, $name, $tokenHash, $owner)
          ON CONFLICT (team, name) DO NOTHING""".update.run
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_ == 1)

  /** See [[BotStore.claimOwner]]. One statement: the `WHERE` accepts only an unowned row or one this account already
    * owns, so a concurrent claim by another account cannot slip between a read and a write. Zero rows updated is then
    * ambiguous on purpose-free grounds — it means either "not registered" or "someone else owns it" — so the follow-up
    * read distinguishes them for the caller's status code rather than guessing.
    */
  def claimOwner(team: String, name: String, ownerExternalId: String): IO[OwnerClaim] =
    val claim =
      sql"""UPDATE play.bots SET owner_external_id = $ownerExternalId
            WHERE team = $team AND name = $name
              AND (owner_external_id IS NULL OR owner_external_id = $ownerExternalId)""".update.run
    val exists = sql"""SELECT 1 FROM play.bots WHERE team = $team AND name = $name""".query[Int].option
    claim
      .flatMap {
        case 1 => OwnerClaim.Claimed.pure[ConnectionIO]
        case _ => exists.map(_.fold(OwnerClaim.NotRegistered)(_ => OwnerClaim.ClaimedByAnother))
      }
      .transact(xa)
      .timeout(SaveTimeout)

  def releaseOwner(team: String, name: String, ownerExternalId: String): IO[Boolean] =
    sql"""UPDATE play.bots SET owner_external_id = NULL
          WHERE team = $team AND name = $name AND owner_external_id = $ownerExternalId""".update.run
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_ == 1)

  /** The bot listings (this one, [[adminBots]], [[catalogBots]]) all show ONE rating per card, so each picks a category
    * and says which: [[RatingCategory.Default]], the same scale an unqualified `/leaderboard` answers on, so a card and
    * the board can never quote different numbers for the same bot.
    *
    * `LEFT JOIN` with a `COALESCE` to `Glicko.Initial`, not the board's inner join: a listing must show every bot it is
    * asked about — an owner's own bot, an admin's inventory, a catalog card the operator opened — including one that
    * has never played this category and therefore has no row (V21 is sparse). Unrated reads as the fresh state here
    * rather than as a missing row, which is what the single-scale columns used to give these queries for free.
    */
  def botsOwnedBy(ownerExternalId: String): IO[List[OwnedBot]] =
    sql"""SELECT b.team, b.name,
                 COALESCE(r.rating, ${Glicko.Initial.rating}), COALESCE(r.rd, ${Glicko.Initial.deviation}),
                 b.on_ladder, b.open_to_humans
          FROM play.bots b
          LEFT JOIN play.bot_ratings r
            ON r.team = b.team AND r.name = b.name AND r.category = ${RatingCategory.Default.wireName}
          WHERE b.owner_external_id = $ownerExternalId
          ORDER BY COALESCE(r.rating, ${Glicko.Initial.rating}) DESC, b.team, b.name"""
      .query[(String, String, Double, Double, Boolean, Boolean)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(OwnedBot.apply.tupled))

  def authenticate(tokenHash: String): IO[Option[Principal.Bot]] =
    sql"""SELECT team, name FROM play.bots WHERE token_hash = $tokenHash"""
      .query[(String, String)]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(Principal.Bot(_, _)))

  def rotate(team: String, name: String, newTokenHash: String): IO[Boolean] =
    sql"""UPDATE play.bots SET token_hash = $newTokenHash, rotated_at = now()
          WHERE team = $team AND name = $name""".update.run
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_ == 1)

  def ratingOf(team: String, name: String): IO[Option[BotRating]] =
    sql"""SELECT glicko_rating, glicko_rd, glicko_vol, on_ladder, owner_external_id
          FROM play.bots WHERE team = $team AND name = $name"""
      .query[(Double, Double, Double, Boolean, Option[String])]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(PgGameStore.toBotRating))

  /** `RETURNING` in the same statement: the update and the read of its result are one round trip, so there's no window
    * for a concurrent change to make the returned state stale.
    */
  def setOnLadder(team: String, name: String, onLadder: Boolean): IO[Option[BotRating]] =
    sql"""UPDATE play.bots SET on_ladder = $onLadder WHERE team = $team AND name = $name
          RETURNING glicko_rating, glicko_rd, glicko_vol, on_ladder, owner_external_id"""
      .query[(Double, Double, Double, Boolean, Option[String])]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(PgGameStore.toBotRating))

  /** The scheduler's whole candidate read in one query (#189): being on the ladder, the declared capacity, and the
    * catalog flag that reserves part of it all come off the same row, so there is no reason to make it three.
    */
  def onLadderCandidates: IO[List[BotSeatPolicy]] =
    sql"""SELECT team, name, max_concurrent_games, open_to_humans
          FROM play.bots WHERE on_ladder = true"""
      .query[(String, String, Int, Boolean)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (team, name, limit, open) => BotSeatPolicy(Principal.Bot(team, name), limit, open) })

  def seatPolicyOf(team: String, name: String): IO[Option[BotSeatPolicy]] =
    sql"""SELECT max_concurrent_games, open_to_humans
          FROM play.bots WHERE team = $team AND name = $name"""
      .query[(Int, Boolean)]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (limit, open) => BotSeatPolicy(Principal.Bot(team, name), limit, open) })

  /** `RETURNING` in the same statement, same no-stale-window reasoning as `setOnLadder`. The value is range-checked by
    * the caller and again by `bots_max_concurrent_games_range`; the constraint is the backstop, not the validation.
    */
  def setMaxConcurrentGames(team: String, name: String, maxConcurrentGames: Int): IO[Option[BotSeatPolicy]] =
    sql"""UPDATE play.bots SET max_concurrent_games = $maxConcurrentGames
          WHERE team = $team AND name = $name
          RETURNING max_concurrent_games, open_to_humans"""
      .query[(Int, Boolean)]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (limit, open) => BotSeatPolicy(Principal.Bot(team, name), limit, open) })

  /** `RETURNING` in the same statement (same no-stale-window reasoning as `setOnLadder`): open the bot and set its
    * description in one write, then read the persisted state back. `None` if no such registered identity.
    */
  def openToHumans(team: String, name: String, description: Option[String]): IO[Option[BotCatalogState]] =
    sql"""UPDATE play.bots SET open_to_humans = true, description = $description
          WHERE team = $team AND name = $name
          RETURNING open_to_humans, description"""
      .query[(Boolean, Option[String])]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (open, desc) => BotCatalogState(open, desc) })

  def closeToHumans(team: String, name: String): IO[Option[BotCatalogState]] =
    sql"""UPDATE play.bots SET open_to_humans = false WHERE team = $team AND name = $name
          RETURNING open_to_humans, description"""
      .query[(Boolean, Option[String])]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (open, desc) => BotCatalogState(open, desc) })

  def openToHumansBots: IO[List[Principal.Bot]] =
    sql"""SELECT team, name FROM play.bots WHERE open_to_humans = true"""
      .query[(String, String)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(Principal.Bot(_, _)))

  // ── AdminBotStore (#273) ────────────────────────────────────────────────────

  /** The administrator's complete inventory (#313), including bots no public read can surface because they are neither
    * on the ladder nor open to humans (or still provisional). This stays a plain SELECT rather than [[adminTx]]:
    * `admin_actions` records changes, never views.
    */
  def adminBots: IO[List[AdminBotListing]] =
    sql"""SELECT b.team, b.name,
                 COALESCE(r.rating, ${Glicko.Initial.rating}), COALESCE(r.rd, ${Glicko.Initial.deviation}),
                 b.on_ladder, b.open_to_humans, b.description, b.owner_external_id IS NOT NULL
          FROM play.bots b
          LEFT JOIN play.bot_ratings r
            ON r.team = b.team AND r.name = b.name AND r.category = ${RatingCategory.Default.wireName}
          ORDER BY COALESCE(r.rating, ${Glicko.Initial.rating}) DESC, b.team, b.name"""
      .query[(String, String, Double, Double, Boolean, Boolean, Option[String], Boolean)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(AdminBotListing.apply.tupled))

  /** One admin mutation and its `admin_actions` row in a single transaction (V19, the `renameTx` shape): a crash can
    * never leave the action applied but unrecorded. The row is written only when the mutation found its bot — the table
    * records what happened, not what was attempted.
    */
  private def adminTx[A](adminUserId: String, team: String, name: String, action: String, detail: Option[String])(
      mutation: ConnectionIO[Option[A]]
  ): IO[Option[A]] =
    mutation
      .flatMap { result =>
        sql"""INSERT INTO play.admin_actions (admin_user_id, team, name, action, detail)
              VALUES ($adminUserId::uuid, $team, $name, $action, $detail)""".update.run.void
          .whenA(result.isDefined)
          .as(result)
      }
      .transact(xa)
      .timeout(SaveTimeout)

  def adminSetOnLadder(adminUserId: String, team: String, name: String, onLadder: Boolean): IO[Option[BotRating]] =
    adminTx(adminUserId, team, name, action = if onLadder then "ladder.join" else "ladder.leave", detail = None) {
      sql"""UPDATE play.bots SET on_ladder = $onLadder WHERE team = $team AND name = $name
            RETURNING glicko_rating, glicko_rd, glicko_vol, on_ladder, owner_external_id"""
        .query[(Double, Double, Double, Boolean, Option[String])]
        .option
    }.map(_.map(PgGameStore.toBotRating))

  def adminOpenToHumans(
      adminUserId: String,
      team: String,
      name: String,
      description: Option[String]
  ): IO[Option[BotCatalogState]] =
    adminTx(adminUserId, team, name, action = "catalog.open", detail = description) {
      sql"""UPDATE play.bots SET open_to_humans = true, description = $description
            WHERE team = $team AND name = $name
            RETURNING open_to_humans, description"""
        .query[(Boolean, Option[String])]
        .option
    }.map(_.map { case (open, desc) => BotCatalogState(open, desc) })

  def adminCloseToHumans(adminUserId: String, team: String, name: String): IO[Option[BotCatalogState]] =
    adminTx(adminUserId, team, name, action = "catalog.close", detail = None) {
      sql"""UPDATE play.bots SET open_to_humans = false WHERE team = $team AND name = $name
            RETURNING open_to_humans, description"""
        .query[(Boolean, Option[String])]
        .option
    }.map(_.map { case (open, desc) => BotCatalogState(open, desc) })

  def adminSetDescription(
      adminUserId: String,
      team: String,
      name: String,
      description: Option[String]
  ): IO[Option[BotCatalogState]] =
    adminTx(adminUserId, team, name, action = "catalog.describe", detail = description) {
      sql"""UPDATE play.bots SET description = $description WHERE team = $team AND name = $name
            RETURNING open_to_humans, description"""
        .query[(Boolean, Option[String])]
        .option
    }.map(_.map { case (open, desc) => BotCatalogState(open, desc) })

  def adminRotate(adminUserId: String, team: String, name: String, newTokenHash: String): IO[Boolean] =
    adminTx(adminUserId, team, name, action = "token.rotate", detail = None) {
      sql"""UPDATE play.bots SET token_hash = $newTokenHash, rotated_at = now()
            WHERE team = $team AND name = $name""".update.run
        .map(rows => Option.when(rows == 1)(()))
    }.map(_.isDefined)

  /** Catalog cards for `GET /lobby/bots` (ADR-0014): the open-to-humans bots with their rating summary and blurb, best
    * rating first. Needs no `game_results` join, unlike the leaderboard. `max_concurrent_games` (#189) rides along in
    * the same row so the route can derive `available` with a pure in-memory registry lookup per card, rather than a
    * second query per bot (#224).
    *
    * One card, one rating, in [[RatingCategory.Default]] — see [[botsOwnedBy]] for the join's rationale. The catalog
    * offers controls spanning all three categories (`1+1` through `10+10`), so a per-control rating on the card would
    * be the more informative answer; that is a wire question for the SPA's own picker (rabestro/dicechess-play#258)
    * rather than something to guess at here.
    */
  def catalogBots: IO[List[BotCatalogListing]] =
    sql"""SELECT b.team, b.name,
                 COALESCE(r.rating, ${Glicko.Initial.rating}), COALESCE(r.rd, ${Glicko.Initial.deviation}),
                 b.description, b.max_concurrent_games
          FROM play.bots b
          LEFT JOIN play.bot_ratings r
            ON r.team = b.team AND r.name = b.name AND r.category = ${RatingCategory.Default.wireName}
          WHERE b.open_to_humans = true
          ORDER BY COALESCE(r.rating, ${Glicko.Initial.rating}) DESC, b.team, b.name"""
      .query[(String, String, Double, Double, Option[String], Int)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (team, name, rating, rd, description, maxConcurrentGames) =>
        BotCatalogListing(team, name, rating, rd, description, maxConcurrentGames)
      })

  // ── WebhookStore (F.2, #104) ────────────────────────────────────────────────

  /** Upsert: a re-register replaces URL and secret together (the old secret stops signing immediately). */
  def put(webhook: BotWebhook): IO[Unit] =
    sql"""INSERT INTO play.bot_webhooks (team, name, url, secret, verified_at, capabilities)
          VALUES (${webhook.team}, ${webhook.name}, ${webhook.url}, ${webhook.secret}, ${webhook.verifiedAt}, ${webhook.capabilities})
          ON CONFLICT (team, name)
          DO UPDATE SET url = EXCLUDED.url, secret = EXCLUDED.secret, verified_at = EXCLUDED.verified_at, capabilities = EXCLUDED.capabilities""".update.run
      .transact(xa)
      .timeout(SaveTimeout)
      .void

  def get(team: String, name: String): IO[Option[BotWebhook]] =
    sql"""SELECT team, name, url, secret, verified_at, capabilities FROM play.bot_webhooks
          WHERE team = $team AND name = $name"""
      .query[BotWebhook]
      .option
      .transact(xa)
      .timeout(SaveTimeout)

  def delete(team: String, name: String): IO[Boolean] =
    sql"""DELETE FROM play.bot_webhooks WHERE team = $team AND name = $name""".update.run
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_ == 1)

  // ── WebhookStatsStore (#225) ─────────────────────────────────────────────────

  /** One delivery folded into its histogram cell, PLUS — for a genuine fault (`DeliveryOutcome.isFailure`) — the bot's
    * "last failure" columns. `hour` is truncated in SQL (`date_trunc`), not in Scala, so the truncation rule lives in
    * exactly one place and `statsFor`'s own range read reasons about the same column the same way. Both writes are
    * best-effort off the turn path: `Webhooks`'s drain loop is the only caller, and a failure here is dropped, never
    * retried, never allowed to touch a game.
    */
  def recordDelivery(
      team: String,
      name: String,
      outcome: DeliveryOutcome,
      elapsed: FiniteDuration,
      at: Instant
  ): IO[Unit] =
    val key             = DeliveryOutcome.key(outcome)
    val bucket          = LatencyHistogram.bucketOf(elapsed)
    val upsertHistogram =
      sql"""INSERT INTO play.bot_webhook_stats (team, name, hour, outcome, latency_bucket, count)
            VALUES ($team, $name, date_trunc('hour', $at::timestamptz), $key, $bucket, 1)
            ON CONFLICT (team, name, hour, outcome, latency_bucket)
            DO UPDATE SET count = play.bot_webhook_stats.count + 1""".update.run.void
    val markLastFailure =
      sql"""UPDATE play.bot_webhooks SET last_failure_at = $at, last_failure_reason = ${DeliveryOutcome.describe(
          outcome
        )}
            WHERE team = $team AND name = $name""".update.run.void
        .whenA(DeliveryOutcome.isFailure(outcome))
    (upsertHistogram *> markLastFailure).transact(xa).timeout(SaveTimeout)

  /** `GET /bot/webhook/stats`'s read: one query covers both windows (7 days is the wider one; the 24h window is
    * re-aggregated from the same rows in Scala, in `DeliveryStatsWindow.aggregate` — no reason to hit Postgres twice
    * for a subset of what the first query already fetched), plus the bot's last-failure columns.
    */
  def statsFor(team: String, name: String, now: Instant): IO[WebhookStats] =
    val since7d  = now.minus(7, java.time.temporal.ChronoUnit.DAYS)
    val since24h = now.minus(24, java.time.temporal.ChronoUnit.HOURS)
    val rows     =
      sql"""SELECT outcome, latency_bucket, hour, count FROM play.bot_webhook_stats
            WHERE team = $team AND name = $name AND hour >= $since7d"""
        .query[(String, Int, Instant, Long)]
        .to[List]
    val lastFailure =
      sql"""SELECT last_failure_at, last_failure_reason FROM play.bot_webhooks
            WHERE team = $team AND name = $name"""
        .query[(Option[Instant], Option[String])]
        .option
    (rows, lastFailure).tupled
      .transact(xa)
      .timeout(SaveTimeout)
      .map { case (all7d, failureRow) =>
        val last24h = all7d.filter { case (_, _, hour, _) => !hour.isBefore(since24h) }
        WebhookStats(
          last24h = DeliveryStatsWindow.aggregate(last24h.map { case (o, b, _, c) => (o, b, c) }),
          last7d = DeliveryStatsWindow.aggregate(all7d.map { case (o, b, _, c) => (o, b, c) }),
          lastFailure = failureRow.flatMap {
            case (Some(at), Some(reason)) => Some(LastFailure(at, reason))
            case _                        => None
          }
        )
      }

  /** Every live game, decoded row by row: one corrupt snapshot is logged and skipped, never aborting the batch — a
    * single bad row must not stop every other game from resuming.
    */
  def loadActive: IO[List[(GameId, GameSnapshot)]] =
    sql"""SELECT id::text, snapshot FROM play.games WHERE status = 'active'"""
      .query[(String, Json)]
      .to[List]
      .transact(xa)
      .timeout(BootTimeout)
      .flatMap {
        _.flatTraverse { case (id, json) =>
          json.as[GameSnapshot] match
            case Right(snapshot) => IO.pure(List(GameId(id) -> snapshot))
            case Left(error)     =>
              Console[IO].errorln(s"[play][store] corrupt snapshot for game $id skipped: $error").as(Nil)
        }
      }

  // ── GameResultsStore ──────────────────────────────────────────────────────

  /** Two LIMIT-bounded, already-ordered subqueries (one per side) unioned and re-limited, rather than one `OR` across
    * both columns: an `OR` predicate on two single-column indexes forces Postgres to bitmap-scan and sort ALL of the
    * participant's matching rows before applying LIMIT — O(history size) — whereas each `(participant, finished_at
    * DESC)` composite index below serves its half of this query as a plain bounded index scan. Plain `UNION`, not
    * `UNION ALL`: `GameRegistry.create` doesn't itself forbid seating the same principal on both sides (only its
    * `Lobby`/`Challenges` callers do), so a self-played game would otherwise match both branches and come back twice.
    * The dedupe cost is over at most `2 * limit` rows, not the participant's whole history.
    */
  def recentResultsFor(externalId: String, limit: Int): IO[List[GameResultRow]] =
    sql"""(SELECT game_id::text, white_external_id, black_external_id, result, termination, rated, time_control,
                  server_seed, pairing_id::text, ladder, finished_at
           FROM play.game_results
           WHERE white_external_id = $externalId
           ORDER BY finished_at DESC
           LIMIT ${limit.toLong})
          UNION
          (SELECT game_id::text, white_external_id, black_external_id, result, termination, rated, time_control,
                  server_seed, pairing_id::text, ladder, finished_at
           FROM play.game_results
           WHERE black_external_id = $externalId
           ORDER BY finished_at DESC
           LIMIT ${limit.toLong})
          ORDER BY finished_at DESC
          LIMIT ${limit.toLong}"""
      .query[PgGameStore.ResultTuple]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(PgGameStore.toRow))

  def finishedRatedSince(since: Instant): IO[List[GameResultRow]] =
    sql"""SELECT game_id::text, white_external_id, black_external_id, result, termination, rated, time_control,
                 server_seed, pairing_id::text, ladder, finished_at
          FROM play.game_results
          WHERE rated = true AND finished_at > $since
          ORDER BY finished_at ASC"""
      .query[PgGameStore.ResultTuple]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(PgGameStore.toRow))

  /** One side's `WHERE` clause: the participant match plus whichever optional filters are present, folded from a list
    * rather than built with always-present `col IS NULL OR ...` guards — the latter risks the planner falling back to a
    * full scan of the participant's matching rows on a parameter it can't prove absent at plan time, defeating the
    * whole point of the composite `(participant, finished_at DESC)` index this shares with `recentResultsFor`.
    * `opponentCol` is the OTHER side's column (the participant's opponent in this branch).
    */
  private def pageSide(
      participantCol: String,
      opponentCol: String,
      externalIds: NonEmptyList[String],
      before: Option[Instant],
      opponent: Option[OpponentFilter],
      povResult: Option[Short],
      fetchLimit: Int
  ): Fragment =
    val opponentFrag = opponent.map:
      case OpponentFilter.Bot(id)   => Fragment.const(opponentCol) ++ fr"= $id"
      case OpponentFilter.HumanOnly => Fragment.const(opponentCol) ++ fr"NOT LIKE 'bot:team:%'"
    // The participant match is always present, so this list is never empty — `reduce` (not `reduceOption`) is safe.
    // `Fragments.in` (not a hand-built IN-list, and not `= ANY(array)`): it keeps every id a bound parameter while
    // needing no Postgres array codec — importing one here would also silently replace the deliberate
    // driver-native java.time mapping this file relies on everywhere else.
    val predicates = fragments.in(Fragment.const(participantCol), externalIds) :: List(
      before.map(b => fr"finished_at < $b"),
      opponentFrag,
      povResult.map(r => fr"result = $r")
    ).flatten
    val where = predicates.reduce(_ ++ fr" AND " ++ _)
    // `.toLong` is not decoration, and it is the convention for EVERY `LIMIT` in this file: PostgreSQL types that
    // parameter as `int8`, so an `Int` binds a type the driver merely happens to widen. `PgQueryCheckSuite` exempts no
    // finding, and it is that strictness — not this conversion — that is the point: the alternative was to stop
    // checking parameter types altogether, which is the only way doobie reports a genuinely wrong binding too. The
    // store's own API keeps `limit: Int`, because a page size is not a Long; only the binding speaks the schema's type.
    fr"""SELECT game_id::text, white_external_id, black_external_id, result, termination, rated, time_control,
                server_seed, pairing_id::text, ladder, finished_at
         FROM play.game_results
          WHERE""" ++ where ++ fr" ORDER BY finished_at DESC LIMIT ${fetchLimit.toLong}"

  /** The requester's own POV result, translated to the white-POV value the `result` column stores for THIS branch —
    * `PovResultFilter.Draw` is its own inverse (`-0 = 0`), so only Win/Loss actually flip between the two branches.
    *
    * `Short` because that is what `game_results.result` is (`smallint`) and this value is bound straight into
    * `result = ?` — same reason as the `LIMIT` above.
    */
  private def povResultValue(result: PovResultFilter, requesterIsWhite: Boolean): Short =
    val whitePov: Short = result match
      case PovResultFilter.Win  => 1
      case PovResultFilter.Draw => 0
      case PovResultFilter.Loss => -1
    if requesterIsWhite then whitePov else (-whitePov).toShort

  def playerGamesPage(
      externalIds: List[String],
      before: Option[Instant],
      opponent: Option[OpponentFilter],
      result: Option[PovResultFilter],
      limit: Int
  ): IO[GameResultsStore.Page] =
    // One row past `limit`, so `hasMore` is exact without a COUNT(*) or a second round trip (same idea as
    // recentResultsFor's own limit-per-branch-then-relimit shape, just with optional filters folded in).
    // No identities means nothing can match — answer an empty page rather than building a predicate-less query that
    // would return every game ever played.
    NonEmptyList.fromList(externalIds) match
      case None      => IO.pure(GameResultsStore.Page(Nil, hasMore = false))
      case Some(ids) => pagedResults(ids, before, opponent, result, limit)

  /** The assembled page query, named rather than inlined into [[pagedResults]] so `PgQueryCheckSuite` can prepare it
    * against a real PostgreSQL for every filter combination. Nothing type-checks a `Fragment`: what a compiler sees
    * here is string concatenation, so the only guard against a malformed one is asking the database.
    */
  private[store] def pagedFragment(
      externalIds: NonEmptyList[String],
      before: Option[Instant],
      opponent: Option[OpponentFilter],
      result: Option[PovResultFilter],
      limit: Int
  ): Fragment =
    val fetchLimit  = limit + 1
    val whiteBranch =
      pageSide(
        "white_external_id",
        "black_external_id",
        externalIds,
        before,
        opponent,
        result.map(povResultValue(_, requesterIsWhite = true)),
        fetchLimit
      )
    val blackBranch =
      pageSide(
        "black_external_id",
        "white_external_id",
        externalIds,
        before,
        opponent,
        result.map(povResultValue(_, requesterIsWhite = false)),
        fetchLimit
      )
    // UNION, not UNION ALL: a game whose BOTH seats belong to the requester satisfies both branches, and that is not an
    // exotic shape — a friend-by-link game records its creator on both sides, and a merged history (#236) puts an
    // account and its own claimed guest id in one id set. `game_id` is in the select list, so the dedupe collapses only
    // a genuinely duplicated game and never two distinct ones. The cost is bounded: each branch is already limited, so
    // it sorts at most 2 * (limit + 1) rows — the same argument spelled out on `recentResultsFor`, which has always
    // deduped for exactly this reason (#98).
    fr"(" ++ whiteBranch ++ fr") UNION (" ++ blackBranch ++ fr") ORDER BY finished_at DESC LIMIT ${fetchLimit.toLong}"

  private def pagedResults(
      externalIds: NonEmptyList[String],
      before: Option[Instant],
      opponent: Option[OpponentFilter],
      result: Option[PovResultFilter],
      limit: Int
  ): IO[GameResultsStore.Page] =
    pagedFragment(externalIds, before, opponent, result, limit)
      .query[PgGameStore.ResultTuple]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map { rows =>
        GameResultsStore.Page(rows.take(limit).map(PgGameStore.toRow), hasMore = rows.length > limit)
      }

  /** Self-play is excluded from both branches — a game against yourself has no opponent to aggregate against, and with
    * several requester identities (#236) that includes a signed-in player against their own claimed guest id. `bot_key`
    * collapses every non-bot opponent onto `NULL`, so `GROUP BY bot_key` yields one row per registered bot plus one row
    * for every human/guest opponent combined.
    */
  def opponentsFor(externalIds: List[String]): IO[List[OpponentAggregateRow]] =
    NonEmptyList.fromList(externalIds).fold(IO.pure(List.empty[OpponentAggregateRow]))(opponentAggregates)

  /** Named for the same reason as [[pagedFragment]]: two hand-assembled branches under a UNION ALL, checkable only by a
    * database.
    */
  private[store] def opponentAggregatesFragment(ids: NonEmptyList[String]): Fragment =
    val mine    = (col: String) => fragments.in(Fragment.const(col), ids)
    val notMine = (col: String) => fr"NOT" ++ fragments.in(Fragment.const(col), ids)
    val asWhite =
      fr"""SELECT CASE WHEN black_external_id LIKE 'bot:team:%' THEN black_external_id END AS bot_key,
                  result AS pov_result, finished_at
           FROM play.game_results
           WHERE""" ++ mine("white_external_id") ++ fr" AND " ++ notMine("black_external_id")
    val asBlack =
      fr"""SELECT CASE WHEN white_external_id LIKE 'bot:team:%' THEN white_external_id END AS bot_key,
                  -result AS pov_result, finished_at
           FROM play.game_results
           WHERE""" ++ mine("black_external_id") ++ fr" AND " ++ notMine("white_external_id")
    fr"""SELECT bot_key, count(*)::int AS games,
                count(*) FILTER (WHERE pov_result = 1)::int AS wins,
                count(*) FILTER (WHERE pov_result = 0)::int AS draws,
                count(*) FILTER (WHERE pov_result = -1)::int AS losses,
                max(finished_at) AS last_played_at
         FROM ((""" ++ asWhite ++ fr")  UNION ALL  (" ++ asBlack ++ fr""")) per_game
         GROUP BY bot_key
         ORDER BY games DESC, last_played_at DESC"""

  private def opponentAggregates(ids: NonEmptyList[String]): IO[List[OpponentAggregateRow]] =
    opponentAggregatesFragment(ids)
      .query[(Option[String], Int, Int, Int, Int, Instant)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (botKey, games, wins, draws, losses, lastPlayedAt) =>
        OpponentAggregateRow(botKey, games, wins, draws, losses, lastPlayedAt)
      })

  // ── RatingStore (#119) ────────────────────────────────────────────────────

  def unappliedRatedGames(limit: Int): IO[List[GameResultRow]] =
    sql"""SELECT game_id::text, white_external_id, black_external_id, result, termination, rated, time_control,
                 server_seed, pairing_id::text, ladder, finished_at
          FROM play.game_results
          WHERE rated = true AND rating_applied_at IS NULL
          ORDER BY finished_at ASC
          LIMIT ${limit.toLong}"""
      .query[PgGameStore.ResultTuple]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(PgGameStore.toRow))

  def applyRatingUpdate(gameId: GameId, white: RatingUpdate, black: RatingUpdate): IO[Unit] =
    (updateGlicko(white.identity, white.after) *> updateGlicko(black.identity, black.after) *>
      updateCategoryGlicko(white) *> updateCategoryGlicko(black) *>
      stampApplied(gameId, Some(white), Some(black)))
      .transact(xa)
      .timeout(SaveTimeout)

  def markRatingApplied(gameId: GameId): IO[Unit] =
    stampApplied(gameId, None, None).transact(xa).timeout(SaveTimeout)

  /** The recorded movement, or `None` for an id with no result row. `applied` reads the V6 stamp rather than the
    * presence of the V17 numbers, because those two genuinely differ: a skipped game is applied AND has no numbers, and
    * a poller that waited for numbers would wait forever on one (#296).
    */
  def ratingChangeFor(gameId: GameId): IO[Option[GameRatingChange]] =
    sql"""SELECT rating_applied_at IS NOT NULL,
                 white_rating_before, white_rating_after, black_rating_before, black_rating_after
          FROM play.game_results
          WHERE game_id = ${gameId.value}::uuid"""
      .query[(Boolean, Option[Double], Option[Double], Option[Double], Option[Double])]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (applied, whiteBefore, whiteAfter, blackBefore, blackAfter) =>
        GameRatingChange(
          applied,
          (whiteBefore, whiteAfter).mapN(SeatRatingChange.apply),
          (blackBefore, blackAfter).mapN(SeatRatingChange.apply)
        )
      })

  /** One participant's rating write, dispatched to the table that identity lives in (#248). Both branches stay inside
    * the caller's single transaction — see `RatingStore.applyRatingUpdate` on why atomicity is per game, not per table.
    */
  private def updateGlicko(identity: RatedIdentity, glicko: Glicko): ConnectionIO[Unit] =
    identity match
      case RatedIdentity.Bot(team, name) =>
        sql"""UPDATE play.bots
              SET glicko_rating = ${glicko.rating}, glicko_rd = ${glicko.deviation}, glicko_vol = ${glicko.volatility}
              WHERE team = $team AND name = $name""".update.run.void
      case RatedIdentity.User(id) =>
        sql"""UPDATE play.users
              SET glicko_rating = ${glicko.rating}, glicko_rd = ${glicko.deviation}, glicko_vol = ${glicko.volatility}
              WHERE id = $id::uuid""".update.run.void

  def categoryRatingOf(identity: RatedIdentity, category: RatingCategory): IO[Glicko] =
    val row = identity match
      case RatedIdentity.Bot(team, name) =>
        sql"""SELECT rating, rd, vol FROM play.bot_ratings
              WHERE team = $team AND name = $name AND category = ${category.wireName}"""
      case RatedIdentity.User(id) =>
        sql"""SELECT rating, rd, vol FROM play.user_ratings
              WHERE user_id = $id::uuid AND category = ${category.wireName}"""
    row
      .query[(Double, Double, Double)]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      // A missing row IS the fresh state, not a missing participant — see `RatingStore.categoryRatingOf`. The batch
      // has already established that the participant exists (`BotStore`/`UserStore.ratingOf` found it) before it can
      // ever get here, so there is nothing else absence could mean.
      .map(_.fold(Glicko.Initial)((rating, rd, vol) => Glicko(rating, rd, vol)))

  def categoryRatingsOf(identity: RatedIdentity): IO[Map[RatingCategory, Glicko]] =
    val rows = identity match
      case RatedIdentity.Bot(team, name) =>
        sql"""SELECT category, rating, rd, vol FROM play.bot_ratings
              WHERE team = $team AND name = $name"""
      case RatedIdentity.User(id) =>
        sql"""SELECT category, rating, rd, vol FROM play.user_ratings
              WHERE user_id = $id::uuid"""
    rows
      .query[(String, Double, Double, Double)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      // Unnameable categories drop out rather than crash, the `categoryTalliesFor` rule: this feeds a public profile.
      .map(_.flatMap { case (category, rating, rd, vol) =>
        RatingCategory.fromWireName(category).map(_ -> Glicko(rating, rd, vol))
      }.toMap)

  /** One participant's PER-CATEGORY rating write (#280), an upsert because the tables are sparse: the first rated game
    * a participant plays in a category creates its row, every later one updates it. `None` — an uncategorised control —
    * writes nothing at all, which is what keeps `Unlimited`/`PerMove` games off every scale while still letting them
    * move the shared one exactly as they do today.
    *
    * Inside the caller's transaction, like `updateGlicko`: a game that moved the shared scale but not the category one
    * would be indistinguishable, afterwards, from a game the batch had not reached yet.
    *
    * '''`INSERT … SELECT` from the parent table, not `VALUES`''', so that a participant which vanished between the
    * batch's read and this write is a NO-OP — exactly what `updateGlicko`'s `UPDATE … WHERE` already degrades to. With
    * bare `VALUES` the foreign key would raise instead, and the consequences are out of all proportion to the race: the
    * transaction aborts, the game is never stamped, and since `drainQueue` has no per-row recovery it sits at the head
    * of the queue failing every tick from then on — for an account that is gone and is never coming back (`DELETE
    * /auth/me`, #237, leaves the `user:` id in `game_results` forever). The bot branch has no such delete path today;
    * it is written the same way because the asymmetry, not the guard, is what a future one would trip on.
    */
  private def updateCategoryGlicko(update: RatingUpdate): ConnectionIO[Unit] =
    update.category.fold(().pure[ConnectionIO]) { move =>
      val glicko = move.after
      update.identity match
        case RatedIdentity.Bot(team, name) =>
          sql"""INSERT INTO play.bot_ratings (team, name, category, rating, rd, vol)
                SELECT b.team, b.name, ${move.category.wireName},
                       ${glicko.rating}, ${glicko.deviation}, ${glicko.volatility}
                FROM play.bots b WHERE b.team = $team AND b.name = $name
                ON CONFLICT (team, name, category)
                DO UPDATE SET rating = EXCLUDED.rating, rd = EXCLUDED.rd, vol = EXCLUDED.vol""".update.run.void
        case RatedIdentity.User(id) =>
          sql"""INSERT INTO play.user_ratings (user_id, category, rating, rd, vol)
                SELECT u.id, ${move.category.wireName},
                       ${glicko.rating}, ${glicko.deviation}, ${glicko.volatility}
                FROM play.users u WHERE u.id = $id::uuid
                ON CONFLICT (user_id, category)
                DO UPDATE SET rating = EXCLUDED.rating, rd = EXCLUDED.rd, vol = EXCLUDED.vol""".update.run.void
    }

  /** The claim stamp, carrying the movement it was stamped for (#296). One statement rather than a second UPDATE on the
    * same row: the numbers and the stamp describe the same event, and a skip passes `None` twice precisely so a skipped
    * game reads back as applied-with-no-movement instead of never-applied.
    *
    * The recorded movement is the CATEGORY one since #280 — what `GET /games/{id}/rating` reports has to be the change
    * on the scale the game actually counted on, which is also the scale the player's profile will show it against. A
    * game with no category move cannot occur any more (an uncategorised control is casual at creation, and the batch
    * skips whatever was already queued), so the fall-back to the shared pair exists only so this method stays total;
    * rows written between V21 and this change carry the shared scale's numbers, which for Blitz — the only category
    * with meaningful traffic — are the same numbers.
    */
  private def stampApplied(
      gameId: GameId,
      white: Option[RatingUpdate],
      black: Option[RatingUpdate]
  ): ConnectionIO[Unit] =
    def before(update: Option[RatingUpdate]) = update.map(u => u.category.fold(u.before.rating)(_.before.rating))
    def after(update: Option[RatingUpdate])  = update.map(u => u.category.fold(u.after.rating)(_.after.rating))
    sql"""UPDATE play.game_results
          SET rating_applied_at = now(),
              white_rating_before = ${before(white)},
              white_rating_after = ${after(white)},
              black_rating_before = ${before(black)},
              black_rating_after = ${after(black)}
          WHERE game_id = ${gameId.value}::uuid""".update.run.void

  // ── LeaderboardStore (#103) ───────────────────────────────────────────────

  /** One query: registered bots joined against their rated, decided W-D-L aggregated from `game_results` (each game
    * contributes from both seats' perspectives via the UNION ALL). The scan over rated games is acceptable at this
    * corpus's scale; if the ladder ever grows past that, a materialised tally is the upgrade path — behind this same
    * trait method.
    *
    * Ordered by the conservative estimate `rating − k·RD` (#169), not the raw rating — see
    * [[dicechess.play.rating.Glicko2.ConservativeOrderingK]] for the k and its justification. The routes' merged
    * re-sort (`LeaderboardRoutes`) uses the same key via `Glicko2.conservativeRating`, so this ORDER BY is really the
    * deterministic fetch order for the single-population case; keeping the two identical is what makes that harmless.
    */
  def leaderboard(
      category: RatingCategory,
      maxRd: Double,
      limit: Int = LeaderboardStore.MaxBoardSize
  ): IO[List[LeaderboardEntry]] =
    sql"""SELECT b.team, b.name, r.rating, r.rd, b.on_ladder,
                 COALESCE(t.wins, 0), COALESCE(t.draws, 0), COALESCE(t.losses, 0)
          FROM play.bots b
          JOIN play.bot_ratings r
            ON r.team = b.team AND r.name = b.name AND r.category = ${category.wireName}
          LEFT JOIN (
            SELECT external_id, SUM(win) AS wins, SUM(draw) AS draws, SUM(loss) AS losses
            FROM (
              SELECT white_external_id AS external_id,
                     CASE WHEN result = 1  THEN 1 ELSE 0 END AS win,
                     CASE WHEN result = 0  THEN 1 ELSE 0 END AS draw,
                     CASE WHEN result = -1 THEN 1 ELSE 0 END AS loss
              FROM play.game_results
              WHERE rated = true AND result IS NOT NULL AND category = ${category.wireName}
              UNION ALL
              SELECT black_external_id,
                     CASE WHEN result = -1 THEN 1 ELSE 0 END,
                     CASE WHEN result = 0  THEN 1 ELSE 0 END,
                     CASE WHEN result = 1  THEN 1 ELSE 0 END
              FROM play.game_results
              WHERE rated = true AND result IS NOT NULL AND category = ${category.wireName}
            ) sides
            GROUP BY external_id
          ) t ON t.external_id = 'bot:team:' || b.team || ':' || b.name
          WHERE r.rd <= $maxRd
          ORDER BY r.rating - ${Glicko2.ConservativeOrderingK} * r.rd DESC,
                   r.rd ASC, b.team, b.name
          LIMIT ${limit.toLong}"""
      .query[(String, String, Double, Double, Boolean, Int, Int, Int)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (team, name, rating, rd, onLadder, wins, draws, losses) =>
        LeaderboardEntry(team, name, rating, rd, onLadder, ResultTally(wins, draws, losses))
      })

  /** The account half of the same board (#249) — deliberately the SAME shape of query as `leaderboard` above, joining
    * `users` against the rated W-D-L aggregated from `game_results` by the account's `user:` external id. A player's
    * CLAIMED guest games are not counted here even though `/me/games` merges them: this is a public read, and folding
    * that history in would retroactively deanonymise every anonymous game the id ever played (#236).
    */
  def playerLeaderboard(
      category: RatingCategory,
      maxRd: Double,
      limit: Int = LeaderboardStore.MaxBoardSize
  ): IO[List[PlayerLeaderboardEntry]] =
    sql"""SELECT u.nickname, r.rating, r.rd,
                 COALESCE(t.wins, 0), COALESCE(t.draws, 0), COALESCE(t.losses, 0)
          FROM play.users u
          JOIN play.user_ratings r ON r.user_id = u.id AND r.category = ${category.wireName}
          LEFT JOIN (
            SELECT external_id, SUM(win) AS wins, SUM(draw) AS draws, SUM(loss) AS losses
            FROM (
              SELECT white_external_id AS external_id,
                     CASE WHEN result = 1  THEN 1 ELSE 0 END AS win,
                     CASE WHEN result = 0  THEN 1 ELSE 0 END AS draw,
                     CASE WHEN result = -1 THEN 1 ELSE 0 END AS loss
              FROM play.game_results
              WHERE rated = true AND result IS NOT NULL AND category = ${category.wireName}
              UNION ALL
              SELECT black_external_id,
                     CASE WHEN result = -1 THEN 1 ELSE 0 END,
                     CASE WHEN result = 0  THEN 1 ELSE 0 END,
                     CASE WHEN result = 1  THEN 1 ELSE 0 END
              FROM play.game_results
              WHERE rated = true AND result IS NOT NULL AND category = ${category.wireName}
            ) sides
            GROUP BY external_id
          ) t ON t.external_id = 'user:' || u.id::text
          WHERE r.rd <= $maxRd AND u.is_active
          ORDER BY r.rating - ${Glicko2.ConservativeOrderingK} * r.rd DESC,
                   r.rd ASC, u.nickname
          LIMIT ${limit.toLong}"""
      .query[(String, Double, Double, Int, Int, Int)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (nickname, rating, rd, wins, draws, losses) =>
        PlayerLeaderboardEntry(nickname, rating, rd, ResultTally(wins, draws, losses))
      })

  /** One participant's rated, decided record grouped by the row's stored `category` (V22, #335 — never by calling
    * `rating_category` per row; see that migration for the 28-second reason). Rows whose control categorises to NULL
    * are dropped rather than bucketed: an uncategorised game is casual from #280 on, and the historical ones that were
    * rated belong to no scale to be counted on.
    */
  def categoryTalliesFor(externalId: String): IO[Map[RatingCategory, ResultTally]] =
    sql"""SELECT category,
            COALESCE(SUM(CASE WHEN (white_external_id = $externalId AND result = 1)
                               OR (black_external_id = $externalId AND result = -1) THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN result = 0 THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN (white_external_id = $externalId AND result = -1)
                               OR (black_external_id = $externalId AND result = 1) THEN 1 ELSE 0 END), 0)
          FROM play.game_results
          WHERE rated = true AND result IS NOT NULL AND category IS NOT NULL
            AND (white_external_id = $externalId OR black_external_id = $externalId)
          GROUP BY 1"""
      .query[(String, Int, Int, Int)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.flatMap { case (category, wins, draws, losses) =>
        // `flatMap` over the parse, not `get`: the column is generated by `rating_category`, so it can only hold a
        // name this server knows — but a value it cannot name must drop out of a public tally rather than crash the
        // profile it feeds.
        RatingCategory.fromWireName(category).map(_ -> ResultTally(wins, draws, losses))
      }.toMap)

  /** The same participant predicate as [[categoryTalliesFor]] minus `rated = true` and the category split —
    * deliberately not derived from it (no `+casualCount` arithmetic on two separate queries): a plain COUNT is the
    * whole answer, and matching the sibling query's shape makes the relationship visible rather than implied.
    */
  def totalGamesFor(externalId: String): IO[Int] =
    sql"""SELECT count(*)
          FROM play.game_results
          WHERE result IS NOT NULL AND (white_external_id = $externalId OR black_external_id = $externalId)"""
      .query[Int]
      .unique
      .transact(xa)
      .timeout(SaveTimeout)

  // ── UserStore (#232) ────────────────────────────────────────────────────────

  /** See [[UserStore.upsertOnLogin]]. The find-or-create runs in one transaction; the three ways it can be forced to
    * retry are all resolved by retrying the WHOLE transaction rather than handling them inline (an aborted transaction
    * leaves nothing useful to run after it anyway): a nickname collision or a name currently on hold (#275) retries
    * with the next candidate, and a lost race on the `(provider, subject)` primary key retries into the winner's
    * identity via the initial find.
    */
  def upsertOnLogin(
      provider: String,
      subject: String,
      email: Option[String],
      freshNickname: IO[String]
  ): IO[UserAccount] =
    def attempt(remaining: Int): IO[UserAccount] =
      (freshNickname, IO(UUID.randomUUID().toString)).flatMapN { (nickname, newId) =>
        signIn(provider, subject, email, newId, nickname)
          .transact(xa)
          .timeout(SaveTimeout)
          .flatMap {
            case SignInOutcome.Created(user) => IO.pure(user)
            case SignInOutcome.NicknameHeld  =>
              if remaining > 1 then attempt(remaining - 1)
              else
                IO.raiseError(
                  RuntimeException(s"upsertOnLogin: $NicknameRetries candidates in a row were all on hold (#275)")
                )
          }
          .recoverWith {
            case e: SQLException if e.getSQLState == UniqueViolation && remaining > 1 => attempt(remaining - 1)
          }
      }
    attempt(NicknameRetries)

  /** `signIn`'s two outcomes (#275), spelled as data rather than an exception: a held candidate is expected, routine
    * control flow — `upsertOnLogin` is the only reader, and it just picks the next candidate — not the kind of failure
    * an exception should model.
    */
  private enum SignInOutcome:
    case Created(user: UserAccount)
    case NicknameHeld

  private def signIn(
      provider: String,
      subject: String,
      email: Option[String],
      newId: String,
      nickname: String
  ): ConnectionIO[SignInOutcome] =
    sql"""SELECT user_id::text FROM play.user_identities
          WHERE provider = $provider AND subject = $subject""".query[String].option.flatMap {
      case Some(id) =>
        // COALESCE keeps a previously-known email when the provider omits it on a later login,
        // instead of blanking the owner's own profile view.
        (sql"""UPDATE play.users SET last_login_at = now() WHERE id = $id::uuid""".update.run *>
          sql"""UPDATE play.user_identities SET email = COALESCE($email, email)
                WHERE provider = $provider AND subject = $subject""".update.run) *>
          userRow(id).map(SignInOutcome.Created.apply)
      case None =>
        // The hold (#275) must bind fresh registrations too, or a lucky word-list draw could hand a brand-new
        // account someone else's just-vacated name — the exact grab the hold exists to prevent, just via the
        // generator instead of a manual rename. `newId` is freshly minted, so it can never match a hold's own
        // `previous_owner_id`: this is equivalent to "is ANY hold on this name still active", spelled with the same
        // helper `renameTx` uses to exempt reclaiming your own just-vacated name.
        //
        // The advisory lock (same one `renameTx` takes on the name it vacates) closes the race where THIS check runs
        // moments before a rename's vacate-and-hold commits: without it, this transaction could read "not held" and
        // still finish its INSERT after that commit, handing the fresh account a name the hold now (correctly)
        // protects. See `lockNicknames` for why an advisory lock, not a row lock, is what serializes a check against a
        // name that has no row of its own to lock.
        lockNicknames(nickname) *>
          nicknameHeldByOther(nickname, requester = newId).flatMap {
            case true  => SignInOutcome.NicknameHeld.pure[ConnectionIO]
            case false =>
              (sql"""INSERT INTO play.users (id, nickname, last_login_at)
                    VALUES ($newId::uuid, $nickname, now())""".update.run *>
                sql"""INSERT INTO play.user_identities (provider, subject, user_id, email)
                      VALUES ($provider, $subject, $newId::uuid, $email)""".update.run) *>
                userRow(newId).map(SignInOutcome.Created.apply)
          }
    }

  /** The one projection both account reads share — a single site to extend when `users` grows a column, so the SELECT
    * list and the read tuple cannot drift apart between `.unique` and `.option` call sites.
    */
  private def selectUser(id: String): Query0[UserAccount] =
    sql"""SELECT id::text, nickname, created_at, last_login_at, is_active
          FROM play.users WHERE id = $id::uuid"""
      .query[(String, String, Instant, Option[Instant], Boolean)]
      .map(UserAccount.apply.tupled)

  /** `.unique` on purpose: within `signIn`'s own transaction the row it just touched cannot be absent, so a miss here
    * is a genuine invariant break worth raising, not an `Option` for callers to shrug at.
    */
  private def userRow(id: String): ConnectionIO[UserAccount] =
    selectUser(id).unique

  def userById(id: String): IO[Option[UserAccount]] =
    selectUser(id).option
      .transact(xa)
      .timeout(SaveTimeout)

  /** Case-insensitive by `lower(nickname)` — the same expression V14's unique index is built on, so this lookup uses
    * that index rather than scanning.
    */
  def byNickname(nickname: String): IO[Option[UserAccount]] =
    sql"""SELECT id::text, nickname, created_at, last_login_at, is_active
          FROM play.users WHERE lower(nickname) = lower($nickname)"""
      .query[(String, String, Instant, Option[Instant], Boolean)]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(UserAccount.apply.tupled))

  /** Account external ids to nicknames, for the public seat/opponent faces (#194 step 4).
    *
    * Only `user:` ids are looked at — a `guest:` id is dropped before the query is even built, because resolving one to
    * the nickname of the account that claimed it would deanonymise that id's whole past (see the trait's contract).
    *
    * Parsing goes through `Principal.fromUserExternalId`, which owns the format and verifies the uuid shape — these ids
    * come out of `game_results`, so the list can hold anything a client ever wrote, and a malformed one must yield "no
    * name" rather than reach a `::uuid` cast and fail the transaction, taking a whole history page with it.
    *
    * `is_active` filters deactivated accounts, which this public surface treats as absent everywhere else too.
    */
  private[store] def nicknameFragment(ids: NonEmptyList[String]): Fragment =
    fr"SELECT id::text, nickname FROM play.users WHERE is_active AND " ++ fragments.in(fr"id::text", ids)

  override def nicknamesByExternalId(externalIds: List[String]): IO[Map[String, String]] =
    val userIds = externalIds.flatMap(Principal.fromUserExternalId).distinct
    NonEmptyList
      .fromList(userIds)
      .fold(IO.pure(Map.empty[String, String])): ids =>
        nicknameFragment(ids)
          .query[(String, String)]
          .to[List]
          .transact(xa)
          .timeout(SaveTimeout)
          .map(_.map((id, nickname) => Principal.User(id).externalId -> nickname).toMap)

  /** Settled ratings for a mixed bag of seat external ids, keyed back by external id (#290) — the batched read the
    * registry samples once per game creation, same shape as [[nicknamesByExternalId]] right above.
    *
    * "Settled" is the leaderboard's own visibility rule (`rd <= maxRd`, plus `is_active` for accounts), applied here so
    * the live board can never show a rating the board itself would hide. Ids that are neither `user:<uuid>` nor
    * `bot:team:...` (guests in particular) are never queried, so they cannot come back rated no matter what is in the
    * tables.
    *
    * The rating is read in the CATEGORY OF THE GAME BEING CREATED (#280), which is what makes a seat's badge answer the
    * question a player is actually asking — "how good is this opponent at THIS speed". A participant with no row in
    * that category simply has no settled rating there, so the seat renders bare, the same as a guest's: the inner-join
    * semantics come for free from the sparse tables (V21).
    */
  private[store] def settledUserRatingsFragment(
      ids: NonEmptyList[String],
      category: RatingCategory,
      maxRd: Double
  ): Fragment =
    fr"""SELECT u.id::text, r.rating FROM play.users u
         JOIN play.user_ratings r ON r.user_id = u.id AND r.category = ${category.wireName}
         WHERE u.is_active AND r.rd <= $maxRd AND """ ++ fragments.in(fr"u.id::text", ids)

  private[store] def settledBotRatingsFragment(
      ids: NonEmptyList[String],
      category: RatingCategory,
      maxRd: Double
  ): Fragment =
    fr"""SELECT 'bot:team:' || b.team || ':' || b.name, r.rating FROM play.bots b
         JOIN play.bot_ratings r
           ON r.team = b.team AND r.name = b.name AND r.category = ${category.wireName}
         WHERE r.rd <= $maxRd AND """ ++
      fragments.in(fr"'bot:team:' || b.team || ':' || b.name", ids)

  def settledRatingsByExternalId(
      externalIds: List[String],
      category: RatingCategory,
      maxRd: Double
  ): IO[Map[String, Double]] =
    val userIds = externalIds.flatMap(Principal.fromUserExternalId).distinct
    val botIds  = externalIds.filter(id => Principal.fromBotExternalId(id).isDefined).distinct
    val users   = NonEmptyList
      .fromList(userIds)
      .fold(IO.pure(Map.empty[String, Double])): ids =>
        settledUserRatingsFragment(ids, category, maxRd)
          .query[(String, Double)]
          .to[List]
          .transact(xa)
          .timeout(SaveTimeout)
          .map(_.map((id, rating) => Principal.User(id).externalId -> rating).toMap)
    val bots = NonEmptyList
      .fromList(botIds)
      .fold(IO.pure(Map.empty[String, Double])): ids =>
        settledBotRatingsFragment(ids, category, maxRd)
          .query[(String, Double)]
          .to[List]
          .transact(xa)
          .timeout(SaveTimeout)
          .map(_.toMap)
    // Independent tables, independent reads — combine in parallel so a mixed human-vs-bot game costs one round-trip.
    (users, bots).parMapN(_ ++ _)

  def ratingOf(userId: String): IO[Option[UserRating]] =
    sql"""SELECT glicko_rating, glicko_rd, glicko_vol FROM play.users WHERE id = $userId::uuid"""
      .query[(Double, Double, Double)]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(UserRating.apply.tupled))

  /** See [[UserStore.updateNickname]]. `now` is sampled once, outside the transaction, so the cooldown check, the
    * write, and the hold/history rows it produces all agree on one instant — the same reason `Retention.tick` samples
    * its own cutoff before touching the database.
    */
  def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] =
    IO.realTimeInstant.flatMap { now =>
      renameTx(userId, nickname, now)
        .transact(xa)
        .timeout(SaveTimeout)
        .recover { case e: SQLException if e.getSQLState == UniqueViolation => NicknameUpdate.Taken }
    }

  /** Serializes any two transactions contending for the SAME nickname(s) (#275) — a fresh registration's hold check
    * racing the exact commit of the rename that creates that hold, or two renames racing to swap the same pair of
    * names. `released_nicknames` has no row for a name that has never been vacated, and `users` has no row for a name
    * nobody has claimed yet, so there is nothing to `SELECT ... FOR UPDATE` on for either side of that check — a
    * Postgres advisory lock, keyed by the name itself rather than any row, is what closes that gap.
    * `pg_advisory_xact_lock` (not the session-scoped variant) so it releases automatically at this transaction's commit
    * or rollback, exactly when the state it protects becomes visible to whoever was waiting.
    *
    * Locks are acquired case-insensitively, sorted, one at a time: sorting makes two contending transactions request
    * the SAME names in the SAME order, so one fully finishes before the other proceeds instead of each holding one lock
    * while waiting on the other's.
    */
  private def lockNicknames(names: String*): ConnectionIO[Unit] =
    names.map(_.toLowerCase).distinct.sorted.traverse_ { name =>
      sql"SELECT pg_advisory_xact_lock(hashtext($name))".query[Unit].unique
    }

  /** Is `nickname` currently held (#275) for someone other than `requester`? `requester` is the account asking — either
    * an existing owner reclaiming their own just-vacated name (never blocked by their own hold), or a brand-new
    * `upsertOnLogin` id that cannot possibly match an existing hold's `previous_owner_id`, which makes this the same
    * check as "is ANY hold on this name still active" for a fresh registration. Callers must hold `lockNicknames` on
    * `nickname` first — this check alone is not atomic with whatever the caller does with its answer.
    */
  private def nicknameHeldByOther(nickname: String, requester: String): ConnectionIO[Boolean] =
    sql"""SELECT 1 FROM play.released_nicknames
          WHERE nickname_lower = lower($nickname) AND expires_at > now() AND previous_owner_id <> $requester::uuid
          LIMIT 1""".query[Int].option.map(_.isDefined)

  /** The rename write itself (#275): cooldown, then hold, then — only past both gates — the update plus its two
    * side-effect rows, all one transaction so a crash between them can never leave a rename applied without its history
    * entry or its old name's hold.
    *
    * `FOR UPDATE` on the account's own row closes the OTHER race (#275): without it, two concurrent renames by the SAME
    * account each read `nickname_changed_at` before either writes, both see no cooldown, and both succeed — a
    * self-inflicted double rename inside the 90-day window. The second transaction's `SELECT ... FOR UPDATE` instead
    * blocks until the first commits, then re-reads the row it's about to act on and finds the cooldown the first rename
    * just started.
    *
    * A pure case change (old and new agree case-insensitively) skips every check and every side effect: nothing is
    * actually vacated, so gating it on the cooldown would let one cosmetic re-case burn the budget for a real rename —
    * and it would release the "old" name into its own hold while the same account still holds it, a hold entry that
    * describes nothing that happened.
    */
  private def renameTx(userId: String, nickname: String, now: Instant): ConnectionIO[NicknameUpdate] =
    sql"""SELECT nickname, nickname_changed_at FROM play.users WHERE id = $userId::uuid FOR UPDATE"""
      .query[(String, Option[Instant])]
      .option
      .flatMap {
        case None => NicknameUpdate.UserNotFound.pure[ConnectionIO]
        case Some((oldNickname, _)) if oldNickname.equalsIgnoreCase(nickname) =>
          sql"""UPDATE play.users SET nickname = $nickname WHERE id = $userId::uuid""".update.run
            .as(NicknameUpdate.Updated)
        case Some((oldNickname, changedAt)) =>
          val cooldownEnds = changedAt.map(_.plusSeconds(RenameCooldown.toSeconds))
          cooldownEnds match
            case Some(until) if until.isAfter(now) =>
              NicknameUpdate.CooldownActive((until.getEpochSecond - now.getEpochSecond).seconds).pure[ConnectionIO]
            case _ =>
              lockNicknames(oldNickname, nickname) *>
                nicknameHeldByOther(nickname, requester = userId).flatMap {
                  case true  => NicknameUpdate.Held.pure[ConnectionIO]
                  case false =>
                    for
                      rows <- sql"""UPDATE play.users SET nickname = $nickname, nickname_changed_at = $now
                                    WHERE id = $userId::uuid""".update.run
                      _ <- sql"""INSERT INTO play.nickname_history (user_id, old_nickname, new_nickname, changed_at)
                                    VALUES ($userId::uuid, $oldNickname, $nickname, $now)""".update.run
                      _ <- sql"""INSERT INTO play.released_nicknames (nickname_lower, previous_owner_id, released_at, expires_at)
                                    VALUES (lower($oldNickname), $userId::uuid, $now, ${now.plusSeconds(
                          NicknameHold.toSeconds
                        )})""".update.run
                    yield if rows == 0 then NicknameUpdate.UserNotFound else NicknameUpdate.Updated
                }
      }

  /** See [[UserStore.linkGuest]]. The no-op `DO UPDATE` is deliberate — unlike `DO NOTHING` it returns (and locks) the
    * existing row, so one statement answers both "claimed it" and "who already had it" without a second, racy SELECT.
    * The claim being keyed on `guest_id` alone is what makes `ClaimedByAnother` terminal.
    */
  def linkGuest(userId: String, guestId: String): IO[GuestLink] =
    sql"""INSERT INTO play.user_guest_links (guest_id, user_id)
          VALUES ($guestId::uuid, $userId::uuid)
          ON CONFLICT (guest_id) DO UPDATE SET guest_id = EXCLUDED.guest_id
          RETURNING user_id::text"""
      .query[String]
      .unique
      .transact(xa)
      .timeout(SaveTimeout)
      .map(owner => if owner == userId then GuestLink.Linked else GuestLink.ClaimedByAnother)
      .recover { case e: SQLException if e.getSQLState == ForeignKeyViolation => GuestLink.UserNotFound }

  def guestsOf(userId: String): IO[List[String]] =
    sql"""SELECT guest_id::text FROM play.user_guest_links
          WHERE user_id = $userId::uuid
          ORDER BY linked_at, guest_id"""
      .query[String]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)

  def deleteUser(userId: String): IO[Boolean] =
    sql"""DELETE FROM play.users WHERE id = $userId::uuid""".update.run
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_ > 0)

object PgGameStore:

  /** The `game_results` fields derivable from a snapshot alone — everything except `finished_at`, which the INSERT
    * leaves to the column's own `DEFAULT now()` rather than threading a captured instant through.
    */
  final private case class FinishedGame(
      whiteExternalId: String,
      blackExternalId: String,
      result: Option[Int],
      termination: String,
      rated: Boolean,
      timeControl: String,
      serverSeed: String,
      ladder: Boolean
  )

  /** `None` while the game is still active (or, for an ended snapshot, if `players` is unexpectedly missing a seat —
    * `save` logs that case separately, since it's a malformed row, not the normal "still active" path). Unlike
    * `PlaysiteIngest.payload`, this does NOT exclude aborted games from the table entirely: `game_results` is an
    * operational projection the scheduler/rating batch query, not the analytics corpus, so an aborted game is still a
    * real row (`termination = "aborted"`). It IS excluded from rating eligibility specifically — `result = None` and
    * `rated = false` regardless of what was decided at creation — since an aborted game has no sporting outcome and
    * must never hand `finishedRatedSince`'s caller a fabricated win/loss/draw.
    */
  private def finishedGameOf(snapshot: GameSnapshot): Option[FinishedGame] =
    snapshot.status match
      case GameStatus.Active                               => None
      case GameStatus.Ended(GameOver(result, termination)) =>
        val aborted = termination == Termination.Aborted
        (snapshot.players.get(Seat.White), snapshot.players.get(Seat.Black)).mapN { (white, black) =>
          FinishedGame(
            whiteExternalId = white.externalId,
            blackExternalId = black.externalId,
            result = Option.unless(aborted)(PlaysiteIngest.resultOf(result)),
            termination = PlaysiteIngest.terminationOf(termination),
            rated = !aborted && snapshot.rated.getOrElse(false),
            timeControl = snapshot.timeControl.toString,
            serverSeed = snapshot.serverSeed,
            ladder = snapshot.ladder.getOrElse(false)
          )
        }

  /** A stored snapshot's archive payload, or `Left(reason)` naming WHY there isn't one (#199). The three causes are not
    * equivalent to whoever is watching a backfill run: an aborted game is a correct, permanent skip, whereas a snapshot
    * that will not decode or is missing a seat is a data problem worth looking at. Collapsing them into one message —
    * and swallowing circe's decode error — would leave an operator scanning tens of thousands of rows with no way to
    * tell the two apart, which is exactly why `loadActive` logs its own decode failures in full.
    */
  private def archivablePayload(json: Json): Either[String, Json] =
    json.as[GameSnapshot] match
      case Left(error)     => Left(s"snapshot does not decode — investigate ($error)")
      case Right(snapshot) =>
        GameArchive.payload(snapshot) match
          case Some(payload) => Right(payload)
          case None          =>
            snapshot.status match
              case GameStatus.Ended(GameOver(_, Termination.Aborted)) =>
                Left("aborted — expected, aborted games are never archived")
              // The SQL filters on `status = 'ended'`, so an active snapshot here means the column and the JSON
              // disagree — impossible through `save`, hence worth surfacing rather than quietly counting.
              case GameStatus.Active   => Left("column says ended but the snapshot says active — investigate")
              case GameStatus.Ended(_) =>
                Left(s"ended but missing a player seat (${snapshot.players.keySet}) — investigate")

  /** The `bots` rating projection, in one place: the column list appears in three statements (one read, two updates)
    * and the tuple shape must not drift between them.
    */
  private def toBotRating(row: (Double, Double, Double, Boolean, Option[String])): BotRating =
    val (rating, rd, vol, onLadder, owner) = row
    BotRating(rating, rd, vol, onLadder, owner)

  private[store] type ResultTuple =
    (String, String, String, Option[Short], String, Boolean, String, String, Option[String], Boolean, Instant)

  private def toRow(t: ResultTuple): GameResultRow =
    val (gameId, white, black, result, termination, rated, timeControl, serverSeed, pairingId, ladder, finishedAt) = t
    // `game_results.result` is a smallint, read as such so the checker holds every query to the schema's own types;
    // the domain row exposes the Int the rest of the server reasons in.
    // Named, not positional: eleven arguments of which four are String and two Boolean, so a field reorder in
    // `GameResultRow` would bind the wrong values here and still compile.
    GameResultRow(
      gameId = GameId(gameId),
      whiteExternalId = white,
      blackExternalId = black,
      result = result.map(_.toInt),
      termination = termination,
      rated = rated,
      timeControl = timeControl,
      serverSeed = serverSeed,
      pairingId = pairingId,
      ladder = ladder,
      finishedAt = finishedAt
    )

  /** Bound on a per-event snapshot write: long enough for a slow LAN round trip, short enough that a stalled database
    * degrades the game to in-memory play instead of freezing its writer fiber.
    */
  private val SaveTimeout: FiniteDuration = 5.seconds

  /** Bound on the boot-time resume scan (one query for all live games). */
  private val BootTimeout: FiniteDuration = 30.seconds

  /** SQLSTATE values the user-account writes branch on (#232). Named string constants rather than doobie's `sqlstate`
    * catalogue because the recovery runs at the `IO` level, after `transact` — a unique violation aborts the
    * transaction, so nothing useful can be handled inside `ConnectionIO` anyway.
    */
  private val UniqueViolation: String     = "23505"
  private val ForeignKeyViolation: String = "23503"

  /** How many nickname candidates `upsertOnLogin` burns through before giving up and surfacing the violation. Distinct
    * candidates make repeat collisions geometrically unlikely; a run of five means the generator itself is broken (or
    * an identity race is livelocking, which the retry-into-find path prevents), and THAT should fail loudly.
    */
  private val NicknameRetries: Int = 5

  /** The rename guard's two windows (#275), kept equal on purpose: the fastest a freed name can legitimately reach
    * someone else is exactly as long as its previous owner would have to wait to want it back anyway.
    */
  private val RenameCooldown: FiniteDuration = 90.days
  private val NicknameHold: FiniteDuration   = 90.days

  /** Bound on one backfill batch's query/insert (#199). Generous compared with `SaveTimeout`: this is an offline
    * maintenance run scanning a large table, and unlike a live snapshot write there is no game waiting on it — a
    * spurious timeout here would just make the operator re-run a batch.
    */
  private val BackfillTimeout: FiniteDuration = 60.seconds

  /** The keyset cursor's starting point — `uuid` has no `-infinity`, so the first batch compares against the lowest
    * possible value rather than special-casing the predicate away.
    */
  private val ZeroUuid: String = "00000000-0000-0000-0000-000000000000"

  /** Connection settings, from the environment. Persistence is opt-in: with `PLAY_DB_URL` unset the server runs
    * in-memory exactly as before (games do not survive a restart).
    */
  final case class Config(url: String, user: String, password: String)

  def configFromEnv: Option[Config] =
    sys.env.get("PLAY_DB_URL").filter(_.nonEmpty).map { url =>
      Config(url, sys.env.getOrElse("PLAY_DB_USER", "play"), sys.env.getOrElse("PLAY_DB_PASSWORD", ""))
    }

  /** Migrate (Flyway owns schema `play`, creating it if absent) and open a pooled transactor. Returns the concrete
    * type: the caller wires it as the registry's `GameStore` and the deliverer's `OutboxStore`.
    */
  def resource(config: Config): Resource[IO, PgGameStore] =
    for
      _ <- Resource.eval(migrate(config))
      // A small dedicated pool for awaiting connections, so blocking waits never land on the compute pool.
      connectEC <- ExecutionContexts.fixedThreadPool[IO](4)
      xa        <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = "org.postgresql.Driver",
        url = config.url,
        user = config.user,
        pass = config.password,
        connectEC = connectEC
      )
    yield new PgGameStore(xa)

  /** Boot-time connect races are normal (compose may start the app before Postgres accepts connections; the
    * testcontainers port-forward on Rancher lags a moment), so the initial migration retries briefly before failing the
    * boot for real.
    */
  private def migrate(config: Config): IO[Unit] =
    def attempt(remaining: Int): IO[Unit] =
      IO.blocking {
        Flyway
          .configure()
          .dataSource(config.url, config.user, config.password)
          .schemas("play") // migrations and their history live in schema `play`
          .createSchemas(true)
          .load()
          .migrate()
        ()
      }.handleErrorWith { error =>
        if remaining <= 1 then IO.raiseError(error)
        else
          Console[IO].errorln(s"[play][store] database not ready (${error.getClass.getSimpleName}), retrying…") *>
            IO.sleep(1.second) *> attempt(remaining - 1)
      }
    attempt(10)
