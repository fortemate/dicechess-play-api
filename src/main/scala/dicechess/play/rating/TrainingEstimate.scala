package dicechess.play.rating

import dicechess.play.core.RatingCategory
import java.time.Instant

/** A user's training state against calibrated bots in one time-control category (#149, ADR 008, #170).
  *
  * Strict domain isolation: completely separated from the user's competitive rating (`user_ratings`), bot ratings
  * (`bot_ratings`), and immutable scale anchors (`AnchorSet`).
  */
final case class TrainingState(
    rating: Double,
    deviation: Double,
    volatility: Double,
    games: Int,
    wins: Int,
    draws: Int,
    losses: Int,
    updatedAt: Option[Instant] = None
):
  def glicko: Glicko = Glicko(rating, deviation, volatility)

  /** Whether the training estimate is provisional (< 10 games or RD > 110). */
  def isProvisional: Boolean =
    games < TrainingEstimate.MinGamesThreshold || deviation > TrainingEstimate.ProvisionalDeviationThreshold

  /** Conservative rating estimate (rating - 2*RD) for ranking or display. */
  def conservativeRating: Double = Glicko2.conservativeRating(rating, deviation)

  /** 95% confidence interval [rating - 1.96*RD, rating + 1.96*RD]. */
  def confidenceInterval95: (Double, Double) = Glicko2.confidenceInterval95(rating, deviation)

object TrainingState:
  val Initial: TrainingState = TrainingState(
    rating = Glicko.Initial.rating,
    deviation = Glicko.Initial.deviation,
    volatility = Glicko.Initial.volatility,
    games = 0,
    wins = 0,
    draws = 0,
    losses = 0,
    updatedAt = None
  )

object TrainingEstimate:
  /** Minimum completed games before an estimate can be considered non-provisional. */
  val MinGamesThreshold: Int = 10

  /** Deviation threshold above which an estimate is considered provisional. */
  val ProvisionalDeviationThreshold: Double = Glicko2.ProvisionalDeviationThreshold // 110.0

  /** System constant tau for training Glicko-2 updates. */
  val DefaultTau: Double = Glicko2.DefaultTau // 0.3

  /** First-move White advantage adjustment (+25.0 Elo). */
  val WhiteAdvantage: Double = Glicko2.DefaultWhiteAdvantage // 25.0

  /** Fixed reference RD assigned to immutable benchmark scale anchors. */
  val AnchorDeviation: Double = 50.0

  /** Resolves a category-matching reference Glicko state for `botKey` ("team/name"). Priority:
    *   1. If anchorSet matches `category` and contains `botKey`, uses fixed anchor target (1500 + targetElo, RD =
    *      50.0).
    *   2. Otherwise, uses `storedBotRating` if present for `category`.
    *   3. If neither is available, returns None — never borrows ratings from another category.
    */
  def resolveBotReference(
      botKey: String,
      category: RatingCategory,
      anchorSet: AnchorSet,
      storedBotRating: Option[Glicko]
  ): Option[Glicko] =
    if anchorSet.category.equalsIgnoreCase(category.wireName) && anchorSet.anchorMap.contains(botKey) then
      val anchor = anchorSet.anchorMap(botKey)
      Some(Glicko(1500.0 + anchor.targetElo, AnchorDeviation, Glicko.Initial.volatility))
    else storedBotRating

  /** Computes the one-way post-game TrainingState for the human player. The bot reference is strictly immutable and
    * untouched.
    */
  def update(
      current: TrainingState,
      botReference: Glicko,
      humanIsWhite: Boolean,
      score: Double,
      gameTime: Instant,
      tau: Double = DefaultTau,
      whiteAdvantage: Double = WhiteAdvantage,
      idleEloPerSqrtDay: Double = Glicko2.DefaultIdleEloPerSqrtDay
  ): TrainingState =
    // 1. Idle RD inflation for returning player
    val idleDays = current.updatedAt match
      case Some(lastUpdated) =>
        val millis = math.max(0L, java.time.Duration.between(lastUpdated, gameTime).toMillis)
        millis.toDouble / (86400.0 * 1000.0)
      case None => 0.0
    val inflatedDeviation = Glicko2.inflateIdleDeviation(current.deviation, idleDays, idleEloPerSqrtDay)
    val preGameHuman      = current.glicko.copy(deviation = inflatedDeviation)

    // 2. White advantage adjustment on opponent reference
    val adjustedOpponent = Glicko2.withWhiteAdvantage(botReference, playerIsWhite = humanIsWhite, whiteAdvantage)

    // 3. Glicko-2 update
    val postGameGlicko = Glicko2.update(preGameHuman, List(Glicko2.Result(adjustedOpponent, score)), tau)

    // 4. Update counters
    val isWin  = score > 0.75
    val isDraw = score >= 0.25 && score <= 0.75
    val isLoss = score < 0.25

    TrainingState(
      rating = postGameGlicko.rating,
      deviation = postGameGlicko.deviation,
      volatility = postGameGlicko.volatility,
      games = current.games + 1,
      wins = current.wins + (if isWin then 1 else 0),
      draws = current.draws + (if isDraw then 1 else 0),
      losses = current.losses + (if isLoss then 1 else 0),
      updatedAt = Some(gameTime)
    )
