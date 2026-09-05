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
<!-- dc-shared:issue-management v6 — keep identical across Fortemate repositories -->

- Use the native GitHub Issue Type as the canonical work classification:
  - `Bug` for unexpected or incorrect behavior.
  - `Feature` for a request, idea, or new user-visible capability.
  - `Task` for a specific piece of engineering, research, maintenance, or documentation work.
- Never commit directly to a repository's default branch. For branches whose names the agent controls, use `<type>/<short-description>` or `<type>/<issue-id>-<short-description>` with the preferred types `task|feat|bug|refactor|chore|docs|ci|test|perf`. The legacy `feature/` and `fix/` forms are compatibility aliases, not preferred names for new agent-created branches. If a branch name contains an Issue id, the pull-request body must close that exact independently actionable Issue. Before dispatching an external tool or opening its pull request, read the target repository's live PR-policy workflow. A tool-managed branch whose name cannot be controlled, including a Jules `jules-*` branch, is acceptable only when that live policy permits non-conventional issue-linked branches and the pull-request body closes the delegated leaf Issue. Never edit a workflow merely to make a generated branch pass; if the exception is absent, stop and report the repository-policy prerequisite.
- Do not apply `bug` or `enhancement` labels to Issues merely to repeat their Type. Keep those labels for pull-request release classification. On Issues, labels describe only a technical domain or cross-cutting concern, and only existing repository labels may be used.
- Applying or reapplying the `jules` label is a live execution trigger. On an open Issue the label denotes the current Jules delegation; on a closed Issue it may remain as historical execution metadata. By default, agents must never apply or reapply it. Exception: a top-level Codex or Claude Code orchestrator directly handling the current human request may apply or reapply `jules` only when that human is authorized to direct work in the target repository and explicitly authorizes Jules delegation for the current parent task. Jules, Antigravity, CI, delegated subagents, and agents without that task-scoped authorization must never apply or reapply `jules`, start Jules through the label, CLI, API, or another mechanism, or recursively delegate work.
- Removing `jules` is cleanup, not dispatch. During takeover of an open Issue, only the top-level primary orchestrator acting under the original task-scoped delegation authorization or an explicit recovery request from a current user authorized to direct work in the target repository may remove it. Separately, a top-level Codex or Claude Code agent directly triaging an already reopened Issue may remove a stale historical `jules` label without Jules-delegation authorization only when the latest application of `jules` predates the latest reopen event; if that ordering cannot be verified, do not remove it. A request to triage an already reopened Issue authorizes only this verified historical-label cleanup. This narrow cleanup exception grants no authority to apply or reapply `jules`, take over active Jules work, or delegate work. Jules, Antigravity, CI, delegated subagents, and all other agents must never remove `jules`.
- Before an authorized orchestrator applies `jules`, it must read the Issue back and verify that it is an open, independently mergeable leaf Issue with no blocker, competing owner or pull request, overlapping active work, or dependency on unmerged changes; belongs to Fortemate Engineering; has Status `Ready`, Execution tier `Routine`, and `spec:ready`; and contains self-contained Context, Objective, testable Definition of Done, Guards, Verification gates, Non-goals, and a bounded file-level blast radius. Apply `jules` last, read it back, monitor the Issue/session/pull request through completion, review the result, and take over stalled work. Never dispatch the same task through both the label and Jules CLI. Follow the `jules-delegation` skill when it is available.
- Actionable Jules feedback must be a submitted pull-request conversation or inline comment from the GitHub user who triggered the task, explicitly mention `@jules`, and be followed by acknowledgement and re-review of the resulting commit. A review body is not a Jules feedback channel. A delegated pull request and its commits may close only its leaf Issue, never its parent or sibling.
- Removing `jules` or using Jules CLI pull/teleport does not prove that the remote session stopped. Never write concurrently to a possibly active Jules branch. Continue the existing pull request only after terminal state is confirmed; otherwise recover verified work in an isolated branch and replacement pull request.
- After successful Jules work closes an Issue, retain `jules` as an audit marker. If that Issue is reopened, remove the historical label before triage under the reopened-Issue cleanup rule above; applying it again requires fresh task-scoped authorization and all dispatch checks, because a new label event starts a new session. During an authorized takeover of an open Issue, the permitted primary orchestrator must remove `jules` and record `outcome:escalated`.
- Before creating or updating an Issue, search relevant Fortemate repositories across open and closed Issues for semantic duplicates. Read the live Types, field options, labels, assignees, and relationships before mutation; never rely on cached IDs or invent metadata.
- GitHub-facing work items are English-only. Use the appropriate Issue Form when available, or `gh issue create --body-file <file>` for CLI creation; never pass a multiline body inline. Every Issue must contain `Context`, `Objective`, and a testable `Definition of Done`.
- Add every actionable Issue (never pull requests) to the organization Project [Fortemate Engineering](https://github.com/orgs/fortemate/projects/1).
- Use Project `Status` only for workflow state:
  - `Backlog` means triaged but not committed for active work.
  - `Ready` means sufficiently defined and available to start.
  - `In progress` means someone is actively working on it.
  - `In review` means implementation is waiting for review or validation.
  - `Done` means the Issue is closed.
- Set the Project `Execution tier` during triage:
  - `Routine` for a bounded, reversible task suitable for Jules or another low-cost agent.
  - `Mid` for a well-scoped task that needs a stronger coding agent with iterative supervision.
  - `Frontier` for architecture, public contracts, complex diagnosis, or other high-blast-radius work; human-led.
  - `Human-only` for releases, production operations, secrets, or legal decisions that must never be delegated.
  - `Decompose` for work too large to route as-is: split it into sub-issues, tier each, then re-tier or close the parent.
  - A blank value means the Issue has not been routed yet.
- Leave the organization `Priority` Issue field blank for normal work. Set it only to deliberately jump the queue: `Urgent` for an immediate incident, security problem, or release blocker; `High` for important or blocking planned work. Never replace organization fields with labels or duplicate Project fields.
- Triage establishes Type, Execution tier, applicable labels, Project membership, Status, and relationships (plus Priority only for queue-jumpers). Assign an Issue only when a person owns its next action, and assign the active owner before moving it to `In progress`; unassigned means agent pool or no current owner, not low priority.
- Use parent/sub-issue relationships for independently actionable decomposition, `Blocking`/`Blocked by` for hard ordering dependencies, and `Relates to` for non-blocking associations. If the live UI or API cannot create a relation, add an explicit typed cross-reference that preserves its semantics: `Parent:`, `Sub-issue:`, `Blocking:`, `Blocked by:`, or `Related:` followed by `owner/repository#<id>`. Do not simulate relationships with title prefixes, labels, or duplicate task lists.
- When a pull request targets the repository's default branch and fully completes an Issue, link it with `Closes #<id>` or `Closes owner/repository#<id>`. Use a non-closing reference for partial work or for a pull request targeting any other branch.
- After every Issue, pull-request, or Project mutation, read the item back. For an Issue, verify Type, Issue fields, labels, assignee, relationships, Project membership, and Status. For a pull request, verify base/head branches, draft and merge state, labels, assignees/reviewers, and linked Issues; pull requests are never Project items, and Issue Type and Issue fields do not apply. Report any metadata that the available API or UI could not set.
- The human owner reviews, approves, and merges pull requests. Agents never merge pull requests or execute releases.

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
