package dicechess.play.ingest

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.store.{OutboxRow, OutboxStore}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Method, Request, Uri}

import scala.concurrent.duration.*

/** Delivers queued game payloads to the analytics ingest endpoint — `POST /api/games` with a Bearer token. play-api is
  * the sole trusted writer for playsite games; it drains two queues through separate instances of this class (#212):
  * the transactional outbox (games this server played) and `client_reports` (browser-submitted reports, structurally
  * validated at `POST /ingest/games`). The deliverer itself is provenance-blind — payloads are forwarded verbatim, and
  * the analytics engine-replay gate stays the authoritative validator for both.
  *
  * The endpoint is idempotent on the game UUID (201 created / 200 already-there), so at-least-once delivery is safe.
  * Transient trouble (5xx, timeouts, network) retries with exponential backoff; a 4xx — e.g. the replay gate's 422 —
  * will never succeed and parks the row for manual inspection, loudly.
  */
final class IngestDeliverer(outbox: OutboxStore, client: Client[IO], config: IngestDeliverer.Config):
  import IngestDeliverer.*

  /** Poll → deliver → sleep, forever. Scoped to the server by the caller (`.background`). */
  def loop: IO[Nothing] =
    (deliverDueOnce *> IO.sleep(config.pollEvery)).foreverM

  /** One polling cycle; returns each row's outcome (exposed for tests). */
  def deliverDueOnce: IO[List[Outcome]] =
    outbox.due(BatchSize).flatMap(_.traverse(deliver))

  private def deliver(row: OutboxRow): IO[Outcome] =
    val request = Request[IO](Method.POST, config.endpoint)
      .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, config.token)))
      .withEntity(row.payload)
    client
      .run(request)
      .use(response => response.bodyText.compile.string.map(body => (response.status.code, body)))
      .timeout(RequestTimeout)
      .attempt
      .flatMap {
        case Right((code, _)) if code == 200 || code == 201 =>
          outbox.markDelivered(row.gameId).as(Outcome.Delivered)
        case Right((code, body)) if code >= 400 && code < 500 =>
          // The replay gate (422) or a contract mismatch: retrying cannot help. Park and shout.
          Console[IO].errorln(s"[play][ingest] game ${row.gameId.value} REJECTED ($code): ${body.take(500)}") *>
            outbox.markParked(row.gameId, s"HTTP $code: ${body.take(500)}").as(Outcome.Parked)
        case Right((code, body)) =>
          retry(row, s"HTTP $code: ${body.take(200)}")
        case Left(error) =>
          retry(row, error.toString.take(200))
      }

  private def retry(row: OutboxRow, error: String): IO[Outcome] =
    val attempts = row.attempts + 1
    val backoff  = backoffFor(attempts)
    Console[IO].errorln(s"[play][ingest] game ${row.gameId.value} attempt $attempts failed, retry in $backoff: $error")
      *> outbox.markRetry(row.gameId, attempts, backoff, error).as(Outcome.Retried)

object IngestDeliverer:

  enum Outcome:
    case Delivered, Retried, Parked

  private val BatchSize                      = 10
  private val RequestTimeout: FiniteDuration = 15.seconds
  private val MaxBackoff: FiniteDuration     = 5.minutes

  /** Exponential backoff: 5s, 10s, 20s, ... capped at 5 minutes. */
  private def backoffFor(attempts: Int): FiniteDuration =
    val base = 5.seconds * (1L << math.min(attempts - 1, 6))
    if base > MaxBackoff then MaxBackoff else base

  /** `INGEST_URL` is the FULL endpoint (e.g. `http://192.168.10.3:8020/api/games`); `INGEST_TOKEN` its Bearer. The
    * handoff is opt-in: without both, finished games simply accumulate in the outbox and can be delivered later.
    */
  final case class Config(endpoint: Uri, token: String, pollEvery: FiniteDuration = 5.seconds)

  def configFromEnv: Option[Config] =
    for
      raw      <- sys.env.get("INGEST_URL").filter(_.nonEmpty)
      endpoint <- Uri.fromString(raw).toOption
      token    <- sys.env.get("INGEST_TOKEN").filter(_.nonEmpty)
    yield Config(endpoint, token)
