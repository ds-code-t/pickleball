import { describe, expect, it } from "vitest";
import { DocumentState } from "../src/core/model/document";
import { layoutDocument, type Layout, type WorldNode } from "../src/core/layout";
import { pickSnapGhost, previewSnap, realizeSnap, type SnapTarget } from "../src/core/snap";
import { prototypeBadge } from "../src/core/render/view";
import { GEOM } from "../src/core/layout/geom";
import type { Registry } from "../src/core/registry";
import { BLOCK_TYPE, CONTROL_TYPE, typed } from "./harness";

describe("hover preview is the real snap layout", () => {
  it("stack-insert: preview matches drop topology, and the block below slides down", () => {
    const state = DocumentState.empty();
    const top = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const below = state.addNode(BLOCK_TYPE, { x: 40, y: 200 }, typed);
    const moving = state.addNode(BLOCK_TYPE, { x: 420, y: 320 }, typed);
    state.connectPorts(typed, { nodeId: top.id, portId: "bottom" }, { nodeId: below.id, portId: "top" });
    const beforeY = layoutDocument(state, typed).nodes.get(below.id)!.y;
    const beforeTopo = topology(state, typed);

    const { preview, dropped, commit, snap } = hoverAndDrop(state, moving.id, (layout) => {
      const topWorld = layout.nodes.get(top.id)!;
      const movingWorld = layout.nodes.get(moving.id)!;
      return offsetTo(movingWorld, topWorld.x + 4, topWorld.y + topWorld.height + 10);
    });

    expect(snap && snap.kind !== "nest" && snap.face).toBe("s");
    expectLayoutGeom(preview.layout, dropped);
    expect(topology(preview.state, typed)).toEqual(topology(commit, typed));
    expect(topology(state, typed)).toEqual(beforeTopo);
    expect(preview.state.outgoingStack(top.id, typed)?.to.nodeId).toBe(moving.id);
    expect(preview.state.outgoingStack(moving.id, typed)?.to.nodeId).toBe(below.id);
    expect(state.outgoingStack(top.id, typed)?.to.nodeId).toBe(below.id);
    expect(preview.movingIds).toEqual([moving.id]);
    expect(preview.movingIds).not.toContain(below.id);
    const previewBelow = preview.layout.nodes.get(below.id)!;
    expect(previewBelow.y).toBeGreaterThan(beforeY + 20);
    expect(previewBelow.y).toBeCloseTo(dropped.nodes.get(below.id)!.y, 0);
    expectNewJoinsOff(preview.layout, top.id, "bottom");
  });

  it("stack-insert with a grab offset still previews the below block sliding", () => {
    const state = DocumentState.empty();
    const top = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const below = state.addNode(BLOCK_TYPE, { x: 40, y: 200 }, typed);
    const moving = state.addNode(BLOCK_TYPE, { x: 420, y: 320 }, typed);
    state.connectPorts(typed, { nodeId: top.id, portId: "bottom" }, { nodeId: below.id, portId: "top" });
    const beforeY = layoutDocument(state, typed).nodes.get(below.id)!.y;

    const { preview, commit, snap } = hoverAndDrop(state, moving.id, (layout) => {
      const topWorld = layout.nodes.get(top.id)!;
      const movingWorld = layout.nodes.get(moving.id)!;
      return offsetTo(movingWorld, topWorld.x + 56, topWorld.y + topWorld.height + 12);
    });

    expect(snap && snap.kind !== "nest").toBe(true);
    expect(preview.state.outgoingStack(top.id, typed)?.to.nodeId).toBe(moving.id);
    expect(preview.state.outgoingStack(moving.id, typed)?.to.nodeId).toBe(below.id);
    expect(topology(preview.state, typed)).toEqual(topology(commit, typed));
    expect(preview.movingIds).not.toContain(below.id);
    expect(preview.layout.nodes.get(below.id)!.y).toBeGreaterThan(beforeY + 20);
  });

  it("nest-into-empty: preview grows the parent L to the same size as drop", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const moving = state.addNode(BLOCK_TYPE, { x: 400, y: 80 }, typed);
    const emptyH = layoutDocument(state, typed).nodes.get(parent.id)!.height;

    const { preview, dropped, commit, snap } = hoverAndDrop(state, moving.id, (layout) => {
      const parentWorld = layout.nodes.get(parent.id)!;
      const movingWorld = layout.nodes.get(moving.id)!;
      return nestInBody(parentWorld, movingWorld);
    });

    expect(snap?.kind).toBe("nest");
    expectLayoutGeom(preview.layout, dropped);
    expect(topology(preview.state, typed)).toEqual(topology(commit, typed));
    expect(state.parentOf(moving.id)).toBeNull();
    const previewParent = preview.layout.nodes.get(parent.id)!;
    expect(previewParent.wrapped).toBe(true);
    expect(previewParent.height).toBeGreaterThan(emptyH);
    expect(preview.layout.nodes.get(moving.id)!.x).toBeCloseTo(previewParent.x + GEOM.inner, 0);
  });

  it("nest-into-filled: preview shifts nested siblings and matches drop", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(CONTROL_TYPE, { x: 40, y: 20 }, typed);
    const nested = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const moving = state.addNode(BLOCK_TYPE, { x: 520, y: 300 }, typed);
    state.insertInPocket(parent.id, "then", nested.id, 0, typed);

    const { preview, dropped, commit, snap } = hoverAndDrop(state, moving.id, (layout) => {
      const parentWorld = layout.nodes.get(parent.id)!;
      const movingWorld = layout.nodes.get(moving.id)!;
      return offsetTo(movingWorld, parentWorld.x + GEOM.inner + 4, parentWorld.y + parentWorld.hatHeight + 10);
    });

    expect(snap?.kind).toBe("nest");
    expectLayoutGeom(preview.layout, dropped);
    expect(topology(preview.state, typed)).toEqual(topology(commit, typed));
    expect(preview.movingIds).not.toContain(nested.id);
    const previewParent = preview.layout.nodes.get(parent.id)!;
    expect(previewParent.height).toBeCloseTo(dropped.nodes.get(parent.id)!.height, 0);
    expect(preview.layout.nodes.get(nested.id)!.y).toBeCloseTo(dropped.nodes.get(nested.id)!.y, 0);
  });

  it("horizontal insert: preview matches drop topology, and the trailer shifts right", () => {
    const state = DocumentState.empty();
    const head = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const trailer = state.addNode(BLOCK_TYPE, { x: 400, y: 20 }, typed);
    const moving = state.addNode(BLOCK_TYPE, { x: 40, y: 240 }, typed);
    state.connectPorts(typed, { nodeId: head.id, portId: "right" }, { nodeId: trailer.id, portId: "left" });
    const beforeX = layoutDocument(state, typed).nodes.get(trailer.id)!.x;
    const beforeTopo = topology(state, typed);

    const { preview, dropped, commit, snap } = hoverAndDrop(state, moving.id, (layout) => {
      const headWorld = layout.nodes.get(head.id)!;
      const movingWorld = layout.nodes.get(moving.id)!;
      return offsetTo(movingWorld, headWorld.x + headWorld.width + 8, headWorld.y + 3);
    });

    expect(snap && snap.kind !== "nest" && snap.face).toBe("e");
    expectLayoutGeom(preview.layout, dropped);
    expect(topology(preview.state, typed)).toEqual(topology(commit, typed));
    expect(topology(state, typed)).toEqual(beforeTopo);
    expect(preview.state.horizontalNext(head.id, typed)).toBe(moving.id);
    expect(preview.state.horizontalNext(moving.id, typed)).toBe(trailer.id);
    expect(state.horizontalNext(head.id, typed)).toBe(trailer.id);
    expect(preview.movingIds).toEqual([moving.id]);
    expect(preview.movingIds).not.toContain(trailer.id);
    const previewTrailer = preview.layout.nodes.get(trailer.id)!;
    expect(previewTrailer.x).toBeGreaterThan(beforeX + 20);
    expect(previewTrailer.x).toBeCloseTo(dropped.nodes.get(trailer.id)!.x, 0);
    expectNewJoinsOff(preview.layout, head.id, "right");
  });

  it("toolbox create preview inserts into a stack without writing the live document", () => {
    const state = DocumentState.empty();
    const top = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const below = state.addNode(BLOCK_TYPE, { x: 40, y: 200 }, typed);
    state.connectPorts(typed, { nodeId: top.id, portId: "bottom" }, { nodeId: below.id, portId: "top" });
    const before = structuredClone(state.toDocument());
    const layout = layoutDocument(state, typed);
    const topWorld = layout.nodes.get(top.id)!;
    const proto = prototypeBadge(typed, BLOCK_TYPE, topWorld.x + 8, topWorld.y + topWorld.height + 10);
    const snap = pickSnapGhost(state, typed, layout, proto.core, new Set(), [], {
      x: proto.core.x + 24,
      y: proto.core.y + 16,
    });
    expect(snap && snap.kind !== "nest").toBe(true);
    const preview = previewSnap(state, typed, snap!, { type: BLOCK_TYPE });
    expect(preview).toBeTruthy();
    expect(preview!.createdId).toBeTruthy();
    expect(preview!.state.outgoingStack(top.id, typed)?.to.nodeId).toBe(preview!.createdId);
    expect(preview!.state.outgoingStack(preview!.createdId!, typed)?.to.nodeId).toBe(below.id);
    expect(state.toDocument()).toEqual(before);
    expect(preview!.layout.nodes.get(below.id)!.y).toBeGreaterThan(layout.nodes.get(below.id)!.y + 20);
  });

  it("does not write the live document while previewing", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const moving = state.addNode(BLOCK_TYPE, { x: 400, y: 80 }, typed);
    const before = structuredClone(state.toDocument());
    const layout = layoutDocument(state, typed);
    const ghost = nestInBody(layout.nodes.get(parent.id)!, layout.nodes.get(moving.id)!);
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([moving.id]));
    expect(snap?.kind).toBe("nest");
    const preview = previewSnap(state, typed, snap!, { nodeId: moving.id });
    expect(preview).toBeTruthy();
    expect(state.toDocument()).toEqual(before);
    expect(state.parentOf(moving.id)).toBeNull();
  });
});

