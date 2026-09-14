import { canConnect, canConnectPorts, canNestInto, type Registry } from "../registry";
import type { DocumentState } from "../model/document";
import type { Layout, WorldNode, WorldPort, WorldPocket } from "../layout";
import { GEOM } from "../layout/geom";
import { alignChildOrigin, alignParentOrigin, isHorizontalPair, isVerticalPair } from "../layout/align";
import {
  blockFaces,
  classifyPose,
  nestOutSegments,
  nestPocketBox,
  sitsBetweenHorizontal,
  sitsBetweenVertical,
  type Face,
  type PoseHit,
} from "./faces";
import type { FaceSegment, NestSnap, PortSnap, SnapTarget } from "./types";

export { blockFaces, classifyPose, nestPocketBox, nestOutSegments, POSE, sitsBetweenHorizontal, sitsBetweenVertical } from "./faces";
export type { BlockFaces, Face, FaceId, PoseBox, PoseHit, PoseKind } from "./faces";
export { applySnap, previewSnap, realizeSnap } from "./preview";
export type { SnapPreview } from "./preview";
export type { FaceSegment, NestSnap, PortSnap, SnapTarget } from "./types";

export const SNAP = {
  nest: 48,
  stack: 52,
  side: 56,
} as const;

export function pickSnapGhost(
  state: DocumentState,
  registry: Registry,
  layout: Layout,
  ghost: WorldNode,
  unit: Set<string>,
  _extra: WorldNode[] = [],
  pointer?: { x: number; y: number },
): SnapTarget | null {
  const candidates: SnapTarget[] = [];
  for (const snap of chainInsertSnaps(state, registry, layout, ghost, unit)) {
    candidates.push(snap);
  }
  for (const world of layout.nodes.values()) {
    if (unit.has(world.id)) continue;
    const parent = state.nodes.get(world.id);
    if (!parent) continue;
    const pose = classifyPose(ghost, world);
    if (!pose) continue;
    if (pose.kind === "nest") {
      const nest = nestSnapFor(state, registry, world, ghost, pose, pointer);
      if (nest) candidates.push(nest);
      continue;
    }
    const port = portSnapFor(state, registry, ghost, world, pose);
    if (port) candidates.push(port);
  }
  return pickPreferred(state, registry, candidates);
}

/**
 * Prefer splitting an existing N/S or E/W link over nest-into-parent.
 * Insert-after (onto the upper/left neighbor's out face) is the snap drop uses
 * so the old below/trailer hangs off the dragged cluster and neighbors move.
 */
function pickPreferred(state: DocumentState, registry: Registry, candidates: SnapTarget[]): SnapTarget | null {
  const nests = candidates.filter((c): c is NestSnap => c.kind === "nest");
  const ports = candidates.filter((c): c is PortSnap => c.kind !== "nest");
  const chainSplit = ports.filter((s) => {
    if (s.to.portId === "bottom") return Boolean(state.outgoingStack(s.to.nodeId, registry));
    if (s.to.portId === "right") return Boolean(state.horizontalNext(s.to.nodeId, registry));
    return false;
  });
  const bestSplit = minBy(chainSplit, (s) => s.score);
  if (bestSplit) return bestSplit;
  const stacksOutsideNest = ports.filter(
    (s) => s.kind === "stack" && !nests.some((n) => inPocketTree(state, n.parentId, s.to.nodeId)),
  );
  const bestStack = minBy(stacksOutsideNest, (s) => s.score);
  if (bestStack) return bestStack;
  const bestNest = minBy(nests, (s) => s.score);
  if (bestNest) return bestNest;
  return minBy(candidates, (s) => s.score);
}

function inPocketTree(state: DocumentState, ancestorId: string, nodeId: string): boolean {
  let cursor: string | null = nodeId;
  const guard = new Set<string>();
  while (cursor && !guard.has(cursor)) {
    guard.add(cursor);
    const parent = state.parentOf(cursor);
    if (!parent) return false;
    if (parent.parentId === ancestorId) return true;
    cursor = parent.parentId;
  }
  return false;
}

