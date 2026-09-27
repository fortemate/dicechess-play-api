-- #189: persist the bot reference a training update is computed against, captured when the game is queued.
--
-- #149 resolved the reference while draining the queue: the bot's CURRENT stored category rating, or its anchor target
-- in whatever anchor set the batch was built with. A delayed drain, a retry after an outage or an anchor-set change
-- therefore graded the human against a strength the game never faced, and nothing on the row said what had been used.
-- The reference is now resolved in the transaction that writes the row (`PgGameStore.save`), which is the moment the
-- row is queued, and the batch applies exactly what is stored here:
--
--   training_reference_source           anchor | bot_rating | unavailable (no reference existed when the game ended);
--                                       NULL when the row predates this migration or is not a training row
--   training_reference_anchor_set       the anchor-set version, for an anchor reference only
--   training_reference_anchor_epoch     that anchor set's scale epoch, for an anchor reference only
--   training_reference_rating/_rd/_vol  the Glicko-2 state the update is computed against
--
-- Additive, with no backfill: a training row queued before this migration keeps NULL, and the batch keeps resolving its
-- reference exactly as it did. Its provenance is unknown and is recorded as unknown, never invented (the rule V9 set for
-- `rating_domain`).

ALTER TABLE game_results
    ADD COLUMN IF NOT EXISTS training_reference_source       text,
    ADD COLUMN IF NOT EXISTS training_reference_anchor_set   text,
    ADD COLUMN IF NOT EXISTS training_reference_anchor_epoch integer,
    ADD COLUMN IF NOT EXISTS training_reference_rating       double precision,
    ADD COLUMN IF NOT EXISTS training_reference_rd           double precision,
    ADD COLUMN IF NOT EXISTS training_reference_vol          double precision;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'game_results'::regclass
                     AND conname = 'game_results_training_reference_source_check') THEN
        ALTER TABLE game_results
            ADD CONSTRAINT game_results_training_reference_source_check
                CHECK (training_reference_source IN ('anchor', 'bot_rating', 'unavailable'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'game_results'::regclass
                     AND conname = 'game_results_training_reference_domain_check') THEN
        -- Only a training row is applied against a bot reference.
        ALTER TABLE game_results
            ADD CONSTRAINT game_results_training_reference_domain_check
                CHECK (training_reference_source IS NULL OR rating_domain = 'training');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'game_results'::regclass
                     AND conname = 'game_results_training_reference_shape_check') THEN
        -- Each source carries exactly its own fields: an anchor names its set and epoch, a stored bot rating does not,
        -- and a missing reference, like a row that recorded none, carries no numbers at all.
        ALTER TABLE game_results
            ADD CONSTRAINT game_results_training_reference_shape_check
                CHECK (CASE training_reference_source
                           WHEN 'anchor' THEN training_reference_anchor_set IS NOT NULL
                                          AND training_reference_anchor_epoch IS NOT NULL
                                          AND training_reference_rating IS NOT NULL
                                          AND training_reference_rd IS NOT NULL
                                          AND training_reference_vol IS NOT NULL
                           WHEN 'bot_rating' THEN training_reference_anchor_set IS NULL
                                              AND training_reference_anchor_epoch IS NULL
                                              AND training_reference_rating IS NOT NULL
                                              AND training_reference_rd IS NOT NULL
                                              AND training_reference_vol IS NOT NULL
                           ELSE training_reference_anchor_set IS NULL
                            AND training_reference_anchor_epoch IS NULL
                            AND training_reference_rating IS NULL
                            AND training_reference_rd IS NULL
                            AND training_reference_vol IS NULL
                       END);
    END IF;
END
$$;
