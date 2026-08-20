package dicechess.play.server

import cats.effect.{IO, Ref}
import dicechess.play.core.{BotEvent, Challenge, GameId, Principal, TimeControl}
import dicechess.play.store.{GameSnapshot, GameStore}

import scala.concurrent.duration.*

class ChallengesSuite extends munit.CatsEffectSuite:

  private val alice = Principal.Bot("acme", "alice")
  private val bob   = Principal.Bot("acme", "bob")
  private val carol = Principal.Bot("acme", "carol")

  private def harness(
      ttl: FiniteDuration = Challenges.DefaultTtl,
      maxPendingPerBot: Int = Challenges.DefaultMaxPendingPerBot,
      admitBoth: (Principal, Principal) => IO[Boolean] = Challenges.AdmitAny
  ): IO[(BotEvents, Challenges)] =
    for
      events     <- BotEvents.create
      registry   <- GameRegistry.create()
      challenges <- Challenges.create(events, registry, ttl, maxPendingPerBot, admitBoth)
    yield (events, challenges)

  /** A harness whose registry writes every creation snapshot into the returned Ref, so a test can see whether the
    * seated game actually came out rated — the flag is not readable off a live room (#282).
    */
  private def capturingHarness: IO[(Challenges, Ref[IO, Vector[GameSnapshot]])] =
    for
      written  <- Ref.of[IO, Vector[GameSnapshot]](Vector.empty)
      events   <- BotEvents.create
      registry <- GameRegistry.create(store = new GameStore:
        def save(id: GameId, snapshot: GameSnapshot): IO[Unit] = written.update(_ :+ snapshot)
        def loadActive: IO[List[(GameId, GameSnapshot)]]       = IO.pure(Nil))
      challenges <- Challenges.create(events, registry)
    yield (challenges, written)

  /** Whether the game seated by accepting `challenge` was recorded as rated. */
  private def seatedRated(challenges: Challenges, written: Ref[IO, Vector[GameSnapshot]], id: String): IO[Boolean] =
    challenges.accept(bob, id).flatMap {
      case Left(rejected) => IO.raiseError(RuntimeException(s"accept unexpectedly rejected: $rejected"))
      case Right(_)       => written.get.map(_.headOption.flatMap(_.rated).getOrElse(false))
    }

  /** Create a challenge that the test expects to be admitted, unwrapping the hygiene envelope. */
  private def mustCreate(challenges: Challenges, from: Principal, to: Principal): IO[Challenge] =
    challenges
      .create(from, to)
      .flatMap:
        case Right(created) => IO.pure(created.challenge)
        case Left(rejected) => IO.raiseError(RuntimeException(s"create unexpectedly rejected: $rejected"))

  test("creating a challenge notifies the target's event stream"):
    harness()
      .flatMap: (events, challenges) =>
        events
          .stream(bob)
          .head
          .compile
          .lastOrError
          .background
          .use: waiting =>
            IO.sleep(150.millis) *> mustCreate(challenges, alice, bob) *> waiting.flatMap(_.embedNever)
      .timeoutTo(5.seconds, IO.raiseError(RuntimeException("challenge not delivered")))
      .map:
        case BotEvent.ChallengeReceived(id, challenger) =>
          assert(id.nonEmpty)
          assertEquals(challenger, alice)
        case other => fail(s"expected ChallengeReceived, got: $other")

  test("creation reports whether the target holds a live account stream"):
    harness()
      .flatMap: (events, challenges) =>
        for
          // Nobody is streaming: offline, but the challenge is still created (discoverable by polling).
          offline <- challenges.create(alice, bob)
          online  <- events
            .stream(bob)
            .head
            .compile
            .last
            .background
            .use(_ => IO.sleep(150.millis) *> challenges.create(carol, bob))
        yield
          assertEquals(offline.map(_.targetOnline), Right(false))
          assertEquals(online.map(_.targetOnline), Right(true))

  test("accepting a challenge seats a game and notifies the challenger"):
    harness()
      .flatMap: (events, challenges) =>
        mustCreate(challenges, alice, bob)
          .flatMap: ch =>
            events
              .stream(alice)
              .head
              .compile
              .lastOrError
              .background
              .use: waiting =>
                IO.sleep(150.millis) *> challenges
                  .accept(bob, ch.id)
                  .flatMap: result =>
                    IO(assert(result.isRight, s"accept should succeed: $result")) *> waiting.flatMap(_.embedNever)
      .timeoutTo(5.seconds, IO.raiseError(RuntimeException("gameStart not delivered")))
      .map:
        case BotEvent.GameStart(gameId) => assert(gameId.nonEmpty)
        case other                      => fail(s"expected GameStart, got: $other")

  test("declining a challenge notifies the challenger"):
    harness()
      .flatMap: (events, challenges) =>
        mustCreate(challenges, alice, bob)
          .flatMap: ch =>
            events
              .stream(alice)
              .head
              .compile
              .lastOrError
              .background
              .use: waiting =>
                IO.sleep(150.millis) *> challenges.decline(bob, ch.id) *> waiting.flatMap(_.embedNever)
      .timeoutTo(5.seconds, IO.raiseError(RuntimeException("decline not delivered")))
      .map:
        case BotEvent.ChallengeDeclined(id) => assert(id.nonEmpty)
        case other                          => fail(s"expected ChallengeDeclined, got: $other")

  test("only the challenged bot may accept"):
    harness()
      .flatMap: (_, challenges) =>
        mustCreate(challenges, alice, bob).flatMap(ch => challenges.accept(alice, ch.id))
      .map(result => assertEquals(result, Left(Challenges.Rejected.NotYours): Either[Challenges.Rejected, String]))

  test("accepting an unknown challenge is NotFound"):
    harness()
      .flatMap((_, challenges) => challenges.accept(bob, "nope"))
      .map(result => assertEquals(result, Left(Challenges.Rejected.NotFound): Either[Challenges.Rejected, String]))

  test("listFor separates challenges addressed to the caller from those it created"):
    harness()
      .flatMap: (_, challenges) =>
        for
          toBob     <- mustCreate(challenges, alice, bob)
          toAlice   <- mustCreate(challenges, bob, alice)
          fromCarol <- mustCreate(challenges, carol, alice)
          (in, out) <- challenges.listFor(alice)
        yield
          assertEquals(in.map(_.id).toSet, Set(toAlice.id, fromCarol.id))
          assertEquals(out.map(_.id), List(toBob.id))

  test("a claimed challenge disappears from the listings"):
    harness()
      .flatMap: (_, challenges) =>
        for
          ch       <- mustCreate(challenges, alice, bob)
          _        <- challenges.accept(bob, ch.id)
          (in, _)  <- challenges.listFor(bob)
          (_, out) <- challenges.listFor(alice)
        yield
          assertEquals(in, Nil)
          assertEquals(out, Nil)

  test("a bot cannot challenge itself"):
    harness()
      .flatMap((_, challenges) => challenges.create(alice, alice))
      .map(result => assertEquals(result.left.map(identity), Left(Challenges.CreateRejected.SelfChallenge)))

  test("the per-challenger pending cap rejects the overflow and frees up on decline"):
    harness(maxPendingPerBot = 2)
      .flatMap: (_, challenges) =>
        for
          _        <- mustCreate(challenges, alice, bob)
          second   <- mustCreate(challenges, alice, carol)
          overflow <- challenges.create(alice, Principal.Bot("acme", "dave"))
          _        <- challenges.decline(carol, second.id)
          retry    <- challenges.create(alice, Principal.Bot("acme", "dave"))
        yield
          assertEquals(overflow.left.map(identity), Left(Challenges.CreateRejected.TooManyPending))
          assert(retry.isRight, s"after a decline the cap must free up: $retry")

  test("the TTL sweep expires unclaimed challenges and declines them back to the challenger"):
    harness(ttl = 100.millis)
      .flatMap: (events, challenges) =>
        mustCreate(challenges, alice, bob).flatMap: ch =>
          events
            .stream(alice)
            .head
            .compile
            .lastOrError
            .background
            .use: waiting =>
              for
                _        <- IO.sleep(250.millis) // past the TTL (and lets the subscriber register)
                _        <- challenges.sweep
                (in, _)  <- challenges.listFor(bob)
                (_, out) <- challenges.listFor(alice)
                declined <- waiting.flatMap(_.embedNever)
                notFound <- challenges.accept(bob, ch.id)
              yield
                assertEquals(in, Nil)
                assertEquals(out, Nil)
                assertEquals(declined, BotEvent.ChallengeDeclined(ch.id))
                assertEquals(notFound, Left(Challenges.Rejected.NotFound): Either[Challenges.Rejected, String])
      .timeoutTo(5.seconds, IO.raiseError(RuntimeException("expiry was not delivered")))

  test("an accept refused for capacity leaves the challenge pending, so it can be taken once a game ends (#189)"):
    // A gate that refuses exactly once, then admits: the retry must succeed against the SAME challenge id, which is
    // only possible if the first refusal did not consume it.
    IO.ref(true).flatMap { refuse =>
      harness(admitBoth = (_, _) => refuse.getAndSet(false).map(!_))
        .flatMap: (_, challenges) =>
          for
            ch      <- mustCreate(challenges, alice, bob)
            busy    <- challenges.accept(bob, ch.id)
            stillIn <- challenges.listFor(bob).map(_._1.map(_.id))
            retry   <- challenges.accept(bob, ch.id)
            gone    <- challenges.listFor(bob).map(_._1)
          yield
            assertEquals(busy, Left(Challenges.Rejected.Busy): Either[Challenges.Rejected, String])
            assertEquals(stillIn, List(ch.id), "a busy refusal must not consume the challenge")
            assert(retry.isRight, s"the same challenge must be acceptable once capacity frees up: $retry")
            assertEquals(gone, Nil, "the successful accept claims it")
    }

  test("a capacity refusal is distinguishable from an unknown or someone else's challenge (#189)"):
    harness(admitBoth = (_, _) => IO.pure(false))
      .flatMap: (_, challenges) =>
        for
          ch      <- mustCreate(challenges, alice, bob)
          busy    <- challenges.accept(bob, ch.id)
          unknown <- challenges.accept(bob, "challenge-nope")
          notMine <- challenges.accept(carol, ch.id)
        yield
          assertEquals(busy, Left(Challenges.Rejected.Busy): Either[Challenges.Rejected, String])
          // Identity checks run first: a stranger learns nothing about anyone's capacity.
          assertEquals(unknown, Left(Challenges.Rejected.NotFound): Either[Challenges.Rejected, String])
          assertEquals(notMine, Left(Challenges.Rejected.NotYours): Either[Challenges.Rejected, String])

  // ── rated challenges (#282) ─────────────────────────────────────────────────

  /** Before #282 this path hardcoded casual: `Challenges.seat` called `registry.create` without `requestedRated`, so
    * two registered bots challenging each other directly could never play for rating — the same gap #279 closed for
    * seeks and catalog games.
    */
  /** 5+3: categorised, so the tests below isolate the identity rule from #280's control rule. */
  private val blitz: TimeControl = TimeControl.Fischer(300, 3)

  test("a rated challenge on an uncategorised control is offered as casual (#280)"):
    harness().flatMap: (_, challenges) =>
      for
        // `POST /bot/challenge` still defaults to `Unlimited` — corpus runs legitimately want no clock — so this is
        // the shape a bot written before #280 sends. The offer must say casual, because that is what it will be.
        _  <- challenges.create(alice, bob, rated = true)
        in <- challenges.listFor(bob).map(_._1)
      yield assertEquals(in.map(_.rated), List(false), "the target must not be shown a promise the game cannot keep")

  test("a rated challenge seats a rated game"):
    capturingHarness.flatMap: (challenges, written) =>
      for
        created <- challenges.create(alice, bob, blitz, rated = true)
        id = created.toOption.map(_.challenge.id).getOrElse(fail("create rejected"))
        rated <- seatedRated(challenges, written, id)
      yield assert(rated, "the challenger asked for rated and both bots are registered")

  test("a challenge is casual unless the challenger asks — omitting the flag must not opt in"):
    capturingHarness.flatMap: (challenges, written) =>
      for
        challenge <- mustCreate(challenges, alice, bob)
        rated     <- seatedRated(challenges, written, challenge.id)
      yield assert(!rated, "silence means casual")

  /** The deliberate asymmetry with seeks: a rated SEEK refuses an anonymous accepter so the offer stays open for
    * someone who can play it, but a challenge is addressed to one named bot — there is no third party to leave it for,
    * so refusing would only destroy it. `GameRegistry.isRated` degrades instead.
    */
  test("an anonymous bot's rated challenge is degraded to casual, not refused"):
    val anon = Principal.Bot(BotAuth.AnonTeam, "ephemeral")
    capturingHarness.flatMap: (challenges, written) =>
      for
        created <- challenges.create(anon, bob, rated = true)
        id = created.toOption.map(_.challenge.id).getOrElse(fail("an anon bot must still be allowed to challenge"))
        rated <- seatedRated(challenges, written, id)
      yield assert(!rated, "an anonymous participant can never make a game rated")

  test("the offer carries `rated` so the target can see it before accepting"):
    harness().flatMap: (_, challenges) =>
      for
        _  <- challenges.create(alice, bob, blitz, rated = true)
        in <- challenges.listFor(bob).map(_._1)
      yield assertEquals(in.map(_.rated), List(true), "a polling bot must be able to see what it would agree to")
