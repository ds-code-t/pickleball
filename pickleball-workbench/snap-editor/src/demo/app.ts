import { SnapEditor } from "../core/editor";
import { emptyDocument } from "../core/model/types";
import { nodePath } from "../core/render/paths";
import { prototypeBadge } from "../core/render/view";
import { installHostBridge } from "../core/host";
import type { EditorPack } from "../packs/types";
import { corePack } from "../packs/core";
import { typedPack } from "../packs/typed";
import { pickleballPack } from "../packs/pickleball";

const PACKS: EditorPack[] = [pickleballPack, corePack, typedPack];

export function bootDemo(root: Document = document): SnapEditor {
  const app = root.querySelector<HTMLElement>("#app");
  const editorHost = root.querySelector<HTMLElement>("#editor")!;
  const toolbox = root.querySelector<HTMLElement>("#toolbox")!;
  const jsonView = root.querySelector<HTMLElement>("#json")!;
  const gherkinView = root.querySelector<HTMLElement>("#gherkin")!;
  const scriptPanel = root.querySelector<HTMLElement>("#script-panel")!;
  const emptyHint = root.querySelector<HTMLElement>("#empty-hint")!;
  const inspector = root.querySelector<HTMLElement>("#inspector")!;
  const inspectorBody = root.querySelector<HTMLElement>("#inspector-body")!;
  const devPacks = root.querySelector<HTMLElement>("#dev-packs");
  const devToggle = root.querySelector<HTMLButtonElement>("#btn-dev-packs");

  let pack = PACKS.find((item) => item.id === (app?.dataset.pack || "pickleball")) ?? pickleballPack;
  let editor = makeEditor(editorHost, pack);
  let baseline = snapshot(editor);
  attachHost(editor);
  bindToolbox(toolbox, editor);
  bindEditor(editor);
  applyPackChrome(pack);

  function isDirty(): boolean {
    return snapshot(editor) !== baseline;
  }

  function confirmLose(message: string): boolean {
    if (!isDirty()) return true;
    return window.confirm(message);
  }

  function switchPack(next: EditorPack): void {
    if (next.id === pack.id) return;
    if (!confirmLose("Switching packs replaces the current scenario. Continue?")) {
      applyPackChrome(pack);
      return;
    }
    pack = next;
    editor.destroy();
    editorHost.replaceChildren();
    editor = makeEditor(editorHost, pack);
    attachHost(editor);
    bindToolbox(toolbox, editor);
    bindEditor(editor);
    applyPackChrome(pack);
    baseline = snapshot(editor);
    window.snapEditor = editor;
    refresh();
  }

  for (const tab of root.querySelectorAll<HTMLButtonElement>(".pack-tab")) {
    tab.addEventListener("click", () => {
      const next = PACKS.find((item) => item.id === tab.dataset.pack) ?? pickleballPack;
      switchPack(next);
    });
  }

  devToggle?.addEventListener("click", () => {
    if (!devPacks) return;
    const open = devPacks.hasAttribute("hidden");
    devPacks.toggleAttribute("hidden", !open);
    devToggle.setAttribute("aria-expanded", open ? "true" : "false");
  });

  function bindEditor(instance: SnapEditor): void {
    instance.onChange(() => refresh());
    instance.onSelection(() => refreshInspector(instance));
  }

  function applyPackChrome(next: EditorPack): void {
    if (app) {
      app.dataset.pack = next.id;
      app.classList.toggle("has-script", Boolean(next.exportText));
    }
    for (const tab of root.querySelectorAll<HTMLButtonElement>(".pack-tab")) {
      tab.setAttribute("aria-selected", tab.dataset.pack === next.id ? "true" : "false");
    }
    if (devPacks && (next.id === "core" || next.id === "typed")) {
      devPacks.removeAttribute("hidden");
    }
    scriptPanel.hidden = !next.exportText;
    emptyHint.textContent = next.emptyHint ?? "Drag a block from the left";
  }

  function refresh(): void {
    jsonView.innerHTML = highlight(JSON.stringify(editor.getDocument(), null, 2));
    const text = editor.exportText();
    const hasCodec = Boolean(editor.pack?.exportText);
    scriptPanel.hidden = !hasCodec;
    if (gherkinView) gherkinView.textContent = hasCodec ? text || "(empty)" : "";
    emptyHint.hidden = editor.getDocument().roots.length > 0;
    refreshInspector(editor);
  }

  function refreshInspector(instance: SnapEditor): void {
    inspector.hidden = false;
    const node = instance.selectedNode();
    if (!node) {
      inspectorBody.innerHTML = `<p class="inspector-hint">${escapeHtml(pack.inspectorHint ?? "Drag a block from the left. Click to edit.")}</p>`;
      return;
    }
    const def = instance.registry.has(node.type) ? instance.registry.get(node.type) : undefined;
    const hint = def?.hint ?? "Click to edit. Drag to snap.";
    inspectorBody.innerHTML = `
      <div class="inspector-label">${escapeHtml(node.label ?? def?.label ?? "Block")}</div>
      <p class="inspector-hint">${escapeHtml(hint)}</p>
      <button type="button" id="btn-delete-block">Delete block</button>
    `;
    inspectorBody.querySelector("#btn-delete-block")?.addEventListener("click", () => {
      instance.deleteSelected();
    });
  }

  root.querySelector("#btn-example")?.addEventListener("click", () => {
    if (!confirmLose("Load example replaces the current scenario. Continue?")) return;
    editor.setDocument(pack.example?.() ?? emptyDocument());
    baseline = snapshot(editor);
    refresh();
  });
  root.querySelector("#btn-clear")?.addEventListener("click", () => {
    if (!confirmLose("Clear removes the current scenario. Continue?")) return;
    editor.setDocument(emptyDocument());
    baseline = snapshot(editor);
    refresh();
  });
  root.querySelector("#btn-undo")?.addEventListener("click", () => editor.undo());
  root.querySelector("#btn-redo")?.addEventListener("click", () => editor.redo());
  root.querySelector("#btn-copy")?.addEventListener("click", async () => {
    await navigator.clipboard.writeText(JSON.stringify(editor.getDocument(), null, 2));
  });
  root.querySelector("#btn-copy-gherkin")?.addEventListener("click", async () => {
    await navigator.clipboard.writeText(editor.exportText());
  });

  refresh();
  window.snapEditor = editor;
  return editor;
}