/** Offer a snap onto the outgoing face of a node that already has a below/right neighbor. */
function chainInsertSnaps(
  state: DocumentState,
  registry: Registry,
  layout: Layout,
  ghost: WorldNode,
  unit: Set<string>,
): PortSnap[] {
  const out: PortSnap[] = [];
  for (const world of layout.nodes.values()) {
    if (unit.has(world.id) || !state.nodes.get(world.id)) continue;
    const belowId = state.outgoingStack(world.id, registry)?.to.nodeId;
    if (belowId && !unit.has(belowId)) {
      const lower = layout.nodes.get(belowId);
      if (lower && sitsBetweenVertical(ghost, world, lower)) {
        const join = (world.y + world.height + lower.y) / 2;
        const port = portSnapFor(state, registry, ghost, world, {
          kind: "stack",
          score: Math.abs(ghost.y + ghost.hatHeight / 2 - join) + Math.abs(ghost.x - world.x) * 0.35,
          face: "s",
          fromFace: "n",
          direction: "south",
        });
        if (port) out.push(port);
      }
    }
    const rightId = state.horizontalNext(world.id, registry);
    if (rightId && !unit.has(rightId)) {
      const right = layout.nodes.get(rightId);
      if (right && sitsBetweenHorizontal(ghost, world, right)) {
        const join = (world.x + world.width + right.x) / 2;
        const port = portSnapFor(state, registry, ghost, world, {
          kind: "horizontal",
          score: Math.abs(ghost.x + ghost.width / 2 - join) + Math.abs(ghost.y - world.y) * 0.35,
          face: "e",
          fromFace: "w",
          direction: "east",
        });
        if (port) out.push(port);
      }
    }
  }
  return out;
}

export function pickSnap(
  state: DocumentState,
  registry: Registry,
  layout: Layout,
  draggedId: string,
  dragOrigin: { x: number; y: number },
  pointer: { x: number; y: number },
): SnapTarget | null {
  const origin = layout.nodes.get(draggedId);
  if (!origin) return null;
  const unit = new Set(state.dragUnit(draggedId, registry));
  const dx = pointer.x - dragOrigin.x;
  const dy = pointer.y - dragOrigin.y;
  const members = [...unit]
    .map((id) => layout.nodes.get(id))
    .filter((n): n is WorldNode => Boolean(n))
    .map((n) => offsetNode(n, dx, dy));
  const ghost = members.find((n) => n.id === draggedId) ?? offsetNode(origin, dx, dy);
  return pickSnapGhost(
    state,
    registry,
    layout,
    ghost,
    unit,
    members.filter((n) => n.id !== draggedId),
    pointer,
  );
}

function nestSnapFor(
  state: DocumentState,
  registry: Registry,
  world: WorldNode,
  ghost: WorldNode,
  pose: PoseHit,
  pointer?: { x: number; y: number },
): NestSnap | null {
  const pockets = world.pockets.filter((pocket) =>
    canNestInto(state, registry, world.id, pocket.id, ghost.type, ghost.id),
  );
  if (pockets.length === 0) return null;
  const probeY = pointer?.y ?? ghost.y + ghost.hatHeight / 2;
  const pocket = closestPocket(pockets, probeY);
  const faces = blockFaces(world);
  const box = nestPocketBox(world);
  const segments = nestHighlightSegments(faces);
  return {
    kind: "nest",
    score: pose.score,
    parentId: world.id,
    pocketId: pocket.id,
    index: slotIndex(pocket, { x: ghost.x, y: probeY }),
    x: world.wrapped ? pocket.x : world.x + GEOM.inner,
    y: world.wrapped ? pocket.y : world.y + world.hatHeight,
    bar: { x: pocket.x, y: pocket.y, width: pocket.width },
    highlight: box,
    face: pose.face,
    fromFace: pose.fromFace,
    segments,
  };
}

function portSnapFor(
  state: DocumentState,
  registry: Registry,
  ghost: WorldNode,
  world: WorldNode,
  pose: PoseHit,
): PortSnap | null {
  const pair = posePortPair(ghost, world, pose.direction);
  if (!pair) return null;
  const [from, to] = pair;
  if (!canConnectPorts(from, to)) return null;
  const fromNode = state.nodes.get(ghost.id);
  const toNode = state.nodes.get(world.id);
  if (!canConnect(registry, ghost.type, from.id, world.type, to.id, fromNode, toNode)) return null;
  if (!isVerticalPair(from.side, to.side) && !isHorizontalPair(from.side, to.side)) return null;
  if (isHorizontalPair(from.side, to.side)) {
    const trailerId = from.side === "left" ? ghost.id : world.id;
    if (isParentLike(state, trailerId)) return null;
  }
  if (isVerticalPair(from.side, to.side)) {
    if (!state.isHorizontalHead(world.id, registry)) return null;
    if (state.nodes.has(ghost.id) && !state.isHorizontalHead(ghost.id, registry)) return null;
  }
  const aligned = alignSnapPose(ghost, from, world, to);
  const faces = blockFaces(world);
  const face = faces[pose.face === "n" ? "n" : pose.face === "s" ? "s" : pose.face === "e" ? "e" : "w"];
  return {
    kind: isVerticalPair(from.side, to.side) ? "stack" : "value",
    score: pose.score,
    threshold: isVerticalPair(from.side, to.side) ? SNAP.stack : SNAP.side,
    from: { nodeId: ghost.id, portId: from.id },
    to: { nodeId: world.id, portId: to.id },
    fromPt: { x: from.x, y: from.y },
    toPt: { x: to.x, y: to.y },
    x: aligned.x,
    y: aligned.y,
    highlightNodeId: world.id,
    highlightPortId: to.id,
    face: pose.face,
    fromFace: pose.fromFace,
    segments: [toSegment(face)],
  };
}

