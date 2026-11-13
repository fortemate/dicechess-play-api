---
title: Running the isolated laboratory service
description: Build, operate, export and retire the disposable two-player laboratory runtime.
sidebar:
  order: 9
---

The laboratory runtime serves casual two-player games from a dedicated PostgreSQL database. It is an explicit opt-in mode with its own routes, configuration, Docker Compose project, network and volume. It does not start the ordinary Play API services.

The runtime exposes health and version endpoints plus the laboratory routes. It has no bot, lobby, rating, training, showcase, authentication, ingest or outbound-client services. The browser client must connect directly to the laboratory API; there is no fallback to the main API.

For the game and wire contract, see [the two-pair laboratory contract](../laboratory-contract/) and [laboratory rooms](../laboratory-rooms/). This page covers local operation only. It does not choose a public hostname, configure DNS, or deploy a service.

## Configuration

Set `PLAY_RUNTIME_MODE=laboratory` explicitly. The following values are required and have no main-service fallback:

| Variable | Requirement or default |
| --- | --- |
| `LAB_DB_URL` | Required JDBC URL for a dedicated PostgreSQL database. |
| `LAB_DB_USER` | Required database user. |
| `LAB_DB_PASSWORD` | Required operator-generated password. |
| `LAB_FRONTEND_ORIGIN` | Required exact browser origin, including scheme and port if present; one origin only. |
| `LAB_BIND_HOST` | Defaults to `127.0.0.1` for a direct host process. |
| `LAB_PORT` | Defaults to `8081`. |
| `LAB_DB_POOL_SIZE` | Defaults to `2`. |
| `LAB_RETENTION_DAYS` | Defaults to `30`; valid range is 1–365 days. Expiration is measured from game creation. |
| `LAB_JOIN_WINDOW_SECONDS` | Defaults to `300`. |
| `LAB_PRUNE_INTERVAL_SECONDS` | Defaults to `3600`. |
| `LAB_PRUNE_BATCH_SIZE` | Defaults to `100`. |
| `LAB_ADMISSION_OPEN` | Defaults to `true`. Set to `false` to reject new games and rematches while existing games and readers continue to work. |

Laboratory mode rejects ordinary Play service configuration, including `PLAY_DB*`, `RATING_*`, `TRAINING_*`, `INGEST_*`, `LADDER_*`, `SHOWCASE_*`, `WEBHOOK_*`, `PLAY_BOT_*`, `GOOGLE_*` and `PLAY_SESSION*`. It also checks that the configured database is not occupied by tables belonging to another service before running laboratory migrations. Do not point it at the main Play database.

## Build and launch locally

Generate a unique password using the operator's approved password manager or secret generator. Keep the value out of shell history and tracked files. Provide `LAB_DB_USER`, `LAB_DB_PASSWORD` and `LAB_FRONTEND_ORIGIN` through the operator's environment or secret manager. Optional `LAB_DB_POOL_SIZE`, `LAB_RETENTION_DAYS`, `LAB_JOIN_WINDOW_SECONDS`, `LAB_PRUNE_INTERVAL_SECONDS`, `LAB_PRUNE_BATCH_SIZE` and `LAB_ADMISSION_OPEN` values can override the documented defaults. The commands below pass `--env-file /dev/null` so Compose does not discover a root `.env`; the current shell environment and required interpolation values remain available. Do not use `env_file` or pass the main service's Compose configuration into this project.

The disposable Compose file builds the repository's `Dockerfile` locally and starts its `/app/bin/dicechess-play-api` entrypoint. It never pulls a published `latest` image. The database has no published port. The API listens on `0.0.0.0:8081` inside its container and is published only on host loopback at `127.0.0.1:8081`.

From the repository root, start the independent project:

```sh
docker compose --env-file /dev/null -p dicechess-laboratory -f docker-compose.laboratory.yaml up --build -d
```

Validate the Compose file without rendering the resolved environment or configuration:

```sh
docker compose --env-file /dev/null -p dicechess-laboratory -f docker-compose.laboratory.yaml config --quiet
```

Check readiness without printing resolved Compose configuration or environment values:

```sh
docker compose --env-file /dev/null -p dicechess-laboratory -f docker-compose.laboratory.yaml ps
curl --fail http://127.0.0.1:8081/health
curl --fail http://127.0.0.1:8081/version
```

The API container also uses its bundled `curl` to check `/health`. The health and version paths are available on the laboratory server. A successful HTTP response confirms the local process is reachable; it does not replace the browser smoke check below.

## Public launch checklist

