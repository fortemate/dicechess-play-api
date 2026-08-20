package dicechess.play.server

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import cats.effect.IO
import dicechess.play.core.Principal
import dicechess.play.store.{GameResultsStore, GuestLink, UserAccount, UserStore}
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.{HttpRoutes, ParseFailure, QueryParamDecoder, Request, Response, Status}

import java.time.Instant

/** The body of a guest claim (#236): the bare uuid the SPA keeps in `localStorage`. */
final case class ClaimGuest(guestId: String) derives Codec.AsObject

/** The guest identities this account has claimed, oldest first. */
final case class ClaimedGuests(guests: List[String]) derives Codec.AsObject

/** The signed-in player's own history (#236, ADR-0017).
  *
  * Claiming is how anonymous play survives signing up: `game_results` and `game_archive` keep the `guest:` external ids
  * they were written with — nothing is ever rewritten — and these reads simply union over the account plus every guest
  * id it has claimed. That keeps immutable records immutable and the already-delivered analytics rows untouched, and it
  * is reversible in the only direction that matters (a link is a row).
  *
  * Proof of ownership is possession of the id, the same trust model the SPA's restore code already relies on: a guest
  * uuid is unguessable and is shown only to its own browser. The claim is first-writer-wins and terminal, because
  * `user_guest_links.guest_id` is a primary key — one guest identity belongs to at most one account, ever.
  *
  * Privacy: the claim set is visible only to its owner. The public `GET /players/{guestId}/…` reads deliberately do NOT
  * learn about linkage — a guest id must never resolve to a nickname there, or signing up would retroactively
  * deanonymise every anonymous game that id ever played.
  */
object MeRoutes:

  private val DefaultLimit = GameResultsStore.DefaultRecentLimit
  private val MaxLimit     = 200

  // Validating (not plain) for the same reason PlayerRoutes documents: a plain matcher's decode failure fails the whole
  // route match and 404s instead of reporting the bad request.
  private object LimitParam extends OptionalValidatingQueryParamDecoderMatcher[Int]("limit")

  private given QueryParamDecoder[Instant] = QueryParamDecoder[String].emap: s =>
    try Right(Instant.parse(s))
    catch case e: java.time.format.DateTimeParseException => Left(ParseFailure(s"invalid instant '$s'", e.getMessage))
  private object BeforeParam extends OptionalValidatingQueryParamDecoderMatcher[Instant]("before")

  private object VsParam     extends OptionalQueryParamDecoderMatcher[String]("vs")
  private object ResultParam extends OptionalQueryParamDecoderMatcher[String]("result")

  def apply(
      session: AuthSession,
      users: UserStore,
      results: GameResultsStore
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case req @ POST -> Root / "auth" / "me" / "guests" =>
        session.withUser(req): user =>
          req
            .attemptAs[ClaimGuest]
            .value
            .flatMap:
              case Left(failure) => BadRequest(failure.message)
              case Right(body)   =>
                // The same validation gate every guest id passes at the identity-issuance boundary — the store casts
                // to uuid without re-validating (see UserStore's id contract).
                Principal.guest(body.guestId) match
                  case Left(error)  => BadRequest(s"guestId: $error")
                  case Right(guest) =>
                    users.linkGuest(user.id, guest.id).flatMap {
                      case GuestLink.Linked           => guestsOf(users, user)
                      case GuestLink.ClaimedByAnother =>
                        IO.pure(Response[IO](Status.Conflict).withEntity("that guest id belongs to another account"))
                      // The account vanished between the session check and the write — "no longer signed in", as
                      // everywhere else in this surface.
                      case GuestLink.UserNotFound => IO.pure(AuthSession.notSignedIn)
                    }

      case req @ GET -> Root / "auth" / "me" / "guests" =>
        session.withUser(req)(guestsOf(users, _))

      case req @ GET -> Root / "me" / "games" :? LimitParam(limit) +& BeforeParam(before) +& VsParam(vs) +&
          ResultParam(result) =>
        session.withUser(req): user =>
          val validated = for
            parsed    <- parseValidated(limit, "limit")
            beforeAt  <- parseValidated(before, "before")
            vsFilter  <- PlayerRoutes.parseVs(vs)
            povResult <- PlayerRoutes.parseResult(result)
          yield (parsed, beforeAt, vsFilter, povResult)
          validated match
            case Left(error)                                    => BadRequest(error)
            case Right((parsed, beforeAt, vsFilter, povResult)) =>
              val bounded = parsed.fold(DefaultLimit)(requested => math.max(1, math.min(requested, MaxLimit)))
              identitiesOf(users, user).flatMap { ids =>
                results
                  .playerGamesPage(ids.toList, beforeAt, vsFilter, povResult, bounded)
                  .flatMap { page =>
                    // One lookup for the page. The requester's own seats are in `ids` and never rendered as the
                    // opponent, so this only ever names the OTHER side — and only when it is an account.
                    val seen = page.games.flatMap(row => List(row.whiteExternalId, row.blackExternalId))
                    users
                      .nicknamesByExternalId(seen)
                      .flatMap: nicknames =>
                        Ok(PlayerGames(page.games.map(PlayerRoutes.playerGame(ids, _, nicknames)), page.hasMore))
                  }
              }

      case req @ GET -> Root / "me" / "opponents" =>
        session.withUser(req): user =>
          identitiesOf(users, user).flatMap { ids =>
            results.opponentsFor(ids.toList).flatMap(rows => Ok(PlayerOpponents(rows.map(playerOpponent))))
          }

  /** Every external id that IS this account: its own, plus each claimed guest id (#236). */
  private def identitiesOf(users: UserStore, user: UserAccount): IO[Set[String]] =
    users.guestsOf(user.id).map { guests =>
      guests.map(id => Principal.Guest(id).externalId).toSet + Principal.User(user.id).externalId
    }

  private def guestsOf(users: UserStore, user: UserAccount): IO[Response[IO]] =
    users.guestsOf(user.id).flatMap(guests => Ok(ClaimedGuests(guests)))

  private def parseValidated[A](param: Option[ValidatedNel[ParseFailure, A]], name: String): Either[String, Option[A]] =
    param match
      case None             => Right(None)
      case Some(Valid(v))   => Right(Some(v))
      case Some(Invalid(e)) => Left(s"$name: ${e.head.sanitized}")
