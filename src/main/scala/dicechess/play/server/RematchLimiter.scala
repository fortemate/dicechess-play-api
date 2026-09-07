package dicechess.play.server

import cats.effect.{IO, Ref}

import scala.collection.immutable.TreeMap
import scala.concurrent.duration.*

/** Fixed-window limiter for the rematch surface. Read and write keys have independent budgets, while sharing a hard key
  * cap that prevents unauthenticated polling from growing memory without bound.
  *
  * The expiry index avoids scanning every tracked key on each request. An attempt removes at most
  * [[RematchLimiter.PrunePerAttempt]] oldest records; at the cap, the first expired record immediately frees a slot.
  * Remaining expired records are reclaimed by later attempts. Both indexes live in one `Ref`, so admission, budget
  * consumption, rollover, and eviction are atomic under contention.
  */
final class RematchLimiter private (
    state: Ref[IO, RematchLimiter.State],
    val config: RematchLimiter.Config,
    monotonic: IO[FiniteDuration]
):
  import RematchLimiter.*

  def attempt(key: String, mutation: Boolean): IO[Boolean] =
    monotonic.flatMap { now =>
      state.modify { current =>
        val fresh  = prune(current, now)
        val scoped = (if mutation then "write:" else "read:") + key
        val limit  = if mutation then config.writesPerMinute else config.readsPerMinute
        fresh.entries.get(scoped) match
          case Some(window) if now < window.expiresAt =>
            if window.count < limit then
              (fresh.copy(entries = fresh.entries.updated(scoped, window.copy(count = window.count + 1))), true)
            else (fresh, false)
          case Some(window) =>
            val rolled = fresh.remove(scoped, window.expiresAt).add(scoped, Window(now, 1))
            (rolled, true)
          case None if fresh.entries.size < config.maxKeys =>
            (fresh.add(scoped, Window(now, 1)), true)
          case None =>
            // If any tracked window were expired, the ordered prune above would have removed at least one and freed
            // a slot. Reaching this branch therefore means every capacity-holding window is still live.
            (fresh, false)
      }
    }

object RematchLimiter:
  private val WindowDuration  = 1.minute
  private val PrunePerAttempt = 64

  final case class Config(readsPerMinute: Int = 120, writesPerMinute: Int = 30, maxKeys: Int = 10000):
    require(readsPerMinute >= 1 && readsPerMinute <= 600)
    require(writesPerMinute >= 1 && writesPerMinute <= 120)
    require(maxKeys >= 1 && maxKeys <= 100000)

  final private case class Window(start: FiniteDuration, count: Int):
    val expiresAt: FiniteDuration = start + WindowDuration

  final private case class State(
      entries: Map[String, Window],
      expiries: TreeMap[FiniteDuration, Set[String]]
  ):
    def add(key: String, window: Window): State =
      copy(
        entries = entries.updated(key, window),
        expiries = expiries.updatedWith(window.expiresAt)(keys => Some(keys.getOrElse(Set.empty) + key))
      )

    def remove(key: String, expiresAt: FiniteDuration): State =
      val nextExpiries = expiries.get(expiresAt) match
        case Some(keys) if keys.sizeIs == 1 => expiries.removed(expiresAt)
        case Some(keys)                     => expiries.updated(expiresAt, keys - key)
        case None                           => expiries
      copy(entries = entries.removed(key), expiries = nextExpiries)

  private object State:
    val empty: State = State(Map.empty, TreeMap.empty)

  private def prune(initial: State, now: FiniteDuration): State =
    @annotation.tailrec
    def loop(current: State, remaining: Int): State =
      current.expiries.headOption match
        case Some((expiresAt, keys)) if remaining > 0 && expiresAt <= now =>
          loop(current.remove(keys.head, expiresAt), remaining - 1)
        case _ => current
    loop(initial, PrunePerAttempt)

  def create(config: Config = Config()): IO[RematchLimiter] =
    create(config, IO.monotonic)

  private[server] def create(config: Config, monotonic: IO[FiniteDuration]): IO[RematchLimiter] =
    Ref.of[IO, State](State.empty).map(new RematchLimiter(_, config, monotonic))
