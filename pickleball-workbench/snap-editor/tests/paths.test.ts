import { describe, expect, it } from "vitest";
import { DocumentState } from "../src/core/model/document";
import { layoutDocument } from "../src/core/layout";
import { nodePath, pathHasJoinCue } from "../src/core/render/paths";
import { BLOCK_TYPE, CONTROL_TYPE, typed } from "./harness";

describe("join cues appear only after a snap", () => {
  it("unconnected blocks are smooth rectangles with no tabs", () => {
    const state = DocumentState.empty();
    const stack = state.addNode(BLOCK_TYPE, { x: 10, y: 10 }, typed);
    const iff = state.addNode(CONTROL_TYPE, { x: 200, y: 10 }, typed);
    const laid = layoutDocument(state, typed);
    const stackPath = nodePath(laid.nodes.get(stack.id)!);
    const ifPath = nodePath(laid.nodes.get(iff.id)!);
    expect(pathHasJoinCue(stackPath)).toBe(false);
    expect(pathHasJoinCue(ifPath)).toBe(false);
    expect(laid.nodes.get(stack.id)!.joins).toEqual({
      top: false,
      bottom: false,
      left: false,
      right: false,
    });
  });

  it("a vertical snap adds a join cue on the shared edge", () => {
    const state = DocumentState.empty();
    const top = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const bottom = state.addNode(BLOCK_TYPE, { x: 40, y: 80 }, typed);
    const idleTop = nodePath(layoutDocument(state, typed).nodes.get(top.id)!);
    expect(pathHasJoinCue(idleTop)).toBe(false);

    state.connectPorts(
      typed,
      { nodeId: top.id, portId: "bottom" },
      { nodeId: bottom.id, portId: "top" },
      "flush",
    );
    const laid = layoutDocument(state, typed);
    const topWorld = laid.nodes.get(top.id)!;
    const bottomWorld = laid.nodes.get(bottom.id)!;
    expect(topWorld.joins.bottom).toBe(true);
    expect(bottomWorld.joins.top).toBe(true);
    expect(pathHasJoinCue(nodePath(topWorld))).toBe(true);
    expect(pathHasJoinCue(nodePath(bottomWorld))).toBe(true);
    expect(topWorld.joins.top).toBe(false);
    expect(bottomWorld.joins.bottom).toBe(false);
  });

  it("a horizontal snap adds a join cue on the shared edge", () => {
    const state = DocumentState.empty();
    const left = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const right = state.addNode(BLOCK_TYPE, { x: 200, y: 0 }, typed);
    state.connectPorts(
      typed,
      { nodeId: left.id, portId: "right" },
      { nodeId: right.id, portId: "left" },
      "flush",
    );
    const laid = layoutDocument(state, typed);
    const leftWorld = laid.nodes.get(left.id)!;
    const rightWorld = laid.nodes.get(right.id)!;
    expect(leftWorld.joins.right).toBe(true);
    expect(rightWorld.joins.left).toBe(true);
    expect(pathHasJoinCue(nodePath(leftWorld))).toBe(true);
    expect(pathHasJoinCue(nodePath(rightWorld))).toBe(true);
    expect(pathHasJoinCue(nodePath(leftWorld, { ...leftWorld.joins, right: false }))).toBe(false);
  });

  it("unsnapping removes the join cue", () => {
    const state = DocumentState.empty();
    const left = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const right = state.addNode(BLOCK_TYPE, { x: 200, y: 0 }, typed);
    const edge = state.connectPorts(
      typed,
      { nodeId: left.id, portId: "right" },
      { nodeId: right.id, portId: "left" },
      "flush",
    );
    expect(edge).toBeTruthy();
    if (edge) state.removeEdge(edge.id);
    const laid = layoutDocument(state, typed);
    expect(pathHasJoinCue(nodePath(laid.nodes.get(left.id)!))).toBe(false);
    expect(pathHasJoinCue(nodePath(laid.nodes.get(right.id)!))).toBe(false);
  });
});
