package dicechess.play.server

import cats.effect.{IO, Ref}
import dicechess.play.core.Principal
import dicechess.play.store.{BotCatalogState, BotRating, BotSeatPolicy, BotStore, OwnedBot, OwnerClaim}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.UUID
import scala.concurrent.duration.*

/** Authenticates bots by an opaque Bearer token. Three sources:
  *
  *   - **Static** tokens from the environment (`team|name|token`), a single-author admin gate for house/official bots —
  *     immutable, compared in constant time.
  *   - **Registered** durable identities minted by `register` (`POST /bot/register`) and persisted (hashed) in the
  *     [[BotStore]]: they survive server restarts — together with `GET /bot/games`, a registered bot resumes its games
  *     after a deploy instead of forfeiting them.
  *   - **Anonymous** ephemeral tokens minted at runtime by `mintAnon` (`POST /bot/anon`): zero-registration, in-memory,
  *     TTL-bounded, **always unranked** (`bot:team:anon:<uuid>`). For third parties to test a bot in minutes.
  *
  * `isBot` is implied: holding a bot token *is* the identity, so a guest/user can never masquerade as a bot, and an
  * anonymous bot can never collide with or impersonate a static/registered one (its team is the reserved `anon`).
  * Registered identities cannot collide with static/house ones either: reserved teams and the static roster are
  * rejected at registration.
  */
