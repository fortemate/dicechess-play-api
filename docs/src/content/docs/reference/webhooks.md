---
title: Webhooks
description: Register one HTTPS callback and let the server POST your turns — the response body is the move. A bot becomes a single stateless function.
---

The push alternative to streams and polling (**registered bots only**): register an HTTPS callback once, and the server POSTs to it whenever it is your turn — **the HTTP response body is your move**. A bot becomes a single stateless HTTPS handler, woken only when there is a decision to make. Works with every time control — a 1–3 s cold start is noise against a Fischer 300+3 budget.

Webhooks are enabled per server by the operator; when off, the legacy Bearer-token registration, inspection, and removal routes answer `503 Service Unavailable`, and no deliveries run. Two read-only exceptions remain available: the public [capability catalog](#discover-capabilities), because it describes the API contract rather than one bot's configuration, and [delivery stats](#how-to-see-what-is-happening), because they describe history. The per-turn wait is bounded by both a server cap — **120 s on the public deployment** — and the mover's remaining clock; see [How long you have to answer](#how-long-you-have-to-answer).

The Bearer-token routes documented first are intentionally unchanged. A separate, staged
[owner/admin management API](#owneradmin-staged-management) adds safe browser control without
turning a session cookie into a bot token or changing this legacy wire contract.

## Discover capabilities

`GET /bot/webhook/capabilities` is public: it needs no Bearer token and continues to answer when webhook delivery is disabled. It returns every canonical capability in stable registry order together with its current selection status:

```json
{
  "capabilities": [
    { "name": "draws", "status": "available", "selectable": true },
    { "name": "doubling", "status": "reserved", "selectable": false }
  ]
}
```

| Name | Status | Selectable | Meaning |
| --- | --- | --- | --- |
| `draws` | `available` | `true` | May be selected now; enables the [`drawDecision`](#draw-decision-delivery-drawdecision) delivery described below. |
| `doubling` | `reserved` | `false` | The canonical name is fixed, but it cannot be selected, stored, or acted on yet. The authoritative events and state machine belong to [play-api issue #37](https://github.com/fortemate/dicechess-play-api/issues/37). |

`available` means accepted by registration; `reserved` does **not** imply a partially implemented behavior. A registry update is an explicit API change — a reserved capability never becomes active merely because its name is present in this response.

## Register a webhook

`POST /bot/webhook`

```json
{ "url": "https://my-function.example.com/dicechess", "capabilities": ["draws"] }
```

The URL must be **HTTPS** and resolve to a **public** address — loopback, RFC1918, link-local, CGNAT and IPv6-ULA targets are rejected, so the server can never be pointed at anyone's internal network. Before anything is stored, the server runs an **ownership handshake**: it POSTs `{"type":"verification","nonce":"<random>"}` to the URL, and the endpoint must answer `200` with `{"nonce":"<the same value>"}`. This first legacy handshake cannot require HMAC verification: the endpoint receives its new secret only in the successful `201` response. Only then does the webhook become active — no game data is ever sent to an unverified URL.

- **Capabilities** (optional list of canonical names):
  - `"draws"`: Opt in to receive `drawDecision` webhook deliveries when an opponent offers a draw. If omitted, `null`, or empty (the default), draw offers are automatically declined by the server on behalf of the bot, immediately revealing dice and sending a normal `yourTurn` payload.
  - Matching is exact and case-sensitive. The server does not trim or lowercase input, so `"Draws"`, `" draws "`, `"double"`, and any other unknown value return `422` with an `unknown webhook capability` reason.
  - `"doubling"` is a known but reserved name and returns `422` with a `webhook capability is not available` reason until the complete protocol is implemented and deliberately enabled.
  - Duplicate selectable input is accepted and collapsed into stable registry order: `["draws", "draws"]` is stored and returned as `["draws"]`.
- **Response** `201`

  ```json
  { "url": "https://my-function.example.com/dicechess", "secret": "3f9a…64 hex chars…c2", "capabilities": ["draws"] }
  ```

  `secret` is the per-bot HMAC key the server signs every delivery with — **shown exactly once.** Keep it in your function's secret storage. Re-registering replaces both URL and secret.
- **Errors:** `403` anonymous/static caller; `422` with distinct plain-text reasons for an unknown/non-canonical capability or a recognized reserved capability; `422` for a URL-policy violation or failed handshake (the body says which); `429` per-IP registration budget; `503` webhooks disabled.

## Inspect / remove

`GET /bot/webhook` → `200 { "url": …, "capabilities": ["draws"], "verifiedAt": "2026-07-17T12:00:00Z" }` (the secret is never shown again), or `404` if none. A non-empty `capabilities` array is always canonical; no selection remains backward-compatible as `"capabilities": null` (clients may also tolerate the optional field being absent).

`DELETE /bot/webhook` → `204`; deliveries stop at the **next turn**. Mid-game included — the games themselves keep running and keep charging your clock.

## Owner/admin staged management

The session-management API is an additive control plane for the bot owner UI and break-glass
administration. It does **not** replace `POST /bot/webhook`, and enabling its routes does not by
itself enable turn delivery. The backend contract ships dark by default; a server exposes it only
when its operator explicitly enables the feature and supplies PostgreSQL persistence, account
sessions, and an exact browser-origin allow-list. This documentation describes the contract, not
the deployment state of any particular server. The public design record and threat-model summary
are tracked in [dicechess-play-api issue #10](https://github.com/fortemate/dicechess-play-api/issues/10).

Every operation is available under two roots with identical state-machine behavior:

| Operation | Owner route | Administrator route |
| --- | --- | --- |
| Read the redacted slot | `GET /me/bots/{team}/{name}/webhook` | `GET /admin/bots/{team}/{name}/webhook` |
| Create a pending setup | `POST /me/bots/{team}/{name}/webhook/setups` | `POST /admin/bots/{team}/{name}/webhook/setups` |
| Verify and activate it | `POST /me/bots/{team}/{name}/webhook/setups/{setupId}/activate` | `POST /admin/bots/{team}/{name}/webhook/setups/{setupId}/activate` |
| Cancel it | `DELETE /me/bots/{team}/{name}/webhook/setups/{setupId}` | `DELETE /admin/bots/{team}/{name}/webhook/setups/{setupId}` |
| Replace capabilities only | `PATCH /me/bots/{team}/{name}/webhook/capabilities` | `PATCH /admin/bots/{team}/{name}/webhook/capabilities` |
| Delete active and pending state | `DELETE /me/bots/{team}/{name}/webhook` | `DELETE /admin/bots/{team}/{name}/webhook` |
| Read delivery history | `GET /me/bots/{team}/{name}/webhook/stats` | `GET /admin/bots/{team}/{name}/webhook/stats` |

The `/me` root requires the live `access_token` session to own the bot. The `/admin` root requires
the live account id to be in `PLAY_ADMINS`. Both re-read live authority; an ownership transfer,
account deactivation, or administrator removal cannot be hidden by an old cookie.

During a rolling `PLAY_ADMINS` change, each enabled API instance heartbeats its hashed allow-list
generation every 5 seconds and a generation remains live for 20 seconds. If old and new generations
overlap — or no generation is live — the staged `/admin` routes fail closed with
`403 admin_required` until the deployment converges. This prevents two replicas from activating
setups under different administrator lists. The `/me` owner routes are unaffected.

### Browser and concurrency contract

All responses carry `Cache-Control: no-store`. Each slot response also carries a **strong** ETag,
for example `ETag: "whrev_0197…"`. Treat the revision as opaque: never derive or increment it.
Every mutation requires all of the following:

- the `access_token` session cookie;
- an `Origin` header that exactly matches one entry in `PLAY_CORS_ORIGINS`;
- `X-DiceChess-CSRF: 1`;
- the last strong ETag copied byte-for-byte into `If-Match`;
- `Content-Type: application/json` whenever the route has a JSON body.

A missing `If-Match` is `428 Precondition Required`. Wildcards, weak tags, lists, and unquoted
revisions are `400 Bad Request`. A well-formed but stale revision is `412 Precondition Failed`;
that problem response includes the current redacted slot and its ETag, so the UI can show what
changed before asking the user to retry. Missing or unlisted Origin and a missing/wrong CSRF value
are `403 Forbidden`; JSON-body routes answer `415 Unsupported Media Type` without JSON content.

The redacted read is explicit even when nothing is configured:

```json
{
  "revision": "whrev_0197…",
  "registration": null,
  "pendingSetup": null
}
```

An active registration exposes its opaque `registrationId`, URL, verification time, canonical
capabilities, and last safe failure summary. A pending setup exposes only its id, kind, candidate
URL, timestamps, and whether this actor may activate it. Neither shape contains a secret.

### Setup, store, activate

There can be only one live pending setup per bot, and it expires after 15 minutes. Create exactly
one of these request objects — fields are variant-specific and unknown or cross-variant fields are
rejected:

```json
{ "kind": "create", "url": "https://bot.example/turn", "capabilities": ["draws"] }
```

```json
{ "kind": "replaceUrl", "url": "https://bot-v2.example/turn", "confirmSecretRotation": true }
```

```json
{ "kind": "rotateSecret", "cutoverMode": "dualKey", "confirm": "exact-bot-name" }
```

`create` is valid only without an active registration. `replaceUrl` and `rotateSecret` require
one. Both replacement variants mint a new secret and eventually a new `registrationId`;
`replaceUrl` also requires a different URL. A capability-only patch is separate:

```json
{ "capabilities": ["draws"] }
```

It preserves the URL, secret, verification time, registration id, and current health. Only the
currently selectable capability (`draws`) is accepted; reserved or unknown names are `422`.

Creating a setup returns `201` with `Location`, the new ETag, `Cache-Control: no-store`, and
`Pragma: no-cache`:

```json
{
  "setupId": "whs_0197…",
  "kind": "create",
  "secret": "64 lowercase hex characters",
  "expiresAt": "2026-09-01T10:15:00Z",
  "revision": "whrev_0197…"
}
```

This is the **only** response that contains the candidate secret. Store it before activation. If
the response is lost, read the slot, cancel the redacted pending setup, and create another one;
there is no secret-recovery endpoint.

After storing the secret, activate with the setup's current slot ETag:

```json
{ "secretStored": true }
```

Activation has one in-flight attempt at a time and at most five attempts. An attempt is consumed
when the server acquires its database lease, before it sends verification-v2; a server crash or
timeout after that point does not refund it. This means no more than five outbound verification
requests can begin for one setup. A failed fifth verification atomically destroys the candidate
and returns `410 Gone` with `setup_attempts_exhausted`; retry by creating a fresh setup from the
new current revision. Setup expiry, lease expiry, the verification budgets and the
15-minute tombstone window are decided against the shared PostgreSQL clock, so a skewed API
instance cannot extend them. Treat `expiresAt` and `Retry-After` as server-authoritative.
The old registration remains active until verification succeeds and the new registration commits.
An activated, cancelled, expired, invalidated, or attempts-exhausted setup remains a redacted
tombstone for 15 minutes (`410 Gone`), then becomes `404 Not Found`. Cancelling a live setup does
not disturb the active registration. Deleting the webhook requires an exact bot-name confirmation:

```json
{ "confirm": "exact-bot-name" }
```

It destroys active and candidate credentials and stops future deliveries while retaining
bot-history telemetry and the audit trail. Deleting an already empty slot is a true no-op: its ETag
does not change and no audit action is invented.

### Verification v2

Activation POSTs a fresh, compact JSON body to the candidate URL:

```json
{
  "type": "verification",
  "version": 2,
  "bot": { "team": "acme", "name": "greedy" },
  "setupId": "whs_0197…",
  "revision": "whrev_0197…",
  "nonce": "base64url-without-padding"
}
```

The request uses the same delivery headers and signature formula as normal webhook traffic:
`HMAC-SHA256(secretUtf8, timestamp + "." + rawBodyBytes)`. Respond `200 OK` with exactly two fields:

```json
{ "nonce": "the-exact-request-nonce", "proof": "64 lowercase hex characters" }
```

Compute `proof` as
`HMAC-SHA256(secretUtf8, "dicechess-webhook-activate-v2\n" || rawBodyBytes)`. Verify and sign the
raw bytes, not parsed/reformatted JSON. The server requires an exact nonce echo and compares the
proof in constant time. It uses a bounded verification timeout and connects only to the public IP
address it validated for the candidate hostname; Host and TLS SNI remain the original hostname.

For `replaceUrl` and `rotateSecret`, the endpoint needs dual-key pending configuration. Keep
accepting ordinary game deliveries signed by the current active secret while using the candidate
secret only to validate the activation-v2 signature and produce its proof. A successful challenge
does not prove that the server committed the change: promote the pending key and retire the old key
only after an authoritative slot `GET` shows the new `registrationId` and revision. If commit fails
or the activation response is lost, the old key remains active.

### Errors and stats scope

Errors use `application/problem+json` with stable `code`, `status`, `title`, `detail`, and
`instance` fields. A stale-revision problem additionally carries `current`; a rate limit also
carries `Retry-After`. Common outcomes include `409` for an incompatible state or another pending
setup, `410` for a setup tombstone, `422` for URL/capability/verification rejection, `429` for the
cross-instance verification budget, and `503` when verification is unavailable. Secret material,
raw transport exceptions, and resolved infrastructure details never appear in errors.

The session stats response adds `"scope": "bot_history"` and the current `registrationId` (or
`null`) to the legacy two-window shape. Counts intentionally survive replacement, rotation, and
deletion: `registrationId` tells the UI which active generation owns current health, while the
windows answer what has happened to this bot over time.

## Webhook deliveries

### Turn delivery (`yourTurn`)

When it is your turn in any of your games and dice are revealed, the server POSTs **one** request:

```json
{
  "type": "yourTurn",
  "gameId": "game-uuid",
  "seat": "White",
  "state": { "version": 4, "dfen": "…", "activeSeat": "White", "dicePending": true, "legalMoves": { "e2e4": {} }, "mayOfferDraw": true, "clocks": { "white": 295000, "black": 300000 }, "…": "…" }
}
```

`state` is exactly the [`Snapshot.state`](../streaming/#snapshot) object — `dfen` (dice in its 7th field), `activeSeat`, `dicePending`, `clocks`, `commit`, `mayOfferDraw` (whether you may offer a draw under alternation anti-spam), and the inline [`legalMoves`](../../game-mechanics/#legal-moves) tree under the usual cap (fetch [`GET /games/{id}/moves`](../rest/#get-legal-moves) when it is `null`). The envelope is self-sufficient: a pure function picks its move from `legalMoves` alone.

### Draw decision delivery (`drawDecision`)

If your webhook declared the `"draws"` capability and the opponent offered a draw, the server pauses auto-roll and POSTs a `drawDecision` request **without dice**:

```json
{
  "type": "drawDecision",
  "gameId": "game-uuid",
  "seat": "Black",
  "state": { "version": 3, "dfen": "…", "activeSeat": "Black", "dicePending": false, "drawOffer": { "pending": true }, "clocks": { "white": 300000, "black": 298000 }, "…": "…" }
}
```

- Answer `200` with `{"acceptDraw": true}` to accept the draw (game ends ½–½).
- Answer `200` with `{"acceptDraw": false}` (or empty `{}` / timeout) to decline the draw. The server immediately reveals your dice and sends a subsequent `yourTurn` payload!

### Verify delivery signatures

Every ordinary `yourTurn`/`drawDecision` delivery, and every staged verification-v2 challenge,
carries two headers:

```text
X-DiceChess-Timestamp: 1752750000
X-DiceChess-Signature: <64-char hex HMAC-SHA256>
```

Verify `HMAC-SHA256(secret, timestamp + "." + rawBody)` equals the signature header and the timestamp is within a few minutes before computing your move.

The initial legacy `POST /bot/webhook` ownership handshake happens before its newly generated
secret is disclosed. Echo its nonce without requiring HMAC; require HMAC for all deliveries after
the `201` response. The staged owner/admin flow is different: its candidate secret is returned at
setup creation, before activation, so verification-v2 must verify the request signature and return
the proof described above.

### Respond with the move

Answer within the timeout with the same shape [`POST /bot/game/{id}/move`](../rest/#submit-turn-moves) accepts:

```json
{ "moves": ["e2e4", "g1f3"], "offerDraw": false }
```

- `200` with a legal turn → the move is played (same engine validation as the move endpoint). Can piggyback `"offerDraw": true` to offer a draw.
- Anything else — a timeout, a non-200, a malformed body, an illegal turn, or `{"moves": []}` without `acceptDraw` — plays nothing: **your clock keeps running**, and the game forfeits on time exactly as if a polling bot had stopped polling.

Delivery is **single-attempt** by design — no retries, no redelivery. The recovery budget for a transient glitch is your remaining clock, not a queue.

### How long you have to answer

```text
min(your remaining clock, the server cap, your own platform's request timeout)
```

- **Your remaining clock** is the real budget. Spend it unevenly if you like — 50 ms in the opening, a minute on a critical position; you pay for it later in the same game, and nothing else objects. In an `Unlimited` game there is no clock, so a fixed **120-second anti-abandonment cap** applies to the turn instead.
- **The server cap** is **120 s** on the public deployment (operators configure it; it is printed at server start). It exists so that an endpoint which accepts a connection and then goes quiet cannot hold a game open forever.
- **Your own platform's request timeout** is the term most bots actually hit, because a webhook turn is computed *inside* an inbound HTTP request: whatever sits in front of your code counts thinking as a slow response. It is the one term we cannot see or raise for you.

| Where your webhook runs | Cut at | Raising it |
| --- | --- | --- |
| AWS API Gateway (REST) | 29 s | quota increase (costs account throttle quota), or a Lambda Function URL instead (15 min) |
| AWS Application Load Balancer | 60 s idle | configurable to 4000 s |
| OCI API Gateway | 60 s | hard maximum — route around the gateway |
| OCI Functions (sync) | 30 s | configure the function timeout |
| Azure Functions / App Service | 230 s | hard — the load balancer's idle timeout, unchanged by plan or `functionTimeout` |
| Cloudflare Workers | 30 s **CPU** (paid); 10 ms CPU (free) | raise `cpu_ms`, up to 5 min |
| Cloudflare proxy (orange cloud) | 100 s | Enterprise `proxy_read_timeout`, or serve the bot from a DNS-only hostname |
| Google Cloud Run | 300 s | `--timeout`, up to 3600 s |

Note the shape of the failure when your platform is the binding term: it answers instead of you, and the server records the status it sent — a `504` or a `524` — rather than a timeout. That is a diagnosable outcome; size your bot's own thinking budget under this number and it never happens.

If your bot wants long thinking time and its platform will not give it, the fix is not a bigger timeout — it is a different [connection mode](../../connection-modes/#how-long-you-may-think). Poll and stream bots hold no inbound request while they think, so none of the ceilings above apply to them.

### How many games you can hold

The window above is one half of a contract; your capacity is the other. Webhook delivery inverts the usual model — the server pushes and you must answer — so the only place to say "one game at a time" is your own declaration:

```text
POST /bot/capacity   { "maxConcurrentGames": 2 }
```

A registered bot starts at **one**, and the limit is applied when a game is seated, never by queuing a turn inside a game you are already playing (that would spend your clock while you wait). If your function is billed or throttled per concurrent invocation, this is the knob that keeps three simultaneous games from turning into three time losses. See [Concurrent games](../rest/#concurrent-games) for the full response shape and what a refused seat looks like on each path.

A webhook bot on the [rating ladder](../../authentication/#joining-the-rating-ladder) is fully passive: the scheduler starts the games and the webhook delivers the turns — the function needs no other integration. (Webhook bots do not contribute a client dice seed today; the [provably-fair scheme](../../provably-fair/) covers them with the participant-bound fallback, and the seed endpoint stays available to hybrid bots that also hold streams.)

### How to see what is happening

A bot that receives few turns has three otherwise-indistinguishable explanations: it declared a low [capacity](#how-many-games-you-can-hold) and is genuinely using it, it isn't being picked, or its endpoint is actually failing. The first is answered by `activeGames` in [`GET /bot/capacity`](../rest/#concurrent-games); the third — the one this server could see all along but never surfaced — is answered here:

```text
GET /bot/webhook/stats
```

```json
{
  "last24h": {
    "totalDeliveries": 812,
    "outcomes": [{ "outcome": "applied", "count": 790 }, { "outcome": "http_503", "count": 22 }],
    "p50Ms": 200, "p90Ms": 1000, "p99Ms": 5000
  },
  "last7d": { "…": "same shape, over 7 days" },
  "lastFailure": { "at": "2026-08-01T10:00:00Z", "reason": "the endpoint answered HTTP 503" }
}
```

Two windows — 24 hours and 7 days — each with a count per outcome and three latency percentiles. Outcomes are named for what actually happened: `applied` (a usable move), `declined` (you sent `{"moves":[]}` on purpose), `refused` (the room rejected the moves — stale or illegal), `garbled` (the body didn't decode), `oversized_body`, `http_<code>` (your endpoint answered, just not `200` — this is exactly the "your own platform's request timeout" row from the table above, made visible: a `504` or `524` here means your gateway cut the turn, not this server), `timed_out` (nothing arrived within this server's own window), `unreachable` (connection refused, DNS, or similar), and `stale_registration` (the response arrived after its registration generation was replaced or deleted and was deliberately discarded). `lastFailure` is the most recent of everything except `applied`/`declined`/`stale_registration` — the answer to "is it still broken, and since when" that a table of counts alone can't give.

Percentiles are bucket-resolution approximations (a fixed set of latency buckets, log-spaced from 50 ms to 300 s), not exact — enough to tell "my p99 moved from 2 s to 30 s" without needing millisecond precision. Recording never sits on the turn path: a delivery is classified and queued the instant it completes, and a slow or unavailable stats write only ever costs a dropped data point, never a turn.

`GET /bot/webhook/stats` needs a registered identity (`403` otherwise, same as the rest of this page) and answers `404` on a server that runs without a database — same as the leaderboard and catalog.
