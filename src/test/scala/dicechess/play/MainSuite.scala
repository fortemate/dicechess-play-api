package dicechess.play

import cats.effect.IO
import dicechess.play.server.Webhooks
import org.http4s.ember.client.EmberClientBuilder

import scala.concurrent.duration.*

/** The shared client's own deadlines are what silently overrode `WEBHOOK_TIMEOUT_SECONDS` (#188) — a default builder
  * cut every delivery at 45 s no matter what the config said. They are asserted here so removing the wiring fails a
  * test instead of quietly restoring that behaviour.
  */
class MainSuite extends munit.FunSuite:

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
