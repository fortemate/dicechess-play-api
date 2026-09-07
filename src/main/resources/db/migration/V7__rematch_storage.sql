-- Durable offers outlive the operational games snapshot and its retention sweep.
CREATE TABLE rematch_sessions (
    source_game_id uuid PRIMARY KEY,
    root_game_id uuid NOT NULL,
    source jsonb NOT NULL,
    ended_at timestamptz NOT NULL,
    phase text NOT NULL DEFAULT 'available'
        CHECK (phase IN ('available', 'offered', 'starting', 'matched', 'closed')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    consent_white boolean NOT NULL DEFAULT false,
    consent_black boolean NOT NULL DEFAULT false,
    offered_by text CHECK (offered_by IN ('White', 'Black')),
    deadline_at timestamptz NOT NULL,
    closed_reason text CHECK (closed_reason IN ('declined', 'cancelled', 'expired', 'technical_failure', 'restart')),
    successor_id uuid UNIQUE,
    CHECK ((phase = 'matched') = (successor_id IS NOT NULL)),
    CHECK ((phase = 'closed') = (closed_reason IS NOT NULL)),
    CHECK (successor_id IS NULL OR (successor_id <> source_game_id AND successor_id <> root_game_id)),
    CHECK (phase <> 'available' OR (NOT consent_white AND NOT consent_black AND offered_by IS NULL)),
    CHECK (phase <> 'offered' OR
        (consent_white <> consent_black AND offered_by IS NOT NULL AND
         ((offered_by = 'White' AND consent_white) OR (offered_by = 'Black' AND consent_black)))),
    CHECK (phase NOT IN ('starting', 'matched') OR (consent_white AND consent_black AND offered_by IS NOT NULL))
);
CREATE INDEX rematch_sessions_pending_idx ON rematch_sessions (source_game_id)
    WHERE phase IN ('offered', 'starting');

-- The immutable initial snapshot preserves the colour draw and seat access even after games is pruned.
CREATE TABLE rematch_successors (
    game_id uuid PRIMARY KEY,
    source_game_id uuid NOT NULL UNIQUE REFERENCES rematch_sessions (source_game_id),
    initial_snapshot jsonb NOT NULL,
    committed_at timestamptz NOT NULL,
    join_deadline_at timestamptz NOT NULL,
    startup_phase text NOT NULL DEFAULT 'awaiting_joins'
        CHECK (startup_phase IN ('awaiting_joins', 'active', 'aborted')),
    startup_version bigint NOT NULL DEFAULT 0 CHECK (startup_version >= 0),
    joined_white boolean NOT NULL DEFAULT false,
    joined_black boolean NOT NULL DEFAULT false,
    activated_at timestamptz,
    CHECK (game_id <> source_game_id),
    CHECK (join_deadline_at = committed_at + interval '15 seconds'),
    CHECK ((startup_phase = 'active') = (activated_at IS NOT NULL)),
    CHECK (startup_phase <> 'active' OR (joined_white AND joined_black)),
    UNIQUE (source_game_id, game_id)
);
ALTER TABLE rematch_sessions ADD CONSTRAINT rematch_successor_matches_source
    FOREIGN KEY (source_game_id, successor_id) REFERENCES rematch_successors (source_game_id, game_id);
CREATE INDEX rematch_successors_pending_idx ON rematch_successors (game_id)
    WHERE startup_phase <> 'active';
-- No FK to games: ended snapshots are disposable; rematch identity/credentials and history are not.
