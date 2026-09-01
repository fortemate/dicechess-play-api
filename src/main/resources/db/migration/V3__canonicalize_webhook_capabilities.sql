-- Webhook capabilities are a closed contract. Keep only the exact, currently
-- selectable `draws` value; every legacy/unknown spelling (including the
-- reserved `doubling` name) is intentionally removed.
UPDATE bot_webhooks
SET capabilities = CASE
    WHEN array_position(capabilities, 'draws') IS NOT NULL THEN ARRAY['draws']::text[]
    ELSE ARRAY[]::text[]
END;

-- Equality against the two canonical arrays also rejects duplicates, NULL
-- elements, non-canonical array bounds, reserved values, and unknown strings.
ALTER TABLE bot_webhooks
    ADD CONSTRAINT bot_webhooks_capabilities_check
    CHECK (
        capabilities = ARRAY[]::text[]
        OR capabilities = ARRAY['draws']::text[]
    );
