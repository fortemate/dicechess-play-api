package dicechess.play.server

import dicechess.play.core.Principal

/** Configuration for the singleton showcase table and its dedicated bot capacity reservation (ADR-005, #44, #45).
  *
  * When enabled, the featured bot reserves exactly 1 seat for showcase use (`SHOWCASE_RESERVED_SEATS = 1`), leaving
  * remaining capacity for general admission. Values of `SHOWCASE_RESERVED_SEATS` other than 1 are rejected during
  * boot-time configuration validation.
  */
final case class ShowcaseConfig(
    enabled: Boolean,
    featuredBot: Option[Principal.Bot],
    reservedSeats: Int
):
  def isFeatured(bot: Principal.Bot): Boolean =
    enabled && featuredBot.contains(bot)

object ShowcaseConfig:

  val Disabled: ShowcaseConfig = ShowcaseConfig(enabled = false, featuredBot = None, reservedSeats = 0)

  /** Parse from raw values (used by tests and fromEnv). */
  def fromValues(
      enabledRaw: Option[String],
      teamRaw: Option[String],
      nameRaw: Option[String],
      reservedSeatsRaw: Option[String]
  ): Either[String, ShowcaseConfig] =
    parseEnabled(enabledRaw).flatMap: isEnabled =>
      if !isEnabled then Right(Disabled)
      else
        for
          team  <- teamRaw.filter(_.trim.nonEmpty).toRight("SHOWCASE_BOT_TEAM is required when SHOWCASE_ENABLED=true")
          name  <- nameRaw.filter(_.trim.nonEmpty).toRight("SHOWCASE_BOT_NAME is required when SHOWCASE_ENABLED=true")
          seats <- reservedSeatsRaw match
            case None      => Left("SHOWCASE_RESERVED_SEATS is required when SHOWCASE_ENABLED=true")
            case Some(raw) =>
              raw.trim.toIntOption match
                case Some(1)     => Right(1)
                case Some(other) =>
                  Left(s"SHOWCASE_RESERVED_SEATS must be exactly 1 when SHOWCASE_ENABLED=true, got: $other")
                case None =>
                  Left(s"SHOWCASE_RESERVED_SEATS must be an integer, got: '$raw'")
        yield ShowcaseConfig(
          enabled = true,
          featuredBot = Some(Principal.Bot(team.trim, name.trim)),
          reservedSeats = seats
        )

  private def parseEnabled(raw: Option[String]): Either[String, Boolean] =
    raw.map(_.trim.toLowerCase) match
      case None | Some("") | Some("false") | Some("0") => Right(false)
      case Some("true") | Some("1")                    => Right(true)
      case Some(value) => Left(s"SHOWCASE_ENABLED must be true/false or 1/0, got: '$value'")

  def fromEnv: Either[String, ShowcaseConfig] =
    fromValues(
      sys.env.get("SHOWCASE_ENABLED"),
      sys.env.get("SHOWCASE_BOT_TEAM"),
      sys.env.get("SHOWCASE_BOT_NAME"),
      sys.env.get("SHOWCASE_RESERVED_SEATS")
    )
