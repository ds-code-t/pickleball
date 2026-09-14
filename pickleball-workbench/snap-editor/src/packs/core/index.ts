import { registerType, Registry, statementPocket } from "../../core/registry";
import type { EditorPack } from "../types";
import type { SnapDocument } from "../../core/model/types";
import { SCHEMA_VERSION } from "../../core/model/types";

export const BLOCK_TYPE = "block";

const BLOCK_COLOR = { fill: "#5b8def", stroke: "#3d6bc4", highlight: "#8bb4ff" };

export function registerCoreTypes(registry: Registry, toolbox = true): void {
  registerType(registry, {
    type: BLOCK_TYPE,
    label: "Block",
    category: "Blocks",
    toolbox,
    hint: "Snap below, beside, or inside another block",
    color: BLOCK_COLOR,
    glyph: { kind: "rounded-rect", width: 176, hatHeight: 46, footHeight: 0, cornerRadius: 8 },
    pockets: [statementPocket("do", { label: "do" })],
  });
}

export function createCoreRegistry(): Registry {
  const registry = new Registry();
  registerCoreTypes(registry, true);
  return registry;
}

export function coreExample(): SnapDocument {
  return {
    schemaVersion: SCHEMA_VERSION,
    roots: [
      {
        typeId: BLOCK_TYPE,
        id: "core-root",
        nest: {
          do: [
            {
              typeId: BLOCK_TYPE,
              id: "core-child",
              s: { typeId: BLOCK_TYPE, id: "core-below-child" },
            },
          ],
        },
        e: { typeId: BLOCK_TYPE, id: "core-right" },
        s: { typeId: BLOCK_TYPE, id: "core-below" },
      },
    ],
  };
}

export const corePack: EditorPack = {
  id: "core",
  label: "Core",
  register: (registry) => registerCoreTypes(registry, true),
  example: coreExample,
  emptyHint: "Drag a Block from the left",
  inspectorHint: "Drag blocks to snap them together. Esc, then Delete removes a block.",
};
