package dicechess.play.rating

/** Sequential Probability Ratio Test for "is bot B stronger than bot A" (E.1, #120) — the Fishtest-style precise
  * verdict, as opposed to the Glicko-2 ladder's rolling estimate. Pure math: no IO, no store.
  *
  * The log-likelihood ratio uses the GSPRT normal approximation (Michel Van den Bergh's formula, the one Fishtest runs
  * on): for observations with sample mean `m` and sample variance `v` of the per-observation score, and hypothesis
  * means `s0`/`s1` (the expected scores at `elo0`/`elo1`),
  *
  * {{{LLR ≈ N · (s1 − s0) · (2m − s0 − s1) / (2v)}}}
  *
  * Two observation kinds contribute additively (independent observations, same hypotheses):
  *   - '''Pentanomial''': one CRN mirror pair = ONE observation with score ∈ {0, ¼, ½, ¾, 1} (the pair's two game
  *     scores summed, normalised by 2). This was the variance reduction the shared dice existed for — the pair's
  *     colour-and-luck noise cancelled inside the observation instead of inflating `v`. Historical only: #190 dropped
  *     the mirrored-pair mechanism that created these, so this family no longer grows.
  *   - '''Trinomial''': an unpaired rated game = one observation with score ∈ {0, ½, 1} — the ladder's only kind of
  *     observation since #190; CRN pairing made these the rare fallback before that.
  *
  * Each family's histogram is smoothed with a small per-bin pseudo-count before `m`/`v` are computed — see
  * `BinPseudoCount` for why that (and not a variance floor) is the degenerate-sample guard.
  */
object Sprt:

  enum Verdict:
    /** The evidence crossed the upper bound: accept H1 (the perspective bot IS stronger by at least `elo1`). */
    case AcceptH1

    /** The evidence crossed the lower bound: accept H0 (not stronger by more than `elo0`). */
    case AcceptH0

    /** Keep playing — the evidence is not decisive at the requested error rates. */
    case Continue

  /** Pair-score histogram from the perspective bot's side: `n0` = lost both games … `n4` = won both. */
  final case class Pentanomial(n0: Long, n1: Long, n2: Long, n3: Long, n4: Long):
    def total: Long = n0 + n1 + n2 + n3 + n4

  object Pentanomial:
    val Empty: Pentanomial = Pentanomial(0, 0, 0, 0, 0)

  /** Single-game histogram from the perspective bot's side. */
  final case class Trinomial(losses: Long, draws: Long, wins: Long):
    def total: Long = losses + draws + wins

  object Trinomial:
    val Empty: Trinomial = Trinomial(0, 0, 0)

  final case class Result(llr: Double, lower: Double, upper: Double, verdict: Verdict, observations: Long)

  /** The logistic expected score of a player who is `elo` points stronger than the opponent. */
  def scoreOfElo(elo: Double): Double = 1.0 / (1.0 + math.pow(10.0, -elo / 400.0))

  /** Run the test: H0 "stronger by ≤ elo0" vs H1 "stronger by ≥ elo1", with type-I/II error rates `alpha`/`beta`.
    * `Continue` until the LLR leaves `(lower, upper)`.
    */
  def test(
      pairs: Pentanomial,
      singles: Trinomial,
      elo0: Double,
      elo1: Double,
      alpha: Double,
      beta: Double
  ): Result =
    val s0    = scoreOfElo(elo0)
    val s1    = scoreOfElo(elo1)
    val lower = math.log(beta / (1.0 - alpha))
    val upper = math.log((1.0 - beta) / alpha)
    val llr   =
      llrContribution(
        List(0.0 -> pairs.n0, 0.25 -> pairs.n1, 0.5 -> pairs.n2, 0.75 -> pairs.n3, 1.0 -> pairs.n4),
        s0,
        s1
      ) +
        llrContribution(List(0.0 -> singles.losses, 0.5 -> singles.draws, 1.0 -> singles.wins), s0, s1)
    val verdict =
      if llr >= upper then Verdict.AcceptH1
      else if llr <= lower then Verdict.AcceptH0
      else Verdict.Continue
    Result(llr, lower, upper, verdict, pairs.total + singles.total)

  /** How much pseudo-observation is added to EVERY bin before computing the sample mean/variance (Laplace-style
    * smoothing, the same regularisation family Fishtest applies). This — not an epsilon floor on the variance — is the
    * degenerate-sample guard: a tiny sample of identical outcomes (variance 0) gets a small, honest LLR that grows with
    * N, instead of an epsilon-divided explosion where one lucky game outweighs a hundred balanced pairs.
    */
  private val BinPseudoCount = 0.5

  /** GSPRT contribution of one observation family (score values with their counts). Zero observations contribute
    * nothing; otherwise mean and variance are computed over the pseudo-count-smoothed histogram (see
    * [[BinPseudoCount]]), which is strictly positive-variance by construction — but the evidence multiplier is the REAL
    * observation count only: pseudo-observations may regularise the estimated moments, never add statistical weight of
    * their own (multiplying by the smoothed total would inflate small samples' confidence by up to 2.5×, as review on
    * the first version caught).
    */
  private def llrContribution(bins: List[(Double, Long)], s0: Double, s1: Double): Double =
    val realCount = bins.map(_._2).sum
    if realCount == 0 then 0.0
    else
      val smoothed = bins.map((value, count) => (value, count.toDouble + BinPseudoCount))
      val total    = smoothed.map(_._2).sum
      val mean     = smoothed.map((value, count) => value * count).sum / total
      val variance = smoothed.map((value, count) => value * value * count).sum / total - mean * mean
      realCount * (s1 - s0) * (2.0 * mean - s0 - s1) / (2.0 * variance)
