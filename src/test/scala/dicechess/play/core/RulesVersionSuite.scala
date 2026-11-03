package dicechess.play.core

import dicechess.engine.domain.GameState

class RulesVersionSuite extends munit.FunSuite:
  test("the build-generated rules version matches the loaded rules dependency"):
    val loadedVersion = Option(classOf[GameState].getPackage.getImplementationVersion)
      .getOrElse(fail("the rules artifact must publish its implementation version"))
    assertEquals(RulesVersion.value, loadedVersion)
