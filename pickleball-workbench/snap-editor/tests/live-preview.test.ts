/** @vitest-environment jsdom */

import { describe, expect, it } from "vitest";
import { SnapEditor } from "../src/core/editor";
import { createTypedRegistry } from "../src/packs/typed";
import { createPickleballRegistry } from "../src/packs/pickleball";
import { BLOCK_TYPE } from "../src/packs/core";
import { FEATURE_TYPE, SCENARIO_TYPE, STEP_TYPE } from "../src/packs/pickleball";

describe("live preview in the editor canvas", () => {
  it("shifts the stacked neighbor on hover and does not commit until drop", () => {
    const { editor, host } = mountEditor({
      schemaVersion: 1,
      roots: [
        { typeId: BLOCK_TYPE, id: "top", s: { typeId: BLOCK_TYPE, id: "below" } },
        { typeId: BLOCK_TYPE, id: "moving" },
      ],
    });

    let changes = 0;
    editor.onChange(() => {
      changes += 1;
    });
    const before = worldPos("below", host);
    const from = clientOn("moving", host, editor, 24, 12);
    const over = clientOn("top", host, editor, 8, 56);
    drag(from, over);

    expect(editor.view.svg.classList.contains("is-live-preview")).toBe(true);
    expect(worldPos("below", host).y).toBe(before.y);
    expect(cls("moving", host)).toContain("is-ghost");
    expect(editor.getDocument().roots.some((root) => root.id === "moving")).toBe(true);
    expect(changes).toBe(0);

    fire(window, "pointerup", over.x, over.y);
    expect(changes).toBe(1);
    expect(editor.view.svg.classList.contains("is-live-preview")).toBe(false);
    expect(editor.getDocument().roots[0]?.s?.id).toBe("moving");
    expect(editor.getDocument().roots[0]?.s?.s?.id).toBe("below");

    editor.destroy();
    host.remove();
  });

  it("shifts the horizontal trailer on hover and does not commit until drop", () => {
    const { editor, host } = mountEditor({
      schemaVersion: 1,
      roots: [
        { typeId: BLOCK_TYPE, id: "head", e: { typeId: BLOCK_TYPE, id: "trailer" } },
        { typeId: BLOCK_TYPE, id: "moving" },
      ],
    });

    const before = worldPos("trailer", host);
    const from = clientOn("moving", host, editor, 24, 12);
    const over = clientOn("head", host, editor, 184, 12);
    drag(from, over);

    expect(editor.view.svg.classList.contains("is-live-preview")).toBe(true);
    expect(worldPos("trailer", host).x).toBe(before.x);
    expect(editor.getDocument().roots[0]?.e?.id).toBe("trailer");

    fire(window, "pointerup", over.x, over.y);
    expect(editor.getDocument().roots[0]?.e?.id).toBe("moving");
    expect(editor.getDocument().roots[0]?.e?.e?.id).toBe("trailer");

    editor.destroy();
    host.remove();
  });

  it("grows the parent L on nest hover without writing until drop", () => {
    const { editor, host } = mountEditor({
      schemaVersion: 1,
      roots: [
        { typeId: BLOCK_TYPE, id: "parent" },
        { typeId: BLOCK_TYPE, id: "moving" },
      ],
    });

    const beforePath = bodyPath("parent", host);
    const from = clientOn("moving", host, editor, 24, 12);
    const over = clientOn("parent", host, editor, 48, 18);
    drag(from, over);

    expect(editor.view.svg.classList.contains("is-live-preview")).toBe(true);
    expect(bodyPath("parent", host)).toBe(beforePath);
    expect(cls("moving", host)).toContain("is-ghost");
    expect(editor.getDocument().roots.some((root) => root.id === "moving")).toBe(true);

    fire(window, "pointerup", over.x, over.y);
    expect(editor.getDocument().roots[0]?.nest?.do?.[0]?.id).toBe("moving");

    editor.destroy();
    host.remove();
  });

  it("toolbox create hover inserts into a stack without committing", () => {
    const { editor, host } = mountEditor({
      schemaVersion: 1,
      roots: [{ typeId: BLOCK_TYPE, id: "top", s: { typeId: BLOCK_TYPE, id: "below" } }],
    });

    const before = worldPos("below", host);
    const start = editor.view.worldToClient(520, 360);
    const overWorld = { x: worldPos("top", host).x + 32, y: worldPos("top", host).y + 62 };
    const over = editor.view.worldToClient(overWorld.x, overWorld.y);
    const down = new Event("pointerdown", { bubbles: true, cancelable: true });
    Object.assign(down, { button: 0, pointerId: 1, clientX: start.x, clientY: start.y });
    editor.beginCreateDrag(BLOCK_TYPE, down as PointerEvent);
    fire(window, "pointermove", over.x, over.y);

    expect(editor.view.svg.classList.contains("is-live-preview")).toBe(true);
    expect(worldPos("below", host).y).toBe(before.y);
    expect(editor.getDocument().roots[0]?.s?.id).toBe("below");

    fire(window, "pointerup", over.x, over.y);
    expect(editor.getDocument().roots[0]?.s?.id).not.toBe("below");
    expect(editor.getDocument().roots[0]?.s?.s?.id).toBe("below");

    editor.destroy();
    host.remove();
  });

  it("insert-between on a flush stack join slides the lower block on hover", () => {
    const { editor, host } = mountEditor({
      schemaVersion: 1,
      roots: [
        { typeId: BLOCK_TYPE, id: "top", s: { typeId: BLOCK_TYPE, id: "below" } },
        { typeId: BLOCK_TYPE, id: "moving" },
      ],
    });

    const top = worldPos("top", host);
    const below = worldPos("below", host);
    const before = { ...below };
    const from = clientOn("moving", host, editor, 24, 12);
    const join = editor.view.worldToClient(top.x + 8, (top.y + 46 + below.y) / 2);
    drag(from, join);

    expect(editor.view.svg.classList.contains("is-live-preview")).toBe(true);
    expect(worldPos("below", host).y).toBe(before.y);
    expect(editor.getDocument().roots[0]?.s?.id).toBe("below");

    fire(window, "pointerup", join.x, join.y);
    expect(editor.getDocument().roots[0]?.s?.id).toBe("moving");
    expect(editor.getDocument().roots[0]?.s?.s?.id).toBe("below");

    editor.destroy();
    host.remove();
  });

  it("Pickleball: hovering a step between Given and When moves When down without committing", () => {
    const host = documentCreateHost();
    const editor = new SnapEditor(host, {
      registry: createPickleballRegistry(),
      document: {
        schemaVersion: 1,
        roots: [
          {
            typeId: FEATURE_TYPE,
            id: "feat",
            nest: {
              do: [
                {
                  typeId: SCENARIO_TYPE,
                  id: "scen",
                  nest: {
                    do: [
                      {
                        typeId: STEP_TYPE,
                        id: "given",
                        data: { keyword: "Given", text: "go home" },
                        s: { typeId: STEP_TYPE, id: "when", data: { keyword: "When", text: "click" } },
                      },
                    ],
                  },
                },
              ],
            },
          },
          { typeId: STEP_TYPE, id: "moving", data: { keyword: "And", text: "wait" } },
        ],
      } as never,
    });
    sizeSvg(editor.view.svg);

    const given = worldPos("given", host);
    const when = worldPos("when", host);
    const beforeY = when.y;
    const from = clientOn("moving", host, editor, 24, 12);
    const join = editor.view.worldToClient(given.x + 12, (given.y + 46 + when.y) / 2);
    drag(from, join);

    expect(editor.view.svg.classList.contains("is-live-preview")).toBe(true);
    expect(worldPos("when", host).y).toBe(beforeY);
    const live = editor.getDocument();
    const steps = live.roots[0]?.nest?.do?.[0]?.nest?.do ?? [];
    expect(steps[0]?.id).toBe("given");
    expect(steps[0]?.s?.id).toBe("when");

    fire(window, "pointerup", join.x, join.y);
    const dropped = editor.getDocument();
    expect(dropped.roots[0]?.nest?.do?.[0]?.nest?.do?.[0]?.s?.id).toBe("moving");
    expect(dropped.roots[0]?.nest?.do?.[0]?.nest?.do?.[0]?.s?.s?.id).toBe("when");

    editor.destroy();
    host.remove();
  });
});

