package dicechess.play.core

class IdentitySuite extends munit.FunSuite:

  test("GameOrigin round-trips through wireName and fromWireName with case-insensitivity"):
    GameOrigin.values.foreach: origin =>
      assertEquals(GameOrigin.fromWireName(origin.wireName), Some(origin))
      assertEquals(GameOrigin.fromWireName(origin.wireName.toUpperCase), Some(origin))

    assertEquals(GameOrigin.fromWireName("SHOWCASE"), Some(GameOrigin.Showcase))
    assertEquals(GameOrigin.fromWireName("unknown"), None)
    assertEquals(GameOrigin.fromWireName(""), None)

  test("GameOrigin and AdmissionPurpose mapping rules"):
    assertEquals(GameOrigin.Showcase.admissionPurpose, AdmissionPurpose.Showcase)
    assertEquals(GameOrigin.Ladder.admissionPurpose, AdmissionPurpose.Ladder)
    assertEquals(GameOrigin.Catalog.admissionPurpose, AdmissionPurpose.Direct)
    assertEquals(GameOrigin.Lobby.admissionPurpose, AdmissionPurpose.Direct)
    assertEquals(GameOrigin.Direct.admissionPurpose, AdmissionPurpose.Direct)
    assertEquals(GameOrigin.Legacy.admissionPurpose, AdmissionPurpose.Direct)

    GameOrigin.values.foreach: origin =>
      assertEquals(origin.isShowcase, origin == GameOrigin.Showcase)

    AdmissionPurpose.values.foreach: purpose =>
      assertEquals(purpose.isShowcase, purpose == AdmissionPurpose.Showcase)
      assertEquals(purpose.isGeneral, !purpose.isShowcase)

  test("Principal.externalId generates canonical analytics external-id strings"):
    assertEquals(Principal.Guest("guest-uuid-123").externalId, "guest:guest-uuid-123")
    assertEquals(Principal.User("user-uuid-456").externalId, "user:user-uuid-456")
    assertEquals(Principal.Bot("rpi3", "hunter-book").externalId, "bot:team:rpi3:hunter-book")

  test("Principal.fromBotExternalId parses valid bot external ids and rejects invalid shapes"):
    val bot: Principal.Bot = Principal.Bot("rpi3", "hunter-book")
    assertEquals(Principal.fromBotExternalId(bot.externalId), Some(bot))

    assertEquals(Principal.fromBotExternalId("guest:550e8400-e29b-41d4-a716-446655440000"), None)
    assertEquals(Principal.fromBotExternalId("user:550e8400-e29b-41d4-a716-446655440000"), None)
    assertEquals(Principal.fromBotExternalId("bot:random"), None)
    assertEquals(Principal.fromBotExternalId("bot:team::hunter-book"), None)
    assertEquals(Principal.fromBotExternalId("bot:team:rpi3:"), None)
    assertEquals(Principal.fromBotExternalId("bot:team:rpi3:hunter-book:extra"), None)

  test("Principal.fromUserExternalId returns bare id for user:<uuid> and None for invalid shapes"):
    val validUuid = "550e8400-e29b-41d4-a716-446655440000"
    val user      = Principal.User(validUuid)

    assertEquals(Principal.fromUserExternalId(user.externalId), Some(validUuid))
    assertEquals(Principal.fromUserExternalId(s"user:$validUuid"), Some(validUuid))

    assertEquals(Principal.fromUserExternalId("user:not-a-valid-uuid"), None)
    assertEquals(Principal.fromUserExternalId(s"guest:$validUuid"), None)
    assertEquals(Principal.fromUserExternalId(s"user:$validUuid:extra"), None)

  test("Principal.guest validates UUIDs and rejects empty, colon-containing, or non-UUID ids"):
    val validUuid                      = "550e8400-e29b-41d4-a716-446655440000"
    val expectedGuest: Principal.Guest = Principal.Guest(validUuid)
    assertEquals(Principal.guest(validUuid), Right(expectedGuest))

    val emptyRes = Principal.guest("")
    assert(emptyRes.isLeft, "expected Left for empty string")
    assert(
      emptyRes.left.toOption.exists(_.contains("not a valid guest id")),
      s"expected message to contain 'not a valid guest id', got: ${emptyRes.left.toOption}"
    )

    val colonRes = Principal.guest("guest:550e8400-e29b-41d4-a716-446655440000")
    assert(colonRes.isLeft, "expected Left for colon-containing string")
    assert(
      colonRes.left.toOption.exists(_.contains("not a valid guest id")),
      s"expected message to contain 'not a valid guest id', got: ${colonRes.left.toOption}"
    )

    val invalidUuidRes = Principal.guest("invalid-guest-uuid")
    assert(invalidUuidRes.isLeft, "expected Left for non-UUID string")
    assert(
      invalidUuidRes.left.toOption.exists(_.contains("not a valid guest id")),
      s"expected message to contain 'not a valid guest id', got: ${invalidUuidRes.left.toOption}"
    )
