import { describe, expect, it } from "vitest";
import { assertUniqueIds, createId } from "../src/core/model/ids";
import { DocumentState } from "../src/core/model/document";
import { BLOCK_TYPE, core } from "./harness";

describe("ids", () => {
  it("creates unique ids", () => {
    const ids = Array.from({ length: 200 }, () => createId());
    expect(new Set(ids).size).toBe(200);
    assertUniqueIds(ids, "generated");
  });

  it("does not regenerate ids when adding sibling nodes", () => {
    const state = DocumentState.empty();
    const a = state.addNode(BLOCK_TYPE, { x: 0, y: 0 }, core);
    const b = state.addNode(BLOCK_TYPE, { x: 20, y: 20 }, core);
    expect(a.id).not.toBe(b.id);
    expect(state.getNode(a.id).id).toBe(a.id);
    expect(state.getNode(b.id).id).toBe(b.id);
  });

  it("rejects duplicate ids", () => {
    expect(() => assertUniqueIds(["a", "a"], "nodes")).toThrow(/duplicate/);
  });
});
