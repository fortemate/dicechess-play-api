package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.Principal
import dicechess.play.store.{BotCatalogState, BotRating, BotStore}

import scala.concurrent.duration.*

class BotAuthSuite extends munit.CatsEffectSuite:

  private def fromSpec(spec: String, anonTtl: FiniteDuration = BotAuth.DefaultAnonTtl): IO[BotAuth] =
    BotStore.inMemory.flatMap(BotAuth.fromSpec(spec, _, anonTtl))

  test("authenticates known static tokens, rejects unknown"):
    fromSpec("acme|greedy|tok-1,acme|mcts|tok-2")
      .flatMap: auth =>
        for
          a <- auth.authenticate("tok-1")
          b <- auth.authenticate("tok-2")
          c <- auth.authenticate("unknown")
        yield
          assertEquals(a, Some(Principal.Bot("acme", "greedy")))
          assertEquals(b, Some(Principal.Bot("acme", "mcts")))
          assertEquals(c, None)

  test("ignores malformed or empty roster entries"):
    fromSpec("bad-entry,acme|greedy|tok,||,team|name|")
      .flatMap: auth =>
        for
          ok    <- auth.authenticate("tok")
          empty <- auth.authenticate("")
        yield
          assertEquals(ok, Some(Principal.Bot("acme", "greedy")))
          assertEquals(empty, None)

  test("an empty roster authenticates nothing"):
    fromSpec("").flatMap(_.authenticate("anything")).map(assertEquals(_, None))

  test("a static entry in the reserved anon team is rejected"):
    fromSpec("anon|evil|tok").flatMap(_.authenticate("tok")).map(assertEquals(_, None))

  test("a minted anonymous token authenticates as bot:team:anon:*, then expires"):
    fromSpec("", anonTtl = 120.millis)
      .flatMap: auth =>
        for
          minted <- auth.mintAnon(Some("Alice!"))
          (token, bot) = minted
          live       <- auth.authenticate(token)
          countLive  <- auth.anonCount
          _          <- IO.sleep(180.millis)
          expired    <- auth.authenticate(token)
          countAfter <- auth.anonCount
        yield
          assertEquals(bot.team, "anon")
          assert(bot.externalId.startsWith("bot:team:anon:alice-"), bot.externalId)
          assertEquals(live, Some(bot))
          assertEquals(countLive, 1)
          assertEquals(expired, None)
          assertEquals(countAfter, 0)

  test("an anonymous token with no label is bot:team:anon:<uuid>, colon-free"):
    fromSpec("")
      .flatMap: auth =>
        auth
          .mintAnon(None)
          .map: (_, bot) =>
            assertEquals(bot.team, "anon")
            assert(bot.externalId.startsWith("bot:team:anon:"), bot.externalId)
            assert(!bot.name.contains(":"), s"name must be colon-free: ${bot.name}")

  test("a registered token authenticates as its durable identity"):
    fromSpec("")
      .flatMap: auth =>
        for
          registered <- auth.register("dragons", "smaug")
          (token, bot) = registered.toOption.get
          found <- auth.authenticate(token)
          wrong <- auth.authenticate("not-the-token")
        yield
          assertEquals(bot, Principal.Bot("dragons", "smaug"))
          assertEquals(found, Some(bot))
          assertEquals(wrong, None)

  test("registration rejects invalid slugs, reserved teams, and taken identities"):
    fromSpec("acme|greedy|tok")
      .flatMap: auth =>
        for
          badSlug  <- auth.register("Dragons", "smaug") // uppercase: not a slug
          badName  <- auth.register("dragons", "smaug:v2")
          anonTeam <- auth.register("anon", "smaug")
          house    <- auth.register("house", "smaug")   // reserved even with no static entry occupying it
          static   <- auth.register("acme", "greedy")   // the static roster identity can't be claimed
          first    <- auth.register("dragons", "smaug")
          dupe     <- auth.register("dragons", "smaug")
        yield
          assertEquals(badSlug, Left(BotAuth.RegisterRejected.InvalidSlug))
          assertEquals(badName, Left(BotAuth.RegisterRejected.InvalidSlug))
          assertEquals(anonTeam, Left(BotAuth.RegisterRejected.ReservedTeam))
          assertEquals(house, Left(BotAuth.RegisterRejected.ReservedTeam))
          assertEquals(static, Left(BotAuth.RegisterRejected.Taken))
          assert(first.isRight, s"a fresh identity must register: $first")
          assertEquals(dupe.left.map(identity), Left(BotAuth.RegisterRejected.Taken))

  test("rotation invalidates the old token, keeps the identity, and is registered-only"):
    fromSpec("acme|greedy|tok")
      .flatMap: auth =>
        for
          registered <- auth.register("dragons", "smaug")
          (oldToken, bot) = registered.toOption.get
          rotated  <- auth.rotate(bot)
          oldDead  <- auth.authenticate(oldToken)
          newAlive <- auth.authenticate(rotated.get)
          // Static and anon callers cannot rotate: their tokens live in the env / are re-minted instead.
          staticNo <- auth.rotate(Principal.Bot("acme", "greedy"))
          anon     <- auth.mintAnon(None)
          anonNo   <- auth.rotate(anon._2)
        yield
          assert(rotated.isDefined, "a registered bot must be able to rotate")
          assertEquals(oldDead, None)
          assertEquals(newAlive, Some(bot: Principal))
          assertEquals(staticNo, None)
          assertEquals(anonNo, None)

  test("a fresh registration starts at the provisional rating, off the ladder"):
    fromSpec("")
      .flatMap: auth =>
        for
          registered <- auth.register("dragons", "smaug")
          (_, bot) = registered.toOption.get
          rating <- auth.ratingOf(bot)
        yield assertEquals(rating, Some(BotRating.initial))

  test("joining and leaving the ladder persists, and is registered-only"):
    fromSpec("acme|greedy|tok")
      .flatMap: auth =>
        for
          registered <- auth.register("dragons", "smaug")
          (_, bot) = registered.toOption.get
          joined <- auth.setOnLadder(bot, onLadder = true)
          left   <- auth.setOnLadder(bot, onLadder = false)
          // Static and anon callers have no registered row to opt in, same gate as rotation.
          staticNo <- auth.setOnLadder(Principal.Bot("acme", "greedy"), onLadder = true)
          anon     <- auth.mintAnon(None)
          anonNo   <- auth.setOnLadder(anon._2, onLadder = true)
        yield
          assert(joined.exists(_.onLadder), s"joining must persist onLadder=true, got $joined")
          assert(left.exists(r => !r.onLadder), s"leaving must persist onLadder=false, got $left")
          assertEquals(staticNo, None)
          assertEquals(anonNo, None)

  test("opening and closing to human games round-trips the description, and is registered-only"):
    fromSpec("acme|greedy|tok")
      .flatMap: auth =>
        for
          registered <- auth.register("dragons", "smaug")
          (_, bot) = registered.toOption.get
          opened <- auth.openToHumans(bot, Some("aggressive + book"))
          closed <- auth.closeToHumans(bot)
          // Static and anon callers have no registered row to flag — same gate as the ladder.
          staticNo <- auth.openToHumans(Principal.Bot("acme", "greedy"), None)
          anon     <- auth.mintAnon(None)
          anonNo   <- auth.closeToHumans(anon._2)
        yield
          assertEquals(opened, Some(BotCatalogState(openToHumans = true, Some("aggressive + book"))))
          assertEquals(closed, Some(BotCatalogState(openToHumans = false, Some("aggressive + book"))))
          assertEquals(staticNo, None)
          assertEquals(anonNo, None)
