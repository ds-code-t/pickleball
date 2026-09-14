import { createId } from "./ids";
import type { EdgePresentation, EdgeRecord, EncapsulatedNode, NodeRecord, SnapDocument, Vec2 } from "./types";
import { SCHEMA_VERSION, emptyDocument } from "./types";
import { canConnect, portOf, type Registry } from "../registry";
import { isHorizontalPair, isVerticalPair } from "../layout/align";

export interface ParentRef {
  parentId: string;
  pocketId: string;
  index: number;
}

export interface NodeInit {
  label?: string;
  fields?: Record<string, string>;
  permissionType?: string;
}

export class DocumentState {
  schemaVersion: typeof SCHEMA_VERSION = SCHEMA_VERSION;
  nodes = new Map<string, NodeRecord>();
  edges = new Map<string, EdgeRecord>();

  static fromDocument(doc: SnapDocument, registry?: Registry): DocumentState {
    const state = new DocumentState();
    state.schemaVersion = doc.schemaVersion ?? SCHEMA_VERSION;
    const graph =
      doc.roots && doc.roots.length > 0
        ? flattenRoots(doc.roots)
        : { nodes: doc.nodes ?? [], edges: doc.edges ?? [] };
    for (const node of graph.nodes) {
      state.nodes.set(node.id, cloneNode(node));
    }
    for (const edge of graph.edges) {
      state.edges.set(edge.id, {
        id: edge.id,
        from: { ...edge.from },
        to: { ...edge.to },
        presentation: "flush",
      });
    }
    if (registry) state.ensurePocketKeys(registry);
    return state;
  }

  static empty(): DocumentState {
    return DocumentState.fromDocument(emptyDocument());
  }

  toDocument(): SnapDocument {
    return {
      schemaVersion: this.schemaVersion,
      roots: [],
      nodes: [...this.nodes.values()].map(cloneNode),
      edges: [...this.edges.values()].map((e) => ({
        id: e.id,
        from: { ...e.from },
        to: { ...e.to },
        presentation: "flush" as const,
      })),
    };
  }

  clone(): DocumentState {
    return DocumentState.fromDocument(this.toDocument());
  }

  ensurePocketKeys(registry: Registry): void {
    for (const node of this.nodes.values()) {
      if (!registry.has(node.type)) continue;
      const def = registry.get(node.type);
      for (const pocket of def.pockets) {
        if (pocket.repeatable) continue;
        if (!node.pockets[pocket.id]) node.pockets[pocket.id] = [];
      }
    }
  }

  pocketIds(id: string, registry: Registry): string[] {
    const node = this.getNode(id);
    if (!registry.has(node.type)) return Object.keys(node.pockets);
    const def = registry.get(node.type);
    const order: string[] = [];
    const seen = new Set<string>();
    for (const pocket of def.pockets) {
      if (pocket.repeatable) {
        const extras = Object.keys(node.pockets)
          .filter((key) => key === pocket.id || key.startsWith(`${pocket.id}-`))
          .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
        for (const key of extras) {
          if (seen.has(key)) continue;
          seen.add(key);
          order.push(key);
        }
        continue;
      }
      if (!seen.has(pocket.id)) {
        seen.add(pocket.id);
        order.push(pocket.id);
      }
    }
    for (const key of Object.keys(node.pockets)) {
      if (seen.has(key)) continue;
      order.push(key);
    }
    return order;
  }

  getNode(id: string): NodeRecord {
    const node = this.nodes.get(id);
    if (!node) throw new Error(`Unknown node ${id}`);
    return node;
  }

  parentOf(id: string): ParentRef | null {
    for (const node of this.nodes.values()) {
      for (const [pocketId, children] of Object.entries(node.pockets)) {
        const index = children.indexOf(id);
        if (index >= 0) return { parentId: node.id, pocketId, index };
      }
    }
    return null;
  }

  incomingEdges(nodeId: string): EdgeRecord[] {
    return [...this.edges.values()].filter((e) => e.to.nodeId === nodeId);
  }

  outgoingEdges(nodeId: string): EdgeRecord[] {
    return [...this.edges.values()].filter((e) => e.from.nodeId === nodeId);
  }

  incomingOnPort(nodeId: string, portId: string): EdgeRecord | undefined {
    return [...this.edges.values()].find((e) => e.to.nodeId === nodeId && e.to.portId === portId);
  }

