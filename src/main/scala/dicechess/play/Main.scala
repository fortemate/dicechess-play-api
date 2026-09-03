package dicechess.play

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import dicechess.play.core.{Principal, RatingCategory}
import dicechess.play.server.{
  AdminBotRoutes,
  AdmissionGuard,
  AnonMintLimiter,
  AuthRoutes,
  AuthSession,
  BotAuth,
  BotEvents,
  BotRoutes,
  CatalogRoutes,
  Challenges,
  Cors,
  GoogleAuth,
  GameRegistry,
  HealthRoutes,
  HistoryRoutes,
  IngestRoutes,
  LadderScheduler,
  LeaderboardRoutes,
  Lobby,
  LobbyRoutes,
  MeRoutes,
  OwnerBotRoutes,
  PlayerRoutes,
  PlayRoutes,
  RatingRoutes,
  SeatGuard,
  SessionWebhookRoutes,
  ShowcaseConfig,
  ShowcaseRoutes,
  ShowcaseTable,
  StrengthRoutes,
  ManagedWebhookVerifier,
  WebhookManagement,
  WebhookRoutes,
  WebhookSecurity,
  WebhookTransport,
  Webhooks
}
import dicechess.play.ingest.IngestDeliverer
import dicechess.play.rating.{Glicko2, RatingBatch, StrengthCache, StrengthReport}
import dicechess.play.store.{
  BotStore,
  GameStore,
  PgGameStore,
  Retention,
  ShowcaseStore,
  WebhookManagementStore,
  WebhookRequestContext,
  WebhookStatsStore,
  WebhookStore
}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** Boots the authoritative HTTP/WebSocket server. */
object Main extends IOApp.Simple:

  private val host    = host"0.0.0.0"
  private val port    = sys.env.get("PORT").flatMap(Port.fromString).getOrElse(port"8080")
  private val version = sys.env.getOrElse("APP_VERSION", "dev")

  /** The shared outbound client, with deadlines that clear the webhook window.
    *
    * On the plain `default` builder Ember's own timeouts (45 s header-receive, 60 s idle) are both shorter than a turn
    * a bot may legitimately spend, so they — not `WEBHOOK_TIMEOUT_SECONDS` — decided when a delivery died: a configured
    * 210 s was silently a 45 s cut in production (#188). Ingest is indifferent to the wider window: `IngestDeliverer`
    * bounds every request with its own 15 s timeout.
    */
  private[play] def outboundClientBuilder(webhooks: Option[Webhooks.Config]): EmberClientBuilder[IO] =
    webhooks.fold(EmberClientBuilder.default[IO]): config =>
      EmberClientBuilder
        .default[IO]
        .withTimeout(config.clientTimeout)
        .withIdleConnectionTime(config.clientIdleTimeout)

  // Persistence is opt-in by env: with PLAY_DB_URL set, games snapshot into Postgres, live games are resumed on boot,
  // and registered bot identities are durable; with INGEST_URL/INGEST_TOKEN also set, finished games are delivered to
  // analytics from the durable outbox. Without PLAY_DB_URL the server runs in-memory exactly as before (games and
  // registered bots die with the process).
  // The third slot is the concrete Postgres store when persistence is on: the rating batch and the public
  // leaderboard/profile routes both need its DB-only seams (RatingStore, LeaderboardStore) and are simply absent
  // without a database. The shared outbound client now carries ingest delivery only: webhook push goes through
  // `WebhookTransport`, whose DNS-pinned per-request client it cannot share (see below). It is still sized from the
  // webhook window, because `Webhooks` keeps it as the fallback transport its hermetic tests use, and because an
  // unused pool holds no connections.
  private def appResources
      : Resource[IO, (GameStore, BotStore, Option[PgGameStore], Client[IO], WebhookTransport, IO[Unit])] =
    (outboundClientBuilder(Webhooks.configFromEnv).build, WebhookTransport.resource).tupled.flatMap {
      (http, webhookTransport) =>
        PgGameStore.configFromEnv match
          case None =>
            Resource
              .eval(BotStore.inMemory)
              .map(bots => (GameStore.noop, bots, None, http, webhookTransport, IO.never))
          case Some(dbConfig) =>
            PgGameStore.resource(dbConfig).map { store =>
              val deliverer = IngestDeliverer.configFromEnv match
                case None =>
                  cats.effect.std
                    .Console[IO]
                    .errorln(
                      "[play][ingest] INGEST_URL/INGEST_TOKEN unset: finished games and browser reports accumulate " +
                        "in the outbox/client_reports queues"
                    )
                    *> IO.never
                case Some(ingestConfig) =>
                  // Two queues, one deliverer each (#212): the first-party outbox and the browser-submitted
                  // client_reports drain in parallel with identical retry/parking semantics.
                  (
                    IngestDeliverer(store, http, ingestConfig).loop.void,
                    IngestDeliverer(store.clientReports, http, ingestConfig).loop.void
                  ).parTupled.void
              (store, store, Some(store), http, webhookTransport, deliverer)
            }
    }

  def run: IO[Unit] = appResources.use(serve)

  /** The registry as production builds it, lifted out of [[serve]] so a test can exercise the REAL wiring instead of
    * restating it. That matters here specifically: both resolvers are seat-face lookups that only ever run against a
    * database, so a suite that re-declares this composition proves the queries work and proves nothing about whether
    * `serve` still calls them.
    *
    * Accounts live in Postgres, so seat nicknames are resolvable only when persistence is configured; without it every
    * human renders anonymous, which is the pre-#194 behaviour. Seat ratings (#290) follow the same rule, under the
    * leaderboard's own visibility threshold — a rating the board would hide as provisional never shows on a game
    * either.
    */
  private[play] def registryFor(store: GameStore, pgStore: Option[PgGameStore]): IO[GameRegistry] =
    GameRegistry.create(
      store = store,
      resolveNicknames =
        pgStore.fold[List[String] => IO[Map[String, String]]](_ => IO.pure(Map.empty))(_.nicknamesByExternalId),
      resolveRatings =
        pgStore.fold[(List[String], RatingCategory) => IO[Map[String, Double]]]((_, _) => IO.pure(Map.empty))(pg =>
          pg.settledRatingsByExternalId(_, _, Glicko2.ProvisionalDeviationThreshold)
        )
    )

  private[play] def initShowcaseConfig(
      parsed: Either[String, ShowcaseConfig] = ShowcaseConfig.fromEnv
  ): IO[ShowcaseConfig] =
    parsed match
      case Left(error) =>
        IO.raiseError(new IllegalArgumentException(s"[play] invalid showcase configuration: $error"))
      case Right(cfg) => IO.pure(cfg)

  private[play] def setupAdmission(
      botStore: BotStore,
      showcaseConfig: ShowcaseConfig,
      registry: GameRegistry
  ): IO[(AdmissionGuard, SeatGuard, Int)] =
    for
      _ <- IO
        .println(
          s"[play] showcase reservation enabled for featured bot ${showcaseConfig.featuredBot.map(b => s"${b.team}/${b.name}").getOrElse("")} (reservedSeats = ${showcaseConfig.reservedSeats})"
        )
        .whenA(showcaseConfig.enabled)
      admissionGuard <- AdmissionGuard.create(botStore, showcaseConfig, registry = Some(registry))
      _              <- registry.attachAdmissionGuard(admissionGuard)
      resumed        <- registry.resume
      _              <- IO.println(s"[play] resumed $resumed live game(s)").whenA(resumed > 0)
      seatGuard = SeatGuard(admissionGuard)
    yield (admissionGuard, seatGuard, resumed)

  private def serve(
      resources: (GameStore, BotStore, Option[PgGameStore], Client[IO], WebhookTransport, IO[Unit])
  ): IO[Unit] =
    val (store, botStore, pgStore, httpClient, webhookTransport, deliverer) = resources
    for
      showcaseConfig                 <- initShowcaseConfig()
      registry                       <- registryFor(store, pgStore)
      (admissionGuard, seatGuard, _) <- setupAdmission(botStore, showcaseConfig, registry)
      botAuth                        <- BotAuth.fromEnv(botStore)
      botEvents                      <- BotEvents.create
      // Declared per-bot capacity (#189, #45). All admission paths pass through AdmissionGuard.
      admitBoth = (one: Principal, other: Principal) => seatGuard.admitsBoth(one, other, SeatGuard.Purpose.Direct)
      challenges <- Challenges.create(botEvents, registry, admitBoth = admitBoth, admissionGuard = Some(admissionGuard))
      mintLimit  <- AnonMintLimiter.create()
      // Registration is rarer than anon minting by nature (one durable identity per team, not one per test session),
      // so it gets its own, much stricter per-IP budget.
      registerLimit <- AnonMintLimiter.create(limit = RegisterLimitPerHour)
      lobby         <- Lobby.create(
        registry,
        admitBoth = admitBoth,
        resolveNicknames =
          pgStore.fold[List[String] => IO[Map[String, String]]](_ => IO.pure(Map.empty))(_.nicknamesByExternalId),
        admissionGuard = Some(admissionGuard)
      )
      allowedOrigins <- Cors.allowedOriginsFromEnv
      cors = Cors.policy(allowedOrigins)
      // Google sign-in config (#233, ADR-0017). Read here, applied where the routes mount below: the feature needs
      // BOTH halves (Google client + session secret) and persistence — anything less leaves the auth surface unmounted.
      googleConfig  <- GoogleAuth.configFromEnv
      sessionSecret <- AuthSession.secretFromEnv
      frontendUrl   <- AuthSession.frontendUrlFromEnv
      // One session verifier shared by the /auth routes AND the game-start/WS routes (#235): with persistence and a
      // secret, a signed-in caller is seated as Principal.User wherever a guest id used to be trusted from the body.
      authSession = (pgStore, sessionSecret).mapN((pg, secret) => AuthSession(pg, secret))
      // The admin allowlist (#273): parsed here (malformed entries are named on stderr and skipped), applied where
      // the admin and `/auth/me` routes mount below — the surface needs the session verifier AND persistence, see
      // warnInertAdmins.
      admins               <- AdminBotRoutes.adminsFromEnv
      managedWebhookConfig <- WebhookManagement.Config.fromEnv(admins)
      managedWebhookRuntime =
        if !allowedOrigins.isExplicitlyConfigured then None
        else
          (managedWebhookConfig, authSession, pgStore).mapN { (config, session, pg) =>
            (config, session, pg)
          }
      _ <- warnLegacyLadderVars
      _ <- warnDeprecatedRatedForHumans
      _ <- warnDeprecatedOpenToHumans
      _ <- warnInertShowcase(enabled = showcaseConfig.enabled, persistenceOn = pgStore.isDefined)
      _ <- warnShowcaseWithoutWebhooks(enabled = showcaseConfig.enabled, webhooksOn = Webhooks.configFromEnv.isDefined)
      _ <- warnInertAdmins(sessionOn = authSession.isDefined, persistenceOn = pgStore.isDefined)
      _ <- warnInertWebhookManagement(
        enabled = managedWebhookConfig.isDefined,
        sessionOn = authSession.isDefined,
        persistenceOn = pgStore.isDefined,
        originsConfigured = allowedOrigins.isExplicitlyConfigured
      )
      _ <- managedWebhookRuntime.traverse_ { case (config, _, pg) =>
        pg.refreshAdminWebhookAuthority(
          config.adminAuthorityGeneration,
          WebhookRequestContext(None)
        ).void
      }
      adminAuthorityLoop = managedWebhookRuntime
        .map { case (config, _, pg) =>
          (IO.sleep(WebhookManagementStore.AdminHeartbeatInterval) *>
            pg
              .refreshAdminWebhookAuthority(
                config.adminAuthorityGeneration,
                WebhookRequestContext(None)
              )
              .void).foreverM
        }
        .getOrElse(IO.never)
      // The ladder scheduler is opt-in by env (LADDER_INTERVAL_SECONDS) — same "absence disables" idiom as
      // persistence/ingest above. Unset, the ladder never starts games on its own even if bots are on_ladder.
      ladderLoop <- LadderScheduler.configFromEnv match
        case None =>
          IO.println("[play][ladder] LADDER_INTERVAL_SECONDS unset: no automatic ladder pairings")
            .as(IO.never: IO[Unit])
        case Some(ladderConfig) =>
          LadderScheduler
            .create(botStore, registry, botEvents, ladderConfig, guard = Some(seatGuard))
            .map(_.scheduler())
      // The strength cache (#181) is created unconditionally: StrengthRoutes below is mounted whenever persistence
      // is configured at all, independent of whether the rating batch (its only writer) ever actually runs.
      strengthCache <- StrengthCache.create
      // The rating batch (#119) is opt-in the same way (RATING_INTERVAL_SECONDS) — and additionally needs the
      // database: without PLAY_DB_URL there is no game_results queue to drain, so a set-but-useless env var gets a
      // loud warning instead of a silent no-op. It also owns refreshing `strengthCache` (#181): with the batch off,
      // GET /strength stays "not ready" forever — the same coupling rating updates and ladder auto-park already have.
      ratingLoop <- (RatingBatch.configFromEnv, pgStore) match
        case (None, _) =>
          IO.println("[play][rating] RATING_INTERVAL_SECONDS unset: no automatic rating updates")
            .as(IO.never: IO[Unit])
        case (Some(_), None) =>
          cats.effect.std
            .Console[IO]
            .errorln("[play][rating] RATING_INTERVAL_SECONDS set but PLAY_DB_URL unset: rating batch disabled")
            .as(IO.never: IO[Unit])
        case (Some(ratingConfig), Some(pg)) =>
          IO.println(
            s"[play][rating] enabled: polling every ${ratingConfig.interval}, strength report rebuilt at most " +
              s"every ${ratingConfig.strengthRefreshInterval}"
          ) *> RatingBatch
            .create(
              botStore = botStore,
              userStore = pg,
              ratingStore = pg,
              resultsStore = pg,
              config = ratingConfig,
              strengthCache = strengthCache,
              strengthConfig = StrengthReport.Config.configFromEnv
            )
            .map(_.scheduler())
      // Retention (#179) follows the same opt-in shape, and for this one the shape is a safety property, not just
      // consistency: it is the only scheduled task that DELETES, so leaving RETENTION_INTERVAL_SECONDS unset must be
      // the state that does nothing. It also needs the database for the obvious reason — nothing to prune in memory.
      retentionLoop <- (Retention.configFromEnv, pgStore) match
        case (None, _) =>
          IO.println("[play][retention] RETENTION_INTERVAL_SECONDS unset: ended snapshots are kept indefinitely")
            .as(IO.never: IO[Unit])
        case (Some(_), None) =>
          cats.effect.std
            .Console[IO]
            .errorln("[play][retention] RETENTION_INTERVAL_SECONDS set but PLAY_DB_URL unset: retention disabled")
            .as(IO.never: IO[Unit])
        case (Some(retentionConfig), Some(pg)) =>
          IO.println(
            s"[play][retention] enabled: every ${retentionConfig.interval}, pruning operational rows older than " +
              s"${retentionConfig.retentionDays} day(s)"
          ).as(new Retention(pg, retentionConfig).scheduler())
      // Registration triggers an outbound verification POST, so it shares the strict per-IP budget of /bot/register.
      webhookLimit <- AnonMintLimiter.create(limit = RegisterLimitPerHour)
      // The catalog wake probe (E3) also POSTs outward (the same unauthenticated handshake), but a visitor browsing
      // the catalog may reasonably click several bots — the generous anon-mint budget, not the strict register one.
      wakeLimit <- AnonMintLimiter.create()
      // Starting a catalog game (E4) is a heavier action than a mere wake ping, but playing several bot games in an
      // hour is completely normal usage — the same generous budget, not the strict register one.
      playBotLimit <- AnonMintLimiter.create()
      // Browser game reports (#212) arrive in bursts when a returning visitor's IndexedDB outbox flushes, so this
      // budget is per-minute, not per-hour — the gateway's 60/min, carried over.
      ingestLimit <- AnonMintLimiter.create(limit = IngestLimitPerMinute, window = 1.minute)
      // Showcase claims (ADR-005 §10, #46) are budgeted per minute on two axes: the caller's IP, like every other open
      // endpoint here, and the acting identity, so one guest id cannot spend the table's whole budget from many hosts.
      showcaseIpLimit    <- AnonMintLimiter.create(limit = ShowcaseRoutes.ClaimsPerIpPerMinute, window = 1.minute)
      showcaseActorLimit <- AnonMintLimiter.create(limit = ShowcaseRoutes.ClaimsPerActorPerMinute, window = 1.minute)
      // Webhook push (F.2, #104) is opt-in the same way (WEBHOOK_TIMEOUT_SECONDS). Unlike the rating batch it does
      // NOT require the database: in-memory mode registers webhooks for the process's lifetime, matching how
      // registered-bot identities behave there. The service is a Resource because it owns its per-game runner
      // fibers (a Supervisor) — releasing it cancels them all. It is threaded to the routes as an Option — absent,
      // the /bot/webhook endpoints answer 503 and no delivery loop runs.
      webhookResource = Webhooks.configFromEnv match
        case None =>
          Resource
            .eval(IO.println("[play][webhook] WEBHOOK_TIMEOUT_SECONDS unset: webhook push disabled"))
            .as(None: Option[Webhooks])
        case Some(webhookConfig) =>
          Resource
            .eval(
              // The effective window, said out loud at boot: a bot author cannot size their time management against a
              // number nobody prints, and an undercutting client deadline is exactly how a configured 210 s became a
              // silent 45 s cut (#188). Delivery runs on the DNS-pinned transport, whose per-request client has no
              // deadlines of its own, so this window is the whole story; the derived shared-client cuts are printed
              // too because ingest and the hermetic fallback transport still answer to them.
              IO.println(
                s"[play][webhook] per-turn window ${webhookConfig.timeout.toSeconds}s, one pinned connection per " +
                  s"delivery (shared client cut ${webhookConfig.clientTimeout.toSeconds}s, " +
                  s"idle ${webhookConfig.clientIdleTimeout.toSeconds}s)"
              ) *> IO
                .println(
                  s"[play][webhook] ALERT: ${WebhookSecurity.LoopbackEnvVar} enabled — private loopback allowed for testing"
                )
                .whenA(
                  sys.env.get(WebhookSecurity.LoopbackEnvVar).exists(v => v.equalsIgnoreCase("true") || v == "1")
                ) *>
                pgStore.fold(WebhookStore.inMemory)(pg => IO.pure(pg: WebhookStore))
            )
            .flatMap { webhookStore =>
              // Delivery telemetry (#225) is Postgres-only, like the leaderboard/catalog: in-memory mode still
              // classifies and drains every delivery exactly as if it were on, the writes just go nowhere
              // (`WebhookStatsStore.noop`) — see its own doc for why that's the right default rather than a
              // second in-memory code path.
              val stats = pgStore.fold(WebhookStatsStore.noop)(pg => pg: WebhookStatsStore)
              Webhooks.create(
                registry,
                webhookStore,
                httpClient,
                webhookConfig,
                stats = stats,
                transport = Some(webhookTransport)
              )
            }
            .map(Some(_))
      // The sweepers (seeks, pending challenges), the ladder scheduler, the rating batch, the webhook loop, and the
      // ingest deliverer are supervised concurrently with the server: if any mandatory loop fails, the error propagates
      // and shuts down the server rather than letting /health pretend everything is running.
      _ <- webhookResource.use { webhookService =>
        // The leaderboard/profile API reads bots + game_results — DB-only seams, so without persistence the
        // routes are simply not mounted (404), same spirit as the rating batch above.
        val leaderboard =
          pgStore.fold(org.http4s.HttpRoutes.empty[IO])(pg => LeaderboardRoutes(botStore, pg, pg, pg, users = Some(pg)))
        // Same DB-only gating: the human catalog reads the bots table's rating + description columns (ADR-0014).
        val catalog = pgStore.fold(org.http4s.HttpRoutes.empty[IO])(pg =>
          CatalogRoutes(
            pg,
            botStore,
            webhookService,
            registry,
            CatalogRoutes.CatalogLimiters(wakeLimit, playBotLimit),
            authSession,
            guard = Some(seatGuard)
          )
        )
        // A visitor's own finished games (#151) — same DB-only-seam idiom: no game_results projection without a
        // database, so the route is simply not mounted.
        val playerGames = pgStore.fold(org.http4s.HttpRoutes.empty[IO])(pg => PlayerRoutes(pg, pg))
        // Same DB-only gating again (#181): `strengthCache` exists either way, but with no persistence there is no
        // rating batch to ever populate it, so mounting the route would just mean an eternal 503 instead of a 404.
        val strength = pgStore.fold(org.http4s.HttpRoutes.empty[IO])(_ => StrengthRoutes(botStore, strengthCache))
        // The durable replay endpoint (#178) reads game_archive — DB-only seam again, same idiom as every route
        // above.
        val history = pgStore.fold(org.http4s.HttpRoutes.empty[IO])(pg => HistoryRoutes(pg, pg))
        // What a finished game did to each seat's rating (#296) — reads the game_results columns the rating batch
        // writes, so it is DB-only for the same reason the batch itself is, and absent (404) without persistence.
        val gameRating = pgStore.fold(org.http4s.HttpRoutes.empty[IO])(pg => RatingRoutes(pg))
        // Browser report intake (#212) writes client_reports — DB-only seam once more: without persistence there
        // is no queue to accept into, so the SPA's POST gets a 404 and its outbox simply retries later.
        val ingest = pgStore.fold(org.http4s.HttpRoutes.empty[IO])(pg => IngestRoutes(pg, ingestLimit))
        // Google sign-in (#233, ADR-0017): needs persistence (users live in Postgres) AND the full auth config —
        // Google client + session secret. Anything less and the routes are simply not mounted (404), the same
        // absence-disables idiom as everything above; GoogleAuth.configFromEnv warns on a PARTIAL Google config.
        val auth = (pgStore, googleConfig, authSession)
          .mapN { (pg, gc, s) =>
            AuthRoutes(s, GoogleAuth.live(gc), pg, pg, pg, frontendUrl, admins)
          }
          .getOrElse(org.http4s.HttpRoutes.empty[IO])
        // The signed-in player's own surface (#236): guest claims plus the merged history those claims produce.
        // Gated exactly like /auth/* — it needs the same session, and there is nothing to read without persistence.
        // The owner's bot surface (#253/#254): the same operations the bot drives with its Bearer token, reached
        // instead with the owner's session. Needs persistence (ownership is a column) and a session secret.
        val ownerBots = (authSession, pgStore)
          .mapN((s, _) => OwnerBotRoutes(s, botAuth, registry))
          .getOrElse(org.http4s.HttpRoutes.empty[IO])
        // The administrator's bot surface (#273/#313): inventory plus any registered bot's mutations, no bot token;
        // writes are audited (V19), reads deliberately are not. Mounted only when someone is actually listed AND
        // both halves the allowlist depends on exist.
        val adminBots = (authSession, pgStore)
          .mapN((s, pg) => AdminBotRoutes(s, botAuth, admins, pg, registry))
          .filter(_ => admins.nonEmpty)
          .getOrElse(org.http4s.HttpRoutes.empty[IO])
        val me = (authSession, pgStore)
          .mapN((s, pg) => MeRoutes(s, pg, pg))
          .getOrElse(org.http4s.HttpRoutes.empty[IO])
        // Shipped dark by default: enabling the staged API requires persistence, a live account session and an exact
        // browser-origin allowlist. Bot-token webhook registration/delivery remains independent of this rollout gate.
        val managedWebhooks = managedWebhookRuntime
          .map { case (config, session, pg) =>
            val service = new WebhookManagement(
              pg,
              pg,
              Some(ManagedWebhookVerifier(webhookTransport)),
              config
            )
            SessionWebhookRoutes(session, botAuth, admins, allowedOrigins, service)
          }
          .getOrElse(org.http4s.HttpRoutes.empty[IO])

        // The singleton showcase table (ADR-005, #46). Created only when enabled; reconciled from durable state BEFORE
        // the port opens, so no visitor can ever see `open` ahead of the boot-time check. Without PostgreSQL the table
        // exists but is permanently unavailable and says so (`warnInertShowcase` above is loud about why).
        val showcaseResource: Resource[IO, Option[ShowcaseTable]] =
          if !showcaseConfig.enabled then Resource.pure(None)
          else
            val tickInterval = sys.env
              .get("SHOWCASE_TICK_SECONDS")
              .flatMap(_.toIntOption)
              .map(_.seconds)
              .getOrElse(ShowcaseTable.DefaultTickInterval)
            ShowcaseTable
              .create(
                showcaseConfig,
                registry,
                admissionGuard,
                pgStore.map(pg => pg: ShowcaseStore),
                botReady = showcaseReadiness(showcaseConfig, botStore, webhookService),
                timings = ShowcaseTable.Timings(tickInterval = tickInterval)
              )
              .evalTap(table =>
                table.reconcile.flatMap(phase =>
                  IO.println(s"[play][showcase] table reconciled at boot: ${ShowcaseTable.describe(phase)}")
                )
              )
              .map(Some(_))

        showcaseResource.use { showcase =>
          val showcaseRoutes = showcase.fold(org.http4s.HttpRoutes.empty[IO])(table =>
            ShowcaseRoutes(table, authSession, allowedOrigins, showcaseIpLimit, showcaseActorLimit)
          )
          EmberServerBuilder
            .default[IO]
            .withHost(host)
            .withPort(port)
            .withHttpWebSocketApp(wsb =>
              cors(
                (HealthRoutes(version) <+> PlayRoutes(registry, wsb, authSession) <+> LobbyRoutes(
                  lobby,
                  authSession
                ) <+>
                  leaderboard <+>
                  catalog <+> playerGames <+> strength <+> history <+> gameRating <+> ingest <+> auth <+> me <+>
                  ownerBots <+> adminBots <+> managedWebhooks <+> showcaseRoutes <+>
                  WebhookRoutes(botAuth, webhookService, webhookLimit, pgStore) <+>
                  BotRoutes(
                    botAuth,
                    challenges,
                    botEvents,
                    registry,
                    lobby,
                    BotRoutes.MintLimiters(mintLimit, registerLimit),
                    session = authSession
                  )).orNotFound
              )
            )
            .build
            .use { _ =>
              (
                deliverer,
                lobby.sweeper(),
                challenges.sweeper(),
                ladderLoop,
                ratingLoop,
                retentionLoop,
                webhookService.fold(IO.unit)(_.loop.void),
                webhookService.fold(IO.unit)(_.statsLoop.void),
                adminAuthorityLoop,
                showcase.fold(IO.unit)(_.supervise.void),
                IO.never
              ).parTupled.void
            }
        }
      }
    yield ()

  /** Per-IP hourly budget for `POST /bot/register` — a team registers a handful of identities, not thirty. */
  private val RegisterLimitPerHour = 5

  /** Per-IP per-minute budget for `POST /ingest/games` (#212) — the gateway's rate limit, carried over unchanged. */
  private val IngestLimitPerMinute = 60

  /** Renamed when #190 dropped mirrored pairs: a "pair" was two games, so the unit these knobs count changed. */
  private val RenamedLadderVars: List[(String, String)] = List(
    "LADDER_MAX_CONCURRENT_PAIRS" -> "LADDER_MAX_CONCURRENT_GAMES",
    "LADDER_TIMEOUT_PARK_PAIRS"   -> "LADDER_TIMEOUT_PARK_GAMES"
  )

  /** An old name left in a deployment's env is **ignored**, not translated — so a deployment that had tuned one away
    * from its old default silently gets the new default instead (`LADDER_MAX_CONCURRENT_PAIRS=2` meant 4 games; it now
    * yields 8). Only the old *defaults* happen to map onto the new ones. That is exactly the "set but useless env var,
    * no error surfaced anywhere" failure this server has already been bitten by three times (see AGENTS.md), so it gets
    * a loud line at boot rather than being left to be discovered from behaviour.
    */
  private def warnLegacyLadderVars: IO[Unit] =
    RenamedLadderVars.traverse_ { (obsolete, replacement) =>
      cats.effect.std
        .Console[IO]
        .errorln(
          s"[play][ladder] $obsolete is obsolete since #190 and is being IGNORED — rename it to $replacement. " +
            "A pair was two games, so double whatever value you had."
        )
        .whenA(sys.env.contains(obsolete))
    }

  /** `PLAY_RATED_FOR_HUMANS` (#247) named the operator-curated roster of bots eligible for rated human games — replaced
    * by player-chosen `rated` at game/seek creation (#279), because the roster gated a set of games that could never
    * actually reach the rating batch (the only path a human played a bot through hardcoded `requestedRated = false`, so
    * nothing curated was ever eligible in the first place). The var is simply no longer read; unlike the ladder rename
    * above there is no replacement to point at, so this warns and stops rather than warning and translating.
    */
  private def warnDeprecatedRatedForHumans: IO[Unit] =
    cats.effect.std
      .Console[IO]
      .errorln(
        "[play][rating] PLAY_RATED_FOR_HUMANS is deprecated since #279 and is being IGNORED — rated is now chosen " +
          "by the player at game/seek creation, for any registered opponent. Remove this variable."
      )
      .whenA(sys.env.contains("PLAY_RATED_FOR_HUMANS"))

  /** `PLAY_OPEN_TO_HUMANS` used to reapply an operator-curated catalog roster at every boot. The admin surface from
    * #273 can now make the same durable catalog change, with an audit row naming the administrator, so the roster was
    * retired in #310. The var is simply no longer read: state already stored in `bots` remains untouched, and this
    * warning gives deployments still setting it an explicit migration path.
    */
  private def warnDeprecatedOpenToHumans: IO[Unit] =
    cats.effect.std
      .Console[IO]
      .errorln(
        "[play][catalog] PLAY_OPEN_TO_HUMANS is deprecated since #310 and is being IGNORED — use " +
          "/admin/bots/{team}/{name}/open-to-humans instead. Remove this variable."
      )
      .whenA(sys.env.contains("PLAY_OPEN_TO_HUMANS"))

  /** `PLAY_ADMINS` (#273) mounts the admin bot surface, which needs BOTH the session verifier (`PLAY_SESSION_SECRET`)
    * and persistence (`PLAY_DB_*`) — the allowlist names accounts, and accounts exist only with both. Set without
    * either, the routes are simply not mounted: exactly the "set but useless env var, no error surfaced anywhere" class
    * the ladder vars above document, so it is loud at boot like its siblings.
    */
  private def warnInertAdmins(sessionOn: Boolean, persistenceOn: Boolean): IO[Unit] =
    cats.effect.std
      .Console[IO]
      .errorln(
        "[play][admin] PLAY_ADMINS is set but the admin surface is NOT mounted — it also needs " +
          "PLAY_SESSION_SECRET and PLAY_DB_URL/PLAY_DB_USER/PLAY_DB_PASSWORD. Set both or remove the variable."
      )
      .whenA(sys.env.contains(AdminBotRoutes.EnvVar) && !(sessionOn && persistenceOn))

  /** The showcase table requires PostgreSQL persistence (ADR-005 §7, #47): `GameRegistry` refuses to create a showcase
    * room over a non-durable store, so `SHOWCASE_ENABLED=true` without `PLAY_DB_URL` reserves a bot seat that nothing
    * can ever use and answers every claim with a failure. That is the same "set but useless env var" class the ladder
    * vars above document, so it is loud at boot like its siblings — the reservation itself is deliberately NOT disabled
    * here, because a silent fallback to unreserved capacity is exactly what ADR-005 forbids.
    */
  private def warnInertShowcase(enabled: Boolean, persistenceOn: Boolean): IO[Unit] =
    cats.effect.std
      .Console[IO]
      .errorln(
        "[play][showcase] SHOWCASE_ENABLED is true but PLAY_DB_URL is unset — showcase games require PostgreSQL " +
          "persistence and every showcase room creation will be refused. Configure PLAY_DB_URL or disable the showcase."
      )
      .whenA(enabled && !persistenceOn)

  /** Whether the featured bot can be advertised as claimable (ADR-005 §9, #46): it must be a registered identity (so
    * the reserved admission class applies to it), webhook delivery must be enabled on this server, and its registered
    * webhook must answer the verification echo — the same unsigned probe the catalog's wake uses, under the short
    * showcase deadline. Anything less is `false`, and the coordinator fails the table closed as `bot_unavailable`.
    */
  private[play] def showcaseReadiness(
      config: ShowcaseConfig,
      bots: BotStore,
      webhooks: Option[Webhooks]
  ): IO[Boolean] =
    (config.featuredBot, webhooks) match
      case (Some(bot), Some(service)) =>
        val probeTimeout = sys.env
          .get("SHOWCASE_PROBE_TIMEOUT_SECONDS")
          .flatMap(_.toIntOption)
          .map(_.seconds)
          .getOrElse(ShowcaseTable.ProbeTimeout)
        bots
          .seatPolicyOf(bot.team, bot.name)
          .flatMap:
            case None    => IO.pure(false)
            case Some(_) => service.wake(bot, probeTimeout).handleError(_ => false)
      case _ => IO.pure(false)

  /** The showcase table can only ever open if the featured bot's webhook can be driven, and `WEBHOOK_TIMEOUT_SECONDS`
    * is what enables delivery — so `SHOWCASE_ENABLED=true` without it is a table that will sit `unavailable` forever.
    * Loud at boot, like its siblings.
    */
  private def warnShowcaseWithoutWebhooks(enabled: Boolean, webhooksOn: Boolean): IO[Unit] =
    cats.effect.std
      .Console[IO]
      .errorln(
        "[play][showcase] SHOWCASE_ENABLED is true but WEBHOOK_TIMEOUT_SECONDS is unset — the featured bot cannot be " +
          "driven, so the table will stay unavailable. Set WEBHOOK_TIMEOUT_SECONDS or disable the showcase."
      )
      .whenA(enabled && !webhooksOn)

  /** ADR-004's feature flag must never produce a half-mounted cookie mutation surface. In particular, the historical
    * empty CORS setting means public credential-less reads; it is not an origin policy suitable for session writes.
    */
  private def warnInertWebhookManagement(
      enabled: Boolean,
      sessionOn: Boolean,
      persistenceOn: Boolean,
      originsConfigured: Boolean
  ): IO[Unit] =
    cats.effect.std
      .Console[IO]
      .errorln(
        "[play][webhook-management] WEBHOOK_SESSION_MANAGEMENT_ENABLED is true but the routes are NOT mounted — " +
          "PLAY_SESSION_SECRET, PLAY_DB_URL/PLAY_DB_USER/PLAY_DB_PASSWORD, and an explicit PLAY_CORS_ORIGINS " +
          "allowlist are all required."
      )
      .whenA(enabled && !(sessionOn && persistenceOn && originsConfigured))
