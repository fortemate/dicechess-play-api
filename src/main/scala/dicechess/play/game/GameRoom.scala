package dicechess.play.game

import cats.effect.{Deferred, Fiber, IO, Outcome, Ref, Resource}
import cats.effect.std.{Console, Queue}
import cats.syntax.all.*
import dicechess.engine.domain.{GameState, Move}
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.store.{GameSnapshot, TurnRecord}
import fs2.Stream

import java.security.SecureRandom
import scala.concurrent.duration.*

/** The authoritative game room. A single consumer fiber drains an inbox and is the only writer of game state (the
  * mailbox-serialization an actor gives, without the framework); all reads are lock-free via `Ref.get`. Events fan out
  * to every subscriber (both players plus spectators) through per-subscriber bounded queues.
  *
  * The room is transport-agnostic: its only surface is `subscribe` / `submit` / `start`, so a human over WebSocket and
  * a bot over HTTP attach as adapters (see PlayerConnection).
  *
  * Fan-out never back-pressures the writer: each subscriber owns a bounded queue and the writer publishes with a
  * non-blocking `tryOffer`. A subscriber that falls `fanOutBuffer` events behind (a paused, half-open, or dead client,
  * including a 24/7 bot) is dropped — its stream is interrupted — rather than blocking the consumer fiber and freezing
  * the game for everyone.
  */
