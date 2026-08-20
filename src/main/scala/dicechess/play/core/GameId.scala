package dicechess.play.core

import cats.effect.IO

import java.util.UUID

/** A game's identifier — a distinct type so route and registry keys can't be confused with arbitrary strings. Backed by
  * a random UUID.
  */
opaque type GameId = String

object GameId:
  def apply(value: String): GameId = value
  def random: IO[GameId]           = IO(UUID.randomUUID().toString)

  extension (id: GameId) def value: String = id
