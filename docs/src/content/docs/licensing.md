---
title: Licensing for Bots
description: You can write a closed-source bot. Talking to this API over the network imposes no license obligation — only linking the engine would, and you never need to.
---

**Short version: you can write and keep a private, closed-source bot.** Playing over the network imposes no licensing obligation on your code. This page explains why, and how the official starter kits are licensed. It describes this project's licensing posture; it is not legal advice — consult a lawyer for your specific situation.

## The wire is a boundary

The play-api server is AGPL-3.0. But you interact with it purely as a **network service** — Bearer auth, REST, and ndjson streams over HTTP. Using a network service does not make your client a derivative work of the server: you are sending requests and reading responses, not incorporating the server's code. Your bot can be any language and any license, public or private.

The copyleft question only arises if you **link the game engine** into your own program (as a Maven/npm/WASM dependency) — that would make your program a derivative of the AGPL engine.

## You never need to link the engine

Historically the only way to compute correct Dice Chess moves was to link the engine, because the rules — especially the Maximum Micro-moves Rule — are subtle. That is no longer true:

- Every roll's **complete set of legal turns** is published on the wire as a [prefix tree](../game-mechanics/#legal-moves) (`DiceRolled.legalMoves`, `Snapshot.state.legalMoves`, and `GET /games/{id}/moves`).
- A bot picks a move by **walking that tree** — no rules engine, no board representation, no linked dependency.

So a private bot needs no engine dependency at all. The wire carries everything the rules would compute. That is a deliberate design choice, precisely to keep the engine's copyleft off your side of the boundary.

## The starter kits are permissive

| Component | License | Why |
| --- | --- | --- |
| play-api server, game engine | AGPL-3.0 | The platform itself stays copyleft. |
| The reference bot (`dicechess-reference-bot`) | AGPL-3.0 | It is a fork-and-replace template derived from platform code. |
| **The official Python / TypeScript starter kits** | **MIT** | Thin transport wrappers you can copy into a closed-source bot with no copyleft reach. |

The starter kits are intentionally **transport only** — auth, reconnect with backoff, HTTP-status and `Retry-After` handling, token re-mint on `401`, re-challenge on game end — with **no engine code**, so nothing in them pulls a copyleft obligation into your project. Two are live, each a "Use this template" starter with a runnable poll-only bot:

- **[dicechess-bot-python](https://github.com/rabestro/dicechess-bot-python)** — dependency-free (stdlib only).
- **[dicechess-bot-typescript](https://github.com/rabestro/dicechess-bot-typescript)** — zero runtime dependencies (built-in `fetch`).

## In one line

- **Play over the wire** → no obligation, write whatever you want.
- **Copy a permissive starter kit** → MIT/Apache, keep your bot closed.
- **Link the AGPL engine** → only then does copyleft apply — and you never have to, because the legal moves are already on the wire.
