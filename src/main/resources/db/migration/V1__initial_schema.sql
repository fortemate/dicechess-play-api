-- Fortemate Dice Chess Play API — Baseline Database Schema
-- Consolidates historical migrations V1 through V22 into a clean baseline.

-- ── 1. The Rating Category Function ──────────────────────────────────────────
-- Categorises time controls into bullet, blitz, or rapid based on estimated game duration.
CREATE FUNCTION rating_category(raw text) RETURNS text
    LANGUAGE sql
    IMMUTABLE
    RETURNS NULL ON NULL INPUT
AS
$$
SELECT CASE
           WHEN estimated IS NULL THEN NULL
           WHEN estimated < 180 THEN 'bullet'
           WHEN estimated < 480 THEN 'blitz'
           ELSE 'rapid'
           END
FROM (SELECT CASE
                 WHEN raw ~ '^Fischer\((\d+),(\d+)\)$'
                     THEN (regexp_replace(raw, '^Fischer\((\d+),(\d+)\)$', '\1'))::bigint +
                          7 * (regexp_replace(raw, '^Fischer\((\d+),(\d+)\)$', '\2'))::bigint
                 WHEN raw ~ '^SuddenDeath\((\d+)\)$'
                     THEN (regexp_replace(raw, '^SuddenDeath\((\d+)\)$', '\1'))::bigint
                 END AS estimated) e
$$;

-- ── 2. Live Games ─────────────────────────────────────────────────────────────
CREATE TABLE games (
    id         uuid PRIMARY KEY,
    status     text        NOT NULL CHECK (status IN ('active', 'ended')),
    snapshot   jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX games_active_idx ON games (status) WHERE status = 'active';

-- ── 3. Outbox (Finished Games Delivery Queue) ──────────────────────────────────
CREATE TABLE outbox (
    game_id            uuid PRIMARY KEY REFERENCES games (id) ON DELETE CASCADE,
    payload            jsonb       NOT NULL,
    attempts           int         NOT NULL DEFAULT 0,
    next_attempt_at    timestamptz NOT NULL DEFAULT now(),
    failed_permanently boolean     NOT NULL DEFAULT false,
    last_error         text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    delivered_at       timestamptz
);

CREATE INDEX outbox_due_idx ON outbox (next_attempt_at)
    WHERE delivered_at IS NULL AND NOT failed_permanently;

-- ── 4. Registered Bots ────────────────────────────────────────────────────────
CREATE TABLE bots (
    team                 text             NOT NULL,
    name                 text             NOT NULL,
    token_hash           text             NOT NULL UNIQUE,
    created_at           timestamptz      NOT NULL DEFAULT now(),
    rotated_at           timestamptz,
    glicko_rating        double precision NOT NULL DEFAULT 1500,
    glicko_rd            double precision NOT NULL DEFAULT 350,
    glicko_vol           double precision NOT NULL DEFAULT 0.06,
    on_ladder            boolean          NOT NULL DEFAULT false,
    owner_external_id    text,
    open_to_humans       boolean          NOT NULL DEFAULT false,
    description          text,
    max_concurrent_games int              NOT NULL DEFAULT 1,
    rated_for_humans     boolean          NOT NULL DEFAULT false,
    PRIMARY KEY (team, name)
);

CREATE INDEX bots_owner_idx ON bots (owner_external_id) WHERE owner_external_id IS NOT NULL;

-- ── 5. Bot Ratings (Per Category) ─────────────────────────────────────────────
CREATE TABLE bot_ratings (
    team     text             NOT NULL,
    name     text             NOT NULL,
    category text             NOT NULL,
    rating   double precision NOT NULL DEFAULT 1500,
    rd       double precision NOT NULL DEFAULT 350,
    vol      double precision NOT NULL DEFAULT 0.06,
    PRIMARY KEY (team, name, category),
    FOREIGN KEY (team, name) REFERENCES bots (team, name) ON DELETE CASCADE,
    CONSTRAINT bot_ratings_category_check CHECK (category IN ('bullet', 'blitz', 'rapid'))
);

-- ── 6. Bot Webhooks ───────────────────────────────────────────────────────────
CREATE TABLE bot_webhooks (
    team                text NOT NULL,
    name                text NOT NULL,
    url                 text NOT NULL,
    secret              text NOT NULL,
    verified_at         timestamptz NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    last_failure_at     timestamptz,
    last_failure_reason text,
    capabilities        text[] NOT NULL DEFAULT '{}',
    PRIMARY KEY (team, name),
    FOREIGN KEY (team, name) REFERENCES bots (team, name) ON DELETE CASCADE
);

-- ── 7. Webhook Delivery Telemetry & Histogram ─────────────────────────────────
CREATE TABLE bot_webhook_stats (
    team           text        NOT NULL,
    name           text        NOT NULL,
    hour           timestamptz NOT NULL,
    outcome        text        NOT NULL,
    latency_bucket smallint    NOT NULL,
    count          bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY (team, name, hour, outcome, latency_bucket),
    FOREIGN KEY (team, name) REFERENCES bots (team, name) ON DELETE CASCADE
);

CREATE INDEX bot_webhook_stats_recent_idx ON bot_webhook_stats (team, name, hour);

-- ── 8. User Accounts & Identities ─────────────────────────────────────────────
CREATE TABLE users (
    id                  uuid PRIMARY KEY,
    nickname            text             NOT NULL,
    created_at          timestamptz      NOT NULL DEFAULT now(),
    last_login_at       timestamptz,
    is_active           boolean          NOT NULL DEFAULT true,
    glicko_rating       double precision NOT NULL DEFAULT 1500,
    glicko_rd           double precision NOT NULL DEFAULT 350,
    glicko_vol          double precision NOT NULL DEFAULT 0.06,
    nickname_changed_at timestamptz
);

CREATE UNIQUE INDEX users_nickname_ci_idx ON users (lower(nickname));

CREATE TABLE user_identities (
    provider   text NOT NULL,
    subject    text NOT NULL,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    email      text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, subject)
);

