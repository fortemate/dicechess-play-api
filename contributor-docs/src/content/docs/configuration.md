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
| `WEBHOOK_TIMEOUT_SECONDS` | Enables bot webhook push — both the routes and the dispatcher. Unset disables the feature. |
| `RETENTION_INTERVAL_SECONDS` | Enables the retention prune. Unset keeps ended snapshots and delivered outbox/`client_reports` rows forever. |
| `RETENTION_DAYS` | Optional, default `30`. |
| `RETENTION_BATCH_SIZE` | Optional, default `1000`. |
| `PLAY_BOT_TOKENS` | Statically configured bots, as `team\|name\|token` CSV. |
| `PLAY_ADMINS` | Comma-separated **account uuids** granted the admin bot surface (#273): `/admin/bots/{team}/{name}/…` drives any registered bot without its token — ladder, catalog, description, token rotation — every write audited in `admin_actions` (V19). Uuids, not nicknames: nicknames rename and release (V18). Needs `PLAY_SESSION_SECRET` **and** persistence; boot warns loudly when set without them. Malformed entries are skipped and reported by **position only** — never by value, since one of them may be a secret pasted into the wrong variable. |
| `PLAY_CORS_ORIGINS` | Allowed origins; empty allows any (credential-less). A non-empty list also enables credentialed CORS — required once sign-in is on. |
| `APP_VERSION` | Surfaced at `GET /version`. Set by the CD workflow from the git tag. |

## Player accounts (Google sign-in)

All-or-nothing (ADR-0017, #233): the `/auth/*` routes mount only when persistence **and** every
required variable below are present. A *partial* Google configuration logs a loud warning at
boot — someone clearly tried to enable sign-in — instead of the usual silent absence.

| Variable | Effect |
| --- | --- |
| `GOOGLE_CLIENT_ID` | The OAuth client (Google Cloud console). Required. |
| `GOOGLE_CLIENT_SECRET` | Its secret. Required. |
| `GOOGLE_REDIRECT_URI` | Must match the console entry, e.g. `https://play-api.jc.id.lv/auth/callback`. Required. |
| `PLAY_SESSION_SECRET` | HMAC key for session JWTs (e.g. `openssl rand -base64 48`). Required; no fallback on purpose. |
| `PLAY_FRONTEND_URL` | Where login/callback send the browser back. Default `https://play.jc.id.lv`; local dev sets `http://localhost:5173`. |

With sign-in enabled, `PLAY_CORS_ORIGINS` must be a real allow-list: the empty allow-all mode
stays credential-less by design, so the SPA's credentialed fetches would fail against it.

:::caution[Retention looks broken on an unbackfilled deployment]
The prune refuses to remove an ended, non-aborted snapshot that has no `game_archive` row —
that snapshot would be the only copy of the game's history. On a deployment whose games predate
the archive table, retention therefore reclaims nothing and appears not to work. Run the
archive backfill first; the retained count appears in the log line.
:::
