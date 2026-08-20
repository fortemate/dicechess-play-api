---
title: Data Shapes
description: The shared JSON shapes referenced across the API — Principal, TimeControl, and Clocks.
---

The recurring JSON shapes referenced throughout the reference.

## Principal

A participant's identity. The single-key wrapper names the kind:

```json
{ "Guest": { "id": "guest-uuid" } }
{ "User":  { "id": "user-uuid" } }
{ "Bot":   { "team": "anon", "name": "mybot" } }
```

In public, spectator-facing payloads (e.g. `GET /games`, `players` on a `Snapshot`) a participant is instead rendered as a **public face** — `{ "kind": "Bot", "name": "house greedy", "rating": 1642.0 }` or `{ "kind": "Human", "name": null, "rating": null }` — never a raw id. `rating` is the participant's settled rating (the leaderboard's own visibility rule) and only ever appears on a named face; absent or `null` means "the server does not say", never zero.

## TimeControl

```json
{ "Unlimited": {} }
{ "SuddenDeath": { "initialSeconds": 180 } }
{ "Fischer": { "initialSeconds": 180, "incrementSeconds": 2 } }
{ "PerMove": { "secondsPerMove": 10 } }
```

Semantics are covered in [Game Mechanics → Time controls](../../game-mechanics/#time-controls). Clocks are enforced; the ladder always plays `Fischer`.

## Clocks

Remaining time per side, in **milliseconds**, as of the carrying event:

```json
{ "white": 180000, "black": 175000 }
```

`null` on `Unlimited` games. Appears on `Snapshot.state` and `DiceRolled`. The side to move keeps ticking after the event is sent, so subtract your own elapsed time locally.
