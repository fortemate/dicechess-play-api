package dicechess.play.server

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.core.Principal
import dicechess.play.rating.Glicko2
import dicechess.play.store.{AdminBotStore, BotCatalogState, BotRating, BotSeatPolicy, UserAccount}
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.{HttpRoutes, Request, Response, Status}

import java.util.UUID

/** Summary of a bot's webhook registration for administrator inspection (#34). The HMAC secret is never exposed. */
final case class AdminWebhook(
    url: String,
    verifiedAt: java.time.Instant,
    capabilities: List[String],
    lastFailure: Option[LastDeliveryFailure]
) derives Codec.AsObject

/** One registered bot in the administrator's inventory (#313, #34). `owned` says only whether some account owns it —
  * the operator can badge self-service candidates without learning an owner's identity, which is irrelevant to the
  * admin's deliberately ownership-free authority.
  *
  * `provisional` is carried for the same reason every other rating-bearing wire type carries it (`MeResponse`, the
  * catalog card, the leaderboard row): the convergence threshold is a server constant, and a client that had to apply
  * it itself would be duplicating one. It matters more here than anywhere else — being provisional is one of the two
  * reasons a registered bot is absent from every public listing, which is the question this inventory exists to answer.
  *
  * `maxConcurrentGames`, `ladderAllowance`, and `activeGames` mirror the capacity model from [[BotRoutes]] (#189),
  * giving the administrator full visibility into bot concurrency and load utilization.
  *
  * `webhook` carries the verified webhook callback and diagnostics without revealing the HMAC secret.
  */
final case class AdminBot(
    team: String,
    name: String,
    rating: Double,
    rd: Double,
    provisional: Boolean,
    onLadder: Boolean,
    openToHumans: Boolean,
    description: Option[String],
    maxConcurrentGames: Int,
    ladderAllowance: Int,
    activeGames: Int,
    owned: Boolean,
    webhook: Option[AdminWebhook]
) derives Codec.AsObject

/** `GET /admin/bots`' full, intentionally unpaginated registry (#313, #34). */
final case class AdminBots(bots: List[AdminBot]) derives Codec.AsObject

/** The administrator's door to any registered bot (#273) — the third door after the bot's own Bearer token and the
  * owner's session, for the bot nobody can drive any more: token lost before it was ever claimed, so it is frozen on
  * the ladder, frozen in the catalog, its card uneditable and its token unrotatable. Admin authority deliberately does
  * NOT pass through claiming — claiming requires the bot's token precisely so a session alone can never take a bot over
  * (#253) — so these routes act without ownership and never grant it: nothing here touches `owner_external_id`, which
  * also keeps `RatingBatch`'s anti-farming rule off the admin's own games.
  *
  * Membership is the `PLAY_ADMINS` env allowlist of account uuids — explicit and operator-only, the same reasoning that
  * keeps the static roster and the catalog roster in the operator's env. Uuids, not nicknames: nicknames rename, and a
  * released nickname is eventually registrable by someone else (V18), which would let admin status leak to whoever
  * picks the name up. The check rides the live session read (`AuthSession.userFor` re-reads `is_active` per request,
  * per V14's doctrine), so deactivating an account revokes its admin power immediately; removing it from the env takes
  * a restart, acceptable for a break-glass surface.
  *
  * Every write goes through [[AdminBotStore]], never the plain store methods, so the mutation and its `admin_actions`
  * row (V19) commit together. Wire shapes are shared with the other two doors (`LadderStatus`, `OpenToHumans`,
  * `RotatedToken`, `ConfirmRotation`, `Capacity`, `SetCapacity`) so a client sees one vocabulary. A signed-in non-admin
  * gets **403**, not 404 — the owner surface's honesty argument: the public `GET /bots/{team}/{name}` already reveals
  * existence, and this repository is public, so hiding the surface would protect nothing. 404 stays "no such registered
  * bot".
  *
  * Not here, on purpose: `releaseOwner` (ownership transfer stays a two-sided explicit act between owner and claimant)
  * and anything rating-related (`rated_for_humans` has been dead since #279 — rated is the player's choice, and the
  * anti-farming rule lives in the batch).
  */
