import type { GlyphDefinition, PocketDefinition, PortDefinition, TypeDefInput } from "./types";
import { CARDINAL_PORTS, DEFAULT_GLYPH } from "./shape";
import type { DocumentState } from "../model/document";
import type { NodeRecord } from "../model/types";
import { dualPermission, portInterface } from "../permissions";

export type { GlyphDefinition, PortDefinition, PocketDefinition, TypeDefInput } from "./types";
export { CARDINAL_PORTS, DEFAULT_GLYPH, statementPocket } from "./shape";

const DEFAULT_COLOR = { fill: "#5b8def", stroke: "#3d6bc4", highlight: "#8bb4ff" };

export class Registry {
  private readonly byType = new Map<string, GlyphDefinition>();

  constructor(defs: readonly GlyphDefinition[] = []) {
    for (const def of defs) this.register(def);
  }

  register(def: GlyphDefinition): void {
    if (!def.type) throw new Error("GlyphDefinition.type is required");
    this.byType.set(def.type, def);
  }

  registerType(def: TypeDefInput): GlyphDefinition {
    const merged = mergeTypeDef(this, def);
    this.register(merged);
    return merged;
  }

  get(type: string): GlyphDefinition {
    const def = this.byType.get(type);
    if (!def) throw new Error(`Unknown glyph type: ${type}`);
    return def;
  }

  has(type: string): boolean {
    return this.byType.has(type);
  }

  all(): GlyphDefinition[] {
    return [...this.byType.values()];
  }

  toolbox(): GlyphDefinition[] {
    return this.all().filter((def) => def.toolbox !== false);
  }

  port(type: string, portId: string): PortDefinition {
    const def = this.get(type);
    const port = def.ports.find((p) => p.id === portId);
    if (!port) throw new Error(`Unknown port ${portId} on ${type}`);
    return port;
  }

  pocket(type: string, pocketId: string): PocketDefinition {
    const def = this.get(type);
    const exact = def.pockets.find((p) => p.id === pocketId);
    if (exact) return exact;
    const repeatable = def.pockets.find(
      (p) => p.repeatable && (pocketId === p.id || pocketId.startsWith(`${p.id}-`)),
    );
    if (repeatable) return repeatable;
    const extra = def.pockets.find((p) => pocketId.startsWith(`${p.id}-`));
    if (extra) return extra;
    throw new Error(`Unknown pocket ${pocketId} on ${type}`);
  }
}

export const defaultRegistry = new Registry();

export function registerType(registry: Registry, def: TypeDefInput): GlyphDefinition {
  return registry.registerType(def);
}

export function mergeTypeDef(registry: Registry, def: TypeDefInput): GlyphDefinition {
  const parent = def.extends
    ? registry.has(def.extends)
      ? registry.get(def.extends)
      : (() => {
          throw new Error(`registerType: unknown parent type ${def.extends}`);
        })()
    : undefined;
  return {
    type: def.type,
    extends: def.extends,
    label: def.label ?? parent?.label ?? def.type,
    category: def.category ?? parent?.category ?? "blocks",
    color: { ...(parent?.color ?? DEFAULT_COLOR), ...(def.color ?? {}) },
    glyph: {
      ...(parent?.glyph ?? DEFAULT_GLYPH),
      ...(def.glyph ?? {}),
      kind: "rounded-rect" as const,
    },
    ports: def.ports ?? parent?.ports ?? CARDINAL_PORTS,
    pockets: def.pockets ?? parent?.pockets ?? [],
    legality: {
      connection: def.legality?.connection !== undefined ? def.legality.connection : parent?.legality?.connection,
      nesting: {
        ...(parent?.legality?.nesting ?? {}),
        ...(def.legality?.nesting ?? {}),
      },
    },
    alwaysFrame: def.alwaysFrame ?? parent?.alwaysFrame,
    controls: def.controls ?? parent?.controls,
    permissionType: def.permissionType ?? parent?.permissionType,
    interfaces: { ...(parent?.interfaces ?? {}), ...(def.interfaces ?? {}) },
    badge: def.badge ?? parent?.badge,
    toolbox: def.toolbox ?? parent?.toolbox,
    hint: def.hint ?? parent?.hint,
  };
}

