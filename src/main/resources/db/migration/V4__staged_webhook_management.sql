-- ADR-004 / #36: an additive, generation-aware webhook control plane.
-- Existing active URL, secret, verified_at and capabilities values are not changed.

ALTER TABLE bots
    ADD COLUMN incarnation_id uuid NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN webhook_revision uuid NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN ownership_generation bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT bots_webhook_incarnation_unique UNIQUE (team, name, incarnation_id);

ALTER TABLE bot_webhooks
    ADD COLUMN registration_id uuid NOT NULL DEFAULT gen_random_uuid();

CREATE TABLE bot_webhook_setups (
    setup_id                 uuid PRIMARY KEY,
    team                     text        NOT NULL,
    name                     text        NOT NULL,
    bot_incarnation_id       uuid        NOT NULL,
    kind                     text,
    actor_kind               text,
    actor_id                 text,
    authority_generation     text,
    activation_revision      uuid,
    candidate_url            text,
    candidate_secret         text,
    candidate_capabilities   text[],
    created_at               timestamptz,
    expires_at               timestamptz,
    activation_attempts      int         DEFAULT 0,
    lease_id                 uuid,
    lease_expires_at         timestamptz,
    status                   text        NOT NULL DEFAULT 'pending',
    terminated_at            timestamptz,
    CONSTRAINT bot_webhook_setups_incarnation_fk
        FOREIGN KEY (team, name, bot_incarnation_id)
        REFERENCES bots (team, name, incarnation_id) ON DELETE RESTRICT,
    CONSTRAINT bot_webhook_setups_kind_check
        CHECK (kind IS NULL OR kind IN ('create', 'replaceUrl', 'rotateSecret')),
    CONSTRAINT bot_webhook_setups_actor_kind_check
        CHECK (actor_kind IS NULL OR actor_kind IN ('owner', 'admin')),
    CONSTRAINT bot_webhook_setups_status_check
        CHECK (status IN ('pending', 'activated', 'cancelled', 'expired', 'invalidated', 'attempts_exhausted')),
    CONSTRAINT bot_webhook_setups_attempts_check
        CHECK (activation_attempts IS NULL OR (activation_attempts >= 0 AND activation_attempts <= 5)),
    CONSTRAINT bot_webhook_setups_capabilities_check
        CHECK (
            candidate_capabilities IS NULL
            OR candidate_capabilities = ARRAY[]::text[]
            OR candidate_capabilities = ARRAY['draws']::text[]
        ),
    CONSTRAINT bot_webhook_setups_lease_check
        CHECK ((lease_id IS NULL) = (lease_expires_at IS NULL)),
    CONSTRAINT bot_webhook_setups_expiry_check
        CHECK (status <> 'pending' OR expires_at > created_at),
    CONSTRAINT bot_webhook_setups_candidate_shape_check
        CHECK (
            status <> 'pending'
            OR (kind = 'create' AND candidate_capabilities IS NOT NULL)
            OR (kind IN ('replaceUrl', 'rotateSecret') AND candidate_capabilities IS NULL)
        ),
    CONSTRAINT bot_webhook_setups_leased_attempt_check
        CHECK (lease_id IS NULL OR activation_attempts > 0),
    CONSTRAINT bot_webhook_setups_redaction_check
        CHECK (
            (
                status = 'pending'
                AND kind IS NOT NULL
                AND actor_kind IS NOT NULL
                AND actor_id IS NOT NULL
                AND authority_generation IS NOT NULL
                AND activation_revision IS NOT NULL
                AND candidate_url IS NOT NULL
                AND candidate_secret IS NOT NULL
                AND created_at IS NOT NULL
                AND expires_at IS NOT NULL
                AND activation_attempts IS NOT NULL
                AND terminated_at IS NULL
            )
            OR
            (
                status <> 'pending'
                AND kind IS NULL
                AND actor_kind IS NULL
                AND actor_id IS NULL
                AND authority_generation IS NULL
                AND activation_revision IS NULL
                AND candidate_url IS NULL
                AND candidate_secret IS NULL
                AND candidate_capabilities IS NULL
                AND created_at IS NULL
                AND expires_at IS NULL
                AND activation_attempts IS NULL
                AND lease_id IS NULL
                AND lease_expires_at IS NULL
                AND terminated_at IS NOT NULL
            )
        )
);

CREATE UNIQUE INDEX bot_webhook_setups_one_pending_idx
    ON bot_webhook_setups (team, name)
    WHERE status = 'pending';

CREATE INDEX bot_webhook_setups_tombstone_expiry_idx
    ON bot_webhook_setups (terminated_at)
    WHERE status <> 'pending';

-- Drives the heartbeat's bounded expiry sweep. Without it an abandoned candidate keeps its plaintext
-- secret until something else happens to touch that bot; the partial predicate keeps the index at one
-- entry per bot, because bot_webhook_setups_one_pending_idx already caps pending rows at one.
CREATE INDEX bot_webhook_setups_pending_expiry_idx
    ON bot_webhook_setups (expires_at)
    WHERE status = 'pending';

-- Every live API generation heartbeats its admin-allowlist digest here. Admin webhook authority is
-- deliberately fail-closed while two different generations overlap during a rolling rollout; once
-- the old generation stops heartbeating, the surviving generation becomes authoritative.
CREATE TABLE webhook_admin_authority_generations (
    authority_generation text        PRIMARY KEY
        CHECK (authority_generation ~ '^[0-9a-f]{64}$'),
    heartbeat_at         timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX webhook_admin_authority_heartbeat_idx
    ON webhook_admin_authority_generations (heartbeat_at);

-- Fixed-window, cross-instance verification budgets. The key is deliberately opaque to this table;
-- callers minimise the actor+bot or source-IP dimension before consuming it.
CREATE TABLE webhook_verification_budgets (
    budget_kind       text        NOT NULL,
    budget_key        text        NOT NULL,
    window_started_at timestamptz NOT NULL,
    window_expires_at timestamptz NOT NULL,
    attempts          int         NOT NULL,
    PRIMARY KEY (budget_kind, budget_key),
    CONSTRAINT webhook_verification_budgets_kind_check
        CHECK (budget_kind IN ('setup_actor_bot', 'activation_actor_bot', 'activation_source_ip')),
    CONSTRAINT webhook_verification_budgets_attempts_check CHECK (attempts >= 1),
    CONSTRAINT webhook_verification_budgets_window_check CHECK (window_expires_at > window_started_at)
);

CREATE INDEX webhook_verification_budgets_expiry_idx
    ON webhook_verification_budgets (window_expires_at);

-- Reuse the existing durable admin_actions audit stream, widening it to represent owner, bot and
-- system actors. Existing admin writers keep working through the legacy nullable/default columns.
ALTER TABLE admin_actions
    ALTER COLUMN admin_user_id DROP NOT NULL,
    ADD COLUMN actor_kind text NOT NULL DEFAULT 'admin',
    ADD COLUMN actor_id text,
    ADD COLUMN request_id text,
    ADD COLUMN before_revision uuid,
    ADD COLUMN after_revision uuid,
    ADD COLUMN before_registration_id uuid,
    ADD COLUMN after_registration_id uuid,
    ADD COLUMN bot_incarnation_id uuid,
    ADD COLUMN metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT admin_actions_actor_kind_check
        CHECK (actor_kind IN ('owner', 'admin', 'bot', 'system'));

UPDATE admin_actions
SET actor_id = admin_user_id::text
WHERE actor_id IS NULL AND admin_user_id IS NOT NULL;

CREATE INDEX admin_actions_request_idx ON admin_actions (request_id) WHERE request_id IS NOT NULL;
