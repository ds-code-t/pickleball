/** Blockly-like stack/nest metrics. Join tabs seat into notches after a snap. */
export const GEOM = {
  inner: 16,
  stub: 24,
  nestPad: 12,
  nestRight: 12,
  /** Open-bottom L: no closing foot under nested stacks. */
  wrapFoot: 0,
  /** Short if/else shelf; does not span under nested children. */
  shelf: 36,
  centerInsetX: 20,
  centerInsetY: 11,
  tabGuard: 22,
  pocketGap: 0,
  r: 6,
  minWidth: 160,
  stackOverlap: 4,
  stackBody: 40,
} as const;

/** Top/bottom stack tab. Drawn only on a joined edge. */
export const NOTCH = {
  x: 18,
  w: 12,
  h: 4,
} as const;

/** Left/right puzzle tab. Drawn only on a joined edge. */
export const PUZZLE = {
  w: 6,
  h: 12,
} as const;

export function notchCenterX(): number {
  return NOTCH.x + NOTCH.w / 2;
}

export function puzzleCenterY(hatHeight: number): number {
  return Math.max(PUZZLE.h / 2 + 4, hatHeight / 2);
}
