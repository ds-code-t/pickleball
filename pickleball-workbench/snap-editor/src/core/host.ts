import { SnapEditor } from "./editor";
import type { SnapDocument } from "./model/types";

export interface SnapEditorHost {
  getDocument: () => SnapDocument;
  setDocument: (doc: SnapDocument) => void;
  getText: () => string;
  setText: (text: string) => void;
  getSelectedText: () => string;
  setLocked: (locked: boolean) => void;
  setPlayhead: (id: string | null) => void;
  setExecuting: (id: string | null) => void;
  onChange: (cb: (doc: SnapDocument) => void) => () => void;
  onSelection: (cb: (id: string | null) => void) => () => void;
}

/** Host bridge for an embedder such as a desktop WebView. */
export function installHostBridge(editor: SnapEditor, target: Window = window): SnapEditorHost {
  const api: SnapEditorHost = {
    getDocument: () => editor.getDocument(),
    setDocument: (doc: SnapDocument) => editor.setDocument(doc),
    getText: () => editor.exportText(),
    setText: (text: string) => editor.importText(text),
    getSelectedText: () => editor.getSelectedText(),
    setLocked: (locked: boolean) => editor.setLocked(locked),
    setPlayhead: (id: string | null) => editor.setPlayhead(id),
    setExecuting: (id: string | null) => editor.setExecuting(id),
    onChange: (cb: (doc: SnapDocument) => void) => editor.onChange(cb),
    onSelection: (cb: (id: string | null) => void) => editor.onSelection(cb),
  };
  Object.assign(target, { snapEditor: editor, SnapEditorHost: api });
  return api;
}

declare global {
  interface Window {
    snapEditor?: SnapEditor;
    SnapEditorHost?: SnapEditorHost;
  }
}
