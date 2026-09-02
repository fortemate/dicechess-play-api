package dicechess.play.core

/** Purpose of a room admission request, separating general admission from the singleton showcase table.
  *
  * Defined in ADR-005 (#44, #45):
  *   - `Ladder`: Pairing initiated by the rating ladder matchmaker.
  *   - `Direct`: Challenges, lobby seeks, and catalog games initiated by players or bots.
  *   - `Showcase`: Claims for the singleton showcase table. Exclusively consumes the reserved showcase seat.
  */
enum AdmissionPurpose:
  case Ladder
  case Direct
  case Showcase

  def isShowcase: Boolean = this == Showcase
  def isGeneral: Boolean  = !isShowcase

/** The originating surface of a game room (ADR-005, #44, #45, #47).
  *
  * Maps every game creation path to an authoritative origin:
  *   - `Showcase`: Singleton showcase table against the featured bot.
  *   - `Ladder`: Rating ladder matchmaker.
  *   - `Catalog`: Human-vs-bot game started from the public bot catalog.
  *   - `Lobby`: Game matched from a public lobby seek.
  *   - `Direct`: Direct bot-to-bot challenge or friend-by-link game.
  *   - `Legacy`: Pre-existing games or records created before origin tracking.
  */
enum GameOrigin:
  case Showcase, Ladder, Catalog, Lobby, Direct, Legacy

  def wireName: String = toString.toLowerCase

  def isShowcase: Boolean = this == Showcase

  def admissionPurpose: AdmissionPurpose = this match
    case Showcase => AdmissionPurpose.Showcase
    case Ladder   => AdmissionPurpose.Ladder
    case _        => AdmissionPurpose.Direct

object GameOrigin:
  val valuesList: List[GameOrigin] = values.toList

  def fromWireName(s: String): Option[GameOrigin] =
    valuesList.find(_.wireName.equalsIgnoreCase(s))
