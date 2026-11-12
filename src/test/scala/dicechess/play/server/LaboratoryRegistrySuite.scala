package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.GameRoom
import dicechess.play.store.*

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class LaboratoryRegistrySuite extends munit.CatsEffectSuite:

  private val alice     = Principal.Guest("11111111-1111-1111-1111-111111111111")
  private val bob       = Principal.User("22222222-2222-2222-2222-222222222222")
  private val fixedTime = Instant.parse("2026-11-12T00:00:00Z")
  private val seeds     = AtomicInteger(0)
  private val control   = TimeControl.Fischer(600, 10)

  final private case class Data(
      snapshots: Map[GameId, GameSnapshot],
      successors: Map[GameId, GameId]
  )

  final private class MemoryStore(data: Ref[IO, Data]) extends LaboratoryStore:
    override val durable: Boolean                                   = true
    override def save(id: GameId, snapshot: GameSnapshot): IO[Unit] =
      data.update(current => current.copy(snapshots = current.snapshots.updated(id, snapshot)))
    override def loadActive: IO[List[(GameId, GameSnapshot)]] =
      data.get.map(_.snapshots.toList.filter((_, snapshot) => !snapshot.ended))
    override def load(id: GameId): IO[Option[GameSnapshot]] = data.get.map(_.snapshots.get(id))
    override def commit(id: GameId, expectedVersion: Option[Long], snapshot: GameSnapshot): IO[LaboratoryWrite] =
      data.modify: current =>
        current.snapshots.get(id) match
          case None if expectedVersion.isEmpty =>
            (current.copy(snapshots = current.snapshots.updated(id, snapshot)), LaboratoryWrite.Applied)
          case Some(previous) if expectedVersion.isDefined && previous == snapshot =>
            (current, LaboratoryWrite.Unchanged)
          case Some(previous)
              if expectedVersion.contains(previous.version) && snapshot.version > previous.version && !previous.ended =>
            (current.copy(snapshots = current.snapshots.updated(id, snapshot)), LaboratoryWrite.Applied)
          case _ => (current, LaboratoryWrite.Conflict)
    override def transcriptFor(id: GameId): IO[Option[LaboratoryTranscriptRecord]] = IO.pure(None)
    override def resultFor(id: GameId): IO[Option[LaboratoryResult]]               = IO.pure(None)
    override def receiptFor(
        id: GameId,
        seat: Seat,
        commandId: String
    ): IO[Option[LaboratoryStorage.AcceptedCommand]] = IO.pure(None)
    override def createRematch(
        source: GameId,
        candidate: GameId,
        initial: GameSnapshot
    ): IO[LaboratoryRematchWrite] =
      data.modify: current =>
        current.successors.get(source) match
          case Some(existing) => (current, LaboratoryRematchWrite.Existing(existing))
          case None if current.snapshots.contains(source) && !current.snapshots.contains(candidate) =>
            (
              current.copy(
                snapshots = current.snapshots.updated(candidate, initial),
                successors = current.successors.updated(source, candidate)
              ),
              LaboratoryRematchWrite.Created(candidate)
            )
          case _ => (current, LaboratoryRematchWrite.Rejected)
    override def successorOf(source: GameId): IO[Option[GameId]] = data.get.map(_.successors.get(source))
    override def pruneExpired(limit: Int): IO[Int]               = IO.pure(0)

  private def memoryStore: IO[MemoryStore] =
    Ref.of[IO, Data](Data(Map.empty, Map.empty)).map(new MemoryStore(_))

  private def registry(store: LaboratoryStore): IO[LaboratoryRegistry] =
    LaboratoryRegistry.create(
      store,
      diceSource = () => IO.delay(DiceSource.commitReveal(Array.fill[Byte](32)(seeds.incrementAndGet().toByte))),
      now = IO.pure(fixedTime)
    )

  private def request(
      white: Principal = alice,
      black: Principal = bob,
      timeControl: TimeControl = control,
      variant: Option[VariantIdentity] = Some(TwoPairs),
      protocol: Option[Int] = Some(ProtocolVersion),
      rated: Boolean = false
  ): IO[Either[String, (GameId, GameRoom)]] =
    memoryStore.flatMap(store =>
      registry(store).flatMap(_.createFriend(white, black, timeControl, variant, protocol, rated))
    )

  test("laboratory creation rejects missing or unsupported mode, rated, bots, and invalid clocks"):
    val bot = Principal.Bot("team", "bot")
    List(
      request(variant = None),
      request(variant = Some(Classic)),
      request(protocol = None),
      request(protocol = Some(2)),
      request(rated = true),
      request(white = bot),
      request(timeControl = TimeControl.Fischer(0, 1)),
      request(timeControl = TimeControl.SuddenDeath(-1)),
      request(timeControl = TimeControl.PerMove(0))
    ).sequence.map(_.foreach(result => assert(result.isLeft)))

  test("creation is durable before publication, starts in AwaitingPlayers and stays out of lobby listing"):
    for
      store <- memoryStore
      reg   <- registry(store)
      made  <- reg.createFriend(alice, bob, control, Some(TwoPairs), Some(ProtocolVersion), requestedRated = false)
      pair  <- IO.fromEither(made.leftMap(message => new RuntimeException(message)))
      (id, room) = pair
      saved  <- store.load(id)
      listed <- reg.list
      public <- room.snapshot
      _ = assert(saved.exists(_.lab.exists(_.turn.phase == Phase.AwaitingPlayers)))
      _ = assertEquals(listed, Nil)
      _ = assert(public.lab.exists(_.turn.phase == Phase.AwaitingPlayers))
      _ = assertEquals(public.rated, Some(false))
      _ <- reg.shutdown
    yield ()

  test("UUID casing resolves to one writer and malformed identifiers never reach storage"):
    for
      store <- memoryStore
      reg   <- registry(store)
      made  <- reg.createFriend(alice, bob, control, Some(TwoPairs), Some(ProtocolVersion), requestedRated = false)
      pair  <- IO.fromEither(made.leftMap(message => new RuntimeException(message)))
      (id, room) = pair
      same    <- reg.get(GameId(id.value.toUpperCase(java.util.Locale.ROOT)))
      missing <- reg.get(GameId("invalid-game-id"))
      _ = assert(same.exists(_ eq room))
      _ = assertEquals(missing, None)
      _ <- reg.shutdown
    yield ()

  test("registry snapshot writes use a strict expected-version CAS"):
    for
      store <- memoryStore
      reg   <- registry(store)
      made  <- reg.createFriend(alice, alice, control, Some(TwoPairs), Some(ProtocolVersion), requestedRated = false)
      pair  <- IO.fromEither(made.leftMap(message => new RuntimeException(message)))
      (id, room) = pair
      before      <- store.load(id).map(_.get)
      claimed     <- room.claimSeat(Seat.Black, bob, None, None)
      after       <- store.load(id).map(_.get)
      stale       <- store.commit(id, Some(before.version), before.copy(version = before.version + 2))
      sameVersion <- store.commit(id, Some(after.version), after.copy(clientSeeds = Map(Seat.White -> "changed")))
      unchanged   <- store.commit(id, Some(before.version), after)
      preserved   <- store.load(id)
      _ = assert(claimed)
      _ = assertEquals(after.players(Seat.Black), bob)
      _ = assert(after.version > before.version)
      _ = assertEquals(stale, LaboratoryWrite.Conflict)
      _ = assertEquals(sameVersion, LaboratoryWrite.Conflict)
      _ = assertEquals(unchanged, LaboratoryWrite.Unchanged)
      _ = assertEquals(preserved, Some(after))
      _ <- reg.shutdown
    yield ()

  test("completed rooms are evicted while their retained snapshot still supports lookup"):
    for
      store <- memoryStore
      reg   <- registry(store)
      made  <- reg.createFriend(alice, bob, control, Some(TwoPairs), Some(ProtocolVersion), requestedRated = false)
      pair  <- IO.fromEither(made.leftMap(message => new RuntimeException(message)))
      (id, original) = pair
      _        <- original.abort
      over     <- original.result
      restored <- {
        def awaitEviction: IO[GameRoom] = reg
          .get(id)
          .flatMap:
            case Some(room) if room ne original => IO.pure(room)
            case _                              => IO.cede *> IO.defer(awaitEviction)
        awaitEviction.timeout(2.seconds)
      }
      retained <- store.load(id)
      _ = assert(retained.exists(_.ended))
      _ <- restored.result.map(result => assertEquals(result, over))
      _ <- reg.shutdown
    yield ()

  test("rematch waits for both source seat tokens, swaps colors, and reveals only the caller token"):
    for
      store <- memoryStore
      reg   <- registry(store)
      made  <- reg.createFriend(alice, bob, control, Some(TwoPairs), Some(ProtocolVersion), requestedRated = false)
      pair  <- IO.fromEither(made.leftMap(message => new RuntimeException(message)))
      (sourceId, _) = pair
      initial   <- store.load(sourceId).map(_.get)
      premature <- reg.rematch(sourceId, initial.seatTokens(Seat.White), consent = true)
      ended = initial.copy(
        status = GameStatus.Ended(GameOver(GameResult.Win(Side.White), Termination.KingCaptured)),
        lab = initial.lab.map(state => state.copy(turn = state.turn.copy(phase = Phase.Ended)))
      )
      _         <- store.save(sourceId, ended)
      wrong     <- reg.rematch(sourceId, "wrong-token", consent = true)
      waitWhite <- reg.rematch(sourceId, initial.seatTokens(Seat.White), consent = true)
      _ = assert(wrong.isLeft)
      _ = assert(premature.isLeft)
      _ = assert(waitWhite.toOption.exists(_.gameId.isEmpty))
      created <- reg.rematch(sourceId, initial.seatTokens(Seat.Black), consent = true)
      view = created.toOption.get
      successor <- store.successorOf(sourceId).map(_.get)
      next      <- store.load(successor).map(_.get)
      _ = assertEquals(view.seat, Some(Seat.White)) // old Black receives White after the color swap
      _ = assertEquals(view.token, next.seatTokens.get(Seat.White))
      _ = assert(!view.token.contains(next.seatTokens(Seat.Black)))
      _ = assertEquals(next.players(Seat.White), bob)
      _ = assertEquals(next.players(Seat.Black), alice)
      _ = assert(next.serverSeed != initial.serverSeed)
      _ = assert(next.seatTokens.values.toSet.intersect(initial.seatTokens.values.toSet).isEmpty)
      _ <- reg.shutdown
    yield ()

  test("resume restores an active durable room with its original id"):
    for
      store         <- memoryStore
      firstRegistry <- registry(store)
      made          <- firstRegistry.createFriend(
        alice,
        bob,
        control,
        Some(TwoPairs),
        Some(ProtocolVersion),
        requestedRated = false
      )
      pair <- IO.fromEither(made.leftMap(message => new RuntimeException(message)))
      (id, _) = pair
      _              <- firstRegistry.shutdown
      secondRegistry <- registry(store)
      resumed        <- secondRegistry.resume
      room           <- secondRegistry.get(id)
      _ = assert(resumed >= 1)
      _ = assert(room.isDefined)
      _ <- secondRegistry.shutdown
    yield ()
