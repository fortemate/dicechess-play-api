---
title: Testing
description: munit conventions, why suites run serially, the non-flaky patterns this repo learned the hard way, and the deadlock that masquerades as a timeout bug.
---

Tests use munit. Pure logic suites extend `munit.FunSuite`; effectful suites extend
`munit.CatsEffectSuite`. Suites are named `<Unit>Suite` and mirror the main package layout, and
test names are full sentences describing behaviour:

```scala
test("the game-end event reveals the server seed")
```

Six suites need Docker, because they run against a real Postgres via Testcontainers:
`PgGameStoreSuite`, `PgQueryCheckSuite`, `PgShowcaseStoreSuite`, `IngestDelivererSuite`,
`RatingBatchSuite`, `HistoryRoutesSuite`. Everything else is Docker-free — including the showcase
coordinator's own suites (`ShowcaseTableSuite`, `ShowcaseRoutesSuite`), which run over the in-memory
harness in `ShowcaseHarness`.

## Suites run one at a time

`Test / parallelExecution := false`. This is not a workaround left in place out of caution:
running the container suites concurrently under scoverage caused enough real CPU
contention to **delay a cats-effect timer by 133 seconds**. Serial is also the *faster* option
as measured — a steady 30 seconds versus a bimodal 30-or-313 — because container startup
dominates and does not parallelise usefully.

Do not re-enable parallel execution to "speed up" CI.

## The deadlock that looks like a timeout bug

A game with an **idle seat and no clock stops forever**. From the starting position only pawns
and knights can move, so a roll containing neither makes the room auto-pass to the other seat;
with `TimeControl.Unlimited` and nobody driving that seat, the game never progresses.

The failure is dice-dependent — roughly `(4/6)³ ≈ 30%` — so it presents as an intermittent
timeout. It has been misdiagnosed **twice** as fiber starvation and "fixed" by widening a
bound. If a test needs a specific seat to get an actionable turn, drive the opponent (see
`WebhooksSuite` and its `BotConnection`) instead of assuming the opening roll falls that seat's
way.

## Non-flaky patterns

This repository has fixed three separate stream races. The patterns that came out of them:

- **Subscribe before acting.** Never trigger an event and then attach to the stream.
- **Poll durable state** rather than sleeping or racing the live stream.
- **Bound every effectful wait** with `timeoutTo`.

## Shared-database assertions

`PgGameStoreSuite` runs every test against one Postgres instance with no reset between tests.
Aggregates computed over the whole table therefore cannot be asserted as if they belonged to a
single test. This has produced false failures twice in one day, and both times the code was
right and the assertion was wrong — scope assertions to the rows the test created.

## Hard bugs get a failing test first

For a non-trivial bug, land the failing test **before** the fix, as its own pull request, marked
suspended (`.fail`, not skipped). Reference the issue with `refs #N` in that test-only pull
request — not `Closes`, since the bug is not fixed yet.

## Running one suite

```bash
sbt "testOnly dicechess.play.game.GameRoomSuite"
```

## Checking a live deployment

`scripts/smoke-test.sh` boots a built image with **no database**, so every persistence-backed path
answers from its in-memory fallback. That is the right shape for a pre-publish gate — it needs
nothing but the image — but it means a broken query, an unset `PLAY_DB_URL`, or a promotion that
pulled an image without recreating the container all still read as PASS.

`scripts/post-deploy-check.sh` covers that half against a running deployment:

```bash
BASE_URL=https://api.fortemate.com EXPECT_VERSION=v0.16.9 scripts/post-deploy-check.sh
```

It pins what is really serving (`GET /version` — the promotion may not have taken), proves the
catalog is mounted at all (that route is absent without a database), then **starts one real game
against a catalog bot** and asserts the bot's seat came back carrying its settled rating.

That last step is why the script writes rather than only reading. `GameRegistry.createRoom`
resolves both seats through `nicknamesByExternalId` and `settledRatingsByExternalId`, and those two
queries run on no read path at all — nothing you can GET will exercise them. Read the assertion
precisely, though: a bot seat's *name* comes from its external id in pure code, so only the
*rating* is evidence a query ran, and a rating is attached only once the bot's own rating has
converged. Against a catalog of purely provisional bots the script reports `PASS (PARTIAL)` and
names what it could not cover.

The side effect is one casual game, played by a synthetic guest id, which nobody moves and which
therefore ends on its own clock. It is always casual — a guest seat can never be rated — so it
reaches no rating. `PLAY_GAME=0` stops after the read-only steps when even that row is unwanted,
at the cost of covering nothing this script exists for.
