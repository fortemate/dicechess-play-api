package dicechess.play.server

import cats.effect.kernel.Outcome
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.game.GameRoom
import dicechess.play.store.{BotSeatPolicy, BotStore}

import scala.concurrent.duration.*

/** Central admission authority owning atomic `acquire -> create/register -> commit` for every game containing a
  * registered bot (ADR-005, #44, #45).
  *
  * Invariants:
  *   - Featured bot (`rpi3/hunter-book`) declared capacity is partitioned: `occupancy(general) <= 2`,
  *     `occupancy(showcase) <= 1`, `occupancy(total) <= 3`.
  *   - General admission paths never borrow the reserved showcase seat, even when showcase is idle.
  *   - Non-featured registered bots preserve their declared capacity with race overshoot eliminated.
  *   - Static, anonymous, and human participants are unbounded.
  *   - Provisional admission carries a 5-second lease timeout.
  *   - Creation failure, database failure, or cancellation releases provisional admission exactly once.
  *   - A committed game holds admission until terminal deregistration.
  *   - Linearizability is process-local; horizontal scaling is prohibited until a distributed coordinator exists.
  */
final class AdmissionGuard private (
    botStore: BotStore,
    val showcaseConfig: ShowcaseConfig,
    state: Ref[IO, AdmissionGuard.State],
    leaseTimeout: FiniteDuration,
    registry: Option[GameRegistry]
):
  import AdmissionGuard.*

  def withRegistry(reg: GameRegistry): AdmissionGuard =
    new AdmissionGuard(botStore, showcaseConfig, state, leaseTimeout, Some(reg))

  private def untrackedInRegistry(bot: Principal.Bot, active: Map[GameId, ActiveAdmission]): IO[Int] =
    registry match
      case None      => IO.pure(0)
      case Some(reg) =>
        reg
          .activeGamesFor(bot)
          .map: regActive =>
            val trackedActive = active.values.count(_.bots.contains(bot))
            math.max(0, regActive - trackedActive)

  /** Attempt to acquire provisional admission for `players` under `purpose`.
    *
    * If all registered bots have capacity available, atomically reserves a provisional slot with a lease timeout.
    * Static, anonymous, or human participants require no reservation and are always admitted.
    */
  def acquire(players: List[Principal], purpose: AdmissionPurpose): IO[Either[AdmissionError, AdmissionTicket]] =
    for
      registeredBots <- resolveRegisteredBots(players)
      result         <-
        if registeredBots.isEmpty then IO.pure(Right(UnboundedTicket(purpose)))
        else
          for
            now       <- IO.monotonic
            untracked <- registeredBots
              .traverse { case (bot, _) =>
                state.get.flatMap(s => untrackedInRegistry(bot, s.active).map(u => bot -> u))
              }
              .map(_.toMap)
            res <- state.modify: current =>
              val pruned = current.pruneExpired(now)
              val check  = registeredBots.traverse { case (bot, policy) =>
                val (inFlightGen, inFlightShow) = pruned.occupancyFor(bot, now)
                val genOcc                      = inFlightGen + untracked.getOrElse(bot, 0)
                val showOcc                     = inFlightShow
                checkCapacity(bot, policy, purpose, genOcc, showOcc)
              }
              check match
                case Left(error) =>
                  (pruned, Left(error))
                case Right(_) =>
                  val ticketId    = pruned.nextTicketId
                  val reservation = Reservation(
                    ticketId = ticketId,
                    bots = registeredBots.map(_._1),
                    purpose = purpose,
                    createdAt = now,
                    expiresAt = now + leaseTimeout
                  )
                  val nextState = pruned.copy(
                    nextTicketId = ticketId + 1,
                    inFlight = pruned.inFlight.updated(ticketId, reservation)
                  )
                  (nextState, Right(createTicket(ticketId, registeredBots.map(_._1), purpose)))
          yield res
    yield result

  /** Execute a room creation action within the atomic admission boundary.
    *
    * Guarantees that:
    *   1. Provisional admission is acquired before the creation action begins.
    *   2. If the creation action succeeds and returns a `GameId`, the reservation commits to an active game.
    *   3. If the creation action returns Left, throws, or is cancelled, provisional admission is released exactly once.
    */
  def admit[A](
      players: List[Principal],
      purpose: AdmissionPurpose
  )(create: AdmissionTicket => IO[Either[String, (GameId, A)]]): IO[Either[AdmissionError, (GameId, A)]] =
    acquire(players, purpose).flatMap:
      case Left(err)     => IO.pure(Left(err))
      case Right(ticket) =>
        create(ticket)
          .flatMap:
            case Left(err) =>
              ticket.release.as(Left(AdmissionError.Failed(err)))
            case Right((gameId, result)) =>
              ticket
                .commit(gameId)
                .flatMap:
                  case true  => IO.pure(Right((gameId, result)))
                  case false =>
                    releaseGame(gameId).as(
                      Left(AdmissionError.Failed("reservation lease expired before room creation completed"))
                    )
          .guaranteeCase:
            case Outcome.Succeeded(_) => IO.unit
            case Outcome.Errored(_)   => ticket.release
            case Outcome.Canceled()   => ticket.release

  /** Convenience method for room creation that does not involve intermediate claim steps. */
  def admitAndCreate(
      registry: GameRegistry,
      white: Principal,
      black: Principal,
      timeControl: TimeControl,
      origin: GameOrigin,
      requestedRated: Boolean = false,
      ladder: Boolean = false
  ): IO[Either[AdmissionError, (GameId, GameRoom)]] =
    admit(List(white, black), origin.admissionPurpose): _ =>
      registry.createRoomInternal(white, black, timeControl, origin, requestedRated, ladder)

  /** Advisory check: whether `principal` would currently be admitted for `purpose`. */
  def admits(principal: Principal, purpose: AdmissionPurpose): IO[Boolean] =
    principal match
      case bot: Principal.Bot =>
        botStore
          .seatPolicyOf(bot.team, bot.name)
          .flatMap:
            case None         => IO.pure(true)
            case Some(policy) =>
              for
                current   <- state.get
                now       <- IO.monotonic
                untracked <- untrackedInRegistry(bot, current.active)
                (inFlightGen, inFlightShow) = current.occupancyFor(bot, now)
                genOcc                      = inFlightGen + untracked
                showOcc                     = inFlightShow
              yield checkCapacity(bot, policy, purpose, genOcc, showOcc).isRight
      case _ => IO.pure(true)

  /** Advisory check: whether both participants would currently be admitted. */
  def admitsBoth(one: Principal, other: Principal, purpose: AdmissionPurpose): IO[Boolean] =
    admits(one, purpose).flatMap(if _ then admits(other, purpose) else IO.pure(false))

  /** Subset of candidate policies available for ladder pairing. */
  def availableForLadder(pool: List[BotSeatPolicy]): IO[List[Principal.Bot]] =
    for
      current      <- state.get
      now          <- IO.monotonic
      untrackedMap <- pool.traverse(p => untrackedInRegistry(p.bot, current.active).map(u => p.bot -> u)).map(_.toMap)
    yield pool
      .filter: policy =>
        val (inFlightGen, inFlightShow) = current.occupancyFor(policy.bot, now)
        val genOcc                      = inFlightGen + untrackedMap.getOrElse(policy.bot, 0)
        val showOcc                     = inFlightShow
        checkCapacity(policy.bot, policy, AdmissionPurpose.Ladder, genOcc, showOcc).isRight
      .map(_.bot)

  /** Server-side diagnostics for `bot`, exposing allowances and occupancies without credentials. */
  def diagnostics(bot: Principal.Bot): IO[Option[AdmissionDiagnostics]] =
    botStore
      .seatPolicyOf(bot.team, bot.name)
      .flatMap:
        case None         => IO.pure(None)
        case Some(policy) =>
          for
            current   <- state.get
            now       <- IO.monotonic
            untracked <- untrackedInRegistry(bot, current.active)
            (inFlightGen, inFlightShow) = current.occupancyFor(bot, now)
            genOcc                      = inFlightGen + untracked
            showOcc                     = inFlightShow
            isFeatured                  = showcaseConfig.isFeatured(bot)
            reserved                    = if isFeatured then showcaseConfig.reservedSeats else 0
            genAllowance                =
              if isFeatured then math.max(0, policy.maxConcurrentGames - reserved) else policy.maxConcurrentGames
            showAllowance    = if isFeatured then reserved else 0
            activeGamesCount = current.active.values.count(_.bots.contains(bot)) + untracked
          yield Some(
            AdmissionDiagnostics(
              bot = bot,
              policy = policy,
              maxConcurrentGames = policy.maxConcurrentGames,
              isFeatured = isFeatured,
              showcaseReservedSeats = reserved,
              generalAllowance = genAllowance,
              generalOccupancy = genOcc,
              showcaseAllowance = showAllowance,
              showcaseOccupancy = showOcc,
              totalOccupancy = genOcc + showOcc,
              activeGames = activeGamesCount
            )
          )

  /** Rebuild purpose-specific occupancy from resumed durable games before serving traffic. */
  def reconcile(resumed: List[(GameId, List[Principal], GameOrigin)]): IO[Int] =
    resumed
      .traverse: (gameId, players, origin) =>
        resolveRegisteredBots(players).map: bots =>
          if bots.isEmpty then None
          else Some(ActiveAdmission(gameId, bots.map(_._1), origin.admissionPurpose))
      .flatMap: admissions =>
        val activeEntries = admissions.flatten.map(a => a.gameId -> a).toMap
        state.update(current => current.copy(active = current.active ++ activeEntries)).as(activeEntries.size)

  /** Record committed active admission if not already tracked (e.g. direct registry creation or fallback). */
  def recordActive(gameId: GameId, players: List[Principal], origin: GameOrigin): IO[Unit] =
    resolveRegisteredBots(players).flatMap: bots =>
      if bots.isEmpty then IO.unit
      else
        state.update: current =>
          if current.active.contains(gameId) then current
          else
            current.copy(
              active = current.active.updated(gameId, ActiveAdmission(gameId, bots.map(_._1), origin.admissionPurpose))
            )

  /** Release committed admission when a room terminates. */
  def releaseGame(gameId: GameId): IO[Unit] =
    state.update(current => current.copy(active = current.active.removed(gameId)))

  private def resolveRegisteredBots(players: List[Principal]): IO[List[(Principal.Bot, BotSeatPolicy)]] =
    val botPrincipals = players.collect { case bot: Principal.Bot => bot }.distinct
    botPrincipals.flatTraverse: bot =>
      botStore
        .seatPolicyOf(bot.team, bot.name)
        .map:
          case Some(policy) => List(bot -> policy)
          case None         => Nil

  private def checkCapacity(
      bot: Principal.Bot,
      policy: BotSeatPolicy,
      purpose: AdmissionPurpose,
      genOcc: Int,
      showOcc: Int
  ): Either[AdmissionError, Unit] =
    if showcaseConfig.isFeatured(bot) then checkFeaturedCapacity(bot, policy, purpose, genOcc, showOcc)
    else checkNonFeaturedCapacity(bot, policy, purpose, genOcc + showOcc)

  private def checkFeaturedCapacity(
      bot: Principal.Bot,
      policy: BotSeatPolicy,
      purpose: AdmissionPurpose,
      genOcc: Int,
      showOcc: Int
  ): Either[AdmissionError, Unit] =
    val totalOcc      = genOcc + showOcc
    val maxCap        = policy.maxConcurrentGames
    val reserved      = showcaseConfig.reservedSeats
    val genAllowance  = math.max(0, maxCap - reserved)
    val showAllowance = reserved

    purpose match
      case AdmissionPurpose.Showcase =>
        if showOcc >= showAllowance then
          Left(AdmissionError.Busy(s"showcase table is currently occupied for bot ${bot.team}/${bot.name}"))
        else if totalOcc >= maxCap then
          Left(AdmissionError.Busy(s"featured bot ${bot.team}/${bot.name} is at total capacity ($totalOcc/$maxCap)"))
        else Right(())

      case AdmissionPurpose.Ladder =>
        val ladderCap = math.min(policy.ladderAllowance, genAllowance)
        if genOcc >= ladderCap then
          Left(
            AdmissionError.Busy(
              s"featured bot ${bot.team}/${bot.name} is at general ladder limit ($genOcc/$ladderCap)"
            )
          )
        else if totalOcc >= maxCap then
          Left(AdmissionError.Busy(s"featured bot ${bot.team}/${bot.name} is at total capacity ($totalOcc/$maxCap)"))
        else Right(())

      case AdmissionPurpose.Direct =>
        if genOcc >= genAllowance then
          Left(
            AdmissionError.Busy(
              s"featured bot ${bot.team}/${bot.name} is at general capacity limit ($genOcc/$genAllowance)"
            )
          )
        else if totalOcc >= maxCap then
          Left(AdmissionError.Busy(s"featured bot ${bot.team}/${bot.name} is at total capacity ($totalOcc/$maxCap)"))
        else Right(())

  private def checkNonFeaturedCapacity(
      bot: Principal.Bot,
      policy: BotSeatPolicy,
      purpose: AdmissionPurpose,
      totalOcc: Int
  ): Either[AdmissionError, Unit] =
    purpose match
      case AdmissionPurpose.Showcase =>
        Left(AdmissionError.Busy(s"bot ${bot.team}/${bot.name} is not the configured featured showcase bot"))
      case AdmissionPurpose.Ladder =>
        if totalOcc >= policy.ladderAllowance then
          Left(
            AdmissionError.Busy(
              s"bot ${bot.team}/${bot.name} is at ladder allowance ($totalOcc/${policy.ladderAllowance})"
            )
          )
        else Right(())
      case AdmissionPurpose.Direct =>
        if totalOcc >= policy.maxConcurrentGames then
          Left(
            AdmissionError.Busy(
              s"bot ${bot.team}/${bot.name} is at declared capacity ($totalOcc/${policy.maxConcurrentGames})"
            )
          )
        else Right(())

  private def createTicket(ticketId: Long, bots: List[Principal.Bot], purpose: AdmissionPurpose): AdmissionTicket =
    new AdmissionTicket:
      private val settled = new java.util.concurrent.atomic.AtomicBoolean(false)

      def id: Long                          = ticketId
      def admittedBots: List[Principal.Bot] = bots
      def ticketPurpose: AdmissionPurpose   = purpose

      def commit(gameId: GameId): IO[Boolean] =
        IO.delay(settled.compareAndSet(false, true))
          .flatMap:
            case true =>
              IO.monotonic.flatMap: now =>
                state.modify: current =>
                  current.inFlight.get(ticketId) match
                    case Some(res) if res.expiresAt > now =>
                      (
                        current.copy(
                          inFlight = current.inFlight.removed(ticketId),
                          active = current.active.updated(gameId, ActiveAdmission(gameId, bots, purpose))
                        ),
                        true
                      )
                    case _ =>
                      (current.copy(inFlight = current.inFlight.removed(ticketId)), false)
            case false => IO.pure(false)

      def release: IO[Unit] =
        IO.delay(settled.compareAndSet(false, true))
          .flatMap:
            case true =>
              state.update(current => current.copy(inFlight = current.inFlight.removed(ticketId)))
            case false => IO.unit

  private case class UnboundedTicket(purpose: AdmissionPurpose) extends AdmissionTicket:
    def id: Long                            = 0L
    def admittedBots: List[Principal.Bot]   = Nil
    def ticketPurpose: AdmissionPurpose     = purpose
    def commit(gameId: GameId): IO[Boolean] = IO.pure(true)
    def release: IO[Unit]                   = IO.unit