function posePortPair(
  ghost: WorldNode,
  world: WorldNode,
  direction: PoseHit["direction"],
): [WorldPort, WorldPort] | null {
  const pick = (node: WorldNode, side: WorldPort["side"]) => node.ports.find((p) => p.side === side);
  if (direction === "south") {
    const from = pick(ghost, "top");
    const to = pick(world, "bottom");
    if (from && to) return [from, to];
  }
  if (direction === "north") {
    const from = pick(ghost, "bottom");
    const to = pick(world, "top");
    if (from && to) return [from, to];
  }
  if (direction === "east") {
    const from = pick(ghost, "left");
    const to = pick(world, "right");
    if (from && to) return [from, to];
  }
  if (direction === "west") {
    const from = pick(ghost, "right");
    const to = pick(world, "left");
    if (from && to) return [from, to];
  }
  return null;
}

function nestHighlightSegments(faces: ReturnType<typeof blockFaces>): FaceSegment[] {
  const segs = nestOutSegments(faces).map(toSegment);
  const drawn = segs.filter((seg) => Math.hypot(seg.b.x - seg.a.x, seg.b.y - seg.a.y) > 0.5);
  if (drawn.length > 0) return drawn;
  const mouth = faces.expander.a;
  return [{ a: mouth, b: { x: mouth.x, y: mouth.y + 12 }, role: "nest-out" }];
}

function toSegment(face: Face): FaceSegment {
  return { a: { ...face.a }, b: { ...face.b }, role: face.id };
}

function closestPocket(pockets: WorldPocket[], probeY: number): WorldPocket {
  let best = pockets[0];
  let bestDist = Infinity;
  for (const pocket of pockets) {
    const mid = pocket.y + pocket.height / 2;
    const dist = probeY >= pocket.y && probeY <= pocket.y + pocket.height ? 0 : Math.abs(probeY - mid);
    if (dist < bestDist) {
      best = pocket;
      bestDist = dist;
    }
  }
  return best;
}

function alignSnapPose(
  dragged: WorldNode,
  from: WorldPort,
  target: WorldNode,
  to: WorldPort,
): { x: number; y: number } {
  if (from.direction === "in") {
    return alignChildOrigin({ x: target.x, y: target.y }, to, from);
  }
  return alignParentOrigin({ x: target.x, y: target.y }, from, to);
}

function offsetNode(node: WorldNode, dx: number, dy: number): WorldNode {
  return {
    ...node,
    x: node.x + dx,
    y: node.y + dy,
    ports: node.ports.map((p) => ({ ...p, x: p.x + dx, y: p.y + dy })),
    pockets: node.pockets.map((p) => ({
      ...p,
      x: p.x + dx,
      y: p.y + dy,
      slots: p.slots.map((s) => ({ ...s, x: s.x + dx, y: s.y + dy })),
    })),
  };
}

function slotIndex(pocket: WorldPocket, probe: { x: number; y: number }): number {
  if (pocket.slots.length === 0) return 0;
  let best = 0;
  let bestDist = Infinity;
  for (const slot of pocket.slots) {
    const dist = Math.abs(probe.y - slot.y);
    if (dist < bestDist) {
      bestDist = dist;
      best = slot.index;
    }
  }
  return best;
}

function isParentLike(state: DocumentState, id: string): boolean {
  if (state.nodes.has(id)) return state.isParent(id);
  return false;
}

function minBy<T>(items: T[], score: (item: T) => number): T | null {
  let best: T | null = null;
  let bestScore = Infinity;
  for (const item of items) {
    const s = score(item);
    if (s < bestScore) {
      best = item;
      bestScore = s;
    }
  }
  return best;
}
