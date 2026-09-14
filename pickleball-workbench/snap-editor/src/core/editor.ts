import { layoutDocument, type Layout } from "./layout";
import { DocumentState, type NodeInit } from "./model/document";
import { HistoryStack } from "./model/history";
import { serializeDocument, parseDocument, canonicalize } from "./model/serialize";
import type { SnapDocument } from "./model/types";
import { emptyDocument } from "./model/types";
import { defaultRegistry, Registry } from "./registry";
import { EditorView, prototypeBadge, type ViewOverlay } from "./render/view";
import { pickSnap, pickSnapGhost, type SnapTarget } from "./snap";
import { previewSnap, realizeSnap, type SnapPreview } from "./snap/preview";
import { followPointer } from "./drag";
import type { EditorPack } from "../packs/types";
import type { GraphEvent, GraphEventListener } from "./events";

export interface SnapEditorOptions {
  document?: SnapDocument;
  registry?: Registry;
  pack?: EditorPack;
}

type ChangeListener = (doc: SnapDocument) => void;
type SelectionListener = (id: string | null) => void;

interface DragSession {
  pointerId: number;
  mode: "existing" | "create";
  type?: string;
  init?: NodeInit;
  nodeId?: string;
  unit: string[];
  grab: { x: number; y: number };
  origin: { x: number; y: number };
  display: { x: number; y: number };
  snapshot: string;
  started: boolean;
  fromControl?: boolean;
  startClient?: { x: number; y: number };
}

interface PanSession {
  pointerId: number;
  lastX: number;
  lastY: number;
}

interface FieldEdit {
  nodeId: string;
  field: string;
}

export class SnapEditor {
  readonly host: HTMLElement;
  readonly registry: Registry;
  readonly view: EditorView;
  readonly pack: EditorPack | null;
  private state: DocumentState;
  private layout: Layout;
  private history = new HistoryStack();
  private selectedId: string | null = null;
  private listeners = new Set<ChangeListener>();
  private selectionListeners = new Set<SelectionListener>();
  private eventListeners = new Set<GraphEventListener>();
  private drag: DragSession | null = null;
  private pan: PanSession | null = null;
  private snap: SnapTarget | null = null;
  private preview: SnapPreview | null = null;
  private lastPointer = { x: 0, y: 0 };
  private spaceDown = false;
  private fieldEdit: FieldEdit | null = null;
  private unbind: Array<() => void> = [];
  private locked = false;
  private playheadId: string | null = null;
  private executingId: string | null = null;
  private lastHostText: string | null = null;
  private paintFrame = 0;
  private windowPointers = false;
  private readonly boundMove = (event: PointerEvent) => this.onPointerMove(event);
  private readonly boundUp = (event: PointerEvent) => this.onPointerUp(event);

  constructor(host: HTMLElement, options: SnapEditorOptions = {}) {
    this.host = host;
    this.host.classList.add("snap-editor-host");
    this.pack = options.pack ?? null;
    this.registry = options.registry ?? new Registry();
    this.pack?.register(this.registry);
    if (this.registry.all().length === 0) {
      for (const def of (options.registry ?? defaultRegistry).all()) this.registry.register(def);
    }
    this.state = DocumentState.fromDocument(options.document ?? emptyDocument(), this.registry);
    this.state.ensurePocketKeys(this.registry);
    this.view = new EditorView(host);
    this.view.onControl = (event) => this.handleControl(event);
    this.view.onControlGrip = (nodeId, event) => this.armExistingDrag(event, nodeId, true);
    this.layout = layoutDocument(this.state, this.registry);
    this.bind();
    this.paint();
    this.fitIfNeeded();
  }

  exportText(): string {
    if (!this.pack?.exportText) return "";
    return this.pack.exportText(this.getDocument(), this.registry);
  }

  importText(text: string): void {
    if (!this.pack?.importText) return;
    const incoming = text ?? "";
    if (incoming === this.lastHostText) return;
    this.lastHostText = incoming;
    this.setDocument(this.pack.importText(incoming, this.registry));
  }

