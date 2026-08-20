package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{PlayerKind, Principal, Seat, Seek, TimeControl}

import scala.concurrent.duration.*

class LobbySuite extends munit.CatsEffectSuite:

  private def lobby: IO[Lobby] = GameRegistry.create().flatMap(Lobby.create(_))

  /** A lobby whose seating capacity check is scripted (#189). */
  private def lobbyWith(admitBoth: (Principal, Principal) => IO[Boolean]): IO[Lobby] =
    GameRegistry.create().flatMap(Lobby.create(_, admitBoth = admitBoth))

  private val alice   = Principal.Guest("alice")
  private val bob     = Principal.Guest("bob")
  private val botFoo  = Principal.Bot("acme", "foo")
  private val anonBot = Principal.Bot(BotAuth.AnonTeam, "ephemeral")
  private val account = Principal.User("0192f000-0000-7000-8000-0000000000bb")

  /** Create a seek the test expects to be admitted, unwrapping the cap envelope. */
  private def mustCreate(
      l: Lobby,
      creator: Principal,
      tc: TimeControl,
      rated: Boolean = false
  ): IO[(Seek, String)] =
    l.create(creator, tc, rated).flatMap {
      case Right(created) => IO.pure(created)
      case Left(rejected) => IO.raiseError(RuntimeException(s"create unexpectedly rejected: $rejected"))
    }

  test("a created seek appears in the open list and reports Open to its creator"):
    for
      l              <- lobby
      (seek, secret) <- mustCreate(l, alice, TimeControl.Fischer(300, 3))
      open           <- l.list
      status         <- l.status(seek.id, secret)
    yield
      assertEquals(open.map(_.id), List(seek.id))
      assertEquals(open.head.timeControl, TimeControl.Fischer(300, 3))
      assertEquals(status, Some(Lobby.SeekStatus.Open))

  test("a guest seek is an anonymous Human; a bot seek carries its public name"):
    for
      l          <- lobby
      (human, _) <- mustCreate(l, alice, TimeControl.Unlimited)
      (bot, _)   <- mustCreate(l, botFoo, TimeControl.Unlimited)
    yield
      assertEquals((human.kind, human.name), (PlayerKind.Human, None))
      assertEquals((bot.kind, bot.name), (PlayerKind.Bot, Some("acme foo")))

  test("status requires the creator's secret"):
    for
      l         <- lobby
      (seek, _) <- mustCreate(l, alice, TimeControl.Unlimited)
      wrong     <- l.status(seek.id, "nope")
    yield assertEquals(wrong, None)

  test("accepting seats a game and gives each side its own seat token; the seek leaves the open list"):
    for
      l              <- lobby
      (seek, secret) <- mustCreate(l, alice, TimeControl.Unlimited)
      accepted       <- l.accept(seek.id, bob)
      creatorStatus  <- l.status(seek.id, secret)
      open           <- l.list
    yield
      val accepterMatch = accepted.toOption.get
      assert(accepterMatch.gameId.nonEmpty)
      assert(accepterMatch.token.nonEmpty)
      creatorStatus match
        case Some(Lobby.SeekStatus.Matched(gameId, creatorToken, creatorSeat)) =>
          assertEquals(gameId, accepterMatch.gameId) // both players join the same game
          assert(creatorToken.nonEmpty)
          assertNotEquals(creatorToken, accepterMatch.token) // but hold different seats
          assertNotEquals(creatorSeat, accepterMatch.seat)   // opposite seats, whichever way the coin fell
        case other => fail(s"expected Matched, got $other")
      assertEquals(open, Nil)

  test("a second accept is refused, and accepting an unknown seek is NotFound"):
    for
      l         <- lobby
      (seek, _) <- mustCreate(l, alice, TimeControl.Unlimited)
      first     <- l.accept(seek.id, bob)
      second    <- l.accept(seek.id, Principal.Guest("carol"))
      unknown   <- l.accept("seek-999", bob)
    yield
      assert(first.isRight)
      assertEquals(second, Left(Lobby.Rejected.AlreadyTaken))
      assertEquals(unknown, Left(Lobby.Rejected.NotFound))

  test("a creator cannot accept its own seek"):
    for
      l         <- lobby
      (seek, _) <- mustCreate(l, botFoo, TimeControl.Unlimited)
      own       <- l.accept(seek.id, botFoo)
      stillOpen <- l.list
    yield
      assertEquals(own, Left(Lobby.Rejected.OwnSeek))
      assertEquals(stillOpen.map(_.id), List(seek.id)) // the failed accept must not claim the seek

  test("a bot's open seeks are capped; a claimed seek frees the slot"):
    for
      reg      <- GameRegistry.create()
      l        <- Lobby.create(reg, maxOpenSeeksPerBot = 2)
      _        <- mustCreate(l, botFoo, TimeControl.Unlimited)
      (s2, _)  <- mustCreate(l, botFoo, TimeControl.Unlimited)
      overflow <- l.create(botFoo, TimeControl.Unlimited, rated = false)
      // Guests are never capped.
      _     <- mustCreate(l, alice, TimeControl.Unlimited)
      _     <- l.accept(s2.id, bob)
      retry <- l.create(botFoo, TimeControl.Unlimited, rated = false)
    yield
      assertEquals(overflow, Left(Lobby.CreateRejected.TooManyOpenSeeks))
      assert(retry.isRight, s"a matched seek must free the cap slot: $retry")

  test("cancel removes the seek only with the right secret"):
    for
      l              <- lobby
      (seek, secret) <- mustCreate(l, alice, TimeControl.Unlimited)
      badCancel      <- l.cancel(seek.id, "nope")
      stillThere     <- l.list
      goodCancel     <- l.cancel(seek.id, secret)
      gone           <- l.list
    yield
      assertEquals(badCancel, false)
      assertEquals(stillThere.map(_.id), List(seek.id))
      assertEquals(goodCancel, true)
      assertEquals(gone, Nil)

  test("a matched seek can't be cancelled (its game already exists); the creator can still read its token"):
    for
      l              <- lobby
      (seek, secret) <- mustCreate(l, alice, TimeControl.Unlimited)
      _              <- l.accept(seek.id, bob)
      cancelled      <- l.cancel(seek.id, secret)
      status         <- l.status(seek.id, secret)
    yield
      assertEquals(cancelled, false)
      assert(status.exists {
        case Lobby.SeekStatus.Matched(_, _, _) => true
        case _                                 => false
      })

  test("the sweep drops a seek whose creator has gone quiet past the TTL"):
    for
      reg  <- GameRegistry.create()
      l    <- Lobby.create(reg, ttl = 50.millis)
      _    <- mustCreate(l, alice, TimeControl.Unlimited)
      _    <- IO.sleep(120.millis)
      _    <- l.sweep
      gone <- l.list
    yield assertEquals(gone, Nil)

  test("a bot seek outlives the guest TTL — sized for a poll-only bot on a lazy timer"):
    for
      reg  <- GameRegistry.create()
      l    <- Lobby.create(reg, ttl = 50.millis, botTtl = 10.seconds)
      _    <- mustCreate(l, alice, TimeControl.Unlimited)
      _    <- mustCreate(l, botFoo, TimeControl.Unlimited)
      _    <- IO.sleep(120.millis)
      _    <- l.sweep
      left <- l.list
    yield
      // The quiet guest's seek is swept; the bot's standing offer survives its longer TTL.
      assertEquals(left.map(_.kind), List(PlayerKind.Bot))

  test("accepting open seeks results in random color assignment, correctly reported to the accepter"):
    val trials = 20
    for
      reg     <- GameRegistry.create()
      results <- List.fill(trials)(()).traverse { _ =>
        for
          l         <- Lobby.create(reg)
          (seek, _) <- mustCreate(l, alice, TimeControl.Unlimited)
          accepted  <- l.accept(seek.id, bob)
          reportedAccepterSeat = accepted.toOption.get.seat
          gameId               = accepted.toOption.get.gameId
          games <- reg.gamesFor(alice)
          room = games.find(_._1.value == gameId).get._2
          seating <- room.seating
          aliceSeat = seating.find(_._2 == alice).get._1
          bobSeat   = seating.find(_._2 == bob).get._1
        yield (aliceSeat, reportedAccepterSeat, bobSeat)
      }
    yield
      val aliceSeats = results.map(_._1)
      assert(aliceSeats.contains(Seat.White), "Should assign creator to White in at least one trial")
      assert(aliceSeats.contains(Seat.Black), "Should assign creator to Black in at least one trial")
      // The seat named in the accept response must match the room's own seating, every trial —
      // not just "sometimes White, sometimes Black" but correctly reported each specific time.
      results.foreach { case (_, reported, bobSeat) =>
        assertEquals(reported, bobSeat, "SeekMatch.seat must match the accepter's actual room seat")
      }

  test("an accept refused for capacity leaves the seek open for the next taker (#189)"):
    // Refuses exactly once, then admits — the retry must match the same seek, which only works if the refusal left
    // it Open rather than consuming it into Claimed.
    IO.ref(true).flatMap { refuse =>
      for
        l          <- lobbyWith((_, _) => refuse.getAndSet(false).map(!_))
        (seek, _)  <- mustCreate(l, botFoo, TimeControl.Fischer(300, 3))
        busy       <- l.accept(seek.id, alice)
        stillOpen  <- l.list.map(_.map(_.id))
        retry      <- l.accept(seek.id, alice)
        afterMatch <- l.list
      yield
        assertEquals(busy, Left(Lobby.Rejected.Busy): Either[Lobby.Rejected, Lobby.Match])
        assertEquals(stillOpen, List(seek.id), "a busy refusal must not consume the offer")
        assert(retry.isRight, s"the same seek must be acceptable once capacity frees up: $retry")
        assertEquals(afterMatch, Nil, "a matched seek is no longer open")
    }

  test("a capacity refusal does not mask the identity checks that run before it (#189)"):
    for
      l         <- lobbyWith((_, _) => IO.pure(false))
      (seek, _) <- mustCreate(l, botFoo, TimeControl.Fischer(300, 3))
      busy      <- l.accept(seek.id, alice)
      unknown   <- l.accept("seek-nope", alice)
      ownSeek   <- l.accept(seek.id, botFoo)
    yield
      assertEquals(busy, Left(Lobby.Rejected.Busy): Either[Lobby.Rejected, Lobby.Match])
      assertEquals(unknown, Left(Lobby.Rejected.NotFound): Either[Lobby.Rejected, Lobby.Match])
      assertEquals(ownSeek, Left(Lobby.Rejected.OwnSeek): Either[Lobby.Rejected, Lobby.Match])

  /** 5+3, the ladder's control: categorised, so these tests isolate the IDENTITY rule from the control rule #280 added
    * beside it. An `Unlimited` seek can no longer be rated at all — asserted separately below.
    */
  private val blitz: TimeControl = TimeControl.Fischer(300, 3)

  test("a rated request from a registered account or bot survives onto the seek (#279)"):
    for
      l            <- lobby
      (viaUser, _) <- mustCreate(l, account, blitz, rated = true)
      (viaBot, _)  <- mustCreate(l, botFoo, blitz, rated = true)
    yield
      assert(viaUser.rated, "a signed-in creator's rated request must not be dropped")
      assert(viaBot.rated, "a registered bot's rated request must not be dropped")

  test("a seek on an uncategorised control is casual however registered its creator is (#280)"):
    for
      l              <- lobby
      (unlimited, _) <- mustCreate(l, account, TimeControl.Unlimited, rated = true)
      (perMove, _)   <- mustCreate(l, account, TimeControl.PerMove(30), rated = true)
    yield
      // Degraded on the SEEK, not only at game creation: this flag is what a browser reads when choosing between
      // open offers, so a `true` here would misinform the very person the field exists for.
      assert(!unlimited.rated, "there is no rating scale for a game of unbounded length")
      assert(!perMove.rated, "per-move bounds a move, not a game — same answer")

  test("an anonymous creator's rated request is silently downgraded to casual (#279)"):
    for
      l               <- lobby
      (viaGuest, _)   <- mustCreate(l, alice, TimeControl.Unlimited, rated = true)
      (viaAnonBot, _) <- mustCreate(l, anonBot, TimeControl.Unlimited, rated = true)
    yield
      assert(!viaGuest.rated, "a guest can never buy into a rated game by asking")
      assert(!viaAnonBot.rated, "an anon-team bot is anonymous too, and gets the same downgrade")

  test("an anonymous accepter is refused RequiresAccount on a rated seek, and it stays open (#279)"):
    for
      l              <- lobby
      (seek, _)      <- mustCreate(l, account, blitz, rated = true)
      guestAttempt   <- l.accept(seek.id, alice)
      anonBotAttempt <- l.accept(seek.id, anonBot)
      stillOpen      <- l.list.map(_.map(_.id))
    yield
      assertEquals(guestAttempt, Left(Lobby.Rejected.RequiresAccount))
      assertEquals(anonBotAttempt, Left(Lobby.Rejected.RequiresAccount))
      assertEquals(stillOpen, List(seek.id), "a rejected rated accept must not consume the seek")

  test("a registered, non-anonymous accepter can take a rated seek (#279)"):
    for
      l         <- lobby
      (seek, _) <- mustCreate(l, account, blitz, rated = true)
      accepted  <- l.accept(seek.id, botFoo)
    yield assert(accepted.isRight, s"a registered opponent must be able to accept a rated seek: $accepted")

  test("a seek carries the registered creator's nickname, and none for a guest"):
    val account = Principal.User("0192f000-0000-7000-8000-0000000000aa")
    val guest   = Principal.Guest("0192f000-0000-7000-8000-000000000001")
    for
      reg <- GameRegistry.create()
      l   <- Lobby.create(
        reg,
        resolveNicknames = ids => IO.pure(Map(account.externalId -> "QuietRook").filter((k, _) => ids.contains(k)))
      )
      named <- l.create(account, TimeControl.Unlimited, rated = false)
      anon  <- l.create(guest, TimeControl.Unlimited, rated = false)
    yield
      assertEquals(named.map(_._1.name), Right(Some("QuietRook")))
      // A guest offering a game stays anonymous in the public list, same as everywhere else.
      assertEquals(anon.map(_._1.name), Right(None))
