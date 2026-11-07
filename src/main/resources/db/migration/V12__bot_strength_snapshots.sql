-- #150: Versioned rating snapshots for bot strength (ADR 008, #170).
--
-- Immutable snapshots of retrospective AnchoredStrength results computed by
-- LadderReportMain / offline pipeline against AnchorSet v1.0.
-- Published via GET /leaderboard and historical snapshot endpoints.

CREATE TABLE IF NOT EXISTS bot_strength_snapshots (
    id                  bigserial        PRIMARY KEY,
    category            text             NOT NULL,
    epoch               integer          NOT NULL,
    anchor_set_version  text             NOT NULL,
    cutoff              timestamptz      NOT NULL,
    computed_at         timestamptz      NOT NULL DEFAULT now(),
    is_current          boolean          NOT NULL DEFAULT false,
    payload             jsonb            NOT NULL,
    CONSTRAINT bot_strength_snapshots_category_check CHECK (category IN ('bullet', 'blitz', 'rapid')),
    CONSTRAINT bot_strength_snapshots_epoch_check CHECK (epoch >= 1)
);

CREATE UNIQUE INDEX IF NOT EXISTS bot_strength_snapshots_cutoff_idx
    ON bot_strength_snapshots (category, epoch, cutoff);

CREATE UNIQUE INDEX IF NOT EXISTS bot_strength_snapshots_current_idx
    ON bot_strength_snapshots (category, epoch)
    WHERE is_current = true;

CREATE INDEX IF NOT EXISTS bot_strength_snapshots_lookup_idx
    ON bot_strength_snapshots (category, epoch, computed_at DESC);

-- #150: Record calculation timestamp for user training estimates independently from game finish cutoff.
ALTER TABLE user_training_ratings ADD COLUMN IF NOT EXISTS computed_at timestamptz;

-- #150: Track rating deviation (RD) changes alongside ratings for historical cutoff consistency.
ALTER TABLE game_results ADD COLUMN IF NOT EXISTS white_rd_before double precision;
ALTER TABLE game_results ADD COLUMN IF NOT EXISTS white_rd_after double precision;
ALTER TABLE game_results ADD COLUMN IF NOT EXISTS black_rd_before double precision;
ALTER TABLE game_results ADD COLUMN IF NOT EXISTS black_rd_after double precision;

-- #150: Backfill historical RD for the latest applied game of each participant at migration time.
-- Only the most recent applied game corresponds to the current rating deviation in bot_ratings / user_ratings;
-- older historical games have unknown RD and must remain NULL so that historical cutoffs do not falsely
-- treat formerly provisional participants as converged.
WITH latest_bot_games AS (
  SELECT DISTINCT ON (r.team, r.name, r.category)
    g.game_id,
    r.rd,
    CASE WHEN g.white_external_id = 'bot:team:' || r.team || ':' || r.name THEN 'white' ELSE 'black' END AS seat
  FROM bot_ratings r
  JOIN game_results g
    ON g.category = r.category
   AND g.rating_outcome IN ('applied', 'legacy')
   AND (
     (g.white_external_id = 'bot:team:' || r.team || ':' || r.name AND g.white_rating_after IS NOT NULL)
     OR
     (g.black_external_id = 'bot:team:' || r.team || ':' || r.name AND g.black_rating_after IS NOT NULL)
   )
  ORDER BY r.team, r.name, r.category, g.finished_at DESC, g.rating_applied_at DESC NULLS LAST, g.game_id DESC
)
UPDATE game_results g
SET white_rd_after = l.rd
FROM latest_bot_games l
WHERE g.game_id = l.game_id
  AND l.seat = 'white'
  AND g.white_rd_after IS NULL;

WITH latest_bot_games AS (
  SELECT DISTINCT ON (r.team, r.name, r.category)
    g.game_id,
    r.rd,
    CASE WHEN g.white_external_id = 'bot:team:' || r.team || ':' || r.name THEN 'white' ELSE 'black' END AS seat
  FROM bot_ratings r
  JOIN game_results g
    ON g.category = r.category
   AND g.rating_outcome IN ('applied', 'legacy')
   AND (
     (g.white_external_id = 'bot:team:' || r.team || ':' || r.name AND g.white_rating_after IS NOT NULL)
     OR
     (g.black_external_id = 'bot:team:' || r.team || ':' || r.name AND g.black_rating_after IS NOT NULL)
   )
  ORDER BY r.team, r.name, r.category, g.finished_at DESC, g.rating_applied_at DESC NULLS LAST, g.game_id DESC
)
UPDATE game_results g
SET black_rd_after = l.rd
FROM latest_bot_games l
WHERE g.game_id = l.game_id
  AND l.seat = 'black'
  AND g.black_rd_after IS NULL;

WITH latest_user_games AS (
  SELECT DISTINCT ON (r.user_id, r.category)
    g.game_id,
    r.rd,
    CASE WHEN g.white_external_id = 'user:' || r.user_id::text THEN 'white' ELSE 'black' END AS seat
  FROM user_ratings r
  JOIN game_results g
    ON g.category = r.category
   AND g.rating_outcome IN ('applied', 'legacy')
   AND (
     (g.white_external_id = 'user:' || r.user_id::text AND g.white_rating_after IS NOT NULL)
     OR
     (g.black_external_id = 'user:' || r.user_id::text AND g.black_rating_after IS NOT NULL)
   )
  ORDER BY r.user_id, r.category, g.finished_at DESC, g.rating_applied_at DESC NULLS LAST, g.game_id DESC
)
UPDATE game_results g
SET white_rd_after = l.rd
FROM latest_user_games l
WHERE g.game_id = l.game_id
  AND l.seat = 'white'
  AND g.white_rd_after IS NULL;

WITH latest_user_games AS (
  SELECT DISTINCT ON (r.user_id, r.category)
    g.game_id,
    r.rd,
    CASE WHEN g.white_external_id = 'user:' || r.user_id::text THEN 'white' ELSE 'black' END AS seat
  FROM user_ratings r
  JOIN game_results g
    ON g.category = r.category
   AND g.rating_outcome IN ('applied', 'legacy')
   AND (
     (g.white_external_id = 'user:' || r.user_id::text AND g.white_rating_after IS NOT NULL)
     OR
     (g.black_external_id = 'user:' || r.user_id::text AND g.black_rating_after IS NOT NULL)
   )
  ORDER BY r.user_id, r.category, g.finished_at DESC, g.rating_applied_at DESC NULLS LAST, g.game_id DESC
)
UPDATE game_results g
SET black_rd_after = l.rd
FROM latest_user_games l
WHERE g.game_id = l.game_id
  AND l.seat = 'black'
  AND g.black_rd_after IS NULL;
