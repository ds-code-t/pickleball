// Pickleball page runtime: keeps agent placements on their targets.
// Runs once per document, in the pkb-surface isolated world when the session has CDP or BiDi.
// It moves light-DOM placements with the CSS `translate` property, so the inline left/top that
// Java wrote (the resolved box) stay as reported. Rings live in a shadow-root layer.
(function () {
  'use strict';
  if (typeof window === 'undefined' || typeof document === 'undefined' || !document.documentElement) return;
  var VERSION = '3.0.0-p4';
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
    + '.ring.todo{box-shadow:0 0 0 1px rgba(59,130,246,.45);}'
    + '.ring.done{box-shadow:0 0 0 2px rgba(34,160,107,.75);}'
    + '.ring.pulse{animation:pkb-pulse .9s ease-out 1;}'
    + '@keyframes pkb-pulse{0%{box-shadow:0 0 0 2px var(--pkb-accent),0 0 0 0 rgba(59,130,246,.55);}100%{box-shadow:0 0 0 2px var(--pkb-accent),0 0 0 14px rgba(59,130,246,0);}}'
    + '.badge{position:fixed;left:0;top:0;min-width:22px;height:22px;padding:0 6px;border-radius:11px;display:flex;align-items:center;justify-content:center;'
    + 'font:700 12px/1 ' + FONT + ';box-sizing:border-box;pointer-events:none;background:#fff;color:var(--pkb-accent);border:2px solid var(--pkb-accent);box-shadow:0 2px 6px rgba(0,0,0,.2);transition:opacity .14s ease-out;}'
    + '.badge.current{background:var(--pkb-accent);color:#fff;}'
    + '.badge.done{background:#22a06b;border-color:#22a06b;color:#fff;}'
    + '.badge.todo{opacity:.55;}'
    + '.tip,.ask{position:fixed;left:0;top:0;max-width:280px;box-sizing:border-box;padding:8px 12px;border-radius:8px;font:13px/1.45 ' + FONT + ';'
    + 'background:#fff;color:#111827;border:1px solid rgba(17,24,39,.12);border-left:3px solid var(--pkb-accent);box-shadow:0 8px 24px rgba(15,23,42,.16),0 1px 3px rgba(15,23,42,.12);}'
    + '.tip{pointer-events:none;}'
    + '.tip b,.ask b{font-weight:700;}'
    + '.ask{pointer-events:auto;display:flex;flex-direction:column;gap:6px;}'
    + '.ask input{font:inherit;padding:5px 8px;border-radius:6px;border:1px solid rgba(17,24,39,.2);}'
    + '.ask .row{display:flex;gap:6px;flex-wrap:wrap;}'
    + '.ask button{font:inherit;border:0;border-radius:6px;padding:4px 12px;background:var(--pkb-accent);color:#fff;cursor:pointer;}'
    + '.ask button.secondary{background:rgba(17,24,39,.08);color:inherit;}'
    + '@media (prefers-color-scheme: dark){.tip,.ask{background:#1f2937;color:#f3f4f6;border-color:rgba(255,255,255,.16);}.badge{background:#1f2937;}.ask input{background:#111827;color:#f3f4f6;border-color:rgba(255,255,255,.2);}}'
    + '@media (prefers-reduced-motion: reduce){.ring,.badge{transition:none;animation:none;}}';

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
    ring.setAttribute('aria-hidden', 'true');
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
    if (panel) panelPlace();
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
    guidesUpdate(vp);
    asksUpdate(vp);
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
    if (idle() && layer && layer.isConnected) layer.remove();
  }

  function schedule() {
    if (frame || paused || idle()) return;
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
    if (idle() && layer && layer.isConnected) layer.remove();
  }

  function clear() {
    Array.from(items.keys()).forEach(untrack);
    Array.from(guides.keys()).forEach(guideClose);
    Array.from(asks.keys()).forEach(askClose);
    if (idle() && layer && layer.isConnected) layer.remove();
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



  // ---- Guided steps and questions ----
  var guides = new Map();
  var asks = new Map();
  var guideListening = false;

  function idle() {
    return items.size === 0 && !panel && guides.size === 0 && asks.size === 0;
  }

  function findTarget(t) {
    if (!t) return null;
    var text = String(t);
    try {
      if (text.indexOf('text:') === 0) {
        var want = text.slice(5).trim();
        var all = document.querySelectorAll('button,a,label,input,select,textarea,[role],h1,h2,h3,p,span,div,li,td');
        for (var i = 0; i < all.length; i++) {
          if (ours(all[i])) continue;
          var own = ((all[i].innerText || all[i].value || '') + '').trim();
          if (own === want) return all[i];
        }
        return null;
      }
      if (text.indexOf('role:') === 0) {
        var m = /^role:([a-z]+)(?:\[name=(.+)\])?$/.exec(text);
        if (!m) return null;
        var role = m[1];
        var name = m[2] ? m[2].replace(/^["']|["']$/g, '') : null;
        var implicit = {button: 'button,input[type=button],input[type=submit]', link: 'a[href]', textbox: 'input:not([type]),input[type=text],input[type=email],input[type=password],textarea', checkbox: 'input[type=checkbox]', combobox: 'select'};
        var list = document.querySelectorAll('[role="' + role + '"]' + (implicit[role] ? ',' + implicit[role] : ''));
        for (var j = 0; j < list.length; j++) {
          if (ours(list[j])) continue;
          var label = (list[j].getAttribute('aria-label') || list[j].innerText || list[j].value || (list[j].labels && list[j].labels[0] && list[j].labels[0].innerText) || '').trim();
          if (name == null || label === name) return list[j];
        }
        return null;
      }
      if (text.indexOf('x:') === 0) {
        return document.evaluate(text.slice(2), document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
      }
      if (text.indexOf('h:') === 0) text = text.slice(2);
      var byId = /^[A-Za-z][\w-]*$/.test(text) ? document.getElementById(text) : null;
      if (byId) return byId;
      var marked = document.querySelector('[data-pkb-handle="' + CSS.escape(text) + '"]');
      if (marked) return marked;
      return document.querySelector(text);
    } catch (bad) {
      return null;
    }
  }

  function sensitive(el, step) {
    if (step && step.sensitive) return true;
    if (!el || !el.getAttribute) return false;
    var type = (el.getAttribute('type') || '').toLowerCase();
    var auto = (el.getAttribute('autocomplete') || '').toLowerCase();
    return type === 'password' || auto.indexOf('password') >= 0 || auto.indexOf('cc-') >= 0 || auto.indexOf('one-time-code') >= 0;
  }

  function tipText(node, n, say) {
    node.textContent = '';
    var b = document.createElement('b');
    b.textContent = n + '. ';
    node.appendChild(b);
    node.appendChild(document.createTextNode(String(say || '')));
  }

  function guideRender(g) {
    var root = ensureLayer();
    g.steps.forEach(function (step, i) {
      if (!step.badge) {
        step.badge = el('div', 'badge');
        step.badge.setAttribute('aria-hidden', 'true');
        step.ring = el('div', 'ring');
        step.ring.setAttribute('aria-hidden', 'true');
        root.appendChild(step.ring);
        root.appendChild(step.badge);
      }
      var state = step.done ? 'done' : (i === g.current ? 'current' : 'todo');
      step.badge.className = 'badge ' + state;
      step.badge.textContent = step.done ? '\u2713' : String(step.n);
      step.ring.className = 'ring ' + state + (i === g.current && g.pulse ? ' pulse' : '');
      step.state = state;
    });
    g.pulse = false;
    if (!g.tip) {
      g.tip = el('div', 'tip');
      g.tip.setAttribute('role', 'status');
      root.appendChild(g.tip);
    }
    var cur = g.steps[g.current];
    if (cur) tipText(g.tip, cur.n, cur.say);
    g.tip.hidden = !cur;
    stepsList();
    window.__pkbGuideState = guideState();
  }

  function guideState() {
    var out = [];
    guides.forEach(function (g) {
      out.push({id: g.id, current: g.current < g.steps.length ? g.steps[g.current].n : null, done: g.steps.filter(function (s) { return s.done; }).map(function (s) { return s.n; }), total: g.steps.length});
    });
    return out;
  }

  function stepsList() {
    if (!panel) return;
    var pane = panel.stepsPane;
    pane.textContent = '';
    if (!guides.size) { pane.appendChild(el('div', 'pkb-empty', 'No steps yet.')); return; }
    guides.forEach(function (g) {
      var list = el('ol', 'pkb-steps');
      g.steps.forEach(function (step) {
        var row = el('li', 'pkb-step');
        row.setAttribute('data-state', step.state || 'todo');
        row.setAttribute('data-step', String(step.n));
        row.appendChild(el('span', 'pkb-num', step.done ? '\u2713' : String(step.n)));
        row.appendChild(el('span', '', String(step.say || '')));
        list.appendChild(row);
      });
      pane.appendChild(list);
    });
  }

  function placeAt(node, x, y) {
    node.style.transform = 'translate3d(' + Math.round(x) + 'px,' + Math.round(y) + 'px,0)';
  }

  function guidesUpdate(vp) {
    guides.forEach(function (g) {
      g.steps.forEach(function (step, i) {
        if (!step.anchor || !step.anchor.isConnected) {
          step.anchor = findTarget(step.t);
          step.scrollers = step.anchor ? scrollersOf(step.anchor) : [];
          if (step.anchor && resizeObserver) { try { resizeObserver.observe(step.anchor); } catch (ignored) { /* detached */ } }
        }
        if (!step.anchor) {
          step.badge.style.visibility = 'hidden';
          step.ring.style.visibility = 'hidden';
          if (i === g.current) g.tip.style.visibility = 'hidden';
          return;
        }
        var r = step.anchor.getBoundingClientRect();
        var v = visibleRect({scrollers: step.scrollers || []}, r, vp);
        var vis = v.empty ? 'hidden' : '';
        step.badge.style.visibility = vis;
        step.ring.style.visibility = vis;
        var bw = step.badge.offsetWidth || 22;
        placeAt(step.badge, r.left - bw / 2, r.top - 11);
        placeAt(step.ring, r.left, r.top);
        step.ring.style.width = Math.round(r.width) + 'px';
        step.ring.style.height = Math.round(r.height) + 'px';
        step.ring.style.clipPath = clipFor(r, v);
        if (i === g.current) {
          g.tip.style.visibility = vis;
          var spot = beside(r, g.tip.offsetWidth || 200, g.tip.offsetHeight || 40, 'right', vp);
          placeAt(g.tip, spot.x, spot.y);
        }
      });
    });
  }

  function guideAdvance(g, from, how, value) {
    var step = g.steps[from];
    if (!step || step.done) return;
    step.done = true;
    var ev = {type: 'step', g: g.id, s: step.n, done: true, how: how};
    if (how === 'input' || how === 'select' || how === 'check') ev.filled = true;
    if (value !== undefined) ev.value = value;
    events().push(ev);
    var next = from + 1;
    while (next < g.steps.length && g.steps[next].done) next++;
    g.current = next;
    g.pulse = true;
    if (g.current >= g.steps.length) events().push({type: 'guide', g: g.id, done: true});
    guideRender(g);
    if (g.scroll && g.steps[g.current] && g.steps[g.current].anchor) {
      try { g.steps[g.current].anchor.scrollIntoView({block: 'center', behavior: 'smooth'}); } catch (ignored) { /* old browser */ }
    }
    schedule();
  }

  function matches(step, target, kind) {
    var a = step.anchor;
    if (!a || !target || !(a === target || a.contains(target))) return false;
    var want = step.do || 'any';
    var tag = (a.nodeName || '').toLowerCase();
    var type = (a.getAttribute('type') || '').toLowerCase();
    if (want === 'click') return kind === 'click';
    if (want === 'check') return kind === 'change' && !!a.checked;
    if (want === 'select') return kind === 'change' && tag === 'select';
    if (want === 'input') return (kind === 'input' || kind === 'change') && !!String(a.value || '').length;
    if (kind === 'click') return !(tag === 'input' && type !== 'checkbox' && type !== 'radio' && type !== 'button' && type !== 'submit') && tag !== 'textarea' && tag !== 'select';
    return (kind === 'input' || kind === 'change') && (a.type === 'checkbox' ? !!a.checked : !!String(a.value || '').length);
  }

  var TYPE_SETTLE_MS = 700;

  function onGuideEvent(ev) {
    if (!guides.size || ours(ev.target)) return;
    guides.forEach(function (g) {
      var step = g.steps[g.current];
      if (!step) return;
      if (!step.anchor || !step.anchor.isConnected) step.anchor = findTarget(step.t);
      if (!matches(step, ev.target, ev.type)) return;
      var how = ev.type === 'click' ? 'click' : ((step.anchor.nodeName || '').toLowerCase() === 'select' ? 'select' : (step.anchor.type === 'checkbox' ? 'check' : 'input'));
      var at = g.current;
      var finish = function () {
        step.timer = 0;
        if (g.current !== at || step.done || !guides.has(g.id)) return;
        // A value is only reported when the agent asked for it and the field is not sensitive.
        var value = step.capture && how !== 'click' && !sensitive(step.anchor, step) ? String(step.anchor.value || '').slice(0, 500) : undefined;
        guideAdvance(g, at, how, value);
      };
      if (step.timer) clearTimeout(step.timer);
      // Typing settles before the step completes, so one keystroke does not skip ahead; change and click complete at once.
      if (ev.type === 'input' && how === 'input') step.timer = setTimeout(finish, TYPE_SETTLE_MS);
      else finish();
    });
  }

  function guideListen() {
    if (guideListening) return;
    guideListening = true;
    on(document, 'click', onGuideEvent, true);
    on(document, 'input', onGuideEvent, true);
    on(document, 'change', onGuideEvent, true);
  }

  function guide(spec) {
    spec = spec || {};
    var steps = Array.isArray(spec.steps) ? spec.steps : [];
    var id = String(spec.id || 'g1');
    guideClose(id);
    listen();
    guideListen();
    var g = {id: id, current: 0, scroll: !!spec.scroll, pulse: true, steps: steps.map(function (st, i) {
      return {t: st.t, say: st.say, do: st.do || 'any', n: st.n || i + 1, sensitive: !!st.sensitive, capture: !!st.capture, done: false};
    })};
    var miss = [];
    g.steps.forEach(function (step) {
      step.anchor = findTarget(step.t);
      step.scrollers = step.anchor ? scrollersOf(step.anchor) : [];
      if (!step.anchor) miss.push(step.n);
    });
    if (typeof spec.at === 'number' && spec.at > 1) {
      for (var k = 0; k < g.steps.length && g.steps[k].n < spec.at; k++) g.steps[k].done = true;
      g.current = Math.min(k, g.steps.length);
    }
    guides.set(id, g);
    guideRender(g);
    if (panel && spec.tab !== false) panelTab('steps');
    update();
    return {id: id, steps: g.steps.length, current: g.steps[g.current] ? g.steps[g.current].n : null, miss: miss};
  }

  function guideMove(spec) {
    var id = String((spec && spec.id) || 'g1');
    var g = guides.get(id);
    if (!g) return null;
    var delta = spec.delta || 0;
    if (delta > 0 && g.current < g.steps.length) {
      guideAdvance(g, g.current, 'agent');
    } else if (delta < 0 && g.current > 0) {
      g.current = Math.min(g.current, g.steps.length) - 1;
      g.steps[g.current].done = false;
      g.pulse = true;
      events().push({type: 'step', g: id, s: g.steps[g.current].n, done: false, how: 'agent'});
      guideRender(g);
      schedule();
    }
    return {id: id, current: g.steps[g.current] ? g.steps[g.current].n : null, total: g.steps.length};
  }

  function guideClose(id) {
    var key = String(id || 'g1');
    var g = guides.get(key);
    if (!g) return false;
    g.steps.forEach(function (step) { if (step.badge) step.badge.remove(); if (step.ring) step.ring.remove(); });
    if (g.tip) g.tip.remove();
    guides.delete(key);
    stepsList();
    window.__pkbGuideState = guideState();
    if (idle() && layer && layer.isConnected) layer.remove();
    return true;
  }

  function guideDone(spec) {
    var id = String((spec && spec.id) || 'g1');
    if (!guides.has(id)) return null;
    events().push({type: 'guide', g: id, done: true, how: 'agent'});
    guideClose(id);
    return {id: id, closed: true};
  }

  function ask(spec) {
    spec = spec || {};
    var id = String(spec.id || 'q1');
    askClose(id);
    listen();
    var root = ensureLayer();
    var node = el('form', 'ask');
    node.setAttribute('role', 'dialog');
    node.setAttribute('aria-label', 'Question from the agent');
    var q = el('div');
    var b = document.createElement('b');
    b.textContent = String(spec.say || '');
    q.appendChild(b);
    node.appendChild(q);
    var choices = Array.isArray(spec.choices) ? spec.choices.filter(function (c) { return String(c).trim(); }) : [];
    var row = el('div', 'row');
    var field = null;
    function answer(value) {
      var ev = {type: 'answer', id: id};
      if (spec.sensitive) ev.filled = true; else ev.value = String(value).slice(0, 2000);
      events().push(ev);
      askClose(id);
    }
    if (choices.length) {
      choices.forEach(function (choice, i) {
        var btn = el('button', i === 0 ? '' : 'secondary', String(choice));
        btn.type = 'button';
        btn.addEventListener('click', function () { answer(choice); });
        row.appendChild(btn);
      });
    } else {
      field = el('input');
      field.type = spec.sensitive ? 'password' : 'text';
      field.setAttribute('aria-label', String(spec.say || 'Answer'));
      if (spec.placeholder) field.placeholder = String(spec.placeholder);
      node.appendChild(field);
      var ok = el('button', '', 'Send');
      ok.type = 'submit';
      row.appendChild(ok);
      node.addEventListener('submit', function (e) { e.preventDefault(); if (field.value.trim()) answer(field.value); });
    }
    node.appendChild(row);
    isolateKeys(node);
    root.appendChild(node);
    asks.set(id, {id: id, node: node, t: spec.t, anchor: spec.t ? findTarget(spec.t) : null, scrollers: []});
    update();
    if (field) { try { field.focus({preventScroll: true}); } catch (ignored) { /* focus refused */ } }
    return {id: id, choices: choices.length};
  }

  function askClose(id) {
    var a = asks.get(String(id));
    if (!a) return false;
    a.node.remove();
    asks.delete(String(id));
    if (idle() && layer && layer.isConnected) layer.remove();
    return true;
  }

  function asksUpdate(vp) {
    asks.forEach(function (a) {
      if (a.t && (!a.anchor || !a.anchor.isConnected)) a.anchor = findTarget(a.t);
      var w = a.node.offsetWidth || 260;
      var h = a.node.offsetHeight || 80;
      if (a.anchor) {
        var r = a.anchor.getBoundingClientRect();
        var spot = beside(r, w, h, 'right', vp);
        placeAt(a.node, spot.x, spot.y);
      } else {
        placeAt(a.node, Math.max(8, (vp.w - w) / 2), 16);
      }
    });
  }

  // ---- Panel: chat and steps in the page (shadow root, keyboard-isolated, draggable, resizable) ----
  var PANEL_CSS = "__PKB_PANEL_CSS__";
  var PANEL_MIN_W = 260;
  var PANEL_MIN_H = 200;
  var SNAP = 16;
  var panel = null;
  var chatSeq = 0;

  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    return node;
  }

  function clock(at) {
    var d = at ? new Date(at) : new Date();
    if (isNaN(d.getTime())) d = new Date();
    return d.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'});
  }

  function panelDefaults() {
    var vp = viewport();
    var w = Math.min(360, Math.floor(vp.w * 0.9));
    var h = Math.min(420, Math.floor(vp.h * 0.9));
    return {x: vp.w - w - 16, y: vp.h - h - 16, w: w, h: h, min: false, tab: 'chat'};
  }

  function panelClamp(st) {
    var vp = viewport();
    st.w = clamp(st.w, PANEL_MIN_W, Math.max(PANEL_MIN_W, Math.floor(vp.w * 0.9)));
    st.h = clamp(st.h, PANEL_MIN_H, Math.max(PANEL_MIN_H, Math.floor(vp.h * 0.9)));
    st.x = clamp(st.x, 0, vp.w - st.w);
    st.y = clamp(st.y, 0, vp.h - st.h);
    return st;
  }

  function panelSnap(st) {
    var vp = viewport();
    if (st.x <= SNAP) st.x = 0;
    if (st.y <= SNAP) st.y = 0;
    if (vp.w - (st.x + st.w) <= SNAP) st.x = vp.w - st.w;
    if (vp.h - (st.y + st.h) <= SNAP) st.y = vp.h - st.h;
    return panelClamp(st);
  }

  function panelPlace() {
    if (!panel) return;
    var st = panelClamp(panel.state);
    var card = panel.card;
    card.style.transform = 'translate3d(' + Math.round(st.x) + 'px,' + Math.round(st.y) + 'px,0)';
    card.style.width = Math.round(st.w) + 'px';
    card.style.height = Math.round(st.h) + 'px';
    card.hidden = !!st.min;
    panel.pill.hidden = !st.min;
    if (st.min) {
      var pw = panel.pill.offsetWidth || 120;
      var ph = panel.pill.offsetHeight || 32;
      panel.pill.style.transform = 'translate3d(' + Math.round(st.x + st.w - pw) + 'px,' + Math.round(st.y + st.h - ph) + 'px,0)';
    }
    window.__pkbPanelState = {x: Math.round(st.x), y: Math.round(st.y), w: Math.round(st.w), h: Math.round(st.h), min: !!st.min, tab: st.tab};
  }

  function panelTab(name) {
    if (!panel) return;
    panel.state.tab = name;
    panel.tabs.forEach(function (b) {
      var on = b.getAttribute('data-tab') === name;
      b.setAttribute('aria-selected', on ? 'true' : 'false');
      if (on) b.querySelector('.pkb-badge').textContent = '';
    });
    panel.chatPane.hidden = name !== 'chat';
    panel.stepsPane.hidden = name !== 'steps';
    panel.composer.hidden = name !== 'chat';
    if (name === 'chat') panel.chatPane.scrollTop = panel.chatPane.scrollHeight;
    panelPlace();
  }

  function panelUnread() {
    var count = 0;
    if (!panel) return;
    if (panel.state.min || panel.state.tab !== 'chat') count = ++panel.unread; else panel.unread = 0;
    var badge = panel.tabs[0].querySelector('.pkb-badge');
    badge.textContent = panel.state.tab !== 'chat' && count ? String(count) : '';
    panel.pill.querySelector('.pkb-unread').textContent = panel.state.min && count ? String(count) : '';
  }

  function bubble(row) {
    var from = row.from || (row.source === 'human' ? 'human' : (row.source === 'system' ? 'system' : 'agent'));
    var node = el('div', 'pkb-msg');
    node.setAttribute('data-from', from);
    if (row.id) node.setAttribute('data-id', row.id);
    if (row.state) node.setAttribute('data-state', row.state);
    var who = from === 'human' ? 'You' : (from === 'system' ? 'System' : ('Agent' + (row.agent ? ' ' + row.agent : '')));
    var meta = el('span', 'pkb-meta', who + ' \u00b7 ' + clock(row.at));
    node.appendChild(meta);
    node.appendChild(document.createTextNode(String(row.text == null ? '' : row.text)));
    return node;
  }

  function chat(rows) {
    if (!panel) return 0;
    var list = Array.isArray(rows) ? rows : [rows];
    var added = 0;
    var pane = panel.chatPane;
    var stick = pane.scrollHeight - pane.scrollTop - pane.clientHeight < 40;
    list.forEach(function (row) {
      if (!row || row.text == null) return;
      var key = row.id ? String(row.id) : '';
      if (key && panel.seen[key]) return;
      if (key) panel.seen[key] = true;
      if (panel.empty) { panel.empty.remove(); panel.empty = null; }
      pane.appendChild(bubble(row));
      added++;
      if ((row.from || row.source) !== 'human') panelUnread();
    });
    if (stick) pane.scrollTop = pane.scrollHeight;
    return added;
  }

  function chatSent(ids) {
    if (!panel || !ids) return 0;
    var n = 0;
    ids.forEach(function (id) {
      var node = panel.chatPane.querySelector('.pkb-msg[data-id="' + CSS.escape(String(id)) + '"]');
      if (node) { node.setAttribute('data-state', 'sent'); n++; }
    });
    return n;
  }

  function panelSend() {
    var text = panel.input.value;
    if (!text.trim()) return;
    var id = 'h' + (++chatSeq) + '-' + Date.now().toString(36);
    window.__pkbChat = window.__pkbChat || [];
    window.__pkbChat.push({id: id, text: text, at: new Date().toISOString()});
    chat({id: id, from: 'human', text: text, state: 'pending', at: new Date().toISOString()});
    panel.input.value = '';
    panel.send.disabled = true;
    panel.input.focus();
  }

  function isolateKeys(node) {
    ['keydown', 'keypress', 'keyup', 'beforeinput', 'input', 'compositionstart', 'compositionupdate', 'compositionend',
      'paste', 'copy', 'cut', 'pointerdown', 'mousedown', 'mouseup', 'click', 'dblclick', 'contextmenu', 'wheel']
      .forEach(function (type) {
        node.addEventListener(type, function (event) { event.stopPropagation(); }, type === 'wheel' ? {passive: true} : false);
      });
  }

  function dragger(handle, onStart, onMove, onEnd) {
    var active = null;
    handle.addEventListener('pointerdown', function (event) {
      if (event.button !== 0) return;
      if (event.target !== handle && event.target.closest && event.target.closest('button,textarea,input,select,a')) return;
      event.preventDefault();
      try { handle.setPointerCapture(event.pointerId); } catch (ignored) { /* synthetic pointer */ }
      active = {id: event.pointerId, x: event.clientX, y: event.clientY};
      onStart();
    });
    handle.addEventListener('pointermove', function (event) {
      if (!active || event.pointerId !== active.id) return;
      onMove(event.clientX - active.x, event.clientY - active.y);
    });
    function end(event) {
      if (!active || event.pointerId !== active.id) return;
      active = null;
      try { handle.releasePointerCapture(event.pointerId); } catch (ignored) { /* already released */ }
      onEnd();
    }
    handle.addEventListener('pointerup', end);
    handle.addEventListener('pointercancel', end);
  }

  function buildPanel(root) {
    var card = el('section', 'pkb-card pkb-panel');
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-label', 'Agent');
    card.setAttribute('data-pkb-panel', '');
    var head = el('header', 'pkb-head');
    var dot = el('span', 'pkb-dot');
    dot.setAttribute('data-state', 'live');
    var title = el('strong', 'pkb-title', 'Agent');
    var tabs = el('nav', 'pkb-tabs');
    tabs.setAttribute('role', 'tablist');
    var tabButtons = [['chat', 'Chat'], ['steps', 'Steps']].map(function (t) {
      var b = el('button', '', t[1]);
      b.type = 'button';
      b.setAttribute('role', 'tab');
      b.setAttribute('data-tab', t[0]);
      b.appendChild(el('span', 'pkb-badge'));
      b.addEventListener('click', function () { panelTab(t[0]); });
      tabs.appendChild(b);
      return b;
    });
    var minimize = el('button', 'pkb-icon', '\u2013');
    minimize.type = 'button';
    minimize.setAttribute('aria-label', 'Minimize');
    minimize.setAttribute('data-action', 'minimize');
    var close = el('button', 'pkb-icon', '\u00d7');
    close.type = 'button';
    close.setAttribute('aria-label', 'Close');
    close.setAttribute('data-action', 'close');
    head.appendChild(dot);
    head.appendChild(title);
    head.appendChild(tabs);
    head.appendChild(minimize);
    head.appendChild(close);
    var chatPane = el('div', 'pkb-pane pkb-chat');
    chatPane.setAttribute('role', 'log');
    chatPane.setAttribute('aria-live', 'polite');
    var empty = el('div', 'pkb-empty', 'Messages from the agent appear here.');
    chatPane.appendChild(empty);
    var stepsPane = el('div', 'pkb-pane');
    stepsPane.hidden = true;
    stepsPane.appendChild(el('div', 'pkb-empty', 'No steps yet.'));
    var composer = el('form', 'pkb-composer');
    composer.setAttribute('autocomplete', 'off');
    var input = el('textarea');
    input.rows = 1;
    input.placeholder = 'Message the agent (Enter to send, Shift+Enter for a new line)';
    input.setAttribute('aria-label', 'Message');
    var send = el('button', '', 'Send');
    send.type = 'submit';
    send.disabled = true;
    composer.appendChild(input);
    composer.appendChild(send);
    card.appendChild(head);
    card.appendChild(chatPane);
    card.appendChild(stepsPane);
    card.appendChild(composer);
    ['n', 'e', 's', 'w', 'ne', 'nw', 'se', 'sw'].forEach(function (dir) {
      var grip = el('div', 'pkb-grip');
      grip.setAttribute('data-dir', dir);
      card.appendChild(grip);
    });
    var pill = el('button', 'pkb-card pkb-pill');
    pill.type = 'button';
    pill.hidden = true;
    pill.setAttribute('aria-label', 'Open agent panel');
    var pillDot = el('span', 'pkb-dot');
    pillDot.setAttribute('data-state', 'live');
    pill.appendChild(pillDot);
    pill.appendChild(document.createTextNode('Agent'));
    pill.appendChild(el('span', 'pkb-unread'));
    root.appendChild(card);
    root.appendChild(pill);
    isolateKeys(card);
    isolateKeys(pill);
    var p = {card: card, pill: pill, head: head, title: title, tabs: tabButtons, chatPane: chatPane, stepsPane: stepsPane,
      composer: composer, input: input, send: send, empty: empty, seen: {}, unread: 0, state: panelDefaults()};
    input.addEventListener('input', function () { send.disabled = !input.value.trim(); });
    input.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
        event.preventDefault();
        panelSend();
      }
    });
    composer.addEventListener('submit', function (event) { event.preventDefault(); panelSend(); });
    minimize.addEventListener('click', function () { p.state.min = true; panelPlace(); });
    close.addEventListener('click', function () {
      events().push({type: 'panel', action: 'close'});
      panelClose();
    });
    pill.addEventListener('click', function () { p.state.min = false; p.unread = 0; pill.querySelector('.pkb-unread').textContent = ''; panelPlace(); });
    var start = null;
    dragger(head, function () {
      start = {x: p.state.x, y: p.state.y};
      card.classList.add('pkb-dragging');
    }, function (dx, dy) {
      p.state.x = start.x + dx;
      p.state.y = start.y + dy;
      panelPlace();
    }, function () {
      card.classList.remove('pkb-dragging');
      panelSnap(p.state);
      panelPlace();
    });
    head.addEventListener('dblclick', function (event) {
      if (event.target.closest && event.target.closest('button')) return;
      var d = panelDefaults();
      d.tab = p.state.tab;
      p.state = d;
      panelPlace();
    });
    Array.prototype.forEach.call(card.querySelectorAll('.pkb-grip'), function (grip) {
      var dir = grip.getAttribute('data-dir');
      var from = null;
      dragger(grip, function () {
        from = {x: p.state.x, y: p.state.y, w: p.state.w, h: p.state.h};
        card.classList.add('pkb-resizing');
      }, function (dx, dy) {
        var vp = viewport();
        var maxW = Math.max(PANEL_MIN_W, Math.floor(vp.w * 0.9));
        var maxH = Math.max(PANEL_MIN_H, Math.floor(vp.h * 0.9));
        var st = p.state;
        if (dir.indexOf('e') >= 0) st.w = clamp(from.w + dx, PANEL_MIN_W, maxW);
        if (dir.indexOf('s') >= 0) st.h = clamp(from.h + dy, PANEL_MIN_H, maxH);
        if (dir.indexOf('w') >= 0) {
          st.w = clamp(from.w - dx, PANEL_MIN_W, maxW);
          st.x = from.x + (from.w - st.w);
        }
        if (dir.indexOf('n') >= 0) {
          st.h = clamp(from.h - dy, PANEL_MIN_H, maxH);
          st.y = from.y + (from.h - st.h);
        }
        panelPlace();
      }, function () {
        card.classList.remove('pkb-resizing');
        panelPlace();
      });
    });
    return p;
  }

  function panelSheet(root) {
    if (root.__pkbPanelSheet) return;
    root.__pkbPanelSheet = true;
    try {
      var sheet = new CSSStyleSheet();
      sheet.replaceSync(PANEL_CSS + '\n.pkb-panel,.pkb-pill{pointer-events:auto;}');
      root.adoptedStyleSheets = Array.from(root.adoptedStyleSheets || []).concat([sheet]);
    } catch (unsupported) {
      var style = document.createElement('style');
      style.textContent = PANEL_CSS;
      root.appendChild(style);
    }
  }

  function panelOpen(opts) {
    opts = opts || {};
    listen();
    var root = ensureLayer();
    panelSheet(root);
    if (!panel) panel = buildPanel(root);
    if (!panel.card.isConnected) { root.appendChild(panel.card); root.appendChild(panel.pill); }
    var st = panel.state;
    ['x', 'y', 'w', 'h'].forEach(function (k) { if (opts[k] != null && opts[k] !== '' && isFinite(+opts[k])) st[k] = +opts[k]; });
    if (opts.min != null) st.min = opts.min === true || opts.min === 'true';
    if (opts.title) panel.title.textContent = String(opts.title).slice(0, 80);
    if (opts.theme === 'light' || opts.theme === 'dark') { panel.card.setAttribute('data-theme', opts.theme); panel.pill.setAttribute('data-theme', opts.theme); }
    else if (opts.theme === 'auto') { panel.card.removeAttribute('data-theme'); panel.pill.removeAttribute('data-theme'); }
    if (Array.isArray(opts.history)) chat(opts.history);
    stepsList();
    panelTab(opts.tab === 'steps' ? 'steps' : (opts.tab === 'chat' ? 'chat' : st.tab || 'chat'));
    raiseLayer();
    if (opts.focus !== false && !st.min && st.tab === 'chat') {
      try { panel.input.focus({preventScroll: true}); } catch (ignored) { /* focus refused */ }
    }
    return window.__pkbPanelState;
  }

  function panelClose() {
    if (!panel) return false;
    panel.card.remove();
    panel.pill.remove();
    panel = null;
    window.__pkbPanelState = null;
    if (idle() && layer && layer.isConnected) layer.remove();
    return true;
  }

  function drainChat() {
    var queue = window.__pkbChat || [];
    window.__pkbChat = [];
    chatSent(queue.map(function (m) { return m.id; }));
    return queue;
  }

  function panelState() {
    return panel ? window.__pkbPanelState : null;
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
    raise: raiseLayer,
    panelOpen: panelOpen,
    panelClose: panelClose,
    panelState: panelState,
    panelTab: panelTab,
    chat: chat,
    chatSent: chatSent,
    drainChat: drainChat,
    guide: guide,
    guideMove: guideMove,
    guideDone: guideDone,
    guideState: guideState,
    ask: ask,
    askClose: askClose
  };
})();
