// Pickleball page runtime: keeps agent placements on their targets.
// Runs once per document, in the pkb-surface isolated world when the session has CDP or BiDi.
// It moves light-DOM placements with the CSS `translate` property, so the inline left/top that
// Java wrote (the resolved box) stay as reported. Rings live in a shadow-root layer.
(function () {
  'use strict';
  if (typeof window === 'undefined' || typeof document === 'undefined' || !document.documentElement) return;
  var VERSION = '3.0.0-p2';
  if (window.__pkbUi && window.__pkbUi.version === VERSION) return;
  if (window.__pkbUi && typeof window.__pkbUi.dispose === 'function') {
    try { window.__pkbUi.dispose(); } catch (ignored) { /* older runtime */ }
  }

  var FONT = 'system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif';
  var LIGHT = '--pkb-bg:#ffffff;--pkb-fg:#111827;--pkb-muted:#6b7280;--pkb-line:rgba(17,24,39,.12);'
    + '--pkb-accent:#3b82f6;--pkb-shadow:0 8px 24px rgba(15,23,42,.16),0 1px 3px rgba(15,23,42,.12);';
  var DARK = '--pkb-bg:#1f2937;--pkb-fg:#f3f4f6;--pkb-muted:#9ca3af;--pkb-line:rgba(255,255,255,.16);'
    + '--pkb-accent:#60a5fa;--pkb-shadow:0 8px 24px rgba(0,0,0,.55),0 1px 3px rgba(0,0,0,.4);';
  var DOC_CSS = ''
    + '[data-pkb-agent][data-pkb-ui]{' + LIGHT + '}'
    + '@media (prefers-color-scheme: dark){[data-pkb-agent][data-pkb-ui]:not([data-pkb-theme=light]){' + DARK + '}}'
    + '[data-pkb-agent][data-pkb-ui][data-pkb-theme=dark]{' + DARK + '}'
    + '[data-pkb-ui][data-pkb-kind=overlay]{background:var(--pkb-bg)!important;color:var(--pkb-fg)!important;'
    + 'border:1px solid var(--pkb-line)!important;border-left:3px solid var(--pkb-accent)!important;border-radius:8px!important;'
    + 'box-shadow:var(--pkb-shadow)!important;padding:8px 12px!important;font:13px/1.45 ' + FONT + '!important;'
    + 'box-sizing:border-box;transition:opacity .14s ease-out;}'
    + '[data-pkb-ui][data-pkb-kind=overlay][data-pkb-at=over]{background:color-mix(in srgb,var(--pkb-bg) 80%,transparent)!important;pointer-events:none!important;}'
    + '[data-pkb-ui][data-pkb-at=over] :is(a,button,input,select,textarea,[data-pkb-action]){pointer-events:auto;}'
    + '[data-pkb-ui][data-pkb-kind=overlay] button{font:inherit;border:0;background:var(--pkb-accent);color:#fff;border-radius:6px;padding:3px 10px;margin:4px 4px 0 0;cursor:pointer;}'
    + '[data-pkb-ui][data-pkb-kind=label]{background:#111827!important;color:#fff!important;border-radius:6px!important;'
    + 'font:600 12px/1.3 ' + FONT + '!important;padding:3px 8px!important;box-shadow:0 2px 8px rgba(0,0,0,.25);pointer-events:none;white-space:nowrap;}'
    + '[data-pkb-ui][data-pkb-kind=border]{border-radius:6px;}'
    + '[data-pkb-ui][data-pkb-kind=magnifier]{box-shadow:var(--pkb-shadow);pointer-events:none;}'
    + '[data-pkb-ui][data-pkb-kind=step],[data-pkb-ui][data-pkb-kind=arrow],[data-pkb-ui][data-pkb-kind=spotlight]{pointer-events:none;}'
    + '@media (prefers-reduced-motion: reduce){[data-pkb-ui]{transition:none!important;}}';
  var LAYER_CSS = ''
    + ':host{all:initial;--pkb-accent:#3b82f6;}'
    + '@media (prefers-color-scheme: dark){:host{--pkb-accent:#60a5fa;}}'
    + '.ring{position:fixed;left:0;top:0;box-sizing:border-box;border-radius:6px;pointer-events:none;'
    + 'box-shadow:0 0 0 2px var(--pkb-accent),0 0 0 6px rgba(59,130,246,.22);background:transparent;'
    + 'transition:opacity .14s ease-out;will-change:transform;}'
    + '@media (prefers-reduced-motion: reduce){.ring{transition:none;}}';

  var items = new Map();
  var layer = null;
  var shadow = null;
  var docSheet = null;
  var frame = 0;
  var listening = false;
  var paused = false;
  var scrollersDirty = false;
  var resizeObserver = null;
  var mutationObserver = null;
  var handlers = [];

  function num(value) {
    var parsed = parseFloat(value);
    return isFinite(parsed) ? parsed : 0;
  }

  function on(target, type, fn, options) {
    if (!target || !target.addEventListener) return;
    target.addEventListener(type, fn, options);
    handlers.push([target, type, fn, options]);
  }

  function events() {
    window.__pkbEvents = window.__pkbEvents || [];
    return window.__pkbEvents;
  }

  function installDocSheet() {
    if (docSheet) return;
    try {
      var sheet = new CSSStyleSheet();
      sheet.replaceSync(DOC_CSS);
      document.adoptedStyleSheets = Array.prototype.concat.call([], Array.from(document.adoptedStyleSheets || []), [sheet]);
      docSheet = sheet;
    } catch (unsupported) {
      docSheet = 'none';
    }
  }

  function ensureLayer() {
    if (layer && layer.isConnected) return shadow;
    if (!layer) {
      layer = document.createElement('pkb-layer');
      layer.setAttribute('data-pkb-layer', 'agent_injected');
      layer.setAttribute('aria-hidden', 'true');
      layer.style.cssText = 'position:fixed;inset:0;width:100vw;height:100vh;max-width:none;max-height:none;margin:0;padding:0;'
        + 'border:0;background:transparent;overflow:visible;pointer-events:none;z-index:2147483647;display:block;';
      shadow = layer.attachShadow({mode: 'open'});
      try {
        var sheet = new CSSStyleSheet();
        sheet.replaceSync(LAYER_CSS);
        shadow.adoptedStyleSheets = [sheet];
      } catch (unsupported) {
        var style = document.createElement('style');
        style.textContent = LAYER_CSS;
        shadow.appendChild(style);
      }
    }
    document.documentElement.appendChild(layer);
    raiseLayer();
    return shadow;
  }

  // The top layer keeps rings above a modal <dialog>; the newest top-layer entry paints last.
  function raiseLayer() {
    if (!layer || !layer.isConnected || typeof layer.showPopover !== 'function') return;
    try {
      if (!layer.hasAttribute('popover')) layer.setAttribute('popover', 'manual');
      if (layer.matches(':popover-open')) layer.hidePopover();
      layer.showPopover();
    } catch (ignored) { /* z-index fallback */ }
  }

  function ours(node) {
    var el = node && node.nodeType === 1 ? node : (node && node.parentElement);
    return !!(el && el.closest && el.closest('[data-pkb-agent],[data-pkb-layer]'));
  }

  function scrollable(el) {
    var style = window.getComputedStyle(el);
    var o = style.overflow + ' ' + style.overflowX + ' ' + style.overflowY;
    return /(auto|scroll|hidden|clip)/.test(o);
  }

  function scrollersOf(el) {
    var list = [];
    var at = el ? el.parentElement : null;
    while (at && at !== document.documentElement && at !== document.body) {
      if (scrollable(at)) list.push(at);
      at = at.parentElement || (at.getRootNode && at.getRootNode().host) || null;
    }
    return list;
  }

  function cssPath(el) {
    var parts = [];
    var at = el;
    while (at && at.nodeType === 1 && at !== document.documentElement && parts.length < 12) {
      if (at.id) { parts.unshift('#' + CSS.escape(at.id)); break; }
      var tag = at.nodeName.toLowerCase();
      var parent = at.parentElement;
      if (!parent) { parts.unshift(tag); break; }
      var same = Array.prototype.filter.call(parent.children, function (c) { return c.nodeName === at.nodeName; });
      parts.unshift(same.length > 1 ? tag + ':nth-of-type(' + (same.indexOf(at) + 1) + ')' : tag);
      at = parent;
    }
    return parts.join('>');
  }

  function locatorOf(el) {
    return {
      id: el.id || '',
      handle: el.getAttribute('data-pkb-handle') || '',
      name: el.getAttribute('name') || '',
      tag: el.nodeName.toLowerCase(),
      path: cssPath(el),
      text: ((el.innerText || el.textContent || '') + '').trim().slice(0, 80)
    };
  }

  function relocate(loc) {
    var found = null;
    try {
      if (loc.id) found = document.getElementById(loc.id);
      if (!found && loc.handle) found = document.querySelector('[data-pkb-handle="' + CSS.escape(loc.handle) + '"]');
      if (!found && loc.name) found = document.querySelector(loc.tag + '[name="' + CSS.escape(loc.name) + '"]');
      if (!found && loc.path) {
        var byPath = document.querySelector(loc.path);
        if (byPath && byPath.nodeName.toLowerCase() === loc.tag) found = byPath;
      }
      if (!found && loc.text) {
        var all = document.querySelectorAll(loc.tag);
        for (var i = 0; i < all.length && !found; i++) {
          if (!ours(all[i]) && ((all[i].innerText || all[i].textContent || '') + '').trim().slice(0, 80) === loc.text) found = all[i];
        }
      }
    } catch (ignored) { found = null; }
    return found && !ours(found) ? found : null;
  }

  function viewport() {
    var vv = window.visualViewport;
    return {
      w: document.documentElement.clientWidth || window.innerWidth,
      h: document.documentElement.clientHeight || window.innerHeight,
      vw: vv ? vv.width : window.innerWidth
    };
  }

  function visibleRect(item, r, vp) {
    var v = {l: Math.max(r.left, 0), t: Math.max(r.top, 0), r: Math.min(r.right, vp.w), b: Math.min(r.bottom, vp.h)};
    for (var i = 0; i < item.scrollers.length; i++) {
      var s = item.scrollers[i];
      if (!s.isConnected) continue;
      var sr = s.getBoundingClientRect();
      // A CSS transform on an ancestor scales the box but not clientWidth/clientHeight.
      var sx = s.offsetWidth ? sr.width / s.offsetWidth : 1;
      var sy = s.offsetHeight ? sr.height / s.offsetHeight : 1;
      var l = sr.left + s.clientLeft * sx;
      var t = sr.top + s.clientTop * sy;
      v.l = Math.max(v.l, l);
      v.t = Math.max(v.t, t);
      v.r = Math.min(v.r, l + s.clientWidth * sx);
      v.b = Math.min(v.b, t + s.clientHeight * sy);
    }
    v.empty = v.r <= v.l || v.b <= v.t || (r.width === 0 && r.height === 0);
    v.full = !v.empty && v.l <= r.left + 0.5 && v.t <= r.top + 0.5 && v.r >= r.right - 0.5 && v.b >= r.bottom - 0.5;
    return v;
  }

  function clamp(value, low, high) {
    if (high < low) return low;
    return Math.min(Math.max(value, low), high);
  }

  function beside(r, w, h, at, vp) {
    var gap = 10;
    var order = ['right', 'left', 'bottom', 'top'];
    if (at && order.indexOf(at) >= 0) order = [at].concat(order.filter(function (o) { return o !== at; }));
    var spots = {
      right: {x: r.right + gap, y: clamp(r.top, 4, vp.h - h - 4), fits: r.right + gap + w <= vp.w - 4},
      left: {x: r.left - gap - w, y: clamp(r.top, 4, vp.h - h - 4), fits: r.left - gap - w >= 4},
      bottom: {x: clamp(r.left, 4, vp.w - w - 4), y: r.bottom + gap, fits: r.bottom + gap + h <= vp.h - 4},
      top: {x: clamp(r.left, 4, vp.w - w - 4), y: r.top - gap - h, fits: r.top - gap - h >= 4}
    };
    for (var i = 0; i < order.length; i++) {
      if (spots[order[i]].fits) return {x: spots[order[i]].x, y: spots[order[i]].y, side: order[i]};
    }
    return {x: spots[order[0]].x, y: spots[order[0]].y, side: order[0]};
  }

  function onTarget(kind, at) {
    return kind === 'border' || kind === 'spotlight' || (kind === 'overlay' && at === 'over');
  }

  function desired(item, r, w, h, vp) {
    var kind = item.kind;
    if (onTarget(kind, item.at)) return {x: r.left, y: r.top};
    if (kind === 'overlay') return beside(r, w, h, item.at === 'auto' ? '' : item.at, vp);
    if (kind === 'label') {
      var above = r.top - h - 4;
      return {x: r.left, y: above >= 0 ? above : r.bottom + 4};
    }
    if (kind === 'step') {
      var left = r.left - w - 6;
      if (left >= 0) return {x: left, y: r.top + (r.height - h) / 2};
      var top = r.top - h - 4;
      return {x: r.left, y: top >= 0 ? top : r.bottom + 4};
    }
    if (kind === 'magnifier') return beside(r, w, h, 'right', vp);
    return {x: item.baseL + (r.left - item.origin.l), y: item.baseT + (r.top - item.origin.t)};
  }

  function sanitizeClone(anchor, zoom) {
    var clone = anchor.cloneNode(true);
    clone.querySelectorAll('script').forEach(function (s) { s.remove(); });
    [clone].concat(Array.from(clone.querySelectorAll('*'))).forEach(function (el) {
      Array.from(el.attributes || []).forEach(function (a) {
        var n = a.name.toLowerCase();
        if (n === 'id' || n === 'name' || n === 'for' || n === 'form' || n === 'tabindex' || n.indexOf('on') === 0) el.removeAttribute(a.name);
      });
    });
    clone.setAttribute('inert', '');
    clone.setAttribute('aria-hidden', 'true');
    clone.style.transform = 'scale(' + zoom + ')';
    clone.style.transformOrigin = '0 0';
    clone.style.pointerEvents = 'none';
    return clone;
  }

  function refreshClone(item) {
    var old = item.host.firstElementChild;
    if (!old) return;
    var zoom = (/scale\(([0-9.]+)\)/.exec(old.style.transform || '') || [])[1] || '2';
    var clone = sanitizeClone(item.anchor, zoom);
    var kind = item.host.getAttribute('data-pkb-kind');
    var sentence = item.host.getAttribute('data-pkb-sentence');
    [clone].concat(Array.from(clone.querySelectorAll('*'))).forEach(function (el) {
      el.setAttribute('data-pkb-agent', 'agent_injected');
      el.setAttribute('data-pkb-kind', kind);
      el.setAttribute('data-pkb-sentence', sentence);
      el.setAttribute('data-pkb-id', item.id);
    });
    old.replaceWith(clone);
  }

  function makeRing(id) {
    var root = ensureLayer();
    var ring = document.createElement('div');
    ring.className = 'ring';
    ring.setAttribute('data-ring', id);
    root.appendChild(ring);
    return ring;
  }

  function observe(item) {
    if (!resizeObserver) return;
    try {
      resizeObserver.observe(item.anchor);
      item.scrollers.forEach(function (s) { resizeObserver.observe(s); });
    } catch (ignored) { /* detached */ }
  }

  function setStyle(el, cache, key, value) {
    if (cache[key] === value) return;
    cache[key] = value;
    el.style[key] = value;
  }

  function clipFor(r, v) {
    if (v.full || v.empty) return '';
    return 'inset(' + Math.max(0, v.t - r.top) + 'px ' + Math.max(0, r.right - v.r) + 'px '
      + Math.max(0, r.bottom - v.b) + 'px ' + Math.max(0, v.l - r.left) + 'px)';
  }

  function update() {
    frame = 0;
    if (scrollersDirty) {
      scrollersDirty = false;
      items.forEach(function (item) {
        if (item.anchor && item.anchor.isConnected) item.scrollers = scrollersOf(item.anchor);
      });
    }
    var vp = viewport();
    var plans = [];
    var gone = [];
    // Read every rect first, then write, so the frame does not thrash layout.
    items.forEach(function (item) {
      if (!item.host.isConnected) { gone.push(item.id); return; }
      var relocated = false;
      if (!item.anchor || !item.anchor.isConnected) {
        var next = relocate(item.locator);
        if (next) {
          item.anchor = next;
          item.scrollers = scrollersOf(next);
          observe(item);
          relocated = true;
          if (item.lost) events().push({type: 'found', id: item.id});
          item.lost = false;
        } else {
          if (!item.lost) events().push({type: 'lost', id: item.id});
          item.lost = true;
          plans.push({item: item, hidden: true});
          return;
        }
      }
      var r = item.anchor.getBoundingClientRect();
      var v = visibleRect(item, r, vp);
      var w = item.host.offsetWidth;
      var h = item.host.offsetHeight;
      plans.push({item: item, r: r, v: v, pos: desired(item, r, w, h, vp), hidden: v.empty, relocated: relocated});
    });
    gone.forEach(untrack);
    plans.forEach(function (plan) {
      var item = plan.item;
      var c = item.cache;
      if (plan.hidden) {
        setStyle(item.host, c, 'visibility', 'hidden');
        item.host.setAttribute('data-pkb-hidden', '');
        if (item.ring) item.ring.style.visibility = 'hidden';
        return;
      }
      setStyle(item.host, c, 'visibility', '');
      item.host.removeAttribute('data-pkb-hidden');
      var r = plan.r;
      var dx = Math.round(plan.pos.x - item.baseL);
      var dy = Math.round(plan.pos.y - item.baseT);
      setStyle(item.host, c, 'translate', dx === 0 && dy === 0 ? '' : dx + 'px ' + dy + 'px');
      if (onTarget(item.kind, item.at)) {
        if (item.sizeW > 0 && Math.abs(r.width - item.sizeW) > 1) setStyle(item.host, c, 'width', Math.round(r.width) + 'px');
        if (item.sizeH > 0 && Math.abs(r.height - item.sizeH) > 1) setStyle(item.host, c, 'height', Math.round(r.height) + 'px');
        if (item.kind !== 'spotlight') setStyle(item.host, c, 'clipPath', clipFor(r, plan.v));
      }
      if (item.kind === 'magnifier' && (item.cloneDirty || plan.relocated)) {
        item.cloneDirty = false;
        refreshClone(item);
      }
      if (item.ring) {
        if (!item.ring.isConnected) ensureLayer().appendChild(item.ring);
        var rc = item.ringCache;
        setStyle(item.ring, rc, 'visibility', '');
        setStyle(item.ring, rc, 'transform', 'translate3d(' + Math.round(r.left) + 'px,' + Math.round(r.top) + 'px,0)');
        setStyle(item.ring, rc, 'width', Math.round(r.width) + 'px');
        setStyle(item.ring, rc, 'height', Math.round(r.height) + 'px');
        setStyle(item.ring, rc, 'clipPath', clipFor(r, plan.v));
      }
    });
    if (items.size === 0 && layer && layer.isConnected) layer.remove();
  }

  function schedule() {
    if (frame || paused || items.size === 0) return;
    frame = window.requestAnimationFrame ? window.requestAnimationFrame(update) : window.setTimeout(update, 16);
  }

  function onMutations(records) {
    var relevant = false;
    var dialog = false;
    for (var i = 0; i < records.length; i++) {
      var rec = records[i];
      if (rec.type === 'childList') {
        var foreign = Array.prototype.some.call(rec.addedNodes, function (n) { return !ours(n); })
          || Array.prototype.some.call(rec.removedNodes, function (n) { return !(n.nodeType === 1 && n.hasAttribute && (n.hasAttribute('data-pkb-agent') || n.hasAttribute('data-pkb-layer'))); });
        if (!foreign) continue;
        Array.prototype.forEach.call(rec.addedNodes, function (n) {
          if (n.nodeName === 'DIALOG' || (n.querySelector && n.querySelector('dialog[open]'))) dialog = true;
        });
      } else if (ours(rec.target)) {
        continue;
      }
      if (rec.attributeName === 'open' && rec.target.nodeName === 'DIALOG') dialog = true;
      relevant = true;
      items.forEach(function (item) {
        if (item.kind === 'magnifier' && item.anchor && (item.anchor === rec.target || item.anchor.contains(rec.target))) item.cloneDirty = true;
      });
    }
    if (dialog) raiseLayer();
    if (relevant) {
      scrollersDirty = true;
      schedule();
    }
  }

  function listen() {
    if (listening) return;
    listening = true;
    on(document, 'scroll', schedule, {capture: true, passive: true});
    on(window, 'resize', schedule, {passive: true});
    if (window.visualViewport) {
      on(window.visualViewport, 'resize', schedule);
      on(window.visualViewport, 'scroll', schedule);
    }
    on(window, 'popstate', schedule);
    on(window, 'hashchange', schedule);
    on(window, 'pageshow', schedule);
    on(document, 'transitionend', schedule, true);
    on(document, 'animationend', schedule, true);
    on(document, 'toggle', function () { raiseLayer(); schedule(); }, true);
    on(document, 'visibilitychange', function () {
      paused = document.hidden;
      if (!paused) schedule();
    });
    if (window.ResizeObserver) resizeObserver = new ResizeObserver(schedule);
    if (window.MutationObserver) {
      mutationObserver = new MutationObserver(onMutations);
      mutationObserver.observe(document.documentElement, {
        childList: true, subtree: true, characterData: true,
        attributes: true, attributeFilter: ['class', 'style', 'hidden', 'open']
      });
    }
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(schedule, function () {});
  }

  function untrack(id) {
    var item = items.get(id);
    if (!item) return false;
    items.delete(id);
    if (item.ring) item.ring.remove();
    if (resizeObserver && item.anchor) {
      try { resizeObserver.unobserve(item.anchor); } catch (ignored) { /* detached */ }
    }
    return true;
  }

  function track(host, anchor, spec) {
    if (!host || !anchor || host.nodeType !== 1 || anchor.nodeType !== 1) return false;
    spec = spec || {};
    var id = spec.id || host.getAttribute('data-pkb-id');
    if (!id) return false;
    listen();
    installDocSheet();
    untrack(id);
    var at = spec.at || 'auto';
    host.setAttribute('data-pkb-ui', '');
    host.setAttribute('data-pkb-at', at);
    if (spec.theme === 'light' || spec.theme === 'dark') host.setAttribute('data-pkb-theme', spec.theme);
    var r = anchor.getBoundingClientRect();
    var item = {
      id: id, host: host, anchor: anchor, kind: spec.kind || host.getAttribute('data-pkb-kind') || '', at: at,
      baseL: num(host.style.left), baseT: num(host.style.top),
      sizeW: num(host.style.width), sizeH: num(host.style.height),
      origin: {l: r.left, t: r.top}, locator: locatorOf(anchor), scrollers: scrollersOf(anchor),
      cache: {}, ringCache: {}, lost: false, cloneDirty: false
    };
    if (item.kind === 'overlay' && at !== 'over') item.ring = makeRing(id);
    items.set(id, item);
    observe(item);
    update();
    return true;
  }

  function prune() {
    items.forEach(function (item, id) { if (!item.host.isConnected) untrack(id); });
    if (items.size === 0 && layer && layer.isConnected) layer.remove();
  }

  function clear() {
    Array.from(items.keys()).forEach(untrack);
    if (layer && layer.isConnected) layer.remove();
  }

  function state() {
    var out = [];
    items.forEach(function (item) {
      out.push({id: item.id, kind: item.kind, at: item.at, lost: item.lost,
        hidden: item.host.style.visibility === 'hidden', translate: item.host.style.translate || ''});
    });
    return out;
  }

  function dispose() {
    clear();
    handlers.forEach(function (h) { h[0].removeEventListener(h[1], h[2], h[3]); });
    handlers = [];
    if (mutationObserver) mutationObserver.disconnect();
    if (resizeObserver) resizeObserver.disconnect();
    listening = false;
  }

  window.__pkbUi = {
    version: VERSION,
    track: track,
    untrack: untrack,
    prune: prune,
    clear: clear,
    refresh: update,
    state: state,
    dispose: dispose,
    raise: raiseLayer
  };
})();
