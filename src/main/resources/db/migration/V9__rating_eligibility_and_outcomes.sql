-- #146: persist how a game was classified for rating and what the rating batch did with it.
--
-- Until now a `game_results` row carried one boolean (`rated`) for three different facts — what the caller asked for,
-- whether the game may move a canonical rating, and whether the batch actually moved one — and the batch's
-- `rating_applied_at` stamp was read as evidence of a numeric update although a skipped game is stamped the same way.
-- The replay audit (#145) counted 115 021 stamped rows without numbers and 39 rows the batch skipped for a reason that
-- exists only in a log line. This migration separates the facts:
--
--   white_kind / black_kind    who sat in the seat: human | bot | guest — from the stored external id's shape, never a name
--   rated_requested            what the caller asked for (NULL when the row predates the column: intent was not recorded)
--   rating_domain              competitive | training | casual | legacy — which namespace the game may move
--   rating_policy_version      the rule set that decided (1 legacy, 2 matrix; 0 = classified before any policy existed)
--   rating_outcome             pending | applied | skipped | casual | legacy — what the batch did
--   rating_skip_reason         the batch's own words when it skipped
--
-- Additive and re-runnable (`IF NOT EXISTS`, guarded constraints, idempotent backfills). Historical rows are NOT
-- reinterpreted: their domain is `legacy` and their outcome is `applied` only where the row carries the numbers that
-- prove it, `legacy` where it was stamped before outcomes were recorded, `pending` where the batch had not reached it,
-- `casual` where it was never rated. The participant kinds are the one legacy field that IS derivable — the external
-- id shape is fixed by `Principal.externalId` — and they are backfilled.

ALTER TABLE game_results
    ADD COLUMN IF NOT EXISTS white_kind            text,
    ADD COLUMN IF NOT EXISTS black_kind            text,
    ADD COLUMN IF NOT EXISTS rated_requested       boolean,
    ADD COLUMN IF NOT EXISTS rating_domain         text     NOT NULL DEFAULT 'legacy',
    ADD COLUMN IF NOT EXISTS rating_policy_version smallint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rating_outcome        text     NOT NULL DEFAULT 'legacy',
    ADD COLUMN IF NOT EXISTS rating_skip_reason    text;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'game_results'::regclass AND conname = 'game_results_white_kind_check') THEN
        ALTER TABLE game_results
            ADD CONSTRAINT game_results_white_kind_check
                CHECK (white_kind IS NULL OR white_kind IN ('human', 'bot', 'guest'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'game_results'::regclass AND conname = 'game_results_black_kind_check') THEN
        ALTER TABLE game_results
            ADD CONSTRAINT game_results_black_kind_check
                CHECK (black_kind IS NULL OR black_kind IN ('human', 'bot', 'guest'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'game_results'::regclass AND conname = 'game_results_rating_domain_check') THEN
        ALTER TABLE game_results
            ADD CONSTRAINT game_results_rating_domain_check
                CHECK (rating_domain IN ('competitive', 'training', 'casual', 'legacy'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'game_results'::regclass AND conname = 'game_results_rating_outcome_check') THEN
        ALTER TABLE game_results
            ADD CONSTRAINT game_results_rating_outcome_check
                CHECK (rating_outcome IN ('pending', 'applied', 'skipped', 'casual', 'legacy'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'game_results'::regclass AND conname = 'game_results_skip_reason_shape_check') THEN
        -- A reason exists exactly for a skip; nothing else may carry one.
        ALTER TABLE game_results
            ADD CONSTRAINT game_results_skip_reason_shape_check
                CHECK ((rating_outcome = 'skipped') = (rating_skip_reason IS NOT NULL));
    END IF;
END
$$;

-- Participant kinds from the id shape (`Principal.externalId`: `user:<uuid>`, `bot:team:<team>:<name>`,
-- `guest:<uuid>`). Only rows still unclassified are touched, so a re-run changes nothing.
UPDATE game_results
SET white_kind = CASE WHEN white_external_id LIKE 'user:%'  THEN 'human'
                      WHEN white_external_id LIKE 'bot:%'   THEN 'bot'
                      WHEN white_external_id LIKE 'guest:%' THEN 'guest' END,
    black_kind = CASE WHEN black_external_id LIKE 'user:%'  THEN 'human'
                      WHEN black_external_id LIKE 'bot:%'   THEN 'bot'
                      WHEN black_external_id LIKE 'guest:%' THEN 'guest' END
WHERE white_kind IS NULL AND black_kind IS NULL;

-- Outcomes for rows written before this migration. `legacy` is the DEFAULT, so only the provable cases move:
-- numbers on the row = applied; casual rows were never queued; an unstamped rated row is still pending. A stamped rated
-- row without numbers stays `legacy` — it was applied before per-row recording (#296) or skipped without a stored
-- reason, and the data cannot say which.
UPDATE game_results
SET rating_outcome = CASE
        WHEN NOT rated THEN 'casual'
        WHEN rating_applied_at IS NULL THEN 'pending'
        WHEN white_rating_after IS NOT NULL AND black_rating_after IS NOT NULL THEN 'applied'
        ELSE 'legacy' END
WHERE rating_outcome = 'legacy' AND rating_domain = 'legacy' AND rating_policy_version = 0
  AND (NOT rated OR rating_applied_at IS NULL OR (white_rating_after IS NOT NULL AND black_rating_after IS NOT NULL));

-- The W-D-L and profile tallies read by outcome and category now, not by the `rated` flag.
CREATE INDEX IF NOT EXISTS game_results_outcome_category_idx
    ON game_results (rating_outcome, category)
    WHERE result IS NOT NULL;