  getDocument(): SnapDocument {
    return canonicalize(this.state.toDocument(), this.registry);
  }

  setDocument(doc: SnapDocument): void {
    this.history.push(serializeDocument(this.state.toDocument(), this.registry));
    this.state = DocumentState.fromDocument(doc, this.registry);
    this.setSelected(null);
    this.refresh(true);
    this.fitIfNeeded();
  }

  onChange(cb: ChangeListener): () => void {
    this.listeners.add(cb);
    return () => this.listeners.delete(cb);
  }

  onSelection(cb: SelectionListener): () => void {
    this.selectionListeners.add(cb);
    return () => this.selectionListeners.delete(cb);
  }

  onEvent(cb: GraphEventListener): () => void {
    this.eventListeners.add(cb);
    return () => this.eventListeners.delete(cb);
  }

  getSelectedId(): string | null {
    return this.selectedId;
  }

  selectedNode() {
    return this.selectedId ? this.state.getNode(this.selectedId) : null;
  }

  getSelectedText(): string {
    const fromInput = selectedInputText();
    if (fromInput !== null) return fromInput;
    if (!this.selectedId) return "";
    const node = this.state.getNode(this.selectedId);
    const fields = node.fields ?? {};
    return fields.text ?? fields.condition ?? fields.rows ?? "";
  }

  setLocked(locked: boolean): void {
    this.locked = locked;
    this.host.classList.toggle("is-locked", locked);
    if (locked && this.drag) {
      if (this.drag.nodeId) this.view.markNodeClass(this.drag.nodeId, "is-ghost", false);
      this.drag = null;
      this.snap = null;
      this.preview = null;
      this.releaseWindowPointers();
    }
    this.paint();
  }

  setPlayhead(id: string | null): void {
    this.playheadId = id;
    this.paint();
  }

  setExecuting(id: string | null): void {
    this.executingId = id;
    this.paint();
  }

  undo(): void {
    if (this.locked) return;
    const prev = this.history.undo(serializeDocument(this.state.toDocument(), this.registry));
    if (!prev) return;
    this.state = DocumentState.fromDocument(parseDocument(prev), this.registry);
    this.refresh(true);
  }

  redo(): void {
    if (this.locked) return;
    const next = this.history.redo(serializeDocument(this.state.toDocument(), this.registry));
    if (!next) return;
    this.state = DocumentState.fromDocument(parseDocument(next), this.registry);
    this.refresh(true);
  }

  deleteSelected(): void {
    if (this.locked || !this.selectedId || this.drag) return;
    this.history.push(serializeDocument(this.state.toDocument(), this.registry));
    this.state.removeNodeSubtree(this.selectedId, this.registry);
    this.setSelected(null);
    this.view.clearFocus();
    this.refresh(true);
  }

  beginCreateDrag(type: string, event: PointerEvent, init?: NodeInit): void {
    if (this.locked) return;
    const world = this.view.clientToWorld(event.clientX, event.clientY);
    const proto = prototypeBadge(this.registry, type, world.x - 24, world.y - 16);
    const fields = { ...(this.pack?.defaultData?.(type) ?? {}), ...(init?.fields ?? {}) };
    this.drag = {
      pointerId: event.pointerId,
      mode: "create",
      type,
      init: { ...init, fields },
      unit: [],
      grab: { x: 24, y: 16 },
      origin: { x: proto.core.x, y: proto.core.y },
      display: { x: proto.core.x, y: proto.core.y },
      snapshot: serializeDocument(this.state.toDocument(), this.registry),
      started: true,
    };
    this.captureWindowPointers();
    try {
      (event.currentTarget as Element | null)?.setPointerCapture?.(event.pointerId);
    } catch {
      /* window pointer listeners still drive the drag */
    }
    this.lastPointer = world;
    this.view.svg.focus();
    this.syncHoverPreview();
    this.paint();
  }

