package dicechess.play.core

import scala.io.Source
import scala.util.Using

class WebhookCapabilitySuite extends munit.FunSuite:

  private def source(path: String): String =
    Using(Source.fromFile(path, "UTF-8"))(_.mkString)
      .fold(error => fail(s"cannot read $path: $error"), identity)

  test("the registry pins exact names, stable order, status and selectability (#35)"):
    assertEquals(
      WebhookCapability.registry.map(capability =>
        (capability.wireName, capability.status.wireName, capability.selectable)
      ),
      List(
        ("draws", "available", true),
        ("doubling", "reserved", false)
      )
    )
    assertEquals(WebhookCapability.selectableCapabilities, List(WebhookCapability.Draws))

  test("registration parsing is exact and canonicalizes duplicate selectable values (#35)"):
    assertEquals(WebhookCapability.parseSelection(Nil), Right(Nil))
    assertEquals(
      WebhookCapability.parseSelection(List("draws", "draws")),
      Right(List(WebhookCapability.Draws))
    )

  test("registration parsing rejects unknown spellings and reserved capabilities (#35)"):
    List("Draws", " draws ", "double", "unknown").foreach: name =>
      assertEquals(WebhookCapability.parseSelection(List(name)), Left(s"unknown webhook capability: $name"))
    assertEquals(
      WebhookCapability.parseSelection(List("doubling")),
      Left("webhook capability is not available: doubling")
    )

  test("the typed service boundary also refuses reserved values and canonicalizes duplicates (#35)"):
    assertEquals(
      WebhookCapability.canonicalizeSelection(List(WebhookCapability.Draws, WebhookCapability.Draws)),
      Right(List(WebhookCapability.Draws))
    )
    assertEquals(
      WebhookCapability.canonicalizeSelection(List(WebhookCapability.Draws, WebhookCapability.Doubling)),
      Left("webhook capability is not available: doubling")
    )

  test("OpenAPI pins the enum, selectable subset and discovery order to the runtime registry (#35)"):
    val openApi         = source("docs/public/openapi.yaml")
    val knownNames      = WebhookCapability.registry.map(_.wireName).mkString(", ")
    val selectableNames = WebhookCapability.selectableCapabilities.map(_.wireName).mkString(", ")
    val statuses        = WebhookCapabilityStatus.values.map(_.wireName).mkString(", ")
    val example = WebhookCapability.registry
      .map(capability =>
        s"          - { name: ${capability.wireName}, status: ${capability.status.wireName}, selectable: ${capability.selectable} }"
      )
      .mkString("\n")

    assert(openApi.contains(s"    WebhookCapability:\n      type: string\n      enum: [$knownNames]"))
    assert(openApi.contains(s"    SelectableWebhookCapability:\n      type: string\n      enum: [$selectableNames]"))
    assert(openApi.contains(s"    WebhookCapabilityStatus:\n      type: string\n      enum: [$statuses]"))
    assert(openApi.contains(s"      example:\n        capabilities:\n$example"))
    assertEquals(
      WebhookCapability.parseSelection(List("draws", "doubling")),
      Left("webhook capability is not available: doubling")
    )
