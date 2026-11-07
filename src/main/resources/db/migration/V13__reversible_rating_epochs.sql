-- #151: staged epoch manifests and active projections. No epoch is activated by migration.
CREATE TABLE rating_epoch_runs (
    run_id uuid PRIMARY KEY,
    plan_sha256 text NOT NULL UNIQUE,
    phase text NOT NULL CHECK (phase IN ('staged', 'applied', 'rolled_back')),
    source_revision bigint NOT NULL,
    source_state_sha256 text NOT NULL,
    target_epoch integer NOT NULL CHECK (target_epoch > 0),
    cutoff timestamptz NOT NULL,
    anchor_set_version text NOT NULL,
    plan jsonb NOT NULL,
    before_state jsonb,
    before_state_sha256 text,
    after_state_sha256 text,
    applied_revision bigint,
    staged_at timestamptz NOT NULL DEFAULT now(),
    applied_at timestamptz,
    rolled_back_at timestamptz
);

CREATE TABLE rating_epoch_control (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    revision bigint NOT NULL DEFAULT 0,
    paused boolean NOT NULL DEFAULT false,
    active_epoch integer CHECK (active_epoch > 0),
    active_run_id uuid REFERENCES rating_epoch_runs(run_id),
    changed_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((active_epoch IS NULL) = (active_run_id IS NULL))
);
INSERT INTO rating_epoch_control (singleton) VALUES (true);

CREATE TABLE rating_epoch_counts (
    participant_external_id text NOT NULL,
    category text NOT NULL CHECK (category IN ('bullet', 'blitz', 'rapid')),
    games bigint NOT NULL CHECK (games >= 0),
    wins bigint NOT NULL CHECK (wins >= 0),
    draws bigint NOT NULL CHECK (draws >= 0),
    losses bigint NOT NULL CHECK (losses >= 0),
    PRIMARY KEY (participant_external_id, category),
    CHECK (games = wins + draws + losses)
);

-- Application provenance of future movements; existing recorded histories remain untouched.
ALTER TABLE game_results ADD COLUMN rating_epoch integer CHECK (rating_epoch > 0);
CREATE INDEX game_results_epoch_cutoff_idx ON game_results (rating_epoch, category, finished_at DESC)
    WHERE rating_epoch IS NOT NULL;

-- The process-level pause/drain procedure remains mandatory. This database fence also rejects
-- an old binary or a calculation which started before the active projection was replaced.
-- Transaction-local settings are internal coordination, not an authorization boundary.
CREATE FUNCTION rating_epoch_projection_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    ctl record;
    supplied text;
BEGIN
    IF current_setting('play.rating_epoch_admin', true) = 'on' THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;
    SELECT * INTO STRICT ctl FROM play.rating_epoch_control WHERE singleton FOR UPDATE;
    -- Privacy deletion and its FK cascades must remain possible even while ratings are paused.
    IF TG_OP = 'DELETE' THEN
        UPDATE play.rating_epoch_control SET revision = revision + 1, changed_at = now() WHERE singleton;
        RETURN OLD;
    END IF;
    IF ctl.paused THEN RAISE EXCEPTION 'rating epoch writes are paused'; END IF;
    supplied := current_setting('play.rating_epoch_revision', true);
    IF supplied IS NOT NULL AND supplied <> '' THEN
        IF supplied <> ctl.revision::text THEN RAISE EXCEPTION 'stale rating epoch revision'; END IF;
    ELSIF ctl.active_epoch IS NOT NULL THEN
        RAISE EXCEPTION 'active rating epoch requires a fenced writer';
    ELSE
        -- Pre-cutover old binaries remain supported, but invalidate staged source revisions.
        UPDATE play.rating_epoch_control SET revision = revision + 1, changed_at = now() WHERE singleton;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER bot_ratings_epoch_guard BEFORE INSERT OR UPDATE OR DELETE ON bot_ratings
    FOR EACH ROW EXECUTE FUNCTION rating_epoch_projection_guard();
CREATE TRIGGER user_ratings_epoch_guard BEFORE INSERT OR UPDATE OR DELETE ON user_ratings
    FOR EACH ROW EXECUTE FUNCTION rating_epoch_projection_guard();
CREATE TRIGGER training_ratings_epoch_guard BEFORE INSERT OR UPDATE OR DELETE ON user_training_ratings
    FOR EACH ROW EXECUTE FUNCTION rating_epoch_projection_guard();
CREATE TRIGGER rating_counts_epoch_guard BEFORE INSERT OR UPDATE OR DELETE ON rating_epoch_counts
    FOR EACH ROW EXECUTE FUNCTION rating_epoch_projection_guard();
CREATE TRIGGER game_results_epoch_guard BEFORE UPDATE OF rating_applied_at,
    white_rating_before, white_rating_after, black_rating_before, black_rating_after,
    white_rd_before, white_rd_after, black_rd_before, black_rd_after, rating_epoch ON game_results
    FOR EACH ROW EXECUTE FUNCTION rating_epoch_projection_guard();

-- Counts have public-history identities rather than FK keys. Remove their active projection on
-- account/bot deletion as well; immutable game history follows the existing retention policy.
CREATE FUNCTION rating_epoch_delete_counts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'users' THEN
        DELETE FROM play.rating_epoch_counts WHERE participant_external_id = 'user:' || OLD.id::text;
    ELSE
        DELETE FROM play.rating_epoch_counts WHERE participant_external_id = 'bot:team:' || OLD.team || ':' || OLD.name;
    END IF;
    RETURN OLD;
END
$$;
CREATE TRIGGER users_epoch_count_cleanup BEFORE DELETE ON users
    FOR EACH ROW EXECUTE FUNCTION rating_epoch_delete_counts();
CREATE TRIGGER bots_epoch_count_cleanup BEFORE DELETE ON bots
    FOR EACH ROW EXECUTE FUNCTION rating_epoch_delete_counts();

-- Participant eligibility is read before the projection transaction as well. Ownership changes,
-- deactivation, deletion/re-registration and scheduler membership invalidate that calculation.
-- Never block a privacy deletion, including an account which has no rating projection yet.
CREATE FUNCTION rating_epoch_lifecycle_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('play.rating_epoch_admin', true) IS DISTINCT FROM 'on' THEN
        UPDATE play.rating_epoch_control SET revision = revision + 1, changed_at = now() WHERE singleton;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END
$$;
CREATE TRIGGER bots_epoch_lifecycle BEFORE INSERT OR DELETE OR
    UPDATE OF incarnation_id, ownership_generation, owner_external_id, on_ladder ON bots
    FOR EACH ROW EXECUTE FUNCTION rating_epoch_lifecycle_changed();
CREATE TRIGGER users_epoch_lifecycle BEFORE INSERT OR DELETE OR UPDATE OF is_active, created_at ON users
    FOR EACH ROW EXECUTE FUNCTION rating_epoch_lifecycle_changed();
