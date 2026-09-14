import { describe, expect, it } from "vitest";
import { DocumentState } from "../src/core/model/document";
import { layoutDocument, type Layout, type WorldNode } from "../src/core/layout";
import { pickSnapGhost, previewSnap, realizeSnap } from "../src/core/snap";
import { pickleball, typed, BLOCK_TYPE } from "./harness";
import { FEATURE_TYPE, SCENARIO_TYPE, STEP_TYPE } from "../src/packs/pickleball";
import type { Registry } from "../src/core/registry";

describe("insert-between splits an existing chain", () => {
  it("vertical: C between stacked A-B → A.below is C, C.below is B, B moves down", () => {
    const state = DocumentState.empty();
    const a = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const b = state.addNode(BLOCK_TYPE, { x: 40, y: 200 }, typed);
    const c = state.addNode(BLOCK_TYPE, { x: 400, y: 320 }, typed);
    state.connectPorts(typed, { nodeId: a.id, portId: "bottom" }, { nodeId: b.id, portId: "top" });
    const before = layoutDocument(state, typed);
    const beforeBY = before.nodes.get(b.id)!.y;

    const { preview, commit, snap } = hoverAndDrop(state, typed, c.id, (layout) =>
      betweenVertical(layout.nodes.get(a.id)!, layout.nodes.get(b.id)!, layout.nodes.get(c.id)!),
    );

    expect(snap && snap.kind !== "nest").toBe(true);
    expect(preview.state.outgoingStack(a.id, typed)?.to.nodeId).toBe(c.id);
    expect(preview.state.outgoingStack(c.id, typed)?.to.nodeId).toBe(b.id);
    expect(state.outgoingStack(a.id, typed)?.to.nodeId).toBe(b.id);
    expect(preview.layout.nodes.get(b.id)!.y).toBeGreaterThan(beforeBY + 20);
    expect(topology(preview.state, typed)).toEqual(topology(commit, typed));
    expect(commit.outgoingStack(a.id, typed)?.to.nodeId).toBe(c.id);
    expect(commit.outgoingStack(c.id, typed)?.to.nodeId).toBe(b.id);
  });

  it("vertical: C centered on a flush A-B join still inserts between, not nest", () => {
    const state = DocumentState.empty();
    const a = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const b = state.addNode(BLOCK_TYPE, { x: 40, y: 80 }, typed);
    const c = state.addNode(BLOCK_TYPE, { x: 420, y: 300 }, typed);
    state.connectPorts(typed, { nodeId: a.id, portId: "bottom" }, { nodeId: b.id, portId: "top" });
    const beforeBY = layoutDocument(state, typed).nodes.get(b.id)!.y;

    const { preview, snap } = hoverAndDrop(state, typed, c.id, (layout) => {
      const aw = layout.nodes.get(a.id)!;
      const bw = layout.nodes.get(b.id)!;
      const cw = layout.nodes.get(c.id)!;
      const joinY = (aw.y + aw.height + bw.y) / 2;
      return offsetTo(cw, aw.x + 6, joinY - cw.hatHeight / 2);
    });

    expect(snap && snap.kind !== "nest").toBe(true);
    expect(preview.state.outgoingStack(a.id, typed)?.to.nodeId).toBe(c.id);
    expect(preview.state.outgoingStack(c.id, typed)?.to.nodeId).toBe(b.id);
    expect(preview.layout.nodes.get(b.id)!.y).toBeGreaterThan(beforeBY + 20);
  });

  it("horizontal: C between joined A-B → A.right is C, C.right is B, B shifts right", () => {
    const state = DocumentState.empty();
    const a = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const b = state.addNode(BLOCK_TYPE, { x: 400, y: 20 }, typed);
    const c = state.addNode(BLOCK_TYPE, { x: 40, y: 240 }, typed);
    state.connectPorts(typed, { nodeId: a.id, portId: "right" }, { nodeId: b.id, portId: "left" });
    const beforeBX = layoutDocument(state, typed).nodes.get(b.id)!.x;

    const { preview, commit, snap } = hoverAndDrop(state, typed, c.id, (layout) =>
      betweenHorizontal(layout.nodes.get(a.id)!, layout.nodes.get(b.id)!, layout.nodes.get(c.id)!),
    );

    expect(snap && snap.kind !== "nest").toBe(true);
    expect(preview.state.horizontalNext(a.id, typed)).toBe(c.id);
    expect(preview.state.horizontalNext(c.id, typed)).toBe(b.id);
    expect(state.horizontalNext(a.id, typed)).toBe(b.id);
    expect(preview.layout.nodes.get(b.id)!.x).toBeGreaterThan(beforeBX + 20);
    expect(topology(preview.state, typed)).toEqual(topology(commit, typed));
  });

  it("Pickleball: a step between two nested Given/When steps splits the stack and moves When down", () => {
    const state = DocumentState.empty();
    const feature = state.addNode(FEATURE_TYPE, { x: 20, y: 20 }, pickleball);
    const scenario = state.addNode(SCENARIO_TYPE, { x: 0, y: 0 }, pickleball);
    const given = state.addNode(STEP_TYPE, { x: 0, y: 0 }, pickleball, {
      fields: { keyword: "Given", text: "go home" },
    });
    const when = state.addNode(STEP_TYPE, { x: 0, y: 0 }, pickleball, {
      fields: { keyword: "When", text: "click" },
    });
    const moving = state.addNode(STEP_TYPE, { x: 520, y: 360 }, pickleball, {
      fields: { keyword: "And", text: "wait" },
    });
    state.insertInPocket(feature.id, "do", scenario.id, 0, pickleball);
    state.insertInPocket(scenario.id, "do", given.id, 0, pickleball);
    state.insertInPocket(scenario.id, "do", when.id, 1, pickleball);
    expect(state.outgoingStack(given.id, pickleball)?.to.nodeId).toBe(when.id);

    const before = layoutDocument(state, pickleball);
    const beforeWhenY = before.nodes.get(when.id)!.y;
    const beforeDoc = structuredClone(state.toDocument());

    const { preview, commit, snap } = hoverAndDrop(state, pickleball, moving.id, (layout) =>
      betweenVertical(layout.nodes.get(given.id)!, layout.nodes.get(when.id)!, layout.nodes.get(moving.id)!),
    );

    expect(snap?.kind).not.toBe("nest");
    expect(preview.state.outgoingStack(given.id, pickleball)?.to.nodeId).toBe(moving.id);
    expect(preview.state.outgoingStack(moving.id, pickleball)?.to.nodeId).toBe(when.id);
    expect(preview.state.parentOf(moving.id)?.parentId).toBe(scenario.id);
    expect(preview.layout.nodes.get(when.id)!.y).toBeGreaterThan(beforeWhenY + 20);
    expect(state.toDocument()).toEqual(beforeDoc);
    expect(commit.outgoingStack(given.id, pickleball)?.to.nodeId).toBe(moving.id);
    expect(commit.outgoingStack(moving.id, pickleball)?.to.nodeId).toBe(when.id);
    expect(layoutDocument(commit, pickleball).nodes.get(when.id)!.y).toBeGreaterThan(beforeWhenY + 20);
  });

  it("nest pocket: inserting among stacked nested children shifts later siblings down", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const first = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const second = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const moving = state.addNode(BLOCK_TYPE, { x: 480, y: 300 }, typed);
    state.insertInPocket(parent.id, "do", first.id, 0, typed);
    state.insertInPocket(parent.id, "do", second.id, 1, typed);
    const beforeY = layoutDocument(state, typed).nodes.get(second.id)!.y;

    const { preview, commit } = hoverAndDrop(state, typed, moving.id, (layout) =>
      betweenVertical(layout.nodes.get(first.id)!, layout.nodes.get(second.id)!, layout.nodes.get(moving.id)!),
    );

    expect(preview.state.outgoingStack(first.id, typed)?.to.nodeId).toBe(moving.id);
    expect(preview.state.outgoingStack(moving.id, typed)?.to.nodeId).toBe(second.id);
    expect(preview.state.parentOf(moving.id)?.parentId).toBe(parent.id);
    expect(preview.layout.nodes.get(second.id)!.y).toBeGreaterThan(beforeY + 20);
    expect(topology(preview.state, typed)).toEqual(topology(commit, typed));
  });
});