function hoverAndDrop(
  state: DocumentState,
  movingId: string,
  ghostAt: (layout: Layout) => WorldNode,
): {
  preview: NonNullable<ReturnType<typeof previewSnap>>;
  dropped: Layout;
  commit: DocumentState;
  snap: SnapTarget;
} {
  const before = structuredClone(state.toDocument());
  const layout = layoutDocument(state, typed);
  const ghost = ghostAt(layout);
  const snap = pickSnapGhost(state, typed, layout, ghost, new Set([movingId]));
  expect(snap).toBeTruthy();
  const preview = previewSnap(state, typed, snap!, { nodeId: movingId });
  expect(preview).toBeTruthy();
  expect(state.toDocument()).toEqual(before);

  const commit = DocumentState.fromDocument(before, typed);
  expect(realizeSnap(commit, typed, movingId, snap!, { x: snap!.x, y: snap!.y })).toBe(true);
  const dropped = layoutDocument(commit, typed);
  return { preview: preview!, dropped, commit, snap: snap! };
}

function topology(state: DocumentState, registry: Registry): {
  stack: Record<string, string | null>;
  east: Record<string, string | null>;
  nest: Record<string, Record<string, string[]>>;
} {
  const stack: Record<string, string | null> = {};
  const east: Record<string, string | null> = {};
  const nest: Record<string, Record<string, string[]>> = {};
  for (const id of [...state.nodes.keys()].sort()) {
    stack[id] = state.outgoingStack(id, registry)?.to.nodeId ?? null;
    east[id] = state.horizontalNext(id, registry);
    nest[id] = Object.fromEntries(
      Object.entries(state.getNode(id).pockets).map(([pocket, kids]) => [pocket, [...kids]]),
    );
  }
  return { stack, east, nest };
}

