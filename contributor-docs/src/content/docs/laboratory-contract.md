---
title: Two-pair laboratory contract
description: Revision-1 identity, two-roll protocol, readiness, retry, persistence and rules-consumer fixtures for the isolated human laboratory.
---

This is the normative preparatory contract for
[play-api issue 289](https://github.com/fortemate/dicechess-play-api/issues/289), under
[the laboratory Epic](https://github.com/fortemate/dicechess-play/issues/221).
`LaboratoryProtocol` and `LaboratoryCodecs` pin its values and JSON examples. They do **not**
enable a route, start laboratory rooms, change classic frames, implement the RNG or migrate a database.
Room, storage and dice implementation remain independently owned by
[292](https://github.com/fortemate/dicechess-play-api/issues/292),
[290](https://github.com/fortemate/dicechess-play-api/issues/290) and
[291](https://github.com/fortemate/dicechess-play-api/issues/291).

## Identity and compatibility

| Field | Revision-1 contract |
| --- | --- |
| Variant | `{"id":"choice-2x2d6","revision":1}` |
| Protocol version | `1`, negotiated explicitly at creation |
| Export schema version | `1`, independent of the variant revision |
| Dice algorithm | `choice-2x2d6-hmac-sha256-v1`; derivation and vectors belong to issue 291 |
| Rules provenance | Resolved `RulesVersion.value`, captured at creation and retained |
| Historical classic identity | `{"id":"classic-3d6","revision":1}` at the legacy-record boundary |

The current qualification uses the repository-pinned rules dependency in `build.sbt`; never
qualify against an unrelated engine checkout. A later dependency upgrade must rerun the fixtures.
`origin` describes where a game came from. `variant` describes its rules. The current server's
`rulesVersion` does not replace the captured provenance of an older game. A restore that cannot
honor the stored rules/dice implementation must fail visibly; it must not reinterpret the game.

Only a **known legacy classic record** lacking `variant` defaults to classic, through
`variantOfLegacyClassicRecord`. An explicitly null, unknown or unsupported identity fails.
New laboratory creation and state require explicit revision-1 identity. The main deployment
continues accepting legacy classic creation with no variant; the lab deployment requires an
explicit supported lab variant. Neither deployment silently falls through to the other mode.

The future additive `POST /games` request retains existing anonymous UUID fields `white` and
`black` and the structured `timeControl`, adding `variant` and `labProtocolVersion: 1`.
Both seats initially belong to the creator until the opponent redeems their distinct seat token,
as in the current friend-by-link flow. The response echoes the agreed variant, protocol and
captured provenance alongside the existing game id, commitment and per-seat tokens. Color
preference remains a seat-allocation concern. Signed-in human sessions may use existing identity
resolution; bot admission and all rated requests are refused by the lab server.

The client must verify that echoed variant, protocol and provenance before opening a board or
issuing an invitation. An absent/mismatched echo is an incompatible server, not a classic fallback.
This matters during staged rollout: the current classic creation decoder can ignore unknown JSON
fields, so sending a new selector to an old server is not proof that it created a lab game.

Classic creation and `GameCommand`/`GameEvent` codecs remain unchanged in this preparatory change.
Laboratory turn commands use a distinct `LabCommand` envelope. Classic endpoints reject that
envelope; a future lab room must refuse classic `SubmitTurn` as a bypass of the version guard.
Existing seat-authenticated `SubmitSeed`, `Resign`, `ArmDrawOffer` and `RespondDraw` lifecycle
commands retain their room authorization and termination/draw policies. They do not roll dice
or bypass readiness. A seed is accepted once before startup freezes seed inputs; reseeding after
that point is refused/ignored consistently with the existing seed policy.

The [approved design decision](https://github.com/fortemate/dicechess-play/issues/222#accepted-laboratory-appearance-direction-2026-11-11),
carried into [the client task](https://github.com/fortemate/dicechess-play/issues/223), specifies
Light / Dark / System browser presentation. At this contract boundary, theme preference is absent
from variant identity, commands, stored rules and exports.
This laboratory does not require the broader
[engine variant architecture](https://github.com/fortemate/dicechess-engine/issues/420).

## Values and stages

A piece die is an integer: pawn `1`, knight `2`, bishop `3`, rook `4`, queen `5`, king `6`.
An offer contains exactly two such values. Preserve their generated order in the transcript;
the chosen engine pool is a multiset. Pair indices are **zero-based**: `0` is the first roll,
`1` the second. The UI labels them pair 1/pair 2. Equal offers retain distinct indices.

| Public phase | Revealed `offers` | `selectedPairIndex` | Next accepted turn action |
| --- | --- | --- | --- |
| `AwaitingPlayers` | `[]` | `null` | None until startup gate opens |
| `AwaitingFirstRoll` | `[]` | `null` | `RollFirst` |
| `AwaitingSecondRoll` | `[first]` | `null` | `RollSecond` |
| `AwaitingPairChoice` | `[first, second]` | `null` | `ChoosePair` |
| `PlayingSelectedPair` | `[first, second]` | `0` or `1` | `SubmitTurn` |
| `Ended` | The 0–2 offers actually revealed | Preserved choice, if made | None |

The next-action column assumes no pending standing draw offer. The existing snapshot's draw
context remains an orthogonal pre-roll gate: while a response is pending, roll/choice/play
requests are refused, and responding does not roll dice automatically.

In `Ended`, a non-null selection still requires two offers. The public type/codec exposes only
revealed offers; it has no private full-offer or seed field. Invalid stage/cardinality combinations
are refused on decode and fail closed on encode rather than publishing a second offer early.

Integration code uses `LaboratoryCodecs.encodeValidated(value)`, returning
`Either[EncodingFailure, Json]`. It validates the complete nested value before invoking any encoder,
so an invalid standalone pair, nested command or public stage produces a typed failure without
using exceptions as control flow. The conventional Circe `Encoder` retains a fail-closed invariant
guard for accidental direct `.asJson` calls with invalid values; it must never substitute partial
or empty JSON for a rejected value.

At each turn, both offers are fixed by committed seed inputs and stable turn/offer identities,
independent of the position, elapsed time, commands and reconnects. Issue 291 specifies the
versioned derivation while preserving every classic three-dice verification vector. Persist
the complete private generation context before revealing either offer. Do not sort or regenerate
the transcript on recovery. Pair 2 is public only after an accepted `RollSecond`.

`ChoosePair` is the explicit confirmation; highlighting a card is local UI state and sends no
command. Choice is irreversible from the server's acceptance, even before any micro-move.
Inject only the selected two dice into the rules engine. Before selection, DFEN has no dice
extension and no selected-pair legal tree exists. Never construct a four-dice engine pool.

Maximum consumption is computed **inside the selected pair**, retaining the king-capture
exception. Do not compare the alternatives' maximum path lengths. Either offer remains selectable
even if it has no legal moves. An empty selected legal tree records an automatic empty-move pass
after confirmation; never pass or choose automatically after either roll. Both-unplayable offers
still require a choice. Pass effects, next-turn setup and their command receipt are persisted
with an exactly-once boundary; no empty `SubmitTurn` is needed or accepted as a second pass.

For a playable pair, submit the complete legal path, containing one or two UCI micro-moves.
A castle is one micro-move spending king and rook dice. A king capture can end a path early.
The room validates the complete path against the existing engine tree; syntactically valid UCI
is not a legality verdict. Do not introduce new chess rules in this contract.

## Startup, clocks and recovery

For a fresh invitation or rematch, use an initial-join gate for **both** laboratory seats.
Both must be claimed/authenticated and connected before the seed-readiness window can complete.
Connection possession comes from the existing seat-token handshake, never a claimed seat in
the command body. Submitting a seed alone is not presence. A missing second participant leaves
the game waiting with no dice revealed and no game-clock charge; expiry of the configured join
gate is a technical abort, retained as an incomplete lab transcript, not a sporting win.

After both connections are ready, accept both post-commit seeds or finish the existing seed grace
with its existing per-seat fallback. If a seat disconnects before the gate opens, keep waiting
within the join deadline. Freeze the seed inputs when the gate opens and persist
`AwaitingFirstRoll` before publishing it. This opens the first turn clock; it does not roll dice.
The room implementation must use this gate for ordinary lab invitations as well as rematches;
classic `Begin` alone is insufficient because it can force-start after seed grace without a
friend having joined.

One turn clock covers both requested rolls, roll animation, deliberation, confirmation and the
complete micro-move path. A roll, choice, rejected request or retry never resets a timed budget.
Fischer increment is applied exactly once after a completed macro-turn, including a deliberately
selected no-move pass. Sudden-death and per-move budgets retain their existing meanings; per-move
is per **macro-turn**, not per roll or micro-move. Apply existing server-controlled between-turn
presentation handling consistently, with no animation-created pause inside the active decision.

`Unlimited` has no clocks (`clocks: null`), but retains the configured `RoomTuning.idleCheck`
anti-abandonment inactivity cap. New accepted stage progress counts as activity. Invalid or
duplicate requests and reconnect traffic must not refresh that cap. Neither control waits
forever for a pair selection. After startup, disconnections use the existing disconnect-grace
policy; they do not pause a running game clock. Expiry during either roll or choice ends the
game on time without selecting an offer on the player's behalf.

Persist each accepted transition **before** public fan-out and acknowledgement. Restore the exact
variant, provenance, stage, offers, reveal state, selection, clocks and receipts. The current
classic recovery policy restores the last durable remaining time onto a new monotonic origin;
process downtime is not charged. Keep that documented player-favoring policy for laboratory
recovery rather than treating a pre-crash monotonic timestamp as meaningful. Reuse the room's
durability-stall clock handling. A refresh/reconnect simply receives the current snapshot;
it is not a clock reset, seed change, new roll or new choice.

## Commands, versions and retries

Versions are nonnegative exact JavaScript integers, at most `9007199254740991`. All JSON
discriminators and ordinary fields retain the existing PascalCase/camelCase wire conventions.
`commandId` is 1–64 ASCII letters, digits, `_` or `-`; it is a retry key, not an authorization secret.
Scope receipts by game **and authenticated seat**, and retain them for the game's retained
lifetime so a retry after restart is safe.

```json
{"LabCommand":{"commandId":"turn-1-first","expectedVersion":1,"action":{"RollFirst":{}}}}
```

```json
{"LabCommand":{"commandId":"turn-1-second","expectedVersion":2,"action":{"RollSecond":{}}}}
```

```json
{"LabCommand":{"commandId":"turn-1-choice","expectedVersion":3,"action":{"ChoosePair":{"pairIndex":1}}}}
```

```json
{"LabCommand":{"commandId":"turn-1-play","expectedVersion":4,"action":{"SubmitTurn":{"moves":["a2a4","a1a3"],"offerDraw":false}}}}
```

The draw flag is optional and decodes to false, exactly as in the classic turn contract. The
selected move tree must be nonempty before accepting a play submission. Existing standing draw
offers are delivered at the appropriate turn boundary before any dice revelation; declining
opens `AwaitingFirstRoll` without rolling it. Roll/choice requests cannot answer or bypass a
pending draw offer. Resignation and game-ending timeouts remain valid at every active stage.

The single writer first authorizes the seat, then checks for a receipt, then checks current
version, stage, active side and payload legality. Persist the parsed semantic request fingerprint
(including the original expected version), effects and receipt atomically. An identical retry
returns the original receipt plus a fresh snapshot without rerolling, debiting twice, switching
selection, advancing version again or replaying old events to other subscribers. The same id
with different semantic fields is `CommandConflict`, even if the expected version was updated.
Unknown receipts must meet the current expected version; stale commands receive resync guidance.

```json
{"LabCommandApplied":{"commandId":"turn-1-choice","appliedVersion":4}}
```

For a choice causing a pass and next-turn setup, `appliedVersion` is the final version of that
committed effect group. Refusals are private, leave state/version unchanged and create no accepted
receipt. Return a structured code, bounded human-readable reason and `resync` flag:

```json
{"LabRejected":{"v":4,"commandId":"turn-1-choice","code":"StaleVersion","reason":"fetch the current snapshot","resync":true}}
```

Codes: `InvalidCommand`, `InvalidPairIndex`, `NotReady`, `WrongSeat`, `WrongStage`, `StaleVersion`,
`CommandConflict`, `GameEnded`. A malformed/unrecoverable command id is returned as null. Map a
pair index outside 0/1 to `InvalidPairIndex`; malformed frames/moves map to `InvalidCommand`.
Do not echo private seeds, other-seat receipts or arbitrary submitted bodies in refusal text.

## Events, snapshots, legal moves and TypeScript

The future `PublicGameState` gains an optional `lab: PublicState` extension; missing `lab` means
classic only for that legacy state shape. Snapshot outer version and state version stay consistent.
`lab` is mandatory for a known laboratory game. New committed lab stages use `LabTurnChanged`;
this carries complete lab context, DFEN and clock readings, not just a pair index patch.
Every lab projection includes `protocolVersion: 1`, so an invited or rejoining client can check
compatibility without having received the creation response. Unknown explicit protocol versions
fail rather than decoding into revision-1 defaults.

```json
{
  "LabTurnChanged": {
    "v": 2,
    "seat": "White",
    "lab": {
      "variant": {"id": "choice-2x2d6", "revision": 1},
      "protocolVersion": 1,
      "provenance": {"rulesVersion": "0.17.1", "diceAlgorithm": "choice-2x2d6-hmac-sha256-v1"},
      "turn": {"phase": "AwaitingSecondRoll", "offers": [[1,3]], "selectedPairIndex": null}
    },
    "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "clocks": {"white": 600000, "black": 600000}
  }
}
```

These clock values and the captured rules version are fixtures, not deployment settings. The
event contains no second offer while awaiting the second request. After selection, `dicePending`
becomes true and `GET /games/{id}/moves` returns the selected-pair tree tied to the same version
and DFEN. Before selection it is unavailable/empty with `dicePending: false`; it must never
calculate the union of both offers or turn a pre-choice empty tree into a pass. On an empty
selected tree the server commits the choice/pass and advances, retaining the choice in history.
Use existing `TurnPlayed`, terminal events and commitment reveal for completed turns/game end,
followed by the new current-stage projection. Do not expose `DiceRolled` for the first offer as
if it were already a selected, playable classic pool.

Each future `SnapshotTurn` gets an optional `labChoice` extension:
`{"offers":[[1,3],[1,4]],"selectedPairIndex":1}`. Its existing `dice` is the chosen pair; an empty
`moves` list denotes a confirmed pass. Classic rows omit `labChoice`. Laboratory rows require it
and preserve both alternatives. Resync/replay rebuilds from the whole snapshot and completed
history; a client does not reapply an acknowledged choice as a new action.

[The TypeScript declarations](/dicechess-play-api/contracts/laboratory-v1.d.ts) pin the corresponding new shapes.
They are a preparatory contract asset, not a replacement for the existing client wire module.
Issues 223/224 adopt these additive declarations and the snapshot/history fields together with
the room implementation, keeping existing classic types/frames intact. Review literal JSON,
enum spelling, nullability, tuple lengths and zero-based indices against the Scala codec suite;
do not independently rename/normalize the new wire.

## Private persistence, exports and isolation

Issue 290 maps variant, protocol version, provenance and public stage into the private `GameSnapshot`, retaining
complete generated offers, reveal count, selected index, fixed seed inputs, current clocks and
receipts. The private full-offer field is not the public `offers` projection. Apply compatible
optional-field decoding to old snapshots; do not assume Circe applies case-class defaults to
missing non-Option fields. Project variant id/revision into result/archive columns as needed
for querying without decoding every JSON payload. The snapshot, result, archive and command
receipt must describe the same immutable variant; rematches inherit it and the time control.

Exports contain `schemaVersion: 1`, variant, captured provenance, settings, result/termination,
complete revealed offer/choice/move history and the final/incomplete turn stage. Export **both**
alternatives even when equal or unplayable, and retain incomplete/technically aborted transcripts
in the lab database under its retention/export policy. Fairness evidence may be included only
after its permitted terminal reveal. Never export seat credentials, unrevealed active offers or
active server seeds. If an incomplete export lacks revealed terminal fairness evidence, mark it
incomplete and unverifiable rather than claiming a complete verified game.

The export's exact top-level fields are `schemaVersion`, `variant`, `provenance`, `timeControl`
(the existing structured enum), `initialDfen`, `status` (the existing `GameStatus`), `completeness`
(`"complete"` or `"incomplete"`), `turns`, `currentTurn` (the public turn projection), and `fairness`.
Each completed `turns` entry contains `turnNumber`, `activeColor` (`"w"`/`"b"`), `offers` (two pairs),
`selectedPairIndex` (0/1), `dice` (the selected pair), `moves` (UCI, empty for a pass), and `fenAfter`.
`fairness` is null until a permitted terminal reveal; afterwards its fields are `commit`,
`serverSeed`, and `clientSeeds` (the existing `{white, black}` shape). A technically aborted
transcript has `completeness: "incomplete"` even if its server seed has been revealed. The export
does not include private command receipts, guest identifiers or seat tokens. A terminal
`currentTurn` preserves only offers actually revealed and an accepted selection, if any.

The lab uses its own database and explicit deployment configuration. Its rooms are unlisted,
human-only and casual, with no bot admission, rating/training writes, main profile/statistics
effects or general analytics/outbox ingestion. Those exclusions are server/storage boundaries,
not UI-only controls. Exporting from the lab is not promotion into the main analytics corpus.
The laboratory can be retired after export; arbitrary variant editing and a permanent service
are not requirements.

### Storage implementation

`PgLaboratoryStore` implements the private `LaboratoryStore` seam over a **dedicated PostgreSQL
database**. Its independent Flyway location is `classpath:db/laboratory`, with schema and migration
history in `laboratory`. The classic `db/migration` line is unchanged. Before creating anything,
the laboratory migrator refuses a database containing user tables or views outside that schema,
including a classic fixture database or an analytics database. It never converts a classic database
into a laboratory database. Runtime configuration and wiring belong to
[issue 293](https://github.com/fortemate/dicechess-play-api/issues/293).

The existing private `GameSnapshot` has optional `variant` and `lab` extensions, and completed
`TurnRecord` values have an optional `labChoice`. Absent fields decode as classic only in this known
legacy snapshot shape. Explicit null identities, unsupported revisions, missing laboratory context,
invalid generated/revealed offers and mismatched public phases are errors. Integration code uses
the typed `encodeValidated` boundary. Classic snapshots keep their previous field shapes when the
extensions are absent, and the classic PostgreSQL writer rejects laboratory snapshots.

The private laboratory context captures the protocol and rules/dice provenance, initial position,
both generated offers, reveal count, accepted choice, frozen effective seed inputs, initial-join
deadline and observed join history, terminal reveal permission and per-seat command receipts.
The existing snapshot retains clocks, seat credentials, submitted seeds, draw state, status and
completed micro-moves. A receipt stores the parsed command, including its original expected version,
as the semantic retry fingerprint. It is never part of the public history. Live connection presence
must still be checked by the room; stored join history alone is not presence after recovery.

`commit(gameId, expectedVersion, snapshot)` is the persist-before-broadcast boundary: `None` strictly
creates, and `Some` fences an update against the stored version. A per-game transaction lock covers
the snapshot, public transcript, result and receipt list. Existing rows are locked against concurrent
expiry cleanup. The room broadcasts and acknowledges only
after `Applied`; an exact retry returns `Unchanged`, and a stale or competing write returns `Conflict`.
Creation identity/settings, captured provenance and frozen seed inputs are immutable. History and
receipts are append-only; generated offers, reveal progress and a confirmed choice cannot be rewritten
within the same macro-turn. This storage seam does not decide move legality or implement room transitions.

The `games`, `results`, `transcripts` and `rematches` projections retain variant id/revision in ordinary
columns, with foreign-key agreement between them. `results` also preserves technical aborts with a
null sporting result. `transcripts` is refreshed on every accepted save, including waiting and
interrupted games. Its schema-1 payload follows the exact export fields above; public reads decode
and re-encode that shape, excluding unknown private-shaped fields. A terminal save is immutable and
idempotent. A sporting export is complete only with permitted fairness evidence; aborts stay incomplete.

`createRematch` atomically stores one successor and the inherited variant, protocol and time control.
The successor starts at `AwaitingPlayers` with new private credentials and seed, empty history/receipts
and no frozen client inputs. Retries return the existing successor rather than creating another game.
The caller is responsible for both authenticated consents and the live startup gate.

### Bounded retention and export

The storage default is **30 days from database creation of each game**, configurable between 1 and
365 days for an experiment. Accepted transitions, reconnects, retries and terminal saves do not move
that deadline. All records share it: active and incomplete snapshots, aborted and sporting results,
public transcripts and private command receipts. Expired records are immediately unavailable to
recovery/export and cannot be revived by a save. `pruneExpired` removes at most 1,000 games per batch,
with dependent records deleted by foreign keys. The compatibility `GameStore.save` seam creates only
a fresh version-zero invitation; it cannot recreate a missing in-flight game after pruning. Explicit
`commit(..., None, ...)` is a trusted strict-insert boundary: creation callers must mint a fresh game id,
and local/test fixture bootstrap can insert an already-interrupted snapshot. A successor has its own deadline; expiration of its
source removes the old rematch link, not the successor's transcript.

Export the sanitized transcript through `transcriptFor` before its deadline; it is also available for
active/incomplete and technically aborted games. There is no automatic export into analytics and no
indefinite laboratory archive. Scheduling pruning and providing the deployment/export surface belong
to the isolated runtime tasks. No shared database migration or service deployment is part of this change.

The storage tests use disposable Testcontainers databases: a fresh laboratory database, a V1 classic
fixture upgraded through its own migrations, refusal to migrate that classic database as laboratory,
interrupted-stage recovery, atomic rollback, concurrent version fencing, sanitized archive/export,
terminal retries, rematch inheritance and expiry. Run the focused suites with:

```sh
sbt 'testOnly dicechess.play.store.LaboratorySnapshotSuite dicechess.play.store.PgLaboratoryStoreSuite'
```

## Independent two-dice qualification

`TwoDiceRulesSuite` uses manually specified positions/UCI witnesses through `EngineOps` and the
resolved rules jar. It has no bot or search calls. `LaboratoryCodecsSuite` pins the new envelopes,
stage disclosure, pair cardinality, safe versions, explicit unknown-variant rejection and unchanged
classic decoding. Run them alongside the existing classic codec suite:

```sh
sbt 'testOnly dicechess.play.game.TwoDiceRulesSuite dicechess.play.wire.LaboratoryCodecsSuite dicechess.play.wire.CodecsSuite'
```

| Rule fixture | Independent witness |
| --- | --- |
| Pawn unlocks bishop; maximal consumption | Initial position, `[1,3]`: `e2e3 f1c4` is legal; `e2e3` alone is not |
| Duplicate dice; same piece twice | Initial position, `[2,2]`: `g1f3 f3g5` |
| Castle spends two dice in one move | `4k3/8/8/8/8/8/8/4K2R w K - 0 1`, `[6,4]`: `e1g1`; `[6]` cannot castle |
| Promotion spends the following piece die | `7k/P7/8/8/8/8/8/7K w - - 0 1`, `[1,5]`: `a7a8q a8h8` captures the king |
| Eligible en passant at turn start | `7k/8/8/3pP3/8/8/8/7K w - d6 0 2`, `[1,1]`: exactly `e5d6 d6d7` or `e5e6 e6e7` |
| No newly acquired en-passant eligibility | `7k/8/8/3p4/4P3/8/8/7K w - d6 0 2`, `[1,1]`: exactly `e4e5 e5e6` or ordinary capture `e4d5 d5d6`; no `e4e5 e5d6` |
| No legal selected turn | Initial position, `[3,4]`: empty legal-path set |
| Short king-capture exception | `7k/8/8/8/8/8/P7/K6Q w - - 0 1`, `[5,1]`: `h1h8` wins even though `a2a3 h1h8` also exists |

Record the resolved rules dependency and these suite results in the implementation PR. A rules
failure is evidence for a separately bounded engine/upgrade issue, not permission to redefine
revision 1 or silently substitute another dependency. Passing these fixtures qualifies the
chosen-pair rules seam; full room/retry/clock/isolation acceptance belongs to the later tasks.
