import { GEOM, NOTCH, PUZZLE, puzzleCenterY } from "../layout/geom";
import { NO_JOINS, type EdgeJoins } from "../layout/joins";
import type { WorldNode } from "../layout";

const NOTCH_NECK = 3;
const PUZZLE_NECK = 2;

/** Rounded rectangle when idle; L-frame when nested. Join cues only on connected edges. */
export function nodePath(world: WorldNode, joins: EdgeJoins = world.joins ?? NO_JOINS): string {
  if (!world.wrapped) {
    return rectPath(world.width, world.height || world.hatHeight, joins);
  }
  return lFramePath(world.width, world.height, world.hatHeight, world.midHeight, world.pocketHeights, joins);
}

export function rectPath(width: number, height: number, joins: EdgeJoins = NO_JOINS): string {
  const r = Math.min(GEOM.r, width / 2, height / 2);
  const puzzleY = puzzleCenterY(Math.min(height, 40));
  const parts: string[] = [`M ${r} 0`];
  parts.push(...hEdge(width, 0, r, 1, joins.top));
  parts.push(`A ${r} ${r} 0 0 1 ${width} ${r}`);
  parts.push(...vEdge(width, r, height - r, puzzleY, 1, joins.right, height));
  parts.push(`A ${r} ${r} 0 0 1 ${width - r} ${height}`);
  parts.push(...hEdge(width, height, r, -1, joins.bottom));
  parts.push(`A ${r} ${r} 0 0 1 0 ${height - r}`);
  parts.push(...vEdge(0, height - r, r, puzzleY, -1, joins.left, height));
  parts.push(`A ${r} ${r} 0 0 1 ${r} 0`);
  parts.push("Z");
  return parts.join(" ");
}

/** Hat + left rail, open at the bottom. No U/foot under nested stacks. */
export function lFramePath(
  width: number,
  height: number,
  hat: number,
  mid: number,
  pockets: number[],
  joins: EdgeJoins = NO_JOINS,
): string {
  const r = Math.min(GEOM.r, hat / 2, GEOM.inner / 2);
  const inner = GEOM.inner;
  const puzzleY = puzzleCenterY(hat);
  const parts: string[] = [`M ${r} 0`];
  parts.push(...hEdge(width, 0, r, 1, joins.top));
  parts.push(`A ${r} ${r} 0 0 1 ${width} ${r}`);
  if (joins.right) {
    parts.push(...puzzleOut(width, r, hat, puzzleY));
    parts.push(`L ${width} ${hat}`);
  } else {
    parts.push(`L ${width} ${hat}`);
  }
  parts.push(`L ${inner} ${hat}`);

  let y = hat;
  const last = Math.max(pockets.length, 1) - 1;
  for (let i = 0; i < pockets.length; i += 1) {
    y += Math.max(pockets[i], 16);
    if (i < last) {
      const shelf = Math.min(GEOM.shelf, width - inner - 8);
      parts.push(`L ${inner} ${y}`);
      parts.push(`L ${inner + shelf} ${y}`);
      parts.push(`L ${inner + shelf} ${y + mid}`);
      parts.push(`L ${inner} ${y + mid}`);
      y += mid;
    }
  }
  if (pockets.length === 0) y = height;

  parts.push(`L ${inner} ${height}`);
  if (joins.bottom) parts.push(...railBottom(inner, height, r));
  else {
    parts.push(`L ${r} ${height}`);
    parts.push(`A ${r} ${r} 0 0 1 0 ${height - r}`);
  }
  if (joins.left) parts.push(...puzzleIn(0, height - r, r, puzzleY));
  else parts.push(`L 0 ${r}`);
  parts.push(`A ${r} ${r} 0 0 1 ${r} 0`);
  parts.push("Z");
  return parts.join(" ");
}

export function pathHasJoinCue(d: string): boolean {
  return (
    d.includes(`l ${NOTCH_NECK} ${NOTCH.h}`) ||
    d.includes(`l ${-NOTCH_NECK} ${NOTCH.h}`) ||
    d.includes(`l ${NOTCH_NECK} ${-NOTCH.h}`) ||
    d.includes(`l ${-NOTCH_NECK} ${-NOTCH.h}`) ||
    d.includes(`l ${PUZZLE.w} ${PUZZLE_NECK}`) ||
    d.includes(`l ${-PUZZLE.w} ${PUZZLE_NECK}`) ||
    d.includes(`l ${PUZZLE.w} ${-PUZZLE_NECK}`) ||
    d.includes(`l ${-PUZZLE.w} ${-PUZZLE_NECK}`)
  );
}

/** True when the path draws a full-width bar at y (a closing U foot). */
export function pathClosesFullWidthAt(d: string, width: number, y: number): boolean {
  return d.includes(`L ${width} ${y}`) || d.includes(`L ${width} ${y - GEOM.r}`);
}

function hEdge(width: number, y: number, r: number, dir: 1 | -1, joined: boolean): string[] {
  if (!joined) {
    return dir === 1 ? [`L ${width - r} ${y}`] : [`L ${r} ${y}`];
  }
  if (dir === 1) {
    return [`L ${NOTCH.x} ${y}`, notch(1), `L ${width - r} ${y}`];
  }
  return [`L ${NOTCH.x + NOTCH.w} ${y}`, notch(-1), `L ${r} ${y}`];
}

function vEdge(
  x: number,
  fromY: number,
  toY: number,
  puzzleY: number,
  dir: 1 | -1,
  joined: boolean,
  height: number,
): string[] {
  if (!joined) return [`L ${x} ${toY}`];
  if (dir === 1) return puzzleOut(x, fromY, height, puzzleY);
  return puzzleIn(x, fromY, toY, puzzleY);
}

function notch(dir: 1 | -1): string {
  const mid = NOTCH.w - NOTCH_NECK * 2;
  return `l ${dir * NOTCH_NECK} ${NOTCH.h} l ${dir * mid} 0 l ${dir * NOTCH_NECK} ${-NOTCH.h}`;
}

function puzzleOut(x: number, _fromY: number, height: number, cy: number): string[] {
  const top = cy - PUZZLE.h / 2;
  const bot = cy + PUZZLE.h / 2;
  return [
    `L ${x} ${top}`,
    `l ${PUZZLE.w} ${PUZZLE_NECK}`,
    `L ${x + PUZZLE.w} ${bot - PUZZLE_NECK}`,
    `l ${-PUZZLE.w} ${PUZZLE_NECK}`,
    `L ${x} ${height - GEOM.r}`,
  ];
}

function puzzleIn(x: number, _fromY: number, toY: number, cy: number): string[] {
  const top = cy - PUZZLE.h / 2;
  const bot = cy + PUZZLE.h / 2;
  return [
    `L ${x} ${bot}`,
    `l ${PUZZLE.w} ${-PUZZLE_NECK}`,
    `L ${x + PUZZLE.w} ${top + PUZZLE_NECK}`,
    `l ${-PUZZLE.w} ${-PUZZLE_NECK}`,
    `L ${x} ${toY}`,
  ];
}

function railBottom(inner: number, height: number, r: number): string[] {
  const mark = Math.min(NOTCH.w, Math.max(inner - r * 2 - 2, 6));
  const x0 = (inner - mark) / 2;
  const neck = Math.min(NOTCH_NECK, mark / 3);
  const mid = mark - neck * 2;
  return [
    `L ${x0 + mark} ${height}`,
    `l ${-neck} ${NOTCH.h} l ${-mid} 0 l ${-neck} ${-NOTCH.h}`,
    `L ${r} ${height}`,
    `A ${r} ${r} 0 0 1 0 ${height - r}`,
  ];
}