  outgoingOnPort(nodeId: string, portId: string): EdgeRecord | undefined {
    return [...this.edges.values()].find((e) => e.from.nodeId === nodeId && e.from.portId === portId);
  }

  incomingFlush(
    id: string,
    registry: Registry,
    kind: "vertical" | "horizontal" | "any" = "any",
  ): EdgeRecord | null {
    for (const edge of this.incomingEdges(id)) {
      if (kind === "any") return edge;
      const fromNode = this.nodes.get(edge.from.nodeId);
      const toNode = this.nodes.get(edge.to.nodeId);
      if (!fromNode || !toNode) continue;
      const fromPort = portOf(fromNode, registry, edge.from.portId);
      const toPort = portOf(toNode, registry, edge.to.portId);
      if (kind === "vertical" && isVerticalPair(fromPort.side, toPort.side)) return edge;
      if (kind === "horizontal" && isHorizontalPair(fromPort.side, toPort.side)) return edge;
    }
    return null;
  }

  outgoingFlush(
    id: string,
    registry: Registry,
    kind: "vertical" | "horizontal" | "any" = "any",
  ): EdgeRecord[] {
    const out: EdgeRecord[] = [];
    for (const edge of this.outgoingEdges(id)) {
      if (kind === "any") {
        out.push(edge);
        continue;
      }
      const fromNode = this.nodes.get(edge.from.nodeId);
      const toNode = this.nodes.get(edge.to.nodeId);
      if (!fromNode || !toNode) continue;
      const fromPort = portOf(fromNode, registry, edge.from.portId);
      const toPort = portOf(toNode, registry, edge.to.portId);
      if (kind === "vertical" && isVerticalPair(fromPort.side, toPort.side)) out.push(edge);
      if (kind === "horizontal" && isHorizontalPair(fromPort.side, toPort.side)) out.push(edge);
    }
    return out;
  }

  incomingStack(id: string, registry: Registry): EdgeRecord | null {
    return this.incomingFlush(id, registry, "vertical");
  }

  outgoingStack(id: string, registry: Registry): EdgeRecord | null {
    return this.outgoingFlush(id, registry, "vertical")[0] ?? null;
  }

  stackPrev(id: string, registry: Registry): string | null {
    return this.incomingStack(id, registry)?.from.nodeId ?? null;
  }

  stackNext(id: string, registry: Registry): string | null {
    return this.outgoingStack(id, registry)?.to.nodeId ?? null;
  }

  horizontalPrev(id: string, registry: Registry): string | null {
    return this.incomingFlush(id, registry, "horizontal")?.from.nodeId ?? null;
  }

  horizontalNext(id: string, registry: Registry): string | null {
    return this.outgoingFlush(id, registry, "horizontal")[0]?.to.nodeId ?? null;
  }

  horizontalHead(id: string, registry: Registry): string {
    let cursor = id;
    const guard = new Set<string>();
    while (cursor && !guard.has(cursor)) {
      guard.add(cursor);
      const prev = this.horizontalPrev(cursor, registry);
      if (!prev) return cursor;
      cursor = prev;
    }
    return id;
  }

  isHorizontalHead(id: string, registry: Registry): boolean {
    return this.horizontalHead(id, registry) === id;
  }

  hasNestedChildren(id: string): boolean {
    const node = this.nodes.get(id);
    if (!node) return false;
    return Object.values(node.pockets).some((kids) => kids.length > 0);
  }

  isParent(id: string): boolean {
    return this.hasNestedChildren(id);
  }

  canBeHorizontalTrailer(id: string): boolean {
    return !this.isParent(id);
  }

  canAcceptNest(id: string, registry: Registry): boolean {
    const node = this.nodes.get(id);
    if (!node || !registry.has(node.type)) return false;
    if (registry.get(node.type).pockets.length === 0) return false;
    return this.isHorizontalHead(id, registry);
  }

  isFlushAttached(id: string, registry: Registry): boolean {
    return this.incomingFlush(id, registry) !== null;
  }

  isFree(id: string, registry: Registry): boolean {
    if (!this.nodes.get(id)) return false;
    return !this.isContained(id) && !this.isFlushAttached(id, registry);
  }

  isContained(id: string): boolean {
    return this.parentOf(id) !== null;
  }

  pocketDescendants(id: string): string[] {
    const node = this.getNode(id);
    const out: string[] = [];
    for (const children of Object.values(node.pockets)) {
      for (const childId of children) {
        out.push(childId);
        out.push(...this.pocketDescendants(childId));
      }
    }
    return out;
  }

