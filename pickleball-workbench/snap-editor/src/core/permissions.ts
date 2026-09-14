import type { NodeRecord } from "./model/types";
import type { Registry } from "./registry";
import type { GlyphDefinition, InterfaceId, InterfacePermission, PermissionMode, PortSide } from "./registry/types";

export interface PermissionSubject {
  type: string;
  id?: string;
  permissionType?: string;
}

export interface PermissionVerdict {
  allow: boolean;
  deny: boolean;
  overrideAllow: boolean;
}

const SNAP_MODES: PermissionMode[] = ["allow", "deny"];

export function portInterface(side: PortSide | string, portId?: string): InterfaceId {
  const key = side || portId || "";
  if (key === "top" || key === "n" || portId === "top") return "n";
  if (key === "bottom" || key === "s" || portId === "bottom") return "s";
  if (key === "right" || key === "e" || portId === "right") return "e";
  if (key === "left" || key === "w" || portId === "left") return "w";
  if (key === "nest-in") return "nest-in";
  return "nest-out";
}

export function evaluateInterface(
  def: GlyphDefinition | undefined,
  self: PermissionSubject,
  iface: InterfaceId,
  other: PermissionSubject,
): PermissionVerdict {
  void self;
  const rules = def?.interfaces?.[iface];
  if (!rules || rules.length === 0) {
    return { allow: true, deny: false, overrideAllow: false };
  }
  const snapRules = rules.filter((rule) => SNAP_MODES.includes(rule.mode));
  if (snapRules.length === 0) {
    return { allow: true, deny: false, overrideAllow: false };
  }
  const matching = snapRules.filter((rule) => ruleMatches(rule, other));
  const deny = matching.some((rule) => rule.mode === "deny");
  const allowRules = matching.filter((rule) => rule.mode === "allow");
  return {
    allow: allowRules.length > 0,
    deny,
    overrideAllow: allowRules.some((rule) => rule.override === true),
  };
}

export function dualPermission(
  registry: Registry,
  moving: PermissionSubject,
  movingIface: InterfaceId,
  stationary: PermissionSubject,
  stationaryIface: InterfaceId,
): boolean {
  const a = evaluateInterface(safeGet(registry, moving.type), moving, movingIface, stationary);
  const b = evaluateInterface(safeGet(registry, stationary.type), stationary, stationaryIface, moving);
  if (a.deny || b.deny) return false;
  if (a.overrideAllow || b.overrideAllow) return true;
  return a.allow && b.allow;
}

export function subjectOf(node: Pick<NodeRecord, "id" | "type" | "permissionType">): PermissionSubject {
  return { type: node.type, id: node.id, permissionType: node.permissionType };
}

export function interfaceFilled(
  connected: { n: boolean; e: boolean; s: boolean; w: boolean; nestIn: boolean; nestOut: boolean },
  iface: InterfaceId,
): boolean {
  if (iface === "n") return connected.n;
  if (iface === "e") return connected.e;
  if (iface === "s") return connected.s;
  if (iface === "w") return connected.w;
  if (iface === "nest-in") return connected.nestIn;
  return connected.nestOut;
}

function ruleMatches(rule: InterfacePermission, other: PermissionSubject): boolean {
  if (rule.typeId && rule.typeId !== other.type) return false;
  if (rule.instanceId && rule.instanceId !== other.id) return false;
  if (rule.permissionType && rule.permissionType !== other.permissionType) return false;
  return true;
}

function safeGet(registry: Registry, typeId: string): GlyphDefinition | undefined {
  return registry.has(typeId) ? registry.get(typeId) : undefined;
}
