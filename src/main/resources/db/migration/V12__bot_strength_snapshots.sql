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
