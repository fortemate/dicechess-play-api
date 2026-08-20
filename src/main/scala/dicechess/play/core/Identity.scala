package dicechess.play.core

/** The two playing sides. Decoupled from the engine's `Color`. */
enum Side:
  case White, Black

  def opponent: Side = this match
    case White => Black
    case Black => White

/** A seat at a game: the two players, plus read-only spectators. */
enum Seat:
  case White, Black, Spectator

  def side: Option[Side] = this match
    case White     => Some(Side.White)
    case Black     => Some(Side.Black)
    case Spectator => None

  def opponent: Option[Seat] = this match
    case White     => Some(Black)
    case Black     => Some(White)
    case Spectator => None

/** A stable participant identity. The game room depends only on this — never on a concrete transport — so humans
  * (WebSocket) and bots (HTTP) plug in as adapters.
  */
enum Principal:
  case Guest(id: String)
  case User(id: String)
  case Bot(team: String, name: String)

  /** Canonical analytics `external_id`. The format is the literal convention shared with `dicechess-analytics` and the
    * play SPA (`guest:<uuidv7>`, `user:<uuid>`, `bot:team:<team>:<name>`) — it must NOT be re-encoded (e.g. Base64), or
    * a guest would get a different id than the SPA mints and the player would split into two rows.
    *
    * Invariant: ids are UUIDs and bot `team`/`name` are colon-free slugs, so the `:`-joined form is unambiguous.
    * Enforced at the identity-issuance boundary — `Principal.guest` for guests, `BotAuth.isSlug` for bots — not by this
    * case class itself (which stays a plain constructor for internal/test use).
    */
  def externalId: String = this match
    case Guest(id)       => s"guest:$id"
    case User(id)        => s"user:$id"
    case Bot(team, name) => s"bot:team:$team:$name"

object Principal:
  /** The inverse of [[Principal.externalId]] for registered-bot ids — `bot:team:<team>:<name>` and nothing else.
    * Colocated with the format so the two can't drift. Safe to split on `:` because team and name are colon-free slugs
    * (the issuance invariant above). `None` for every other identity shape (guests, users, the legacy `bot:<algorithm>`
    * ids analytics knows).
    */
  def fromBotExternalId(externalId: String): Option[Principal.Bot] =
    externalId.split(':') match
      case Array("bot", "team", team, name) if team.nonEmpty && name.nonEmpty => Some(Principal.Bot(team, name))
      case _                                                                  => None

  /** The inverse of [[Principal.externalId]] for accounts — `user:<uuid>` and nothing else (#248). Colocated with the
    * bot parser above and with the format itself, so the three cannot drift. Returns the bare id (not a `Principal`)
    * because every caller wants exactly that: the key to look the account up by.
    *
    * The uuid shape is verified, so a malformed stored id resolves to `None` rather than reaching a `::uuid` cast.
    */
  def fromUserExternalId(externalId: String): Option[String] =
    externalId.split(':') match
      case Array("user", id) if scala.util.Try(java.util.UUID.fromString(id)).isSuccess => Some(id)
      case _                                                                            => None

  /** Validates a client-supplied guest id at the identity-issuance boundary (`POST /games`, `/lobby/seeks`,
    * `/lobby/seeks/{id}/accept`) before it can ever reach `externalId`: it must be a UUID, per the invariant
    * `externalId` documents. Rejects empty and colon-containing ids in particular — either would otherwise produce a
    * malformed or ambiguous `guest:...` external_id that collides with the `user:`/`bot:team:` conventions (see
    * dicechess-play-api#14).
    */
  def guest(id: String): Either[String, Principal.Guest] =
    if scala.util.Try(java.util.UUID.fromString(id)).isSuccess then Right(Principal.Guest(id))
    else Left(s"'$id' is not a valid guest id (must be a UUID)")
