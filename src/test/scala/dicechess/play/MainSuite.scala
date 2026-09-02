package dicechess.play

import cats.effect.IO
import dicechess.play.server.Webhooks
import org.http4s.ember.client.EmberClientBuilder

import scala.concurrent.duration.*

/** The shared client's own deadlines are what silently overrode `WEBHOOK_TIMEOUT_SECONDS` (#188) — a default builder
  * cut every delivery at 45 s no matter what the config said. They are asserted here so removing the wiring fails a
  * test instead of quietly restoring that behaviour.
  */
class MainSuite extends munit.CatsEffectSuite:

  test("the outbound client's cut clears the configured per-turn window"):
    val config  = Webhooks.Config(timeout = 120.seconds)
    val builder = Main.outboundClientBuilder(Some(config))
    assert(
      builder.timeout > config.timeout,
      s"the client cut (${builder.timeout}) must sit above the window (${config.timeout}), or it decides the deadline"
    )
    assertEquals(builder.timeout, config.clientTimeout)

  test("without webhooks the outbound client keeps Ember's own defaults"):
    assertEquals(Main.outboundClientBuilder(None).timeout, EmberClientBuilder.default[IO].timeout)

  test("initShowcaseConfig accepts parsed configuration without reading the ambient environment"):
    Main.initShowcaseConfig(Right(dicechess.play.server.ShowcaseConfig.Disabled)).map { cfg =>
      assertEquals(cfg, dicechess.play.server.ShowcaseConfig.Disabled)
    }

  test("initShowcaseConfig rejects an invalid parsed configuration"):
    Main
      .initShowcaseConfig(Left("bad showcase config"))
      .attempt
      .map: result =>
        assert(result.left.exists(_.getMessage.contains("bad showcase config")))

  test("setupAdmission attaches admissionGuard and resumes rooms"):
    for
      botStore <- dicechess.play.store.BotStore.inMemory
      cfg = dicechess.play.server.ShowcaseConfig(
        enabled = true,
        featuredBot = Some(dicechess.play.core.Principal.Bot("rpi3", "hunter-book")),
        reservedSeats = 1
      )
      registry                             <- dicechess.play.server.GameRegistry.create()
      (admissionGuard, seatGuard, resumed) <- Main.setupAdmission(botStore, cfg, registry)
    yield
      assertEquals(resumed, 0)
      assert(seatGuard.admissionGuard eq admissionGuard)