  stackChain(id: string, registry: Registry): string[] {
    const out = [id];
    let cursor: string | null = id;
    const guard = new Set<string>([id]);
    while (cursor) {
      const edge = this.outgoingStack(cursor, registry);
      if (!edge) break;
      const next = edge.to.nodeId;
      if (guard.has(next)) break;
      guard.add(next);
      out.push(next);
      cursor = next;
    }
    return out;
  }

  dragUnit(id: string, registry: Registry): string[] {
    const unit: string[] = [];
    const visit = (nid: string) => {
      if (unit.includes(nid)) return;
      unit.push(nid);
      for (const child of this.pocketDescendants(nid)) visit(child);
      for (const edge of this.outgoingFlush(nid, registry)) visit(edge.to.nodeId);
    };
    visit(id);
    return unit;
  }

  /** Lift only the cluster root. Nested / right / below stay attached. */
  liftCluster(id: string, registry: Registry): string[] {
    const unit = this.dragUnit(id, registry);
    this.detachFromParent(id);
    const incoming = this.incomingFlush(id, registry);
    if (incoming) this.removeEdge(incoming.id);
    for (const nid of unit) {
      if (nid === id) continue;
      const parent = this.parentOf(nid);
      if (parent && !unit.includes(parent.parentId)) this.detachFromParent(nid);
    }
    return unit;
  }

  addNode(type: string, transform: Vec2, registry: Registry, init: NodeInit = {}): NodeRecord {
    const def = registry.get(type);
    const pockets: Record<string, string[]> = {};
    for (const pocket of def.pockets) {
      if (pocket.repeatable) continue;
      pockets[pocket.id] = [];
    }
    const id = createId();
    const node: NodeRecord = {
      id,
      type,
      permissionType: init.permissionType ?? def.permissionType,
      transform: { x: transform.x, y: transform.y },
      pockets,
      role: "block",
      label: init.label ?? def.label,
      fields: { ...(init.fields ?? {}) },
    };
    this.nodes.set(node.id, node);
    return node;
  }

  setField(id: string, key: string, value: string): void {
    const node = this.getNode(id);
    node.fields = { ...(node.fields ?? {}), [key]: value };
  }

  addRepeatablePocket(id: string, templateId: string, registry: Registry): string | null {
    const node = this.getNode(id);
    if (!registry.has(node.type)) return null;
    const def = registry.get(node.type);
    const template = def.pockets.find((p) => p.id === templateId);
    if (!template) return null;
    if (template.repeatable) {
      let n = 1;
      while (node.pockets[`${templateId}-${n}`]) n += 1;
      const pocketId = `${templateId}-${n}`;
      node.pockets[pocketId] = [];
      return pocketId;
    }
    if (!node.pockets[templateId]) {
      node.pockets[templateId] = [];
      return templateId;
    }
    let n = 2;
    while (node.pockets[`${templateId}-${n}`]) n += 1;
    const pocketId = `${templateId}-${n}`;
    node.pockets[pocketId] = [];
    return pocketId;
  }

  setTransform(id: string, transform: Vec2): void {
    this.getNode(id).transform = { x: transform.x, y: transform.y };
  }

  detachFromParent(id: string): ParentRef | null {
    const parent = this.parentOf(id);
    if (!parent) return null;
    const node = this.getNode(parent.parentId);
    node.pockets[parent.pocketId] = node.pockets[parent.pocketId].filter((cid) => cid !== id);
    return parent;
  }

  insertInPocket(parentId: string, pocketId: string, childId: string, index: number, registry?: Registry): boolean {
    if (registry && !this.canAcceptNest(parentId, registry)) return false;
    this.detachFromParent(childId);
    const parent = this.getNode(parentId);
    const list = parent.pockets[pocketId] ?? [];
    const clamped = Math.max(0, Math.min(index, list.length));
    const next = [...list];
    next.splice(clamped, 0, childId);
    parent.pockets[pocketId] = next;
    if (registry) this.wirePocketStack(parentId, pocketId, registry);
    return true;
  }

