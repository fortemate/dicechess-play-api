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

  val DefaultReservedSeats: Int = 1

  val Disabled: ShowcaseConfig = ShowcaseConfig(enabled = false, featuredBot = None, reservedSeats = 0)

  /** Parse from raw values (used by tests and fromEnv). */
  def fromValues(
      enabledRaw: Option[String],
      teamRaw: Option[String],
      nameRaw: Option[String],
      reservedSeatsRaw: Option[String]
  ): Either[String, ShowcaseConfig] =
    val isEnabled = enabledRaw.exists(v => v.equalsIgnoreCase("true") || v == "1")
    if !isEnabled then Right(Disabled)
    else
      for
        team  <- teamRaw.filter(_.trim.nonEmpty).toRight("SHOWCASE_BOT_TEAM is required when SHOWCASE_ENABLED=true")
        name  <- nameRaw.filter(_.trim.nonEmpty).toRight("SHOWCASE_BOT_NAME is required when SHOWCASE_ENABLED=true")
        seats <- reservedSeatsRaw match
          case None      => Right(DefaultReservedSeats)
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

  def fromEnv: Either[String, ShowcaseConfig] =
    fromValues(
      sys.env.get("SHOWCASE_ENABLED"),
      sys.env.get("SHOWCASE_BOT_TEAM"),
      sys.env.get("SHOWCASE_BOT_NAME"),
      sys.env.get("SHOWCASE_RESERVED_SEATS")
    )
