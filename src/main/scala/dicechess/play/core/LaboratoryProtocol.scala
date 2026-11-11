package dicechess.play.core

/** Preparatory laboratory v1 contracts, deliberately not wired into classic routes or rooms. The normative turn, retry,
  * startup, storage and export mapping is in the contributor documentation's laboratory-contract page. Implementing the
  * room, storage and dice derivation belongs to separate issues.
  */
object LaboratoryProtocol:

  val ProtocolVersion: Int      = 1
  val ExportSchemaVersion: Int  = 1
  val DiceAlgorithm: String     = "choice-2x2d6-hmac-sha256-v1"
  val MaxWireVersion: Long      = 9007199254740991L // JavaScript's largest exact integer
  val Classic: VariantIdentity  = VariantIdentity("classic-3d6", 1)
  val TwoPairs: VariantIdentity = VariantIdentity("choice-2x2d6", 1)

  final case class VariantIdentity(id: String, revision: Int)

  /** Captured at game creation and retained; not the current server's mutable dependency metadata. */
  final case class Provenance(rulesVersion: String, diceAlgorithm: String)

  /** Array order is preserved for the roll transcript, even though engine legality treats dice as a multiset. */
  final case class DicePair(first: Int, second: Int):
    def values: List[Int] = List(first, second)

  enum Phase:
    case AwaitingPlayers, AwaitingFirstRoll, AwaitingSecondRoll, AwaitingPairChoice, PlayingSelectedPair, Ended

  /** PUBLIC projection: contains only revealed offers. The private snapshot must retain the deterministic complete
    * offer state and reveal count separately; an unrevealed offer has no field in this type.
    */
  final case class PublicTurn(phase: Phase, offers: List[DicePair], selectedPairIndex: Option[Int])

  final case class PublicState(
      variant: VariantIdentity,
      protocolVersion: Int,
      provenance: Provenance,
      turn: PublicTurn
  )

  /** Exactly two offers and a zero-based index, attached to an existing completed-turn history record. */
  final case class HistoryChoice(offers: List[DicePair], selectedPairIndex: Int)

  enum Action:
    case RollFirst, RollSecond
    case ChoosePair(pairIndex: Int)
    case SubmitTurn(moves: List[String], offerDraw: Boolean = false)

  /** Future WS envelope: {"LabCommand":{"commandId":"turn-1-choice","expectedVersion":3,"action":...}}. No
    * authenticated seat or game id comes from this body: the existing connection supplies both.
    */
  final case class Command(commandId: String, expectedVersion: Long, action: Action)
  final case class CommandFrame(LabCommand: Command)

  enum ErrorCode:
    case InvalidCommand, InvalidPairIndex, NotReady, WrongSeat, WrongStage, StaleVersion, CommandConflict, GameEnded

  /** A private refusal at the CURRENT version; it does not increment the room version or mint a command receipt. */
  final case class Rejection(v: Long, commandId: Option[String], code: ErrorCode, reason: String, resync: Boolean)
  final case class RejectionFrame(LabRejected: Rejection)

  /** Complete laboratory turn context accompanying each committed stage change. DFEN has no dice field before
    * selection; the selected two-dice field and legal tree become available only in PlayingSelectedPair.
    */
  final case class TurnChanged(v: Long, seat: Seat, lab: PublicState, dfen: String, clocks: Option[Clocks])
  final case class TurnChangedFrame(LabTurnChanged: TurnChanged)

  /** Private command receipt, persisted with its effects. Duplicate identical commands return the original applied
    * version and a fresh snapshot without broadcasting the old public events again.
    */
  final case class Receipt(commandId: String, appliedVersion: Long)
  final case class ReceiptFrame(LabCommandApplied: Receipt)

  def validVersion(version: Long): Boolean = version >= 0 && version <= MaxWireVersion

  def validCommandId(id: String): Boolean = id.matches("[A-Za-z0-9_-]{1,64}")

  def validateVariant(variant: VariantIdentity): Either[String, VariantIdentity] =
    Either.cond(variant == Classic || variant == TwoPairs, variant, "unsupported variant identity or revision")

  def validatePair(pair: DicePair): Either[String, DicePair] =
    Either.cond(pair.values.forall(v => v >= 1 && v <= 6), pair, "each die must be in 1..6")

  def validateTurn(turn: PublicTurn): Either[String, PublicTurn] =
    val validChoice = turn.selectedPairIndex.forall(i => i >= 0 && i <= 1 && turn.offers.size == 2)
    val validStage  = turn.phase match
      case Phase.AwaitingPlayers | Phase.AwaitingFirstRoll => turn.offers.isEmpty && turn.selectedPairIndex.isEmpty
      case Phase.AwaitingSecondRoll                        => turn.offers.size == 1 && turn.selectedPairIndex.isEmpty
      case Phase.AwaitingPairChoice                        => turn.offers.size == 2 && turn.selectedPairIndex.isEmpty
      case Phase.PlayingSelectedPair                       => turn.offers.size == 2 && turn.selectedPairIndex.isDefined
      case Phase.Ended                                     => turn.offers.size <= 2
    Either.cond(
      validChoice && validStage && turn.offers.forall(p => validatePair(p).isRight),
      turn,
      "offers and selectedPairIndex do not match the public phase"
    )
