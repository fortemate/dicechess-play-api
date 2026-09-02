package dicechess.play.server

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.game.EngineOps
import dicechess.play.store.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** A `GameStore` that claims durability, keeps the latest snapshot per game, answers `loadActive` from them (so a fresh
  * registry can "resume" after a simulated crash), and can be told to fail every write — the switch behind the stalled
  * and rolled-back cases.
  */
final class RecordingGameStore(
    val snapshots: Ref[IO, Map[GameId, GameSnapshot]],
    failing: Ref[IO, Boolean]
) extends GameStore:
  override def durable: Boolean = true

  def save(id: GameId, snapshot: GameSnapshot): IO[Unit] =
    failing.get.flatMap: fail =>
      if fail then IO.raiseError(RuntimeException(s"database unavailable (game ${id.value} v${snapshot.version})"))
      else snapshots.update(_.updated(id, snapshot))

  def loadActive: IO[List[(GameId, GameSnapshot)]] = snapshots.get.map(_.toList.filter((_, s) => !s.ended))

  def failWrites(on: Boolean): IO[Unit] = failing.set(on)

  /** What `PgGameStore.activeShowcaseGameIds` answers from the `origin` column, derived from the same snapshots. */
  def activeShowcaseGameIds: IO[List[GameId]] =
    snapshots.get.map(_.collect {
      case (id, s) if s.effectiveOrigin.isShowcase && !s.ended => id
    }.toList.sortBy(_.value))

/** An in-memory [[ShowcaseStore]] with the same semantics as the PostgreSQL one (the fence, the idempotent record, the
  * colour repair) and two failure switches: one for the commit alone (so a room gets created and then rolled back) and
  * one for every read.
  */
final class InMemoryShowcaseStore(
    val table: Ref[IO, ShowcaseTableRecord],
    val claims: Ref[IO, Map[(String, UUID), ShowcaseClaimRecord]],
    active: IO[List[GameId]],
    failCommits: Ref[IO, Boolean],
    failReads: Ref[IO, Boolean]
) extends ShowcaseStore:

  private def guardReads[A](io: IO[A]): IO[A] =
    failReads.get.flatMap(fail => if fail then IO.raiseError(RuntimeException("database unavailable")) else io)

  def showcaseTable: IO[ShowcaseTableRecord]                                         = guardReads(table.get)
  def activeShowcaseGameIds: IO[List[GameId]]                                        = guardReads(active)
  def failCommit(on: Boolean): IO[Unit]                                              = failCommits.set(on)
  def failEveryRead(on: Boolean): IO[Unit]                                           = failReads.set(on)
  def findShowcaseClaim(actorId: String, key: UUID): IO[Option[ShowcaseClaimRecord]] =
    guardReads(claims.get.map(_.get((actorId, key))))

  def commitShowcaseClaim(
      actorId: String,
      key: UUID,
      requestHash: String,
      gameId: GameId,
      humanColor: Side,
      expectedNextHumanColor: Side
  ): IO[Boolean] =
    failCommits.get.flatMap: fail =>
      if fail then IO.raiseError(RuntimeException("database unavailable"))
      else
        IO.realTimeInstant.flatMap: now =>
          table
            .modify: current =>
              if current.nextHumanColor == expectedNextHumanColor && current.currentGameId.isEmpty then
                (ShowcaseTableRecord(expectedNextHumanColor.opponent, Some(gameId)), true)
              else (current, false)
            .flatTap: moved =>
              claims
                .update(
                  _.updatedWith((actorId, key))(
                    _.orElse(
                      Some(
                        ShowcaseClaimRecord(
                          actorId,
                          key,
                          requestHash,
                          ShowcaseClaimOutcome.Claimed,
                          Some(gameId),
                          Some(humanColor),
                          now,
                          now.plus(ShowcaseStore.ClaimRetention)
                        )
                      )
                    )
                  )
                )
                .whenA(moved)

  def recordSpectatingClaim(actorId: String, key: UUID, requestHash: String, gameId: Option[GameId]): IO[Unit] =
    IO.realTimeInstant.flatMap: now =>
      claims.update(
        _.updatedWith((actorId, key))(
          _.orElse(
            Some(
              ShowcaseClaimRecord(
                actorId,
                key,
                requestHash,
                ShowcaseClaimOutcome.Spectating,
                gameId,
                None,
                now,
                now.plus(ShowcaseStore.ClaimRetention)
              )
            )
          )
        )
      )

  def adoptShowcaseGame(gameId: GameId, humanColor: Side): IO[ShowcaseTableRecord] =
    table.updateAndGet: current =>
      val advance = !current.currentGameId.contains(gameId) && current.nextHumanColor == humanColor
      ShowcaseTableRecord(if advance then humanColor.opponent else current.nextHumanColor, Some(gameId))

  def clearShowcaseGame(gameId: GameId): IO[Boolean] =
    table.modify: current =>
      if current.currentGameId.contains(gameId) then (current.copy(currentGameId = None), true) else (current, false)

