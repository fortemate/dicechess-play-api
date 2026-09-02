---
title: Architecture
description: The module map of dicechess-play-api — what each package owns, and how a game travels from HTTP request to durable snapshot.
---

A single sbt module. The entry point is `dicechess.play.Main` (an `IOApp.Simple` serving Ember
on `0.0.0.0:8080`), which reads the environment and wires up the optional subsystems —
persistence, analytics ingest, the ladder scheduler, the rating batch, webhook push, and
retention. Each is opt-in; see [Configuration](/dicechess-play-api/configuration/).

## Package map

Everything lives under `src/main/scala/dicechess/play/`.

| Package | Owns |
| --- | --- |
| `core/` | Domain types with no dependencies: `Protocol`, `Identity` (Seat / Principal), `GameId`, `Seek`, `BotEvent` |
| `dice/` | `DiceSource` — server-only CSPRNG dice with commit-reveal fairness |
| `game/` | `GameRoom` (the actor-style room), `EngineOps` (the only engine wrapper), `PlayerConnection` |
| `server/` | http4s routes and the services behind them — the largest package |
| `store/` | `GameStore` / `PgGameStore` (doobie + Flyway), `GameArchive`, `Retention` |
| `rating/` | `Glicko2`, `RatingBatch`, and the strength report: `Sprt`, `BradleyTerry`, `StrengthReport`, `StrengthCache` |
| `ingest/` | `PlaysiteIngest` + `IngestDeliverer` — the transactional outbox to analytics |
| `wire/` | `Codecs.scala` — the Circe codecs that *are* the client wire contract |

## How a move travels

```mermaid
flowchart TD
    C["Client<br/>(browser or bot)"] -->|"intent: move / challenge / seek"| R["server/ routes<br/>PlayRoutes · BotRoutes · LobbyRoutes"]
    R --> REG["GameRegistry"]
    REG --> ROOM["game/GameRoom<br/>single writer fiber"]
    ROOM -->|"validate"| ENG["game/EngineOps<br/>→ dicechess-engine-scala"]
    ROOM -->|"roll"| DICE["dice/DiceSource<br/>CSPRNG + commit-reveal"]
    ROOM -->|"tryOffer, non-blocking"| SUB["Per-subscriber queues<br/>WebSocket · ndjson · webhook"]
    ROOM -->|"snapshot"| STORE["store/PgGameStore<br/>jsonb snapshot"]
    STORE -->|"same transaction, on game end"| OUT["ingest/ outbox"]
    OUT -->|"HTTP POST, retried"| AN["dicechess-analytics"]
    STORE --> RES["game_results projection"]
    RES --> RB["rating/RatingBatch<br/>Glicko-2 + strength report"]
```

The shape to hold on to: **the room is the only writer of game state**, and everything
downstream of it — subscribers, snapshots, the outbox — is fed without ever letting a slow
consumer block play. That rule is spelled out in
[Concurrency Doctrine](/dicechess-play-api/concurrency/).

## Cross-repository contracts

`play-api` sits in the middle of four contracts. Changing either side of one without the other
is the most common way to break the platform.

- **Consumes** `lv.id.jc:dicechess-engine-scala`, a JVM artifact from GitHub Packages with the
  version pinned in `build.sbt`. It is the single source of truth for the rules — legality is
  never reimplemented here. Legal moves ship on the wire as a prefix tree of UCI micro-moves.
- **Publishes** the client wire protocol in `wire/Codecs.scala`, consumed by the
  `dicechess-play` SvelteKit front end. Both sides must be verified together.
- **Publishes** the analytics ingest payload built in `ingest/PlaysiteIngest.scala` — posted to
  the analytics service's `/api/games` with `source=playsite`, idempotent, first writer wins.
