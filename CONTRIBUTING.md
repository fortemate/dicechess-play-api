# Contributing

Thanks for helping build the Dice Chess play server. This repo follows the same conventions
as `dicechess-engine-scala` and `dicechess-analytics`.

## Contributor License Agreement

Before your first pull request can be accepted, you must sign the project's
[Contributor License Agreement](CLA.md). Signing is self-service: append yourself to the `signatures`
array in [`.github/cla-signatures.json`](.github/cla-signatures.json) in the same pull request
(see [CLA.md](CLA.md), "How to Sign"). The `CI: CLA` status check fails until the
entry is present. Repository-owner and bot pull requests are exempt.

Why a CLA: the project follows an open-core model. The public repositories are
AGPL-3.0, and the project owner retains the ability to combine the code with
closed-source modules and to offer it under additional licenses. The CLA preserves
that option while your contribution always remains available under AGPL-3.0 — and
you keep the copyright to your work. A plain DCO (`Signed-off-by`) would not grant
relicensing rights, which is why a CLA is used instead.

## Prerequisites

- [mise](https://mise.jdx.dev) manages the local toolchain (Java 25, scalafmt, lefthook, …).
- SBT is the build tool; the dice-chess **engine** is resolved from GitHub Packages, which
  requires a GitHub token even for public packages. The build reads it from the `gh` CLI
  automatically (`gh auth login` once), or from `GITHUB_TOKEN` in CI.

## Setup

```bash
mise run setup      # install tools + register Git hooks (lefthook)
mise run check      # full validation: scalafmt + clean + coverage + tests (mirrors CI)
```

Other tasks: `mise run compile`, `mise run test`, `mise run format`, `mise run coverage`.

## Workflow

- **Branch naming** (enforced by CI): `<type>/<short-desc>`, optionally `<type>/<id>-<short-desc>` to
  link an issue. Types: `task` / `feat` / `bug` (issue-driven), `refactor` / `chore` / `docs` / `ci` /
  `test` / `perf` (issueless). E.g. `feat/12-game-room` or `refactor/extract-clock-policy`.
- **Link an issue**: required only when the branch carries an issue number — then the PR description
  must contain `Closes #<id>` (or `Fixes` / `Resolves`).
- **Formatting**: `scalafmt` runs in the pre-commit hook; CI fails on unformatted code.
- **Secrets**: the pre-commit hook scans staged diffs (betterleaks) — never commit tokens.
- **English** for code, commits, PR descriptions, and review comments.
- After opening a PR, request a review with an `@coderabbitai review` comment.

## Definition of Done

- Code compiles and tests pass: `mise run check`.
- New behaviour is covered by tests.
- Docs updated where relevant.
- CI is green and review feedback is addressed.