export function portOf(node: NodeRecord, registry: Registry, portId: string): PortDefinition {
  return registry.port(node.type, portId);
}

export function definitionFor(node: NodeRecord, registry: Registry): GlyphDefinition {
  return registry.get(node.type);
}

export function canConnectPorts(from: PortDefinition, to: PortDefinition): boolean {
  if (from.direction === to.direction) return false;
  if (from.kind === "value" || to.kind === "value") {
    return from.kind === "value" && to.kind === "value";
  }
  return from.kind === to.kind;
}

export function canConnect(
  registry: Registry,
  fromType: string,
  fromPortId: string,
  toType: string,
  toPortId: string,
  fromNode?: NodeRecord,
  toNode?: NodeRecord,
): boolean {
  const from = fromNode ? portOf(fromNode, registry, fromPortId) : registry.port(fromType, fromPortId);
  const to = toNode ? portOf(toNode, registry, toPortId) : registry.port(toType, toPortId);
  if (!canConnectPorts(from, to)) return false;

  const fromAllow = registry.get(fromType).legality?.connection?.allow;
  const toAllow = registry.get(toType).legality?.connection?.allow;
  if (fromAllow && !matchesAllow(fromAllow, fromType, fromPortId, toType, toPortId)) return false;
  if (toAllow && !matchesAllow(toAllow, fromType, fromPortId, toType, toPortId)) return false;

  const moving = fromNode ?? { type: fromType, id: undefined, permissionType: undefined };
  const stationary = toNode ?? { type: toType, id: undefined, permissionType: undefined };
  return dualPermission(
    registry,
    moving,
    portInterface(from.side, from.id),
    stationary,
    portInterface(to.side, to.id),
  );
}

function matchesAllow(
  allow: Array<{ fromType?: string; fromPort?: string; toType?: string; toPort?: string }>,
  fromType: string,
  fromPort: string,
  toType: string,
  toPort: string,
): boolean {
  return allow.some(
    (rule) =>
      (rule.fromType === undefined || rule.fromType === fromType) &&
      (rule.fromPort === undefined || rule.fromPort === fromPort) &&
      (rule.toType === undefined || rule.toType === toType) &&
      (rule.toPort === undefined || rule.toPort === toPort),
  );
}

export function canNest(
  registry: Registry,
  parentType: string,
  pocketId: string,
  childType: string,
  parentNode?: NodeRecord,
  childNode?: NodeRecord,
): boolean {
  const parent = registry.get(parentType);
  if (parent.glyph.kind !== "rounded-rect") return false;
  let pocket: PocketDefinition;
  try {
    pocket = registry.pocket(parentType, pocketId);
  } catch {
    return false;
  }
  if (pocket.kind !== "statement") return false;
  const allowed = pocket.limits?.allowedTypes ?? parent.legality?.nesting?.allowedChildTypes;
  if (allowed && allowed.length > 0 && !allowed.includes(childType)) return false;
  const moving = childNode ?? { type: childType };
  const stationary = parentNode ?? { type: parentType };
  return dualPermission(registry, moving, "nest-in", stationary, "nest-out");
}

export function canNestInto(
  state: DocumentState,
  registry: Registry,
  parentId: string,
  pocketId: string,
  childType: string,
  childId?: string,
): boolean {
  const parent = state.nodes.get(parentId);
  if (!parent) return false;
  if (!state.canAcceptNest(parentId, registry)) return false;
  const child = childId ? state.nodes.get(childId) : undefined;
  return canNest(registry, parent.type, pocketId, childType, parent, child);
}

export function wouldBeHorizontalTrailer(
  fromSide: string,
  fromId: string,
  toId: string,
): string {
  return fromSide === "left" ? fromId : toId;
}

export function canJoinHorizontal(
  state: DocumentState,
  _registry: Registry,
  fromId: string,
  fromSide: string,
  toId: string,
  _toType?: string,
): boolean {
  const rightId = wouldBeHorizontalTrailer(fromSide, fromId, toId);
  if (state.nodes.has(rightId)) return state.canBeHorizontalTrailer(rightId);
  return true;
}

export { subjectOf } from "../permissions";
