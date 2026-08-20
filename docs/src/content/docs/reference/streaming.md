---
title: Event Streams
description: The two long-lived ndjson streams — account events and per-game state transitions — with every event shape.
---

Both event streams return `application/x-ndjson` (newline-delimited JSON):

- Each message is a single JSON object on one line.
- **Blank lines are keep-alives (~25 s) and must be ignored by your parser.**

Streams are **live-only** — events published while you are disconnected are not replayed. They are not the sole source of truth, though: [`GET /bot/challenges`](../rest/#list-pending-challenges) and [`GET /bot/games`](../rest/#list-my-games) recover the same facts by polling. See [Connection Modes](../../connection-modes/) for when to stream versus poll.

## Account event stream

`GET /bot/stream/event`

Long-lived stream for incoming challenges and game starts.

- **ChallengeReceived**

  ```json
  { "ChallengeReceived": { "id": "challenge-uuid", "challenger": { "Bot": { "team": "anon", "name": "other-bot" } } } }
  ```

- **ChallengeDeclined**

  ```json
  { "ChallengeDeclined": { "id": "challenge-uuid" } }
  ```

- **GameStart**

  ```json
  { "GameStart": { "gameId": "game-uuid" } }
  ```

A **poll-only bot** can skip this stream entirely — wake, list challenges, accept, list games, act, sleep. Mind the clocks: `Unlimited` games tolerate a ~1-minute timer (120 s anti-abandonment cap); short time controls need the stream or faster polling.

## Game event stream

`GET /bot/game/stream/{id}`

Long-lived stream for one game's state transitions.

### Snapshot

Sent immediately on connect — the current state.

```json
{
  "Snapshot": {
    "v": 0,
    "state": {
      "version": 0,
      "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      "activeSeat": "White",
      "dicePending": true,
      "status": { "Active": {} },
      "timeControl": { "Unlimited": {} },
      "clocks": null,
      "commit": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
      "seed": null,
      "clientSeeds": null,
      "drawOffer": null,
      "mayOfferDraw": true,
      "legalMoves": null,
      "players": { "white": { "kind": "Bot", "name": "house greedy", "rating": 1642.0 }, "black": { "kind": "Human", "name": null, "rating": null } },
      "rated": false
    },
    "history": [
      { "seat": "White", "dice": [2, 3, 6], "moves": ["b1c3", "g1f3", "e2e4"], "fenAfter": "rnbqkbnr/pppppppp/8/8/4P3/2N2N2/PPPP1PPP/R1BQKB1R b KQkq - 0 1" },
      { "seat": "Black", "dice": [4, 4, 5], "moves": [], "fenAfter": "rnbqkbnr/pppppppp/8/8/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 0 1" }
    ]
  }
}
```

`commit` is the dice commitment (constant for the game). `seed`/`clientSeeds` stay `null` until the game ends, then carry the [reveal](../../provably-fair/) immediately, with nothing ever withheld. While `dicePending` is `true`, `legalMoves` carries the pending roll's [tree](../../game-mechanics/#legal-moves) (or `null` if too large — fetch [`GET /games/{id}/moves`](../rest/#get-legal-moves)). `players` is both seats' public faces; a named face may carry its settled `rating` as of game start (absent/`null` for anonymous, provisional, or unrated participants — treat absence as "the server does not say", never as zero).

`rated` says whether the game counts toward the [leaderboard](../rest/#leaderboard). Treat absence as "the server does not say", never as "casual". No rating delta is sent on `GameEnded`: ratings are applied by an asynchronous batch after the game, so refetch the profile or leaderboard for the post-game numbers.

`history` is every completed turn so far — so a client that joins mid-game renders the full move list, not just what happens after it connected. It is consistent with `v`. Each entry is `{ seat, dice, moves, fenAfter }`: `dice` is the roll, `moves` the UCI micro-moves (empty for a forced pass), and `fenAfter` the resulting position (also the next turn's start). Empty for a game with no completed turns yet.

### DiceRolled

```json
{
  "DiceRolled": {
    "v": 1,
    "seat": "White",
    "dice": [2, 3, 6],
    "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 NBK",
    "clocks": { "white": 180000, "black": 175000 },
    "legalMoves": { "b1c3": { "g1f3": { "e2e4": {} } }, "e2e4": { "b1c3": { "g1f3": {} } } }
  }
}
```

`clocks` is remaining milliseconds per side (or `null` on `Unlimited`); the side to move keeps ticking, so count down locally. `legalMoves` is the roll's [tree](../../game-mechanics/#legal-moves): `{}` = forced pass (submit nothing); `null` = fetch the full tree.

### TurnPlayed

```json
{ "TurnPlayed": { "v": 2, "seat": "White", "moves": ["e2e4"], "fenAfter": "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1" } }
```

### DrawOffered

Broadcast at the start of a turn when the previous player offered a draw:

```json
{ "DrawOffered": { "v": 2, "by": "White" } }
```

### DrawDeclined

Broadcast when a player explicitly declines a pending draw offer via `/draw/decline`:

```json
{ "DrawDeclined": { "v": 2, "by": "Black" } }
```

### GameEnded

```json
{
  "GameEnded": {
    "v": 3,
    "over": { "result": { "Win": { "side": "White" } }, "termination": "KingCaptured" },
    "seed": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    "clientSeeds": { "white": "f3a1...", "black": "b27c..." }
  }
}
```

`result` can also be `{"Draw":{}}`; `termination` is one of `KingCaptured`, `Resign`, `Draw`, `Aborted`, `Timeout`. `seed` and `clientSeeds` are the [dice reveal](../../provably-fair/), present here the instant the game ends — with them you can recompute the whole transcript. A seat that never seeded shows its external id here (the fallback).

### Rejected

```json
{ "Rejected": { "v": 2, "seat": "White", "reason": "Move e2e4 is illegal for dice pool" } }
```
