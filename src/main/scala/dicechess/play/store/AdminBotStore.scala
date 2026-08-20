package dicechess.play.store

import cats.effect.IO

/** The administrator's door to any registered bot (#273) — the same mutations [[BotStore]] grants the bot's own token
  * and the owner surface grants a claimed owner, minus every credential check, plus an audit row. Deliberately a
  * SEPARATE authority rather than an ownership bypass: claiming requires the bot's token precisely so a session alone
  * can never take a bot over (#253), and the admin's whole purpose is to act without that token — so the two paths must
  * not share a gate, and no method here may touch `owner_external_id` (or `RatingBatch`'s anti-farming rule would
  * quietly start applying to the admin).
  *
  * Implemented by [[PgGameStore]] only, like [[UserStore]]: each mutation and its `admin_actions` row (V19) commit in
  * one transaction, and an audit trail that evaporates with the process would be pretense — so there is no in-memory
  * implementation, and the admin surface mounts only when persistence is configured.
  *
  * `adminUserId` is the acting account's uuid, already vetted by the route (session plus the `PLAY_ADMINS` allowlist);
  * the store does not re-check it — it records it.
  */
trait AdminBotStore:
  /** Every registered bot for the administrator's inventory, best rating first. Unlike the methods below this is a
    * read, so it deliberately writes no `admin_actions` row: the audit answers who changed a bot, not who looked at it.
    */
  def adminBots: IO[List[AdminBotListing]]

  /** [[BotStore.setOnLadder]] with an audit row (`ladder.join` / `ladder.leave`). `None` if no such registered bot. */
  def adminSetOnLadder(adminUserId: String, team: String, name: String, onLadder: Boolean): IO[Option[BotRating]]

  /** [[BotStore.openToHumans]] with an audit row (`catalog.open`, the description as its detail). */
  def adminOpenToHumans(
      adminUserId: String,
      team: String,
      name: String,
      description: Option[String]
  ): IO[Option[BotCatalogState]]

  /** [[BotStore.closeToHumans]] with an audit row (`catalog.close`). */
  def adminCloseToHumans(adminUserId: String, team: String, name: String): IO[Option[BotCatalogState]]

  /** Replace the catalog description WITHOUT touching `open_to_humans` — the primitive no other door has:
    * [[BotStore.openToHumans]] writes a description only by also opening the bot, which is exactly wrong for marking a
    * retired bot's card. Audited as `catalog.describe` with the description as its detail.
    */
  def adminSetDescription(
      adminUserId: String,
      team: String,
      name: String,
      description: Option[String]
  ): IO[Option[BotCatalogState]]

  /** [[BotStore.rotate]] with an audit row (`token.rotate` — its detail stays NULL: no token material may reach the
    * audit). This is the recovery half of #273: a fresh token handed to the author lets them claim through the normal
    * session-plus-token path, so self-service returns without ownership ever passing through the admin.
    */
  def adminRotate(adminUserId: String, team: String, name: String, newTokenHash: String): IO[Boolean]

/** One row in the administrator's full bot inventory (#313). `owned` intentionally stops at a boolean: an administrator
  * needs to distinguish self-service from unclaimed bots, but revealing the owning account would turn a recovery
  * surface into an attribution lookup and would not change any action it may take.
  */
final case class AdminBotListing(
    team: String,
    name: String,
    rating: Double,
    rd: Double,
    onLadder: Boolean,
    openToHumans: Boolean,
    description: Option[String],
    owned: Boolean
)
