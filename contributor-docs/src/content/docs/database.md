---
title: Database Schema
description: The play-api Postgres tables, what each is for, and the deliberate absence of some foreign keys.
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

Under the showcase contract (ADR-005, #44, #47), rows carry an `origin` column (`showcase`,
`ladder`, `catalog`, `lobby`, `direct`, `legacy`, pinned by a check constraint) projected out of
the snapshot on every write, so reconciliation never decodes JSON. The partial index
`games_showcase_active_idx` on `(id) WHERE origin = 'showcase' AND status = 'active'` serves the
singleton table's startup question — "is exactly one showcase game live?" — through
`PgGameStore.activeShowcaseGameIds`. V5 backfilled the column from the snapshot's own `origin`
key where one was recorded, from the `ladder` flag otherwise, and to `legacy` for everything
that predates both; `GameSnapshot.effectiveOrigin` is that same rule in Scala, and it is the
one `GameRoom.restore` and the store's write path apply, so a legacy row resolves identically
everywhere. `origin` sits **beside** `ladder`, never instead of it: the flag keeps its exact
meaning for the rating batch's auto-park rule.

A showcase room is created only over a store that positively claims durability
(`GameStore.durable`) — the in-memory store does not, so `SHOWCASE_ENABLED=true` without
`PLAY_DB_URL` refuses every showcase creation (and says so at boot) rather than silently playing
in memory. Every version a showcase room publishes is committed here **first**: the room's
writer fiber halts on a failed write, retries it, and only broadcasts once the row is in
(`Durability.Required`; see [Architecture](/dicechess-play-api/architecture/)).

### `showcase_table` — the singleton table's one row (V6, #46)

