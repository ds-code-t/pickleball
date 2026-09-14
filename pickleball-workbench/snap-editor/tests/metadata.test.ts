import { describe, expect, it } from "vitest";
import { DocumentState } from "../src/core/model/document";
import { deriveMetadata } from "../src/core/metadata";
import { BLOCK_TYPE, core } from "./harness";

describe("derived topology metadata", () => {
  it("computes chain index, nest depth, and descendant counts from links", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, core);
    const child = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, core);
    const right = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, core);
    const below = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, core);
    state.insertInPocket(parent.id, "do", child.id, 0, core);
    state.connectPorts(core, { nodeId: parent.id, portId: "right" }, { nodeId: right.id, portId: "left" });
    state.connectPorts(core, { nodeId: parent.id, portId: "bottom" }, { nodeId: below.id, portId: "top" });

    const parentMeta = deriveMetadata(state, parent.id, core);
    expect(parentMeta.horizontal).toMatchObject({ first: true, last: false, index: 0, length: 2 });
    expect(parentMeta.vertical).toMatchObject({ first: true, last: false, index: 0, length: 2 });
    expect(parentMeta.nestDepth).toBe(0);
    expect(parentMeta.childCount).toBe(1);
    expect(parentMeta.descendantCount).toBe(1);

    const childMeta = deriveMetadata(state, child.id, core);
    expect(childMeta.nestDepth).toBe(1);
    expect(childMeta.ancestors).toEqual([parent.id]);

    const rightMeta = deriveMetadata(state, right.id, core);
    expect(rightMeta.horizontal).toMatchObject({ first: false, last: true, index: 1 });
  });
});
