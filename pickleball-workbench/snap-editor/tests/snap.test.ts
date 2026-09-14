import { describe, expect, it } from "vitest";
import { DocumentState } from "../src/core/model/document";
import { layoutDocument, type WorldNode } from "../src/core/layout";
import { pickSnapGhost } from "../src/core/snap";
import { GEOM } from "../src/core/layout/geom";
import { BLOCK_TYPE, CONTROL_TYPE, typed } from "./harness";

describe("snap pose matches committed layout", () => {
  it("aligns a stack under another stack on the shared edge", () => {
    const state = DocumentState.empty();
    const top = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const stack = state.addNode(BLOCK_TYPE, { x: 200, y: 200 }, typed);
    const layout = layoutDocument(state, typed);
    const topWorld = layout.nodes.get(top.id)!;
    const stackWorld = layout.nodes.get(stack.id)!;
    const bottom = topWorld.ports.find((p) => p.id === "bottom")!;
    const gx = bottom.x - stackWorld.ports.find((p) => p.id === "top")!.local.x;
    const gy = bottom.y - 6;
    const ghost = offsetTo(stackWorld, gx, gy);
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([stack.id]));
    expect(snap).toBeTruthy();
    expect(snap && snap.kind !== "nest" && snap.to.portId).toBe("bottom");

    state.connectPorts(
      typed,
      { nodeId: top.id, portId: "bottom" },
      { nodeId: stack.id, portId: "top" },
      "flush",
    );
    const placed = layoutDocument(state, typed).nodes.get(stack.id)!;
    expect(snap && snap.kind !== "nest" ? snap.x : 0).toBeCloseTo(placed.x, 0);
    expect(snap && snap.kind !== "nest" ? snap.y : 0).toBeCloseTo(placed.y, 0);
  });

  it("snaps a toolbox drop beside a control header (horizontal)", () => {
    const state = DocumentState.empty();
    const iff = state.addNode(CONTROL_TYPE, { x: 80, y: 40 }, typed);
    const layout = layoutDocument(state, typed);
    const ifWorld = layout.nodes.get(iff.id)!;
    const right = ifWorld.ports.find((p) => p.id === "right")!;
    const stack = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const proto = layoutDocument(state, typed).nodes.get(stack.id)!;
    const left = proto.ports.find((p) => p.id === "left")!;
    const gx = right.x - left.local.x + 10;
    const gy = right.y - left.local.y + 8;
    const ghost = offsetTo(proto, gx, gy);
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([stack.id]), [], {
      x: right.x + 8,
      y: right.y,
    });
    expect(snap).toBeTruthy();
    expect(snap && snap.kind !== "nest").toBe(true);
    if (snap && snap.kind !== "nest") {
      expect([snap.from.portId, snap.to.portId].sort()).toEqual(["left", "right"]);
    }
  });

  it("offers a nest highlight for an overlapped-right drop into an empty C-pocket", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(CONTROL_TYPE, { x: 40, y: 20 }, typed);
    const child = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const layout = layoutDocument(state, typed);
    const parentWorld = layout.nodes.get(parent.id)!;
    const childWorld = layout.nodes.get(child.id)!;
    const gx = parentWorld.x + GEOM.inner + 28;
    const gy = parentWorld.y + 4;
    const ghost = offsetTo(childWorld, gx, gy);
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([child.id]));
    expect(snap?.kind).toBe("nest");
    if (snap?.kind === "nest") {
      expect(snap.pocketId).toBe("then");
      expect(snap.face).toBe("nest-out");
      expect(snap.highlight.width).toBeGreaterThan(40);
      expect(snap.highlight.height).toBeGreaterThan(10);
    }
  });

  it("places a nested child so the parent grows by the child stack height", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(CONTROL_TYPE, { x: 10, y: 10 }, typed);
    const child = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const empty = layoutDocument(state, typed).nodes.get(parent.id)!;
    state.insertInPocket(parent.id, "then", child.id, 0, typed);
    const filled = layoutDocument(state, typed);
    const parentWorld = filled.nodes.get(parent.id)!;
    const childWorld = filled.nodes.get(child.id)!;
    expect(parentWorld.height).toBeCloseTo(
      empty.height - empty.pockets[0].height + childWorld.stackHeight + GEOM.nestPad,
      0,
    );
    expect(childWorld.x).toBeCloseTo(parentWorld.x + GEOM.inner, 0);
    expect(childWorld.y).toBeCloseTo(parentWorld.y + parentWorld.hatHeight, 0);
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
