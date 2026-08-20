---
title: Webhooks
description: Register one HTTPS callback and let the server POST your turns — the response body is the move. A bot becomes a single stateless function.
---

The push alternative to streams and polling (**registered bots only**): register an HTTPS callback once, and the server POSTs to it whenever it is your turn — **the HTTP response body is your move**. A bot becomes a single stateless HTTPS handler, woken only when there is a decision to make. Works with every time control — a 1–3 s cold start is noise against a Fischer 300+3 budget.

Webhooks are enabled per server by the operator; when off, every endpoint below answers `503 Service Unavailable` — except [delivery stats](#how-to-see-what-is-happening), which read history and answer regardless of whether the feature is currently on. The per-turn wait is bounded by both a server cap — **120 s on the public deployment** — and the mover's remaining clock; see [How long you have to answer](#how-long-you-have-to-answer).

## Register a webhook

`POST /bot/webhook`

```json
{ "url": "https://my-function.example.com/dicechess", "capabilities": ["draws"] }
```

The URL must be **HTTPS** and resolve to a **public** address — loopback, RFC1918, link-local, CGNAT and IPv6-ULA targets are rejected, so the server can never be pointed at anyone's internal network. Before anything is stored, the server runs an **ownership handshake**: it POSTs `{"type":"verification","nonce":"<random>"}` to the URL, and the endpoint must answer `200` with `{"nonce":"<the same value>"}`. Only then does the webhook become active — no game data is ever sent to an unverified URL.

- **Capabilities** (optional list of strings):
  - `"draws"`: Opt-in to receive `drawDecision` webhook deliveries when an opponent offers a draw. If omitted or empty (the default), draw offers are automatically declined by the server on behalf of the bot, immediately revealing dice and sending a normal `yourTurn` payload.
- **Response** `201`

  ```json
  { "url": "https://my-function.example.com/dicechess", "secret": "3f9a…64 hex chars…c2", "capabilities": ["draws"] }
  ```

  `secret` is the per-bot HMAC key the server signs every delivery with — **shown exactly once.** Keep it in your function's secret storage. Re-registering replaces both URL and secret.
- **Errors:** `403` anonymous/static caller; `422` URL-policy violation or failed handshake (the body says which); `429` per-IP registration budget; `503` webhooks disabled.

## Inspect / remove

`GET /bot/webhook` → `200 { "url": …, "capabilities": ["draws"], "verifiedAt": "2026-07-17T12:00:00Z" }` (the secret is never shown again), or `404` if none.

`DELETE /bot/webhook` → `204`; deliveries stop at the **next turn**. Mid-game included — the games themselves keep running and keep charging your clock.

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

### Verify the signature

Every delivery (the verification handshake included) carries two headers:

```text
X-DiceChess-Timestamp: 1752750000
X-DiceChess-Signature: <64-char hex HMAC-SHA256>
```

Verify `HMAC-SHA256(secret, timestamp + "." + rawBody)` equals the signature header and the timestamp is within a few minutes before computing your move.

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

Two windows — 24 hours and 7 days — each with a count per outcome and three latency percentiles. Outcomes are named for what actually happened: `applied` (a usable move), `declined` (you sent `{"moves":[]}` on purpose), `refused` (the room rejected the moves — stale or illegal), `garbled` (the body didn't decode), `oversized_body`, `http_<code>` (your endpoint answered, just not `200` — this is exactly the "your own platform's request timeout" row from the table above, made visible: a `504` or `524` here means your gateway cut the turn, not this server), `timed_out` (nothing arrived within this server's own window), and `unreachable` (connection refused, DNS, or similar). `lastFailure` is the most recent of everything except `applied`/`declined` — the answer to "is it still broken, and since when" that a table of counts alone can't give.

Percentiles are bucket-resolution approximations (a fixed set of latency buckets, log-spaced from 50 ms to 300 s), not exact — enough to tell "my p99 moved from 2 s to 30 s" without needing millisecond precision. Recording never sits on the turn path: a delivery is classified and queued the instant it completes, and a slow or unavailable stats write only ever costs a dropped data point, never a turn.

`GET /bot/webhook/stats` needs a registered identity (`403` otherwise, same as the rest of this page) and answers `404` on a server that runs without a database — same as the leaderboard and catalog.