/** The wiring every showcase suite shares: a registered featured bot with capacity 3, a durable recording game store,
  * an in-memory showcase store derived from it, and a registry with the admission guard attached — the same shape
  * `Main.setupAdmission` builds, minus PostgreSQL.
  */
object ShowcaseHarness:

  val FeaturedBot: Principal.Bot = Principal.Bot("rpi3", "hunter-book")

  val Config: ShowcaseConfig = ShowcaseConfig(enabled = true, featuredBot = Some(FeaturedBot), reservedSeats = 1)

  final case class ShowcaseFixture(
      bots: BotStore,
      games: RecordingGameStore,
      store: InMemoryShowcaseStore,
      registry: GameRegistry,
      guard: AdmissionGuard,
      ready: Ref[IO, Boolean],
      alerts: Ref[IO, Vector[String]]
  ):
    /** A table over this fixture. The tick interval is effectively off: tests drive `reconcile` themselves. */
    def table(
        claimGrace: FiniteDuration = 30.seconds,
        withStore: Boolean = true,
        config: ShowcaseConfig = Config
    ): Resource[IO, ShowcaseTable] =
      ShowcaseTable.create(
        config,
        registry,
        guard,
        Option.when(withStore)(store),
        botReady = ready.get,
        alert = message => alerts.update(_ :+ message),
        claimGrace = claimGrace,
        tickInterval = 1.hour
      )

    /** A simulated process restart: a fresh registry and guard over the SAME stores, with the live games resumed from
      * the recorded snapshots exactly as `Main.setupAdmission` resumes them at boot.
      */
    def restart: IO[ShowcaseFixture] =
      for
        registry2 <- GameRegistry.create(store = games)
        guard2    <- AdmissionGuard.create(bots, Config, registry = Some(registry2))
        _         <- registry2.attachAdmissionGuard(guard2)
        _         <- registry2.resume
      yield copy(registry = registry2, guard = guard2)

  def fixture: IO[ShowcaseFixture] =
    for
      bots      <- BotStore.inMemory
      _         <- bots.register(FeaturedBot.team, FeaturedBot.name, "hash-featured")
      _         <- bots.setMaxConcurrentGames(FeaturedBot.team, FeaturedBot.name, 3)
      snapshots <- Ref.of[IO, Map[GameId, GameSnapshot]](Map.empty)
      failing   <- Ref.of[IO, Boolean](false)
      games = RecordingGameStore(snapshots, failing)
      tableRef    <- Ref.of[IO, ShowcaseTableRecord](ShowcaseTableRecord(Side.White, None))
      claims      <- Ref.of[IO, Map[(String, UUID), ShowcaseClaimRecord]](Map.empty)
      failCommits <- Ref.of[IO, Boolean](false)
      failReads   <- Ref.of[IO, Boolean](false)
      store = InMemoryShowcaseStore(tableRef, claims, games.activeShowcaseGameIds, failCommits, failReads)
      registry <- GameRegistry.create(store = games)
      guard    <- AdmissionGuard.create(bots, Config, registry = Some(registry))
      _        <- registry.attachAdmissionGuard(guard)
      ready    <- Ref.of[IO, Boolean](true)
      alerts   <- Ref.of[IO, Vector[String]](Vector.empty)
    yield ShowcaseFixture(bots, games, store, registry, guard, ready, alerts)

  /** A live showcase snapshot with no room behind it — what the store would hold after a crash whose resume failed, or
    * a second table another process wrote.
    */
  def orphanShowcaseSnapshot(human: Principal): GameSnapshot =
    GameSnapshot(
      version = 0L,
      dfen = EngineOps.InitialDfen,
      players = Map(Seat.White -> human, Seat.Black -> FeaturedBot),
      seatTokens = Map(Seat.White -> "tok-w", Seat.Black -> "tok-b"),
      serverSeed = "ab12cd34",
      clientSeeds = Map.empty,
      started = false,
      ply = 0L,
      pending = false,
      status = GameStatus.Active,
      timeControl = ShowcaseTable.FixedTimeControl,
      remainingMs = Map(Seat.White -> 300000L, Seat.Black -> 300000L),
      lastRoll = Nil,
      turns = Vector.empty,
      createdAtEpochMs = Some(Instant.now().toEpochMilli),
      rated = Some(false),
      ladder = Some(false),
      origin = Some(GameOrigin.Showcase)
    )

  /** Poll `read` until `pred` holds — every wait in these suites is on a condition, never a fixed delay. */
  def await[A](read: IO[A])(pred: A => Boolean): IO[A] =
    read
      .flatTap(a => IO.sleep(20.millis).unlessA(pred(a)))
      .iterateUntil(pred)
      .timeoutTo(
        15.seconds,
        read.flatMap(a => IO.raiseError(RuntimeException(s"the awaited condition never held: $a")))
      )
