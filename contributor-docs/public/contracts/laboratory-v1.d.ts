/** Preparatory laboratory v1 wire contract. Not enabled in the classic server/client protocol yet.
 * Adopt with the optional PublicGameState.lab and SnapshotTurn.labChoice fields in issues 292/223/224.
 * The Scala codec suite pins canonical JSON and validates numeric bounds/UCI syntax at runtime.
 */
export type Die = 1 | 2 | 3 | 4 | 5 | 6;
export type DicePair = readonly [Die, Die];
export type PairIndex = 0 | 1;
export type VariantIdentity =
  | { id: 'classic-3d6'; revision: 1 }
  | { id: 'choice-2x2d6'; revision: 1 };
export type LabVariant = { id: 'choice-2x2d6'; revision: 1 };
export interface Provenance {
  rulesVersion: string;
  diceAlgorithm: 'choice-2x2d6-hmac-sha256-v1';
}
export type PublicTurn =
  | { phase: 'AwaitingPlayers' | 'AwaitingFirstRoll'; offers: readonly []; selectedPairIndex: null }
  | { phase: 'AwaitingSecondRoll'; offers: readonly [DicePair]; selectedPairIndex: null }
  | { phase: 'AwaitingPairChoice'; offers: readonly [DicePair, DicePair]; selectedPairIndex: null }
  | { phase: 'PlayingSelectedPair'; offers: readonly [DicePair, DicePair]; selectedPairIndex: PairIndex }
  | { phase: 'Ended'; offers: readonly [] | readonly [DicePair]; selectedPairIndex: null }
  | { phase: 'Ended'; offers: readonly [DicePair, DicePair]; selectedPairIndex: PairIndex | null };
export interface PublicState {
  variant: LabVariant;
  protocolVersion: 1;
  provenance: Provenance;
  turn: PublicTurn;
}
export interface HistoryChoice {
  offers: readonly [DicePair, DicePair];
  selectedPairIndex: PairIndex;
}
export type Action =
  | { RollFirst: Record<string, never> }
  | { RollSecond: Record<string, never> }
  | { ChoosePair: { pairIndex: PairIndex } }
  | { SubmitTurn: { moves: readonly [string] | readonly [string, string]; offerDraw?: boolean } };
export interface CommandFrame {
  LabCommand: { commandId: string; expectedVersion: number; action: Action };
}
export type ErrorCode =
  | 'InvalidCommand' | 'InvalidPairIndex' | 'NotReady' | 'WrongSeat' | 'WrongStage'
  | 'StaleVersion' | 'CommandConflict' | 'GameEnded';
export interface RejectionFrame {
  LabRejected: { v: number; commandId: string | null; code: ErrorCode; reason: string; resync: boolean };
}
export interface TurnChangedFrame {
  LabTurnChanged: {
    v: number;
    seat: 'White' | 'Black';
    lab: PublicState;
    dfen: string;
    clocks: { white: number; black: number } | null;
  };
}
export interface ReceiptFrame {
  LabCommandApplied: { commandId: string; appliedVersion: number };
}
export type LabProtocolVersion = 1;
export type LabExportSchemaVersion = 1;
