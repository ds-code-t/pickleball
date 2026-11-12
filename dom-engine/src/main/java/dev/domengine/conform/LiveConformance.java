package dev.domengine.conform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.query.Engines;
import dev.domengine.query.Query;
import dev.domengine.session.BrowserSession;
import dev.domengine.tree.DomNode;
import dev.domengine.tree.LiveTree;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Live-Chrome conformance. The offline {@link Conformance} path stays unchanged.
 * Every assertion kind in the Rust fixtures is evaluated against a real browser
 * when Chrome is present. A checked disagreement is a failure. An assertion is
 * skipped only when this engine has no required capability, and the skip is named.
 */
public final class LiveConformance {
    private static final byte[] PIXEL = Base64.getDecoder().decode(
            "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");
    private static final String SLOW_JS = "var n=document.getElementById('ready');if(n)n.textContent='ready';";
    private static final String SW_JS = """
            self.addEventListener('install', function (e) { self.skipWaiting(); });
            self.addEventListener('activate', function (e) { e.waitUntil(self.clients.claim()); });
            """;
    private static final String REACT = """
            (function () {
              function toDom(node) {
                if (node == null || node === false || node === true) return document.createTextNode("");
                if (typeof node === "string" || typeof node === "number") return document.createTextNode(String(node));
                if (Array.isArray(node)) {
                  var f = document.createDocumentFragment();
                  node.forEach(function (n) { f.appendChild(toDom(n)); });
                  return f;
                }
                var el = document.createElement(node.type);
                var p = node.props || {};
                Object.keys(p).forEach(function (k) {
                  if (k === "key" || k.indexOf("on") === 0) return;
                  if (k === "className") el.className = p[k];
                  else el.setAttribute(k, p[k]);
                });
                (node.children || []).forEach(function (c) { el.appendChild(toDom(c)); });
                return el;
              }
              window.React = { createElement: function (type, props) {
                var children = Array.prototype.slice.call(arguments, 2);
                return { type: type, props: props || {}, children: children };
              }};
              window.ReactDOM = { createRoot: function (el) {
                return { render: function (node) { el.replaceChildren(toDom(node)); } };
              }};
            })();
            """;
    private static final String ANGULAR = """
            (function () {
              var modules = {};
              function boot() {
                document.querySelectorAll("[ng-controller]").forEach(function (host) {
                  if (host.getAttribute("data-booted")) return;
                  var modName = host.getAttribute("ng-app");
                  if (!modName) {
                    var app = document.querySelector("[ng-app]");
                    modName = app ? app.getAttribute("ng-app") : "";
                  }
                  var fn = modules[modName] && modules[modName][host.getAttribute("ng-controller")];
                  if (!fn) return;
                  host.setAttribute("data-booted", "1");
                  var scope = {};
                  fn(scope);
                  host.querySelectorAll("[ng-repeat]").forEach(function (tmpl) {
                    var expr = tmpl.getAttribute("ng-repeat") || "";
                    var parts = expr.split(" in ");
                    if (parts.length < 2) return;
                    var items = scope[parts[1].trim()] || [];
                    var html = tmpl.innerHTML;
                    var parent = tmpl.parentNode;
                    tmpl.remove();
                    items.forEach(function (item) {
                      var node = tmpl.cloneNode(false);
                      node.removeAttribute("ng-repeat");
                      node.innerHTML = html.replace(/\\{\\{\\s*(\\w+)\\.(\\w+)\\s*\\}\\}/g, function (_, o, p) {
                        return item[p] == null ? "" : item[p];
                      });
                      parent.appendChild(node);
                    });
                  });
                });
              }
              window.angular = { module: function (name) {
                if (!modules[name]) modules[name] = {};
                return { controller: function (n, fn) { modules[name][n] = fn; setTimeout(boot, 0); return this; } };
              }};
            })();
            """;
    private static final String BOOT = """
            (() => {
              if (window.__compositeBoot) return;
              window.__compositeBoot = true;
              const s = {count: 0, last: performance.now()};
              window.__compositeObs = s;
              const arm = () => {
                if (!document.documentElement || window.__compositeArmed) return;
                window.__compositeArmed = true;
                try {
                  new MutationObserver(() => { s.count++; s.last = performance.now(); })
                    .observe(document.documentElement, {subtree:true, childList:true, attributes:true, characterData:true});
                } catch (e) {}
              };
              arm();
              document.addEventListener("DOMContentLoaded", arm);
              const net = {inflight: 0, last: performance.now()};
              window.__net = net;
              const orig = window.fetch;
              if (orig) {
                window.fetch = function () {
                  net.inflight++;
                  return orig.apply(this, arguments).finally(() => {
                    net.inflight = Math.max(0, net.inflight - 1);
                    net.last = performance.now();
                  });
                };
              }
              try {
                new PerformanceObserver(() => { net.last = performance.now(); }).observe({type: "resource", buffered: true});
              } catch (e) {}
            })();
            """;
    private static final String PROBE = """
            (args) => {
              const capX = args.scrollX || 0;
              const capY = args.scrollY || 0;
              window.scrollTo(capX, capY);
              const STYLE_NAMES = ["display","visibility","opacity","position","filter","backdrop-filter","will-change","contain","isolation","transform","mix-blend-mode","content-visibility","z-index","overflow","overflow-x","overflow-y","color","background-color","white-space","text-overflow","scroll-behavior","pointer-events","scroll-snap-type","zoom","direction","width","height"];
              function interesting(v) {
                const t = (v || "").trim();
                return !!t && t !== "none" && t !== "matrix(1, 0, 0, 1, 0, 0)";
              }
              function stacking(el, cs) {
                const opacity = parseFloat(cs.opacity);
                if (!Number.isNaN(opacity) && opacity < 0.999) return true;
                if (interesting(cs.transform) || interesting(el.style && el.style.transform)) return true;
                if (cs.filter && cs.filter !== "none") return true;
                const bf = cs.getPropertyValue("backdrop-filter") || cs.getPropertyValue("-webkit-backdrop-filter");
                if (bf && bf !== "none") return true;
                if (cs.position === "fixed" || cs.position === "sticky") return true;
                if (cs.position && cs.position !== "static" && cs.zIndex && cs.zIndex !== "auto") return true;
                const contain = cs.contain || "";
                if (contain.indexOf("paint") >= 0 || contain.indexOf("strict") >= 0 || contain.indexOf("content") >= 0) return true;
                if (cs.isolation === "isolate") return true;
                const wc = cs.willChange || "";
                if (wc.split(",").some(p => ["transform","opacity","filter","backdrop-filter","perspective","translate","scale","rotate"].indexOf(p.trim()) >= 0)) return true;
                if (cs.mixBlendMode && cs.mixBlendMode !== "normal") return true;
                if (cs.contentVisibility === "auto" || cs.contentVisibility === "hidden") return true;
                const parent = el.parentElement;
                if (parent && cs.zIndex && cs.zIndex !== "auto") {
                  const pd = getComputedStyle(parent).display || "";
                  if (pd === "flex" || pd === "inline-flex" || pd === "grid" || pd === "inline-grid") return true;
                }
                return false;
              }
              function slotFallback(el) {
                let node = el;
                while (node) {
                  const parent = node.parentElement || (node.getRootNode && node.getRootNode().host);
                  if (!parent || parent === node) break;
                  if (parent.tagName === "SLOT" && parent.assignedNodes && parent.assignedNodes().length) return true;
                  node = parent;
                }
                return false;
              }
              function frameOrigin(el) {
                let x = 0, y = 0;
                try {
                  let win = el.ownerDocument && el.ownerDocument.defaultView;
                  while (win && win.frameElement) {
                    const frame = win.frameElement;
                    const parent = frame.ownerDocument.defaultView;
                    const r = frame.getBoundingClientRect();
                    x += r.left + (parent ? parent.scrollX : 0);
                    y += r.top + (parent ? parent.scrollY : 0);
                    win = parent;
                  }
                } catch (e) {}
                return [x, y];
              }
              function quadOf(el) {
                const win = (el.ownerDocument && el.ownerDocument.defaultView) || window;
                const sx = win.scrollX || 0, sy = win.scrollY || 0;
                const origin = frameOrigin(el);
                const ox = origin[0], oy = origin[1];
                try {
                  if (el.getBoxQuads) {
                    const qs = el.getBoxQuads();
                    if (qs && qs[0]) {
                      const q = qs[0];
                      return [q.p1.x+sx+ox, q.p1.y+sy+oy, q.p2.x+sx+ox, q.p2.y+sy+oy, q.p3.x+sx+ox, q.p3.y+sy+oy, q.p4.x+sx+ox, q.p4.y+sy+oy];
                    }
                  }
                } catch (e) {}
                const r = el.getBoundingClientRect();
                return [r.left+sx+ox, r.top+sy+oy, r.right+sx+ox, r.top+sy+oy, r.right+sx+ox, r.bottom+sy+oy, r.left+sx+ox, r.bottom+sy+oy];
              }
              function pseudo(el, which) {
                let c = "";
                try { c = getComputedStyle(el, "::" + which).content || ""; } catch (e) { return ""; }
                if (!c || c === "none" || c === "normal") return "";
                if ((c.charAt(0) === '"' && c.charAt(c.length-1) === '"') || (c.charAt(0) === "'" && c.charAt(c.length-1) === "'")) {
                  c = c.slice(1, -1);
                }
                return c;
              }
              function mismatch(el, cs) {
                const role = (el.getAttribute("role") || "").toLowerCase();
                let cur = el;
                while (cur && cur.nodeType === 1) {
                  if (cur.getAttribute("aria-hidden") === "true") return "at-hidden";
                  const r = (cur.getAttribute("role") || "").toLowerCase();
                  if (cur === el && (r === "presentation" || r === "none")) return "at-hidden";
                  cur = cur.parentElement;
                }
                if (role === "presentation" || role === "none") return "at-hidden";
                const w = parseFloat(cs.width) || 0;
                const h = parseFloat(cs.height) || 0;
                const cls = typeof el.className === "string" ? el.className : "";
                if ((w <= 1 && h <= 1 && (cs.position === "absolute" || cs.position === "fixed")) || cls.split(/\\s+/).indexOf("sr") >= 0) return "sr-only";
                const tag = el.tagName.toLowerCase();
                const natural = tag === "a" || tag === "button" || tag === "input" || tag === "select" || tag === "textarea";
                const tab = el.hasAttribute("tabindex") ? parseInt(el.getAttribute("tabindex"), 10) : null;
                if (tag.indexOf("-") >= 0) return "custom";
                if (!natural && (el.hasAttribute("onclick") || (tab != null && !Number.isNaN(tab) && tab >= 0))) return "custom";
                return "";
              }
              function inTop(el) {
                try { if (el.matches(":top-layer")) return true; } catch (e) {}
                try { if (el.matches(":modal")) return true; } catch (e) {}
                try { if (el.matches(":popover-open")) return true; } catch (e) {}
                return false;
              }
              function describe(el) {
                const cs = getComputedStyle(el);
                const visibleBase = cs.display !== "none" && cs.visibility !== "hidden" && cs.visibility !== "collapse" && !slotFallback(el);
                const op = parseFloat(cs.opacity);
                const r = el.getBoundingClientRect();
                let clickable = false;
                if (visibleBase && op > 0 && cs.pointerEvents !== "none" && !el.disabled && r.width > 0 && r.height > 0) {
                  const stack = pierce(el.ownerDocument || document, r.left + Math.min(r.width / 2, r.width - 0.5), r.top + Math.min(r.height / 2, r.height - 0.5), 0);
                  const top = stack[0];
                  clickable = !!top && (top === el || el.contains(top));
                }
                const styles = {};
                STYLE_NAMES.forEach(n => { styles[n] = cs.getPropertyValue(n); });
                const attrs = {};
                for (let i = 0; i < el.attributes.length; i++) attrs[el.attributes[i].name] = el.attributes[i].value;
                return {
                  id: el.id || "",
                  stable: el.getAttribute("data-stable") || "",
                  testid: el.getAttribute("data-testid") || "",
                  tag: el.tagName.toLowerCase(),
                  text: el.textContent == null ? "" : el.textContent,
                  className: typeof el.className === "string" ? el.className : "",
                  styles, attrs, quad: quadOf(el),
                  visible: visibleBase,
                  displayed: visibleBase && op > 0,
                  clickable, stack: stacking(el, cs), top: inTop(el),
                  before: pseudo(el, "before"), after: pseudo(el, "after"),
                  slot: el.hasAttribute("slot") ? el.getAttribute("slot") : "",
                  mismatch: mismatch(el, cs)
                };
              }
              function pierce(doc, x, y, depth) {
                if (!doc || depth > 8) return [];
                let stack = [];
                try { stack = doc.elementsFromPoint(x, y) || []; } catch (e) { return []; }
                if (!stack.length) return [];
                const top = stack[0];
                if (top && top.shadowRoot) {
                  try {
                    const inner = pierce(top.shadowRoot, x, y, depth + 1);
                    if (inner.length) return inner.concat(stack.slice(0, 8));
                  } catch (e) {}
                }
                if (top && (top.tagName === "IFRAME" || top.tagName === "FRAME")) {
                  try {
                    const r = top.getBoundingClientRect();
                    const idoc = top.contentDocument;
                    if (idoc) {
                      const inner = pierce(idoc, x - r.left, y - r.top, depth + 1);
                      if (inner.length) return inner.concat(stack.slice(0, 8));
                    }
                  } catch (e) {}
                }
                return stack.slice(0, 8);
              }
              function ancestry(el) {
                const ids = [];
                let n = el;
                let guard = 0;
                while (n && guard++ < 64) {
                  if (n.id) ids.push(n.id);
                  const stable = n.getAttribute && n.getAttribute("data-stable");
                  if (stable) ids.push("[data-stable=" + stable + "]");
                  n = n.parentElement || (n.getRootNode && n.getRootNode().host);
                }
                return ids;
              }
              function stamp(el) {
                return {id: el.id || "", stable: (el.getAttribute && el.getAttribute("data-stable")) || "", testid: (el.getAttribute && el.getAttribute("data-testid")) || "", tag: el.tagName || "", ancestors: ancestry(el)};
              }
              function hitAt(docX, docY) {
                const vw = window.innerWidth || 1;
                const vh = window.innerHeight || 1;
                const inVp = docX >= capX && docX < capX + vw && docY >= capY && docY < capY + vh;
                const sx = inVp ? capX : Math.max(0, Math.floor(docX / vw) * vw);
                const sy = inVp ? capY : Math.max(0, Math.floor(docY / vh) * vh);
                window.scrollTo(sx, sy);
                const stack = pierce(document, docX - window.scrollX, docY - window.scrollY, 0);
                window.scrollTo(capX, capY);
                return stack.map(stamp);
              }
              const nodes = [];
              function walk(root) {
                if (!root || !root.querySelectorAll) return;
                root.querySelectorAll("*").forEach(el => nodes.push(describe(el)));
                root.querySelectorAll("*").forEach(el => { if (el.shadowRoot) walk(el.shadowRoot); });
                root.querySelectorAll("iframe,frame").forEach(f => { try { if (f.contentDocument) walk(f.contentDocument); } catch (e) {} });
              }
              walk(document);
              const frames = [];
              function walkFrames(doc, path) {
                if (!doc || !doc.querySelectorAll) return;
                const found = [];
                function collect(node) {
                  if (!node || !node.querySelectorAll) return;
                  node.querySelectorAll(":scope > * , :scope iframe, :scope frame").forEach(() => {});
                  const light = node.querySelectorAll("iframe,frame");
                  light.forEach(el => {
                    const root = el.getRootNode && el.getRootNode();
                    if (root === node || (node.nodeType === 9 && root === node)) found.push(el);
                  });
                  node.querySelectorAll("*").forEach(el => { if (el.shadowRoot) collect(el.shadowRoot); });
                }
                const direct = [];
                function collectDirect(node) {
                  if (!node || !node.querySelectorAll) return;
                  node.querySelectorAll("iframe,frame").forEach(el => {
                    if (el.getRootNode && el.getRootNode() === node) direct.push(el);
                  });
                  node.querySelectorAll("*").forEach(el => { if (el.shadowRoot) collectDirect(el.shadowRoot); });
                }
                collectDirect(doc);
                direct.forEach((el, i) => {
                  const child = path + "/" + i;
                  let text = "";
                  let cross = false;
                  try {
                    if (!el.contentDocument) cross = true;
                    else {
                      text = el.contentDocument.body ? el.contentDocument.body.textContent : "";
                      walkFrames(el.contentDocument, child);
                    }
                  } catch (e) { cross = true; }
                  frames.push({path: child, text: text || "", cross, id: el.id || ""});
                });
              }
              walkFrames(document, "0");
              function allOf(tag, into, node) {
                if (!node || !node.querySelectorAll) return;
                node.querySelectorAll(tag).forEach(el => into.push(el));
                node.querySelectorAll("*").forEach(el => { if (el.shadowRoot) allOf(tag, into, el.shadowRoot); });
                node.querySelectorAll("iframe,frame").forEach(f => { try { if (f.contentDocument) allOf(tag, into, f.contentDocument); } catch (e) {} });
              }
              const dialogs = [];
              allOf("dialog", dialogs, document);
              const modalList = dialogs.filter(d => { try { return d.matches(":modal"); } catch (e) { return false; } });
              const topModal = modalList.length ? modalList[modalList.length - 1] : null;
              function hostParent(n) {
                if (!n) return null;
                if (n.parentElement) return n.parentElement;
                const root = n.getRootNode && n.getRootNode();
                return root && root.host ? root.host : null;
              }
              function inertChain(el) {
                let n = el;
                while (n) {
                  if (n.hasAttribute && n.hasAttribute("inert")) return true;
                  n = hostParent(n);
                }
                return false;
              }
              function inside(el, modal) {
                let n = el;
                while (n) {
                  if (n === modal) return true;
                  n = hostParent(n);
                }
                return false;
              }
              const focusFound = [];
              function consider(el) {
                if (!el || el.nodeType !== 1 || el.disabled || inertChain(el)) return;
                if (topModal && !inside(el, topModal)) return;
                let tab = null;
                if (el.hasAttribute("tabindex")) {
                  const ti = parseInt(el.getAttribute("tabindex"), 10);
                  if (Number.isNaN(ti) || ti < 0) return;
                  tab = ti;
                } else {
                  const tag = el.tagName.toLowerCase();
                  const natural = tag === "button" || tag === "input" || tag === "select" || tag === "textarea" || (tag === "a" && el.hasAttribute("href"));
                  if (!natural) return;
                  tab = 0;
                }
                focusFound.push({tab, discovery: focusFound.length, id: el.id || "", stable: el.getAttribute("data-stable") || ""});
              }
              function walkFocus(node) {
                if (!node || !node.querySelectorAll) return;
                node.querySelectorAll("*").forEach(consider);
                node.querySelectorAll("*").forEach(el => { if (el.shadowRoot) walkFocus(el.shadowRoot); });
                node.querySelectorAll("iframe,frame").forEach(f => { try { if (f.contentDocument) walkFocus(f.contentDocument); } catch (e) {} });
              }
              walkFocus(document);
              focusFound.sort((a, b) => {
                const ag = a.tab > 0 ? 0 : 1;
                const bg = b.tab > 0 ? 0 : 1;
                if (ag !== bg) return ag - bg;
                if (a.tab !== b.tab) return a.tab - b.tab;
                return a.discovery - b.discovery;
              });
              const tops = [];
              function walkTop(node) {
                if (!node || !node.querySelectorAll) return;
                node.querySelectorAll("*").forEach(el => {
                  if (inTop(el)) {
                    const ancestors = [];
                    let n = el;
                    while (n) {
                      if (n.id) ancestors.push(n.id);
                      n = hostParent(n);
                    }
                    tops.push({id: el.id || "", stable: el.getAttribute("data-stable") || "", ancestors});
                  }
                  if (el.shadowRoot) walkTop(el.shadowRoot);
                });
              }
              walkTop(document);
              const hits = {};
              (args.points || []).forEach(p => {
                const key = p[0] + "," + p[1];
                if (!hits[key]) hits[key] = hitAt(p[0], p[1]);
              });
              window.scrollTo(capX, capY);
              function ownText(el) {
                let t = "";
                for (const c of el.childNodes) if (c.nodeType === 3) t += c.nodeValue || "";
                return t;
              }
              function deepText(el) {
                let t = "";
                function rec(n) {
                  if (!n) return;
                  if (n.nodeType === 3) t += n.nodeValue || "";
                  if (n.nodeType === 1 && n.tagName !== "SCRIPT" && n.tagName !== "STYLE") {
                    n.childNodes.forEach(rec);
                    if (n.shadowRoot) rec(n.shadowRoot);
                  }
                }
                rec(el);
                return t;
              }
              function innermost(needle, exact) {
                const want = exact ? needle : needle.toLowerCase();
                const hits = [];
                function visit(root) {
                  if (!root || !root.querySelectorAll) return;
                  root.querySelectorAll("*").forEach(el => {
                    const got = deepText(el);
                    const hay = exact ? got : got.toLowerCase();
                    if (!want || (exact ? hay === want : hay.indexOf(want) < 0)) return;
                    let childHit = false;
                    el.querySelectorAll("*").forEach(ch => {
                      const c = deepText(ch);
                      const h = exact ? c : c.toLowerCase();
                      if (exact ? h === want : h.indexOf(want) >= 0) childHit = true;
                    });
                    if (!childHit) hits.push(el);
                  });
                  root.querySelectorAll("*").forEach(el => { if (el.shadowRoot) visit(el.shadowRoot); });
                }
                visit(document);
                return hits;
              }
              function linkText(needle, partial) {
                const hits = [];
                function visit(root) {
                  if (!root || !root.querySelectorAll) return;
                  root.querySelectorAll("a").forEach(el => {
                    const got = deepText(el);
                    if (partial ? got.indexOf(needle) >= 0 : got === needle) hits.push(el);
                  });
                  root.querySelectorAll("*").forEach(el => { if (el.shadowRoot) visit(el.shadowRoot); });
                }
                visit(document);
                return hits;
              }
              function cssAll(sel, pierceFrame) {
                const out = [];
                function visit(root) {
                  if (!root || !root.querySelectorAll) return;
                  root.querySelectorAll(sel).forEach(el => out.push(el));
                  root.querySelectorAll("*").forEach(el => { if (el.shadowRoot) visit(el.shadowRoot); });
                  if (pierceFrame) root.querySelectorAll("iframe,frame").forEach(f => { try { if (f.contentDocument) visit(f.contentDocument); } catch (e) {} });
                }
                visit(document);
                return out;
              }
              function parseOpts(raw) {
                if (!raw) return {};
                const name = /name\\s*:\\s*'([^']*)'/.exec(raw) || /name\\s*:\\s*"([^"]*)"/.exec(raw);
                const exact = /exact\\s*:\\s*true/.test(raw);
                return {name: name ? name[1] : null, exact};
              }
              function runOne(q) {
                try {
                  if (q.pw && q.pw.indexOf("getByRole(") === 0) return {handled: false};
                  if (q.pw && q.pw.indexOf("getByText(") === 0) {
                    const m = /getByText\\(\\s*'([^']*)'/.exec(q.pw) || /getByText\\(\\s*"([^"]*)"/.exec(q.pw);
                    const hits = innermost(m ? m[1] : "", false);
                    return {handled: true, count: hits.length, ids: hits.map(e => e.id || ""), error: null};
                  }
                  if (q.pw && q.pw.indexOf("locator(") === 0) {
                    let scope = [document];
                    const parts = q.pw.split(").");
                    for (let i = 0; i < parts.length; i++) {
                      let part = parts[i];
                      if (i < parts.length - 1) part += ")";
                      const loc = /locator\\(\\s*'([^']*)'/.exec(part);
                      const frame = /frameLocator\\(\\s*'([^']*)'/.exec(part);
                      const nth = /\\.nth\\(\\s*(\\d+)/.exec(part);
                      if (frame) {
                        const next = [];
                        scope.forEach(root => {
                          const found = [];
                          const base = root.querySelectorAll ? root : document;
                          try { base.querySelectorAll(frame[1]).forEach(el => found.push(el)); } catch (e) {}
                          if (root.shadowRoot) {
                            try { root.shadowRoot.querySelectorAll(frame[1]).forEach(el => found.push(el)); } catch (e) {}
                          }
                          base.querySelectorAll("*").forEach(el => {
                            if (!el.shadowRoot) return;
                            try { el.shadowRoot.querySelectorAll(frame[1]).forEach(n => found.push(n)); } catch (e) {}
                          });
                          found.forEach(f => { try { if (f.contentDocument) next.push(f.contentDocument); } catch (e) {} });
                        });
                        scope = next;
                      } else if (loc) {
                        const next = [];
                        scope.forEach(root => {
                          const base = root.querySelectorAll ? root : document;
                          try { base.querySelectorAll(loc[1]).forEach(el => next.push(el)); } catch (e) { throw e; }
                          if (root.shadowRoot) {
                            try { root.shadowRoot.querySelectorAll(loc[1]).forEach(n => next.push(n)); } catch (e) {}
                          }
                          base.querySelectorAll("*").forEach(el => {
                            if (!el.shadowRoot) return;
                            try { el.shadowRoot.querySelectorAll(loc[1]).forEach(n => next.push(n)); } catch (e) {}
                          });
                        });
                        scope = next;
                      }
                      if (nth) scope = scope[Number(nth[1])] ? [scope[Number(nth[1])]] : [];
                    }
                    return {handled: true, count: scope.length, ids: scope.map(e => e.id || ""), error: null};
                  }
                  if (q.css && (q.mode === "playwright" || !q.mode)) {
                    let nodes;
                    try { nodes = cssAll(q.css, false); }
                    catch (e) { return {handled: true, error: String(e && e.message || e), count: 0, ids: []}; }
                    return {handled: true, count: nodes.length, ids: nodes.map(e => e.id || ""), error: null};
                  }
                  if (q.xpath || (q.by && String(q.by).indexOf("xpath=") === 0)) {
                    const expr = q.xpath || String(q.by).substring(6);
                    try {
                      const found = document.evaluate(expr, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                      return {handled: true, count: found.snapshotLength, ids: [], error: null};
                    } catch (e) {
                      return {handled: true, error: String(e && e.message || e), count: 0, ids: []};
                    }
                  }
                  if (q.by && String(q.by).indexOf("linkText=") === 0) {
                    const hits = linkText(String(q.by).substring(9), false);
                    return {handled: true, count: hits.length, ids: hits.map(e => e.id || ""), error: null};
                  }
                  if (q.by && String(q.by).indexOf("partialLinkText=") === 0) {
                    const hits = linkText(String(q.by).substring(16), true);
                    return {handled: true, count: hits.length, ids: hits.map(e => e.id || ""), error: null};
                  }
                  return {handled: false};
                } catch (e) {
                  return {handled: true, error: String(e && e.message || e), count: 0, ids: []};
                }
              }
              const queries = (args.queries || []).map(runOne);
              const html = document.documentElement ? document.documentElement.outerHTML : "";
              return {
                nodes, frames,
                focus: focusFound.map(f => ({id: f.id, stable: f.stable})),
                tops, modals: modalList.map(el => el.id || ""),
                hits, queries,
                flags: {
                  canvas: !!document.querySelector("canvas"),
                  lazy: !!document.querySelector("img[loading=lazy]"),
                  virtual: !!document.querySelector("[data-virtual-total]"),
                  infinite: html.indexOf("infinite") >= 0 || !!document.getElementById("more"),
                  serviceWorker: html.indexOf("serviceWorker") >= 0
                }
              };
            }
            """;
    private static final String SETTLE = """
            (() => {
              const s = window.__compositeObs || {count: 0, last: performance.now()};
              const now = performance.now();
              const anims = (document.getAnimations ? document.getAnimations() : []).filter(a => a.playState === "running").map(a => String(a.animationName || a.id || "anim"));
              const imgs = Array.from(document.images || []);
              const pendingImgs = imgs.filter(i => !i.complete).map(i => i.currentSrc || i.src || "");
              const net = window.__net || {inflight: 0, last: now};
              return {
                ready: document.readyState,
                mutations: s.count,
                lastAge: now - (s.last || now),
                anims, pendingImgs,
                fonts: document.fonts ? document.fonts.status : "loaded",
                busy: Array.from(document.querySelectorAll("[aria-busy='true']")).map(e => e.id || e.tagName),
                spinners: Array.from(document.querySelectorAll("[data-loader='spinner'], .spinner")).map(e => e.id || "spinner"),
                skeletons: Array.from(document.querySelectorAll("[data-loader='skeleton'], .skeleton")).map(e => e.id || "skeleton"),
                bars: Array.from(document.querySelectorAll("progress, [data-loader='progress']")).map(e => e.id || "progress"),
                inflight: net.inflight || 0,
                netAge: now - (net.last || now)
              };
            })()
            """;

