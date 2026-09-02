package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.{Durability, GameRoom, PersistenceTelemetry}
import dicechess.play.store.GameStore

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory registry of live game rooms (one authoritative node, for now). Rooms snapshot themselves into the
  * `GameStore` on every event, and `resume` rebuilds them on boot — so live games survive a restart or deploy.
  */
final class GameRegistry private (
    rooms: Ref[IO, Map[GameId, GameRoom]],
    byPlayer: Ref[IO, Map[Principal, Set[GameId]]],
    disconnectGrace: FiniteDuration,
    store: GameStore,
    resolveNicknames: List[String] => IO[Map[String, String]],
    resolveRatings: (List[String], RatingCategory) => IO[Map[String, Double]],
    diceSource: () => IO[DiceSource],
    registerHooks: CopyOnWriteArrayList[(GameId, List[Principal], GameOrigin) => IO[Unit]],
    deregisterHooks: CopyOnWriteArrayList[GameId => IO[Unit]],
    resumeHooks: CopyOnWriteArrayList[List[(GameId, List[Principal], GameOrigin)] => IO[Unit]]
):

  /** Seat ratings for one game's participants, in the game's OWN category (#290 + #280).
    *
    * An uncategorised control resolves to no ratings at all rather than to some fallback scale. That is the honest
    * answer once there is one rating per speed: a clockless game is on no scale, so there is no number to show, and
    * `PublicPlayer` already renders a bare face when a seat has none (a guest always has).
    */
  private def ratingsFor(seatIds: List[String], timeControl: TimeControl): IO[Map[String, Double]] =
    RatingCategory.of(timeControl).fold(IO.pure(Map.empty))(resolveRatings(seatIds, _))

  def get(id: GameId): IO[Option[GameRoom]] = rooms.get.map(_.get(id))

  /** Every live room, for the public games listing — one entry per active game on this node, so the map stays small.
    * Per-caller lookups should use [[gamesFor]] instead (indexed); this is for the whole-lobby view.
    */
  def list: IO[List[(GameId, GameRoom)]] = rooms.get.map(_.toList)

  /** The live games `principal` is seated in — an index lookup plus O(own games), not a scan over every room on the
    * node: bot discovery polls this on a timer, so its cost must not grow with everyone else's games.
    */
  def gamesFor(principal: Principal): IO[List[(GameId, GameRoom)]] =
    (byPlayer.get.map(_.getOrElse(principal, Set.empty)), rooms.get).mapN: (ids, all) =>
      ids.toList.sortBy(_.value).flatMap(id => all.get(id).map(id -> _))

  /** How many games `principal` is actually playing right now — the only capacity count in the server (#189).
    *
    * It is **derived**, never accumulated: a separate counter can leak a slot when a game dies in an unexpected way,
    * and a leaked slot locks a bot out of every future game while failing silently, which is worse than the timeouts
    * per-bot capacity exists to prevent. Here there is nothing to repair — the index is rebuilt from live rooms and a
    * room deregisters itself when its result resolves, so a wrong count cannot outlive the room that caused it.
    *
    * A just-ended room can linger until the registry evicts it, hence the `hasEnded` filter (the same one
    * `GET /bot/games` applies). The one shape that does hold a slot is a genuinely unfinished game — a clockless room
    * with an idle seat can deadlock forever (see the testing notes on idle seats). That is not a miscount, it is a real
    * stuck game, and every path that seats bots automatically imposes a clock.
    */
  def activeGamesFor(principal: Principal): IO[Int] =
    gamesFor(principal).flatMap(_.traverse((_, room) => room.hasEnded)).map(_.count(!_))

  /** Create and start a room for two players. Dice come from a fresh commit-reveal source whose server seed is
    * committed before any client connects; each player then folds in its own post-commit seed (see GameRoom's gate).
    * Domain errors (e.g. a bad initial position) are returned as a Left. Effect failures such as an unavailable durable
    * store fail the creation effect so an admission caller can compensate the reservation and any partial registration.
    *
    * `requestedRated` is only a hint: the game is actually rated iff [[GameRegistry.isRated]] agrees, so an anonymous
    * participant on either side silently forces a casual game regardless of what was requested.
    *
    * `ladder` flags a game the ladder scheduler is starting, as opposed to a direct challenge or a catalog game — the
    * only marker in `game_results` distinguishing that, which is what keeps a casual/challenge timeout from ever
    * tripping ladder auto-park (`RatingBatch.shouldPark`, #150).
    */
  def onRegister(hook: (GameId, List[Principal], GameOrigin) => IO[Unit]): IO[Unit] =
    IO.delay(registerHooks.add(hook)).void

  def onDeregister(hook: GameId => IO[Unit]): IO[Unit] =
    IO.delay(deregisterHooks.add(hook)).void

  private val attachedGuard = new java.util.concurrent.atomic.AtomicReference[Option[AdmissionGuard]](None)

  def attachedAdmissionGuard: Option[AdmissionGuard] = attachedGuard.get()

  def onResume(hook: List[(GameId, List[Principal], GameOrigin)] => IO[Unit]): IO[Unit] =
    IO.delay(resumeHooks.add(hook)).void

  def attachAdmissionGuardSync(guard: AdmissionGuard): Unit =
    attachedGuard.set(Some(guard))
    registerHooks.add((id, players, origin) => guard.recordActive(id, players, origin))
    deregisterHooks.add(guard.releaseGame)
    resumeHooks.add(details => guard.reconcile(details).void)

  def attachAdmissionGuard(guard: AdmissionGuard): IO[Unit] =
    IO.delay(attachAdmissionGuardSync(guard))

  def create(
      white: Principal,
      black: Principal,
      timeControl: TimeControl = TimeControl.Unlimited,
      requestedRated: Boolean = false,
      ladder: Boolean = false,
      origin: GameOrigin = GameOrigin.Legacy
  ): IO[Either[String, (GameId, GameRoom)]] =
    attachedGuard.get() match
      case Some(guard) =>
        guard
          .admitAndCreate(this, white, black, timeControl, origin, requestedRated, ladder)
          .map(_.left.map(_.message))
      case None =>
        createRoomInternal(white, black, timeControl, origin, requestedRated, ladder)

  private[server] def createRoomInternal(
      white: Principal,
      black: Principal,
      timeControl: TimeControl,
      origin: GameOrigin,
      requestedRated: Boolean,
      ladder: Boolean
  ): IO[Either[String, (GameId, GameRoom)]] =
    (GameId.random, diceSource()).flatMapN { (id, dice) =>
      createRoom(
        id,
        Map(Seat.White -> white, Seat.Black -> black),
        dice,
        timeControl,
        rated = GameRegistry.isRated(white, black, requestedRated, timeControl),
        ladder = ladder,
        origin = origin
      )
    }

  private[server] def createWithDice(
      white: Principal,
      black: Principal,
      dice: DiceSource,
      timeControl: TimeControl = TimeControl.Unlimited,
      requestedRated: Boolean = false,
      ladder: Boolean = false,
      origin: GameOrigin = GameOrigin.Legacy
  ): IO[Either[String, (GameId, GameRoom)]] =
    GameId.random.flatMap: id =>
      createRoom(
        id,
        Map(Seat.White -> white, Seat.Black -> black),
        dice,
        timeControl,
        rated = GameRegistry.isRated(white, black, requestedRated, timeControl),
        ladder = ladder,
        origin = origin
      )

  /** Shared room-creation seam behind `create`: build the room, register it, start it.
    *
    * A showcase room is refused outright over a store that is not durable (ADR-005 §7, #47): the table promises that
    * every played game is recorded, and an in-memory store cannot keep it, so the honest answer is no room at all —
    * returned as a `Left` so an admission caller releases its reservation like for any other creation failure.
    */
  private def createRoom(
      id: GameId,
      players: Map[Seat, Principal],
      dice: DiceSource,
      timeControl: TimeControl,
      rated: Boolean,
      ladder: Boolean,
      origin: GameOrigin
  ): IO[Either[String, (GameId, GameRoom)]] =
    if origin.isShowcase && !store.durable then IO.pure(Left(GameRegistry.ShowcaseRequiresPersistence))
    else
      for
        // Resolved here rather than inside the room: a room emits a snapshot on every move, so the seat faces must be
        // decided once and carried. Doing it in the registry also means no caller of `create` has to know about it —
        // direct games, lobby accepts, catalog games, bot challenges and ladder pairings all get named seats for free.
        // Ratings (#290) follow the identical rationale: sampled once here, they ARE "the rating as of game start".
        seatIds <- IO.pure(players.values.map(_.externalId).toList)
        // The two resolvers read independent tables, so they run in parallel — one round-trip of latency, not two.
        (names, ratings) <- (resolveNicknames(seatIds), ratingsFor(seatIds, timeControl)).parTupled
        made             <- GameRoom.create(
          players,
          dice,
          displayNames = names,
          ratings = ratings,
          disconnectGrace = disconnectGrace,
          timeControl = timeControl,
          rated = rated,
          ladder = ladder,
          origin = origin,
          persist = store.save(id, _),
          durability = durabilityFor(id, origin)
        )
        result <- made.traverse: room =>
          val cleanup = room.abort *> abortAndDeregister(id)
          (register(id, room) *> room.start.as((id, room)))
            .onError(_ => cleanup)
            .onCancel(cleanup)
      yield result

  /** The write discipline a room gets from its origin (ADR-005 §7): fail-closed for the showcase table, whose every
    * public version must be committed before anyone sees it, availability-first for every other game. The telemetry
    * sink names the game, so an operator reading the log can tell which table is stalled and on which version.
    */
  private def durabilityFor(id: GameId, origin: GameOrigin): Durability =
    if origin.isShowcase then Durability.required(GameRegistry.logPersistence(id)) else Durability.BestEffort

  /** Rebuild rooms for every game that was live when the process stopped; returns how many were revived. A snapshot
    * that fails to restore is logged and skipped — one corrupt row must not take the server down.
    */
  def resume: IO[Int] =
    store.loadActive.flatMap: snapshots =>
      // A snapshot does not persist display names, so they are resolved again on boot — otherwise a restart would leave
      // every live game's opponents anonymous for the rest of its life. ONE query covering every resumed game's seats,
      // not one per game: the same reason `createRoom` resolves before the room exists instead of letting the room look
      // names up. The shared map is safe because a name is keyed by external id, not by game.
      val seatIds = snapshots.flatMap((_, snapshot) => snapshot.players.values.map(_.externalId)).distinct
      // Ratings cannot share one map the same way (#280): they are keyed by external id AND category, and the resumed
      // games need not agree on a category. Batched per category instead — at most three queries however many games
      // are being revived, and uncategorised games contribute none.
      val idsByCategory = snapshots
        .groupBy((_, snapshot) => RatingCategory.of(snapshot.timeControl))
        .collect { case (Some(category), group) =>
          category -> group.flatMap((_, s) => s.players.values.map(_.externalId)).distinct
        }
      for
        names             <- resolveNicknames(seatIds)
        ratingsByCategory <- idsByCategory.toList.parTraverse((category, ids) =>
          resolveRatings(ids, category).map(category -> _)
        )
        byCategory = ratingsByCategory.toMap
        restored <- snapshots.traverse: (id, snapshot) =>
          DiceSource
            .fromHexSeed(snapshot.serverSeed)
            .flatTraverse: dice =>
              GameRoom.restore(
                snapshot,
                dice,
                displayNames = names,
                ratings = RatingCategory.of(snapshot.timeControl).flatMap(byCategory.get).getOrElse(Map.empty),
                disconnectGrace = disconnectGrace,
                persist = store.save(id, _),
                // A resumed showcase game is as fail-closed as it was before the restart: the origin travels in the
                // snapshot precisely so the discipline can be re-derived from it.
                durability = durabilityFor(id, snapshot.effectiveOrigin)
              )
            .map(id -> _)
        failures  = restored.collect { case (id, Left(error)) => id -> error }
        successes = restored.collect { case (id, Right(room)) => (id, room) }
        _ <- failures.traverse_((id, error) => Console[IO].errorln(s"[play][resume] game ${id.value} skipped: $error"))
        _ <- successes.traverse_((id, room) => register(id, room))
        _ <- successes.traverse_((_, room) => room.start)
        resumedDetails <- successes.traverse { (id, room) =>
          (room.seating, room.origin).mapN((seats, orig) => (id, seats.values.toList, orig))
        }
        _ <- IO.defer(resumeHooks.asScala.toList.traverse_(_(resumedDetails)))
      yield successes.size

  /** Bind a seat to whoever redeemed its join token, and index the game under them (#285).
    *
    * The nickname is resolved HERE rather than in the room, for the same reason creation resolves it here: the room
    * must not gain a dependency on the accounts store. Answers whether the seat was actually rebound — `false` for
    * every game that already has two distinct players, which is the ordinary case.
    */
  def claimSeat(id: GameId, seat: Seat, claimer: Principal): IO[Boolean] =
    rooms.get
      .map(_.get(id))
      .flatMap:
        case None       => IO.pure(false)
        case Some(room) =>
          room.seating.flatMap: seats =>
            if !GameRoom.claimable(seats, seat, claimer) then IO.pure(false)
            else
              for
                timeControl      <- room.timeControl
                (names, ratings) <-
                  (
                    resolveNicknames(List(claimer.externalId)),
                    ratingsFor(List(claimer.externalId), timeControl)
                  ).parTupled
                bound <- room.claimSeat(
                  seat,
                  claimer,
                  names.get(claimer.externalId),
                  ratings.get(claimer.externalId)
                )
                _ <- indexClaim(id, claimer).whenA(bound)
              yield bound

  /** Index the game under its new claimer, without leaving an entry behind if the game ended meanwhile.
    *
    * `rooms` and `byPlayer` are separate Refs, so there is no single atomic boundary to hold across both. The window
    * that matters is cleanup running between the rebind and this write: it deregisters the room's final seating, which
    * includes the claimer, but it cannot remove an entry that does not exist yet. So add, then check whether the room
    * survived and undo if it did not. The other interleaving is already safe — cleanup running *after* the check finds
    * the entry and removes it, because the claimer is in the final seating it reads.
    */
  private def indexClaim(id: GameId, claimer: Principal): IO[Unit] =
    byPlayer.update(index => index.updated(claimer, index.getOrElse(claimer, Set.empty) + id)) *>
      rooms.get
        .map(_.contains(id))
        .flatMap: alive =>
          byPlayer
            .update: index =>
              val rest = index.getOrElse(claimer, Set.empty) - id
              if rest.isEmpty then index.removed(claimer) else index.updated(claimer, rest)
            .unlessA(alive)

  private def register(id: GameId, room: GameRoom): IO[Unit] =
    (room.seating, room.origin).flatMapN: (seats, origin) =>
      val players = seats.values.toList
      rooms.update(_.updated(id, room)) *>
        byPlayer.update(index =>
          players.foldLeft(index)((acc, p) => acc.updated(p, acc.getOrElse(p, Set.empty) + id))
        ) *>
        IO.defer(registerHooks.asScala.toList.traverse_(_(id, players, origin))) *>
        // Deregister against the room's FINAL seating, not the list captured here: a seat rebound mid-game (#285) adds
        // an index entry for the claimer, and cleaning up the original list would leak it forever — one `byPlayer`
        // entry per friend game, for the life of the process. A rebind only ever replaces one of two identical
        // principals, so the displaced one still holds the other seat and the final seating is always a superset of
        // what was indexed.
        //
        // Deliberately carries no test: the leak is invisible through `gamesFor`, which intersects the index with the
        // live rooms and so returns nothing for a stale entry either way. A test asserting through it would pass with
        // or without this line, which is worse than none.
        (room.result *> room.seating.flatMap(fin => deregister(id, fin.values.toList.distinct))).start.void

  private[server] def deregister(id: GameId, players: List[Principal]): IO[Unit] =
    IO.uncancelable: _ =>
      rooms
        .modify(current => (current.removed(id), current.contains(id)))
        .flatMap:
          case false => IO.unit
          case true  =>
            byPlayer.update(index =>
              players.foldLeft(index): (acc, p) =>
                val rest = acc.getOrElse(p, Set.empty) - id
                if rest.isEmpty then acc.removed(p) else acc.updated(p, rest)
            ) *>
              IO.defer(deregisterHooks.asScala.toList.traverse_(_(id)))

  /** Compensate a room that was built and started but whose admission could not be committed. Aborting through the
    * room's inbox terminates its consumer and persists an auditable technical result; explicit deregistration makes
    * cleanup deterministic instead of waiting for the result-watcher fiber to be scheduled.
    */
  private[server] def abortAndDeregister(id: GameId): IO[Unit] =
    rooms.get
      .map(_.get(id))
      .flatMap:
        case None       => IO.unit
        case Some(room) =>
          room.seating.flatMap(players => room.abort *> deregister(id, players.values.toList.distinct))

