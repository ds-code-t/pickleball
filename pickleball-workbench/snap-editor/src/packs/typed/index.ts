import { registerType, Registry, statementPocket } from "../../core/registry";
import { BLOCK_TYPE, registerCoreTypes } from "../core";
import type { EditorPack } from "../types";
import type { SnapDocument } from "../../core/model/types";
import { SCHEMA_VERSION } from "../../core/model/types";

export const STATEMENT_TYPE = "statement";
export const STACK_TYPE = "stack";
export const CONTROL_TYPE = "control";

export function registerTypedTypes(registry: Registry): void {
  registerCoreTypes(registry, false);
  registerType(registry, {
    type: STATEMENT_TYPE,
    extends: BLOCK_TYPE,
    label: "Statement",
    category: "Blocks",
    toolbox: true,
    hint: "Snaps below another block",
    color: { fill: "#4C97FF", stroke: "#3373CC", highlight: "#82B5FF" },
    pockets: [],
  });
  registerType(registry, {
    type: STACK_TYPE,
    extends: BLOCK_TYPE,
    label: "Stack",
    category: "Blocks",
    toolbox: true,
    hint: "Drop blocks inside",
    color: { fill: "#59C059", stroke: "#3a9b3a", highlight: "#8fe08f" },
    pockets: [statementPocket("do", { label: "do" })],
  });
  registerType(registry, {
    type: CONTROL_TYPE,
    extends: BLOCK_TYPE,
    label: "Control",
    category: "Logic",
    toolbox: true,
    alwaysFrame: true,
    hint: "Branches nest inside",
    color: { fill: "#FFAB19", stroke: "#CF8B17", highlight: "#FFD36A" },
    glyph: {
      kind: "rounded-rect",
      width: 300,
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
    controls: [
      {
        id: "addElseIf",
        kind: "button",
        label: "+ else if",
        action: "addRepeatablePocket",
        pocket: "elseif",
        x: 88,
        y: 9,
        width: 86,
        height: 26,
      },
      {
        id: "addElse",
        kind: "button",
        label: "+ else",
        action: "addRepeatablePocket",
        pocket: "else",
        x: 178,
        y: 9,
        width: 88,
        height: 26,
      },
    ],
  });
}

export function createTypedRegistry(): Registry {
  const registry = new Registry();
  registerTypedTypes(registry);
  return registry;
}

export function typedExample(): SnapDocument {
  return {
    schemaVersion: SCHEMA_VERSION,
    roots: [
      {
        typeId: CONTROL_TYPE,
        id: "typed-if",
        nest: {
          then: [{ typeId: STATEMENT_TYPE, id: "typed-then" }],
          else: [{ typeId: STACK_TYPE, id: "typed-else", nest: { do: [{ typeId: STATEMENT_TYPE, id: "typed-nested" }] } }],
        },
        s: { typeId: STACK_TYPE, id: "typed-below" },
      },
    ],
  };
}

export const typedPack: EditorPack = {
  id: "typed",
  label: "Typed",
  register: registerTypedTypes,
  example: typedExample,
  emptyHint: "Drag a Statement, Stack, or Control from the left",
  inspectorHint: "Stacks nest. Statements snap below. Esc, then Delete removes a block.",
};
