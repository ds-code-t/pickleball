import { describe, expect, it } from "vitest";
import { DocumentState } from "../src/core/model/document";
import { canonicalize } from "../src/core/model/serialize";
import {
  exportGherkin,
  importGherkin,
  pickleballPack,
  WORKBENCH_SAMPLE,
  FEATURE_TYPE,
  STEP_TYPE,
  PHRASE_TYPE,
  IF_TYPE,
  TAGS_TYPE,
  COMMENT_TYPE,
  BACKGROUND_TYPE,
  OUTLINE_TYPE,
  EXAMPLES_TYPE,
  TABLE_TYPE,
  DOCSTRING_TYPE,
  SCENARIO_TYPE,
} from "../src/packs/pickleball";
import { pickleball } from "./harness";

function roundTrip(text: string): string {
  return exportGherkin(importGherkin(text));
}

describe("pickleball gherkin pack", () => {
  it("round-trips the workbench sample with quoted commas, semicolons, and nested colons", () => {
    const imported = importGherkin(WORKBENCH_SAMPLE);
    const text = exportGherkin(imported);
    expect(text).toContain("Feature: Workbench Live Scenario");
    expect(text).toContain("Given navigate to: URL.home");
    expect(text).toContain(`click the "Open Forms Playground" Link`);
    expect(text).toContain(`"Save, now"`);
    expect(text).toContain("; wait for page");
    expect(text).toContain("* IF: 1 == 1");
    expect(text).toContain("* ELSE-IF: 2 == 2");
    expect(text).toContain("* ELSE:");
    const again = exportGherkin(importGherkin(text));
    expect(again).toBe(text);
    expect(again).toContain("Feature: Workbench Live Scenario");
    expect(again).toContain(`"Save, now"`);
    expect(again).toContain("; wait for page");
  });

  it("round-trips tags, Background, and Scenario", () => {
    const src = `@fast @ui
Feature: Tagged
  Background:
    Given setup
  @slow
  Scenario: One
    When go
`;
    const doc = importGherkin(src);
    expect(doc.roots[0].typeId).toBe(TAGS_TYPE);
    expect(doc.roots[0].data?.text).toBe("@fast @ui");
    expect(doc.roots[1].typeId).toBe(FEATURE_TYPE);
    const kids = doc.roots[1].nest?.do ?? [];
    expect(kids[0]?.typeId).toBe(BACKGROUND_TYPE);
    expect(kids[0]?.nest?.do?.[0]?.data?.text).toBe("setup");
    expect(kids[1]?.typeId).toBe(TAGS_TYPE);
    expect(kids[1]?.data?.text).toBe("@slow");
    expect(kids[2]?.typeId).toBe(SCENARIO_TYPE);
    const out = exportGherkin(doc);
    expect(out).toContain("@fast @ui");
    expect(out).toContain("Background:");
    expect(out).toContain("    Given setup");
    expect(out).toContain("  @slow");
    expect(out).toContain("  Scenario: One");
    expect(out).toContain("    When go");
    expect(roundTrip(out)).toBe(out);
  });

  it("round-trips Scenario Outline and Examples", () => {
    const src = `Feature: Shop
  Scenario Outline: buy <item>
    Given <item>
    Examples:
      | item |
      | apple |
      | pear |
`;
    const doc = importGherkin(src);
    const outline = doc.roots[0].nest?.do?.[0];
    expect(outline?.typeId).toBe(OUTLINE_TYPE);
    const step = outline?.nest?.do?.[0];
    expect(step?.typeId).toBe(STEP_TYPE);
    const examples = step?.s;
    expect(examples?.typeId).toBe(EXAMPLES_TYPE);
    const table = examples?.nest?.do?.[0];
    expect(table?.typeId).toBe(TABLE_TYPE);
    expect(JSON.parse(table?.data?.rows ?? "[]")).toEqual([
      ["item"],
      ["apple"],
      ["pear"],
    ]);
    const out = exportGherkin(doc);
    expect(out).toContain("Scenario Outline: buy <item>");
    expect(out).toContain("Examples:");
    expect(out).toMatch(/\| item\s*\|/);
    expect(out).toMatch(/\| apple\s*\|/);
    expect(out).toMatch(/\| pear\s*\|/);
    expect(roundTrip(out)).toBe(out);
  });

  it("round-trips a DataTable under a step", () => {
    const src = `Feature: Data
  Scenario: Table
    Given the following users:
      | name | age |
      | Ann  | 2   |
`;
    const doc = importGherkin(src);
    const step = doc.roots[0].nest?.do?.[0]?.nest?.do?.[0];
    const table = step?.nest?.do?.[0];
    expect(table?.typeId).toBe(TABLE_TYPE);
    expect(JSON.parse(table?.data?.rows ?? "[]")).toEqual([
      ["name", "age"],
      ["Ann", "2"],
    ]);
    const out = exportGherkin(doc);
    expect(out).toMatch(/\| name \| age \|/);
    expect(out).toMatch(/\| Ann\s+\| 2\s+\|/);
    expect(roundTrip(out)).toBe(out);
  });

  it("round-trips comment lines", () => {
    const src = `# header note
Feature: Commented
  # inner
  Scenario: S
    # step comment
    Given x
`;
    const doc = importGherkin(src);
    expect(doc.roots[0].typeId).toBe(COMMENT_TYPE);
    expect(doc.roots[0].data?.text).toBe("header note");
    const kids = doc.roots[1].nest?.do ?? [];
    expect(kids[0]?.typeId).toBe(COMMENT_TYPE);
    expect(kids[0]?.data?.text).toBe("inner");
    const scenario = kids[1];
    expect(scenario?.typeId).toBe(SCENARIO_TYPE);
    expect(scenario?.nest?.do?.[0]?.typeId).toBe(COMMENT_TYPE);
    expect(scenario?.nest?.do?.[0]?.data?.text).toBe("step comment");
    const out = exportGherkin(doc);
    expect(out).toContain("# header note");
    expect(out).toContain("  # inner");
    expect(out).toContain("    # step comment");
    expect(roundTrip(out)).toBe(out);
  });

  it("round-trips a DocString under a step", () => {
    const src = `Feature: Docs
  Scenario: Blob
    Given a blob:
      """
      hello
      world
      """
`;
    const doc = importGherkin(src);
    const step = doc.roots[0].nest?.do?.[0]?.nest?.do?.[0];
    const blob = step?.nest?.do?.[0];
    expect(blob?.typeId).toBe(DOCSTRING_TYPE);
    expect(blob?.data?.delimiter).toBe('"""');
    expect(blob?.data?.text).toBe("hello\nworld");
    const out = exportGherkin(doc);
    expect(out).toContain('"""');
    expect(out).toContain("hello");
    expect(out).toContain("world");
    expect(roundTrip(out)).toBe(out);
  });

  it("imports a comma dynamic chain as an east phrase list", () => {
    const src = `Feature: Demo
Scenario: Nested
  When , click the "a, b" Button; wait
  When , parent step:
  : Then , child step
`;
    const imported = importGherkin(src);
    expect(imported.roots[0].typeId).toBe(FEATURE_TYPE);
    const scenario = imported.roots[0].nest?.do?.[0];
    const when = scenario?.nest?.do?.[0];
    expect(when?.typeId).toBe(STEP_TYPE);
    expect(when?.data?.text).toBe(`, click the "a, b" Button`);
    expect(when?.e?.typeId).toBe(PHRASE_TYPE);
    expect(when?.e?.data?.text).toBe(`; wait`);
    const parent = when?.s;
    expect(parent?.nest?.do?.[0]?.data?.text).toContain(", child step");
  });

  it("mints new instance ids on import", () => {
    const a = importGherkin(WORKBENCH_SAMPLE);
    const b = importGherkin(WORKBENCH_SAMPLE);
    expect(a.roots[0].id).not.toBe(b.roots[0].id);
    expect(exportGherkin(a)).toBe(exportGherkin(b));
  });

  it("adds extra else-if pockets from a control action", () => {
    const state = DocumentState.empty();
    const iff = state.addNode(IF_TYPE, { x: 0, y: 0 }, pickleball, { fields: { condition: "1 == 1" } });
    expect(state.pocketIds(iff.id, pickleball)).toEqual(["then", "else"]);
    const added = state.addRepeatablePocket(iff.id, "elseif", pickleball);
    expect(added).toBe("elseif-1");
    expect(state.pocketIds(iff.id, pickleball)).toEqual(["then", "elseif-1", "else"]);
    const child = state.addNode(STEP_TYPE, { x: 0, y: 0 }, pickleball, {
      fields: { keyword: "*", text: "branch" },
    });
    expect(state.insertInPocket(iff.id, added!, child.id, 0, pickleball)).toBe(true);
    expect(state.parentOf(child.id)?.pocketId).toBe("elseif-1");
    const extraElse = state.addRepeatablePocket(iff.id, "else", pickleball);
    expect(extraElse).toBe("else-2");
    expect(state.pocketIds(iff.id, pickleball)).toEqual(["then", "elseif-1", "else", "else-2"]);
  });

  it("canonical JSON from the sample has no pixels and uses pack type ids", () => {
    const doc = canonicalize(pickleballPack.example!(), pickleball);
    expect(JSON.stringify(doc)).not.toContain('"transform"');
    expect(doc.roots[0].typeId).toBe(FEATURE_TYPE);
  });
});
