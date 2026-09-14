import { describe, expect, it } from "vitest";
import { DocumentState } from "../src/core/model/document";
import { layoutDocument, type WorldNode } from "../src/core/layout";
import { pickSnapGhost } from "../src/core/snap";
import { pickleball } from "./harness";
import { FEATURE_TYPE, SCENARIO_TYPE, STEP_TYPE } from "../src/packs/pickleball";

describe("Pickleball nest-inside a scenario", () => {
  it("hovering a step over a filled scenario body nests inside, not stack-above", () => {
    const state = DocumentState.empty();
    const feature = state.addNode(FEATURE_TYPE, { x: 20, y: 20 }, pickleball);
    const scenario = state.addNode(SCENARIO_TYPE, { x: 0, y: 0 }, pickleball);
    const given = state.addNode(STEP_TYPE, { x: 0, y: 0 }, pickleball, { fields: { keyword: "Given", text: "navigate to: URL.home" } });
    const moving = state.addNode(STEP_TYPE, { x: 400, y: 400 }, pickleball, { fields: { keyword: "When", text: "click" } });
    state.insertInPocket(feature.id, "do", scenario.id, 0, pickleball);
    state.insertInPocket(scenario.id, "do", given.id, 0, pickleball);

    const layout = layoutDocument(state, pickleball);
    const scenarioWorld = layout.nodes.get(scenario.id)!;
    const movingWorld = layout.nodes.get(moving.id)!;
    expect(scenarioWorld.wrapped).toBe(true);

    const givenWorld = layout.nodes.get(given.id)!;
    const probe = {
      x: scenarioWorld.x + scenarioWorld.width - 28,
      y: givenWorld.y + givenWorld.height + 12,
    };
    const ghost = offsetTo(movingWorld, probe.x - movingWorld.width / 2, probe.y - movingWorld.hatHeight / 2);
    const snap = pickSnapGhost(state, pickleball, layout, ghost, new Set([moving.id]), [], probe);
    expect(snap?.kind).toBe("nest");
    if (snap?.kind === "nest") {
      expect(snap.parentId).toBe(scenario.id);
    }
  });

  it("does not offer stacking a step onto a scenario", () => {
    const state = DocumentState.empty();
    const scenario = state.addNode(SCENARIO_TYPE, { x: 40, y: 80 }, pickleball);
    const moving = state.addNode(STEP_TYPE, { x: 40, y: 20 }, pickleball, { fields: { keyword: "Given", text: "go" } });
    const layout = layoutDocument(state, pickleball);
    const scenarioWorld = layout.nodes.get(scenario.id)!;
    const movingWorld = layout.nodes.get(moving.id)!;
    const top = scenarioWorld.ports.find((p) => p.id === "top")!;
    const ghost = offsetTo(
      movingWorld,
      top.x - movingWorld.ports.find((p) => p.id === "bottom")!.local.x,
      top.y - movingWorld.height - 4,
    );
    const snap = pickSnapGhost(state, pickleball, layout, ghost, new Set([moving.id]), [], {
      x: top.x,
      y: top.y - 4,
    });
    if (snap?.kind === "nest") {
      expect(snap.parentId).toBe(scenario.id);
    } else if (snap) {
      expect(snap.highlightNodeId).not.toBe(scenario.id);
    }
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
