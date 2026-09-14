import type { GlyphDefinition } from "../core/registry/types";
import type { SnapDocument } from "../core/model/types";
import type { Registry } from "../core/registry";

export interface EditorPack {
  id: string;
  label: string;
  register(registry: Registry): void;
  example?(): SnapDocument;
  exportText?(doc: SnapDocument, registry: Registry): string;
  importText?(text: string, registry: Registry): SnapDocument;
  defaultData?(typeId: string): Record<string, string>;
  /** Empty-canvas copy. Say what to drag, not jargon. */
  emptyHint?: string;
  /** Idle inspector line. */
  inspectorHint?: string;
  /** @deprecated Prefer register(). Kept for pack authors migrating defs. */
  definitions?: GlyphDefinition[];
}
