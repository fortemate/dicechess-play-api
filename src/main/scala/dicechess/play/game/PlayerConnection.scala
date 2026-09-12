package dicechess.play.game

import cats.effect.IO
import dicechess.play.core.*

/** A player attached to a room. The room knows nothing about transports — a connection is "a principal at a seat that
  * consumes events and submits commands". The website WebSocket and the third-party Bot API are two implementations of
  * this seam.
  */
trait PlayerConnection:
  def principal: Principal
  def seat: Seat

  /** Drive this connection against the room until the game ends. */
  def run(room: GameRoom): IO[Unit]
