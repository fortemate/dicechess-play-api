# Ladder Strength Methodology

How the bot ladder measures "which algorithm is stronger", and what each number means. Two
complementary instruments run over the same `game_results` corpus:

| Instrument | Question | Where |
| --- | --- | --- |
| **Glicko-2** (continuous) | "roughly how strong is each bot right now?" | rating batch → `bots.glicko_*` → public `/leaderboard` |
| **SPRT + Bradley-Terry** (batch report) | "is B stronger than A, with controlled error rates?" | `mise run ladder:report` (owner-facing, read-only) |

## Glicko-2 configuration (#119, revisited in #169)

Three linked decisions, evaluated together because they move the same numbers:

- **Board ordering — conservative.** The public `/leaderboard` (both populations, and the routes'
  merged re-sort) orders by `rating − k·RD` with **k = 2** (`Glicko2.ConservativeOrderingK`), ties
  to the lower RD. Raw-rating ordering let a converged bot on a hot streak jump better-settled
  entries, because a streak inflates the rating *and* (via volatility) the RD that raw ordering
  ignored. k = 2 is the conventional ~97.7% one-sided lower bound, TrueSkill's μ − kσ idea with RD
  as the rating's standard deviation; k = 1 (the printed `rating − RD` bound) was considered and
  passed over as too weak for exactly the motivating case, where the streaker's raw-rating lead can
  exceed one RD gap. A constant, not a knob: there is one public board, and a per-deployment k
  would make its order mean different things on different servers.
- **Rating periods — one game each, kept.** Glickman suggests 10–15 games per period; #169 weighed
  batching as a wiggle damper and kept one-game periods. The batch's crash-safety rests on
  applying a game to both participants in one transaction with that game's `rating_applied_at`
  stamp; per-player N-game periods close at different times for the two sides of one game, so
  honouring them needs per-side stamps or a period ledger — schema and lag for what is a
  presentation problem, already treated by the ordering above. Verdict-grade comparisons were
  never the ladder's job (that is SPRT/Bradley-Terry below).
- **τ = 0.5 → 0.3** (`Glicko2.DefaultTau`). Glickman's range is 0.3–1.2, smaller where improbable
  results are expected noise; dice-driven upsets are exactly that, and one-game periods are the
  setting most prone to volatility spikes, so the bottom of the range. Evaluated jointly with the
  period decision: had batching been adopted, each update would pool more evidence and a larger τ
  would have been defensible.

**Migration/backfill: none.** The ordering is read-time only. The τ change applies to future
updates only — `bots.glicko_*`/`users.glicko_*` state evolved under τ = 0.5 remains a valid
Glicko-2 state, since τ constrains how fast volatility may *change*, not what values are legal.
Recomputing the whole ladder from `game_results` under τ = 0.3 was considered and rejected: there
is no replay machinery to build and maintain for it, the drift it would correct is within the RD
the board already prints, and a visible one-off reshuffle of every public number is precisely the
churn this change exists to reduce.

## Common random numbers (CRN) — historical only, dropped in #190

The scheduler used to start ladder games as **mirrored pairs**: two games between the same two
bots, colours swapped, sharing one dice sequence (`pairing_id` tied them together). Whatever luck
the dice handed White in game 1, they handed the *other* bot in game 2 — so over a pair, dice luck
and colour advantage cancelled, and what remained was skill difference. This was the
Fishtest/Stockfish testing methodology adapted to dice chess, where the dice make single games far
noisier than regular chess.

It was removed (#190) once measurement showed the actual variance reduction was modest (~16% on
2247 production pairs — far short of a real pentanomial's) while the shared dice sequence leaked
each game's future rolls to anyone seated in both halves of a pair, since dice are public in real
time and the two games routinely finished tens of seconds to several minutes apart. The scheduler
now starts one independent game per pairing; colour balance is a scheduling property instead of a
shared-seed side effect. Historical CRN rows are untouched and still scored as pairs below — the
sections after this one describe mechanics that are permanent, not tied to CRN's presence.

## Pairwise SPRT (pentanomial)

For each bot matchup the report runs a Sequential Probability Ratio Test of

- **H0**: the perspective bot is stronger by **≤ elo0** (default 0),
- **H1**: stronger by **≥ elo1** (default 20),

at error rates α = β = 0.05 (both configurable: `mise run ladder:report -- 0 20 0.05 0.05`).

One **complete CRN pair is a single observation** with score ∈ {0, ¼, ½, ¾, 1} (both game scores
summed, normalised) — the *pentanomial* model. Scoring pairs as units, rather than their games
independently, keeps the correlated halves of a pair from being double-counted and captures the
variance reduction CRN created. Since #190 every new ladder game is unpaired and falls back to
ordinary per-game *trinomial* observations — the pentanomial family only ever has historical CRN
rows left to draw on; the two families' log-likelihood contributions add regardless.

The LLR uses the GSPRT normal approximation (Van den Bergh, as deployed by Fishtest): for a
sample with mean `m` and variance `v` of the per-observation score and hypothesis means
`s0 = score(elo0)`, `s1 = score(elo1)` (logistic `score(e) = 1/(1+10^(-e/400))`):

```
LLR ≈ N · (s1 − s0) · (2m − s0 − s1) / (2v)
```

with decision bounds `ln(β/(1−α))` and `ln((1−β)/α)`. `ACCEPT H1` = the perspective bot is
stronger (at the requested confidence); `ACCEPT H0` = it is not; `CONTINUE` = keep playing.

## Pool ranking (Bradley-Terry + bootstrap)

A single relative-Elo number per bot, fitted jointly over all rated decided games by
minorization-maximization (draws = half a win each way). A small **virtual draw** between every
pair of present players keeps undefeated bots finite and the comparison graph connected; the
resulting pull toward the mean is negligible next to the intervals at current corpus sizes.
Ratings are **relative** (pool mean = 0) — not comparable to the Glicko board's 1500-centred
numbers.

Uncertainty comes from a seeded **bootstrap over pairing groups**: a CRN pair is resampled as one
unit (resampling its halves independently would pretend the shared-dice correlation away). The
report prints the 95% percentile interval per bot and the **LOS** (likelihood of superiority)
against the next-ranked bot — the fraction of bootstrap worlds in which the order holds.

## Reading the report

- `CONTINUE` is not a failure: it means the corpus hasn't accumulated enough evidence for the
  requested elo gap and error rates — let the scheduler play more games.
- Overlapping CIs with LOS ≈ 50% mean the bots are indistinguishable so far.
- The report is read-only and deterministic for a given corpus (fixed bootstrap seed), so two
  runs on the same data render identically.
