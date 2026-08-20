#!/usr/bin/env python3
"""A complete Dice Chess bot in pure Python stdlib — no engine, no rules code, no streams, no dependencies.

Self-play demo: mints two anonymous tokens, has A challenge B, B accept, and then
both sides play random legal turns by polling until the game ends.

The whole "chess" part is: fetch the legal-move tree, walk root->leaf at random.
  * GET  /bot/challenges          -> discover the incoming challenge (no stream held)
  * POST /bot/challenge/{id}/accept
  * GET  /bot/games               -> whose turn is it, in which game
  * GET  /games/{id}/moves        -> the legal turns as a prefix tree of UCI moves
  * POST /bot/game/{id}/move      -> submit; the verdict comes back synchronously

Usage: PLAY_API=http://localhost:8080 python3 random_bot.py
(point PLAY_API at a locally running dicechess-play-api, or at the live server)

This example is dedicated to the public domain (CC0) — copy it into your own bot freely,
whatever license your bot uses. Playing the platform over the wire imposes no obligations.
"""

import json
import os
import random
import secrets
import time
import urllib.error
import urllib.request

BASE = os.environ.get("PLAY_API", "http://localhost:8080")


def api(method: str, path: str, token: str | None = None, body: dict | None = None, params: str = ""):
    req = urllib.request.Request(f"{BASE}{path}{params}", method=method)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data=data) as resp:
            return json.loads(resp.read() or "{}")
    except urllib.error.HTTPError as e:  # 409 = synchronous move verdict, still a JSON body
        return json.loads(e.read() or "{}")


def bot(name: str) -> dict:
    """Mint an ephemeral anonymous identity — zero registration."""
    me = api("POST", "/bot/anon", params=f"?name={name}")
    print(f"[{name}] minted {me['id']}")
    return me


def random_turn(tree: dict) -> list[str]:
    """Walk the legal-move tree root->leaf, picking a random child at each node.

    A childless node IS a complete legal turn (the server pre-applies the
    Maximum Micro-moves Rule), so any such walk is a valid submission."""
    path = []
    while tree:
        move = random.choice(list(tree))
        path.append(move)
        tree = tree[move]
    return path


def play_if_my_turn(me: dict, name: str) -> bool:
    """One poll tick: find a game where it's my roll, submit a random legal turn."""
    games = api("GET", "/bot/games", me["token"])["games"]
    for g in games:
        if g["dicePending"] and g["activeSeat"] == g["seat"]:
            moves = api("GET", f"/games/{g['gameId']}/moves")
            turn = random_turn(moves["legalMoves"])
            if not turn:
                continue  # forced pass: the server plays it for us
            verdict = api("POST", f"/bot/game/{g['gameId']}/move", me["token"], {"moves": turn})
            print(f"[{name}] {g['seat']}: {' '.join(turn):18} -> {verdict}")
    return bool(games)


def main() -> None:
    a, b = bot("rustam"), bot("emilia")

    # A challenges B; B discovers it by POLLING (no event stream anywhere in this file).
    ch = api("POST", "/bot/challenge", a["token"], {"team": b["team"], "name": b["name"]})
    print(f"[rustam] challenged emilia: {ch['id']} (targetOnline={ch['targetOnline']})")
    incoming = api("GET", "/bot/challenges", b["token"])["in"]
    game = api("POST", f"/bot/challenge/{incoming[0]['id']}/accept", b["token"])
    print(f"[emilia] accepted -> game {game['gameId']}")

    # Contribute provably-fair entropy (optional but polite; opens the roll gate instantly).
    for me in (a, b):
        api("POST", f"/bot/game/{game['gameId']}/seed", me["token"], {"seed": secrets.token_hex(16)})

    # The whole game loop: poll, move if it's our turn, until the game leaves the listings.
    while play_if_my_turn(a, "rustam") | play_if_my_turn(b, "emilia"):
        time.sleep(0.2)

    # The room is evicted from the live registry shortly after the game ends, so this read races it.
    over = api("GET", f"/games/{game['gameId']}")
    if "status" in over:
        status = over["status"].get("Ended", {}).get("over", over["status"])
        print(f"game over: {status}")
        print(f"dice were provably fair: commit={over['commit'][:16]}… seed={str(over['seed'])[:16]}…")
    else:
        print("game over: the room already left the live registry (exported to analytics)")


if __name__ == "__main__":
    main()
