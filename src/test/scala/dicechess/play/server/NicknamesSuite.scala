package dicechess.play.server

/** The nickname rules (#234): what `PATCH /auth/me` accepts, and the invariant that the generator only ever produces
  * names its own validator would accept.
  */
class NicknamesSuite extends munit.CatsEffectSuite:

  test("validation trims and accepts a well-formed nickname, keeping the owner's casing"):
    assertEquals(Nicknames.validate("  LuckyRook417  "), Right("LuckyRook417"))
    assertEquals(Nicknames.validate("_under_score-"), Right("_under_score-"))

  test("validation rejects out-of-bounds lengths"):
    assert(Nicknames.validate("ab").isLeft, "two characters is too short")
    assert(Nicknames.validate("a" * 25).isLeft, "twenty-five characters is too long")

  test("validation rejects a leading digit or dash and any character outside the alphabet"):
    assert(Nicknames.validate("1stPlace").isLeft, "a leading digit could read as a number or id")
    assert(Nicknames.validate("-dash").isLeft)
    assert(Nicknames.validate("ni ck").isLeft, "no spaces")
    assert(Nicknames.validate("ник").isLeft, "ASCII alphabet only")
    assert(Nicknames.validate("nick!").isLeft)

  test("validation rejects reserved system words case-insensitively, but not as substrings"):
    for reserved <- List("guest", "Guest", "ADMIN", "anonymous", "anon", "house", "bot", "system") do
      assert(Nicknames.validate(reserved).isLeft, s"'$reserved' must be reserved")
    assertEquals(Nicknames.validate("BotanicalRook"), Right("BotanicalRook"))
    assertEquals(Nicknames.validate("guesswork"), Right("guesswork"))

  test("every generated nickname passes validation and follows the adjective-noun-number shape"):
    Nicknames.fresh.replicateA(200).map { samples =>
      samples.foreach { name =>
        assertEquals(Nicknames.validate(name), Right(name), s"generated '$name' must validate as-is")
        assert(name.matches("^[A-Za-z]+[0-9]{2,3}$"), s"unexpected shape: '$name'")
      }
      // 200 draws from a ~1.1M space collapsing to a handful of values would mean a broken RNG hookup.
      assert(samples.distinct.size > 100, s"suspiciously little variety: ${samples.distinct.size} distinct in 200")
    }