  destroy(): void {
    for (const off of this.unbind) off();
    this.unbind = [];
    this.view.destroy();
    this.view.svg.remove();
  }

  private setSelected(id: string | null, notify = true): void {
    if (this.selectedId === id) return;
    if (this.selectedId) this.view.markNodeClass(this.selectedId, "is-selected", false);
    this.selectedId = id;
    if (id) this.view.markNodeClass(id, "is-selected", true);
    if (!notify) return;
    for (const cb of this.selectionListeners) cb(id);
    this.emit({ kind: "select", moving: id ? this.party(id) : undefined });
  }

  private bind(): void {
    const svg = this.view.svg;
    const onDown = (e: PointerEvent) => this.onPointerDown(e);
    const onKeyDown = (e: KeyboardEvent) => this.onKeyDown(e);
    const onKeyUp = (e: KeyboardEvent) => this.onKeyUp(e);
    const onWheel = (e: WheelEvent) => this.onWheel(e);
    svg.addEventListener("pointerdown", onDown);
    svg.addEventListener("pointermove", this.boundMove);
    svg.addEventListener("pointerup", this.boundUp);
    svg.addEventListener("pointercancel", this.boundUp);
    window.addEventListener("keydown", onKeyDown);
    window.addEventListener("keyup", onKeyUp);
    svg.addEventListener("wheel", onWheel, { passive: false });
    this.unbind.push(() => svg.removeEventListener("pointerdown", onDown));
    this.unbind.push(() => svg.removeEventListener("pointermove", this.boundMove));
    this.unbind.push(() => svg.removeEventListener("pointerup", this.boundUp));
    this.unbind.push(() => svg.removeEventListener("pointercancel", this.boundUp));
    this.unbind.push(() => window.removeEventListener("keydown", onKeyDown));
    this.unbind.push(() => window.removeEventListener("keyup", onKeyUp));
    this.unbind.push(() => svg.removeEventListener("wheel", onWheel));
    this.unbind.push(() => this.releaseWindowPointers());
  }

  private captureWindowPointers(): void {
    if (this.windowPointers) return;
    window.addEventListener("pointermove", this.boundMove);
    window.addEventListener("pointerup", this.boundUp);
    window.addEventListener("pointercancel", this.boundUp);
    this.windowPointers = true;
  }

  private releaseWindowPointers(): void {
    if (!this.windowPointers) return;
    window.removeEventListener("pointermove", this.boundMove);
    window.removeEventListener("pointerup", this.boundUp);
    window.removeEventListener("pointercancel", this.boundUp);
    this.windowPointers = false;
  }

  private onWheel(event: WheelEvent): void {
    if (isControlTarget(event.target)) return;
    event.preventDefault();
    const factor = event.deltaY > 0 ? 0.92 : 1.09;
    this.view.zoomAt(event.clientX, event.clientY, factor);
  }

  private onPointerDown(event: PointerEvent): void {
    if (this.startPan(event)) return;
    if (event.button !== 0) return;
    if (isControlTarget(event.target)) {
      const nodeEl = eventElement(event.target)?.closest?.("[data-node-id]");
      const rawId = nodeEl?.getAttribute("data-node-id");
      if (rawId) this.setSelected(rawId);
      return;
    }
    const world = this.view.clientToWorld(event.clientX, event.clientY);
    this.lastPointer = world;
    const nodeEl = eventElement(event.target)?.closest?.("[data-node-id]");
    const rawId = nodeEl?.getAttribute("data-node-id");
    if (!rawId) {
      this.view.clearFocus();
      this.setSelected(null);
      this.view.svg.focus();
      return;
    }
    if (this.locked) {
      this.setSelected(rawId);
      return;
    }
    this.armExistingDrag(event, rawId, false);
  }

