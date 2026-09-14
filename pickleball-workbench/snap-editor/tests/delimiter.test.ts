import { describe, expect, it } from "vitest";
import { joinLeadingDelimiter, splitLeadingDelimiter } from "../src/core/render/delimiter";

describe("leading phrase delimiter chip", () => {
  it("splits punctuation and following space from the rest of the phrase", () => {
    expect(splitLeadingDelimiter(", click")).toEqual({ mark: ",", space: " ", rest: "click" });
    expect(splitLeadingDelimiter("; wait")).toEqual({ mark: ";", space: " ", rest: "wait" });
    expect(splitLeadingDelimiter(": And")).toEqual({ mark: ":", space: " ", rest: "And" });
    expect(splitLeadingDelimiter(". done")).toEqual({ mark: ".", space: " ", rest: "done" });
    expect(splitLeadingDelimiter("! go")).toEqual({ mark: "!", space: " ", rest: "go" });
    expect(splitLeadingDelimiter("? ask")).toEqual({ mark: "?", space: " ", rest: "ask" });
  });

  it("does not treat a normal step as a delimiter phrase", () => {
    expect(splitLeadingDelimiter("navigate to: URL.home")).toBeNull();
    expect(splitLeadingDelimiter("")).toBeNull();
  });

  it("rejoins without losing the original spacing", () => {
    const parts = splitLeadingDelimiter(",  click")!;
    expect(joinLeadingDelimiter({ ...parts, rest: "save now" })).toBe(",  save now");
  });
});
