package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.VariantIdentity
import dicechess.play.game.GameRoom

/** The play routes' shared registry surface for classic and isolated laboratory games. */
trait PlayRegistry:
  def get(id: GameId): IO[Option[GameRoom]]
  def list: IO[List[(GameId, GameRoom)]]
  def claimSeat(id: GameId, seat: Seat, claimer: Principal): IO[Boolean]
  def createFriend(
      white: Principal,
      black: Principal,
      timeControl: TimeControl,
      variant: Option[VariantIdentity],
      protocolVersion: Option[Int],
      requestedRated: Boolean
  ): IO[Either[String, (GameId, GameRoom)]]
