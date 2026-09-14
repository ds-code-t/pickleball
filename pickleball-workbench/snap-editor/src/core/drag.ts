import type { Vec2 } from "./model/types";

/** Pointer-follow pose. The dragged block never lerps toward a snap while moving. */
export function followPointer(pointer: Vec2, grab: Vec2): Vec2 {
  return { x: pointer.x - grab.x, y: pointer.y - grab.y };
}