final class BotAuth private (
    staticTokens: Map[String, Principal],
    store: BotStore,
    anon: Ref[IO, Map[String, BotAuth.Anon]],
    anonTtl: FiniteDuration
):
  import BotAuth.*

  /** Static (env) token match, in constant time so a token can't be recovered by timing. Pure. */
  def authenticateStatic(token: String): Option[Principal] =
    val presented = token.getBytes(UTF_8)
    staticTokens.collectFirst {
      case (configured, bot) if MessageDigest.isEqual(configured.getBytes(UTF_8), presented) => bot
    }

  /** Full authentication: a static token, else a registered one (an indexed lookup by the token's SHA-256 — only the
    * hash is ever stored or compared, and both key spaces are high-entropy random, so lookups leak nothing useful),
    * else a live ephemeral anon token. Anon expiry uses monotonic time (immune to wall-clock / NTP shifts); an expired
    * anon token is evicted on lookup.
    */
  def authenticate(token: String): IO[Option[Principal]] =
    authenticateStatic(token) match
      case found @ Some(_) => IO.pure(found)
      case None            =>
        store.authenticate(sha256Hex(token)).flatMap {
          case found @ Some(_) => IO.pure(found)
          case None            =>
            IO.monotonic.flatMap: now =>
              anon.modify: live =>
                live.get(token) match
                  case Some(a) if a.expiresAt > now => (live, Some(a.principal))
                  case Some(_)                      => (live - token, None) // expired -> evict
                  case None                         => (live, None)
        }

  /** Claim a durable self-service identity: mint a token, persist only its hash, and hand the plaintext back — it is
    * shown exactly once and never stored. First-come-first-served within the slug rules; reserved teams and the static
    * roster can never be claimed, so registration cannot impersonate house/official bots.
    *
    * `owner` is the registering account's `user:<uuid>` when a signed-in author calls this (#253): written in the same
    * INSERT, so registering while signed in needs no follow-up claim. Absent, the bot is simply unowned — still a
    * first-class state, the same anonymous-first stance guests have.
    */
  def register(
      team: String,
      name: String,
      owner: Option[String] = None
  ): IO[Either[RegisterRejected, (String, Principal.Bot)]] =
    if !isSlug(team) || !isSlug(name) then IO.pure(Left(RegisterRejected.InvalidSlug))
    else if ReservedTeams.contains(team) then IO.pure(Left(RegisterRejected.ReservedTeam))
    else if staticTokens.values.exists(_ == Principal.Bot(team, name)) then IO.pure(Left(RegisterRejected.Taken))
    else
      val token = randomToken()
      store
        .register(team, name, sha256Hex(token), owner)
        .map(claimed => if claimed then Right((token, Principal.Bot(team, name))) else Left(RegisterRejected.Taken))

  /** Rotate a registered bot's token: the old one stops authenticating immediately, the new plaintext is handed back
    * (shown once). `None` when the caller is not a *registered* identity — static tokens rotate via the env, and
    * ephemeral anon tokens are re-minted, not rotated.
    */
  def rotate(bot: Principal.Bot): IO[Option[String]] =
    val token = randomToken()
    store.rotate(bot.team, bot.name, sha256Hex(token)).map(rotated => Option.when(rotated)(token))

  /** [[rotate]] through the audited admin path (#273): the same minting policy and shown-once contract, but the write
    * goes through [[dicechess.play.store.AdminBotStore.adminRotate]], so the credential swap and its `admin_actions`
    * row commit together. Token material stays here — the store receives only the hash, and its audit row records
    * neither. `None` when no such registered bot.
    */
  def rotateAsAdmin(
      bot: Principal.Bot,
      adminUserId: String,
      admin: dicechess.play.store.AdminBotStore
  ): IO[Option[String]] =
    val token = randomToken()
    admin.adminRotate(adminUserId, bot.team, bot.name, sha256Hex(token)).map(rotated => Option.when(rotated)(token))

  /** Claim this bot for an account (#253) — the write that finally fills `bots.owner_external_id`, and with it arms the
    * anti-farming rule `RatingBatch` already enforces (a player's game against their own bot is never rated).
    */
  def claimOwner(bot: Principal.Bot, ownerExternalId: String): IO[OwnerClaim] =
    store.claimOwner(bot.team, bot.name, ownerExternalId)

  /** Release a bot the account owns, making it claimable again. */
  def releaseOwner(team: String, name: String, ownerExternalId: String): IO[Boolean] =
    store.releaseOwner(team, name, ownerExternalId)

  /** Every bot the account owns — the "My bots" read. */
  def botsOwnedBy(ownerExternalId: String): IO[List[OwnedBot]] = store.botsOwnedBy(ownerExternalId)

  /** The registered bot's current rating-ladder state. `None` for a static or anonymous caller — same "not a registered
    * identity" meaning as `rotate`.
    */
  def ratingOf(bot: Principal.Bot): IO[Option[BotRating]] = store.ratingOf(bot.team, bot.name)

  /** Opt a registered bot in or out of the rating ladder. `None` when the caller is not a registered identity — static
    * (house) and anonymous bots have no row to opt in.
    */
  def setOnLadder(bot: Principal.Bot, onLadder: Boolean): IO[Option[BotRating]] =
    store.setOnLadder(bot.team, bot.name, onLadder)

  /** Open a registered bot to human catalog games, setting its description in the same write (ADR-0014). `None` when
    * the caller is not a registered identity — static (house) and anonymous bots have no row to flag.
    */
  def openToHumans(bot: Principal.Bot, description: Option[String]): IO[Option[BotCatalogState]] =
    store.openToHumans(bot.team, bot.name, description)

  /** Close a registered bot to human catalog games, leaving its description intact (ADR-0014). `None` when not a
    * registered identity.
    */
  def closeToHumans(bot: Principal.Bot): IO[Option[BotCatalogState]] =
    store.closeToHumans(bot.team, bot.name)

  /** The registered bot's declared seating capacity (#189). `None` for a static or anonymous caller — those have no row
    * to declare on and are unbounded, the same "not a registered identity" meaning as everywhere else here.
    */
  def seatPolicyOf(bot: Principal.Bot): IO[Option[BotSeatPolicy]] =
    store.seatPolicyOf(bot.team, bot.name)

  /** Declare how many games this bot will hold at once. The caller has already range-checked the value against
    * [[BotSeatPolicy.isDeclarable]]; `None` when the caller is not a registered identity.
    */
  def setMaxConcurrentGames(bot: Principal.Bot, maxConcurrentGames: Int): IO[Option[BotSeatPolicy]] =
    store.setMaxConcurrentGames(bot.team, bot.name, maxConcurrentGames)

  /** Mint an ephemeral, unranked anonymous bot — `bot:team:anon:<uuid>`. An optional display label becomes a readable,
    * collision-proof prefix (the uuid suffix guarantees uniqueness and the slug keeps the externalId colon-free).
    * Pruning expired entries on mint keeps the registry from accumulating tokens that are never presented again.
    */
  def mintAnon(label: Option[String]): IO[(String, Principal.Bot)] =
    for
      now  <- IO.monotonic
      uuid <- IO(UUID.randomUUID().toString)
      token              = randomToken()
      name               = label.map(slug).filter(_.nonEmpty).fold(uuid)(s => s"$s-${uuid.take(8)}")
      bot: Principal.Bot = Principal.Bot(AnonTeam, name)
      _ <- anon.update(_.filter(_._2.expiresAt > now).updated(token, Anon(bot, now + anonTtl)))
    yield (token, bot)

  /** Number of live anon tokens; also prunes any that have expired — for tests / future metrics. */
  def anonCount: IO[Int] =
    IO.monotonic.flatMap: now =>
      anon.modify: live =>
        val pruned = live.filter(_._2.expiresAt > now)
        (pruned, pruned.size)

