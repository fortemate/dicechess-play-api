---
title: Database Schema
description: The eleven tables of the play-api Postgres schema, what each is for, and the deliberate absence of some foreign keys.
---

Persistence is **opt-in**: with no database URL configured the server runs fully in memory and
a restart drops everything. When a database is configured, Flyway applies the migrations in
`src/main/resources/db/migration/` at boot, and doobie does the querying.

:::note
This page explains *why* each table exists, which the SQL cannot. For the column-level
reference — every type, default, constraint, and index — see
[Schema Reference](/dicechess-play-api/reference/schema/), which is generated from the
migrations and therefore always current.
:::

## The tables

### `games` — resumable snapshots

One upserted row per game, active or ended, holding an opaque `jsonb` snapshot that is
self-sufficient to resume play. This is deliberately **not** event sourcing: the server
restores a room by decoding one snapshot, not by replaying a log. A partial index on
`status = 'active'` serves the boot-time resume scan, and a check constraint pins `status` to
`active` or `ended`.

### `outbox` — transactional delivery to analytics

The finished game's analytics payload, written **in the same transaction as the terminal
snapshot**. That is the whole point of the pattern: a game cannot end without its ingest row
existing, and the HTTP call is decoupled from the game's commit. `IngestDeliverer` polls a
partial index of rows that are undelivered, not permanently parked, and due; failures back off
via `attempts` / `next_attempt_at`, and a 4xx parks the row as `failed_permanently` with the
error preserved.

### `client_reports` — browser-submitted reports awaiting relay

