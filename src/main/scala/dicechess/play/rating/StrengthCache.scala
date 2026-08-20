package dicechess.play.rating

import cats.effect.{IO, Ref}

/** The last computed [[StrengthReport]], held in memory rather than rebuilt per request (#181): `StrengthReport.build`
  * folds the full `game_results` history and its Bradley-Terry ranking runs a four-figure bootstrap by default, both
  * too expensive to pay on an unauthenticated route's request path. [[RatingBatch]] is the only production writer,
  * refreshing on its own polling cadence; `StrengthRoutes` only ever reads. A trait (not a bare `Ref`), the same
  * fakeable-store shape as `BotStore`/`GameResultsStore` elsewhere in this codebase, so route tests can stub `get`
  * without a real batch tick.
  *
  * `get` answers `None` before the first refresh — a fresh boot, or a server running with `RATING_INTERVAL_SECONDS`
  * unset. That coupling is deliberate, not an oversight: turning off rating computation already turns off Glicko
  * updates and ladder auto-park, so turning off the report that rides on the same batch tick is the same idiom, not a
  * new one.
  */
trait StrengthCache:
  def get: IO[Option[StrengthReport]]
  def set(report: StrengthReport): IO[Unit]

object StrengthCache:
  def create: IO[StrengthCache] =
    Ref.of[IO, Option[StrengthReport]](None).map { ref =>
      new StrengthCache:
        def get: IO[Option[StrengthReport]]       = ref.get
        def set(report: StrengthReport): IO[Unit] = ref.set(Some(report))
    }
