package dicechess.play.server

import dicechess.play.core.*
import dicechess.play.game.EngineOps
import dicechess.play.store.*
import dicechess.play.wire.Codecs.given
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import java.nio.file.{Files, Path}
import java.time.Instant

class RematchWireSuite extends CatsEffectSuite:
  private val now     = Instant.parse("2026-09-07T12:00:20Z")
  private val id      = GameId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
  private val nextId  = GameId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
  private val players = Map(
    Seat.White -> Principal.Guest("11111111-1111-4111-8111-111111111111"),
    Seat.Black -> Principal.User("22222222-2222-4222-8222-222222222222")
  )
  private val offered = RematchSession(
    id,
    id,
    RematchSource(
      RematchConditions(TimeControl.Fischer(300, 3), false),
      players,
      Map(Seat.White -> "private-verifier")
    ),
    now.minusSeconds(20),
    RematchPhase.Offered,
    1,
    Set(Seat.White),
    Some(Seat.White),
    Instant.parse("2026-09-07T12:00:29Z"),
    None,
    None
  )
  private val initial = GameSnapshot(
    0,
    EngineOps.InitialDfen,
    players.map((seat, owner) => seat.opponent.get -> owner),
    Map(Seat.White -> "example-white-capability", Seat.Black -> "example-black-capability"),
    "a" * 64,
    Map.empty,
    false,
    0,
    false,
    GameStatus.Active,
    TimeControl.Fischer(300, 3),
    Map.empty,
    Nil,
    Vector.empty
  )
  private val successor = RematchSuccessor(
    nextId,
    id,
    initial,
    now,
    now.plusSeconds(15),
    0,
    RematchStartup(RematchStartupPhase.AwaitingJoins, Set.empty, None)
  )

  private def fixtures: Json = parse(Files.readString(Path.of("contracts/rematch-v1.json"))).toOption.get

  test("versioned frontend fixtures match the actual participant/public projections"):
    val matched =
      offered.copy(phase = RematchPhase.Matched, consents = Set(Seat.White, Seat.Black), successorId = Some(nextId))
    val actual = Map(
      "privateOffered"      -> PrivateRematch.of(offered, Seat.White, now, None).asJson,
      "privateMatchedBlack" -> PrivateRematch.of(matched, Seat.White, now, Some(successor)).asJson,
      "publicMatched"       -> PublicContinuation.of(matched, now, Some(successor)).asJson,
      "publicWaiting"       -> PublicContinuation.of(offered, now, None).asJson,
      "publicActiveSource"  -> PublicContinuation(id.value, "waiting", now).asJson,
      "publicClosed"        -> PublicContinuation
        .of(offered.copy(phase = RematchPhase.Closed, closedReason = Some(RematchCloseReason.Declined)), now, None)
        .asJson,
      "startupAwaiting" -> PublicRematchStartup(
        PublicRematchStartupPhase.AwaitingJoins,
        Some(now.plusSeconds(15))
      ).asJson,
      "startupActive"  -> PublicRematchStartup(PublicRematchStartupPhase.Active).asJson,
      "startupAborted" -> PublicRematchStartup(PublicRematchStartupPhase.Aborted).asJson
    )
    actual.foreach((key, json) => assertEquals(json, fixtures.hcursor.downField(key).as[Json].toOption.get, key))

  test("all supported time controls retain their existing wire shape"):
    val controls =
      List(TimeControl.Unlimited, TimeControl.SuddenDeath(300), TimeControl.Fischer(300, 3), TimeControl.PerMove(15))
    assertEquals(controls.asJson, fixtures.hcursor.downField("timeControls").as[Json].toOption.get)
    controls.foreach(control => assertEquals(control.asJson.as[TimeControl], Right(control)))

  test("every public phase uses a strict field allowlist and private join data belongs only to the caller"):
    RematchPhase.values.foreach { phase =>
      val state = offered.copy(phase = phase)
      val json  = PublicContinuation.of(state, now, Option.when(phase == RematchPhase.Matched)(successor)).asJson
      assert(
        json.asObject.get.keys.toSet.subsetOf(Set("sourceGameId", "phase", "serverNow", "deadlineAt", "nextGameId"))
      )
      assert(!json.noSpaces.contains("private-verifier"))
      assert(!json.noSpaces.contains("example-"))
    }
    val matched = offered.copy(phase = RematchPhase.Matched, consents = Set(Seat.White, Seat.Black))
    val first   = PrivateRematch.of(matched, Seat.White, now, Some(successor))
    val second  = PrivateRematch.of(matched, Seat.Black, now, Some(successor))
    assertEquals(first.join.map(_.seat), Some(Seat.Black))
    assertEquals(second.join.map(_.seat), Some(Seat.White))
    assert(!first.asJson.noSpaces.contains(second.join.get.token))
    assert(!second.asJson.noSpaces.contains(first.join.get.token))
    assertEquals(PrivateRematch.of(matched, Seat.White, now, None).phase, "starting")
    assertEquals(PrivateRematch.of(matched, Seat.White, now, None).join, None)

  test("rate limiter rejects new keys at its hard cap while preserving existing budgets"):
    for
      limiter  <- RematchLimiter.create(RematchLimiter.Config(readsPerMinute = 2, writesPerMinute = 1, maxKeys = 2))
      first    <- limiter.attempt("a", false)
      second   <- limiter.attempt("b", false)
      overflow <- limiter.attempt("c", false)
      repeat   <- limiter.attempt("a", false)
      spent    <- limiter.attempt("a", false)
      write    <- limiter.attempt("a", true)
    yield assertEquals(List(first, second, overflow, repeat, spent, write), List(true, true, false, true, false, false))
