import { GEOM, notchCenterX, puzzleCenterY } from "./geom";
import { NO_JOINS, type EdgeJoins } from "./joins";
import { alignChildOrigin } from "./align";
import type { DocumentState } from "../model/document";
import type { NodeRecord, Vec2 } from "../model/types";
import { portOf, type Registry } from "../registry";
import type { GlyphDefinition, PortDefinition, PortSide } from "../registry/types";

export interface WorldPort {
  id: string;
  kind: PortDefinition["kind"];
  direction: PortDefinition["direction"];
  side: PortSide;
  x: number;
  y: number;
  label?: string;
  local: { x: number; y: number };
  edgeA: Vec2;
  edgeB: Vec2;
}

export interface WorldPocket {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  stub: boolean;
  children: string[];
  slots: WorldSlot[];
}

export interface WorldSlot {
  pocketId: string;
  index: number;
  x: number;
  y: number;
  width: number;
}

export interface WorldNode {
  id: string;
  type: string;
  x: number;
  y: number;
  width: number;
  height: number;
  stackHeight: number;
  rowHeight: number;
  label: string;
  color: GlyphDefinition["color"];
  glyph: GlyphDefinition["glyph"];
  ports: WorldPort[];
  pockets: WorldPocket[];
  pocketHeights: number[];
  midHeight: number;
  hatHeight: number;
  footHeight: number;
  hatOnly: boolean;
  wrapped: boolean;
  joins: EdgeJoins;
  fields: Record<string, string>;
  pocketLabels: string[];
  role?: "block";
}

export interface Layout {
  nodes: Map<string, WorldNode>;
  order: string[];
}

export function layoutDocument(state: DocumentState, registry: Registry): Layout {
  const cache = new Map<string, WorldNode>();
  const measuring = new Set<string>();

  const measure = (id: string): WorldNode => {
    const hit = cache.get(id);
    if (hit) return hit;
    if (measuring.has(id)) throw new Error(`Layout cycle at node ${id}`);
    measuring.add(id);
    const node = state.getNode(id);
    const def = registry.get(node.type);
    const world = measureRect(state, registry, id, def, measure);
    cache.set(id, world);
    measuring.delete(id);
    return world;
  };

  for (const id of state.nodes.keys()) measure(id);
  applyRowBottoms(state, registry, cache);

  const placed = new Set<string>();
  const place = (id: string, x: number, y: number) => {
    const world = cache.get(id);
    if (!world || placed.has(id)) return;
    world.x = x;
    world.y = y;
    placed.add(id);

    let pocketTop = world.hatHeight;
    if (!world.wrapped) {
      world.pockets = world.pockets.map((pocket) => ({
        ...pocket,
        x: x + GEOM.centerInsetX,
        y: y + GEOM.centerInsetY,
        width: Math.max(world.width - GEOM.centerInsetX * 2, 48),
        height: Math.max(world.height - GEOM.centerInsetY * 2, 16),
        slots: [
          {
            pocketId: pocket.id,
            index: 0,
            x: x + GEOM.centerInsetX,
            y: y + GEOM.centerInsetY,
            width: Math.max(world.width - GEOM.centerInsetX * 2, 48),
          },
        ],
      }));
    } else {
    world.pockets = world.pockets.map((pocket, i) => {
      const next: WorldPocket = { ...pocket, x: x + GEOM.inner, y: y + pocketTop, slots: [] };
      const children = state.getNode(id).pockets[pocket.id] ?? [];
      next.slots.push({
        pocketId: pocket.id,
        index: 0,
        x: x + GEOM.inner,
        y: y + pocketTop,
        width: Math.max(world.width - GEOM.inner - 8, 80),
      });
      let cy = pocketTop;
      const seen = new Set<string>();
      children.forEach((childId) => {
        if (seen.has(childId)) return;
        const child = cache.get(childId);
        if (!child) return;
        place(childId, x + GEOM.inner, y + cy);
        for (const mid of state.stackChain(childId, registry)) seen.add(mid);
        cy += child.stackHeight - GEOM.stackOverlap;
      });
      children.forEach((childId, index) => {
        const child = cache.get(childId);
        if (!child) return;
        next.slots.push({
          pocketId: pocket.id,
          index: index + 1,
          x: x + GEOM.inner,
          y: child.y + child.height,
          width: Math.max(world.width - GEOM.inner - 8, 80),
        });
      });
      pocketTop += pocket.height;
      if (i < world.pockets.length - 1) pocketTop += world.midHeight;
      return next;
    });
    }

    world.ports = world.ports.map((port) => ({
      ...port,
      x: x + port.local.x,
      y: y + port.local.y,
    }));

    for (const edge of state.outgoingFlush(id, registry)) {
      const target = cache.get(edge.to.nodeId);
      const from = world.ports.find((p) => p.id === edge.from.portId);
      const toPort = target?.ports.find((p) => p.id === edge.to.portId);
      if (!target || !from || !toPort) continue;
      const origin = alignChildOrigin({ x: world.x, y: world.y }, from, toPort);
      place(edge.to.nodeId, origin.x, origin.y);
    }
  };

  for (const id of state.nodes.keys()) {
    if (state.isFree(id, registry)) {
      const node = state.getNode(id);
      place(id, node.transform.x, node.transform.y);
    }
  }
  for (const id of state.nodes.keys()) {
    if (!placed.has(id)) {
      const node = state.getNode(id);
      place(id, node.transform.x, node.transform.y);
    }
  }

  const order: string[] = [];
  const visit = (id: string) => {
    if (order.includes(id)) return;
    order.push(id);
    const world = cache.get(id);
    if (!world) return;
    for (const pocket of world.pockets) {
      for (const childId of pocket.children) visit(childId);
    }
    for (const edge of state.outgoingFlush(id, registry)) visit(edge.to.nodeId);
  };
  for (const id of state.nodes.keys()) {
    if (state.isFree(id, registry)) visit(id);
  }
  for (const id of state.nodes.keys()) visit(id);

  return { nodes: cache, order };
}

