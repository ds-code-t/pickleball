import { assertUniqueIds, createId } from "./ids";
import type { EdgeRecord, EncapsulatedNode, NodeRecord, SnapDocument, Vec2 } from "./types";
import { SCHEMA_VERSION, emptyDocument } from "./types";
import { DocumentState } from "./document";
import type { Registry } from "../registry";

const KEY_ORDER_DOC = ["schemaVersion", "roots"] as const;
const KEY_ORDER_NODE = ["typeId", "id", "permissionType", "data", "nest", "e", "s"] as const;

function ordered<T extends object>(obj: T, keys: readonly string[]): T {
  const out: Record<string, unknown> = {};
  for (const key of keys) {
    if (key in obj && (obj as Record<string, unknown>)[key] !== undefined) {
      out[key] = (obj as Record<string, unknown>)[key];
    }
  }
  for (const key of Object.keys(obj).sort()) {
    if (!(key in out) && (obj as Record<string, unknown>)[key] !== undefined) {
      out[key] = (obj as Record<string, unknown>)[key];
    }
  }
  return out as T;
}

export function canonicalize(doc: SnapDocument, registry?: Registry): SnapDocument {
  const state = DocumentState.fromDocument(doc, registry);
  if (registry) state.canonicalizeTransforms(registry);
  if (registry) state.ensurePocketKeys(registry);
  const roots = encapsulateState(state, registry).map(canonicalizeEncapsulated);
  const ids = collectIds(roots);
  assertUniqueIds(ids, "nodes");
  return ordered({ schemaVersion: SCHEMA_VERSION, roots }, KEY_ORDER_DOC) as SnapDocument;
}

export function encapsulateState(state: DocumentState, registry?: Registry): EncapsulatedNode[] {
  const placed = new Set<string>();
  const roots: EncapsulatedNode[] = [];
  const free = [...state.nodes.keys()].filter((id) => (registry ? state.isFree(id, registry) : !state.isContained(id)));
  free.sort(compareFree(state));
  for (const id of free) {
    if (placed.has(id)) continue;
    roots.push(encapsulateNode(state, registry, id, placed));
  }
  for (const id of state.nodes.keys()) {
    if (placed.has(id)) continue;
    roots.push(encapsulateNode(state, registry, id, placed));
  }
  return roots;
}

function compareFree(state: DocumentState): (a: string, b: string) => number {
  return (a, b) => {
    const na = state.getNode(a).transform;
    const nb = state.getNode(b).transform;
    if (na.y !== nb.y) return na.y - nb.y;
    if (na.x !== nb.x) return na.x - nb.x;
    return a < b ? -1 : a > b ? 1 : 0;
  };
}

function encapsulateNode(
  state: DocumentState,
  registry: Registry | undefined,
  id: string,
  placed: Set<string>,
): EncapsulatedNode {
  placed.add(id);
  const node = state.getNode(id);
  const enc: EncapsulatedNode = {
    typeId: node.type,
    id: node.id,
  };
  const permissionType = node.permissionType ?? (registry?.has(node.type) ? registry.get(node.type).permissionType : undefined);
  if (permissionType) enc.permissionType = permissionType;
  if (node.fields && Object.keys(node.fields).length > 0) enc.data = { ...node.fields };

  const pocketIds = registry ? state.pocketIds(id, registry) : Object.keys(node.pockets);
  const nest: Record<string, EncapsulatedNode[]> = {};
  for (const pocketId of pocketIds) {
    const children = node.pockets[pocketId] ?? [];
    const heads: EncapsulatedNode[] = [];
    const seen = new Set<string>();
    for (const childId of children) {
      if (seen.has(childId) || placed.has(childId)) continue;
      heads.push(encapsulateNode(state, registry, childId, placed));
      if (registry) {
        for (const mid of state.stackChain(childId, registry)) seen.add(mid);
      } else {
        seen.add(childId);
      }
    }
    if (heads.length > 0 || keepEmptyPocket(state, registry, id, pocketId)) {
      nest[pocketId] = heads;
    }
  }
  if (Object.keys(nest).length > 0) enc.nest = nest;

  if (registry) {
    const rightId = state.horizontalNext(id, registry);
    if (rightId && !placed.has(rightId)) enc.e = encapsulateNode(state, registry, rightId, placed);
    const belowId = state.stackNext(id, registry);
    if (belowId && !placed.has(belowId)) enc.s = encapsulateNode(state, registry, belowId, placed);
  }
  return enc;
}