  private armExistingDrag(event: PointerEvent, nodeId: string, fromControl: boolean): void {
    if (this.locked) {
      this.setSelected(nodeId);
      return;
    }
    const worldNode = this.layout.nodes.get(nodeId);
    if (!worldNode) return;
    const world = this.view.clientToWorld(event.clientX, event.clientY);
    this.lastPointer = world;
    this.setSelected(nodeId, false);
    this.drag = {
      pointerId: event.pointerId,
      mode: "existing",
      nodeId,
      unit: this.state.dragUnit(nodeId, this.registry),
      grab: { x: world.x - worldNode.x, y: world.y - worldNode.y },
      origin: { x: worldNode.x, y: worldNode.y },
      display: { x: worldNode.x, y: worldNode.y },
      snapshot: serializeDocument(this.state.toDocument(), this.registry),
      started: false,
      fromControl,
      startClient: { x: event.clientX, y: event.clientY },
    };
    this.captureWindowPointers();
    if (!fromControl) {
      try {
        this.view.svg.setPointerCapture(event.pointerId);
      } catch {
        /* jsdom and some embeds have no capture */
      }
      this.view.svg.focus();
    }
  }

  private startPan(event: PointerEvent): boolean {
    const middle = event.button === 1;
    const space = event.button === 0 && this.spaceDown && !isTyping(event);
    if (!middle && !space) return false;
    event.preventDefault();
    this.pan = { pointerId: event.pointerId, lastX: event.clientX, lastY: event.clientY };
    this.captureWindowPointers();
    this.view.svg.classList.add("is-panning");
    try {
      this.view.svg.setPointerCapture(event.pointerId);
    } catch {
      /* window listeners still drive the pan */
    }
    return true;
  }

  private onPointerMove(event: PointerEvent): void {
    if (this.pan && event.pointerId === this.pan.pointerId) {
      this.view.panBy(event.clientX - this.pan.lastX, event.clientY - this.pan.lastY);
      this.pan.lastX = event.clientX;
      this.pan.lastY = event.clientY;
      return;
    }
    if (!this.drag || event.pointerId !== this.drag.pointerId) return;
    const world = this.view.clientToWorld(event.clientX, event.clientY);
    this.lastPointer = world;
    if (!this.drag.started) {
      const origin = this.drag.startClient ?? {
        x: this.drag.origin.x + this.drag.grab.x,
        y: this.drag.origin.y + this.drag.grab.y,
      };
      const dist = this.drag.startClient
        ? Math.hypot(event.clientX - origin.x, event.clientY - origin.y)
        : Math.hypot(
            world.x - (this.drag.origin.x + this.drag.grab.x),
            world.y - (this.drag.origin.y + this.drag.grab.y),
          );
      if (dist < 5) return;
      this.startExistingDrag();
    }
    this.stepDrag();
  }

  private schedulePaint(): void {
    if (this.paintFrame) return;
    this.paintFrame = requestAnimationFrame(() => {
      this.paintFrame = 0;
      this.paint();
    });
  }

  private startExistingDrag(): void {
    if (!this.drag || !this.drag.nodeId || this.drag.started) return;
    const active = document.activeElement;
    if (active instanceof HTMLElement && isControlTarget(active)) active.blur();
    try {
      this.view.svg.setPointerCapture(this.drag.pointerId);
    } catch {
      /* capture optional */
    }
    this.view.svg.focus();
    const id = this.drag.nodeId;
    const world = this.layout.nodes.get(id);
    if (!world) return;
    this.drag.unit = this.state.dragUnit(id, this.registry);
    this.drag.origin = { x: world.x, y: world.y };
    this.drag.started = true;
    this.view.markNodeClass(id, "is-ghost", true);
    this.emit({ kind: "pickup", moving: this.party(id) });
  }

