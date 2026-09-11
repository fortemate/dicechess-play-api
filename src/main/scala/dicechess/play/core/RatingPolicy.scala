package dicechess.play.core

/** Who sat in a seat, as far as rating is concerned (#146). Derived from the principal's identity shape — never from a
  * name — and persisted on every `game_results` row so a reader can classify a game without resolving anybody.
  */
enum ParticipantKind:
  case Human, Bot, Guest

  def wireName: String = toString.toLowerCase

object ParticipantKind:
  def of(principal: Principal): ParticipantKind = principal match
    case Principal.User(_)   => Human
    case Principal.Bot(_, _) => Bot
    case Principal.Guest(_)  => Guest

  def fromWireName(name: String): Option[ParticipantKind] = values.find(_.wireName == name)

/** Which rating namespace a game may move (ADR 008 and the bot-rating-boundaries ADR, #146):
  *   - `Competitive` — a canonical rating: the human competitive rating for two accounts, the bot competitive rating
  *     for two registered bots (under the matrix policy only when the ladder scheduler paired them);
  *   - `Training` — a human against a bot: no canonical rating moves; the row is the input of the training estimate
  *     (#149), which is a separately named number;
  *   - `Casual` — nothing moves and nothing is estimated: a guest or anonymous seat, self-play, an unbounded control, a
  *     direct bot challenge under the matrix policy, or simply a game nobody asked to have rated.
  *
  * Persisted per row as `rating_domain` and surfaced on the wire as `ratingDomain`. `rated` keeps its meaning of "moves
  * a canonical rating", so `rated = true` implies `Competitive` and a `Training` game reads `rated = false`.
  */
enum RatingDomain:
  case Competitive, Training, Casual

  def wireName: String = toString.toLowerCase

object RatingDomain:
  def fromWireName(name: String): Option[RatingDomain] = values.find(_.wireName == name)

/** What the rating batch did with a `game_results` row (#146) — persisted as `rating_outcome`, so that the
  * `rating_applied_at` stamp alone is never read as evidence of a numeric update:
  *   - `Pending` — rated, not yet visited by the batch;
  *   - `Applied` — both seats' canonical ratings were updated and the movement recorded on the row;
  *   - `Skipped` — visited and stamped, no rating moved; `rating_skip_reason` says why in the batch's own words;
  *   - `Casual` — never queued (`rated = false`), so there was nothing to apply;
  *   - `Legacy` — stamped before outcomes were recorded (V9 backfill). Numbers on the row prove an application; their
  *     absence proves nothing either way, and the row is never reinterpreted.
  */
enum RatingOutcome:
  case Pending, Applied, Skipped, Casual, Legacy

  def wireName: String = toString.toLowerCase

object RatingOutcome:
  def fromWireName(name: String): Option[RatingOutcome] = values.find(_.wireName == name)

/** The eligibility rule set a deployment runs (#146). `Legacy` is the behaviour every game before this policy existed
  * was created under: any two registered, non-anonymous participants may play rated on a bounded control, whatever the
  * pairing. `Matrix` is the approved domain matrix of the bot-rating-boundaries ADR: two accounts compete, two bots
  * compete only when the ladder scheduler paired them, a human against a bot trains, everything else is casual.
  *
  * A versioned switch rather than a flag day, because production cutover belongs to the human owner (#151):
  * `RATING_POLICY=legacy|matrix`, `legacy` when unset. Every row records the `version` it was classified under, so a
  * later reader can tell the two populations apart without knowing when the switch was flipped.
  */
enum RatingPolicy:
  case Legacy, Matrix

  def version: Int = this match
    case Legacy => 1
    case Matrix => 2

  def wireName: String = toString.toLowerCase

/** How a game was classified at creation (#146) — immutable for the life of the game, carried in the snapshot and
  * written to `game_results`. `requestedRated` is what the caller asked for; `rated` is what the game IS (whether a
  * canonical rating may move); `domain` names the namespace; `policy` is the rule set that decided.
  */
final case class GameClassification(
    whiteKind: ParticipantKind,
    blackKind: ParticipantKind,
    requestedRated: Boolean,
    rated: Boolean,
    domain: RatingDomain,
    policy: RatingPolicy
)

