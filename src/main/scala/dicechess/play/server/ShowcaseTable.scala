package dicechess.play.server

import cats.effect.std.{Console, Mutex, Supervisor}
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.game.GameRoom
import dicechess.play.store.{ShowcaseClaimOutcome, ShowcaseClaimRecord, ShowcaseStore}

import java.util.UUID
import scala.concurrent.duration.*

/** The singleton showcase table's coordinator (ADR-005 §3, §5, §6, §11; #46): one process-local actor that owns the
  * table's public state machine — `unavailable`, `open`, `live`, `finishing` — and linearises every claim.
  *
  * What it owns and what it merely observes:
  *   - It owns the transitions and the claim. A claim runs under one mutex, so "first visitor wins" is decided by
  *     arrival at that lock, not by a race in the admission guard; the guard's reserved showcase seat is still taken
  *     through [[AdmissionGuard.admitAndCreate]], so the invariant holds even if something else ever seated the bot.
  *   - It observes the game. The room is the only writer of game state, and under
  *     [[dicechess.play.game.Durability.Required]] its `result` fires only once the terminal transaction (final
  *     snapshot, results, archive, outbox) has committed — so "the table reopens only after durable finalisation" is
  *     inherited from the room, not re-implemented here.
  *   - It remembers nothing across a restart. The next human colour, the current game and the idempotency records live
  *     in PostgreSQL behind [[ShowcaseStore]]; [[reconcile]] rebuilds the in-memory phase from them before the table is
  *     ever advertised as `open`.
  *
  * Linearisability is process-local: exactly one serving process may run this, the same boundary [[AdmissionGuard]]
  * documents. The `showcase_table` fence in the store is defence in depth, not a coordinator.
  */
