/** Canonical relationship document. Geometry is derived from topology. */

export const SCHEMA_VERSION = 1 as const;

export interface Vec2 {
  x: number;
  y: number;
}

export interface PortRef {
  nodeId: string;
  portId: string;
}

export type NodeRole = "block";
export type EdgePresentation = "flush";
export type Cardinal = "top" | "bottom" | "left" | "right";

export interface NodeRecord {
  id: string;
  type: string;
  permissionType?: string;
  /** Session pose cache for free / top-level nodes. Not part of canonical JSON. */
  transform: Vec2;
  /** Named mouths → ordered child node ids (nesting, not a side-edge). */
  pockets: Record<string, string[]>;
  role?: NodeRole;
  label?: string;
  /** Inner control values owned by a pack (keyword, text, …). Not HTML/CSS/JS. */
  fields?: Record<string, string>;
}

export interface EdgeRecord {
  id: string;
  from: PortRef;
  to: PortRef;
  presentation: EdgePresentation;
}

/** Topology-only node. N/W links are implied by a parent’s S/E. */
export interface EncapsulatedNode {
  typeId: string;
  id: string;
  permissionType?: string;
  data?: Record<string, string>;
  nest?: Record<string, EncapsulatedNode[]>;
  e?: EncapsulatedNode;
  s?: EncapsulatedNode;
  type?: string;
  fields?: Record<string, string>;
  pockets?: Record<string, EncapsulatedNode[]>;
  right?: EncapsulatedNode;
  below?: EncapsulatedNode;
  transform?: Vec2;
  role?: NodeRole;
  label?: string;
  lineId?: string;
}

export interface SnapDocument {
  schemaVersion: typeof SCHEMA_VERSION;
  roots: EncapsulatedNode[];
  /** Legacy graph form (accepted on parse, omitted on canonicalize). */
  nodes?: NodeRecord[];
  edges?: EdgeRecord[];
}

export function emptyDocument(): SnapDocument {
  return { schemaVersion: SCHEMA_VERSION, roots: [] };
}

export function cloneDocument(doc: SnapDocument): SnapDocument {
  return structuredClone(doc);
}

export function nodeTypeId(node: EncapsulatedNode | NodeRecord): string {
  if ("typeId" in node && node.typeId) return node.typeId;
  return node.type ?? "";
}
