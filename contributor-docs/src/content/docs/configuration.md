---
title: Configuration
description: Every server environment variable, what it enables, and the silent-disable idiom that has burned three deployments.
---

Every subsystem is opt-in through the environment. The server reads these once at boot in
`Main` and wires up only what is configured.

:::danger[Absence disables the feature, silently]
`LADDER_INTERVAL_SECONDS`, `RATING_INTERVAL_SECONDS`, `WEBHOOK_TIMEOUT_SECONDS` and
`RETENTION_INTERVAL_SECONDS` all follow the same idiom: **unset means the feature simply never
runs, with no error anywhere**. The server starts clean and `/health` returns 200 while ladder
pairing, rating updates, webhook push, or pruning quietly do nothing.

This has bitten a real deployment three separate times, one variable at a time. Verify a new
environment with a *live* check — `GET /games` becomes non-empty and `/leaderboard` counts
increase over a minute — never with `/health` alone. When copying configuration between
environments, enumerate the variables from the source (`grep -rhoE '"[A-Z][A-Z0-9_]{3,}"'
src/main/scala`) rather than from documentation, which drifts.
:::

## Persistence

| Variable | Effect |
| --- | --- |
| `PLAY_DB_URL`, `PLAY_DB_USER`, `PLAY_DB_PASSWORD` | Enable Postgres persistence. Unset means fully in-memory: a restart drops every game. |

## Analytics ingest

| Variable | Effect |
| --- | --- |
| `INGEST_URL` | The **full** endpoint URL, not a base. Enables outbox delivery. |
| `INGEST_TOKEN` | Bearer token for that endpoint. |

The same pair also drives delivery of browser-submitted reports accepted at
`POST /ingest/games` (queued in `client_reports`) — there are no separate variables for that
intake; it is mounted whenever persistence is configured.

Setting `PLAY_DB_URL` without these is a trap: finished games and browser reports accumulate
in their queues undelivered. Boot warns on stderr, and nothing else complains.

## Ladder and rating