CREATE INDEX user_identities_user_idx ON user_identities (user_id);

CREATE TABLE user_guest_links (
    guest_id  uuid PRIMARY KEY,
    user_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    linked_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX user_guest_links_user_idx ON user_guest_links (user_id);

CREATE TABLE user_ratings (
    user_id  uuid             NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    category text             NOT NULL,
    rating   double precision NOT NULL DEFAULT 1500,
    rd       double precision NOT NULL DEFAULT 350,
    vol      double precision NOT NULL DEFAULT 0.06,
    PRIMARY KEY (user_id, category),
    CONSTRAINT user_ratings_category_check CHECK (category IN ('bullet', 'blitz', 'rapid'))
);

-- ── 9. Nickname Rename Guard & Audit ──────────────────────────────────────────
CREATE TABLE released_nicknames (
    nickname_lower    text NOT NULL,
    previous_owner_id uuid NOT NULL,
    released_at       timestamptz NOT NULL DEFAULT now(),
    expires_at        timestamptz NOT NULL
);

CREATE INDEX released_nicknames_lookup_idx ON released_nicknames (nickname_lower, expires_at);

CREATE TABLE nickname_history (
    id           bigserial PRIMARY KEY,
    user_id      uuid NOT NULL,
    old_nickname text NOT NULL,
    new_nickname text NOT NULL,
    changed_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX nickname_history_user_idx ON nickname_history (user_id, changed_at);
CREATE INDEX nickname_history_old_name_idx ON nickname_history (lower(old_nickname));

-- ── 10. Game Results Projection ───────────────────────────────────────────────
CREATE TABLE game_results (
    game_id             uuid PRIMARY KEY,
    white_external_id   text             NOT NULL,
    black_external_id   text             NOT NULL,
    result              smallint,
    termination         text             NOT NULL,
    rated               boolean          NOT NULL,
    time_control        text             NOT NULL,
    server_seed         text             NOT NULL,
    pairing_id          uuid,
    finished_at         timestamptz      NOT NULL DEFAULT now(),
    rating_applied_at   timestamptz,
    ladder              boolean          NOT NULL DEFAULT false,
    white_rating_before double precision,
    white_rating_after  double precision,
    black_rating_before double precision,
    black_rating_after  double precision,
    category            text GENERATED ALWAYS AS (rating_category(time_control)) STORED
);

CREATE INDEX game_results_rated_finished_idx ON game_results (rated, finished_at);
CREATE INDEX game_results_white_finished_idx ON game_results (white_external_id, finished_at DESC);
CREATE INDEX game_results_black_finished_idx ON game_results (black_external_id, finished_at DESC);
CREATE INDEX game_results_pairing_idx ON game_results (pairing_id) WHERE pairing_id IS NOT NULL;
CREATE INDEX game_results_rating_queue_idx ON game_results (finished_at) WHERE rated AND rating_applied_at IS NULL;
CREATE INDEX game_results_ladder_idx ON game_results (ladder) WHERE ladder;

-- ── 11. Immutable Finished Game Archive ───────────────────────────────────────
CREATE TABLE game_archive (
    game_id     uuid PRIMARY KEY,
    payload     jsonb NOT NULL,
    finished_at timestamptz NOT NULL DEFAULT now()
);

-- ── 12. Client-Reported Games Outbox ──────────────────────────────────────────
CREATE TABLE client_reports (
    report_id          uuid PRIMARY KEY,
    payload            jsonb       NOT NULL,
    attempts           int         NOT NULL DEFAULT 0,
    next_attempt_at    timestamptz NOT NULL DEFAULT now(),
    failed_permanently boolean     NOT NULL DEFAULT false,
    last_error         text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    delivered_at       timestamptz
);

CREATE INDEX client_reports_due_idx ON client_reports (next_attempt_at)
    WHERE delivered_at IS NULL AND NOT failed_permanently;

-- ── 13. Admin Actions Audit ───────────────────────────────────────────────────
CREATE TABLE admin_actions (
    id            bigserial PRIMARY KEY,
    admin_user_id uuid NOT NULL,
    team          text NOT NULL,
    name          text NOT NULL,
    action        text NOT NULL,
    detail        text,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX admin_actions_bot_idx ON admin_actions (team, name, created_at);
