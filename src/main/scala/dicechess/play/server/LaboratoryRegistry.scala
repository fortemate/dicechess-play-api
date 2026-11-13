package dicechess.play.server

import cats.effect.{FiberIO, IO, Ref}
import cats.effect.std.{Console, Mutex}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.{Durability, EngineOps, GameRoom}
import dicechess.play.store.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import scala.util.Try

final case class LaboratoryRematchView(
    phase: String,
    gameId: Option[String],
    seat: Option[Seat],
    token: Option[String]
):
  override def toString: String = s"LaboratoryRematchView($phase,${gameId.isDefined},$seat,<redacted>)"

/** Isolated, fail-closed room registry backed only by the dedicated laboratory store. */
final class LaboratoryRegistry private (
    private val rooms: Ref[IO, Map[GameId, GameRoom]],
    private val store: LaboratoryStore,
    private val tuning: GameRoom.RoomTuning,
    private val diceSource: () => IO[DiceSource],
    private val joinWindow: FiniteDuration,
    private val now: IO[Instant],
    private val mutex: Mutex[IO],
    private val consents: Ref[IO, Map[GameId, Set[Seat]]],
    private val observers: Ref[IO, Map[GameId, FiberIO[Unit]]],
    private val admissionOpen: Boolean
) extends PlayRegistry:
  import LaboratoryRegistry.{constantTimeEqual, human, validTimeControl}

  override val list: IO[List[(GameId, GameRoom)]] = IO.pure(Nil)

  override def get(id: GameId): IO[Option[GameRoom]] = canonical(id) match
    case None             => IO.pure(None)
    case Some(normalized) => mutex.lock.surround(refresh(normalized))

  private def canonical(id: GameId): Option[GameId] = Try(UUID.fromString(id.value)).toOption
    .filter(_.toString.equalsIgnoreCase(id.value))
    .map(value => GameId(value.toString))

  private def refresh(id: GameId): IO[Option[GameRoom]] =
    store
      .load(id)
      .flatMap:
        case None =>
          IO.uncancelable: _ =>
            rooms
              .modify(current => (current.removed(id), current.get(id)))
              .flatMap(room => cancelObserver(id) *> room.traverse_(_.stopForRestart))
              .as(None)
        case Some(snapshot) =>
          rooms.get.flatMap(_.get(id) match
            case Some(room) => IO.pure(Some(room))
            case None       => restoreAndPublish(id, snapshot).map(Some(_)))

  override def claimSeat(id: GameId, seat: Seat, claimer: Principal): IO[Boolean] =
    if !human(claimer) || seat.side.isEmpty then IO.pure(false)
    else
      get(id).flatMap:
        case None       => IO.pure(false)
        case Some(room) =>
          room.snapshot.flatMap: state =>
            if state.lab.forall(_.turn.phase != Phase.AwaitingPlayers) then IO.pure(false)
            else room.claimSeat(seat, claimer, None, None)

  override def createFriend(
      white: Principal,
      black: Principal,
      timeControl: TimeControl,
      variant: Option[VariantIdentity],
      protocolVersion: Option[Int],
      requestedRated: Boolean
  ): IO[Either[String, (GameId, GameRoom)]] =
    val modeError =
      if !admissionOpen then Some("laboratory room admission is closed")
      else if variant != Some(TwoPairs) || protocolVersion != Some(ProtocolVersion) then
        Some("unsupported laboratory variant or protocol")
      else if requestedRated then Some("laboratory games cannot be rated")
      else if !human(white) || !human(black) then Some("laboratory games are human-only")
      else if !validTimeControl(timeControl) then Some("invalid laboratory time control")
      else if joinWindow <= Duration.Zero then Some("invalid laboratory join window")
      else None
    modeError match
      case Some(error) => IO.pure(Left(error))
      case None        => mutex.lock.surround(createLocked(white, black, timeControl))

  private def createLocked(
      white: Principal,
      black: Principal,
      timeControl: TimeControl
  ): IO[Either[String, (GameId, GameRoom)]] =
    for
      id       <- GameId.random
      dice     <- diceSource()
      at       <- now
      deadline <- IO.delay(at.plusNanos(joinWindow.toNanos)).attempt
      answer   <- deadline match
        case Left(_)             => IO.pure(Left("invalid laboratory join deadline"))
        case Right(joinDeadline) =>
          GameRoom
            .laboratorySnapshot(
              Map(Seat.White -> white, Seat.Black -> black),
              dice,
              timeControl,
              joinDeadline,
              EngineOps.InitialDfen
            )
            .flatMap:
              case Left(_) => IO.pure(Left("could not create laboratory snapshot"))
              case Right(snapshot) if validInitial(snapshot, timeControl) =>
                store
                  .commit(id, None, snapshot)
                  .flatMap:
                    case LaboratoryWrite.Conflict => IO.pure(Left("laboratory game creation conflicted"))
                    case _                        => restoreAndPublish(id, snapshot).map(room => Right(id -> room))
              case Right(_) => IO.pure(Left("laboratory snapshot did not match the requested mode"))
    yield answer

  private def restore(id: GameId, snapshot: GameSnapshot): IO[GameRoom] =
    for
      last <- Ref.of[IO, Long](snapshot.version)
      persistence = GameRoom.RoomPersistence(
        save = next => persist(id, last, next),
        durability = Durability.required(GameRegistry.logPersistence(id))
      )
      confirms = (next: GameSnapshot) =>
        store.load(id).map(_.contains(next)).flatTap(matches => last.set(next.version).whenA(matches))
      made <- GameRoom.restoreLaboratory(snapshot, tuning, persistence, confirms, now)
      room <- IO.fromEither(made.leftMap(_ => new IllegalStateException("invalid laboratory snapshot")))
    yield room

  private def persist(id: GameId, last: Ref[IO, Long], next: GameSnapshot): IO[Unit] =
    last.get.flatMap: expected =>
      store
        .commit(id, Some(expected), next)
        .flatMap:
          case LaboratoryWrite.Applied | LaboratoryWrite.Unchanged => last.set(next.version)
          case LaboratoryWrite.Conflict                            =>
            IO.raiseError(new IllegalStateException("laboratory snapshot write conflicted"))

  // Every caller holds the registry mutex. The consumer and its completion observer must become owned together.
  private def restoreAndPublish(id: GameId, snapshot: GameSnapshot): IO[GameRoom] = IO.uncancelable: _ =>
    restore(id, snapshot).flatTap: room =>
      rooms.update(_.updated(id, room)) *>
        (room.result *> mutex.lock.surround(evictCompleted(id, room))).start
          .flatMap(observer => observers.update(_.updated(id, observer)))

  private def evictCompleted(id: GameId, room: GameRoom): IO[Unit] =
    rooms
      .modify: current =>
        if current.get(id).exists(_ eq room) then (current.removed(id), true)
        else (current, false)
      .flatMap(removed => observers.update(_.removed(id)).whenA(removed))

  private def cancelObserver(id: GameId): IO[Unit] =
    observers.modify(current => (current.removed(id), current.get(id))).flatMap(_.traverse_(_.cancel))

  /** Load active snapshots and publish each healthy room; corruption logs only its game id. */
  def resume: IO[Int] = mutex.lock.surround:
    store.loadActive.flatMap: snapshots =>
      for restored <- snapshots.traverse: (id, snapshot) =>
          rooms.get.flatMap: current =>
            if current.contains(id) then IO.pure(Some(id))
            else
              restoreAndPublish(id, snapshot).attempt.flatMap:
                case Left(_)  => Console[IO].errorln(s"[play][laboratory-resume] game ${id.value} skipped").as(None)
                case Right(_) => IO.pure(Some(id))
      yield restored.flatten.size

  def shutdown: IO[Unit] = mutex.lock.surround:
    IO.uncancelable: _ =>
      observers.getAndSet(Map.empty).flatMap(_.values.toList.traverse_(_.cancel)) *>
        rooms.getAndSet(Map.empty).flatMap(_.values.toList.traverse_(_.stopForRestart))

  /** Expired live rooms lose their writer before their recovery rows are removed. TTL also applies to unfinished games.
    */
  def pruneExpired(limit: Int): IO[Int] = mutex.lock.surround:
    rooms.get.flatMap(_.keys.toList.traverse_(refresh)) *> store.pruneExpired(limit)

  def transcript(id: GameId): IO[Option[LaboratoryTranscriptRecord]] = canonical(id).traverseFilter(store.transcriptFor)

  /** Two authenticated final players must consent before the durable store creates one swapped-color successor. */
  def rematch(sourceId: GameId, token: String, consent: Boolean): IO[Either[String, LaboratoryRematchView]] =
    canonical(sourceId) match
      case None             => IO.pure(Left("laboratory game not found"))
      case Some(normalized) => mutex.lock.surround(rematchLocked(normalized, token, consent))

  private def rematchLocked(
      sourceId: GameId,
      token: String,
      consent: Boolean
  ): IO[Either[String, LaboratoryRematchView]] =
    store
      .load(sourceId)
      .flatMap:
        case None         => IO.pure(Left("laboratory game not found"))
        case Some(source) =>
          authorizeRematch(source, token) match
            case Left(error) => IO.pure(Left(error))
            case Right(seat) => continueRematch(sourceId, source, seat, consent)

  private def authorizeRematch(source: GameSnapshot, token: String): Either[String, Seat] =
    val sporting = source.status match
      case GameStatus.Ended(GameOver(_, termination)) => termination != Termination.Aborted
      case _                                          => false
    val validSeats = source.effectiveVariant == TwoPairs && source.lab.exists(state =>
      state.protocolVersion == ProtocolVersion && state.provenance == Provenance(RulesVersion.value, DiceAlgorithm)
    ) &&
      source.players.keySet == Set(Seat.White, Seat.Black) && source.players.values.toSet.size == 2 &&
      source.players.values.forall(human)
    for
      seat <- source.seatTokens
        .collectFirst {
          case (seat, candidate) if seat.side.isDefined && constantTimeEqual(candidate, token) => seat
        }
        .toRight("invalid laboratory seat token")
      _ <- Either.cond(sporting && validSeats, (), "laboratory game is not eligible for a rematch")
    yield seat

  private def continueRematch(
      sourceId: GameId,
      source: GameSnapshot,
      seat: Seat,
      consent: Boolean
  ): IO[Either[String, LaboratoryRematchView]] =
    store
      .successorOf(sourceId)
      .flatMap:
        case Some(successor)        => ensurePublished(successor) *> rematchView(seat, successor).map(Right(_))
        case None if !admissionOpen => IO.pure(Left("laboratory room admission is closed"))
        case None if !consent       => IO.pure(Right(LaboratoryRematchView("awaiting_consent", None, None, None)))
        case None                   =>
          consents
            .modify { current =>
              val agreed = current.getOrElse(sourceId, Set.empty) + seat
              (current.updated(sourceId, agreed), agreed == Set(Seat.White, Seat.Black))
            }
            .flatMap:
              case false => IO.pure(Right(LaboratoryRematchView("awaiting_consent", None, None, None)))
              case true  =>
                createSuccessor(sourceId, source).flatMap:
                  case Left(error)      => IO.pure(Left(error))
                  case Right(successor) => rematchView(seat, successor).map(Right(_))

  private def createSuccessor(sourceId: GameId, source: GameSnapshot): IO[Either[String, GameId]] =
    store
      .successorOf(sourceId)
      .flatMap:
        case Some(id) => IO.pure(Right(id))
        case None     =>
          for
            candidate <- GameId.random
            dice      <- diceSource()
            at        <- now
            deadline = at.plusNanos(joinWindow.toNanos)
            swapped  = Map(Seat.White -> source.players(Seat.Black), Seat.Black -> source.players(Seat.White))
            result    <- GameRoom.laboratorySnapshot(swapped, dice, source.timeControl, deadline, EngineOps.InitialDfen)
            successor <- result match
              case Left(_)          => IO.pure(Left("could not create laboratory rematch"))
              case Right(generated) =>
                val initial = generated.copy(
                  variant = source.variant,
                  lab = generated.lab.map(state =>
                    state.copy(
                      protocolVersion = source.lab.fold(ProtocolVersion)(_.protocolVersion),
                      provenance = source.lab.fold(state.provenance)(_.provenance)
                    )
                  )
                )
                store
                  .createRematch(sourceId, candidate, initial)
                  .flatMap:
                    case LaboratoryRematchWrite.Created(id) =>
                      ensurePublished(id).as(Right(id))
                    case LaboratoryRematchWrite.Existing(id) =>
                      ensurePublished(id).as(Right(id))
                    case LaboratoryRematchWrite.Rejected => IO.pure(Left("laboratory rematch was rejected"))
          yield successor

  private def rematchView(oldSeat: Seat, successor: GameId): IO[LaboratoryRematchView] =
    store
      .load(successor)
      .flatMap:
        case None           => IO.pure(LaboratoryRematchView("created", Some(successor.value), None, None))
        case Some(snapshot) =>
          val newSeat = oldSeat.opponent.get
          IO.pure(
            LaboratoryRematchView(
              "created",
              Some(successor.value),
              Some(newSeat),
              snapshot.seatTokens.get(newSeat)
            )
          )

  private def ensurePublished(id: GameId): IO[Unit] =
    rooms.get.flatMap: current =>
      if current.contains(id) then IO.unit
      else
        store
          .load(id)
          .flatMap:
            case None           => IO.raiseError(new IllegalStateException("laboratory room snapshot is unavailable"))
            case Some(snapshot) => restoreAndPublish(id, snapshot).void

  private def validInitial(snapshot: GameSnapshot, timeControl: TimeControl): Boolean =
    snapshot.effectiveVariant == TwoPairs && snapshot.timeControl == timeControl &&
      snapshot.lab.exists(state =>
        state.protocolVersion == ProtocolVersion && state.provenance.rulesVersion.trim.nonEmpty &&
          state.provenance.diceAlgorithm == DiceAlgorithm && state.turn.phase == Phase.AwaitingPlayers &&
          state.turn.generatedOffers.isEmpty && state.receipts.isEmpty && state.joined.isEmpty &&
          state.frozenClientSeeds.isEmpty && !state.fairnessRevealed
      ) && snapshot.serverSeed.matches("[0-9a-f]{64}") && snapshot.seatTokens.keySet == Set(Seat.White, Seat.Black) &&
      snapshot.players.keySet == Set(Seat.White, Seat.Black) && !snapshot.started && snapshot.version == 0

