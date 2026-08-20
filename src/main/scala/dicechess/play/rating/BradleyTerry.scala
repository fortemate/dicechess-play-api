package dicechess.play.rating

/** Bradley-Terry pool ranking (E.1, #120) — the Ordo-style batch complement to the pairwise SPRT: one relative-Elo
  * number per bot, fitted jointly over every rated game, with bootstrap confidence intervals and LOS between
  * neighbours. Pure math: no IO; randomness only through an explicit seed.
  *
  * Fitting is the classic minorization-maximization iteration over strengths `p_i` (draws count half a win each way). A
  * small '''virtual draw''' is added between every pair of present players ("smoothing"): it keeps a 100%-scorer's
  * strength finite and the comparison graph connected, at the cost of a slight pull toward the mean — acceptable for a
  * report whose CIs dwarf the pull at these corpus sizes.
  *
  * Bootstrap resamples '''pairing groups''' (a CRN mirror pair = one unit, an unpaired game = its own unit) rather than
  * individual games — resampling the two halves of a pair independently would pretend the shared-dice correlation does
  * not exist and understate the intervals.
  *
  * '''Everything runs over an integer-encoded [[Corpus]]''' (#217). The fit is called `iterations + 1` times per
  * report, so anything it redoes per call is multiplied by four figures: player discovery (`flatMap.distinct.sorted`
  * over two `String`s per game), the `String -> Int` index, and a freshly materialised resample of the whole corpus
  * used to cost far more than the numerical fit they fed. Names are resolved once, a resample is a draw of group
  * indices, and the accumulation reads primitive arrays. The output is unchanged down to the last bit for a given JVM —
  * deliberately, since the report is reproducible by seed — which is why the summation ORDER below is preserved
  * everywhere it was not obviously irrelevant. (Across ARCHITECTURES the last digit or two moves no matter what this
  * code does: `Math.log`/`exp`/`log10` are 1-ulp-tolerant and intrinsic-dependent, so an aarch64 machine and an x86-64
  * CI runner disagree at 1e-14. `BradleyTerrySuite`'s golden vector therefore compares within 1e-9, not exactly.)
  */
