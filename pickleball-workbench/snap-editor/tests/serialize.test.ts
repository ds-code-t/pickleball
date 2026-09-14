import { describe, expect, it } from "vitest";
import { DocumentState } from "../src/core/model/document";
import { canonicalize, parseDocument, roundTrip, serializeDocument } from "../src/core/model/serialize";
import type { SnapDocument } from "../src/core/model/types";
import { BLOCK_TYPE, CONTROL_TYPE, typed } from "./harness";

function sample(): SnapDocument {
  return {
    schemaVersion: 1,
    roots: [
      {
        typeId: CONTROL_TYPE,
        id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        nest: {
          then: [{ typeId: BLOCK_TYPE, id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" }],
        },
        s: { typeId: BLOCK_TYPE, id: "cccccccc-cccc-4ccc-8ccc-cccccccccccc" },
        e: { typeId: BLOCK_TYPE, id: "dddddddd-dddd-4ddd-8ddd-dddddddddddd" },
      },
    ],
  };
}

describe("serialize / deserialize", () => {
  it("round-trips to an identical canonical document", () => {
    const doc = sample();
    const canonical = canonicalize(doc, typed);
    const again = roundTrip(doc, typed);
    expect(again).toEqual(canonical);
    expect(serializeDocument(again, typed)).toBe(serializeDocument(doc, typed));
  });

  it("is topology-only: no pixel transform in canonical JSON", () => {
    const canonical = canonicalize(sample(), typed);
    expect(JSON.stringify(canonical)).not.toContain('"transform"');
    expect(canonical.roots[0].typeId).toBe(CONTROL_TYPE);
    expect(canonical.roots[0].e?.id).toBe("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    expect(canonical.roots[0].s?.id).toBe("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    expect(canonical.roots[0].nest?.then?.[0]?.id).toBe("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  });

  it("is deterministic: same document in, same JSON out", () => {
    const a = serializeDocument(sample(), typed);
    const b = serializeDocument(parseDocument(a), typed);
    expect(b).toBe(a);
  });

  it("keeps unique session ids across document operations", () => {
    const state = DocumentState.fromDocument(sample(), typed);
    const created = state.addNode(BLOCK_TYPE, { x: 10, y: 20 }, typed);
    const json = serializeDocument(state.toDocument(), typed);
    const parsed = parseDocument(json);
    const ids: string[] = [];
    const walk = (n: { id: string; nest?: Record<string, { id: string }[]>; e?: { id: string }; s?: { id: string } }) => {
      ids.push(n.id);
      if (n.nest) for (const kids of Object.values(n.nest)) kids.forEach(walk);
      if (n.e) walk(n.e);
      if (n.s) walk(n.s);
    };
    parsed.roots.forEach(walk);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toContain(created.id);
  });
});
