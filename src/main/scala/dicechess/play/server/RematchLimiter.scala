package dicechess.play.server

import cats.effect.{IO, Ref}
import scala.concurrent.duration.*

/** A hard key cap prevents unauthenticated polling from growing the rate-limit map without bound. */
final class RematchLimiter private (
    state: Ref[IO, Map[String, (FiniteDuration, Int)]],
    val config: RematchLimiter.Config
):
  def attempt(key: String, mutation: Boolean): IO[Boolean] =
    IO.monotonic.flatMap { now =>
      state.modify { entries =>
        val fresh  = entries.filter((_, value) => now - value._1 < 1.minute)
        val scoped = (if mutation then "write:" else "read:") + key
        val limit  = if mutation then config.writesPerMinute else config.readsPerMinute
        fresh.get(scoped) match
          case Some((since, count)) if count < limit => (fresh.updated(scoped, (since, count + 1)), true)
          case Some(_)                               => (fresh, false)
          case None if fresh.size < config.maxKeys   => (fresh.updated(scoped, (now, 1)), true)
          case None                                  => (fresh, false)
      }
    }

object RematchLimiter:
  final case class Config(readsPerMinute: Int = 120, writesPerMinute: Int = 30, maxKeys: Int = 10000):
    require(readsPerMinute >= 1 && readsPerMinute <= 600)
    require(writesPerMinute >= 1 && writesPerMinute <= 120)
    require(maxKeys >= 1 && maxKeys <= 100000)
  def create(config: Config = Config()): IO[RematchLimiter] =
    Ref.of[IO, Map[String, (FiniteDuration, Int)]](Map.empty).map(new RematchLimiter(_, config))
