package dicechess.play.server

import cats.effect.{IO, Ref}
import dicechess.play.core.GameId
import dicechess.play.store.{ClientReportStore, OutboxRow, OutboxStore}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.{Method, Request, Status, Uri}

import scala.concurrent.duration.*

/** The browser-report intake wire (#212) over a stub queue — the real `client_reports` SQL is covered in
  * `PgGameStoreSuite` and end-to-end delivery in `IngestDelivererSuite`; here the subject is the HTTP contract the
  * SPA's outbox classifies: 201 accepted / 200 duplicate / 400 malformed / 422 rejected / 413 too large / 429 rate
  * limited, plus the gateway-inherited ordering (rate limit before the body is even read) and the structural validation
  * ported from the gateway's `validate.ts`.
  */
class IngestRoutesSuite extends munit.CatsEffectSuite:

  private val reportId = "6ba7b811-9dad-11d1-80b4-00c04fd430c8"

  private def validReport(id: String = reportId): Json =
    Json.obj(
      "id"          -> id.asJson,
      "source"      -> "playsite".asJson,
      "initial_fen" -> "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1".asJson,
      "turns"       -> Json.arr()
    )

  /** A ClientReportStore over a Ref: first write wins, like the ON CONFLICT DO NOTHING it stands in for. */
  private def stubStore(accepted: Ref[IO, Map[String, Json]]): ClientReportStore = new ClientReportStore:
    def insertClientReport(id: GameId, payload: Json): IO[Boolean] =
      accepted.modify(m => if m.contains(id.value) then (m, false) else (m.updated(id.value, payload), true))
    val clientReports: OutboxStore = new OutboxStore:
      def due(limit: Int): IO[List[OutboxRow]]                                                       = IO.pure(Nil)
      def markDelivered(gameId: GameId): IO[Unit]                                                    = IO.unit
      def markRetry(gameId: GameId, attempts: Int, retryIn: FiniteDuration, error: String): IO[Unit] = IO.unit
      def markParked(gameId: GameId, error: String): IO[Unit]                                        = IO.unit

  private def post(body: String): Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString("/ingest/games")).withEntity(body)

  private def run(body: String, limit: Int = 60): IO[(Status, Map[String, Json])] =
    for
      accepted <- Ref.of[IO, Map[String, Json]](Map.empty)
      limiter  <- AnonMintLimiter.create(limit = limit, window = 1.minute)
      response <- IngestRoutes(stubStore(accepted), limiter).orNotFound.run(post(body))
      stored   <- accepted.get
    yield (response.status, stored)

  test("a valid report is accepted with 201 and enqueued verbatim"):
    run(validReport().noSpaces).map: (status, stored) =>
      assertEquals(status, Status.Created)
      assertEquals(stored.get(reportId), Some(validReport()))

  test("a duplicate report answers 200 and does not overwrite the first write"):
    for
      accepted <- Ref.of[IO, Map[String, Json]](Map.empty)
      limiter  <- AnonMintLimiter.create()
      routes = IngestRoutes(stubStore(accepted), limiter).orNotFound
      first  <- routes.run(post(validReport().noSpaces))
      second <- routes.run(post(validReport().deepMerge(Json.obj("initial_fen" -> "forged".asJson)).noSpaces))
      stored <- accepted.get
    yield
      assertEquals(first.status, Status.Created)
      assertEquals(second.status, Status.Ok)
      assertEquals(stored(reportId), validReport(), "the duplicate's payload must not replace the original")

  test("malformed JSON answers 400 and stores nothing"):
    run("{not json").map: (status, stored) =>
      assertEquals(status, Status.BadRequest)
      assert(stored.isEmpty)

  test("a JSON array body is structurally rejected with 422"):
    run(Json.arr(validReport()).noSpaces).map((status, _) => assertEquals(status, Status.UnprocessableEntity))

  test("a missing id is rejected with 422"):
    run(validReport().mapObject(_.remove("id")).noSpaces)
      .map((status, _) => assertEquals(status, Status.UnprocessableEntity))

  test("a non-UUID id is rejected with 422 — it becomes the client_reports primary key"):
    run(validReport(id = "not-a-uuid").noSpaces)
      .map((status, _) => assertEquals(status, Status.UnprocessableEntity))

  test("a source other than playsite is rejected with 422 — the endpoint is not an open relay"):
    run(validReport().deepMerge(Json.obj("source" -> "dicechess.com".asJson)).noSpaces)
      .map((status, _) => assertEquals(status, Status.UnprocessableEntity))

  test("a missing or empty initial_fen is rejected with 422"):
    for
      missing <- run(validReport().mapObject(_.remove("initial_fen")).noSpaces)
      empty   <- run(validReport().deepMerge(Json.obj("initial_fen" -> "".asJson)).noSpaces)
    yield
      assertEquals(missing(0), Status.UnprocessableEntity)
      assertEquals(empty(0), Status.UnprocessableEntity)

  test("missing turns are rejected with 422"):
    run(validReport().mapObject(_.remove("turns")).noSpaces)
      .map((status, _) => assertEquals(status, Status.UnprocessableEntity))

  test("a non-array events field is rejected with 422, while an absent one is fine"):
    run(validReport().deepMerge(Json.obj("events" -> "nope".asJson)).noSpaces)
      .map((status, _) => assertEquals(status, Status.UnprocessableEntity))

  test("a body over the size cap answers 413"):
    val oversized = validReport()
      .deepMerge(Json.obj("padding" -> ("x" * IngestRoutes.MaxBodyBytes.toInt).asJson))
      .noSpaces
    run(oversized).map: (status, stored) =>
      assertEquals(status, Status.PayloadTooLarge)
      assert(stored.isEmpty)

  test("the per-IP rate limit answers 429 with Retry-After once spent"):
    for
      accepted <- Ref.of[IO, Map[String, Json]](Map.empty)
      limiter  <- AnonMintLimiter.create(limit = 1, window = 1.minute)
      routes = IngestRoutes(stubStore(accepted), limiter).orNotFound
      first  <- routes.run(post(validReport().noSpaces))
      second <- routes.run(post(validReport().noSpaces))
    yield
      assertEquals(first.status, Status.Created)
      assertEquals(second.status, Status.TooManyRequests)
      assert(second.headers.get[org.http4s.headers.`Retry-After`].isDefined)

  test("the rate limit gates before the body is read — an over-limit garbage request is 429, not 400"):
    for
      accepted <- Ref.of[IO, Map[String, Json]](Map.empty)
      limiter  <- AnonMintLimiter.create(limit = 1, window = 1.minute)
      routes = IngestRoutes(stubStore(accepted), limiter).orNotFound
      _         <- routes.run(post(validReport().noSpaces))
      overLimit <- routes.run(post("{not json"))
    yield assertEquals(overLimit.status, Status.TooManyRequests)