object BradleyTerry:

  /** One game: `(playerA, playerB, scoreA)` with scoreA ∈ {0, 0.5, 1}. */
  type Game = (String, String, Double)

  /** One bot's row of the pool ranking. `elo` is RELATIVE (the pool's mean is 0 by construction — not comparable to the
    * Glicko-2 board's 1500-centred numbers); `losVsNext` is the likelihood of superiority over the next-ranked bot,
    * absent for the last row.
    */
  final case class Ranked(player: String, elo: Double, ciLow: Double, ciHigh: Double, losVsNext: Option[Double])

  /** Virtual-draw weight per player pair. NOTE the deliberate scale-dependence (review): each bot absorbs
    * `Smoothing/2 × (N−1)` virtual games, so in a LARGE pool a barely-active bot gets squashed toward the mean. Fine at
    * today's handful of bots; the scale-invariant upgrade path, should the pool grow to dozens, is a single virtual
    * ANCHOR player that everyone draws against once, instead of pairwise smoothing.
    */
  private val Smoothing     = 0.5
  private val MaxIterations = 1000
  private val Tolerance     = 1e-10

  /** Relative Elo per player (mean 0), fitted by MM over the given games. Empty input → empty map. */
  def ratings(games: Seq[Game]): Map[String, Double] =
    val corpus = Corpus.of(Seq(games))
    corpus.fit(Array(0))

  /** The ranked pool with bootstrap 95% CIs and neighbour LOS. `groups` are the resampling units (see the object doc);
    * `seed` makes the whole report reproducible.
    */
  def rankedWithBootstrap(
      groups: Seq[Seq[Game]],
      iterations: Int = 1000,
      seed: Long = 42L
  ): List[Ranked] =
    val corpus = Corpus.of(groups)
    val base   = corpus.fit(Array.range(0, corpus.groupCount))
    if base.isEmpty then Nil
    else
      val order = base.toList.sortBy(-_._2).map(_._1)

      val rng = new scala.util.Random(seed)
      // One reusable draw buffer for every iteration: the fit reads it and keeps nothing, so `iterations` separate
      // arrays would be pure garbage. The draw order — one `nextInt` per group, ascending — is part of the seeded
      // contract, not an implementation detail.
      val picks   = new Array[Int](corpus.groupCount)
      val samples = Vector.fill(iterations):
        var k = 0
        while k < picks.length do
          picks(k) = rng.nextInt(corpus.groupCount)
          k += 1
        corpus.fit(picks)

      def percentile(sorted: Vector[Double], p: Double): Double =
        if sorted.isEmpty then 0.0
        else sorted(math.min(sorted.size - 1, math.max(0, math.round(p * (sorted.size - 1)).toInt)))

      order.zipWithIndex.map: (player, rank) =>
        val values = samples.flatMap(_.get(player)).sorted
        val los    = order
          .lift(rank + 1)
          .map: next =>
            val both = samples.flatMap(s => s.get(player).zip(s.get(next)))
            if both.isEmpty then 0.5 else both.count(_ > _).toDouble / both.size
        Ranked(player, base(player), percentile(values, 0.025), percentile(values, 0.975), los)

  /** The corpus as integers: every player name resolved once to its index in `players` (distinct and sorted, so an
    * ascending scan of indices is an ascending scan of names), every game flattened into three parallel arrays, and
    * `groupStart` marking where each resampling unit begins — `groupStart(g)` until `groupStart(g + 1)`.
    *
    * A "selection" is therefore just an `Array[Int]` of group indices, which is all a bootstrap draw has to produce.
    */
  final private class Corpus(
      players: Array[String],
      playerA: Array[Int],
      playerB: Array[Int],
      scoreA: Array[Double],
      groupStart: Array[Int]
  ):

    def groupCount: Int = groupStart.length - 1

    /** Fit the games of the selected groups, in selection order.
      *
      * Only the players that actually appear in the selection are ranked, and the virtual draws are spread over those
      * players alone. That is not an optimisation detail but the pre-existing semantics: a bootstrap sample that misses
      * a rarely-seen bot must not silently rank it off smoothing games it never played. Hence the two passes — presence
      * has to be known before the matrix can be sized and seeded, and seeding has to happen before the games are added
      * (`Smoothing/2 + s` and `s + Smoothing/2` are not the same double).
      */
    def fit(selection: Array[Int]): Map[String, Double] =
      val total   = players.length
      val present = new Array[Boolean](total)
      var s       = 0
      while s < selection.length do
        var t        = groupStart(selection(s))
        val groupEnd = groupStart(selection(s) + 1)
        while t < groupEnd do
          present(playerA(t)) = true
          present(playerB(t)) = true
          t += 1
        s += 1

      // Local (fit-sized) index per present player, assigned in ascending global order so the fit sees exactly the
      // sorted subset the name-based implementation used to build.
      val localOf = new Array[Int](total)
      var n       = 0
      var p       = 0
      while p < total do
        if present(p) then
          localOf(p) = n
          n += 1
        else localOf(p) = -1
        p += 1

      if n < 2 then (0 until total).filter(present(_)).map(i => players(i) -> 0.0).toMap
      else
        // points(i)(j) = points i scored against j; the virtual draw spreads Smoothing/2 each way per pair.
        val points = Array.ofDim[Double](n, n)
        var i      = 0
        while i < n do
          var j = 0
          while j < n do
            if i != j then points(i)(j) = Smoothing / 2.0
            j += 1
          i += 1

        s = 0
        while s < selection.length do
          var t        = groupStart(selection(s))
          val groupEnd = groupStart(selection(s) + 1)
          while t < groupEnd do
            val a     = localOf(playerA(t))
            val b     = localOf(playerB(t))
            val score = scoreA(t)
            points(a)(b) += score
            points(b)(a) += 1.0 - score
            t += 1
          s += 1

        val strengths = BradleyTerry.mm(points, n)
        val elos      = strengths.map(strength => 400.0 * math.log10(strength))
        val mean      = elos.sum / n
        val ranked    = Map.newBuilder[String, Double]
        p = 0
        while p < total do
          if present(p) then ranked += players(p) -> (elos(localOf(p)) - mean)
          p += 1
        ranked.result()

  private object Corpus:

    def of(groups: Seq[Seq[Game]]): Corpus =
      val players = groups.iterator.flatten.flatMap((a, b, _) => Iterator(a, b)).toArray.distinct.sorted
      val index   = players.iterator.zipWithIndex.toMap
      val total   = groups.iterator.map(_.size).sum

      val playerA    = new Array[Int](total)
      val playerB    = new Array[Int](total)
      val scoreA     = new Array[Double](total)
      val groupStart = new Array[Int](groups.size + 1)

      var t = 0
      var g = 0
      groups.foreach: group =>
        groupStart(g) = t
        group.foreach: (a, b, score) =>
          playerA(t) = index(a)
          playerB(t) = index(b)
          scoreA(t) = score
          t += 1
        g += 1
      groupStart(g) = t

      new Corpus(players, playerA, playerB, scoreA, groupStart)

  /** The MM iteration itself, over an already-built points matrix. Hand-rolled loops rather than
    * `Array.tabulate`/`filter`/`map`/`sum`: at `iterations + 1` fits per report and up to [[MaxIterations]] sweeps per
    * fit, the intermediate collections dominated. Every accumulation still runs in ascending index order, which is what
    * keeps the result identical to the comprehension it replaced.
    */
  private def mm(points: Array[Array[Double]], n: Int): Array[Double] =
    val gamesBetween = Array.tabulate(n, n)((i, j) => points(i)(j) + points(j)(i))
    val totalPoints  = Array.tabulate(n)(i => points(i).sum)

    var strengths = Array.fill(n)(1.0)
    var iteration = 0
    var moved     = Double.MaxValue
    while iteration < MaxIterations && moved > Tolerance do
      val next = new Array[Double](n)
      var i    = 0
      while i < n do
        var denominator = 0.0
        var j           = 0
        while j < n do
          if j != i then denominator += gamesBetween(i)(j) / (strengths(i) + strengths(j))
          j += 1
        next(i) = if denominator == 0.0 then strengths(i) else totalPoints(i) / denominator
        i += 1

      // Renormalise to geometric mean 1 so the iteration can't drift off to infinity as a family.
      var logSum = 0.0
      i = 0
      while i < n do
        logSum += math.log(next(i))
        i += 1
      val scale = math.exp(logSum / n)

      moved = 0.0
      i = 0
      while i < n do
        val scaled = next(i) / scale
        val delta  = math.abs(scaled - strengths(i))
        if delta > moved then moved = delta
        next(i) = scaled
        i += 1

      strengths = next
      iteration += 1

    strengths
