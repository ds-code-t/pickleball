import { registerType, Registry, statementPocket } from "../../core/registry";
import { BLOCK_TYPE, registerCoreTypes } from "../core";
import type { EditorPack } from "../types";
import { exportGherkin, importGherkin } from "./gherkin";
import { WORKBENCH_SAMPLE } from "./sample";
import {
  BACKGROUND_TYPE,
  COMMENT_TYPE,
  DOCSTRING_TYPE,
  EXAMPLES_TYPE,
  FEATURE_TYPE,
  IF_TYPE,
  OUTLINE_TYPE,
  PHRASE_TYPE,
  RULE_TYPE,
  SCENARIO_TYPE,
  STEP_TYPE,
  TABLE_TYPE,
  TAGS_TYPE,
} from "./types";

export { exportGherkin, importGherkin } from "./gherkin";
export { splitPhrases, joinPhrases, isDynamicPhraseText } from "./phrases";
export { WORKBENCH_SAMPLE } from "./sample";
export {
  BACKGROUND_TYPE,
  COMMENT_TYPE,
  DOCSTRING_TYPE,
  EXAMPLES_TYPE,
  FEATURE_TYPE,
  IF_TYPE,
  OUTLINE_TYPE,
  PHRASE_TYPE,
  RULE_TYPE,
  SCENARIO_TYPE,
  STEP_TYPE,
  TABLE_TYPE,
  TAGS_TYPE,
} from "./types";

function allow(...typeIds: string[]): Array<{ mode: "allow"; typeId: string }> {
  return typeIds.map((typeId) => ({ mode: "allow", typeId }));
}

const STORY_TYPES = [SCENARIO_TYPE, OUTLINE_TYPE, BACKGROUND_TYPE, RULE_TYPE, TAGS_TYPE, COMMENT_TYPE];
const RULE_CHILDREN = [SCENARIO_TYPE, OUTLINE_TYPE, BACKGROUND_TYPE, TAGS_TYPE, COMMENT_TYPE];
const BODY_TYPES = [STEP_TYPE, IF_TYPE, COMMENT_TYPE, TABLE_TYPE, DOCSTRING_TYPE, EXAMPLES_TYPE];
const STEP_PARENTS = [SCENARIO_TYPE, OUTLINE_TYPE, BACKGROUND_TYPE, STEP_TYPE, IF_TYPE];

