/**
 * Type-defs for nestable blocks. Packs call `registerType()`; a def may
 * `extends` a parent type id (look / ports / mouths / controls merge).
 * Instances are not live JS subclasses.
 */

export type GlyphKind = "rounded-rect";
export type PortKind = "stack" | "value";
export type PortDirection = "in" | "out";
export type PortSide = "top" | "bottom" | "left" | "right";
export type PocketKind = "statement" | "value";
export type InterfaceId = "n" | "e" | "s" | "w" | "nest-in" | "nest-out";
export type PermissionMode = "allow" | "deny" | "mandatory";

export interface ColorSpec {
  fill: string;
  stroke: string;
  highlight?: string;
}

export interface PortDefinition {
  id: string;
  kind: PortKind;
  direction: PortDirection;
  side: PortSide;
  offset?: { x: number; y: number };
  label?: string;
  provides?: string[];
  accepts?: string[];
}

export interface PocketLimits {
  maxChildren?: number | null;
  allowedTypes?: string[] | null;
  maxDepth?: number | null;
}

export interface PocketDefinition {
  id: string;
  kind: PocketKind;
  label?: string;
  /** Extra instances are created by a control action (e.g. + else if). */
  repeatable?: boolean;
  /** Each instance stores a string on `node.fields[pocketId]` (shelf text). */
  controlField?: boolean;
  limits?: PocketLimits;
}

export type ControlKind = "text" | "dropdown" | "button";
export type ControlAction = "addRepeatablePocket";

export interface ControlDefinition {
  id: string;
  kind: ControlKind;
  label?: string;
  field?: string;
  options?: string[];
  action?: ControlAction;
  pocket?: string;
  x?: number;
  y?: number;
  width?: number;
  height?: number;
}

export interface RectGlyph {
  kind: "rounded-rect";
  width: number;
  hatHeight: number;
  footHeight: number;
  midHeight?: number;
  cornerRadius?: number;
}

export type GlyphShape = RectGlyph;

export interface ConnectionLegalityStub {
  allow?: Array<{
    fromType?: string;
    fromPort?: string;
    toType?: string;
    toPort?: string;
  }> | null;
}

export interface NestingLegalityStub {
  maxDepth?: number | null;
  allowedChildTypes?: string[] | null;
}

export interface LegalityHooks {
  connection?: ConnectionLegalityStub | null;
  nesting?: NestingLegalityStub | null;
}

export interface InterfacePermission {
  mode: PermissionMode;
  typeId?: string;
  instanceId?: string;
  permissionType?: string;
  /** Skip the other side's invite. Never bypasses a deny (veto). */
  override?: boolean;
}

export interface GlyphDefinition {
  type: string;
  label: string;
  category: string;
  color: ColorSpec;
  glyph: GlyphShape;
  ports: PortDefinition[];
  pockets: PocketDefinition[];
  legality?: LegalityHooks;
  alwaysFrame?: boolean;
  controls?: ControlDefinition[];
  extends?: string;
  permissionType?: string;
  interfaces?: Partial<Record<InterfaceId, InterfacePermission[]>>;
  badge?: string;
  toolbox?: boolean;
  /** One-line toolbox / inspector hint. Never a type id. */
  hint?: string;
}

export type TypeDefInput = Omit<Partial<GlyphDefinition>, "color" | "glyph" | "type"> & {
  type: string;
  extends?: string;
  color?: Partial<ColorSpec>;
  glyph?: Partial<RectGlyph> & { kind?: "rounded-rect" };
};