function keepEmptyPocket(
  state: DocumentState,
  registry: Registry | undefined,
  id: string,
  pocketId: string,
): boolean {
  if (!Object.prototype.hasOwnProperty.call(state.getNode(id).pockets, pocketId)) return false;
  if (!registry || !registry.has(state.getNode(id).type)) return true;
  const def = registry.get(state.getNode(id).type);
  return !def.pockets.some((pocket) => pocket.id === pocketId && !pocket.repeatable);
}

function canonicalizeEncapsulated(node: EncapsulatedNode): EncapsulatedNode {
  const nestSrc = node.nest ?? node.pockets;
  const nest: Record<string, EncapsulatedNode[]> = {};
  if (nestSrc) {
    for (const key of Object.keys(nestSrc)) {
      nest[key] = nestSrc[key].map(canonicalizeEncapsulated);
    }
  }
  const raw: EncapsulatedNode = {
    typeId: node.typeId || node.type || "",
    id: node.id,
  };
  if (node.permissionType) raw.permissionType = node.permissionType;
  const data = node.data ?? node.fields;
  if (data && Object.keys(data).length > 0) raw.data = { ...data };
  if (Object.keys(nest).length > 0) raw.nest = nest;
  const east = node.e ?? node.right;
  const south = node.s ?? node.below;
  if (east) raw.e = canonicalizeEncapsulated(east);
  if (south) raw.s = canonicalizeEncapsulated(south);
  return ordered(raw, KEY_ORDER_NODE) as EncapsulatedNode;
}

export function flattenDocument(doc: SnapDocument): { nodes: NodeRecord[]; edges: EdgeRecord[] } {
  if (doc.roots && doc.roots.length > 0) {
    const nodes: NodeRecord[] = [];
    const edges: EdgeRecord[] = [];
    const seen = new Set<string>();
    doc.roots.forEach((root, index) => {
      flattenNode(root, nodes, edges, seen, root.transform ?? { x: 48, y: 24 + index * 96 }, true);
    });
    return { nodes, edges };
  }
  return {
    nodes: (doc.nodes ?? []).map(cloneGraphNode),
    edges: (doc.edges ?? []).map((e) => ({
      id: e.id,
      from: { ...e.from },
      to: { ...e.to },
      presentation: "flush" as const,
    })),
  };
}

function flattenNode(
  node: EncapsulatedNode,
  nodes: NodeRecord[],
  edges: EdgeRecord[],
  seen: Set<string>,
  transform: Vec2,
  isRoot: boolean,
): string {
  if (seen.has(node.id)) return node.id;
  seen.add(node.id);
  const pockets: Record<string, string[]> = {};
  const nest = node.nest ?? node.pockets;
  if (nest) {
    for (const [pocketId, kids] of Object.entries(nest)) {
      const ids: string[] = [];
      for (const kid of kids) {
        flattenNode(kid, nodes, edges, seen, { x: 0, y: 0 }, false);
        ids.push(...chainIds(kid));
      }
      pockets[pocketId] = unique(ids);
    }
  }
  const data = node.data ?? node.fields;
  nodes.push({
    id: node.id,
    type: node.typeId || node.type || "",
    permissionType: node.permissionType,
    role: "block",
    label: node.label,
    transform: isRoot ? { x: transform.x, y: transform.y } : { x: 0, y: 0 },
    fields: data ? { ...data } : {},
    pockets,
  });
  const east = node.e ?? node.right;
  const south = node.s ?? node.below;
  if (east) {
    flattenNode(east, nodes, edges, seen, { x: 0, y: 0 }, false);
    edges.push({
      id: createId(),
      from: { nodeId: node.id, portId: "right" },
      to: { nodeId: east.id, portId: "left" },
      presentation: "flush",
    });
  }
  if (south) {
    flattenNode(south, nodes, edges, seen, { x: 0, y: 0 }, false);
    edges.push({
      id: createId(),
      from: { nodeId: node.id, portId: "bottom" },
      to: { nodeId: south.id, portId: "top" },
      presentation: "flush",
    });
  }
  return node.id;
}

