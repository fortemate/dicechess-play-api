package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.rating.Glicko2
import dicechess.play.store.{BotCatalogListing, BotCatalogStore, BotStore}
import dicechess.play.wire.Codecs.given
import io.circe.Codec
import io.circe.derivation.ConfiguredCodec
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.`Retry-After`
import org.http4s.{Request, Response}

import java.security.SecureRandom

/** One catalog card: a bot a visitor can start a game against, plus the rating summary to show. `provisional` flags a
  * bot whose rating has not converged (RD above the threshold) — shown, not hidden, so a freshly opened bot still
  * appears (the opposite of the leaderboard's hide-until-converged policy).
  *
  * `available` (#224) is advisory, not authoritative: it is read once per catalog fetch (the SPA does not poll), so a
  * bot's actual seating state can have moved by the time a visitor clicks — `wake`'s own capacity check, and ultimately
  * `play-bot`'s 409, remain the real gate. It exists so a card can show "playing now" instead of inviting a click that
  * is refused.
  */
final case class CatalogBot(
    team: String,
    name: String,
    rating: Double,
    rd: Double,
    provisional: Boolean,
    description: Option[String],
    available: Boolean
) derives Codec.AsObject

/** The human-facing bot catalog. */
final case class BotCatalog(bots: List[CatalogBot]) derives Codec.AsObject

/** `POST /lobby/bots/{team}/{name}/wake` response. `alive` is whether the endpoint answered the liveness probe; `busy`
  * (#224) is true when the bot is at its declared concurrent-game capacity (#189) — a state the route detects WITHOUT
  * running the probe (a busy bot is never woken), so `alive` is always `false` alongside `busy: true`: there is nothing
  * for a busy bot to have answered.
  */
/** `ConfiguredCodec` for the same reason the request bodies below use it: `busy`'s default must survive a body that
  * omits it. This is a RESPONSE, so nothing server-side decodes it and the bug never bit here — it is converted anyway
  * so the rule is "any wire type with a defaulted non-Option field", with no per-type judgement about which direction
  * of the wire it travels.
  */
final case class Wake(alive: Boolean, busy: Boolean = false) derives ConfiguredCodec

/** `POST /lobby/play-bot` body (ADR-0014, E4): start a human-vs-bot game from the catalog. `guestId` is the anonymous
  * fallback (#235): a signed-in caller is seated from the session and the field is ignored; without a session it is
  * required (the SPA's stable per-browser UUID — same convention `POST /lobby/seeks` uses for `creator`). `timeControl`
  * is mandatory: a catalog game is never unlimited. `preferredColor` absent means a random seat (the default);
  * `Some(side)` seats the human there and the bot on the other side.
  */
/** `rated` (#279, ADR-0017) is the human's own choice, made when starting the game — replacing the operator-curated
  * `rated_for_humans` roster this used to gate on, which never worked (the roster fed a batch that never saw a
  * human-vs-bot game marked rated at all). `GameRegistry.isRated` degrades a guest's request to casual regardless of
  * what is sent here, so there is no need to check sign-in status before setting this field.
  *
  * `ConfiguredCodec`, not `Codec.AsObject`: without it `rated`'s default does not apply and an SPA bundle predating
  * #279 — one still live in a visitor's service-worker cache — gets a 400 instead of a casual game.
  */
final case class PlayBot(
    guestId: Option[String] = None,
    team: String,
    name: String,
    timeControl: TimeControl,
    preferredColor: Option[Side] = None,
    rated: Boolean = false
) derives ConfiguredCodec

/** Public, unauthenticated read of the human-facing bot catalog (ADR-0014, E2), the wake probe that precedes starting a
  * game against one (E3), and the start itself (E4): the bots that opened themselves to human games via
  * `POST /bot/open-to-humans`. Pure reads plus one write; mounted only when persistence is configured — without the
  * database there is no `bots` table to list, same spirit as [[LeaderboardRoutes]]. Wake and play-bot are bundled under
  * the same gate for one feature flag, even though neither strictly needs Postgres itself ([[BotStore]], [[Webhooks]],
  * and [[GameRegistry]] all have in-memory fallbacks) — without a listing to click from, neither has anything to be
  * called for.
  */
