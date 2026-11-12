---
title: Laboratory dice verification
description: Exact version-1 two-pair derivation, independent test vectors and terminal transcript verification.
---

This specifies `choice-2x2d6-hmac-sha256-v1`, implemented by `LaboratoryDiceSource` for
[issue 291](https://github.com/fortemate/dicechess-play-api/issues/291). It is a separate, private
dice-source seam for the [laboratory contract](../laboratory-contract/). Classic `DiceSource`,
its byte encoding and its three-dice stream are unchanged. This work enables no routes or rooms;
the authoritative room and reveal policy belong to
[issue 292](https://github.com/fortemate/dicechess-play-api/issues/292).

## Inputs and commitment

Generate a fresh 32-byte server seed using a CSPRNG for each game. Publish its SHA-256 digest
as 64 lowercase hex characters **before** collecting the client seeds. Freeze the effective
white and black client seeds after the post-commit readiness gate. Recovery must use exactly
those persisted values and the captured algorithm identifier; an unknown version fails closed.
The dice source does not choose fallback seeds, advance the room or enforce readiness itself.

`turnNumber` is the zero-based macro-turn identity, the persisted current `ply`: 0 for White's
first turn, 1 for Black's first turn, and so on. It advances once after a completed turn, including
an explicit pass, and never for a roll, choice, retry, micro-move or reconnect. Version 1 accepts
`0..9007199254740991`, matching the exact JSON integer contract. The alternatives' stable
`offerIndex` values are 0 and 1. Equal pairs retain both indices.

Client seeds are encoded as strict UTF-8, without trimming, case folding or Unicode normalization.
Lengths count **bytes**, including any embedded NUL. Malformed Unicode is rejected, rather than
silently replaced. Server seeds restore from exactly 64 ASCII hex characters (either case);
errors contain no submitted seed. Constructors defensively copy the server seed.

## Exact version-1 derivation

All integers below are big-endian. `++` denotes concatenation, `ASCII` and `UTF8` denote raw bytes,
and the domain ends with exactly one zero byte, with no length prefix or newline:

```text
domain = ASCII("choice-2x2d6-hmac-sha256-v1") ++ 0x00
w = UTF8(clientSeeds.white)
b = UTF8(clientSeeds.black)

base(turnNumber, offerIndex) = domain
  ++ uint32be(length(w)) ++ w
  ++ uint32be(length(b)) ++ b
  ++ int64be(turnNumber)
  ++ uint8(offerIndex)

block(k) = HMAC-SHA256(serverSeed, base ++ uint32be(k))
```

Derive each pair independently, starting its own block counter at 0. Scan each 32-byte digest
from left to right as unsigned bytes. Reject values 252–255. Every other byte yields
`(byte % 6) + 1`. Take exactly the first two accepted values in generation order. If necessary,
continue with the next block, retaining any already accepted die. No mutable stream cursor is
shared between alternatives or calls; pair 2 is not a continuation of pair 1.

Each face has exactly 42 preimages in 0–251, so rejection removes modulo bias, under the usual
HMAC pseudorandomness assumption. The counter range is 0–4294967295 inclusive. If that entire
space is exhausted without two accepted bytes, return a deterministic error; never wrap the
counter, reuse blocks or substitute biased dice. The bounded sampler therefore has an explicit
failure outcome even for pathological input, rather than claiming an unconditional mathematical
guarantee of successful sampling.

Only the committed server seed, frozen client seeds, turn number and offer index affect the
result. The position, game identifier, wall clock, command identifier and selected pair do not.
The versioned domain separates this stream from the classic three-dice stream.

## Published vectors and independent verifier

Download the [JSON vectors](../contracts/laboratory-dice-v1-vectors.json) and
[dependency-free Python reference](../contracts/laboratory-dice-v1.py) into the same directory.
Every seed in that fixture is deliberately public synthetic test data. Run Python 3:

```sh
python3 laboratory-dice-v1.py
```

The fixture pins commitments, canonical message bytes, first HMAC blocks, both pairs and the
unchanged classic three-dice result. Cases include turn/seed/offer separation, seed-boundary
ambiguity, UTF-8, embedded NUL, empty seeds, equal alternatives and a rejected leading byte.
The Python reference was implemented independently of the Scala source. The Scala suite consumes
the same published vectors and separately forces rejection, block refill and counter exhaustion.
These are deterministic checks; no frequency test is used as proof of unbiasedness.

## Verify a terminal transcript

1. Retain the original pre-seed commitment and the frozen client seeds when joining the game.
   A commitment delivered only with a terminal export proves internal consistency, not that the
   server committed before receiving those seeds.
2. Check the laboratory variant/revision, export schema and captured `provenance.diceAlgorithm`.
   Dispatch by that exact version; do not silently apply the latest algorithm to old games.
3. Require a permitted terminal reveal and non-null `fairness`. Decode its `serverSeed`, check
   SHA-256 against both `fairness.commit` and the retained initial commitment, and compare the
   effective `fairness.clientSeeds` with the originally frozen values. An active game or missing
   terminal evidence is not publicly verifiable.
4. For each completed entry, in consecutive zero-based `turnNumber` order, call the reference's
   `derive_offers(bytes.fromhex(server_seed_hex), white, black, turn_number)` and compare **both** alternatives
   in their original order. Confirm `selectedPairIndex` is 0 or 1 and `dice` equals that pair.
   An empty `moves` list is an explicit pass, not permission to omit the offers or increment twice.
5. Derive the next turn at `len(turns)` for `currentTurn`. Compare only the 0–2 offers actually
   revealed in that public projection and its selection, if present. Never require an unrevealed
   alternative to have been published while the game was active.
6. Report dice verification separately from move legality, clocks, command ordering and whether
   the game is complete. A technically aborted export stays `incomplete` even when its permitted
   seed reveal makes the recorded dice verifiable. Missing evidence must never become a claim of
   a complete verified game.

The room must generate and persist both private alternatives before revealing pair 1. It publishes
pair 2 only after accepting `RollSecond`, and publishes the server seed only under the terminal
reveal policy. Repeated commands and restoration derive identical pairs; they do not authorize
new public revelation. The source has no public encoder or HTTP endpoint.
