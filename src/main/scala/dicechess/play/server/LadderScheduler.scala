package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.store.BotStore

import scala.concurrent.duration.*

/** Background matchmaker for the bot rating ladder (#102): on an interval, randomly selects two on-ladder bots and
  * starts one rated game between them, up to a concurrency cap. Pairings are **server-chosen only** — a bot cannot pick
  * its opponent, so an owner can't farm rating with two colluding bots. MVP policy: uniform-random subject to a
  * one-step anti-repeat (don't immediately re-pick the pair just started, unless it's the only pair available);
  * rating-aware pairing (prefer close ratings / high RD) is a future refinement, not required for the ladder to start
  * producing results.
  *
  * Colour balance across a matchup is a scheduling property, not a by-product of one call spawning two games (#190
  * dropped that CRN mirroring — see the issue for why): the anti-repeat state already tracks each bot's most recent
  * opponent, so alternating colours over successive picks is a natural extension if it's ever worth adding; today each
  * tick starts exactly one game with whichever seats `GameRegistry.create` assigns.
  *
  * `config.maxConcurrentGames` is a **server-wide** ceiling on scheduler-started games. It says nothing about any one
  * bot, which is how a single bot came to be seated in three simultaneous games in production and lost them on time
  * (#188). The per-bot half of that is each candidate's own declared capacity (#189), applied by [[SeatGuard]] below.
  */
final class LadderScheduler private (
    botStore: BotStore,
    registry: GameRegistry,
    events: BotEvents,
    guard: SeatGuard,
    inFlight: Ref[IO, Int],
    lastPairedWith: Ref[IO, Map[Principal.Bot, Principal.Bot]],
    config: LadderScheduler.Config
):

  /** One scheduling tick: if under the concurrency cap and at least two *available* bots are on the ladder, start one
    * game. A no-op — not an error — when there's nothing to do yet, whether too few bots are on the ladder, all of them
    * are at their declared capacity, or the server-wide cap is already spent.
    *
    * The capacity filter runs before pairing rather than as a veto after it, so a tick where one candidate is busy
    * still starts a game between two that are free, instead of picking a doomed pair and doing nothing.
    */
  def tick: IO[Unit] =
    inFlight.get.flatMap: running =>
      if running >= config.maxConcurrentGames then IO.unit
      else
        botStore.onLadderCandidates
          .flatMap(guard.availableForLadder)
          .flatMap: pool =>
            pickPair(pool).flatMap:
              case None               => IO.unit
              case Some((botA, botB)) => startPair(botA, botB)

  private def pickPair(pool: List[Principal.Bot]): IO[Option[(Principal.Bot, Principal.Bot)]] =
    if pool.size < 2 then IO.pure(None)
    else
      (shuffled(pool), lastPairedWith.get).mapN: (shuffledPool, recent) =>
        val candidates = shuffledPool.combinations(2).map(l => (l(0), l(1))).toList
        // Checked both ways round: `recent` holds one entry per bot (its single most recent partner), so once a
        // third bot has been paired, one side of a candidate pair can go stale relative to the other (#117 review)
        // — e.g. after A-B then A-C, recent = {A:C, B:A, C:A}; checking only recent(a) would let {A,B} or {B,A}
        // through depending on which happened to land in slot a of this tick's shuffle.
        candidates
          .find((a, b) => recent.get(a).forall(_ != b) && recent.get(b).forall(_ != a))
          .orElse(candidates.headOption)

  private[server] def startPair(botA: Principal.Bot, botB: Principal.Bot): IO[Unit] =
    guard.admissionGuard
      .admitAndCreate(
        registry,
        botA,
        botB,
        config.timeControl,
        GameOrigin.Ladder,
        requestedRated = true,
        ladder = true
      )
      .flatMap:
        case Left(AdmissionGuard.AdmissionError.Busy(msg)) =>
          Console[IO].errorln(s"[play][ladder] pairing skipped (busy): $msg")
        case Left(AdmissionGuard.AdmissionError.Failed(err)) =>
          Console[IO].errorln(s"[play][ladder] pairing failed: $err")
        case Right((id, _)) =>
          // lastPairedWith is updated synchronously, before this method (hence tick) returns: the very next tick's
          // anti-repeat check depends on seeing this pairing immediately, not whenever a forked fiber happens to be
          // scheduled. An earlier version of the #117 inFlight-leak fix moved this update into the forked block
          // below (to close a different gap), which raced it against the next tick and silently broke anti-repeat
          // — caught by a dedicated regression test. Only notifyBoth/awaitEnded — the parts that can genuinely take
          // a while or, for notifyBoth, throw — are forked and guaranteed.
          lastPairedWith.update(current => current + (botA -> botB) + (botB -> botA)) *>
            inFlight.update(_ + 1) *>
            (notifyBoth(botA, botB, id) *> awaitEnded(id))
              .guarantee(inFlight.update(_ - 1))
              .start
              .void

  /** Push `gameStart` to both bots — same advisory-push idiom as `Challenges.accept` — so a listening bot learns
    * immediately; a poll-only bot still discovers the game via `GET /bot/games`.
    */
  private def notifyBoth(botA: Principal.Bot, botB: Principal.Bot, id: GameId): IO[Unit] =
    List(botA, botB).traverse_(events.publish(_, BotEvent.GameStart(id.value)))

  private def awaitEnded(id: GameId): IO[Unit] =
    registry
      .get(id)
      .flatMap:
        case Some(room) => room.result.void
        case None       => IO.unit

  private def shuffled(pool: List[Principal.Bot]): IO[List[Principal.Bot]] =
    IO(scala.util.Random.shuffle(pool))

  /** Background scheduling loop; start once at boot. Same idiom as `Lobby.sweeper`/`Challenges.sweeper`. */
  def scheduler(interval: FiniteDuration = config.interval): IO[Unit] = (IO.sleep(interval) *> tick).foreverM