object CatalogRoutes:

  final case class CatalogLimiters(wake: AnonMintLimiter, playBot: AnonMintLimiter)

  def apply(
      catalog: BotCatalogStore,
      bots: BotStore,
      webhooks: Option[Webhooks],
      registry: GameRegistry,
      limiters: CatalogLimiters,
      session: Option[AuthSession] = None,
      guard: Option[SeatGuard] = None
  ): HttpRoutes[IO] =
    // Built once, not per request: it is a pure view over the collaborators already threaded here.
    val effectiveGuard = guard.getOrElse(SeatGuard(bots, registry))
    HttpRoutes.of[IO]:
      case GET -> Root / "lobby" / "bots" =>
        catalog.catalogBots.flatMap(
          _.traverse(card(_, registry, effectiveGuard)).flatMap(cards => Ok(BotCatalog(cards)))
        )

      // A visitor clicks a catalog card to start a game (ADR-0014): this wakes a scale-to-zero endpoint and reports
      // whether it answered, so the SPA knows whether to offer the game-config panel. 404 for a name outside the
      // catalog (not eligible to be woken here, whatever its webhook state); 200 alive:false covers "no webhook" and
      // "webhook didn't answer" alike — the caller only needs yes/no. The rate limit (an in-memory check) gates
      // BEFORE the catalog-membership read (a database query, in Pg mode) — this endpoint is fully unauthenticated,
      // so the cheapest defense must run first, rather than letting anonymous spam reach the database at all.
      //
      // Declared capacity (#189) is checked BEFORE the probe (#224): a bot at its limit is never woken at all — an
      // outbound POST held up to the full per-turn window would cold-start a scale-to-zero endpoint for nothing, when
      // the in-memory game count plus one indexed read already answers the only question that matters. This runs
      // whether or not webhooks are enabled on the server: busy is a per-bot fact, independent of that feature flag.
      case req @ POST -> Root / "lobby" / "bots" / team / name / "wake" =>
        limiters.wake
          .attempt(BotRoutes.clientIp(req))
          .flatMap:
            case Left(retryAfter) =>
              TooManyRequests("wake rate limit exceeded — retry later")
                .map(_.putHeaders(`Retry-After`.unsafeFromLong(math.max(1L, retryAfter.toSeconds))))
            case Right(()) =>
              val target: Principal.Bot = Principal.Bot(team, name)
              bots.openToHumansBots.flatMap { open =>
                if !open.contains(target) then NotFound()
                else
                  effectiveGuard.admits(target, SeatGuard.Purpose.Direct).flatMap {
                    case false => Ok(Wake(alive = false, busy = true))
                    case true  =>
                      webhooks match
                        case None          => ServiceUnavailable("webhooks are not enabled on this server")
                        case Some(service) => service.wake(target).flatMap(alive => Ok(Wake(alive)))
                  }
              }

      case req @ POST -> Root / "lobby" / "play-bot" =>
        playBot(req, bots, registry, effectiveGuard, limiters.playBot, session)

  /** Derive the catalog card from a stored listing, flagging (not hiding) a not-yet-converged rating — the same RD
    * threshold the leaderboard uses to hide provisional bots. `available` (#224) is evaluated against
    * listing.maxConcurrentGames minus any dedicated showcase reservation for the featured bot.
    */
  private def card(listing: BotCatalogListing, registry: GameRegistry, guard: SeatGuard): IO[CatalogBot] =
    val bot: Principal.Bot = Principal.Bot(listing.team, listing.name)
    registry.activeGamesFor(bot).map { active =>
      val maxAvailable =
        if guard.admissionGuard.showcaseConfig.isFeatured(bot) then
          math.max(0, listing.maxConcurrentGames - guard.admissionGuard.showcaseConfig.reservedSeats)
        else listing.maxConcurrentGames
      CatalogBot(
        team = listing.team,
        name = listing.name,
        rating = listing.rating,
        rd = listing.rd,
        provisional = listing.rd > Glicko2.ProvisionalDeviationThreshold,
        description = listing.description,
        available = active < maxAvailable
      )
    }

  /** `POST /lobby/play-bot` (E4). Checks run cheapest-first — the ordering the E3 review established applies here too:
    * the per-IP rate limit before any registry/store read, the guest's own active-game count (in-memory) before catalog
    * membership (a database query in Pg mode), and only then is a game actually created. A guest with an unfinished
    * catalog game is refused a second one (409) — the ADR's "limit 1 concurrent game for now" policy; switching between
    * several is a later feature. No liveness re-check here: `wake` already confirmed the endpoint moments earlier, and
    * if it has since gone away, the game clock forfeits it exactly as it would mid-game — the same "reliability is the
    * clock" model `Webhooks` documents, not a reason to duplicate the probe.
    */
  private def playBot(
      req: Request[IO],
      bots: BotStore,
      registry: GameRegistry,
      guard: SeatGuard,
      limiter: AnonMintLimiter,
      session: Option[AuthSession]
  ): IO[Response[IO]] =
    limiter
      .attempt(BotRoutes.clientIp(req))
      .flatMap:
        case Left(retryAfter) =>
          TooManyRequests("play-bot rate limit exceeded — retry later")
            .map(_.putHeaders(`Retry-After`.unsafeFromLong(math.max(1L, retryAfter.toSeconds))))
        case Right(()) =>
          req
            .attemptAs[PlayBot]
            .value
            .flatMap:
              case Left(failure) => BadRequest(failure.message)
              case Right(body)   =>
                AuthSession
                  .actingPrincipal(session, req, body.guestId, "guestId")
                  .flatMap:
                    case Left(err)     => BadRequest(err)
                    case Right(player) =>
                      if body.timeControl == TimeControl.Unlimited then
                        BadRequest("a catalog game must have a time control")
                      else startAgainstBot(player, body, bots, registry, guard)

  /** Guest identity and time control are already validated; from here: the 1-active-game gate, catalog membership, the
    * bot's own declared capacity, seat assignment, and the actual `registry.create`.
    *
    * The bot's capacity is checked after catalog membership so an unknown name still reads as 404 rather than leaking
    * whether some unrelated identity happens to be busy.
    */
  private def startAgainstBot(
      player: Principal,
      body: PlayBot,
      bots: BotStore,
      registry: GameRegistry,
      guard: SeatGuard
  ): IO[Response[IO]] =
    registry.activeGamesFor(player).flatMap {
      case active if active > 0 => Conflict("you already have an active game — finish it before starting another")
      case _                    =>
        val target: Principal.Bot = Principal.Bot(body.team, body.name)
        bots.openToHumansBots.flatMap { open =>
          if !open.contains(target) then NotFound()
          else seatGame(player, body, target, registry, guard)
        }
    }

  /** Assign seats and create the room within the atomic admission boundary; the human gets back its own seat token. */
  private def seatGame(
      player: Principal,
      body: PlayBot,
      target: Principal.Bot,
      registry: GameRegistry,
      guard: SeatGuard
  ): IO[Response[IO]] =
    seatAssignment(body.preferredColor, player, target).flatMap { (white, black, playerSeat) =>
      guard.admissionGuard
        .admitAndCreate(
          registry,
          white,
          black,
          body.timeControl,
          GameOrigin.Catalog,
          requestedRated = body.rated
        )
        .flatMap:
          case Left(AdmissionGuard.AdmissionError.Busy(_)) =>
            Conflict("that bot is busy — it is at its concurrent-game limit; try another or retry soon")
          case Left(AdmissionGuard.AdmissionError.Failed(error)) =>
            BadRequest(error)
          case Right((gameId, room)) =>
            room.joinTokens.get(playerSeat) match
              case Some(token) => Created(SeekMatch(gameId.value, token, playerSeat))
              case None        =>
                registry.abortAndDeregister(gameId) *> InternalServerError("missing seat token")
    }

  /** `(white, black, playerSeat)`: the human's chosen side if given, otherwise a coin flip — the ADR's "random by
    * default" policy.
    */
  private def seatAssignment(
      preferredColor: Option[Side],
      player: Principal,
      bot: Principal.Bot
  ): IO[(Principal, Principal, Seat)] =
    preferredColor match
      case Some(Side.White) => IO.pure((player, bot, Seat.White))
      case Some(Side.Black) => IO.pure((bot, player, Seat.Black))
      case None             =>
        IO(SecureRandom().nextBoolean()).map(playerIsWhite =>
          if playerIsWhite then (player, bot, Seat.White) else (bot, player, Seat.Black)
        )