final class GameRoom private (
    stateRef: Ref[IO, GameRoom.Session],
    inbox: Queue[IO, GameRoom.Msg],
    subscribers: Ref[IO, Map[Long, GameRoom.Subscriber]],
    nextSubscriberId: Ref[IO, Long],
    fanOutBuffer: Int,
    seatTokens: Map[Seat, String],
    idleCheck: FiniteDuration,
    done: Deferred[IO, GameOver],
    presence: Ref[IO, Map[Seat, Int]],
    graceFibers: Ref[IO, Map[Seat, Fiber[IO, Throwable, Unit]]],
    disconnectGrace: FiniteDuration,
    seedGrace: FiniteDuration,
    maxInlinePaths: Int,
    persist: GameSnapshot => IO[Unit]
):
  import GameRoom.*

  /** Current state, then the live event feed — so a late subscriber can still act. The stream completes once the game
    * is over (after emitting the terminal event, or immediately for someone who joins an already-finished game), so the
    * transport can close the connection instead of holding it open forever.
    *
    * A subscriber that registers concurrently with an `emit` may see the same version twice (once in the snapshot, once
    * live); that overlap is intentional — it guarantees at-least-once delivery, and clients de-duplicate by `version`.
    */
  def subscribe: Stream[IO, GameEvent] =
    Stream
      .resource(Resource.make(register)(sub => unregister(sub.id)))
      .flatMap: sub =>
        val snapshot =
          Stream.eval(
            (stateRef.get, IO.monotonic)
              .mapN((s, now) => GameEvent.Snapshot(s.version, s.publicAt(now, maxInlinePaths), snapshotHistory(s)))
          )
        val live = Stream.fromQueueUnterminated(sub.queue)
        (snapshot ++ live)
          .interruptWhen(sub.dropped.get.attempt)
          .takeThrough(event => !isTerminal(event))

  private def register: IO[Subscriber] =
    for
      id      <- nextSubscriberId.getAndUpdate(_ + 1)
      queue   <- Queue.bounded[IO, GameEvent](fanOutBuffer)
      dropped <- Deferred[IO, Unit]
      sub = Subscriber(id, queue, dropped)
      _ <- subscribers.update(_.updated(id, sub))
    yield sub

  private def unregister(id: Long): IO[Unit] = subscribers.update(_.removed(id))

  /** Fan out to every subscriber without ever parking the writer: `tryOffer` is non-blocking, and a subscriber whose
    * queue is full has fallen too far behind, so it is dropped (its stream interrupted) instead of stalling the game.
    */
  private def broadcast(event: GameEvent): IO[Unit] =
    subscribers.get.flatMap: subs =>
      subs.values.toList.traverse_ { sub =>
        sub.queue.tryOffer(event).flatMap {
          case true  => IO.unit
          case false => sub.dropped.complete(()).attempt.void
        }
      }

  private def isTerminal(event: GameEvent): Boolean = event match
    case GameEvent.GameEnded(_, _, _, _) => true
    case GameEvent.Snapshot(_, ps, _)    =>
      ps.status match
        case GameStatus.Ended(_) => true
        case GameStatus.Active   => false
    case _ => false

  /** Hand a command to the writer, stamping it with the receive time so a timed game charges only the player's thinking
    * time — not the queue-wait or the engine validation that follows.
    */
  def submit(seat: Seat, command: GameCommand): IO[Unit] =
    IO.monotonic.flatMap(receivedAt => inbox.offer(Msg.Command(seat, command, receivedAt, reply = None)))

  /** Submit the turn (with optional draw offer) and await the writer's verdict: `Applied` with the `TurnPlayed`
    * version, or `Refused` with the same reason the stream's `Rejected` carries — the synchronous feedback surface for
    * polling bots. The verdict is answered only after the outcome event is persisted and broadcast, so a 200 to the
    * caller is already durable. Stream events are unchanged.
    */
  def submitTurn(seat: Seat, moves: List[String], offerDraw: Boolean = false): IO[TurnVerdict] =
    enqueueTurn(seat, moves, offerDraw).flatten

  /** Linearize a turn command in the room inbox, returning the separate effect that awaits its durable verdict.
    *
    * Webhook delivery uses the split deliberately: its cross-instance registration fence is held only through the
    * current-registration check and this enqueue, then released before awaiting the writer. The writer persists via the
    * same Postgres pool, so holding a database connection while waiting for the verdict could otherwise self-starve the
    * pool. Existing callers use [[submitTurn]] and observe exactly the same behaviour.
    */
  def enqueueTurn(seat: Seat, moves: List[String], offerDraw: Boolean = false): IO[IO[TurnVerdict]] =
    stateRef.get.flatMap: s =>
      // The writer fiber stops once the game ends, so an ended room would never drain the inbox — answer eagerly.
      // (A game ending in the gap between this check and the offer is caught by the caller's verdict timeout.)
      if s.ended then IO.pure(IO.pure(TurnVerdict.Refused("game is over")))
      else
        (Deferred[IO, TurnVerdict], IO.monotonic).flatMapN: (reply, receivedAt) =>
          inbox
            .offer(Msg.Command(seat, GameCommand.SubmitTurn(moves, offerDraw), receivedAt, Some(reply)))
            .as(reply.get)

  /** Respond to a pending draw offer (accept or decline explicitly) and await the writer's verdict. */
  def respondDraw(seat: Seat, accept: Boolean): IO[TurnVerdict] =
    enqueueDrawResponse(seat, accept).flatten

  /** The draw-decision equivalent of [[enqueueTurn]]; see that method for the database-fence rationale. */
  def enqueueDrawResponse(seat: Seat, accept: Boolean): IO[IO[TurnVerdict]] =
    stateRef.get.flatMap: s =>
      if s.ended then IO.pure(IO.pure(TurnVerdict.Refused("game is over")))
      else
        (Deferred[IO, TurnVerdict], IO.monotonic).flatMapN: (reply, receivedAt) =>
          inbox.offer(Msg.Command(seat, GameCommand.RespondDraw(accept), receivedAt, Some(reply))).as(reply.get)

  /** Begin the game (roll the first turn). Call after subscribers have attached. */
  def start: IO[Unit] = inbox.offer(Msg.Begin)

  /** Bind `seat` to the identity that just redeemed its join token, if [[GameRoom.claimable]] allows it (#285). Answers
    * whether the seat was actually rebound, so a caller can log or test the outcome; `false` is the ordinary case, not
    * an error — every game that already has two distinct players says no.
    *
    * Possession of the token still grants PLAY regardless of the answer; this only decides whose name the game is
    * recorded under. `game_results` is written at game end from the final seating, so a rebind here is what puts the
    * game into the right player's history.
    */
  def claimSeat(
      seat: Seat,
      claimer: Principal,
      displayName: Option[String],
      rating: Option[Double] = None
  ): IO[Boolean] =
    stateRef.get.flatMap: s =>
      // The writer fiber stops once the game ends, so an ended room would never drain the inbox — answer eagerly.
      if s.ended || !GameRoom.claimable(s.players, seat, claimer) then IO.pure(false)
      else
        Deferred[IO, Boolean].flatMap: reply =>
          // The game can end in the gap between the check above and this message being consumed, and then nothing
          // completes `reply` — the writer has stopped draining. `submitTurn` survives the same race on its caller's
          // verdict timeout; this path has no timeout to fall back on and its caller is a WebSocket upgrade, so it
          // would hang the handshake. Race the answer against the game's own end instead: losing that race means the
          // claim never applied, which is exactly `false`.
          inbox.offer(Msg.ClaimSeat(seat, claimer, displayName, rating, reply)) *>
            IO.race(reply.get, done.get).map(_.fold(identity, _ => false))

  // ── player presence / reconnect grace ───────────────────────────────────────

  /** A player's live presence on the room: acquired when a transport (a WebSocket) attaches the seat, released when it
    * detaches. A seat may hold several connections at once (e.g. two tabs); only when the *last* one drops does the
    * seat have `disconnectGrace` to reconnect before it forfeits — so a refresh or a brief network blip no longer ends
    * the game. Spectators hold nothing.
    */
  def connection(seat: Seat): Resource[IO, Unit] =
    seat.side match
      case None    => Resource.unit
      case Some(_) => Resource.make(onConnect(seat))(_ => onDisconnect(seat))

  private def onConnect(seat: Seat): IO[Unit] =
    presence.update(m => m.updated(seat, m.getOrElse(seat, 0) + 1)) *> cancelGrace(seat)

  private def onDisconnect(seat: Seat): IO[Unit] =
    presence
      .updateAndGet(m => m.updated(seat, math.max(0, m.getOrElse(seat, 1) - 1)))
      .flatMap(m => if m.getOrElse(seat, 0) == 0 then scheduleForfeit(seat) else IO.unit)

  /** Start the grace timer for a now-unmanned seat. It forfeits only if the seat is *still* unmanned when the timer
    * elapses — the post-sleep re-check makes a reconnect that races the scheduling safe even if `cancelGrace` missed
    * it. Resigning an already-ended game is a no-op in `process`.
    */
  private def scheduleForfeit(seat: Seat): IO[Unit] =
    val forfeit =
      IO.sleep(disconnectGrace) *>
        presence.get.flatMap(m => if m.getOrElse(seat, 0) == 0 then submit(seat, GameCommand.Resign) else IO.unit)
    forfeit.start.flatMap(fib => swapGrace(seat, Some(fib)))

  private def cancelGrace(seat: Seat): IO[Unit] = swapGrace(seat, None)

  /** Install (or clear) a seat's pending grace fiber, cancelling whatever it displaces. */
  private def swapGrace(seat: Seat, next: Option[Fiber[IO, Throwable, Unit]]): IO[Unit] =
    graceFibers
      .modify { m =>
        val prev = m.get(seat)
        val m2   = next.fold(m.removed(seat))(m.updated(seat, _))
        (m2, prev)
      }
      .flatMap(_.traverse_(_.cancel))

  /** Completes when the game ends. */
  def result: IO[GameOver] = done.get

  /** A cheap, non-blocking peek at whether the game has ended yet — unlike `result`, never awaits. */
  def hasEnded: IO[Boolean] = stateRef.get.map(_.ended)

  /** Who is seated where. */
  def seating: IO[Map[Seat, Principal]] = stateRef.get.map(_.players)

  /** Per-seat join tokens minted at creation; the creator distributes them so each player can claim its seat. */
  def joinTokens: Map[Seat, String] = seatTokens

  /** The seat a join token grants, if any — so a WebSocket upgrade is authorized by a secret, not a trusted query
    * param. The tokens are high-entropy random, so a plain comparison is fine.
    */
  def seatFor(token: String): Option[Seat] =
    seatTokens.collectFirst { case (seat, t) if t == token => seat }

  /** The dice commitment (SHA-256 of the server seed), published at game start. */
  def diceCommit: IO[String] = stateRef.get.map(_.dice.commit)

  /** The game's own time control — fixed at creation and never changed, so this is a constant dressed as a read. Exists
    * for `GameRegistry.claimSeat` (#285), which has to sample the claimer's rating on the game's own rating category
    * (#280) and would otherwise pull the whole `PublicGameState` to learn one field.
    */
  def timeControl: IO[TimeControl] = stateRef.get.map(_.timeControl)

  /** Current public state (for a REST snapshot or a freshly-joining client). */
  def snapshot: IO[PublicGameState] =
    (stateRef.get, IO.monotonic).mapN((s, now) => s.publicAt(now, maxInlinePaths))

  /** The full legal-move tree for the pending roll — never capped, unlike the inline `legalMoves` on the events (see
    * `GET /games/{id}/moves`). Empty when no roll is pending or the roll is a forced pass.
    */
  def legalMoves: IO[GameMoves] =
    stateRef.get.map: s =>
      GameMoves(s.version, EngineOps.serialize(s.state), s.pending, s.legalTree)

  // ── consumer fiber ─────────────────────────────────────────────────────────

  /** The writer fiber, supervised: if `consume` ever fails (an engine invariant throws) or is cancelled (shutdown), the
    * game would otherwise be wedged forever — `done` never completes, the registry never evicts the room, and every
    * subscriber stream hangs. So on any non-success outcome we abort the game: publish a terminal `GameEnded` (to close
    * subscriber streams) and complete `done`, exactly once.
    */
  private def supervisedConsume: IO[Unit] =
    consume.guaranteeCase:
      case Outcome.Succeeded(_) => IO.unit // normal end already completed `done` in endGame
      case _                    => abortIfActive

  private def abortIfActive: IO[Unit] =
    stateRef.get.flatMap: s =>
      if s.ended then IO.unit
      else
        val over = GameOver(GameResult.Draw, Termination.Aborted)
        emit(
          s.copy(pending = false, status = GameStatus.Ended(over)),
          v => GameEvent.GameEnded(v, over, Some(s.dice.reveal), Some(s.clientSeedsRevealed))
        ).flatTap(_ => done.complete(over).attempt.void).void

  private def consume: IO[Unit] =
    // No command within the deadline while a turn is pending => the player to move forfeits. The deadline is the mover's
    // remaining clock for a timed game, or the fixed anti-abandonment `idleCheck` for an unlimited one. The timeout is
    // part of the single-writer loop (not a separate fiber), so it can't race the state it acts on.
    stateRef.get
      .flatMap(deadlineFor)
      .flatMap: deadline =>
        inbox.take
          .timeoutTo(deadline, IO.pure(Msg.Timeout))
          .flatMap:
            case Msg.Begin =>
              stateRef.get.flatMap { s =>
                // Idempotent: a Begin never re-rolls (ply > 0) or revives an ended game. Otherwise it marks the game
                // started and rolls as soon as both client seeds are in — which also kicks a game resumed from a
                // snapshot that had started but not yet rolled. If seeds are still missing, `deadlineFor`/`onTimeout`
                // force-start once the seed grace elapses (so a game never stalls).
                if s.ply > 0L || s.ended then IO.unit
                else
                  IO.monotonic.flatMap { now =>
                    val started = if s.started then s else s.copy(started = true, startedAt = Some(now))
                    // Persist `started` (and any seeds already in) before the opening roll, so a roll failure aborts
                    // from current state rather than from stale state (dropping seeds / re-starting).
                    stateRef.set(started) *>
                      (if started.hasAllSeeds then beginTurn(started).flatMap(stateRef.set) else IO.unit)
                  }
              } *> continue
            case Msg.Command(seat, command, receivedAt, reply) =>
              stateRef.get.flatMap(s => process(s, seat, command, receivedAt, reply)).flatMap(stateRef.set) *> continue
            case Msg.Timeout =>
              stateRef.get.flatMap(onTimeout).flatMap(stateRef.set) *> continue
            case Msg.ClaimSeat(seat, claimer, displayName, rating, reply) =>
              stateRef.get.flatMap(s => onClaimSeat(s, seat, claimer, displayName, rating, reply)) *> continue

  /** Bind `seat` to `claimer` and re-emit, so every board relabels that side (`players` rides on the snapshot). Runs on
    * the writer fiber; re-checks [[GameRoom.claimable]] because the public entry point checked it before queueing and
    * the state may have moved since.
    *
    * The old display name is left in `displayNames` rather than removed: it is keyed by external id, so a stale entry
    * for someone no longer seated is unreachable, and dropping it would mean reasoning about whether the departing
    * principal still holds the OTHER seat.
    */
  private def onClaimSeat(
      s: Session,
      seat: Seat,
      claimer: Principal,
      displayName: Option[String],
      rating: Option[Double],
      reply: Deferred[IO, Boolean]
  ): IO[Unit] =
    if !GameRoom.claimable(s.players, seat, claimer) then reply.complete(false).void
    else
      val named = displayName.fold(s.displayNames)(n => s.displayNames.updated(claimer.externalId, n))
      // The claim is the moment the friend seat's identity first exists, so this IS their rating "as of game start" —
      // frozen from here on like every other seat's.
      val ratingsNow = rating.fold(s.ratings)(r => s.ratings.updated(claimer.externalId, r))
      val rebound    = s.copy(players = s.players.updated(seat, claimer), displayNames = named, ratings = ratingsNow)
      // A full Snapshot rather than a bespoke event: `players` already rides on it, so every existing client relabels
      // the seat with no wire addition — and a client that ignores the resend simply keeps drawing what it had.
      //
      // It carries the real turn history, not Nil: a mid-stream Snapshot is indistinguishable from the one sent on
      // subscribe, and a client that rebuilds its move list from it would otherwise wipe the game's history. The
      // version inside the public state is set to the emitted one so the two cannot disagree.
      IO.monotonic
        .flatMap: now =>
          emit(
            rebound,
            v =>
              GameEvent.Snapshot(v, rebound.copy(version = v).publicAt(now, maxInlinePaths), snapshotHistory(rebound))
          )
        .flatMap(_ => reply.complete(true).void)

  /** A turn deadline elapsed: if a turn is genuinely pending, the side to move forfeits; otherwise (idle between turns,
    * or already over) it is a no-op.
    */
  private def onTimeout(s: Session): IO[Session] =
    if s.ended then IO.pure(s)
    // A started game whose opening roll hasn't happened yet: the seed grace elapsed (force-start; a missing seat
    // falls back to its external id, see `seedFor`), or the game was resumed from a snapshot taken in that window.
    else if s.started && s.ply == 0L then beginTurn(s)
    else if !s.pending then IO.pure(s)
    else
      val seat   = EngineOps.activeSeat(s.state)
      val toMove = EngineOps.activeSide(s.state)
      // Flag-fall (timed) or anti-abandonment forfeit (unlimited): both are a loss on time. Zero the flagged seat's
      // clock so a later snapshot reads accurately.
      val flagged = s.copy(remaining = s.remaining.updated(seat, Duration.Zero), turnStartedAt = None)
      endGame(flagged, GameOver(GameResult.Win(toMove.opponent), Termination.Timeout))

  // ── clocks ───────────────────────────────────────────────────────────────────

  /** How long to wait for the mover before forfeiting: the mover's remaining clock for a timed game (minus the time
    * already spent on this turn — so repeated illegal submits can't refill it), or the fixed `idleCheck` cap otherwise.
    * Unlimited games, and the brief windows when no turn is pending, always use `idleCheck`.
    */
  private def deadlineFor(s: Session): IO[FiniteDuration] =
    if s.ended then IO.pure(idleCheck)
    else if s.awaitingSeeds then
      // Time left in the opening seed grace before we force-start with whatever seeds have arrived.
      IO.monotonic.map(now => floorZero(seedGrace - s.startedAt.fold(Duration.Zero: FiniteDuration)(now - _)))
    else if !s.pending then IO.pure(idleCheck)
    else
      turnBudget(s, EngineOps.activeSeat(s.state)) match
        case None         => IO.pure(idleCheck)
        case Some(budget) =>
          IO.monotonic.map: now =>
            val elapsed = s.turnStartedAt.fold(Duration.Zero)(now - _)
            floorZero(budget - elapsed)

  /** The time available to `mover` for the current turn: their bank (SuddenDeath/Fischer), a fresh per-move budget
    * (PerMove), or `None` for Unlimited (no chess clock — the `idleCheck` cap applies instead).
    */
  private def turnBudget(s: Session, mover: Seat): Option[FiniteDuration] =
    s.timeControl match
      case TimeControl.Unlimited      => None
      case TimeControl.PerMove(spm)   => Some(spm.seconds)
      case TimeControl.SuddenDeath(_) => Some(s.remaining.getOrElse(mover, Duration.Zero))
      case TimeControl.Fischer(_, _)  => Some(s.remaining.getOrElse(mover, Duration.Zero))

  /** Start the mover's clock for a turn that has a real decision (a legal move). Forced passes never reach here, so
    * they cost nothing.
    */
  private def startClock(s: Session): IO[Session] =
    IO.monotonic.map(now => s.copy(turnStartedAt = Some(now)))

  private def continue: IO[Unit] =
    stateRef.get.flatMap(s => if s.ended then IO.unit else consume)

  /** Advance the session, write it, persist it, THEN broadcast — so the Ref always reflects the latest published event,
    * and anything a player has seen is already durable (a fixed roll must never un-roll on a crash). A subscriber that
    * registers just after a broadcast reads a current Snapshot (and acts), and one that registers just before catches
    * the live event; broadcasting before the write would let a subscriber in that window miss both and hang.
    */
  private def emit(s: Session, make: Long => GameEvent): IO[Session] =
    val s2 = s.copy(version = s.version + 1)
    stateRef.set(s2) *> persistQuietly(s2) *> broadcast(make(s2.version)).as(s2)

  /** Persistence is availability-first: a store failure is logged and the game plays on in memory (degrading to exactly
    * what the storeless mode offers) rather than wedging the writer fiber mid-game.
    */
  private def persistQuietly(s: Session): IO[Unit] =
    persist(snapshotOf(s)).handleErrorWith(e => Console[IO].errorln(s"[play][persist] snapshot write failed: $e"))

  /** The durable image of the session — enough to resume the room after a restart and, once ended, to hand the game to
    * analytics.
    */
  private def snapshotOf(s: Session): GameSnapshot =
    GameSnapshot(
      version = s.version,
      dfen = EngineOps.serialize(s.state),
      players = s.players,
      seatTokens = seatTokens,
      serverSeed = s.dice.reveal,
      clientSeeds = s.clientSeeds,
      started = s.started,
      ply = s.ply,
      pending = s.pending,
      status = s.status,
      timeControl = s.timeControl,
      rated = Some(s.rated), // always written going forward; only pre-existing rows lack the key (see GameSnapshot)
      ladder = Some(s.ladder),
      remainingMs = s.remaining.map((seat, left) => seat -> left.toMillis),
      lastRoll = s.lastRoll,
      turns = s.turns,
      createdAtEpochMs = s.createdAtEpochMs,
      pendingDrawOffer = s.pendingDrawOffer,
      lastDrawOfferer = s.lastDrawOfferer
    )

  /** Roll for the side to move; publish the roll; auto-pass while there is no legal move. The turn's legal paths are
    * enumerated (and their UCI index and wire tree built) exactly once here and cached on the session — `SubmitTurn`
    * validates with a cache lookup, so a spammed or illegal submit never re-runs the engine's turn generation.
    */
  private def revealDiceAndBegin(s0: Session): IO[Session] =
    // A turn's roll index is its 0-based turn number, and `ply` counts turns begun. On the normal path `ply` still
    // counts completed rolls here, so this turn's index is `ply` itself and `ply` advances below. After the pre-roll
    // gate, `ply` was already advanced to reserve this turn while the roll stayed suspended, so the index is
    // `ply - 1`. Deriving both from one `plyAfter` is what keeps the sequence consecutive: rolling with the
    // already-advanced `ply` skipped an index and dealt the next one to two consecutive turns — after a declined
    // draw offer the offerer, having seen the responder's dice, knew their own next roll in advance (#331).
    val plyAfter      = if s0.pending then s0.ply else s0.ply + 1
    val dice          = s0.dice.roll(plyAfter - 1, s0.seedFor(Seat.White), s0.seedFor(Seat.Black))
    val rolled        = EngineOps.withDice(s0.state, dice)
    val seat          = EngineOps.activeSeat(rolled)
    val (turns, tree) = turnCache(rolled)
    val s1            = s0.copy(
      state = rolled,
      ply = plyAfter,
      pending = true,
      lastRoll = dice,
      legalTurns = turns,
      legalTree = tree,
      pendingDrawOffer = None
    )
    // The new mover's clock has not started ticking yet (startClock runs below), so these are the banks at turn start —
    // including the increment just credited to the side that completed the previous turn.
    val inline   = Option.when(turns.sizeIs <= maxInlinePaths)(tree)
    val emitRoll =
      IO.monotonic.flatMap: now =>
        val clocks = liveClocks(s1, now)
        emit(s1, v => GameEvent.DiceRolled(v, seat, dice, EngineOps.serialize(rolled), clocks, inline))

    emitRoll.flatMap: s2 =>
      if turns.nonEmpty then if s2.turnStartedAt.isDefined then IO.pure(s2) else startClock(s2)
      else
        val passed   = rolled.endTurn()
        val passDfen = EngineOps.serialize(passed)
        val s3       = s2.copy(
          state = passed,
          pending = false,
          legalTurns = Map.empty,
          legalTree = MoveTree.empty,
          turns = s2.turns :+ TurnRecord(s2.ply, colorLetter(seat), dice, Nil, passDfen, Some(0L))
        )
        emit(s3, v => GameEvent.TurnPlayed(v, seat, Nil, passDfen))
          .flatMap(advanceOrEnd)

  /** Either open the pre-roll gate for an active draw offer (suspending auto-roll) or reveal dice immediately. */
  private def beginTurnOrPreRoll(s0: Session): IO[Session] =
    val seat = EngineOps.activeSeat(s0.state)
    s0.pendingDrawOffer match
      case Some(offerer) if offerer != seat =>
        // Pre-roll gate (ADR-0018 v2): auto-roll is suspended while a draw offer is pending for the active seat.
        // The responder's clock runs during their deliberation.
        val s1 = s0.copy(
          ply = s0.ply + 1,
          pending = true,
          lastRoll = Nil,
          legalTurns = Map.empty,
          legalTree = MoveTree.empty
        )
        emit(s1, v => GameEvent.DrawOffered(v, offerer)).flatMap(startClock)
      case _ =>
        revealDiceAndBegin(s0.copy(pendingDrawOffer = None))

  private def beginTurn(s0: Session): IO[Session] = beginTurnOrPreRoll(s0)

  /** After a completed turn (or a pass), either end on a limit/draw or roll the next turn. */
  private def advanceOrEnd(s: Session): IO[Session] =
    if s.state.halfMoveClock >= FiftyMoveHalfMoves then endGame(s, GameOver(GameResult.Draw, Termination.Draw))
    else if s.ply >= MaxPlies then endGame(s, GameOver(GameResult.Draw, Termination.Draw))
    else beginTurnOrPreRoll(s)

  private def endGame(s: Session, over: GameOver): IO[Session] =
    emit(
      s.copy(pending = false, status = GameStatus.Ended(over)),
      v => GameEvent.GameEnded(v, over, Some(s.dice.reveal), Some(s.clientSeedsRevealed))
    ).flatTap(_ => done.complete(over).attempt.void)

  private def process(
      s: Session,
      seat: Seat,
      command: GameCommand,
      receivedAt: FiniteDuration,
      reply: Option[Deferred[IO, TurnVerdict]]
  ): IO[Session] =
    s.status match
      case GameStatus.Ended(_) => answer(reply, TurnVerdict.Refused("game is over")).as(s)
      case GameStatus.Active   =>
        command match
          case GameCommand.Resign =>
            seat.side match
              case Some(loser) => endGame(s, GameOver(GameResult.Win(loser.opponent), Termination.Resign))
              case None        => emit(s, v => GameEvent.Rejected(v, seat, "spectator cannot resign"))

          case GameCommand.SubmitSeed(seed) =>
            seat.side match
              case None    => emit(s, v => GameEvent.Rejected(v, seat, "spectator cannot submit a seed"))
              case Some(_) =>
                // Accept exactly one seed per seat, and only before the opening roll — afterwards the dice are locked.
                if s.ply > 0L || s.clientSeeds.contains(seat) then IO.pure(s)
                else if seed.length < MinSeedChars || seed.length > MaxSeedChars then
                  emit(s, v => GameEvent.Rejected(v, seat, s"seed must be $MinSeedChars..$MaxSeedChars characters"))
                else
                  val seeded = s.copy(clientSeeds = s.clientSeeds.updated(seat, seed))
                  // Persist the accepted seed (no event is emitted for it), then — if both seats are in and the game
                  // already started — open the gate and roll the opening turn from the durable state.
                  if seeded.started && seeded.hasAllSeeds then
                    stateRef.set(seeded) *> persistQuietly(seeded) *> beginTurn(seeded)
                  else persistQuietly(seeded).as(seeded)

          case GameCommand.RespondDraw(accept) =>
            seat.side match
              case None =>
                emit(s, v => GameEvent.Rejected(v, seat, "spectator cannot respond to draw offer"))
                  .flatTap(_ => answer(reply, TurnVerdict.Refused("spectator cannot respond to draw offer")))
              case Some(_) =>
                val active = EngineOps.activeSeat(s.state)
                if !s.pending || seat != active then
                  emit(s, v => GameEvent.Rejected(v, seat, "not your turn"))
                    .flatTap(_ => answer(reply, TurnVerdict.Refused("not your turn")))
                else if !s.pendingDrawOffer.exists(_ != seat) then
                  emit(s, v => GameEvent.Rejected(v, seat, "no draw offer pending"))
                    .flatTap(_ => answer(reply, TurnVerdict.Refused("no draw offer pending")))
                else if accept then
                  endGame(s.copy(pendingDrawOffer = None), GameOver(GameResult.Draw, Termination.Draw))
                    .flatTap(s1 => answer(reply, TurnVerdict.Applied(s1.version)))
                else
                  val s1 = s.copy(pendingDrawOffer = None)
                  emit(s1, v => GameEvent.DrawDeclined(v, seat))
                    .flatMap(revealDiceAndBegin)
                    .flatTap(s2 => answer(reply, TurnVerdict.Applied(s2.version)))

          case GameCommand.SubmitTurn(uci, offerDraw) =>
            // The verdict is answered AFTER the outcome event is emitted (state written, snapshot persisted,
            // broadcast), so a synchronous `Applied` already implies durability.
            if !s.pending || seat != EngineOps.activeSeat(s.state) then
              emit(s, v => GameEvent.Rejected(v, seat, "not your turn"))
                .flatTap(_ => answer(reply, TurnVerdict.Refused("not your turn")))
            else if s.pendingDrawOffer.exists(_ != seat) then
              emit(s, v => GameEvent.Rejected(v, seat, "dice not rolled yet — accept or decline draw offer first"))
                .flatTap(_ =>
                  answer(reply, TurnVerdict.Refused("dice not rolled yet — accept or decline draw offer first"))
                )
            else
              // Validate against the UCI index cached when the turn was rolled — a lookup, never a re-enumeration.
              s.legalTurns.get(uci) match
                case None =>
                  emit(s, v => GameEvent.Rejected(v, seat, "illegal turn"))
                    .flatTap(_ => answer(reply, TurnVerdict.Refused("illegal turn")))
                case Some(path) =>
                  val (next, winner) = EngineOps.applyPath(s.state, path)
                  val nextDfen       = EngineOps.serialize(next)
                  // Stop the mover's clock at the receive time (not now, after validation) and apply the control's bonus
                  // before the next turn rolls and resets the clock for the other side.
                  val (sd, elapsedMs)                   = debit(s, seat, receivedAt)
                  val (newPendingOffer, newLastOfferer) =
                    if offerDraw then
                      if s.lastDrawOfferer.contains(seat) then
                        (None, s.lastDrawOfferer) // Disallowed by alternation: ignored
                      else (Some(seat), Some(seat))
                    else (None, s.lastDrawOfferer) // No new offer

                  emit(
                    sd.copy(
                      state = next,
                      pending = false,
                      legalTurns = Map.empty,
                      legalTree = MoveTree.empty,
                      pendingDrawOffer = newPendingOffer,
                      lastDrawOfferer = newLastOfferer,
                      turns =
                        sd.turns :+ TurnRecord(sd.ply, colorLetter(seat), sd.lastRoll, uci, nextDfen, Some(elapsedMs))
                    ),
                    v => GameEvent.TurnPlayed(v, seat, uci, nextDfen)
                  )
                    .flatMap: s1 =>
                      (winner match
                        case Some(w) => endGame(s1, GameOver(GameResult.Win(w), Termination.KingCaptured))
                        case None    => advanceOrEnd(s1)
                      ).flatTap(_ => answer(reply, TurnVerdict.Applied(s1.version)))

  /** Complete a synchronous reply channel, if the command carried one. */
  private def answer(reply: Option[Deferred[IO, TurnVerdict]], verdict: TurnVerdict): IO[Unit] =
    reply.traverse_(_.complete(verdict).void)

