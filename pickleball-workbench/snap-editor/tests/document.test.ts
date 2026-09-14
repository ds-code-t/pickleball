import { describe, expect, it } from "vitest";
import { DocumentState } from "../src/core/model/document";
import { layoutDocument } from "../src/core/layout";
import { BLOCK_TYPE, CONTROL_TYPE, STACK_TYPE, STATEMENT_TYPE, typed } from "./harness";

describe("document relationships", () => {
  it("records nesting in pockets, not as edges", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(CONTROL_TYPE, { x: 0, y: 0 }, typed);
    const child = state.addNode(BLOCK_TYPE, { x: 80, y: 80 }, typed);
    state.insertInPocket(parent.id, "then", child.id, 0);
    expect(state.parentOf(child.id)).toEqual({ parentId: parent.id, pocketId: "then", index: 0 });
    expect(state.edges.size).toBe(0);
  });

  it("records vertical stack snaps as next/prev flush edges", () => {
    const state = DocumentState.empty();
    const a = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const b = state.addNode(BLOCK_TYPE, { x: 0, y: 80 }, typed);
    const edge = state.connectPorts(
      typed,
      { nodeId: a.id, portId: "bottom" },
      { nodeId: b.id, portId: "top" },
      "flush",
    );
    expect(edge?.presentation).toBe("flush");
    expect(state.stackNext(a.id, typed)).toBe(b.id);
    expect(state.stackPrev(b.id, typed)).toBe(a.id);
  });

  it("records horizontal joins as right/left flush edges", () => {
    const state = DocumentState.empty();
    const a = state.addNode(CONTROL_TYPE, { x: 0, y: 0 }, typed);
    const b = state.addNode(BLOCK_TYPE, { x: 200, y: 0 }, typed);
    state.connectPorts(typed, { nodeId: a.id, portId: "right" }, { nodeId: b.id, portId: "left" });
    expect(state.horizontalNext(a.id, typed)).toBe(b.id);
    expect(state.horizontalPrev(b.id, typed)).toBe(a.id);
    expect(state.isFree(a.id, typed)).toBe(true);
    expect(state.isFree(b.id, typed)).toBe(false);
  });

  it("splices a stack so the displaced neighbor attaches to the tail", () => {
    const state = DocumentState.empty();
    const a = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const b = state.addNode(BLOCK_TYPE, { x: 0, y: 40 }, typed);
    const c = state.addNode(BLOCK_TYPE, { x: 0, y: 80 }, typed);
    state.connectPorts(typed, { nodeId: a.id, portId: "bottom" }, { nodeId: c.id, portId: "top" });
    state.connectPorts(typed, { nodeId: a.id, portId: "bottom" }, { nodeId: b.id, portId: "top" });
    expect(state.stackNext(a.id, typed)).toBe(b.id);
    expect(state.stackNext(b.id, typed)).toBe(c.id);
  });

  it("grows a C-pocket by the child's stack height and shrinks when emptied", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(CONTROL_TYPE, { x: 10, y: 10 }, typed);
    const child = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const empty = layoutDocument(state, typed).nodes.get(parent.id)!;
    expect(empty.pockets[0].stub).toBe(true);
    state.insertInPocket(parent.id, "then", child.id, 0, typed);
    const filled = layoutDocument(state, typed).nodes.get(parent.id)!;
    expect(filled.height).toBeGreaterThan(empty.height);
    expect(filled.pockets[0].stub).toBe(false);
    expect(filled.pockets[0].height).toBeGreaterThanOrEqual(empty.pockets[0].height);
    state.detachFromParent(child.id);
    const shrunk = layoutDocument(state, typed).nodes.get(parent.id)!;
    expect(shrunk.pockets[0].stub).toBe(true);
    expect(shrunk.height).toBe(empty.height);
  });

  it("grows if/else pockets independently", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(CONTROL_TYPE, { x: 0, y: 0 }, typed);
    const thenChild = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const elseA = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const elseB = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    state.insertInPocket(parent.id, "then", thenChild.id, 0, typed);
    const one = layoutDocument(state, typed).nodes.get(parent.id)!;
    state.insertInPocket(parent.id, "else", elseA.id, 0, typed);
    state.insertInPocket(parent.id, "else", elseB.id, 1, typed);
    const two = layoutDocument(state, typed).nodes.get(parent.id)!;
    expect(two.pockets[0].height).toBe(one.pockets[0].height);
    expect(two.pockets[1].height).toBeGreaterThan(one.pockets[1].height);
    expect(two.height).toBeGreaterThan(one.height);
  });

  it("stacks multiple siblings in the same pocket and wires next/prev", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(STACK_TYPE, { x: 0, y: 0 }, typed);
    const a = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const b = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    state.insertInPocket(parent.id, "do", a.id, 0, typed);
    state.insertInPocket(parent.id, "do", b.id, 1, typed);
    expect(state.getNode(parent.id).pockets.do).toEqual([a.id, b.id]);
    expect(state.stackNext(a.id, typed)).toBe(b.id);
  });

  it("drag unit includes pocket children and the next stack", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(CONTROL_TYPE, { x: 0, y: 0 }, typed);
    const child = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const below = state.addNode(BLOCK_TYPE, { x: 0, y: 80 }, typed);
    state.insertInPocket(parent.id, "then", child.id, 0, typed);
    state.connectPorts(typed, { nodeId: parent.id, portId: "bottom" }, { nodeId: below.id, portId: "top" });
    const unit = state.dragUnit(parent.id, typed);
    expect(unit).toEqual(expect.arrayContaining([parent.id, child.id, below.id]));
  });

  it("exposes statement, stack, and control in the typed toolbox", () => {
    expect(typed.toolbox().map((d) => d.type).sort()).toEqual([
      CONTROL_TYPE,
      STACK_TYPE,
      STATEMENT_TYPE,
    ]);
  });

  it("does not zero a free parent transform when a child flush-joins", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(CONTROL_TYPE, { x: 220, y: 80 }, typed);
    const child = state.addNode(BLOCK_TYPE, { x: 400, y: 200 }, typed);
    state.connectPorts(
      typed,
      { nodeId: parent.id, portId: "bottom" },
      { nodeId: child.id, portId: "top" },
      "flush",
    );
    state.canonicalizeTransforms(typed);
    expect(state.getNode(parent.id).transform).toEqual({ x: 220, y: 80 });
    expect(state.getNode(child.id).transform).toEqual({ x: 0, y: 0 });
    expect(state.isFree(parent.id, typed)).toBe(true);
    expect(state.isFree(child.id, typed)).toBe(false);
  });

  it("does not expose fork, taper, or polygon APIs", () => {
    const state = DocumentState.empty();
    expect("addFlow" in state).toBe(false);
    expect("flipOrientation" in state).toBe(false);
    expect("refreshTapers" in state).toBe(false);
    expect("morphPolygon" in state).toBe(false);
    expect(typed.has("flow")).toBe(false);
  });
});
