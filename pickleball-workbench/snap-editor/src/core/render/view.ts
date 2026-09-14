import { layoutDocument, type Layout, type WorldNode } from "../layout";
import { DocumentState } from "../model/document";
import type { Registry } from "../registry";
import type { SnapTarget } from "../snap";
import { nodePath } from "./paths";
import type { ControlDefinition, GlyphDefinition } from "../registry/types";
import { joinLeadingDelimiter, splitLeadingDelimiter, type LeadingDelimiter } from "./delimiter";

const SVG_NS = "http://www.w3.org/2000/svg";
const HAT_PAD_X = 10;
const HAT_GAP = 6;
const CONTROL_H = 28;
const KEYWORD_W = 68;
const CHIP_W = 22;
const MIN_ZOOM = 0.35;
const MAX_ZOOM = 2.5;

export interface ViewOverlay {
  selectedId: string | null;
  selectedBadge?: string[];
  playheadId?: string | null;
  executingId?: string | null;
  locked?: boolean;
  offsets: Map<string, { x: number; y: number }>;
  ghost: WorldNode | null;
  snap: SnapTarget | null;
  snapGhosts?: WorldNode[];
  /** Nodes drawn as the translucent cluster at the live snap pose. */
  ghostIds?: string[];
  showPorts: boolean;
  hotPorts?: Array<{ x: number; y: number; kind: string }>;
}

export type ControlActionEvent = {
  kind: "click" | "change" | "input";
  nodeId: string;
  action?: string;
  pocket?: string;
  field?: string;
  value?: string;
  caret?: number | null;
};

interface DrawOpts {
  selected?: boolean;
  showPorts?: boolean;
  preview?: boolean;
  ghost?: boolean;
  playhead?: boolean;
  executing?: boolean;
  locked?: boolean;
}

interface PlacedControl {
  control: ControlDefinition;
  x: number;
  y: number;
  width: number;
  height: number;
  delim?: LeadingDelimiter | null;
  showChip?: boolean;
}

interface FocusMemory {
  nodeId: string;
  field?: string;
  caret?: number | null;
}

export class EditorView {
  readonly svg: SVGSVGElement;
  onControl: ((event: ControlActionEvent) => void) | null = null;
  onControlGrip: ((nodeId: string, event: PointerEvent) => void) | null = null;
  panX = 0;
  panY = 0;
  scale = 1;
  private readonly defs: SVGDefsElement;
  private readonly world: SVGGElement;
  private readonly grid: SVGGElement;
  private readonly connectors: SVGGElement;
  private readonly nodes: SVGGElement;
  private readonly overlays: SVGGElement;
  private knownGradients = new Set<string>();
  private focusMemory: FocusMemory | null = null;
  private resizeObs: ResizeObserver | null = null;

  constructor(host: HTMLElement) {
    this.svg = el("svg", {
      class: "snap-canvas",
      tabindex: "0",
    });
    this.defs = el("defs");
    this.defs.append(
      filterShadow(),
      filterGlow("insert-glow", "#4C97FF"),
      filterGlow("port-glow", "#82B5FF"),
      filterGlow("select-glow", "#FFFFFF", 2.4, 0.75),
    );
    this.grid = el("g", { class: "grid-layer" });
    this.connectors = el("g", { class: "connector-layer" });
    this.nodes = el("g", { class: "node-layer" });
    this.overlays = el("g", { class: "overlay-layer" });
    this.world = el("g", { class: "camera-layer" });
    this.world.append(this.grid, this.connectors, this.nodes, this.overlays);
    this.svg.append(this.defs, this.world);
    host.append(this.svg);
    this.drawGrid();
    this.applyCamera();
    if (typeof ResizeObserver !== "undefined") {
      this.resizeObs = new ResizeObserver(() => this.applyCamera());
      this.resizeObs.observe(host);
    }
  }

  destroy(): void {
    this.resizeObs?.disconnect();
    this.resizeObs = null;
  }