The Compose recipe is local and loopback-only. Before any public launch, the operator must choose and own the API hostname, configure TLS so browsers use HTTPS and WebSocket Secure, and operate a dedicated laboratory database. Set `LAB_FRONTEND_ORIGIN` to the one exact frontend origin. The API's database guard must accept only an empty or laboratory-only database; a database containing main-service tables must be refused before migration.

After launch, verify that a request with the configured browser origin succeeds and a request with a different origin is refused. Check the frontend network panel to confirm every API and WebSocket request targets only the laboratory API. Verify bot, ingest and rating endpoints are unavailable (404). Do not launch if the frontend can fall back to the main API or if any of these checks fail.

## Browser and WebSocket smoke check

Build a separate laboratory frontend with its API base URL set to `http://127.0.0.1:8081` (or the operator's chosen origin when deployed). Set `LAB_FRONTEND_ORIGIN` to that frontend's exact origin, such as scheme plus hostname and optional port, without a path. Do not configure the client to fall back to the main API. The frontend integration is tracked separately in [Play issue 223](https://github.com/fortemate/dicechess-play/issues/223) and [Play issue 224](https://github.com/fortemate/dicechess-play/issues/224).

Open the laboratory frontend from the origin configured on the server and verify that it can create a casual game, connect to its WebSocket, invite a second human, and exchange a laboratory update. Confirm the browser network panel shows requests and WebSocket traffic only to the laboratory API origin. A different scheme, host or port is a different origin and must not be treated as equivalent.

## Admission and retention

Set `LAB_ADMISSION_OPEN=false` to close admission. Existing games can continue and existing readers remain available; new game creation and rematches are refused. Reopen admission by setting it to `true` and recreating the API service with the operator's normal change procedure.

For the disposable recipe, apply the change explicitly:

```sh
LAB_ADMISSION_OPEN=false docker compose --env-file /dev/null -p dicechess-laboratory -f docker-compose.laboratory.yaml up -d --no-deps api
```

Retention applies to every game, including incomplete and active games, using its fixed creation time. The pruning job refreshes cached rooms first; if a game has expired, its live writer is stopped and the expired record can then be deleted in a bounded batch. Set the retention period within the configured 1–365 day range based on the operator's retention policy.

## Export and backup

Before an API update that changes rules, dice or protocol provenance, close admission and finish or freeze active games. Recovery deliberately refuses incompatible recorded versions. Retain a compatible tested image or export the experiment; do not rewrite stored provenance to force recovery.

Before exporting, close admission with `LAB_ADMISSION_OPEN=false`, then let active games complete or stop them according to the operator's decision. Do not export while games are actively changing. Export only the versioned public transcript payloads with their game IDs:

To freeze unfinished games for export, stop only the API and leave PostgreSQL running:

```sh
docker compose --env-file /dev/null -p dicechess-laboratory -f docker-compose.laboratory.yaml stop api
```

```sh
docker compose --env-file /dev/null -p dicechess-laboratory -f docker-compose.laboratory.yaml exec -T db \
  psql -U "$LAB_DB_USER" -d laboratory -X -A -t -v ON_ERROR_STOP=1 \
  < scripts/laboratory/export.sql > laboratory-transcripts.jsonl
```

The query reads only unexpired records from `laboratory.transcripts.payload`; its envelope has `exportSchemaVersion: 1`, while each transcript retains its own `schemaVersion`. It omits private snapshots, guest identifiers, seat tokens, command receipts, unrevealed offers and live seeds. Terminal fairness evidence appears only when the game's rules permit its reveal. Treat exported transcripts as public game history.

If a full database backup is required, use the operator's approved PostgreSQL backup procedure and restrict access to the resulting file. A full backup contains private recovery snapshots, credentials and active game state. Store it separately from public transcript exports and apply the operator's access and retention controls.

## Retirement

1. Set `LAB_ADMISSION_OPEN=false` and allow existing games to finish, or make an explicit operator decision to stop them.
2. Export public transcripts and take any required restricted full-database backup.
3. Stop the laboratory containers while preserving the volume:

   ```sh
   docker compose --env-file /dev/null -p dicechess-laboratory -f docker-compose.laboratory.yaml down
   ```

4. Decide explicitly whether to preserve or delete the `dicechess-laboratory-db` volume. Deleting it permanently removes retained games and database state. The standard retirement command does not delete it; never automate `down -v`.
5. Remove generated export and backup files according to their respective retention and access policies.

The Compose project, network and named volume are dedicated to the laboratory. Keep the operator's chosen hostname and deployment configuration outside this disposable local recipe.

The runbook documents a disposable local recipe. Compose configuration validation passed with both the default and closed-admission settings; the application stack was not executed.
