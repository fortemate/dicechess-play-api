-- #149: User training ratings against calibrated bot references.
--
-- Strict domain isolation (ADR 008, #170): human-vs-bot games update only this table and
-- never touch human competitive ratings (user_ratings), bot ratings (bot_ratings),
-- or immutable scale anchors (AnchorSet).
--
-- Exposes conservative estimates and confidence intervals, and records game counters
-- to distinguish provisional estimates (< 10 games or RD > 110).

CREATE TABLE IF NOT EXISTS user_training_ratings (
    user_id     uuid             NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category    text             NOT NULL,
    rating      double precision NOT NULL DEFAULT 1500,
    rd          double precision NOT NULL DEFAULT 350,
    vol         double precision NOT NULL DEFAULT 0.06,
    games       int              NOT NULL DEFAULT 0,
    wins        int              NOT NULL DEFAULT 0,
    draws       int              NOT NULL DEFAULT 0,
    losses      int              NOT NULL DEFAULT 0,
    updated_at  timestamptz      NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, category),
    CONSTRAINT user_training_ratings_category_check CHECK (category IN ('bullet', 'blitz', 'rapid'))
);

-- Partial index for draining unapplied training games from the rating queue.
CREATE INDEX IF NOT EXISTS game_results_training_queue_idx
    ON game_results (finished_at)
    WHERE rating_domain = 'training' AND rating_applied_at IS NULL;
