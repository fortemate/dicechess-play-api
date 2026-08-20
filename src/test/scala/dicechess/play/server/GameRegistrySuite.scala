package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.game.GameRoom
import dicechess.play.store.{GameSnapshot, GameStore}

import scala.concurrent.duration.*

/** The rated/casual policy (#97): a game is rated only when both requested AND every participant is non-anonymous.
  * `isRated` itself is checked directly as a pure matrix; the `create` tests confirm the policy actually reaches the
  * persisted snapshot, not just the in-memory decision.
  */
class GameRegistrySuite extends munit.CatsEffectSuite:

  private val alice: Principal.Bot = Principal.Bot("acme", "alice")
  private val bob: Principal.Bot   = Principal.Bot("acme", "bob")

  /** The ladder's own control (5+3), which categorises — so every row of the matrix below isolates the identity rule
    * from the control rule (#280). The control's own half is asserted separately.
    */
  private val Blitz: TimeControl = TimeControl.Fischer(300, 3)

  private def capturingStore(written: Ref[IO, Vector[GameSnapshot]]): GameStore = new GameStore:
    def save(id: GameId, snapshot: GameSnapshot): IO[Unit] = written.update(_ :+ snapshot)
    def loadActive: IO[List[(GameId, GameSnapshot)]]       = IO.pure(Nil)

  // ── isRated: the pure policy matrix ──────────────────────────────────────────

  test("two registered bots, rated requested -> rated"):
    assert(GameRegistry.isRated(alice, bob, requested = true, Blitz))

  test("two registered bots, rated NOT requested -> casual"):
    assert(!GameRegistry.isRated(alice, bob, requested = false, Blitz))

  test("a guest on either side forces casual even when rated was requested"):
    assert(!GameRegistry.isRated(Principal.Guest("g1"), bob, requested = true, Blitz))
    assert(!GameRegistry.isRated(alice, Principal.Guest("g2"), requested = true, Blitz))

  test("an anonymous (team=anon) bot on either side forces casual even when rated was requested"):
    assert(!GameRegistry.isRated(Principal.Bot(BotAuth.AnonTeam, "x"), bob, requested = true, Blitz))
    assert(!GameRegistry.isRated(alice, Principal.Bot(BotAuth.AnonTeam, "y"), requested = true, Blitz))

  test("two guests are always casual, requested or not"):
    assert(!GameRegistry.isRated(Principal.Guest("g1"), Principal.Guest("g2"), requested = true, Blitz))
    assert(!GameRegistry.isRated(Principal.Guest("g1"), Principal.Guest("g2"), requested = false, Blitz))

  test("two registered human accounts (User) can be rated"):
    assert(GameRegistry.isRated(Principal.User("u1"), Principal.User("u2"), requested = true, Blitz))

  test("a registered human and a registered bot can be rated together"):
    assert(GameRegistry.isRated(Principal.User("u1"), alice, requested = true, Blitz))

  /** The trap that decided #282's friend-by-link half: `isRated` asks only "is either side anonymous", so the SAME
    * registered principal on both seats passes it. Nothing here would stop a self-play game from being stamped rated —
    * the guard is `RatingBatch`, which skips it afterwards with "self-play carries no rating information".
    *
    * That asymmetry is why `POST /games` (friend-by-link) must NOT gain a `rated` flag: both of its seats hold the same
    * principal until a friend uses a seat token, so the flag would write a `true` the batch then contradicts. See the
    * rationale comment on `PlayRoutes`'s `registry.create` call.
    */
  test("isRated does NOT exclude self-play — the same principal on both seats still counts as rated"):
    assert(GameRegistry.isRated(alice, alice, requested = true, Blitz))
    assert(GameRegistry.isRated(Principal.User("u1"), Principal.User("u1"), requested = true, Blitz))

  test("an uncategorised control forces casual however registered the participants are (#280)"):
    // There is one rating scale per speed now, and neither of these bounds how long a game lasts — so there is no
    // scale for the result to land on. Degraded here rather than skipped in the batch, which is what keeps
    // `game_results.rated` from claiming something no scale can honour.
    assert(!GameRegistry.isRated(alice, bob, requested = true, TimeControl.Unlimited))
    assert(!GameRegistry.isRated(alice, bob, requested = true, TimeControl.PerMove(30)))
    assert(!GameRegistry.isRated(Principal.User("u1"), alice, requested = true, TimeControl.Unlimited))

  test("every categorised control keeps a registered pairing rated — Bullet and Rapid included (#280)"):
    List(TimeControl.Fischer(60, 1), TimeControl.SuddenDeath(300), TimeControl.Fischer(600, 10)).foreach: control =>
      assert(GameRegistry.isRated(alice, bob, requested = true, control), s"$control must stay ratable")

  // ── create: the policy actually reaches the persisted snapshot ──────────────

  test("create with requestedRated=true between two registered bots persists a rated snapshot"):
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRegistry.create(store = capturingStore(written)).flatMap { registry =>
        registry.create(alice, bob, Blitz, requestedRated = true).flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"create failed: $error"))
          case Right(_)    =>
            written.get.map { snaps =>
              assert(snaps.headOption.exists(_.rated.contains(true)), "the creation snapshot must be marked rated")
            }
        }
      }
    }

  test("create with requestedRated=true is silently downgraded to casual when one side is a guest"):
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRegistry.create(store = capturingStore(written)).flatMap { registry =>
        registry.create(Principal.Guest("g1"), bob, Blitz, requestedRated = true).flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"create failed: $error"))
          case Right(_)    =>
            written.get.map { snaps =>
              assert(
                snaps.headOption.exists(_.rated.contains(false)),
                "a guest participant must force the snapshot casual"
              )
            }
        }
      }
    }

  test("create without requestedRated defaults to a casual snapshot even between two registered bots"):
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRegistry.create(store = capturingStore(written)).flatMap { registry =>
        registry.create(alice, bob, Blitz).flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"create failed: $error"))
          case Right(_)    =>
            written.get.map { snaps =>
              assert(
                snaps.headOption.exists(_.rated.contains(false)),
                "omitting requestedRated must default to casual"
              )
            }
        }
      }
    }

  /** Seat faces on the live wire (#194 step 4). The registry resolves them once at creation, which is why no caller of
    * `create` had to change — and why a rename mid-game keeps the old label (see `Session.displayNames`).
    */
  test("a live game names a registered seat and leaves a guest anonymous"):
    val account = Principal.User("0192f000-0000-7000-8000-0000000000aa")
    val guest   = Principal.Guest("0192f000-0000-7000-8000-000000000001")
    GameRegistry
      .create(resolveNicknames =
        ids => IO.pure(Map(account.externalId -> "QuietRook").filter((k, _) => ids.contains(k)))
      )
      .flatMap: registry =>
        registry
          .create(account, guest)
          .flatMap:
            case Left(error)      => IO(fail(s"room creation failed: $error"))
            case Right((_, room)) =>
              room.snapshot.map: state =>
                assertEquals(state.players.map(_.white.name), Some(Some("QuietRook")))
                assertEquals(state.players.map(_.black.name), Some(None), "a guest seat must stay anonymous")

  test("a live game is anonymous on both seats when no names can be resolved"):
    val account = Principal.User("0192f000-0000-7000-8000-0000000000aa")
    val guest   = Principal.Guest("0192f000-0000-7000-8000-000000000001")
    // The default resolver — in-memory mode, and the pre-#194 behaviour.
    GameRegistry
      .create()
      .flatMap: registry =>
        registry
          .create(account, guest)
          .flatMap:
            case Left(error)      => IO(fail(s"room creation failed: $error"))
            case Right((_, room)) =>
              room.snapshot.map: state =>
                assertEquals(state.players.map(_.white.name), Some(None))
                assertEquals(state.players.map(_.black.name), Some(None))

  // ── seat ratings and the rated flag on the live wire (#290) ─────────────────

  test("the live state says whether the game is rated, and a named seat carries its settled rating"):
    val account = Principal.User("0192f000-0000-7000-8000-0000000000aa")
    GameRegistry
      .create(
        resolveNicknames = ids => IO.pure(Map(account.externalId -> "QuietRook").filter((k, _) => ids.contains(k))),
        resolveRatings = (ids, _) =>
          IO.pure(Map(account.externalId -> 1756.0, bob.externalId -> 1642.0).filter((k, _) => ids.contains(k)))
      )
      .flatMap: registry =>
        registry
          .create(account, bob, Blitz, requestedRated = true)
          .flatMap:
            case Left(error)      => IO(fail(s"room creation failed: $error"))
            case Right((_, room)) =>
              room.snapshot.map: state =>
                assertEquals(state.rated, Some(true), "the board must be able to say what is at stake")
                assertEquals(state.players.map(_.white.rating), Some(Some(1756.0)))
                assertEquals(state.players.map(_.black.rating), Some(Some(1642.0)))

  test("a casual game still says so explicitly, and unresolved seats simply carry no rating"):
    GameRegistry
      .create()
      .flatMap: registry =>
        registry
          .create(alice, bob)
          .flatMap:
            case Left(error)      => IO(fail(s"room creation failed: $error"))
            case Right((_, room)) =>
              room.snapshot.map: state =>
                assertEquals(state.rated, Some(false), "absent means 'the server does not say' — a room always says")
                assertEquals(state.players.map(_.white.rating), Some(None))
                assertEquals(state.players.map(_.black.rating), Some(None))

  test("a guest seat never carries a rating, even from a resolver that wrongly rates its id"):
    val guest = Principal.Guest("0192f000-0000-7000-8000-000000000001")
    // A hostile/buggy resolver rates the guest's external id anyway — the `ofExternalId` funnel must drop it, because
    // a stable number on an anonymous face is a cross-game correlation handle (#290, same promise as `name = None`).
    GameRegistry
      .create(resolveRatings = (ids, _) => IO.pure(ids.map(_ -> 1500.0).toMap))
      .flatMap: registry =>
        registry
          .create(guest, bob, Blitz)
          .flatMap:
            case Left(error)      => IO(fail(s"room creation failed: $error"))
            case Right((_, room)) =>
              room.snapshot.map: state =>
                assertEquals(state.players.map(_.white.rating), Some(None), "an anonymous face stays bare")
                assertEquals(state.players.map(_.black.rating), Some(Some(1500.0)))

  /** `resume` must resolve seat names for ALL revived games in ONE call. It used to ask per game — an N+1 at boot, and
    * the exact thing `createRoom` avoids by resolving before the room exists.
    */
  test("resume resolves every revived game's seat names in a single lookup"):
    val accountA = Principal.User("0192f000-0000-7000-8000-00000000000a")
    val accountB = Principal.User("0192f000-0000-7000-8000-00000000000b")
    val guest    = Principal.Guest("0192f000-0000-7000-8000-000000000001")

    def snapshot(white: Principal, black: Principal): GameSnapshot =
      GameSnapshot(
        version = 1L,
        dfen = dicechess.play.game.EngineOps.InitialDfen,
        players = Map(Seat.White -> white, Seat.Black -> black),
        seatTokens = Map(Seat.White -> "tw", Seat.Black -> "tb"),
        serverSeed = "ab12cd34",
        clientSeeds = Map.empty,
        started = false,
        ply = 0L,
        pending = false,
        status = GameStatus.Active,
        timeControl = TimeControl.Unlimited,
        remainingMs = Map.empty,
        lastRoll = Nil,
        turns = Vector.empty,
        createdAtEpochMs = None,
        rated = Some(false)
      )

    for
      calls <- Ref.of[IO, Int](0)
      ids   <- Ref.of[IO, List[String]](Nil)
      idA   <- GameId.random
      idB   <- GameId.random
      store = new GameStore:
        def save(id: GameId, s: GameSnapshot): IO[Unit]  = IO.unit
        def loadActive: IO[List[(GameId, GameSnapshot)]] =
          IO.pure(List(idA -> snapshot(accountA, guest), idB -> snapshot(accountB, guest)))
      registry <- GameRegistry.create(
        store = store,
        resolveNicknames = requested =>
          calls.update(_ + 1) *> ids.update(_ ++ requested) *>
            IO.pure(Map(accountA.externalId -> "RookA", accountB.externalId -> "RookB"))
      )
      revived <- registry.resume
      seen    <- calls.get
      asked   <- ids.get
      roomA   <- registry.get(idA)
      stateA  <- roomA.traverse(_.snapshot)
    yield
      assertEquals(revived, 2)
      assertEquals(seen, 1, "one lookup for every resumed game, not one per game")
      // Both games' seats in the same request, de-duplicated (the shared guest appears once).
      assertEquals(asked.distinct.size, asked.size, "the id list must already be distinct")
      assert(asked.contains(accountA.externalId) && asked.contains(accountB.externalId))
      assertEquals(stateA.flatMap(_.players.map(_.white.name)), Some(Some("RookA")))
      assertEquals(stateA.flatMap(_.players.map(_.black.name)), Some(None), "the guest seat stays anonymous")

  // ── claimSeat: capturing the friend's identity (#285) ────────────────────────

  /** The predicate carries the whole safety argument, so it is tested as a matrix rather than only through a room. */
  private val seats = Map(Seat.White -> alice, Seat.Black -> alice)

  test("claimable only while both seats hold the same principal, and only for somebody else"):
    assert(GameRoom.claimable(seats, Seat.Black, bob), "the friend-by-link shape is exactly what may rebind")
    assert(GameRoom.claimable(seats, Seat.White, bob), "either seat, whichever token was shared")
    // The creator opening their own board (or testing their own share link) must never settle a seat onto themselves.
    assert(!GameRoom.claimable(seats, Seat.Black, alice), "the current holder claiming is a no-op")
    // Once the seats differ the game has two identities; a second person handed the link cannot take attribution.
    val settled = Map(Seat.White -> alice, Seat.Black -> bob)
    assert(!GameRoom.claimable(settled, Seat.Black, Principal.Guest("g9")), "a settled seat never rebinds")
    assert(!GameRoom.claimable(settled, Seat.White, Principal.Guest("g9")))
    // Every other game shape starts with two distinct principals, so it is excluded by the same clause.
    assert(!GameRoom.claimable(Map(Seat.White -> alice, Seat.Black -> bob), Seat.White, alice))

  test("claimSeat rebinds the seat, renames it, and indexes the game under the claimer"):
    val friend = Principal.User("0192f000-0000-7000-8000-0000000000cc")
    GameRegistry
      .create(
        resolveNicknames = ids => IO.pure(Map(friend.externalId -> "QuietRook").filter((k, _) => ids.contains(k))),
        resolveRatings = (ids, _) => IO.pure(Map(friend.externalId -> 1756.0).filter((k, _) => ids.contains(k)))
      )
      .flatMap: registry =>
        registry.create(alice, alice, Blitz).flatMap {
          case Left(error)       => IO.raiseError(RuntimeException(s"create failed: $error"))
          case Right((id, room)) =>
            for
              before  <- registry.gamesFor(friend)
              bound   <- registry.claimSeat(id, Seat.Black, friend)
              seating <- room.seating
              after   <- registry.gamesFor(friend)
              creator <- registry.gamesFor(alice)
              snap    <- room.snapshot
            yield
              assert(bound, "the friend-by-link shape must rebind")
              assertEquals(before, Nil, "not indexed under the friend before the claim")
              assertEquals(seating, Map(Seat.White -> alice, Seat.Black -> friend))
              assertEquals(after.map(_._1), List(id), "the friend must now find the game in their own list")
              assertEquals(creator.map(_._1), List(id), "the creator still holds the other seat")
              // The seat label follows the identity, or a board keeps showing the creator's name on both sides.
              assertEquals(snap.players.map(_.black.name), Some(Some("QuietRook")))
              // And so does the rating (#290): the claim is the moment this seat's identity first exists, so it is
              // sampled here — the claimer's "as of game start".
              assertEquals(snap.players.map(_.black.rating), Some(Some(1756.0)))
        }

  test("claimSeat is a no-op for a game that already has two distinct players"):
    GameRegistry
      .create()
      .flatMap: registry =>
        registry.create(alice, bob, Blitz).flatMap {
          case Left(error)       => IO.raiseError(RuntimeException(s"create failed: $error"))
          case Right((id, room)) =>
            for
              bound   <- registry.claimSeat(id, Seat.Black, Principal.Guest("11111111-1111-1111-1111-111111111111"))
              seating <- room.seating
            yield
              assert(!bound, "a lobby accept, a challenge and a ladder pairing must all be untouched")
              assertEquals(seating, Map(Seat.White -> alice, Seat.Black -> bob))
        }

  test("claimSeat on an unknown game says no rather than failing"):
    GameRegistry.create().flatMap(_.claimSeat(GameId("no-such-game"), Seat.Black, bob)).map(assertEquals(_, false))

  /** The race CodeRabbit caught on #286: the writer fiber stops when the game ends, so a claim queued into an
    * already-finished room has nobody left to answer it. Its caller is a WebSocket upgrade, so a valid join token would
    * have hung on the handshake instead of opening a socket.
    *
    * Two guards cover that: the eager `s.ended` check, and racing the reply against the game's own end. This test pins
    * the CLASS, not either guard — it fails (on its own timeout) only when both are gone, which is verified. The narrow
    * interleaving that needs the race specifically — the game ending between the check and the message being consumed —
    * has no deterministic test here: forcing it would mean betting on fiber scheduling, and a flaky test is worse than
    * an honest gap in this repo.
    */
  test("a claim on a finished game answers instead of hanging"):
    GameRegistry
      .create()
      .flatMap: registry =>
        registry.create(alice, alice, Blitz).flatMap {
          case Left(error)       => IO.raiseError(RuntimeException(s"create failed: $error"))
          case Right((id, room)) =>
            for
              _     <- room.submit(Seat.White, GameCommand.Resign)
              _     <- room.result
              bound <- registry
                .claimSeat(id, Seat.Black, Principal.User("0192f000-0000-7000-8000-0000000000ee"))
                .timeoutTo(5.seconds, IO.raiseError(RuntimeException("claimSeat hung on an ended game")))
            yield assertEquals(bound, false)
        }

  /** Attribution reads the accounts store, and a valid join token has to keep working through an outage of it — the
    * same availability-first rule `GameRoom.persistQuietly` follows. `claimSeat` is allowed to fail; the route swallows
    * it (see `PlayRoutes.claimSeatQuietly`), so what this pins is that the failure surfaces as a failed effect rather
    * than a corrupted seating.
    */
  test("a nickname-store outage leaves the seating untouched"):
    val friend = Principal.User("0192f000-0000-7000-8000-0000000000ff")
    // Fails only for the friend's lookup: creation resolves names too, and a resolver that fails for everyone would
    // break `create` instead and prove nothing about the claim.
    GameRegistry
      .create(resolveNicknames =
        ids =>
          if ids.contains(friend.externalId) then IO.raiseError(RuntimeException("accounts store down"))
          else IO.pure(Map.empty)
      )
      .flatMap: registry =>
        registry.create(alice, alice, Blitz).flatMap {
          case Left(error)       => IO.raiseError(RuntimeException(s"create failed: $error"))
          case Right((id, room)) =>
            for
              outcome <- registry.claimSeat(id, Seat.Black, friend).attempt
              seating <- room.seating
            yield
              assert(outcome.isLeft, "the store failure must not be silently swallowed this deep")
              assertEquals(seating, Map(Seat.White -> alice, Seat.Black -> alice), "no half-applied rebind")
        }