final class ShowcaseTable private (
    val config: ShowcaseConfig,
    registry: GameRegistry,
    guard: AdmissionGuard,
    store: Option[ShowcaseStore],
    botReady: IO[Boolean],
    phase: Ref[IO, ShowcaseTable.Phase],
    watched: Ref[IO, Set[GameId]],
    mutex: Mutex[IO],
    supervisor: Supervisor[IO],
    alert: String => IO[Unit],
    claimGrace: FiniteDuration,
    tickInterval: FiniteDuration
):
  import ShowcaseTable.*

  def currentPhase: IO[Phase] = phase.get

  // ── public read model ────────────────────────────────────────────────────────

  /** The table as `GET /showcase` reports it. Derived from the phase plus what the current room says about itself: a
    * game that has ended in memory but whose completion the coordinator has not yet processed reads as `finishing`, and
    * a room whose required write is stalled reads as `unavailable` (ADR-005 §3 `live -> unavailable`) — the table
    * cannot move, so it must not look claimable or watchable.
    */
  def view: IO[View] =
    phase.get.flatMap:
      case Phase.Unavailable(reason) =>
        IO.pure(View(Status.Unavailable, config.featuredBot, None, None, Some(reason.public)))
      case Phase.Open(next) =>
        IO.pure(View(Status.Open, config.featuredBot, Some(next), None, None))
      case Phase.Live(game) =>
        (game.room.persistenceStalled, game.room.hasEnded, game.room.snapshot).flatMapN: (stalled, ended, state) =>
          if stalled then
            IO.pure(View(Status.Unavailable, config.featuredBot, None, None, Some(UnavailableReason.Stalled.public)))
          else
            IO.pure(
              View(
                if ended then Status.Finishing else Status.Live,
                config.featuredBot,
                Some(game.humanColor.opponent),
                Some(CurrentGame(game.id, state, game.humanColor)),
                None
              )
            )
      case Phase.Finishing(game) =>
        game.room.snapshot.map: state =>
          View(
            Status.Finishing,
            config.featuredBot,
            Some(game.humanColor.opponent),
            Some(CurrentGame(game.id, state, game.humanColor)),
            None
          )

  // ── the claim ────────────────────────────────────────────────────────────────

  /** `POST /showcase/claim`, after the route has authenticated the actor and validated the headers. Linearised: one
    * claim at a time, so concurrent claimants are decided in lock order and exactly one can find the table `open`.
    *
    * `requestHash` fingerprints the request body under the actor; a same-key retry with a different fingerprint is an
    * `Idempotency-Key` reused for a different request and is refused. `clientEntropy`, when given, is offered to the
    * new room as the winner's dice seed — the same `SubmitSeed` the WebSocket client would send, accepted once per
    * seat, so a client that also sends it over the socket loses nothing.
    */
  def claim(actor: Principal, key: UUID, requestHash: String, clientEntropy: Option[String]): IO[ClaimOutcome] =
    mutex.lock.surround(claimLocked(actor, key, requestHash, clientEntropy))

  private def claimLocked(
      actor: Principal,
      key: UUID,
      requestHash: String,
      clientEntropy: Option[String]
  ): IO[ClaimOutcome] =
    store match
      case None     => IO.pure(ClaimOutcome.Unavailable(UnavailableReason.PersistenceMissing))
      case Some(db) =>
        db.findShowcaseClaim(actor.externalId, key)
          .attempt
          .flatMap:
            case Left(error) =>
              failClosed(UnavailableReason.PersistenceFailure(s"claim lookup failed: ${error.getMessage}"))
            case Right(Some(record)) if record.requestHash != requestHash => IO.pure(ClaimOutcome.Conflict)
            case Right(Some(record))                                      => replay(record)
            case Right(None)                                              =>
              phase.get.flatMap:
                case Phase.Unavailable(reason) => IO.pure(ClaimOutcome.Unavailable(reason))
                case Phase.Live(game)          => spectate(db, actor, key, requestHash, game)
                case Phase.Finishing(game)     => spectate(db, actor, key, requestHash, game)
                case Phase.Open(next)          => seatHuman(db, actor, key, requestHash, clientEntropy, next)

  /** A same-key retry answers what the first request committed (ADR-005 §5). A winner is handed its credential again
    * while its game is still on; once the game is over — or was never this table's current game any more — nobody is
    * handed a credential, and the answer is the spectator outcome naming the game the record was about.
    */
  private def replay(record: ShowcaseClaimRecord): IO[ClaimOutcome] =
    currentGame.flatMap: live =>
      record.outcome match
        case ShowcaseClaimOutcome.Claimed =>
          (record.gameId, record.humanColor) match
            case (Some(id), Some(color)) =>
              live.filter(_.id == id) match
                case Some(game) =>
                  game.room.hasEnded.map: ended =>
                    game.room.joinTokens.get(seatOf(color)) match
                      case Some(token) if !ended => ClaimOutcome.Claimed(id, color, token)
                      case _ => ClaimOutcome.Spectating(SpectatingReason.GameEnded, Some(id), watchable = true)
                case None =>
                  IO.pure(ClaimOutcome.Spectating(SpectatingReason.GameEnded, Some(id), watchable = false))
            // Unreachable under the V6 CHECK constraint (a claimed record always carries both); answered honestly anyway.
            case _ => IO.pure(ClaimOutcome.Spectating(SpectatingReason.GameEnded, record.gameId, watchable = false))
        case ShowcaseClaimOutcome.Spectating =>
          IO.pure:
            live match
              case Some(game) if record.gameId.forall(_ == game.id) =>
                ClaimOutcome.Spectating(SpectatingReason.AlreadyClaimed, Some(game.id), watchable = true)
              case _ => ClaimOutcome.Spectating(SpectatingReason.GameEnded, record.gameId, watchable = false)

  /** The table is taken: record the loser's claim so its retry replays, and point it at the game everyone watches. A
    * record that cannot be written costs only idempotency — a retry re-derives the same answer from the table — so it
    * is reported to the operator rather than failing the visitor.
    */
  private def spectate(
      db: ShowcaseStore,
      actor: Principal,
      key: UUID,
      requestHash: String,
      game: LiveGame
  ): IO[ClaimOutcome] =
    db.recordSpectatingClaim(actor.externalId, key, requestHash, Some(game.id))
      .attempt
      .flatMap:
        case Left(error) => alert(s"spectating claim record not written: ${error.getMessage}")
        case Right(())   => IO.unit
      .as(ClaimOutcome.Spectating(SpectatingReason.AlreadyClaimed, Some(game.id), watchable = true))

  /** The winning path (ADR-005 §5, §6, §7 barrier 1). Order matters:
    *
    *   1. the room is created through the reserved showcase admission, and its creation snapshot commits fail-closed
    *      before `admitAndCreate` returns — that is the pre-publication fence;
    *   2. the claim record, the colour advance and the table's current-game pointer commit in ONE store transaction,
    *      fenced on the colour the human was seated on and on no game being current;
    *   3. only then is the phase `live`, the completion watched, and the credential returned.
    *
    * A failure at step 1 has advanced nothing. A failure at step 2 aborts the room it just created (a technical abort,
    * archived as such, so the aborted creation stays auditable) and fails the table closed: the database and the
    * coordinator disagree, and guessing which is right is how a second table gets seated.
    */
  private def seatHuman(
      db: ShowcaseStore,
      actor: Principal,
      key: UUID,
      requestHash: String,
      clientEntropy: Option[String],
      humanColor: Side
  ): IO[ClaimOutcome] =
    val bot            = config.featuredBot.getOrElse(Principal.Bot("", ""))
    val (white, black) = humanColor match
      case Side.White => (actor, bot)
      case Side.Black => (bot, actor)
    guard
      .admitAndCreate(registry, white, black, FixedTimeControl, GameOrigin.Showcase, requestedRated = false)
      .flatMap:
        case Left(AdmissionGuard.AdmissionError.Busy(message)) =>
          failClosed(
            UnavailableReason
              .Irreconcilable(s"the reserved showcase seat was refused while the table was open: $message")
          )
        case Left(AdmissionGuard.AdmissionError.Failed(message)) =>
          if message == GameRegistry.ShowcaseRequiresPersistence then failClosed(UnavailableReason.PersistenceMissing)
          else
            // The room never came to exist, so nothing was published and the colour did not move: a plain 503 that
            // leaves the table open, so the next claim simply tries again.
            alert(s"showcase room creation failed, colour not advanced: $message")
              .as(ClaimOutcome.Unavailable(UnavailableReason.Transient(message)))
        case Right((id, room)) =>
          room.joinTokens.get(seatOf(humanColor)) match
            case None =>
              compensate(id) *> failClosed(
                UnavailableReason.Irreconcilable(s"room ${id.value} minted no human seat token")
              )
            case Some(token) =>
              db.commitShowcaseClaim(actor.externalId, key, requestHash, id, humanColor, humanColor)
                .attempt
                .flatMap:
                  case Right(true) =>
                    val game = LiveGame(id, room, humanColor)
                    transition(Phase.Live(game)) *>
                      watch(game) *>
                      noShowGuard(game) *>
                      clientEntropy.traverse_(seed => room.submit(seatOf(humanColor), GameCommand.SubmitSeed(seed))) *>
                      IO.pure(ClaimOutcome.Claimed(id, humanColor, token))
                  case Right(false) =>
                    compensate(id) *> failClosed(
                      UnavailableReason.Irreconcilable(
                        s"showcase_table rejected the claim for room ${id.value}: the colour or the current game " +
                          "differ from what this process believes"
                      )
                    )
                  case Left(error) =>
                    compensate(id) *>
                      failClosed(UnavailableReason.PersistenceFailure(s"claim commit failed: ${error.getMessage}"))

  /** Abort a room whose claim could not be committed. Runs supervised rather than inline: under the required durability
    * mode the abort is itself a terminal write that retries for as long as the database is away, and a claim response
    * must not hang on it.
    */
  private def compensate(id: GameId): IO[Unit] =
    supervisor.supervise(registry.abortAndDeregister(id)).void

  /** Fail the table closed from an `open` or `unavailable` phase. A live game is left alone: its room is the authority
    * on whether it can continue, and the reconciliation loop re-adopts it from the database if this process's view of
    * it was ever lost.
    */
  private def failClosed(reason: UnavailableReason): IO[ClaimOutcome] =
    phase.get
      .flatMap:
        case Phase.Open(_) | Phase.Unavailable(_) => transition(Phase.Unavailable(reason))
        case _                                    => alert(reason.detail)
      .as(ClaimOutcome.Unavailable(reason))

  private def currentGame: IO[Option[LiveGame]] =
    phase.get.map:
      case Phase.Live(game)      => Some(game)
      case Phase.Finishing(game) => Some(game)
      case _                     => None

  // ── completion ───────────────────────────────────────────────────────────────

  /** Follow one game to its durable end. `room.result` completes only once the terminal transaction has committed
    * (ADR-005 §7 barrier 3), so everything after it is barrier 4: wait for the registry to have released the seat,
    * forget the game as current, and re-evaluate the table. Idempotent per game — a second watcher for the same id is a
    * no-op, and a duplicate completion finds nothing left to clear.
    */
  private def watch(game: LiveGame): IO[Unit] =
    watched
      .modify(ids => (ids + game.id, ids.contains(game.id)))
      .flatMap:
        case true  => IO.unit
        case false =>
          supervisor
            .supervise(
              game.room.result.attempt.void *>
                phase.update {
                  case Phase.Live(live) if live.id == game.id => Phase.Finishing(live)
                  case other                                  => other
                } *>
                log(s"table -> finishing (game ${game.id.value})") *>
                onGameCompleted(game.id)
            )
            .void

  /** Barrier 4 for `gameId` (ADR-005 §7): the seat must be released and the table's current-game pointer cleared before
    * the table can be `open` again. Public within the package so a test can invoke a duplicate completion directly.
    */
  private[server] def onGameCompleted(gameId: GameId): IO[Unit] =
    awaitDeregistered(gameId) *>
      mutex.lock.surround(releaseLocked(gameId)) *>
      reconcile.void

  private def releaseLocked(gameId: GameId): IO[Unit] =
    store.traverse_ { db =>
      clearWithRetry(db, gameId, attempts = ClearAttempts)
    } *>
      phase.update {
        case Phase.Live(game) if game.id == gameId      => Phase.Unavailable(UnavailableReason.Reconciling)
        case Phase.Finishing(game) if game.id == gameId => Phase.Unavailable(UnavailableReason.Reconciling)
        case other                                      => other
      } *>
      watched.update(_ - gameId)

  /** The clear is bookkeeping after a commit that just succeeded, so the database was reachable a moment ago; a few
    * quick retries cover a blip, and past them the stale pointer is left for [[reconcile]] to repair once the store is
    * back — the table is `unavailable` meanwhile, never `open` over a row that still names a finished game.
    */
  private def clearWithRetry(db: ShowcaseStore, gameId: GameId, attempts: Int): IO[Unit] =
    db.clearShowcaseGame(gameId)
      .attempt
      .flatMap:
        case Right(_)                => IO.unit
        case Left(_) if attempts > 1 => IO.sleep(ClearBackoff) *> clearWithRetry(db, gameId, attempts - 1)
        case Left(error)             =>
          alert(s"could not clear current showcase game ${gameId.value}: ${error.getMessage}")

  /** The registry releases the admission on deregistration, which follows `result` on the registry's own fiber. Waiting
    * for it keeps "the table is open" and "the reserved seat is free" from being observed in the wrong order. Bounded:
    * the registry never fails to deregister an ended room, so the cap is only a guard against a hang.
    */
  private def awaitDeregistered(gameId: GameId): IO[Unit] =
    registry
      .get(gameId)
      .flatMap:
        case None    => IO.unit
        case Some(_) => IO.sleep(DeregisterPoll) *> awaitDeregistered(gameId)
      .timeoutTo(DeregisterWait, IO.unit)

  /** ADR-005 §11 (abandoned claim): a winner who never opens the socket within the grace forfeits, exactly as a player
    * who disconnected and did not come back does — the room already ends a game that way, so the seat is resigned
    * through the same command rather than a new termination. Presence is polled rather than subscribed: the room owns
    * it and exposes only a read.
    */
  private def noShowGuard(game: LiveGame): IO[Unit] =
    val seat                      = seatOf(game.humanColor)
    def awaitConnection: IO[Unit] =
      game.room
        .seatConnected(seat)
        .flatMap(connected => if connected then IO.unit else IO.sleep(NoShowPoll) *> awaitConnection)
    supervisor
      .supervise(
        IO.race(awaitConnection, IO.sleep(claimGrace))
          .flatMap:
            case Left(())  => IO.unit
            case Right(()) =>
              game.room.hasEnded.flatMap: ended =>
                if ended then IO.unit
                else
                  log(
                    s"claimant never connected within $claimGrace; forfeiting the human seat of game ${game.id.value}"
                  ) *>
                    game.room.submit(seat, GameCommand.Resign)
      )
      .void

  // ── reconciliation ───────────────────────────────────────────────────────────

  /** Rebuild the phase from durable state (ADR-005 §11 crash recovery) — at boot, before any route is served, and again
    * whenever the table has to decide whether it may open. Exactly one live showcase game in the store means a table to
    * resume as `live`; none means a table that may open if the bot is ready; several is split-brain and fails closed.
    * The bot probe runs OUTSIDE the mutex so a slow webhook never delays a claim.
    */
  def reconcile: IO[Phase] =
    botReady.attempt
      .map(_.getOrElse(false))
      .flatMap(ready => mutex.lock.surround(reconcileLocked(ready))) *> phase.get

  private def reconcileLocked(ready: Boolean): IO[Unit] =
    if !config.enabled then transition(Phase.Unavailable(UnavailableReason.Disabled))
    else
      store match
        case None     => transition(Phase.Unavailable(UnavailableReason.PersistenceMissing))
        case Some(db) =>
          phase.get.flatMap:
            // A live table is governed by its game; nothing here may reopen it.
            case Phase.Live(_) | Phase.Finishing(_) => IO.unit
            case _                                  =>
              db.activeShowcaseGameIds.attempt.flatMap:
                case Left(error) =>
                  transition(
                    Phase.Unavailable(
                      UnavailableReason.PersistenceFailure(s"reconciliation read failed: ${error.getMessage}")
                    )
                  )
                case Right(Nil)       => reopen(db, ready)
                case Right(id :: Nil) => adopt(db, id)
                case Right(many)      =>
                  transition(Phase.Unavailable(UnavailableReason.DuplicateActiveGames(many.size)))

  /** No live showcase game: the table may open — after repairing a current-game pointer left by a crash between the
    * terminal commit and the clear, and only if the bot answered its readiness probe.
    */
  private def reopen(db: ShowcaseStore, ready: Boolean): IO[Unit] =
    db.showcaseTable.attempt.flatMap:
      case Left(error) =>
        transition(Phase.Unavailable(UnavailableReason.PersistenceFailure(s"table read failed: ${error.getMessage}")))
      case Right(record) =>
        record.currentGameId.traverse_ { stale =>
          db.clearShowcaseGame(stale).attempt.void *>
            alert(s"cleared stale current game ${stale.value}: its terminal transaction had committed before a restart")
        } *>
          (if ready then transition(Phase.Open(record.nextHumanColor))
           else transition(Phase.Unavailable(UnavailableReason.BotNotReady)))

  /** Exactly one live showcase game: it must be the room the registry resumed, with a human on one side. The store row
    * is repaired to name it (and to have advanced the colour if the claim transaction never got to), and the table is
    * `live` with its completion watched — the same shape a fresh claim leaves behind.
    */
  private def adopt(db: ShowcaseStore, id: GameId): IO[Unit] =
    registry
      .get(id)
      .flatMap:
        case None =>
          transition(
            Phase.Unavailable(
              UnavailableReason
                .Irreconcilable(s"active showcase game ${id.value} is in the store but not in the registry")
            )
          )
        case Some(room) =>
          room.seating.flatMap: seats =>
            humanColorOf(seats) match
              case None =>
                transition(
                  Phase.Unavailable(UnavailableReason.Irreconcilable(s"showcase game ${id.value} has no human seat"))
                )
              case Some(color) =>
                db.adoptShowcaseGame(id, color)
                  .attempt
                  .flatMap:
                    case Left(error) =>
                      transition(
                        Phase.Unavailable(
                          UnavailableReason.PersistenceFailure(s"could not adopt game ${id.value}: ${error.getMessage}")
                        )
                      )
                    case Right(_) =>
                      val game = LiveGame(id, room, color)
                      transition(Phase.Live(game)) *> watch(game)

  private def humanColorOf(seats: Map[Seat, Principal]): Option[Side] =
    seats.collectFirst:
      case (Seat.White, p) if !p.isInstanceOf[Principal.Bot] => Side.White
      case (Seat.Black, p) if !p.isInstanceOf[Principal.Bot] => Side.Black

  /** The readiness loop: while the table is not live, re-run reconciliation on a timer so a bot that comes back, a
    * database that recovers, or a duplicate that an operator resolved is picked up without a restart — and so a bot
    * that stops answering takes the table out of `open` before a visitor claims a dead table (ADR-005 §9).
    */
  def supervise: IO[Nothing] =
    (IO.sleep(tickInterval) *> tick.attempt.void).foreverM

  private def tick: IO[Unit] =
    phase.get.flatMap:
      case Phase.Live(_) | Phase.Finishing(_)                      => IO.unit
      case Phase.Unavailable(UnavailableReason.Disabled)           => IO.unit
      case Phase.Unavailable(UnavailableReason.PersistenceMissing) => IO.unit
      case _                                                       => reconcile.void

  // ── transitions ──────────────────────────────────────────────────────────────

  /** Every phase change is logged once, and a fail-closed reason that an operator must act on is raised once — on the
    * transition, not on every tick that re-observes it.
    */
  private def transition(next: Phase): IO[Unit] =
    phase
      .getAndSet(next)
      .flatMap: previous =>
        if previous == next then IO.unit
        else
          log(s"table -> ${describe(next)}") *>
            (next match
              case Phase.Unavailable(reason) if reason.operatorSignal => alert(reason.detail)
              case _                                                  => IO.unit)

  private def log(message: String): IO[Unit] = Console[IO].println(s"[play][showcase] $message")