object RatingPolicy:

  /** The throwaway bot team (`POST /bot/anon`) — defined here, next to the rule that treats it as anonymous, and
    * re-exported by `BotAuth.AnonTeam` so the two can never disagree.
    */
  val AnonBotTeam: String = "anon"

  /** Environment variable selecting the policy; absent or unparseable means [[Legacy]]. */
  val EnvVar: String = "RATING_POLICY"

  def parse(raw: Option[String]): Option[RatingPolicy] =
    // `Locale.ROOT`: under a Turkish default locale `MATRIX`.toLowerCase is `matrıx`, which would silently fall back
    // to `Legacy` — the wrong eligibility matrix for every new game.
    raw.map(_.trim.toLowerCase(java.util.Locale.ROOT)).flatMap(name => values.find(_.wireName == name))

  def fromEnv: RatingPolicy = parse(sys.env.get(EnvVar)).getOrElse(Legacy)

  /** Whether `p` can sustain a meaningful rating at all: a human guest's identity is free to reset, and an anon-team
    * bot is the same kind of throwaway for bots — resetting either would make rating free.
    */
  def isAnonymous(p: Principal): Boolean = p match
    case Principal.Guest(_)     => true
    case Principal.User(_)      => false
    case Principal.Bot(team, _) => team == AnonBotTeam

  /** Classify one game at creation. Pure, and the whole matrix in one place, so both policies are testable row by row.
    *
    * Common to both policies: an anonymous seat, an unbounded control (`Unlimited`/`PerMove` belong to no scale, #280),
    * self-play and a game nobody asked to have rated are casual. The policies differ only on the pairing:
    *
    * | pairing                           | Legacy             | Matrix                                  |
    * |:----------------------------------|:-------------------|:----------------------------------------|
    * | account vs account                | rated, competitive | rated, competitive                      |
    * | bot vs bot, ladder-scheduled      | rated, competitive | rated, competitive                      |
    * | bot vs bot, direct/seek/challenge | rated, competitive | casual — no canonical rating            |
    * | account vs bot                    | rated, competitive | training — rated=false, domain training |
    *
    * "Ladder-scheduled" is `ladder && origin == Ladder`: the scheduler is the only caller that creates games with the
    * ladder origin, so a caller passing the `ladder` flag alone (which auto-park reads) cannot buy a competitive bot
    * game for a direct challenge. A `Training` classification is the only case where `rated` and `domain` disagree with
    * "rated means some rating": the row is kept for the training estimate (#149) while no canonical rating moves.
    *
    * The kinds describe the seats AS CREATED. A friend-by-link seat can later be claimed by another principal
    * (`GameRegistry.claimSeat`), but only in a game whose seats were the same principal — casual by construction — so
    * the domain and `rated` decided here never go stale; the stored `white_kind`/`black_kind` follow the recorded
    * external ids, i.e. the seats that actually played.
    */
  def classify(
      policy: RatingPolicy,
      white: Principal,
      black: Principal,
      requestedRated: Boolean,
      timeControl: TimeControl,
      origin: GameOrigin,
      ladder: Boolean
  ): GameClassification =
    val whiteKind = ParticipantKind.of(white)
    val blackKind = ParticipantKind.of(black)
    val scheduled = ladder && origin == GameOrigin.Ladder
    val ratable   =
      requestedRated && !isAnonymous(white) && !isAnonymous(black) && white != black &&
        RatingCategory.of(timeControl).isDefined
    val (rated, domain) =
      if !ratable then (false, RatingDomain.Casual)
      else
        policy match
          case Legacy => (true, RatingDomain.Competitive)
          case Matrix =>
            (whiteKind, blackKind) match
              case (ParticipantKind.Human, ParticipantKind.Human) => (true, RatingDomain.Competitive)
              case (ParticipantKind.Bot, ParticipantKind.Bot)     =>
                if scheduled then (true, RatingDomain.Competitive) else (false, RatingDomain.Casual)
              case _ => (false, RatingDomain.Training)
    GameClassification(whiteKind, blackKind, requestedRated, rated, domain, policy)
