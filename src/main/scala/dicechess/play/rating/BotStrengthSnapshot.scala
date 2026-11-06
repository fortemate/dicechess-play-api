package dicechess.play.rating

import dicechess.play.server.LeaderRow
import io.circe.Codec

import java.time.Instant

/** Public, immutable rating snapshot stored in PostgreSQL (#150, ADR 008, #170). */
final case class RatingSnapshot(
    category: String,
    epoch: Int,
    anchorSetVersion: String,
    cutoff: Instant,
    computedAt: Instant,
    isCurrent: Boolean,
    leaders: List[LeaderRow]
) derives Codec.AsObject

final case class RatingSnapshotSummary(
    category: String,
    epoch: Int,
    anchorSetVersion: String,
    cutoff: Instant,
    computedAt: Instant,
    isCurrent: Boolean,
    entryCount: Int
) derives Codec.AsObject

type BotStrengthSnapshot        = RatingSnapshot
type BotStrengthSnapshotSummary = RatingSnapshotSummary