object ShowcaseTable:

  /** The one time control the table plays (ADR-005 §2): 5 minutes plus 3 seconds a move. Not configurable in this
    * release, on purpose — the homepage promises one table with one clock.
    */
  val FixedTimeControl: TimeControl = TimeControl.Fischer(300, 3)

  /** How long a winner has to open its socket before the seat is forfeited (ADR-005 §11). */
  val DefaultClaimGrace: FiniteDuration = 30.seconds

  /** How often an idle table re-checks its preconditions — one small signed POST to the featured bot per tick. */
  val DefaultTickInterval: FiniteDuration = 15.seconds

  /** The readiness probe's own deadline: a warm bot answers a verification echo in well under this, and a table must
    * not stay unadvertised for a whole per-turn window while one probe waits.
    */
  val ProbeTimeout: FiniteDuration = 5.seconds

  private val NoShowPoll: FiniteDuration     = 50.millis
  private val DeregisterPoll: FiniteDuration = 20.millis
  private val DeregisterWait: FiniteDuration = 5.seconds
  private val ClearAttempts: Int             = 5
  private val ClearBackoff: FiniteDuration   = 1.second

  final case class LiveGame(id: GameId, room: GameRoom, humanColor: Side)

  /** The in-memory state machine. `Finishing` is the window between the room's committed end and the coordinator having
    * released the table; a live room that has ended but is not yet here also reads as finishing (see
    * [[ShowcaseTable.view]]).
    */
  enum Phase:
    case Unavailable(reason: UnavailableReason)
    case Open(nextHumanColor: Side)
    case Live(game: LiveGame)
    case Finishing(game: LiveGame)

  /** Why the table is closed. `public` is the coarse word the wire carries — never a hostname, a stack trace or a count
    * of anything; `detail` is for the operator log. `operatorSignal` marks the reasons that need a human.
    */
  enum UnavailableReason(val public: String, val detail: String, val operatorSignal: Boolean):
    case Disabled           extends UnavailableReason("disabled", "SHOWCASE_ENABLED is not true", false)
    case Reconciling        extends UnavailableReason("maintenance", "the table is being reconciled", false)
    case PersistenceMissing extends UnavailableReason("maintenance", "PostgreSQL persistence is not configured", true)
    case PersistenceFailure(cause: String)
        extends UnavailableReason("maintenance", s"PostgreSQL persistence failed: $cause", true)
    case Stalled extends UnavailableReason("maintenance", "the current game's required write is stalled", false)
    case BotNotReady
        extends UnavailableReason(
          "bot_unavailable",
          "the featured bot is not registered, has no webhook, or did not answer the readiness probe",
          true
        )
    case Irreconcilable(cause: String) extends UnavailableReason("maintenance", s"irreconcilable state: $cause", true)
    case DuplicateActiveGames(count: Int)
        extends UnavailableReason("maintenance", s"$count active showcase games found; exactly one is allowed", true)
    case Transient(cause: String) extends UnavailableReason("maintenance", s"transient failure: $cause", false)

  enum Status(val wireName: String):
    case Unavailable extends Status("unavailable")
    case Open        extends Status("open")
    case Live        extends Status("live")
    case Finishing   extends Status("finishing")

  final case class CurrentGame(id: GameId, state: PublicGameState, humanColor: Side)

  /** What `GET /showcase` renders. `featuredBot` is the configured identity (absent only when the showcase is
    * disabled); `nextHumanColor` is present when open, and — as the colour the table will offer next — while a game is
    * on.
    */
  final case class View(
      status: Status,
      featuredBot: Option[Principal.Bot],
      nextHumanColor: Option[Side],
      currentGame: Option[CurrentGame],
      reason: Option[String]
  )

  enum SpectatingReason(val wireName: String):
    case AlreadyClaimed extends SpectatingReason("already_claimed")
    case GameEnded      extends SpectatingReason("game_ended")

  /** The claim's answer. Only `Claimed` ever carries a credential. `watchable` says whether the named game still has a
    * room to connect a spectator socket to.
    */
  enum ClaimOutcome:
    case Claimed(gameId: GameId, color: Side, seatToken: String)
    case Spectating(reason: SpectatingReason, gameId: Option[GameId], watchable: Boolean)
    case Conflict
    case Unavailable(reason: UnavailableReason)

  def seatOf(side: Side): Seat = side match
    case Side.White => Seat.White
    case Side.Black => Seat.Black

  def describe(phase: Phase): String = phase match
    case Phase.Unavailable(reason) => s"unavailable (${reason.detail})"
    case Phase.Open(next)          => s"open (next human colour $next)"
    case Phase.Live(game)          => s"live (game ${game.id.value}, human ${game.humanColor})"
    case Phase.Finishing(game)     => s"finishing (game ${game.id.value})"

  /** The production operator signal: one stderr line per event, prefixed so it can be grepped beside the room's own
    * `[play][persist][required]` lines. Never carries a token, a secret or an address.
    */
  def alertToStderr(message: String): IO[Unit] = Console[IO].errorln(s"[play][showcase] ALERT: $message")

  /** A `Resource` because the table owns the fibers that follow games to completion and enforce the claim grace:
    * releasing it cancels them all. The table starts `unavailable`; call [[ShowcaseTable.reconcile]] before serving it.
    *
    * `store = None` models a deployment without PostgreSQL: the table is permanently unavailable and says so, exactly
    * as ADR-005 §7 requires — never a silent fall-back to memory.
    */
  def create(
      config: ShowcaseConfig,
      registry: GameRegistry,
      guard: AdmissionGuard,
      store: Option[ShowcaseStore],
      botReady: IO[Boolean],
      alert: String => IO[Unit] = alertToStderr,
      claimGrace: FiniteDuration = DefaultClaimGrace,
      tickInterval: FiniteDuration = DefaultTickInterval
  ): Resource[IO, ShowcaseTable] =
    Supervisor[IO](await = false).evalMap { supervisor =>
      (
        Ref.of[IO, Phase](Phase.Unavailable(UnavailableReason.Reconciling)),
        Ref.of[IO, Set[GameId]](Set.empty),
        Mutex[IO]
      ).mapN { (phase, watched, mutex) =>
        new ShowcaseTable(
          config,
          registry,
          guard,
          store,
          botReady,
          phase,
          watched,
          mutex,
          supervisor,
          alert,
          claimGrace,
          tickInterval
        )
      }
    }
