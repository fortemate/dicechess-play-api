package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.Principal
import dicechess.play.core.WebhookCapability
import dicechess.play.wire.Codecs.given
import io.circe.syntax.*
import io.circe.{Codec, Decoder, DecodingFailure, Encoder, HCursor, Json, JsonObject}
import org.http4s.Status

import java.time.Instant

enum ManagedWebhookActor:
  case Owner(id: String)
  case Admin(id: String)

  def userId: String = this match
    case Owner(id) => id
    case Admin(id) => id

  def kind: String = this match
    case Owner(_) => "owner"
    case Admin(_) => "admin"

/** Public, redacted registration state for the owner/admin session-management API (ADR-004). */
final case class ManagedWebhookRegistration(
    registrationId: String,
    url: String,
    verifiedAt: Instant,
    capabilities: List[WebhookCapability],
    lastFailure: Option[LastDeliveryFailure]
) derives Codec.AsObject

/** Public metadata for the sole live candidate. Candidate credentials never enter this shape. */
final case class ManagedPendingWebhookSetup(
    setupId: String,
    kind: String,
    candidateUrl: String,
    createdAt: Instant,
    expiresAt: Instant,
    canActivate: Boolean
) derives Codec.AsObject

/** The authoritative webhook control-plane state. Both optional fields deliberately encode as JSON `null`. */
final case class ManagedWebhookSlot(
    revision: String,
    registration: Option[ManagedWebhookRegistration],
    pendingSetup: Option[ManagedPendingWebhookSetup]
) derives Codec.AsObject

/** The only secret-bearing response in the session-management API. */
final case class ManagedWebhookSetupCreated(
    setupId: String,
    kind: String,
    secret: String,
    expiresAt: Instant,
    revision: String
) derives Codec.AsObject

/** Session stats make the bot-history scope and current generation explicit without changing the legacy Bot API DTO. */
final case class ManagedWebhookDeliveryStats(
    scope: String,
    registrationId: Option[String],
    last24h: DeliveryWindow,
    last7d: DeliveryWindow,
    lastFailure: Option[LastDeliveryFailure]
) derives Codec.AsObject

/** Exact discriminated setup requests. Custom decoding rejects fields belonging to another variant (and all unknown
  * fields), rather than Circe's usual permissive derived-decoder behaviour.
  */
enum ManagedWebhookSetupRequest:
  case Create(url: String, capabilities: List[String])
  case ReplaceUrl(url: String)
  case RotateSecret(confirm: String)

object ManagedWebhookSetupRequest:
  private val CreateFields  = Set("kind", "url", "capabilities")
  private val ReplaceFields = Set("kind", "url", "confirmSecretRotation")
  private val RotateFields  = Set("kind", "cutoverMode", "confirm")

  given Decoder[ManagedWebhookSetupRequest] = Decoder.instance { cursor =>
    cursor
      .get[String]("kind")
      .flatMap:
        case "create" =>
          exact(cursor, CreateFields) *> (cursor.get[String]("url"), cursor.get[List[String]]("capabilities"))
            .mapN(Create.apply)
        case "replaceUrl" =>
          exact(cursor, ReplaceFields) *>
            (cursor.get[String]("url"), cursor.get[Boolean]("confirmSecretRotation")).flatMapN {
              case (url, true) => Right(ReplaceUrl(url))
              case _           => Left(DecodingFailure("confirmSecretRotation must be true", cursor.history))
            }
        case "rotateSecret" =>
          exact(cursor, RotateFields) *>
            (cursor.get[String]("cutoverMode"), cursor.get[String]("confirm")).flatMapN {
              case ("dualKey", confirm) => Right(RotateSecret(confirm))
              case _                    => Left(DecodingFailure("cutoverMode must be dualKey", cursor.history))
            }
        case _ => Left(DecodingFailure("kind must be create, replaceUrl, or rotateSecret", cursor.history))
  }

  private def exact(cursor: HCursor, expected: Set[String]): Decoder.Result[Unit] =
    cursor.value.asObject match
      case None      => Left(DecodingFailure("request body must be a JSON object", cursor.history))
      case Some(obj) =>
        val actual = obj.keys.toSet
        Either.cond(
          actual == expected,
          (),
          DecodingFailure(
            s"request fields must be exactly: ${expected.toList.sorted.mkString(", ")}",
            cursor.history
          )
        )

  extension [A, B](pair: (Decoder.Result[A], Decoder.Result[B]))
    private def mapN[C](f: (A, B) => C): Decoder.Result[C] = pair._1.flatMap(a => pair._2.map(b => f(a, b)))

  extension [A, B](pair: (Decoder.Result[A], Decoder.Result[B]))
    private def flatMapN[C](f: (A, B) => Decoder.Result[C]): Decoder.Result[C] =
      pair._1.flatMap(a => pair._2.flatMap(b => f(a, b)))

