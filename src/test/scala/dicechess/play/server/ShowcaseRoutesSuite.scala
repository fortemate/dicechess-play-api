package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.server.ShowcaseHarness.*
import dicechess.play.store.{GuestLink, NicknameUpdate, UserAccount, UserStore}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.headers.{`Cache-Control`, `Retry-After`, ETag}
import org.http4s.EntityTag
import org.http4s.implicits.*
import org.http4s.{CacheDirective, Header, HttpApp, MediaType, Method, Request, RequestCookie, Response, Status}
import org.typelevel.ci.CIStringSyntax

import java.util.UUID
import scala.concurrent.duration.*

/** The HTTP contract of `GET /showcase` and `POST /showcase/claim` (ADR-005 §10, #46): response shapes pinned as JSON,
  * the cache and problem headers, the identity and CSRF rules, both rate limits, and — above all — that a credential
  * appears in exactly one place: the winner's claim response.
  */
class ShowcaseRoutesSuite extends munit.CatsEffectSuite:

  private val Secret = "test-session-secret"
  private val Guest1 = "11111111-1111-1111-1111-111111111111"
  private val Guest2 = "22222222-2222-2222-2222-222222222222"

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
    def userById(id: String): IO[Option[UserAccount]]         = ref.get.map(_.values.find(_.id == id))
    def byNickname(nickname: String): IO[Option[UserAccount]] =
      ref.get.map(_.values.find(_.nickname.equalsIgnoreCase(nickname)))
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.raiseError(AssertionError("unused"))
    def linkGuest(userId: String, guestId: String): IO[GuestLink]            = IO.raiseError(AssertionError("unused"))
    def guestsOf(userId: String): IO[List[String]]                           = IO.raiseError(AssertionError("unused"))
    def deleteUser(userId: String): IO[Boolean]                              = IO.raiseError(AssertionError("unused"))

  private def app(
      table: ShowcaseTable,
      session: Option[AuthSession] = None,
      origins: Cors.AllowedOrigins = Cors.allowedOrigins(""),
      ipLimit: Int = ShowcaseRoutes.ClaimsPerIpPerMinute,
      actorLimit: Int = ShowcaseRoutes.ClaimsPerActorPerMinute
  ): IO[HttpApp[IO]] =
    (
      AnonMintLimiter.create(limit = ipLimit, window = 1.minute),
      AnonMintLimiter.create(limit = actorLimit, window = 1.minute)
    ).mapN((ip, actor) => ShowcaseRoutes(table, session, origins, ip, actor).orNotFound)

  private def get(app: HttpApp[IO], headers: Header.ToRaw*): IO[Response[IO]] =
    app.run(Request[IO](Method.GET, uri"/showcase").putHeaders(headers*))

  private def claim(
      app: HttpApp[IO],
      key: Option[String],
      body: Option[Json],
      headers: Header.ToRaw*
  ): IO[Response[IO]] =
    val base    = Request[IO](Method.POST, uri"/showcase/claim").putHeaders(headers*)
    val withKey = key.fold(base)(k => base.putHeaders(Header.Raw(ci"Idempotency-Key", k)))
    app.run(body.fold(withKey)(json => withKey.withEntity(json)))

  private def guestBody(guestId: String): Json = Json.obj("guestId" -> guestId.asJson)

  private def cacheControl(resp: Response[IO]): List[CacheDirective] =
    resp.headers.get[`Cache-Control`].map(_.values.toList).getOrElse(Nil)

  private def isProblem(resp: Response[IO]): Boolean =
    resp.contentType.exists(_.mediaType == MediaType.unsafeParse("application/problem+json"))

  private def code(resp: Response[IO]): IO[String] = resp.as[Json].map(_.hcursor.get[String]("code").toOption.get)

  private def openTable(t: ShowcaseTable): IO[Unit] =
    t.reconcile.map(phase => assertEquals(phase, ShowcaseTable.Phase.Open(Side.White)))

  test("GET /showcase when open: the pinned shape, uncacheable, with a weak ETag that answers 304"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _    <- openTable(t)
          a    <- app(t)
          resp <- get(a)
          json <- resp.as[Json]
          tag = resp.headers.get[ETag].getOrElse(fail("no ETag")).tag
          again <- get(a, Header.Raw(ci"If-None-Match", tag.toString))
        yield
          assertEquals(resp.status, Status.Ok)
          assertEquals(
            json,
            Json.obj(
              "status"      -> "open".asJson,
              "featuredBot" -> Json.obj(
                "team"        -> "rpi3".asJson,
                "name"        -> "hunter-book".asJson,
                "displayName" -> "rpi3 hunter-book".asJson
              ),
              "timeControl" -> Json.obj(
                "initialSeconds"   -> 300.asJson,
                "incrementSeconds" -> 3.asJson,
                "display"          -> "5+3".asJson
              ),
              "nextHumanColor" -> "White".asJson,
              "currentGame"    -> Json.Null,
              "spectator"      -> Json.Null,
              "reason"         -> Json.Null
            )
          )
          assert(cacheControl(resp).contains(CacheDirective.`no-store`), cacheControl(resp).toString)
          assert(cacheControl(resp).contains(CacheDirective.`must-revalidate`))
          assertEquals(tag.weakness, EntityTag.Weak)
          assertEquals(again.status, Status.NotModified)
      }
    }

  test("GET /showcase when unavailable names only a coarse public reason"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _    <- f.ready.set(false)
          _    <- t.reconcile
          a    <- app(t)
          resp <- get(a)
          json <- resp.as[Json]
        yield
          assertEquals(json.hcursor.get[String]("status").toOption, Some("unavailable"))
          assertEquals(json.hcursor.get[String]("reason").toOption, Some("bot_unavailable"))
          assertEquals(json.hcursor.downField("currentGame").focus, Some(Json.Null))
      }
    }

  test("POST /showcase/claim requires a UUID Idempotency-Key and answers RFC 7807 problems"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _           <- openTable(t)
          a           <- app(t)
          missing     <- claim(a, None, Some(guestBody(Guest1)))
          invalid     <- claim(a, Some("not-a-uuid"), Some(guestBody(Guest1)))
          missingCode <- code(missing)
          invalidCode <- code(invalid)
          phase       <- t.currentPhase
        yield
          assertEquals(missing.status, Status.BadRequest)
          assert(isProblem(missing))
          assertEquals(missingCode, "missing_idempotency_key")
          assertEquals(invalid.status, Status.BadRequest)
          assertEquals(invalidCode, "invalid_idempotency_key")
          assert(cacheControl(missing).contains(CacheDirective.`no-store`))
          assertEquals(phase, ShowcaseTable.Phase.Open(Side.White), "a refused request never touches the table")
      }
    }

  test("POST /showcase/claim without a session needs a valid guestId, and a non-JSON body is refused"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _ <- openTable(t)
          a <- app(t)
          k = UUID.randomUUID().toString
          noGuest  <- claim(a, Some(k), Some(Json.obj()))
          badGuest <- claim(a, Some(k), Some(guestBody("nope")))
          notJson  <- a.run(
            Request[IO](Method.POST, uri"/showcase/claim")
              .putHeaders(Header.Raw(ci"Idempotency-Key", k))
              // The plain-text encoder explicitly: with the circe codecs in scope, a bare `withEntity(String)` would
              // send a JSON string under application/json, which is a different (400) failure.
              .withEntity("guestId=x")(using org.http4s.EntityEncoder.stringEncoder[IO])
          )
          noGuestCode  <- code(noGuest)
          badGuestCode <- code(badGuest)
          notJsonCode  <- code(notJson)
        yield
          assertEquals(noGuest.status, Status.BadRequest)
          assertEquals(noGuestCode, "guest_required")
          assertEquals(badGuest.status, Status.BadRequest)
          assertEquals(badGuestCode, "invalid_guest_id")
          assertEquals(notJson.status, Status.UnsupportedMediaType)
          assertEquals(notJsonCode, "malformed_request")
      }
    }

  test("the winner gets the credential once, no-store and private; the read and every loser never carry it"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _ <- openTable(t)
          a <- app(t)
          k1 = UUID.randomUUID().toString
          k2 = UUID.randomUUID().toString
          won  <- claim(a, Some(k1), Some(guestBody(Guest1)))
          body <- won.as[Json]
          gameId = body.hcursor.get[String]("gameId").toOption.get
          token  = body.hcursor.get[String]("seatToken").toOption.get
          lost     <- claim(a, Some(k2), Some(guestBody(Guest2)))
          lostBody <- lost.as[Json]
          read     <- get(a)
          readBody <- read.as[Json]
          room     <- f.registry.get(GameId(gameId)).map(_.get)
        yield
          assertEquals(won.status, Status.Ok)
          assertEquals(body.hcursor.keys.map(_.toSet), Some(Set("outcome", "gameId", "seat", "seatToken", "wsUrl")))
          assertEquals(body.hcursor.get[String]("outcome").toOption, Some("claimed"))
          assertEquals(body.hcursor.get[String]("seat").toOption, Some("White"))
          assertEquals(body.hcursor.get[String]("wsUrl").toOption, Some(s"/games/$gameId/ws?token=$token"))
          assertEquals(room.seatFor(token), Some(Seat.White))
          assert(cacheControl(won).contains(CacheDirective.`no-store`), cacheControl(won).toString)
          assert(cacheControl(won).contains(CacheDirective.`private`()), cacheControl(won).toString)

          assertEquals(lost.status, Status.Ok)
          assertEquals(
            lostBody,
            Json.obj(
              "outcome"        -> "spectating".asJson,
              "reason"         -> "already_claimed".asJson,
              "gameId"         -> gameId.asJson,
              "spectatorWsUrl" -> s"/games/$gameId/ws".asJson
            )
          )
          assert(!lostBody.noSpaces.contains(token))

          assertEquals(readBody.hcursor.get[String]("status").toOption, Some("live"))
          assertEquals(readBody.hcursor.downField("currentGame").get[String]("gameId").toOption, Some(gameId))
          assertEquals(readBody.hcursor.downField("currentGame").get[String]("humanSeat").toOption, Some("White"))
          assertEquals(
            readBody.hcursor.downField("spectator").get[String]("wsUrl").toOption,
            Some(s"/games/$gameId/ws")
          )
          assertEquals(readBody.hcursor.get[String]("nextHumanColor").toOption, Some("Black"))
          assert(!readBody.noSpaces.contains(token), "the public read never carries a seat token")
          assert(!readBody.noSpaces.contains("seatToken"))
          assert(!readBody.noSpaces.contains(Guest1), "the public read never carries a private identity")
      }
    }

  test("a same-key retry replays the same credential; the same key with another body is 409 idempotency_conflict"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _ <- openTable(t)
          a <- app(t)
          k = UUID.randomUUID().toString
          first    <- claim(a, Some(k), Some(guestBody(Guest1))).flatMap(_.as[Json])
          replayed <- claim(a, Some(k), Some(guestBody(Guest1))).flatMap(_.as[Json])
          conflict <- claim(
            a,
            Some(k),
            Some(Json.obj("guestId" -> Guest1.asJson, "clientEntropy" -> "x".repeat(16).asJson))
          )
          conflictCode <- code(conflict)
        yield
          assertEquals(replayed, first)
          assertEquals(conflict.status, Status.Conflict)
          assertEquals(conflictCode, "idempotency_conflict")
      }
    }

  test("the per-IP and per-actor budgets answer 429 with Retry-After"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _          <- openTable(t)
          byIp       <- app(t, ipLimit = 1)
          first      <- claim(byIp, Some(UUID.randomUUID().toString), Some(guestBody(Guest1)))
          second     <- claim(byIp, Some(UUID.randomUUID().toString), Some(guestBody(Guest2)))
          secondCode <- code(second)
          byActor    <- app(t, actorLimit = 1)
          third      <- claim(byActor, Some(UUID.randomUUID().toString), Some(guestBody(Guest2)))
          fourth     <- claim(byActor, Some(UUID.randomUUID().toString), Some(guestBody(Guest2)))
        yield
          assertEquals(first.status, Status.Ok)
          assertEquals(second.status, Status.TooManyRequests)
          assertEquals(secondCode, "rate_limited")
          assert(second.headers.get[`Retry-After`].isDefined)
          assertEquals(third.status, Status.Ok)
          assertEquals(fourth.status, Status.TooManyRequests)
      }
    }

  test("an unavailable table answers 503 showcase_unavailable with Retry-After and no credential"):
    fixture.flatMap { f =>
      f.table(withStore = false).use { t =>
        for
          _    <- t.reconcile
          a    <- app(t)
          resp <- claim(a, Some(UUID.randomUUID().toString), Some(guestBody(Guest1)))
          body <- resp.as[Json]
        yield
          assertEquals(resp.status, Status.ServiceUnavailable)
          assert(isProblem(resp))
          assertEquals(body.hcursor.get[String]("code").toOption, Some("showcase_unavailable"))
          assert(resp.headers.get[`Retry-After`].isDefined)
          assert(!body.noSpaces.contains("seatToken"))
      }
    }

  test("a session-authenticated claim needs the CSRF header (and an allowed Origin when one is configured)"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _     <- openTable(t)
          users <- Ref.of[IO, Map[String, UserAccount]](Map.empty).map(StubUsers(_))
          session = AuthSession(users, Secret)
          user  <- users.upsertOnLogin("google", "sub-showcase", None, IO.pure("ShowNick"))
          token <- session.sign(user)
          cookie = RequestCookie(AuthSession.SessionCookieName, token)
          open   <- app(t, Some(session))
          pinned <- app(t, Some(session), Cors.allowedOrigins("https://fortemate.com"))
          noCsrf <- open.run(
            Request[IO](Method.POST, uri"/showcase/claim")
              .addCookie(cookie)
              .putHeaders(Header.Raw(ci"Idempotency-Key", UUID.randomUUID().toString))
          )
          noCsrfCode  <- code(noCsrf)
          wrongOrigin <- pinned.run(
            Request[IO](Method.POST, uri"/showcase/claim")
              .addCookie(cookie)
              .putHeaders(
                Header.Raw(ci"Idempotency-Key", UUID.randomUUID().toString),
                Header.Raw(ci"X-DiceChess-CSRF", "1"),
                Header.Raw(ci"Origin", "https://evil.example")
              )
          )
          won <- pinned.run(
            Request[IO](Method.POST, uri"/showcase/claim")
              .addCookie(cookie)
              .putHeaders(
                Header.Raw(ci"Idempotency-Key", UUID.randomUUID().toString),
                Header.Raw(ci"X-DiceChess-CSRF", "1"),
                Header.Raw(ci"Origin", "https://fortemate.com")
              )
          )
          wonBody <- won.as[Json]
          seating <- f.registry.get(GameId(wonBody.hcursor.get[String]("gameId").toOption.get)).flatMap(_.get.seating)
        yield
          assertEquals(noCsrf.status, Status.Forbidden)
          assertEquals(noCsrfCode, "csrf_origin_rejected")
          assertEquals(wrongOrigin.status, Status.Forbidden)
          assertEquals(won.status, Status.Ok)
          assertEquals(seating(Seat.White), Principal.User(user.id), "the session wins; no body was even needed")
      }
    }

  test("a body-supplied guestId is ignored for a signed-in caller (no way to claim as someone else)"):
    fixture.flatMap { f =>
      f.table().use { t =>
        for
          _     <- openTable(t)
          users <- Ref.of[IO, Map[String, UserAccount]](Map.empty).map(StubUsers(_))
          session = AuthSession(users, Secret)
          user  <- users.upsertOnLogin("google", "sub-showcase-2", None, IO.pure("OtherNick"))
          token <- session.sign(user)
          a     <- app(t, Some(session))
          won   <- a.run(
            Request[IO](Method.POST, uri"/showcase/claim")
              .addCookie(RequestCookie(AuthSession.SessionCookieName, token))
              .putHeaders(
                Header.Raw(ci"Idempotency-Key", UUID.randomUUID().toString),
                Header.Raw(ci"X-DiceChess-CSRF", "1")
              )
              .withEntity(guestBody(Guest1))
          )
          body    <- won.as[Json]
          seating <- f.registry.get(GameId(body.hcursor.get[String]("gameId").toOption.get)).flatMap(_.get.seating)
        yield
          assertEquals(won.status, Status.Ok)
          assertEquals(seating(Seat.White), Principal.User(user.id))
      }
    }
