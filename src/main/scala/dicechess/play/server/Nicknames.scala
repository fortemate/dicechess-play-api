package dicechess.play.server

import cats.effect.IO

import java.security.SecureRandom

/** Nicknames for registered players (#234, ADR-0017): the dice/chess-flavored generator behind first login, and the
  * format rules `PATCH /auth/me` enforces on a manual rename.
  *
  * The split of responsibilities is deliberate: THIS object owns everything about what a nickname may look like;
  * `UserStore` owns only what the database can enforce (case-insensitive uniqueness). Generated names always pass
  * [[validate]] by construction — a regression there is a bug worth a test, not a runtime surprise.
  */
object Nicknames:

  /** Bounds chosen for display: long enough for the generator's longest `adjective+noun+3digits` combination, short
    * enough for one line in a player strip.
    */
  private val MinLength = 3
  private val MaxLength = 24

  /** Names that would impersonate a system actor or an anonymized label. `guest`/`anonymous` are what the UI calls
    * unsigned humans, `house`/`anon` are reserved bot namespaces (`BotAuth`), and `admin`/`bot` claim an authority no
    * account has. Checked case-insensitively against the WHOLE name, not as a substring — `BotanicalRook` is fine.
    */
  private val Reserved: Set[String] =
    Set("guest", "anonymous", "anon", "house", "bot", "admin", "moderator", "system")

  private val Shape = "^[A-Za-z_][A-Za-z0-9_-]*$".r

  /** Validate a manual nickname. `Right` carries the trimmed value to store verbatim (display casing is the player's
    * choice; uniqueness is case-insensitive in the database). The rules, and why:
    *   - 3–24 chars of `[A-Za-z0-9_-]`, not starting with a digit or dash — so a nickname can never be confused with a
    *     UUID, a number, or a CLI flag anywhere it is rendered or logged;
    *   - no reserved system words (see [[Reserved]]).
    */
  def validate(raw: String): Either[String, String] =
    val name = raw.trim
    if name.length < MinLength || name.length > MaxLength then
      Left(s"nickname must be $MinLength-$MaxLength characters")
    else if !Shape.matches(name) then
      Left("nickname must be letters, digits, '_' or '-', and start with a letter or '_'")
    else if Reserved.contains(name.toLowerCase) then Left("that nickname is reserved")
    else Right(name)

  /** A fresh dice/chess-flavored candidate — `LuckyRook417`, `DoubleSixKnight8`. Uniqueness is NOT this function's job:
    * the store retries with the next candidate on a collision (`upsertOnLogin`), and with ~50×24 word pairs times 990
    * numbers the space is deep enough that a retry run of five means something is broken, not crowded.
    */
  def fresh: IO[String] = IO {
    val adjective = Adjectives(random.nextInt(Adjectives.length))
    val noun      = Nouns(random.nextInt(Nouns.length))
    val number    = 10 + random.nextInt(990) // 10..999: two or three digits, never a bare single digit
    s"$adjective$noun$number"
  }

  private val random = SecureRandom()

  /** Upbeat, game-adjacent, and safe in any combination — no words that could turn insulting next to a noun. */
  private val Adjectives: Vector[String] = Vector(
    "Lucky",
    "Swift",
    "Bold",
    "Clever",
    "Brave",
    "Calm",
    "Daring",
    "Eager",
    "Fierce",
    "Gentle",
    "Happy",
    "Jolly",
    "Keen",
    "Lively",
    "Mighty",
    "Noble",
    "Plucky",
    "Quick",
    "Rapid",
    "Sharp",
    "Silent",
    "Sly",
    "Smart",
    "Solid",
    "Spry",
    "Steady",
    "Stout",
    "Sunny",
    "Tricky",
    "Vivid",
    "Wise",
    "Zesty",
    "Golden",
    "Silver",
    "Crimson",
    "Cobalt",
    "Amber",
    "Ivory",
    "Jade",
    "Coral",
    "Double",
    "Triple",
    "Rolling",
    "Dancing",
    "Flying",
    "Charging",
    "Wandering",
    "Winning"
  )

  /** Pieces, dice, and game terms — the site's own vocabulary. */
  private val Nouns: Vector[String] = Vector(
    "Rook",
    "Knight",
    "Bishop",
    "Queen",
    "King",
    "Pawn",
    "Dice",
    "Roll",
    "Six",
    "Pip",
    "Gambit",
    "Check",
    "Castle",
    "Tempo",
    "Fork",
    "Pin",
    "Endgame",
    "Opening",
    "Streak",
    "Charge",
    "Squire",
    "Herald",
    "Jester",
    "Champion"
  )