final case class ActivateManagedWebhook(secretStored: Boolean)
object ActivateManagedWebhook:
  given Decoder[ActivateManagedWebhook] = exactBoolean("secretStored", mustBe = true).map(ActivateManagedWebhook.apply)

final case class UpdateManagedWebhookCapabilities(capabilities: List[String])
object UpdateManagedWebhookCapabilities:
  given Decoder[UpdateManagedWebhookCapabilities] = exactSingleField("capabilities")(_.as[List[String]])
    .map(UpdateManagedWebhookCapabilities.apply)

final case class DeleteManagedWebhook(confirm: String)
object DeleteManagedWebhook:
  given Decoder[DeleteManagedWebhook] = exactSingleField("confirm")(_.as[String]).map(DeleteManagedWebhook.apply)

private def exactBoolean(field: String, mustBe: Boolean): Decoder[Boolean] =
  exactSingleField(field)(_.as[Boolean]).emap(value => Either.cond(value == mustBe, value, s"$field must be $mustBe"))

private def exactSingleField[A](field: String)(decode: Json => Decoder.Result[A]): Decoder[A] =
  Decoder.instance { cursor =>
    cursor.value.asObject match
      case Some(obj) if obj.keys.toSet == Set(field) =>
        obj(field).toRight(DecodingFailure(s"missing $field", cursor.history)).flatMap(decode)
      case Some(_) => Left(DecodingFailure(s"request fields must be exactly: $field", cursor.history))
      case None    => Left(DecodingFailure("request body must be a JSON object", cursor.history))
  }

/** Stable RFC 9457-style problem body. `current` is emitted only for a stale-revision response. */
final case class ManagedWebhookProblem(
    status: Status,
    code: String,
    title: String,
    detail: String,
    instance: String,
    current: Option[ManagedWebhookSlot] = None,
    retryAfterSeconds: Option[Long] = None
)

/** Service-side failure before the request-specific `instance` URI is attached by the route. */
final case class ManagedWebhookFailure(
    status: Status,
    code: String,
    title: String,
    detail: String,
    current: Option[ManagedWebhookSlot] = None,
    retryAfterSeconds: Option[Long] = None
)

/** One implementation shared by the owner and administrator route roots. The service owns state transitions,
  * verification and commit-time authority rechecks; routes own HTTP/session/CSRF concerns.
  */
trait SessionWebhookService:
  def read(bot: Principal.Bot, actor: ManagedWebhookActor): IO[Either[ManagedWebhookFailure, ManagedWebhookSlot]]

  def createSetup(
      bot: Principal.Bot,
      actor: ManagedWebhookActor,
      expectedRevision: String,
      request: ManagedWebhookSetupRequest,
      requestId: String,
      sourceIp: String
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookSetupCreated]]

  def activate(
      bot: Principal.Bot,
      actor: ManagedWebhookActor,
      expectedRevision: String,
      setupId: String,
      requestId: String,
      sourceIp: String,
      stillAuthorized: IO[Boolean]
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookSlot]]

  def cancelSetup(
      bot: Principal.Bot,
      actor: ManagedWebhookActor,
      expectedRevision: String,
      setupId: String,
      requestId: String
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookSlot]]

  def updateCapabilities(
      bot: Principal.Bot,
      actor: ManagedWebhookActor,
      expectedRevision: String,
      capabilities: List[String],
      requestId: String
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookSlot]]

  def delete(
      bot: Principal.Bot,
      actor: ManagedWebhookActor,
      expectedRevision: String,
      requestId: String
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookSlot]]

  def stats(
      bot: Principal.Bot,
      actor: ManagedWebhookActor
  ): IO[Either[ManagedWebhookFailure, ManagedWebhookDeliveryStats]]

object ManagedWebhookProblem:
  given Encoder.AsObject[ManagedWebhookProblem] = Encoder.AsObject.instance { problem =>
    val base = JsonObject(
      "type"     -> s"https://docs.dicechess.org/problems/${problem.code.replace('_', '-')}".asJson,
      "title"    -> problem.title.asJson,
      "status"   -> problem.status.code.asJson,
      "code"     -> problem.code.asJson,
      "detail"   -> problem.detail.asJson,
      "instance" -> problem.instance.asJson
    )
    problem.current.fold(base)(slot => base.add("current", slot.asJson))
  }
