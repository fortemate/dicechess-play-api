package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*

import scala.concurrent.duration.*

class RematchLimiterSuite extends munit.CatsEffectSuite:

  private def withClock[A](config: RematchLimiter.Config)(test: (RematchLimiter, FiniteDuration => IO[Unit]) => IO[A]) =
    for
      now     <- Ref.of[IO, FiniteDuration](Duration.Zero)
      limiter <- RematchLimiter.create(config, now.get)
      result  <- test(limiter, now.set)
    yield result

  test("read and write fixed-window budgets are independent, and denied attempts do not consume more budget"):
    withClock(RematchLimiter.Config(readsPerMinute = 2, writesPerMinute = 1, maxKeys = 2)) { (limiter, setTime) =>
      for
        read1       <- limiter.attempt("client", mutation = false)
        read2       <- limiter.attempt("client", mutation = false)
        readDenied1 <- limiter.attempt("client", mutation = false)
        readDenied2 <- limiter.attempt("client", mutation = false)
        write1      <- limiter.attempt("client", mutation = true)
        writeDenied <- limiter.attempt("client", mutation = true)
        _           <- setTime(1.minute - 1.nanosecond)
        beforeEdge  <- limiter.attempt("client", mutation = false)
        _           <- setTime(1.minute)
        readReset   <- limiter.attempt("client", mutation = false)
        writeReset  <- limiter.attempt("client", mutation = true)
      yield assertEquals(
        List(read1, read2, readDenied1, readDenied2, write1, writeDenied, beforeEdge, readReset, writeReset),
        List(true, true, false, false, true, false, false, true, true)
      )
    }

  test("an expired key frees hard-cap capacity at the exact window boundary"):
    withClock(RematchLimiter.Config(readsPerMinute = 1, writesPerMinute = 1, maxKeys = 2)) { (limiter, setTime) =>
      for
        first     <- limiter.attempt("first", mutation = false)
        second    <- limiter.attempt("second", mutation = false)
        capped    <- limiter.attempt("third", mutation = false)
        _         <- setTime(1.minute)
        reclaimed <- limiter.attempt("third", mutation = false)
      yield assertEquals(List(first, second, capped, reclaimed), List(true, true, false, true))
    }

  test("bounded expiry cleanup rolls over an existing key and admits a new key after a large window expires"):
    val maxKeys = 100
    withClock(RematchLimiter.Config(readsPerMinute = 2, writesPerMinute = 1, maxKeys = maxKeys)) { (limiter, setTime) =>
      for
        admitted <- (1 to maxKeys).toList.traverse { i =>
          setTime(i.nanoseconds) *> limiter.attempt(s"key-$i", mutation = false)
        }
        _ = assert(admitted.forall(identity))
        _        <- setTime(1.minute + maxKeys.nanoseconds)
        rollover <- limiter.attempt("key-100", mutation = false)
        newcomer <- limiter.attempt("new-key", mutation = false)
        repeat   <- limiter.attempt("key-100", mutation = false)
        spent    <- limiter.attempt("key-100", mutation = false)
      yield
        assert(rollover, "an existing expired key starts a fresh fixed window")
        assert(newcomer, "expired entries release capacity without a full-map scan")
        assert(repeat, "cleanup preserves the refreshed window's remaining budget")
        assert(!spent, "the refreshed window still enforces its configured budget")
    }

  test("concurrent unique requests never exceed the hard key cap and admitted keys keep their budget"):
    val maxKeys = 10
    withClock(RematchLimiter.Config(readsPerMinute = 2, writesPerMinute = 1, maxKeys = maxKeys)) { (limiter, _) =>
      for
        results <- (1 to 100).toList.parTraverse(i => limiter.attempt(s"key-$i", mutation = false))
        admitted = results.zipWithIndex.collect { case (true, index) => s"key-${index + 1}" }
        _        = assertEquals(admitted.size, maxKeys)
        repeat   <- limiter.attempt(admitted.head, mutation = false)
        spent    <- limiter.attempt(admitted.head, mutation = false)
        overflow <- limiter.attempt("overflow", mutation = false)
      yield assertEquals(List(repeat, spent, overflow), List(true, false, false))
    }

  test("concurrent attempts for one key consume exactly its budget"):
    val limit = 30
    withClock(RematchLimiter.Config(readsPerMinute = limit, writesPerMinute = 1, maxKeys = 1)) { (limiter, _) =>
      (1 to 100).toList.parTraverse(_ => limiter.attempt("shared", mutation = false)).map { results =>
        assertEquals(results.count(identity), limit)
      }
    }

  test("configuration bounds remain enforced"):
    intercept[IllegalArgumentException](RematchLimiter.Config(readsPerMinute = 0))
    intercept[IllegalArgumentException](RematchLimiter.Config(readsPerMinute = 601))
    intercept[IllegalArgumentException](RematchLimiter.Config(writesPerMinute = 0))
    intercept[IllegalArgumentException](RematchLimiter.Config(writesPerMinute = 121))
    intercept[IllegalArgumentException](RematchLimiter.Config(maxKeys = 0))
    intercept[IllegalArgumentException](RematchLimiter.Config(maxKeys = 100001))