function measureRect(
  state: DocumentState,
  registry: Registry,
  id: string,
  def: GlyphDefinition,
  measure: (id: string) => WorldNode,
): WorldNode {
  const node = state.getNode(id);
  const glyph =
    def.glyph.kind === "rounded-rect"
      ? def.glyph
      : { kind: "rounded-rect" as const, width: 176, hatHeight: 40, footHeight: 20 };
  const hat = glyph.hatHeight;
  const hatOnly = !state.isHorizontalHead(id, registry);
  const alwaysFrame = def.alwaysFrame === true;
  const pocketIds = state.pocketIds(id, registry);
  const filled = pocketIds.some((pid) => (node.pockets[pid] ?? []).length > 0);
  const wrapped = !hatOnly && (alwaysFrame || filled);
  const fitted = estimateContentWidth(node, def);

  if (hatOnly || !wrapped) {
    const width = Math.max(glyph.width, fitted);
    const height = hat;
    const pockets: WorldPocket[] =
      hatOnly || def.pockets.length === 0
        ? []
        : pocketIds.map((pid) => ({
            id: pid,
            x: 0,
            y: 0,
            width: width - GEOM.centerInsetX * 2,
            height: height - GEOM.centerInsetY * 2,
            stub: true,
            children: [],
            slots: [],
          }));
    return finishWorld(state, registry, id, def, glyph, {
      width,
      height,
      hat,
      foot: 0,
      mid: 0,
      pockets,
      pocketHeights: [],
      hatOnly,
      wrapped: false,
      measure,
    });
  }

  const foot = 0;
  const mid = glyph.midHeight ?? 28;
  let width = Math.max(glyph.width, fitted);
  const pockets: WorldPocket[] = [];
  const pocketHeights: number[] = [];

  for (const pocketId of pocketIds) {
    const children = node.pockets[pocketId] ?? [];
    let height: number = GEOM.stub;
    let stub = true;
    if (children.length > 0) {
      stub = false;
      height = 0;
      const seen = new Set<string>();
      children.forEach((childId) => {
        if (seen.has(childId)) return;
        const child = measure(childId);
        height += height > 0 ? child.stackHeight - GEOM.stackOverlap : child.stackHeight;
        width = Math.max(width, GEOM.inner + occupiedWidth(state, registry, childId, measure) + GEOM.nestRight);
        for (const midId of state.stackChain(childId, registry)) seen.add(midId);
      });
      height = Math.max(height + GEOM.nestPad, GEOM.stub);
    }
    pocketHeights.push(height);
    pockets.push({
      id: pocketId,
      x: 0,
      y: 0,
      width: width - GEOM.inner - 8,
      height,
      stub,
      children: [...children],
      slots: [],
    });
  }

  for (const pocket of pockets) pocket.width = width - GEOM.inner - 8;

  let height = hat;
  if (pocketHeights.length === 0) height = hat + GEOM.stub;
  else {
    pocketHeights.forEach((ph, i) => {
      height += ph;
      if (i < pocketHeights.length - 1) height += mid;
    });
  }

  return finishWorld(state, registry, id, def, glyph, {
    width,
    height,
    hat,
    foot,
    mid: pockets.length > 1 ? mid : 0,
    pockets,
    pocketHeights,
    hatOnly: false,
    wrapped: true,
    measure,
  });
}