object LadderScheduler:

  /** `interval` between ticks; `maxConcurrentGames` bounds simultaneously in-flight scheduler-started games;
    * `timeControl` is used for every scheduler-started game — **`Fischer`, not `Unlimited`/`PerMove`**, which are being
    * removed from the client (`rabestro/dicechess-play#99`) and would leave a ladder game with no clock enforcement.
    */
  final case class Config(interval: FiniteDuration, maxConcurrentGames: Int, timeControl: TimeControl)

  object Config:
    val DefaultInterval: FiniteDuration = 60.seconds
    // Matches the real capacity the previous `maxConcurrentPairs=4` (2 games per pair) produced (#190) — chosen so a
    // deployment that hasn't yet renamed its env var keeps today's actual throughput unchanged, not halved.
    val DefaultMaxConcurrentGames: Int  = 8
    val DefaultTimeControl: TimeControl = TimeControl.Fischer(300, 3)
    val Default: Config                 = Config(DefaultInterval, DefaultMaxConcurrentGames, DefaultTimeControl)

    /** Parse from explicit optional raw values (also used by tests — same split as `BotAuth.fromSpec`/`fromEnv`). Both
      * are filtered to strictly positive: a non-positive interval makes `IO.sleep` resolve immediately, busy-spinning
      * `tick` at 100% CPU forever, and a non-positive cap wedges the scheduler at zero throughput with no error
      * surfaced (#117 review) — either is treated the same as an absent/unparseable value. An invalid interval falls
      * through to `None` (scheduler not built at all); an invalid cap falls back to the default instead, since it's a
      * secondary tuning knob rather than the feature's own on/off switch.
      */
    def fromValues(intervalSecondsRaw: Option[String], maxConcurrentGamesRaw: Option[String]): Option[Config] =
      intervalSecondsRaw.filter(_.nonEmpty).flatMap(_.toIntOption).filter(_ > 0).map { seconds =>
        val cap = maxConcurrentGamesRaw.flatMap(_.toIntOption).filter(_ > 0).getOrElse(DefaultMaxConcurrentGames)
        Config(seconds.seconds, cap, DefaultTimeControl)
      }

  /** Opt-in by env, same "absence disables" idiom as `PgGameStore.configFromEnv`/`IngestDeliverer.configFromEnv`: with
    * `LADDER_INTERVAL_SECONDS` unset, the scheduler is never built and no ladder games start automatically.
    *
    * `LADDER_MAX_CONCURRENT_GAMES` (#190) replaces `LADDER_MAX_CONCURRENT_PAIRS`, which counted CRN mirror pairs (2
    * games each) — a deployment carrying the old var name over unchanged simply falls back to
    * `DefaultMaxConcurrentGames`, sized to match what that deployment's configured pair count actually produced.
    */
  def configFromEnv: Option[Config] =
    Config.fromValues(sys.env.get("LADDER_INTERVAL_SECONDS"), sys.env.get("LADDER_MAX_CONCURRENT_GAMES"))

  def create(
      botStore: BotStore,
      registry: GameRegistry,
      events: BotEvents,
      config: Config = Config.Default,
      guard: Option[SeatGuard] = None
  ): IO[LadderScheduler] =
    (Ref.of[IO, Int](0), Ref.of[IO, Map[Principal.Bot, Principal.Bot]](Map.empty))
      .mapN((inFlight, lastPairedWith) =>
        new LadderScheduler(
          botStore,
          registry,
          events,
          guard.getOrElse(SeatGuard(botStore, registry)),
          inFlight,
          lastPairedWith,
          config
        )
      )
