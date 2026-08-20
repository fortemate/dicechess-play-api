# Dice Chess Play API 🎲♟️

[![CI Pipeline](https://github.com/fortemate/dicechess-play-api/actions/workflows/ci.yaml/badge.svg)](https://github.com/fortemate/dicechess-play-api/actions/workflows/ci.yaml)
[![Play Live](https://img.shields.io/badge/Play-Live-success)](https://fortemate.com/)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-lightgrey)](./LICENSE)

Authoritative real-time server for **Dice Chess** — human-vs-human play, a third-party **Bot API**, and an automatic **Glicko-2 rating ladder**.

> **Status: live.** Authoritative HvH over WebSocket, the full Bot API (REST + ndjson event streams + webhooks), PostgreSQL durability with crash recovery, analytics hand-off, and a continuously-paired Glicko-2 rating ladder.

## Architecture

Scala 3 · cats-effect · http4s, reusing the **dice-chess engine on the JVM** (`com.fortemate:dicechess-engine`) so move legality and rules never drift from the client.

```
  browser SPA (dicechess-play) ──WebSocket──┐
                                            ▼
  third-party bot ──HTTP (ndjson + REST)──► play-api (AUTHORITY)
                                            │  per-game fiber + Ref + Topic + Queue
                                            │  engine (JVM) · server clocks · DiceSource
                                            ▼  on game end: POST /api/games (Bearer)
                                       dicechess-analytics (read-only + token write)
```

**Transport-agnostic player — the core principle.** A `GameRoom` does not know whether a player is a human over WebSocket or a bot over HTTP. A player is *something that receives game events and submits commands*, identified by a `Principal` and seated at a `Seat`. The website WS and the Bot API are two thin adapters over the same room — the game logic is written once and is identical for human-vs-human, human-vs-bot, and bot-vs-bot.

### Dice fairness

The **server** generates dice (CSPRNG), wrapped in **commit-reveal** so every roll is provably fair after the fact, behind a swappable `DiceSource` interface. No client ever rolls.

### Bot API

Third-party bots connect via a dedicated API — a token plus any of three connection modes: REST polling, an ndjson event stream, or a single serverless **webhook** (the server POSTs each turn, the HTTP response is the move). Language-agnostic and reconnect-safe.

## Quick Start

### Prerequisites

- [mise](https://mise.jdx.dev/) (manages Java, sbt, scalafmt, and developer tools)
- PostgreSQL (or run via Docker / Testcontainers)

```bash
mise run setup       # Install dependencies & register git hooks
mise run compile     # Compile Scala sources
mise run test        # Run unit & integration tests
mise run check       # Run the full CI quality gate locally
mise run run         # Start local server on :8080
```

## License

Licensed under the [GNU Affero General Public License v3.0](./LICENSE).