function finishWorld(
  state: DocumentState,
  registry: Registry,
  id: string,
  def: GlyphDefinition,
  glyph: GlyphDefinition["glyph"],
  box: {
    width: number;
    height: number;
    hat: number;
    foot: number;
    mid: number;
    pockets: WorldPocket[];
    pocketHeights: number[];
    hatOnly: boolean;
    wrapped: boolean;
    measure?: (id: string) => WorldNode;
  },
): WorldNode {
  const node = state.getNode(id);
  let stackHeight = box.height;
  const next = state.outgoingStack(id, registry);
  if (next && box.measure) stackHeight += box.measure(next.to.nodeId).stackHeight - GEOM.stackOverlap;

  return {
    id,
    type: def.type,
    x: 0,
    y: 0,
    width: box.width,
    height: box.height,
    stackHeight,
    rowHeight: box.height,
    label: node.label ?? def.label,
    color: def.color,
    glyph,
    ports: def.ports.map((p) => ({
      id: p.id,
      kind: p.kind,
      direction: p.direction,
      side: p.side,
      x: 0,
      y: 0,
      label: p.label,
      local: localRectPort(box.width, box.height, box.hat, p.side),
      edgeA: sideEdge(box.width, box.height, p.side, box.hat).a,
      edgeB: sideEdge(box.width, box.height, p.side, box.hat).b,
    })),
    pockets: box.pockets,
    pocketHeights: box.pocketHeights,
    midHeight: box.mid,
    hatHeight: box.hat,
    footHeight: box.foot,
    hatOnly: box.hatOnly,
    wrapped: box.wrapped,
    joins: computeJoins(state, registry, id),
    fields: { ...(node.fields ?? {}) },
    pocketLabels: box.pockets.map((p) => pocketLabel(def, p.id)),
    role: "block",
  };
}

function computeJoins(state: DocumentState, registry: Registry, id: string): EdgeJoins {
  return {
    top: state.incomingFlush(id, registry, "vertical") !== null,
    bottom: state.outgoingFlush(id, registry, "vertical").length > 0,
    left: state.incomingFlush(id, registry, "horizontal") !== null,
    right: state.outgoingFlush(id, registry, "horizontal").length > 0,
  };
}

function pocketLabel(def: GlyphDefinition, pocketId: string): string {
  const exact = def.pockets.find((p) => p.id === pocketId);
  if (exact?.label) return exact.label;
  const repeatable = def.pockets.find(
    (p) => p.repeatable && (pocketId === p.id || pocketId.startsWith(`${p.id}-`)),
  );
  return repeatable?.label ?? pocketId;
}

function estimateContentWidth(node: NodeRecord, def: GlyphDefinition): number {
  const glyphWidth = def.glyph.kind === "rounded-rect" ? def.glyph.width : 168;
  const keyword = node.fields?.keyword ?? "";
  const text = node.fields?.text ?? node.fields?.condition ?? "";
  if (!keyword && !text && !def.badge) return glyphWidth;
  const buttons = (def.controls ?? []).reduce((sum, control) => {
    if (control.kind !== "button") return sum;
    return sum + (control.width ?? 0) + 8;
  }, 0);
  const badge = def.badge ? Math.max(56, def.badge.length * 8.2) : 0;
  const keywordW = keyword ? 76 : 0;
  const delim = /^[,;:.!?]/.test(text) ? 22 : 0;
  return Math.max(glyphWidth, 24 + badge + keywordW + delim + text.length * 9 + buttons);
}

function occupiedWidth(
  state: DocumentState,
  registry: Registry,
  id: string,
  measure: (id: string) => WorldNode,
): number {
  let total = 0;
  let cursor: string | null = id;
  const seen = new Set<string>();
  while (cursor && !seen.has(cursor)) {
    seen.add(cursor);
    total += measure(cursor).width;
    cursor = state.horizontalNext(cursor, registry);
  }
  return total;
}

function applyRowBottoms(state: DocumentState, registry: Registry, cache: Map<string, WorldNode>): void {
  for (const [id, world] of cache) {
    const headId = state.horizontalHead(id, registry);
    const head = cache.get(headId);
    if (!head) continue;
    world.rowHeight = head.height;
    if (headId === id) continue;
    const bottom = world.ports.find((p) => p.id === "bottom");
    if (bottom) {
      bottom.local.y = head.height;
      const edge = sideEdge(world.width, head.height, "bottom", head.height);
      bottom.edgeA = edge.a;
      bottom.edgeB = edge.b;
    }
    const next = state.outgoingStack(id, registry);
    const nextWorld = next ? cache.get(next.to.nodeId) : undefined;
    world.stackHeight = head.height + (nextWorld ? nextWorld.stackHeight - GEOM.stackOverlap : 0);
  }
}

function localRectPort(width: number, height: number, hat: number, side: PortSide): { x: number; y: number } {
  switch (side) {
    case "top":
      return { x: notchCenterX(), y: 0 };
    case "bottom":
      return { x: notchCenterX(), y: height };
    case "left":
      return { x: 0, y: puzzleCenterY(hat) };
    case "right":
      return { x: width, y: puzzleCenterY(hat) };
  }
}

function sideEdge(width: number, height: number, side: PortSide, hat = 46): { a: Vec2; b: Vec2 } {
  const edgeH = Math.min(height, Math.max(hat, 40));
  switch (side) {
    case "top":
      return { a: { x: 0, y: 0 }, b: { x: width, y: 0 } };
    case "right":
      return { a: { x: width, y: 0 }, b: { x: width, y: edgeH } };
    case "bottom":
      return { a: { x: width, y: height }, b: { x: 0, y: height } };
    case "left":
      return { a: { x: 0, y: edgeH }, b: { x: 0, y: 0 } };
  }
}

export { GEOM };
export { portOf };
export { NO_JOINS };
export type { EdgeJoins };
