package dicechess.play.rating

import cats.effect.{ExitCode, IO, IOApp}
import dicechess.play.core.RatingCategory
import dicechess.play.store.PgGameStore

import java.time.Instant

/** Owner-facing E.1 (#120) report runner — NOT a public endpoint. Loads every rated decided game from `game_results`
  * (via the same store the server uses) and prints [[StrengthReport.render]]: pairwise SPRT verdicts plus the
  * Bradley-Terry pool ranking.
  *
  * Run: `mise run ladder:report [elo0 elo1 alpha beta]` (defaults 0 20 0.05 0.05) with `PLAY_DB_URL` set — read-only
  * against the database. All analysis logic lives in the separately unit-tested `Sprt`/`BradleyTerry`/
  * `StrengthReport`; this file is a thin shell (and is name-excluded from coverage like `Main.scala`).
  */
object LadderReportMain extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    val config = StrengthReport.Config(
      elo0 = args.headOption.flatMap(_.toDoubleOption).getOrElse(0.0),
      elo1 = args.lift(1).flatMap(_.toDoubleOption).getOrElse(20.0),
      alpha = args.lift(2).flatMap(_.toDoubleOption).getOrElse(0.05),
      beta = args.lift(3).flatMap(_.toDoubleOption).getOrElse(0.05)
    )
    PgGameStore.configFromEnv match
      case None =>
        IO.println("[ladder-report] PLAY_DB_URL unset: nothing to report on").as(ExitCode.Error)
      case Some(dbConfig) =>
        PgGameStore
          .resource(dbConfig)
          .use { store =>
            store
              .finishedRatedSince(Instant.EPOCH) // every rated game ever — the corpus IS the input
              // The same scale `/strength` reports on (#280) — the ladder's; see `RatingBatch.Config.strengthCategory`.
              .map(StrengthReport.build(_, RatingCategory.Default, config))
              .flatMap(report => IO.println(StrengthReport.render(report, config)))
          }
          .as(ExitCode.Success)
