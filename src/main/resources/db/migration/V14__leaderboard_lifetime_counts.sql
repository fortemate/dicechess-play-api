-- The live, pre-epoch board needs lifetime records, not a scan of result history.
-- Block writers until the backfill and maintenance triggers are installed atomically.
LOCK TABLE game_results IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE leaderboard_lifetime_counts (
    participant_external_id text NOT NULL,
    category text NOT NULL,
    wins bigint NOT NULL CHECK (wins >= 0),
    draws bigint NOT NULL CHECK (draws >= 0),
    losses bigint NOT NULL CHECK (losses >= 0),
    PRIMARY KEY (participant_external_id, category)
);

INSERT INTO leaderboard_lifetime_counts
SELECT seat.external_id, g.category,
       sum((seat.score = 1)::int), sum((seat.score = 0)::int), sum((seat.score = -1)::int)
FROM game_results g
CROSS JOIN LATERAL (VALUES (g.white_external_id, g.result), (g.black_external_id, -g.result)) seat(external_id, score)
WHERE g.rating_outcome IN ('applied', 'legacy') AND g.rating_domain <> 'training'
  AND g.result IS NOT NULL AND g.category IS NOT NULL
GROUP BY seat.external_id, g.category;

-- Transition tables keep bulk corrections bounded by the changed rows. Combine old
-- and new contributions before updating, and lock keys in a stable order. An epoch
-- stamp or rating-only edit has zero delta and does not touch the lifetime records.
CREATE FUNCTION maintain_leaderboard_lifetime_counts() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    source_sql text;
    delta record;
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        TRUNCATE leaderboard_lifetime_counts;
        RETURN NULL;
    ELSIF TG_OP = 'INSERT' THEN
        source_sql := 'SELECT *, 1 AS direction FROM new_rows';
    ELSIF TG_OP = 'DELETE' THEN
        source_sql := 'SELECT *, -1 AS direction FROM old_rows';
    ELSE
        source_sql := 'SELECT *, 1 AS direction FROM new_rows UNION ALL SELECT *, -1 AS direction FROM old_rows';
    END IF;

    FOR delta IN EXECUTE
        'SELECT seat.external_id, g.category,
                sum(g.direction * (seat.score = 1)::int)::bigint AS wins,
                sum(g.direction * (seat.score = 0)::int)::bigint AS draws,
                sum(g.direction * (seat.score = -1)::int)::bigint AS losses
         FROM (' || source_sql || ') g
         CROSS JOIN LATERAL (VALUES (g.white_external_id, g.result), (g.black_external_id, -g.result)) seat(external_id, score)
         WHERE g.rating_outcome IN (''applied'', ''legacy'') AND g.rating_domain <> ''training''
           AND g.result IS NOT NULL AND g.category IS NOT NULL
         GROUP BY seat.external_id, g.category
         HAVING sum(g.direction * (seat.score = 1)::int) <> 0
             OR sum(g.direction * (seat.score = 0)::int) <> 0
             OR sum(g.direction * (seat.score = -1)::int) <> 0
         ORDER BY seat.external_id, g.category'
    LOOP
        -- UPDATE first: negative deltas must never be proposed as new rows,
        -- because CHECK constraints are evaluated before ON CONFLICT resolution.
        UPDATE leaderboard_lifetime_counts
        SET wins = wins + delta.wins, draws = draws + delta.draws, losses = losses + delta.losses
        WHERE participant_external_id = delta.external_id AND category = delta.category;
        IF NOT FOUND THEN
            INSERT INTO leaderboard_lifetime_counts AS counts
                (participant_external_id, category, wins, draws, losses)
            VALUES (delta.external_id, delta.category, delta.wins, delta.draws, delta.losses)
            ON CONFLICT (participant_external_id, category) DO UPDATE
            SET wins = counts.wins + EXCLUDED.wins,
                draws = counts.draws + EXCLUDED.draws,
                losses = counts.losses + EXCLUDED.losses;
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE TRIGGER leaderboard_lifetime_insert AFTER INSERT ON game_results
REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION maintain_leaderboard_lifetime_counts();
CREATE TRIGGER leaderboard_lifetime_update AFTER UPDATE ON game_results
REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION maintain_leaderboard_lifetime_counts();
CREATE TRIGGER leaderboard_lifetime_delete AFTER DELETE ON game_results
REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION maintain_leaderboard_lifetime_counts();
CREATE TRIGGER leaderboard_lifetime_truncate AFTER TRUNCATE ON game_results
FOR EACH STATEMENT EXECUTE FUNCTION maintain_leaderboard_lifetime_counts();

-- Independent extrema let PostgreSQL use index endpoints for live provenance.
CREATE INDEX game_results_lifetime_cutoff_idx ON game_results (category, finished_at DESC)
WHERE rating_outcome IN ('applied', 'legacy') AND rating_domain <> 'training';
CREATE INDEX game_results_lifetime_computed_idx ON game_results (category, (COALESCE(rating_applied_at, finished_at)) DESC)
WHERE rating_outcome IN ('applied', 'legacy') AND rating_domain <> 'training';
CREATE INDEX game_results_live_epoch_cutoff_idx ON game_results (rating_epoch, category, finished_at DESC)
WHERE rating_outcome = 'applied' AND rating_domain <> 'training';
CREATE INDEX game_results_epoch_computed_idx ON game_results (rating_epoch, category, rating_applied_at DESC)
WHERE rating_outcome = 'applied' AND rating_domain <> 'training';
