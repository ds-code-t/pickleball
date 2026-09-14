export type GraphEventKind =
  | "drag"
  | "drop"
  | "snap"
  | "disconnect"
  | "graph-change"
  | "select"
  | "pickup"
  | "cancel";

export interface GraphEventParty {
  id: string;
  typeId: string;
}

export interface GraphEvent {
  kind: GraphEventKind;
  moving?: GraphEventParty;
  stationary?: GraphEventParty;
  extra?: Record<string, unknown>;
}

export type GraphEventListener = (event: GraphEvent) => void;