function mountEditor(document: { schemaVersion: 1; roots: unknown[] }) {
  const host = documentCreateHost();
  const editor = new SnapEditor(host, {
    registry: createTypedRegistry(),
    document: document as never,
  });
  sizeSvg(editor.view.svg);
  return { editor, host };
}

function documentCreateHost(): HTMLDivElement {
  const host = document.createElement("div");
  document.body.append(host);
  return host;
}

function sizeSvg(svg: SVGSVGElement): void {
  Object.defineProperty(svg, "clientWidth", { configurable: true, get: () => 900 });
  Object.defineProperty(svg, "clientHeight", { configurable: true, get: () => 700 });
  svg.getBoundingClientRect = () =>
    ({ left: 0, top: 0, right: 900, bottom: 700, width: 900, height: 700, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
}

function node(id: string, host: HTMLElement): SVGGElement {
  return host.querySelector(`[data-node-id="${id}"]`) as SVGGElement;
}

function cls(id: string, host: HTMLElement): string {
  return node(id, host).className.baseVal || node(id, host).getAttribute("class") || "";
}

function bodyPath(id: string, host: HTMLElement): string {
  return node(id, host).querySelector(".node-body")?.getAttribute("d") ?? "";
}

function worldPos(id: string, host: HTMLElement): { x: number; y: number } {
  let x = 0;
  let y = 0;
  let cur: Element | null = node(id, host);
  while (cur instanceof SVGGElement) {
    const t = cur.getAttribute("transform") ?? "";
    const match = /translate\(([-\d.]+)[ ,]+([-\d.]+)/.exec(t);
    if (match) {
      x += Number(match[1]);
      y += Number(match[2]);
    }
    cur = cur.parentElement;
  }
  return { x, y };
}

function clientOn(
  id: string,
  host: HTMLElement,
  editor: SnapEditor,
  localX: number,
  localY: number,
): { x: number; y: number } {
  const pos = worldPos(id, host);
  return editor.view.worldToClient(pos.x + localX, pos.y + localY);
}

function drag(from: { x: number; y: number }, to: { x: number; y: number }): void {
  fire(window, "pointermove", from.x, from.y);
  const moving = document.querySelector("[data-node-id='moving']");
  fire(moving ?? window, "pointerdown", from.x, from.y);
  fire(window, "pointermove", from.x + 12, from.y + 12);
  fire(window, "pointermove", to.x, to.y);
}

function fire(target: EventTarget, type: string, clientX: number, clientY: number): void {
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.assign(event, { button: 0, pointerId: 1, clientX, clientY });
  target.dispatchEvent(event);
}