  private onPointerUp(event: PointerEvent): void {
    if (this.pan && event.pointerId === this.pan.pointerId) {
      this.pan = null;
      this.view.svg.classList.remove("is-panning");
      this.releaseWindowPointers();
      return;
    }
    if (!this.drag || event.pointerId !== this.drag.pointerId) return;
    const session = this.drag;
    this.syncHoverPreview();
    if (!session.started) {
      this.drag = null;
      this.snap = null;
      this.preview = null;
      this.releaseWindowPointers();
      if (session.nodeId) {
        for (const cb of this.selectionListeners) cb(session.nodeId);
        this.emit({ kind: "select", moving: this.party(session.nodeId) });
        if (!session.fromControl) this.view.focusControl(session.nodeId);
      }
      return;
    }
    const committed = this.commitDrag(session);
    if (session.nodeId) this.view.markNodeClass(session.nodeId, "is-ghost", false);
    this.drag = null;
    this.snap = null;
    this.preview = null;
    this.releaseWindowPointers();
    if (committed) this.refresh(true);
    else this.clearDragChrome();
  }

  private commitDrag(session: DragSession): boolean {
    if (!this.snap) {
      this.emit({ kind: "cancel", extra: { reason: "no-snap" } });
      return false;
    }
    const before = this.fingerprintOf(this.state);
    const preview = this.previewFromSnap(this.snap);
    if (!preview) {
      this.emit({ kind: "cancel", extra: { reason: "no-preview" } });
      return false;
    }
    const after = this.fingerprintOf(preview.state);
    if (after === before) {
      this.emit({ kind: "cancel", extra: { reason: "unchanged" } });
      return false;
    }
    this.history.push(session.snapshot);
    this.state = preview.state;
    if (session.mode === "create" && preview.createdId) this.setSelected(preview.createdId);
    this.emit({
      kind: "snap",
      moving: session.nodeId ? this.party(session.nodeId) : undefined,
      extra: { committed: true },
    });
    this.emit({ kind: "drop", extra: { committed: true } });
    return true;
  }

  private fingerprintOf(state: DocumentState): string {
    const doc = canonicalize(state.toDocument(), this.registry);
    if (this.pack?.exportText) return this.pack.exportText(doc, this.registry);
    return serializeDocument(doc, this.registry);
  }

  private clearDragChrome(): void {
    this.view.svg.classList.remove("is-live-preview");
    this.view.renderDragChrome(this.registry, this.layout, {
      selectedId: this.selectedId,
      offsets: new Map(),
      ghost: null,
      snap: null,
      snapGhosts: [],
      showPorts: false,
      hotPorts: [],
    });
  }

  private applySnapToNode(nodeId: string, pose: { x: number; y: number }): void {
    const snap = this.snap;
    if (!snap) {
      this.state.setTransform(nodeId, pose);
      return;
    }
    const stationaryId = this.snapStationaryId();
    realizeSnap(this.state, this.registry, nodeId, snap, pose);
    this.emit({
      kind: "snap",
      moving: this.party(nodeId),
      stationary: snap.kind === "nest" ? this.party(snap.parentId) : stationaryId ? this.party(stationaryId) : undefined,
      extra: snap.kind === "nest" ? { kind: "nest", pocketId: snap.pocketId } : { kind: snap.kind },
    });
  }

  private computeSnap(): SnapTarget | null {
    if (!this.drag?.started) return null;
    const target = {
      x: this.lastPointer.x - this.drag.grab.x,
      y: this.lastPointer.y - this.drag.grab.y,
    };
    if (this.drag.mode === "create" && this.drag.type) {
      const badge = prototypeBadge(this.registry, this.drag.type, target.x, target.y);
      return pickSnapGhost(this.state, this.registry, this.layout, badge.core, new Set(), [], this.lastPointer);
    }
    if (this.drag.nodeId) {
      return pickSnap(this.state, this.registry, this.layout, this.drag.nodeId, this.drag.origin, {
        x: this.drag.origin.x + (target.x - this.drag.origin.x),
        y: this.drag.origin.y + (target.y - this.drag.origin.y),
      });
    }
    return null;
  }

