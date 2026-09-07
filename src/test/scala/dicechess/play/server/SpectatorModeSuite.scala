package dicechess.play.server

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import dicechess.play.core.*
import dicechess.play.store.{GameStore, GuestLink, NicknameUpdate, UserAccount, UserStore}
import dicechess.play.wire.Codecs.given
import io.circe.syntax.*
import org.http4s.{Header, Headers, Uri}
import org.http4s.client.websocket.{WSConnectionHighLevel, WSFrame, WSRequest}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkWSClient
import org.typelevel.ci.*

import java.util.UUID
import scala.concurrent.duration.*

class SpectatorModeSuite extends munit.CatsEffectSuite:

  private val Secret = "spectator-mode-test-secret"

  final private class StubUsers(ref: Ref[IO, Map[String, UserAccount]]) extends UserStore:
    def upsertOnLogin(
        provider: String,
        subject: String,
        email: Option[String],
        freshNickname: IO[String]
    ): IO[UserAccount] =
      (freshNickname, IO.realTimeInstant).flatMapN { (nickname, now) =>
        ref.modify { users =>
          users.get(subject) match
            case Some(existing) => (users, existing)
            case None           =>
              val user = UserAccount(UUID.randomUUID().toString, nickname, now, Some(now), isActive = true)
              (users.updated(subject, user), user)
        }
      }

    def userById(id: String): IO[Option[UserAccount]] = ref.get.map(_.values.find(_.id == id))

    def byNickname(nickname: String): IO[Option[UserAccount]] =
      ref.get.map(_.values.find(_.nickname.equalsIgnoreCase(nickname)))

    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.raiseError(AssertionError("unused"))
    def linkGuest(userId: String, guestId: String): IO[GuestLink]            = IO.raiseError(AssertionError("unused"))
    def guestsOf(userId: String): IO[List[String]]                           = IO.raiseError(AssertionError("unused"))
    def deleteUser(userId: String): IO[Boolean]                              = IO.raiseError(AssertionError("unused"))

  private def server: Resource[IO, (Int, GameRegistry, String, String)] =
    for
      registry <- Resource.make(GameRegistry.create(store = GameStore.noop, disconnectGrace = 500.millis))(
        _.list.flatMap(_.traverse_(_._2.stopForRestart))
      )
      users <- Resource.eval(Ref.of[IO, Map[String, UserAccount]](Map.empty).map(StubUsers(_)))
      session = AuthSession(users, Secret)
      user  <- Resource.eval(users.upsertOnLogin("google", "spectator-user", None, IO.pure("Spectator")))
      token <- Resource.eval(session.sign(user))
      srv   <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withShutdownTimeout(1.second)
        .withHttpWebSocketApp(wsb => PlayRoutes(registry, wsb, Some(session)).orNotFound)
        .build
    yield (srv.address.getPort, registry, token, user.id)

  private def sessionWs(uri: Uri, token: String): WSRequest =
    WSRequest(uri).withHeaders(Headers(Header.Raw(ci"Cookie", s"${AuthSession.SessionCookieName}=$token")))

  private def send(conn: WSConnectionHighLevel[IO], command: GameCommand): IO[Unit] =
    conn.send(WSFrame.Text(command.asJson.noSpaces))

  test("mode=spectator overrides valid and invalid tokens and ignores player commands"):
    (server, JdkWSClient.simple[IO]).tupled.use { case ((port, registry, sessionToken, userId), ws) =>
      val wsBase = Uri.unsafeFromString(s"ws://127.0.0.1:$port")
      for
        // Without mode=spectator, the session cookie would restore White on this user/guest game.
        accountGame <- registry.create(Principal.User(userId), Principal.Guest("33333333-3333-3333-3333-333333333333"))
        (accountId, accountRoom) <- IO.fromEither(accountGame.leftMap(AssertionError(_)))
        accountBefore            <- accountRoom.snapshot
        accountUri = wsBase / "games" / accountId.value / "ws" +? ("mode" -> "spectator")
        _ <- ws.connectHighLevel(sessionWs(accountUri, sessionToken)).use { conn =>
          for
            _              <- IO.sleep(100.millis)
            connectedWhite <- accountRoom.seatConnected(Seat.White)
            connectedBlack <- accountRoom.seatConnected(Seat.Black)
            _              <- send(conn, GameCommand.Resign)
            _              <- IO.sleep(100.millis)
            after          <- accountRoom.snapshot
            seats          <- accountRoom.seating
          yield
            assertEquals(
              seats,
              Map(
                Seat.White -> Principal.User(userId),
                Seat.Black -> Principal.Guest("33333333-3333-3333-3333-333333333333")
              )
            )
            assert(!connectedWhite && !connectedBlack, "spectator mode must not create player presence")
            assertEquals(after.version, accountBefore.version)
            assertEquals(after.status, accountBefore.status)
        }
        // Without mode=spectator, a valid Black token would claim that seat for the signed-in account.
        guestGame <- registry.create(
          Principal.Guest("44444444-4444-4444-4444-444444444444"),
          Principal.Guest("55555555-5555-5555-5555-555555555555")
        )
        (guestId, guestRoom) <- IO.fromEither(guestGame.leftMap(AssertionError(_)))
        validToken = guestRoom.joinTokens(Seat.Black)
        beforeGuest <- guestRoom.snapshot
        validUri   = wsBase / "games" / guestId.value / "ws" +? ("token" -> validToken) +? ("mode" -> "spectator")
        invalidUri = wsBase / "games" / guestId.value / "ws" +? ("token" -> "invalid") +? ("mode"  -> "spectator")
        _ <- ws.connectHighLevel(sessionWs(validUri, sessionToken)).use { conn =>
          for
            _              <- send(conn, GameCommand.Resign)
            _              <- IO.sleep(100.millis)
            seats          <- guestRoom.seating
            connectedBlack <- guestRoom.seatConnected(Seat.Black)
            after          <- guestRoom.snapshot
          yield
            assertEquals(
              seats.values.toSet,
              Set(
                Principal.Guest("44444444-4444-4444-4444-444444444444"),
                Principal.Guest("55555555-5555-5555-5555-555555555555")
              )
            )
            assert(!connectedBlack, "spectator mode must not create token-seat presence")
            assertEquals(after.version, beforeGuest.version)
        }
        _ <- ws.connectHighLevel(sessionWs(invalidUri, sessionToken)).use { conn =>
          send(conn, GameCommand.Resign) *> IO.sleep(100.millis)
        }
        finalState <- guestRoom.snapshot
      yield assertEquals(finalState.version, beforeGuest.version)
    }