object AdmissionGuard:

  val DefaultLeaseTimeout: FiniteDuration = 5.seconds

  enum AdmissionError(val message: String):
    case Busy(override val message: String) extends AdmissionError(message)
    case Failed(cause: String)              extends AdmissionError(cause)

  trait AdmissionTicket:
    def id: Long
    def admittedBots: List[Principal.Bot]
    def ticketPurpose: AdmissionPurpose
    def commit(gameId: GameId): IO[Boolean]
    def release: IO[Unit]

  final case class Reservation(
      ticketId: Long,
      bots: List[Principal.Bot],
      purpose: AdmissionPurpose,
      createdAt: FiniteDuration,
      expiresAt: FiniteDuration
  )

  final case class ActiveAdmission(
      gameId: GameId,
      bots: List[Principal.Bot],
      purpose: AdmissionPurpose
  )

  final case class State(
      nextTicketId: Long,
      inFlight: Map[Long, Reservation],
      active: Map[GameId, ActiveAdmission]
  ):
    def pruneExpired(now: FiniteDuration): State =
      val validInFlight = inFlight.filter((_, r) => r.expiresAt > now)
      if validInFlight.size == inFlight.size then this
      else copy(inFlight = validInFlight)

    def occupancyFor(bot: Principal.Bot, now: FiniteDuration): (Int, Int) =
      val validInFlight = inFlight.values.filter(r => r.expiresAt > now && r.bots.contains(bot))
      val validActive   = active.values.filter(_.bots.contains(bot))
      val gen           = validInFlight.count(_.purpose.isGeneral) + validActive.count(_.purpose.isGeneral)
      val show          = validInFlight.count(_.purpose.isShowcase) + validActive.count(_.purpose.isShowcase)
      (gen, show)

  final case class AdmissionDiagnostics(
      bot: Principal.Bot,
      policy: BotSeatPolicy,
      maxConcurrentGames: Int,
      isFeatured: Boolean,
      showcaseReservedSeats: Int,
      generalAllowance: Int,
      generalOccupancy: Int,
      showcaseAllowance: Int,
      showcaseOccupancy: Int,
      totalOccupancy: Int,
      activeGames: Int
  )

  def unsafe(
      botStore: BotStore,
      showcaseConfig: ShowcaseConfig = ShowcaseConfig.Disabled,
      leaseTimeout: FiniteDuration = DefaultLeaseTimeout,
      registry: Option[GameRegistry] = None
  ): AdmissionGuard =
    new AdmissionGuard(
      botStore,
      showcaseConfig,
      Ref.unsafe[IO, State](State(nextTicketId = 1L, inFlight = Map.empty, active = Map.empty)),
      leaseTimeout,
      registry
    )

  def create(
      botStore: BotStore,
      showcaseConfig: ShowcaseConfig = ShowcaseConfig.Disabled,
      leaseTimeout: FiniteDuration = DefaultLeaseTimeout,
      registry: Option[GameRegistry] = None
  ): IO[AdmissionGuard] =
    Ref
      .of[IO, State](State(nextTicketId = 1L, inFlight = Map.empty, active = Map.empty))
      .map(new AdmissionGuard(botStore, showcaseConfig, _, leaseTimeout, registry))