object LaboratoryRegistry:
  def create(
      store: LaboratoryStore,
      tuning: GameRoom.RoomTuning = GameRoom.RoomTuning.Default,
      diceSource: () => IO[DiceSource] = () => DiceSource.newCommitReveal(),
      joinWindow: FiniteDuration = 5.minutes,
      now: IO[Instant] = IO.realTimeInstant,
      admissionOpen: Boolean = true
  ): IO[LaboratoryRegistry] =
    for
      _         <- IO.raiseWhen(!store.durable)(new IllegalArgumentException("laboratory storage must be durable"))
      rooms     <- Ref.of[IO, Map[GameId, GameRoom]](Map.empty)
      mutex     <- Mutex[IO]
      consents  <- Ref.of[IO, Map[GameId, Set[Seat]]](Map.empty)
      observers <- Ref.of[IO, Map[GameId, FiberIO[Unit]]](Map.empty)
    yield new LaboratoryRegistry(
      rooms,
      store,
      tuning,
      diceSource,
      joinWindow,
      now,
      mutex,
      consents,
      observers,
      admissionOpen
    )

  private def human(principal: Principal): Boolean = principal match
    case Principal.Guest(_) | Principal.User(_) => true
    case Principal.Bot(_, _)                    => false

  private def validTimeControl(control: TimeControl): Boolean = control match
    case TimeControl.Unlimited                   => true
    case TimeControl.SuddenDeath(seconds)        => seconds > 0
    case TimeControl.Fischer(seconds, increment) => seconds > 0 && increment >= 0
    case TimeControl.PerMove(seconds)            => seconds > 0

  private def constantTimeEqual(left: String, right: String): Boolean =
    MessageDigest.isEqual(left.getBytes(UTF_8), right.getBytes(UTF_8))