- **Publishes** the public Bot API, documented at [bots.fortemate.com](https://bots.fortemate.com/). Its
  machine-readable contracts (`openapi.yaml`, `asyncapi.yaml`) live in that site's `public/`
  directory and are rendered into the reference at build time, so the spec cannot drift
  silently from the docs.

## HTTP surface

Routes are grouped by audience rather than by resource:

- **Operational** — `GET /health`, `GET /version`.
- **Human game surface** — `POST /games`, `GET /games/{id}`, `GET /games/{id}/ws?token=…`. The
  token grants the seat; redeeming it is also when the seat learns who is sitting in it (#285),
  from the session or a `?guest=` uuid.
- **Public discovery** — `GET /games`, `GET /leaderboard`, `GET /bots/{team}/{name}`, plus the
  history and strength endpoints.
- **Showcase table** — `GET /showcase` and `POST /showcase/claim`. Exposes the homepage singleton table:
  - `GET /showcase` returns the public table view: state (`unavailable`, `open`, `live`, `finishing`), featured bot
    summary, time control (`5+3`), next human color when `open`, current game FEN and clocks when `live`/`finishing`,
    and the public spectator WebSocket URL. It enforces `Cache-Control: no-cache, no-store, must-revalidate` for active
    states (max-age 1s when `open`), requires no authentication, and **never exposes player credentials or internal tokens**.
  - `POST /showcase/claim` is the atomic first-claim endpoint. Authentication accepts either a valid session cookie
    (`user:<uuid>`) or a stable `guestId` (`guest:<uuid>`). It requires an `Idempotency-Key` header (UUIDv4) and accepts
    optional `clientEntropy`. The atomic winner receives `200 OK` with `outcome: "playing"`, assigned color, and
    `playerToken` in a `Cache-Control: no-store` body; concurrent losers and subsequent callers receive `200 OK` with
    `outcome: "spectating"` and the spectator URL, with **no player credentials**. Errors use RFC 7807 problem details:
    `400 Bad Request` (`missing_idempotency_key`), `409 Conflict` (`idempotency_conflict` on reused key with different
    payload), `429 Too Many Requests` (per-IP and per-actor claim rate limits), or `503 Service Unavailable`
    (`showcase_unavailable`). There is no queue and no rematch. The authoritative contract is ADR-005 (#44).
- **Bot API** — everything under `/bot/…`: identity, challenges, seeks, gameplay, streams,
  webhooks, ladder.
- **Account control** — `/me/…` uses the live `access_token` session and current ownership;
  `/admin/…` additionally requires the account id in `PLAY_ADMINS`. The parallel staged webhook
  roots share `SessionWebhookRoutes` and `WebhookManagement`, so only authorization differs —
  status mapping, CAS, redaction, verification, and audit cannot drift between them. They mount
  only behind `WEBHOOK_SESSION_MANAGEMENT_ENABLED` plus persistence, session verification, and an
  explicit origin allow-list; the legacy `/bot/webhook…` contract remains separate. The accepted
  security/state-machine contract is ADR-004; this page and
  [Concurrency](/dicechess-play-api/concurrency/) carry everything a contributor needs from it.

The complete, authoritative reference for the bot-facing routes is the
[Bot API site](https://bots.fortemate.com/), not this page.

## Staged webhook control plane

The staged path deliberately separates browser authorization, durable credential state, and
outbound networking:

1. `SessionWebhookRoutes` authenticates the live owner/admin session and enforces exact Origin,
   CSRF, content type, and strong `If-Match` requirements.
2. `WebhookManagement` validates the typed transition, performs a DB-authoritative authority and
   revision preflight, then consumes the shared verification budget and coordinates the lease
   around verification-v2 without exposing stored candidate material. The store repeats authority
   and CAS checks at mutation/commit time; unauthorized or stale requests spend no budget and cause
   no DNS or outbound HTTP.
3. `WebhookManagementStore` / `PgGameStore` own the authoritative revision, registration
   generation, setup/tombstone lifecycle, admin-authority heartbeat and audit transaction. Security
   deadlines use the PostgreSQL clock, and activation attempts are reserved when the lease is
   acquired.
4. `WebhookTransport` resolves and validates the candidate once, connects to that validated public
   IP, and retains the hostname for HTTP Host and TLS SNI. The ordinary delivery path reuses this
   DNS-pinned primitive and checks `registration_id` again before a response can enter `GameRoom`.

`Main` mounts the routes and supervises the 5-second admin-generation heartbeat only when the
complete staged configuration is present. A generation is live for 20 seconds; overlapping admin
allow-list generations fail closed until the old one ages out. Deployment ordering is part of the
architecture, not an operations afterthought: follow the
[staged webhook rollout runbook](/dicechess-play-api/configuration/#staged-webhook-rollout-runbook),
including the old-writer/worker drain, DNS-pinning proof, compatible runtime v2 dual-key release,
and flag-off rollback.

## Singleton showcase table

The homepage (`fortemate.com`, route `/`) exposes exactly one permanent public Dice Chess table facing
a configurable featured bot (initially `rpi3/hunter-book`) at casual/unrated `5+3`. When open, the first
visitor to claim it atomically starts one game; all other visitors spectate that same game. The existing
play site remains unchanged at `/play`.

The table moves through four public states:

```mermaid
stateDiagram-v2
    [*] --> unavailable
    unavailable --> open: Bot ready + DB healthy + no active game
    unavailable --> live: Resumed active game
    unavailable --> finishing: Resumed terminal game
    open --> live: Atomic first claim committed
    open --> unavailable: Bot down / DB outage
    live --> finishing: Game ends (mate, flag, resign, abort)
    live --> unavailable: Persistence failure (fail-closed)
    finishing --> open: Terminal persistence committed (4 fences satisfied)
    finishing --> unavailable: Terminal persistence exhausted
```

1. `unavailable`: The table cannot accept claims. Set when PostgreSQL is unconfigured or unreachable,
   the featured bot's webhook is unresponsive, or startup reconciliation detects ambiguous state.
2. `open`: The table is idle, advertising the configured featured bot, `5+3`, and the server-assigned
   next human color. Exactly 1 bot capacity slot is reserved.
3. `live`: One active game against the featured bot is in progress. The winning claimant holds player
   credentials (`playerToken`), which appears **only** in the winning `POST /showcase/claim` response body under
   `Cache-Control: no-store`; spectators observe via WebSocket. `GET /showcase` and spectator outcomes
   (`outcome: "spectating"`) never include player credentials.
4. `finishing`: The game has ended in memory; terminal persistence is executing atomically. The table
   remains closed to new claims until the transaction commits.

### Fail-closed persistence and recovery rules

Showcase games enforce strict, fail-closed durability fences:
- Initial room snapshots and every intermediate move/roll must commit to PostgreSQL before turn acknowledgement,
  client broadcast, or bot webhook dispatch.
- If a required save fails, the room immediately halts forward game progress, retries with bounded backoff, and
  suppresses turn acknowledgement, spectator event broadcast, and bot webhook delivery for the uncommitted version.
- If persistence fails unrecoverably or times out, the table transitions directly to `unavailable`. Connected player
  and spectator WebSocket sessions settle cleanly with close code 1011 (internal error) or a typed termination
  notice, and the in-memory room is stopped.
- The table cannot reopen or accept new claims until terminal persistence (final snapshot, `game_results`, immutable
  `game_archive` with `origin = 'showcase'`, and outbox payload) has successfully committed. On server boot or after an
  unclean restart, startup reconciliation reads the last durable snapshot or completes pending terminal persistence
  before reopening the table.

The full specification is codified in ADR-005 (#44); concurrency and capacity invariants are detailed in
[Concurrency](/dicechess-play-api/concurrency/).