function expectLayoutGeom(preview: Layout, dropped: Layout): void {
  expect([...preview.nodes.keys()].sort()).toEqual([...dropped.nodes.keys()].sort());
  for (const [id, a] of preview.nodes) {
    const b = dropped.nodes.get(id)!;
    expect(a.x, id).toBeCloseTo(b.x, 0);
    expect(a.y, id).toBeCloseTo(b.y, 0);
    expect(a.width, id).toBeCloseTo(b.width, 0);
    expect(a.height, id).toBeCloseTo(b.height, 0);
    expect(a.wrapped, id).toBe(b.wrapped);
  }
}

function expectNewJoinsOff(layout: Layout, id: string, side: "top" | "bottom" | "left" | "right"): void {
  expect(layout.nodes.get(id)!.joins[side]).toBe(false);
}

function nestInBody(parent: WorldNode, child: WorldNode): WorldNode {
  return offsetTo(child, parent.x + GEOM.inner + 28, parent.y + 4);
}

function offsetTo(node: WorldNode, x: number, y: number): WorldNode {
  const dx = x - node.x;
  const dy = y - node.y;
  return {
    ...node,
    x,
    y,
    ports: node.ports.map((p) => ({ ...p, x: p.x + dx, y: p.y + dy })),
    pockets: node.pockets.map((p) => ({
      ...p,
      x: p.x + dx,
      y: p.y + dy,
      slots: p.slots.map((s) => ({ ...s, x: s.x + dx, y: s.y + dy })),
    })),
  };
}
