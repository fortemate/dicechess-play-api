---
title: Laboratory rooms and recovery
description: Opt-in authoritative two-pair rooms, atomic commands, clocks, restart recovery and isolated invitation/rematch adapters.
---

`LaboratoryRegistry` and `LaboratoryRoutes` implement
[issue 292](https://github.com/fortemate/dicechess-play-api/issues/292), consuming the
[revision-1 contract](../laboratory-contract/), dedicated `LaboratoryStore` and
[versioned deterministic offers](../laboratory-dice/). They are not installed by the classic
`Main` route assembly. Dedicated runtime/database/origin configuration belongs to
[issue 293](https://github.com/fortemate/dicechess-play-api/issues/293); this change is not a public launch.

## Creation and invitation

The laboratory adapter serves the familiar `POST /games`, per-game snapshot, `/moves` and
seat-token WebSocket paths. Creation requires explicit `variant: {"id":"choice-2x2d6","revision":1}`
and `labProtocolVersion: 1`. Both participants must be humans; `rated: true` and invalid time
controls are refused. Unknown or null mode fields do not fall back to classic. The classic registry
refuses explicit laboratory selectors. Ordinary classic requests and responses omit the new fields.

The creation response adds `variant`, `labProtocolVersion` and `provenance` to `gameId`, `commit`
and the creator's per-seat tokens. Verify all echoed values before distributing the opponent link.
The first-message handshake below authenticates each WebSocket seat; command bodies do not name a seat.
Laboratory rooms are absent from the ordinary `GET /games` listing. This registry has no rating,
training, showcase, analytics delivery or ordinary registry-hook integration.

Both authenticated seat connections must be live before seed grace can finish. Seeds alone and
`Begin` cannot start the game. A disconnect during readiness closes that readiness window until
both reconnect, within the durable join deadline. Expiry or cancellation while waiting produces
a retained technical abort, with no clock charge and no sporting win for the connected player.

## WebSocket authentication

Connect to `wss://<laboratory-api>/games/{id}/ws` with no query parameters or fragment. The first
complete text message must be `{"LabAuthenticate":{"token":"<seat-capability>","guest":"<guest-uuid>"}}`.
The server validates the capability for that room and replies privately with
`{"LabAuthenticated":{"seat":"White"}}` (or `Black`) before sending any room state.
The client verifies the expected seat, then sends its stable `SubmitSeed` contribution and queued commands.
Every reconnect repeats authentication; a successful HTTP upgrade alone does not establish seat ownership.

Until authentication succeeds there is no room subscription, presence or command processing.
The first authentication message is limited to 1024 UTF-8 bytes and must arrive within five seconds;
malformed, fragmented, binary, invalid-guest and invalid-token attempts close with WebSocket code `1008`.
Ping/pong control frames cannot extend this deadline. Failure messages never echo a credential.
Changing the seat requires a new authenticated connection; a later authentication message cannot rebind it.

Seat socket query parameters, including the former `token`/`guest` parameters, are rejected with HTTP 400.
The explicit `?mode=spectator` path remains public and has no seat permissions. Classic deployment
authentication is unchanged. Deploy this laboratory adapter before enabling the updated laboratory client;
the client has no URL-token fallback and waits for acknowledgement when talking to an older adapter.
Invitation links retain capabilities in their page fragments. Production transport requires HTTPS/WSS;
application and proxy diagnostics must not record WebSocket message bodies containing authentication.

## Atomic commands and public state

`LaboratoryTurns` computes a whole immutable effect group inside the existing room's single
writer. `RollFirst`, `RollSecond`, `ChoosePair` and selected-pair `SubmitTurn` use the distinct
`LabCommand` envelope. The room refuses a classic `SubmitTurn` as a version-guard bypass.

The room authenticates the seat, looks up its durable retry receipt, and then checks the expected
version, readiness, active side, draw gate, phase and payload. An identical retry returns its
original applied version plus a fresh snapshot. Changed fields under the same id conflict.
Refusals are private and do not change the version, offers, clock origin or accepted receipts.
Laboratory WebSockets keep a bounded two-second drain window after the terminal event or terminal
greeting, so a completing command's private receipt is not cut off by the public ending. A client
with an uncertain command can reconnect and resend its original fingerprint during that window;
the stored receipt remains valid after game end. Classic sockets retain their immediate close.

Both generated pairs remain private recovery data. Public `LabTurnChanged` events reveal only
the permitted prefix, and legal moves exist only for the selected pair. Selection preserves its
index even when the pairs are equal. Only the selected tree determines maximum dice consumption.

Choosing an unplayable pair immediately records an explicit pass, even if the other pair is
playable. Choice, pass, next-turn setup and the original command receipt commit together. A
subscriber cannot observe any part of that effect group before the store has committed it.
Completed `Snapshot.history` entries contain both alternatives and `labChoice.selectedPairIndex`.
The existing rules dependency owns move legality, castling, promotion and king capture.

New lab fields are omitted from classic JSON. Unsupported laboratory protocol, provenance,
stages or malformed pairs fail closed. The TypeScript contract asset remains the adoption
reference for client issues 223/224; theme preferences are browser presentation only.

## Clocks, failure and restart

The initial clock starts at `AwaitingFirstRoll`. Rolls and selection do not restart it or grant
animation grace within the active decision. Fischer increment is credited once after the completed
macro-turn, including a confirmed pass. Server-controlled playback grace applies between turns.
Pending draw offers block either roll/choice until answered, without revealing dice early.

Unlimited games expose no clocks, but accepted stage progress refreshes their configured inactivity
deadline. Duplicate/invalid requests, draw toggles and reconnect traffic cannot refresh it. Clock
expiry during any active stage ends the game without choosing on the player's behalf. Existing
disconnect-grace behavior remains active after startup.

Every laboratory room uses required durability. Failed writes halt publication and acknowledgements;
the existing retry policy credits the server's persistence stall. A retry uses one fixed snapshot
image, including its remaining-time readings, so an ambiguous commit cannot become a different
same-version write. If the store confirms a previously uncertain write, the next CAS fence advances
to that confirmed version. An abandoned intermediate write aborts from the last durable state.

Recovery validates the captured rules/dice provenance and re-derives active generated offers from
the persisted seed inputs. It restores all reveal stages, selection, history, receipts and tokens.
Only the remaining budget from the durable image is resumed on a new monotonic origin; process
downtime is not charged. Waiting rooms require both live connections again, regardless of stored
join history. The server seed stays private until a permitted terminal reveal. An abort before
inputs freeze remains incomplete and has no public fairness evidence.

## Review, export and rematch

`GET /games/{id}/transcript` returns the validated schema-1 public transcript from the separate
store, including incomplete games, under its fixed retention deadline. It never returns seat
credentials, private receipts, unrevealed active offers or the active server seed. At game end,
the public current stage can retain the just-completed choice as well as its completed history
entry; a verifier should recognize that terminal projection as the same turn, not a new roll.

The lab's bounded rematch adapter uses `POST /games/{id}/rematch` with the caller's source-seat
token in `X-Laboratory-Seat-Token` and `{"consent": true}`. `{"consent": false}` reads the caller's
current continuation without adding consent. Tokens are not accepted in query parameters. Both
distinct final human seats must consent after a sporting result; an active game or technical abort
is ineligible. A successor swaps colors explicitly and inherits variant, protocol, provenance and
time control, with a fresh seed, tokens, readiness gate and empty receipts/history.

The private response has `phase: "awaiting_consent"` without successor credentials, or
`phase: "created"` with `gameId`, the caller's new `seat` and only that seat's `token`. Polling/retrying
returns the same durable successor. Preliminary consent is deliberately process-local: after a
restart both players must consent again if no successor was committed. Once created, the single
successor and its private credentials survive a restart. This is a separate laboratory adapter;
it does not alter the classic rematch API.

The registry requires an explicit laboratory store and has no implicit main database fallback.
Runtime wiring, CORS allow-list, deployment/retirement controls and final two-browser acceptance
remain separate tasks. No DNS changes or deployment are performed by this implementation.
