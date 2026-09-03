---
title: Reserved Stake-Doubling Contract
description: The accepted future x2 stake and webhook contract. It is documented for implementers but is not enabled on the live API.
---

:::caution[Reserved — not available yet]
This page specifies the contract implementation must follow. The `doubling` webhook capability is
still `reserved` and unselectable, and the live API does not yet accept staked games or the actions
below. Schema publication does not enable behaviour.
:::

The authoritative engineering decision is
[ADR-0019](https://fortemate.com/dicechess-play-api/stake-doubling/). The validated schema bundle and
fixtures are available at
[`/contracts/stake-doubling/v1/schema.json`](/contracts/stake-doubling/v1/schema.json) and
[`/contracts/stake-doubling/v1/fixtures.json`](/contracts/stake-doubling/v1/fixtures.json).

## What a stake means

Stake doubling is opt-in. The stake is one seat's exposure in positive integer `PLAY_CREDIT`, a
closed-loop game credit with no purchase, redemption, cash, token, prize, or transfer meaning in this
protocol. It is not the combined pot.

Both seats reserve `initialStake * maximumMultiplier` before a game starts. A wallet may settle the
result, but wallet ids, balances, reservation ids, credentials, and settlement internals never appear
in the game or webhook payloads.

The cube begins centered at `1`. Either seat may offer before rolling on its own turn. Once accepted,
the stake doubles and cube ownership moves to the responder; only that owner may offer a later double.
The maximum multiplier is fixed at game creation and cannot exceed `64`. Declining loses the current
pre-offer stake, not the proposed doubled amount.

Staked games are casual, durable games between supported, authenticated participants. They are never
ladder or showcase games. Omitting `stake` preserves the existing classic protocol.

## Creation opt-in

The first version adds `stake` to authenticated `POST /lobby/play-bot` requests:

```json
{
  "team": "house",
  "name": "aggressive",
  "timeControl": { "Fischer": { "initialSeconds": 300, "incrementSeconds": 3 } },
  "preferredColor": "White",
  "rated": false,
  "stake": {
    "amount": 10,
    "currency": "PLAY_CREDIT",
    "maximumMultiplier": 64
  }
}
```

A direct registered-bot `POST /bot/challenge` carries the same complete agreement as part of the
offer:

```json
{
  "team": "house",
  "name": "defender",
  "timeControl": { "Fischer": { "initialSeconds": 300, "incrementSeconds": 3 } },
  "rated": false,
  "stake": {
    "amount": 10,
    "currency": "PLAY_CREDIT",
    "maximumMultiplier": 64
  }
}
```

The whole object is required when present. There are no partial defaults. `maximumMultiplier` must be
one of `2, 4, 8, 16, 32, 64`, and the server validates the maximum amount without integer overflow.
`rated: true`, a guest, unavailable durable storage or settlement, an unsupported bot, or an
unsupported creation surface is rejected rather than downgraded.

## Public game state

A classic game omits `doubling` or sends `null`. A staked game always sends the complete object.

Before White rolls, an offer is legal:

```json
{
  "version": 17,
  "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
  "activeSeat": "White",
  "dicePending": false,
  "status": { "Active": {} },
  "timeControl": { "Fischer": { "initialSeconds": 300, "incrementSeconds": 3 } },
  "clocks": { "white": 295000, "black": 300000 },
  "commit": "c0ffee",
  "seed": null,
  "clientSeeds": null,
  "legalMoves": null,
  "players": null,
  "rated": false,
  "drawOffer": null,
  "mayOfferDraw": null,
  "doubling": {
    "currency": "PLAY_CREDIT",
    "initialStake": 10,
    "currentStake": 10,
    "cubeValue": 1,
    "cubeOwner": null,
    "maximumMultiplier": 64,
    "mayOfferDouble": true,
    "turnSeat": "White",
    "decision": {
      "id": "double_01K4F4Y7M8R2",
      "kind": "offer",
      "seat": "White",
      "proposedStake": 20
    }
  }
}
```

After White offers, Black becomes the decision actor while White remains the seat whose turn resumes
after a take. No dice exist yet:

```json
{
  "version": 18,
  "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
  "activeSeat": "Black",
  "dicePending": false,
  "status": { "Active": {} },
  "timeControl": { "Fischer": { "initialSeconds": 300, "incrementSeconds": 3 } },
  "clocks": { "white": 294000, "black": 299000 },
  "commit": "c0ffee",
  "seed": null,
  "clientSeeds": null,
  "legalMoves": null,
  "players": null,
  "rated": false,
  "drawOffer": null,
  "mayOfferDraw": null,
  "doubling": {
    "currency": "PLAY_CREDIT",
    "initialStake": 10,
    "currentStake": 10,
    "cubeValue": 1,
    "cubeOwner": null,
    "maximumMultiplier": 64,
    "mayOfferDouble": false,
    "turnSeat": "White",
    "decision": {
      "id": "double_01K4F4Y7M8R2",
      "kind": "response",
      "seat": "Black",
      "offeredBy": "White",
      "proposedStake": 20
    }
  }
}
```

`activeSeat` names the actor who must answer now. The active colour in `dfen` remains the actual chess
side to move and matches `doubling.turnSeat`; during a response it deliberately differs from
`activeSeat`. Runtime consumers use the explicit decision `seat` for the responder's perspective rather
than treating DFEN as a decision-actor marker. The active actor's clock runs; acceptance does not award
an increment. On acceptance the active actor returns to `turnSeat`, the stake and cube are updated, and
the roll follows immediately: cube ownership has moved to the responder, so the turn owner cannot
immediately re-offer.

## Commands and REST actions

The transport-neutral WebSocket commands are:

```json
{ "RequestRoll": { "decisionId": "double_01K4F4Y7M8R2" } }
```

```json
{ "OfferDouble": { "decisionId": "double_01K4F4Y7M8R2" } }
```

```json
{ "RespondDouble": { "decisionId": "double_01K4F4Y7M8R2", "accept": true } }
```

Equivalent authenticated Bot API operations are:

| Operation | Request body |
| --- | --- |
| `POST /bot/game/{id}/roll` | `{ "decisionId": "..." }` |
| `POST /bot/game/{id}/double/offer` | `{ "decisionId": "..." }` |
| `POST /bot/game/{id}/double/accept` | `{ "decisionId": "..." }` |
| `POST /bot/game/{id}/double/decline` | `{ "decisionId": "..." }` |

An applied action returns:

```json
{
  "applied": true,
  "version": 18,
  "decisionId": "double_01K4F4Y7M8R2",
  "duplicate": false,
  "reason": null
}
```

An identical retry returns the original outcome with `duplicate: true` and emits nothing:

```json
{
  "applied": true,
  "version": 18,
  "decisionId": "double_01K4F4Y7M8R2",
  "duplicate": true,
  "reason": null
}
```

An unknown, stale, wrong-seat, conflicting, or wrong-kind decision returns `409` with
`applied: false`. Like move submissions, a bounded server wait may fall back to `202`; the stream or
snapshot remains authoritative.

```json
{
  "applied": false,
  "version": 18,
  "decisionId": "double_01K4F4Y7M8R2",
  "duplicate": false,
  "reason": "decision_conflict"
}
```

## Stream events

An eligible staked pre-roll phase publishes a dice-free opportunity. If the cube belongs to the
opponent or has reached the configured cap, the existing automatic roll path continues without a
decision:

```json
{
  "DoubleOpportunity": {
    "v": 17,
    "decisionId": "double_01K4F4Y7M8R2",
    "seat": "White",
    "mayOfferDouble": true,
    "currentStake": 10,
    "proposedStake": 20
  }
}
```

Offering, taking, and dropping publish:

```json
{
  "DoubleOffered": {
    "v": 18,
    "offerId": "double_01K4F4Y7M8R2",
    "by": "White",
    "to": "Black",
    "currentStake": 10,
    "proposedStake": 20
  }
}
```

```json
{
  "DoubleAccepted": {
    "v": 19,
    "offerId": "double_01K4F4Y7M8R2",
    "by": "Black",
    "currentStake": 20,
    "cubeValue": 2,
    "cubeOwner": "Black"
  }
}
```

```json
{
  "DoubleDeclined": {
    "v": 19,
    "offerId": "double_01K4F4Y7M8R2",
    "by": "Black",
    "currentStake": 10,
    "proposedStake": 20,
    "reason": "declined"
  }
}
```

`reason` is `declined`, `timeout`, or `disconnect`. Only an explicit `declined` response uses the
public `DoubleDeclined` game termination. Timeout and disconnect keep their factual termination while
settling the same current stake.

## Webhook deliveries

Only a bot admitted with the `doubling` capability receives these signed deliveries. Both are
dice-free and carry the same complete `PublicGameState` served by snapshots.

Offer or roll:

```json
{
  "type": "doubleOpportunity",
  "gameId": "game-uuid",
  "seat": "White",
  "state": {
    "version": 17,
    "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "activeSeat": "White",
    "dicePending": false,
    "status": { "Active": {} },
    "timeControl": { "Fischer": { "initialSeconds": 300, "incrementSeconds": 3 } },
    "clocks": { "white": 295000, "black": 300000 },
    "commit": "c0ffee",
    "doubling": {
      "currency": "PLAY_CREDIT",
      "initialStake": 10,
      "currentStake": 10,
      "cubeValue": 1,
      "cubeOwner": null,
      "maximumMultiplier": 64,
      "mayOfferDouble": true,
      "turnSeat": "White",
      "decision": {
        "id": "double_01K4F4Y7M8R2",
        "kind": "offer",
        "seat": "White",
        "proposedStake": 20
      }
    }
  }
}
```

```json
{ "decisionId": "double_01K4F4Y7M8R2", "offerDouble": false }
```

Take or drop:

```json
{
  "type": "doubleDecision",
  "gameId": "game-uuid",
  "seat": "Black",
  "state": {
    "version": 18,
    "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "activeSeat": "Black",
    "dicePending": false,
    "status": { "Active": {} },
    "timeControl": { "Fischer": { "initialSeconds": 300, "incrementSeconds": 3 } },
    "clocks": { "white": 294000, "black": 299000 },
    "commit": "c0ffee",
    "doubling": {
      "currency": "PLAY_CREDIT",
      "initialStake": 10,
      "currentStake": 10,
      "cubeValue": 1,
      "cubeOwner": null,
      "maximumMultiplier": 64,
      "mayOfferDouble": false,
      "turnSeat": "White",
      "decision": {
        "id": "double_01K4F4Y7M8R2",
        "kind": "response",
        "seat": "Black",
        "offeredBy": "White",
        "proposedStake": 20
      }
    }
  }
}
```

```json
{ "decisionId": "double_01K4F4Y7M8R2", "acceptDouble": false }
```

A malformed, wrong-kind, failed, or late webhook response is not interpreted as a financial action;
the decision stays pending and the clock decides. Runtime strategy defaults explicitly return
`offerDouble: false` and `acceptDouble: false` when the callback itself succeeds.

## Ordering with draws, dice, and reconnects

- A pending draw response always resolves before the next double opportunity.
- Dice are neither derived nor revealed while a draw or double decision is pending.
- An accepted double returns to the original turn owner and then reveals the roll; it does not create
  a redundant ineligible opportunity.
- A forced pass is known only after the roll and follows the existing automatic pass path.
- A reconnecting client receives the exact `decisionId`, decision kind, cube, stake, and turn owner in
  `Snapshot`; it never infers the phase from `dicePending` alone.
- Repeated identical actions for a decision id are idempotent. Conflicting actions are rejected.
- At game end, the existing seed and client-seed reveal still occurs immediately.

## Capability rollout

Publishing this contract does not change the current catalog:

```json
{
  "capabilities": [
    { "name": "draws", "status": "available", "selectable": true },
    { "name": "doubling", "status": "reserved", "selectable": false }
  ]
}
```

The capability becomes available only after durable server state, settlement, runtime support,
analytics, and compatible clients have passed end-to-end staging checks. Rollback first stops new
staked admissions; active games keep their snapshotted contract until settlement.