  /** Consecutive pocket siblings become a stack chain (next/prev edges). */
  wirePocketStack(parentId: string, pocketId: string, registry: Registry): void {
    const list = this.getNode(parentId).pockets[pocketId] ?? [];
    const members = new Set(list);
    for (const edge of [...this.edges.values()]) {
      if (!members.has(edge.from.nodeId) || !members.has(edge.to.nodeId)) continue;
      const fromNode = this.nodes.get(edge.from.nodeId);
      const toNode = this.nodes.get(edge.to.nodeId);
      if (!fromNode || !toNode) continue;
      const fromPort = portOf(fromNode, registry, edge.from.portId);
      const toPort = portOf(toNode, registry, edge.to.portId);
      if (isVerticalPair(fromPort.side, toPort.side)) this.edges.delete(edge.id);
    }
    for (let i = 0; i < list.length - 1; i += 1) {
      this.connectPorts(
        registry,
        { nodeId: list[i], portId: "bottom" },
        { nodeId: list[i + 1], portId: "top" },
        "flush",
      );
    }
  }

  /** If a vertical neighbor lives in a pocket, adopt the other end into that pocket. */
  adoptStackIntoPocket(from: { nodeId: string; portId: string }, to: { nodeId: string; portId: string }, registry: Registry): void {
    const fromPort = portOf(this.getNode(from.nodeId), registry, from.portId);
    const toPort = portOf(this.getNode(to.nodeId), registry, to.portId);
    if (!isVerticalPair(fromPort.side, toPort.side)) return;

    const src = fromPort.direction === "out" ? from : to;
    const dst = fromPort.direction === "out" ? to : from;
    const srcParent = this.parentOf(src.nodeId);
    const dstParent = this.parentOf(dst.nodeId);

    if (srcParent && !dstParent) {
      this.insertInPocket(srcParent.parentId, srcParent.pocketId, dst.nodeId, srcParent.index + 1, registry);
    } else if (dstParent && !srcParent) {
      this.insertInPocket(dstParent.parentId, dstParent.pocketId, src.nodeId, dstParent.index, registry);
    }
  }

  addEdge(
    from: { nodeId: string; portId: string },
    to: { nodeId: string; portId: string },
    presentation: EdgePresentation = "flush",
  ): EdgeRecord {
    const edge: EdgeRecord = {
      id: createId(),
      from: { ...from },
      to: { ...to },
      presentation,
    };
    this.edges.set(edge.id, edge);
    return edge;
  }

  removeEdge(id: string): void {
    this.edges.delete(id);
  }

  disconnectPort(nodeId: string, portId: string): void {
    for (const edge of [...this.edges.values()]) {
      if (
        (edge.from.nodeId === nodeId && edge.from.portId === portId) ||
        (edge.to.nodeId === nodeId && edge.to.portId === portId)
      ) {
        this.edges.delete(edge.id);
      }
    }
  }

  connectPorts(
    registry: Registry,
    from: { nodeId: string; portId: string },
    to: { nodeId: string; portId: string },
    presentation: EdgePresentation = "flush",
  ): EdgeRecord | null {
    const fromNode = this.getNode(from.nodeId);
    const toNode = this.getNode(to.nodeId);
    if (from.nodeId === to.nodeId) return null;
    if (!canConnect(registry, fromNode.type, from.portId, toNode.type, to.portId, fromNode, toNode)) {
      return null;
    }

    const fromPort = portOf(fromNode, registry, from.portId);
    const toPort = portOf(toNode, registry, to.portId);
    if (isHorizontalPair(fromPort.side, toPort.side)) {
      const rightId = fromPort.side === "left" ? from.nodeId : to.nodeId;
      if (!this.canBeHorizontalTrailer(rightId)) return null;
    }
    if (isVerticalPair(fromPort.side, toPort.side)) {
      if (!this.isHorizontalHead(from.nodeId, registry) || !this.isHorizontalHead(to.nodeId, registry)) {
        return null;
      }
    }
    const src = fromPort.direction === "out" ? from : to;
    const dst = fromPort.direction === "out" ? to : from;
    const srcPort = fromPort.direction === "out" ? fromPort : toPort;

    const displaced = this.outgoingOnPort(src.nodeId, src.portId);
    this.disconnectPort(src.nodeId, src.portId);
    this.disconnectPort(dst.nodeId, dst.portId);

    const edge = this.addEdge(src, dst, presentation);

    if (
      displaced &&
      displaced.to.nodeId !== dst.nodeId &&
      (srcPort.side === "bottom" || srcPort.side === "right")
    ) {
      const kind = srcPort.side === "bottom" ? "vertical" : "horizontal";
      const chain = walkChain(this, dst.nodeId, registry, kind);
      const tail = chain[chain.length - 1];
      if (tail && !chain.includes(displaced.to.nodeId)) {
        const tailOut = srcPort.side === "bottom" ? "bottom" : "right";
        this.disconnectPort(tail, tailOut);
        this.addEdge({ nodeId: tail, portId: tailOut }, displaced.to, "flush");
      }
    }

    return edge;
  }