export function registerPickleballTypes(registry: Registry): void {
  registerCoreTypes(registry, false);
  registerType(registry, {
    type: FEATURE_TYPE,
    extends: BLOCK_TYPE,
    label: "Feature",
    category: "Story",
    toolbox: true,
    badge: "Feature",
    hint: "Drop scenarios inside",
    permissionType: "feature",
    color: { fill: "#9966FF", stroke: "#774DCB", highlight: "#c2a3ff" },
    glyph: { kind: "rounded-rect", width: 300, hatHeight: 46, footHeight: 0, cornerRadius: 8 },
    pockets: [statementPocket("do", { label: "scenarios" })],
    interfaces: {
      "nest-out": allow(...STORY_TYPES),
      n: [{ mode: "deny" }],
      s: [{ mode: "deny" }],
    },
    controls: [{ id: "text", kind: "text", field: "text", label: "name", x: 86, y: 9, width: 196, height: 28 }],
  });
  registerType(registry, {
    type: RULE_TYPE,
    extends: BLOCK_TYPE,
    label: "Rule",
    category: "Story",
    toolbox: true,
    badge: "Rule",
    hint: "Groups scenarios under a feature",
    permissionType: "rule",
    color: { fill: "#7C5CBF", stroke: "#5A3F96", highlight: "#b39ddb" },
    glyph: { kind: "rounded-rect", width: 300, hatHeight: 46, footHeight: 0, cornerRadius: 8 },
    pockets: [statementPocket("do", { label: "scenarios" })],
    interfaces: {
      "nest-in": allow(FEATURE_TYPE),
      "nest-out": allow(...RULE_CHILDREN),
      n: allow(...STORY_TYPES),
      s: allow(...STORY_TYPES),
    },
    controls: [{ id: "text", kind: "text", field: "text", label: "name", x: 62, y: 9, width: 220, height: 28 }],
  });
  registerType(registry, {
    type: BACKGROUND_TYPE,
    extends: BLOCK_TYPE,
    label: "Background",
    category: "Story",
    toolbox: true,
    badge: "Background",
    hint: "Shared steps for scenarios",
    permissionType: "background",
    color: { fill: "#0E9AA7", stroke: "#0A7A84", highlight: "#7fdbda" },
    glyph: { kind: "rounded-rect", width: 300, hatHeight: 46, footHeight: 0, cornerRadius: 8 },
    pockets: [statementPocket("do", { label: "steps" })],
    interfaces: {
      "nest-in": allow(FEATURE_TYPE, RULE_TYPE),
      "nest-out": allow(...BODY_TYPES),
      n: allow(...STORY_TYPES),
      s: allow(...STORY_TYPES),
    },
    controls: [{ id: "text", kind: "text", field: "text", label: "name", x: 118, y: 9, width: 164, height: 28 }],
  });
  registerType(registry, {
    type: SCENARIO_TYPE,
    extends: BLOCK_TYPE,
    label: "Scenario",
    category: "Story",
    toolbox: true,
    badge: "Scenario",
    hint: "Drop steps inside",
    permissionType: "scenario",
    color: { fill: "#FF8C1A", stroke: "#CF6D00", highlight: "#ffc07a" },
    glyph: { kind: "rounded-rect", width: 300, hatHeight: 46, footHeight: 0, cornerRadius: 8 },
    pockets: [statementPocket("do", { label: "steps" })],
    interfaces: {
      "nest-in": allow(FEATURE_TYPE, RULE_TYPE),
      "nest-out": allow(...BODY_TYPES),
      n: allow(...STORY_TYPES),
      s: allow(...STORY_TYPES),
    },
    controls: [{ id: "text", kind: "text", field: "text", label: "name", x: 96, y: 9, width: 188, height: 28 }],
  });
  registerType(registry, {
    type: OUTLINE_TYPE,
    extends: BLOCK_TYPE,
    label: "Scenario Outline",
    category: "Story",
    toolbox: true,
    badge: "Outline",
    hint: "Parameterized scenario with Examples",
    permissionType: "outline",
    color: { fill: "#FF6B35", stroke: "#CC4E1F", highlight: "#ffb199" },
    glyph: { kind: "rounded-rect", width: 320, hatHeight: 46, footHeight: 0, cornerRadius: 8 },
    pockets: [statementPocket("do", { label: "steps" })],
    interfaces: {
      "nest-in": allow(FEATURE_TYPE, RULE_TYPE),
      "nest-out": allow(...BODY_TYPES),
      n: allow(...STORY_TYPES),
      s: allow(...STORY_TYPES),
    },
    controls: [{ id: "text", kind: "text", field: "text", label: "name", x: 92, y: 9, width: 210, height: 28 }],
  });
  registerType(registry, {
    type: EXAMPLES_TYPE,
    extends: BLOCK_TYPE,
    label: "Examples",
    category: "Story",
    toolbox: true,
    badge: "Examples",
    hint: "Outline table of values",
    permissionType: "examples",
    color: { fill: "#C9A227", stroke: "#9A7A1A", highlight: "#ead37a" },
    glyph: { kind: "rounded-rect", width: 300, hatHeight: 46, footHeight: 0, cornerRadius: 8 },
    pockets: [statementPocket("do", { label: "table" })],
    interfaces: {
      "nest-in": allow(OUTLINE_TYPE),
      "nest-out": allow(TABLE_TYPE, COMMENT_TYPE),
      n: allow(...BODY_TYPES, ...STORY_TYPES),
      s: allow(...BODY_TYPES, ...STORY_TYPES),
    },
    controls: [{ id: "text", kind: "text", field: "text", label: "title", x: 104, y: 9, width: 178, height: 28 }],
  });
  registerType(registry, {
    type: STEP_TYPE,
    extends: BLOCK_TYPE,
    label: "Step",
    category: "Steps",
    toolbox: true,
    hint: "Stacks below · phrases chain to the right",
    permissionType: "step",
    color: { fill: "#4C97FF", stroke: "#3373CC", highlight: "#82B5FF" },
    glyph: { kind: "rounded-rect", width: 340, hatHeight: 46, footHeight: 0, cornerRadius: 8 },
    pockets: [statementPocket("do", { label: "do" })],
    interfaces: {
      "nest-in": allow(...STEP_PARENTS),
      "nest-out": allow(STEP_TYPE, IF_TYPE, TABLE_TYPE, DOCSTRING_TYPE, COMMENT_TYPE),
      n: allow(...BODY_TYPES),
      s: allow(...BODY_TYPES),
      e: allow(PHRASE_TYPE, STEP_TYPE),
    },
    controls: [
      {
        id: "keyword",
        kind: "dropdown",
        field: "keyword",
        options: ["Given", "When", "Then", "And", "But", "*"],
        x: 10,
        y: 9,
        width: 70,
        height: 28,
      },
      { id: "text", kind: "text", field: "text", label: "what happens", x: 88, y: 9, width: 232, height: 28 },
    ],
  });
  registerType(registry, {
    type: PHRASE_TYPE,
    extends: BLOCK_TYPE,
    label: "Phrase",
    category: "Steps",
    toolbox: true,
    hint: "Continues a step to the right",
    permissionType: "phrase",
    color: { fill: "#76b9f0", stroke: "#4a8ec4", highlight: "#a9d6f7" },
    glyph: { kind: "rounded-rect", width: 220, hatHeight: 46, footHeight: 0, cornerRadius: 8 },
    pockets: [],
    interfaces: {
      "nest-in": [{ mode: "deny" }],
      "nest-out": [{ mode: "deny" }],
      n: [{ mode: "deny" }],
      s: [{ mode: "deny" }],
      w: allow(STEP_TYPE, PHRASE_TYPE),
      e: allow(PHRASE_TYPE),
    },
    controls: [{ id: "text", kind: "text", field: "text", label: "continue the step", x: 10, y: 9, width: 200, height: 28 }],
  });
  registerType(registry, {
    type: IF_TYPE,
    extends: BLOCK_TYPE,
    label: "If / else",
    category: "Logic",
    toolbox: true,
    badge: "IF",
    alwaysFrame: true,
    hint: "Drop steps in each branch",
    permissionType: "control",
    color: { fill: "#FFAB19", stroke: "#CF8B17", highlight: "#FFD36A" },
    glyph: {
      kind: "rounded-rect",
      width: 420,
      hatHeight: 46,
      footHeight: 0,
      midHeight: 32,
      cornerRadius: 8,
    },
    pockets: [
      statementPocket("then", { label: "if" }),
      statementPocket("elseif", { label: "else if", repeatable: true, controlField: true }),
      statementPocket("else", { label: "else" }),
    ],
    interfaces: {
      "nest-out": allow(STEP_TYPE, IF_TYPE, COMMENT_TYPE),
      n: allow(...BODY_TYPES),
      s: allow(...BODY_TYPES),
    },
    controls: [
      { id: "condition", kind: "text", field: "condition", label: "when this is true", x: 40, y: 9, width: 188, height: 28 },
      {
        id: "addElseIf",
        kind: "button",
        label: "+ else if",
        action: "addRepeatablePocket",
        pocket: "elseif",
        x: 232,
        y: 9,
        width: 86,
        height: 28,
      },
      {
        id: "addElse",
        kind: "button",
        label: "+ else",
        action: "addRepeatablePocket",
        pocket: "else",
        x: 322,
        y: 9,
        width: 80,
        height: 28,
      },
    ],
  });
  registerType(registry, {
    type: TAGS_TYPE,
    extends: BLOCK_TYPE,
    label: "Tags",
    category: "Story",
    toolbox: false,
    badge: "@",
    hint: "Cucumber tags for the next block",
    permissionType: "tags",
    color: { fill: "#5A8F7B", stroke: "#3D6B5C", highlight: "#9fd0be" },
    glyph: { kind: "rounded-rect", width: 260, hatHeight: 40, footHeight: 0, cornerRadius: 8 },
    pockets: [],
    interfaces: {
      "nest-in": allow(FEATURE_TYPE, RULE_TYPE, OUTLINE_TYPE),
      "nest-out": [{ mode: "deny" }],
      n: allow(...STORY_TYPES, ...BODY_TYPES),
      s: allow(...STORY_TYPES, ...BODY_TYPES),
    },
    controls: [{ id: "text", kind: "text", field: "text", label: "@tags", x: 36, y: 6, width: 212, height: 28 }],
  });
  registerType(registry, {
    type: COMMENT_TYPE,
    extends: BLOCK_TYPE,
    label: "Comment",
    category: "Story",
    toolbox: false,
    badge: "#",
    hint: "A # comment or description line",
    permissionType: "comment",
    color: { fill: "#5C677A", stroke: "#3D4553", highlight: "#9aa4b5" },
    glyph: { kind: "rounded-rect", width: 280, hatHeight: 40, footHeight: 0, cornerRadius: 8 },
    pockets: [],
    interfaces: {
      "nest-in": allow(FEATURE_TYPE, RULE_TYPE, SCENARIO_TYPE, OUTLINE_TYPE, BACKGROUND_TYPE, STEP_TYPE, IF_TYPE, EXAMPLES_TYPE),
      "nest-out": [{ mode: "deny" }],
      n: allow(...STORY_TYPES, ...BODY_TYPES),
      s: allow(...STORY_TYPES, ...BODY_TYPES),
    },
    controls: [{ id: "text", kind: "text", field: "text", label: "comment", x: 36, y: 6, width: 232, height: 28 }],
  });
  registerType(registry, {
    type: TABLE_TYPE,
    extends: BLOCK_TYPE,
    label: "Table",
    category: "Steps",
    toolbox: false,
    badge: "Table",
    hint: "DataTable or Examples rows",
    permissionType: "table",
    color: { fill: "#2A9D8F", stroke: "#1D7268", highlight: "#80cbc4" },
    glyph: { kind: "rounded-rect", width: 360, hatHeight: 40, footHeight: 0, cornerRadius: 8 },
    pockets: [],
    interfaces: {
      "nest-in": allow(STEP_TYPE, EXAMPLES_TYPE),
      "nest-out": [{ mode: "deny" }],
      n: allow(...BODY_TYPES),
      s: allow(...BODY_TYPES),
    },
    controls: [{ id: "rows", kind: "text", field: "rows", label: "rows", x: 70, y: 6, width: 278, height: 28 }],
  });
  registerType(registry, {
    type: DOCSTRING_TYPE,
    extends: BLOCK_TYPE,
    label: "DocString",
    category: "Steps",
    toolbox: false,
    badge: '"""',
    hint: "Multiline step argument",
    permissionType: "docstring",
    color: { fill: "#457B9D", stroke: "#2F5770", highlight: "#90caf9" },
    glyph: { kind: "rounded-rect", width: 360, hatHeight: 40, footHeight: 0, cornerRadius: 8 },
    pockets: [],
    interfaces: {
      "nest-in": allow(STEP_TYPE),
      "nest-out": [{ mode: "deny" }],
      n: allow(...BODY_TYPES),
      s: allow(...BODY_TYPES),
    },
    controls: [{ id: "text", kind: "text", field: "text", label: "content", x: 56, y: 6, width: 292, height: 28 }],
  });
}

