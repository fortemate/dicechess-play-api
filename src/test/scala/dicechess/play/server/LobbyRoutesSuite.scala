package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{Seek, TimeControl}
import dicechess.play.wire.Codecs.given
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Status}

/** `/lobby/seeks` and `/lobby/seeks/{id}/accept`: the guest-id validation gate added for dicechess-play-api#14 (a
  * malformed or colon-containing creator/accepter id must never reach a game's `Principal.externalId`), plus the
  * time-control default applied when the request omits one. The happy-path lobby flow itself (seek → poll → accept →
  * match) is exercised by LobbySuite (the domain class) and BotRoutesSuite (human vs bot, end to end); this suite is
  * scoped to the route-level validation only.
  */
class LobbyRoutesSuite extends munit.CatsEffectSuite:

  private val ValidCreator  = "55555555-5555-5555-5555-555555555555"
  private val ValidAccepter = "66666666-6666-6666-6666-666666666666"

  private def app: IO[HttpApp[IO]] =
    for
      registry <- GameRegistry.create()
      lobby    <- Lobby.create(registry)
    yield LobbyRoutes(lobby).orNotFound

  test("POST /lobby/seeks rejects a non-UUID creator with 400 (dicechess-play-api#14)"):
    app.flatMap: service =>
      for
        empty <- service
          .run(Request[IO](Method.POST, uri"/lobby/seeks").withEntity(CreateSeek(Some(""))))
          .map(_.status)
        // A value containing `:` would otherwise produce an ambiguous, colon-joined external_id.
        colonJoined <- service
          .run(Request[IO](Method.POST, uri"/lobby/seeks").withEntity(CreateSeek(Some("guest:not-a-uuid"))))
          .map(_.status)
        valid <- service
          .run(Request[IO](Method.POST, uri"/lobby/seeks").withEntity(CreateSeek(Some(ValidCreator))))
          .map(_.status)
      yield
        assertEquals(empty, Status.BadRequest)
        assertEquals(colonJoined, Status.BadRequest)
        assertEquals(valid, Status.Created)

  test("POST /lobby/seeks without a time control gets a clock, not Unlimited (rabestro/dicechess-play#99)"):
    app.flatMap: service =>
      for
        _    <- service.run(Request[IO](Method.POST, uri"/lobby/seeks").withEntity(CreateSeek(Some(ValidCreator))))
        open <- service.run(Request[IO](Method.GET, uri"/lobby/seeks")).flatMap(_.as[List[Seek]])
      yield
        // A clockless seek in the public lobby is the bug this default exists to prevent: nothing ever ends
        // such a game except the anti-abandonment cap.
        assertEquals(open.map(_.timeControl), List(TimeControl.Default))
        assertNotEquals(TimeControl.Default, TimeControl.Unlimited: TimeControl)

  test("POST /lobby/seeks honours an explicitly requested Unlimited"):
    app.flatMap: service =>
      for
        _ <- service.run(
          Request[IO](Method.POST, uri"/lobby/seeks")
            .withEntity(CreateSeek(Some(ValidCreator), Some(TimeControl.Unlimited)))
        )
        open <- service.run(Request[IO](Method.GET, uri"/lobby/seeks")).flatMap(_.as[List[Seek]])
      yield assertEquals(open.map(_.timeControl), List(TimeControl.Unlimited: TimeControl))

  test("POST /lobby/seeks/{id}/accept rejects a non-UUID accepter with 400 (dicechess-play-api#14)"):
    app.flatMap: service =>
      for
        created <- service
          .run(Request[IO](Method.POST, uri"/lobby/seeks").withEntity(CreateSeek(Some(ValidCreator))))
          .flatMap(_.as[CreatedSeek])
        garbage <- service
          .run(
            Request[IO](Method.POST, uri"/lobby/seeks" / created.seekId / "accept")
              .withEntity(AcceptSeek(Some("not-a-uuid")))
          )
          .map(_.status)
        accepted <- service
          .run(
            Request[IO](Method.POST, uri"/lobby/seeks" / created.seekId / "accept")
              .withEntity(AcceptSeek(Some(ValidAccepter)))
          )
          .map(_.status)
      yield
        assertEquals(garbage, Status.BadRequest)
        assertEquals(accepted, Status.Created)
