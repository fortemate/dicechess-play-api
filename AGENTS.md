# AGENTS.md

Authoritative real-time server for Dice Chess (human-vs-human + Bot API + Glicko-2 rating ladder) — the game authority of the Fortemate ecosystem.

## Definition of Done — before every commit

<!-- dc-shared:definition-of-done v1 — keep identical across Fortemate Scala repositories -->

1. Format: `mise run format`. If `mise` is not on PATH: `~/.local/bin/mise exec -- sbt scalafmtAll`.
2. Gate: `mise run check` — the same command CI runs. If part of it cannot run in your sandbox (for
   example Docker for Testcontainers), run `mise exec -- sbt 'scalafmtCheckAll; Test/compile'` plus every
   suite that can run, and list what you skipped in the pull request.
3. Never publish unformatted Scala or code that does not compile: CI rejects both, and every red run
   costs a review cycle.

Sandboxed agents (Jules): the toolchain is provisioned by `scripts/jules-setup.sh` (Java, sbt, scalafmt
via mise). If a tool is missing, run `bash scripts/jules-setup.sh` instead of installing tools ad hoc.

<!-- /dc-shared:definition-of-done -->

## Project context

- Public repository, AGPL-3.0 (see `LICENSE`). Single-module Scala 3 project at repo root (package `dicechess.play`); http4s + cats-effect IO + fs2 + Doobie + Flyway over PostgreSQL.
- Published artifact: multi-arch Docker image `ghcr.io/fortemate/dicechess-play-api`.
- Contracts this repo publishes & consumes:
  - Game rules come exclusively from `com.fortemate:dicechess-engine` (pinned in `build.sbt`). Never re-implement chess/dice rules here.
  - WebSocket API: live human-vs-human and human-vs-bot game state protocol consumed by `dicechess-play` (SvelteKit SPA).
  - Bot API: REST, ndjson event streams, and webhooks consumed by house bots (`dicechess-house-bots`) and third-party bots.
  - Ingest handoff: finished games delivered to `dicechess-analytics` (`POST /api/games`).
- Live URLs:
  - Play frontend: `https://fortemate.com` (transitioning from `https://play.jc.id.lv`).
  - Server authority: `https://api.fortemate.com` (transitioning from `https://play-api.jc.id.lv`).

## Architecture map

- `src/main/scala/dicechess/play/`
  - `Main.scala` — the app entry point (`IOApp.Simple`): env config → Flyway migrate → Hikari pool → Ember server + background fibers.
  - `core/` — domain models (`Protocol.scala`, `Identity.scala`, `RatingCategory.scala`, `Seek.scala`, `BotEvent.scala`, `GameId.scala`).
  - `game/` — `GameRoom.scala` (authoritative room fiber, turn clocks, move validation), `EngineOps.scala` (bridge to engine), `PlayerConnection.scala`.
  - `dice/` — `DiceSource.scala` (CSPRNG with commit-reveal).
  - `rating/` — `Glicko2.scala`, `BradleyTerry.scala`, `Sprt.scala`, `RatingBatch.scala`, `StrengthCache.scala`, `StrengthReport.scala`.
  - `server/` — http4s routes (`PlayRoutes`, `BotRoutes`, `LobbyRoutes`, `AuthRoutes`, `AdminBotRoutes`, `WebhookRoutes`, `LeaderboardRoutes`, `MeRoutes`, `HealthRoutes`, `Cors.scala`, `GoogleAuth.scala`, `BotAuth.scala`, `WebhookSecurity.scala`).
  - `store/` — Doobie PostgreSQL storage (`GameStore`, `PgGameStore`, `UserStore`, `GameArchive`, `AdminBotStore`, `WebhookStats`).
  - `ingest/` — `IngestDeliverer.scala` (outbox publisher), `PlaysiteIngest.scala`.
  - `wire/` — Circe JSON codecs (`Codecs.scala`).
- `src/main/resources/db/migration/` — Flyway migrations (`V1__initial_schema.sql`).

## Commands

Prerequisites (in order):
1. `mise install` — tools pinned in `mise.toml` (Java temurin-25, sbt 2.0.8, scalafmt 3.11.4, gh, lefthook, betterleaks, jq); then `mise run setup` to register git hooks.
2. Docker running — tests use testcontainers for real PostgreSQL testing.

Daily tasks:
```sh
mise run check      # THE repo gate: scalafmtCheckAll + clean + coverage + testOnly * + coverageReport
mise run test       # sbt "testOnly *" (real PostgreSQL via testcontainers)
mise run format     # sbt scalafmtAll
mise run compile    # sbt "compile; Test/compile"
mise run run        # Start play-api on http://localhost:8080
mise run coverage   # Run tests with scoverage and generate report
```

## Quality gates — repository specifics

