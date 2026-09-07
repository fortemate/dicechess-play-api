-- Recognition is per final source seat. No credentials or response payloads are retained here.
CREATE TABLE rematch_commands (
    source_game_id uuid NOT NULL REFERENCES rematch_sessions (source_game_id),
    seat text NOT NULL CHECK (seat IN ('White', 'Black')),
    request_id uuid NOT NULL,
    action text NOT NULL CHECK (action IN ('propose', 'accept', 'decline', 'cancel')),
    error_code text,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (source_game_id, seat, request_id)
);
CREATE INDEX rematch_commands_retention_idx ON rematch_commands (recorded_at);
