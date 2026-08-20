---
title: Concurrency Doctrine
description: The single-writer rule for GameRoom, non-blocking event fan-out, and the invariants enforced in review.
---

These rules are enforced in code review. They exist because the server is authoritative over
live games: a stalled fiber or a trusted client field is not a cosmetic bug here, it is a
corrupted or frozen game.

## One writer per room

A `GameRoom` has **a single consumer fiber, and it is the only writer of game state**. Requests
do not mutate a room; they hand it an intent, and the room's own fiber applies it in order.
That is what makes the state machine reasonable without locks — and it is why introducing a
`synchronized` block or a shared mutable field around a room is a design error rather than an
optimisation.

**Who sits where is game state too.** A seat's principal can change exactly once, when a friend
redeems its join token (#285), and that write goes through the inbox like every move: the route
calls `GameRegistry.claimSeat`, which offers a message and awaits the room's answer. It does not
reach into the room's players map, even though the change looks like bookkeeping rather than
gameplay. The room answers whether the rebind happened, so the caller never has to guess.

## Fan-out must never block the room

Events reach subscribers — WebSocket clients, ndjson streams, webhook dispatch — through
**bounded per-subscriber queues, written with a non-blocking `tryOffer`**. If a subscriber is
not draining its queue, its event is dropped and, past the bound, the subscriber itself is
dropped. The room does not wait. A single slow consumer must never be able to stall a game for
its opponent.

## Rooms know nothing about transports

A room depends only on `Principal`, `Seat`, and `PlayerConnection`. It never references a
WebSocket, an HTTP response, or a webhook. Adding a transport means implementing
`PlayerConnection`, not touching `GameRoom`.

## The server trusts nothing from the client

No code path may accept a client-supplied FEN, dice roll, clock value, or result. Legality is
decided by `EngineOps` against the engine artifact; dice come only from `DiceSource`. This is
not defence in depth against a hostile bot alone — it is what makes the provably-fair dice
promise meaningful.

## Effects and lifecycles

Everything is cats-effect `IO`. No nulls, and no exceptions for control flow — errors are
returned as values (`GameRegistry.create` returns a `Left`, for instance). Lifecycles are
`Resource`; background fibers are scoped with `.background` / `.surround` so a failure surfaces
instead of vanishing.

## The dice path is a public promise

`dice/DiceSource.scala` implements commit-reveal fairness: a SHA-256 commitment published up
front, HMAC-SHA256 rolls mixing in client entropy, length-prefixed framing. Third parties
verify their games against the published procedure. Changing this file requires golden test
vectors **and** the matching update to the public verification procedure on
[bots.fortemate.com](https://bots.fortemate.com/provably-fair/), in the same pull request.

## A trap worth knowing before you write a test

A game with an **idle seat and no clock deadlocks**. From the starting position only pawns and
knights can move, so a roll containing neither makes the room auto-pass to the other seat; with
an unlimited time control and nobody driving that seat, play stops forever. It is
dice-dependent, so it fails roughly 30% of the time and looks exactly like a timeout bug — it
has twice been misdiagnosed as fiber starvation and "fixed" by widening a timeout. If a test
needs a specific seat to get an actionable turn, drive the opponent rather than assuming the
opening roll falls that seat's way. See [Testing](/dicechess-play-api/testing/).
