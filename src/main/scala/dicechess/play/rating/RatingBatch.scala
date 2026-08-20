package dicechess.play.rating

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.core.{Principal, RatingCategory, Termination}
import dicechess.play.ingest.PlaysiteIngest
import dicechess.play.store.{
  BotRating,
  BotStore,
  CategoryMove,
  GameResultRow,
  GameResultsStore,
  RatedIdentity,
  RatingStore,
  RatingUpdate,
  UserRating,
  UserStore
}

import java.time.Instant
import scala.concurrent.duration.*

/** The Glicko-2 rating batch (#119): a single background fiber that drains the claim queue of rated, not-yet-applied
  * `game_results` rows (oldest first) and updates both participants' `bots.glicko_*` state — per game, in one
  * transaction with the row's `rating_applied_at` stamp, so a crash can neither double-apply a game nor lose one side's
  * update. Runs OUTSIDE the game write-path (the roadmap's "zero load on the game flow" holds); single fiber = single
  * writer, so rating reads-then-writes never race themselves.
  *
  * Every game is its own one-game rating period for both participants, applied in `finished_at` order — see
  * [[Glicko2]]'s doc for why (and for the deliberate absence of idle RD inflation). Both sides update from the same
  * PRE-game snapshots, the standard simultaneous treatment.
  *
  * Games the update can never apply — a participant that is not a registered bot (humans arrive with accounts later), a
  * missing result, self-play — are logged and stamped applied anyway: left unstamped they would sit at the head of the
  * queue forever.
  *
  * '''Ladder auto-park (#150)''' rides along here. A bot whose last `ladderTimeoutParkGames` ladder games were all lost
  * on the clock is opted out of the ladder — `on_ladder = false`, exactly what `POST /bot/ladder/leave` writes — which
  * stops a dead bot bleeding rating all night AND stops every opponent it is paired with banking free timeout wins. The
  * check lives in the batch rather than in `LadderScheduler` because the batch already visits every finished rated game
  * exactly once: it costs one extra bounded query on the rare timeout loss and nothing at all otherwise. There is
  * deliberately no auto-rejoin — returning is an explicit `POST /bot/ladder/join` by the owner, since an automatic
  * cooldown would just send a still-offline bot back out to bleed again.
  *
  * The park write is a separate transaction from the rating write it follows, so a crash between them leaves a rating
  * applied and the bot still on the ladder. That is deliberately not worth a shared transaction: the streak is
  * re-evaluated from scratch on the bot's next timeout loss, which an actually-offline bot supplies within a minute.
  *
  * '''The [[StrengthCache]] refresh (#181)''' also rides along here rather than on its own timer or the `/strength`
  * request path, but on its own much slower cadence (#215) — see `tick`'s doc.
  *
  * Constructed through [[RatingBatch.create]] because that refresh cadence is stateful: the batch remembers when it
  * last rebuilt the report and whether games have landed since.
  */
