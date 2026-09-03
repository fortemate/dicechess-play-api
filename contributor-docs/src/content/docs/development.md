---
title: Development Setup
description: Toolchain, the commands that mirror CI, the quality gates a pull request must clear, and the traps that produce a false green.
---

## Before anything else: GitHub auth

```bash
gh auth login
```

The engine artifact resolves from GitHub Packages, which requires authentication **even for
public packages**; `build.sbt` reads the token via `gh auth token`. If you skip this, the
failure looks like a broken build rather than a missing credential:

```text
unresolved dependency: com.fortemate#dicechess-engine...
```

That signature always means auth, never a broken build.

## Toolchain

Tools are pinned in `mise.toml`: Java temurin-25, native scalafmt, lefthook, betterleaks, actionlint, gh,
jq. Install everything and register the Git hooks with:

```bash
mise run setup
```

Docker is needed by the five Postgres suites and by `mise run contrib-docs:schema`. On Rancher Desktop, export
`DOCKER_HOST=unix://$HOME/.rd/docker.sock` and `TESTCONTAINERS_RYUK_DISABLED=true` first —
without them the test run hangs at container startup rather than failing.

## Commands

| Command | What it does |
| --- | --- |
| `mise run compile` | `sbt compile Test/compile` |
| `mise run test` | `sbt "testOnly *"` (full suite, needs Docker for five suites) |
| `mise run check` | **The CI mirror**: shutdown, disposable cache, scalafmtCheckAll, clean, coverage, testOnly *, coverageReport |
| `mise run format` | `sbt scalafmtAll` |
| `mise run run` | Start the server on `:8080` |
| `mise run contrib-docs:install` | Install dependencies for this site (`npm install`) |
| `mise run contrib-docs:dev` | This site, locally (`npm run dev`) |
| `mise run contrib-docs:build` | Build static output for this site (`npm run build`) |
| `mise run contrib-docs:schema` | Regenerate the schema reference from the migrations |
| `mise run docs:install` | Install dependencies for the Bot API site (`npm install`) |
| `mise run docs:dev` | The Bot API site, locally (`npm run dev`) |
| `mise run docs:build` | Build static output for the Bot API site (`npm run build`) |

For a targeted, Docker-free run: `sbt "testOnly dicechess.play.game.*"`.

## Code conventions

- Scala 3 "fewer braces" throughout: colon syntax for template bodies and lambdas, no end
  markers. Formatting is law — scalafmt decides, `maxColumn` is 120.
- `-Werror -Wunused:all -deprecation -feature -explain`. One unused import fails the build.
- Pure Typelevel FP in cats-effect `IO`. No nulls, no exceptions for control flow.
- Comments explain **why**, not what. There are zero `TODO`/`FIXME` comments in `src/` — keep
  it that way; encode the decision as a rationale comment instead.
- Two-space indent, per `.editorconfig`.

## Quality gates

- `mise run check` passes locally. It mirrors CI exactly.
- Backend CI is **path-filtered** to `src/**`, `build.sbt`, `project/**`, `.scalafmt.conf`, `Dockerfile`,
  `mise.toml`, `sonar-project.properties`, and `.github/workflows/ci.yaml`.
- All pull requests run `CI: PR Policy` and `CI: CLA` regardless of modified paths. Pull requests modifying
  `contributor-docs/**`, Flyway migrations, or related scripts trigger `CD: Deploy Contributor Docs`, which
  runs the schema-drift gate (`mise run contrib-docs:schema` + git diff check) and an Astro build. A pull request
  touching only other workflow files gets no backend CI run; validate those with `gh workflow run`.
- Automated code review by CodeRabbit is enabled but does not start automatically on PR creation.
  To request a review, comment `@coderabbitai review` on your pull request.
- SonarCloud imports the scoverage report. No coverage minimum is enforced — which is not a
  licence to skip tests.
- Branch naming and `Closes #n` linking are validated automatically; external contributors must
  sign the CLA.

## Traps that produce a false green

**Warm-cache formatting.** On a warm `target/`, sbt-scalafmt's incremental cache can skip a
genuinely misformatted file, so a local check passes while CI — a fresh checkout — fails. Check
after a `clean`, or confirm with the native `scalafmt --test <files>`.

**Untracked Scala files.** `sbt scalafmtAll` skips them. `git add` a new `.scala` file *before*
running the formatter, or the native pre-commit hook rejects the commit.

**Three scalafmt toolchains.** `.scalafmt.conf`, the native CLI pinned in `mise.toml` (used by
the hooks; it does not auto-dispatch by version), and sbt-scalafmt must be bumped together.

**Never pipe test output through `grep` or `head`** — it masks the exit code.

## Things `build.sbt` does on purpose

- Force-bumps testcontainers-java / docker-java and sets `-Dapi.version=1.43`: the wrapper's
  pinned docker-java speaks an API version modern daemons reject.
- `ThisBuild/version` is frozen at `0.1.0-SNAPSHOT`. Real versions come only from git tags via
  the CD workflow. Do not bump it.
- The Dockerfile pins `eclipse-temurin:25-*-noble`; the unsuffixed tag drifted to a base image
  whose coreutils break the launcher. The `JAVA_OPTS` flags in Dockerfile and compose are
  required by cats-effect on Java 25.
- Docker builds pass the GitHub token as a BuildKit secret so it never lands in a layer — never
  convert it to a build argument.

## Pull requests

Branch as `<type>/<short-desc>` or `<type>/<id>-<short-desc>`, where type is one of
`task`, `feat`, `bug`, `refactor`, `chore`, `docs`, `ci`, `test`, `perf`. Run `git status`
before editing so unrelated work does not bleed into your commit, and stage files by name —
`git add -A` is forbidden. Commits, pull request descriptions, and review replies are English
only. Split large work into small, reviewable pull requests.

Releases, production promotion, schema migrations against shared databases, data repair, and
secret rotation are **operator actions**: prepare and propose them, never execute them.
