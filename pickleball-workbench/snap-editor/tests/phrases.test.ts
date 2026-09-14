import { describe, expect, it } from "vitest";
import { joinPhrases, splitPhrases, isDynamicPhraseText } from "../src/packs/pickleball";

describe("Pickleball 2.1.9 phrase split/join", () => {
  it("keeps quoted commas inside a single phrase", () => {
    const src = `, click the "Save, now" Button`;
    expect(splitPhrases(src)).toEqual([src]);
    expect(joinPhrases(splitPhrases(src))).toBe(src);
  });

  it("splits on commas inside unmatched quotes", () => {
    const src = `, hello "world, foo`;
    expect(splitPhrases(src)).toEqual([`, hello "world`, `, foo`]);
    expect(joinPhrases(splitPhrases(src))).toBe(src);
  });

  it("treats semicolon as a chain-link terminator", () => {
    const src = `, first; second`;
    expect(splitPhrases(src)).toEqual([`, first`, `; second`]);
    expect(joinPhrases(splitPhrases(src))).toBe(src);
  });

  it("does not split semicolon or colon unless at end or before whitespace", () => {
    expect(splitPhrases(`, url:home, next`)).toEqual([`, url:home`, `, next`]);
    expect(splitPhrases(`, wait;no-split, yes`)).toEqual([`, wait;no-split`, `, yes`]);
  });

  it("masks brackets so inner commas do not split", () => {
    const src = `, call fn(a, b) now`;
    expect(splitPhrases(src)).toEqual([src]);
  });

  it("preserves leading delimiters and join is concatenation", () => {
    const src = `, click "a, b" Link; wait: more.`;
    const parts = splitPhrases(src);
    expect(parts[0]?.startsWith(",")).toBe(true);
    expect(parts.some((p) => p.startsWith(";"))).toBe(true);
    expect(joinPhrases(parts)).toBe(src);
  });

  it("only treats comma-leading text as dynamic", () => {
    expect(isDynamicPhraseText("navigate to: URL.home")).toBe(false);
    expect(isDynamicPhraseText(", click")).toBe(true);
    expect(isDynamicPhraseText(" , click")).toBe(true);
  });
});