  setCamera(panX: number, panY: number, scale: number): void {
    this.panX = panX;
    this.panY = panY;
    this.scale = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, scale));
    this.applyCamera();
  }

  panBy(dx: number, dy: number): void {
    this.setCamera(this.panX + dx, this.panY + dy, this.scale);
  }

  zoomAt(clientX: number, clientY: number, factor: number): void {
    const next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, this.scale * factor));
    if (next === this.scale) return;
    const world = this.clientToWorld(clientX, clientY);
    const rect = this.svg.getBoundingClientRect();
    this.setCamera(clientX - rect.left - world.x * next, clientY - rect.top - world.y * next, next);
  }

  fitToContent(layout: Layout, viewport: { width: number; height: number }): void {
    if (viewport.width < 16 || viewport.height < 16) return;
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const node of layout.nodes.values()) {
      minX = Math.min(minX, node.x);
      minY = Math.min(minY, node.y);
      maxX = Math.max(maxX, node.x + node.width);
      maxY = Math.max(maxY, node.y + node.stackHeight);
    }
    if (!Number.isFinite(minX)) {
      this.setCamera(0, 0, 1);
      return;
    }
    const pad = 56;
    const bw = Math.max(maxX - minX, 80);
    const bh = Math.max(maxY - minY, 80);
    if (bw + pad * 2 <= viewport.width && bh + pad * 2 <= viewport.height) {
      this.setCamera(pad - minX, pad - minY, 1);
      return;
    }
    const scale = Math.max(MIN_ZOOM, Math.min((viewport.width - pad * 2) / bw, (viewport.height - pad * 2) / bh, 1));
    this.setCamera((viewport.width - bw * scale) / 2 - minX * scale, pad - minY * scale, scale);
  }

  clientToWorld(clientX: number, clientY: number): { x: number; y: number } {
    const ctm = safeScreenCtm(this.svg);
    if (ctm) return transformPoint(this.svg, ctm, clientX, clientY);
    const rect = this.svg.getBoundingClientRect?.() ?? { left: 0, top: 0 };
    return {
      x: (clientX - rect.left - this.panX) / this.scale,
      y: (clientY - rect.top - this.panY) / this.scale,
    };
  }

  worldToClient(x: number, y: number): { x: number; y: number } {
    const ctm = safeScreenCtm(this.svg);
    if (!ctm || typeof this.svg.createSVGPoint !== "function") {
      const rect = this.svg.getBoundingClientRect?.() ?? { left: 0, top: 0 };
      return { x: rect.left + this.panX + x * this.scale, y: rect.top + this.panY + y * this.scale };
    }
    const pt = this.svg.createSVGPoint();
    pt.x = x;
    pt.y = y;
    const client = pt.matrixTransform(ctm);
    return { x: client.x, y: client.y };
  }

  rememberFocus(nodeId: string, field?: string, caret?: number | null): void {
    this.focusMemory = { nodeId, field, caret };
  }

  clearFocus(): void {
    this.focusMemory = null;
  }

  focusControl(nodeId: string, field?: string, caret?: number | null): boolean {
    this.rememberFocus(nodeId, field, caret);
    return this.restoreFocus();
  }

  render(state: DocumentState, registry: Registry, layout: Layout, overlay: ViewOverlay): void {
    this.ensureGradients(registry);
    this.nodes.replaceChildren();
    this.connectors.replaceChildren();
    this.overlays.replaceChildren();

    const worlds = applyOffsets(layout, overlay.offsets);
    const ghostIds = new Set(overlay.ghostIds ?? []);
    const drawn = new Set<string>();
    const drawNodeAt = (id: string, originX: number, originY: number): SVGGElement | null => {
      const world = worlds.nodes.get(id);
      if (!world) return null;
      const ghost = ghostIds.has(id);
      return this.drawNode(
        world,
        {
          selected: overlay.selectedId === id || Boolean(overlay.selectedBadge?.includes(id)),
          showPorts: overlay.showPorts && !ghost,
          preview: ghost,
          ghost,
          playhead: overlay.playheadId === id,
          executing: overlay.executingId === id,
          locked: overlay.locked,
        },
        world.x - originX,
        world.y - originY,
        registry,
      );
    };
    /** Live snap preview is drawn in world space so ghost opacity does not wrap neighbors. */
    if (ghostIds.size > 0) {
      const ghosts: SVGGElement[] = [];
      for (const id of worlds.order) {
        const world = worlds.nodes.get(id);
        if (!world) continue;
        const g = drawNodeAt(id, 0, 0);
        if (!g) continue;
        if (ghostIds.has(id)) ghosts.push(g);
        else this.nodes.append(g);
      }
      for (const g of ghosts) this.nodes.append(g);
    } else {
      const drawTree = (id: string, originX: number, originY: number): SVGGElement | null => {
        if (drawn.has(id)) return null;
        const g = drawNodeAt(id, originX, originY);
        if (!g) return null;
        drawn.add(id);
        const record = state.nodes.get(id);
        if (record) {
          for (const children of Object.values(record.pockets)) {
            for (const childId of children) {
              const child = drawTree(childId, worlds.nodes.get(id)?.x ?? originX, worlds.nodes.get(id)?.y ?? originY);
              if (child) g.append(child);
            }
          }
        }
        for (const edge of state.outgoingFlush(id, registry)) {
          const child = drawTree(edge.to.nodeId, worlds.nodes.get(id)?.x ?? originX, worlds.nodes.get(id)?.y ?? originY);
          if (child) g.append(child);
        }
        return g;
      };
      for (const id of worlds.order) {
        if (drawn.has(id)) continue;
        if (!state.isFree(id, registry) && state.nodes.has(id)) continue;
        const tree = drawTree(id, 0, 0);
        if (tree) this.nodes.append(tree);
      }
      for (const id of worlds.order) {
        if (drawn.has(id)) continue;
        const tree = drawTree(id, 0, 0);
        if (tree) this.nodes.append(tree);
      }
    }
    if (overlay.ghost) {
      const g = this.drawNode(
        overlay.ghost,
        { selected: true, showPorts: false, preview: true },
        overlay.ghost.x,
        overlay.ghost.y,
        registry,
      );
      g.classList.add("is-ghost");
      this.nodes.append(g);
    }

    for (const port of overlay.hotPorts ?? []) {
      this.overlays.append(
        el("circle", {
          class: "port-hint",
          cx: String(port.x),
          cy: String(port.y),
          r: "7",
          fill: "rgba(76,151,255,0.14)",
          stroke: "#82B5FF",
          "stroke-width": "1.8",
          "pointer-events": "none",
        }),
      );
    }
    if (overlay.snap) {
      this.overlays.append(this.drawSnap(overlay.snap, worlds));
    }
    for (const ghost of overlay.snapGhosts ?? []) {
      this.overlays.append(this.drawSnapGhost(ghost, registry));
    }
    this.restoreFocus();
  }

  /** Overlay-only drag chrome. Leaves the existing node tree untouched. */
  renderDragChrome(registry: Registry, layout: Layout, overlay: ViewOverlay): void {
    this.overlays.replaceChildren();
    if (overlay.ghost) {
      const g = this.drawNode(
        overlay.ghost,
        { selected: true, showPorts: false, preview: true },
        overlay.ghost.x,
        overlay.ghost.y,
        registry,
      );
      g.classList.add("is-ghost");
      this.overlays.append(g);
    }
    for (const port of overlay.hotPorts ?? []) {
      this.overlays.append(
        el("circle", {
          class: "port-hint",
          cx: String(port.x),
          cy: String(port.y),
          r: "7",
          fill: "rgba(76,151,255,0.14)",
          stroke: "#82B5FF",
          "stroke-width": "1.8",
          "pointer-events": "none",
        }),
      );
    }
    if (overlay.snap) this.overlays.append(this.drawSnap(overlay.snap, layout));
    for (const ghost of overlay.snapGhosts ?? []) {
      this.overlays.append(this.drawSnapGhost(ghost, registry));
    }
  }

  markNodeClass(nodeId: string | null, className: string, on: boolean): void {
    if (!nodeId) return;
    const node = this.svg.querySelector(`[data-node-id="${cssAttr(nodeId)}"]`);
    if (!(node instanceof Element)) return;
    node.classList.toggle(className, on);
  }

  private applyCamera(): void {
    this.world.removeAttribute("transform");
    const w = this.svg.clientWidth;
    const h = this.svg.clientHeight;
    if (w < 8 || h < 8) {
      this.svg.removeAttribute("viewBox");
      return;
    }
    const vbX = -this.panX / this.scale;
    const vbY = -this.panY / this.scale;
    this.svg.setAttribute("viewBox", `${vbX} ${vbY} ${w / this.scale} ${h / this.scale}`);
    this.svg.setAttribute("preserveAspectRatio", "none");
  }

  private restoreFocus(): boolean {
    const mem = this.focusMemory;
    if (!mem) return false;
    const scope = this.svg.querySelector(`[data-node-id="${cssAttr(mem.nodeId)}"]`);
    if (!scope) return false;
    const fieldSelector = mem.field
      ? `input.node-control[data-control-field="${cssAttr(mem.field)}"], select.node-control[data-control-field="${cssAttr(mem.field)}"]`
      : null;
    const control =
      (fieldSelector
        ? scope.querySelector<HTMLInputElement | HTMLSelectElement>(fieldSelector)
        : scope.querySelector<HTMLInputElement>("input.node-control")) ??
      scope.querySelector<HTMLSelectElement>("select.node-control");
    if (!control) return false;
    control.focus();
    if (control instanceof HTMLInputElement && mem.caret != null) {
      const caret = Math.max(0, Math.min(control.value.length, mem.caret));
      control.setSelectionRange(caret, caret);
    }
    return true;
  }

  private drawNode(
    world: WorldNode,
    opts: DrawOpts,
    tx = world.x,
    ty = world.y,
    registry?: Registry,
  ): SVGGElement {
    const selected = Boolean(opts.selected);
    const preview = Boolean(opts.preview);
    const ghost = Boolean(opts.ghost);
    const playhead = Boolean(opts.playhead);
    const executing = Boolean(opts.executing);
    const g = el("g", {
      class: [
        "node",
        selected ? "is-selected" : "",
        preview ? "is-preview" : "",
        ghost ? "is-ghost" : "",
        playhead ? "is-playhead playhead" : "",
        executing ? "is-executing executing" : "",
      ]
        .filter(Boolean)
        .join(" "),
      "data-node-id": world.id,
      transform: `translate(${tx} ${ty})`,
    });
    const d = nodePath(world, world.joins);
    let stroke = selected && !preview ? "#FFFFFF" : world.color.stroke;
    let strokeWidth = selected && !preview ? "2.8" : "1.4";
    if (!preview && executing) {
      stroke = "#7ec8ff";
      strokeWidth = "3.2";
    } else if (!preview && playhead) {
      stroke = "#f5d76e";
      strokeWidth = "3";
    }
    const body = el("path", {
      class: "node-body",
      d,
      fill: `url(#grad-${typeToken(world.type)})`,
      stroke,
      "stroke-width": strokeWidth,
      filter: selected && !preview ? "url(#select-glow)" : "url(#node-shadow)",
    });
    const sheen = el("path", {
      class: "node-sheen",
      d,
      fill: "none",
      stroke: "rgba(255,255,255,0.22)",
      "stroke-width": "1",
      transform: "translate(0 0.5)",
      "pointer-events": "none",
    });
    g.append(body, sheen);

    for (const label of nodeLabels(world, registry)) {
      g.append(
        el(
          "text",
          {
            class: "node-label",
            x: String(label.x),
            y: String(label.y),
            fill: "rgba(255,255,255,0.96)",
            "font-size": "15",
            "font-weight": "700",
            "pointer-events": "none",
          },
          label.text,
        ),
      );
    }

    if (registry?.has(world.type)) {
      const def = registry.get(world.type);
      for (const placed of placeHatControls(world, def)) {
        g.append(this.drawControl(world, placed, preview, opts.locked, selected));
      }
      if (world.wrapped) {
        let y = world.hatHeight;
        world.pockets.forEach((pocket, i) => {
          if (i > 0) {
            const pocketDef = pocketDefFor(def, pocket.id);
            if (pocketDef?.controlField) {
              g.append(
                this.drawControl(
                  world,
                  {
                    control: {
                      id: pocket.id,
                      kind: "text",
                      field: pocket.id,
                      x: 72,
                      y: y + 3,
                      width: Math.max(world.width - 120, 64),
                      height: Math.max(world.midHeight - 6, 22),
                    },
                    x: 72,
                    y: y + 3,
                    width: Math.max(world.width - 120, 64),
                    height: Math.max(world.midHeight - 6, 22),
                  },
                  preview,
                  opts.locked,
                  selected,
                ),
              );
            }
            const addPocket = pocketDef?.repeatable ? pocketDef.id : /^else(?:-\d+)?$/.test(pocket.id) ? "else" : null;
            if (addPocket) {
              const addLabel = pocketDef?.label ? `+ ${pocketDef.label}` : "+ else";
              const addW = Math.max(24, addLabel.length * 7 + 12);
              g.append(
                this.drawControl(
                  world,
                  {
                    control: {
                      id: `add-${pocket.id}`,
                      kind: "button",
                      label: addLabel,
                      action: "addRepeatablePocket",
                      pocket: addPocket,
                      x: world.width - addW - 8,
                      y: y + 3,
                      width: addW,
                      height: Math.max(world.midHeight - 6, 22),
                    },
                    x: world.width - addW - 8,
                    y: y + 3,
                    width: addW,
                    height: Math.max(world.midHeight - 6, 22),
                  },
                  preview,
                  opts.locked,
                  selected,
                ),
              );
            }
            y += world.midHeight;
          }
          y += pocket.height;
        });
      }
    }

    for (const port of world.ports) {
      const visible = Boolean(opts.showPorts);
      g.append(
        el("circle", {
          class: `port-dot kind-${port.kind}`,
          "data-port-id": port.id,
          cx: String(port.x - world.x),
          cy: String(port.y - world.y),
          r: "3",
          fill: port.kind === "value" ? "#FFE9A8" : "#D6E7FF",
          stroke: "rgba(0,0,0,0.22)",
          "stroke-width": "1",
          opacity: visible ? "0.85" : "0",
          "pointer-events": "none",
        }),
      );
    }
    return g;
  }

  private drawSnap(snap: SnapTarget, _layout: Layout): SVGGElement {
    const g = el("g", { class: "snap-overlay" });
    if (snap.kind === "nest") {
      g.append(
        el("rect", {
          class: "nest-pocket",
          x: String(snap.highlight.x),
          y: String(snap.highlight.y),
          width: String(Math.max(snap.highlight.width, 36)),
          height: String(Math.max(snap.highlight.height, 16)),
          rx: "8",
          fill: "rgba(76,151,255,0.22)",
          stroke: "#B6D4FF",
          "stroke-width": "2.4",
          filter: "url(#insert-glow)",
        }),
      );
    }
    for (const seg of snap.segments) {
      g.append(
        el("path", {
          class: snap.kind === "nest" ? "nest-face" : "insert-edge",
          d: `M ${seg.a.x} ${seg.a.y} L ${seg.b.x} ${seg.b.y}`,
          fill: "none",
          stroke: snap.kind === "nest" ? "#C5DCFF" : "#8CB8FF",
          "stroke-width": snap.kind === "nest" ? "6" : "7",
          "stroke-linecap": "round",
          filter: "url(#insert-glow)",
        }),
      );
    }
    return g;
  }

  private drawSnapGhost(world: WorldNode, registry?: Registry): SVGGElement {
    const g = this.drawNode(world, { selected: false, showPorts: false, preview: true }, world.x, world.y, registry);
    g.classList.add("snap-ghost");
    g.setAttribute("pointer-events", "none");
    g.setAttribute("opacity", "0.52");
    const d = nodePath(world, world.joins);
    g.append(
      el("path", {
        class: "snap-ghost-outline",
        d,
        fill: "rgba(76,151,255,0.16)",
        stroke: "#C5DCFF",
        "stroke-width": "3.2",
        "stroke-linejoin": "round",
        "pointer-events": "none",
      }),
    );
    return g;
  }

  private ensureGradients(registry: Registry): void {
    for (const def of registry.all()) {
      const token = typeToken(def.type);
      if (this.knownGradients.has(token)) continue;
      const grad = el("linearGradient", {
        id: `grad-${token}`,
        x1: "0",
        y1: "0",
        x2: "0",
        y2: "1",
      });
      grad.append(
        el("stop", { offset: "0%", "stop-color": def.color.highlight ?? def.color.fill }),
        el("stop", { offset: "100%", "stop-color": def.color.fill }),
      );
      this.defs.append(grad);
      this.knownGradients.add(token);
    }
  }

  private drawGrid(): void {
    const pattern = el("pattern", {
      id: "canvas-grid",
      width: "24",
      height: "24",
      patternUnits: "userSpaceOnUse",
    });
    pattern.append(el("circle", { cx: "1.2", cy: "1.2", r: "1.05", fill: "rgba(255,255,255,0.045)" }));
    this.defs.append(pattern);
    this.grid.append(
      el("rect", {
        x: "-400",
        y: "-400",
        width: "2400",
        height: "1800",
        fill: "url(#canvas-grid)",
      }),
    );
  }

  private drawControl(
    world: WorldNode,
    placed: PlacedControl,
    preview: boolean,
    locked = false,
    selected = false,
  ): SVGElement {
    const { control, x, y, width, height, delim } = placed;
    if (preview) return this.drawControlPreview(world, placed);

    const fo = el("foreignObject", {
      x: String(x),
      y: String(y),
      width: String(width),
      height: String(height),
      class: "node-control-host",
      style: "overflow: hidden;",
    });
    const wrap = document.createElement("div");
    wrap.setAttribute("xmlns", "http://www.w3.org/1999/xhtml");
    wrap.style.width = "100%";
    wrap.style.height = "100%";
    wrap.style.pointerEvents = "auto";
    const field = control.field ?? control.id;
    const value = world.fields?.[field] ?? "";
    const stop = (event: Event) => event.stopPropagation();
    if (control.kind === "dropdown") {
      const select = document.createElement("select");
      select.className = "node-control";
      select.dataset.controlField = field;
      select.dataset.nodeId = world.id;
      for (const option of control.options ?? []) {
        const opt = document.createElement("option");
        opt.value = option;
        opt.textContent = option;
        if (option === value || (!value && option === (control.options ?? [])[0])) opt.selected = true;
        select.append(opt);
      }
      select.addEventListener("pointerdown", (event) => {
        event.stopPropagation();
        this.onControlGrip?.(world.id, event as PointerEvent);
      });
      select.addEventListener("change", () => {
        this.onControl?.({ kind: "change", nodeId: world.id, field, value: select.value });
      });
      if (locked) select.disabled = true;
      wrap.append(select);
    } else if (control.kind === "button") {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "node-plus";
      button.textContent = control.label ?? "+";
      button.dataset.controlAction = control.action ?? "";
      button.dataset.controlPocket = control.pocket ?? "";
      button.dataset.nodeId = world.id;
      button.addEventListener("pointerdown", stop);
      const fire = (event: Event) => {
        event.stopPropagation();
        event.preventDefault();
        if (button.dataset.fired === "1") return;
        button.dataset.fired = "1";
        this.onControl?.({
          kind: "click",
          nodeId: world.id,
          action: control.action,
          pocket: control.pocket,
        });
      };
      button.addEventListener("pointerup", fire);
      button.addEventListener("click", fire);
      if (locked) button.disabled = true;
      wrap.append(button);
    } else {
      const row = document.createElement("div");
      row.className = "node-hat-field";
      let inputValue = value;
      if (delim) {
        if (placed.showChip) {
          const chip = document.createElement("span");
          chip.className = "delim-chip";
          chip.textContent = delim.mark;
          row.append(chip);
        }
        inputValue = delim.rest;
      }
      const input = document.createElement("input");
      input.type = "text";
      input.className = "node-control";
      input.value = inputValue;
      input.placeholder = control.label ?? "";
      input.dataset.controlField = field;
      input.dataset.nodeId = world.id;
      input.addEventListener("pointerdown", (event) => {
        event.stopPropagation();
        this.onControlGrip?.(world.id, event as PointerEvent);
      });
      const emit = (kind: "input" | "change") => {
        const next = delim ? joinLeadingDelimiter({ ...delim, rest: input.value }) : input.value;
        this.onControl?.({
          kind,
          nodeId: world.id,
          field,
          value: next,
          caret: input.selectionStart,
        });
      };
      input.addEventListener("input", () => emit("input"));
      input.addEventListener("change", () => emit("change"));
      if (locked) input.readOnly = true;
      row.append(input);
      wrap.append(row);
    }
    fo.append(wrap);
    return fo;
  }

  private drawControlPreview(world: WorldNode, placed: PlacedControl): SVGElement {
    const g = el("g", {
      class: "node-control-preview",
      "pointer-events": "none",
    });
    const { control, x, y, width, height, delim, showChip } = placed;
    const field = control.field ?? control.id;
    const value = world.fields?.[field] ?? "";
    if (control.kind === "button") return g;
    const text = delim ? delim.rest : control.kind === "dropdown" ? value || (control.options ?? [])[0] || "" : value;
    if (delim && showChip) {
      g.append(
        el("rect", {
          x: String(x),
          y: String(y + 2),
          width: String(CHIP_W),
          height: String(height - 4),
          rx: "6",
          fill: "rgba(0,0,0,0.28)",
        }),
        el("text", {
          x: String(x + CHIP_W / 2),
          y: String(y + height / 2 + 5),
          fill: "#fff",
          "font-size": "15",
          "font-weight": "700",
          "text-anchor": "middle",
        }, delim.mark),
      );
    }
    g.append(
      el(
        "text",
        {
        x: String(x + (delim && showChip ? CHIP_W + 8 : 8)),
          y: String(y + height / 2 + 5),
          fill: "rgba(255,255,255,0.95)",
          "font-size": control.kind === "dropdown" ? "13" : "15",
          "font-weight": control.kind === "dropdown" ? "750" : "600",
        },
        text,
      ),
    );
    void width;
    return g;
  }
}