final class RatingBatch private (
    botStore: BotStore,
    userStore: UserStore,
    ratingStore: RatingStore,
    resultsStore: GameResultsStore,
    config: RatingBatch.Config,
    strengthCache: StrengthCache,
    strengthConfig: StrengthReport.Config,
    refreshState: Ref[IO, RatingBatch.RefreshState]
):

  /** One batch tick: drain the queue page by page until a short page says it is drained, then refresh the cached
    * [[StrengthReport]] if one is due.
    *
    * The refresh rides this fiber rather than one of its own — a second fiber rebuilding the same cache would need its
    * own single-writer argument, and this one already visits every finished rated game. What it does NOT ride is this
    * tick's cadence. `StrengthReport.build` folds the full `game_results` history and its Bradley-Terry ranking runs a
    * four-figure bootstrap; on a live ladder "did new rated games land" is true on essentially every tick, so gating
    * the rebuild on that alone rebuilt a sixty-thousand-game corpus every `RATING_INTERVAL_SECONDS` because one or two
    * games had been added to it — a compute-bound job holding a cats-effect worker for a third of all wall-clock time,
    * measured in production (#215). `strengthRefreshInterval` is the real gate now; `appliedAny` only marks the report
    * dirty, and a tick that applies nothing still refreshes a dirty report once the interval has passed, so the last
    * games before an idle stretch are never stranded outside the report.
    *
    * A cold cache is exempt: a fresh boot warms it on the first tick regardless of the interval, since until then
    * `/strength` has nothing at all to answer with.
    */
  def tick: IO[Unit] =
    for
      appliedAny <- drainQueue
      cold       <- strengthCache.get.map(_.isEmpty)
      now        <- IO.monotonic
      due        <- refreshState.modify(RatingBatch.planRefresh(appliedAny, cold, now, config.strengthRefreshInterval))
      _          <- refreshStrengthCache.whenA(due)
    yield ()

  private def drainQueue: IO[Boolean] =
    ratingStore.unappliedRatedGames(config.batchSize).flatMap { games =>
      games.traverse_(applyGame) *> {
        if games.size == config.batchSize then drainQueue.as(true) else IO.pure(games.nonEmpty)
      }
    }

  /** Deliberately non-fatal, the same spirit as `parkIfStreakReached`: a failure here must never re-abort a tick that
    * already committed its rating writes, and retrying immediately buys nothing a fresh full-history fold at the next
    * due refresh doesn't already give for free.
    *
    * Failure does re-arm the dirty flag `planRefresh` just cleared, though. Without that, a refresh that failed would
    * leave the cache stale until the NEXT game happened to land — the games it was rebuilding for would have been
    * marked delivered by a rebuild that never produced anything.
    */
  private def refreshStrengthCache: IO[Unit] =
    resultsStore
      .finishedRatedSince(Instant.EPOCH) // the whole history, every time — a batch snapshot, not an incremental fold
      // `IO.blocking`, even though nothing here blocks (#216). The fold is a single CPU-bound stretch with no
      // suspension points, so on the compute pool it holds one worker start to finish — on the 2-OCPU production box
      // that is HALF the pool, and cats-effect says so out loud once per rebuild ("Your CPU is probably starving").
      // Off the pool, the work still competes for cores with everything else, which is unavoidable and fine; what
      // stops is a game fiber waiting on a worker that will not yield for the length of a batch job. A dedicated
      // single-thread pool would express the CPU-bound nature better, but it needs a `Resource` and therefore new
      // lifecycle plumbing through `Main` for a job that runs four times an hour — not worth the surface.
      .flatMap(rows => IO.blocking(StrengthReport.build(rows, config.strengthCategory, strengthConfig)))
      .flatMap(strengthCache.set)
      .handleErrorWith(error =>
        refreshState.update(_.copy(pending = true)) *>
          Console[IO].errorln(s"[play][rating] strength report refresh failed, keeping the last cached one: $error")
      )

  /** Background loop; start once at boot. Unlike the in-memory sweepers (`Lobby`/`Challenges`), a tick here does real
    * database I/O, so a transient failure is logged and the loop lives on to retry next interval — a poisoned row halts
    * progress at the head of the queue *visibly* (an error per tick), never silently kills rating updates.
    */
  def scheduler(interval: FiniteDuration = config.interval): IO[Unit] =
    (IO.sleep(interval) *> tick.handleErrorWith(error =>
      Console[IO].errorln(s"[play][rating] tick failed, retrying next interval: $error")
    )).foreverM

  /** Apply one rated game, whichever populations its two seats belong to (#248).
    *
    * Eligibility is decided HERE, not at game creation: `game_results.rated` records what the room was told, and this
    * batch is the authority on whether that pairing may actually move a rating. The rules (#279, ADR-0017), each with
    * its own skip reason so an operator asking "why is my rating not moving" gets an answer rather than silence:
    *   - a guest seat is never rated — resetting a guest identity is free, so it would be free rating too;
    *   - an account vs a bot it OWNS never counts — that is farming with extra steps, and the only rule the Glicko-2
    *     scale itself cannot defend against (beating a much weaker opponent already yields close to nothing, but
    *     collusion with your own bot bypasses that entirely).
    *
    * There is deliberately no operator-curated roster gating which BOTS may be rated against a human — rated is the
    * human's own choice at game creation (`CatalogRoutes`/`Lobby`), the same as it always was for human-vs-human. The
    * `rated_for_humans` roster this replaced never worked: the only path a human played a bot through hardcoded
    * `requestedRated = false`, so the roster gated a set of games that could never reach this batch at all (#279).
    */
  private def applyGame(row: GameResultRow): IO[Unit] =
    (participantOf(row.whiteExternalId), participantOf(row.blackExternalId)).flatMapN { (white, black) =>
      (white, black, row.result.flatMap(RatingBatch.scores)) match
        case (Some(w), Some(b), _) if w.identity == b.identity =>
          skip(row, "self-play carries no rating information")
        case (Some(w), Some(b), Some((whiteScore, blackScore))) =>
          (RatingBatch.ineligible(w, b), RatingCategory.ofStored(row.timeControl)) match
            case (Some(why), _) => skip(row, why)
            // Unreachable for a game created after #280 — `GameRegistry.isRated` marks such a game casual, so it never
            // enters this queue. Reachable exactly once, for whatever was already queued when that shipped, and for a
            // stored control this server can no longer parse. Both want the same answer.
            case (None, None) =>
              skip(row, s"uncategorised time control '${row.timeControl}' belongs to no rating scale")
            case (None, Some(category)) =>
              val whiteNew = Glicko2.update(w.glicko, List(Glicko2.Result(b.glicko, whiteScore)))
              val blackNew = Glicko2.update(b.glicko, List(Glicko2.Result(w.glicko, blackScore)))
              categoryMoves(category, w, b, whiteScore, blackScore).flatMap: (whiteCategory, blackCategory) =>
                // Both states travel to the store, not just the new one (#296): the movement is recorded on the
                // game's own row, and `before` is only knowable here — the moment after this write, the tables carry
                // `after`.
                ratingStore.applyRatingUpdate(
                  row.gameId,
                  RatingUpdate(w.identity, w.glicko, whiteNew, Some(whiteCategory)),
                  RatingUpdate(b.identity, b.glicko, blackNew, Some(blackCategory))
                ) *>
                  parkIfOnLadder(w, row) *> parkIfOnLadder(b, row)
        case (Some(_), Some(_), None) => skip(row, "no definite result")
        case _                        =>
          // All three causes named: an operator reading this for a deleted account (#237 makes that reachable — the
          // user: id outlives the row) must not be told it was a guest.
          skip(row, "a participant has no rating state (a guest, an unregistered bot, or a deleted account)")
    }

  /** The per-category half of one game's rating write (#280): the same Glicko-2 step, run again on the scale the game's
    * own time control belongs to, against both seats' PRE-game state on THAT scale.
    *
    * A second full update rather than a reuse of the shared-scale numbers, because the two scales are different states:
    * seeded games-based (V21), a ladder bot's Blitz state starts equal to its shared one and stays equal for as long as
    * only Blitz games are rated, while everything else diverges from the first game it plays elsewhere. Reading both
    * `before` states is what makes the pair a real Glicko-2 update rather than a copy of one.
    *
    * The shared scale is still written beside it, unread by anything since #280's cutover, and deliberately so until
    * the follow-up migration drops those columns: while they exist they must stay current, or rolling this release back
    * would resume from a scale with a hole in it exactly the size of the time it was deployed.
    */
  private def categoryMoves(
      category: RatingCategory,
      white: RatingBatch.Participant,
      black: RatingBatch.Participant,
      whiteScore: Double,
      blackScore: Double
  ): IO[(CategoryMove, CategoryMove)] =
    (
      ratingStore.categoryRatingOf(white.identity, category),
      ratingStore.categoryRatingOf(black.identity, category)
      // `parMapN`, the `PgGameStore.settledRatingsByExternalId` precedent: two independent point reads, possibly in
      // different tables, so a mixed human-vs-bot game costs one round trip rather than two. Reading outside the
      // write transaction is safe for the same reason the shared scale's read is — the batch is a single writer.
    ).parMapN: (whiteBefore, blackBefore) =>
      def move(before: Glicko, opponent: Glicko, score: Double) =
        CategoryMove(category, before, Glicko2.update(before, List(Glicko2.Result(opponent, score))))
      (move(whiteBefore, blackBefore, whiteScore), move(blackBefore, whiteBefore, blackScore))

  /** Resolve one stored external id into the rating state behind it, or `None` when it has none — a guest, an
    * anonymous/unregistered bot, or an account that has since been deleted (#237 makes that reachable: the id stays in
    * `game_results` forever, resolving to nothing).
    */
  private def participantOf(externalId: String): IO[Option[RatingBatch.Participant]] =
    Principal.fromBotExternalId(externalId) match
      case Some(bot) =>
        botStore.ratingOf(bot.team, bot.name).map(_.map(RatingBatch.Participant.OfBot(bot, _)))
      case None =>
        Principal.fromUserExternalId(externalId) match
          case Some(userId) => userStore.ratingOf(userId).map(_.map(RatingBatch.Participant.OfUser(userId, _)))
          case None         => IO.pure(None)

  /** The ladder auto-park check (#150) applies to bots only: a human losing on time is a human losing on time, not a
    * dead endpoint to take off the pairing pool.
    */
  private def parkIfOnLadder(participant: RatingBatch.Participant, row: GameResultRow): IO[Unit] =
    participant match
      case RatingBatch.Participant.OfBot(bot, rating) => parkIfStreakReached(bot, row, rating.onLadder)
      case RatingBatch.Participant.OfUser(_, _)       => IO.unit

  /** The auto-park check for one participant of a just-applied game (#150).
    *
    * Two cheap guards keep the history query off the hot path. Only the loser of a `timeout` game can start or extend a
    * streak, so every other outcome returns without touching the database. And a bot already off the ladder is skipped:
    * without that guard, the whole backlog a night of downtime produces — roughly a game a minute — would re-park an
    * already-parked bot once per game, each with its own UPDATE and its own log line, drowning the one line that
    * carries information. `onLadder` is the value `applyGame` read before the rating write; that write never touches
    * `on_ladder`, so it cannot be stale here.
    */
  private def parkIfStreakReached(bot: Principal.Bot, row: GameResultRow, onLadder: Boolean): IO[Unit] =
    if !onLadder || !RatingBatch.isTimeoutLossFor(row, bot) then IO.unit
    else
      evaluateStreak(bot).handleErrorWith: error =>
        Console[IO].errorln(s"[play][rating] auto-park check for ${bot.team}/${bot.name} failed: $error")

  /** Deliberately non-fatal to the tick. Unlike `applyRatingUpdate`, whose failure leaves the row unstamped and must
    * abort so the next tick retries it, this runs AFTER the rating write has committed: letting a transient query
    * timeout here propagate would abort the rest of the page and delay rating updates for unrelated bots, while
    * retrying it is pointless — the row is already stamped applied and will never come back through the queue. Nothing
    * is lost by giving up on this one game, because the streak is recomputed from scratch on the bot's next timeout
    * loss, which an actually-offline bot supplies within a minute.
    */
  private def evaluateStreak(bot: Principal.Bot): IO[Unit] =
    resultsStore
      .recentResultsFor(bot.externalId, RatingBatch.parkScanLimit(config.ladderTimeoutParkGames))
      .flatMap: recent =>
        park(bot).whenA(RatingBatch.shouldPark(recent, bot, config.ladderTimeoutParkGames))

  private def park(bot: Principal.Bot): IO[Unit] =
    botStore.setOnLadder(bot.team, bot.name, onLadder = false).flatMap {
      case Some(_) =>
        Console[IO].errorln(
          s"[play][rating] auto-parked ${bot.team}/${bot.name} off the ladder: " +
            s"${config.ladderTimeoutParkGames} consecutive fully-timed-out ladder games"
        )
      // Unreachable through `applyGame`, which already required `ratingOf` to find both participants — kept because a
      // future caller without that precondition should get a loud line, not a silent no-op.
      case None =>
        Console[IO].errorln(s"[play][rating] auto-park of ${bot.team}/${bot.name} found no registered bot")
    }

  private def skip(row: GameResultRow, why: String): IO[Unit] =
    Console[IO].errorln(s"[play][rating] game ${row.gameId.value} skipped ($why); stamped applied") *>
      ratingStore.markRatingApplied(row.gameId)