function hoverAndDrop(
  state: DocumentState,
  registry: Registry,
  movingId: string,
  ghostAt: (layout: Layout) => WorldNode,
) {
  const before = structuredClone(state.toDocument());
  const layout = layoutDocument(state, registry);
  const ghost = ghostAt(layout);
  const snap = pickSnapGhost(state, registry, layout, ghost, new Set([movingId]), [], {
    x: ghost.x + 24,
    y: ghost.y + 16,
  });
  expect(snap).toBeTruthy();
  const preview = previewSnap(state, registry, snap!, { nodeId: movingId });
  expect(preview).toBeTruthy();
  expect(state.toDocument()).toEqual(before);

  const commit = DocumentState.fromDocument(before, registry);
  expect(realizeSnap(commit, registry, movingId, snap!, { x: snap!.x, y: snap!.y })).toBe(true);
  return { preview: preview!, commit, snap: snap! };
}

function topology(state: DocumentState, registry: Registry) {
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

function betweenVertical(upper: WorldNode, lower: WorldNode, moving: WorldNode): WorldNode {
  const joinY = (upper.y + upper.height + lower.y) / 2;
  return offsetTo(moving, upper.x + 4, joinY - moving.hatHeight / 2);
}

function betweenHorizontal(left: WorldNode, right: WorldNode, moving: WorldNode): WorldNode {
  const joinX = (left.x + left.width + right.x) / 2;
  return offsetTo(moving, joinX - moving.width / 2, left.y + 3);
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
