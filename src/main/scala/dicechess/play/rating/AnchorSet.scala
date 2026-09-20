package dicechess.play.rating

/** Versioned multi-anchor calibration contract for bot strength (#147, ADR 008, #170).
  *
  * Centering Bradley–Terry on the pool mean (mean = 0) shifts the scale when participants join, leave, or improve. An
  * anchor set defines a stationary reference frame over immutable, operator-hosted baseline strategies with fixed
  * depth/node budgets, no opening books, and pinned engine releases.
  */
final case class Anchor(
    identity: String,
    targetElo: Double,
    weight: Double = 1.0,
    description: String = ""
)

final case class AnchorSet(
    version: String,
    epoch: Int,
    category: String,
    anchors: List[Anchor]
):
  val anchorMap: Map[String, Anchor] = anchors.map(a => a.identity -> a).toMap
  val identities: Set[String]        = anchorMap.keySet

  /** Computes the scale calibration offset against present anchors.
    *
    * For a set of raw fitted Elos, the anchor offset aligns the weighted average of present anchors to their target
    * Elos:
    * ```scala
    * offset = sum(w_a * (rawElo(a) - targetElo(a))) / sum(w_a)
    * anchoredElo(p) = rawElo(p) - offset
    * ```
    * Returns `None` if no anchors from this set are present.
    */
  def scaleOffset(rawElos: Map[String, Double]): Option[Double] =
    val present = anchors.filter(a => rawElos.contains(a.identity))
    if present.isEmpty then None
    else
      val weightedDiffSum = present.map(a => a.weight * (rawElos(a.identity) - a.targetElo)).sum
      val totalWeight     = present.map(_.weight).sum
      if totalWeight > 0.0 then Some(weightedDiffSum / totalWeight) else None

object AnchorSet:

  /** Anchor Set Version 1.0 for Blitz (#170 specification).
    *
    * Contains 4 immutable benchmark identities spanning beginner to strong amateur:
    *   1. `anchor/random` (~ -610 Elo): minimal baseline of legal moves (RandomSearch).
    *   2. `rabestro/java-baseline` (~ -280 Elo): simple 1-ply ONNX model (dicechess-bot-java).
    *   3. `cloudflare/greedy` (~ -125 Elo): 1-ply static material heuristic (GreedySearch).
    *   4. `anchor/aggressive` (~ -35 Elo): 1-ply attack-oriented heuristic without book (AggressiveSearch).
    *
    * The team is `anchor`, not `house`, because `house` is a RESERVED team ([[dicechess.play.server.BotAuth]]
    * `ReservedTeams`) held by the static `PLAY_BOT_TOKENS` roster. Those identities have no `bots` row, so they carry
    * no rating and no declared capacity and can never be ladder candidates — an anchor that cannot be scheduled cannot
    * anchor anything. The `anchor` team is self-registrable, which is all these need.
    *
    * `rabestro/java-baseline` and `cloudflare/greedy` deliberately keep their own teams: both are already registered
    * and playing, and renaming either would mint a new identity and throw away the history that makes it an anchor in
    * the first place — `cloudflare/greedy` alone carries over 63k games. An anchor set is a list of identities, not a
    * team prefix.
    *
    * The DEPLOYED roster decides which identity fills which role (`fortemate/dicechess-bots-deno`, `src/bots.ts`). An
    * identity named here that nobody runs matches nothing in `scaleOffset` and is not recognised by
    * `TrainingEstimate.resolveBotReference`, so it silently shrinks the anchor set rather than failing.
    */
  val V1_0: AnchorSet = AnchorSet(
    version = "v1.0",
    epoch = 1,
    category = "blitz",
    anchors = List(
      Anchor("anchor/random", -610.0, 0.25, "Built-in RandomSearch (lower boundary of legal play)"),
      Anchor("rabestro/java-baseline", -280.0, 0.25, "1-ply simple ONNX value model (dicechess-bot-java)"),
      Anchor("cloudflare/greedy", -125.0, 0.25, "1-ply static material heuristic (GreedySearch)"),
      Anchor("anchor/aggressive", -35.0, 0.25, "1-ply attack heuristic without book (AggressiveSearch)")
    )
  )

  /** Default active anchor set across the ladder. */
  val Default: AnchorSet = V1_0

  /** Fallback unanchored anchor set for categories without a pinned baseline. */
  def unanchored(category: String): AnchorSet =
    AnchorSet(
      version = s"unanchored-$category",
      epoch = 0,
      category = category,
      anchors = Nil
    )