export function prototypeWorld(registry: Registry, type: string, x: number, y: number): WorldNode {
  return prototypeBadge(registry, type, x, y).core;
}

export function prototypeBadge(
  registry: Registry,
  type: string,
  x: number,
  y: number,
): { core: WorldNode; state: DocumentState; layout: Layout } {
  const state = DocumentState.empty();
  const node = state.addNode(type, { x, y }, registry);
  const layout = layoutDocument(state, registry);
  const core = layout.nodes.get(node.id);
  if (!core) throw new Error("Failed to measure prototype");
  return { core, state, layout };
}

function placeHatControls(world: WorldNode, def: GlyphDefinition): PlacedControl[] {
  const controls = (def.controls ?? []).filter((control) => (control.width ?? 1) > 0 && (control.height ?? 1) > 0);
  if (controls.length === 0) return [];
  const y = Math.max(8, (world.hatHeight - CONTROL_H) / 2);
  const badgeReserve = def.badge ? Math.max(58, 12 + def.badge.length * 8.2) : 0;
  const buttons = controls.filter((control) => control.kind === "button");
  const fields = controls.filter((control) => control.kind !== "button");
  const buttonWidth = buttons.reduce((sum, button) => sum + (button.width ?? 80) + HAT_GAP, 0);
  const placed: PlacedControl[] = [];
  let x = HAT_PAD_X + badgeReserve;
  const textRight = world.width - HAT_PAD_X - buttonWidth;

  fields.forEach((control, index) => {
    if (control.kind === "dropdown") {
      placed.push({ control, x, y, width: KEYWORD_W, height: CONTROL_H });
      x += KEYWORD_W + HAT_GAP;
      return;
    }
    const isLast = index === fields.length - 1;
    const width = isLast ? Math.max(64, textRight - x) : (control.width ?? 120);
    const field = control.field ?? control.id;
    const value = world.fields?.[field] ?? "";
    const delim = control.kind === "text" && field === "text" ? splitLeadingDelimiter(value) : null;
    const hasKeyword = fields.some((item) => item.kind === "dropdown" && (item.field === "keyword" || item.id === "keyword"));
    placed.push({
      control,
      x,
      y,
      width,
      height: CONTROL_H,
      delim,
      showChip: Boolean(delim) && !hasKeyword,
    });
    x += width + HAT_GAP;
  });

  let bx = world.width - HAT_PAD_X;
  for (const button of [...buttons].reverse()) {
    const width = button.width ?? 80;
    bx -= width;
    placed.push({ control: button, x: bx, y, width, height: CONTROL_H });
    bx -= HAT_GAP;
  }
  return placed;
}

