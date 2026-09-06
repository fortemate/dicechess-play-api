package dicechess.play.server

import io.circe.DecodingFailure
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.Status

class SessionWebhookProtocolSuite extends munit.FunSuite:

  test("ManagedWebhookSetupRequest decodes valid Create payload"):
    val json     = """{"kind":"create","url":"https://bot.example/webhook","capabilities":["draws"]}"""
    val expected = ManagedWebhookSetupRequest.Create("https://bot.example/webhook", List("draws"))
    assertEquals(decode[ManagedWebhookSetupRequest](json), Right(expected))

  test("ManagedWebhookSetupRequest decodes valid ReplaceUrl payload"):
    val json     = """{"kind":"replaceUrl","url":"https://bot.example/new-url","confirmSecretRotation":true}"""
    val expected = ManagedWebhookSetupRequest.ReplaceUrl("https://bot.example/new-url")
    assertEquals(decode[ManagedWebhookSetupRequest](json), Right(expected))

  test("ManagedWebhookSetupRequest decodes valid RotateSecret payload"):
    val json     = """{"kind":"rotateSecret","cutoverMode":"dualKey","confirm":"my-bot"}"""
    val expected = ManagedWebhookSetupRequest.RotateSecret("my-bot")
    assertEquals(decode[ManagedWebhookSetupRequest](json), Right(expected))

  test("ManagedWebhookSetupRequest rejects non-JSON-object inputs"):
    val inputs = List(
      "\"just a string\"",
      "[1, 2, 3]",
      "123",
      "true",
      "null"
    )
    inputs.foreach { input =>
      val result = decode[ManagedWebhookSetupRequest](input)
      assert(result.isLeft, s"Input '$input' should fail decoding")
    }

  test("ManagedWebhookSetupRequest rejects payload missing kind field"):
    val json = """{"url":"https://bot.example/webhook","capabilities":["draws"]}"""
    assert(decode[ManagedWebhookSetupRequest](json).isLeft)

  test("ManagedWebhookSetupRequest rejects unknown or invalid kind field"):
    val invalidKinds = List(
      """{"kind":"unknown","url":"https://bot.example/webhook"}""",
      """{"kind":"delete","url":"https://bot.example/webhook"}""",
      """{"kind":123,"url":"https://bot.example/webhook"}"""
    )
    invalidKinds.foreach { json =>
      val result = decode[ManagedWebhookSetupRequest](json)
      assert(result.isLeft, s"JSON '$json' should fail decoding")
    }

  test("ManagedWebhookSetupRequest.Create rejects extra or missing fields"):
    val cases = List(
      // extra field
      """{"kind":"create","url":"https://bot.example/webhook","capabilities":["draws"],"extra":"field"}""",
      // missing capabilities
      """{"kind":"create","url":"https://bot.example/webhook"}""",
      // missing url
      """{"kind":"create","capabilities":["draws"]}""",
      // wrong field type for url
      """{"kind":"create","url":123,"capabilities":["draws"]}""",
      // wrong field type for capabilities
      """{"kind":"create","url":"https://bot.example/webhook","capabilities":"draws"}"""
    )
    cases.foreach { json =>
      assert(decode[ManagedWebhookSetupRequest](json).isLeft, s"JSON '$json' should fail decoding")
    }

  test("ManagedWebhookSetupRequest.ReplaceUrl rejects invalid payloads and confirmSecretRotation != true"):
    val cases = List(
      // confirmSecretRotation = false
      """{"kind":"replaceUrl","url":"https://bot.example/new-url","confirmSecretRotation":false}""",
      // confirmSecretRotation as string
      """{"kind":"replaceUrl","url":"https://bot.example/new-url","confirmSecretRotation":"true"}""",
      // extra field
      """{"kind":"replaceUrl","url":"https://bot.example/new-url","confirmSecretRotation":true,"extra":"x"}""",
      // missing url
      """{"kind":"replaceUrl","confirmSecretRotation":true}""",
      // missing confirmSecretRotation
      """{"kind":"replaceUrl","url":"https://bot.example/new-url"}"""
    )
    cases.foreach { json =>
      assert(decode[ManagedWebhookSetupRequest](json).isLeft, s"JSON '$json' should fail decoding")
    }

  test("ManagedWebhookSetupRequest.ReplaceUrl error message when confirmSecretRotation is false"):
    val json = """{"kind":"replaceUrl","url":"https://bot.example/new-url","confirmSecretRotation":false}"""
    decode[ManagedWebhookSetupRequest](json) match
      case Left(failure: DecodingFailure) =>
        assert(failure.message.contains("confirmSecretRotation must be true"))
      case res => fail(s"Expected Left(DecodingFailure), got $res")

  test("ManagedWebhookSetupRequest.RotateSecret rejects invalid payloads and cutoverMode != dualKey"):
    val cases = List(
      // cutoverMode = immediate
      """{"kind":"rotateSecret","cutoverMode":"immediate","confirm":"my-bot"}""",
      // extra field
      """{"kind":"rotateSecret","cutoverMode":"dualKey","confirm":"my-bot","extra":"x"}""",
      // missing cutoverMode
      """{"kind":"rotateSecret","confirm":"my-bot"}""",
      // missing confirm
      """{"kind":"rotateSecret","cutoverMode":"dualKey"}""",
      // wrong type for confirm
      """{"kind":"rotateSecret","cutoverMode":"dualKey","confirm":123}"""
    )
    cases.foreach { json =>
      assert(decode[ManagedWebhookSetupRequest](json).isLeft, s"JSON '$json' should fail decoding")
    }

  test("ManagedWebhookSetupRequest.RotateSecret error message when cutoverMode is not dualKey"):
    val json = """{"kind":"rotateSecret","cutoverMode":"immediate","confirm":"my-bot"}"""
    decode[ManagedWebhookSetupRequest](json) match
      case Left(failure: DecodingFailure) =>
        assert(failure.message.contains("cutoverMode must be dualKey"))
      case res => fail(s"Expected Left(DecodingFailure), got $res")

  test("ActivateManagedWebhook decodes valid payload and rejects invalid payloads"):
    assertEquals(decode[ActivateManagedWebhook]("""{"secretStored":true}"""), Right(ActivateManagedWebhook(true)))
    assert(decode[ActivateManagedWebhook]("""{"secretStored":false}""").isLeft)
    assert(decode[ActivateManagedWebhook]("""{"secretStored":"true"}""").isLeft)
    assert(decode[ActivateManagedWebhook]("""{"secretStored":true,"extra":1}""").isLeft)
    assert(decode[ActivateManagedWebhook]("""{}""").isLeft)

  test("UpdateManagedWebhookCapabilities decodes valid payload and rejects invalid payloads"):
    assertEquals(
      decode[UpdateManagedWebhookCapabilities]("""{"capabilities":["draws"]}"""),
      Right(UpdateManagedWebhookCapabilities(List("draws")))
    )
    assert(decode[UpdateManagedWebhookCapabilities]("""{"capabilities":[]}""").isRight)
    assert(decode[UpdateManagedWebhookCapabilities]("""{"capabilities":"draws"}""").isLeft)
    assert(decode[UpdateManagedWebhookCapabilities]("""{"capabilities":["draws"],"extra":1}""").isLeft)
    assert(decode[UpdateManagedWebhookCapabilities]("""{}""").isLeft)

  test("DeleteManagedWebhook decodes valid payload and rejects invalid payloads"):
    assertEquals(decode[DeleteManagedWebhook]("""{"confirm":"alice"}"""), Right(DeleteManagedWebhook("alice")))
    assert(decode[DeleteManagedWebhook]("""{"confirm":123}""").isLeft)
    assert(decode[DeleteManagedWebhook]("""{"confirm":"alice","extra":1}""").isLeft)
    assert(decode[DeleteManagedWebhook]("""{}""").isLeft)

  test("ManagedWebhookProblem JSON encoding includes required fields and optional current slot"):
    val problemWithoutCurrent = ManagedWebhookProblem(
      status = Status.NotFound,
      code = "bot_not_found",
      title = "Bot Not Found",
      detail = "The specified bot was not found.",
      instance = "/me/bots/acme/alice/webhook"
    )
    val jsonWithoutCurrent = problemWithoutCurrent.asJson.noSpaces
    assert(jsonWithoutCurrent.contains(""""type":"https://bots.fortemate.com/reference/webhooks/#bot-not-found""""))
    assert(jsonWithoutCurrent.contains(""""status":404"""))
    assert(jsonWithoutCurrent.contains(""""code":"bot_not_found""""))
    assert(!jsonWithoutCurrent.contains(""""current""""))

    val slot = ManagedWebhookSlot(
      revision = "whrev_123",
      registration = None,
      pendingSetup = None
    )
    val problemWithCurrent = problemWithoutCurrent.copy(current = Some(slot))
    val jsonWithCurrent    = problemWithCurrent.asJson.noSpaces
    assert(jsonWithCurrent.contains(""""current":{"""))
    assert(jsonWithCurrent.contains(""""revision":"whrev_123""""))
