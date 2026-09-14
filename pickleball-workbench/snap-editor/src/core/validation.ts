import type { DocumentState } from "./model/document";
import type { Registry } from "./registry";
import type { InterfaceId } from "./registry/types";
import { interfaceFilled, portInterface } from "./permissions";

export interface ValidationIssue {
  nodeId: string;
  interfaceId: InterfaceId;
  message: string;
}

/** "Must be filled" is validation, not a snap veto. */
export function validateMandatory(state: DocumentState, registry: Registry): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  for (const node of state.nodes.values()) {
    if (!registry.has(node.type)) continue;
    const def = registry.get(node.type);
    const filled = {
      n: Boolean(state.incomingFlush(node.id, registry, "vertical")),
      s: state.outgoingFlush(node.id, registry, "vertical").length > 0,
      e: state.outgoingFlush(node.id, registry, "horizontal").length > 0,
      w: Boolean(state.incomingFlush(node.id, registry, "horizontal")),
      nestIn: state.parentOf(node.id) !== null,
      nestOut: state.hasNestedChildren(node.id),
    };
    for (const port of def.ports) {
      const iface = portInterface(port.side, port.id);
      const rules = def.interfaces?.[iface] ?? [];
      if (!rules.some((rule) => rule.mode === "mandatory")) continue;
      if (interfaceFilled(filled, iface)) continue;
      issues.push({
        nodeId: node.id,
        interfaceId: iface,
        message: `${iface} is mandatory`,
      });
    }
    const nestRules = def.interfaces?.["nest-out"] ?? [];
    if (nestRules.some((rule) => rule.mode === "mandatory") && !filled.nestOut) {
      issues.push({ nodeId: node.id, interfaceId: "nest-out", message: "nest-out is mandatory" });
    }
    const nestInRules = def.interfaces?.["nest-in"] ?? [];
    if (nestInRules.some((rule) => rule.mode === "mandatory") && !filled.nestIn) {
      issues.push({ nodeId: node.id, interfaceId: "nest-in", message: "nest-in is mandatory" });
    }
  }
  return issues;
}