object RatingBatch:

  /** One seat's rating state, whichever population it belongs to (#248) — the batch's uniform view over the two, since
    * they share one scale and differ only in which row a write lands on.
    */
  private[rating] enum Participant:
    case OfBot(bot: Principal.Bot, rating: BotRating)
    case OfUser(userId: String, rating: UserRating)

    def identity: RatedIdentity = this match
      case OfBot(bot, _)     => RatedIdentity.of(bot)
      case OfUser(userId, _) => RatedIdentity.User(userId)

    def glicko: Glicko = this match
      case OfBot(_, rating)  => rating.glicko
      case OfUser(_, rating) => rating.glicko

  /** Why this pairing may NOT move a rating, or `None` when it may. Pure, so the whole policy matrix is testable
    * without a database — and symmetric, so a rule cannot apply to White but not Black.
    *
    * The only remaining rule (#279): a player's game against a bot they OWN never counts, whichever seat it sits in.
    * Guest exclusion happens earlier, in `participantOf` (a guest resolves to no rating state at all, so it never
    * reaches here as a `Participant`); there is no curated-bot check any more — see `applyGame`'s doc for why.
    */
  private[rating] def ineligible(white: Participant, black: Participant): Option[String] =
    def ownBot(user: Participant.OfUser, bot: Participant.OfBot): Option[String] =
      Option.when(bot.rating.ownerExternalId.contains(Principal.User(user.userId).externalId)):
        "a player's game against their own bot is never rated"

    (white, black) match
      case (u: Participant.OfUser, b: Participant.OfBot) => ownBot(u, b)
      case (b: Participant.OfBot, u: Participant.OfUser) => ownBot(u, b)
      case _                                             => None

  /** The only way to build one: the strength refresh cadence needs a `Ref`, so construction is effectful. Named
    * `create` like `StrengthCache.create` rather than `apply`, so a call site cannot read as a bare constructor.
    */
  def create(
      botStore: BotStore,
      userStore: UserStore,
      ratingStore: RatingStore,
      resultsStore: GameResultsStore,
      config: Config,
      strengthCache: StrengthCache,
      strengthConfig: StrengthReport.Config = StrengthReport.Config()
  ): IO[RatingBatch] =
    Ref
      .of[IO, RefreshState](RefreshState.Initial)
      .map(new RatingBatch(botStore, userStore, ratingStore, resultsStore, config, strengthCache, strengthConfig, _))

  /** What the strength refresh has to remember between ticks (#215): when it last rebuilt the report, and whether rated
    * games have landed since. `pending` outlives the tick that set it precisely so a burst of games followed by an idle
    * stretch still gets folded in, instead of waiting for a game that may not come for hours.
    */
  final private[rating] case class RefreshState(lastRefreshAt: Option[FiniteDuration], pending: Boolean)

  private[rating] object RefreshState:
    val Initial: RefreshState = RefreshState(None, pending = false)

  /** The refresh decision as a pure `Ref.modify` step: `(next state, refresh now?)`.
    *
    * `now` is monotonic, so the elapsed comparison is immune to wall-clock jumps. A cold cache refreshes
    * unconditionally — see `tick` — and still stamps `lastRefreshAt`, so a boot that lands mid-interval does not
    * immediately rebuild a second time.
    */
  private[rating] def planRefresh(
      appliedAny: Boolean,
      cold: Boolean,
      now: FiniteDuration,
      interval: FiniteDuration
  )(state: RefreshState): (RefreshState, Boolean) =
    val pending = state.pending || appliedAny
    val due     = state.lastRefreshAt.forall(now - _ >= interval)
    if cold || (pending && due) then (RefreshState(Some(now), pending = false), true)
    else (state.copy(pending = pending), false)

  /** The stored `termination` value that counts towards a park streak, taken from the same mapping that WROTE the
    * column (`PgGameStore.finishedGameOf`) rather than spelled out again here: a literal would let the two drift, and
    * the failure mode of that drift is silent — auto-park would simply stop firing, with nothing logged anywhere.
    */
  private val TimeoutTermination: String = PlaysiteIngest.terminationOf(Termination.Timeout)

  /** Did `bot` lose this game on the clock? Nothing else feeds the park streak: a weak-but-live bot legitimately loses
    * by `king_captured` all day and must never be parked for it (#150).
    */
  private[rating] def isTimeoutLossFor(row: GameResultRow, bot: Principal.Bot): Boolean =
    val lost =
      if row.whiteExternalId == bot.externalId then row.result.contains(-1)
      else if row.blackExternalId == bot.externalId then row.result.contains(1)
      else false
    lost && row.termination == TimeoutTermination

  /** The park rule of #150 as a pure function of `recent` (a bot's newest-first results) — the streak logic is worth
    * testing without a database, and this is the whole of it.
    *
    * Counted by ladder GAME (#190), not by CRN mirror pairing as originally shipped — `ladder` (set by
    * `GameRegistry.create`/`LadderScheduler.startPair`) is what keeps a casual or challenge timeout from ever parking
    * anyone; it replaced `pairingId.isDefined`, the marker CRN pairing happened to also serve as, once pairing itself
    * was dropped. `recent` is already newest-first (`GameResultsStore.recentResultsFor`), and `filter` preserves that
    * order, so no re-sort is needed the way grouping by pairing id used to require.
    */
  private[rating] def shouldPark(recent: List[GameResultRow], bot: Principal.Bot, parkGames: Int): Boolean =
    val ladderGames = recent.filter(_.ladder).take(parkGames)
    // Size first, and load-bearing: `forall` on a shorter list is vacuously true, so a bot with fewer than
    // `parkGames` ladder games to its name would otherwise be parked by a threshold it hasn't even reached.
    ladderGames.sizeIs == parkGames && ladderGames.forall(isTimeoutLossFor(_, bot))

  /** How much history one park check reads. Bounded on purpose: it runs per applied timeout loss, and an on-ladder bot
    * accrues games at roughly one a minute, so an unbounded scan grows without limit — precisely what
    * `recentResultsFor`'s default page size exists to prevent, and what its `UNION` of two `LIMIT`ed index scans is
    * built around.
    *
    * Generous rather than tight, because casual and challenge games share this history and dilute it: `parkGames`
    * itself is the exact ladder-only need, and any bound near it would silently stop parking as soon as a bot mixed in
    * a few non-ladder games. Erring wide is safe in one direction only, which is the useful one — truncation drops the
    * OLDEST games while the rule reads the NEWEST ones, so a too-small window can only ever defer a park to the next
    * timeout loss, never cause a wrong one.
    */
  private[rating] def parkScanLimit(parkGames: Int): Int =
    math.max(GameResultsStore.DefaultRecentLimit, parkGames * 4)

  /** `interval` between queue polls; `batchSize` is the page size of one poll (the tick keeps paging until a short
    * page, so the backlog after downtime still drains in one tick); `ladderTimeoutParkGames` is the auto-park threshold
    * (#150), counted in consecutive fully-timed-out ladder games; `strengthRefreshInterval` is the floor between two
    * [[StrengthReport]] rebuilds (#215).
    */
  final case class Config(
      interval: FiniteDuration,
      batchSize: Int,
      ladderTimeoutParkGames: Int,
      strengthRefreshInterval: FiniteDuration,
      /** Which rating scale `/strength` reports on (#280). Not an env knob and deliberately not one: the report exists
        * to answer "which ladder bot is strongest", so it belongs to whatever the ladder plays, and
        * [[RatingCategory.Default]] is that value pinned by `LadderSchedulerSuite`. A parameter rather than a literal
        * only so a test can fold a different category without a scheduler.
        */
      strengthCategory: RatingCategory = RatingCategory.Default
  )

  object Config:
    val DefaultInterval: FiniteDuration = 60.seconds
    val DefaultBatchSize: Int           = 100
    // Matches the real threshold the previous `ladderTimeoutParkPairs=2` (2 games per pairing) enforced (#190).
    val DefaultLadderTimeoutParkGames: Int = 4

    /** Two orders of magnitude slower than the queue poll, and deliberately so: the report is a batch snapshot of the
      * whole corpus, nothing reads it more than a few times an hour, and every rebuild costs a full pass over every
      * rated game ever played times the bootstrap iteration count. Fifteen minutes of staleness is invisible to
      * `/strength`; the rebuild it replaces was visible in production as periodic fiber starvation (#215).
      */
    val DefaultStrengthRefreshInterval: FiniteDuration = 15.minutes

    val Default: Config =
      Config(DefaultInterval, DefaultBatchSize, DefaultLadderTimeoutParkGames, DefaultStrengthRefreshInterval)

    /** Parse from explicit optional raw values (also used by tests — same split, and the same strictly-positive
      * validation, as `LadderScheduler.Config.fromValues`): a non-positive interval would busy-spin the loop, a
      * non-positive batch size would make every tick a no-op; either is treated as absent/unparseable. An invalid
      * interval disables the batch entirely; an invalid batch size or ladder timeout park games falls back to the
      * default, since they are tuning knobs, not the on/off switch.
      */
    def fromValues(
        intervalSecondsRaw: Option[String],
        batchSizeRaw: Option[String],
        ladderTimeoutParkGamesRaw: Option[String],
        strengthRefreshSecondsRaw: Option[String] = None
    ): Option[Config] =
      intervalSecondsRaw.filter(_.nonEmpty).flatMap(_.toIntOption).filter(_ > 0).map { seconds =>
        val size      = batchSizeRaw.flatMap(_.toIntOption).filter(_ > 0).getOrElse(DefaultBatchSize)
        val parkGames = ladderTimeoutParkGamesRaw
          .flatMap(_.toIntOption)
          .filter(_ > 0)
          .getOrElse(DefaultLadderTimeoutParkGames)
        // Zero is accepted here, unlike every other knob: it means "rebuild on every tick that applied something",
        // which is exactly the pre-#215 behaviour and the only way to ask for it back.
        val strengthRefresh = strengthRefreshSecondsRaw
          .flatMap(_.toIntOption)
          .filter(_ >= 0)
          .map(_.seconds)
          .getOrElse(DefaultStrengthRefreshInterval)
        Config(seconds.seconds, size, parkGames, strengthRefresh)
      }

  /** Opt-in by env, same "absence disables" idiom as `LADDER_INTERVAL_SECONDS`: with `RATING_INTERVAL_SECONDS` unset,
    * no ratings are ever recomputed — and, since auto-park rides on this batch, no bot is ever auto-parked either.
    *
    * `LADDER_TIMEOUT_PARK_GAMES` (#190) replaces `LADDER_TIMEOUT_PARK_PAIRS`, which counted CRN mirror pairings (2
    * games each) — a deployment carrying the old var name over unchanged simply falls back to
    * `DefaultLadderTimeoutParkGames`, sized to match what that deployment's configured pairing count actually enforced.
    * It is named for the feature it governs (the ladder), matching
    * `LADDER_INTERVAL_SECONDS`/`LADDER_MAX_CONCURRENT_GAMES`, not for the component that happens to host the check. The
    * price of that choice is exactly the coupling above — a `LADDER_*` knob that does nothing without a `RATING_*` one
    * — so it is spelled out here and in AGENTS.md rather than left to be discovered on a new deployment.
    */
  def configFromEnv: Option[Config] =
    Config.fromValues(
      sys.env.get("RATING_INTERVAL_SECONDS"),
      sys.env.get("RATING_BATCH_SIZE"),
      sys.env.get("LADDER_TIMEOUT_PARK_GAMES"),
      // A `STRENGTH_*` name on a `RATING_*` config, the mirror image of `LADDER_TIMEOUT_PARK_GAMES` above and for the
      // same reason: the knob is named for the feature it governs, not for the batch that happens to host it. It
      // inherits that var's coupling too — with `RATING_INTERVAL_SECONDS` unset there is no batch, so no report.
      sys.env.get("STRENGTH_REFRESH_INTERVAL_SECONDS")
    )

  /** White-POV stored result → (whiteScore, blackScore) in Glicko terms; `None` for any out-of-vocabulary value. */
  private[rating] def scores(result: Int): Option[(Double, Double)] = result match
    case 1  => Some((1.0, 0.0))
    case 0  => Some((0.5, 0.5))
    case -1 => Some((0.0, 1.0))
    case _  => None
