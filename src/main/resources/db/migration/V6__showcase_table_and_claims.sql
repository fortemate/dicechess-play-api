-- ADR-005 / #46: the singleton showcase table's durable state and its claim idempotency records.
--
-- Additive: nothing existing is touched, and a binary that predates this migration never reads either table. Every
-- statement is re-runnable (`IF NOT EXISTS`, `ON CONFLICT DO NOTHING`), so a retried or partially applied run
-- converges on the same schema.

-- ── 1. showcase_table: exactly one row, by constraint ────────────────────────────────────────
-- The table's identity IS the row: `id = 1` is the only value the CHECK admits, so a second table cannot be
-- recorded. `next_human_color` is the durable half of ADR-005 §6 (the colour alternates only when a claim's
-- transaction commits, and survives a restart); `current_game_id` is the claim fence of §5 — a claim commits only by
-- moving it from NULL to the new game in the same statement that advances the colour, so two processes cannot both
-- record a game as current. It is cleared once the game's terminal transaction has committed and the coordinator has
-- observed it (§7 barrier 4). Deliberately no foreign key to `games`: a stale pointer left by a crash between the
-- terminal commit and the clear is repaired by startup reconciliation, and a cascade would hide exactly that case.
CREATE TABLE IF NOT EXISTS showcase_table (
    id               smallint    PRIMARY KEY DEFAULT 1,
    next_human_color text        NOT NULL DEFAULT 'white',
    current_game_id  uuid,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT showcase_table_singleton_check        CHECK (id = 1),
    CONSTRAINT showcase_table_next_human_color_check CHECK (next_human_color IN ('white', 'black'))
);

-- The first human plays White (ADR-005 §6). Idempotent, so a re-run never resets an alternation in progress.
INSERT INTO showcase_table (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- ── 2. showcase_claims: durable idempotency records, keyed by (actor, key) ───────────────────
-- One row per processed `POST /showcase/claim`, whatever its outcome, so a same-key retry replays the committed
-- answer instead of creating another room or advancing the colour twice (ADR-005 §5). `request_hash` is what turns a
-- key reused with a different payload into `409 idempotency_conflict`. Rows expire after the 24-hour retention window
-- and are pruned opportunistically by the claim path itself; an expired key reads as a fresh claim. `game_id` has no
-- foreign key for the same reason `game_results` has none: the record must outlive the snapshot retention prunes.
CREATE TABLE IF NOT EXISTS showcase_claims (
    actor_id        text        NOT NULL,
    idempotency_key uuid        NOT NULL,
    request_hash    text        NOT NULL,
    outcome         text        NOT NULL,
    game_id         uuid,
    human_color     text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    PRIMARY KEY (actor_id, idempotency_key),
    CONSTRAINT showcase_claims_outcome_check     CHECK (outcome IN ('claimed', 'spectating')),
    CONSTRAINT showcase_claims_human_color_check CHECK (human_color IS NULL OR human_color IN ('white', 'black')),
    CONSTRAINT showcase_claims_claimed_shape_check
        CHECK (outcome <> 'claimed' OR (game_id IS NOT NULL AND human_color IS NOT NULL)),
    CONSTRAINT showcase_claims_expiry_check      CHECK (expires_at > created_at)
);

-- The prune reads by expiry, never by actor.
CREATE INDEX IF NOT EXISTS showcase_claims_expires_idx ON showcase_claims (expires_at);