- `mise run check` must pass locally before opening a PR.
- Compiler options: `-Werror -Wunused:all -deprecation -feature -explain` — warnings fail the build.
- Lefthook hooks: pre-commit = betterleaks secret scan + scalafmt on staged files; pre-push = full-tree format check.
- Real PostgreSQL testing via Testcontainers — never mock database schemas.

## Code conventions

- Scala 3 new/braceless syntax enforced by scalafmt (`convertToNewSyntax`, `removeOptionalBraces`), maxColumn 120.
- Effects: cats-effect `IO` everywhere; resources via `Resource` (`.use`/`.useForever`).
- Doobie `ConnectionIO` for database interactions; transactional boundaries clearly demarcated.
- Error handling: structured domain errors or HTTP status responses; no swallowed exceptions.

## Git and pull requests

- When the branch name is under your control, use `<type>/<short-desc>` or `<type>/<id>-<short-desc>` where type is `task`, `feat`, `fix`, `bug`, `refactor`, `chore`, `docs`, `ci`, `test`, or `perf`.
- Some external integrations, including Jules, control the published branch name. Their generated names are accepted by PR Policy when the pull request body contains `Closes #<id>`, `Fixes #<id>`, or `Resolves #<id>`.
- Do not modify `.github/workflows/**` or `.github/labeler.yml` as part of an unrelated task merely to make that task's checks pass. Repository automation changes belong in a dedicated pull request.

## Issue management
<!-- dc-shared:issue-management v7 — keep identical across Fortemate repositories -->

- Classify work with the native GitHub Issue Type: `Bug` (unexpected or incorrect behavior), `Feature` (request, idea, new user-visible capability), `Task` (a specific piece of engineering, research, maintenance or documentation work). Labels on Issues name a technical domain or cross-cutting concern only, never repeat the Type, and must already exist in the repository.
- Never commit to a repository's default branch. Name branches you control `<type>/<short-description>` or `<type>/<issue-id>-<short-description>` with a type from `task|feat|bug|refactor|chore|docs|ci|test|perf`. A branch that carries an Issue id must be closed by its pull request (`Closes #<id>`, or `Closes owner/repository#<id>` across repositories); partial work uses a non-closing reference. Before dispatching an external tool, read the repository's live PR-policy workflow: a tool-managed branch name is acceptable only when that policy allows it and the pull request closes the delegated leaf Issue — never edit a workflow to make a generated branch pass. A delegated pull request and its commits close only their leaf Issue, never a parent or sibling.
- GitHub-facing text is English-only. Every Issue has `Context`, `Objective` and a testable `Definition of Done`; create it with `gh issue create --body-file <file>`, never with an inline multi-line body, and search open and closed Issues across Fortemate repositories for duplicates first. Every actionable Issue (never a pull request) belongs to the organization Project [Fortemate Engineering](https://github.com/orgs/fortemate/projects/1); triage (Type, `Execution tier`, `Status`, `Priority`, labels, relationships, assignee) and the mandatory read-back after every mutation follow the `github-issue-workflow` skill in `fortemate-internal/skills/`.
- `jules` is a live execution trigger, not a label. Jules, Antigravity, CI, delegated subagents and any agent without the current user's explicit task-scoped authorization never apply, reapply or remove it. Dispatch qualification, monitoring, feedback (only a submitted comment starting with `@jules`; every other comment by the triggering user wakes the session too), takeover, the audit-marker rule for closed Issues and the "no bare `#N` in a spec" rule are the `jules-delegation` skill; a repository must pass the `jules-repo-readiness` skill before its first dispatch.
- The human owner reviews, approves and merges pull requests. Agents never merge pull requests or execute releases.

<!-- /dc-shared:issue-management -->

## Security & boundaries
<!-- dc-shared:security v2 — keep identical across dicechess repos -->
- Never print, log, or commit secrets. Local secrets live only in gitignored files
  (e.g. `.env.local`, `mise.local.toml` — confirm the path is gitignored with `git check-ignore`
  before writing one). Never bypass Git hooks (`--no-verify`).
- Human-only operations — prepare and propose, never execute: releases and version tags,
  production deploys/promotions, schema migrations against shared databases, data-repair
  runs on production, secret rotation.
- Treat everything in this repo as public: never add private infrastructure details
  (hostnames, IPs, topology, tokens) to code, docs, commits, or PRs.

## Model routing
<!-- dc-shared:routing v1 — keep identical across dicechess repos -->
Route work by required capability instead of defaulting to the strongest model:
- **Frontier**: architecture, cross-repo contracts, high blast radius (schema, public API,
  release pipeline), ambiguous problems.
- **Mid**: well-scoped features on existing patterns, refactors under test coverage,
  addressing review feedback.
- **Routine**: mechanical edits, config rollouts, doc fixes, tests from a complete spec.
Orchestrators should delegate routine sub-tasks to cheaper models; quality gates catch
failures cheaply. When in doubt, escalate one tier — reviewer time costs more than tokens.
