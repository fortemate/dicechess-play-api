-- Independent Flyway location: never included in the classic db/migration history.
CREATE TABLE laboratory.games (
    game_id uuid PRIMARY KEY,
    variant_id text NOT NULL CHECK (variant_id = 'choice-2x2d6'),
    variant_revision integer NOT NULL CHECK (variant_revision = 1),
    protocol_version integer NOT NULL CHECK (protocol_version = 1),
    rules_version text NOT NULL CHECK (length(trim(rules_version)) > 0),
    dice_algorithm text NOT NULL CHECK (dice_algorithm = 'choice-2x2d6-hmac-sha256-v1'),
    time_control jsonb NOT NULL,
    version bigint NOT NULL CHECK (version BETWEEN 0 AND 9007199254740991),
    status text NOT NULL CHECK (status IN ('active', 'ended')),
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    UNIQUE (game_id, variant_id, variant_revision)
);
CREATE INDEX laboratory_games_recovery ON laboratory.games (game_id) WHERE status = 'active';
CREATE INDEX laboratory_games_expiry ON laboratory.games (expires_at, game_id);
CREATE INDEX laboratory_games_variant ON laboratory.games (variant_id, variant_revision, created_at);

CREATE TABLE laboratory.transcripts (
    game_id uuid PRIMARY KEY,
    variant_id text NOT NULL,
    variant_revision integer NOT NULL,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    FOREIGN KEY (game_id, variant_id, variant_revision)
        REFERENCES laboratory.games (game_id, variant_id, variant_revision) ON DELETE CASCADE
);
CREATE INDEX laboratory_transcripts_variant ON laboratory.transcripts (variant_id, variant_revision);

CREATE TABLE laboratory.results (
    game_id uuid PRIMARY KEY,
    variant_id text NOT NULL,
    variant_revision integer NOT NULL,
    result integer CHECK (result IN (-1, 0, 1)),
    termination text NOT NULL CHECK (termination IN ('KingCaptured', 'Resign', 'Draw', 'Aborted', 'Timeout')),
    finished_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((termination = 'Aborted') = (result IS NULL)),
    FOREIGN KEY (game_id, variant_id, variant_revision)
        REFERENCES laboratory.games (game_id, variant_id, variant_revision) ON DELETE CASCADE
);
CREATE INDEX laboratory_results_variant ON laboratory.results (variant_id, variant_revision, finished_at);

CREATE TABLE laboratory.rematches (
    source_game_id uuid PRIMARY KEY,
    successor_game_id uuid NOT NULL UNIQUE REFERENCES laboratory.games (game_id) ON DELETE CASCADE,
    variant_id text NOT NULL,
    variant_revision integer NOT NULL,
    conditions jsonb NOT NULL,
    CHECK (source_game_id <> successor_game_id),
    FOREIGN KEY (source_game_id, variant_id, variant_revision)
        REFERENCES laboratory.games (game_id, variant_id, variant_revision) ON DELETE CASCADE,
    FOREIGN KEY (successor_game_id, variant_id, variant_revision)
        REFERENCES laboratory.games (game_id, variant_id, variant_revision)
);
