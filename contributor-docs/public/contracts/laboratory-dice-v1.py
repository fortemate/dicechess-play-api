#!/usr/bin/env python3
"""Independent reference implementation for laboratory dice v1.

Published vector seeds are synthetic public fixtures; never use them in production.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import sys
from pathlib import Path
from typing import Any

ALGORITHM = "choice-2x2d6-hmac-sha256-v1"
DOMAIN = ALGORITHM.encode("ascii") + b"\x00"
MAX_TURN = 9007199254740991
MAX_COUNTER = 0xFFFFFFFF


class VectorError(ValueError):
    pass


def commitment(server_seed: bytes) -> str:
    _check_server_seed(server_seed)
    return hashlib.sha256(server_seed).hexdigest()


def _check_server_seed(server_seed: bytes) -> None:
    if not isinstance(server_seed, bytes) or len(server_seed) != 32:
        raise ValueError("server seed must be exactly 32 bytes")


def _check_inputs(white: str, black: str, turn_number: int, offer_index: int) -> tuple[bytes, bytes]:
    if not isinstance(white, str) or not isinstance(black, str):
        raise TypeError("client seeds must be strings")
    white_bytes = white.encode("utf-8", errors="strict")
    black_bytes = black.encode("utf-8", errors="strict")
    if len(white_bytes) > 0xFFFFFFFF or len(black_bytes) > 0xFFFFFFFF:
        raise ValueError("client seed UTF-8 length exceeds uint32")
    if isinstance(turn_number, bool) or not isinstance(turn_number, int) or not 0 <= turn_number <= MAX_TURN:
        raise ValueError("turn number must be an integer from 0 through 9007199254740991")
    if isinstance(offer_index, bool) or not isinstance(offer_index, int) or offer_index not in (0, 1):
        raise ValueError("offer index must be 0 or 1")
    return white_bytes, black_bytes


def message_base(white: str, black: str, turn_number: int, offer_index: int) -> bytes:
    wb, bb = _check_inputs(white, black, turn_number, offer_index)
    return (DOMAIN + len(wb).to_bytes(4, "big") + wb + len(bb).to_bytes(4, "big") + bb
            + turn_number.to_bytes(8, "big", signed=True) + bytes((offer_index,)))


def classic_base(white: str, black: str, turn_number: int) -> bytes:
    wb, bb = _check_inputs(white, black, turn_number, 0)
    return (len(wb).to_bytes(4, "big") + wb + len(bb).to_bytes(4, "big") + bb
            + turn_number.to_bytes(8, "big", signed=True))


def _accepted_dice(server_seed: bytes, base: bytes, count: int) -> tuple[tuple[int, ...], bytes]:
    _check_server_seed(server_seed)
    accepted: list[int] = []
    first_block: bytes | None = None
    counter = 0
    while counter <= MAX_COUNTER:
        digest = hmac.new(server_seed, base + counter.to_bytes(4, "big"), hashlib.sha256).digest()
        if first_block is None:
            first_block = digest
        for byte in digest:
            if byte < 252:
                accepted.append(byte % 6 + 1)
                if len(accepted) == count:
                    return tuple(accepted), first_block
        counter += 1
    raise RuntimeError("HMAC block counter space exhausted")


def derive_offers(server_seed: bytes, white: str, black: str, turn_number: int) -> tuple[tuple[int, int], tuple[int, int]]:
    _check_server_seed(server_seed)
    offers = []
    for index in (0, 1):
        values, _ = _accepted_dice(server_seed, message_base(white, black, turn_number, index), 2)
        offers.append((values[0], values[1]))
    return offers[0], offers[1]


def classic_dice(server_seed: bytes, white: str, black: str, turn_number: int) -> tuple[int, int, int]:
    values, _ = _accepted_dice(server_seed, classic_base(white, black, turn_number), 3)
    return values  # type: ignore[return-value]


def _vector(name: str, server_seed: bytes, white: str, black: str, turn: int) -> dict[str, Any]:
    bases = [message_base(white, black, turn, i) for i in (0, 1)]
    return {
        "name": name,
        "serverSeed": server_seed.hex(),
        "commit": commitment(server_seed),
        "clientSeeds": {"white": white, "black": black},
        "turnNumber": turn,
        "offers": [list(pair) for pair in derive_offers(server_seed, white, black, turn)],
        "messages": [value.hex() for value in bases],
        "firstBlocks": [hmac.new(server_seed, base + (0).to_bytes(4, "big"), hashlib.sha256).hexdigest()
                        for base in bases],
        "classicDice": list(classic_dice(server_seed, white, black, turn)),
    }


def build_vectors() -> list[dict[str, Any]]:
    asc = bytes(range(32))
    specs = [
        ("ascending-alice-bob-turn-0", asc, "alice", "bob", 0),
        ("ascending-alice-bob-turn-1", asc, "alice", "bob", 1),
        ("length-boundary-ab-c", asc, "ab", "c", 7),
        ("length-boundary-a-bc", asc, "a", "bc", 7),
        ("unicode-supplementary-nonascii-nul", bytes(range(32, 64)), "雪🙂\x00white", "черный\x00♟", 42),
        ("empty-client-seeds", bytes(range(64, 96)), "", "", 9),
        ("swapped-clients", asc, "bob", "alice", 0),
        ("different-server-seed", bytes(reversed(range(32))), "alice", "bob", 0),
        ("maximum-wire-turn", asc, "max", "turn", MAX_TURN),
    ]
    vectors = [_vector(*spec) for spec in specs]
    for turn in range(20000):
        candidate = _vector("equal-offer-pairs-witness", asc, "equal", "pair", turn)
        if candidate["offers"][0] == candidate["offers"][1]:
            vectors.append(candidate)
            break
    else:
        raise RuntimeError("could not find equality-of-pairs witness")
    for turn in range(20000):
        candidate = _vector("leading-rejected-byte-witness", asc, "reject", "first", turn)
        if bytes.fromhex(candidate["firstBlocks"][0])[0] >= 252:
            vectors.append(candidate)
            break
    else:
        raise RuntimeError("could not find leading rejected-byte witness")
    return vectors


def validate_fixture(path: Path) -> int:
    try:
        fixture = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise VectorError("cannot load vectors fixture") from exc
    if not isinstance(fixture, dict) or fixture.get("algorithm") != ALGORITHM:
        raise VectorError("fixture algorithm is missing or incorrect")
    vectors = fixture.get("vectors")
    if not isinstance(vectors, list) or not vectors:
        raise VectorError("fixture vectors must be a nonempty array")
    names: set[str] = set()
    for index, vector in enumerate(vectors):
        if not isinstance(vector, dict):
            raise VectorError(f"vector {index} is malformed")
        name = vector.get("name")
        if not isinstance(name, str) or not name or name in names:
            raise VectorError(f"vector {index} has missing or duplicate name")
        names.add(name)
        try:
            seed_text = vector["serverSeed"]
            if not isinstance(seed_text, str) or len(seed_text) != 64:
                raise ValueError
            seed = bytes.fromhex(seed_text)
            if seed.hex() != seed_text:
                raise ValueError
            clients = vector["clientSeeds"]
            white, black = clients["white"], clients["black"]
            turn = vector["turnNumber"]
            expected = _vector(name, seed, white, black, turn)
        except (KeyError, TypeError, ValueError, UnicodeError, OverflowError) as exc:
            raise VectorError(f"vector {name} has malformed inputs") from exc
        for key in ("commit", "offers", "messages", "firstBlocks", "classicDice"):
            if vector.get(key) != expected[key]:
                raise VectorError(f"vector {name} field {key} does not match reference computation")
    return len(vectors)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", nargs="?", type=Path, help="vectors JSON (defaults to sibling fixture)")
    parser.add_argument("--write-vectors", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    fixture_path = args.fixture or Path(__file__).with_name("laboratory-dice-v1-vectors.json")
    if args.write_vectors:
        fixture_path.write_text(json.dumps({"algorithm": ALGORITHM, "vectors": build_vectors()}, ensure_ascii=False,
                                           indent=2) + "\n", encoding="utf-8")
        return 0
    try:
        count = validate_fixture(fixture_path)
    except VectorError as exc:
        print(f"invalid fixture: {exc}", file=sys.stderr)
        return 1
    print(f"validated {count} {ALGORITHM} vectors")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