function chainIds(node: EncapsulatedNode): string[] {
  const ids = [node.id];
  let cursor: EncapsulatedNode | undefined = node.s ?? node.below;
  while (cursor) {
    ids.push(cursor.id);
    cursor = cursor.s ?? cursor.below;
  }
  return ids;
}

function unique(ids: string[]): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const id of ids) {
    if (seen.has(id)) continue;
    seen.add(id);
    out.push(id);
  }
  return out;
}

function cloneGraphNode(node: NodeRecord): NodeRecord {
  const pockets: Record<string, string[]> = {};
  for (const key of Object.keys(node.pockets)) pockets[key] = [...node.pockets[key]];
  return {
    id: node.id,
    type: node.type,
    permissionType: node.permissionType,
    transform: { x: node.transform.x, y: node.transform.y },
    pockets,
    role: node.role ?? "block",
    label: node.label,
    fields: node.fields ? { ...node.fields } : {},
  };
}

function collectIds(roots: EncapsulatedNode[]): string[] {
  const ids: string[] = [];
  const walk = (node: EncapsulatedNode) => {
    ids.push(node.id);
    const nest = node.nest ?? node.pockets;
    if (nest) for (const kids of Object.values(nest)) kids.forEach(walk);
    const east = node.e ?? node.right;
    const south = node.s ?? node.below;
    if (east) walk(east);
    if (south) walk(south);
  };
  roots.forEach(walk);
  return ids;
}

export function serializeDocument(doc: SnapDocument, registry?: Registry): string {
  return `${JSON.stringify(canonicalize(doc, registry), null, 2)}\n`;
}

export function parseDocument(json: string): SnapDocument {
  const parsed = JSON.parse(json) as Partial<SnapDocument>;
  if (!parsed || typeof parsed !== "object") throw new Error("Document must be an object");
  const hasRoots = Array.isArray(parsed.roots) && parsed.roots.length > 0;
  const hasGraph = Array.isArray(parsed.nodes) && parsed.nodes.length > 0;
  if (hasRoots || (Array.isArray(parsed.roots) && !hasGraph)) {
    return { schemaVersion: SCHEMA_VERSION, roots: (parsed.roots ?? []).map(normalizeEncapsulated) };
  }
  return {
    schemaVersion: SCHEMA_VERSION,
    roots: [],
    nodes: Array.isArray(parsed.nodes) ? parsed.nodes.map(cloneGraphNode) : [],
    edges: Array.isArray(parsed.edges)
      ? parsed.edges.map((e) => ({
          id: String(e.id),
          from: { nodeId: String(e.from.nodeId), portId: String(e.from.portId) },
          to: { nodeId: String(e.to.nodeId), portId: String(e.to.portId) },
          presentation: "flush" as const,
        }))
      : [],
  };
}

function normalizeEncapsulated(node: EncapsulatedNode): EncapsulatedNode {
  const nestSrc = node.nest ?? node.pockets;
  const nest: Record<string, EncapsulatedNode[]> = {};
  if (nestSrc) {
    for (const [key, kids] of Object.entries(nestSrc)) {
      nest[key] = (kids ?? []).map(normalizeEncapsulated);
    }
  }
  const data = node.data ?? node.fields;
  const out: EncapsulatedNode = {
    typeId: String(node.typeId || node.type || ""),
    id: String(node.id),
  };
  if (node.permissionType) out.permissionType = String(node.permissionType);
  if (data) {
    const next: Record<string, string> = {};
    for (const [k, v] of Object.entries(data)) next[k] = String(v);
    out.data = next;
  }
  if (Object.keys(nest).length > 0) out.nest = nest;
  const east = node.e ?? node.right;
  const south = node.s ?? node.below;
  if (east) out.e = normalizeEncapsulated(east);
  if (south) out.s = normalizeEncapsulated(south);
  if (node.transform) out.transform = { x: Number(node.transform.x ?? 0), y: Number(node.transform.y ?? 0) };
  return out;
}

export function roundTrip(doc: SnapDocument, registry?: Registry): SnapDocument {
  return parseDocument(serializeDocument(doc, registry));
}

export { emptyDocument };