| Variable | Effect |
| --- | --- |
| `LADDER_INTERVAL_SECONDS` | Enables automatic ladder pairing. Unset disables pairing entirely. |
| `LADDER_MAX_CONCURRENT_GAMES` | Optional, default `8`. Renamed from `LADDER_MAX_CONCURRENT_PAIRS` (#190) — an old name left in the environment is ignored, not translated. |
| `RATING_INTERVAL_SECONDS` | Enables Glicko-2 rating updates **and** ladder auto-park. Unset disables both. |
| `RATING_BATCH_SIZE` | Optional, default `100`. |
| `LADDER_TIMEOUT_PARK_GAMES` | Optional, default `4`. Renamed from `LADDER_TIMEOUT_PARK_PAIRS` (#190). Despite the `LADDER_` prefix it is read by the *rating* batch — with rating off, a dead bot is never parked and keeps bleeding rating while inflating every opponent it meets. The name follows the feature, not the component. |
| `STRENGTH_ELO0`, `STRENGTH_ELO1`, `STRENGTH_ALPHA`, `STRENGTH_BETA`, `STRENGTH_BOOTSTRAP_ITERATIONS` | Tuning knobs for the SPRT / Bradley-Terry report. Each falls back to its own default rather than disabling anything — but the report is refreshed by the rating batch, so it is only ever populated while `RATING_INTERVAL_SECONDS` is set. |
| `STRENGTH_REFRESH_INTERVAL_SECONDS` | Optional, default `900` (15 minutes). The floor between two rebuilds of that report (#215). Like `LADDER_TIMEOUT_PARK_GAMES` this is read by the rating batch, so it does nothing without `RATING_INTERVAL_SECONDS`. A rebuild folds every rated game ever played, `STRENGTH_BOOTSTRAP_ITERATIONS` times over; tying it to the batch's own poll cadence cost a compute worker a third of all wall-clock time in production. `0` restores that pre-#215 behaviour (rebuild on every tick that applied a game) and is the only value that does. |

## Webhooks, retention, and the rest

| Variable | Effect |
| --- | --- |
| `WEBHOOK_TIMEOUT_SECONDS` | Enables the legacy Bearer-token webhook routes and turn dispatcher. Unset means no turn delivery, independently of whether the staged session-management control plane is enabled. |
| `WEBHOOK_SESSION_MANAGEMENT_ENABLED` | Optional, default off. Only case-insensitive `true` mounts the owner/admin staged webhook routes. Also requires persistence, a live account session, and an explicit non-empty `PLAY_CORS_ORIGINS`; a partial configuration logs a boot warning and mounts nothing. This is a backend rollout gate, not evidence that any deployment or UI has enabled the feature. |
| `WEBHOOK_VERIFICATION_TIMEOUT_SECONDS` | Candidate verification-v2 timeout for the staged session API. Default `10`; valid range `1..30`. An invalid value fails startup even while the staged API flag is off, so stale configuration cannot lie dormant until a future rollout. |
| `WEBHOOK_SETUP_CREATES_PER_WINDOW` | Optional staged-verification budget per actor+bot in the fixed 15-minute window. Default and maximum `5`; valid range `1..5`, so production can only tighten the accepted policy. |
| `WEBHOOK_ACTIVATIONS_PER_ACTOR_BOT_WINDOW` | Optional staged-activation budget per actor+bot in the fixed 15-minute window. Default and maximum `10`; valid range `1..10`. |
| `WEBHOOK_ACTIVATIONS_PER_SOURCE_IP_WINDOW` | Optional staged-activation budget per source IP in the fixed 15-minute window. Default and maximum `30`; valid range `1..30`. |
| `RETENTION_INTERVAL_SECONDS` | Enables the retention prune. Unset keeps ended snapshots and delivered outbox/`client_reports` rows forever. |
| `RETENTION_DAYS` | Optional, default `30`. |
| `RETENTION_BATCH_SIZE` | Optional, default `1000`. |
| `PLAY_BOT_TOKENS` | Statically configured bots, as `team\|name\|token` CSV. |
| `PLAY_ADMINS` | Comma-separated **account uuids** granted the admin bot surface (#273): `/admin/bots/{team}/{name}/…` drives any registered bot without its token — ladder, catalog, description, capacity, token rotation — every write audited in `admin_actions` (V19). Uuids, not nicknames: nicknames rename and release (V18). Needs `PLAY_SESSION_SECRET` **and** persistence; boot warns loudly when set without them. Malformed entries are skipped and reported by **position only** — never by value, since one of them may be a secret pasted into the wrong variable. When staged webhook management is enabled, the parsed, sorted allow-list is also hashed into the instance's admin-authority generation; the raw ids are not copied to the generation table. |
| `PLAY_CORS_ORIGINS` | Exact comma-separated browser origins, each one lower-case `scheme://host[:port]` with no path, no wildcard, and no explicitly written default port. Unset or blank allows any origin only in credential-less CORS mode. A non-empty list enables credentialed CORS and is mandatory for staged webhook session mutations, whose server-side guard also requires an exact `Origin` match and `X-DiceChess-CSRF: 1`. Unusable entries are reported on stderr and ignored; a non-empty value with **no** usable entry **fails startup** rather than silently degrading to allow-all — see the warning below. |
| `APP_VERSION` | Surfaced at `GET /version`. Set by the CD workflow from the git tag. |

## Player accounts (Google sign-in)

All-or-nothing (ADR-0017, #233): the `/auth/*` routes mount only when persistence **and** every
required variable below are present. A *partial* Google configuration logs a loud warning at
boot — someone clearly tried to enable sign-in — instead of the usual silent absence.

| Variable | Effect |
| --- | --- |
| `GOOGLE_CLIENT_ID` | The OAuth client (Google Cloud console). Required. |
| `GOOGLE_CLIENT_SECRET` | Its secret. Required. |
| `GOOGLE_REDIRECT_URI` | Must match the console entry, e.g. `https://api.fortemate.com/auth/callback`. Required. |
| `PLAY_SESSION_SECRET` | HMAC key for session JWTs (e.g. `openssl rand -base64 48`). Required; no fallback on purpose. |
| `PLAY_FRONTEND_URL` | Where login/callback send the browser back. Default `https://fortemate.com`; local dev sets `http://localhost:5173`. |

With sign-in enabled, `PLAY_CORS_ORIGINS` must be a real allow-list: the empty allow-all mode
stays credential-less by design, so the SPA's credentialed fetches would fail against it.

:::caution[A typo here used to become a policy change]
Each entry must be exactly what a browser puts in the `Origin` header: one concrete
`scheme://host[:port]`, lower-case, with no path and no explicitly written default port.
`https://fortemate.com` and `http://localhost:5173` are usable; `fortemate.com`,
`HTTPS://Fortemate.com`, `https://fortemate.com/api`, `https://fortemate.com:443`, a wildcard
host and the literal `null` are not. The middle ones matter because the header parser accepts
them — they would be stored and then match nothing, which looks identical to a policy that is
simply refusing you.

Unusable entries are reported on stderr and ignored. If **every** entry is unusable, the empty
set that remains is indistinguishable from "unset", which selects credential-less allow-all —
that would swap a locked-down credentialed policy for a public one, break every
cookie-authenticated browser call, and leave the staged webhook routes unmounted, all from one
typo. The server therefore **refuses to start** in that case. Leave the variable unset if
allow-all is what you actually want.
:::

The staged webhook-management surface is stricter still: it stays unmounted unless
`WEBHOOK_SESSION_MANAGEMENT_ENABLED=true`, persistence, `PLAY_SESSION_SECRET`, and a non-empty
origin allow-list are all present. `PLAY_ADMINS` is required only for an administrator to use the
`/admin/bots/{team}/{name}/webhook…` root; owners use the parallel `/me` root. Enabling the control
plane does not start deliveries — set `WEBHOOK_TIMEOUT_SECONDS` separately when the dispatcher is
intended to run. Promotion, database migration, and the flag change remain operator actions; this
repository configuration does not perform them.

The three staged-verification budget overrides are deliberately one-way: each accepts only a
positive value at or below its documented default. A zero, a larger value, or non-integer text
fails startup even while the feature flag is off. The shared window remains fixed at 15 minutes, so
an environment override cannot silently weaken the effective request rate by shortening it.

Every enabled instance writes its hashed admin-authority generation to PostgreSQL at startup and
then heartbeats it every **5 seconds**. A generation is live for **20 seconds** after its last
database-timestamped heartbeat. Admin webhook operations are authorized only while exactly one
generation is live. A rolling `PLAY_ADMINS` change therefore fails closed with
`403 admin_required` while old and new generations overlap; it cannot let each replica activate a
setup under a different allow-list. Once the old heartbeat ages out, the sole surviving generation
invalidates and scrubs pending admin setups from older generations. The `/me` owner root is not
subject to this admin-generation fence. The heartbeat is a supervised server loop: a database or
loop failure stops the process instead of leaving it serving stale administrator authority.

## Showcase table (ADR-005, #44)

The singleton showcase table on the homepage (`/`) is controlled by the following variables:

| Variable | Effect |
| --- | --- |
| `SHOWCASE_ENABLED` | Enables the singleton showcase table coordinator and routes (`GET /showcase`, `POST /showcase/claim`). Unset or `false` disables the showcase surface entirely. |
| `SHOWCASE_BOT_TEAM` | Team identifier of the featured bot (e.g. `rpi3`). Required when showcase is enabled. |
| `SHOWCASE_BOT_NAME` | Name of the featured bot (e.g. `hunter-book`). Required when showcase is enabled. |
| `SHOWCASE_RESERVED_SEATS` | Dedicated capacity reserved exclusively for the showcase table. When `SHOWCASE_ENABLED=true`, this value must be set to exactly `1`. Values `0` or `> 1` are rejected during configuration validation at boot time. |

The clock is fixed at `5+3` (`ShowcaseTable.FixedTimeControl`) and is deliberately not configurable in this
release — the homepage promises one table with one clock. The routes mount only when `SHOWCASE_ENABLED=true`;
disabled, both paths answer a plain 404.

The table can only ever be `open` while the featured bot passes its readiness probe: it must be a **registered**
bot (so the reserved admission class applies), `WEBHOOK_TIMEOUT_SECONDS` must be set (delivery is what drives the
bot), and its registered webhook must answer the unsigned verification echo within 5 seconds. The probe reruns
every 15 seconds while the table is not live. `SHOWCASE_ENABLED=true` without `WEBHOOK_TIMEOUT_SECONDS` logs a
`[play][showcase]` warning at boot and the table stays `unavailable` (`reason: "bot_unavailable"`).

:::danger[Showcase requires PostgreSQL persistence]
The showcase table enforces fail-closed durability: if `PLAY_DB_URL` is unset, the showcase table
remains `unavailable` (`reason: "maintenance"`) and answers every claim `503 showcase_unavailable`. Falling
back to an in-memory store is strictly prohibited for showcase games. Concretely (#47, #46): `GameRegistry`
refuses to create a showcase room over a store that does not claim durability, the coordinator is built
without a `ShowcaseStore` and never leaves `unavailable`, and `SHOWCASE_ENABLED=true` without `PLAY_DB_URL`
logs a `[play][showcase]` warning at boot — the seat reservation still applies (a silent fallback to
unreserved capacity is what ADR-005 forbids), so fix the configuration rather than expecting the table to
open.
:::

Operator signals: every phase change is one `[play][showcase] table -> …` line on stdout, and every
fail-closed reason that needs a human (no persistence, a persistence failure, a bot that fails the probe,
irreconcilable state, more than one active showcase game) is one `[play][showcase] ALERT: …` line on stderr,
raised on the transition rather than on every tick. None of them ever carries a seat token, a webhook secret
or an address.

## Staged webhook rollout runbook

This is the required order from ADR-004 §14, reproduced here so the runbook stands on its own.
The session feature flag is the safety boundary; a green build alone is not permission to skip a
step.

1. Apply the additive migration while `WEBHOOK_SESSION_MANAGEMENT_ENABLED` is unset or `false`.
   Do not mutate production registrations or rotate secrets as part of the migration.
2. Before deploying, audit the existing `bot_webhooks` rows against the **tightened URL policy**.
   `WebhookSecurity.checkPublicHttps` — the legacy `/bot/webhook` guard — now also rejects
   userinfo, a fragment, an invalid port, and any IPv6 address outside global unicast
   (`2000::/3`) or inside an IANA special-purpose block, Teredo (`2001::/23`) and 6to4
   (`2002::/16`) included. The policy is re-applied at send time, so a stored registration that
   no longer passes keeps its row and silently stops receiving deliveries. Report affected bot
   owners before the deploy rather than after they notice missed turns.
3. Deploy the revision/registration-generation-aware API with the session flag still off.
4. Drain **and read back** every old webhook writer and delivery worker. Confirm from the platform,
   not only from the deploy command, that no old API instance can perform a legacy webhook write
   and no old delivery worker can run beside the generation-scoped apply/health path.
5. Confirm that every remaining API instance reports the expected generation-aware build, and keep
   the session flag off until the DNS-pinning integration proof passes: policy resolution must
   reject private/rebound destinations, the connection must use the validated public IP, and Host
   plus TLS SNI must retain the candidate hostname without a second DNS resolution.
6. Wait for a versioned, compatible `dicechess-bot-runtime` release that implements
   verification-v2 proof and dual-key pending configuration. Verify its vectors and deployment
   guidance; an endpoint must keep accepting deliveries signed by the active key while using the
   pending key only for activation-v2 proof.
7. Only after all previous readbacks and proofs pass, set
   `WEBHOOK_SESSION_MANAGEMENT_ENABLED=true`. Confirm the initial database heartbeat and a sole live
   admin-authority generation before enabling any UI that performs session mutations.

Rollback is **flag off**: unset or disable `WEBHOOK_SESSION_MANAGEMENT_ENABLED` and read back that
the session routes are gone. Keep the additive migration and generation-aware writers/workers in
place; do not reintroduce an old writer or delivery worker as the rollback mechanism.

:::caution[Retention looks broken on an unbackfilled deployment]
The prune refuses to remove an ended, non-aborted snapshot that has no `game_archive` row —
that snapshot would be the only copy of the game's history. On a deployment whose games predate
the archive table, retention therefore reclaims nothing and appears not to work. Run the
archive backfill first; the retained count appears in the log line.
:::
