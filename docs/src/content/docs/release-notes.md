---
title: Release Notes
description: Bot API changes and migration instructions.
---

## Unreleased

### Inline legal-move trees are deprecated

[`GET /games/{id}/moves`](../reference/rest/#get-legal-moves) is the single source of the complete legal-turn tree. It is public and never capped; bots can continue to play without linking a rules engine.

`DiceRolled.legalMoves` and `Snapshot.state.legalMoves` (also used by `GET /games/{id}` and webhook `state`) remain in the schemas as nullable, deprecated fields. The default `PLAY_MAX_INLINE_TURN_PATHS` changes from `1000` to `0`, so these inline fields are always `null`, including on forced passes. Unset or invalid settings also use `0`. A positive operator override can temporarily enable legacy inline copies.

On each pending turn belonging to your seat, fetch `GET /games/{id}/moves`, check that its `version` and `dfen` match the roll you are answering, and choose a root-to-leaf path from its `legalMoves`. Refresh the state on a mismatch. If `dicePending` is false or the endpoint tree is `{}`, submit nothing. A null inline field does not indicate a forced pass.

Update polling, streaming, and webhook clients to use this endpoint regardless of the inline fields. Clients that compute turns locally must compare the server's `rulesVersion` with their bundled rules and fetch the authoritative tree when the version is absent or differs, or when the server refuses a locally computed turn.