object GameRoom:

  /** Whether `seat` may be rebound to `claimer` (#285).
    *
    * True only while BOTH seats still hold the SAME principal AND the claimer is somebody else. That single condition
    * carries the whole safety argument:
    *
    *   - it matches exactly one shape — friend-by-link, where `POST /games` seats the creator (or one guest id) on both
    *     sides because the second player is authorized by POSSESSING a token rather than by being named;
    *   - every other game (a lobby accept, a challenge, a ladder pairing) starts with two distinct principals, so it
    *     can never rebind and needs no special case here;
    *   - the creator opening their OWN board is a no-op, because they already hold that seat — so testing your own
    *     share link cannot accidentally settle the friend's side onto you;
    *   - once the two seats differ the game has two identities and nothing rebinds again, so a second person handed the
    *     same link cannot take the first claimer's attribution away. They can still play the seat (possession grants
    *     play, unchanged) — they just do not become its recorded identity.
    */
  private[play] def claimable(players: Map[Seat, Principal], seat: Seat, claimer: Principal): Boolean =
    val held = players.values.toSet
    players.contains(seat) && held.sizeIs == 1 && !held.contains(claimer)

  private val MaxPlies           = 5000L
  private val FiftyMoveHalfMoves = 100

  /** Per-subscriber fan-out buffer. A subscriber this many events behind is dropped, never blocking the writer. */
  private val DefaultFanOutBuffer = 256

  /** Anti-abandonment turn cap for **Unlimited** games (and the brief between-turn windows in any game): if the side to
    * move doesn't act within this, it forfeits. Timed controls (SuddenDeath/Fischer/PerMove) instead enforce the real
    * per-side clock; see `deadlineFor`.
    */
  private val DefaultIdleCheck: FiniteDuration = FiniteDuration(120, "seconds")

  /** How long a seat may be without any connection before it forfeits — long enough to ride out a tab refresh or a
    * brief network blip (paired with client auto-reconnect), short enough that a truly-gone player doesn't strand the
    * opponent for long.
    */
  val DefaultDisconnectGrace: FiniteDuration = FiniteDuration(30, "seconds")

  /** How long the opening roll is held for both seats to submit their post-commit dice seed (provably-fair, #13). A
    * real client/bot seeds within milliseconds of connecting, so this only bites a client that never seeds — after it,
    * the game force-starts and any missing seat falls back to its (already-public) external id, so a game never stalls.
    */
  val DefaultSeedGrace: FiniteDuration = FiniteDuration(5, "seconds")

  /** Accepted bounds for a client dice seed (characters). The lower bound asks for real entropy (≥16 chars, e.g. the
    * hex of 8+ random bytes); the upper bound caps abuse. A weak or absent seed only weakens that seat's own
    * contribution — the committed server seed still makes the dice ungrindable.
    */
  private val MinSeedChars = 16
  private val MaxSeedChars = 256

  /** Above this many legal turn paths, the `legalMoves` tree is elided from `DiceRolled`/`Snapshot` (streams are
    * uncompressed and the enumeration's tail reaches tens of thousands of paths) — `GET /games/{id}/moves` always
    * serves the full tree. ~1000 paths keep the inline tree under a few tens of KB and cover the vast majority of turns
    * (p50 is ~160 paths).
    */
  val DefaultMaxInlineTurnPaths: Int = 1000

  /** The per-roll turn cache, built once when the dice land: the UCI→engine-path index `SubmitTurn` validates against
    * (a lookup per submit, never a re-enumeration or re-mapping), and the prebuilt wire tree every snapshot and
    * `GET /games/{id}/moves` read serves as-is.
    */
  private def turnCache(state: GameState): (Map[List[String], List[Move]], MoveTree) =
    val turns = EngineOps.legalMovePaths(state).map(path => path.map(EngineOps.toUci) -> path).toMap
    (turns, MoveTree.fromPaths(turns.keys.toList))

  /** A live subscriber's mailbox plus a one-shot "you fell behind, disconnect" signal. */
  final private case class Subscriber(id: Long, queue: Queue[IO, GameEvent], dropped: Deferred[IO, Unit])

  /** The writer's verdict on a synchronously-submitted turn — the request/response face of `TurnPlayed`/`Rejected`. */
  enum TurnVerdict:
    case Applied(version: Long)
    case Refused(reason: String)

  private enum Msg:
    case Begin
    case Command(
        seat: Seat,
        command: GameCommand,
        receivedAt: FiniteDuration,
        reply: Option[Deferred[IO, TurnVerdict]]
    )
    case Timeout
    // #285: bind a seat to whoever redeemed its join token. Goes through the inbox like every other write — the
    // consumer fiber is the only writer of `players`, and a route handler must never touch it directly.
    case ClaimSeat(
        seat: Seat,
        claimer: Principal,
        displayName: Option[String],
        rating: Option[Double],
        reply: Deferred[IO, Boolean]
    )

  final private[play] case class Session(
      state: GameState,
      version: Long,
      players: Map[Seat, Principal],
      // Display names for the seats, keyed by external id — resolved ONCE when the room is created and carried
      // verbatim afterwards, exactly like `rated`/`ladder`. A room emits a snapshot on every move, so this must never
      // become a lookup: `publicAt` is called far too often for that. The cost of freezing it is that a rename
      // mid-game keeps the old label until the next room, which is the better trade — a board whose opponent label
      // changes under you mid-game is worse than one that is a few minutes stale.
      displayNames: Map[String, String],
      // Settled ratings for the seats, keyed by external id (#290) — resolved once at creation and frozen exactly
      // like `displayNames`, and for the same two reasons: `publicAt` runs on every event, and "your rating as of
      // game start" is the number the issue asks for, so drifting mid-game would be wrong even if it were cheap.
      ratings: Map[String, Double],
      dice: DiceSource,
      ply: Long,
      pending: Boolean,
      status: GameStatus,
      timeControl: TimeControl,
      // Decided once at creation (`GameRegistry.isRated`) and carried verbatim into every snapshot; never
      // recomputed mid-game.
      rated: Boolean = false,
      // Whether the ladder scheduler started this game, as opposed to a direct challenge or a catalog game —
      // decided once at creation, carried verbatim like `rated`. The only marker in `game_results` distinguishing
      // a scheduler-started game, which is what keeps a casual/challenge timeout from ever tripping ladder
      // auto-park (`RatingBatch.shouldPark`, #150).
      ladder: Boolean = false,
      remaining: Map[Seat, FiniteDuration] = Map.empty,
      turnStartedAt: Option[FiniteDuration] = None,
      // Provably-fair dice gate: `started` flips on the first Begin; `startedAt` stamps it (to measure the seed grace);
      // `clientSeeds` collects each seat's post-commit entropy before the opening roll.
      started: Boolean = false,
      startedAt: Option[FiniteDuration] = None,
      clientSeeds: Map[Seat, String] = Map.empty,
      // The dice of the turn in flight (recorded into `turns` when the turn completes) and the completed-turn history
      // — kept for the end-of-game analytics handoff, and persisted so it survives a restart.
      lastRoll: List[Int] = Nil,
      turns: Vector[TurnRecord] = Vector.empty,
      createdAtEpochMs: Option[Long] = None,
      // The roll-in-flight turn cache (see `turnCache`): the UCI index `SubmitTurn` validates against and the
      // prebuilt wire tree. Transient: never persisted (a pure function of the pending `state`), recomputed on
      // restore, cleared when the turn completes.
      legalTurns: Map[List[String], List[Move]] = Map.empty,
      legalTree: MoveTree = MoveTree.empty,
      // Whether a draw offer from the opponent is currently live on this turn (#327). `None` when no offer is pending.
      pendingDrawOffer: Option[Seat] = None,
      // The seat that last offered a draw, enforcing the Option A alternation anti-spam rule (#327).
      lastDrawOfferer: Option[Seat] = None
  ):
    def ended: Boolean = status match
      case GameStatus.Ended(_) => true
      case GameStatus.Active   => false

    /** Every seated player has submitted a client seed. */
    def hasAllSeeds: Boolean = players.keySet.forall(clientSeeds.contains)

    /** The game has begun but is still holding the opening roll, waiting for the seats' client seeds. */
    def awaitingSeeds: Boolean = started && ply == 0L && !ended && !hasAllSeeds

    /** The seed folded in for `seat`: the one it submitted, or — if it never did before the grace elapsed — its
      * external id as a deterministic, already-public fallback (so a missing seed never stalls the game).
      */
    def seedFor(seat: Seat): String = clientSeeds.getOrElse(seat, players.get(seat).fold("")(_.externalId))

    def publicAt(now: FiniteDuration, maxInlinePaths: Int): PublicGameState =
      val (revealed, seeds) = status match
        case GameStatus.Ended(_) => (Some(dice.reveal), Some(clientSeedsRevealed))
        case _                   => (None, None)
      val activeSt      = EngineOps.activeSeat(state)
      val isDrawPending = pending && pendingDrawOffer.exists(_ != activeSt)
      val dicePending   = pending && !isDrawPending
      PublicGameState(
        version,
        EngineOps.serialize(state),
        activeSt,
        dicePending,
        status,
        timeControl,
        liveClocks(this, now),
        dice.commit,
        revealed,
        seeds,
        Option.when(dicePending && legalTurns.sizeIs <= maxInlinePaths)(legalTree),
        // The public faces of the seats: bots by name, registered players by nickname, guests anonymous. The guest
        // rule (and the rating-only-on-a-named-face rule) lives in `PublicPlayer.ofExternalId`, not here.
        (players.get(Seat.White), players.get(Seat.Black))
          .mapN((w, b) =>
            Players(
              PublicPlayer.ofExternalId(w.externalId, displayNames, ratings),
              PublicPlayer.ofExternalId(b.externalId, displayNames, ratings)
            )
          ),
        Some(rated),
        Option.when(isDrawPending)(DrawOffer(pending = true)),
        Option.when(dicePending)(!lastDrawOfferer.contains(activeSt))
      )

    /** The pair of client seeds actually folded into the dice, for the end-of-game reveal. */
    def clientSeedsRevealed: ClientSeeds = ClientSeeds(seedFor(Seat.White), seedFor(Seat.Black))

  /** Create a room, or describe why the initial position is invalid — errors as values. */
  def create(
      players: Map[Seat, Principal],
      dice: DiceSource,
      // Seat display names by external id, resolved by the caller before the room exists (see `Session.displayNames`).
      // Empty means every human renders anonymous, which is the pre-#194 behaviour and what in-memory mode gets.
      displayNames: Map[String, String] = Map.empty,
      // Settled seat ratings by external id, resolved by the caller like `displayNames` (see `Session.ratings`).
      // Empty means no seat shows a rating, which is what in-memory mode gets.
      ratings: Map[String, Double] = Map.empty,
      initialDfen: String = EngineOps.InitialDfen,
      fanOutBuffer: Int = DefaultFanOutBuffer,
      idleCheck: FiniteDuration = DefaultIdleCheck,
      disconnectGrace: FiniteDuration = DefaultDisconnectGrace,
      timeControl: TimeControl = TimeControl.Unlimited,
      // Whether this game should count toward rating — decided by the caller (see `GameRegistry.isRated`) before
      // the room exists; the room itself never judges anonymity, it just carries the flag into every snapshot.
      rated: Boolean = false,
      // Whether the ladder scheduler is starting this game — see `Session.ladder`. `false` outside the ladder.
      ladder: Boolean = false,
      seedGrace: FiniteDuration = DefaultSeedGrace,
      maxInlinePaths: Int = DefaultMaxInlineTurnPaths,
      persist: GameSnapshot => IO[Unit] = _ => IO.unit
  ): IO[Either[String, GameRoom]] =
    EngineOps.parse(initialDfen) match
      case Left(error)   => IO.pure(Left(error))
      case Right(state0) =>
        for
          createdAt <- IO.realTime
          session0 = Session(
            state0,
            0L,
            players,
            displayNames,
            ratings,
            dice,
            0L,
            pending = false,
            GameStatus.Active,
            timeControl,
            rated = rated,
            ladder = ladder,
            remaining = initialRemaining(timeControl, players.keys),
            createdAtEpochMs = Some(createdAt.toMillis)
          )
          seatTokens <- mintTokens(players.keys)
          room       <- build(
            session0,
            seatTokens,
            fanOutBuffer,
            idleCheck,
            disconnectGrace,
            seedGrace,
            maxInlinePaths,
            persist
          )
          // The creation row must be durable before anyone plays: the seat tokens and the dice commitment have been
          // handed out, so a restart in the first seconds must not lose them.
          _ <- room.persistQuietly(session0)
          _ <- room.supervisedConsume.start
        yield Right(room)

  /** Rebuild a room from a durable snapshot after a restart. Tokens, seeds, clocks and turn history come from the
    * snapshot; the caller re-derives the `DiceSource` from the stored server seed, so the committed dice sequence
    * continues (and still opens the published commitment at reveal). The mover's in-flight turn timer restarts —
    * monotonic time is process-scoped — which errs in the player's favour.
    */
  def restore(
      snapshot: GameSnapshot,
      dice: DiceSource,
      // Resolved by the caller from the snapshot's own principals — a snapshot does not persist display names, so
      // without this a game resumed after a restart would show anonymous opponents for the rest of its life.
      displayNames: Map[String, String] = Map.empty,
      // Re-resolved on restore for the same reason as `displayNames`. A restart therefore re-samples "rating as of
      // game start" at resume time — the same accepted staleness trade the display name makes.
      ratings: Map[String, Double] = Map.empty,
      fanOutBuffer: Int = DefaultFanOutBuffer,
      idleCheck: FiniteDuration = DefaultIdleCheck,
      disconnectGrace: FiniteDuration = DefaultDisconnectGrace,
      seedGrace: FiniteDuration = DefaultSeedGrace,
      maxInlinePaths: Int = DefaultMaxInlineTurnPaths,
      persist: GameSnapshot => IO[Unit] = _ => IO.unit
  ): IO[Either[String, GameRoom]] =
    EngineOps.parse(snapshot.dfen) match
      case Left(error)   => IO.pure(Left(s"corrupt snapshot dfen: $error"))
      case Right(state0) =>
        IO.monotonic.flatMap { now =>
          // The cache is transient; a pending roll's turns re-derive from the persisted DFEN (dice included).
          val (turns, tree) =
            if snapshot.pendingDrawOffer.isDefined then (Map.empty[List[String], List[Move]], MoveTree.empty)
            else if snapshot.pending then turnCache(state0)
            else (Map.empty[List[String], List[Move]], MoveTree.empty)
          val session0 = Session(
            state0,
            snapshot.version,
            snapshot.players,
            displayNames,
            ratings,
            dice,
            snapshot.ply,
            snapshot.pending,
            snapshot.status,
            snapshot.timeControl,
            // A pre-existing row from before this field existed has no key at all (see GameSnapshot.rated) —
            // resolve that to unrated, exactly like createdAtEpochMs's own absent-key story.
            rated = snapshot.rated.getOrElse(false),
            ladder = snapshot.ladder.getOrElse(false),
            remaining = snapshot.remainingMs.map((seat, ms) => seat -> FiniteDuration(ms, "milliseconds")),
            // A pending turn's clock restarts NOW: monotonic time is process-scoped, so the pre-crash start is
            // meaningless — but leaving it unset would let `debit` charge zero for the whole post-restart turn.
            turnStartedAt = Option.when(snapshot.pending)(now),
            started = snapshot.started,
            startedAt = None,
            clientSeeds = snapshot.clientSeeds,
            lastRoll = snapshot.lastRoll,
            turns = snapshot.turns,
            createdAtEpochMs = snapshot.createdAtEpochMs,
            legalTurns = turns,
            legalTree = tree,
            pendingDrawOffer = snapshot.pendingDrawOffer,
            lastDrawOfferer = snapshot.lastDrawOfferer
          )
          build(
            session0,
            snapshot.seatTokens,
            fanOutBuffer,
            idleCheck,
            disconnectGrace,
            seedGrace,
            maxInlinePaths,
            persist
          )
            .flatTap(_.supervisedConsume.start)
            .map(Right(_))
        }

  private def build(
      session0: Session,
      seatTokens: Map[Seat, String],
      fanOutBuffer: Int,
      idleCheck: FiniteDuration,
      disconnectGrace: FiniteDuration,
      seedGrace: FiniteDuration,
      maxInlinePaths: Int,
      persist: GameSnapshot => IO[Unit]
  ): IO[GameRoom] =
    for
      ref         <- Ref.of[IO, Session](session0)
      inbox       <- Queue.unbounded[IO, Msg]
      subscribers <- Ref.of[IO, Map[Long, Subscriber]](Map.empty)
      nextId      <- Ref.of[IO, Long](0L)
      done        <- Deferred[IO, GameOver]
      presence    <- Ref.of[IO, Map[Seat, Int]](Map.empty)
      graceFibers <- Ref.of[IO, Map[Seat, Fiber[IO, Throwable, Unit]]](Map.empty)
    yield new GameRoom(
      ref,
      inbox,
      subscribers,
      nextId,
      fanOutBuffer,
      seatTokens,
      idleCheck,
      done,
      presence,
      graceFibers,
      disconnectGrace,
      seedGrace,
      maxInlinePaths,
      persist
    )

  /** Starting clocks for a timed control: both seats get the initial bank (SuddenDeath/Fischer). PerMove keeps no bank
    * (each turn gets a fresh budget) and Unlimited has no clock, so both start empty.
    */
  private def initialRemaining(timeControl: TimeControl, seats: Iterable[Seat]): Map[Seat, FiniteDuration] =
    timeControl match
      case TimeControl.SuddenDeath(init) => seats.map(_ -> init.seconds).toMap
      case TimeControl.Fischer(init, _)  => seats.map(_ -> init.seconds).toMap
      case _                             => Map.empty

  /** Charge `mover` for the turn just completed, stopping the clock at `stoppedAt` — the moment the move was *received*
    * (sampled in `submit`), so neither queue-wait nor engine validation is billed to the player. Fischer then adds its
    * increment; SuddenDeath only debits; PerMove keeps no bank. Returns the updated session and the elapsed thinking
    * time in milliseconds.
    */
  private[play] def debit(s: Session, mover: Seat, stoppedAt: FiniteDuration): (Session, Long) =
    val elapsed   = s.turnStartedAt.fold(Duration.Zero: FiniteDuration)(start => floorZero(stoppedAt - start))
    val elapsedMs = elapsed.toMillis
    val sNext     = s.timeControl match
      case TimeControl.Unlimited | TimeControl.PerMove(_) => s.copy(turnStartedAt = None)
      case TimeControl.SuddenDeath(_)                     =>
        val left = floorZero(s.remaining.getOrElse(mover, Duration.Zero) - elapsed)
        s.copy(remaining = s.remaining.updated(mover, left), turnStartedAt = None)
      case TimeControl.Fischer(_, inc) =>
        val left = floorZero(s.remaining.getOrElse(mover, Duration.Zero) - elapsed) + inc.seconds
        s.copy(remaining = s.remaining.updated(mover, left), turnStartedAt = None)
    (sNext, elapsedMs)

  private def floorZero(d: FiniteDuration): FiniteDuration = if d > Duration.Zero then d else Duration.Zero

  /** Analytics colour letter for a *player* seat (turn records are only ever created for the side that moved). */
  private def colorLetter(seat: Seat): String = if seat == Seat.White then "w" else "b"

  /** The completed-turn history for a joining client's `Snapshot`, mapped from the room's analytics `turns`. */
  private def snapshotHistory(s: Session): List[SnapshotTurn] =
    s.turns.iterator
      .map(t => SnapshotTurn(if t.activeColor == "w" then Seat.White else Seat.Black, t.dice, t.moves, t.fenAfter))
      .toList

  /** Remaining time per side as of `now`, in millis — the mover's in-progress turn already subtracted so a snapshot is
    * live, not frozen at the last completed turn. `None` for Unlimited (no clock).
    */
  private def liveClocks(s: Session, now: FiniteDuration): Option[Clocks] =
    s.timeControl match
      case TimeControl.Unlimited => None
      case timeControl           =>
        val mover                          = Option.when(s.pending)(EngineOps.activeSeat(s.state))
        def remainingFor(seat: Seat): Long =
          val bank = timeControl match
            case TimeControl.PerMove(spm) => spm.seconds
            case _                        => s.remaining.getOrElse(seat, Duration.Zero)
          val elapsed =
            if mover.contains(seat) then s.turnStartedAt.fold(Duration.Zero: FiniteDuration)(now - _) else Duration.Zero
          floorZero(bank - elapsed).toMillis
        Some(Clocks(remainingFor(Seat.White), remainingFor(Seat.Black)))

  private def mintTokens(seats: Iterable[Seat]): IO[Map[Seat, String]] =
    seats.toList.traverse(seat => randomToken.map(seat -> _)).map(_.toMap)

  private def randomToken: IO[String] = IO:
    val bytes = new Array[Byte](16)
    SecureRandom().nextBytes(bytes)
    bytes.map("%02x".format(_)).mkString
