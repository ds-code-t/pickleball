import type { Vec2 } from "../model/types";

export function alignChildOrigin(
  parentPos: Vec2,
  from: { local: Vec2 },
  to: { local: Vec2 },
): Vec2 {
  return {
    x: parentPos.x + from.local.x - to.local.x,
    y: parentPos.y + from.local.y - to.local.y,
  };
}

export function alignParentOrigin(
  childPos: Vec2,
  from: { local: Vec2 },
  to: { local: Vec2 },
): Vec2 {
  return {
    x: childPos.x + to.local.x - from.local.x,
    y: childPos.y + to.local.y - from.local.y,
  };
}

export function worldPortEdge(
  node: { x: number; y: number },
  port: { edgeA: Vec2; edgeB: Vec2 },
): { a: Vec2; b: Vec2 } {
  return {
    a: { x: node.x + port.edgeA.x, y: node.y + port.edgeA.y },
    b: { x: node.x + port.edgeB.x, y: node.y + port.edgeB.y },
  };
}

export function pointSegmentDistance(p: Vec2, seg: { a: Vec2; b: Vec2 }): number {
  const dx = seg.b.x - seg.a.x;
  const dy = seg.b.y - seg.a.y;
  const len2 = dx * dx + dy * dy;
  if (len2 < 1e-8) return Math.hypot(p.x - seg.a.x, p.y - seg.a.y);
  const t = Math.max(0, Math.min(1, ((p.x - seg.a.x) * dx + (p.y - seg.a.y) * dy) / len2));
  return Math.hypot(p.x - (seg.a.x + dx * t), p.y - (seg.a.y + dy * t));
}

export function segmentDistance(a: { a: Vec2; b: Vec2 }, b: { a: Vec2; b: Vec2 }): number {
  return Math.min(
    pointSegmentDistance(a.a, b),
    pointSegmentDistance(a.b, b),
    pointSegmentDistance(b.a, a),
    pointSegmentDistance(b.b, a),
  );
}

export function isVerticalPair(a: string, b: string): boolean {
  return (a === "bottom" && b === "top") || (a === "top" && b === "bottom");
}

export function isHorizontalPair(a: string, b: string): boolean {
  return (a === "right" && b === "left") || (a === "left" && b === "right");
}
