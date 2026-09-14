import { describe, expect, it } from "vitest";
import { DocumentState } from "../src/core/model/document";
import { canNestInto } from "../src/core/registry";
import { layoutDocument } from "../src/core/layout";
import { GEOM } from "../src/core/layout/geom";
import { pickSnapGhost } from "../src/core/snap";
import { nodePath, pathClosesFullWidthAt } from "../src/core/render/paths";
import { BLOCK_TYPE, CONTROL_TYPE, STACK_TYPE, typed } from "./harness";

describe("grown boxes and nest rules", () => {
  it("grows parent height by the full nested stack + padding, and width by the widest horizontal chain", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(BLOCK_TYPE, { x: 10, y: 10 }, typed);
    const head = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const trailer = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const empty = layoutDocument(state, typed).nodes.get(parent.id)!;
    state.insertInPocket(parent.id, "do", head.id, 0, typed);
    state.connectPorts(typed, { nodeId: head.id, portId: "right" }, { nodeId: trailer.id, portId: "left" });
    const laid = layoutDocument(state, typed);
    const parentWorld = laid.nodes.get(parent.id)!;
    const headWorld = laid.nodes.get(head.id)!;
    const trailerWorld = laid.nodes.get(trailer.id)!;
    expect(parentWorld.height).toBeGreaterThan(empty.height);
    expect(parentWorld.pockets[0].height).toBeCloseTo(headWorld.stackHeight + GEOM.nestPad, 0);
    expect(parentWorld.width).toBeGreaterThanOrEqual(GEOM.inner + headWorld.width + trailerWorld.width);
    expect(trailerWorld.hatOnly).toBe(true);
    expect(trailerWorld.height).toBe(trailerWorld.hatHeight);
    expect(trailerWorld.y).toBeCloseTo(headWorld.y, 0);
  });

  it("allows nest only on the horizontal-chain head", () => {
    const state = DocumentState.empty();
    const left = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const right = state.addNode(BLOCK_TYPE, { x: 200, y: 0 }, typed);
    const child = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    state.connectPorts(typed, { nodeId: left.id, portId: "right" }, { nodeId: right.id, portId: "left" });
    expect(state.canAcceptNest(left.id, typed)).toBe(true);
    expect(state.canAcceptNest(right.id, typed)).toBe(false);
    expect(canNestInto(state, typed, right.id, "do", BLOCK_TYPE)).toBe(false);
    expect(state.insertInPocket(right.id, "do", child.id, 0, typed)).toBe(false);
    expect(state.parentOf(child.id)).toBeNull();

    const layout = layoutDocument(state, typed);
    const rightWorld = layout.nodes.get(right.id)!;
    const childWorld = layout.nodes.get(child.id)!;
    const ghost = {
      ...childWorld,
      x: rightWorld.x + 8,
      y: rightWorld.y + 8,
      ports: childWorld.ports.map((p) => ({
        ...p,
        x: p.x - childWorld.x + rightWorld.x + 8,
        y: p.y - childWorld.y + rightWorld.y + 8,
      })),
    };
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([child.id]));
    expect(snap?.kind === "nest" && snap.parentId === right.id).toBeFalsy();
  });

  it("rejects a nested parent as a horizontal trailer; empty control can connect as a regular head", () => {
    const state = DocumentState.empty();
    const stack = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const iff = state.addNode(CONTROL_TYPE, { x: 200, y: 0 }, typed);
    const grown = state.addNode(BLOCK_TYPE, { x: 0, y: 80 }, typed);
    const nested = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    state.insertInPocket(grown.id, "do", nested.id, 0, typed);

    expect(state.connectPorts(typed, { nodeId: iff.id, portId: "right" }, { nodeId: stack.id, portId: "left" })).toBeTruthy();
    expect(state.horizontalNext(iff.id, typed)).toBe(stack.id);

    expect(state.connectPorts(typed, { nodeId: stack.id, portId: "right" }, { nodeId: grown.id, portId: "left" })).toBeNull();

    const trailer = state.addNode(BLOCK_TYPE, { x: 400, y: 80 }, typed);
    expect(
      state.connectPorts(typed, { nodeId: iff.id, portId: "right" }, { nodeId: trailer.id, portId: "left" }),
    ).toBeTruthy();

    const head = state.addNode(BLOCK_TYPE, { x: 0, y: 160 }, typed);
    const emptyIf = state.addNode(CONTROL_TYPE, { x: 200, y: 160 }, typed);
    expect(
      state.connectPorts(typed, { nodeId: head.id, portId: "right" }, { nodeId: emptyIf.id, portId: "left" }),
    ).toBeTruthy();
    expect(state.isHorizontalHead(emptyIf.id, typed)).toBe(false);
  });

  it("places a vertical stack below the full grown height of the parent", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const nested = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const below = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    state.insertInPocket(parent.id, "do", nested.id, 0, typed);
    state.connectPorts(typed, { nodeId: parent.id, portId: "bottom" }, { nodeId: below.id, portId: "top" });
    const laid = layoutDocument(state, typed);
    const parentWorld = laid.nodes.get(parent.id)!;
    const belowWorld = laid.nodes.get(below.id)!;
    const nestedWorld = laid.nodes.get(nested.id)!;
    expect(parentWorld.height).toBeGreaterThan(parentWorld.hatHeight + nestedWorld.hatHeight);
    expect(belowWorld.y).toBeCloseTo(parentWorld.y + parentWorld.height, 0);
    expect(belowWorld.y).toBeGreaterThanOrEqual(nestedWorld.y + nestedWorld.height - 1);
    expect(parentWorld.footHeight).toBe(0);
    expect(nestedWorld.y + nestedWorld.height).toBeLessThanOrEqual(parentWorld.y + parentWorld.height + 1);
  });

  it("renders an empty nestable block as a flat rectangle, not a C", () => {
    const state = DocumentState.empty();
    const stack = state.addNode(BLOCK_TYPE, { x: 10, y: 10 }, typed);
    const world = layoutDocument(state, typed).nodes.get(stack.id)!;
    expect(world.wrapped).toBe(false);
    expect(world.height).toBe(world.hatHeight);
    expect(world.footHeight).toBe(0);
    expect(world.pocketHeights).toEqual([]);
  });

  it("nests on an overlapped-right drop and restores a flat rect when dragged out", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const child = state.addNode(BLOCK_TYPE, { x: 300, y: 20 }, typed);
    const layout = layoutDocument(state, typed);
    const parentWorld = layout.nodes.get(parent.id)!;
    const childWorld = layout.nodes.get(child.id)!;
    const gx = parentWorld.x + GEOM.inner + 28;
    const gy = parentWorld.y + 4;
    const ghost = {
      ...childWorld,
      x: gx,
      y: gy,
      ports: childWorld.ports.map((p) => ({
        ...p,
        x: p.x - childWorld.x + gx,
        y: p.y - childWorld.y + gy,
      })),
    };
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([child.id]));
    expect(snap?.kind).toBe("nest");
    if (snap?.kind === "nest") expect(snap.parentId).toBe(parent.id);

    expect(state.insertInPocket(parent.id, "do", child.id, 0, typed)).toBe(true);
    const wrapped = layoutDocument(state, typed).nodes.get(parent.id)!;
    const nested = layoutDocument(state, typed).nodes.get(child.id)!;
    expect(wrapped.wrapped).toBe(true);
    expect(wrapped.height).toBeGreaterThan(parentWorld.height);
    expect(wrapped.footHeight).toBe(0);
    expect(wrapped.height).toBe(wrapped.hatHeight + wrapped.pocketHeights[0]);
    expect(nested.y).toBeCloseTo(wrapped.y + wrapped.hatHeight, 0);
    expect(nested.x).toBeCloseTo(wrapped.x + GEOM.inner, 0);

    state.detachFromParent(child.id);
    const restored = layoutDocument(state, typed).nodes.get(parent.id)!;
    expect(restored.wrapped).toBe(false);
    expect(restored.height).toBe(restored.hatHeight);
    expect(restored.footHeight).toBe(0);
  });

  it("rejects a center nest on a horizontal trailer", () => {
    const state = DocumentState.empty();
    const left = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const right = state.addNode(BLOCK_TYPE, { x: 200, y: 0 }, typed);
    const child = state.addNode(BLOCK_TYPE, { x: 0, y: 80 }, typed);
    state.connectPorts(typed, { nodeId: left.id, portId: "right" }, { nodeId: right.id, portId: "left" });
    const layout = layoutDocument(state, typed);
    const rightWorld = layout.nodes.get(right.id)!;
    const childWorld = layout.nodes.get(child.id)!;
    const cx = rightWorld.x + rightWorld.width / 2;
    const cy = rightWorld.y + rightWorld.height / 2;
    const ghost = {
      ...childWorld,
      x: cx - childWorld.width / 2,
      y: cy - childWorld.hatHeight / 2,
      ports: childWorld.ports.map((p) => ({
        ...p,
        x: p.x - childWorld.x + (cx - childWorld.width / 2),
        y: p.y - childWorld.y + (cy - childWorld.hatHeight / 2),
      })),
    };
    const snap = pickSnapGhost(state, typed, layout, ghost, new Set([child.id]), [], { x: cx, y: cy });
    expect(snap?.kind === "nest" && snap.parentId === right.id).toBeFalsy();
    expect(rightWorld.wrapped).toBe(false);
    expect(rightWorld.pockets).toHaveLength(0);
  });

  it("nest is an open-bottom L, not a U under the child", () => {
    const state = DocumentState.empty();
    const parent = state.addNode(BLOCK_TYPE, { x: 40, y: 20 }, typed);
    const child = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    state.insertInPocket(parent.id, "do", child.id, 0, typed);
    const laid = layoutDocument(state, typed);
    const parentWorld = laid.nodes.get(parent.id)!;
    const childWorld = laid.nodes.get(child.id)!;
    expect(parentWorld.wrapped).toBe(true);
    expect(parentWorld.footHeight).toBe(0);
    const d = nodePath(parentWorld);
    expect(pathClosesFullWidthAt(d, parentWorld.width, parentWorld.height)).toBe(false);
    expect(d).toContain(`L ${GEOM.inner} ${parentWorld.height}`);
    expect(childWorld.y + childWorld.height).toBeLessThanOrEqual(parentWorld.y + parentWorld.height + 1);
  });

  it("control and stack stay open-bottom under nested stacks", () => {
    const state = DocumentState.empty();
    const iff = state.addNode(CONTROL_TYPE, { x: 10, y: 10 }, typed);
    const stack = state.addNode(STACK_TYPE, { x: 260, y: 10 }, typed);
    const thenChild = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    const doChild = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, typed);
    state.insertInPocket(iff.id, "then", thenChild.id, 0, typed);
    state.insertInPocket(stack.id, "do", doChild.id, 0, typed);
    const laid = layoutDocument(state, typed);
    const ifWorld = laid.nodes.get(iff.id)!;
    const stackWorld = laid.nodes.get(stack.id)!;
    expect(ifWorld.footHeight).toBe(0);
    expect(stackWorld.footHeight).toBe(0);
    expect(pathClosesFullWidthAt(nodePath(ifWorld), ifWorld.width, ifWorld.height)).toBe(false);
    expect(pathClosesFullWidthAt(nodePath(stackWorld), stackWorld.width, stackWorld.height)).toBe(false);
  });
});