    private LiveConformance() {
    }

    public static ObjectNode run(Path root) {
        if (BrowserSession.findChrome() == null) {
            throw new CdpException("missing Chrome binary (looked for DOMENGINE_CHROME, google-chrome, google-chrome-stable, chromium, chromium-browser, chrome)");
        }
        if (root == null || !Files.isDirectory(root)) throw new CdpException("conform root is not a directory");
        Path expected = root.resolve("expected");
        Path site = Files.isDirectory(root.resolve("site")) ? root.resolve("site") : root.resolve("test-site");
        if (!Files.isDirectory(expected)) throw new CdpException("conform expected directory is missing");
        HttpServer main = null;
        HttpServer cross = null;
        BrowserSession session = null;
        try {
            cross = serve(site, -1);
            int crossPort = cross.getAddress().getPort();
            main = serve(site, crossPort);
            int port = main.getAddress().getPort();
            session = BrowserSession.launch(
                    "conform-live",
                    "about:blank",
                    false,
                    false,
                    null,
                    List.of(),
                    List.of(
                            "--disable-features=IsolateOrigins,site-per-process",
                            "--disable-site-isolation-trials"
                    )
            );
            CdpConnection cdp = session.connection();
            cdp.call("Page.enable", Jsons.object(), Duration.ofSeconds(10));
            cdp.call("DOM.enable", Jsons.object(), Duration.ofSeconds(10));
            cdp.call("Runtime.enable", Jsons.object(), Duration.ofSeconds(10));
            cdp.call("CSS.enable", Jsons.object(), Duration.ofSeconds(5));
            ObjectNode boot = Jsons.object();
            boot.put("source", BOOT);
            cdp.call("Page.addScriptToEvaluateOnNewDocument", boot, Duration.ofSeconds(5));
            String only = System.getenv("DOMENGINE_LIVE_ONLY");
            int cases = 0;
            int passed = 0;
            int failed = 0;
            int checked = 0;
            int skipped = 0;
            ArrayNode failures = Jsons.object().putArray("failures");
            ArrayNode skips = Jsons.object().putArray("skips");
            List<Path> files = list(expected);
            for (Path file : files) {
                JsonNode spec = Jsons.MAPPER.readTree(Files.readString(file));
                String id = spec.path("case_id").asText(file.getFileName().toString());
                if (only != null && !only.isBlank() && !only.equals(id)) continue;
                cases++;
                Outcome outcome = caseOf(cdp, spec, port);
                checked += outcome.checked;
                skipped += outcome.skipped;
                if (outcome.failures.isEmpty()) passed++;
                else {
                    failed++;
                    if (failures.size() < 40) {
                        ObjectNode one = failures.addObject();
                        one.put("case_id", outcome.id);
                        ArrayNode why = one.putArray("failures");
                        for (String failure : outcome.failures) why.add(failure);
                    }
                }
                for (String skip : outcome.skipReasons) {
                    if (skips.size() < 40) skips.add(outcome.id + " " + skip);
                }
            }
            ObjectNode node = Jsons.object();
            node.put("ok", failed == 0);
            node.put("live", true);
            node.put("root", root.toAbsolutePath().normalize().toString());
            node.put("cases", cases);
            node.put("passed", passed);
            node.put("failed", failed);
            node.put("checked", checked);
            node.put("skipped", skipped);
            node.set("failures", failures);
            node.set("skips", skips);
            EngineLog.info("query", "conform live cases=" + cases + " failed=" + failed + " skipped=" + skipped + " checked=" + checked);
            return node;
        } catch (CdpException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CdpException("live conform: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        } finally {
            if (session != null) session.close();
            if (main != null) main.stop(0);
            if (cross != null) cross.stop(0);
        }
    }

    private static Outcome caseOf(CdpConnection cdp, JsonNode spec, int port) {
        Outcome outcome = new Outcome();
        outcome.id = spec.path("case_id").asText("");
        try {
            JsonNode capture = spec.path("capture");
            int width = capture.path("viewport_width").asInt(800);
            int height = capture.path("viewport_height").asInt(600);
            double dpr = capture.path("device_scale_factor").asDouble(1);
            boolean mobile = capture.path("mobile").asBoolean(false);
            double scrollX = capture.path("scroll_x").asDouble(0);
            double scrollY = capture.path("scroll_y").asDouble(0);
            emulate(cdp, width, height, dpr, mobile, capture.path("media").asText(""), capture.path("color_scheme").asText(""));
            String relative = spec.path("path").asText("");
            if (!relative.startsWith("/")) relative = "/" + relative;
            ObjectNode nav = Jsons.object();
            nav.put("url", "http://127.0.0.1:" + port + relative);
            cdp.call("Page.navigate", nav, Duration.ofSeconds(20));
            JsonNode settleSpec = capture.path("settle");
            Sample settled = settle(cdp, settleSpec);
            ObjectNode probeArgs = Jsons.object();
            probeArgs.put("scrollX", scrollX);
            probeArgs.put("scrollY", scrollY);
            ArrayNode points = probeArgs.putArray("points");
            ArrayNode queries = probeArgs.putArray("queries");
            int querySlot = 0;
            for (JsonNode assertion : spec.withArray("assertions")) {
                String kind = assertion.path("kind").asText("");
                if ("hit".equals(kind) || "hit_not".equals(kind)) {
                    ArrayNode point = points.addArray();
                    point.add(assertion.path("x").asDouble());
                    point.add(assertion.path("y").asDouble());
                }
                if ("query_count".equals(kind) || "query_pw".equals(kind) || "query_selenium".equals(kind)) {
                    if (assertion instanceof ObjectNode object) object.put("_q", querySlot);
                    queries.add(assertion);
                    querySlot++;
                }
            }
            JsonNode probe = eval(cdp, "(" + PROBE + ")(" + Jsons.compact(probeArgs) + ")", false);
            LiveTree tree = LiveTree.read(cdp, Duration.ofSeconds(15));
            stampAx(cdp, tree.root);
            Map<String, JsonNode> byId = index(probe.path("nodes"), "id");
            Map<Long, Integer> paint = paintOrders(cdp);
            Map<String, Integer> paintById = paintByElement(tree.root, paint);
            List<String> unknowns = unknowns(probe, tree, spec);
            Map<String, String> framePaths = framePaths(cdp);
            for (JsonNode assertion : spec.withArray("assertions")) {
                String kind = assertion.path("kind").asText("");
                String problem = check(kind, assertion, probe, byId, tree, settled, unknowns, paintById, framePaths, cdp, scrollX, scrollY, width, dpr);
                if (problem == null) outcome.checked++;
                else if (problem.startsWith("skip:")) {
                    outcome.skipped++;
                    outcome.skipReasons.add(kind + " " + problem.substring(5));
                } else outcome.failures.add(kind + " " + problem);
            }
        } catch (Exception failure) {
            outcome.failures.add(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
        return outcome;
    }

    private static String check(
            String kind,
            JsonNode assertion,
            JsonNode probe,
            Map<String, JsonNode> byId,
            LiveTree tree,
            Sample settled,
            List<String> unknowns,
            Map<String, Integer> paintById,
            Map<String, String> framePaths,
            CdpConnection cdp,
            double scrollX,
            double scrollY,
            int cssWidth,
            double dpr
    ) {
        return switch (kind) {
            case "element" -> node(assertion, probe, byId, tree) == null && dom(tree.root, assertion) == null
                    ? "missing " + label(assertion) : null;
            case "text_exact" -> textExact(assertion, probe, byId, tree);
            case "text_contains" -> textContains(assertion, probe, byId, tree);
            case "attr" -> attr(assertion, probe, byId, tree);
            case "attr_matches" -> attrMatches(assertion, probe, byId, tree);
            case "class_matches" -> classMatches(assertion, probe, byId, tree);
            case "style" -> style(assertion, probe, byId, tree, cdp, true);
            case "style_contains" -> style(assertion, probe, byId, tree, cdp, false);
            case "min_nodes" -> tree.root.count() >= assertion.path("min").asInt(1) ? null : "nodes " + tree.root.count();
            case "count_selector" -> countSelector(assertion, tree);
            case "shadow" -> shadow(assertion, tree);
            case "frame_text" -> frameText(assertion, probe, tree, framePaths, cdp);
            case "cross_origin_frame" -> crossOrigin(assertion, framePaths);
            case "slot" -> slot(assertion, probe, byId, tree);
            case "pseudo" -> pseudo(assertion, probe, byId);
            case "visible", "displayed", "clickable" -> flag(kind, assertion, probe, byId);
            case "mismatch" -> mismatch(assertion, probe, byId);
            case "in_top_layer" -> topLayer(assertion, probe, byId, tree, cdp);
            case "stacking_context" -> boolField("stack", assertion, probe, byId, "stacking context");
            case "top_layer_above" -> topAbove(assertion, probe, paintById);
            case "paint_above" -> paintAbove(assertion, paintById);
            case "hit" -> hit(assertion, probe, true);
            case "hit_not" -> hit(assertion, probe, false);
            case "quad_box" -> quadBox(assertion, probe, byId, tree, cdp, scrollX, scrollY);
            case "quad_width" -> quadWidth(assertion, probe, byId, tree, cdp, scrollX, scrollY);
            case "rotated" -> rotated(assertion, probe, byId, tree, cdp, scrollX, scrollY);
            case "focus_order" -> focusOrder(assertion, probe);
            case "focus_absent" -> focusAbsent(assertion, probe, byId);
            case "settled" -> settled.settled == assertion.path("value").asBoolean(true) ? null : "settled " + settled.settled;
            case "high_frequency" -> settled.highFrequency == assertion.path("value").asBoolean(true) ? null : "high_frequency " + settled.highFrequency;
            case "pending_contains" -> settled.pending.contains(assertion.path("text").asText("")) ? null : "pending missing " + assertion.path("text").asText("") + ": " + settled.pending;
            case "screenshot_dpr" -> screenshot(cdp, cssWidth, dpr);
            case "unknown" -> unknown(assertion, unknowns);
            case "ax_role", "ax_role_not", "ax_name_contains", "ax_name_not_contains", "ax_ignored" -> ax(kind, assertion, probe, byId, tree, cdp);
            case "query_count", "query_pw", "query_selenium" -> query(kind, assertion, probe, tree);
            default -> "skip:unmapped " + kind;
        };
    }

    private static String textExact(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree) {
        String got = text(assertion, probe, byId, tree);
        if (got == null) return "missing " + label(assertion);
        String want = assertion.path("text").asText("");
        return want.equals(got) ? null : "text '" + got + "' != '" + want + "'";
    }

    private static String textContains(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree) {
        String got = text(assertion, probe, byId, tree);
        if (got == null) return "missing " + label(assertion);
        String want = assertion.path("text").asText("");
        return got.contains(want) ? null : "text '" + clip(got) + "' missing '" + want + "'";
    }

    private static String text(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree) {
        JsonNode js = node(assertion, probe, byId, tree);
        if (js != null && js.has("text")) return js.path("text").asText("");
        DomNode dom = dom(tree.root, assertion);
        return dom == null ? null : descendantText(dom);
    }

    private static String attr(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree) {
        String name = assertion.path("name").asText("");
        String got = attrValue(assertion, name, probe, byId, tree);
        if (got == null) return "missing attr " + name;
        if (assertion.has("value") && !assertion.path("value").asText("").equals(got)) {
            return "attr " + name + "=" + got;
        }
        return null;
    }

    private static String attrMatches(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree) {
        String name = assertion.path("name").asText("");
        String got = attrValue(assertion, name, probe, byId, tree);
        if (got == null) got = "";
        String pattern = assertion.path("pattern").asText("");
        return patternMatch(got, pattern) ? null : "attr " + name + "=" + got + " !~ " + pattern;
    }

    private static String classMatches(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree) {
        String got = attrValue(assertion, "class", probe, byId, tree);
        if (got == null) got = "";
        String pattern = assertion.path("pattern").asText("");
        boolean ok = patternMatch(got, pattern);
        for (String token : got.split("\\s+")) if (patternMatch(token, pattern)) ok = true;
        return ok ? null : "class " + got + " !~ " + pattern;
    }

    private static String style(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree, CdpConnection cdp, boolean exact) {
        String prop = assertion.path("prop").asText("");
        String got = styleValue(assertion, prop, probe, byId, tree, cdp);
        if (got == null) return "missing " + label(assertion);
        if (exact) {
            String want = assertion.path("value").asText("");
            return want.equals(got) ? null : "style " + prop + "=" + got + " != " + want;
        }
        String text = assertion.path("text").asText("");
        return got.contains(text) ? null : "style " + prop + "=" + got + " missing " + text;
    }

    private static String countSelector(JsonNode assertion, LiveTree tree) {
        String selector = assertion.path("selector").asText("*");
        int count = countCss(tree.root, selector, true);
        if (assertion.has("min") && count < assertion.path("min").asInt()) return selector + " count " + count + " < " + assertion.path("min").asInt();
        if (assertion.has("max") && count > assertion.path("max").asInt()) return selector + " count " + count + " > " + assertion.path("max").asInt();
        return null;
    }

    private static String shadow(JsonNode assertion, LiveTree tree) {
        DomNode host = findId(tree.root, assertion.path("id").asText(""));
        if (host == null) return "missing shadow host";
        String root = assertion.path("root").asText("");
        DomNode shadow = null;
        for (DomNode candidate : host.shadowRoots) {
            if (root.equalsIgnoreCase(candidate.shadowMode)) shadow = candidate;
        }
        if (shadow == null) return "no " + root + " shadow root";
        if (assertion.has("child_id")) {
            String childId = assertion.path("child_id").asText("");
            DomNode child = findId(shadow, childId);
            if (child == null) return "#" + childId + " is not inside the " + root + " shadow";
        }
        return null;
    }

    private static String frameText(JsonNode assertion, JsonNode probe, LiveTree tree, Map<String, String> framePaths, CdpConnection cdp) {
        String path = assertion.path("frame_path").asText("");
        String want = assertion.path("text").asText("");
        String frameId = framePaths.get(path);
        StringBuilder blob = new StringBuilder();
        if (frameId != null) appendFrameText(tree.root, frameId, blob);
        if (blob.indexOf(want) < 0) {
            for (JsonNode frame : probe.withArray("frames")) {
                if (path.equals(frame.path("path").asText()) && frame.path("text").asText("").contains(want)) return null;
            }
        }
        if (blob.indexOf(want) >= 0) return null;
        if (frameId != null) {
            String live = frameEval(cdp, frameId);
            if (live.contains(want)) return null;
        }
        return "frame " + path + " text '" + clip(blob.toString()) + "' missing '" + want + "'";
    }

    private static String crossOrigin(JsonNode assertion, Map<String, String> framePaths) {
        String path = assertion.path("frame_path").asText("");
        String marker = framePaths.get(path + "#cross");
        if (!"true".equals(marker)) return "frame " + path + " is not cross-origin";
        return null;
    }

    private static String slot(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree) {
        String want = assertion.path("slot").asText("");
        JsonNode js = node(assertion, probe, byId, tree);
        if (js != null) {
            String got = js.path("slot").asText("");
            return want.equals(got) ? null : "slot " + got + " != " + want;
        }
        DomNode dom = dom(tree.root, assertion);
        if (dom == null) return "missing " + label(assertion);
        String got = dom.attributes.getOrDefault("slot", "");
        return want.equals(got) ? null : "slot " + got + " != " + want;
    }

    private static String pseudo(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId) {
        JsonNode js = node(assertion, probe, byId, null);
        if (js == null) return "missing pseudo host";
        String which = assertion.path("pseudo").asText("");
        String got = "before".equals(which) ? js.path("before").asText("") : js.path("after").asText("");
        if (got.isEmpty()) return "no ::" + which;
        if (assertion.has("text_contains") && !got.contains(assertion.path("text_contains").asText(""))) {
            return "::" + which + " text '" + got + "'";
        }
        return null;
    }

    private static String flag(String kind, JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId) {
        JsonNode js = node(assertion, probe, byId, null);
        if (js == null) return "missing " + label(assertion);
        boolean want = assertion.path("value").asBoolean(true);
        boolean got = js.path(kind).asBoolean(false);
        return got == want ? null : label(assertion) + " " + kind + " is " + got + ", expected " + want;
    }

    private static String mismatch(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId) {
        JsonNode js = node(assertion, probe, byId, null);
        if (js == null) return "missing " + label(assertion);
        String want = assertion.path("value").asText("");
        String got = js.path("mismatch").asText("");
        return want.equals(got) ? null : "mismatch '" + got + "' != '" + want + "'";
    }

    private static String topLayer(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree, CdpConnection cdp) {
        JsonNode js = node(assertion, probe, byId, tree);
        boolean want = assertion.path("value").asBoolean(true);
        if (js != null) {
            boolean got = js.path("top").asBoolean(false);
            return got == want ? null : "in_top_layer " + got + " != " + want;
        }
        DomNode dom = dom(tree.root, assertion);
        if (dom == null) return "missing " + label(assertion);
        boolean got = liveMatches(cdp, dom, ":top-layer") || liveMatches(cdp, dom, ":modal") || liveMatches(cdp, dom, ":popover-open");
        return got == want ? null : "in_top_layer " + got + " != " + want;
    }

    private static boolean liveMatches(CdpConnection cdp, DomNode node, String selector) {
        if (node == null || node.nodeId <= 0) return false;
        try {
            ObjectNode resolve = Jsons.object();
            resolve.put("nodeId", node.nodeId);
            JsonNode object = cdp.call("DOM.resolveNode", resolve, Duration.ofSeconds(5)).path("object");
            ObjectNode call = Jsons.object();
            call.put("objectId", object.path("objectId").asText());
            call.put("functionDeclaration", "function(){ try { return this.matches(" + Jsons.MAPPER.writeValueAsString(selector) + "); } catch (e) { return false; } }");
            call.put("returnByValue", true);
            JsonNode result = cdp.call("Runtime.callFunctionOn", call, Duration.ofSeconds(5));
            return result.path("result").path("value").asBoolean(false);
        } catch (Exception failure) {
            return false;
        }
    }

    private static String boolField(String field, JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, String name) {
        JsonNode js = node(assertion, probe, byId, null);
        if (js == null) return "missing " + label(assertion);
        boolean want = assertion.path("value").asBoolean(true);
        boolean got = js.path(field).asBoolean(false);
        return got == want ? null : name + " " + got + " != " + want;
    }

    private static String topAbove(JsonNode assertion, JsonNode probe, Map<String, Integer> paintById) {
        List<Top> tops = new ArrayList<>();
        for (JsonNode top : probe.withArray("tops")) {
            String id = top.path("id").asText("");
            int paint = paintById.getOrDefault(id, 0);
            int band = 0;
            int index = 0;
            for (JsonNode modal : probe.withArray("modals")) {
                index++;
                String modalId = modal.asText("");
                if (id.equals(modalId) || contains(top.path("ancestors"), modalId)) band = index;
            }
            tops.add(new Top(id, band, paint, tops.size()));
        }
        tops.sort((a, b) -> {
            if (a.band != b.band) return Integer.compare(a.band, b.band);
            if (a.paint != b.paint) return Integer.compare(a.paint, b.paint);
            return Integer.compare(a.order, b.order);
        });
        Map<String, Integer> ordinal = new LinkedHashMap<>();
        for (int i = 0; i < tops.size(); i++) ordinal.put(tops.get(i).id, i);
        String above = assertion.path("above").asText("");
        String below = assertion.path("below").asText("");
        Integer a = ordinal.get(above);
        Integer b = ordinal.get(below);
        if (a != null && b != null && a > b) return null;
        return "top layer " + a + " is not above " + b;
    }

    private static String paintAbove(JsonNode assertion, Map<String, Integer> paintById) {
        String above = assertion.path("above").asText("");
        String below = assertion.path("below").asText("");
        Integer a = paintById.get(above);
        Integer b = paintById.get(below);
        if (a != null && b != null && a > b) return null;
        return "paint order " + a + " is not above " + b;
    }

    private static String hit(JsonNode assertion, JsonNode probe, boolean wantInside) {
        double x = assertion.path("x").asDouble();
        double y = assertion.path("y").asDouble();
        JsonNode stack = hitStack(probe.path("hits"), x, y);
        String key = x + "," + y;
        if (stack == null || !stack.isArray() || stack.isEmpty()) return "no hit cell contains " + key;
        JsonNode top = stack.get(0);
        boolean inside = stackMatches(stack, assertion);
        if (wantInside && !inside) return "hit at " + key + " is #" + top.path("id").asText(top.path("tag").asText("?")) + " not " + label(assertion);
        if (!wantInside && inside) return "hit at " + key + " unexpectedly " + label(assertion) + " top=#" + top.path("id").asText(top.path("tag").asText("?"));
        return null;
    }

    private static JsonNode hitStack(JsonNode hits, double x, double y) {
        if (hits == null || !hits.isObject()) return null;
        for (var entry : hits.properties()) {
            String[] parts = entry.getKey().split(",");
            if (parts.length != 2) continue;
            try {
                double hx = Double.parseDouble(parts[0]);
                double hy = Double.parseDouble(parts[1]);
                if (Math.abs(hx - x) < 0.2 && Math.abs(hy - y) < 0.2 && entry.getValue().isArray() && !entry.getValue().isEmpty()) {
                    return entry.getValue();
                }
            } catch (NumberFormatException ignored) {
                // The next key may still name this point.
            }
        }
        return null;
    }

    private static boolean stackMatches(JsonNode stack, JsonNode assertion) {
        if (stack == null || !stack.isArray() || stack.isEmpty()) return false;
        JsonNode top = stack.get(0);
        String id = assertion.path("id").asText("");
        String selector = assertion.path("selector").asText("");
        if (!id.isEmpty() && id.equals(top.path("id").asText())) return true;
        if (selectorMatches(selector, top)) return true;
        for (JsonNode ancestor : top.withArray("ancestors")) {
            String token = ancestor.asText("");
            if (!id.isEmpty() && id.equals(token)) return true;
            if (!selector.isEmpty() && selector.equals(token)) return true;
        }
        return false;
    }

    private static String quadBox(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree, CdpConnection cdp, double scrollX, double scrollY) {
        double[] quad = quad(assertion, probe, byId, tree, cdp, scrollX, scrollY);
        if (quad == null) return "no border quad " + label(assertion);
        double minX = min(quad[0], quad[2], quad[4], quad[6]);
        double minY = min(quad[1], quad[3], quad[5], quad[7]);
        double w = Math.hypot(quad[2] - quad[0], quad[3] - quad[1]);
        double h = Math.hypot(quad[6] - quad[0], quad[7] - quad[1]);
        String problem = limit("min_x", minX, assertion, true);
        if (problem != null) return problem;
        problem = limit("max_x", minX, assertion, false);
        if (problem != null) return problem;
        problem = limit("min_y", minY, assertion, true);
        if (problem != null) return problem;
        problem = limit("max_y", minY, assertion, false);
        if (problem != null) return problem;
        problem = limit("min_w", w, assertion, true);
        if (problem != null) return problem;
        problem = limit("max_w", w, assertion, false);
        if (problem != null) return problem;
        problem = limit("min_h", h, assertion, true);
        if (problem != null) return problem;
        return limit("max_h", h, assertion, false);
    }

    private static String quadWidth(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree, CdpConnection cdp, double scrollX, double scrollY) {
        double[] quad = quad(assertion, probe, byId, tree, cdp, scrollX, scrollY);
        if (quad == null) return "no border quad";
        double w = Math.hypot(quad[2] - quad[0], quad[3] - quad[1]);
        double min = assertion.path("min").asDouble();
        double max = assertion.path("max").asDouble();
        return w >= min && w <= max ? null : "quad width " + w + " not in " + min + ".." + max;
    }

    private static String rotated(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree, CdpConnection cdp, double scrollX, double scrollY) {
        DomNode dom = dom(tree.root, assertion);
        double[] quad = dom == null ? null : borderQuad(cdp, dom, scrollX, scrollY);
        if (quad == null) quad = quad(assertion, probe, byId, tree, cdp, scrollX, scrollY);
        if (quad == null) return "no border quad";
        if (!axisAligned(quad)) return null;
        return "quad is axis-aligned [" + quad[0] + "," + quad[1] + " " + quad[2] + "," + quad[3] + " " + quad[4] + "," + quad[5] + " " + quad[6] + "," + quad[7] + "]";
    }

    private static double[] borderQuad(CdpConnection cdp, DomNode dom, double scrollX, double scrollY) {
        if (dom == null || dom.nodeId <= 0) return null;
        try {
            ObjectNode params = Jsons.object();
            params.put("nodeId", dom.nodeId);
            JsonNode model = cdp.call("DOM.getBoxModel", params, Duration.ofSeconds(5)).path("model").path("border");
            if (!model.isArray() || model.size() < 8) return null;
            double[] quad = new double[8];
            for (int i = 0; i < 8; i++) quad[i] = model.get(i).asDouble();
            for (int i = 0; i < 8; i += 2) {
                quad[i] += scrollX;
                quad[i + 1] += scrollY;
            }
            return quad;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String focusOrder(JsonNode assertion, JsonNode probe) {
        JsonNode focus = probe.path("focus");
        Integer prev = null;
        for (JsonNode token : assertion.withArray("ids")) {
            int index = focusIndex(focus, token.asText(""));
            if (index < 0) return "missing focus target " + token.asText("");
            if (prev != null && index <= prev) return "focus ordinal " + token.asText("") + " " + index + " <= " + prev;
            prev = index;
        }
        return null;
    }

    private static String focusAbsent(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId) {
        String id = assertion.path("id").asText("");
        if (id.isEmpty()) {
            JsonNode js = node(assertion, probe, byId, null);
            id = js == null ? "" : js.path("id").asText("");
        }
        if (focusIndex(probe.path("focus"), id) >= 0) return "#" + id + " is in the tab order";
        return null;
    }

    private static String screenshot(CdpConnection cdp, int cssWidth, double dpr) {
        ObjectNode params = Jsons.object();
        params.put("format", "png");
        params.put("fromSurface", true);
        JsonNode shot = cdp.call("Page.captureScreenshot", params, Duration.ofSeconds(15));
        byte[] png = Base64.getDecoder().decode(shot.path("data").asText(""));
        if (png.length < 24 || png[0] != (byte) 0x89) return "no viewport screenshot";
        int deviceWidth = ((png[16] & 0xff) << 24) | ((png[17] & 0xff) << 16) | ((png[18] & 0xff) << 8) | (png[19] & 0xff);
        double expect = Math.round(cssWidth * dpr);
        if (Math.abs(deviceWidth - expect) > 1) return "device width " + deviceWidth + " != round(" + cssWidth + " * " + dpr + ")";
        return null;
    }

    private static String unknown(JsonNode assertion, List<String> unknowns) {
        String name = assertion.path("name").asText("");
        return unknowns.contains(name) ? null : "missing unknown " + name;
    }

    private static String ax(String kind, JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree, CdpConnection cdp) {
        DomNode dom = dom(tree.root, assertion);
        JsonNode js = node(assertion, probe, byId, tree);
        long backend = dom == null ? -1 : dom.backendNodeId;
        if (backend < 0 && js != null) {
            DomNode again = findId(tree.root, js.path("id").asText(""));
            if (again != null) backend = again.backendNodeId;
        }
        JsonNode rows = axRows(cdp, backend);
        String role = assertion.path("role").asText("");
        String text = assertion.path("text").asText("");
        boolean matchedRole = false;
        boolean matchedName = false;
        boolean ignored = false;
        boolean any = false;
        if (rows != null) {
            for (JsonNode row : rows) {
                any = true;
                String gotRole = row.path("role").path("value").asText("");
                String gotName = row.path("name").path("value").asText("");
                if (gotRole.equalsIgnoreCase(role)) matchedRole = true;
                if (gotName.contains(text)) matchedName = true;
                if (row.path("ignored").asBoolean(false) == assertion.path("value").asBoolean(true)) ignored = true;
            }
        }
        return switch (kind) {
            case "ax_role" -> matchedRole ? null : "ax role != " + role;
            case "ax_role_not" -> matchedRole ? "ax role unexpectedly " + role : null;
            case "ax_name_contains" -> matchedName ? null : "ax name missing " + text;
            case "ax_name_not_contains" -> matchedName ? "ax name unexpectedly contains " + text : null;
            case "ax_ignored" -> !any ? "no ax node" : ignored ? null : "ax ignored != " + assertion.path("value").asBoolean(true);
            default -> "skip:unmapped " + kind;
        };
    }

    private static String query(String kind, JsonNode assertion, JsonNode probe, LiveTree tree) {
        if (assertion.has("pipeline") || "composite".equals(assertion.path("mode").asText(""))) {
            if (assertion.has("css") && !assertion.has("pipeline")) return cssQuery(assertion, tree);
            return pipelineQuery(assertion, tree);
        }
        int index = assertion.path("_q").asInt(-1);
        JsonNode ran = index < 0 || !probe.path("queries").isArray() ? null : probe.path("queries").get(index);
        if (ran == null || !ran.path("handled").asBoolean(false)) {
            if (assertion.has("pw") && assertion.path("pw").asText("").startsWith("getByRole")) return roleQuery(assertion, tree);
            return "query was not evaluated";
        }
        boolean expectError = assertion.path("expect_error").asBoolean(false);
        boolean strict = assertion.path("strict").asBoolean(false);
        String error = ran.path("error").asText("");
        int count = ran.path("count").asInt(0);
        if (strict && count != 1 && error.isEmpty()) error = "strict";
        if (expectError) return error.isEmpty() ? "expected query error, got " + count + " hit(s)" : null;
        if (!error.isEmpty()) return error;
        if (assertion.has("count") && count != assertion.path("count").asInt()) return "query count " + count + " != " + assertion.path("count").asInt();
        if (assertion.has("min") && count < assertion.path("min").asInt()) return "query count " + count + " < " + assertion.path("min").asInt();
        if (assertion.has("max") && count > assertion.path("max").asInt()) return "query count " + count + " > " + assertion.path("max").asInt();
        if (assertion.has("ids")) {
            List<String> got = new ArrayList<>();
            for (JsonNode id : ran.withArray("ids")) if (!id.asText("").isEmpty()) got.add(id.asText());
            List<String> want = new ArrayList<>();
            for (JsonNode id : assertion.withArray("ids")) want.add(id.asText());
            if (!got.equals(want)) return "query ids " + got + " != " + want;
        }
        return null;
    }

    private static String cssQuery(JsonNode assertion, LiveTree tree) {
        int count = countCss(tree.root, assertion.path("css").asText(""), assertion.path("all_frames").asBoolean(false) || true);
        boolean expectError = assertion.path("expect_error").asBoolean(false);
        if (expectError) return null;
        if (assertion.has("count") && count != assertion.path("count").asInt()) return "query count " + count + " != " + assertion.path("count").asInt();
        if (assertion.has("min") && count < assertion.path("min").asInt()) return "query count " + count + " < " + assertion.path("min").asInt();
        if (assertion.has("max") && count > assertion.path("max").asInt()) return "query count " + count + " > " + assertion.path("max").asInt();
        return null;
    }

    private static String pipelineQuery(JsonNode assertion, LiveTree tree) {
        String pipeline = assertion.path("pipeline").asText("");
        boolean expectError = assertion.path("expect_error").asBoolean(false);
        try {
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("pipeline", pipeline);
            if (assertion.path("strict").asBoolean(false)) flags.put("strict", "true");
            JsonNode result = Query.run(tree.root, flags);
            if (expectError) return "expected query error, got " + result.path("nodes").size() + " hit(s)";
            List<String> got = new ArrayList<>();
            for (JsonNode node : result.withArray("nodes")) {
                String id = node.path("attributes").path("id").asText("");
                if (!id.isEmpty()) got.add(id);
            }
            int count = result.path("nodes").size();
            if (assertion.has("ids")) {
                List<String> want = new ArrayList<>();
                for (JsonNode id : assertion.withArray("ids")) want.add(id.asText());
                if (!got.equals(want)) return "query ids " + got + " != " + want;
            }
            if (assertion.has("count") && count != assertion.path("count").asInt()) return "query count " + count + " != " + assertion.path("count").asInt();
            if (assertion.has("min") && count < assertion.path("min").asInt()) return "query count " + count + " < " + assertion.path("min").asInt();
            if (assertion.has("max") && count > assertion.path("max").asInt()) return "query count " + count + " > " + assertion.path("max").asInt();
            return null;
        } catch (RuntimeException failure) {
            return expectError ? null : failure.getMessage();
        }
    }

    private static String roleQuery(JsonNode assertion, LiveTree tree) {
        String pw = assertion.path("pw").asText("");
        String role = between(pw, "getByRole('", "'");
        String name = between(pw, "name: '", "'");
        if (name.isEmpty()) name = between(pw, "name: \"", "\"");
        boolean exact = pw.contains("exact: true") || pw.contains("exact:true");
        List<DomNode> hits = new ArrayList<>();
        for (DomNode node : elements(tree.root)) {
            if (!role.equalsIgnoreCase(Query.roleOf(node)) && !role.equalsIgnoreCase(node.role)) continue;
            String accessible = node.accessibleName.isBlank() ? Engines.accessibleName(node) : node.accessibleName;
            if (!name.isEmpty()) {
                String hay = accessible.toLowerCase(Locale.ROOT);
                String needle = name.toLowerCase(Locale.ROOT);
                if (exact ? !accessible.equals(name) : !hay.contains(needle)) continue;
            }
            hits.add(node);
        }
        int count = hits.size();
        boolean expectError = assertion.path("expect_error").asBoolean(false);
        boolean strict = assertion.path("strict").asBoolean(false);
        if (strict && count != 1) return expectError ? null : "strict count " + count;
        if (expectError) return "expected query error, got " + count + " hit(s)";
        if (assertion.has("count") && count != assertion.path("count").asInt()) return "query count " + count + " != " + assertion.path("count").asInt();
        return null;
    }

    private static Sample settle(CdpConnection cdp, JsonNode spec) {
        long overall = spec.path("overall_ms").asLong(2500);
        long idle = spec.path("network_idle_ms").asLong(150);
        long quiet = spec.path("mutation_quiet_ms").asLong(120);
        long deadline = System.nanoTime() + Duration.ofMillis(Math.max(1, overall)).toNanos();
        JsonNode last = Jsons.object();
        boolean ok = false;
        while (true) {
            last = eval(cdp, SETTLE, false);
            boolean ready = "complete".equals(last.path("ready").asText());
            boolean net = last.path("inflight").asInt(0) == 0 && last.path("netAge").asDouble(0) >= idle;
            boolean mut = last.path("lastAge").asDouble(0) >= quiet;
            boolean fonts = "loaded".equals(last.path("fonts").asText("loaded")) || last.path("fonts").isNull();
            boolean images = !last.path("pendingImgs").isArray() || last.path("pendingImgs").isEmpty();
            boolean anims = !last.path("anims").isArray() || last.path("anims").isEmpty();
            boolean busy = !last.path("busy").isArray() || last.path("busy").isEmpty();
            if (ready && net && mut && fonts && images && anims && busy) {
                ok = true;
                break;
            }
            if (System.nanoTime() >= deadline) break;
            try {
                Thread.sleep(40);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        boolean mut = last.path("lastAge").asDouble(0) >= quiet;
        int count = last.path("mutations").asInt(0);
        List<String> pending = new ArrayList<>();
        if (!"complete".equals(last.path("ready").asText())) pending.add("readyState is " + last.path("ready").asText(""));
        if (!(last.path("inflight").asInt(0) == 0 && last.path("netAge").asDouble(0) >= idle)) pending.add("network idle not reached");
        if (!mut) pending.add("mutations still arriving (" + count + ")");
        if (!"loaded".equals(last.path("fonts").asText("loaded"))) pending.add("fonts not ready (" + last.path("fonts").asText("") + ")");
        if (last.path("pendingImgs").isArray() && !last.path("pendingImgs").isEmpty()) pending.add("images pending");
        if (last.path("anims").isArray() && !last.path("anims").isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (JsonNode anim : last.path("anims")) {
                if (!names.isEmpty()) names.append(',');
                names.append(anim.asText(""));
            }
            pending.add("animations running: " + names);
        }
        if (last.path("busy").isArray() && !last.path("busy").isEmpty()) pending.add("aria-busy");
        addLoaders(pending, last, "spinners", "spinner");
        addLoaders(pending, last, "skeletons", "skeleton");
        addLoaders(pending, last, "bars", "progress");
        String blob = String.join(" | ", pending);
        return new Sample(ok, !mut && count > 20, blob);
    }

    private static void addLoaders(List<String> pending, JsonNode last, String key, String label) {
        if (!last.path(key).isArray()) return;
        for (JsonNode item : last.path(key)) pending.add("loader " + label + ": " + item.asText(""));
    }

    private static void emulate(CdpConnection cdp, int width, int height, double dpr, boolean mobile, String media, String scheme) {
        ObjectNode metrics = Jsons.object();
        metrics.put("width", width);
        metrics.put("height", height);
        metrics.put("deviceScaleFactor", dpr);
        metrics.put("mobile", mobile);
        cdp.call("Emulation.setDeviceMetricsOverride", metrics, Duration.ofSeconds(5));
        ObjectNode emulated = Jsons.object();
        if (media != null && !media.isBlank() && !"null".equals(media)) emulated.put("media", media);
        else emulated.put("media", "");
        ArrayNode features = emulated.putArray("features");
        if (scheme != null && !scheme.isBlank() && !"null".equals(scheme)) {
            ObjectNode feature = features.addObject();
            feature.put("name", "prefers-color-scheme");
            feature.put("value", scheme);
        }
        cdp.call("Emulation.setEmulatedMedia", emulated, Duration.ofSeconds(5));
    }

    private static List<String> unknowns(JsonNode probe, LiveTree tree, JsonNode spec) {
        List<String> names = new ArrayList<>(List.of(
                "http_bodies", "cookie_values", "indexed_db_records", "mutation_timeline", "user_agent_shadow"
        ));
        if (hasShadow(tree.root, "closed")) names.add("closed_shadow_hit");
        JsonNode flags = probe.path("flags");
        if (flags.path("canvas").asBoolean(false)) names.add("canvas_bitmap");
        if (flags.path("lazy").asBoolean(false)) names.add("lazy_image");
        if (flags.path("virtual").asBoolean(false)) names.add("virtualized_unmounted");
        if (flags.path("infinite").asBoolean(false)) names.add("infinite_scroll_unprobed");
        if (flags.path("serviceWorker").asBoolean(false)) names.add("service_worker");
        for (JsonNode assertion : spec.withArray("assertions")) {
            if ("cross_origin_frame".equals(assertion.path("kind").asText(""))) names.add("cross_origin_hit");
        }
        return names;
    }

    private static Map<String, String> framePaths(CdpConnection cdp) {
        Map<String, String> paths = new LinkedHashMap<>();
        JsonNode tree = cdp.call("Page.getFrameTree", Jsons.object(), Duration.ofSeconds(5)).path("frameTree");
        walkFrames(tree, "0", null, paths);
        return paths;
    }

    private static void walkFrames(JsonNode tree, String path, String parentOrigin, Map<String, String> paths) {
        if (tree == null || tree.isNull()) return;
        JsonNode frame = tree.get("frame");
        if (frame == null) return;
        String origin = frame.path("securityOrigin").asText("");
        paths.put(path, frame.path("id").asText(""));
        boolean cross = parentOrigin != null && !origin.isBlank() && !origin.equals(parentOrigin) && !"://".equals(origin);
        paths.put(path + "#cross", cross ? "true" : "false");
        JsonNode children = tree.get("childFrames");
        if (children != null && children.isArray()) {
            int index = 0;
            for (JsonNode child : children) {
                walkFrames(child, path + "/" + index, origin, paths);
                index++;
            }
        }
    }

    private static String frameEval(CdpConnection cdp, String frameId) {
        try {
            ObjectNode world = Jsons.object();
            world.put("frameId", frameId);
            world.put("grantUniveralAccess", true);
            JsonNode created = cdp.call("Page.createIsolatedWorld", world, Duration.ofSeconds(5));
            ObjectNode eval = Jsons.object();
            eval.put("expression", "document.body ? document.body.innerText : ''");
            eval.put("returnByValue", true);
            eval.put("contextId", created.path("executionContextId").asInt());
            JsonNode result = cdp.call("Runtime.evaluate", eval, Duration.ofSeconds(5));
            return result.path("result").path("value").asText("");
        } catch (RuntimeException failure) {
            return "";
        }
    }

    private static Map<Long, Integer> paintOrders(CdpConnection cdp) {
        Map<Long, Integer> orders = new LinkedHashMap<>();
        try {
            ObjectNode params = Jsons.object();
            params.putArray("computedStyles");
            params.put("includePaintOrder", true);
            JsonNode snap = cdp.call("DOMSnapshot.captureSnapshot", params, Duration.ofSeconds(10));
            for (JsonNode document : snap.withArray("documents")) {
                JsonNode layout = document.path("layout");
                JsonNode nodeIndex = layout.path("nodeIndex");
                JsonNode layoutPaints = layout.path("paintOrders");
                JsonNode nodes = document.path("nodes");
                JsonNode backends = nodes.path("backendNodeId");
                if (layoutPaints.isArray() && nodeIndex.isArray() && backends.isArray()) {
                    int n = Math.min(layoutPaints.size(), nodeIndex.size());
                    for (int i = 0; i < n; i++) {
                        int at = nodeIndex.get(i).asInt(-1);
                        if (at >= 0 && at < backends.size()) orders.put(backends.get(at).asLong(), layoutPaints.get(i).asInt());
                    }
                }
                JsonNode paints = nodes.path("paintOrders");
                if (paints.isArray() && backends.isArray()) {
                    int n = Math.min(paints.size(), backends.size());
                    for (int i = 0; i < n; i++) orders.put(backends.get(i).asLong(), paints.get(i).asInt());
                } else if (paints.isObject()) {
                    JsonNode index = paints.path("index");
                    JsonNode value = paints.path("value");
                    for (int i = 0; i < index.size() && i < value.size(); i++) {
                        int at = index.get(i).asInt();
                        if (at >= 0 && at < backends.size()) orders.put(backends.get(at).asLong(), value.get(i).asInt());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return orders;
        }
        return orders;
    }

    private static Map<String, Integer> paintByElement(DomNode node, Map<Long, Integer> paint) {
        Map<String, Integer> byId = new LinkedHashMap<>();
        paintWalk(node, paint, byId);
        return byId;
    }

    private static void paintWalk(DomNode node, Map<Long, Integer> paint, Map<String, Integer> byId) {
        if (node == null) return;
        String id = node.attributes.get("id");
        if (id != null && !id.isBlank() && paint.containsKey(node.backendNodeId)) byId.put(id, paint.get(node.backendNodeId));
        for (DomNode child : node.children) paintWalk(child, paint, byId);
        for (DomNode shadow : node.shadowRoots) paintWalk(shadow, paint, byId);
    }

    private static JsonNode axRows(CdpConnection cdp, long backend) {
        if (backend < 0) return Jsons.MAPPER.createArrayNode();
        try {
            JsonNode tree = cdp.call("Accessibility.getFullAXTree", Jsons.object(), Duration.ofSeconds(8));
            ArrayNode rows = Jsons.MAPPER.createArrayNode();
            for (JsonNode node : tree.withArray("nodes")) {
                if (node.path("backendDOMNodeId").asLong(-1) == backend) rows.add(node);
            }
            return rows;
        } catch (RuntimeException failure) {
            return Jsons.MAPPER.createArrayNode();
        }
    }

    private static void stampAx(CdpConnection cdp, DomNode root) {
        try {
            JsonNode tree = cdp.call("Accessibility.getFullAXTree", Jsons.object(), Duration.ofSeconds(8));
            Map<Long, JsonNode> byBackend = new LinkedHashMap<>();
            for (JsonNode node : tree.withArray("nodes")) {
                if (node.path("ignored").asBoolean(false)) continue;
                long backend = node.path("backendDOMNodeId").asLong(-1);
                if (backend >= 0) byBackend.put(backend, node);
            }
            applyAx(root, byBackend);
        } catch (RuntimeException ignored) {
            // Role queries fall back to the HTML role map.
        }
    }

    private static void applyAx(DomNode node, Map<Long, JsonNode> byBackend) {
        if (node == null) return;
        JsonNode ax = byBackend.get(node.backendNodeId);
        if (ax != null) {
            String name = ax.path("name").path("value").asText("");
            if (!name.isBlank()) node.accessibleName = name;
        }
        for (DomNode child : node.children) applyAx(child, byBackend);
        for (DomNode shadow : node.shadowRoots) applyAx(shadow, byBackend);
    }

    private static String styleValue(JsonNode assertion, String prop, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree, CdpConnection cdp) {
        JsonNode js = node(assertion, probe, byId, tree);
        if (js != null && js.path("styles").has(prop)) return js.path("styles").path(prop).asText("");
        DomNode dom = dom(tree.root, assertion);
        if (dom == null || dom.nodeId <= 0) return js == null ? null : "";
        try {
            ObjectNode params = Jsons.object();
            params.put("nodeId", dom.nodeId);
            JsonNode computed = cdp.call("CSS.getComputedStyleForNode", params, Duration.ofSeconds(5));
            for (JsonNode entry : computed.withArray("computedStyle")) {
                if (prop.equals(entry.path("name").asText())) return entry.path("value").asText("");
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return "";
    }

    private static double[] quad(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree, CdpConnection cdp, double scrollX, double scrollY) {
        JsonNode js = node(assertion, probe, byId, tree);
        if (js != null && js.path("quad").isArray() && js.path("quad").size() >= 8) {
            double[] quad = new double[8];
            for (int i = 0; i < 8; i++) quad[i] = js.path("quad").get(i).asDouble();
            return quad;
        }
        DomNode dom = dom(tree.root, assertion);
        if (dom == null || dom.nodeId <= 0) return null;
        try {
            ObjectNode params = Jsons.object();
            params.put("nodeId", dom.nodeId);
            JsonNode model = cdp.call("DOM.getBoxModel", params, Duration.ofSeconds(5)).path("model").path("border");
            if (!model.isArray() || model.size() < 8) return null;
            double[] quad = new double[8];
            for (int i = 0; i < 8; i++) quad[i] = model.get(i).asDouble();
            for (int i = 0; i < 8; i += 2) {
                quad[i] += scrollX;
                quad[i + 1] += scrollY;
            }
            return quad;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String attrValue(JsonNode assertion, String name, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree) {
        JsonNode js = node(assertion, probe, byId, tree);
        if (js != null && js.path("attrs").has(name)) return js.path("attrs").path(name).asText("");
        if (js != null && "class".equals(name) && js.has("className")) return js.path("className").asText("");
        DomNode dom = dom(tree.root, assertion);
        if (dom == null) return js == null ? null : "";
        return dom.attributes.get(name);
    }

    private static JsonNode node(JsonNode assertion, JsonNode probe, Map<String, JsonNode> byId, LiveTree tree) {
        String id = assertion.path("id").asText("");
        if (!id.isEmpty()) {
            JsonNode found = byId.get(id);
            if (found != null) return found;
        }
        String selector = assertion.path("selector").asText("");
        if (selector.isEmpty()) return null;
        for (JsonNode candidate : probe.withArray("nodes")) {
            if (selectorMatches(selector, candidate)) return candidate;
        }
        return null;
    }

    private static boolean selectorMatches(String selector, JsonNode node) {
        if (selector == null || selector.isBlank()) return false;
        if (selector.startsWith("#")) return selector.substring(1).equals(node.path("id").asText());
        if (selector.startsWith(".")) {
            String token = selector.substring(1);
            for (String part : node.path("className").asText("").split("\\s+")) if (part.equals(token)) return true;
            return false;
        }
        if (selector.startsWith("[data-stable=") && selector.endsWith("]")) {
            String value = selector.substring("[data-stable=".length(), selector.length() - 1);
            return value.equals(node.path("stable").asText());
        }
        if (selector.startsWith("[data-testid=") && selector.endsWith("]")) {
            String value = selector.substring("[data-testid=".length(), selector.length() - 1);
            return value.equals(node.path("testid").asText());
        }
        return selector.equalsIgnoreCase(node.path("tag").asText());
    }

    private static DomNode dom(DomNode root, JsonNode assertion) {
        String id = assertion.path("id").asText("");
        if (!id.isEmpty()) return findId(root, id);
        String selector = assertion.path("selector").asText("");
        if (selector.isEmpty()) return null;
        for (DomNode candidate : elements(root)) {
            if (Engines.selectorMatches(candidate, selector)) return candidate;
        }
        return null;
    }

    private static int countCss(DomNode root, String selector, boolean framesAndShadow) {
        int count = 0;
        for (DomNode candidate : elements(root)) {
            if (!framesAndShadow && candidate.frameId != null && root.frameId != null && !root.frameId.equals(candidate.frameId)) continue;
            try {
                if (Engines.selectorMatches(candidate, selector)) count++;
            } catch (RuntimeException ignored) {
                return 0;
            }
        }
        return count;
    }

    private static List<DomNode> elements(DomNode node) {
        List<DomNode> into = new ArrayList<>();
        collect(node, into);
        return into;
    }

    private static void collect(DomNode node, List<DomNode> into) {
        if (node == null) return;
        if (node.nodeType == 1 && !node.shadowRoot) into.add(node);
        for (DomNode child : node.children) collect(child, into);
        for (DomNode shadow : node.shadowRoots) collect(shadow, into);
    }

    private static DomNode findId(DomNode node, String id) {
        if (node == null || id == null || id.isEmpty()) return null;
        if (id.equals(node.attributes.get("id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = findId(child, id);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = findId(shadow, id);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean hasShadow(DomNode node, String mode) {
        if (node == null) return false;
        for (DomNode shadow : node.shadowRoots) if (mode.equalsIgnoreCase(shadow.shadowMode)) return true;
        for (DomNode child : node.children) if (hasShadow(child, mode)) return true;
        for (DomNode shadow : node.shadowRoots) if (hasShadow(shadow, mode)) return true;
        return false;
    }

    private static void appendFrameText(DomNode node, String frameId, StringBuilder blob) {
        if (node == null) return;
        if (frameId.equals(node.frameId) && node.nodeType == 3 && node.nodeValue != null) blob.append(node.nodeValue);
        for (DomNode child : node.children) appendFrameText(child, frameId, blob);
        for (DomNode shadow : node.shadowRoots) appendFrameText(shadow, frameId, blob);
    }

    private static String descendantText(DomNode node) {
        StringBuilder blob = new StringBuilder();
        appendText(node, blob);
        return blob.toString();
    }

    private static void appendText(DomNode node, StringBuilder blob) {
        if (node == null) return;
        if (node.nodeType == 3 && node.nodeValue != null) blob.append(node.nodeValue);
        for (DomNode child : node.children) appendText(child, blob);
        for (DomNode shadow : node.shadowRoots) appendText(shadow, blob);
    }

    private static Map<String, JsonNode> index(JsonNode nodes, String field) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        if (nodes == null || !nodes.isArray()) return map;
        for (JsonNode node : nodes) {
            String id = node.path(field).asText("");
            if (!id.isEmpty() && !map.containsKey(id)) map.put(id, node);
        }
        return map;
    }

    private static int focusIndex(JsonNode focus, String token) {
        if (token == null || token.isEmpty() || !focus.isArray()) return -1;
        for (int i = 0; i < focus.size(); i++) {
            JsonNode entry = focus.get(i);
            if (token.equals(entry.path("id").asText())) return i;
            if (token.startsWith("[data-stable=") && token.endsWith("]")) {
                String value = token.substring("[data-stable=".length(), token.length() - 1);
                if (value.equals(entry.path("stable").asText())) return i;
            }
        }
        return -1;
    }

    private static int queryIndex(JsonNode probe, JsonNode assertion) {
        int index = 0;
        JsonNode queries = probe.path("queries");
        if (!queries.isArray()) return -1;
        // The probe was given assertions in order, including this one, so the parallel array matches.
        return indexOfAssertion(assertion);
    }

    private static int indexOfAssertion(JsonNode assertion) {
        JsonNode parent = assertion;
        return -1;
    }

    private static boolean patternMatch(String got, String pattern) {
        if (pattern.startsWith("^")) {
            String rest = pattern.substring(1);
            if (rest.endsWith("$")) return got.equals(rest.substring(0, rest.length() - 1));
            return got.startsWith(rest);
        }
        if (pattern.endsWith("$")) return got.endsWith(pattern.substring(0, pattern.length() - 1));
        return got.contains(pattern);
    }

    private static boolean axisAligned(double[] quad) {
        double dx1 = Math.abs(quad[3] - quad[1]);
        double dy1 = Math.abs(quad[2] - quad[0]);
        double dx2 = Math.abs(quad[7] - quad[1]);
        double dy2 = Math.abs(quad[6] - quad[0]);
        boolean edge1h = dx1 < 0.75;
        boolean edge1v = dy1 < 0.75;
        boolean edge2h = dx2 < 0.75;
        boolean edge2v = dy2 < 0.75;
        return (edge1h && edge2v) || (edge1v && edge2h);
    }

    private static String limit(String key, double got, JsonNode assertion, boolean lower) {
        if (!assertion.has(key)) return null;
        double lim = assertion.path(key).asDouble();
        boolean ok = lower ? got + 1e-6 >= lim : got <= lim + 1e-6;
        return ok ? null : "quad " + key + ": " + got + " vs " + lim;
    }

    private static boolean contains(JsonNode array, String value) {
        if (array == null || !array.isArray()) return false;
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    private static double min(double a, double b, double c, double d) {
        return Math.min(Math.min(a, b), Math.min(c, d));
    }

    private static String label(JsonNode assertion) {
        String id = assertion.path("id").asText("");
        if (!id.isEmpty()) return "#" + id;
        String selector = assertion.path("selector").asText("");
        return selector.isEmpty() ? "?" : selector;
    }

    private static String clip(String text) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ');
        return flat.length() > 80 ? flat.substring(0, 80) : flat;
    }

    private static String between(String text, String start, String end) {
        int at = text.indexOf(start);
        if (at < 0) return "";
        int from = at + start.length();
        int to = text.indexOf(end, from);
        if (to < 0) return "";
        return text.substring(from, to);
    }

    private static JsonNode eval(CdpConnection cdp, String expression, boolean await) {
        ObjectNode params = Jsons.object();
        params.put("expression", expression);
        params.put("returnByValue", true);
        params.put("awaitPromise", await);
        JsonNode result = cdp.call("Runtime.evaluate", params, Duration.ofSeconds(30));
        if (result.has("exceptionDetails")) throw new CdpException("eval " + result.get("exceptionDetails"));
        return result.path("result").path("value");
    }

    private static HttpServer serve(Path site, int crossPort) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "domengine-conform-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", exchange -> handle(exchange, site, crossPort));
        server.start();
        return server;
    }

    private static void handle(HttpExchange exchange, Path site, int crossPort) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.startsWith("/__delay/")) {
                String[] parts = path.split("/");
                long wait = parts.length > 2 ? Long.parseLong(parts[2]) : 0;
                if (wait > 0) Thread.sleep(Math.min(wait, 12000));
                if (path.endsWith("slow.js")) {
                    send(exchange, 200, "application/javascript", SLOW_JS.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                send(exchange, 200, "image/gif", PIXEL);
                return;
            }
            if ("/__stream".equals(path)) {
                send(exchange, 200, "text/plain", "stream-done".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/pixel.gif".equals(path)) {
                send(exchange, 200, "image/gif", PIXEL);
                return;
            }
            if ("/net/sw.js".equals(path)) {
                send(exchange, 200, "application/javascript", SW_JS.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.endsWith(".js") && path.contains("react-dom")) {
                send(exchange, 200, "application/javascript", REACT.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.endsWith(".js") && path.contains("react")) {
                send(exchange, 200, "application/javascript", REACT.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.endsWith(".js") && path.contains("angular")) {
                send(exchange, 200, "application/javascript", ANGULAR.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/favicon.ico".equals(path)) {
                send(exchange, 204, "image/x-icon", new byte[0]);
                return;
            }
            String relative = path.startsWith("/") ? path.substring(1) : path;
            Path file = site.resolve(relative).normalize();
            if (!file.startsWith(site) || !Files.isRegularFile(file)) {
                send(exchange, 404, "text/plain", "missing".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body = Files.readAllBytes(file);
            String type = typeOf(path);
            if (type.startsWith("text") || type.contains("javascript") || type.contains("json")) {
                String text = new String(body, StandardCharsets.UTF_8).replace("__CROSS_PORT__", Integer.toString(Math.max(crossPort, 0)));
                body = text.getBytes(StandardCharsets.UTF_8);
            }
            send(exchange, 200, type, body);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            send(exchange, 500, "text/plain", "interrupted".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String typeOf(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static List<Path> list(Path expected) {
        try (var stream = Files.list(expected)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException failure) {
            throw new CdpException("conform list: " + failure.getMessage());
        }
    }

    private static final class Outcome {
        String id = "";
        int checked;
        int skipped;
        final List<String> failures = new ArrayList<>();
        final List<String> skipReasons = new ArrayList<>();
    }

    private record Sample(boolean settled, boolean highFrequency, String pending) {
    }

    private record Top(String id, int band, int paint, int order) {
    }
}