function applyOffsets(layout: Layout, offsets: Map<string, { x: number; y: number }>): Layout {
  if (offsets.size === 0) return layout;
  const nodes = new Map<string, WorldNode>();
  for (const [id, node] of layout.nodes) {
    const off = offsets.get(id);
    if (!off) {
      nodes.set(id, node);
      continue;
    }
    nodes.set(id, {
      ...node,
      x: node.x + off.x,
      y: node.y + off.y,
      ports: node.ports.map((p) => ({ ...p, x: p.x + off.x, y: p.y + off.y })),
      pockets: node.pockets.map((p) => ({
        ...p,
        x: p.x + off.x,
        y: p.y + off.y,
        slots: p.slots.map((s) => ({ ...s, x: s.x + off.x, y: s.y + off.y })),
      })),
    });
  }
  return { nodes, order: layout.order };
}

function nodeLabels(world: WorldNode, registry?: Registry): Array<{ text: string; x: number; y: number }> {
  const labels: Array<{ text: string; x: number; y: number }> = [];
  const def = registry?.has(world.type) ? registry.get(world.type) : undefined;
  const hasInner =
    Boolean(world.fields?.keyword) || Boolean(world.fields?.text) || Boolean(world.fields?.condition);
  const baseline = Math.round(world.hatHeight / 2 + 5);
  if (def?.badge) labels.push({ text: def.badge, x: 12, y: baseline });
  else if (!hasInner) labels.push({ text: world.label, x: 14, y: baseline });
  if (world.wrapped && world.pockets.length > 1) {
    let y = world.hatHeight;
    world.pockets.forEach((pocket, i) => {
      if (i === 0) {
        y += pocket.height;
        return;
      }
      const midY = y + world.midHeight / 2 + 5;
      const text = world.pocketLabels[i] ?? pocket.id;
      labels.push({ text, x: 22, y: midY });
      y += world.midHeight + pocket.height;
    });
  }
  return labels;
}

