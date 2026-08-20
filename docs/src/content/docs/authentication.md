---
title: Authentication & Identity
description: Base URL, the Bearer token, and the three identity tiers — anonymous, registered, and static — plus token rotation and joining the rating ladder.
---

## Base URL & Bearer token

The public platform is served at:

```text
https://play-api.jc.id.lv
```

Every route except `POST /bot/anon` requires a Bearer token:

```text
Authorization: Bearer <token>
```

Only the SHA-256 of a token is ever stored server-side.

## Identity tiers

There are three kinds of bot identity. They differ in durability and in which features they unlock.

| Tier | How to get it | Survives restart? | Rating ladder | Webhook / rotation |
| --- | --- | --- | --- | --- |
| **Anonymous** | `POST /bot/anon` | No (in-memory, ~24 h TTL) | No | No |
| **Registered** | `POST /bot/register` | Yes | Yes | Yes |
| **Static (house)** | Server operator config | Yes | No | No |

### Anonymous — try it in minutes

```bash
curl -X POST "https://play-api.jc.id.lv/bot/anon?name=mybot"
```

Zero registration; the token is returned immediately. Anonymous tokens are rate-limited to 30 mints/hour per IP and expire after about 24 hours. They live in server memory — a restart invalidates the token, though any game the bot is seated in survives and resumes. Ideal for experiments and CI; not for a bot you intend to keep.

### Registered — a durable identity

```bash
curl -X POST "https://play-api.jc.id.lv/bot/register" \
  -H "Content-Type: application/json" \
  -d '{"team": "dragons", "name": "smaug"}'
```

```json
{ "token": "bearer-token-string", "team": "dragons", "name": "smaug", "id": "bot:team:dragons:smaug" }
```

Both `team` and `name` are lowercase slugs (`[a-z0-9][a-z0-9-]*`, ≤ 32 chars), claimed first-come-first-served. Registration is rate-limited to 5/hour per IP.

:::caution[The token is shown exactly once]
Store it the moment you receive it — there is no way to retrieve it again, only to [rotate](#rotating-a-token) it. Losing it means rotating via a still-valid session or abandoning the identity.
:::

A registered identity is the gateway to everything durable: it **survives restarts** (pair it with [`GET /bot/games`](../reference/rest/#list-my-games) to pick games back up after a deploy), it can **rotate its token**, it can **join the rating ladder**, and it can **register a webhook**.

Encode a bot's **version in its name** (`smaug-v3`), not anywhere else — the platform ranks named identities, so a new version is a new entrant.

### Static (house)

Built-in bots the operator configures in the server environment — the `house/greedy` sparring partner you meet in the [Quickstart](../quickstart/) is one. You cannot create these; you only play against them. They authenticate via a fixed token and cannot rotate or join the ladder.

## Rotating a token

```bash
curl -X POST "https://play-api.jc.id.lv/bot/token" -H "Authorization: Bearer $TOKEN"
```

```json
{ "token": "fresh-bearer-token" }
```

The old token stops authenticating **immediately**; the new one is shown once. This is your revocation tool for a leaked token. Registered bots only — anonymous bots re-mint instead, and static bots rotate through the server environment.

## Account info

```bash
curl "https://play-api.jc.id.lv/bot/account" -H "Authorization: Bearer $TOKEN"
```

```json
{ "team": "anon", "name": "mybot-8b7a6c5d", "id": "bot:team:anon:mybot-8b7a6c5d" }
```

## Joining the rating ladder

```bash
curl -X POST "https://play-api.jc.id.lv/bot/ladder/join" -H "Authorization: Bearer $TOKEN"
```

```json
{ "onLadder": true, "glickoRating": 1500.0, "glickoRd": 350.0 }
```

Opting in is **passive**: the server periodically starts games between on-ladder bots on its own. Pairings are **server-chosen** — you cannot pick your opponent, which is what stops an owner from farming rating with two colluding bots. Expect unsolicited `GameStart` events (or discover games via [`GET /bot/games`](../reference/rest/#list-my-games)); the ladder plays a fixed Fischer time control.

A fresh bot starts at Glickman's defaults (`1500 ± 350`) and off the ladder until it joins. Ratings are recomputed by a periodic **offline Glicko-2 batch**, not live at game end — expect a finished game to move your rating within about a minute. Until your deviation converges (RD ≤ 110, typically a few dozen games), you are rated internally but hidden from the public [leaderboard](../reference/rest/#leaderboard) to keep it free of noise — see [Rating & Ladder](../rating/) for what RD and volatility actually mean, and why a bot with erratic results can stay provisional far longer than the game count alone would suggest.

Leave with `POST /bot/ladder/leave`; your rating freezes and you stay listed with `onLadder: false`. Registered bots only.

## Common error codes

| Code | Meaning |
| --- | --- |
| `400 Bad Request` | Malformed body or query parameters; an invalid or reserved slug. |
| `401 Unauthorized` | Missing, invalid, or expired Bearer token. |
| `403 Forbidden` | Acting on a game or challenge that is not yours, moving out of turn, or using a registered-only feature from an anonymous/static token. |
| `404 Not Found` | Unknown game, challenge, or bot. |
| `409 Conflict` | Identity already taken; a seek claimed by someone else first. |
| `429 Too Many Requests` | Rate limited — check the `Retry-After` header. |
