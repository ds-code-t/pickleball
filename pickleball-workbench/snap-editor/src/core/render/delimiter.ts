/** Leading phrase punctuation shown as a chip, not as part of the typed text. */

const MARKS = new Set([",", ";", ":", ".", "!", "?"]);

export interface LeadingDelimiter {
  mark: string;
  space: string;
  rest: string;
}

export function splitLeadingDelimiter(text: string): LeadingDelimiter | null {
  if (!text) return null;
  const mark = text[0];
  if (!MARKS.has(mark)) return null;
  let i = 1;
  while (i < text.length && (text[i] === " " || text[i] === "\t")) i += 1;
  return { mark, space: text.slice(1, i), rest: text.slice(i) };
}

export function joinLeadingDelimiter(parts: LeadingDelimiter): string {
  return parts.mark + parts.space + parts.rest;
}
