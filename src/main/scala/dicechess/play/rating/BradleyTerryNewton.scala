package dicechess.play.rating

import cats.syntax.all.*
import dicechess.play.rating.BradleyTerry.{FitFailure, FitResult}

import scala.annotation.tailrec

/** Bounded fallback for the slowly converging modes of the Bradley-Terry MM fit.
  *
  * Coordinates are log strengths; the last coordinate fixes the otherwise arbitrary common shift. The reduced
  * information matrix is positive definite because the color-neutral virtual draws connect every present player.
  */
private[rating] object BradleyTerryNewton:

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

  private enum Step:
    case Converged(value: Array[Double])
    case Continue(coordinates: Array[Double])

  def fit(
      points: Array[Array[Double]],
      whiteGames: Array[Array[Double]],
      colorStrength: Double,
      smoothing: Double,
      initial: Array[Double]
  ): FitResult[Array[Double]] =
    val n = initial.length
    if n == 0 then Right(Array.emptyDoubleArray)
    else if n == 1 then Right(Array(1.0))
    else
      initialCoordinates(initial, colorStrength, smoothing).flatMap { x =>
        iterate(x, Likelihood(points, whiteGames, math.log(colorStrength), smoothing))
      }

  private def initialCoordinates(
      initial: Array[Double],
      colorStrength: Double,
      smoothing: Double
  ): FitResult[Array[Double]] =
    Either
      .cond(
        initial.forall(p => p.isFinite && p > 0.0) && colorStrength.isFinite && colorStrength > 0.0 &&
          smoothing.isFinite && smoothing > 0.0,
        (),
        FitFailure("invalid initial strength or likelihood scale")
      )
      .map { _ =>
        val origin = math.log(initial.last)
        initial.map(p => math.log(p) - origin)
      }

  private def iterate(initial: Array[Double], model: Likelihood): FitResult[Array[Double]] =
    @tailrec
    def loop(x: Array[Double], iteration: Int): FitResult[Array[Double]] =
      if iteration >= MaxSteps then Left(FitFailure("Newton fit exceeded its iteration limit"))
      else
        newtonStep(x, model) match
          case Left(problem)                => Left(problem)
          case Right(Step.Converged(value)) => Right(value)
          case Right(Step.Continue(next))   => loop(next, iteration + 1)
    loop(initial, 0)

  private def newtonStep(x: Array[Double], model: Likelihood): FitResult[Step] =
    derivatives(x, model).flatMap { (gradient, information) =>
      if !gradient.forall(_.isFinite) then Left(FitFailure("non-finite likelihood gradient"))
      else if gradient.take(x.length - 1).forall(g => math.abs(g) <= GradientTolerance) then
        strengths(x).map(Step.Converged.apply)
      else advance(x, model, gradient, information)
    }

  private def advance(
      x: Array[Double],
      model: Likelihood,
      gradient: Array[Double],
      information: Array[Array[Double]]
  ): FitResult[Step] =
    boundedDirection(information, gradient).flatMap { delta =>
      if delta.forall(d => math.abs(d) <= StepTolerance) then strengths(x).map(Step.Converged.apply)
      else
        for
          ascent <- directionAscent(gradient, delta)
          next   <- backtrack(x, delta, ascent, model)
        yield Step.Continue(next)
    }

  private def boundedDirection(information: Array[Array[Double]], gradient: Array[Double]): FitResult[Array[Double]] =
    solve(information, gradient.take(gradient.length - 1)).map { solved =>
      val rawDelta = solved :+ 0.0
      // Bound the initial trial in log space so extreme color offsets cannot jump to zero curvature.
      val scale = math.max(1.0, rawDelta.map(math.abs).max / 8.0)
      rawDelta.map(_ / scale)
    }

  private def directionAscent(gradient: Array[Double], delta: Array[Double]): FitResult[Double] =
    val ascent = gradient.indices.map(i => gradient(i) * delta(i)).sum
    Either.cond(ascent.isFinite && ascent > 0.0, ascent, FitFailure("Newton direction does not ascend"))

  private def backtrack(
      x: Array[Double],
      delta: Array[Double],
      ascent: Double,
      model: Likelihood
  ): FitResult[Array[Double]] =
    @tailrec
    def trial(step: Double, attempt: Int): FitResult[Array[Double]] =
      if attempt >= MaxBacktracks then Left(FitFailure("Newton line search did not converge"))
      else
        val candidate   = x.indices.map(i => x(i) + step * delta(i)).toArray
        val improvement = if candidate.forall(_.isFinite) then objectiveChange(x, candidate, model) else Double.NaN
        if improvement.isFinite && improvement >= Armijo * step * ascent then Right(candidate)
        else trial(step * 0.5, attempt + 1)
    trial(1.0, 0)

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

  private def derivatives(x: Array[Double], model: Likelihood): FitResult[(Array[Double], Array[Array[Double]])] =
    val n           = x.length
    val gradient    = new Array[Double](n)
    val information = Array.ofDim[Double](n - 1, n - 1)
    (0 until n).toList
      .traverse_ { i =>
        (i + 1 until n).toList.traverse_(j => accumulatePair(i, j, x, model, gradient, information))
      }
      .map(_ => (gradient, information))

  private def accumulatePair(
      i: Int,
      j: Int,
      x: Array[Double],
      model: Likelihood,
      gradient: Array[Double],
      information: Array[Array[Double]]
  ): FitResult[Unit] =
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
    if !v.isFinite || v < 0.0 then Left(FitFailure("non-finite likelihood curvature"))
    else
      // i < j ensures that only j can be the coordinate removed by the gauge constraint.
      information(i)(i) += v
      if j < x.length - 1 then
        information(j)(j) += v
        information(i)(j) -= v
        information(j)(i) -= v
      Right(())

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

  private def solve(matrix: Array[Array[Double]], rhs: Array[Double]): FitResult[Array[Double]] =
    val a = matrix.map(_.clone())
    val b = rhs.clone()
    (0 until b.length).toList.traverse_(k => eliminateColumn(a, b, k)).flatMap(_ => backSubstitute(a, b))

  private def eliminateColumn(a: Array[Array[Double]], b: Array[Double], k: Int): FitResult[Unit] =
    val n     = b.length
    var pivot = k
    var i     = k + 1
    while i < n do
      if math.abs(a(i)(k)) > math.abs(a(pivot)(k)) then pivot = i
      i += 1
    if !a(pivot)(k).isFinite || a(pivot)(k) == 0.0 then Left(FitFailure("singular information matrix"))
    else
      eliminatePivot(a, b, k, pivot)
      Right(())

  private def eliminatePivot(a: Array[Array[Double]], b: Array[Double], k: Int, pivot: Int): Unit =
    val row = a(k)
    a(k) = a(pivot)
    a(pivot) = row
    val value = b(k)
    b(k) = b(pivot)
    b(pivot) = value
    var i = k + 1
    while i < b.length do
      val factor = a(i)(k) / a(k)(k)
      var j      = k + 1
      while j < b.length do
        a(i)(j) -= factor * a(k)(j)
        j += 1
      b(i) -= factor * b(k)
      i += 1

  private def backSubstitute(a: Array[Array[Double]], b: Array[Double]): FitResult[Array[Double]] =
    val n        = b.length
    val solution = new Array[Double](n)
    (0 until n).reverse.toList
      .traverse_ { i =>
        var value = b(i)
        var j     = i + 1
        while j < n do
          value -= a(i)(j) * solution(j)
          j += 1
        solution(i) = value / a(i)(i)
        Either.cond(solution(i).isFinite, (), FitFailure("non-finite Newton step"))
      }
      .map(_ => solution)

  private def strengths(x: Array[Double]): FitResult[Array[Double]] =
    val mean   = x.sum / x.length
    val result = x.map(v => math.exp(v - mean))
    Either.cond(result.forall(v => v.isFinite && v > 0.0), result, FitFailure("non-finite fitted strength"))
