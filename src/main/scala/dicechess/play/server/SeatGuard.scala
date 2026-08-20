package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.Principal
import dicechess.play.store.{BotSeatPolicy, BotStore}

/** Enforces the capacity a bot declared at registration (#189): the half of the bot contract where the *bot* says how
  * much load it can take, mirroring the per-turn window the *server* publishes.
  *
  * **Why seating and not delivery.** Throttling in-flight webhook deliveries looks more precise, but a delivery held
  * back inside a running game burns that bot's clock — and stopping the clock while it queues would turn the limit into
  * free thinking time. Limiting entry into a game has neither problem: a bot is either not seated, or seated and served
  * immediately under honest clocks.
  *
  * **Who is bounded.** Only registered bots, because only they have a row to declare on. Static (`PLAY_BOT_TOKENS`) and
  * anonymous bots are unbounded exactly as before: the house bot must face every quickstart visitor at once, and an
  * anon token is a throwaway test identity. Guests are unbounded here too — the catalog's own one-game rule is a
  * different policy, about a person juggling boards, not about a machine's capacity.
  *
  * **The count can overshoot by one, and that is the right trade.** `activeGames` is derived from live rooms (see
  * `GameRegistry.activeGamesFor`), so two accepts racing through the check can both pass and seat one game too many.
  * The alternative — a reserved counter held across seating — reintroduces exactly the leak the derived count exists to
  * avoid, and pays for it with a bot permanently locked out instead of momentarily one game over. The overshoot
  * disappears on its own as soon as either game ends.
  */
final class SeatGuard(bots: BotStore, registry: GameRegistry):

  /** Whether this participant may be seated in one more game for `purpose`. Always true for anyone without a declared
    * capacity — a static or anonymous bot, or a human.
    */
  def admits(principal: Principal, purpose: SeatGuard.Purpose): IO[Boolean] =
    principal match
      case bot: Principal.Bot =>
        bots.seatPolicyOf(bot.team, bot.name).flatMap {
          case None         => IO.pure(true)
          case Some(policy) => registry.activeGamesFor(bot).map(_ < purpose.allowanceOf(policy))
        }
      case _ => IO.pure(true)

  /** Both sides of a proposed game at once — a seat is only free if nobody at the table is over their limit. */
  def admitsBoth(one: Principal, other: Principal, purpose: SeatGuard.Purpose): IO[Boolean] =
    admits(one, purpose).flatMap(if _ then admits(other, purpose) else IO.pure(false))

  /** The subset of a ladder candidate pool that can take another game right now. The scheduler filters *before*
    * pairing, so a busy bot is skipped this tick and picked up on a later one: capacity shapes how often a bot is
    * paired, it does not excuse it from being rated.
    */
  def availableForLadder(pool: List[BotSeatPolicy]): IO[List[Principal.Bot]] =
    pool
      .traverseFilter(policy =>
        registry.activeGamesFor(policy.bot).map(active => Option.when(active < policy.ladderAllowance)(policy.bot))
      )

  /** What a bot author needs to see to tell a low limit apart from being ignored: the declaration, what the ladder may
    * take of it, and how much is in use this second. `None` for a caller with no registered row.
    */
  def report(bot: Principal.Bot): IO[Option[SeatGuard.Report]] =
    bots.seatPolicyOf(bot.team, bot.name).flatMap {
      case None         => IO.pure(None)
      case Some(policy) => registry.activeGamesFor(bot).map(active => Some(SeatGuard.Report(policy, active)))
    }

object SeatGuard:

  /** How a seat is being claimed, which decides how much of the declared capacity is available for it.
    *
    *   - `Ladder` — the server pairing the bot with another bot. Bounded by `ladderAllowance`, so a bot that is also in
    *     the human catalog keeps a slot free for a person.
    *   - `Direct` — a challenge, a lobby seek, or a catalog game a human started. Bounded by the full declaration:
    *     these are the seats the reservation exists to protect, and a bot-initiated accept is its own consent.
    */
  enum Purpose:
    case Ladder
    case Direct

    def allowanceOf(policy: BotSeatPolicy): Int = this match
      case Ladder => policy.ladderAllowance
      case Direct => policy.maxConcurrentGames

  /** The capacity answer for `GET /bot/capacity`. */
  final case class Report(policy: BotSeatPolicy, activeGames: Int)