  private onKeyDown(event: KeyboardEvent): void {
    if (event.key === " " && !isTyping(event)) {
      this.spaceDown = true;
      this.view.svg.classList.add("space-pan");
      event.preventDefault();
    }
    const meta = event.metaKey || event.ctrlKey;
    if (event.key === "Escape") {
      if (isTyping(event)) {
        (event.target as HTMLElement | null)?.blur?.();
        this.view.clearFocus();
        this.view.svg.focus();
        event.preventDefault();
        return;
      }
      if (this.drag?.started) {
        if (this.drag.nodeId) this.view.markNodeClass(this.drag.nodeId, "is-ghost", false);
        this.drag = null;
        this.snap = null;
        this.preview = null;
        this.releaseWindowPointers();
        this.emit({ kind: "cancel", extra: { reason: "escape" } });
        this.clearDragChrome();
      } else {
        this.setSelected(null);
        this.view.clearFocus();
        this.paint();
      }
      return;
    }
    if (isTyping(event)) return;
    if (meta && event.key.toLowerCase() === "z") {
      event.preventDefault();
      if (event.shiftKey) this.redo();
      else this.undo();
      return;
    }
    if (meta && event.key.toLowerCase() === "y") {
      event.preventDefault();
      this.redo();
      return;
    }
    if ((event.key === "Backspace" || event.key === "Delete") && this.selectedId && !this.drag && !isTyping(event)) {
      event.preventDefault();
      this.deleteSelected();
    }
  }

  private onKeyUp(event: KeyboardEvent): void {
    if (event.key === " ") {
      this.spaceDown = false;
      this.view.svg.classList.remove("space-pan");
    }
  }

  private stepDrag(): void {
    if (!this.drag?.started) return;
    this.drag.display = followPointer(this.lastPointer, this.drag.grab);
    this.syncHoverPreview();
    this.paint();
  }

  private syncHoverPreview(): void {
    if (!this.drag?.started) {
      this.snap = null;
      this.preview = null;
      return;
    }
    this.snap = this.computeSnap();
    this.preview = null;
  }

  private previewFromSnap(snap: SnapTarget): SnapPreview | null {
    if (!this.drag?.started) return null;
    if (this.drag.mode === "create" && this.drag.type) {
      return previewSnap(this.state, this.registry, snap, { type: this.drag.type, init: this.drag.init });
    }
    if (this.drag.nodeId) {
      return previewSnap(this.state, this.registry, snap, { nodeId: this.drag.nodeId });
    }
    return null;
  }

  private refresh(emit: boolean): void {
    this.state.canonicalizeTransforms(this.registry);
    this.layout = layoutDocument(this.state, this.registry);
    this.paint();
    if (emit) {
      const doc = this.getDocument();
      this.lastHostText = this.pack?.exportText?.(doc, this.registry) ?? this.lastHostText;
      for (const cb of this.listeners) cb(doc);
      this.emit({ kind: "graph-change" });
    }
  }

  private paint(): void {
    const preview = this.preview;
    this.view.svg.classList.toggle("is-live-preview", Boolean(this.drag?.started));
    if (this.drag?.started) {
      const overlay: ViewOverlay = {
        selectedId: this.selectedId,
        offsets: new Map(),
        ghost: null,
        snap: this.snap,
        snapGhosts: [],
        showPorts: false,
        hotPorts: [],
      };
      if (this.drag.mode === "create" && this.drag.type) {
        overlay.ghost = prototypeBadge(this.registry, this.drag.type, this.drag.display.x, this.drag.display.y).core;
      } else if (this.drag.nodeId) {
        const origin = this.layout.nodes.get(this.drag.nodeId);
        if (origin) {
          overlay.ghost = { ...origin, x: this.drag.display.x, y: this.drag.display.y };
        }
      }
      this.view.renderDragChrome(this.registry, this.layout, overlay);
      return;
    }
    const overlay: ViewOverlay = {
      selectedId: this.selectedId,
      selectedBadge: this.selectedId ? [this.selectedId] : [],
      playheadId: this.playheadId,
      executingId: this.executingId,
      locked: this.locked,
      offsets: new Map(),
      ghost: null,
      snap: null,
      snapGhosts: [],
      showPorts: false,
      hotPorts: [],
    };
    this.view.render(this.state, this.registry, this.layout, overlay);
  }

