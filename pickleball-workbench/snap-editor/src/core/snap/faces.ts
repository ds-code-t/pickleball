import type { Vec2 } from "../model/types";
import { GEOM } from "../layout/geom";

/** Permission-facing ids: four outer sides plus nest-in / nest-out. */
export type FaceId = "n" | "e" | "s" | "w" | "nest-in" | "nest-out";

export interface Face {
  id: FaceId;
  a: Vec2;
  b: Vec2;
  length: number;
}

/**
 * Logical 6-sided block. The drawn rectangle may still look 4-sided;
 * the visual bottom is three entities: left-rail, expander, true south.
 */
export interface BlockFaces {
  n: Face;
  e: Face;
  w: Face;
  s: Face;
  /** Nest-out: left-rail + expander. */
  leftRail: Face;
  expander: Face;
}

export interface PoseBox {
  x: number;
  y: number;
  width: number;
  height: number;
  hatHeight: number;
  pockets?: Array<{ children: string[]; height: number }>;
}

export type PoseKind = "stack" | "nest" | "horizontal";

export interface PoseHit {
  kind: PoseKind;
  score: number;
  /** Stationary face being targeted. */
  face: FaceId;
  /** Dragged face that meets it. */
  fromFace: FaceId;
  direction: "north" | "south" | "east" | "west" | "nest";
}

export const POSE = {
  /** x origins "pretty close" — generous N/S, never stolen into nest. */
  alignX: 32,
  /** Dragged origin must sit this far right of the stationary origin to nest. */
  nestInset: GEOM.inner,
  overlapX: 36,
  overlapY: 12,
  yClose: 40,
  /** Generous room around true north/south. */
  stackRoom: 96,
  stackOverlapSlack: 16,
  /** End-x of one block vs start-x of the other. Generous so a grab offset still joins. */
  sideRoom: 80,
} as const;

export function hasNestedKids(node: PoseBox): boolean {
  return Boolean(node.pockets?.some((pocket) => pocket.children.length > 0));
}

export function blockFaces(node: PoseBox): BlockFaces {
  const x = node.x;
  const y = node.y;
  const w = node.width;
  const h = node.height;
  const hat = node.hatHeight;
  const inner = GEOM.inner;
  const kids = hasNestedKids(node);
  const railLen = kids ? inner : 0;
  const expandLen = kids ? Math.max(0, h - hat) : 0;
  const southLeft = kids ? x + inner : x;
  const mouth = { x: x + inner, y: y + hat };
  const railY = y + h;

  const n = horiz(x, y, w, "n");
  const e = vert(x + w, y, hat, "e");
  const west = vert(x, y, hat, "w");
  const leftRail = horiz(x, railY, railLen, "nest-out");
  const expander: Face = {
    id: "nest-out",
    a: mouth,
    b: { x: mouth.x, y: mouth.y + expandLen },
    length: expandLen,
  };
  const s = horiz(southLeft, railY, Math.max(0, x + w - southLeft), "s");
  return { n, e, w: west, s, leftRail, expander };
}

export function nestOutSegments(faces: BlockFaces): Face[] {
  return [faces.leftRail, faces.expander];
}

export function nestPocketBox(node: PoseBox): { x: number; y: number; width: number; height: number } {
  const inner = GEOM.inner;
  const kids = hasNestedKids(node);
  return {
    x: node.x + inner,
    y: kids ? node.y + node.hatHeight : node.y,
    width: Math.max(node.width - inner, 32),
    height: kids ? Math.max(node.height - node.hatHeight, 16) : Math.max(node.height, node.hatHeight, 16),
  };
}

/**
 * Decide snap kind from both blocks' x and y. Aligned-x vertical wins over nest
 * so N/S drops are never stolen into a nest pocket.
 */
