---
title: Rating & Ladder
description: What Glicko-2 rating, RD, and volatility actually mean, and why a bot can stay "provisional" for a long time.
---

Every on-ladder bot carries three numbers, not one: a **rating**, a **deviation** (RD), and a **volatility**. This page explains what each means, how a finished game moves them, and why your bot might be actively playing — and even winning some games — without showing up on the public [leaderboard](../reference/rest/#leaderboard).

## One rating per speed

There is no single "your rating" any more: a bot carries an independent Glicko-2 state for each of **Bullet**, **Blitz** and **Rapid**, and a game moves only the one its own time control belongs to. Being strong at 10+10 and being strong at 1+1 are different claims, and one number for both is wrong about at least one of them.

Which category a control falls into is its **estimated duration per player**: `initial + 7 × increment` seconds for a Fischer control, plain `initial` for sudden death. **Bullet** is under 180 s, **Blitz** under 480 s, **Rapid** at or above.

| Control | Estimated | Category |
| --- | --- | --- |
| 1 + 1 | 67 s | Bullet |
| 3 + 2 | 194 s | Blitz |
| 5 min, 5 + 3, 5 + 5 | 300–335 s | Blitz |
| 10 min, 10 + 5, 10 + 10 | 600–670 s | Rapid |
| 15 + 10 | 970 s | Rapid |

The multiplier is measured, not borrowed: chess uses 40 expected moves per side, but over **94,596 finished games** in the production archive a dice chess game runs a median of 14 turns — 7 moves per side. A king falls quickly.

**`Unlimited` and `PerMove` belong to no category, and games under them are always casual.** Neither bounds how long a game lasts, so there is no scale for the result to land on. Asking for `rated` with one of those gets you a casual game and a `rated: false` you can read back — never a promise the game cannot keep. Clockless bot-vs-bot corpus runs keep working exactly as before; they simply stop claiming to be rated.

The ladder plays a single control (5 + 3, Blitz), so that is the scale [`GET /leaderboard`](../reference/rest/#leaderboard) answers on when you do not name one, and the scale the strength report is built from.

## The three numbers

Dice Chess uses [Glicko-2](http://www.glicko.net/glicko/glicko2.pdf) (Glickman), not plain Elo. Elo gives you one number; Glicko-2 gives you three, because a rating built on 3 games and a rating built on 300 games shouldn't be trusted equally:

| Field | Meaning | Fresh bot |
| --- | --- | --- |
| `rating` | Your estimated strength, on the familiar ~0–3000 scale. | `1500.0` |
| `rd` | **Rating deviation** — how *uncertain* that estimate still is. Lower means more confident. | `350.0` |
| `volatility` | How erratic your results have been relative to what your rating predicts. Not on the public wire, but it's what keeps `rd` from converging when results are noisy. | `0.06` |

A rating without its `rd` is close to meaningless: `1500 ± 30` and `1500 ± 350` are very different claims about the same number. The `±` is the point — Glicko-2 tracks it honestly instead of pretending every rating is equally solid.

## How one game moves the numbers

The ladder treats **every finished, rated game as its own one-game rating period** for both participants — a deliberate choice, revisited and kept when the board's ordering changed (#169): batching games into Glickman's suggested 10–15-game periods would damp streak wiggle a little, but at the cost of ratings that update in lurches, and the wiggle is handled where it is felt instead — by [how the board is ordered](#how-the-board-is-ordered) and by a low volatility constant (τ = 0.3, the bottom of Glickman's suggested range, so a run of dice-driven upsets moves the volatility — and through it the rating — less than a "the rating must be wrong" reading would). An offline batch — not the game server itself — recomputes ratings roughly once a minute from the finished-game backlog, so a result lands on your rating shortly after the game ends, not instantly.

Each update pulls the rating and shrinks (or doesn't shrink) `rd` depending on how *surprising* the result was, given both bots' ratings at the time:

- **Win as the underdog, or lose as the favourite** — the rating moves a lot, `rd` shrinks. The result was informative.
- **Win as the favourite, or lose as the underdog** — the rating barely moves. That's what everyone expected.
- **A run of results your rating didn't predict at all** (winning far more, or far less, consistently than expected) — `volatility` rises, which pushes `rd` back up on later updates. The system is telling you the estimate is *less* trustworthy than the raw game count would suggest.

That last point matters more than it sounds: **`rd` does not shrink purely from playing more games.** A bot that loses almost every game in a stable, predictable way converges quickly, because every result confirms the estimate. A bot whose results are erratic — say, a random-move bot that mostly loses but occasionally, unpredictably wins — can keep a high `rd` for a surprisingly long time, because those upsets are exactly the "my rating didn't see that coming" signal Glicko-2 reacts to. Games played is not the same as certainty gained.

There is deliberately **no idle-time RD inflation** here (the part of Glicko-2 where a rating gets less certain just from not playing): on-ladder bots are paired continuously by the server, so there's no meaningful idle time to model.

### Asking what one game did

[`GET /games/{id}/rating`](../reference/rest/#get-a-games-rating-change) answers that for a single finished game: both seats' rating before and after, exactly as the batch recorded them.

Reach for it instead of the obvious-looking alternative — read your rating, play, read it again. Since the batch applies games one at a time and up to a minute late, any *other* game of yours that lands in between is folded into that difference: play two games back to back and the subtraction reports the earlier one, which can show a rating drop after a win. Poll the `applied` flag — `false` means the batch has not reached this game yet, and once it is `true` the answer is final.

Final includes both seats coming back `null`, which means the game moved nobody's rating. That happens when:

- the game was **casual** — rated is the player's own choice at game creation, and only rated games reach the batch;
- the control was **`Unlimited` or `PerMove`** — see [One rating per speed](#one-rating-per-speed): such a game is casual by construction;
- a seat was an **anonymous guest** — resetting a guest identity is free, so rating it would be free rating too;
- an account played **a bot it owns** — that is farming with extra steps, and it is the one thing the Glicko-2 scale itself cannot defend against;
- both seats were **the same identity** (self-play), which carries no rating information at all.

## How the board is ordered

The public [`GET /leaderboard`](../reference/rest/#leaderboard) sorts by the **conservative estimate `rating − 2·rd`**, not by the raw rating (ties go to the lower `rd`). The board still *shows* `rating ± rd`; only the order is conservative.

Why: raw-rating ordering ignores the uncertainty printed right next to the number. A converged bot on a lucky streak gains rating *and* — because a streak is exactly the "my rating didn't see that coming" signal — volatility, which pushes `rd` back up. Sorting by `rating − 2·rd` ranks each bot by what its rating is *at least* (roughly the 97.7% one-sided bound, the same idea as TrueSkill's conservative μ − kσ), so the streak-inflated entry stops jumping better-settled ones, and falls back into place as its `rd` re-converges — without the displayed numbers changing meaning.

The board is a live standing, not a verdict: "is bot A actually stronger than bot B" has its own instrument — the [strength report](#a-more-precise-alternative-the-strength-report) below.

## Provisional bots and the public board

A rating is **provisional** while `rd > 110` — Glickman's own convergence threshold. A fresh bot starts provisional at `1500 ± 350` and typically settles within a few dozen games, *if its results are reasonably consistent*.

- The public [`GET /leaderboard`](../reference/rest/#leaderboard) lists **only converged** (non-provisional) bots, ordered as [above](#how-the-board-is-ordered). This is a deliberate policy, not a bug — showing every wildly-uncertain fresh rating on the same board as settled ones would make the board noisy and misleading.
- Your own [`GET /bots/{team}/{name}`](../reference/rest/#bot-profile) profile shows you regardless, flagged `"provisional": true`, so joining the ladder never feels like a black hole while you wait to converge.
- `onLadder: false` means the bot left (`POST /bot/ladder/leave`, or [auto-park](#auto-park-when-your-bot-stops-answering)) — its rating is **frozen**, not deleted, and it still appears on the leaderboard (if converged) or the profile endpoint (always) at whatever it was when it left.

If your bot has played plenty of games and is still provisional, check its win/loss pattern on its profile before assuming something is broken: a bot with a genuinely volatile result pattern (frequent upsets in either direction) converges slower than one that consistently loses — or consistently wins — no matter how many games it plays.

## Joining and leaving

Covered in [Authentication & Identity → Joining the rating ladder](../authentication/#joining-the-rating-ladder): `POST /bot/ladder/join` / `POST /bot/ladder/leave`, both registered-bot only. That page is the "how do I opt in" companion to this "what do these numbers mean" one.

## Auto-park: when your bot stops answering

On-ladder bots are paired continuously, whether or not they are actually running. A bot that goes offline still gets paired every minute and loses every game on the clock — so the server parks it for you.

**The rule:** lose your last **four consecutive ladder games** on the clock (`timeout`) and your bot is set to `onLadder: false`.

How long that takes is not a fixed number of minutes: a game against a bot that never answers runs until that bot's own clock expires — five minutes on the ladder's default 5+3 — and games run in parallel, so the total depends on how often the scheduler happens to pick your bot out of the pool. Expect tens of minutes of being unreachable, not seconds.

The threshold forgives a game or two on purpose: one bad game must not park a bot that is otherwise healthy, so the streak needs a genuine run of them.

What does **not** count:

- **Normal losses.** Only `timeout` terminations feed the streak. A weak bot that answers every move and loses by `king_captured` is never parked, however badly it is doing — being outplayed is not being offline.
- **Casual and challenge games.** Only games the ladder scheduler itself started are counted, so a timeout in a game you started yourself can't park you.
- **Any answered game.** One real result in the window breaks the streak.

Parking is exactly what `POST /bot/ladder/leave` does: pairing stops, your rating **freezes** where it is, and the bot stays visible with `onLadder: false`. Nothing is deleted and nothing is penalised — the point is to stop the bleeding, for your rating *and* for everyone banking free wins against you.

**There is no auto-rejoin.** Coming back is an explicit `POST /bot/ladder/join` once your bot is actually running again; an automatic timer would just send a still-offline bot back out to lose another four games.

:::tip[Leave before you go offline]
If your bot runs on a laptop, a dev machine, or anything you shut down at night, call `POST /bot/ladder/leave` on the way out and `POST /bot/ladder/join` when you are back. Auto-park is the safety net, not the intended flow — using it means eating four timeout losses every time.
:::

A genuinely slow bot that keeps flagging on the ladder's 5+3 clock will eventually be parked too. That is intended: at that time control it is not competitive, and the fix is a faster move loop or a [stream/webhook](../connection-modes/) instead of a slow poll.

## A more precise alternative: the strength report

Glicko-2 is a good *live standing* — a number that updates quickly enough to pair bots sensibly and to show on a leaderboard. It is a weaker instrument for the sharper question "is my bot actually stronger than that other one, and how sure can I be?": per-game variance in a dice game is large, so `rd` stays wide and converges slowly, and the ladder pool is small and closed — Glicko measures standing *within* the pool, which can drift as a whole.

[`GET /strength`](../reference/rest/#strength-report) answers that sharper question directly, for every pair of registered bots with enough shared history: a [Sequential Probability Ratio Test](https://en.wikipedia.org/wiki/Sequential_probability_ratio_test) verdict — `"AcceptH1"`, `"AcceptH0"`, or an honest `"Continue"` when there simply isn't enough evidence yet — weighted per game between the two bots, plus a pool-wide [Bradley-Terry](https://en.wikipedia.org/wiki/Bradley%E2%80%93Terry_model) ranking with bootstrap confidence intervals. [`GET /bots/{team}/{name}/strength`](../reference/rest/#bot-strength-profile) narrows that to one bot's own matchups.

The report is refreshed on the same batch cadence as ratings, not per request, so it can lag a live game by up to the batch interval — and it answers `503` rather than a guess before the first refresh completes.
