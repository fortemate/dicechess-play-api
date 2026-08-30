package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{Principal, RatingCategory}
import dicechess.play.rating.{BradleyTerry, Sprt, StrengthCache, StrengthReport}
import dicechess.play.store.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Status}

/** The public strength-report wire (#181) over a stub `BotStore` and `StrengthCache` — the SPRT/Bradley-Terry math
  * itself is covered dice-free in `StrengthReportSuite`; here the subject is the HTTP layer: the cold-cache `503`, the
  * wire shape once warm, and the per-bot filter on the profile sub-route.
  */
class StrengthRoutesSuite extends munit.CatsEffectSuite:

  private def stubBots(known: Map[(String, String), BotRating]): BotStore = new UnownedBotStore:
    def authenticate(tokenHash: String): IO[Option[Principal.Bot]]                  = IO.pure(None)
    def rotate(team: String, name: String, newTokenHash: String): IO[Boolean]       = IO.pure(false)
    def ratingOf(team: String, name: String): IO[Option[BotRating]]                 = IO.pure(known.get((team, name)))
    def setOnLadder(team: String, name: String, on: Boolean): IO[Option[BotRating]] = IO.pure(None)
    def onLadderCandidates: IO[List[BotSeatPolicy]]                                 = IO.pure(Nil)
    def seatPolicyOf(team: String, name: String): IO[Option[BotSeatPolicy]]         = IO.pure(None)
    def setMaxConcurrentGames(team: String, name: String, n: Int): IO[Option[BotSeatPolicy]] = IO.pure(None)
    def openToHumans(team: String, name: String, description: Option[String]): IO[Option[BotCatalogState]] =
      IO.pure(None)
    def closeToHumans(team: String, name: String): IO[Option[BotCatalogState]] = IO.pure(None)
    def openToHumansBots: IO[List[Principal.Bot]]                              = IO.pure(Nil)

  private def stubCache(report: Option[StrengthReport]): StrengthCache = new StrengthCache:
    def get: IO[Option[StrengthReport]]       = IO.pure(report)
    def set(report: StrengthReport): IO[Unit] = IO.unit

  private def app(
      bots: Map[(String, String), BotRating] = Map.empty,
      report: Option[StrengthReport] = None
  ): HttpApp[IO] =
    StrengthRoutes(stubBots(bots), stubCache(report)).orNotFound

  private val aliceVsBob = StrengthReport.Pairwise(
    perspective = "acme/alice",
    opponent = "acme/bob",
    pairs = Sprt.Pentanomial(1, 0, 0, 0, 3),
    singles = Sprt.Trinomial(losses = 0, draws = 1, wins = 2),
    result = Sprt.Result(llr = 1.5, lower = -2.89, upper = 2.89, verdict = Sprt.Verdict.Continue, observations = 7)
  )

  private val sampleReport = StrengthReport(
    category = RatingCategory.Default,
    pairwise = List(aliceVsBob),
    ranking = List(BradleyTerry.Ranked("acme/alice", elo = 42.0, ciLow = 10.0, ciHigh = 74.0, losVsNext = Some(0.9))),
    completePairs = 1,
    singles = 1,
    excludedRows = 2
  )

  test("GET /strength is 503 before the cache has ever been warmed"):
    app().run(Request[IO](Method.GET, uri"/strength")).map(resp => assertEquals(resp.status, Status.ServiceUnavailable))

  test("GET /strength returns the whole cached report and pins the wire shape"):
    val expected = parse(
      """{"category":"blitz",
         "pairwise":[
           {"perspective":"acme/alice","opponent":"acme/bob",
            "pairs":{"n0":1,"n1":0,"n2":0,"n3":0,"n4":3},
            "singles":{"losses":0,"draws":1,"wins":2},
            "result":{"llr":1.5,"lower":-2.89,"upper":2.89,"verdict":"Continue","observations":7}}
         ],
         "ranking":[
           {"player":"acme/alice","elo":42.0,"ciLow":10.0,"ciHigh":74.0,"losVsNext":0.9}
         ],
         "completePairs":1,"singles":1,"excludedRows":2}"""
    ).toOption.get
    app(report = Some(sampleReport)).run(Request[IO](Method.GET, uri"/strength")).flatMap { resp =>
      assertEquals(resp.status, Status.Ok)
      resp.as[Json].map(assertEquals(_, expected, "the public strength wire shape is a contract — pin it"))
    }

  test("GET /bots/{team}/{name}/strength is 404 for an unregistered identity, regardless of cache state"):
    app(report = Some(sampleReport))
      .run(Request[IO](Method.GET, uri"/bots/ghost/nobody/strength"))
      .map(resp => assertEquals(resp.status, Status.NotFound))

  test("GET /bots/{team}/{name}/strength is 503 for a registered bot before the cache has ever been warmed"):
    app(bots = Map(("acme", "alice") -> BotRating(onLadder = true, None)))
      .run(Request[IO](Method.GET, uri"/bots/acme/alice/strength"))
      .map(resp => assertEquals(resp.status, Status.ServiceUnavailable))

  test("GET /bots/{team}/{name}/strength narrows the report to only that bot's matchups"):
    val carolVsDave = StrengthReport.Pairwise(
      perspective = "acme/carol",
      opponent = "acme/dave",
      pairs = Sprt.Pentanomial.Empty,
      singles = Sprt.Trinomial(losses = 1, draws = 0, wins = 0),
      result = Sprt.Result(llr = 0.0, lower = -2.89, upper = 2.89, verdict = Sprt.Verdict.Continue, observations = 1)
    )
    val report  = sampleReport.copy(pairwise = List(aliceVsBob, carolVsDave))
    val service = app(
      bots = Map(("acme", "alice") -> BotRating(onLadder = true, None)),
      report = Some(report)
    )
    service.run(Request[IO](Method.GET, uri"/bots/acme/alice/strength")).flatMap { resp =>
      assertEquals(resp.status, Status.Ok)
      resp.as[Json].map { body =>
        val matchups = body.hcursor.downField("pairwise").values.map(_.size)
        assertEquals(matchups, Some(1), "alice's profile must not leak the unrelated carol/dave matchup")
        assertEquals(body.hcursor.get[String]("team").toOption, Some("acme"))
        assertEquals(body.hcursor.get[String]("name").toOption, Some("alice"))
        // The slice says which scale it is a slice OF (#280) — `excludedRows` upstream now counts other categories,
        // so a reader with no category on the wire cannot tell a narrowed report from a shrinking one.
        assertEquals(body.hcursor.get[String]("category").toOption, Some("blitz"))
      }
    }
