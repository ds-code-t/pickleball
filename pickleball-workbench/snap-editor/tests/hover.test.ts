import { describe, expect, it } from "vitest";
import { followPointer } from "../src/core/drag";
import { DocumentState } from "../src/core/model/document";
import { layoutDocument } from "../src/core/layout";
import { pickSnapGhost } from "../src/core/snap";
import { GEOM } from "../src/core/layout/geom";
import type { WorldNode } from "../src/core/layout";
import { BLOCK_TYPE, core } from "./harness";

describe("hover preview does not commit; drop does", () => {
  it("pointer follow stays on the pointer instead of the snap pose", () => {
    const pointer = { x: 220, y: 96 };
    const grab = { x: 18, y: 12 };
    const display = followPointer(pointer, grab);
    expect(display).toEqual({ x: 202, y: 84 });
    const snapPose = { x: 40, y: 60 };
    expect(display).not.toEqual(snapPose);
  });

  it("finding a hover snap does not mutate node positions or edges", () => {
    const state = DocumentState.empty();
    const top = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, core);
    const moving = state.addNode(BLOCK_TYPE, { x: 200, y: 200 }, core);
    const before = structuredClone(state.toDocument());
    const layout = layoutDocument(state, core);
    const topWorld = layout.nodes.get(top.id)!;
    const movingWorld = layout.nodes.get(moving.id)!;
    const bottom = topWorld.ports.find((p) => p.id === "bottom")!;
    const ghost = offsetTo(movingWorld, bottom.x - movingWorld.ports.find((p) => p.id === "top")!.local.x, bottom.y - 6);
    const snap = pickSnapGhost(state, core, layout, ghost, new Set([moving.id]));
    expect(snap).toBeTruthy();
    expect(snap && snap.kind !== "nest").toBe(true);
    expect(state.toDocument()).toEqual(before);
    expect(state.getNode(top.id).transform).toEqual({ x: 40, y: 20 });
    expect(state.getNode(moving.id).transform).toEqual({ x: 200, y: 200 });
    expect(state.outgoingStack(top.id, core)).toBeNull();
  });

  it("drop commits a vertical snap", () => {
    const state = DocumentState.empty();
    const top = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, core);
    const moving = state.addNode(BLOCK_TYPE, { x: 200, y: 200 }, core);
    const layout = layoutDocument(state, core);
    const topWorld = layout.nodes.get(top.id)!;
    const movingWorld = layout.nodes.get(moving.id)!;
    const bottom = topWorld.ports.find((p) => p.id === "bottom")!;
    const ghost = offsetTo(movingWorld, bottom.x - movingWorld.ports.find((p) => p.id === "top")!.local.x, bottom.y - 6);
    const snap = pickSnapGhost(state, core, layout, ghost, new Set([moving.id]));
    expect(snap && snap.kind !== "nest").toBe(true);
    const edge = state.connectPorts(
      core,
      { nodeId: top.id, portId: "bottom" },
      { nodeId: moving.id, portId: "top" },
      "flush",
    );
    expect(edge).toBeTruthy();
    state.setTransform(moving.id, { x: 0, y: 0 });
    const placed = layoutDocument(state, core).nodes.get(moving.id)!;
    expect(snap && snap.kind !== "nest" ? snap.x : 0).toBeCloseTo(placed.x, 0);
    expect(snap && snap.kind !== "nest" ? snap.y : 0).toBeCloseTo(placed.y, 0);
    expect(state.outgoingStack(top.id, core)?.to.nodeId).toBe(moving.id);
  });

  it("finding a nest hover does not insert; drop does", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, core);
    const child = state.addNode(BLOCK_TYPE, { x: 300, y: 20 }, core);
    const before = structuredClone(state.toDocument());
    const layout = layoutDocument(state, core);
    const parentWorld = layout.nodes.get(parent.id)!;
    const childWorld = layout.nodes.get(child.id)!;
    const ghost = offsetTo(childWorld, parentWorld.x + GEOM.inner + 28, parentWorld.y + 4);
    const snap = pickSnapGhost(state, core, layout, ghost, new Set([child.id]));
    expect(snap?.kind).toBe("nest");
    expect(state.toDocument()).toEqual(before);
    expect(state.parentOf(child.id)).toBeNull();

    if (snap?.kind === "nest") {
      expect(state.insertInPocket(snap.parentId, snap.pocketId, child.id, snap.index, core)).toBe(true);
    }
    expect(state.parentOf(child.id)?.parentId).toBe(parent.id);
    const laid = layoutDocument(state, core);
    const wrapped = laid.nodes.get(parent.id)!;
    const nested = laid.nodes.get(child.id)!;
    expect(wrapped.wrapped).toBe(true);
    expect(nested.x).toBeCloseTo(wrapped.x + GEOM.inner, 0);
    expect(nested.y).toBeCloseTo(wrapped.y + wrapped.hatHeight, 0);
  });
});

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
