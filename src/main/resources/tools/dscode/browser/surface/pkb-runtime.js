// Pickleball page runtime: keeps agent placements on their targets.
// Runs once per document, in the pkb-surface isolated world when the session has CDP or BiDi.
// It moves light-DOM placements with the CSS `translate` property, so the inline left/top that
// Java wrote (the resolved box) stay as reported. Rings live in a shadow-root layer.
(function pkbSurfaceInstall() {
  'use strict';
  if (typeof window === 'undefined' || typeof document === 'undefined') return;
  // A preload can run before <html> exists. Retry once the element is inserted,
  // which is still before the page's own scripts.
  if (!document.documentElement) {
    if (window.__pkbSurfaceWait) return;
    window.__pkbSurfaceWait = true;
    var wait = new MutationObserver(function () {
      if (!document.documentElement) return;
      wait.disconnect();
      window.__pkbSurfaceWait = false;
      pkbSurfaceInstall();
    });
    try { wait.observe(document, {childList: true, subtree: true}); } catch (ignored) { /* not observable yet */ }
    return;
  }
  var VERSION = '3.0.0-editor';
  if (window.__pkbUi && window.__pkbUi.version === VERSION) return;
  if (window.__pkbUi && typeof window.__pkbUi.dispose === 'function') {
    try { window.__pkbUi.dispose(); } catch (ignored) { /* older runtime */ }
  }

  var FONT = 'system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif';
  var LIGHT = '--pkb-bg:#ffffff;--pkb-fg:#111827;--pkb-muted:#6b7280;--pkb-line:rgba(17,24,39,.12);'
    + '--pkb-accent:#3b82f6;--pkb-shadow:0 8px 24px rgba(15,23,42,.16),0 1px 3px rgba(15,23,42,.12);';
  var DARK = '--pkb-bg:#1f2937;--pkb-fg:#f3f4f6;--pkb-muted:#9ca3af;--pkb-line:rgba(255,255,255,.16);'
    + '--pkb-accent:#60a5fa;--pkb-shadow:0 8px 24px rgba(0,0,0,.55),0 1px 3px rgba(0,0,0,.4);'
  var VALUE_CSS = '.pkb-value{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-weight:700;'
    + 'background:rgba(59,130,246,.14);border-radius:4px;padding:0 3px;}';;
  var DOC_CSS = ''
    + '[data-pkb-agent][data-pkb-ui]{' + LIGHT + '}'
    + '@media (prefers-color-scheme: dark){[data-pkb-agent][data-pkb-ui]:not([data-pkb-theme=light]){' + DARK + '}}'
    + '[data-pkb-agent][data-pkb-ui][data-pkb-theme=dark]{' + DARK + '}'
    + '[data-pkb-ui][data-pkb-kind=overlay]{background:var(--pkb-bg)!important;color:var(--pkb-fg)!important;'
    + 'border:1px solid var(--pkb-line)!important;border-left:3px solid var(--pkb-accent)!important;border-radius:8px!important;'
    + 'box-shadow:var(--pkb-shadow)!important;padding:8px 12px!important;font:13px/1.45 ' + FONT + '!important;'
    + 'box-sizing:border-box;transition:opacity .14s ease-out;}'
    + '[data-pkb-ui][data-pkb-kind=overlay][data-pkb-at=over]{background:color-mix(in srgb,var(--pkb-bg) 80%,transparent)!important;pointer-events:none!important;}'
    + '[data-pkb-ui][data-pkb-kind=overlay][data-pkb-at=over][data-pkb-block=true]{pointer-events:auto!important;}'
    + '[data-pkb-ui][data-pkb-at=over] :is(a,button,input,select,textarea,[data-pkb-action]){pointer-events:auto;}'
    + '[data-pkb-ui][data-pkb-kind=overlay] button{font:inherit;border:0;background:var(--pkb-accent);color:#fff;border-radius:6px;padding:3px 10px;margin:4px 4px 0 0;cursor:pointer;}'
    + '[data-pkb-ui][data-pkb-kind=label]{background:var(--pkb-bg)!important;color:var(--pkb-fg)!important;'
    + 'border:1px solid var(--pkb-line)!important;border-radius:8px!important;'
    + 'font:600 13px/1.3 ' + FONT + '!important;padding:4px 10px!important;box-shadow:var(--pkb-shadow);pointer-events:none;white-space:nowrap;}'
    + '[data-pkb-ui][data-pkb-kind=label]:has([data-pkb-card]){background:var(--pkb-bg)!important;color:var(--pkb-fg)!important;'
    + 'pointer-events:auto!important;white-space:normal!important;border:1px solid var(--pkb-line)!important;border-radius:10px!important;'
    + 'padding:10px 12px!important;box-shadow:var(--pkb-shadow)!important;font:14px/1.45 ' + FONT + '!important;max-width:280px!important;}'
    + '[data-pkb-card] [data-pkb-check]{display:flex;gap:8px;align-items:flex-start;margin:4px 0;cursor:pointer;}'
    + '[data-pkb-card] input[type=checkbox]{margin:2px 0 0;cursor:pointer;}'
    + '[data-pkb-feedback="sending"]{opacity:.65!important;}'
    + '[data-pkb-feedback="delivered"]{opacity:1!important;box-shadow:inset 3px 0 0 #22a06b!important;}'
    + '[data-pkb-feedback="read"]{opacity:.92!important;box-shadow:inset 3px 0 0 var(--pkb-accent)!important;}'
    + '[data-pkb-ui][data-pkb-kind=border]{border-radius:6px;}'
    + '[data-pkb-ui][data-pkb-kind=magnifier]{box-shadow:var(--pkb-shadow);pointer-events:none;}'
    + '[data-pkb-ui][data-pkb-kind=step],[data-pkb-ui][data-pkb-kind=arrow],[data-pkb-ui][data-pkb-kind=spotlight]{pointer-events:none;}'
    + '@media (prefers-reduced-motion: reduce){[data-pkb-ui]{transition:none!important;}}'
    + VALUE_CSS;
  var LAYER_CSS = ''
    + ':host{all:initial;--pkb-accent:#3b82f6;}'
    + '@media (prefers-color-scheme: dark){:host{--pkb-accent:#60a5fa;}}'
    + '.ring{position:fixed;left:0;top:0;box-sizing:border-box;border-radius:6px;pointer-events:none;'
    + 'box-shadow:0 0 0 2px var(--pkb-accent),0 0 0 6px rgba(59,130,246,.22);background:transparent;'
    + 'transition:opacity .14s ease-out;will-change:transform;}'
    + '.ring[data-pkb-block=true]{pointer-events:auto;}'
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
    + '.ask .pkb-confirm{font-size:12px;color:#22a06b;}'
    + '.badge.missing,.tip.missing{outline:2px dashed #d93025;}'
    + '[data-pkb-feedback="sending"]{opacity:.65;}'
    + '[data-pkb-feedback="delivered"]{box-shadow:inset 3px 0 0 #22a06b;}'
    + '[data-pkb-feedback="read"]{box-shadow:inset 3px 0 0 var(--pkb-accent);}'
    + '@media (prefers-color-scheme: dark){.tip,.ask{background:#1f2937;color:#f3f4f6;border-color:rgba(255,255,255,.16);}.badge{background:#1f2937;}.ask input{background:#111827;color:#f3f4f6;border-color:rgba(255,255,255,.2);}}'
    + '@media (prefers-reduced-motion: reduce){.ring,.badge{transition:none;animation:none;}}'
    + VALUE_CSS;

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

  var PUSH_CAP = 20;
  var pushWindow = 0;
  var pushCount = 0;
  var pushDropped = 0;
  var mutationTimer = 0;
  var eventSeq = 0;
  var installId = Math.random().toString(36).slice(2, 8);
  var lastPressed = null;
  function nextEid(prefix) {
    return installId + (prefix || 'e') + (++eventSeq);
  }
  function tryBind(ev) {
    try {
      var fn = window.pkbSurface;
      if (typeof fn === 'function') {
        fn(JSON.stringify(ev));
        return true;
      }
    } catch (ignored) { /* this world has no binding */ }
    return false;
  }
  function setFeedback(node, state) {
    if (!node || !node.setAttribute) return;
    node.setAttribute('data-state', state);
    node.setAttribute('data-pkb-feedback', state);
    if (node.hasAttribute && node.hasAttribute('data-pkb-msg-id')) node.setAttribute('data-pkb-msg-status', state === 'pending' ? 'sending' : (state === 'sent' ? 'delivered' : state));
    var note = node.querySelector ? node.querySelector(':scope > .pkb-feedback') : null;
    if (!note && node.classList && node.classList.contains('pkb-msg')) {
      note = document.createElement('span');
      note.className = 'pkb-feedback';
      var anchor = null;
      for (var i = 0; i < node.childNodes.length; i++) {
        var child = node.childNodes[i];
        if (child.nodeType === 1 && child.classList && child.classList.contains('pkb-meta')) continue;
        anchor = child;
        break;
      }
      if (anchor) node.insertBefore(note, anchor);
      else node.appendChild(note);
    }
    if (note) {
      note.textContent = state === 'sending' || state === 'pending' ? 'Sending'
        : (state === 'delivered' || state === 'sent' ? 'Delivered' : (state === 'read' ? 'Read' : ''));
    }
  }
  function noteDelivered(ev) {
    var node = lastPressed;
    if (panel && ev && ev.id) {
      var bubble = panel.chatPane.querySelector('.pkb-msg[data-id="' + CSS.escape(String(ev.id)) + '"]');
      if (bubble) node = bubble;
    }
    if (!node) return;
    if (node.getAttribute('data-pkb-feedback') === 'read' || node.getAttribute('data-state') === 'read') return;
    setFeedback(node, 'delivered');
  }
  function deliver(ev) {
    var now = Date.now();
    if (now - pushWindow > 1000) {
      if (pushDropped) {
        var dropped = {type: 'dropped', text: String(pushDropped), eid: nextEid('d')};
        if (!tryBind(dropped)) events().push(dropped);
      }
      pushWindow = now;
      pushCount = 0;
      pushDropped = 0;
    }
    if (pushCount >= PUSH_CAP) {
      pushDropped++;
      return false;
    }
    pushCount++;
    if (!ev.eid) ev.eid = nextEid('e');
    // Queue as well as bind. A binding that does not reach the host is still drained.
    // The host drops the second copy when the eid matches.
    var bound = tryBind(ev);
    events().push(ev);
    if (bound) noteDelivered(ev);
    return bound;
  }
  // The run that acted (set by act for the next moments): page events name it, and say by=agent|human.
  function actingRun() {
    try {
      var raw = document.documentElement.getAttribute('data-pkb-acting-run') || '';
      var bar = raw.lastIndexOf('|');
      if (bar > 0 && Date.now() < Number(raw.slice(bar + 1))) return raw.slice(0, bar);
    } catch (ignored) { /* no root */ }
    return '';
  }
  function pushEvent(ev) {
    if (!ev) return false;
    if (!ev.eid) ev.eid = nextEid('e');
    if (ev.type !== 'mutation') {
      if (!ev.by) ev.by = actingSource();
      var run = actingRun();
      if (run) {
        if (!ev.run) ev.run = run;
        if (!ev.sessionId) ev.sessionId = run;
      }
    }
    if (ev.type === 'mutation') {
      if (mutationTimer) return false;
      mutationTimer = setTimeout(function () {
        mutationTimer = 0;
        deliver(ev);
      }, 50);
      return false;
    }
    return deliver(ev);
  }
  window.__pkbPush = pushEvent;

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
    if (layer && layer.isConnected) {
      layer.setAttribute('data-pkb-layer', 'agent_injected');
      return shadow;
    }
    if (!layer) {
      layer = document.createElement('pkb-layer');
      layer.setAttribute('data-pkb-layer', 'agent_injected');
      tagMeta(layer, 'layer');
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
        tagMeta(style, 'layer');
        shadow.appendChild(style);
      }
    }
    document.documentElement.appendChild(layer);
    raiseLayer();
    return shadow;
  }

  // The top layer keeps rings above a modal <dialog>; the newest top-layer entry paints last.
  // hide+show is intentional when a new dialog appears, but toggle on this layer must not re-enter.
  var raisingLayer = false;
  var raiseCount = 0;
  function raiseLayer() {
    if (raisingLayer) return;
    if (!layer || !layer.isConnected || typeof layer.showPopover !== 'function') return;
    raisingLayer = true;
    raiseCount++;
    try {
      if (!layer.hasAttribute('popover')) layer.setAttribute('popover', 'manual');
      var open = false;
      try { open = layer.matches(':popover-open'); } catch (ignored) { open = false; }
      if (open) layer.hidePopover();
      layer.showPopover();
    } catch (ignored) { /* z-index fallback */ }
    finally { raisingLayer = false; }
  }

  var OWN = '[data-pkb-agent],[data-pkb-layer],[data-pkb-record],[data-pkb-meta="true"]';
  var metaPurpose = '';

  /** Marks one injected node. An id is prefixed only when one is already assigned. */
  function tagMeta(node, purpose) {
    if (!node || node.nodeType !== 1 || !node.setAttribute) return node;
    node.setAttribute('data-pkb-meta', 'true');
    var add = purpose || metaPurpose;
    if (add) {
      var have = (node.getAttribute('data-pkb-purpose') || '').split(/\s+/).filter(Boolean);
      String(add).split(/\s+/).forEach(function (token) {
        if (token && have.indexOf(token) < 0) have.push(token);
      });
      node.setAttribute('data-pkb-purpose', have.join(' '));
    }
    function prefix(attr) {
      var current = attr === 'id' ? node.id : node.getAttribute(attr);
      if (!current || current.indexOf('pkb-') === 0) return;
      var next = 'pkb-' + current;
      if (attr === 'id') node.id = next;
      else node.setAttribute(attr, next);
    }
    prefix('id');
    prefix('data-pkb-id');
    return node;
  }

  function tagTree(node, purpose) {
    tagMeta(node, purpose);
    if (!node || !node.querySelectorAll) return node;
    Array.prototype.forEach.call(node.querySelectorAll('*'), function (child) { tagMeta(child, purpose); });
    return node;
  }

  function purposeFor(kind) {
    var name = String(kind || '');
    if (name === 'overlay' || name === 'label' || name === 'html' || name === 'step' || name === 'instructions' || name === 'diagram' || name === 'callout') return 'placement callout';
    if (name === 'panel') return 'panel';
    if (name === 'guide') return 'guide';
    if (name === 'editor') return 'editor';
    if (name === 'recorder' || name === 'record') return 'recorder';
    if (name === 'layer') return 'layer';
    return 'placement';
  }

  function ours(node) {
    var el = node && node.nodeType === 1 ? node : (node && node.parentElement);
    var guard = 0;
    while (el && guard++ < 24) {
      if (el.nodeType === 1 && el.closest && el.closest(OWN)) return true;
      var root = el.getRootNode ? el.getRootNode() : null;
      if (root && root.host && root.host !== el) el = root.host;
      else break;
    }
    return false;
  }

  function taggedNode(node) {
    return node && node.nodeType === 1 && node.hasAttribute
      && (node.hasAttribute('data-pkb-agent') || node.hasAttribute('data-pkb-layer') || node.hasAttribute('data-pkb-record') || node.getAttribute('data-pkb-meta') === 'true');
  }

  function eventLeaf(event) {
    if (!event) return null;
    var path = event.composedPath ? event.composedPath() : [];
    var leaf = path.length ? path[0] : event.target;
    if (leaf && leaf.nodeType !== 1) leaf = leaf.parentElement;
    return leaf && leaf.nodeType === 1 ? leaf : null;
  }

  function clipText(text, max) {
    return String(text || '').replace(/\s+/g, ' ').trim().slice(0, max || 80);
  }
  function labelOf(el) {
    if (!el || !el.getAttribute) return '';
    var labelled = '';
    try { labelled = el.labels && el.labels[0] ? el.labels[0].innerText : ''; } catch (ignored) { labelled = ''; }
    return clipText(el.getAttribute('data-choice') || el.getAttribute('aria-label') || labelled
      || el.getAttribute('placeholder') || el.getAttribute('name') || el.innerText || '');
  }
  function roleOf(el) {
    if (!el || !el.getAttribute) return '';
    var role = el.getAttribute('role');
    if (role) return role;
    var tag = (el.nodeName || '').toLowerCase();
    if (tag === 'a') return 'link';
    if (tag === 'select') return 'combobox';
    if (tag === 'textarea') return 'textbox';
    if (tag === 'button') return 'button';
    if (tag === 'input') {
      var type = (el.type || 'text').toLowerCase();
      if (type === 'checkbox') return 'checkbox';
      if (type === 'radio') return 'radio';
      if (type === 'button' || type === 'submit' || type === 'reset') return 'button';
      return 'textbox';
    }
    return tag;
  }
  function boundsOf(el) {
    try {
      var r = el.getBoundingClientRect();
      return Math.round(r.left) + ',' + Math.round(r.top) + ',' + Math.round(r.width) + ',' + Math.round(r.height);
    } catch (ignored) { return ''; }
  }
  function frameName(node) {
    try {
      var doc = node && node.ownerDocument ? node.ownerDocument : document;
      var view = doc.defaultView || window;
      if (view.__pkbFrameLabel) return String(view.__pkbFrameLabel);
      var host = null;
      try { host = view.frameElement; } catch (ignored) { host = null; }
      if (host) return host.id || host.getAttribute('name') || host.getAttribute('title') || 'frame';
      try { if (view === view.top) return 'top'; } catch (ignored) { /* cross-origin top */ }
      if (view.name) return String(view.name);
    } catch (ignored) { /* detached */ }
    return 'frame';
  }
  function controlOf(node) {
    if (!node || !node.closest) return node;
    return node.closest('button,a,input,select,textarea,summary,label,[role],[data-choice],[data-pkb-action]') || node;
  }
  /** Agent input (form-fill) stamps data-pkb-agent-acting on the root with an expiry in ms; rows sensed before it expires are source=agent. */
  function actingSource() {
    try {
      var until = Number(document.documentElement.getAttribute('data-pkb-agent-acting') || 0);
      if (until && Date.now() < until) return 'agent';
    } catch (ignored) { /* no root */ }
    return 'human';
  }
  function sense(kind, node, extra) {
    var el = controlOf(node);
    if (!el || el.nodeType !== 1) return false;
    if (ours(el)) return false;
    var tagName = (el.nodeName || '').toLowerCase();
    if (tagName === 'iframe' || tagName === 'frame') return false;
    var name = labelOf(el);
    var value = extra && extra.value != null ? String(extra.value) : (el.value != null ? String(el.value) : '');
    if (kind === 'select' && el.multiple && el.selectedOptions && !(extra && extra.value != null)) {
      var picked = [];
      for (var s = 0; s < el.selectedOptions.length; s++) {
        picked.push(el.selectedOptions[s].label || el.selectedOptions[s].value || '');
      }
      value = picked.join('|');
    }
    var row = {
      event: 'interaction',
      type: kind,
      kind: kind,
      source: (extra && extra.source) || actingSource(),
      id: (extra && extra.id) || el.id || '',
      role: (extra && extra.role) || roleOf(el),
      name: extra && extra.name != null ? String(extra.name) : name,
      text: extra && extra.name != null ? String(extra.name) : name,
      value: value,
      oldValue: extra && extra.oldValue != null ? String(extra.oldValue) : '',
      frame: (extra && extra.frame) || frameName(el),
      bounds: boundsOf(el),
      target: name || el.id || roleOf(el)
    };
    if (extra && extra.drop) row.drop = String(extra.drop);
    if (extra && extra.checked != null) row.checked = String(extra.checked);
    var sensitive = (el.getAttribute && el.getAttribute('data-sensitive') === 'true')
      || (el.type && String(el.type).toLowerCase() === 'password');
    if (sensitive) {
      row.sensitive = 'true';
      row.value = 'filled';
      row.oldValue = '';
    }
    return pushEvent(row);
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

  function isBlocked(spec) {
    var b = spec && spec.block;
    return b === true || b === 'true' || b === 1 || b === '1';
  }

  function applyBlock(item) {
    if (!item || !item.block) return;
    if (item.host) {
      item.host.setAttribute('data-pkb-block', 'true');
      if (onTarget(item.kind, item.at)) item.host.style.setProperty('pointer-events', 'auto', 'important');
    }
    if (item.ring) item.ring.setAttribute('data-pkb-block', 'true');
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

  function paintCopy(source, clone) {
    if (!source || !clone || !window.getComputedStyle) return;
    var keys = ['color', 'backgroundColor', 'backgroundImage', 'font', 'fontSize', 'fontWeight', 'fontFamily', 'border', 'borderRadius', 'padding', 'display', 'alignItems', 'justifyContent', 'textAlign', 'lineHeight', 'boxSizing', 'whiteSpace'];
    function apply(from, to) {
      var style;
      try { style = window.getComputedStyle(from); } catch (e) { return; }
      keys.forEach(function (key) {
        try { if (style[key]) to.style[key] = style[key]; } catch (ignored) {}
      });
    }
    apply(source, clone);
    var box = source.getBoundingClientRect ? source.getBoundingClientRect() : null;
    if (box && box.width > 0) clone.style.width = Math.round(box.width) + 'px';
    if (box && box.height > 0) clone.style.height = Math.round(box.height) + 'px';
    var fromKids = source.querySelectorAll ? source.querySelectorAll('*') : [];
    var toKids = clone.querySelectorAll('*');
    for (var i = 0; i < fromKids.length && i < toKids.length; i++) apply(fromKids[i], toKids[i]);
    clone.setAttribute('data-pkb-copy', 'live');
  }

  function visibleText(source) {
    var raw = '';
    try { raw = source.innerText || ''; } catch (e) { raw = ''; }
    if (!String(raw).trim()) raw = source.textContent || source.value || '';
    if (!String(raw).trim() && source.getAttribute) {
      raw = source.getAttribute('aria-label') || source.getAttribute('alt') || source.getAttribute('title') || '';
    }
    return String(raw).replace(/\s+/g, ' ').trim().slice(0, 80);
  }

  function readableColor(color) {
    var match = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(color || '');
    if (!match) return color || '#1b1f24';
    var light = (0.3 * match[1]) + (0.59 * match[2]) + (0.11 * match[3]);
    return light > 210 ? '#1b1f24' : color;
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
    paintCopy(anchor, clone);
    if (anchor && 'value' in anchor && 'value' in clone && anchor.value) clone.value = anchor.value;
    var text = visibleText(anchor);
    var shown = ((clone.innerText || clone.textContent || '') + '').replace(/\s/g, '');
    if (!shown && text) clone.textContent = text;
    var z = zoom || 2;
    // Scale around the centre so the label stays inside the oval. Origin 0 0 crops the padding.
    clone.style.position = 'absolute';
    clone.style.left = '50%';
    clone.style.top = '50%';
    clone.style.margin = '0';
    clone.style.transformOrigin = 'center center';
    clone.style.transform = 'translate(-50%, -50%) scale(' + z + ')';
    clone.style.pointerEvents = 'none';
    clone.style.display = 'flex';
    clone.style.alignItems = 'center';
    clone.style.justifyContent = 'center';
    clone.style.textAlign = 'center';
    try {
      var painted = window.getComputedStyle(anchor);
      clone.style.color = readableColor(painted.color);
    } catch (ignored) { /* keep the copied color */ }
    if (anchor) clone.setAttribute('data-pkb-copy', 'live');
    else clone.setAttribute('data-pkb-copy', 'fallback');
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
      tagMeta(el, 'placement');
    });
    old.replaceWith(clone);
    var blank = !((clone.textContent || '').replace(/\s/g, '')) && !clone.querySelector('img,svg,canvas,video,input');
    var note = item.host.querySelector('[data-pkb-copy="fallback"]');
    if (blank) {
      if (!note) {
        note = document.createElement('div');
        note.setAttribute('data-pkb-copy', 'fallback');
        note.textContent = 'target not captured';
        tagMeta(note, 'placement');
        note.style.cssText = 'color:#1b1f24;background:#ffffff;font:600 13px/1.3 system-ui,sans-serif;padding:8px;border:1px solid #dfe3e8;border-radius:8px;';
        item.host.appendChild(note);
      }
    } else if (note) {
      note.remove();
    }
  }

  function makeRing(id) {
    var root = ensureLayer();
    var ring = document.createElement('div');
    ring.className = 'ring';
    ring.setAttribute('data-ring', id);
    ring.setAttribute('aria-hidden', 'true');
    tagMeta(ring, 'placement');
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
          if (item.lost) pushEvent({type: 'found', id: item.id});
          item.lost = false;
        } else {
          if (!item.lost) pushEvent({type: 'lost', id: item.id});
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
          || Array.prototype.some.call(rec.removedNodes, function (n) { return !taggedNode(n); });
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
      bumpDoc();
      schedule();
      adoptFrames(document);
      if (window.__pkbPushMutations) pushEvent({type: 'mutation', source: 'human'});
    }
  }

  function markSensed(doc) {
    try {
      if (!doc || !doc.documentElement) return false;
      if (doc.documentElement.getAttribute('data-pkb-sense') === '1') return false;
      doc.documentElement.setAttribute('data-pkb-sense', '1');
      return true;
    } catch (ignored) { return false; }
  }

  function wireSense(doc) {
    if (!markSensed(doc)) return false;
    on(doc, 'click', function (event) {
      if (!event) return;
      var node = eventLeaf(event);
      if (!node) return;
      if (ours(node)) return;
      var control = (node.closest && node.closest('button,a,label,input,summary,[data-choice],[data-pkb-action],[role]')) || node;
      var tag = (control.nodeName || '').toLowerCase();
      if (tag === 'iframe' || tag === 'frame') return;
      var name = labelOf(control);
      var placed = control.closest && control.closest('[data-pkb-agent]');
      sense('click', control, {
        id: (placed && placed.getAttribute('data-pkb-id')) || (control.id || node.id || ''),
        name: name
      });
    }, true);
    on(doc, 'change', function (event) {
      var input = eventLeaf(event);
      if (!input || !input.closest) return;
      var row = input.closest('[data-pkb-check]');
      if (!row) return;
      var host = input.closest('[data-pkb-id]');
      var on = input.checked === true || row.getAttribute('data-pkb-checked') === 'true';
      if (input.type === 'checkbox') on = !!input.checked;
      row.setAttribute('data-pkb-checked', on ? 'true' : 'false');
      lastPressed = row;
      setFeedback(row, 'sending');
      pushEvent({
        type: 'check', source: 'human',
        id: host ? host.getAttribute('data-pkb-id') || '' : '',
        check: row.getAttribute('data-pkb-check') || '',
        checked: on ? 'true' : 'false',
        name: row.getAttribute('data-pkb-check') || ''
      });
    }, true);
    var inputTimer = 0;
    var inputEl = null;
    on(doc, 'input', function (event) {
      if (window.__pkbRecord !== true) return;
      var input = eventLeaf(event);
      if (!input || ours(input)) return;
      inputEl = input;
      if (inputTimer) clearTimeout(inputTimer);
      inputTimer = setTimeout(function () {
        inputTimer = 0;
        if (!inputEl) return;
        var tag = (inputEl.nodeName || '').toLowerCase();
        sense(tag === 'select' ? 'select' : 'input', inputEl, {value: inputEl.value == null ? '' : String(inputEl.value)});
      }, 400);
    }, true);
    on(doc, 'change', function (event) {
      if (window.__pkbRecord !== true) return;
      var input = eventLeaf(event);
      if (!input || ours(input) || input.closest && input.closest('[data-pkb-check]')) return;
      var tag = (input.nodeName || '').toLowerCase();
      if (tag === 'select') sense('select', input, {value: input.value == null ? '' : String(input.value)});
      else if (input.type === 'checkbox' || input.type === 'radio') {
        sense('check', input, {checked: input.checked ? 'true' : 'false', value: input.checked ? 'true' : 'false'});
      } else sense('change', input, {value: input.value == null ? '' : String(input.value)});
    }, true);
    on(doc, 'keydown', function (event) {
      if (window.__pkbRecord !== true) return;
      if (!event) return;
      var key = event.key || '';
      if (key !== 'Enter' && key !== 'Tab' && key !== 'Escape') return;
      var target = eventLeaf(event);
      if (!target || ours(target)) return;
      sense('key', target, {value: key, name: labelOf(target) || key});
    }, true);
    var hoverTimer = 0;
    var hoverEl = null;
    on(doc, 'pointerover', function (event) {
      if (window.__pkbRecord !== true || window.__pkbRecordNoise !== true) return;
      var node = eventLeaf(event);
      if (!node || ours(node)) return;
      var tag = (node.nodeName || '').toLowerCase();
      if (tag === 'iframe' || tag === 'frame') return;
      hoverEl = node;
      if (hoverTimer) clearTimeout(hoverTimer);
      hoverTimer = setTimeout(function () {
        hoverTimer = 0;
        if (hoverEl) sense('hover', hoverEl, {});
      }, 350);
    }, true);
    on(doc, 'focusin', function (event) {
      if (window.__pkbRecord !== true || window.__pkbRecordNoise !== true) return;
      var node = eventLeaf(event);
      if (!node || ours(node)) return;
      sense('focus', node, {});
    }, true);
    var dragFrom = '';
    on(doc, 'dragstart', function (event) {
      if (window.__pkbRecord !== true) return;
      var node = eventLeaf(event);
      dragFrom = labelOf(controlOf(node));
    }, true);
    on(doc, 'drop', function (event) {
      if (window.__pkbRecord !== true) return;
      var node = eventLeaf(event);
      if (!node) return;
      sense('drag', node, {name: dragFrom || labelOf(controlOf(node)), drop: labelOf(controlOf(node))});
    }, true);
    return true;
  }

  function adoptFrames(doc) {
    if (!doc || !doc.querySelectorAll) return;
    var frames;
    try { frames = doc.querySelectorAll('iframe,frame'); } catch (ignored) { return; }
    for (var i = 0; i < frames.length; i++) {
      var frame = frames[i];
      if (frame && !frame.__pkbFrameHook) {
        frame.__pkbFrameHook = true;
        on(frame, 'load', function () { adoptFrames(document); });
      }
      var child = null;
      try { child = frame.contentDocument; } catch (ignored) { child = null; }
      if (!child || child === doc || !child.documentElement) continue;
      wireSense(child);
      adoptFrames(child);
    }
  }

  function armSense() {
    wireSense(document);
    adoptFrames(document);
    if (!window.__pkbFrameWatch && window.MutationObserver && document.documentElement) {
      window.__pkbFrameWatch = new MutationObserver(function () { adoptFrames(document); });
      try { window.__pkbFrameWatch.observe(document.documentElement, {childList: true, subtree: true}); } catch (ignored) { /* not observable */ }
    }
    return true;
  }

  function publishRecord(on) {
    var msg = {pkb: 'record', on: on === true};
    function walk(win) {
      var list;
      try { list = win.frames; } catch (ignored) { return; }
      for (var i = 0; i < list.length; i++) {
        try { list[i].postMessage(msg, '*'); } catch (ignored) { /* cross-origin */ }
        walk(list[i]);
      }
    }
    walk(window);
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
    on(document, 'toggle', function (event) {
      if (event && event.target === layer) return;
      raiseLayer();
      schedule();
    }, true);
    on(document, 'pointerdown', function (event) {
      var leaf = eventLeaf(event);
      if (!leaf || !leaf.closest) return;
      var hit = leaf.closest('button,[role="button"],[data-pkb-check],.pkb-step,.ask button');
      if (!hit) return;
      if (!ours(hit) && !(hit.closest && hit.closest(OWN))) return;
      lastPressed = hit;
      setFeedback(hit, 'sending');
    }, true);
    armSense();
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
    tagTree(host, purposeFor(spec.kind || host.getAttribute('data-pkb-kind') || ''));
    var r = anchor.getBoundingClientRect();
    var item = {
      id: id, host: host, anchor: anchor, kind: spec.kind || host.getAttribute('data-pkb-kind') || '', at: at,
      baseL: num(host.style.left), baseT: num(host.style.top),
      sizeW: num(host.style.width), sizeH: num(host.style.height),
      origin: spec.origin && isFinite(spec.origin.l) ? {l: +spec.origin.l, t: +spec.origin.t} : {l: r.left, t: r.top}, locator: locatorOf(anchor), scrollers: scrollersOf(anchor),
      cache: {}, ringCache: {}, lost: false, cloneDirty: spec.kind === 'magnifier', block: isBlocked(spec)
    };
    if ((item.kind === 'overlay' && at !== 'over') || (item.block && !onTarget(item.kind, at))) item.ring = makeRing(id);
    applyBlock(item);
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
    try { popIn('editor', true); } catch (ignored) { /* pop not open */ }
    try { popIn('chat', true); } catch (ignored) { /* pop not open */ }
    try { editorClose(); } catch (ignored) { /* editor not open */ }
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
    return items.size === 0 && !panel && !editor && guides.size === 0 && asks.size === 0;
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
      var idHit = byDomId(idToken(text));
      if (idHit && !ours(idHit)) return idHit;
      var marked = document.querySelector('[data-pkb-handle="' + CSS.escape(text) + '"]');
      if (marked) return marked;
      return document.querySelector(text);
    } catch (bad) {
      return null;
    }
  }

  function idToken(text) {
    if (!text) return null;
    var attr = /^\[id=(["'])([^"']+)\1\]$/.exec(text);
    if (attr) return attr[2];
    if (text.charAt(0) === '#') return text.slice(1);
    if (/^[A-Za-z0-9][\w:.-]*$/.test(text)) return text;
    return null;
  }

  function byDomId(id) {
    if (!id) return null;
    var hit = document.getElementById(id);
    if (hit) return hit;
    var quoted = id.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
    try {
      hit = document.querySelector('[id="' + quoted + '"]');
      if (hit) return hit;
    } catch (ignored) { /* bad id */ }
    try {
      hit = document.querySelector('#' + CSS.escape(id));
      if (hit) return hit;
    } catch (ignored) { /* selector rejected */ }
    return null;
  }

  function sensitive(el, step) {
    if (step && step.sensitive) return true;
    if (!el || !el.getAttribute) return false;
    var type = (el.getAttribute('type') || '').toLowerCase();
    var auto = (el.getAttribute('autocomplete') || '').toLowerCase();
    return type === 'password' || auto.indexOf('password') >= 0 || auto.indexOf('cc-') >= 0 || auto.indexOf('one-time-code') >= 0;
  }

  function tipText(node, n, say, value) {
    node.textContent = '';
    var b = document.createElement('b');
    b.textContent = n + '. ';
    tagMeta(b, metaPurpose || 'guide');
    node.appendChild(b);
    appendDistinguished(node, instruction(say, value));
  }

  function guideRender(g) {
    metaPurpose = 'guide';
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
      var state = step.done ? 'done' : (!step.anchor && step.missing ? 'missing' : (i === g.current ? 'current' : 'todo'));
      step.badge.className = 'badge ' + state;
      step.badge.textContent = step.done ? '\u2713' : String(step.n);
      step.ring.className = 'ring ' + state + (i === g.current && g.pulse && step.anchor ? ' pulse' : '');
      step.state = state;
      if (step.anchor && step.anchor.setAttribute) {
        try { step.anchor.setAttribute('data-pkb-guide-step', g.id + ' step ' + step.n + '/' + g.steps.length + ' ' + state); } catch (ignored) { /* detached */ }
      }
    });
    g.pulse = false;
    if (!g.tip) {
      g.tip = el('div', 'tip');
      g.tip.setAttribute('role', 'status');
      root.appendChild(g.tip);
    }
    var cur = g.steps[g.current];
    if (cur && cur.anchor) tipText(g.tip, cur.n, cur.say, cur.expect);
    g.tip.hidden = !(cur && cur.anchor);
    g.tip.classList.toggle('missing', !!(cur && !cur.anchor));
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

  function guideBadge() {
    if (!panel || !panel.tabs) return;
    var tab = null;
    panel.tabs.forEach(function (b) { if (b.getAttribute('data-tab') === 'steps') tab = b; });
    var badge = tab && tab.querySelector('.pkb-badge');
    if (!badge) return;
    var open = 0;
    guides.forEach(function (g) { g.steps.forEach(function (st) { if (!st.done) open++; }); });
    var show = open && panel.state.tab !== 'steps' ? String(open) : '';
    badge.textContent = show;
    badge.setAttribute('data-pkb-badge', show);
  }

  function stepsList() {
    metaPurpose = 'guide';
    if (!panel) return;
    guideBadge();
    var pane = panel.stepsPane;
    pane.textContent = '';
    if (!guides.size) { pane.appendChild(el('div', 'pkb-empty', 'No guide yet.')); return; }
    guides.forEach(function (g) {
      var list = el('ol', 'pkb-steps');
      g.steps.forEach(function (step) {
        var row = el('li', 'pkb-step');
        row.setAttribute('data-state', step.state || 'todo');
        row.setAttribute('data-step', String(step.n));
        row.appendChild(el('span', 'pkb-num', step.done ? '\u2713' : String(step.n)));
        var say = el('span', 'pkb-say');
        appendDistinguished(say, instruction(step.say, step.expect));
        row.appendChild(say);
        list.appendChild(row);
      });
      pane.appendChild(list);
    });
  }

  function placeAt(node, x, y) {
    node.style.transform = 'translate3d(' + Math.round(x) + 'px,' + Math.round(y) + 'px,0)';
  }

  // A step badge sits outside its target so it never covers a field's text: left of it, else
  // above, else below; only when all three are cut off by the viewport does it go inside the corner.
  var BADGE_GAP = 6;
  function badgeSpot(r, bw, bh, vp) {
    var midY = r.top + (r.height - bh) / 2;
    if (r.left - bw - BADGE_GAP >= 0) return {x: r.left - bw - BADGE_GAP, y: midY};
    var x = Math.max(0, r.left);
    if (r.top - bh - BADGE_GAP >= 0) return {x: x, y: r.top - bh - BADGE_GAP};
    if (r.bottom + BADGE_GAP + bh <= vp.h) return {x: x, y: r.bottom + BADGE_GAP};
    return {x: x + 2, y: Math.max(0, r.top) + 2};
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
          step.missing = true;
          step.badge.style.visibility = 'hidden';
          step.ring.style.visibility = 'hidden';
          if (i === g.current) {
            g.tip.hidden = true;
            g.tip.style.visibility = 'hidden';
          }
          return;
        }
        step.missing = false;
        var r = step.anchor.getBoundingClientRect();
        var v = visibleRect({scrollers: step.scrollers || []}, r, vp);
        var vis = v.empty ? 'hidden' : '';
        step.badge.style.visibility = vis;
        step.ring.style.visibility = vis;
        var b = badgeSpot(r, step.badge.offsetWidth || 22, step.badge.offsetHeight || 22, vp);
        placeAt(step.badge, b.x, b.y);
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
    // by says who did it: a person's click, typing, or blur is human; agent acts mark the root first.
    var ev = {type: 'step', g: g.id, s: step.n, done: true, how: how, by: how === 'agent' || actingSource() === 'agent' ? 'agent' : 'human'};
    if (how === 'input' || how === 'select' || how === 'check') ev.filled = true;
    if (value !== undefined) ev.value = value;
    pushEvent(ev);
    var next = from + 1;
    while (next < g.steps.length && g.steps[next].done) next++;
    g.current = next;
    g.pulse = true;
    if (g.current >= g.steps.length) {
      pushEvent({type: 'guide', g: g.id, done: true, by: ev.by});
      guideClose(g.id);
      return;
    }
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
    if (want === 'input') {
      if (kind === 'keydown' || kind === 'keyup' || kind === 'blur') return true;
      return (kind === 'input' || kind === 'change') && !!String(a.value || '').length;
    }
    if (kind === 'click') return !(tag === 'input' && type !== 'checkbox' && type !== 'radio' && type !== 'button' && type !== 'submit') && tag !== 'textarea' && tag !== 'select';
    return (kind === 'input' || kind === 'change') && (a.type === 'checkbox' ? !!a.checked : !!String(a.value || '').length);
  }

  function onGuideEvent(ev) {
    if (!guides.size || ours(ev.target)) return;
    guides.forEach(function (g) {
      var step = g.steps[g.current];
      if (!step) return;
      if (!step.anchor || !step.anchor.isConnected) step.anchor = findTarget(step.t);
      if (!matches(step, ev.target, ev.type)) return;
      var how = ev.type === 'click' ? 'click' : ((step.anchor.nodeName || '').toLowerCase() === 'select' ? 'select' : (step.anchor.type === 'checkbox' ? 'check' : 'input'));
      var at = g.current;
      if (step.timer) { clearTimeout(step.timer); step.timer = 0; }
      var finish = function () {
        step.timer = 0;
        if (g.current !== at || step.done || !guides.has(g.id)) return;
        // Read the field when the person finishes, so the value is the whole entry.
        var value = step.capture && how !== 'click' && !sensitive(step.anchor, step) ? String(step.anchor.value || '').slice(0, 500) : undefined;
        setFeedback(step.badge, 'sending');
        guideAdvance(g, at, how, value);
        setFeedback(step.badge, 'delivered');
      };
      var typing = how === 'input' && (ev.type === 'input' || ev.type === 'change' || ev.type === 'keyup' || ev.type === 'keydown' || ev.type === 'blur');
      if (typing) {
        var valueNow = String(step.anchor.value || '');
        var expected = step.expect == null ? '' : String(step.expect);
        var explicit = ev.type === 'blur' || (ev.type === 'keydown' && (ev.key === 'Enter' || ev.keyCode === 13));
        var matched = expected !== '' && valueNow === expected;
        // A pause or a single keystroke does not finish the step.
        if (!valueNow.length) return;
        if (explicit || matched) finish();
        return;
      }
      finish();
    });
  }

  function guideListen() {
    if (guideListening) return;
    guideListening = true;
    on(document, 'click', onGuideEvent, true);
    on(document, 'input', onGuideEvent, true);
    on(document, 'change', onGuideEvent, true);
    on(document, 'keydown', onGuideEvent, true);
    on(document, 'blur', onGuideEvent, true);
  }

  function guide(spec) {
    spec = spec || {};
    var steps = Array.isArray(spec.steps) ? spec.steps : [];
    var id = String(spec.id || 'g1');
    guideClose(id);
    listen();
    guideListen();
    var g = {id: id, current: 0, scroll: !!spec.scroll, pulse: true, spec: {id: id, scroll: !!spec.scroll, steps: steps.map(function (st) {
      return {t: st.t, say: st.say, do: st.do, n: st.n, sensitive: st.sensitive, capture: st.capture, expect: st.expect};
    })}, steps: steps.map(function (st, i) {
      return {t: st.t, say: st.say, do: st.do || 'any', n: st.n || i + 1, sensitive: !!st.sensitive, capture: !!st.capture, expect: st.expect == null ? '' : String(st.expect), done: false};
    })};
    var miss = [];
    g.steps.forEach(function (step) {
      step.anchor = findTarget(step.t);
      step.scrollers = step.anchor ? scrollersOf(step.anchor) : [];
      step.missing = !step.anchor;
      if (!step.anchor) miss.push(step.n);
    });
    if (typeof spec.at === 'number' && spec.at > 1) {
      for (var k = 0; k < g.steps.length && g.steps[k].n < spec.at; k++) g.steps[k].done = true;
      g.current = Math.min(k, g.steps.length);
    }
    var current = g.steps[g.current];
    var unresolved = !!(current && !current.anchor);
    if (unresolved) {
      return {id: id, steps: g.steps.length, current: current ? current.n : null, miss: miss, unresolved: true,
        flag: 'step ' + current.n + ' target was not found'};
    }
    guides.set(id, g);
    guideRender(g);
    if (panel && spec.tab !== false) panelTab('steps');
    update();
    return {id: id, steps: g.steps.length, current: current ? current.n : null, miss: miss, unresolved: unresolved,
      flag: unresolved ? 'step ' + current.n + ' target was not found' : ''};
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
      pushEvent({type: 'step', g: id, s: g.steps[g.current].n, done: false, how: 'agent'});
      guideRender(g);
      schedule();
    }
    return {id: id, current: g.steps[g.current] ? g.steps[g.current].n : null, total: g.steps.length};
  }

  function clearFieldChrome(g) {
    if (!g) return;
    g.steps.forEach(function (step) {
      if (step.timer) { clearTimeout(step.timer); step.timer = 0; }
      if (step.badge) { step.badge.remove(); step.badge = null; }
      if (step.ring) { step.ring.remove(); step.ring = null; }
      if (step.anchor && step.anchor.removeAttribute) { try { step.anchor.removeAttribute('data-pkb-guide-step'); } catch (ignored) { /* gone */ } }
    });
    if (g.tip) { g.tip.remove(); g.tip = null; }
  }

  function guideClose(id) {
    var key = String(id || 'g1');
    var g = guides.get(key);
    if (!g) return false;
    clearFieldChrome(g);
    guides.delete(key);
    stepsList();
    window.__pkbGuideState = guideState();
    if (idle() && layer && layer.isConnected) layer.remove();
    return true;
  }

  function guideDone(spec) {
    var id = String((spec && spec.id) || 'g1');
    if (!guides.has(id)) return {id: id, closed: true};
    pushEvent({type: 'guide', g: id, done: true, how: 'agent'});
    guideClose(id);
    return {id: id, closed: true};
  }

  var ANSWER_CONFIRM_MS = 1500;

  /** Comma list or a JSON array. A label keeps its spaces. A JSON label may contain a comma. */
  function choiceList(raw) {
    var out = [];
    function push(value) {
      value = String(value == null ? '' : value).trim();
      if (!value || out.indexOf(value) >= 0) return;
      out.push(value);
    }
    function fromString(value) {
      value = String(value == null ? '' : value).trim();
      if (!value) return;
      if (value.charAt(0) === '[') {
        try {
          var parsed = JSON.parse(value);
          if (Array.isArray(parsed)) {
            parsed.forEach(push);
            return;
          }
        } catch (ignored) { /* not JSON; split on commas below */ }
      }
      if (value.indexOf(',') >= 0) {
        value.split(',').forEach(push);
        return;
      }
      push(value);
    }
    if (Array.isArray(raw)) {
      if (raw.length === 1) fromString(raw[0]);
      else raw.forEach(push);
    } else fromString(raw);
    return out;
  }

  function ask(spec) {
    metaPurpose = 'callout';
    spec = spec || {};
    var id = String(spec.id || 'q1');
    askClose(id, 'replace');
    listen();
    var root = ensureLayer();
    var node = el('form', 'ask');
    node.setAttribute('role', 'dialog');
    node.setAttribute('aria-label', 'Question from the agent');
    var q = el('div');
    var b = document.createElement('b');
    tagMeta(b, 'callout');
    appendDistinguished(b, String(spec.say || ''));
    q.appendChild(b);
    node.appendChild(q);
    var choices = choiceList(spec.choices);
    var row = el('div', 'row');
    var field = null;
    function answer(value, btn) {
      metaPurpose = 'callout';
      var picked = String(value == null ? '' : value);
      var note = el('div', 'pkb-confirm');
      note.appendChild(document.createTextNode('Answer sent: ' + picked));
      node.appendChild(note);
      if (btn) setFeedback(btn, 'sending');
      var ev = {type: 'answer', id: id, choice: picked, value: picked};
      if (spec.sensitive) { delete ev.value; delete ev.choice; ev.filled = true; }
      var bound = pushEvent(ev);
      if (btn) setFeedback(btn, bound ? 'delivered' : 'sending');
      node.setAttribute('data-pkb-card-state', 'answered');
      cardTrail(id, spec.say, 'answered', spec.sensitive ? '' : picked);
      setTimeout(function () { askClose(id, 'answered'); }, ANSWER_CONFIRM_MS);
    }
    if (choices.length) {
      choices.forEach(function (choice, i) {
        var btn = el('button', i === 0 ? '' : 'secondary', choice);
        btn.type = 'button';
        btn.setAttribute('data-choice', choice);
        tagMeta(btn, 'card-option');
        btn.addEventListener('click', function (event) {
          event.preventDefault();
          event.stopPropagation();
          answer(btn.getAttribute('data-choice'), btn);
        });
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
    node.setAttribute('data-pkb-id', 'ask-' + id);
    node.setAttribute('data-pkb-card', 'ask');
    node.setAttribute('data-pkb-card-id', 'ask-' + id);
    node.setAttribute('data-pkb-card-title', String(spec.say || '').slice(0, 80));
    node.setAttribute('data-pkb-card-state', 'open');
    tagMeta(node, 'callout card');
    cardTrail(id, spec.say, 'open', '');
    isolateKeys(node);
    root.appendChild(node);
    asks.set(id, {id: id, node: node, t: spec.t, anchor: spec.t ? findTarget(spec.t) : null, scrollers: [],
      shownAt: Date.now(), minShow: Math.max(0, +spec.minShow || 0)});
    update();
    if (field) { try { field.focus({preventScroll: true}); } catch (ignored) { /* focus refused */ } }
    return {id: id, choices: choices.length};
  }

  // The last few cards stay readable after they close: one JSON attribute on <html>, from any world.
  function cardTrail(id, title, state, choice) {
    try {
      var root = document.documentElement;
      var trail = JSON.parse(root.getAttribute('data-pkb-card-trail') || '[]');
      if (!Array.isArray(trail)) trail = [];
      var key = 'ask-' + id;
      var row = null;
      trail.forEach(function (r) { if (r && r.id === key) row = r; });
      if (!row) { row = {id: key, title: String(title || '').slice(0, 80), at: Date.now()}; trail.push(row); }
      if (row.state === 'answered' && state === 'closed') return;
      row.state = state;
      if (choice) row.choice = String(choice).slice(0, 80);
      row.changed = Date.now();
      if (trail.length > 8) trail = trail.slice(-8);
      root.setAttribute('data-pkb-card-trail', JSON.stringify(trail));
    } catch (ignored) { /* attribute refused */ }
  }

  function askClose(id, reason) {
    var a = asks.get(String(id));
    if (!a) return false;
    // ask --min-show holds an unanswered card on screen at least that long; an answer or a replacement closes it now.
    var held = a.minShow - (Date.now() - a.shownAt);
    if (held > 0 && reason !== 'answered' && reason !== 'replace') {
      if (!a.closing) {
        a.closing = true;
        setTimeout(function () { if (asks.get(String(id)) === a) { a.minShow = 0; askClose(id, reason); } }, held);
      }
      return true;
    }
    cardTrail(String(id), a.node.getAttribute('data-pkb-card-title'), reason === 'timeout' ? 'timed-out' : (reason || 'closed'), '');
    a.node.remove();
    asks.delete(String(id));
    if (idle() && layer && layer.isConnected) layer.remove();
    return true;
  }

  function rectsOf(skip) {
    var boxes = [{left: 0, top: 0, right: 160, bottom: 56, width: 160, height: 56}];
    items.forEach(function (item) {
      if (!item.host || item.host === skip) return;
      var r = item.host.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) boxes.push(r);
    });
    asks.forEach(function (a) {
      if (!a.node || a.node === skip) return;
      var r = a.node.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) boxes.push(r);
    });
    return boxes;
  }

  function hits(x, y, w, h, boxes) {
    var r1 = x + w;
    var b1 = y + h;
    for (var i = 0; i < boxes.length; i++) {
      var b = boxes[i];
      if (r1 <= b.left || b.right <= x || b1 <= b.top || b.bottom <= y) continue;
      return true;
    }
    return false;
  }

  function shiftClear(x, y, w, h, vp, skip) {
    var boxes = rectsOf(skip);
    var nx = x;
    var ny = Math.max(y, 64);
    for (var n = 0; n < 12; n++) {
      if (!hits(nx, ny, w, h, boxes)) return {x: Math.max(8, nx), y: Math.max(64, ny)};
      ny += Math.max(24, Math.round(h * 0.6));
      if (ny + h > vp.h - 8) { ny = 72; nx = Math.max(8, nx - 48); }
    }
    return {x: Math.max(8, nx), y: Math.max(72, ny)};
  }

  function asksUpdate(vp) {
    asks.forEach(function (a) {
      if (a.t && (!a.anchor || !a.anchor.isConnected)) a.anchor = findTarget(a.t);
      var w = a.node.offsetWidth || 260;
      var h = a.node.offsetHeight || 80;
      if (a.anchor) {
        var r = a.anchor.getBoundingClientRect();
        var spot = beside(r, w, h, 'right', vp);
        var clear = shiftClear(spot.x, spot.y, w, h, vp, a.node);
        placeAt(a.node, clear.x, clear.y);
      } else {
        var clearTop = shiftClear(Math.max(8, (vp.w - w) / 2), 72, w, h, vp, a.node);
        placeAt(a.node, clearTop.x, clearTop.y);
      }
    });
  }

  // ---- Panel: chat and steps in the page (shadow root, keyboard-isolated, draggable, resizable) ----
  var PANEL_CSS = "__PKB_PANEL_CSS__";
  var PANEL_MIN_W = 260;
  var PANEL_MIN_H = 200;
  var SNAP = 16;
  var panel = null;
  var editor = null;
  var chatSeq = 0;
  var chatMirror = [];
  var pops = {editor: null, chat: null};

  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    tagMeta(node, metaPurpose);
    return node;
  }

  /** Quotes a concrete value. The quote characters stay in the text. */
  function quoteValue(value) {
    var text = String(value == null ? '' : value);
    return '"' + text.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  }

  /** Quotes a known value inside instructional text when it is not already quoted. */
  function instruction(text, value) {
    var words = String(text == null ? '' : text);
    if (value == null || value === '') return words;
    var quoted = quoteValue(value);
    if (words.indexOf(quoted) >= 0) return words;
    var raw = String(value);
    var at = words.indexOf(raw);
    if (at >= 0) {
      var open = at > 0 && words.charAt(at - 1) === '"';
      var after = at + raw.length;
      var close = after < words.length && words.charAt(after) === '"';
      if (!(open && close)) return words.slice(0, at) + quoted + words.slice(at + raw.length);
    }
    return words ? words + ' ' + quoted : quoted;
  }

  /** Paints each quoted span as a chip. textContent of the node stays the original sentence. */
  function appendDistinguished(node, text) {
    var src = String(text == null ? '' : text);
    var re = /"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/g;
    var last = 0;
    var match;
    var any = false;
    while ((match = re.exec(src))) {
      any = true;
      if (match.index > last) node.appendChild(document.createTextNode(src.slice(last, match.index)));
      var chip = document.createElement('span');
      chip.className = 'pkb-value';
      chip.textContent = match[0];
      tagMeta(chip, metaPurpose);
      node.appendChild(chip);
      last = match.index + match[0].length;
    }
    if (!any) node.appendChild(document.createTextNode(src));
    else if (last < src.length) node.appendChild(document.createTextNode(src.slice(last)));
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
    // The header grab sits about 30px in and 18px down. Leave room for a (-400,-250) drag.
    var maxW = Math.max(PANEL_MIN_W, vp.w - 16 - 370);
    var maxH = Math.max(PANEL_MIN_H, vp.h - 16 - 232);
    if (w > maxW) w = maxW;
    if (h > maxH) h = maxH;
    return {x: vp.w - w - 16, y: vp.h - h - 16, w: w, h: h, min: false, tab: 'chat', pop: false, closed: false};
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
    var closed = !!st.closed || !card.isConnected;
    card.style.transform = 'translate3d(' + Math.round(st.x) + 'px,' + Math.round(st.y) + 'px,0)';
    card.style.width = Math.round(st.w) + 'px';
    card.style.height = Math.round(st.h) + 'px';
    card.hidden = !!st.min || !!st.pop || closed;
    panel.pill.hidden = !st.min && !st.pop && !closed;
    var label = panel.pill.querySelector('.pkb-pill-label');
    if (label) label.textContent = st.pop ? 'Chat window' : 'Agent';
    panel.pill.setAttribute('aria-label', st.pop ? 'Pop chat back in' : 'Open agent panel');
    window.__pkbPanelState = closed ? null : {x: Math.round(st.x), y: Math.round(st.y), w: Math.round(st.w), h: Math.round(st.h), min: !!st.min, tab: st.tab, pop: !!st.pop};
    recountUnread();
    dockPlace();
    dockSave();
  }

  function panelTab(name) {
    if (!panel) return;
    panel.state.tab = name;
    panel.tabs.forEach(function (b) {
      var on = b.getAttribute('data-tab') === name;
      b.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    panel.chatPane.hidden = name !== 'chat';
    panel.stepsPane.hidden = name !== 'steps';
    if (panel.recordPane) panel.recordPane.hidden = name !== 'record';
    panel.composer.hidden = name !== 'chat';
    if (name === 'chat') panel.chatPane.scrollTop = panel.chatPane.scrollHeight;
    if (name === 'steps') stepsList();
    guideBadge();
    panelPlace();
  }

  function panelUnread() {
    recountUnread();
  }

  function bubble(row) {
    metaPurpose = 'panel';
    var from = row.from || (row.source === 'human' ? 'human' : (row.source === 'system' ? 'system' : 'agent'));
    var node = el('div', 'pkb-msg');
    node.setAttribute('data-from', from);
    if (row.id) node.setAttribute('data-id', row.id);
    node.setAttribute('data-pkb-msg-id', String(row.id || ''));
    node.setAttribute('data-pkb-msg-author', from);
    node.setAttribute('data-pkb-msg-status', String(row.state === 'pending' ? 'sending' : (row.state === 'sent' ? 'delivered' : (row.state || 'shown'))));
    var who = from === 'human' ? 'You' : (from === 'system' ? 'System' : ('Agent' + (row.agent ? ' ' + row.agent : '')));
    var meta = el('span', 'pkb-meta', who + ' \u00b7 ' + clock(row.at));
    node.appendChild(meta);
    appendDistinguished(node, String(row.text == null ? '' : row.text));
    if (row.state === 'sending' || row.state === 'pending' || row.state === 'delivered' || row.state === 'read' || row.state === 'sent') {
      setFeedback(node, row.state === 'pending' ? 'sending' : (row.state === 'sent' ? 'delivered' : row.state));
    } else if (row.state) {
      node.setAttribute('data-state', row.state);
    }
    return node;
  }

  function chat(rows) {
    metaPurpose = 'panel';
    if (!panel) return 0;
    var list = Array.isArray(rows) ? rows : [rows];
    var added = 0;
    var pane = panel.chatPane;
    var stick = pane.scrollHeight - pane.scrollTop - pane.clientHeight < 40;
    list.forEach(function (row) {
      if (!row || row.text == null) return;
      var key = row.id ? String(row.id) : '';
      if (key && panel.seen[key]) return;
      var from = row.from || row.source || 'agent';
      // The same message can arrive twice: the composer's local id, then the stored chat id. One bubble.
      var twin = chatTwin(from, String(row.text), row.at);
      if (twin) {
        if (key) {
          panel.seen[key] = true;
          var shown = pane.querySelector('.pkb-msg[data-id="' + CSS.escape(String(twin.id)) + '"]');
          if (shown) shown.setAttribute('data-alt-id', key);
          twin.alt = key;
        }
        return;
      }
      if (key) panel.seen[key] = true;
      if (panel.empty) { panel.empty.remove(); panel.empty = null; }
      pane.appendChild(bubble(row));
      added++;
      chatMirror.push({
        id: key,
        from: from,
        text: String(row.text),
        state: row.state || '',
        at: row.at || '',
        seen: !!replaying,
        ms: Date.now()
      });
      if ((row.from || row.source) !== 'human' && row.state !== 'read') panelUnread();
    });
    if (stick) pane.scrollTop = pane.scrollHeight;
    syncPop('chat');
    return added;
  }

  var replaying = false;
  var TWIN_MS = 20000;

  function chatTwin(from, text, at) {
    var when = at ? Date.parse(at) : NaN;
    for (var i = chatMirror.length - 1; i >= 0 && i >= chatMirror.length - 50; i--) {
      var row = chatMirror[i];
      if (!row || row.text !== text) continue;
      var a = row.from === 'human' ? 'human' : row.from;
      var b = from === 'human' ? 'human' : from;
      if (a !== b) continue;
      var then = row.at ? Date.parse(row.at) : NaN;
      if (isNaN(when) || isNaN(then) || Math.abs(when - then) <= TWIN_MS || Date.now() - row.ms <= TWIN_MS) return row;
    }
    return null;
  }

  function chatSent(ids) {
    if (!panel || !ids) return 0;
    var n = 0;
    ids.forEach(function (id) {
      var node = panel.chatPane.querySelector('.pkb-msg[data-id="' + CSS.escape(String(id)) + '"]');
      if (!node) return;
      if (node.getAttribute('data-state') === 'read') return;
      setFeedback(node, 'delivered');
      chatMirror.forEach(function (row) {
        if (String(row.id) === String(id) && row.state !== 'read') row.state = 'delivered';
      });
      n++;
    });
    syncPop('chat');
    return n;
  }

  function markRead(ids) {
    var list = Array.isArray(ids) ? ids : (ids ? [ids] : []);
    var n = 0;
    list.forEach(function (id) {
      if (id == null || id === '') return;
      var key = CSS.escape(String(id));
      var nodes = [];
      if (panel) {
        panel.chatPane.querySelectorAll('.pkb-msg[data-id="' + key + '"],.pkb-msg[data-alt-id="' + key + '"]').forEach(function (bubble) { nodes.push(bubble); });
      }
      document.querySelectorAll('[data-pkb-id="' + key + '"],[data-feedback-id="' + key + '"]').forEach(function (node) { nodes.push(node); });
      if (layer && layer.shadowRoot) {
        layer.shadowRoot.querySelectorAll('[data-pkb-id="' + key + '"],[data-id="' + key + '"]').forEach(function (node) { nodes.push(node); });
      }
      nodes.forEach(function (node) { setFeedback(node, 'read'); n++; });
      chatMirror.forEach(function (row) {
        if (String(row.id) === String(id) || String(row.alt || '') === String(id)) row.state = 'read';
      });
    });
    syncPop('chat');
    return n;
  }

  function readIds() {
    var ids = [];
    chatMirror.forEach(function (row) {
      if (row && row.state === 'read' && row.id) ids.push(String(row.id));
    });
    return ids;
  }

  function parkCards() {
    var hosts = [];
    var seen = new Set();
    function walk(root) {
      if (!root || !root.querySelectorAll || seen.has(root)) return;
      seen.add(root);
      Array.prototype.forEach.call(root.querySelectorAll('[data-pkb-id]'), function (el) {
        if (el.querySelector('[data-pkb-card]') || el.hasAttribute('data-pkb-card')) hosts.push(el);
      });
      Array.prototype.forEach.call(root.querySelectorAll('*'), function (n) { if (n.shadowRoot) walk(n.shadowRoot); });
    }
    walk(document);
    var vp = viewport();
    var y = 24;
    hosts.forEach(function (host) {
      if (host.getAttribute('data-pkb-parked') === 'user') return;
      var w = host.offsetWidth || 220;
      var h = host.offsetHeight || 48;
      var x = Math.max(16, vp.w - w - 16);
      if (x < 168) x = 168;
      host.style.left = Math.round(x) + 'px';
      host.style.top = Math.round(y) + 'px';
      y += h + 8;
      if (y > vp.h - 48) y = 72;
      if (!host.__pkbDrag) {
        host.__pkbDrag = true;
        var drag = null;
        host.addEventListener('pointerdown', function (event) {
          if (event.button !== 0) return;
          if (event.target && event.target.closest && event.target.closest('input,button,a,textarea,select')) return;
          drag = {id: event.pointerId, x: event.clientX, y: event.clientY, l: parseFloat(host.style.left) || 0, t: parseFloat(host.style.top) || 0};
          try { host.setPointerCapture(event.pointerId); } catch (ignored) { /* synthetic */ }
        });
        host.addEventListener('pointermove', function (event) {
          if (!drag || event.pointerId !== drag.id) return;
          host.style.left = Math.round(drag.l + event.clientX - drag.x) + 'px';
          host.style.top = Math.round(drag.t + event.clientY - drag.y) + 'px';
          host.setAttribute('data-pkb-parked', 'user');
        });
        host.addEventListener('pointerup', function (event) {
          if (!drag || event.pointerId !== drag.id) { drag = null; return; }
          var x = Math.round(parseFloat(host.style.left) || 0);
          var y = Math.round(parseFloat(host.style.top) || 0);
          var moved = x !== Math.round(drag.l) || y !== Math.round(drag.t);
          drag = null;
          if (moved) pushEvent({type: 'card', action: 'drag', x: x, y: y, id: host.getAttribute('data-pkb-id') || ''});
        });
      }
    });
    return {parked: hosts.length};
  }

  function panelSend() {
    var text = panel.input.value;
    if (!text.trim()) return;
    var id = 'h' + (++chatSeq) + '-' + Date.now().toString(36);
    window.__pkbChat = window.__pkbChat || [];
    window.__pkbChat.push({id: id, text: text, at: new Date().toISOString()});
    chat({id: id, from: 'human', text: text, state: 'sending', at: new Date().toISOString()});
    var bound = pushEvent({type: 'chat', source: 'human', id: id, text: text});
    if (bound) noteDelivered({id: id});
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

  // ---- Unified toolbar (C9) ----
  // One dock holds a button per tool (agent panel, step editor, recording). A collapsed or closed tool
  // lives on its button; the handle collapses the whole dock and drags it. Position, the collapsed
  // flag, and the open panel are kept per origin in localStorage under pkb-dock.
  var dock = null;
  var DOCK_KEY = 'pkb-dock';
  function dockLoad() {
    try { return JSON.parse(localStorage.getItem(DOCK_KEY) || 'null') || {}; } catch (ignored) { return {}; }
  }
  function dockSave() {
    if (!dock) return;
    var keep = {collapsed: !!dock.state.collapsed, open: dockOpenTool()};
    if (dock.state.x != null) { keep.x = Math.round(dock.state.x); keep.y = Math.round(dock.state.y); }
    try { localStorage.setItem(DOCK_KEY, JSON.stringify(keep)); } catch (ignored) { /* storage blocked */ }
  }
  function dockOpenTool() {
    if (panel && panel.card.isConnected && !panel.state.closed && !panel.state.min && !panel.state.pop) return 'panel:' + (panel.state.tab || 'chat');
    if (editor && editor.card.isConnected && !editorDismissed && !editor.state.min && !editor.state.pop) return 'editor';
    return '';
  }
  function ensureDock(root) {
    if (dock) {
      if (!dock.bar.isConnected) root.appendChild(dock.bar);
      return dock;
    }
    var bar = el('div', 'pkb-dock');
    bar.setAttribute('role', 'toolbar');
    bar.setAttribute('aria-label', 'Pickleball tools');
    bar.setAttribute('data-pkb-dock', '');
    tagMeta(bar, 'toolbar');
    var handle = el('button', 'pkb-dock-handle', '\u22ee');
    handle.type = 'button';
    handle.setAttribute('aria-label', 'Tools');
    tagMeta(handle, 'toolbar-handle');
    var slots = el('div', 'pkb-dock-slots');
    tagMeta(slots, 'toolbar');
    bar.appendChild(handle);
    bar.appendChild(slots);
    root.appendChild(bar);
    isolateKeys(bar);
    var saved = dockLoad();
    dock = {bar: bar, handle: handle, slots: slots, moved: false,
      state: {x: isFinite(+saved.x) && saved.x != null ? +saved.x : null, y: isFinite(+saved.y) && saved.y != null ? +saved.y : null, collapsed: !!saved.collapsed}};
    handle.addEventListener('click', function () {
      if (dock.moved) { dock.moved = false; return; }
      dockCollapse(!dock.state.collapsed);
      pushEvent({type: 'panel', action: dock.state.collapsed ? 'toolbar-collapse' : 'toolbar-expand'});
    });
    var from = null;
    dragger(handle, function () {
      var r = bar.getBoundingClientRect();
      from = {x: r.left, y: r.top};
      dock.moved = false;
    }, function (dx, dy) {
      if (Math.abs(dx) + Math.abs(dy) > 4) dock.moved = true;
      dock.state.x = from.x + dx;
      dock.state.y = from.y + dy;
      dockPlace();
    }, function () {
      dockPlace();
      dockSave();
    });
    window.addEventListener('resize', function () { dockPlace(); }, true);
    document.addEventListener('focusin', function () { dockPlace(); }, true);
    return dock;
  }
  function dockAdd(button, root) {
    var d = ensureDock(root || ensureLayer());
    if (button.parentNode !== d.slots) d.slots.appendChild(button);
    button.style.transform = '';
    dockPlace();
  }
  function dockCollapse(on) {
    if (!dock) ensureDock(ensureLayer());
    dock.state.collapsed = !!on;
    dockPlace();
    dockSave();
  }
  function dockOverlap(x, y, w, h, r) {
    return x < r.right && x + w > r.left && y < r.bottom && y + h > r.top;
  }
  function dockPlace() {
    if (!dock) return;
    var bar = dock.bar;
    var tools = Array.prototype.filter.call(dock.slots.children, function (b) { return !b.hidden; });
    var present = !!(panel || editor || recordBar);
    bar.hidden = !present;
    dock.slots.hidden = !!dock.state.collapsed;
    bar.setAttribute('data-collapsed', dock.state.collapsed ? 'true' : 'false');
    dock.handle.setAttribute('aria-expanded', dock.state.collapsed ? 'false' : 'true');
    dock.handle.setAttribute('data-pkb-badge', tools.length ? String(tools.length) : '');
    var vp = viewport();
    var w = bar.offsetWidth || 48;
    var h = bar.offsetHeight || 36;
    var x = dock.state.x == null ? vp.w - w - 16 : dock.state.x;
    var y = dock.state.y == null ? vp.h - h - 16 : dock.state.y;
    x = clamp(x, 0, Math.max(0, vp.w - w));
    y = clamp(y, 0, Math.max(0, vp.h - h));
    // Keep the dock off the open panel and step editor so it never covers their controls (Send, close).
    [panel && panel.card, editor && editor.card].forEach(function (card) {
      if (!card || !card.isConnected || card.hidden || !card.getBoundingClientRect) return;
      var c = card.getBoundingClientRect();
      if (!c.width || !c.height || !dockOverlap(x, y, w, h, c)) return;
      if (c.left - w - 8 >= 0) x = c.left - w - 8;
      else if (c.top - h - 8 >= 0) y = c.top - h - 8;
      else if (c.right + 8 + w <= vp.w) x = c.right + 8;
      else if (c.bottom + 8 + h <= vp.h) y = c.bottom + 8;
    });
    var active = document.activeElement;
    if (active && active !== document.body && active !== document.documentElement && active !== layer && active.getBoundingClientRect) {
      var r = active.getBoundingClientRect();
      if (dockOverlap(x, y, w, h, r)) {
        if (r.top - h - 8 >= 0) y = r.top - h - 8;
        else if (r.bottom + 8 + h <= vp.h) y = r.bottom + 8;
      }
    }
    bar.style.transform = 'translate3d(' + Math.round(x) + 'px,' + Math.round(y) + 'px,0)';
    window.__pkbDockState = present ? {
      x: Math.round(x), y: Math.round(y), collapsed: !!dock.state.collapsed, open: dockOpenTool(),
      buttons: tools.map(function (b) { return b.getAttribute('aria-label') || b.textContent; })
    } : null;
  }
  function dockState() {
    if (dock) dockPlace();
    return {dock: window.__pkbDockState || null, panel: panel ? window.__pkbPanelState || null : null,
      editor: window.__pkbEditorState || null, recording: recordBar ? (recordBar.getAttribute('data-state') || 'recording') : 'off'};
  }
  var recordButton = null;
  function recordDock() {
    if (!recordBar) {
      if (recordButton && recordButton.parentNode) recordButton.parentNode.removeChild(recordButton);
      dockPlace();
      return;
    }
    if (!recordButton) {
      recordButton = el('button', 'pkb-card pkb-pill pkb-dock-record');
      recordButton.type = 'button';
      tagMeta(recordButton, 'recorder-toggle');
      recordButton.appendChild(el('span', 'pkb-dot'));
      recordButton.appendChild(el('span', 'pkb-pill-label', 'Recording'));
      recordButton.addEventListener('click', function () {
        if (!recordBar) return;
        var hide = recordBar.style.display !== 'none';
        recordBar.style.display = hide ? 'none' : '';
        recordButton.setAttribute('aria-pressed', hide ? 'false' : 'true');
        recordButton.setAttribute('aria-label', hide ? 'Show recording controls' : 'Collapse recording controls');
      });
    }
    var shown = recordBar.style.display !== 'none';
    recordButton.setAttribute('aria-pressed', shown ? 'true' : 'false');
    recordButton.setAttribute('aria-label', shown ? 'Collapse recording controls' : 'Show recording controls');
    var paused = recordBar.getAttribute('data-state') === 'paused';
    recordButton.querySelector('.pkb-dot').setAttribute('data-state', paused ? 'idle' : 'live');
    var root = ensureLayer();
    panelSheet(root);
    dockAdd(recordButton, root);
  }

  // Close asks first when it would discard unsent chat text, an open guide, or a recording.
  function closeLosses() {
    var lose = [];
    if (panel && panel.input && panel.input.value.trim()) lose.push('unsent text');
    if (guides.size > 0) lose.push('the open guide');
    if (window.__pkbRecord === true) lose.push('the recording');
    return lose;
  }
  function closeAsk() {
    var lose = closeLosses();
    if (!lose.length) { pushEvent({type: 'panel', action: 'close'}); panelClose(); return; }
    if (panel.confirm && panel.confirm.isConnected) return;
    var bar = el('div', 'pkb-confirm');
    bar.setAttribute('role', 'alertdialog');
    bar.setAttribute('aria-label', 'Confirm close');
    tagMeta(bar, 'close-confirm');
    bar.appendChild(el('span', 'pkb-confirm-text', 'Close and discard ' + lose.join(', ') + '?'));
    var yes = el('button', '', 'Discard and close');
    yes.type = 'button';
    tagMeta(yes, 'confirm-close');
    var no = el('button', '', 'Keep open');
    no.type = 'button';
    tagMeta(no, 'cancel-close');
    bar.appendChild(yes);
    bar.appendChild(no);
    yes.addEventListener('click', function () {
      bar.remove();
      if (panel.input) { panel.input.value = ''; panel.send.disabled = true; }
      Array.from(guides.keys()).forEach(function (id) { guideClose(id); });
      pushEvent({type: 'panel', action: 'close', discarded: lose});
      panelClose();
    });
    no.addEventListener('click', function () {
      bar.remove();
      pushEvent({type: 'panel', action: 'close-cancel'});
    });
    panel.confirm = bar;
    panel.card.insertBefore(bar, panel.head.nextSibling);
    try { yes.focus({preventScroll: true}); } catch (ignored) { /* focus refused */ }
  }

  function buildPanel(root) {
    metaPurpose = 'panel';
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
    var tabButtons = [['chat', 'Chat', 'chat-tab'], ['steps', 'Guide', 'guide-tab'], ['record', 'Recorder', 'recorder-tab']].map(function (t) {
      var b = el('button', '', t[1]);
      b.type = 'button';
      b.setAttribute('role', 'tab');
      b.setAttribute('data-tab', t[0]);
      b.setAttribute('aria-label', t[1]);
      tagMeta(b, t[2]);
      var tabBadge = el('span', 'pkb-badge');
      tabBadge.setAttribute('aria-hidden', 'true');
      tabBadge.setAttribute('data-pkb-badge', '');
      b.appendChild(tabBadge);
      b.addEventListener('click', function () { panelTab(t[0]); });
      tabs.appendChild(b);
      return b;
    });
    var pop = el('button', 'pkb-icon', '\u2197');
    pop.type = 'button';
    pop.setAttribute('aria-label', 'Pop out chat');
    pop.setAttribute('data-action', 'popout');
    tagMeta(pop, 'popout');
    var minimize = el('button', 'pkb-icon', '\u2013');
    minimize.type = 'button';
    minimize.setAttribute('aria-label', 'Minimize');
    minimize.setAttribute('data-action', 'minimize');
    tagMeta(minimize, 'collapse');
    var close = el('button', 'pkb-icon', '\u00d7');
    close.type = 'button';
    close.setAttribute('aria-label', 'Close');
    close.setAttribute('data-action', 'close');
    tagMeta(close, 'close');
    head.appendChild(dot);
    head.appendChild(title);
    head.appendChild(tabs);
    head.appendChild(pop);
    head.appendChild(minimize);
    head.appendChild(close);
    var chatPane = el('div', 'pkb-pane pkb-chat');
    chatPane.setAttribute('role', 'log');
    chatPane.setAttribute('aria-live', 'polite');
    tagMeta(chatPane, 'chat-log');
    var empty = el('div', 'pkb-empty', 'Messages from the agent appear here.');
    chatPane.appendChild(empty);
    var stepsPane = el('div', 'pkb-pane');
    tagMeta(stepsPane, 'guide-pane');
    stepsPane.hidden = true;
    stepsPane.appendChild(el('div', 'pkb-empty', 'No guide yet.'));
    var recordPane = el('div', 'pkb-pane');
    tagMeta(recordPane, 'recorder-pane');
    recordPane.hidden = true;
    recordPane.appendChild(el('div', 'pkb-empty', 'Not recording.'));
    var composer = el('form', 'pkb-composer');
    composer.setAttribute('autocomplete', 'off');
    var input = el('textarea');
    input.rows = 1;
    input.placeholder = 'Message the agent (Enter to send, Shift+Enter for a new line)';
    input.setAttribute('aria-label', 'Message');
    tagMeta(input, 'message');
    var send = el('button', '', 'Send');
    tagMeta(send, 'send');
    send.type = 'submit';
    send.disabled = true;
    composer.appendChild(input);
    composer.appendChild(send);
    card.appendChild(head);
    card.appendChild(chatPane);
    card.appendChild(stepsPane);
    card.appendChild(recordPane);
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
    tagMeta(pill, 'reopen');
    var pillDot = el('span', 'pkb-dot');
    pillDot.setAttribute('data-state', 'live');
    pill.appendChild(pillDot);
    var pillLabel = el('span', 'pkb-pill-label', 'Agent');
    pill.appendChild(pillLabel);
    var pillUnread = el('span', 'pkb-unread');
    pillUnread.setAttribute('aria-hidden', 'true');
    pillUnread.setAttribute('data-pkb-badge', '');
    pill.appendChild(pillUnread);
    root.appendChild(card);
    isolateKeys(card);
    isolateKeys(pill);
    var p = {card: card, pill: pill, head: head, title: title, tabs: tabButtons, chatPane: chatPane, stepsPane: stepsPane,
      recordPane: recordPane, composer: composer, input: input, send: send, empty: empty, seen: {}, unread: 0, state: panelDefaults()};
    input.addEventListener('input', function () { send.disabled = !input.value.trim(); });
    input.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
        event.preventDefault();
        panelSend();
      }
    });
    composer.addEventListener('submit', function (event) { event.preventDefault(); panelSend(); });
    minimize.addEventListener('click', function () { p.state.min = true; p.state.pop = false; panelPlace(); });
    pop.addEventListener('click', function () { popOut('chat'); });
    close.addEventListener('click', function () { closeAsk(); });
    pill.addEventListener('click', function () {
      if (p.state.pop) { popIn('chat'); return; }
      if (p.state.closed || !p.card.isConnected) {
        p.state.closed = false;
        var root = ensureLayer();
        if (!p.card.isConnected) root.appendChild(p.card);
      }
      p.state.min = false;
      panelPlace();
    });
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
      pushEvent({type: 'card', action: 'drag', x: Math.round(p.state.x), y: Math.round(p.state.y), id: 'panel'});
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
      sheet.replaceSync(PANEL_CSS + '\n.pkb-panel,.pkb-pill,.pkb-dock{pointer-events:auto;}');
      root.adoptedStyleSheets = Array.from(root.adoptedStyleSheets || []).concat([sheet]);
    } catch (unsupported) {
      var style = document.createElement('style');
      style.textContent = PANEL_CSS;
      tagMeta(style, 'panel');
      root.appendChild(style);
    }
  }

  function panelOpen(opts) {
    opts = opts || {};
    listen();
    var root = ensureLayer();
    panelSheet(root);
    if (!panel) panel = buildPanel(root);
    if (!panel.card.isConnected) root.appendChild(panel.card);
    dockAdd(panel.pill, root);
    var st = panel.state;
    ['x', 'y', 'w', 'h'].forEach(function (k) { if (opts[k] != null && opts[k] !== '' && isFinite(+opts[k])) st[k] = +opts[k]; });
    if (opts.min != null) st.min = opts.min === true || opts.min === 'true';
    if (opts.dockCollapsed != null) dockCollapse(opts.dockCollapsed === true || opts.dockCollapsed === 'true');
    if (opts.title) panel.title.textContent = String(opts.title).slice(0, 80);
    if (opts.theme === 'light' || opts.theme === 'dark') { panel.card.setAttribute('data-theme', opts.theme); panel.pill.setAttribute('data-theme', opts.theme); }
    else if (opts.theme === 'auto') { panel.card.removeAttribute('data-theme'); panel.pill.removeAttribute('data-theme'); }
    if (Array.isArray(opts.history)) { replaying = true; try { chat(opts.history); } finally { replaying = false; } }
    stepsList();
    try { document.documentElement.setAttribute('data-pkb-panel-want', 'open'); } catch (ignored) { /* attribute refused */ }
    st.closed = false;
    var wantTab = opts.tab === 'guide' ? 'steps' : (opts.tab === 'recorder' ? 'record' : opts.tab);
    panelTab(wantTab === 'steps' ? 'steps' : (wantTab === 'record' ? 'record' : (wantTab === 'chat' ? 'chat' : st.tab || 'chat')));
    paintRecordPane();
    raiseLayer();
    if (opts.focus !== false && !st.min && st.tab === 'chat') {
      try { panel.input.focus({preventScroll: true}); } catch (ignored) { /* focus refused */ }
    }
    return window.__pkbPanelState;
  }

  function panelClose() {
    if (!panel) return false;
    if (panel.card.parentNode) panel.card.remove();
    panel.state.closed = true;
    panel.state.min = false;
    panel.state.pop = false;
    window.__pkbPanelState = null;
    try { document.documentElement.removeAttribute('data-pkb-panel-want'); } catch (ignored) { /* attribute refused */ }
    panelPlace();
    if (items.size === 0 && !editor && guides.size === 0 && asks.size === 0 && layer && layer.isConnected) {
      layer.removeAttribute('data-pkb-layer');
    }
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


  // ---- State and re-injection (P5) ----
  // snapshot() describes what is shown (placements, guides with progress, the panel) without any
  // value a person typed; restoreWhenReady(state) brings it back after a reload or route change.
  var restoreState = null;
  var restoreApplied = false;
  var restoreHref = '';
  var restoreTimer = 0;
  var pendingItems = [];

  function snapshot() {
    var out = {v: 1, url: location.href, items: [], guides: [], panel: panel ? window.__pkbPanelState : null};
    items.forEach(function (item) {
      if (!item.host.isConnected) return;
      var copy = item.host.cloneNode(true);
      copy.style.translate = '';
      copy.style.visibility = '';
      copy.style.clipPath = '';
      Array.prototype.forEach.call(copy.querySelectorAll('input,textarea'), function (field) {
        field.removeAttribute('value');
        if (field.nodeName.toLowerCase() === 'textarea') field.textContent = '';
      });
      out.items.push({id: item.id, kind: item.kind, at: item.at, theme: item.host.getAttribute('data-pkb-theme') || '',
        html: copy.outerHTML, locator: item.locator, origin: item.origin});
    });
    guides.forEach(function (g) {
      var cur = g.steps[g.current];
      out.guides.push({spec: g.spec, at: cur ? cur.n : (g.steps.length ? g.steps[g.steps.length - 1].n + 1 : 1)});
    });
    return out;
  }

  function empty(snap) {
    return !snap || ((!snap.items || !snap.items.length) && (!snap.guides || !snap.guides.length) && !snap.panel);
  }

  function inScope(st) {
    if (!st || !st.snap) return false;
    if (st.expires && Date.now() > st.expires) return false;
    var saved;
    try { saved = new URL(st.snap.url); } catch (ignored) { return st.scope === 'run'; }
    if (st.scope === 'run') return true;
    if (st.scope === 'origin') return saved.origin === location.origin;
    // A page is its origin, path and query; an SPA route that changes the query is another page.
    return saved.origin === location.origin && saved.pathname === location.pathname && saved.search === location.search;
  }

  function placeItem(rec) {
    if (document.querySelector('[data-pkb-id="' + CSS.escape(rec.id) + '"]')) return true;
    var anchor = relocate(rec.locator || {});
    if (!anchor) return false;
    var box = document.createElement('div');
    box.innerHTML = rec.html;
    var host = box.firstElementChild;
    if (!host) return true;
    (document.body || document.documentElement).appendChild(host);
    track(host, anchor, {id: rec.id, kind: rec.kind, at: rec.at, theme: rec.theme, origin: rec.origin});
    return true;
  }

  function applyRestore() {
    var snap = restoreState.snap;
    pendingItems = (snap.items || []).filter(function (rec) { return !placeItem(rec); });
    (snap.guides || []).forEach(function (rec) {
      if (!guides.has(rec.spec.id)) guide({id: rec.spec.id, steps: rec.spec.steps, scroll: false, at: rec.at, tab: false});
    });
    if (snap.panel && !panel) panelOpen({x: snap.panel.x, y: snap.panel.y, w: snap.panel.w, h: snap.panel.h, min: snap.panel.min, tab: snap.panel.tab, focus: false});
    restoreApplied = true;
  }

  function withdraw() {
    items.forEach(function (item) { if (item.host && item.host.isConnected) item.host.remove(); });
    clear();
    panelClose();
    pendingItems = [];
    restoreApplied = false;
  }

  function restoreCheck() {
    if (!restoreState || !document.body) return;
    var href = location.href;
    var want = inScope(restoreState);
    if (want && !restoreApplied) applyRestore();
    else if (!want && restoreApplied) withdraw();
    else if (want && pendingItems.length) pendingItems = pendingItems.filter(function (rec) { return !placeItem(rec); });
    restoreHref = href;
  }

  function restoreWhenReady(st) {
    restoreState = st;
    restoreApplied = false;
    try {
      if (st && st.snap && st.snap.panel && inScope(st)) document.documentElement.setAttribute('data-pkb-panel-want', 'open');
    } catch (ignored) { /* attribute refused */ }
    if (st && st.pageState) {
      try { sessionStorage.setItem('pkb-ui', JSON.stringify(st)); } catch (ignored) { /* storage blocked */ }
    }
    var start = function () {
      restoreCheck();
      // SPA routes change the URL without a reload; checking twice a second costs one string compare.
      if (!restoreTimer) restoreTimer = setInterval(function () { if (location.href !== restoreHref || pendingItems.length) restoreCheck(); }, 500);
    };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, {once: true});
    else start();
    on(window, 'pageshow', function () { restoreCheck(); });
    return {scope: st && st.scope, inScope: inScope(st)};
  }

  function forget() {
    restoreState = null;
    if (restoreTimer) { clearInterval(restoreTimer); restoreTimer = 0; }
    try { sessionStorage.removeItem('pkb-ui'); } catch (ignored) { /* storage blocked */ }
    items.forEach(function (item) { if (item.host && item.host.isConnected) item.host.remove(); });
    clear();
    pendingItems = [];
    restoreApplied = false;
    return {cleared: true, panel: !!panel};
  }

  // A Selenium-only session has no preload; with pkb_ui_page_state=session the digest kept in
  // sessionStorage brings the state back the next time the runtime is installed on this tab.
  try {
    var kept = sessionStorage.getItem('pkb-ui');
    if (kept && !restoreState) {
      var parsed = JSON.parse(kept);
      if (parsed && parsed.pageState) setTimeout(function () { if (!restoreState) restoreWhenReady(parsed); }, 0);
    }
  } catch (ignored) { /* storage blocked */ }

  // ui-apply targets: a CSS, text, role or xpath target is marked with a handle the helper can find.
  var resolveSeq = 0;
  function resolve(spec) {
    var el = findTarget(spec && spec.t);
    if (!el) return null;
    var h = el.getAttribute('data-pkb-handle');
    if (!h) {
      h = 'ui-' + (++resolveSeq) + '-' + Math.random().toString(36).slice(2, 6);
      el.setAttribute('data-pkb-handle', h);
    }
    var r = el.getBoundingClientRect();
    return {h: h, x: r.left, y: r.top, w: r.width, hh: r.height};
  }

  var recordBar = null;
  function record(spec) {
    spec = spec || {};
    if (spec.on === true || spec.on === 'true') window.__pkbRecord = true;
    if (spec.on === false || spec.on === 'false') window.__pkbRecord = false;
    if (window.__pkbRecord === true) armSense();
    publishRecord(window.__pkbRecord === true);
    var status = spec.status || (window.__pkbRecord ? 'recording' : 'stopped');
    if (status === 'stopped' && !spec.script && !(Array.isArray(spec.steps) && spec.steps.length)) {
      if (recordBar && recordBar.parentNode) recordBar.parentNode.removeChild(recordBar);
      recordBar = null;
      recordDock();
      editorSync(spec);
      return {on: !!window.__pkbRecord, status: status, editor: window.__pkbEditorState || null};
    }
    if (!recordBar || !recordBar.isConnected) {
      recordBar = document.createElement('div');
      recordBar.setAttribute('data-pkb-layer', '');
      recordBar.setAttribute('data-pkb-record', '');
      tagMeta(recordBar, 'recorder');
      recordBar.style.cssText = 'position:fixed;left:16px;bottom:64px;z-index:2147483646;max-width:420px;'
        + 'background:#ffffff;color:#1b1f24;border:1px solid #dfe3e8;border-radius:12px;padding:10px 12px;'
        + 'box-shadow:0 12px 32px rgba(15,23,42,.18);font:13px/1.45 ' + FONT + ';';
      (document.documentElement || document.body).appendChild(recordBar);
    }
    metaPurpose = 'recorder';
    recordBar.textContent = '';
    tagMeta(recordBar, 'recorder');
    var pausedNow = status === 'paused' || window.__pkbRecord !== true;
    recordBar.setAttribute('data-state', pausedNow ? 'paused' : 'recording');
    var title = document.createElement('div');
    title.textContent = pausedNow ? 'Paused' : 'Recording';
    tagMeta(title, 'recorder');
    recordBar.appendChild(title);
    var pre = document.createElement('pre');
    pre.setAttribute('data-pkb-script', '');
    tagMeta(pre, 'recorder');
    appendDistinguished(pre, spec.script || '');
    pre.style.cssText = 'white-space:pre-wrap;margin:6px 0;max-height:140px;overflow:auto;';
    recordBar.appendChild(pre);
    function button(label, action) {
      var node = document.createElement('button');
      node.type = 'button';
      node.textContent = label;
      node.setAttribute('data-pkb-action', action);
      tagMeta(node, 'recorder');
      node.style.cursor = 'pointer';
      node.style.marginRight = '6px';
      node.addEventListener('click', function () {
        var actionNow = node.getAttribute('data-pkb-action') || action;
        lastPressed = node;
        setFeedback(node, 'sending');
        var ev = {type: 'record', event: 'record', action: actionNow, source: 'human'};
        if (actionNow === 'accept' || actionNow === 'reject') {
          var lines = (pre.textContent || '').split('\n').filter(function (line) { return line.trim(); });
          ev.index = String(Math.max(0, lines.length - 1));
        }
        pushEvent(ev);
        setFeedback(node, 'delivered');
        if (actionNow === 'pause' || actionNow === 'resume') {
          window.__pkbRecord = actionNow === 'resume';
          var nowPaused = window.__pkbRecord !== true;
          title.textContent = nowPaused ? 'Paused' : 'Recording';
          recordBar.setAttribute('data-state', nowPaused ? 'paused' : 'recording');
          node.textContent = nowPaused ? 'Resume' : 'Pause';
          node.setAttribute('data-pkb-action', nowPaused ? 'resume' : 'pause');
          paintRecordPane();
        }
      });
      return node;
    }
    recordBar.appendChild(button(pausedNow ? 'Resume' : 'Pause', pausedNow ? 'resume' : 'pause'));
    recordBar.appendChild(button('Keep step', 'accept'));
    recordBar.appendChild(button('Drop step', 'reject'));
    recordDock();
    paintRecordPane();
    editorSync(spec);
    return {on: !!window.__pkbRecord, status: status, script: spec.script || '', editor: window.__pkbEditorState || null};
  }

  // ---- In-page step editor and pop-out windows (chat + editor) ----
  var editorDismissed = false;
  var editorWasRecording = false;
  var editorRendering = false;

  function editorPhrase(text) {
    var value = String(text == null ? '' : text).trim();
    if (!value) return ', ';
    if (value.charAt(0) === ',' || value.charAt(0) === ':' || value.indexOf('Then ') === 0 || value.indexOf('And ') === 0) return value;
    return ', ' + value;
  }

  function editorNormalize(steps) {
    var prev = 0;
    steps.forEach(function (row, index) {
      var depth = Math.max(0, +row.depth || 0);
      if (index === 0) depth = 0;
      else depth = Math.min(depth, prev + 1);
      row.depth = depth;
      row.index = index;
      prev = depth;
    });
  }

  function editorRows(spec) {
    spec = spec || {};
    if (Array.isArray(spec.steps) && spec.steps.length && typeof spec.steps[0] === 'object') {
      return spec.steps.map(function (row, index) {
        return {
          id: String(row.id || ('s' + (index + 1))),
          text: String(row.text == null ? '' : row.text),
          depth: +row.depth || 0,
          index: index
        };
      });
    }
    var lines = String(spec.script || '').split('\n').filter(function (line) { return line.trim(); });
    return lines.map(function (line, index) {
      var text = line.trim();
      var depth = 0;
      var marks = text.match(/^:+/);
      if (marks) {
        depth = marks[0].length;
        text = text.slice(depth).trim();
      }
      text = text.replace(/^(Then |And )/, '').replace(/:$/, '');
      return {id: 's' + (index + 1), text: text, depth: depth, index: index};
    });
  }

  function editorWanted(spec, steps) {
    if (!spec) return false;
    if (spec.editor === false || spec.editor === 'false') return false;
    var status = spec.status || '';
    if (status === 'recording' || status === 'paused') return true;
    if (spec.script) return true;
    return !!(steps && steps.length);
  }

  function editorDefaults() {
    var vp = viewport();
    var w = Math.min(380, Math.floor(vp.w * 0.9));
    var h = Math.min(480, Math.floor(vp.h * 0.9));
    return {x: 16, y: 16, w: w, h: h, min: false, pop: false};
  }

  function editorClamp(st) {
    var vp = viewport();
    st.w = clamp(st.w, PANEL_MIN_W, Math.max(PANEL_MIN_W, Math.floor(vp.w * 0.9)));
    st.h = clamp(st.h, PANEL_MIN_H, Math.max(PANEL_MIN_H, Math.floor(vp.h * 0.9)));
    st.x = clamp(st.x, 0, vp.w - st.w);
    st.y = clamp(st.y, 0, vp.h - st.h);
    return st;
  }

  function editorPlace() {
    if (!editor) return;
    var st = editorClamp(editor.state);
    var closed = editorDismissed || !editor.card.isConnected;
    editor.card.hidden = !!st.min || !!st.pop || closed;
    editor.pill.hidden = !st.min && !st.pop && !closed;
    editor.card.style.transform = 'translate3d(' + Math.round(st.x) + 'px,' + Math.round(st.y) + 'px,0)';
    editor.card.style.width = Math.round(st.w) + 'px';
    editor.card.style.height = Math.round(st.h) + 'px';
    var label = editor.pill.querySelector('.pkb-pill-label');
    if (label) label.textContent = st.pop ? 'Steps window' : 'Steps';
    editor.pill.setAttribute('aria-label', st.pop ? 'Pop steps back in' : 'Open step editor');
    window.__pkbEditorState = closed ? null : {
      x: Math.round(st.x), y: Math.round(st.y), w: Math.round(st.w), h: Math.round(st.h),
      min: !!st.min, pop: !!st.pop, count: (editor.steps || []).length
    };
    dockPlace();
  }

  function editorApplyIncoming(steps) {
    if (!editor) return;
    var active = editor.list.querySelector('.pkb-editor-text:focus');
    var activeId = active ? active.closest('.pkb-editor-row').getAttribute('data-id') : '';
    var typing = active ? active.textContent : null;
    editor.steps = steps.map(function (row) {
      if (activeId && row.id === activeId && typing != null) row.text = typing;
      return row;
    });
    editorRender();
    if (activeId) {
      var field = editor.list.querySelector('[data-id="' + CSS.escape(activeId) + '"] .pkb-editor-text');
      if (field) {
        field.focus();
        if (typing != null) field.textContent = typing;
      }
    }
  }

  function editorRender() {
    metaPurpose = 'editor';
    if (!editor || editorRendering) return;
    editorRendering = true;
    try {
    var list = editor.list;
    var stick = list.scrollHeight - list.scrollTop - list.clientHeight < 40;
    list.textContent = '';
    var steps = editor.steps || [];
    if (!steps.length) {
      list.appendChild(el('div', 'pkb-empty', 'No steps yet. Interactions appear here.'));
      editorPlace();
      return;
    }
    steps.forEach(function (step, index) {
      var row = el('div', 'pkb-editor-row');
      row.setAttribute('data-index', String(index));
      row.setAttribute('data-id', step.id || ('s' + (index + 1)));
      row.setAttribute('data-depth', String(step.depth || 0));
      row.style.marginLeft = ((step.depth || 0) * 16) + 'px';
      var handle = el('button', 'pkb-handle', '\u2630');
      handle.type = 'button';
      handle.draggable = true;
      handle.setAttribute('aria-label', 'Drag to reorder');
      handle.setAttribute('data-action', 'drag');
      var num = el('span', 'pkb-num', step.id || ('s' + (index + 1)));
      var text = el('div', 'pkb-editor-text');
      text.setAttribute('contenteditable', 'true');
      text.setAttribute('role', 'textbox');
      text.setAttribute('aria-label', 'Step ' + (step.id || index + 1));
      text.setAttribute('spellcheck', 'false');
      appendDistinguished(text, step.text || '');
      var outdent = el('button', 'pkb-icon', '\u2190');
      outdent.type = 'button';
      outdent.setAttribute('aria-label', 'Outdent');
      outdent.setAttribute('data-action', 'outdent');
      var nest = el('button', 'pkb-icon', '\u2192');
      nest.type = 'button';
      nest.setAttribute('aria-label', 'Nest');
      nest.setAttribute('data-action', 'nest');
      var remove = el('button', 'pkb-icon', '\u00d7');
      remove.type = 'button';
      remove.setAttribute('aria-label', 'Delete step');
      remove.setAttribute('data-action', 'delete');
      row.appendChild(handle);
      row.appendChild(num);
      row.appendChild(text);
      row.appendChild(outdent);
      row.appendChild(nest);
      row.appendChild(remove);
      list.appendChild(row);
    });
    if (stick) list.scrollTop = list.scrollHeight;
    editorPlace();
    } finally {
      editorRendering = false;
    }
  }

  function editorApplyLocal(action, extra) {
    var steps = editor.steps || [];
    var index = extra.index == null || extra.index === '' ? -1 : +extra.index;
    if (action === 'edit' && steps[index]) steps[index].text = editorPhrase(extra.text);
    else if (action === 'move' && steps[+extra.from]) {
      var moved = steps.splice(+extra.from, 1)[0];
      if (extra.depth != null && extra.depth !== '') moved.depth = +extra.depth;
      var at = extra.to == null ? steps.length : Math.max(0, Math.min(+extra.to, steps.length));
      steps.splice(at, 0, moved);
    } else if (action === 'nest' && steps[index]) steps[index].depth = (+steps[index].depth || 0) + 1;
    else if (action === 'outdent' && steps[index]) steps[index].depth = (+steps[index].depth || 0) - 1;
    else if (action === 'add') {
      var insert = extra.index == null || extra.index === '' ? steps.length : Math.max(0, Math.min(+extra.index, steps.length));
      steps.splice(insert, 0, {
        id: 'm' + Date.now().toString(36),
        text: editorPhrase(extra.text || ', '),
        depth: +(extra.depth || 0)
      });
    } else if (action === 'delete' && index >= 0 && index < steps.length) steps.splice(index, 1);
    editorNormalize(steps);
    editor.steps = steps;
  }

  function editorCommand(action, extra) {
    if (!editor) editorOpen({steps: []});
    extra = extra || {};
    var ev = {type: 'record', event: 'record', action: action, source: 'human'};
    ['index', 'text', 'from', 'to', 'depth'].forEach(function (key) {
      if (extra[key] != null && extra[key] !== '') ev[key] = String(extra[key]);
    });
    var node = editor.list && extra.index != null
      ? editor.list.querySelector('.pkb-editor-row[data-index="' + String(extra.index) + '"]')
      : editor.card;
    if (node) {
      lastPressed = node;
      setFeedback(node, 'sending');
    }
    var bound = pushEvent(ev);
    if (node) setFeedback(node, bound ? 'delivered' : 'sending');
    editorApplyLocal(action, extra);
    editorRender();
    syncPop('editor');
    return bound;
  }

  function editorOpen(opts) {
    opts = opts || {};
    editorDismissed = false;
    listen();
    var root = ensureLayer();
    panelSheet(root);
    if (!editor) editor = buildEditor(root);
    if (!editor.card.isConnected) root.appendChild(editor.card);
    dockAdd(editor.pill, root);
    if (Array.isArray(opts.steps)) editorApplyIncoming(opts.steps);
    else editorRender();
    raiseLayer();
    return window.__pkbEditorState;
  }

  function editorClose() {
    editorDismissed = true;
    if (!editor) return false;
    if (editor.card.parentNode) editor.card.remove();
    editor.state.min = false;
    editor.state.pop = false;
    window.__pkbEditorState = null;
    editorPlace();
    return true;
  }

  function buildEditor(root) {
    metaPurpose = 'editor';
    var card = el('section', 'pkb-card pkb-panel');
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-label', 'Step editor');
    card.setAttribute('data-pkb-editor', '');
    var head = el('header', 'pkb-head');
    var dot = el('span', 'pkb-dot');
    dot.setAttribute('data-state', 'live');
    var title = el('strong', 'pkb-title', 'Steps');
    var pop = el('button', 'pkb-icon', '\u2197');
    pop.type = 'button';
    pop.setAttribute('aria-label', 'Pop out step editor');
    pop.setAttribute('data-action', 'popout');
    var minimize = el('button', 'pkb-icon', '\u2013');
    minimize.type = 'button';
    minimize.setAttribute('aria-label', 'Collapse step editor');
    minimize.setAttribute('data-action', 'minimize');
    var close = el('button', 'pkb-icon', '\u00d7');
    close.type = 'button';
    close.setAttribute('aria-label', 'Close step editor');
    close.setAttribute('data-action', 'close');
    head.appendChild(dot);
    head.appendChild(title);
    head.appendChild(pop);
    head.appendChild(minimize);
    head.appendChild(close);
    var list = el('div', 'pkb-pane pkb-editor-list');
    list.setAttribute('role', 'list');
    list.setAttribute('aria-label', 'Recorded steps');
    var tools = el('div', 'pkb-editor-tools');
    var add = el('button', '', 'Add step');
    add.type = 'button';
    add.setAttribute('data-action', 'add');
    tools.appendChild(add);
    card.appendChild(head);
    card.appendChild(list);
    card.appendChild(tools);
    ['n', 'e', 's', 'w', 'ne', 'nw', 'se', 'sw'].forEach(function (dir) {
      var grip = el('div', 'pkb-grip');
      grip.setAttribute('data-dir', dir);
      card.appendChild(grip);
    });
    var pill = el('button', 'pkb-card pkb-pill');
    pill.type = 'button';
    pill.hidden = true;
    pill.setAttribute('aria-label', 'Open step editor');
    pill.appendChild(el('span', 'pkb-dot'));
    pill.querySelector('.pkb-dot').setAttribute('data-state', 'live');
    pill.appendChild(el('span', 'pkb-pill-label', 'Steps'));
    root.appendChild(card);
    isolateKeys(card);
    isolateKeys(pill);
    var state = editorDefaults();
    var built = {card: card, pill: pill, list: list, head: head, state: state, steps: []};
    function bindList() {
      list.addEventListener('keydown', function (event) {
        var row = event.target.closest && event.target.closest('.pkb-editor-row');
        if (!row) return;
        var index = +row.getAttribute('data-index');
        var field = row.querySelector('.pkb-editor-text');
        if (event.key === 'Enter' && !event.shiftKey && field && event.target === field) {
          event.preventDefault();
          editorCommand('edit', {index: index, text: field.textContent});
          return;
        }
        if (event.key === 'Tab') {
          event.preventDefault();
          editorCommand(event.shiftKey ? 'outdent' : 'nest', {index: index});
          return;
        }
        if (event.altKey && (event.key === 'ArrowUp' || event.key === 'ArrowDown')) {
          event.preventDefault();
          var to = event.key === 'ArrowUp' ? Math.max(0, index - 1) : index + 1;
          editorCommand('move', {from: index, to: to});
          return;
        }
        if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
          event.preventDefault();
          editorCommand('add', {index: index + 1, text: ', '});
          return;
        }
        if ((event.metaKey || event.ctrlKey) && event.key === 'Backspace') {
          event.preventDefault();
          editorCommand('delete', {index: index});
        }
      });
      list.addEventListener('focusin', function (event) {
        var field = event.target;
        if (!field.classList || !field.classList.contains('pkb-editor-text')) return;
        if (!field.querySelector || !field.querySelector('.pkb-value')) return;
        field.textContent = field.textContent;
      });
      list.addEventListener('focusout', function (event) {
        if (editorRendering) return;
        var field = event.target;
        if (!field.classList || !field.classList.contains('pkb-editor-text')) return;
        var row = field.closest('.pkb-editor-row');
        if (!row) return;
        var index = +row.getAttribute('data-index');
        var current = (built.steps[index] && built.steps[index].text) || '';
        if (field.textContent !== current) editorCommand('edit', {index: index, text: field.textContent});
      });
      list.addEventListener('click', function (event) {
        var button = event.target.closest && event.target.closest('button[data-action]');
        if (!button) return;
        var row = button.closest('.pkb-editor-row');
        if (!row) return;
        var index = +row.getAttribute('data-index');
        var action = button.getAttribute('data-action');
        if (action === 'nest' || action === 'outdent' || action === 'delete') editorCommand(action, {index: index});
      });
      var dragFrom = -1;
      list.addEventListener('dragstart', function (event) {
        var handle = event.target.closest && event.target.closest('.pkb-handle');
        if (!handle) return;
        var row = handle.closest('.pkb-editor-row');
        dragFrom = row ? +row.getAttribute('data-index') : -1;
        if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
      });
      list.addEventListener('dragover', function (event) {
        var row = event.target.closest && event.target.closest('.pkb-editor-row');
        if (!row || dragFrom < 0) return;
        event.preventDefault();
        list.querySelectorAll('[data-drop]').forEach(function (node) { node.removeAttribute('data-drop'); });
        row.setAttribute('data-drop', 'on');
      });
      list.addEventListener('drop', function (event) {
        var row = event.target.closest && event.target.closest('.pkb-editor-row');
        if (!row || dragFrom < 0) return;
        event.preventDefault();
        row.removeAttribute('data-drop');
        var to = +row.getAttribute('data-index');
        var rect = row.getBoundingClientRect();
        var nest = event.clientX > rect.left + Math.min(48, rect.width / 2);
        var extra = {from: dragFrom, to: to};
        if (nest) extra.depth = (+row.getAttribute('data-depth') || 0) + 1;
        dragFrom = -1;
        editorCommand('move', extra);
      });
      list.addEventListener('dragend', function () {
        dragFrom = -1;
        list.querySelectorAll('[data-drop]').forEach(function (node) { node.removeAttribute('data-drop'); });
      });
    }
    bindList();
    add.addEventListener('click', function () { editorCommand('add', {text: ', '}); });
    pop.addEventListener('click', function () { popOut('editor'); });
    minimize.addEventListener('click', function () { built.state.min = true; built.state.pop = false; editorPlace(); });
    close.addEventListener('click', function () { editorClose(); });
    pill.addEventListener('click', function () {
      if (built.state.pop) { popIn('editor'); return; }
      if (editorDismissed || !built.card.isConnected) {
        editorDismissed = false;
        var root = ensureLayer();
        if (!built.card.isConnected) root.appendChild(built.card);
      }
      built.state.min = false;
      editorPlace();
    });
    var start = null;
    dragger(head, function () {
      start = {x: built.state.x, y: built.state.y};
      card.classList.add('pkb-dragging');
    }, function (dx, dy) {
      built.state.x = start.x + dx;
      built.state.y = start.y + dy;
      editorPlace();
    }, function () {
      card.classList.remove('pkb-dragging');
      var st = built.state;
      var vp = viewport();
      if (st.x <= SNAP) st.x = 0;
      if (st.y <= SNAP) st.y = 0;
      if (vp.w - (st.x + st.w) <= SNAP) st.x = vp.w - st.w;
      if (vp.h - (st.y + st.h) <= SNAP) st.y = vp.h - st.h;
      editorPlace();
      pushEvent({type: 'card', action: 'drag', x: Math.round(st.x), y: Math.round(st.y), id: 'editor'});
    });
    Array.prototype.forEach.call(card.querySelectorAll('.pkb-grip'), function (grip) {
      var dir = grip.getAttribute('data-dir');
      var from = null;
      dragger(grip, function () {
        from = {x: built.state.x, y: built.state.y, w: built.state.w, h: built.state.h};
        card.classList.add('pkb-resizing');
      }, function (dx, dy) {
        var vp = viewport();
        var maxW = Math.max(PANEL_MIN_W, Math.floor(vp.w * 0.9));
        var maxH = Math.max(PANEL_MIN_H, Math.floor(vp.h * 0.9));
        var st = built.state;
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
        editorPlace();
      }, function () {
        card.classList.remove('pkb-resizing');
        editorPlace();
      });
    });
    return built;
  }

  function paintRecordPane() {
    if (!panel || !panel.recordPane) return;
    var text = window.__pkbRecord === true
      ? 'Recording. New steps appear in the Steps window.'
      : (recordBar ? 'Paused. Use Resume on the recording bar. The Steps window keeps the steps.' : 'Not recording.');
    panel.recordPane.textContent = '';
    panel.recordPane.appendChild(el('div', 'pkb-empty', text));
  }

  function editorSync(spec) {
    spec = spec || {};
    var steps = editorRows(spec);
    if (spec.status === 'recording' && !editorWasRecording) editorDismissed = false;
    editorWasRecording = spec.status === 'recording';
    if (editorWanted(spec, steps) && !editorDismissed) editorOpen({steps: steps});
    else if (editor) editorApplyIncoming(steps);
    placeFromSpec(spec);
  }

  function placeFromSpec(spec) {
    var panels = spec && spec.panels ? spec.panels : {};
    if (panels.editor === 'window' && (!editor || !editor.state.pop)) popOut('editor', true);
    if (panels.editor === 'page' && editor && editor.state.pop) popIn('editor', true);
    if (panels.chat === 'window' && (!panel || !panel.state.pop)) popOut('chat', true);
    if (panels.chat === 'page' && panel && panel.state.pop) popIn('chat', true);
  }

  function popName(kind) {
    return kind === 'chat' ? 'pkb-chat-panel' : 'pkb-step-editor';
  }

  function popOut(kind, silent) {
    kind = kind === 'chat' ? 'chat' : 'editor';
    if (kind === 'chat' && !panel) panelOpen({focus: false});
    if (kind === 'editor' && !editor) editorOpen({steps: []});
    var win = pops[kind] && !pops[kind].closed ? pops[kind] : window.open('', popName(kind), 'popup=yes,width=440,height=720');
    if (!win) {
      if (!silent) pushEvent({type: 'record', event: 'record', action: 'popout', panel: kind, source: 'human', error: 'blocked'});
      return {ok: false, panel: kind, reason: 'popup blocked'};
    }
    pops[kind] = win;
    var ready = false;
    try { ready = win.document.documentElement.getAttribute('data-pkb-pop') === kind; } catch (ignored) { ready = false; }
    if (!ready) writePop(win, kind);
    try { sessionStorage.setItem('pkb-pop-' + kind, 'window'); } catch (ignored) { /* storage blocked */ }
    if (kind === 'editor' && editor) { editor.state.pop = true; editor.state.min = false; editorPlace(); }
    if (kind === 'chat' && panel) { panel.state.pop = true; panel.state.min = false; panelPlace(); }
    if (!silent) pushEvent({type: 'record', event: 'record', action: 'popout', panel: kind, source: 'human'});
    syncPop(kind);
    return {ok: true, panel: kind, where: 'window'};
  }

  function popIn(kind, silent) {
    kind = kind === 'chat' ? 'chat' : 'editor';
    var win = pops[kind];
    if (win && !win.closed) { try { win.close(); } catch (ignored) { /* already closed */ } }
    pops[kind] = null;
    try { sessionStorage.removeItem('pkb-pop-' + kind); } catch (ignored) { /* storage blocked */ }
    if (kind === 'editor' && editor) { editor.state.pop = false; editorPlace(); }
    if (kind === 'chat' && panel) {
      panel.state.pop = false;
      reapplyChat();
      panelPlace();
    }
    if (!silent) pushEvent({type: 'record', event: 'record', action: 'popin', panel: kind, source: 'human'});
    return {ok: true, panel: kind, where: 'page'};
  }

  function reapplyChat() {
    if (!panel) return;
    var rows = chatMirror.slice();
    panel.chatPane.textContent = '';
    panel.seen = {};
    if (panel.empty) { panel.empty.remove(); panel.empty = null; }
    chatMirror = [];
    rows.forEach(function (row) {
      if (!row || row.text == null) return;
      chat({id: row.id, from: row.from, source: row.from, text: row.text, state: row.state || '', at: row.at || ''});
    });
    recountUnread();
  }

  function recountUnread() {
    if (!panel || !panel.tabs || !panel.tabs.length) return;
    var n = 0;
    var looking = !panel.state.min && !panel.state.pop && !panel.state.closed && panel.state.tab === 'chat' && panel.card.isConnected;
    chatMirror.forEach(function (row) {
      var from = row.from || 'agent';
      if (from === 'human' || row.state === 'read') return;
      // A message shown while the person looks at the chat pane, or replayed after a navigation, is seen.
      if (looking) row.seen = true;
      if (!row.seen) n++;
    });
    panel.unread = n;
    var chatTab = panel.tabs[0];
    var badge = chatTab.querySelector('.pkb-badge');
    var show = !looking && n;
    if (badge) { badge.textContent = show ? String(n) : ''; badge.setAttribute('data-pkb-badge', show ? String(n) : ''); }
    if (show) chatTab.setAttribute('data-unread', String(n));
    else chatTab.removeAttribute('data-unread');
    // The accessible name stays "Chat" so a ref or query keeps matching; the count is a description.
    chatTab.setAttribute('aria-label', 'Chat');
    if (show) chatTab.setAttribute('aria-description', n + ' unread');
    else chatTab.removeAttribute('aria-description');
    var pill = panel.pill && panel.pill.querySelector('.pkb-unread');
    var showPill = (panel.state.min || panel.state.pop || panel.state.closed || !panel.card.isConnected) && n;
    if (pill) { pill.textContent = showPill ? String(n) : ''; pill.setAttribute('data-pkb-badge', showPill ? String(n) : ''); }
  }

  function syncPop(kind) {
    if (!pops || !pops[kind] || pops[kind].closed) return;
    var payload = {pkb: 'pop-state', panel: kind};
    if (kind === 'editor') payload.steps = (editor && editor.steps) ? editor.steps : [];
    if (kind === 'chat') payload.messages = chatMirror.slice(-200);
    try { pops[kind].postMessage(payload, '*'); } catch (ignored) { /* window gone */ }
  }

  function chatFromPop(text) {
    if (!text || !String(text).trim()) return;
    if (!panel) panelOpen({focus: false});
    panel.input.value = text;
    panelSend();
  }

  function onPopMessage(event) {
    var data = event.data;
    if (!data || data.pkb !== 'pop') return;
    var kind = data.panel === 'chat' ? 'chat' : 'editor';
    if (data.op === 'hello' || data.op === 'ready') { syncPop(kind); return; }
    if (data.op === 'popin') { popIn(kind); return; }
    if (kind === 'chat' && data.op === 'send') { chatFromPop(data.text); return; }
    if (kind === 'editor' && data.op) editorCommand(data.op, data);
  }

  function writePop(win, kind) {
    var doc = win.document;
    doc.open();
    doc.write(popHtml(kind));
    doc.close();
  }

  function popHtml(kind) {
    var title = kind === 'chat' ? 'Chat' : 'Steps';
    var purpose = kind === 'chat' ? 'panel' : 'editor';
    return '<!DOCTYPE html><html data-pkb-pop="' + kind + '" data-pkb-meta="true" data-pkb-purpose="' + purpose + '"><head><meta charset="utf-8"><title>' + title
      + '</title><style>'
      + 'body{margin:0;background:#15181d;color:#e6e9ee;font:14px/1.45 system-ui,sans-serif;}'
      + 'header{display:flex;align-items:center;gap:8px;padding:8px 12px;background:#1d2127;}'
      + 'button{cursor:pointer;}'
      + '#list{padding:12px;display:flex;flex-direction:column;gap:6px;}'
      + '.row{display:flex;gap:6px;align-items:flex-start;}'
      + '.text{flex:1;min-height:22px;white-space:pre-wrap;overflow-wrap:anywhere;}'
      + '.pkb-value{font-family:ui-monospace,monospace;font-weight:700;background:rgba(59,130,246,.18);border-radius:4px;padding:0 3px;}'
      + '.msg{padding:6px 10px;border-radius:10px;background:#1d2127;border:1px solid #2c323b;}'
      + '.msg[data-from="human"]{align-self:flex-end;background:#1f3354;}'
      + '[data-state="sending"],[data-pkb-feedback="sending"]{opacity:.65;}'
      + '[data-state="delivered"],[data-pkb-feedback="delivered"]{box-shadow:inset 3px 0 0 #22a06b;}'
      + '[data-state="read"],[data-pkb-feedback="read"]{box-shadow:inset 3px 0 0 #3b82f6;}'
      + 'form{display:flex;gap:8px;padding:8px;border-top:1px solid #2c323b;}'
      + 'textarea{flex:1;min-height:38px;}'
      + '@media (prefers-color-scheme: light){body{background:#fff;color:#1b1f24;}header{background:#f6f7f9;}.msg{background:#f6f7f9;border-color:#dfe3e8;}}'
      + '@media (prefers-reduced-motion: reduce){*{animation:none!important;transition:none!important;}}'
      + '</style></head><body><header><strong>' + title + '</strong>'
      + '<button type="button" id="pkb-popin" data-pkb-meta="true" data-pkb-purpose="' + purpose + '" data-action="popin">Pop in</button></header>'
      + '<div id="pkb-list" data-pkb-meta="true" data-pkb-purpose="' + purpose + '"></div>'
      + (kind === 'chat' ? '<form id="pkb-composer" data-pkb-meta="true" data-pkb-purpose="' + purpose + '"><textarea aria-label="Message"></textarea><button type="submit" data-pkb-meta="true">Send</button></form>' : '<div data-pkb-meta="true"><button type="button" id="pkb-add" data-pkb-meta="true" data-pkb-purpose="' + purpose + '" data-action="add">Add step</button></div>')
      + '<script>' + popScript(kind) + '<\/script></body></html>';
  }

  function popScript(kind) {
    return ''
      + 'var PANEL="' + kind + '";'
      + 'var list=document.getElementById("pkb-list");'
      + 'function tag(node){if(!node||!node.setAttribute)return node;node.setAttribute("data-pkb-meta","true");node.setAttribute("data-pkb-purpose",PANEL==="chat"?"panel":"editor");if(node.id&&node.id.indexOf("pkb-")!==0)node.id="pkb-"+node.id;return node;}'
      + 'function chip(node,text){var src=String(text==null?"":text);node.textContent="";var re=/"([^"\\\\]|\\\\.)*"|\'([^\'\\\\]|\\\\.)*\'/g;var last=0,m,any=false;while((m=re.exec(src))){any=true;if(m.index>last)node.appendChild(document.createTextNode(src.slice(last,m.index)));var s=document.createElement("span");s.className="pkb-value";s.textContent=m[0];tag(s);node.appendChild(s);last=m.index+m[0].length;}if(!any)node.textContent=src;else if(last<src.length)node.appendChild(document.createTextNode(src.slice(last)));}'
      + 'function send(op,extra){extra=extra||{};extra.pkb="pop";extra.panel=PANEL;extra.op=op;if(window.opener)window.opener.postMessage(extra,"*");}'
      + 'function phrase(text){var value=String(text==null?"":text).trim();if(!value)return", ";if(value.charAt(0)===","||value.charAt(0)===":"||value.indexOf("Then ")===0||value.indexOf("And ")===0)return value;return", "+value;}'
      + 'function renderSteps(steps){list.textContent="";(steps||[]).forEach(function(step,index){'
      + 'var row=document.createElement("div");row.className="row";row.setAttribute("data-index",String(index));row.style.marginLeft=((step.depth||0)*16)+"px";tag(row);'
      + 'var text=document.createElement("div");text.className="text";text.contentEditable="true";text.setAttribute("role","textbox");tag(text);chip(text,step.text||"");'
      + 'text.addEventListener("focusin",function(){if(text.querySelector(".pkb-value"))text.textContent=text.textContent;});'
      + 'text.addEventListener("keydown",function(event){if(event.key==="Enter"&&!event.shiftKey){event.preventDefault();send("edit",{index:index,text:text.textContent});}'
      + 'if(event.key==="Tab"){event.preventDefault();send(event.shiftKey?"outdent":"nest",{index:index});}'
      + 'if(event.altKey&&(event.key==="ArrowUp"||event.key==="ArrowDown")){event.preventDefault();send("move",{from:index,to:event.key==="ArrowUp"?Math.max(0,index-1):index+1});}'
      + 'if((event.metaKey||event.ctrlKey)&&event.key==="Enter"){event.preventDefault();send("add",{index:index+1,text:", "});}'
      + 'if((event.metaKey||event.ctrlKey)&&event.key==="Backspace"){event.preventDefault();send("delete",{index:index});}});'
      + 'text.addEventListener("focusout",function(){send("edit",{index:index,text:text.textContent});});'
      + 'var nest=document.createElement("button");nest.type="button";nest.textContent="Nest";tag(nest);nest.addEventListener("click",function(){send("nest",{index:index});});'
      + 'var out=document.createElement("button");out.type="button";out.textContent="Outdent";tag(out);out.addEventListener("click",function(){send("outdent",{index:index});});'
      + 'var del=document.createElement("button");del.type="button";del.textContent="Delete";tag(del);del.addEventListener("click",function(){send("delete",{index:index});});'
      + 'row.appendChild(text);row.appendChild(out);row.appendChild(nest);row.appendChild(del);list.appendChild(row);});}'
      + 'function renderChat(messages){list.textContent="";(messages||[]).forEach(function(row){'
      + 'var node=document.createElement("div");node.className="msg";node.setAttribute("data-from",row.from||"agent");tag(node);'
      + 'if(row.state){node.setAttribute("data-state",row.state);node.setAttribute("data-pkb-feedback",row.state);}chip(node,row.text||"");list.appendChild(node);});'
      + 'list.scrollTop=list.scrollHeight;}'
      + 'window.addEventListener("message",function(event){var data=event.data;if(!data||data.pkb!=="pop-state"||data.panel!==PANEL)return;'
      + 'if(PANEL==="editor")renderSteps(data.steps||[]);else renderChat(data.messages||[]);});'
      + 'document.getElementById("pkb-popin").addEventListener("click",function(){send("popin");});'
      + (kind === 'chat'
        ? 'var form=document.getElementById("pkb-composer");form.addEventListener("submit",function(event){event.preventDefault();var field=form.querySelector("textarea");send("send",{text:field.value});field.value="";});'
          + 'form.querySelector("textarea").addEventListener("keydown",function(event){if(event.key==="Enter"&&!event.shiftKey){event.preventDefault();form.requestSubmit?form.requestSubmit():form.dispatchEvent(new Event("submit"));}});'
        : 'document.getElementById("pkb-add").addEventListener("click",function(){send("add",{text:", "});});')
      + 'if(window.opener)send("hello");';
  }

  function reattachPops() {
    ['editor', 'chat'].forEach(function (kind) {
      var where = '';
      try { where = sessionStorage.getItem('pkb-pop-' + kind) || ''; } catch (ignored) { where = ''; }
      if (where !== 'window') return;
      var win;
      try { win = window.open('', popName(kind)); } catch (ignored) { win = null; }
      if (!win) return;
      var ready = false;
      try { ready = win.document.documentElement.getAttribute('data-pkb-pop') === kind; } catch (ignored) { ready = false; }
      if (!ready) {
        try { win.close(); } catch (ignored) { /* blank window from a missing pop */ }
        try { sessionStorage.removeItem('pkb-pop-' + kind); } catch (ignored) { /* storage blocked */ }
        return;
      }
      pops[kind] = win;
      if (kind === 'editor') {
        if (!editor) editorOpen({steps: []});
        editor.state.pop = true;
        editorPlace();
      } else {
        if (!panel) panelOpen({focus: false});
        panel.state.pop = true;
        panelPlace();
      }
      syncPop(kind);
    });
  }

  window.addEventListener('message', onPopMessage);
  window.addEventListener('message', function (event) {
    var data = event.data;
    if (!data || data.pkb !== 'record') return;
    window.__pkbRecord = data.on === true;
    if (data.on === true) armSense();
  });
  reattachPops();
  if (window.__pkbRecord === true) armSense();

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
    raiseCount: function () { return raiseCount; },
    panelOpen: panelOpen,
    panelClose: panelClose,
    panelState: panelState,
    dockState: dockState,
    dockCollapse: function (on) { dockCollapse(on !== false && on !== 'false'); return dockState(); },
    panelTab: panelTab,
    chat: chat,
    chatSent: chatSent,
    markRead: markRead,
    readIds: readIds,
    parkCards: parkCards,
    drainChat: drainChat,
    guide: guide,
    guideMove: guideMove,
    guideDone: guideDone,
    guideState: guideState,
    ask: ask,
    askClose: askClose,
    askTimeout: function (id) { return askClose(id, 'timeout'); },
    cardTrailRead: function () {
      try { return JSON.parse(document.documentElement.getAttribute('data-pkb-card-trail') || '[]'); } catch (bad) { return []; }
    },
    snapshot: snapshot,
    restoreWhenReady: restoreWhenReady,
    forget: forget,
    resolve: resolve,
    watchUntil: watchUntil,
    docVersion: function () { return docVersion; },
    record: record,
    armSense: armSense,
    sensed: function () {
      return events().filter(function (row) { return row && row.event === 'interaction'; });
    },
    editor: editorOpen,
    editorClose: editorClose,
    editorState: function () { return window.__pkbEditorState || null; },
    popOut: function (kind) { return popOut(kind || 'editor'); },
    popIn: function (kind) { return popIn(kind || 'editor'); }
  };

  // ---- Upload hand-off (C6) ----
  // A person who clicks a file input, or an Upload/Attach control that stands for one, is offered
  // "Ask agent to attach a file". That button posts upload-request with the input's ref and label;
  // the agent answers with act --kind=attach or upload. The native picker still opens as usual.
  var uploadOffer = null;
  var uploadSeq = 0;
  function uploadInputFor(node) {
    if (!node || !node.closest) return null;
    if (node.matches && node.matches('input[type=file]')) return {input: node, control: node};
    var label = node.closest('label');
    if (label) {
      var c = label.control || (label.htmlFor ? document.getElementById(label.htmlFor) : null);
      if (c && c.matches && c.matches('input[type=file]')) return {input: c, control: label};
    }
    var ctl = node.closest('button,[role="button"],a,label');
    if (!ctl) return null;
    var name = String(ctl.getAttribute('aria-label') || ctl.textContent || '').trim();
    if (!/upload|attach/i.test(name)) return null;
    var inner = ctl.querySelector ? ctl.querySelector('input[type=file]') : null;
    if (inner) return {input: inner, control: ctl};
    var all = document.querySelectorAll('input[type=file]');
    return all.length === 1 ? {input: all[0], control: ctl} : null;
  }
  function uploadLabel(found) {
    var input = found.input;
    var text = (input.labels && input.labels[0] && input.labels[0].textContent) || input.getAttribute('aria-label')
      || (found.control !== input ? found.control.getAttribute('aria-label') || found.control.textContent : '') || input.name || 'file';
    return String(text).replace(/\s+/g, ' ').trim().slice(0, 80);
  }
  function offerUpload(event) {
    var leaf = eventLeaf(event);
    if (!leaf || ours(leaf) || (leaf.closest && leaf.closest(OWN))) return;
    if (actingSource() === 'agent') return;
    var found = uploadInputFor(leaf);
    if (!found) return;
    var input = found.input;
    var ref = input.id ? '#' + input.id : (input.getAttribute('data-pkb-handle') || '');
    if (!ref) {
      ref = 'upload-' + (++uploadSeq);
      input.setAttribute('data-pkb-handle', ref);
    }
    var label = uploadLabel(found);
    var root = ensureLayer();
    if (uploadOffer && uploadOffer.parentNode) uploadOffer.remove();
    metaPurpose = 'callout';
    var btn = el('button', 'pkb-upload-ask', 'Ask agent to attach a file');
    btn.type = 'button';
    tagMeta(btn, 'request-upload');
    btn.setAttribute('data-pkb-upload-ref', ref);
    var r = (found.control.getBoundingClientRect ? found.control : input).getBoundingClientRect();
    btn.style.cssText = 'position:fixed;left:' + Math.round(Math.max(4, r.left)) + 'px;top:' + Math.round(r.bottom + 6)
      + 'px;pointer-events:auto;z-index:2147483647;font:13px system-ui,sans-serif;padding:4px 10px;border-radius:6px;'
      + 'border:1px solid #2563eb;background:#eff6ff;color:#1e3a8a;cursor:pointer;';
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      pushEvent({type: 'upload-request', ref: ref, label: label, name: input.name || '', multiple: !!input.multiple});
      btn.textContent = 'Asked the agent to attach a file';
      btn.disabled = true;
      setTimeout(function () { if (btn.parentNode) btn.remove(); }, 8000);
    });
    isolateKeys(btn);
    root.appendChild(btn);
    uploadOffer = btn;
  }
  try { document.addEventListener('click', offerUpload, true); } catch (ignored) { /* no document */ }

  var docVersion = 0;
  var docWatchers = [];
  function bumpDoc() {
    docVersion++;
    var pending = docWatchers;
    docWatchers = [];
    for (var i = 0; i < pending.length; i++) pending[i]({ok: true, doc: docVersion});
  }
  function watchUntil(condition, timeoutMs) {
    listen();
    return new Promise(function (resolve) {
      var finished = false;
      function finish(body) {
        if (finished) return;
        finished = true;
        resolve(body);
      }
      var timer = window.setTimeout(function () {
        finish({ok: false, reason: 'timeout', doc: docVersion, condition: condition || ''});
      }, timeoutMs == null ? 1000 : timeoutMs);
      docWatchers.push(function (body) {
        window.clearTimeout(timer);
        body.condition = condition || '';
        finish(body);
      });
    });
  }
})();
