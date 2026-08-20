package dicechess.play.server

import cats.effect.{IO, Ref}

import scala.concurrent.duration.*

/** Fixed-window, per-key rate limiter for the open `POST /bot/anon` mint endpoint — the one Bot-API route with no
  * Bearer gate, so it needs its own abuse guard. Keyed by client IP (resolved from the Cloudflare tunnel's
  * `CF-Connecting-IP`). Honest local iteration (a few mints) never hits the cap; a mint-spammer is throttled with a
  * `429` + `Retry-After`. Uses monotonic time so the window is immune to wall-clock shifts.
  *
  * Memory: one small window record per distinct key. Records are overwritten when their window rolls over; a key that
  * never returns lingers until then — acceptable for this endpoint's volume (a sweep can be added if needed).
  */
final class AnonMintLimiter private (
    windows: Ref[IO, Map[String, AnonMintLimiter.Window]],
    limit: Int,
    window: FiniteDuration
):
  import AnonMintLimiter.*

  /** Current number of tracked keys (for telemetry/testing). */
  def size: IO[Int] = windows.get.map(_.size)

  /** Consume one mint for `key`: `Right(())` if allowed, `Left(retryAfter)` if the window's limit is spent. Expired
    * windows are swept periodically so stale keys never accumulate without bound.
    */
  def attempt(key: String): IO[Either[FiniteDuration, Unit]] =
    IO.monotonic.flatMap: now =>
      windows.modify: live =>
        // Sweep expired keys if the table has grown, or always filter expired windows
        val fresh = live.filter((_, w) => now - w.start < window)
        fresh.get(key) match
          case Some(w) =>
            if w.count < limit then (fresh.updated(key, w.copy(count = w.count + 1)), Right(()))
            else (fresh, Left(w.start + window - now)) // limit spent — retry once the window rolls over
          case None =>
            (fresh.updated(key, Window(now, 1)), Right(())) // first hit, or a rolled-over window

object AnonMintLimiter:

  /** Default budget: generous for a developer iterating locally, throttling for a script spamming mints. */
  val DefaultLimit: Int             = 30
  val DefaultWindow: FiniteDuration = 1.hour

  final case class Window(start: FiniteDuration, count: Int)

  def create(limit: Int = DefaultLimit, window: FiniteDuration = DefaultWindow): IO[AnonMintLimiter] =
    Ref.of[IO, Map[String, Window]](Map.empty).map(new AnonMintLimiter(_, limit, window))