object BotAuth:

  /** Env var holding the static bot roster: comma-separated `team|name|token` entries. */
  private val EnvVar = "PLAY_BOT_TOKENS"

  /** Reserved team for anonymous bots — never used by static/registered bots, so anon can't impersonate them. */
  val AnonTeam = "anon"

  /** Teams no self-service registration can claim: `anon` (ephemeral tokens) and `house` (official bots — normally
    * occupied by the static roster, reserved explicitly so a deploy with an empty roster leaves no squatting window).
    */
  val ReservedTeams: Set[String] = Set(AnonTeam, "house")

  /** How long an anonymous token stays valid after minting. */
  val DefaultAnonTtl: FiniteDuration = 24.hours

  /** Why a registration was refused. */
  enum RegisterRejected:
    case InvalidSlug  // team/name must be lowercase slugs (the externalId convention forbids colons etc.)
    case ReservedTeam // anon / house
    case Taken        // the (team, name) identity already exists (registered or static)

  final case class Anon(principal: Principal.Bot, expiresAt: FiniteDuration)

  def fromEnv(store: BotStore): IO[BotAuth] = fromSpec(sys.env.getOrElse(EnvVar, ""), store)

  /** Build from a roster spec (also used by tests). `anonTtl` overridable for fast TTL tests. */
  def fromSpec(spec: String, store: BotStore, anonTtl: FiniteDuration = DefaultAnonTtl): IO[BotAuth] =
    Ref.of[IO, Map[String, Anon]](Map.empty).map(new BotAuth(parseStatic(spec), store, _, anonTtl))

  /** Parse a roster spec — `team|name|token` entries separated by commas. Malformed or empty entries are ignored. */
  private def parseStatic(spec: String): Map[String, Principal] =
    spec
      .split(',')
      .toList
      .flatMap { entry =>
        entry.split('|').toList match
          // The `anon` team is reserved for self-service tokens, so a static entry can never occupy it.
          case team :: name :: token :: Nil if team.nonEmpty && name.nonEmpty && token.nonEmpty && team != AnonTeam =>
            Some(token -> Principal.Bot(team, name))
          case _ => None
      }
      .toMap

  private val rng = new SecureRandom()

  private def randomToken(): String =
    val bytes = new Array[Byte](16)
    rng.nextBytes(bytes)
    bytes.map("%02x".format(_)).mkString

  /** Lowercase, colon-free slug for a display label (externalId invariant forbids colons). */
  private def slug(s: String): String =
    s.toLowerCase.replaceAll("[^a-z0-9-]+", "-").replaceAll("(^-+|-+$)", "").take(20)

  /** Registration requires an exact slug (no silent transformation): lowercase alphanumerics and dashes, 1–32 chars,
    * starting alphanumeric — colon-free per the shared externalId convention.
    */
  private def isSlug(s: String): Boolean = s.matches("[a-z0-9][a-z0-9-]{0,31}")

  /** Hex SHA-256 of a token — the only form a registered token exists in at rest. */
  private def sha256Hex(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString
