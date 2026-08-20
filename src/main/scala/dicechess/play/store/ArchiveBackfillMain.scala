package dicechess.play.store

import cats.effect.{ExitCode, IO, IOApp}
import dicechess.play.core.GameId

/** Owner-facing one-off backfill runner (#199) — NOT a public endpoint, and deliberately not wired into server startup:
  * a data-repair run against production is an operator action, not a side effect of a deploy.
  *
  * `game_archive` (#177) is written at game end, so it covers only games that finished after that feature reached
  * production. Every older finished game shows "history unavailable" on the replay page (dicechess-play#163) even
  * though its full per-turn history is still in `play.games.snapshot` — until #179 prunes those snapshots, at which
  * point the data is gone for good. This walks the remaining ended snapshots and writes the archive rows they never
  * got.
  *
  * Run: `mise run archive:backfill [batchSize]` (default 500) with `PLAY_DB_URL`/`PLAY_DB_USER`/`PLAY_DB_PASSWORD` set.
  * Safe to interrupt and safe to re-run: every row is inserted `ON CONFLICT DO NOTHING` in its own transaction, and the
  * keyset cursor restarts from the beginning, skipping what already exists. All the logic (and the reason the cursor is
  * a `game_id` rather than an offset) lives in `PgGameStore.backfillArchive`; this file is a thin shell, name-excluded
  * from coverage like `Main.scala`.
  */
object ArchiveBackfillMain extends IOApp:

  private val DefaultBatchSize = 500

  def run(args: List[String]): IO[ExitCode] =
    val batchSize = args.headOption.flatMap(_.toIntOption).filter(_ > 0).getOrElse(DefaultBatchSize)
    PgGameStore.configFromEnv match
      case None =>
        IO.println("[backfill] PLAY_DB_URL unset: nothing to back-fill").as(ExitCode.Error)
      case Some(dbConfig) =>
        PgGameStore
          .resource(dbConfig)
          .use(store =>
            IO.println(s"[backfill] starting, batch size $batchSize") *>
              loop(store, after = None, batchSize, scanned = 0, inserted = 0, skipped = 0)
          )
          .as(ExitCode.Success)

  /** Drains batch after batch until one comes back empty, carrying the cursor forward. Recursive rather than
    * `Stream`/`iterateWhile`: the running totals and the cursor advance together, and each step logs its own progress
    * line so an operator watching a long run can see it move.
    */
  private def loop(
      store: PgGameStore,
      after: Option[GameId],
      batchSize: Int,
      scanned: Int,
      inserted: Int,
      skipped: Int
  ): IO[Unit] =
    store.backfillArchive(after, batchSize).flatMap { batch =>
      if batch.scanned == 0 then IO.println(s"[backfill] done — scanned $scanned, inserted $inserted, skipped $skipped")
      else
        val totals = (scanned + batch.scanned, inserted + batch.inserted, skipped + batch.skipped)
        IO.println(s"[backfill] +${batch.inserted} (skipped ${batch.skipped}) — total ${totals._2}") *>
          loop(store, batch.lastId, batchSize, totals._1, totals._2, totals._3)
    }
