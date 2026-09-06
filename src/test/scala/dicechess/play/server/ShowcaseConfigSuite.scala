package dicechess.play.server

import dicechess.play.core.Principal

class ShowcaseConfigSuite extends munit.FunSuite:

  test(
    "SHOWCASE_ENABLED absent, empty, false, 0, and padded/upper-case variants short-circuit to ShowcaseConfig.Disabled"
  ):
    val garbageTeam  = Some("invalid-team")
    val garbageName  = Some("invalid-name")
    val garbageSeats = Some("garbage-seats")

    assertEquals(
      ShowcaseConfig.fromValues(None, garbageTeam, garbageName, garbageSeats),
      Right(ShowcaseConfig.Disabled)
    )
    assertEquals(
      ShowcaseConfig.fromValues(Some(""), garbageTeam, garbageName, garbageSeats),
      Right(ShowcaseConfig.Disabled)
    )
    assertEquals(
      ShowcaseConfig.fromValues(Some("false"), garbageTeam, garbageName, garbageSeats),
      Right(ShowcaseConfig.Disabled)
    )
    assertEquals(
      ShowcaseConfig.fromValues(Some("0"), garbageTeam, garbageName, garbageSeats),
      Right(ShowcaseConfig.Disabled)
    )
    assertEquals(
      ShowcaseConfig.fromValues(Some(" FALSE "), garbageTeam, garbageName, garbageSeats),
      Right(ShowcaseConfig.Disabled)
    )
    assertEquals(
      ShowcaseConfig.fromValues(Some("False"), garbageTeam, garbageName, garbageSeats),
      Right(ShowcaseConfig.Disabled)
    )

  test("SHOWCASE_ENABLED accepts valid true/1/TRUE values and rejects invalid boolean strings"):
    val validTeam      = Some("teamA")
    val validName      = Some("botA")
    val validSeats     = Some("1")
    val expectedConfig = ShowcaseConfig(enabled = true, Some(Principal.Bot("teamA", "botA")), 1)

    assertEquals(ShowcaseConfig.fromValues(Some("true"), validTeam, validName, validSeats), Right(expectedConfig))
    assertEquals(ShowcaseConfig.fromValues(Some("1"), validTeam, validName, validSeats), Right(expectedConfig))
    assertEquals(ShowcaseConfig.fromValues(Some(" TRUE "), validTeam, validName, validSeats), Right(expectedConfig))

    val invalidYes = ShowcaseConfig.fromValues(Some("yes"), validTeam, validName, validSeats)
    assert(invalidYes.isLeft, "expected Left for 'yes'")
    assertEquals(
      invalidYes.left.toOption,
      Some("SHOWCASE_ENABLED must be true/false or 1/0, got: 'yes'")
    )

    val invalid2 = ShowcaseConfig.fromValues(Some("2"), validTeam, validName, validSeats)
    assert(invalid2.isLeft, "expected Left for '2'")
    assertEquals(
      invalid2.left.toOption,
      Some("SHOWCASE_ENABLED must be true/false or 1/0, got: '2'")
    )

  test("validation fails when enabled with missing or blank team"):
    val missingTeam = ShowcaseConfig.fromValues(Some("true"), None, Some("botA"), Some("1"))
    assertEquals(missingTeam, Left("SHOWCASE_BOT_TEAM is required when SHOWCASE_ENABLED=true"))

    val blankTeam = ShowcaseConfig.fromValues(Some("true"), Some("   "), Some("botA"), Some("1"))
    assertEquals(blankTeam, Left("SHOWCASE_BOT_TEAM is required when SHOWCASE_ENABLED=true"))

  test("validation fails when enabled with missing or blank name"):
    val missingName = ShowcaseConfig.fromValues(Some("true"), Some("teamA"), None, Some("1"))
    assertEquals(missingName, Left("SHOWCASE_BOT_NAME is required when SHOWCASE_ENABLED=true"))

    val blankName = ShowcaseConfig.fromValues(Some("true"), Some("teamA"), Some("   "), Some("1"))
    assertEquals(blankName, Left("SHOWCASE_BOT_NAME is required when SHOWCASE_ENABLED=true"))

  test("validation fails when enabled with missing, non-integer, or non-1 reserved seats"):
    val missingSeats = ShowcaseConfig.fromValues(Some("true"), Some("teamA"), Some("botA"), None)
    assertEquals(missingSeats, Left("SHOWCASE_RESERVED_SEATS is required when SHOWCASE_ENABLED=true"))

    val seats0 = ShowcaseConfig.fromValues(Some("true"), Some("teamA"), Some("botA"), Some("0"))
    assertEquals(seats0, Left("SHOWCASE_RESERVED_SEATS must be exactly 1 when SHOWCASE_ENABLED=true, got: 0"))

    val seats2 = ShowcaseConfig.fromValues(Some("true"), Some("teamA"), Some("botA"), Some("2"))
    assertEquals(seats2, Left("SHOWCASE_RESERVED_SEATS must be exactly 1 when SHOWCASE_ENABLED=true, got: 2"))

    val seatsX = ShowcaseConfig.fromValues(Some("true"), Some("teamA"), Some("botA"), Some("x"))
    assertEquals(seatsX, Left("SHOWCASE_RESERVED_SEATS must be an integer, got: 'x'"))

  test("happy path trims padded values correctly"):
    val result = ShowcaseConfig.fromValues(
      Some("true"),
      Some(" rpi3 "),
      Some(" hunter-book "),
      Some(" 1 ")
    )
    val expected = ShowcaseConfig(enabled = true, Some(Principal.Bot("rpi3", "hunter-book")), 1)
    assertEquals(result, Right(expected))

  test("isFeatured is true only for the featured bot of an enabled config"):
    val bot: Principal.Bot      = Principal.Bot("rpi3", "hunter-book")
    val otherBot: Principal.Bot = Principal.Bot("rpi3", "other-bot")
    val enabledConfig           = ShowcaseConfig(enabled = true, Some(bot), 1)

    assertEquals(enabledConfig.isFeatured(bot), true)
    assertEquals(enabledConfig.isFeatured(otherBot), false)
    assertEquals(ShowcaseConfig.Disabled.isFeatured(bot), false)