export function classifyPose(dragged: PoseBox, stationary: PoseBox): PoseHit | null {
  const dx = dragged.x - stationary.x;
  const dy = dragged.y - stationary.y;
  const overlapX = overlap1d(dragged.x, dragged.x + dragged.width, stationary.x, stationary.x + stationary.width);
  const overlapY = overlap1d(dragged.y, dragged.y + dragged.height, stationary.y, stationary.y + stationary.height);
  const southGap = dragged.y - (stationary.y + stationary.height);
  const northGap = stationary.y - (dragged.y + dragged.height);
  const eastJoin = Math.abs(dragged.x - (stationary.x + stationary.width));
  const westJoin = Math.abs(dragged.x + dragged.width - stationary.x);
  const originAligned = Math.abs(dx) <= POSE.alignX;
  const sameColumn = originAligned || overlapX >= Math.min(dragged.width, stationary.width) * 0.4;
  const nsSouth = southGap >= -POSE.stackOverlapSlack && southGap <= POSE.stackRoom;
  const nsNorth = northGap >= -POSE.stackOverlapSlack && northGap <= POSE.stackRoom;
  const significantOverlap =
    overlapX >= Math.min(POSE.overlapX, dragged.width * 0.35, stationary.width * 0.35) &&
    overlapY >= POSE.overlapY;
  const yClose = Math.abs(dy) <= POSE.yClose || overlapY >= POSE.overlapY;
  const nestBand =
    dragged.y + dragged.hatHeight / 2 >= stationary.y - POSE.yClose &&
    dragged.y <= stationary.y + stationary.height + POSE.yClose;

  if (sameColumn && (nsSouth || nsNorth)) {
    const south = nsSouth && (!nsNorth || Math.abs(southGap) <= Math.abs(northGap));
    const gap = south ? Math.abs(southGap) : Math.abs(northGap);
    return {
      kind: "stack",
      score: Math.abs(dx) * 0.35 + gap,
      face: south ? "s" : "n",
      fromFace: south ? "n" : "s",
      direction: south ? "south" : "north",
    };
  }

  if (dx >= POSE.nestInset && significantOverlap && yClose && nestBand) {
    const insetErr = Math.abs(dx - GEOM.inner);
    const nestY = hasNestedKids(stationary) ? stationary.y + stationary.hatHeight : stationary.y;
    return {
      kind: "nest",
      score: insetErr * 0.25 + Math.abs(dragged.y - nestY) * 0.35 + Math.max(0, 40 - overlapX) * 0.2,
      face: "nest-out",
      fromFace: "nest-in",
      direction: "nest",
    };
  }

  const yForSide = Math.abs(dy) <= POSE.yClose || overlapY >= Math.min(dragged.hatHeight, stationary.hatHeight) * 0.4;
  const join = Math.min(eastJoin, westJoin);
  const xSpaced = Math.abs(dx) > POSE.nestInset * 2;
  if (yForSide && join <= POSE.sideRoom && xSpaced) {
    const toEast = eastJoin <= westJoin;
    return {
      kind: "horizontal",
      score: Math.abs(dy) * 0.35 + join,
      face: toEast ? "e" : "w",
      fromFace: toEast ? "w" : "e",
      direction: toEast ? "east" : "west",
    };
  }

  return null;
}

function horiz(x: number, y: number, length: number, id: FaceId): Face {
  return { id, a: { x, y }, b: { x: x + length, y }, length };
}

function vert(x: number, y: number, length: number, id: FaceId): Face {
  return { id, a: { x, y }, b: { x, y: y + length }, length };
}

/** True when the dragged brick sits in the N/S join between two stacked neighbors. */
export function sitsBetweenVertical(dragged: PoseBox, upper: PoseBox, lower: PoseBox): boolean {
  const overlapX = overlap1d(dragged.x, dragged.x + dragged.width, upper.x, upper.x + upper.width);
  const sameColumn =
    Math.abs(dragged.x - upper.x) <= POSE.alignX || overlapX >= Math.min(dragged.width, upper.width) * 0.4;
  if (!sameColumn) return false;
  const join = (upper.y + upper.height + lower.y) / 2;
  const cy = dragged.y + dragged.hatHeight / 2;
  if (Math.abs(cy - join) > Math.max(32, dragged.hatHeight * 0.7)) return false;
  if (dragged.y + 8 < upper.y + upper.height - Math.min(44, upper.height * 0.85)) return false;
  if (dragged.y > lower.y + Math.min(24, lower.height * 0.5)) return false;
  return true;
}

/** True when the dragged brick sits in the E/W join between two horizontally linked neighbors. */
export function sitsBetweenHorizontal(dragged: PoseBox, left: PoseBox, right: PoseBox): boolean {
  const overlapY = overlap1d(dragged.y, dragged.y + dragged.height, left.y, left.y + left.hatHeight);
  const yClose = Math.abs(dragged.y - left.y) <= POSE.yClose || overlapY >= Math.min(dragged.hatHeight, left.hatHeight) * 0.4;
  if (!yClose) return false;
  const join = (left.x + left.width + right.x) / 2;
  const cx = dragged.x + dragged.width / 2;
  if (Math.abs(cx - join) > Math.max(56, dragged.width * 0.5)) return false;
  if (dragged.x < left.x + left.width - dragged.width * 0.75) return false;
  if (dragged.x > right.x + 16) return false;
  return true;
}

function overlap1d(a0: number, a1: number, b0: number, b1: number): number {
  return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0));
}