object GameRegistry:

  /** The refusal a showcase creation gets over a non-durable store (ADR-005 §7, #47). Public so the coordinator (#46)
    * can recognise it and answer `unavailable` rather than a generic failure.
    */
  val ShowcaseRequiresPersistence: String =
    "showcase games require PostgreSQL persistence (PLAY_DB_URL); refusing to create one over an in-memory store"

  /** The production telemetry sink for a fail-closed room: one stderr line per event, each naming the game and the
    * version, each saying what happens next — the operator reading it should never have to guess whether the table is
    * retrying, has given up, or is back.
    */
  private[server] def logPersistence(id: GameId)(event: PersistenceTelemetry): IO[Unit] =
    val prefix = s"[play][persist][required] game ${id.value}"
    event match
      case PersistenceTelemetry.SaveFailed(version, attempt, terminal, retryIn, error) =>
        val kind = if terminal then "terminal" else "intermediate"
        val next = retryIn.fold("no attempts left")(d => s"retrying in $d")
        Console[IO].errorln(s"$prefix v$version: $kind save attempt $attempt failed, $next: $error")
      case PersistenceTelemetry.SaveRecovered(version, attempts, stalledFor) =>
        Console[IO].errorln(
          s"$prefix v$version: committed after $attempts attempts; the room was stalled for $stalledFor"
        )
      case PersistenceTelemetry.SaveAbandoned(version, attempts) =>
        Console[IO].errorln(
          s"$prefix v$version: ABANDONED after $attempts attempts — technical abort from durable v${version - 1} follows"
        )
      case PersistenceTelemetry.SubscribersDropped(version, stalledFor) =>
        Console[IO].errorln(s"$prefix v$version: still failing after $stalledFor, subscribers released; retrying")

  /** `resolveNicknames` turns external ids into display names for the seats — `UserStore.nicknamesByExternalId` in
    * production. Passed as a function rather than the whole store (the `upsertOnLogin`/`freshNickname` precedent) so
    * the registry gains no dependency on the accounts trait, and so the default is honestly "no names": in-memory mode
    * has no accounts, and every human renders anonymous exactly as before #194.
    *
    * `resolveRatings` (#290) is the same shape for the seats' settled ratings —
    * `PgGameStore.settledRatingsByExternalId` in production, empty in in-memory mode where there are no ratings at all.
    * It takes the CATEGORY to read (#280): a seat's badge answers "how good is this opponent at this speed", so the
    * scale is the game's own, never a default the caller would have to remember to pass.
    */
  def create(
      disconnectGrace: FiniteDuration = GameRoom.DefaultDisconnectGrace,
      store: GameStore = GameStore.noop,
      resolveNicknames: List[String] => IO[Map[String, String]] = _ => IO.pure(Map.empty),
      resolveRatings: (List[String], RatingCategory) => IO[Map[String, Double]] = (_, _) => IO.pure(Map.empty),
      diceSource: () => IO[DiceSource] = () => DiceSource.newCommitReveal()
  ): IO[GameRegistry] =
    (
      Ref.of[IO, Map[GameId, GameRoom]](Map.empty),
      Ref.of[IO, Map[Principal, Set[GameId]]](Map.empty)
    ).mapN: (rooms, byPlayer) =>
      new GameRegistry(
        rooms,
        byPlayer,
        disconnectGrace,
        store,
        resolveNicknames,
        resolveRatings,
        diceSource,
        new CopyOnWriteArrayList(),
        new CopyOnWriteArrayList(),
        new CopyOnWriteArrayList()
      )

  /** Whether `p` can sustain a meaningful rating at all: a human guest's identity is free to reset, and an anon-team
    * bot (`POST /bot/anon`) is the same kind of throwaway for bots — resetting either would make rating free. Shared by
    * [[isRated]] (per-game eligibility) and `Lobby` (whether a seek's own creator/accepter may even ask for rated), so
    * the two can never disagree about who counts as anonymous.
    */
  private[server] def isAnonymous(p: Principal): Boolean = p match
    case Principal.Guest(_)     => true
    case Principal.User(_)      => false
    case Principal.Bot(team, _) => team == BotAuth.AnonTeam

  /** Whether a game between these participants should count toward rating, given the caller's request (#279, ADR-0017).
    * Rated is player-chosen at creation, not operator-curated: any registered account or bot may play rated, and the
    * Glicko-2 scale itself is what discourages farming a weak opponent (beating one far below your rating yields close
    * to nothing). What is NOT defended by the math, and stays a hard rule here, is an anonymous participant — see
    * [[isAnonymous]] — which can never be rated regardless of what was requested. Decided once, at creation; the result
    * is carried verbatim into every snapshot afterward (`GameSnapshot.rated`), never recomputed mid-game.
    *
    * '''The control now decides too (#280).''' With one scale per speed there is no scale for a game whose length is
    * unbounded, so `Unlimited` and `PerMove` are casual — the same silent degrade an anonymous participant already
    * gets, and for the same reason: refusing would take away a game that is perfectly playable, it just cannot be
    * measured. Deciding it HERE rather than in `RatingBatch` is what keeps `game_results.rated` honest: a row marked
    * rated that the batch would then always skip is exactly the lie #279 removed from that column. Bot-vs-bot corpus
    * runs, which legitimately want no clock, therefore keep working and simply stop claiming to be rated.
    */
  private[server] def isRated(
      white: Principal,
      black: Principal,
      requested: Boolean,
      timeControl: TimeControl
  ): Boolean =
    requested && !isAnonymous(white) && !isAnonymous(black) && RatingCategory.of(timeControl).isDefined
