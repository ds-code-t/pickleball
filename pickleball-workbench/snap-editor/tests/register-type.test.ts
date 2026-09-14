import { describe, expect, it } from "vitest";
import { Registry, registerType } from "../src/core/registry";
import { BLOCK_TYPE, createCoreRegistry } from "../src/packs/core";
import { dualPermission } from "../src/core/permissions";
import { DocumentState } from "../src/core/model/document";
import { validateMandatory } from "../src/core/validation";

describe("registerType extends a parent type id", () => {
  it("merges look and mouths from the parent", () => {
    const registry = createCoreRegistry();
    const child = registerType(registry, {
      type: "blue-block",
      extends: BLOCK_TYPE,
      label: "Blue",
      color: { fill: "#123456" },
    });
    expect(child.extends).toBe(BLOCK_TYPE);
    expect(child.pockets.map((p) => p.id)).toEqual(["do"]);
    expect(child.ports).toHaveLength(4);
    expect(child.color.fill).toBe("#123456");
    expect(child.color.stroke).toBe(registry.get(BLOCK_TYPE).color.stroke);
    expect(registry.get("blue-block").label).toBe("Blue");
  });

  it("throws when the parent type id is missing", () => {
    const registry = new Registry();
    expect(() => registerType(registry, { type: "x", extends: "missing" })).toThrow(/unknown parent/);
  });
});

describe("interface permissions", () => {
  it("dual invite requires both sides unless override skips an invite", () => {
    const registry = new Registry();
    registerType(registry, {
      type: "host",
      interfaces: { e: [{ mode: "allow", typeId: "guest" }] },
    });
    registerType(registry, {
      type: "guest",
      interfaces: { w: [{ mode: "allow", typeId: "other" }] },
    });
    expect(
      dualPermission(registry, { type: "host" }, "e", { type: "guest" }, "w"),
    ).toBe(false);
    registerType(registry, {
      type: "guest-over",
      interfaces: { w: [{ mode: "allow", typeId: "host", override: true }] },
    });
    expect(
      dualPermission(registry, { type: "host" }, "e", { type: "guest-over" }, "w"),
    ).toBe(true);
  });

  it("a deny is a veto even with override", () => {
    const registry = new Registry();
    registerType(registry, {
      type: "host",
      interfaces: { e: [{ mode: "deny", typeId: "guest" }] },
    });
    registerType(registry, {
      type: "guest",
      interfaces: { w: [{ mode: "allow", typeId: "host", override: true }] },
    });
    expect(dualPermission(registry, { type: "host" }, "e", { type: "guest" }, "w")).toBe(false);
  });

  it("mandatory is validation, not a snap veto", () => {
    const registry = createCoreRegistry();
    registerType(registry, {
      type: "must-south",
      extends: BLOCK_TYPE,
      interfaces: { s: [{ mode: "mandatory" }, { mode: "allow" }] },
    });
    const state = DocumentState.empty();
    const a = state.addNode("must-south", { x: 0, y: 0 }, registry);
    const b = state.addNode(BLOCK_TYPE, { x: 0, y: 80 }, registry);
    expect(
      state.connectPorts(registry, { nodeId: a.id, portId: "bottom" }, { nodeId: b.id, portId: "top" }),
    ).toBeTruthy();
    const lonely = state.addNode("must-south", { x: 200, y: 0 }, registry);
    const issues = validateMandatory(state, registry);
    expect(issues.some((issue) => issue.nodeId === lonely.id && issue.interfaceId === "s")).toBe(true);
    expect(issues.some((issue) => issue.nodeId === a.id && issue.interfaceId === "s")).toBe(false);
  });
});