The table's identity IS the row: `id` is constrained to `1`, so a second table cannot be recorded.
`next_human_color` is the durable half of ADR-005 §6 — it flips only inside a claim's committing
transaction, so a failed creation or a failed commit never consumes a colour, and it survives a
restart. `current_game_id` is the claim fence of §5: `ShowcaseStore.commitShowcaseClaim` moves it
from `NULL` to the new game in the same `UPDATE` that advances the colour, fenced on the colour the
human was seated on, so two processes cannot both record a game as current (defence in depth behind
the coordinator's mutex, not a substitute for it). It is cleared once the game's terminal
transaction has committed and the coordinator has observed it (§7 barrier 4).

Deliberately **no foreign key to `games`**: a stale pointer left by a crash between the terminal
commit and the clear is exactly what startup reconciliation repairs (`GET /showcase` opens only after
it has), and a cascade would hide that case. `adoptShowcaseGame` is the other repair: a live showcase
game found on boot whose row still names the resumed human's own colour as "next" had its claim
transaction lost, so the colour advances then — and only then, so a second reconciliation changes
nothing.

### `showcase_claims` — durable claim idempotency (V6, #46)

One row per processed `POST /showcase/claim`, whatever its outcome, keyed by `(actor_id,
idempotency_key)`. `request_hash` fingerprints the actor and body, which is what turns a key reused
for a different request into `409 idempotency_conflict`; `outcome` is `claimed` or `spectating`, and
a claimed row must carry `game_id` and `human_color` (a check constraint). Rows expire 24 hours after
creation (`expires_at`, defaulted from the database clock), an expired row is invisible to the lookup
and reads as a fresh claim, and the claim path prunes at most 128 expired rows per write by primary
key — so the table needs no sweeper and never blocks a live claim for long. Because that prune is
bounded, an expired row under a reused key may still be present when the fresh outcome is written:
both writes are `ON CONFLICT … DO UPDATE … WHERE expires_at <= now()`, replacing an expired row and
restarting its window while leaving a live one untouched (the coordinator never writes over a live
record — it replays it). A winning write that lands on nothing rolls the whole transaction back,
colour advance included. No foreign key to
`games` for the same reason `game_results` has none: the record must outlive the snapshot
retention prunes.

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
unique constraint so one token maps to exactly one identity. The same row carries the
`on_ladder` flag, the owner account (`owner_external_id`), and the human-facing catalog opt-in
(`open_to_humans`, `description`). Primary key is `(team, name)`. Ratings live in `bot_ratings`
per category.

`rated_for_humans` is a legacy column no longer selected by any query. The
curation model it embodied was superseded by #279: rated play is now a player choice at game or
seek creation, and the anti-farming guarantee moved into the rating batch itself
(`RatingBatch.applyGame`: a guest seat is never rated; an account vs a bot it **owns** never
counts). `Main.warnDeprecatedRatedForHumans` tells an operator who still has the old variable set.

`max_concurrent_games` is the bot's own declaration of how many games it will hold at once
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

V4 adds three webhook-control values to the identity row. `incarnation_id` distinguishes a bot
that was deleted and later re-registered under the same team/name; `ownership_generation`
invalidates an owner's pending setup on claim/release; and `webhook_revision` is the opaque,
non-ABA compare-and-swap token exposed as a strong ETag. Legacy Bearer-token webhook writes also
advance this state, so the old and staged APIs cannot silently overwrite each other.

### `bot_webhooks` — verified callback registration

Where the server POSTs on a bot's turn. A row exists only after the ownership handshake
succeeded (`verified_at`). Note the asymmetry with `bots`: the per-bot HMAC `secret` is stored
in plaintext because the server must read it back to sign requests, whereas the bearer token is
only ever compared as a hash. Deleting the bot cascades to its webhook.

`last_failure_at`/`last_failure_reason` (#225) are the one delivery a histogram alone can't
answer: not "how often does my bot fail", but "is it still failing, and since when". Both
nullable — a bot with a clean history, or no deliveries yet, has neither. Written only by a
genuine fault (`DeliveryOutcome.isFailure`); a usable move or a clean decline never overwrites
them. They live here rather than in a second one-row-per-bot table because `bot_webhooks` is
already exactly that shape.

`registration_id` (V4) names one active webhook generation. Create, URL replacement, and secret
rotation mint a new value; capability-only updates preserve it. Turn delivery carries the id it
read and takes the same per-bot PostgreSQL advisory fence as control-plane mutations before it
applies a response. A late response from a replaced/deleted registration is classified as
`stale_registration` and cannot submit a move or overwrite current-health fields.

### `bot_webhook_setups` — one pending candidate plus redacted tombstones

The staged owner/admin API never replaces a live webhook merely because a browser asked it to.
It first writes one pending candidate with its actor/authority generation, candidate URL and
secret, capabilities, expiry, activation attempt count, and short verification lease. A partial
unique index enforces one live setup per bot across every server instance. Activation leaves the
old registration live until verification-v2 succeeds and the replacement commits atomically.

An activation attempt is reserved atomically when the database grants the lease, before the
outbound verification request begins. It is not charged later only when a failure response happens.
The same lease transaction writes `webhook.activation.start` with the setup id and reserved attempt
number, so even a verifier process crash leaves a durable explanation for the consumed attempt.
At most five outbound attempts can therefore start, and a verifier process crash or abandoned
lease cannot refund an attempt and bypass the cap. A failed fifth attempt becomes an
`attempts_exhausted` tombstone immediately; an abandoned final lease is reconciled after its hard
expiry instead of granting a sixth attempt.

Terminal rows are deliberately destructive tombstones. Activation, cancellation, expiry,
authority invalidation, or attempt exhaustion clears candidate URL, secret, capabilities,
actor, lifecycle timestamps, attempt count, and lease material in the same update. Only the setup
id, target bot/incarnation, terminal status, and terminal time remain long enough to answer
deterministic `410 Gone` responses for 15 minutes. After that window the API treats the id as
unknown. The table's redaction check constraint makes a half-scrubbed terminal row invalid.

Expiry is reached two ways. Every per-bot control-plane transaction expires a stale candidate
before it reads or acts on the slot, which is what makes an abandoned candidate unusable. That
alone would leave the row physically present, secret included, until something happened to touch
that bot again — so the admin-authority heartbeat additionally sweeps expired pending setups,
bounded to 25 bots per tick and taking each bot's usual advisory fence, on every instance rather
than only the authoritative one. A TTL is not an authority decision, and a candidate secret should
not outlive its 15 minutes just because nobody came back for it.

Setup creation/expiry, lease expiry, terminal timestamps, and tombstone cleanup use PostgreSQL's
`clock_timestamp()` as the lifecycle authority. JVM timestamps contribute requested durations, not
security deadlines, so clock skew between API replicas cannot extend a setup, steal a live lease,
or retain a tombstone inconsistently.

### `webhook_admin_authority_generations` — fail-closed allow-list epochs

One row per SHA-256 generation of the parsed, sorted `PLAY_ADMINS` allow-list; no administrator id
is stored in this table. Every enabled API instance refreshes its generation at startup and every
5 seconds using the database clock. A row is live for 20 seconds. The store accepts admin webhook
authority only when exactly one generation is live, so old/new allow-lists overlapping during a
rolling deploy return `admin_required` instead of each authorizing its own pending setup.

The same database-wide advisory fence serializes heartbeat refresh and admin webhook transactions.
Once a sole generation remains, boot/background cleanup invalidates older-generation pending admin
setups, scrubs their candidate material, advances the bot revision, and writes the system audit in
the same transaction. Expired generation rows are only liveness markers and are deleted during
refresh.

### `webhook_verification_budgets` — cross-instance abuse limits

Fixed-window counters for setup creation by actor+bot and activation by both actor+bot and source
IP. Keys are one-way digests prepared by the service; raw account ids and IP addresses are not
stored here. Keeping the counters in PostgreSQL means horizontal replicas enforce one budget,
instead of each process granting a fresh allowance after a restart or load-balanced hop. Window
start, reset and the returned `Retry-After` are derived from the same database clock; a skewed API
instance cannot reset a window early or understate how long it remains closed.
Each row also stores its authoritative `window_expires_at`; a bounded, indexed cleanup removes at
most 128 expired keys on each consume without blocking live counters. This bounds retained source-IP
and actor+bot digests while preserving atomic fixed-window increments under concurrency.

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

`origin` (V5, ADR-005 §8, #47) is the same typed origin `games` carries, written in the terminal
transaction and indexed with `finished_at` (`game_results_origin_finished_idx`) so a later
bot-versus-human aggregate can read showcase results by time without parsing names or inferring
anything from a bot id. Eligibility for such a score is already in the row: a technical abort
has `result = NULL` and `termination = 'aborted'`, which `GameResultRow.sportingEligible` turns
into the one boolean a reader needs. The V5 backfill took `origin` from the still-existing
snapshot where there was one and from the `ladder` flag otherwise.

The four `*_rating_before` / `*_rating_after` columns (#296) record what the game did to
each seat, written by the rating batch in the same transaction as the `rating_applied_at` stamp
and the Glicko write — `before` is only knowable there, since the instant that transaction
commits the participant's own row carries `after`. They are what `GET /games/{id}/rating` serves.
Nullable and deliberately never backfilled: for games applied before rating tracking landed the pre-game states are
gone, and NULLs are also the honest record for a game the batch skipped (a guest seat, an
unregistered bot, self-play, a deleted account), which is stamped applied with no rating write at
all. Together with the stamp they make a `game_results` row write-**twice** rather than
write-once: one bookkeeping UPDATE, by a single writer, never revisited.

`category` (#335) is a **STORED generated column**, `rating_category(time_control)` computed
once at insert — the schema reference above cannot show that, so it reads as an ordinary nullable
`text`. It exists because the readers used to call that function inside their `WHERE` clauses, and
the function is not inlinable: its body ends in a sub-SELECT, and PostgreSQL only inlines a SQL
function whose body is a single expression. So it ran once per scanned row. The leaderboard reads
this table twice (one UNION half per seat), which on the production corpus meant ~338k calls and
turned a 123 ms aggregate into 28 s — past the 5 s query timeout, and `GET /leaderboard` answered
500 until the generated category column landed.

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
independent of both the analytics wire contract and snapshot retention. Access is by game id,
so the primary key is the serving index.

Under the showcase contract (ADR-005 §8, #47), **every ended showcase game is archived**,
technical aborts included — the one place the general rule "an aborted game has no history worth
keeping" is overridden, and only for `origin = 'showcase'` (an aborted lobby or ladder game is
still not archived, exactly as before). An aborted showcase row keeps the full moves, dice,
participants, time control and fairness material, but its payload says `"result": null` and
`"sporting_eligible": false`, so it can never be mistaken for a draw. The two V5 columns beside
the payload, `origin` and `sporting_eligible`, are a projection of the same two payload keys
(indexed with `finished_at` as `game_archive_origin_finished_idx`) so a reader can filter without
decoding JSON; `GET /games/{id}/history` serves both, with `result` as `null` for such a game.
Nothing else changes for an abort: the outbox still excludes it (analytics never sees a game
without a sporting result) and `game_results` still records it with `result = NULL`.

The write is the terminal transaction of ADR-005 §7: final snapshot, `game_results`, this row
and the outbox payload commit together, every part `ON CONFLICT DO NOTHING`, so a retried or
duplicated terminal save converges on one row of each. Retention never touches this table; it
does prune the aborted showcase game's operational snapshot once the archive row exists, the same
rule every other archived game follows.

### `users` — registered player accounts (#232, ADR-0017)

The account behind optional Google sign-in. Its `id` is a UUID **this server mints** at first
login — the stable half of the `user:<uuid>` external id that lands in `game_results` — so it
can never be forked or reassigned by anything a login provider controls. The nickname is the
only public-facing field; uniqueness is case-insensitive via a functional index on
`lower(nickname)` (no `citext` extension to install). `is_active` is a kill switch re-checked
on every authenticated request, because the session token is deliberately never trusted for
authorization state. Ratings live in `user_ratings` per category.

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
public response reads them, and per-category rating is the only rating system. The legacy single-scale
`glicko_*` columns on `bots` and `users` and the batch's dual-write were dropped in migration `V2` (#9).

The tables are **sparse**: a row exists only for a `(participant, category)` pair that has
actually been rated, and an absent row *is* the fresh state 1500 / 350 / 0.06. "Provisional in a category I have never played"
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
entirely from Blitz evidence. For tests that exercise incremental migration behaviors against historical states, `PgGameStoreSuite` stages migrations into a scratch schema and asserts their effects.

### `admin_actions` — durable bot-control audit (#273, ADR-004)

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
`catalog.describe`, `token.rotate`, `capacity.set`); `detail` carries the action's human-relevant
parameter — the description text of catalog writes or `before -> after` capacity limits — and never
secret material: `token.rotate` keeps it NULL. Operator-only, read with `psql`; never exposed on any
wire type.

V4 widens this stream to owner, administrator, bot and system actors and adds request id,
before/after slot revisions, before/after registration ids, and safe JSON metadata. Every staged
mutation and its audit row share one transaction, including lease reservation and failed verification.
Legacy bot-token writes receive a transaction correlation id when there is no HTTP request id.
Secret values, HMAC material, nonces, raw
transport exceptions, and resolved infrastructure addresses are forbidden from both `detail` and
`metadata`. Existing administrator actions remain readable; their historical `admin_user_id`
continues to identify the actor.

## Two deliberate design choices

**`game_results` and `game_archive` have no foreign key to `games`.** This is intentional, not
an oversight: both must outlive the snapshot. Retention prunes ended snapshots, and a foreign
key would either block that or cascade away the very history these tables exist to keep.

**Historical `pairing_id` remains in `game_results`.** Historical CRN-paired rows stay interpretable
by the strength report, while the `ladder` boolean marks ladder-origin games and new rows leave
`pairing_id` null. Migration `V2` dropped six single-scale `glicko_*` columns on `bots` and `users` (#9) after per-category ratings landed.

## Changing the schema

- Add a new numbered migration; never edit one that has been applied anywhere.
- Regenerate the reference with `mise run contrib-docs:schema` and commit it in the same pull
  request — CI applies the migrations to a throwaway Postgres and fails if the committed page
  is stale.
- Migrations against a shared database are an operator action, not a CI action.
- The four suites that touch Postgres run against Testcontainers, so a migration that fails to
  apply fails the build — see [Testing](/dicechess-play-api/testing/).
