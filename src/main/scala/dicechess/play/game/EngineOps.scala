package dicechess.play.game

import dicechess.engine.domain.*
import dicechess.engine.search.TurnGenerator
import dicechess.play.core.{Seat, Side}

/** Thin, centralized wrapper over the dice-chess engine's JVM API. Everything that touches engine internals (FEN/DFEN,
  * dice injection, turn generation, move application, king-capture detection) lives here so the rest of the server
  * stays engine-agnostic and the rules never drift from the analytics replay gate.
  */
object EngineOps:

  val InitialDfen: String = FenParser.InitialPosition

  def parse(dfen: String): Either[String, GameState] = FenParser.parse(dfen)

  def serialize(state: GameState): String = FenParser.serialize(state)

  /** Inject the server-rolled dice into the position (the authoritative RNG seam). */
  def withDice(state: GameState, dice: List[Int]): GameState = state.withDicePool(dice)

  def activeSide(state: GameState): Side =
    if state.activeColor.isWhite then Side.White else Side.Black

  def activeSeat(state: GameState): Seat =
    if state.activeColor.isWhite then Seat.White else Seat.Black

  /** UCI for a search-layer `Move` (which has no `toNotation` of its own). */
  def toUci(move: Move): String =
    move.fromSquare.toNotation + move.toSquare.toNotation +
      move.promotionPieceType.map(_.asNotation).getOrElse("")

  /** All legal turns for the current dice pool, as engine `Move` paths. Enumerated once per roll by the room (which
    * caches the result for wire exposure and submit validation) — never per submitted command.
    */
  def legalMovePaths(state: GameState): List[List[Move]] =
    TurnGenerator.generateAllLegalTurnPaths(state)

  /** Applies a chosen path of micro-moves. Returns the resulting state and the winning side if the opponent's king was
    * captured — in which case `endTurn()` is deliberately NOT called, because the game is already over.
    */
  def applyPath(state: GameState, path: List[Move]): (GameState, Option[Side]) =
    var current              = state
    var winner: Option[Side] = None
    val moves                = path.iterator
    while moves.hasNext && winner.isEmpty do
      val move   = moves.next()
      val target = current.mailbox(move.toSquare)
      if !target.isEmpty && target.pieceType == PieceType.King && target.color != current.activeColor
      then winner = Some(if current.activeColor.isWhite then Side.White else Side.Black)
      current = current.makeMove(move)
    if winner.isDefined then (current, winner) else (current.endTurn(), None)
