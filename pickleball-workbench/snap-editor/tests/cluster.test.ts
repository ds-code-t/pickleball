import { describe, expect, it } from "vitest";
import { DocumentState } from "../src/core/model/document";
import { layoutDocument } from "../src/core/layout";
import { BLOCK_TYPE, core } from "./harness";

describe("cluster drag", () => {
  it("keeps nested kids, right siblings, and the below stack attached when the parent is lifted", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, core);
    const child = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, core);
    const right = state.addNode(BLOCK_TYPE, { x: 200, y: 20 }, core);
    const below = state.addNode(BLOCK_TYPE, { x: 40, y: 200 }, core);
    state.insertInPocket(parent.id, "do", child.id, 0, core);
    state.connectPorts(core, { nodeId: parent.id, portId: "right" }, { nodeId: right.id, portId: "left" });
    state.connectPorts(core, { nodeId: parent.id, portId: "bottom" }, { nodeId: below.id, portId: "top" });
    const before = layoutDocument(state, core);
    const rel = {
      childX: before.nodes.get(child.id)!.x - before.nodes.get(parent.id)!.x,
      childY: before.nodes.get(child.id)!.y - before.nodes.get(parent.id)!.y,
      rightX: before.nodes.get(right.id)!.x - before.nodes.get(parent.id)!.x,
      belowY: before.nodes.get(below.id)!.y - before.nodes.get(parent.id)!.y,
    };

    const unit = state.liftCluster(parent.id, core);
    expect(unit).toEqual(expect.arrayContaining([parent.id, child.id, right.id, below.id]));
    expect(state.parentOf(child.id)?.parentId).toBe(parent.id);
    expect(state.horizontalNext(parent.id, core)).toBe(right.id);
    expect(state.stackNext(parent.id, core)).toBe(below.id);
    expect(state.parentOf(parent.id)).toBeNull();

    state.setTransform(parent.id, { x: 120, y: 80 });
    const after = layoutDocument(state, core);
    expect(after.nodes.get(child.id)!.x - after.nodes.get(parent.id)!.x).toBeCloseTo(rel.childX, 0);
    expect(after.nodes.get(child.id)!.y - after.nodes.get(parent.id)!.y).toBeCloseTo(rel.childY, 0);
    expect(after.nodes.get(right.id)!.x - after.nodes.get(parent.id)!.x).toBeCloseTo(rel.rightX, 0);
    expect(after.nodes.get(below.id)!.y - after.nodes.get(parent.id)!.y).toBeCloseTo(rel.belowY, 0);
  });

  it("lifts a pocket child with its below stack and does not leave descendants in the parent", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, core);
    const a = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, core);
    const b = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, core);
    state.insertInPocket(parent.id, "do", a.id, 0, core);
    state.insertInPocket(parent.id, "do", b.id, 1, core);
    expect(state.stackNext(a.id, core)).toBe(b.id);

    const unit = state.liftCluster(a.id, core);
    expect(unit).toEqual(expect.arrayContaining([a.id, b.id]));
    expect(state.parentOf(a.id)).toBeNull();
    expect(state.parentOf(b.id)).toBeNull();
    expect(state.stackNext(a.id, core)).toBe(b.id);
    expect(state.getNode(parent.id).pockets.do).toEqual([]);
  });
});

describe("vertical connect only on a horizontal-chain head", () => {
  it("rejects stacking a trailing horizontal sibling", () => {
    const state = DocumentState.empty();
    const head = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, core);
    const trailer = state.addNode(BLOCK_TYPE, { x: 180, y: 0 }, core);
    const below = state.addNode(BLOCK_TYPE, { x: 180, y: 80 }, core);
    state.connectPorts(core, { nodeId: head.id, portId: "right" }, { nodeId: trailer.id, portId: "left" });
    expect(
      state.connectPorts(core, { nodeId: trailer.id, portId: "bottom" }, { nodeId: below.id, portId: "top" }),
    ).toBeNull();
    expect(
      state.connectPorts(core, { nodeId: head.id, portId: "bottom" }, { nodeId: below.id, portId: "top" }),
    ).toBeTruthy();
  });
});
