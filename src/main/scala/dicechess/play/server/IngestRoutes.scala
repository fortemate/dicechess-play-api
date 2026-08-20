package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.GameId
import dicechess.play.store.ClientReportStore
import io.circe.Json
import org.http4s.dsl.io.*
import org.http4s.headers.`Retry-After`
import org.http4s.{HttpRoutes, Request, Response}

import java.nio.charset.StandardCharsets

/** Public intake for browser-submitted game reports (#212): the SPA's games against its own in-browser bots, previously
  * relayed by a standalone token-holding gateway (ADR-0005). Unauthenticated by design — the browser must never hold
  * the analytics Bearer token — so every cheap defense the gateway had runs here, cheapest first: the per-IP rate limit
  * before the body is read, the size cap while it streams, then structural validation. What survives is enqueued into
  * `client_reports` and drained by the same [[dicechess.play.ingest.IngestDeliverer]] as the first-party outbox;
  * acceptance is therefore asynchronous, and the analytics replay gate — still the authoritative validator — parks a
  * bad report server-side instead of answering the browser.
  *
  * The status contract is what the SPA's outbox already classifies (do not widen it): `201` accepted, `200` duplicate,
  * `400` malformed JSON, `422` structurally rejected — both permanent, the client quarantines — `413` too large, `429`
  * rate limited, both retried. Reports go NOWHERE but the relay queue: a forgeable payload must never reach
  * `game_results`, `game_archive`, or `/history`.
  */
object IngestRoutes:

  /** Mirrors the gateway's `MAX_BODY_BYTES`: a real report is a few KB; 256 KB is generous headroom, not a target. */
  private[server] val MaxBodyBytes: Long = 256L * 1024

  /** Pinning `source` is what keeps this from being an open relay: only playsite reports are forwarded. */
  private[server] val ExpectedSource = "playsite"

  def apply(reports: ClientReportStore, limiter: AnonMintLimiter): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case req @ POST -> Root / "ingest" / "games" =>
        limiter
          .attempt(BotRoutes.clientIp(req))
          .flatMap:
            case Left(retryAfter) =>
              TooManyRequests("ingest rate limit exceeded — retry later")
                .map(_.putHeaders(`Retry-After`.unsafeFromLong(math.max(1L, retryAfter.toSeconds))))
            case Right(()) => accept(req, reports)

  private def accept(req: Request[IO], reports: ClientReportStore): IO[Response[IO]] =
    // Streamed with a hard stop one byte past the cap — the declared Content-Length is not trusted, same as the
    // gateway's readBody. No other route needs a cap because every other POST body is tiny by construction; a whole
    // game's turn list is not.
    req.body
      .take(MaxBodyBytes + 1)
      .compile
      .to(Array)
      .flatMap: bytes =>
        if bytes.length > MaxBodyBytes then PayloadTooLarge(s"body exceeds $MaxBodyBytes bytes")
        else
          io.circe.parser.parse(String(bytes, StandardCharsets.UTF_8)) match
            case Left(_)        => BadRequest("invalid JSON body")
            case Right(payload) =>
              validate(payload) match
                case Left(reason) => UnprocessableEntity(reason)
                case Right(id)    =>
                  reports
                    .insertClientReport(id, payload)
                    .flatMap:
                      case true  => Created("report accepted")
                      case false => Ok("report already accepted")

  /** Canonical 8-4-4-4-12 hex form — a syntactic gate, not a parse: stricter than `UUID.fromString` (which accepts
    * non-canonical forms) and exception-free.
    */
  private val UuidPattern = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r

  /** The gateway's structural check (`validate.ts`), ported — deliberately NOT a decode into a case class: the wire
    * contract is owned by analytics and the payload is forwarded verbatim, so decoding here would make this server a
    * second, drifting copy of that schema. One tightening: `id` must be a UUID (the gateway accepted any non-empty
    * string) because it becomes the `client_reports` primary key — and the SPA has always sent a UUIDv5.
    */
  private[server] def validate(payload: Json): Either[String, GameId] =
    for
      obj <- payload.asObject.toRight("body must be a JSON object")
      id  <- obj("id").flatMap(_.asString).toRight("id (string) is required")
      _   <- Either.cond(UuidPattern.matches(id), (), "id must be a UUID")
      _   <- Either.cond(
        obj("source").flatMap(_.asString).contains(ExpectedSource),
        (),
        s"source must be \"$ExpectedSource\""
      )
      _ <- obj("initial_fen")
        .flatMap(_.asString)
        .filter(_.nonEmpty)
        .toRight("initial_fen (string) is required")
      _ <- Either.cond(obj("turns").exists(_.isArray), (), "turns (array) is required")
      _ <- Either.cond(obj("events").forall(_.isArray), (), "events must be an array when present")
    yield GameId(id.toLowerCase)
