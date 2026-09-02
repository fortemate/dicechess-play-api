package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{AdmissionPurpose, Principal}
import dicechess.play.store.{BotSeatPolicy, BotStore}

/** Enforces the capacity a bot declared at registration (#189, #45): delegates all admission checks to the central
  * atomic [[AdmissionGuard]].
  *
  * Kept as a facade for backwards compatibility across existing routes and suites.
  */
final class SeatGuard(val admissionGuard: AdmissionGuard):

  def this(bots: BotStore, registry: GameRegistry) =
    this {
      val guard = AdmissionGuard.unsafe(bots, registry = Some(registry))
      registry.attachAdmissionGuardSync(guard)
      guard
    }

  /** Whether this participant may be seated in one more game for `purpose`. Always true for anyone without a declared
    * capacity — a static or anonymous bot, or a human.
    */
  def admits(principal: Principal, purpose: SeatGuard.Purpose): IO[Boolean] =
    admissionGuard.admits(principal, purpose)

  /** Both sides of a proposed game at once — a seat is only free if nobody at the table is over their limit. */
  def admitsBoth(one: Principal, other: Principal, purpose: SeatGuard.Purpose): IO[Boolean] =
    admissionGuard.admitsBoth(one, other, purpose)

  /** The subset of a ladder candidate pool that can take another game right now. The scheduler filters *before*
    * pairing, so a busy bot is skipped this tick and picked up on a later one: capacity shapes how often a bot is
    * paired, it does not excuse it from being rated.
    */
  def availableForLadder(pool: List[BotSeatPolicy]): IO[List[Principal.Bot]] =
    admissionGuard.availableForLadder(pool)

  /** What a bot author needs to see to tell a low limit apart from being ignored: the declaration, what the ladder may
    * take of it, and how much is in use this second. `None` for a caller with no registered row.
    */
  def report(bot: Principal.Bot): IO[Option[SeatGuard.Report]] =
    admissionGuard
      .diagnostics(bot)
      .map:
        _.map: d =>
          SeatGuard.Report(
            policy = d.policy,
            activeGames = d.activeGames,
            generalAllowance = d.generalAllowance,
            generalOccupancy = d.generalOccupancy,
            showcaseAllowance = d.showcaseAllowance,
            showcaseOccupancy = d.showcaseOccupancy
          )

object SeatGuard:

  /** How a seat is being claimed, which decides how much of the declared capacity is available for it.
    *
    *   - `Ladder` — the server pairing the bot with another bot. Bounded by `ladderAllowance`, so a bot that is also in
    *     the human catalog keeps a slot free for a person.
    *   - `Direct` — a challenge, a lobby seek, or a catalog game a human started. Bounded by the full declaration:
    *     these are the seats the reservation exists to protect, and a bot-initiated accept is its own consent.
    *   - `Showcase` — singleton showcase table claim against the featured bot.
    */
  type Purpose = AdmissionPurpose
  val Purpose = AdmissionPurpose

  extension (purpose: AdmissionPurpose)
    def allowanceOf(policy: BotSeatPolicy): Int = purpose match
      case AdmissionPurpose.Ladder   => policy.ladderAllowance
      case AdmissionPurpose.Direct   => policy.maxConcurrentGames
      case AdmissionPurpose.Showcase => 1

  /** The capacity answer for `GET /bot/capacity`. */
  final case class Report(
      policy: BotSeatPolicy,
      activeGames: Int,
      generalAllowance: Int = 0,
      generalOccupancy: Int = 0,
      showcaseAllowance: Int = 0,
      showcaseOccupancy: Int = 0
  )

  def apply(guard: AdmissionGuard): SeatGuard = new SeatGuard(guard)

  def apply(bots: BotStore, registry: GameRegistry): SeatGuard =
    new SeatGuard(bots, registry)

  def create(
      bots: BotStore,
      registry: GameRegistry,
      showcaseConfig: ShowcaseConfig = ShowcaseConfig.Disabled
  ): IO[SeatGuard] =
    AdmissionGuard
      .create(bots, showcaseConfig)
      .flatMap: guard =>
        registry.attachAdmissionGuard(guard).as(new SeatGuard(guard))