export function createPickleballRegistry(): Registry {
  const registry = new Registry();
  registerPickleballTypes(registry);
  return registry;
}

export const pickleballPack: EditorPack = {
  id: "pickleball",
  label: "Pickleball",
  register: registerPickleballTypes,
  example: () => importGherkin(WORKBENCH_SAMPLE),
  exportText: (doc) => exportGherkin(doc),
  importText: (text) => importGherkin(text),
  emptyHint: "Drag a Feature, Scenario, or Step from the left",
  inspectorHint: "Click a step to type. Drag to snap it under another step. Esc, then Delete removes a block.",
  defaultData: (typeId: string): Record<string, string> => {
    if (typeId === STEP_TYPE) return { keyword: "Given", text: "" };
    if (typeId === PHRASE_TYPE) return { text: ", " };
    if (typeId === IF_TYPE) return { condition: "" };
    if (typeId === FEATURE_TYPE) return { text: "Workbench Live Scenario" };
    if (typeId === SCENARIO_TYPE) return { text: "New scenario" };
    if (typeId === OUTLINE_TYPE) return { text: "New outline" };
    if (typeId === RULE_TYPE) return { text: "New rule" };
    if (typeId === BACKGROUND_TYPE) return { text: "" };
    if (typeId === EXAMPLES_TYPE) return { text: "" };
    if (typeId === TAGS_TYPE) return { text: "@tag" };
    if (typeId === COMMENT_TYPE) return { text: "", hash: "1" };
    if (typeId === TABLE_TYPE) return { rows: "[]" };
    if (typeId === DOCSTRING_TYPE) return { delimiter: '"""', mediaType: "", text: "" };
    return {};
  },
};
