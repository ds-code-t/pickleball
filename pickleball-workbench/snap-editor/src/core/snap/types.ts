import type { FaceId } from "./faces";

export interface FaceSegment {
  a: { x: number; y: number };
  b: { x: number; y: number };
  role: FaceId;
}

export interface NestSnap {
  kind: "nest";
  score: number;
  parentId: string;
  pocketId: string;
  index: number;
  x: number;
  y: number;
  bar: { x: number; y: number; width: number };
  highlight: { x: number; y: number; width: number; height: number };
  face: FaceId;
  fromFace: FaceId;
  segments: FaceSegment[];
}

export interface PortSnap {
  kind: "stack" | "value";
  score: number;
  threshold: number;
  from: { nodeId: string; portId: string };
  to: { nodeId: string; portId: string };
  fromPt: { x: number; y: number };
  toPt: { x: number; y: number };
  x: number;
  y: number;
  highlightNodeId: string;
  highlightPortId: string;
  face: FaceId;
  fromFace: FaceId;
  segments: FaceSegment[];
}

export type SnapTarget = NestSnap | PortSnap;
