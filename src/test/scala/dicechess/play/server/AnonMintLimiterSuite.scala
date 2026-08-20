package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*

import scala.concurrent.duration.*

class AnonMintLimiterSuite extends munit.CatsEffectSuite:

  test("allows up to the limit, then throttles with a positive retry-after"):
    AnonMintLimiter
      .create(limit = 2, window = 1.hour)
      .flatMap: lim =>
        for
          a <- lim.attempt("ip")
          b <- lim.attempt("ip")
          c <- lim.attempt("ip")
        yield
          assert(a.isRight)
          assert(b.isRight)
          c match
            case Left(retry) => assert(retry > Duration.Zero, s"retry-after must be positive: $retry")
            case Right(_)    => fail("the third attempt should be throttled")

  test("limits are independent per key"):
    AnonMintLimiter
      .create(limit = 1, window = 1.hour)
      .flatMap: lim =>
        for
          a1 <- lim.attempt("a")
          a2 <- lim.attempt("a")
          b1 <- lim.attempt("b")
        yield
          assert(a1.isRight)
          assert(a2.isLeft, "key 'a' is exhausted")
          assert(b1.isRight, "key 'b' is independent")

  test("the window rolls over"):
    AnonMintLimiter
      .create(limit = 1, window = 80.millis)
      .flatMap: lim =>
        for
          a <- lim.attempt("ip")
          b <- lim.attempt("ip")
          _ <- IO.sleep(120.millis)
          c <- lim.attempt("ip")
        yield
          assert(a.isRight)
          assert(b.isLeft)
          assert(c.isRight, "after the window rolls over, attempts are allowed again")

  test("evicts stale keys after their window expires"):
    AnonMintLimiter
      .create(limit = 10, window = 50.millis)
      .flatMap: lim =>
        for
          _     <- (1 to 100).toList.traverse(i => lim.attempt(s"one-shot-key-$i"))
          size1 <- lim.size
          _ = assertEquals(size1, 100)
          _     <- IO.sleep(70.millis)
          _     <- lim.attempt("active-key")
          size2 <- lim.size
        yield assertEquals(size2, 1, "all 100 expired keys must be swept, leaving only the fresh key")
