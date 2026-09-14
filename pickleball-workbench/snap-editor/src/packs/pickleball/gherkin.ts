import type { EncapsulatedNode, SnapDocument } from "../../core/model/types";
import { SCHEMA_VERSION } from "../../core/model/types";
import { createId } from "../../core/model/ids";
import { isDynamicPhraseText, joinPhrases, splitPhrases } from "./phrases";
import {
  BACKGROUND_TYPE,
  COMMENT_TYPE,
  DOCSTRING_TYPE,
  EXAMPLES_TYPE,
  FEATURE_TYPE,
  IF_TYPE,
  OUTLINE_TYPE,
  PHRASE_TYPE,
  RULE_TYPE,
  SCENARIO_TYPE,
  STEP_TYPE,
  TABLE_TYPE,
  TAGS_TYPE,
} from "./types";

const STEP_KW = /^(Given|When|Then|And|But|\*)(?=\s|$)/;
const FEATURE = /^Feature:\s*(.*)$/i;
const RULE = /^Rule:\s*(.*)$/i;
const BACKGROUND = /^Background:\s*(.*)$/i;
const OUTLINE = /^(?:Scenario Outline|Scenario Template):\s*(.*)$/i;
const SCENARIO = /^(?:Scenario|Example):\s*(.*)$/i;
const EXAMPLES = /^(?:Examples|Scenarios):\s*(.*)$/i;
const IF_LINE = /^(?:\*\s*)?IF:\s*(.*)$/i;
const ELSEIF_LINE = /^(?:\*\s*)?ELSE-IF:\s*(.*)$/i;
const ELSE_LINE = /^(?:\*\s*)?ELSE:\s*(.*)$/i;
const DOCSTRING_OPEN = /^("{3}|`{3})(.*)$/;

const STORY_TYPES = new Set([FEATURE_TYPE, RULE_TYPE, BACKGROUND_TYPE, SCENARIO_TYPE, OUTLINE_TYPE]);
const BODY_STACK_TYPES = new Set([
  STEP_TYPE,
  IF_TYPE,
  COMMENT_TYPE,
  TABLE_TYPE,
  DOCSTRING_TYPE,
  EXAMPLES_TYPE,
]);

export function exportGherkin(doc: SnapDocument): string {
  const lines: string[] = [];
  for (const root of doc.roots) emitNode(root, 0, lines, false, 0);
  return lines.join("\n") + (lines.length ? "\n" : "");
}

function emitNode(
  node: EncapsulatedNode,
  colonDepth: number,
  lines: string[],
  nested: boolean,
  indent: number,
): void {
  const prefix = linePrefix(indent, nested, colonDepth);
  const type = typeId(node);
  if (type === FEATURE_TYPE) {
    lines.push(`Feature: ${data(node, "text") || ""}`.trimEnd());
    emitDescription(node, indent + 2, lines);
    for (const kid of nestKids(node, "do")) emitNode(kid, 0, lines, false, indent + 2);
    if (south(node)) emitNode(south(node)!, colonDepth, lines, nested, indent);
    return;
  }
  if (type === RULE_TYPE) {
    lines.push(`${prefix}Rule: ${data(node, "text") || ""}`.trimEnd());
    emitDescription(node, indent + 2, lines);
    for (const kid of nestKids(node, "do")) emitNode(kid, 0, lines, false, indent + 2);
    if (south(node)) emitNode(south(node)!, colonDepth, lines, nested, indent);
    return;
  }
  if (type === BACKGROUND_TYPE) {
    lines.push(`${prefix}Background:${suffixName(node)}`.trimEnd());
    emitDescription(node, indent + 2, lines);
    for (const kid of nestKids(node, "do")) emitNode(kid, 0, lines, false, indent + 2);
    if (south(node)) emitNode(south(node)!, colonDepth, lines, nested, indent);
    return;
  }
  if (type === SCENARIO_TYPE) {
    lines.push(`${prefix}Scenario: ${data(node, "text") || ""}`.trimEnd());
    emitDescription(node, indent + 2, lines);
    for (const kid of nestKids(node, "do")) emitNode(kid, 0, lines, false, indent + 2);
    if (south(node)) emitNode(south(node)!, colonDepth, lines, nested, indent);
    return;
  }
  if (type === OUTLINE_TYPE) {
    lines.push(`${prefix}Scenario Outline: ${data(node, "text") || ""}`.trimEnd());
    emitDescription(node, indent + 2, lines);
    for (const kid of nestKids(node, "do")) emitNode(kid, 0, lines, false, indent + 2);
    if (south(node)) emitNode(south(node)!, colonDepth, lines, nested, indent);
    return;
  }
  if (type === EXAMPLES_TYPE) {
    lines.push(`${prefix}Examples:${suffixName(node)}`.trimEnd());
    emitDescription(node, indent + 2, lines);
    for (const kid of nestKids(node, "do")) emitNode(kid, 0, lines, false, indent + 2);
    if (south(node)) emitNode(south(node)!, colonDepth, lines, nested, indent);
    return;
  }
  if (type === TAGS_TYPE) {
    const tags = canonicalizeTags(data(node, "text"));
    if (tags) lines.push(`${prefix}${tags}`);
    if (south(node)) emitNode(south(node)!, colonDepth, lines, nested, indent);
    return;
  }
  if (type === COMMENT_TYPE) {
    lines.push(emitCommentLine(prefix, node));
    if (south(node)) emitNode(south(node)!, colonDepth, lines, nested, indent);
    return;
  }
  if (type === TABLE_TYPE) {
    emitTable(node, indent, lines);
    if (south(node)) emitNode(south(node)!, colonDepth, lines, nested, indent);
    return;
  }
  if (type === DOCSTRING_TYPE) {
    emitDocString(node, indent, lines);
    if (south(node)) emitNode(south(node)!, colonDepth, lines, nested, indent);
    return;
  }
  if (type === IF_TYPE) {
    const cond = data(node, "condition") || inlineCondition(node);
    const thenInline = inlineThen(node);
    const colon = hasColonNest(node, "then") || hasElseBranches(node) ? ":" : "";
    if (thenInline && !hasNestKids(node, "then")) {
      lines.push(`${prefix}* IF: ${cond} THEN: ${thenInline}`);
    } else {
      lines.push(`${prefix}* IF: ${cond}${colon}`);
      emitColonPocket(node, "then", nested ? colonDepth + 1 : 1, lines, indent);
    }
    const elseifKeys = Object.keys(nests(node))
      .filter((key) => key === "elseif" || key.startsWith("elseif-"))
      .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    for (const pocketId of elseifKeys) {
      const kids = nestKids(node, pocketId);
      const branchCond = data(node, pocketId);
      lines.push(`${prefix}* ELSE-IF: ${branchCond}:`);
      for (const kid of kids) emitColonChild(kid, nested ? colonDepth + 1 : 1, lines, indent);
    }
    const elseKeys = Object.keys(nests(node))
      .filter((key) => key === "else" || /^else-\d+$/.test(key))
      .sort((a, b) => {
        if (a === "else") return -1;
        if (b === "else") return 1;
        return a.localeCompare(b, undefined, { numeric: true });
      });
    for (const key of elseKeys) {
      if (!hasNestKids(node, key)) continue;
      lines.push(`${prefix}* ELSE:`);
      emitColonPocket(node, key, nested ? colonDepth + 1 : 1, lines, indent);
    }
    if (south(node)) emitNode(south(node)!, colonDepth, lines, nested, indent);
    return;
  }

  const keyword = data(node, "keyword") || defaultKeyword(type);
  const text = stepText(node);
  const colon = hasNestKids(node, "do") && !/[:?]$/.test(text.trim()) ? ":" : "";
  lines.push(`${prefix}${keyword} ${text}${colon}`.trimEnd());
  emitStepPocket(node, "do", nested ? colonDepth + 1 : 1, lines, indent);
  if (south(node)) emitNode(south(node)!, colonDepth, lines, nested, indent);
}

function emitStepPocket(
  node: EncapsulatedNode,
  pocketId: string,
  colonDepth: number,
  lines: string[],
  indent: number,
): void {
  for (const kid of nestKids(node, pocketId)) emitColonChild(kid, colonDepth, lines, indent);
}

function emitColonPocket(
  node: EncapsulatedNode,
  pocketId: string,
  colonDepth: number,
  lines: string[],
  indent: number,
): void {
  for (const kid of nestKids(node, pocketId)) emitColonChild(kid, colonDepth, lines, indent);
}

function emitColonChild(kid: EncapsulatedNode, colonDepth: number, lines: string[], indent: number): void {
  let cursor: EncapsulatedNode | undefined = kid;
  while (cursor) {
    const rest = south(cursor);
    const t = typeId(cursor);
    if (t === TABLE_TYPE) emitTable(cursor, indent + 2, lines);
    else if (t === DOCSTRING_TYPE) emitDocString(cursor, indent + 2, lines);
    else emitNode({ ...cursor, s: undefined }, colonDepth, lines, true, indent);
    cursor = rest;
  }
}

function emitDescription(node: EncapsulatedNode, indent: number, lines: string[]): void {
  const description = data(node, "description");
  if (!description) return;
  for (const line of description.split("\n")) {
    if (!line.length) continue;
    lines.push(`${" ".repeat(indent)}${line}`.trimEnd());
  }
}

function emitCommentLine(prefix: string, node: EncapsulatedNode): string {
  const text = data(node, "text");
  if (data(node, "hash") === "0") return `${prefix}${text}`.trimEnd();
  if (!text) return `${prefix}#`;
  return `${prefix}# ${text}`.trimEnd();
}

function emitTable(node: EncapsulatedNode, indent: number, lines: string[]): void {
  const rows = readTableRows(node);
  if (rows.length === 0) return;
  const widths = columnWidths(rows);
  const pad = " ".repeat(indent);
  for (const row of rows) {
    const cells = widths.map((width, i) => ` ${(row[i] ?? "").padEnd(width, " ")} `);
    lines.push(`${pad}|${cells.join("|")}|`);
  }
}

function emitDocString(node: EncapsulatedNode, indent: number, lines: string[]): void {
  const delim = data(node, "delimiter") || '"""';
  const media = data(node, "mediaType");
  const pad = " ".repeat(indent);
  lines.push(`${pad}${delim}${media}`);
  const text = data(node, "text");
  if (text.length) {
    for (const line of text.split("\n")) {
      lines.push(line.length ? `${pad}${line}` : "");
    }
  }
  lines.push(`${pad}${delim}`);
}

function linePrefix(indent: number, nested: boolean, colonDepth: number): string {
  const spaces = " ".repeat(Math.max(indent, 0));
  if (!nested) return spaces;
  return `${spaces}${":".repeat(Math.max(colonDepth, 1))} `;
}

function suffixName(node: EncapsulatedNode): string {
  const text = data(node, "text");
  return text ? ` ${text}` : "";
}

function canonicalizeTags(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

function stepText(node: EncapsulatedNode): string {
  const parts = [data(node, "text")];
  let cursor = east(node);
  while (cursor && typeId(cursor) === PHRASE_TYPE) {
    parts.push(data(cursor, "text"));
    cursor = east(cursor);
  }
  return joinPhrases(parts.filter((part) => part.length > 0));
}

function hasNestKids(node: EncapsulatedNode, pocketId: string): boolean {
  return nestKids(node, pocketId).length > 0;
}

function hasElseBranches(node: EncapsulatedNode): boolean {
  return Object.entries(nests(node)).some(
    ([k, kids]) => (k === "else" || k.startsWith("elseif") || /^else-\d+$/.test(k)) && kids.length > 0,
  );
}

function hasColonNest(node: EncapsulatedNode, pocketId: string): boolean {
  const walk = (item: EncapsulatedNode | undefined): boolean => {
    if (!item) return false;
    const t = typeId(item);
    if (t === STEP_TYPE || t === IF_TYPE) return true;
    if (walk(east(item))) return true;
    if (walk(south(item))) return true;
    for (const kids of Object.values(nests(item))) {
      if (kids.some((kid) => walk(kid))) return true;
    }
    return false;
  };
  return nestKids(node, pocketId).some((kid) => walk(kid));
}

function inlineThen(node: EncapsulatedNode): string | null {
  const next = east(node);
  if (!next || typeId(next) === PHRASE_TYPE) return null;
  const keyword = data(next, "keyword") || defaultKeyword(typeId(next));
  const text = data(next, "text") || "";
  return `${keyword} ${text}`.trim();
}

function inlineCondition(node: EncapsulatedNode): string {
  const next = east(node);
  if (!next) return "";
  return data(next, "text") || "";
}

function data(node: EncapsulatedNode, key: string): string {
  return node.data?.[key] ?? node.fields?.[key] ?? "";
}

function typeId(node: EncapsulatedNode): string {
  return node.typeId || node.type || "";
}

function nests(node: EncapsulatedNode): Record<string, EncapsulatedNode[]> {
  return node.nest ?? node.pockets ?? {};
}

function nestKids(node: EncapsulatedNode, pocketId: string): EncapsulatedNode[] {
  return nests(node)[pocketId] ?? [];
}

function east(node: EncapsulatedNode): EncapsulatedNode | undefined {
  return node.e ?? node.right;
}

function south(node: EncapsulatedNode): EncapsulatedNode | undefined {
  return node.s ?? node.below;
}

function defaultKeyword(type: string): string {
  if (type === STEP_TYPE) return "Given";
  return "*";
}

function readTableRows(node: EncapsulatedNode): string[][] {
  const raw = data(node, "rows") || data(node, "text");
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.map((row) => (Array.isArray(row) ? row.map((cell) => String(cell ?? "")) : [String(row)]));
  } catch {
    return raw
      .split("\n")
      .filter((line) => line.includes("|"))
      .map(parseTableRow);
  }
}

function columnWidths(rows: string[][]): number[] {
  const widths: number[] = [];
  for (const row of rows) {
    row.forEach((cell, i) => {
      widths[i] = Math.max(widths[i] ?? 0, cell.length);
    });
  }
  return widths.map((width) => Math.max(width, 0));
}

export function importGherkin(text: string): SnapDocument {
  const items = parseGherkin(text);
  const roots: EncapsulatedNode[] = [];
  let feature: EncapsulatedNode | null = null;
  let rule: EncapsulatedNode | null = null;
  let container: EncapsulatedNode | null = null;
  let pendingIf: EncapsulatedNode | null = null;
  const pending: EncapsulatedNode[] = [];
  const colonStack: Array<{ node: EncapsulatedNode; pocket: string } | undefined> = [];

  const storyParent = (): EncapsulatedNode | null => rule ?? feature;

  const placeRoot = (node: EncapsulatedNode): void => {
    appendRoot(roots, node);
  };

  const placeStory = (node: EncapsulatedNode): void => {
    const parent = storyParent();
    if (parent) appendChild(parent, "do", node, "list");
    else placeRoot(node);
  };

  const placeBody = (node: EncapsulatedNode): void => {
    const parent = container ?? storyParent();
    if (parent) appendChild(parent, defaultPocket(parent), node, "stack");
    else placeRoot(node);
    colonStack.length = 0;
    colonStack[0] = { node, pocket: defaultPocket(node) };
  };

  const placeNested = (colonDepth: number, node: EncapsulatedNode): void => {
    const frame = colonStack[colonDepth - 1] ?? colonStack.filter(Boolean).at(-1);
    if (!frame) {
      placeBody(node);
      return;
    }
    appendChild(frame.node, frame.pocket, node, "stack");
    colonStack.length = colonDepth;
    colonStack[colonDepth] = { node, pocket: defaultPocket(node) };
  };

  const flushPending = (place: (node: EncapsulatedNode) => void): void => {
    for (const node of pending) place(node);
    pending.length = 0;
  };

  const attachArgument = (node: EncapsulatedNode, depth: number): void => {
    const frame = depth > 0 ? (colonStack[depth - 1] ?? colonStack.filter(Boolean).at(-1)) : colonStack[0];
    const owner = frame?.node ?? lastBodyOwner(container ?? storyParent());
    if (!owner) {
      if (depth > 0) placeNested(depth, node);
      else placeBody(node);
      return;
    }
    appendChild(owner, defaultPocket(owner), node, "stack");
  };

  for (const item of items) {
    if (item.kind === "comment" || item.kind === "tags") {
      pending.push(item.kind === "tags" ? tagsNode(item.text) : commentNode(item.text, item.hash !== false));
      continue;
    }
    if (item.kind === "table") {
      flushPending((node) => (item.depth > 0 ? placeNested(item.depth, node) : placeBody(node)));
      attachArgument(tableNode(item.rows ?? []), item.depth);
      continue;
    }
    if (item.kind === "docstring") {
      flushPending((node) => (item.depth > 0 ? placeNested(item.depth, node) : placeBody(node)));
      attachArgument(docStringNode(item.delimiter ?? '"""', item.mediaType ?? "", item.text), item.depth);
      continue;
    }
    if (item.kind === "elseif" || item.kind === "else") {
      flushPending((node) => placeBody(node));
      if (!pendingIf) continue;
      if (item.kind === "elseif") {
        const pocketId = nextElseIf(pendingIf);
        pendingIf.nest = pendingIf.nest ?? {};
        pendingIf.nest[pocketId] = [];
        pendingIf.data = { ...(pendingIf.data ?? {}), [pocketId]: item.text };
        colonStack.length = 0;
        colonStack[0] = { node: pendingIf, pocket: pocketId };
        if (item.then) appendChild(pendingIf, pocketId, stepFromParts(item.then.keyword, item.then.text), "stack");
        continue;
      }
      pendingIf.nest = pendingIf.nest ?? {};
      const pocketId = nextElse(pendingIf, colonStack[0]);
      colonStack.length = 0;
      colonStack[0] = { node: pendingIf, pocket: pocketId };
      if (item.then) appendChild(pendingIf, pocketId, stepFromParts(item.then.keyword, item.then.text), "stack");
      continue;
    }
    if (item.kind === "feature") {
      const node = hatNode(FEATURE_TYPE, item.text);
      flushPending(placeRoot);
      feature = node;
      rule = null;
      container = null;
      pendingIf = null;
      colonStack.length = 0;
      roots.push(node);
      continue;
    }
    if (item.kind === "rule") {
      const node = hatNode(RULE_TYPE, item.text);
      flushPending(placeStory);
      container = null;
      pendingIf = null;
      colonStack.length = 0;
      placeStory(node);
      rule = node;
      continue;
    }
    if (item.kind === "background") {
      const node = hatNode(BACKGROUND_TYPE, item.text);
      flushPending(placeStory);
      container = node;
      pendingIf = null;
      colonStack.length = 0;
      placeStory(node);
      continue;
    }
    if (item.kind === "scenario") {
      const node = hatNode(SCENARIO_TYPE, item.text);
      flushPending(placeStory);
      container = node;
      pendingIf = null;
      colonStack.length = 0;
      placeStory(node);
      continue;
    }
    if (item.kind === "outline") {
      const node = hatNode(OUTLINE_TYPE, item.text);
      flushPending(placeStory);
      container = node;
      pendingIf = null;
      colonStack.length = 0;
      placeStory(node);
      continue;
    }
    if (item.kind === "examples") {
      const node = hatNode(EXAMPLES_TYPE, item.text);
      flushPending((n) => (item.depth > 0 ? placeNested(item.depth, n) : placeBody(n)));
      pendingIf = null;
      if (item.depth > 0) placeNested(item.depth, node);
      else placeBody(node);
      continue;
    }
    if (item.kind === "if") {
      const node = ifNode(item.text);
      flushPending((n) => (item.depth > 0 ? placeNested(item.depth, n) : placeBody(n)));
      pendingIf = node;
      if (item.then) node.e = stepFromParts(item.then.keyword, item.then.text);
      if (item.depth > 0) placeNested(item.depth, node);
      else placeBody(node);
      continue;
    }
    const node = stepFromParts(item.keyword ?? "*", item.text);
    flushPending((n) => (item.depth > 0 ? placeNested(item.depth, n) : placeBody(n)));
    if (item.depth === 0) pendingIf = null;
    if (item.depth > 0) placeNested(item.depth, node);
    else placeBody(node);
  }
  flushPending((node) => {
    if (container) placeBody(node);
    else placeStory(node);
  });

  return { schemaVersion: SCHEMA_VERSION, roots };
}

interface ParsedItem {
  depth: number;
  kind:
    | "feature"
    | "rule"
    | "background"
    | "scenario"
    | "outline"
    | "examples"
    | "if"
    | "elseif"
    | "else"
    | "step"
    | "tags"
    | "comment"
    | "table"
    | "docstring";
  text: string;
  keyword?: string;
  then?: { keyword: string; text: string };
  hash?: boolean;
  rows?: string[][];
  delimiter?: string;
  mediaType?: string;
}

function parseGherkin(text: string): ParsedItem[] {
  const lines = text
    .replace(/^\uFEFF/, "")
    .replace(/\r\n/g, "\n")
    .replace(/\r/g, "\n")
    .split("\n");
  const items: ParsedItem[] = [];
  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i];
    const { depth, rest } = splitLead(raw);
    if (!rest) continue;

    if (rest.startsWith("#")) {
      items.push({ depth, kind: "comment", text: rest.replace(/^#\s?/, ""), hash: true });
      continue;
    }
    if (rest.startsWith("@")) {
      items.push({ depth, kind: "tags", text: rest.replace(/\s+/g, " ").trim() });
      continue;
    }
    if (FEATURE.test(rest)) {
      items.push({ depth, kind: "feature", text: rest.replace(FEATURE, "$1").trim() });
      continue;
    }
    if (RULE.test(rest)) {
      items.push({ depth, kind: "rule", text: rest.replace(RULE, "$1").trim() });
      continue;
    }
    if (BACKGROUND.test(rest)) {
      items.push({ depth, kind: "background", text: rest.replace(BACKGROUND, "$1").trim() });
      continue;
    }
    if (OUTLINE.test(rest)) {
      items.push({ depth, kind: "outline", text: rest.replace(OUTLINE, "$1").trim() });
      continue;
    }
    if (SCENARIO.test(rest)) {
      items.push({ depth, kind: "scenario", text: rest.replace(SCENARIO, "$1").trim() });
      continue;
    }
    if (EXAMPLES.test(rest)) {
      items.push({ depth, kind: "examples", text: rest.replace(EXAMPLES, "$1").trim() });
      continue;
    }
    const ifMatch = rest.match(IF_LINE);
    if (ifMatch) {
      const split = splitThen(ifMatch[1].trim());
      items.push({ depth, kind: "if", text: split.left.replace(/:$/, "").trim(), then: split.then });
      continue;
    }
    const elseIf = rest.match(ELSEIF_LINE);
    if (elseIf) {
      const split = splitThen(elseIf[1].trim());
      items.push({ depth, kind: "elseif", text: split.left.replace(/:$/, "").trim(), then: split.then });
      continue;
    }
    const elseMatch = rest.match(ELSE_LINE);
    if (elseMatch) {
      const split = splitThen(elseMatch[1].trim());
      items.push({ depth, kind: "else", text: split.left.replace(/:$/, "").trim(), then: split.then });
      continue;
    }
    const doc = rest.match(DOCSTRING_OPEN);
    if (doc) {
      const consumed = consumeDocString(lines, i, depth, doc[1], doc[2].trim());
      items.push({
        depth,
        kind: "docstring",
        text: consumed.text,
        delimiter: doc[1],
        mediaType: consumed.mediaType,
      });
      i = consumed.end;
      continue;
    }
    if (rest.startsWith("|")) {
      const consumed = consumeTable(lines, i, depth);
      items.push({ depth, kind: "table", text: "", rows: consumed.rows });
      i = consumed.end;
      continue;
    }
    const kw = rest.match(STEP_KW);
    if (kw) {
      items.push({
        depth,
        kind: "step",
        keyword: kw[1],
        text: rest.slice(kw[0].length).trimStart().replace(/:$/, ""),
      });
      continue;
    }
    items.push({ depth, kind: "comment", text: rest, hash: false });
  }
  return items;
}

function splitLead(raw: string): { depth: number; rest: string } {
  let rest = raw.trim();
  let depth = 0;
  while (rest.startsWith(":")) {
    depth += 1;
    rest = rest.slice(1).replace(/^\s*/, "");
  }
  return { depth, rest };
}

function consumeTable(lines: string[], start: number, depth: number): { rows: string[][]; end: number } {
  const rows: string[][] = [];
  let end = start;
  for (let i = start; i < lines.length; i++) {
    const { depth: lineDepth, rest } = splitLead(lines[i]);
    if (!rest) break;
    if (lineDepth !== depth || !rest.startsWith("|")) break;
    rows.push(parseTableRow(rest));
    end = i;
  }
  return { rows, end };
}

function parseTableRow(line: string): string[] {
  const trimmed = line.trim();
  const inner = trimmed.replace(/^\|/, "").replace(/\|$/, "");
  return inner.split("|").map((cell) => cell.trim());
}

function consumeDocString(
  lines: string[],
  start: number,
  depth: number,
  delim: string,
  mediaType: string,
): { text: string; mediaType: string; end: number } {
  const openerIndent = leadingWhitespace(lines[start]).length;
  const content: string[] = [];
  let end = start;
  for (let i = start + 1; i < lines.length; i++) {
    end = i;
    const stripped = stripColonPrefix(lines[i], depth);
    if (stripped.trim() === delim) break;
    content.push(stripIndent(stripped, openerIndent));
  }
  return { text: content.join("\n"), mediaType, end };
}

function stripColonPrefix(raw: string, depth: number): string {
  if (depth <= 0) return raw;
  const indent = leadingWhitespace(raw);
  let rest = raw.slice(indent.length);
  let seen = 0;
  while (seen < depth && rest.startsWith(":")) {
    seen += 1;
    rest = rest.slice(1);
    if (rest.startsWith(" ")) rest = rest.slice(1);
  }
  return indent + rest;
}

function leadingWhitespace(text: string): string {
  return text.match(/^[ \t]*/)?.[0] ?? "";
}

function stripIndent(line: string, spaces: number): string {
  let i = 0;
  while (i < spaces && (line[i] === " " || line[i] === "\t")) i += 1;
  return line.slice(i);
}

function splitThen(body: string): { left: string; then?: { keyword: string; text: string } } {
  const idx = body.search(/\sTHEN:\s/i);
  if (idx < 0) return { left: body };
  const left = body.slice(0, idx).trim();
  const right = body.slice(idx).replace(/^\sTHEN:\s/i, "").trim();
  const kw = right.match(STEP_KW);
  if (!kw) return { left, then: { keyword: "*", text: right } };
  return { left, then: { keyword: kw[1], text: right.slice(kw[0].length).trim() } };
}

function stepFromParts(keyword: string, text: string): EncapsulatedNode {
  const body = text.trimStart();
  if (isDynamicPhraseText(body)) {
    const phrases = splitPhrases(body);
    const head = phrases[0] ?? body;
    const node: EncapsulatedNode = {
      typeId: STEP_TYPE,
      id: createId(),
      permissionType: "step",
      data: { keyword, text: head },
    };
    let cursor = node;
    for (const phrase of phrases.slice(1)) {
      const next: EncapsulatedNode = {
        typeId: PHRASE_TYPE,
        id: createId(),
        permissionType: "phrase",
        data: { text: phrase },
      };
      cursor.e = next;
      cursor = next;
    }
    return node;
  }
  return {
    typeId: STEP_TYPE,
    id: createId(),
    permissionType: "step",
    data: { keyword, text: body.replace(/:$/, "") },
  };
}

function ifNode(condition: string): EncapsulatedNode {
  return {
    typeId: IF_TYPE,
    id: createId(),
    permissionType: "control",
    data: { condition },
    nest: { then: [], else: [] },
  };
}

function hatNode(type: string, text: string): EncapsulatedNode {
  return {
    typeId: type,
    id: createId(),
    data: { text },
    nest: { do: [] },
  };
}

function tagsNode(text: string): EncapsulatedNode {
  return {
    typeId: TAGS_TYPE,
    id: createId(),
    permissionType: "tags",
    data: { text: canonicalizeTags(text) },
  };
}

function commentNode(text: string, hash: boolean): EncapsulatedNode {
  return {
    typeId: COMMENT_TYPE,
    id: createId(),
    permissionType: "comment",
    data: { text, hash: hash ? "1" : "0" },
  };
}

function tableNode(rows: string[][]): EncapsulatedNode {
  return {
    typeId: TABLE_TYPE,
    id: createId(),
    permissionType: "table",
    data: { rows: JSON.stringify(rows) },
  };
}

function docStringNode(delimiter: string, mediaType: string, text: string): EncapsulatedNode {
  return {
    typeId: DOCSTRING_TYPE,
    id: createId(),
    permissionType: "docstring",
    data: { delimiter, mediaType, text },
  };
}

function defaultPocket(node: EncapsulatedNode): string {
  if (typeId(node) === IF_TYPE) return "then";
  return "do";
}

function lastBodyOwner(node: EncapsulatedNode | null): EncapsulatedNode | null {
  if (!node) return null;
  const kids = nestKids(node, defaultPocket(node));
  if (kids.length === 0) return node;
  return lastBelow(kids[kids.length - 1]);
}

function lastBelow(node: EncapsulatedNode): EncapsulatedNode {
  let cursor = node;
  while (south(cursor)) cursor = south(cursor)!;
  return cursor;
}

function appendChild(
  parent: EncapsulatedNode,
  pocket: string,
  node: EncapsulatedNode,
  mode: "stack" | "list",
): void {
  parent.nest = parent.nest ?? {};
  parent.nest[pocket] = parent.nest[pocket] ?? [];
  const list = parent.nest[pocket];
  if (mode === "stack" && list.length > 0) {
    lastBelow(list[list.length - 1]).s = node;
    return;
  }
  list.push(node);
}

function appendRoot(roots: EncapsulatedNode[], node: EncapsulatedNode): void {
  if (roots.length > 0) {
    const prev = lastBelow(roots[roots.length - 1]);
    const prevType = typeId(prev);
    const nextType = typeId(node);
    if (!STORY_TYPES.has(prevType) && !STORY_TYPES.has(nextType) && BODY_STACK_TYPES.has(nextType)) {
      prev.s = node;
      return;
    }
  }
  roots.push(node);
}

function nextElseIf(node: EncapsulatedNode): string {
  const keys = Object.keys(nests(node)).filter((k) => k === "elseif" || k.startsWith("elseif-"));
  return `elseif-${keys.length + 1}`;
}

function nextElse(
  node: EncapsulatedNode,
  current?: { node: EncapsulatedNode; pocket: string },
): string {
  const usedPrimary =
    (current?.node === node && current.pocket === "else") ||
    (nests(node).else?.length ?? 0) > 0 ||
    Object.keys(nests(node)).some((k) => /^else-\d+$/.test(k));
  if (!usedPrimary) {
    node.nest = node.nest ?? {};
    node.nest.else = node.nest.else ?? [];
    return "else";
  }
  let n = 2;
  while (nests(node)[`else-${n}`]) n += 1;
  const pocketId = `else-${n}`;
  node.nest = node.nest ?? {};
  node.nest[pocketId] = [];
  return pocketId;
}
