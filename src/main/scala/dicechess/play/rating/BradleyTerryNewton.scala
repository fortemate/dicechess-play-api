package dicechess.play.rating

/** Bounded fallback for the slowly converging modes of the Bradley-Terry MM fit.
  *
  * Coordinates are log strengths; the last coordinate fixes the otherwise arbitrary common shift. The reduced
  * information matrix is positive definite because the color-neutral virtual draws connect every present player.
  */
private[rating] object BradleyTerryNewton:
  final class FitFailure(message: String) extends IllegalStateException(message)

  private val MaxSteps          = 100
  private val MaxBacktracks     = 40
  private val GradientTolerance = 1e-10
  private val StepTolerance     = 1e-10
  private val Armijo            = 1e-4

  final private case class Likelihood(
      points: Array[Array[Double]],
      whiteGames: Array[Array[Double]],
      logColor: Double,
      smoothing: Double
  )

  def fit(
      points: Array[Array[Double]],
      whiteGames: Array[Array[Double]],
      colorStrength: Double,
      smoothing: Double,
      initial: Array[Double]
  ): Array[Double] =
    val n = initial.length
    if n == 0 then Array.emptyDoubleArray
    else if n == 1 then Array(1.0)
    else
      val x = initialCoordinates(initial, colorStrength, smoothing)
      iterate(x, Likelihood(points, whiteGames, math.log(colorStrength), smoothing))

  private def initialCoordinates(initial: Array[Double], colorStrength: Double, smoothing: Double): Array[Double] =
    if !initial.forall(p => p.isFinite && p > 0.0) || !colorStrength.isFinite || colorStrength <= 0.0 ||
      !smoothing.isFinite || smoothing <= 0.0
    then throw new FitFailure("invalid initial strength or likelihood scale")
    val origin = math.log(initial.last)
    initial.map(p => math.log(p) - origin)

  private def iterate(initial: Array[Double], model: Likelihood): Array[Double] =
    var x         = initial
    var iteration = 0
    while iteration < MaxSteps do
      val (gradient, information) = derivatives(x, model)
      if !gradient.forall(_.isFinite) then throw new FitFailure("non-finite likelihood gradient")
      if gradient.take(x.length - 1).forall(g => math.abs(g) <= GradientTolerance) then return strengths(x)
      val delta = boundedDirection(information, gradient)
      if delta.forall(d => math.abs(d) <= StepTolerance) then return strengths(x)
      x = backtrack(x, delta, directionAscent(gradient, delta), model)
      iteration += 1
    throw new FitFailure("Newton fit exceeded its iteration limit")

  private def boundedDirection(information: Array[Array[Double]], gradient: Array[Double]): Array[Double] =
    val rawDelta = solve(information, gradient.take(gradient.length - 1)) :+ 0.0
    // Bound the initial trial in log space so extreme color offsets cannot jump to zero curvature.
    val scale = math.max(1.0, rawDelta.map(math.abs).max / 8.0)
    rawDelta.map(_ / scale)

  private def directionAscent(gradient: Array[Double], delta: Array[Double]): Double =
    val ascent = gradient.indices.map(i => gradient(i) * delta(i)).sum
    if !ascent.isFinite || ascent <= 0.0 then throw new FitFailure("Newton direction does not ascend")
    ascent

  private def backtrack(x: Array[Double], delta: Array[Double], ascent: Double, model: Likelihood): Array[Double] =
    var step      = 1.0
    var backtrack = 0
    while backtrack < MaxBacktracks do
      val candidate = x.indices.map(i => x(i) + step * delta(i)).toArray
      if candidate.forall(_.isFinite) then
        val improvement = objectiveChange(x, candidate, model)
        if improvement.isFinite && improvement >= Armijo * step * ascent then return candidate
      step *= 0.5
      backtrack += 1
    throw new FitFailure("Newton line search did not converge")

  private def probability(z: Double): Double =
    if z >= 0.0 then 1.0 / (1.0 + math.exp(-z))
    else
      val e = math.exp(z)
      e / (1.0 + e)

  private def curvature(z: Double): Double =
    val e = math.exp(-math.abs(z))
    e / ((1.0 + e) * (1.0 + e))

  private def softplus(z: Double): Double = math.max(0.0, z) + math.log1p(math.exp(-math.abs(z)))

  private def softplusChange(next: Double, previous: Double): Double =
    val delta = next - previous
    // Avoid subtracting adjacent log1p/exp values in the final, very small Newton steps.
    if math.abs(delta) <= 1.0 then math.log1p(probability(previous) * math.expm1(delta))
    else if next >= 0.0 && previous >= 0.0 then
      (next - previous) + math.log1p(math.exp(-next)) - math.log1p(math.exp(-previous))
    else if next <= 0.0 && previous <= 0.0 then math.log1p(math.exp(next)) - math.log1p(math.exp(previous))
    else softplus(next) - softplus(previous)

  private def derivatives(x: Array[Double], model: Likelihood): (Array[Double], Array[Array[Double]]) =
    val n           = x.length
    val gradient    = new Array[Double](n)
    val information = Array.ofDim[Double](n - 1, n - 1)
    var i           = 0
    while i < n do
      var j = i + 1
      while j < n do
        accumulatePair(i, j, x, model, gradient, information)
        j += 1
      i += 1
    (gradient, information)

  private def accumulatePair(
      i: Int,
      j: Int,
      x: Array[Double],
      model: Likelihood,
      gradient: Array[Double],
      information: Array[Array[Double]]
  ): Unit =
    val d  = x(i) - x(j)
    val ij = model.whiteGames(i)(j)
    val ji = model.whiteGames(j)(i)
    // Use expected losses on the positive side to avoid subtracting two nearly equal large win totals.
    val residual =
      if d >= 0.0 then
        ij * probability(-d - model.logColor) + ji * probability(-d + model.logColor) +
          model.smoothing * probability(-d) - model.points(j)(i)
      else
        model.points(i)(j) - ij * probability(d + model.logColor) - ji * probability(d - model.logColor) -
          model.smoothing * probability(d)
    gradient(i) += residual
    gradient(j) -= residual
    val v = ij * curvature(d + model.logColor) + ji * curvature(d - model.logColor) + model.smoothing * curvature(d)
    if !v.isFinite || v < 0.0 then throw new FitFailure("non-finite likelihood curvature")
    // i < j ensures that only j can be the coordinate removed by the gauge constraint.
    information(i)(i) += v
    if j < x.length - 1 then
      information(j)(j) += v
      information(i)(j) -= v
      information(j)(i) -= v

  /** Compare likelihoods directly, cancelling their common terms before summation. This matters for near-perfect
    * records: the residual uncertainty can be much smaller than the corpus-sized constant part of the likelihood.
    */
  private def objectiveChange(previous: Array[Double], next: Array[Double], model: Likelihood): Double =
    var result = 0.0
    var i      = 0
    while i < previous.length do
      var j = i + 1
      while j < previous.length do
        val before = previous(i) - previous(j)
        val after  = next(i) - next(j)
        if before >= 0.0 then
          result += -model.points(j)(i) * (after - before) -
            model.whiteGames(i)(j) * softplusChange(-after - model.logColor, -before - model.logColor) -
            model.whiteGames(j)(i) * softplusChange(-after + model.logColor, -before + model.logColor) -
            model.smoothing * softplusChange(-after, -before)
        else
          result += model.points(i)(j) * (after - before) -
            model.whiteGames(i)(j) * softplusChange(after + model.logColor, before + model.logColor) -
            model.whiteGames(j)(i) * softplusChange(after - model.logColor, before - model.logColor) -
            model.smoothing * softplusChange(after, before)
        j += 1
      i += 1
    result

  private def solve(matrix: Array[Array[Double]], rhs: Array[Double]): Array[Double] =
    val a = matrix.map(_.clone())
    val b = rhs.clone()
    var k = 0
    while k < b.length do
      eliminateColumn(a, b, k)
      k += 1
    backSubstitute(a, b)

  private def eliminateColumn(a: Array[Array[Double]], b: Array[Double], k: Int): Unit =
    val n     = b.length
    var pivot = k
    var i     = k + 1
    while i < n do
      if math.abs(a(i)(k)) > math.abs(a(pivot)(k)) then pivot = i
      i += 1
    if !a(pivot)(k).isFinite || a(pivot)(k) == 0.0 then throw new FitFailure("singular information matrix")
    val row = a(k)
    a(k) = a(pivot)
    a(pivot) = row
    val value = b(k)
    b(k) = b(pivot)
    b(pivot) = value
    i = k + 1
    while i < n do
      val factor = a(i)(k) / a(k)(k)
      var j      = k + 1
      while j < n do
        a(i)(j) -= factor * a(k)(j)
        j += 1
      b(i) -= factor * b(k)
      i += 1

  private def backSubstitute(a: Array[Array[Double]], b: Array[Double]): Array[Double] =
    val n        = b.length
    val solution = new Array[Double](n)
    var i        = n - 1
    while i >= 0 do
      var value = b(i)
      var j     = i + 1
      while j < n do
        value -= a(i)(j) * solution(j)
        j += 1
      solution(i) = value / a(i)(i)
      if !solution(i).isFinite then throw new FitFailure("non-finite Newton step")
      i -= 1
    solution

  private def strengths(x: Array[Double]): Array[Double] =
    val mean   = x.sum / x.length
    val result = x.map(v => math.exp(v - mean))
    if !result.forall(v => v.isFinite && v > 0.0) then throw new FitFailure("non-finite fitted strength")
    result