  removeNodeSubtree(id: string, registry: Registry): void {
    if (!this.nodes.has(id)) return;
    const doomed = new Set<string>([id, ...this.pocketDescendants(id)]);
    for (const nid of doomed) this.detachFromParent(nid);
    for (const edge of [...this.edges.values()]) {
      if (doomed.has(edge.from.nodeId) || doomed.has(edge.to.nodeId)) this.edges.delete(edge.id);
    }
    for (const nid of doomed) this.nodes.delete(nid);
    void registry;
  }

  canonicalizeTransforms(registry: Registry): void {
    for (const node of this.nodes.values()) {
      if (!this.isFree(node.id, registry)) {
        node.transform = { x: 0, y: 0 };
      }
    }
  }
}

function walkChain(
  state: DocumentState,
  id: string,
  registry: Registry,
  kind: "vertical" | "horizontal",
): string[] {
  const out = [id];
  let cursor: string | null = id;
  const guard = new Set<string>([id]);
  while (cursor) {
    const edge: EdgeRecord | undefined = state.outgoingFlush(cursor, registry, kind)[0];
    if (!edge) break;
    const next: string = edge.to.nodeId;
    if (guard.has(next)) break;
    guard.add(next);
    out.push(next);
    cursor = next;
  }
  return out;
}

function cloneNode(node: NodeRecord): NodeRecord {
  return {
    id: node.id,
    type: node.type,
    permissionType: node.permissionType,
    transform: { x: node.transform.x, y: node.transform.y },
    pockets: clonePockets(node.pockets),
    role: node.role ?? "block",
    label: node.label,
    fields: node.fields ? { ...node.fields } : {},
  };
}

function clonePockets(pockets: Record<string, string[]>): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  for (const key of Object.keys(pockets)) out[key] = [...pockets[key]];
  return out;
}

function flattenRoots(roots: EncapsulatedNode[]): { nodes: NodeRecord[]; edges: EdgeRecord[] } {
  const nodes: NodeRecord[] = [];
  const edges: EdgeRecord[] = [];
  const seen = new Set<string>();
  let rootIndex = 0;
  const walk = (node: EncapsulatedNode, isRoot: boolean): void => {
    if (seen.has(node.id)) return;
    seen.add(node.id);
    const pockets: Record<string, string[]> = {};
    const nest = node.nest ?? node.pockets;
    if (nest) {
      for (const [pocketId, kids] of Object.entries(nest)) {
        const ids: string[] = [];
        for (const kid of kids) {
          ids.push(...belowChainIds(kid));
          walk(kid, false);
        }
        pockets[pocketId] = uniqueIds(ids);
      }
    }
    const data = node.data ?? node.fields;
    nodes.push({
      id: node.id,
      type: node.typeId || node.type || "",
      permissionType: node.permissionType,
      role: "block",
      label: node.label,
      transform: isRoot
        ? {
            x: node.transform?.x ?? 48,
            y: node.transform?.y ?? 24 + rootIndex * 96,
          }
        : { x: 0, y: 0 },
      fields: data ? { ...data } : {},
      pockets,
    });
    const east = node.e ?? node.right;
    const south = node.s ?? node.below;
    if (east) {
      walk(east, false);
      edges.push({
        id: createId(),
        from: { nodeId: node.id, portId: "right" },
        to: { nodeId: east.id, portId: "left" },
        presentation: "flush",
      });
    }
    if (south) {
      walk(south, false);
      edges.push({
        id: createId(),
        from: { nodeId: node.id, portId: "bottom" },
        to: { nodeId: south.id, portId: "top" },
        presentation: "flush",
      });
    }
  };
  for (const root of roots) {
    walk(root, true);
    rootIndex += 1;
  }
  return { nodes, edges };
}

function belowChainIds(node: EncapsulatedNode): string[] {
  const ids = [node.id];
  let cursor: EncapsulatedNode | undefined = node.s ?? node.below;
  while (cursor) {
    ids.push(cursor.id);
    cursor = cursor.s ?? cursor.below;
  }
  return ids;
}

function uniqueIds(ids: string[]): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const id of ids) {
    if (seen.has(id)) continue;
    seen.add(id);
    out.push(id);
  }
  return out;
}
