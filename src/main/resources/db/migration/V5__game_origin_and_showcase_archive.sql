-- ADR-005 / #47: a typed, indexed game-origin projection and an archive that can hold a showcase
-- game's technical abort.
--
-- Additive and backward compatible with the pre-deployment process: every new column has a safe
-- default, nothing is dropped or renamed, and a binary that predates this migration keeps writing
-- rows that read as `origin = 'legacy'`. Every statement is also re-runnable (`IF NOT EXISTS`,
-- guarded constraints, idempotent backfills), so a retried or partially applied run converges on
-- the same schema instead of failing halfway.
--
-- The origin vocabulary is `GameOrigin.wireName` in `core/GameOrigin.scala`; the CHECK constraints
-- below pin the same six values so a typo can never become a seventh origin in the database.

-- ── 1. games: the live-snapshot origin, for startup reconciliation ───────────────────────────
ALTER TABLE games
    ADD COLUMN IF NOT EXISTS origin text NOT NULL DEFAULT 'legacy';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'games'::regclass AND conname = 'games_origin_check') THEN
        ALTER TABLE games
            ADD CONSTRAINT games_origin_check
                CHECK (origin IN ('showcase', 'ladder', 'catalog', 'lobby', 'direct', 'legacy'));
    END IF;
END
$$;

-- Backfill from the snapshot itself: rows written since origin tracking began carry it in the
-- JSON; older ladder games are recognisable by their `ladder` flag; everything else predates the
-- concept and is honestly `legacy`. Only rows still at the default are touched, so a re-run
-- changes nothing.
UPDATE games
SET origin = COALESCE(
        snapshot ->> 'origin',
        CASE WHEN (snapshot ->> 'ladder')::boolean THEN 'ladder' ELSE 'legacy' END
    )
WHERE origin = 'legacy'
  AND COALESCE(
        snapshot ->> 'origin',
        CASE WHEN (snapshot ->> 'ladder')::boolean THEN 'ladder' ELSE 'legacy' END
    ) IN ('showcase', 'ladder', 'catalog', 'lobby', 'direct');

-- The singleton showcase table's reconciliation read (#46): "is there an active showcase game?"
-- must be an index probe, never a scan of every live snapshot.
CREATE INDEX IF NOT EXISTS games_showcase_active_idx
    ON games (id)
    WHERE origin = 'showcase' AND status = 'active';

-- ── 2. game_results: the queryable projection gains the same origin ─────────────────────────
ALTER TABLE game_results
    ADD COLUMN IF NOT EXISTS origin text NOT NULL DEFAULT 'legacy';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'game_results'::regclass AND conname = 'game_results_origin_check') THEN
        ALTER TABLE game_results
            ADD CONSTRAINT game_results_origin_check
                CHECK (origin IN ('showcase', 'ladder', 'catalog', 'lobby', 'direct', 'legacy'));
    END IF;
END
$$;

-- A result row's origin is its snapshot's origin where the snapshot still exists (retention prunes
-- ended snapshots, so it may not), and otherwise the `ladder` flag it has always carried.
UPDATE game_results r
SET origin = g.origin
FROM games g
WHERE g.id = r.game_id
  AND r.origin = 'legacy'
  AND g.origin <> 'legacy';

UPDATE game_results
SET origin = 'ladder'
WHERE origin = 'legacy'
  AND ladder;

-- Later aggregation ("bot-versus-human showcase results over time") reads by origin, newest first.
CREATE INDEX IF NOT EXISTS game_results_origin_finished_idx
    ON game_results (origin, finished_at DESC);

-- ── 3. game_archive: origin plus sporting eligibility on the immutable record ─────────────────
-- Every ended showcase game is archived from here on, technical aborts included (ADR-005 §8).
-- `sporting_eligible` is what keeps such an abort out of every future score: it is stored
-- alongside the payload so a reader can filter without decoding JSON.
ALTER TABLE game_archive
    ADD COLUMN IF NOT EXISTS origin text NOT NULL DEFAULT 'legacy',
    ADD COLUMN IF NOT EXISTS sporting_eligible boolean NOT NULL DEFAULT true;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'game_archive'::regclass AND conname = 'game_archive_origin_check') THEN
        ALTER TABLE game_archive
            ADD CONSTRAINT game_archive_origin_check
                CHECK (origin IN ('showcase', 'ladder', 'catalog', 'lobby', 'direct', 'legacy'));
    END IF;
END
$$;

-- Pre-existing archive rows are all sporting (aborted games were never archived before this
-- migration), so `sporting_eligible = true` is exactly right for them; only origin is backfilled.
UPDATE game_archive a
SET origin = r.origin
FROM game_results r
WHERE r.game_id = a.game_id
  AND a.origin = 'legacy'
  AND r.origin <> 'legacy';

CREATE INDEX IF NOT EXISTS game_archive_origin_finished_idx
    ON game_archive (origin, finished_at DESC);
