/** @vitest-environment jsdom */

import { describe, expect, it } from "vitest";
import { SnapEditor } from "../src/core/editor";
import { pickleballPack, IF_TYPE, STEP_TYPE, PHRASE_TYPE } from "../src/packs/pickleball";

describe("block inner controls", () => {
  it("clicking + else if adds a branch row", () => {
    const host = document.createElement("div");
    document.body.append(host);
    const editor = new SnapEditor(host, { pack: pickleballPack });
    editor.setDocument({
      schemaVersion: 1,
      roots: [
        {
          typeId: IF_TYPE,
          id: "iff",
          data: { condition: "1 == 1" },
        },
      ],
    });
    const button = [...host.querySelectorAll("button.node-plus")].find((el) =>
      (el.textContent ?? "").includes("else if"),
    ) as HTMLButtonElement | undefined;
    expect(button).toBeTruthy();
    button!.click();
    const after = editor.getDocument().roots[0];
    expect(Object.keys(after.nest ?? {})).toContain("elseif-1");
    editor.destroy();
    host.remove();
  });

  it("clicking a step without dragging focuses the text field", () => {
    const host = document.createElement("div");
    document.body.append(host);
    const editor = new SnapEditor(host, {
      pack: pickleballPack,
      document: {
        schemaVersion: 1,
        roots: [{ typeId: STEP_TYPE, id: "step", data: { keyword: "Given", text: "hello" } }],
      },
    });
    let changes = 0;
    editor.onChange(() => {
      changes += 1;
    });
    const node = host.querySelector("[data-node-id='step']");
    expect(node).toBeTruthy();
    node!.dispatchEvent(pointer("pointerdown", 7));
    window.dispatchEvent(pointer("pointerup", 7));
    const input = host.querySelector("input.node-control") as HTMLInputElement | null;
    expect(input).toBeTruthy();
    expect(document.activeElement).toBe(input);
    expect(changes).toBe(0);
    editor.destroy();
    host.remove();
  });

  it("hides a dynamic step's leading comma in the text field but keeps it in the document", () => {
    const host = document.createElement("div");
    document.body.append(host);
    const editor = new SnapEditor(host, {
      pack: pickleballPack,
      document: {
        schemaVersion: 1,
        roots: [{ typeId: STEP_TYPE, id: "step", data: { keyword: "When", text: ", ensure shown" } }],
      },
    });
    const input = host.querySelector("input.node-control") as HTMLInputElement | null;
    expect(host.querySelector(".delim-chip")).toBeNull();
    expect(input?.value).toBe("ensure shown");
    expect(editor.getDocument().roots[0]?.data?.text).toBe(", ensure shown");
    editor.destroy();
    host.remove();
  });

  it("phrase blocks show the leading delimiter outside the text field", () => {
    const host = document.createElement("div");
    document.body.append(host);
    const editor = new SnapEditor(host, {
      pack: pickleballPack,
      document: {
        schemaVersion: 1,
        roots: [{ typeId: PHRASE_TYPE, id: "phrase", data: { text: ", click the button" } }],
      },
    });
    const chip = host.querySelector(".delim-chip");
    const input = host.querySelector("input.node-control") as HTMLInputElement | null;
    expect(chip?.textContent).toBe(",");
    expect(input?.value).toBe("click the button");
    editor.destroy();
    host.remove();
  });
});

function pointer(type: string, pointerId: number): Event {
  const event = new Event(type, { bubbles: true });
  Object.assign(event, { button: 0, pointerId, clientX: 24, clientY: 16 });
  return event;
}
