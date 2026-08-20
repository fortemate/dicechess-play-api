package dicechess.play

import cats.effect.IO

class MainSuite extends munit.CatsEffectSuite:

  test("Main server resource boots cleanly and binds port"):
    Main.serverResource.use { server =>
      IO:
        assert(server.address.getPort > 0)
    }
