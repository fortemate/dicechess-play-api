---
title: REST Endpoints
description: The complete REST surface — identity, challenges, seeks, gameplay, public discovery, the leaderboard, and the strength report.
---

All routes are relative to `https://api.fortemate.com` (legacy `https://play-api.jc.id.lv`) and require `Authorization: Bearer <token>` unless marked **public**. See [Authentication & Identity](../../authentication/) for tokens and [Common error codes](../../authentication/#common-error-codes).

## Identity & tokens

Covered in depth under [Authentication & Identity](../../authentication/); summarised here.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/bot/anon` | Mint an anonymous token (public; `?name=` optional). |
| `POST` | `/bot/register` | Claim a durable identity. Token shown once. |
| `POST` | `/bot/token` | Rotate the token (registered only). |
| `GET` | `/bot/account` | Current identity. |
| `POST` | `/bot/ladder/join` · `/bot/ladder/leave` | Opt in/out of the rating ladder (registered only). |
| `POST` | `/bot/open-to-humans` · `/bot/open-to-humans/leave` | Opt in/out of the human catalog; the open call sets an optional description (registered only). |
| `GET` · `POST` | `/bot/capacity` | Read or declare how many games you will hold at once (registered only). |

## Concurrent games

`GET /bot/capacity` · `POST /bot/capacity`

How many games the server may seat you in at the same time. This is the counterpart of the per-turn window in [Webhooks](../webhooks/#how-long-you-have-to-answer): the server tells you how long you have to answer, you tell the server how much you can hold.

**A registered bot starts at one game at a time.** That default is deliberate — if you run on modest hardware, silence should not put you in three games at once and lose all of them on time. Raise it when you know you can serve more:

```json
{ "maxConcurrentGames": 4 }
```

Both routes answer with the full picture:

```json
{ "maxConcurrentGames": 4, "openToHumans": true, "ladderAllowance": 3, "activeGames": 1 }
```

- **`ladderAllowance`** is how much of your declaration the rating ladder may occupy. If you are also in the human catalog, the ladder leaves one slot free so a person can always reach you — so a catalog bot that declared 4 is paired into at most 3 ladder games. At a declaration of 1 there is nothing to reserve: the ladder may take the only slot, and a visitor is told the bot is busy.
- **`activeGames`** is what you are playing right now. It exists so a low limit is legible: a bot that is rarely paired can tell "I said one at a time and I'm playing it" apart from "the server is ignoring me".

Enforcement happens **when a game is seated**, never by holding a turn back inside a running game — a delayed delivery would burn your own clock. Once you are at your limit:

- the ladder skips you this round and pairs you later — a low limit changes how *often* you are paired, it does not exempt you from being rated;
- `POST /lobby/play-bot` answers `409` ("that bot is busy") instead of leaving a visitor at a board nobody will answer;
- an accept (`/bot/challenge/{id}/accept`, `/bot/seeks/{id}/accept`) answers `409` and the challenge or seek **stays open**, so you can take it once a game finishes.

Errors: `400` outside 1–32; `403` you are not a registered bot. Anonymous (`/bot/anon`) and server-configured house bots have no declaration and are unbounded.

## Challenges

### Create challenge

`POST /bot/challenge`

```json
{ "team": "house", "name": "greedy", "timeControl": { "Unlimited": {} }, "rated": true }
```

Responds `201` with the challenge, including `targetOnline` (advisory — an offline target can still discover it by polling). Errors: `400` challenging yourself; `429` too many pending. An unclaimed challenge expires after ~5 minutes.

`rated` is optional, defaults to `false`, and belongs to the **offer**: the target reads it in `GET /bot/challenges` and takes the challenge as offered or declines it. Any registered opponent can be played rated — there is no roster to get onto. If either side is anonymous (a `/bot/anon` identity), the game is quietly played casual rather than refused: unlike a public seek, a challenge is addressed to one bot, so there is nobody else the offer could be left open for.

:::caution[`rated` now needs a bounded control]
Rating is **per speed**, so a game whose length is unbounded belongs to no scale: with `timeControl` left out (this route still defaults to `Unlimited`) or set to `PerMove`, a `rated: true` challenge is offered and played **casual**. The offer you read back says so — `rated` comes back `false` — rather than promising a rating the game cannot produce. Name a `SuddenDeath` or `Fischer` control to play rated.
:::

### List pending challenges

`GET /bot/challenges`

Every pending challenge involving you. `in` entries are addressed to you (accept/decline by id); `out` are yours to watch. Recovers challenges you missed while offline.

```json
{ "in": [{ "id": "challenge-7", "challenger": { "Bot": { "team": "acme", "name": "rival" } }, "timeControl": { "Unlimited": {} }, "rated": true }], "out": [] }
```

### Accept / decline challenge

`POST /bot/challenge/{id}/accept` → `201 { "gameId": "game-uuid" }` (only the challenged bot). `409` if either side is at its [concurrent-game limit](#concurrent-games) — the challenge stays pending, so retry after a game ends.
`POST /bot/challenge/{id}/decline` → `200`.

## Seeks (meeting humans)

Also useful for testing your own bot against yourself — see
[Play Against Your Bot](../../play-your-bot/).

### Post a lobby seek

`POST /bot/seeks`

A standing public offer in the same lobby guests use — anyone, human or bot, may accept it.

```json
{ "timeControl": { "Fischer": { "initialSeconds": 180, "incrementSeconds": 2 } } }
```

`timeControl` is optional. Omit it and the seek gets **Fischer 600+10** (10+10): a human can sit down at this offer, and a clockless public game has nothing to end it but the 120-second anti-abandonment cap. Pass `{ "Unlimited": {} }` explicitly if you really want no clock.

`rated` is optional, defaults to `false`. Rated is your choice as the seek's creator, for any registered opponent — there's no curated roster to get onto. Posting the seek as an unregistered/anonymous identity silently drops it to casual, and so does an `Unlimited` or `PerMove` control: rating is per speed, and neither of those bounds how long a game lasts. The published seek carries the `rated` it will actually honour.

Responds `201 { "seekId": "seek-12", "secret": "capability-secret" }`. Hold the seek by polling `GET /lobby/seeks/{id}?secret=<secret>` — bot seeks expire after ~2 minutes without a poll; that same poll reports the match. Cancel with `DELETE /lobby/seeks/{id}?secret=<secret>`. Cap: 3 open seeks (`429` beyond).

### Accept a lobby seek

`POST /bot/seeks/{id}/accept` — accept an open seek from the public `GET /lobby/seeks` list. Colour is random; read it off [`GET /bot/games`](#list-my-games). Errors: `404` no such seek, `409` claimed first **or** a side is at its [concurrent-game limit](#concurrent-games) (the seek stays open), `400` your own seek, `403` the seek is rated and you're accepting anonymously (the seek stays open for a registered opponent instead).

## Gameplay

### Submit a dice seed

`POST /bot/game/{id}/seed`

Contribute this seat's entropy for the [provably-fair dice](../../provably-fair/). Submit once, as soon as the game starts and before the opening roll.

```json
{ "seed": "f3a1c0de9b8a7c6d" }
```

Responds `202` (fire-and-forget). A duplicate, too-late, or malformed seed is ignored (a malformed one may surface as a `Rejected` game-stream event). A seed is 16–256 characters.

### Submit turn moves

`POST /bot/game/{id}/move`

The turn's micro-moves in UCI, one per rolled die. Can optionally piggyback a draw offer (`offerDraw: true`) or accept a pending draw offer (`acceptDraw: true`).

```json
{ "moves": ["e2e4", "g8f6"], "offerDraw": false, "acceptDraw": false }
```

The verdict is **synchronous**:

- `200 { "applied": true, "version": 17, "reason": null }` — applied; `version` is the resulting `TurnPlayed`'s `v` (or game-ending event version).
- `409 { "applied": false, "version": null, "reason": "illegal turn" }` — refused, same reason the stream's `Rejected` carries (`"not your turn"`, `"illegal turn"`, `"no draw offer pending"`, `"game is over"`).
- `202` — fallback: no verdict within a few seconds (never blocks on a wedged game); treat as fire-and-forget and watch the stream.

A `TurnPlayed`/`Rejected`/`GameEnded` still broadcasts on the game stream regardless, so fire-and-forget bots can ignore the body.

### Accept / decline a draw offer

- `POST /bot/game/{id}/draw/accept` (or `/bot/game/{id}/draw/yes`) → `200` with `applied: true` (accepts pending draw, terminates game in Draw agreement before dice are revealed). Fallback `202` on lagged verdict.
- `POST /bot/game/{id}/draw/decline` (or `/bot/game/{id}/draw/no`) → `200` with `applied: true` (explicitly declines pending draw, reveals dice and enters move phase). Fallback `202` on lagged verdict.

### Resign

`POST /bot/game/{id}/resign` → `202`.

### List my games

`GET /bot/games`

Every live game you are seated in — the polling counterpart of `GameStart` and the **post-restart recovery path**.

```json
{ "games": [{ "gameId": "game-uuid", "seat": "White", "activeSeat": "White", "dicePending": true, "timeControl": { "Unlimited": {} }, "clocks": null, "version": 17 }] }
```

## Public discovery

### List live games

`GET /games` — **public.** Every live game on the node, both seats' public faces — the spectating surface. Sorted by `version` descending, capped at 50; `total` carries the real count. No legal-move tree (fetch the per-game endpoint).

```json
{
  "games": [{
    "gameId": "game-uuid",
    "players": { "white": { "kind": "Bot", "name": "house greedy" }, "black": { "kind": "Human", "name": null } },
    "timeControl": { "Unlimited": {} },
    "activeSeat": "Black", "dicePending": true, "clocks": null, "version": 17,
    "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1 nk"
  }],
  "total": 1
}
```

### Get legal moves

`GET /games/{id}/moves` — **public.** The full [legal-move tree](../../game-mechanics/#legal-moves) for the pending roll, never capped.

```json
{
  "version": 4,
  "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 NBK",
  "dicePending": true,
  "legalMoves": { "b1c3": { "g1f3": { "e2e4": {} } }, "e2e4": { "b1c3": { "g1f3": {} } } }
}
```

`version` and `dfen` tie the tree to the roll it answers. `legalMoves` is `{}` when `dicePending` is `false` or the roll is a forced pass. Errors: `404` unknown game.

### Get a game snapshot

`GET /games/{id}` — **public.** The polling read of a single game: the same `Snapshot.state` object the game stream sends on connect (documented under [Event Streams](../streaming/#snapshot)) — `dfen`, `activeSeat`, `dicePending`, `clocks`, `commit`, `players`, and, while `dicePending` is true, the inline `legalMoves`. `seed`/`clientSeeds` are `null` while the game is active and appear the instant it ends, on this endpoint exactly as on the live `GameEnded` event. Errors: `404` unknown game.

### Get game history (replay)

`GET /games/{id}/history` — **public.** The full replay of a **finished** game, independent of whether the live room has been evicted from memory: every turn's dice and moves, plus the dice-fairness reveal so anyone can [re-derive every roll](../../provably-fair/) and check it against the commitment published at creation.

```json
{
  "gameId": "game-uuid",
  "players": { "white": { "kind": "Bot", "name": "house greedy" }, "black": { "kind": "Human", "name": null } },
  "rated": true,
  "timeControl": { "Fischer": { "initialSeconds": 300, "incrementSeconds": 3 } },
  "result": 1,
  "termination": "king_captured",
  "finishedAt": "2026-07-30T21:05:00Z",
  "initialDfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
  "turns": [{ "turnNumber": 1, "activeColor": "White", "dice": [1, 1, 4], "moves": ["e2e4"], "fenAfter": "..." }],
  "fairness": { "commit": "sha256-hex", "seed": "server-seed-hex", "clientSeeds": { "white": "...", "black": "..." } }
}
```

`result` is white-POV (`1` white won, `-1` black won, `0` draw) and `termination` is one of `king_captured` / `timeout` / `resign` / `draw_agreement` / `aborted` — there is no requester identity here, so neither is reframed to a point of view the way a player's own games list is. `fairness.commit` is always present, and `seed`/`clientSeeds` are populated immediately — every game recorded here has already finished, so nothing is ever withheld. The response is cached `public, max-age=31536000, immutable`, since a finished game's history can never change again. Errors: `404` unknown game, or a finished game with no archive row (pre-archive history — not backfilled).

### Get a game's rating change

`GET /games/{id}/rating` — **public.** What one finished game did to each seat's rating, on the same Glicko-2 scale as the leaderboard.

```json
{
  "gameId": "game-uuid",
  "applied": true,
  "white": { "before": 1775.6714474976957, "after": 1797.2144251082318 },
  "black": { "before": 1601.5, "after": 1580.25 }
}
```

Rating is applied by a background batch, not at game end, so a game that has just finished answers `"applied": false` with both seats `null` for up to `RATING_INTERVAL_SECONDS` — poll it. **Do not compute the change by diffing a player's current rating against an earlier one**: the batch applies games one at a time, so between a game's start and its application any number of that player's other games can land, and a client that diffs typically reports the *previous* game's change — a negative delta after a win, which Glicko-2 cannot produce.

Once `applied` is `true` the answer is final, and that includes both seats being `null`: a game that moved nobody's rating (casual, a guest seat, an unregistered bot, self-play, a deleted account). Ratings are unrounded, like every other rating on this API; the delta is `after - before` and is deliberately not sent. The response names seats, never who sat in them — the faces come from `GET /games/{id}` or `/games/{id}/history`, under their own rules.

Caching follows the answer: an applied game is `public, max-age=31536000` (the row is written once and never revisited), a pending one is `no-store` so a poller can see it change. Errors: `404` unknown game, a game that has not finished, or a server without persistence.

## Leaderboard & bot profiles

Public, no `Authorization`. Both exist only when the server runs with persistence; an in-memory dev server answers `404`.

### Leaderboard

`GET /leaderboard?category=blitz`

One rating scale at a time. `category` is optional and one of `bullet`, `blitz`, `rapid`; omitted, you get **blitz** — the ladder's own control, and so the board this endpoint has always shown. Anything else is a `400`, deliberately: a fresh category is legitimately empty for a while, so a typo must not be indistinguishable from a speed nobody has played yet. The response echoes the `category` it answered on.

Registered bots whose rating on that scale has **converged** (RD ≤ 110), best first — where "best" is the **conservative estimate `rating − 2·rd`**, not the raw rating (ties go to the lower `rd`). The response still carries the raw `rating` and `rd`; only the ordering is conservative, so an entry whose rating a hot streak has inflated (visible as a raised `rd`) does not jump ahead of better-settled estimates. See [Rating & Ladder](../../rating/#how-the-board-is-ordered) for the reasoning. Provisional bots are counted internally but absent by policy — see the same page for what that means and why it can take longer than expected. `wins`/`draws`/`losses` count **rated, decided** games **in that category** only: a Blitz rating beside an all-speeds record would be two different questions answered as one. A bot that has never played the category is absent for the same reason a provisional one is — nothing has been measured yet.

```json
{ "category": "blitz", "leaders": [{ "rank": 1, "kind": "bot", "team": "acme", "name": "alice", "rating": 1720.5, "rd": 85.2, "onLadder": true, "games": 42, "wins": 30, "draws": 2, "losses": 10 }] }
```

A bot that left the ladder keeps its frozen rating and stays listed with `onLadder: false`.

### Bot catalog

`GET /lobby/bots`

Bots that opened themselves to human play via [`POST /bot/open-to-humans`](#identity), each with the rating summary its catalog card shows. Unlike the leaderboard, a **provisional** bot (RD > 110) is listed and flagged rather than hidden, so a freshly opened bot still appears. `description` is the bot's own blurb (may be `null`). `available` is `true` when the bot is below its declared [concurrent-game limit](#concurrent-games) at the moment the catalog was fetched — **advisory**: the SPA fetches this list once per visit rather than polling, so a bot's actual state can move before you click. The authoritative gate is still `wake` (below) and, ultimately, `play-bot`'s `409`.

```json
{ "bots": [{ "team": "acme", "name": "alice", "rating": 1720.5, "rd": 85.0, "provisional": false, "description": "aggressive + book", "available": true }] }
```

### Wake a catalog bot

`POST /lobby/bots/{team}/{name}/wake`

Before starting a game against a scale-to-zero bot, ping it to force a cold start and confirm it actually answers — the SPA calls this on catalog click, before offering the game-config panel. `404` for a name outside the catalog; otherwise `200` always, `alive` covering "no webhook registered" and "webhook didn't answer" alike (the caller only needs yes/no). `503` if the server runs without webhooks enabled. Rate-limited per IP.

If the bot is already at its declared [concurrent-game limit](#concurrent-games), the endpoint is **not woken at all** — probing costs an outbound request held up to the full per-turn window, and a busy bot's answer would be discarded anyway. The response instead reports `busy: true` alongside `alive: false`:

```json
{ "alive": true, "busy": false }
```

```json
{ "alive": false, "busy": true }
```

### Play a catalog bot

`POST /lobby/play-bot`

Starts a human-vs-bot game from the catalog:

```json
{ "guestId": "0a1b2c3d-...", "team": "acme", "name": "alice", "timeControl": { "Fischer": { "initialSeconds": 300, "incrementSeconds": 5 } }, "preferredColor": "White" }
```

`guestId` is the SPA's stable per-browser identity (a UUID, same convention as `POST /lobby/seeks`'s `creator`). It is the **anonymous fallback**: a caller signed in to a player account is seated from that session and `guestId` is ignored, so the field is required only without a session. `timeControl` is **mandatory** — a catalog game is never unlimited, `400` if it is. `preferredColor` (`"White"` / `"Black"`) is optional; omitted, the seat is random. `rated` is optional, defaults to `false` — any catalog bot can be played rated, but only for a signed-in caller; a guest always plays casual regardless of this field. Responds `201` with the caller's seat:

```json
{ "gameId": "g-42", "token": "seat-secret", "seat": "White" }
```

Errors: `400` bad body, a missing or invalid `guestId` with no session, or an unlimited time control; `404` a name outside the catalog; `409` the caller already has an unfinished catalog game (one at a time, for now) **or** the bot is at its [concurrent-game limit](#concurrent-games); `429` rate limit. No fresh liveness check runs here — `wake` already confirmed the bot moments earlier, and a bot that's gone dark since is handled the same way any registered-webhook bot going quiet mid-game is: the clock forfeits it.

### Bot profile

`GET /bots/{team}/{name}`

One registered bot's public card: rating summary, its aggregate record against every opponent it has played, and up to 20 recent games, newest first. Unlike the board, a **provisional** bot is visible here (flagged). `opponent` is a public face (never a raw id); `result` is from the profiled bot's point of view.

`ratings` carries one entry per scale the bot has **actually been rated on**, each with its own `provisional` flag and its own record — a bot can be settled at blitz and still finding its level at bullet, and one flag for both would be a lie about one of them. A speed it has never played is simply absent, not reported as `1500 ± 350`. The top-level `rating`/`rd`/`provisional`/`games`/`wins`/`draws`/`losses` describe **blitz**, the same default `GET /leaderboard` answers on.

`opponents` is one row per other registered bot (head-to-head) plus one collapsed row for every human/guest opponent combined — that collapsed row (`team`/`botName` both `null`) is the bot's **record vs humans**. Unlike the top-level `wins`/`draws`/`losses` (rated, decided games only — the ladder record), `opponents` counts every game, rated and casual alike: a guest game is always casual, so a rated-only tally would always read zero against humans. A bucket with no games simply has no row.

```json
{
  "team": "acme", "name": "alice",
  "rating": 1650.0, "rd": 95.0, "provisional": false, "onLadder": true,
  "games": 30, "wins": 20, "draws": 3, "losses": 7, "totalGames": 34,
  "ratings": [
    { "category": "blitz", "rating": 1650.0, "rd": 95.0, "provisional": false, "games": 30, "wins": 20, "draws": 3, "losses": 7 },
    { "category": "rapid", "rating": 1512.0, "rd": 180.0, "provisional": true, "games": 3, "wins": 1, "draws": 0, "losses": 2 }
  ],
  "opponents": [
    { "opponent": { "kind": "Bot", "name": "acme bob" }, "team": "acme", "botName": "bob", "games": 42, "wins": 22, "draws": 4, "losses": 16, "lastPlayedAt": "2026-07-16T12:00:00Z" },
    { "opponent": { "kind": "Human", "name": null }, "team": null, "botName": null, "games": 15, "wins": 11, "draws": 1, "losses": 3, "lastPlayedAt": "2026-07-15T09:30:00Z" }
  ],
  "recent": [{ "gameId": "game-uuid", "seat": "White", "opponent": { "kind": "Bot", "name": "acme bob" }, "result": "win", "rated": true, "termination": "resign", "finishedAt": "2026-07-16T12:00:00Z" }]
}
```

Errors: `404` no registered bot with that team/name.

## Strength report

Public, no `Authorization`. The precise, error-rate-bounded complement to the Glicko-2 leaderboard above — see [Rating & Ladder](../../rating/) for why a bot might want both numbers. Exists only when the server runs with persistence (`404` otherwise); before the rating batch has completed its first refresh (a fresh boot, or a server with rating updates disabled), both routes answer `503` rather than blocking on a synchronous build — the underlying report folds the entire game history and its ranking runs a four-figure bootstrap, too expensive to pay per request.

### Strength report

`GET /strength`

The whole cached report: every pairwise [SPRT](https://en.wikipedia.org/wiki/Sequential_probability_ratio_test) verdict, weighted over every eligible game for that matchup, plus a [Bradley-Terry](https://en.wikipedia.org/wiki/Bradley%E2%80%93Terry_model) pool ranking. `verdict` is `"AcceptH1"` (the `perspective` bot is stronger), `"AcceptH0"` (not stronger by the tested margin), or `"Continue"` (not enough data yet) — `Continue` is surfaced honestly rather than hidden or rounded into a claim. `elo` in `ranking` is **relative** (the pool's mean is 0 by construction, not the Glicko board's 1500-centred scale).

The report covers **one rating scale**, named in `category` — the ladder's, `blitz`. Games played at any other speed
are not folded in and are counted in `excludedRows` instead, alongside the rows that were never eligible (non-bot
seats, undecided games). Comparing a bot's 1+1 results with its 10+10 ones and calling the mixture its strength is
exactly the conflation per-category ratings exist to end.

```json
{
  "category": "blitz",
  "pairwise": [{
    "perspective": "acme/alice", "opponent": "acme/bob",
    "pairs": { "n0": 1, "n1": 0, "n2": 2, "n3": 4, "n4": 9 },
    "singles": { "losses": 0, "draws": 1, "wins": 2 },
    "result": { "llr": 1.8, "lower": -2.89, "upper": 2.89, "verdict": "Continue", "observations": 19 }
  }],
  "ranking": [{ "player": "acme/alice", "elo": 42.0, "ciLow": 10.0, "ciHigh": 74.0, "losVsNext": 0.91 }],
  "completePairs": 16, "singles": 3, "excludedRows": 2
}
```

### Bot strength profile

`GET /bots/{team}/{name}/strength`

Just the matchups involving one registered bot — the profile-page-sized slice of the report above, on the same scale and carrying the same `category`. `pairwise` uses the same shape. Errors: `404` no registered bot with that team/name.

```json
{ "team": "acme", "name": "alice", "category": "blitz", "pairwise": [{ "perspective": "acme/alice", "opponent": "acme/bob", "pairs": { "n0": 1, "n1": 0, "n2": 2, "n3": 4, "n4": 9 }, "singles": { "losses": 0, "draws": 1, "wins": 2 }, "result": { "llr": 1.8, "lower": -2.89, "upper": 2.89, "verdict": "Continue", "observations": 19 } }] }
```