function typeToken(typeId: string): string {
  return typeId.replace(/[^A-Za-z0-9_-]/g, "_");
}

function pocketDefFor(
  def: { pockets: Array<{ id: string; repeatable?: boolean; controlField?: boolean; label?: string }> },
  pocketId: string,
) {
  const exact = def.pockets.find((p) => p.id === pocketId);
  if (exact) return exact;
  return def.pockets.find((p) => pocketId === p.id || pocketId.startsWith(`${p.id}-`));
}

function filterShadow(): SVGFilterElement {
  const filter = el("filter", {
    id: "node-shadow",
    x: "-18%",
    y: "-12%",
    width: "136%",
    height: "150%",
  });
  filter.append(
    el("feDropShadow", {
      dx: "0",
      dy: "3",
      stdDeviation: "2.4",
      "flood-color": "#000",
      "flood-opacity": "0.22",
    }),
  );
  return filter;
}

function filterGlow(id: string, color: string, deviation = 2.8, opacity = 0.85): SVGFilterElement {
  const filter = el("filter", { id, x: "-40%", y: "-80%", width: "180%", height: "260%" });
  filter.append(
    el("feDropShadow", {
      dx: "0",
      dy: "0",
      stdDeviation: String(deviation),
      "flood-color": color,
      "flood-opacity": String(opacity),
    }),
  );
  return filter;
}

function transformPoint(
  svg: SVGSVGElement,
  ctm: DOMMatrix | null,
  clientX: number,
  clientY: number,
): { x: number; y: number } {
  if (!ctm || typeof svg.createSVGPoint !== "function") return { x: clientX, y: clientY };
  const pt = svg.createSVGPoint();
  pt.x = clientX;
  pt.y = clientY;
  const world = pt.matrixTransform(ctm.inverse());
  return { x: world.x, y: world.y };
}

function safeScreenCtm(node: { getScreenCTM?: () => DOMMatrix | null }): DOMMatrix | null {
  if (typeof node.getScreenCTM !== "function") return null;
  try {
    return node.getScreenCTM() ?? null;
  } catch {
    return null;
  }
}

function cssAttr(value: string): string {
  return value.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}

function el<K extends keyof SVGElementTagNameMap>(
  name: K,
  attrs: Record<string, string> = {},
  text?: string,
): SVGElementTagNameMap[K] {
  const node = document.createElementNS(SVG_NS, name);
  for (const [key, value] of Object.entries(attrs)) {
    node.setAttribute(key, value);
  }
  if (text !== undefined) node.textContent = text;
  return node;
}
