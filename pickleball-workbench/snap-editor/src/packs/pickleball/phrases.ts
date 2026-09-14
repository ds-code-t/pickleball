/**
 * Phrase split/join copied from Pickleball 2.1.9 LineData + QuoteParser + BracketMasker.
 * Join is concatenation; each stored phrase keeps its leading delimiter character.
 */

const DELIMITERS = new Set([",", ";", ":", ".", "!", "?"]);
const MASK_CONTENT = "\u2404";
const MASK_BOUNDARY = "\u2405";
const BRACKET_PREFIX = "\u2408_";

const QUOTED_SINGLECHAR = new RegExp(
  "(?<!\\\\)(['\"`])((?:\\\\.|(?!\\1).)*?)(?<!\\\\)(?:\\\\\\\\)*\\1",
  "g",
);
const QUOTED_TRIPLE = new RegExp(
  "(?<!\\\\)(?:\\\\\\\\)*(''')((?:\\\\.|(?!\\1).)*?)(?<!\\\\)(?:\\\\\\\\)*\\1",
  "g",
);

export function isDynamicPhraseText(text: string): boolean {
  return text.trimStart().startsWith(",");
}

export function splitPhrases(input: string): string[] {
  if (!isDynamicPhraseText(input)) return input ? [input] : [];
  const source = input.trimStart();
  const qp = new QuoteParser(source);
  const bm = new BracketMasker(qp.masked());
  const masked = bm.masked();
  const phrases: string[] = [];
  let buf = "";
  let lead = "";
  let started = false;
  for (let i = 0; i < masked.length; i += 1) {
    if (!isDelimiterAt(masked, i)) {
      buf += masked[i];
      continue;
    }
    const delim = masked[i];
    if (!started) {
      lead = delim;
      started = true;
      buf = "";
      continue;
    }
    phrases.push(lead + qp.restoreFrom(bm.restoreFrom(buf)));
    lead = delim;
    buf = "";
  }
  if (started) phrases.push(lead + qp.restoreFrom(bm.restoreFrom(buf)));
  return phrases.filter((phrase) => phrase.trim().length > 0);
}

export function joinPhrases(phrases: readonly string[]): string {
  return phrases.join("");
}

function isDelimiterAt(s: string, index: number): boolean {
  const c = s[index];
  if (!DELIMITERS.has(c)) return false;
  if (c === ",") return true;
  return index === s.length - 1 || isWhitespace(s[index + 1]);
}

function isWhitespace(c: string | undefined): boolean {
  return c !== undefined && /\s/.test(c);
}

class QuoteParser {
  private readonly captured = new Map<string, string>();
  private readonly delimiterOf = new Map<string, string>();
  private readonly maskedText: string;

  constructor(input: string) {
    let n = 1;
    const pass1 = this.maskPass(input, QUOTED_SINGLECHAR, n);
    n = pass1.n;
    const pass2 = this.maskPass(pass1.out, QUOTED_TRIPLE, n);
    this.maskedText = pass2.out;
  }

  masked(): string {
    return this.maskedText;
  }

  restoreFrom(text: string): string {
    let out = text;
    const keys = [...this.captured.keys()].sort((a, b) => b.length - a.length);
    for (const key of keys) {
      const delim = this.delimiterOf.get(key) ?? "";
      const val = this.captured.get(key) ?? "";
      const escaped = escapeForQuote(val, delim.charAt(0));
      out = out.split(key).join(delim + escaped + delim);
    }
    return out;
  }

  private maskPass(input: string, pattern: RegExp, start: number): { out: string; n: number } {
    let n = start;
    const re = new RegExp(pattern.source, pattern.flags);
    const out = input.replace(re, (_whole, opening: string, inner: string) => {
      const placeholder = MASK_BOUNDARY + MASK_CONTENT.repeat(n) + MASK_BOUNDARY;
      n += 1;
      this.captured.set(placeholder, unescapeSameQuote(inner, opening.charAt(0)));
      this.delimiterOf.set(placeholder, opening);
      return placeholder;
    });
    return { out, n };
  }
}

class BracketMasker {
  private readonly captured = new Map<string, string>();
  private readonly bracketOf = new Map<string, string>();
  private maskedText: string;

  constructor(input: string) {
    this.maskedText = input;
    for (;;) {
      const before = this.maskedText;
      this.maskAllOfType("(", ")");
      this.maskAllOfType("{", "}");
      this.maskAllOfType("[", "]");
      this.maskAllOfType("<", ">");
      if (this.maskedText === before) break;
    }
  }

  masked(): string {
    return this.maskedText;
  }

  restoreFrom(text: string): string {
    let out = text;
    if (!out.includes(BRACKET_PREFIX)) return out;
    for (;;) {
      const before = out;
      if (!before.includes(BRACKET_PREFIX)) break;
      for (const [key, inner] of this.captured) {
        if (!out.includes(key)) continue;
        const br = this.bracketOf.get(key) ?? "()";
        out = out.split(key).join(br[0] + inner + br[1]);
      }
      if (out === before) break;
    }
    return out;
  }

  private maskAllOfType(open: string, close: string): void {
    const pat = new RegExp(innermostPattern(open, close), "g");
    for (;;) {
      if (!pat.test(this.maskedText)) break;
      pat.lastIndex = 0;
      this.maskedText = this.maskedText.replace(pat, (whole) => {
        const inner = whole.slice(1, whole.length - 1);
        const placeholder = BRACKET_PREFIX + (this.captured.size + 1);
        this.captured.set(placeholder, inner);
        this.bracketOf.set(placeholder, open + close);
        return placeholder;
      });
    }
  }
}

function innermostPattern(open: string, close: string): string {
  if (open === "(") return "\\([^()]*\\)";
  if (open === "{") return "\\{[^{}]*\\}";
  if (open === "[") return "\\[[^\\[\\]]*\\]";
  return "<[^<>]*>";
}

function unescapeSameQuote(s: string, q: string): string {
  return s.split(`\\${q}`).join(q);
}

function escapeForQuote(s: string, q: string): string {
  if (!s || !q) return s;
  return s.split(q).join(`\\${q}`);
}
