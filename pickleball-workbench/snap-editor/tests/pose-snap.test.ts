import { describe, expect, it } from "vitest";
import { DocumentState } from "../src/core/model/document";
import { layoutDocument, type WorldNode } from "../src/core/layout";
import { blockFaces, classifyPose, pickSnapGhost, POSE } from "../src/core/snap";
import { GEOM } from "../src/core/layout/geom";
import { BLOCK_TYPE, CONTROL_TYPE, typed } from "./harness";

describe("logical six-sided faces", () => {
  it("empty parent: left-rail and expander have length 0; south is the full bottom", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const world = layoutDocument(state, typed).nodes.get(parent.id)!;
    const faces = blockFaces(world);
    expect(world.wrapped).toBe(false);
    expect(faces.leftRail.length).toBe(0);
    expect(faces.expander.length).toBe(0);
    expect(faces.s.length).toBeCloseTo(world.width, 0);
    expect(faces.n.length).toBeCloseTo(world.width, 0);
    expect(faces.e.length).toBeCloseTo(world.hatHeight, 0);
    expect(faces.w.length).toBeCloseTo(world.hatHeight, 0);
    expect(faces.leftRail.id).toBe("nest-out");
    expect(faces.expander.id).toBe("nest-out");
    expect(faces.s.id).toBe("s");
  });

  it("filled parent: left-rail is the back width, expander is nest height, south is the remaining bottom", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const child = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    state.insertInPocket(parent.id, "do", child.id, 0, typed);
    const world = layoutDocument(state, typed).nodes.get(parent.id)!;
    const faces = blockFaces(world);
    expect(world.wrapped).toBe(true);
    expect(faces.leftRail.length).toBeCloseTo(GEOM.inner, 0);
    expect(faces.expander.length).toBeCloseTo(world.height - world.hatHeight, 0);
    expect(faces.expander.length).toBeGreaterThan(0);
    expect(faces.s.a.x).toBeCloseTo(world.x + GEOM.inner, 0);
    expect(faces.s.length).toBeCloseTo(world.width - GEOM.inner, 0);
    expect(faces.s.length).toBeGreaterThan(faces.leftRail.length);
    expect(faces.expander.a).toEqual({ x: world.x + GEOM.inner, y: world.y + world.hatHeight });
  });
});

