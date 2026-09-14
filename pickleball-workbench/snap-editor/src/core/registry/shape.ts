import type { PocketDefinition, PortDefinition, RectGlyph } from "./types";

export const CARDINAL_PORTS: PortDefinition[] = [
  { id: "top", kind: "stack", direction: "in", side: "top" },
  { id: "bottom", kind: "stack", direction: "out", side: "bottom" },
  { id: "left", kind: "value", direction: "in", side: "left" },
  { id: "right", kind: "value", direction: "out", side: "right" },
];

export const DEFAULT_GLYPH: RectGlyph = {
  kind: "rounded-rect",
  width: 168,
  hatHeight: 46,
  footHeight: 0,
  cornerRadius: 8,
};

export function statementPocket(id: string, extra: Partial<PocketDefinition> = {}): PocketDefinition {
  return {
    id,
    kind: "statement",
    label: extra.label ?? id,
    limits: { maxChildren: null, allowedTypes: null, maxDepth: null },
    ...extra,
  };
}
