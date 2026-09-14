import type { DocumentState } from "./model/document";
import type { Registry } from "./registry";

export interface ChainMeta {
  first: boolean;
  last: boolean;
  index: number;
  length: number;
}

export interface DerivedMeta {
  horizontal: ChainMeta;
  vertical: ChainMeta;
  nestDepth: number;
  childCount: number;
  descendantCount: number;
  ancestors: string[];
}

/** Topology-derived metadata. Never stored as source of truth. */
export function deriveMetadata(state: DocumentState, id: string, registry: Registry): DerivedMeta {
  const horizontal = chainFrom(state, id, registry, "horizontal");
  const vertical = chainFrom(state, id, registry, "vertical");
  const ancestors = ancestorIds(state, id);
  const childCount = Object.values(state.getNode(id).pockets).reduce((n, kids) => n + kids.length, 0);
  return {
    horizontal,
    vertical,
    nestDepth: ancestors.length,
    childCount,
    descendantCount: state.pocketDescendants(id).length,
    ancestors,
  };
}

function chainFrom(
  state: DocumentState,
  id: string,
  registry: Registry,
  kind: "horizontal" | "vertical",
): ChainMeta {
  const head =
    kind === "horizontal" ? state.horizontalHead(id, registry) : verticalHead(state, id, registry);
  const members: string[] = [];
  let cursor: string | null = head;
  const guard = new Set<string>();
  while (cursor && !guard.has(cursor)) {
    guard.add(cursor);
    members.push(cursor);
    cursor =
      kind === "horizontal" ? state.horizontalNext(cursor, registry) : state.stackNext(cursor, registry);
  }
  const index = Math.max(0, members.indexOf(id));
  return {
    first: index === 0,
    last: index === members.length - 1,
    index,
    length: members.length,
  };
}

function verticalHead(state: DocumentState, id: string, registry: Registry): string {
  let cursor = id;
  const guard = new Set<string>();
  while (cursor && !guard.has(cursor)) {
    guard.add(cursor);
    const prev = state.stackPrev(cursor, registry);
    if (!prev) return cursor;
    cursor = prev;
  }
  return id;
}

function ancestorIds(state: DocumentState, id: string): string[] {
  const out: string[] = [];
  let cursor = state.parentOf(id);
  const guard = new Set<string>();
  while (cursor && !guard.has(cursor.parentId)) {
    guard.add(cursor.parentId);
    out.push(cursor.parentId);
    cursor = state.parentOf(cursor.parentId);
  }
  return out;
}