The intake queue behind `POST /ingest/games` (#212): finished games the SPA played against its
**own in-browser bots** — games this server never hosted, reported by the client and forwarded
to analytics with the same deliverer semantics as `outbox` (backoff, 4xx parking). A separate
table rather than more `outbox` rows because the two must never mix: an outbox row is what this
server *played* (trusted, enqueued transactionally, FK to `games`), a client report is what a
browser *claimed* (forgeable, structurally validated at ingress, no `games` row to reference).
Nothing from this table reaches `game_results`, `game_archive`, or `/history`; the analytics
engine-replay gate stays the authoritative validator. The primary key is the payload's own
idempotency UUID, so a duplicate POST answers `200` without overwriting the first write.

### `bots` — durable identity plus ladder state

A bot's identity survives restarts here. Only a **hash** of the bearer token is stored, with a
unique constraint so one token maps to exactly one identity. The same row carries the Glicko-2
triple (`glicko_rating`, `glicko_rd`, `glicko_vol`, seeded at 1500 / 350 / 0.06), the
`on_ladder` flag, and the human-facing catalog opt-in (`open_to_humans`, `description`).
Primary key is `(team, name)`. The Glicko triple here is the single scale across every speed;
`bot_ratings` (V21) is the per-category one being grown beside it.

`rated_for_humans` (V15) is a dead column kept in place by the no-drop rule — no migration has
ever dropped a column, and none will start now. It is no longer selected by any query. The
curation model it embodied was superseded by #279: rated play is now a player choice at game or
seek creation, and the anti-farming guarantee moved into the rating batch itself
(`RatingBatch.applyGame`: a guest seat is never rated; an account vs a bot it **owns** never
counts). `Main.warnDeprecatedRatedForHumans` tells an operator who still has the old variable set.

`max_concurrent_games` (V12) is the bot's own declaration of how many games it will hold at once
— the counterpart of the per-turn window the server publishes. Its default of **1** is the whole
point rather than an incidental choice: absence has to select the conservative policy, because
the authors who most need the limit are the ones who never read about it, and because the
alternative is what production actually did — one bot seated in three simultaneous games, losing
them on time. Only registered bots have a row and therefore a limit; static (`PLAY_BOT_TOKENS`)
and anonymous identities are unbounded, which is required for the house bot that faces every
quickstart visitor at once.

Nothing counts games *here*. Enforcement lives in `SeatGuard`, which derives the current count
from live rooms in `GameRegistry` at the moment a game is seated: a persisted counter could leak
a slot and lock a bot out of every future game, failing silently — strictly worse than the
timeouts the column exists to prevent.

### `bot_webhooks` — verified callback registration

Where the server POSTs on a bot's turn. A row exists only after the ownership handshake
succeeded (`verified_at`). Note the asymmetry with `bots`: the per-bot HMAC `secret` is stored
in plaintext because the server must read it back to sign requests, whereas the bearer token is
only ever compared as a hash. Deleting the bot cascades to its webhook.

`last_failure_at`/`last_failure_reason` (V13, #225) are the one delivery a histogram alone can't
answer: not "how often does my bot fail", but "is it still failing, and since when". Both
nullable — a bot with a clean history, or no deliveries yet, has neither. Written only by a
genuine fault (`DeliveryOutcome.isFailure`); a usable move or a clean decline never overwrites
them. They live here rather than in a second one-row-per-bot table because `bot_webhooks` is
already exactly that shape.

### `bot_webhook_stats` — delivery telemetry (#225)

A bucketed histogram, not a row per delivery: one row per `(team, name, hour, outcome,
latency_bucket)`, upserted with `count = count + 1`. Bounded growth on purpose — at most a few
dozen rows per bot per hour (the outcomes actually seen times ~14 latency buckets), which is why
this needed no retention story of its own. `outcome` folds an HTTP status into the string itself
(`http_503`) rather than a nullable side column, so the whole classification stays one `NOT NULL`
text and fits cleanly into the primary key.

Recording is fire-and-forget, off the turn-delivery path entirely: `Webhooks.deliverTurn`
classifies the attempt and `tryOffer`s it to a bounded in-process queue; a separate drain loop
does the actual upsert. A slow or failing write only ever costs a dropped data point, never a
turn — this table's own INSERT latency is never on the same critical path a bot's clock is.

`GET /bot/webhook/stats` reads this table (plus the two `bot_webhooks` columns above) and does
its own aggregation in Scala (`WebhookStats.aggregate`, DB-free and unit-tested on its own) rather
than in SQL — one query fetches the wider 7-day window, and the 24-hour window is a Scala-side
filter over the same rows, so the read never has to hit Postgres twice.

### `game_results` — the queryable projection

Finished games, decoded out of the opaque snapshot so the ladder scheduler, the rating batch,
and the strength report can query by participant, result, rated flag, or ladder origin without
touching JSON. It carries the revealed `server_seed`, the `termination`, the `time_control`,
and `rating_applied_at` as the rating batch's work-queue marker. `result` follows a white-POV
convention — `1` white won, `-1` black won, `0` draw — enforced by application convention, not
by a check constraint.

The four `*_rating_before` / `*_rating_after` columns (V17, #296) record what the game did to
each seat, written by the rating batch in the same transaction as the `rating_applied_at` stamp
and the Glicko write — `before` is only knowable there, since the instant that transaction
commits the participant's own row carries `after`. They are what `GET /games/{id}/rating` serves.
Nullable and deliberately never backfilled: for games applied before V17 the pre-game states are
gone, and NULLs are also the honest record for a game the batch skipped (a guest seat, an
unregistered bot, self-play, a deleted account), which is stamped applied with no rating write at
all. Together with the stamp they make a `game_results` row write-**twice** rather than
write-once: one bookkeeping UPDATE, by a single writer, never revisited.

`category` (V22, #335) is a **STORED generated column**, `rating_category(time_control)` computed
once at insert — the schema reference above cannot show that, so it reads as an ordinary nullable
`text`. It exists because the readers used to call that function inside their `WHERE` clauses, and
the function is not inlinable: its body ends in a sub-SELECT, and PostgreSQL only inlines a SQL
function whose body is a single expression. So it ran once per scanned row. The leaderboard reads
this table twice (one UNION half per seat), which on the production corpus meant ~338k calls and
turned a 123 ms aggregate into 28 s — past the 5 s query timeout, and `GET /leaderboard` answered
500 until V22 landed.

Two fixes that look plausible and are not: rewriting the function to be inlinable still costs
17 s, because the regexes themselves are the expense; and an index on the expression buys nothing,
because essentially every rated row is blitz, so the predicate has no selectivity. Precomputing is
the only thing that helps, and it is also the honest shape — the table is append-only and
`time_control` never changes, so the category is a pure function of an immutable column. NULL means
an uncategorised control, exactly as the function answers.

`rating_category` itself stays: it generates this column, and `PgGameStoreSuite` runs it against
the Scala `RatingCategory` so the two implementations of one rule cannot drift.
`StoreQueryShapeSuite` guards the other half by reading the store's source — no query may mention
the function, because nothing at this repository's test data volume can catch it if one does.

### `game_archive` — immutable history

A sanitized, immutable record of a finished game: play's own durable representation of history,
independent of both the analytics wire contract and snapshot retention. Access is always by
game id, so the primary key is the only index.

### `users` — registered player accounts (#232, ADR-0017)

The account behind optional Google sign-in. Its `id` is a UUID **this server mints** at first
login — the stable half of the `user:<uuid>` external id that lands in `game_results` — so it
can never be forked or reassigned by anything a login provider controls. The nickname is the
only public-facing field; uniqueness is case-insensitive via a functional index on
`lower(nickname)` (no `citext` extension to install). `is_active` is a kill switch re-checked
on every authenticated request, because the session token is deliberately never trusted for
authorization state.

Rating state (V15) lives on this row too — `glicko_rating`, `glicko_rd`, `glicko_vol`, seeded 1500 / 350 /
0.06. The types and seeds are **identical to `bots`** on purpose: accounts and bots share ONE Glicko-2 scale,
which is what makes "who is strongest" answerable across both and what solves cold start, since
human-vs-human traffic is thin while bots are always available to be measured against. There is no
`on_ladder` counterpart — a person is not scheduled into games by the server. As on `bots`, this is
the all-speeds scale; `user_ratings` (V21) is its per-category successor.

### `user_identities` — login methods, keyed by `(provider, subject)`

Why a second table instead of a `google_sub` column: identity and account are different
lifecycles. The key is `(provider, subject)` — Google's stable `sub` claim — and **email is
deliberately a mutable attribute here, never an identity key**; an address change must not
fork the account (the lab/analytics predecessors keyed users by email and could not survive
one). A second provider later is a row, not a schema change. Rows cascade away with the
account.

### `user_guest_links` — anonymous history claimed by an account

`guest_id` is the primary key on purpose: one guest identity belongs to at most one account,
ever — the claim is first-writer-wins and terminal, mirroring the restore-code trust model
(possession of the id is the proof). History is **linked, not rewritten**: `game_results`
keeps its `guest:` external ids and merged-history reads union over the account's linked set,
so immutable records and already-delivered analytics rows are never touched. Links cascade
away with the account, freeing the guest id for a future claim.

### `bot_ratings` / `user_ratings` — one Glicko-2 state per speed (#280)

Bullet / Blitz / Rapid, keyed by estimated game duration. **These are the live scales**: every
public response reads them since phase 2, and the single-scale `glicko_*` columns on `bots` and
`users` are read by nothing.

Those columns are still *written* by the batch, though, and that is deliberate rather than an
oversight to tidy up. While they exist they have to stay current: rolling the release back would
otherwise resume from a scale with a hole in it exactly the size of the time it was deployed.
They are dropped — together with the batch's dual write — in a follow-up migration once the
cutover has been observed in production.

The tables are **sparse**: a row exists only for a `(participant, category)` pair that has
actually been rated, and an absent row *is* the fresh state 1500 / 350 / 0.06 — the same seeds
V4 and V15 gave the columns they will replace. "Provisional in a category I have never played"
is therefore expressed by the absence itself rather than by a copied number nobody measured, and
the leaderboard needs no special case: an unplayed category sits at RD 350, above
`Glicko2.ProvisionalDeviationThreshold`, so the existing visibility rule already hides it.

`play.rating_category(text)` is the SQL twin of `core/RatingCategory` — needed because the
backfill has to bucket the historical `game_results.time_control` text, which is the ADT's
`toString` form. Two implementations of one rule can drift, so `PgGameStoreSuite` runs both over
the same table of controls and asserts they agree; the function is kept permanently rather than
inlined into the backfill precisely so that test has something to call. The estimate is
`initial + 7 × increment` seconds per player — 7 is the **measured** expected moves per side in
dice chess (median 14 turns over 94,596 finished games), not chess's 40, and the boundaries are
Lichess's 180 s / 480 s. `Unlimited` and `PerMove` return NULL: neither bounds how long a game
lasts, so they belong on no scale.

Seeding is **games-based**: each participant's current rating seeds the one category its rated,
already-applied games actually live in (Blitz for every ladder bot — the ladder plays a single
control, 5+3), and no other. A participant whose history spans several categories has no honest
single answer, since its stored number is a blend, so the modal category wins; one with no
categorised rated history gets no row at all. The alternative on the table — copy the number into
all three with RD reset — was passed over: it would publish a Bullet and a Rapid rating derived
entirely from Blitz evidence. Because the backfill can only ever run against history the suite's
own database does not have, `PgGameStoreSuite` stages the real migration chain into a scratch
schema, plants a history at V20, and applies V21 to it.

### `admin_actions` — who did what to somebody else's bot (#273)

One row per action an administrator performs on a bot through the admin surface — the answer
to "who retired this bot" (or rotated its token, or rewrote its card) when the bot's own
author cannot act because the registration token is gone. The row is written **in the same
transaction as the mutation it records**, the `nickname_history` shape: a crash can never
leave an action applied but unrecorded, and no row is written when the action found no bot —
the table records what happened, not what was attempted.

No foreign key to `users` or `bots`, deliberately: the table exists to answer questions after
the actors are gone (an admin account deleted via `DELETE /auth/me` still has to be nameable
as "who did this"), and a cascade would erase exactly the history an audit exists to keep.
`action` is a short verb id (`ladder.join`, `ladder.leave`, `catalog.open`, `catalog.close`,
`catalog.describe`, `token.rotate`); `detail` carries the action's human-relevant parameter —
the description text of the catalog writes — and never secret material: `token.rotate` keeps
it NULL. Operator-only, read with `psql`; never exposed on any wire type.

## Two deliberate design choices

**`game_results` and `game_archive` have no foreign key to `games`.** This is intentional, not
an oversight: both must outlive the snapshot. Retention prunes ended snapshots, and a foreign
key would either block that or cascade away the very history these tables exist to keep.

**V10 drops nothing, despite its name.** The migration is called `drop_crn_pairing`, but
`pairing_id` and its partial index remain — historical CRN-paired rows must stay interpretable
by the strength report. What V10 actually does is *add* the `ladder` boolean that now marks
ladder-origin games, taking over the role `pairing_id` used to imply. New rows leave
`pairing_id` null. Across V1–V11, no column is ever dropped.

## Changing the schema

- Add a new numbered migration; never edit one that has been applied anywhere.
- Regenerate the reference with `mise run contrib-docs:schema` and commit it in the same pull
  request — CI applies the migrations to a throwaway Postgres and fails if the committed page
  is stale.
- Migrations against a shared database are an operator action, not a CI action.
- The four suites that touch Postgres run against Testcontainers, so a migration that fails to
  apply fails the build — see [Testing](/dicechess-play-api/testing/).
