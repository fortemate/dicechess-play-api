package dicechess.play.store

import cats.effect.IO

import java.time.Instant
import scala.annotation.unused
import scala.concurrent.duration.FiniteDuration

/** A registered player (#231/#232, ADR-0017). `id` is a UUID minted by this server at first login — it is the stable
  * half of `Principal.User(id).externalId` and therefore must never be derived from anything a login provider controls
  * (see V14's rationale for why email in particular is not an identity key).
  */
final case class UserAccount(
    id: String,
    nickname: String,
    createdAt: Instant,
    lastLoginAt: Option[Instant],
    isActive: Boolean
)

/** An account's Glicko-2 state (#247, ADR-0017) — the human half of the ONE shared scale bots already live on, which is
  * why the triple is identical to [[BotRating]]'s and seeded from the same defaults. No `onLadder` counterpart: a
  * person is not scheduled into games by the server.
  */
final case class UserRating(glickoRating: Double, glickoRd: Double, glickoVol: Double):
  /** The pure-math view, exactly as `Glicko2.update` consumes and produces it. */
  def glicko: dicechess.play.rating.Glicko =
    dicechess.play.rating.Glicko(rating = glickoRating, deviation = glickoRd, volatility = glickoVol)

object UserRating:
  /** A fresh account's starting state — Glickman's defaults for an unrated player, same as `BotRating.initial`. */
  val initial: UserRating = UserRating(glickoRating = 1500, glickoRd = 350, glickoVol = 0.06)

/** The ways a nickname change can land (#275). `Taken` and `Held` are kept status-for-status indistinguishable at the
  * route (both a plain 409) on purpose: telling a caller "that name is on hold" rather than "that name is taken" would
  * itself be a membership oracle, leaking that a specific name was recently vacated. `CooldownActive` carries how long
  * until the account's next rename is allowed, for a `Retry-After` header — a plain `Taken`-shaped 409 would give the
  * caller nothing to back off on.
  */
enum NicknameUpdate:
  case Updated, Taken, UserNotFound, Held
  case CooldownActive(retryAfter: FiniteDuration)

/** The outcome of claiming a guest id. Idempotent by design: re-claiming an id already linked to the SAME account is
  * `Linked` again, not an error — the client cannot tell (and must not need to tell) whether a retry raced its first
  * attempt. `ClaimedByAnother` is terminal: `user_guest_links.guest_id` is a primary key, one account ever.
  */
enum GuestLink:
  case Linked, ClaimedByAnother, UserNotFound

/** Persistence seam for player accounts. Like the catalog and leaderboard this is a Postgres-only feature: without
  * `PLAY_DB_URL` the auth surface is simply never mounted, so no in-memory implementation exists.
  *
  * Id contract: every `userId`/`guestId` parameter must already be a valid UUID string — the implementation casts with
  * `::uuid` and does not re-validate. Routes own that boundary, same as everywhere else in this server: `userId` comes
  * from a session token this server signed, and a client-presented `guestId` must pass `Principal.guest` (the
  * `HistoryRoutes` precedent) before it reaches this seam, so a garbage id answers 4xx there instead of surfacing here
  * as a raw SQL cast error.
  */
trait UserStore:

  /** The single write behind a completed sign-in: find the `(provider, subject)` identity, or create the account it
    * should point at. First login mints the user id server-side and names the account via `freshNickname`, retrying
    * with a new candidate on a nickname collision; repeat logins refresh `last_login_at` and the stored email.
    *
    * `freshNickname` is a dependency, not a stored value, so the generator (its word lists, its collision widening) can
    * evolve in the auth layer without touching persistence.
    *
    * Concurrency: two simultaneous first logins with the same subject race on the `user_identities` primary key; the
    * loser's transaction rolls back and its retry finds the winner's identity, so exactly one account is ever created.
    */
  def upsertOnLogin(
      provider: String,
      subject: String,
      email: Option[String],
      freshNickname: IO[String]
  ): IO[UserAccount]

  /** The per-request session check reads the live row — the JWT is never trusted for `isActive`/existence. */
  def userById(id: String): IO[Option[UserAccount]]

  /** An account by its public nickname, case-insensitively — the lookup behind the public profile (#249). The nickname
    * is the ONLY public handle an account has: `user:<uuid>` must never appear on a public wire type, and a nickname is
    * what one player can actually type after seeing another across a board.
    */
  def byNickname(nickname: String): IO[Option[UserAccount]]

  /** An account's rating state (#247). Separate from [[userById]] because the two have different readers: every
    * authenticated request needs the account, only rating-aware surfaces need the triple. `None` for an unknown id.
    */
  def ratingOf(userId: String): IO[Option[UserRating]]

  /** Display names for ACCOUNT external ids (`user:<uuid>`), so a registered opponent shows a nickname instead of
    * "Anonymous" on seats, seek offers, replays and history (#194 step 4). Batched because every caller resolves a
    * whole page of games at once.
    *
    * **There is deliberately no guest path, and this must never grow one.** Resolving a `guest:<uuid>` to the nickname
    * of the account that claimed it would retroactively deanonymise every anonymous game that id ever played — the
    * promise #236 made, and the reason `GET /players/{guestId}/…` stays nameless. Ids that are not live accounts —
    * guests, bots, deleted or deactivated accounts — are simply absent from the map, which every caller renders as the
    * anonymous human face.
    *
    * The default answers "no names", so a store without accounts degrades to the pre-#194 anonymous behaviour rather
    * than to a leak: a wrong answer here should cost a nickname, never someone's anonymity.
    */
  def nicknamesByExternalId(@unused externalIds: List[String]): IO[Map[String, String]] = IO.pure(Map.empty)

  /** Rename, enforcing the case-insensitive uniqueness the V14 index defines, plus the rename guard (#275): a 90-day
    * cooldown since this account's last actual rename, and a 90-day hold on a name this or another account vacated
    * (checked against the previous owner, so reclaiming your OWN just-vacated name is never blocked by your own hold).
    * A pure case change (`"Foo"` → `"foo"`) is not a rename for either purpose — it touches neither the cooldown clock
    * nor the hold table, since nothing is actually vacated. On success the old name is released into the hold table and
    * the change is appended to `nickname_history`, all in the one transaction. Format validation (length, alphabet,
    * reserved words) is the route's job — this store only knows what the database can enforce.
    */
  def updateNickname(userId: String, nickname: String): IO[NicknameUpdate]

  /** Claim a guest id for an account (proof of ownership = presenting the id, the restore-code trust model). */
  def linkGuest(userId: String, guestId: String): IO[GuestLink]

  /** The guest ids this account has claimed, oldest link first — the id set merged-history reads expand over. */
  def guestsOf(userId: String): IO[List[String]]

  /** Self-service account deletion (#237). Identities and guest links go via `ON DELETE CASCADE`; `game_results` and
    * `game_archive` are untouched on purpose — the account's `user:<uuid>` external id simply stops resolving, which
    * anonymizes the history without rewriting immutable records. `false` when the user was already gone.
    */
  def deleteUser(userId: String): IO[Boolean]
