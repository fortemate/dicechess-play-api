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

## Webhook responses need a registration fence

A webhook turn can spend seconds outside this process while an owner rotates its secret, replaces
its URL, or deletes the registration on another replica. Reading a registration before the HTTP
call is therefore not authority to apply the response afterward. Every active row has a
`registration_id`; delivery retains that generation and, before submitting moves, takes the same
per-bot PostgreSQL advisory fence used by control-plane writes and rechecks that it is still
current. Replacement/deletion wins cleanly: the late response is recorded as
`stale_registration`, does not enter `GameRoom`, and cannot overwrite current-generation health.

The network half is fenced too. URL policy resolution produces the public IP address that the
client actually connects to; the request URI, HTTP Host, and TLS SNI retain the validated hostname.
Never replace this with "resolve, inspect, then let the normal client resolve again" — that
reopens DNS rebinding between policy and connect.

## Staged webhook activation is a leased two-phase operation

Setup creation and activation are cross-instance state machines, not process-local critical
sections. Each mutation takes the per-bot PostgreSQL advisory fence and locks the bot row. Lease
acquisition atomically binds an opaque lease id to the setup/revision and increments
`activation_attempts` **before** the external verification request starts. The HTTP call then runs
outside the database transaction; completion reacquires the fence and must still match the bot
incarnation, actor authority, slot revision, setup, candidate, and unexpired lease. A second caller
gets `409 activation_in_progress` while the lease is live.

Reserving the attempt on acquisition is deliberate. If a process dies after sending the request,
the hard lease expiry permits recovery but the attempt remains consumed; a crash loop cannot turn
the five-attempt limit into an unlimited verifier. The lease reservation and its
`webhook.activation.start` audit event commit together, so this crash case is observable even when
no verification result is ever recorded. Setup, lease, budget, terminal and tombstone
deadlines are evaluated against PostgreSQL `clock_timestamp()`. Application clocks express
durations and wire timestamps only, never the cross-replica security boundary.

Admin authority has a database-wide fence of its own. Each enabled instance heartbeats the digest
of its parsed `PLAY_ADMINS` generation every 5 seconds, and PostgreSQL considers it live for 20
seconds. Admin webhook requests proceed only when exactly one live generation matches the caller;
zero live generations or an old/new overlap fail closed as `403 admin_required`. Only the sole
surviving generation may invalidate and scrub pending setups created by an earlier allow-list.
The heartbeat loop is supervised with the server, so losing it cannot silently leave stale admin
authority in service.

## Showcase admission and first-claim concurrency (ADR-005, #44)

The singleton showcase table introduces three strict concurrency invariants:

### 1. First-claim linearizability ("first visitor wins")

When the table is `open`, multiple visitors may submit `POST /showcase/claim` simultaneously:
- Exactly one claim commits atomically. The server generates player credentials (`seatToken`) returned only to the
  winner in a `Cache-Control: no-store` response.
- Concurrent losers and subsequent callers receive a spectator outcome with the active game ID and WebSocket URL.
  Losers **never** receive player credentials.
- Idempotency is keyed by `(actor_id, Idempotency-Key)` for both signed-in users (`user:<uuid>`) and guests (`guestId`).
  Retrying with the same key returns the committed outcome; conflicting key reuse is rejected as `409 Conflict`.
- Next human color strictly alternates (White $\leftrightarrow$ Black) upon successful durable room creation. Failed
  creation does not consume the next color.

### 2. Dedicated capacity reservation and no-borrowing rule

The featured bot declares capacity 3 (`maxConcurrentGames = 3`). Showcase reserves 1 seat (`reservedConcurrentGames = 1`),
leaving at most 2 seats for general admission:
$$\text{occupancy}_{\text{general}} \le 2, \quad \text{occupancy}_{\text{showcase}} \le 1, \quad \text{occupancy}_{\text{total}} \le 3$$

Every admission path is classified:
- `general`: ladder matchmaking (`LadderScheduler`), catalog challenges (`POST /bot/challenge`), lobby bot play
  (`POST /lobby/play-bot`), direct challenges (`POST /bot/challenges`), and bot seeks (`POST /bot/seeks`). None may
  consume the reserved seat.
- `showcase`: `POST /showcase/claim`. Exclusively consumes the reserved seat.

General admission paths **never borrow** the reserved showcase slot, even if the showcase table is idle.
The central `AdmissionGuard` uses an atomic `acquire -> create/register -> commit` protocol, replacing check-then-create
to prevent concurrent overshooting.

### 3. Single-node process-local coordinator

In this release, showcase coordination and `AdmissionGuard` are process-local in-memory structures backed by PostgreSQL
transactions. **Horizontal scaling to multiple nodes is strictly prohibited** until a shared distributed coordinator
lease (e.g. PostgreSQL row lock / advisory lock lease) is designed and implemented.

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