describe("pose snap: aligned-x stack, overlapped-right nest, end-to-start horizontal", () => {
  it("empty parent: aligned x below stacks on true south, not nest", () => {
    const { state, parent, moving, layout } = pair();
    const parentWorld = layout.nodes.get(parent.id)!;
    const movingWorld = layout.nodes.get(moving.id)!;
    const ghost = offsetTo(movingWorld, parentWorld.x + 6, parentWorld.y + parentWorld.height + 10);
    const pose = classifyPose(ghost, parentWorld);
    expect(pose?.kind).toBe("stack");
    expect(pose?.face).toBe("s");
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([moving.id]), [], {
      x: parentWorld.x + parentWorld.width / 2,
      y: parentWorld.y + parentWorld.height / 2,
    });
    expect(snap && snap.kind !== "nest" && snap.face).toBe("s");
    if (snap && snap.kind !== "nest") {
      expect(snap.to.portId).toBe("bottom");
      expect(snap.segments[0]?.role).toBe("s");
    }
  });

  it("empty parent: overlapped-right sitting in the body nests on nest-out", () => {
    const { state, parent, moving, layout } = pair();
    const parentWorld = layout.nodes.get(parent.id)!;
    const movingWorld = layout.nodes.get(moving.id)!;
    const ghost = nestInBody(parentWorld, movingWorld);
    const pose = classifyPose(ghost, parentWorld);
    expect(pose?.kind).toBe("nest");
    expect(pose?.face).toBe("nest-out");
    expect(pose?.fromFace).toBe("nest-in");
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([moving.id]));
    expect(snap?.kind).toBe("nest");
    if (snap?.kind === "nest") {
      expect(snap.parentId).toBe(parent.id);
      expect(snap.face).toBe("nest-out");
      expect(snap.highlight.width).toBeGreaterThan(40);
      expect(snap.highlight.x).toBeGreaterThan(parentWorld.x + 8);
      expect(snap.segments.every((seg) => seg.role === "nest-out")).toBe(true);
    }
  });

  it("empty parent: end-to-start x with close y joins horizontally", () => {
    const { state, parent, moving, layout } = pair();
    const parentWorld = layout.nodes.get(parent.id)!;
    const movingWorld = layout.nodes.get(moving.id)!;
    const ghost = offsetTo(movingWorld, parentWorld.x + parentWorld.width + 8, parentWorld.y + 4);
    const pose = classifyPose(ghost, parentWorld);
    expect(pose?.kind).toBe("horizontal");
    expect(pose?.face).toBe("e");
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([moving.id]));
    expect(snap && snap.kind !== "nest").toBe(true);
    if (snap && snap.kind !== "nest") {
      expect([snap.from.portId, snap.to.portId].sort()).toEqual(["left", "right"]);
      expect(snap.face).toBe("e");
      expect(snap.segments[0]?.role).toBe("e");
    }
  });

  it("filled parent: aligned x below the grown L stacks on true south", () => {
    const { state, parent, moving, layout } = filledPair();
    const parentWorld = layout.nodes.get(parent.id)!;
    const movingWorld = layout.nodes.get(moving.id)!;
    expect(parentWorld.wrapped).toBe(true);
    const ghost = offsetTo(movingWorld, parentWorld.x + 4, parentWorld.y + parentWorld.height + 12);
    const pose = classifyPose(ghost, parentWorld);
    expect(pose?.kind).toBe("stack");
    expect(pose?.face).toBe("s");
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([moving.id]), [], {
      x: parentWorld.x + GEOM.inner + 40,
      y: parentWorld.y + parentWorld.hatHeight + 20,
    });
    expect(snap && snap.kind !== "nest" && snap.face).toBe("s");
    if (snap && snap.kind !== "nest") expect(snap.to.portId).toBe("bottom");
  });

  it("filled parent: overlapped-right in the L body nests, not stack", () => {
    const { state, parent, moving, layout } = filledPair();
    const parentWorld = layout.nodes.get(parent.id)!;
    const movingWorld = layout.nodes.get(moving.id)!;
    const ghost = offsetTo(
      movingWorld,
      parentWorld.x + GEOM.inner + 4,
      parentWorld.y + parentWorld.hatHeight + 10,
    );
    const pose = classifyPose(ghost, parentWorld);
    expect(pose?.kind).toBe("nest");
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([moving.id]));
    expect(snap?.kind).toBe("nest");
    if (snap?.kind === "nest") {
      expect(snap.parentId).toBe(parent.id);
      expect(snap.segments.some((seg) => seg.role === "nest-out")).toBe(true);
      const faces = blockFaces(parentWorld);
      expect(faces.expander.length).toBeGreaterThan(0);
    }
  });

  it("filled parent: end-to-start beside the hat joins horizontally", () => {
    const { state, parent, moving, layout } = filledPair();
    const parentWorld = layout.nodes.get(parent.id)!;
    const movingWorld = layout.nodes.get(moving.id)!;
    const ghost = offsetTo(movingWorld, parentWorld.x + parentWorld.width - 6, parentWorld.y + 3);
    const pose = classifyPose(ghost, parentWorld);
    expect(pose?.kind).toBe("horizontal");
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([moving.id]));
    expect(snap && snap.kind !== "nest").toBe(true);
    if (snap && snap.kind !== "nest") {
      expect([snap.from.portId, snap.to.portId].sort()).toEqual(["left", "right"]);
    }
  });

  it("does not treat a tiny center hotspot as nest when x is aligned for stack", () => {
    const { state, parent, moving, layout } = pair();
    const parentWorld = layout.nodes.get(parent.id)!;
    const movingWorld = layout.nodes.get(moving.id)!;
    const ghost = offsetTo(movingWorld, parentWorld.x, parentWorld.y + parentWorld.height + 8);
    const probe = {
      x: parentWorld.x + parentWorld.width / 2,
      y: parentWorld.y + parentWorld.height / 2,
    };
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([moving.id]), [], probe);
    expect(snap?.kind).not.toBe("nest");
    expect(snap && snap.kind !== "nest" && snap.face).toBe("s");
  });

  it("still refuses nest on a horizontal trailer even with overlapped-right pose", () => {
    const state = DocumentState.empty();
    const left = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const right = state.addNode(BLOCK_TYPE, { x: 200, y: 0 }, typed);
    const child = state.addNode(BLOCK_TYPE, { x: 0, y: 80 }, typed);
    state.connectPorts(typed, { nodeId: left.id, portId: "right" }, { nodeId: right.id, portId: "left" });
    const layout = layoutDocument(state, typed);
    const rightWorld = layout.nodes.get(right.id)!;
    const childWorld = layout.nodes.get(child.id)!;
    const ghost = nestInBody(rightWorld, childWorld);
    expect(classifyPose(ghost, rightWorld)?.kind).toBe("nest");
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([child.id]));
    expect(snap?.kind === "nest" && snap.parentId === right.id).toBeFalsy();
  });
});

describe("pose thresholds", () => {
  it("keeps aligned-x N/S room larger than a nest inset", () => {
    expect(POSE.alignX).toBeGreaterThan(0);
    expect(POSE.stackRoom).toBeGreaterThan(POSE.alignX);
    expect(POSE.nestInset).toBe(GEOM.inner);
  });
});

function pair() {
  const state = DocumentState.empty();
  const parent = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
  const moving = state.addNode(BLOCK_TYPE, { x: 400, y: 300 }, typed);
  const layout = layoutDocument(state, typed);
  return { state, parent, moving, layout };
}

function filledPair() {
  const state = DocumentState.empty();
  const parent = state.addNode(CONTROL_TYPE, { x: 40, y: 20 }, typed);
  const nested = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
  const moving = state.addNode(BLOCK_TYPE, { x: 500, y: 320 }, typed);
  state.insertInPocket(parent.id, "then", nested.id, 0, typed);
  const layout = layoutDocument(state, typed);
  return { state, parent, moving, layout };
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