function snapshot(editor: SnapEditor): string {
  return JSON.stringify(editor.getDocument());
}

function attachHost(editor: SnapEditor): void {
  const api = installHostBridge(editor);
  Object.assign(api, {
    getGherkin: () => api.getText(),
    setGherkin: (text: string) => api.setText(text),
  });
}

function makeEditor(host: HTMLElement, pack: EditorPack): SnapEditor {
  return new SnapEditor(host, { pack, document: pack.example?.() ?? emptyDocument() });
}

function bindToolbox(toolbox: HTMLElement, editor: SnapEditor): void {
  toolbox.replaceChildren();
  const grouped = new Map<string, ReturnType<typeof editor.registry.toolbox>>();
  for (const def of editor.registry.toolbox()) {
    const list = grouped.get(def.category) ?? [];
    list.push(def);
    grouped.set(def.category, list);
  }
  for (const [category, defs] of grouped) {
    const group = document.createElement("div");
    group.className = "tool-group";
    const title = document.createElement("div");
    title.className = "panel-title";
    title.textContent = category;
    group.append(title);
    for (const def of defs) {
      const item = document.createElement("button");
      item.type = "button";
      item.className = "tool-item";
      item.title = def.hint ?? "Drag onto the canvas";
      item.innerHTML = `${miniSvg(editor, def.type)}<span class="tool-copy"><span class="tool-caption">${escapeHtml(def.label)}</span>${def.hint ? `<span class="tool-hint">${escapeHtml(def.hint)}</span>` : ""}</span>`;
      item.addEventListener("pointerdown", (event) => {
        event.preventDefault();
        editor.beginCreateDrag(def.type, event, { label: def.label });
      });
      group.append(item);
    }
    toolbox.append(group);
  }
}

function miniSvg(editor: SnapEditor, type: string): string {
  const { core } = prototypeBadge(editor.registry, type, 0, 0);
  const pad = 10;
  const d = nodePath(core);
  return `<svg viewBox="${-pad} ${-pad} ${core.width + pad * 2} ${core.height + pad * 2}" aria-hidden="true"><path d="${d}" fill="${core.color.fill}" stroke="${core.color.stroke}" stroke-width="1.6"/></svg>`;
}

function highlight(text: string): string {
  return text.replace(
    /("(\\u[\da-fA-F]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(-?\d+(?:\.\d+)?)\b)/g,
    (match) => {
      if (match.endsWith(":")) return `<span class="key">${escapeHtml(match)}</span>`;
      if (match.startsWith("\"")) return `<span class="string">${escapeHtml(match)}</span>`;
      return `<span class="number">${escapeHtml(match)}</span>`;
    },
  );
}

function escapeHtml(value: string): string {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
