import "./styles.css";
import { SnapEditor } from "./core/editor";
import { pickleballPack } from "./packs/pickleball";
import { installHostBridge } from "./core/host";

type WorkbenchWindow = Window & {
  gherkinHost?: {
    gherkinChanged?(text: string): void;
    selectionText?(text: string): void;
    blockAction?(json: string): void;
    ready?(): void;
  };
  setWorkbenchGherkin?: (payload: string | { text?: string; locked?: boolean }, locked?: boolean) => void;
  onWorkbenchReady?: () => void;
};

const host = document.querySelector<HTMLElement>("#editor") ?? document.body;
host.replaceChildren();
host.style.width = "100%";
host.style.height = "100%";
host.style.minHeight = "240px";
const editor = new SnapEditor(host, { pack: pickleballPack });
const api = installHostBridge(editor);
let suppress = false;
Object.assign(api, {
  getGherkin: () => api.getText(),
  setGherkin: (text: string) => api.setText(text),
  getSelectedText: () => api.getSelectedText(),
  setLocked: (locked: boolean) => api.setLocked(locked),
  setPlayhead: (id: string | null) => api.setPlayhead(id),
  setExecuting: (id: string | null) => api.setExecuting(id),
});
api.onChange(() => {
  if (suppress) return;
  (window as WorkbenchWindow).gherkinHost?.gherkinChanged?.(api.getText());
});
api.onSelection(() => {
  if (suppress) return;
  (window as WorkbenchWindow).gherkinHost?.selectionText?.(api.getSelectedText());
});
editor.onEvent((event) => {
  if (suppress) return;
  (window as WorkbenchWindow).gherkinHost?.blockAction?.(JSON.stringify(event));
});
function notifyReady(): void {
  (window as WorkbenchWindow).gherkinHost?.ready?.();
}
(window as WorkbenchWindow).setWorkbenchGherkin = (payload, lockedFlag) => {
  const parsed = typeof payload === "string" && payload.trim().startsWith("{")
    ? JSON.parse(payload) as { text?: string; locked?: boolean }
    : { text: payload as string, locked: lockedFlag };
  const text = parsed.text ?? "";
  const locked = !!parsed.locked;
  suppress = true;
  try {
    api.setText(text);
    api.setLocked(locked);
  } finally {
    suppress = false;
  }
};
(window as WorkbenchWindow).onWorkbenchReady = notifyReady;
notifyReady();