  handleControl(event: {
    kind: "click" | "change" | "input";
    nodeId: string;
    action?: string;
    pocket?: string;
    field?: string;
    value?: string;
    caret?: number | null;
  }): void {
    if (this.locked) return;
    if (!this.state.nodes.has(event.nodeId)) return;
    if (event.kind === "input" && event.field !== undefined) {
      if (!this.fieldEdit || this.fieldEdit.nodeId !== event.nodeId || this.fieldEdit.field !== event.field) {
        this.history.push(serializeDocument(this.state.toDocument(), this.registry));
        this.fieldEdit = { nodeId: event.nodeId, field: event.field };
      }
      this.state.setField(event.nodeId, event.field, event.value ?? "");
      this.view.rememberFocus(event.nodeId, event.field, event.caret);
      this.refresh(false);
      return;
    }
    if (event.kind === "change" && event.field !== undefined) {
      if (!this.fieldEdit || this.fieldEdit.nodeId !== event.nodeId || this.fieldEdit.field !== event.field) {
        this.history.push(serializeDocument(this.state.toDocument(), this.registry));
        this.state.setField(event.nodeId, event.field, event.value ?? "");
        this.refresh(true);
      }
      this.fieldEdit = null;
      return;
    }
    if (event.kind === "click" && event.action === "addRepeatablePocket" && event.pocket) {
      this.history.push(serializeDocument(this.state.toDocument(), this.registry));
      this.state.addRepeatablePocket(event.nodeId, event.pocket, this.registry);
      this.refresh(true);
    }
  }

  private snapStationaryId(): string | null {
    const snap = this.snap;
    if (!snap) return null;
    if (snap.kind === "nest") return snap.parentId;
    return snap.highlightNodeId;
  }

  private party(id: string): { id: string; typeId: string } | undefined {
    const node = this.state.nodes.get(id);
    if (!node) return { id, typeId: this.drag?.type ?? "" };
    return { id: node.id, typeId: node.type };
  }

  private emit(event: GraphEvent): void {
    for (const cb of this.eventListeners) cb(event);
  }

  private fitIfNeeded(): void {
    this.view.fitToContent(this.layout, {
      width: this.host.clientWidth,
      height: this.host.clientHeight,
    });
  }
}

function eventElement(target: EventTarget | null): Element | null {
  if (!target) return null;
  if (target instanceof Element) return target;
  if (target instanceof Text) return target.parentElement;
  return null;
}

function isControlTarget(target: EventTarget | null): boolean {
  const el = eventElement(target);
  if (!el || typeof el.closest !== "function") return false;
  return Boolean(el.closest("input, select, textarea, button.node-plus, .node-control-host"));
}

function selectedInputText(): string | null {
  if (typeof document === "undefined") return null;
  const active = document.activeElement;
  if (!(active instanceof HTMLInputElement) && !(active instanceof HTMLTextAreaElement)) return null;
  const start = active.selectionStart;
  const end = active.selectionEnd;
  if (start != null && end != null && end > start) return active.value.slice(start, end);
  return active.value;
}

function isTyping(event: { target: EventTarget | null }): boolean {
  const target = eventElement(event.target);
  if (!target || !(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable;
}

function collectHotPorts(
  layout: Layout,
  ghost: { id: string; ports: Array<{ id: string; kind: string; direction: string; side: string }> },
  unit: Set<string>,
): Array<{ x: number; y: number; kind: string }> {
  const hot: Array<{ x: number; y: number; kind: string }> = [];
  for (const world of layout.nodes.values()) {
    if (unit.has(world.id) || world.id === ghost.id) continue;
    for (const to of world.ports) {
      const ok = ghost.ports.some((from) => from.direction !== to.direction && from.kind === to.kind);
      if (ok) hot.push({ x: to.x, y: to.y, kind: to.kind });
    }
  }
  return hot;
}
