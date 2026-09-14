import { describe, expect, it } from "vitest";

const coreModules = import.meta.glob("../src/core/**/*.ts", {
  eager: true,
  query: "?raw",
  import: "default",
}) as Record<string, string>;

describe("core isolation", () => {
  it("does not mention pack grammar type names", () => {
    const files = Object.keys(coreModules);
    expect(files.length).toBeGreaterThan(5);
    for (const [file, text] of Object.entries(coreModules)) {
      expect(text, file).not.toMatch(/gherkin/i);
      expect(text, file).not.toMatch(/\bFeature\b/);
      expect(text, file).not.toMatch(/\bScenario\b/);
      expect(text, file).not.toMatch(/\bGiven\b/);
      expect(text, file).not.toMatch(/pkb\./);
    }
  });
});
