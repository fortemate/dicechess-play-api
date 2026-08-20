---
title: Provably-Fair Dice
description: The commit-reveal scheme behind every roll, and the exact procedure to recompute a finished game's dice yourself and prove no one grinded them.
---

The server is authoritative over the dice — but it commits to its randomness **before** it sees yours, and reveals everything **after** the game, so anyone can recompute every roll and confirm it was neither chosen nor changed to favour a side. This page is the verification procedure in full: the guarantee, the three published values, and code you can run.

## The guarantee

Each roll is an HMAC keyed by a secret **server seed**, mixing in both players' **client seeds** and the ply index. The trick is the ordering:

1. **Commit.** At game creation the server publishes `commit = SHA-256(serverSeed)`. It is now locked to a server seed it cannot change without breaking the hash.
2. **Contribute.** *After* the commit, each side submits its own `clientSeed`. Because the commit was published first, the server could not have chosen its seed to steer the rolls given yours — and because your seed is folded into every roll, neither could a player who saw the commit.
3. **Roll.** Every roll is `HMAC-SHA256(serverSeed, message)` reduced to three unbiased dice.
4. **Reveal.** At game end the server reveals `serverSeed` and both client seeds. Anyone can now recompute every roll and check that `SHA-256(serverSeed)` equals the `commit`.

Neither side can grind the dice: the server is bound by the commitment before it sees the client seeds, and no player controls the server seed.

## The three published values

| Value | Where | When |
| --- | --- | --- |
| `commit` | create response, and every `Snapshot.state.commit` | game start (constant for the game) |
| `seed` | `GameEnded.seed`, and `GET /games/{id}` after the end | game end (the revealed server seed, hex) |
| `clientSeeds` | `GameEnded.clientSeeds`, and `GET /games/{id}` | game end (`{"white": "...", "black": "..."}`) |

If a seat never submitted a seed, the server folds in that seat's **public external id** as the fallback, and `clientSeeds` shows exactly that value — so the transcript is still fully reproducible.

## The roll function

For ply index `n` and the two client seeds, the message is a **canonical, length-prefixed** byte string (so different seed splits can never collide):

```text
message(n) = uint32be(len(clientWhite)) ++ clientWhite
          ++ uint32be(len(clientBlack)) ++ clientBlack
          ++ int64be(n)
```

Lengths are UTF-8 **byte** counts; all integers are big-endian. The three dice are drawn from the HMAC output by **rejection sampling**, which removes modulo bias:

- Compute `HMAC-SHA256(serverSeed, message(n) ++ int32be(block))`, starting at `block = 0`.
- Scan the output bytes. For each byte `b` (`0..255`): if `b < 252` (the largest multiple of 6 below 256), it yields the die `(b mod 6) + 1`; otherwise it is **rejected** and skipped.
- Collect three dice. If a block runs out first, increment `block`, re-key, and keep scanning — so the result is always complete *and* unbiased.

## Verify it yourself

This dependency-free Python reproduces the server exactly. Feed it the revealed `seed`, the `clientSeeds`, and a ply index, and it returns the same three dice the server rolled.

```python
import hashlib
import hmac
import struct


def roll(server_seed_hex: str, client_white: str, client_black: str, ply: int) -> list[int]:
    server_seed = bytes.fromhex(server_seed_hex)
    w, b = client_white.encode("utf-8"), client_black.encode("utf-8")
    message = (
        struct.pack(">I", len(w)) + w
        + struct.pack(">I", len(b)) + b
        + struct.pack(">q", ply)          # int64be
    )

    dice: list[int] = []
    block = 0
    while len(dice) < 3:
        digest = hmac.new(
            server_seed, message + struct.pack(">i", block), hashlib.sha256
        ).digest()
        for byte in digest:
            if len(dice) == 3:
                break
            if byte < 252:                 # reject to avoid modulo bias
                dice.append(byte % 6 + 1)
        block += 1
    return dice


def commit_matches(server_seed_hex: str, commit_hex: str) -> bool:
    return hashlib.sha256(bytes.fromhex(server_seed_hex)).hexdigest() == commit_hex
```

The full verification of a finished game is then:

1. Confirm `commit_matches(seed, commit)` — the revealed seed is the one committed at the start.
2. For each roll in the game (in order, `ply = 0, 1, 2, …`), call `roll(seed, clientWhite, clientBlack, ply)` and check it equals the `dice` you saw on that `DiceRolled` event.

`ply` is the monotonic roll counter the server folds in; reproduce the rolls in the order they occurred. If every roll matches and the commit checks out, the dice were provably fixed before either seed was known.

:::note[Position independence]
`roll` depends only on the seeds and the ply — never on the moves played. That means you can verify a roll without reconstructing the board.
:::

`seed` and `clientSeeds` are `null` while a game is still active and become available the instant it ends — every finished game reveals immediately, with nothing ever withheld.