object AdminBotRoutes:

  /** Env var holding the admin allowlist: comma-separated account uuids. */
  private[play] val EnvVar = "PLAY_ADMINS"

  def apply(
      session: AuthSession,
      auth: BotAuth,
      admins: Set[String],
      store: AdminBotStore,
      registry: GameRegistry
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case req @ GET -> Root / "admin" / "bots" =>
        withAdmin(session, admins, req): _ =>
          store.adminBots.flatMap: bots =>
            bots
              .traverse: bot =>
                val principal: Principal.Bot = Principal.Bot(bot.team, bot.name)
                val policy                   = BotSeatPolicy(principal, bot.maxConcurrentGames, bot.openToHumans)
                registry.activeGamesFor(principal).map: active =>
                  AdminBot(
                    team = bot.team,
                    name = bot.name,
                    rating = bot.rating,
                    rd = bot.rd,
                    provisional = bot.rd > Glicko2.ProvisionalDeviationThreshold,
                    onLadder = bot.onLadder,
                    openToHumans = bot.openToHumans,
                    description = bot.description,
                    maxConcurrentGames = bot.maxConcurrentGames,
                    ladderAllowance = policy.ladderAllowance,
                    activeGames = active,
                    owned = bot.owned,
                    webhook = bot.webhook.map: w =>
                      AdminWebhook(
                        url = w.url,
                        verifiedAt = w.verifiedAt,
                        capabilities = w.capabilities,
                        lastFailure = w.lastFailure.map(f => LastDeliveryFailure(f.at, f.reason))
                      )
                  )
              .flatMap(enriched => Ok(AdminBots(enriched)))

      case req @ POST -> Root / "admin" / "bots" / team / name / "capacity" =>
        withAdmin(session, admins, req): admin =>
          req
            .attemptAs[SetCapacity]
            .value
            .flatMap:
              case Left(failure) => BadRequest(failure.message)
              case Right(body)   =>
                body.validate match
                  case Left(message) => BadRequest(message)
                  case Right(valid)  =>
                    respondCapacity(
                      store.adminSetMaxConcurrentGames(admin.id, team, name, valid.maxConcurrentGames),
                      registry,
                      Principal.Bot(team, name)
                    )

      case req @ POST -> Root / "admin" / "bots" / team / name / "ladder" / "join" =>
        withAdmin(session, admins, req): admin =>
          respondLadder(store.adminSetOnLadder(admin.id, team, name, onLadder = true))

      case req @ POST -> Root / "admin" / "bots" / team / name / "ladder" / "leave" =>
        withAdmin(session, admins, req): admin =>
          respondLadder(store.adminSetOnLadder(admin.id, team, name, onLadder = false))

      case req @ POST -> Root / "admin" / "bots" / team / name / "open-to-humans" =>
        withAdmin(session, admins, req): admin =>
          BotRoutes
            .catalogDescription(req)
            .flatMap:
              case Left(message)      => BadRequest(message)
              case Right(description) => respondCatalog(store.adminOpenToHumans(admin.id, team, name, description))

      case req @ POST -> Root / "admin" / "bots" / team / name / "open-to-humans" / "leave" =>
        withAdmin(session, admins, req): admin =>
          respondCatalog(store.adminCloseToHumans(admin.id, team, name))

      // The primitive no other door has: edit the card of a bot that stays (or is being left) closed — exactly the
      // "mark it retired" shape. The bot and owner doors only write a description while opening.
      //
      // PUT, alone on this surface, because it alone REPLACES a value rather than performing an action: two identical
      // requests leave the same state, so a client may safely retry one that timed out. Its siblings (`ladder/join`,
      // `open-to-humans`, `token`) are actions and stay POST; `DELETE /me/bots/{team}/{name}` is the same reasoning
      // applied elsewhere. Note this obliged `Cors.CredentialedMethods` to carry PUT — a session-gated route reached
      // with a verb outside that whitelist is refused at the preflight, in the browser only (#312).
      case req @ PUT -> Root / "admin" / "bots" / team / name / "description" =>
        withAdmin(session, admins, req): admin =>
          BotRoutes
            .catalogDescription(req)
            .flatMap:
              case Left(message)      => BadRequest(message)
              case Right(description) => respondCatalog(store.adminSetDescription(admin.id, team, name, description))

      // Rotation, guarded by the echoed name exactly like the owner route — it takes a RUNNING bot offline. This is
      // the recovery half of #273: hand the fresh token to the author and they claim through the normal
      // session-plus-token path, so self-service returns without ownership ever passing through the admin.
      case req @ POST -> Root / "admin" / "bots" / team / name / "token" =>
        withAdmin(session, admins, req): admin =>
          req
            .attemptAs[ConfirmRotation]
            .value
            .flatMap:
              case Left(failure)                                            => BadRequest(failure.message)
              case Right(body) if !body.confirm.trim.equalsIgnoreCase(name) =>
                BadRequest("confirm must be the bot's name — rotation takes a running bot offline")
              case Right(_) =>
                auth
                  .rotateAsAdmin(Principal.Bot(team, name), admin.id, store)
                  .flatMap:
                    case Some(token) => Ok(RotatedToken(token))
                    case None        => noSuchBot

  /** Parse an allowlist spec (also used by tests): comma-separated account uuids. Only well-formed uuids are kept,
    * canonicalized through `UUID.fromString` (the lowercase text form `users.id` travels in); every malformed entry is
    * skipped — fail-closed per entry, so a typo can only fail to grant, never grant wider.
    *
    * A rejected entry is reported by its POSITION in the list, never by its value. The realistic accident here is a
    * secret pasted into the wrong variable — `PLAY_SESSION_SECRET` and `PLAY_BOT_TOKENS` live a line away in the same
    * env file — and echoing the value would copy it into the deploy log, where it long outlives the mistake. The
    * position is what an operator actually needs to find the offending entry, and it leaks nothing.
    */
  def adminsFromSpec(spec: String): IO[Set[String]] =
    val entries     = spec.split(',').toList.map(_.trim).filter(_.nonEmpty)
    val (bad, good) = entries.zipWithIndex.partitionMap: (entry, index) =>
      Either.catchNonFatal(UUID.fromString(entry).toString.toLowerCase).left.map(_ => index + 1)
    Console[IO]
      .errorln(malformedWarning(bad, entries.size))
      .whenA(bad.nonEmpty)
      .as(good.toSet)

  /** The rejection line, as a pure function so a test can pin what it may and may not contain — the value-withholding
    * above is a security property, and an edit that re-introduced the offending entry would otherwise pass silently.
    */
  private[server] def malformedWarning(badPositions: List[Int], total: Int): String =
    s"[play][admin] PLAY_ADMINS: ${badPositions.size} entry(s) ignored, not account uuids — " +
      s"position(s) ${badPositions.mkString(", ")} of $total. Values are withheld: one of them may be a secret " +
      "pasted into the wrong variable."

  def adminsFromEnv: IO[Set[String]] = adminsFromSpec(sys.env.getOrElse(EnvVar, ""))

  /** The admin gate: a live session, then membership in the allowlist. 401 without a session (from `withUser`); 403 for
    * anyone signed-in who is not listed — see the object doc for why existence is not worth hiding.
    */
  private def withAdmin(session: AuthSession, admins: Set[String], req: Request[IO])(
      action: UserAccount => IO[Response[IO]]
  ): IO[Response[IO]] =
    session.withUser(req): user =>
      if admins.contains(user.id) then action(user)
      else IO.pure(Response[IO](Status.Forbidden).withEntity("admin only"))

  private def respondLadder(state: IO[Option[BotRating]]): IO[Response[IO]] =
    state.flatMap:
      case Some(r) => Ok(LadderStatus(r.onLadder))
      case None    => noSuchBot

  private def respondCatalog(state: IO[Option[BotCatalogState]]): IO[Response[IO]] =
    state.flatMap:
      case Some(s) => Ok(OpenToHumans(s.openToHumans, s.description))
      case None    => noSuchBot

  private def respondCapacity(
      state: IO[Option[BotSeatPolicy]],
      registry: GameRegistry,
      bot: Principal.Bot
  ): IO[Response[IO]] =
    state.flatMap:
      case Some(p) =>
        registry
          .activeGamesFor(bot)
          .flatMap(active => Ok(Capacity(p.maxConcurrentGames, p.openToHumans, p.ladderAllowance, active)))
      case None => noSuchBot

  /** Absence answered like the owner surface: the admin store's `None` means "not a registered bot". */
  private def noSuchBot: IO[Response[IO]] =
    IO.pure(Response[IO](Status.NotFound).withEntity("no such bot"))
