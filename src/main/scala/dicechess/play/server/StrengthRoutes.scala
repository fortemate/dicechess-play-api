package dicechess.play.server

import cats.effect.IO
import dicechess.play.rating.{BradleyTerry, StrengthCache, StrengthReport}
import dicechess.play.store.BotStore
import io.circe.Codec
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*

/** One matchup's pairwise SPRT verdict on the wire — a direct copy of `StrengthReport.Pairwise`'s three nested domain
  * types, with `verdict` flattened to its case name (`"AcceptH1"` / `"AcceptH0"` / `"Continue"`) rather than a nested
  * object: this route is response-only, so a plain `toString` at the mapping site is enough to get there — there is no
  * decoder to write.
  */
final case class PentanomialCounts(n0: Long, n1: Long, n2: Long, n3: Long, n4: Long) derives Codec.AsObject
final case class TrinomialCounts(losses: Long, draws: Long, wins: Long) derives Codec.AsObject
final case class SprtVerdictResult(llr: Double, lower: Double, upper: Double, verdict: String, observations: Long)
    derives Codec.AsObject
final case class PairwiseResult(
    perspective: String,
    opponent: String,
    pairs: PentanomialCounts,
    singles: TrinomialCounts,
    result: SprtVerdictResult
) derives Codec.AsObject

/** One bot's row of the Bradley-Terry pool ranking — a direct copy of `BradleyTerry.Ranked`. `elo` is relative (the
  * pool's mean is 0 by construction, not comparable to the Glicko-2 board's 1500-centred numbers); `losVsNext` is
  * absent for the last row.
  */
final case class RankedBot(player: String, elo: Double, ciLow: Double, ciHigh: Double, losVsNext: Option[Double])
    derives Codec.AsObject

/** The whole cached report: every pairwise SPRT verdict plus the pool ranking.
  *
  * `category` (#280) says which rating scale the report covers — the ladder's. Stated rather than implied for the same
  * reason `/leaderboard` echoes it: this report folds ONE category and counts every other one into `excludedRows`, so a
  * reader comparing that number across releases has no way to interpret a jump without knowing the scope.
  */
final case class StrengthReportResponse(
    category: String,
    pairwise: List[PairwiseResult],
    ranking: List[RankedBot],
    completePairs: Int,
    singles: Int,
    excludedRows: Int
) derives Codec.AsObject

/** Just the matchups involving one bot — the profile-page-sized slice of the same report. */
final case class BotStrengthProfile(team: String, name: String, category: String, pairwise: List[PairwiseResult])
    derives Codec.AsObject

/** Public, unauthenticated read API over the statistical strength report (E.1, #120 / #181): pairwise SPRT verdicts
  * (pentanomial on historical CRN mirror pairs, trinomial on ordinary games since #190 dropped that mechanism) plus a
  * Bradley-Terry pool ranking — the precise, error-rate-bounded complement to the Glicko-2 leaderboard
  * (`LeaderboardRoutes`). See [[dicechess.play.rating.StrengthReport]] for why this exists alongside a rating every bot
  * already has.
  *
  * Reads only ever touch `cache`: `StrengthReport.build` folds the full `game_results` history and its Bradley-Terry
  * ranking runs a four-figure bootstrap by default, both too expensive to pay per request on an unauthenticated route.
  * `RatingBatch` is the sole writer (#181), refreshed on its own polling cadence, never this route's — so a `None`
  * cache (fresh boot, or a server with rating updates disabled) answers `503`, not a synchronous fallback build.
  * Mounted only when persistence is configured, the same DB-only seam as `LeaderboardRoutes`.
  */
object StrengthRoutes:

  def apply(bots: BotStore, cache: StrengthCache): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "strength" =>
        cache.get.flatMap:
          case None         => ServiceUnavailable("strength report not ready yet")
          case Some(report) => Ok(toResponse(report))

      case GET -> Root / "bots" / team / name / "strength" =>
        bots
          .ratingOf(team, name)
          .flatMap:
            case None    => NotFound()
            case Some(_) =>
              cache.get.flatMap:
                case None         => ServiceUnavailable("strength report not ready yet")
                case Some(report) =>
                  val display  = s"$team/$name"
                  val involved = report.pairwise.filter(p => p.perspective == display || p.opponent == display)
                  Ok(BotStrengthProfile(team, name, report.category.wireName, involved.map(toPairwiseResult)))

  private def toResponse(report: StrengthReport): StrengthReportResponse =
    StrengthReportResponse(
      category = report.category.wireName,
      pairwise = report.pairwise.map(toPairwiseResult),
      ranking = report.ranking.map(toRankedBot),
      completePairs = report.completePairs,
      singles = report.singles,
      excludedRows = report.excludedRows
    )

  private def toPairwiseResult(p: StrengthReport.Pairwise): PairwiseResult =
    PairwiseResult(
      perspective = p.perspective,
      opponent = p.opponent,
      pairs = PentanomialCounts(p.pairs.n0, p.pairs.n1, p.pairs.n2, p.pairs.n3, p.pairs.n4),
      singles = TrinomialCounts(p.singles.losses, p.singles.draws, p.singles.wins),
      result = SprtVerdictResult(
        p.result.llr,
        p.result.lower,
        p.result.upper,
        p.result.verdict.toString,
        p.result.observations
      )
    )

  private def toRankedBot(r: BradleyTerry.Ranked): RankedBot =
    RankedBot(r.player, r.elo, r.ciLow, r.ciHigh, r.losVsNext)
