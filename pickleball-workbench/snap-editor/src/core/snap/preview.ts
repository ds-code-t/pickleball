import { DocumentState, type NodeInit } from "../model/document";
import { layoutDocument, type Layout } from "../layout";
import { NO_JOINS } from "../layout/joins";
import type { Registry } from "../registry";
import type { Vec2 } from "../model/types";
import type { SnapTarget } from "./types";

export interface SnapPreview {
  state: DocumentState;
  layout: Layout;
  movingIds: string[];
  createdId?: string;
}

/** Apply a snap the same way a drop does. Mutates `state`. */
export function applySnap(
  state: DocumentState,
  registry: Registry,
  nodeId: string,
  snap: SnapTarget,
): boolean {
  if (snap.kind === "nest") {
    state.setTransform(nodeId, { x: 0, y: 0 });
    return state.insertInPocket(snap.parentId, snap.pocketId, nodeId, snap.index, registry);
  }
  const from = state.nodes.has(snap.from.nodeId) ? snap.from : { nodeId, portId: snap.from.portId };
  const edge = state.connectPorts(registry, from, snap.to, "flush");
  if (!edge) return false;
  state.adoptStackIntoPocket(edge.from, edge.to, registry);
  if (edge.from.nodeId === nodeId) {
    const pose = Math.hypot(snap.x, snap.y) > 2 ? { x: snap.x, y: snap.y } : state.getNode(nodeId).transform;
    state.setTransform(nodeId, pose);
  } else if (edge.to.nodeId === nodeId) {
    state.setTransform(nodeId, { x: 0, y: 0 });
  }
  return true;
}

/** Pose the node, snap it, then canonicalize free transforms. Same path drop uses. */
export function realizeSnap(
  state: DocumentState,
  registry: Registry,
  nodeId: string,
  snap: SnapTarget,
  pose: Vec2,
): boolean {
  state.setTransform(nodeId, pose);
  const ok = applySnap(state, registry, nodeId, snap);
  state.canonicalizeTransforms(registry);
  return ok;
}

/**
 * Tentative copy of the document after the given snap. Does not mutate the
 * live state. Layout matches a real drop; new join tabs stay off until commit.
 */
export function previewSnap(
  live: DocumentState,
  registry: Registry,
  snap: SnapTarget,
  moving: { nodeId: string } | { type: string; init?: NodeInit },
): SnapPreview | null {
  const state = DocumentState.fromDocument(live.toDocument(), registry);
  let nodeId: string;
  let createdId: string | undefined;
  /** Held cluster before the snap — displaced neighbors must stay solid. */
  let heldIds: string[];
  const pose = { x: snap.x, y: snap.y };
  if ("nodeId" in moving) {
    nodeId = moving.nodeId;
    if (!state.nodes.has(nodeId)) return null;
    heldIds = live.dragUnit(nodeId, registry);
    state.liftCluster(nodeId, registry);
  } else {
    const node = state.addNode(moving.type, pose, registry, moving.init);
    nodeId = node.id;
    createdId = node.id;
    heldIds = [nodeId];
  }
  if (!realizeSnap(state, registry, nodeId, snap, pose)) return null;
  const layout = layoutDocument(state, registry);
  keepExistingJoins(layout, live, state, registry);
  return {
    state,
    layout,
    movingIds: heldIds,
    createdId,
  };
}

function keepExistingJoins(
  layout: Layout,
  original: DocumentState,
  preview: DocumentState,
  registry: Registry,
): void {
  for (const world of layout.nodes.values()) {
    if (!original.nodes.has(world.id)) {
      world.joins = { ...NO_JOINS };
      continue;
    }
    world.joins = {
      top: samePartner(original, preview, registry, world.id, "top"),
      bottom: samePartner(original, preview, registry, world.id, "bottom"),
      left: samePartner(original, preview, registry, world.id, "left"),
      right: samePartner(original, preview, registry, world.id, "right"),
    };
  }
}

function samePartner(
  original: DocumentState,
  preview: DocumentState,
  registry: Registry,
  id: string,
  side: "top" | "bottom" | "left" | "right",
): boolean {
  const before = facePartner(original, registry, id, side);
  const after = facePartner(preview, registry, id, side);
  return before !== null && before === after;
}

function facePartner(
  state: DocumentState,
  registry: Registry,
  id: string,
  side: "top" | "bottom" | "left" | "right",
): string | null {
  if (side === "top") return state.incomingFlush(id, registry, "vertical")?.from.nodeId ?? null;
  if (side === "bottom") return state.outgoingFlush(id, registry, "vertical")[0]?.to.nodeId ?? null;
  if (side === "left") return state.incomingFlush(id, registry, "horizontal")?.from.nodeId ?? null;
  return state.outgoingFlush(id, registry, "horizontal")[0]?.to.nodeId ?? null;
}
