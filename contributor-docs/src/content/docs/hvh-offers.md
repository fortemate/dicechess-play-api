---
title: HvH offers and rematches
description: Version 1 HTTP, game-start, and spectator-continuation contract for classic human rematches.
---

# HvH offers and rematches

**Contract version: 1 (coordinator/API #129).** Backend implementation is in this repository;
participant and spectator UI integration are separate follow-up tasks. Design gate:
[dicechess-play-api#8](https://github.com/fortemate/dicechess-play-api/issues/8).
Implementation parent: [dicechess-play#104](https://github.com/fortemate/dicechess-play/issues/104).
This document is the public implementation contract. Product rationale is maintained in the
private Fortemate ADR 007. API paths below are relative to the configured API base URL.
The timings and wire shapes below define version 1. Direct challenges remain a proposal.

## Scope and product rules

- Ordinary completed human-versus-human games in the `/play` product and its existing
  `/live/[id]` game route are eligible. Both final seats must belong to distinct human
  principals. Account players and casual guests are supported.
- Exclude showcase, ladder, bots, unclaimed friend seats, duplicate principals, and technical
  aborts. The singleton demonstration board at `/` retains its existing lifecycle and has
  no rematch button or queue.
- Every rematch uses a fresh independent 50/50 colour assignment. Consecutive identical
  colours are valid. Colours are disclosed only after both consent and the successor is committed.
- Copy the root game's immutable conditions: complete time control, actual rated/casual
  setting, and classic mode. Start with a new position, full clocks, and new dice. Copy
  final participants, not the original provisional friend-link creator identity.
- A successor is a new direct game, never a new showcase or ladder game. Keep each result,
  rating event, game ID, history, and archive link separate.
- If inherited settings are no longer admissible, fail without silently changing them.
  Preserve current support for simultaneous ordinary human games; this feature does not
  promise an exclusive human admission slot.
- No currency, stakes, wallets, or doubling in this release. A future extension copies the
  initial `baseStake`, mode, and `maxMultiplier`, resetting the cube to one, centred. A game
  starting at 10 and ending at 80 starts its rematch at 10. The economic contract is
  separately gated by [#108](https://github.com/fortemate/dicechess-play-api/issues/108).

## Consent and deadlines

`sourceGameId` is the idempotency boundary: at most one committed successor may exist for
one completed game, regardless of clicks, browser tabs, reconnects, or server restarts.

| Phase       | Commands and transitions                                                                                      | Absolute deadline                            |
| ----------- | ------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| `available` | First `propose` records that participant's consent and enters `offered`.                                      | Source authoritative `endedAt + 15s`         |
| `offered`   | Other participant's `accept` or `propose` enters `starting`. Initiator may `cancel`; recipient may `decline`. | First proposal's server time `+ 15s`         |
| `starting`  | The server validates admission and commits one successor; no extra user confirmation.                         | Server processing; no renewed consent window |
| `matched`   | Return the same committed successor; no cancellation or new colour draw.                                      | Terminal                                     |
| `closed`    | No successor can be created from this source.                                                                 | Terminal                                     |

Reject a transition when `now >= deadline`, even if a background expiry job has not run.
The first proposal on second 14 grants a full 15-second response window. Repeated requests,
self-proposals, keepalive, polling, reload, and reconnect never extend any deadline.
A duplicate initiator `propose` is a readback, not the second participant's consent.
The first serialized accept/decline/cancel/expiry wins. Once `starting`, offer withdrawal is
not available: acceptance commits both participants to the next game. An abandoned server
creation attempt without a committed successor closes on recovery, rather than resetting consent.

The server returns `serverNow` and absolute deadlines; clients calculate display countdowns
without deciding eligibility. The result UI must show the control promptly on authoritative
completion, independently of delayed replay/move animation. Do not silently consume the
window behind that animation or begin it afresh when an animation finishes.

## Atomic creation and bounded initial join

The two consents are sufficient; there is no extra readiness POST, heartbeat, or user dialog.
No new `GameRoom` or running clock exists while the offer is merely available or offered.
After second consent, validate inherited conditions and admission before allocating one
colour assignment. Atomically persist the successor's initial snapshot, final participants,
new credential material, and predecessor link. Register a readable room behind a persisted
rematch-only `awaiting-joins` gate. At that point return `matched` and the caller's join data.

The server enforces a durable **15-second first-join deadline from commit** for
rematch games. Invoke `Begin` exactly once only after both new seats connect; no seed timeout
or clock may run before that gate opens. A first-join command at or after the deadline is late.
One never-connected participant loses to the participant who joined; if neither ever joined,
record a technical abort. Once activated, use the existing 30-second disconnect grace.
Before activation, a seat that disconnects must reconnect within the original first-join
window; do not start against a known-disconnected player. If exactly one seat is connected at
the deadline, that seat wins; if neither is connected, technically abort. Persist connection
history, activation, and the absolute deadline; do not grant a new window after restart.

Serialize activation, first-join expiry, and ordinary timeout/disconnect handling so there
is exactly one result and rating application. The existing non-rematch creation lifecycle
is unchanged. For MVP, a server restart recovering a committed but not-yet-activated rematch records a
technical abort for that same game, regardless of remaining join time: no sporting result,
rating, player forfeit, or reopened source. An already activated game follows existing game
recovery rules. A registration failure without a restart may retry only until the original
join deadline, then technically abort; it must not forfeit a player for server delay.
Persisted connection history alone is not proof of a currently connected seat. Never activate
clocks before both are connected.

A lost response or client disconnect after commit returns the same game and colours on
retry. No technical abort reopens the predecessor for another draw. Before future stake
support, #108 must define reservations before colour disclosure, no-show outcomes, refunds,
and settlement. A post-disclosure client disconnect must not create a free colour reroll.

## State ownership, transactions, and recovery

A rematch coordinator outlives `GameRoom`. Existing ended rooms are removed from the registry,
and the frontend closes its game WebSocket; neither is a reliable offer transport.

Persist enough information to reconstruct:

- Source and root IDs, immutable root conditions, final eligible principals and the source
  end time; the minimum source credential verifier needed after room eviction.
- Phase, consent identities, absolute deadlines, terminal reason, and a version for serialized
  updates. Activation and live socket presence are separate from persisted consent.
- A unique non-null predecessor-to-successor link and the successor's initial snapshot,
  colour assignment, participants, recoverable private credential material, and initial-join
  deadline. Reuse the existing secure persistence conventions; no public secret fields.

Use a database uniqueness constraint and a single transaction for the initial snapshot plus
successor relation. An in-memory lock, an accepted cache, or saving the link after ordinary
`createRoom` returns does not satisfy the crash boundary. Registration and activation must
be idempotent and recoverable from that transaction. Keep the committed mapping authoritative
while registration is pending; respond `starting`/retryable until the same room is readable,
and never reopen consent or allocate another successor.

Expose `matched` and `nextGameId` only when the committed game is readable as live state or
immutable history. Recovery must complete registration or preserve a terminal game record,
so an accepted link never leads to a permanent 404. Keep successor links for at least as
long as the source history is retained; do not expire them with the short offer window.

For MVP, restart closes unfinished `offered`/`starting` sessions which have no committed
successor. This closure is durable; a reboot must not reopen or extend the source window.
A committed successor always wins recovery over stale transient phase data. An untouched
`available` opportunity retains its original end-based deadline. Never roll back a committed
successor because publication or the requesting connection failed.

## Authorization and privacy

- Account actions require the current account session matching the **final** source-seat
  principal. Apply existing origin/CSRF rules to all mutations. A guest ID or nickname alone
  is never authorization.
- Casual guest actions require the source seat capability, supplied as
  `X-Rematch-Seat-Token` over HTTPS, and resolved to that source's final guest seat. The same
  holder can only consent for that seat. Do not let a guest-token fallback override an
  account-owned seat. Persist a verifier so authentication still works after room eviction.
- Friend-link creators currently receive both seat capabilities. Guest rematch retains
  that bearer-capability trust model; it cannot prove two independent people. Account/rated
  paths use final account ownership and do not rely on that assumption.
- The private accepted response contains only the caller's new seat credential and colour;
  never return the existing two-token `CreatedGame` envelope to a rematch participant.
  Recovery must reissue or recover access to the same seat without generating a new game.
- Public reads reveal only `waiting`, `matched`, or `closed`, the public deadline, and the
  successor ID when available. No initiating/declining identity, private closure reason,
  connection details, seat capabilities, or future balance information.
- Spectator connections explicitly select read-only mode, taking precedence over account
  cookie restoration and any seat token. They must not claim a seat or send player commands.
  Implement `GET /games/{id}/ws?mode=spectator` with that contract; existing unspecified mode
  keeps its current semantics. A tokenless connection alone is not a spectator guarantee.
- Private responses use `Cache-Control: no-store`. Credentials never go into public links,
  analytics, errors, or server logs. Rate-limit reads and mutations with bounded configured
  limits; return `429` with `Retry-After`, without altering existing deadlines.

## HTTP contract v1

[Machine-readable v1 fixtures](https://github.com/fortemate/dicechess-play-api/blob/main/contracts/rematch-v1.json)
are checked against the production encoders by `RematchWireSuite`. Use them for participant
and spectator frontend fixtures. The example capabilities are synthetic.

The UI polls independently of the finished game socket. Start with one-second status polling
while waiting; pause background polling and immediately read back on focus/reconnect.
Stop offer polling at `matched` and connect to the successor, which may still be waiting
for the other player. Stop polling `closed`; chain-follow readback may resume on reload or
explicit user action.

| Method and path                     | Audience                      | Result                                                                                                                |
| ----------------------------------- | ----------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `GET /games/{id}/rematch`           | Authorized source participant | Private state, inherited settings, own consent, deadlines, allowed actions; own successor join data only when matched |
| `POST /games/{id}/rematch`          | Authorized source participant | Apply an action and return canonical private state                                                                    |
| `GET /games/{id}/continuation`      | Public                        | Public waiting/matched/closed projection, independent of live room retention                                          |
| `GET /games/{id}/ws?mode=spectator` | Public                        | Explicit read-only game subscription; no seat restoration or claim                                                    |

The routes require PostgreSQL; an in-memory deployment returns `503 temporarily_unavailable`.
Use `Content-Type: application/json`, an exact allowed `Origin`, and `X-DiceChess-CSRF: 1`
for every POST (including guest requests). `PLAY_CORS_ORIGINS` must explicitly name the
browser origin. Account callers send their session cookie; guests send only their source
capability in `X-Rematch-Seat-Token`. A verified account session takes precedence over that
header. The added CORS request header does not expand the configured origin allowlist. The
CSRF/origin check runs before seat lookup and deliberately uses `403 not_participant` for a
missing or untrusted origin, preserving the current wire contract without revealing whether
the caller is a participant. With PostgreSQL enabled but no explicit origin allowlist, reads
may still work while all POST mutations are refused; the server warns about this at startup.
No seat, account UUID, guest UUID, or token is accepted from the JSON body.

Bodies are limited to 1 KiB and must contain exactly the two fields below. IDs use canonical
UUID syntax. All rematch/continuation responses, including errors, have `Cache-Control: no-store`.
Default fixed-window limits are 120 reads and 30 mutations per minute per IP, plus the same
limits per authenticated participant identity. The limiter configuration is bounded at
construction (`RematchLimiter.Config`) and tracks at most 10,000 keys; exhaustion is also
`429 rate_limited` with `Retry-After: 60`. These defaults are process-local and do not change
the authoritative opportunity deadlines.

Every POST has a client-generated UUID `requestId` and one of `propose`, `accept`, `decline`,
or `cancel`. Retrying the same ID/payload is safe and returns current canonical state;
reuse with a different action is `409 request_id_conflict`. Logical repeats with new IDs are
also safe at the source/participant level. Request IDs are never authorization.

```json
{
  "requestId": "2441a570-90f4-4205-a956-6760dc64a6d3",
  "action": "propose"
}
```

Example private pending response (times are UTC ISO-8601; absent fields are omitted):

```json
{
  "sourceGameId": "game-a",
  "phase": "offered",
  "serverNow": "2026-09-07T12:00:14Z",
  "deadlineAt": "2026-09-07T12:00:29Z",
  "myConsent": true,
  "allowedActions": ["cancel"],
  "settings": {
    "timeControl": {
      "Fischer": { "initialSeconds": 300, "incrementSeconds": 3 }
    },
    "rated": false,
    "mode": "classic"
  }
}
```

The `timeControl` object uses the existing `wire/Codecs` tagged-union shape, here five
minutes plus three seconds. Preserve all supported variants (`Unlimited`, `SuddenDeath`,
`Fischer`, `PerMove`) without a lossy conversion. Future currency fields are absent, not
fake zero balances.

Example private matched response for the caller who received black:

```json
{
  "sourceGameId": "game-a",
  "phase": "matched",
  "serverNow": "2026-09-07T12:00:20Z",
  "myConsent": true,
  "allowedActions": [],
  "nextGameId": "game-b",
  "joinDeadlineAt": "2026-09-07T12:00:35Z",
  "join": { "seat": "Black", "token": "<caller-only-opaque-token>" }
}
```

The same state is public only as:

```json
{
  "sourceGameId": "game-a",
  "phase": "matched",
  "serverNow": "2026-09-07T12:00:20Z",
  "nextGameId": "game-b"
}
```

For `waiting`, public `deadlineAt` is the current opportunity/response deadline;
when registration is recovering after commit, omit it and continue bounded retry. A public
`closed` response has no private reason. Permanently ineligible sources return `closed`;
nonexistent source IDs return `404`. An ordinary active HvH game returns `waiting` without a
deadline; live followers wait for game completion and then resume continuation polling,
including after reload. Do not mistake an ongoing game for a terminally closed chain. Private states `available` and `offered` include the applicable deadline. `starting` has
no allowed actions or disclosed join data and does not run offer expiry. `matched` includes
`joinDeadlineAt`; the successor game state also exposes its `awaiting-joins` or active phase
and deadline, without leaking private connection identities to spectators.

Mutations return `200` with canonical state for valid transitions and safe duplicates.
Use `400 invalid_request`, `401 authentication_required`, `403 not_participant`,
`404 game_not_found`, `409 invalid_transition` / `request_id_conflict` / `settings_unavailable`,
`410 rematch_closed`, and `503 temporarily_unavailable` consistently. Return a safe stable
`error.code`; any state attached to an error must have the caller's authorized projection.
A `503` never implies that a commit did not happen: read back and retry the same source.
A terminal replay of an earlier valid command returns its current terminal state while its request record is retained; a new attempt to reopen a closed
opportunity receives `410`. Request-ID recognition is retained for 24 hours from the first attempt, scoped to the
source game and final seat. Replays do not extend retention. Old receipts are cleaned up
on the next command for that source; inactive sources can retain them longer. Regardless of request-ID expiry, persist terminal source state and
the unique successor mapping for the source retention period. An expired request record
cannot authorize another game or colour draw.

### Successor snapshots

Both `GET /games/{id}` and the `state` of a WebSocket `Snapshot` include the optional
`rematchStartup` object for successor games. Ordinary games omit it. Its exact pending shape is:

```json
{
  "rematchStartup": {
    "phase": "awaiting_joins",
    "joinDeadlineAt": "2026-09-07T12:00:35Z"
  }
}
```

After activation it is `{ "phase": "active" }`; a successor whose startup ends without
activation exposes `{ "phase": "aborted" }` together with the normal terminal game status.
No connection counts, joined identities, or capabilities appear here. Clients keep the board
in a waiting state until activation. The initial join deadline is not a chess clock.

Private responses always include inherited `settings`; `closed` also carries `closedReason`
(`declined`, `cancelled`, `expired`, `technical_failure`, or `restart`). Public `closed` omits
that reason. A successful POST can first return `starting`; poll the same source until
`matched` or `closed`. Accepted work is supervised independently of request cancellation,
and a bounded database scan picks up accepted commands whose response was lost. This is
server recovery, not a notification bus. `matched` can point to either live state or the
existing `/games/{nextGameId}/history` route if the successor has already ended.

A failed command returns `{ "error": { "code": "invalid_transition" }, "state": ... }`
when an authorized canonical state is available. Failed authorization never attaches state.
Replaying an earlier rejected command keeps its error and returns current authorized state;
replaying an earlier successful command returns `200` with current state, including `closed`.
Clients should not infer a rollback from `503`: retry the same request ID or read the source.

## Spectator continuation

Follow intent belongs to the viewer, not the historical game. A live spectator initially
follows the chain. At game end show the result and rematch wait state; on public `matched`,
open the successor in explicit spectator mode and update colours, names, clocks, and board.
Continue through A → B → C. An unrelated new game by the same people is not a successor.

“Stay on this game” disables following and retains move/history access. Preserve the choice
across reload. Opening an archive/replay URL starts in exact-game mode, never auto-redirects;
provide an explicit action to follow the current continuation. Store follow intent and chain
position without tokens. Reconnect walks retained links to the latest game, using bounded
batches, a visited-ID/cycle guard, and backoff for transient failures. A broken chain shows
an actionable retry while keeping the last readable result; it must not silently move to an
unrelated board. Manual navigation out of the game cancels its follow loop.

## Direct challenges: next stage

Keep [dicechess-play#10](https://github.com/fortemate/dicechess-play/issues/10) as the existing
profile challenge work. It is outside the classic rematch release, but reuses these consent,
authorization, bounded start, and atomic creation boundaries rather than representing an offer
as an already-running game or an open lobby seek.

Proposed first direct-challenge contract: account-to-account, stable target account ID,
server-validated immutable settings shown before acceptance, random colours, five-minute TTL,
private inbox polling in the open site's shared shell (including profile/analysis pages).
The recipient need not be online when the challenge is created; returning within the TTL
makes it visible. No automatic acceptance, external notifications, or guest nickname targeting.
Initiator creation is first consent, recipient acceptance is second; then use the same
atomic creation and initial-join gate. Decline/cancel/expiry and rate limits follow the same non-extending rules.
Endpoint/schema details and the existing #10 colour-choice proposal must be reconciled in
that stage's contract before coding. No direct-challenge endpoints are shipped by this MVP.

## Acceptance and rollout

Freeze examples and codecs before parallel frontend work. Backend leaves include real
PostgreSQL concurrency/recovery tests and a controllable clock/RNG. Test both colour branches
and one persisted assignment; avoid a flaky statistical test on a small random sample.

Required scenarios: proposal at second 14; equality at every deadline; duplicate and crossed
clicks; accept/decline/cancel races; failed persistence; crash before/after commit
and before/after room registration; lost accepted response; first-join and server-outage
outcomes; exactly one successor/result/rating; post-eviction authorization; account cookies
in explicit spectator mode; and no secret leakage between participants or to public reads.

Frontend acceptance uses two players and one spectator against an isolated real backend,
including two successive rematches, reload/network recovery, staying on a game, immutable
archive navigation, and the unchanged `/` demo. Fixture-driven UI work is useful early but
is not end-to-end acceptance. Existing preview-origin restrictions must not be bypassed by
expanding production CORS for testing. Prepare a local or isolated review environment.

Deliver small dependent PRs: [storage #127](https://github.com/fortemate/dicechess-play-api/issues/127)
→ [admission/start #128](https://github.com/fortemate/dicechess-play-api/issues/128)
→ [coordinator/API #129](https://github.com/fortemate/dicechess-play-api/issues/129),
then [participant UI #105](https://github.com/fortemate/dicechess-play/issues/105) and
[spectator UI #106](https://github.com/fortemate/dicechess-play/issues/106) in parallel. The feature parent remains open until the
integrated browser criteria pass. Merge and production release remain separate owner actions.
