var spec = arguments[0];
var op = spec.op;
var resolved = null;
function node() {
  if (resolved) return resolved;
  var handle = spec.handle || '';
  if (spec.bridge) {
    var bridged = document.querySelector('[data-pkb-bridge="' + CSS.escape(String(spec.bridge)) + '"]');
    if (bridged) {
      bridged.removeAttribute('data-pkb-bridge');
      resolved = bridged;
      return bridged;
    }
  }
  if (!handle) return null;
  var direct = document.getElementById(handle);
  if (direct) return direct;
  var marked = document.querySelector('[data-pkb-handle="' + CSS.escape(handle) + '"]')
    || document.querySelector('[data-pkb-id="' + CSS.escape(handle) + '"]');
  if (marked) return marked;
  return null;
}
// Pages that require Trusted Types reject plain innerHTML. The HTML is already neutralized in Java,
// so a named policy passes it through; if the page forbids new policies the text is shown instead.
function setHtml(el, html) {
  var text = html || '';
  if (typeof window !== 'undefined' && window.trustedTypes && window.trustedTypes.createPolicy) {
    if (window.__pkbPolicy === undefined) {
      try {
        window.__pkbPolicy = window.trustedTypes.createPolicy('pkb-surface', {createHTML: function(s) { return s; }});
      } catch (refused) {
        window.__pkbPolicy = null;
      }
    }
    if (window.__pkbPolicy) {
      el.innerHTML = window.__pkbPolicy.createHTML(text);
      return;
    }
    try {
      el.innerHTML = text;
    } catch (blocked) {
      el.textContent = text;
    }
    return;
  }
  el.innerHTML = text;
}
function purposeOf(kind) {
  var name = String(kind || '');
  if (name === 'overlay' || name === 'label' || name === 'html' || name === 'step' || name === 'instructions' || name === 'diagram' || name === 'callout') return 'placement callout';
  return 'placement';
}
function tagMeta(el, purpose) {
  if (!el || el.nodeType !== 1 || !el.setAttribute) return;
  el.setAttribute('data-pkb-meta', 'true');
  var have = (el.getAttribute('data-pkb-purpose') || '').split(/\s+/).filter(Boolean);
  String(purpose || '').split(/\s+/).forEach(function (token) {
    if (token && have.indexOf(token) < 0) have.push(token);
  });
  if (have.length) el.setAttribute('data-pkb-purpose', have.join(' '));
  function prefix(attr) {
    var current = attr === 'id' ? el.id : el.getAttribute(attr);
    if (!current || current.indexOf('pkb-') === 0) return;
    var next = 'pkb-' + current;
    if (attr === 'id') el.id = next;
    else el.setAttribute(attr, next);
  }
  prefix('id');
  prefix('data-pkb-id');
}
function mark(el, kind, sentence) {
  if (!el || el.nodeType !== 1) return;
  el.setAttribute('data-pkb-agent', 'agent_injected');
  el.setAttribute('data-pkb-kind', kind);
  el.setAttribute('data-pkb-sentence', sentence);
  if (spec.id) el.setAttribute('data-pkb-id', spec.id);
  tagMeta(el, purposeOf(kind));
}
function markTree(el, kind, sentence) {
  mark(el, kind, sentence);
  el.querySelectorAll('*').forEach(function(child) { mark(child, kind, sentence); });
}
function restoreStyle(el) {
  if (!el) return;
  var original = el.getAttribute('data-pkb-original-style');
  if (original) el.setAttribute('style', original);
  else el.removeAttribute('style');
  el.removeAttribute('data-pkb-style');
  el.removeAttribute('data-pkb-css');
  el.removeAttribute('data-pkb-sentence');
  el.removeAttribute('data-pkb-original-style');
}
if (op === 'highlight') {
  var el = node();
  if (!el) return {ok:false, error:'unknown handle'};
  if (!el.hasAttribute('data-pkb-original-style')) {
    el.setAttribute('data-pkb-original-style', el.getAttribute('style') || '');
  }
  var original = el.getAttribute('data-pkb-original-style') || '';
  var css = spec.css || '';
  var joined = original;
  if (joined && css && joined.trim().slice(-1) !== ';') joined += ';';
  el.setAttribute('style', joined + css);
  el.setAttribute('data-pkb-style', 'style_modified');
  el.setAttribute('data-pkb-sentence', spec.sentence);
  el.setAttribute('data-pkb-css', css);
  return {ok:true};
}
if (op === 'unhighlight') {
  var target = spec.handle ? node() : null;
  var list = spec.handle ? [target] : Array.from(document.querySelectorAll('[data-pkb-style]'));
  if (spec.handle && !target) return {ok:false, error:'unknown handle'};
  list.forEach(restoreStyle);
  return {ok:true};
}
if (op === 'remove' || op === 'cleanup') {
  window.__pkbUndo = window.__pkbUndo || {};
  var ids = [];
  if (op === 'cleanup') {
    document.querySelectorAll('[data-pkb-id]').forEach(function(el) {
      var id = el.getAttribute('data-pkb-id');
      if (id && ids.indexOf(id) < 0) ids.push(id);
    });
  } else if (spec.id) ids.push(spec.id);
  var removed = 0;
  ids.forEach(function(id) {
    var undo = window.__pkbUndo[id];
    var nodes = document.querySelectorAll('[data-pkb-id="' + CSS.escape(id) + '"]');
    if (!nodes.length) return;
    nodes.forEach(function(el) {
      if (undo) {
        var tpl = document.createElement('template');
        setHtml(tpl, undo);
        el.replaceWith(tpl.content);
        delete window.__pkbUndo[id];
      } else el.remove();
      removed++;
    });
  });
  if (op === 'cleanup') document.querySelectorAll('[data-pkb-style]').forEach(restoreStyle);
  if (typeof window !== 'undefined' && window.__pkbUi) {
    if (op === 'cleanup') window.__pkbUi.clear(); else window.__pkbUi.prune();
  }
  if (op === 'remove' && removed === 0) return {ok:false, error:'unknown id'};
  return {ok:true, removed:removed};
}
if (spec.handle && !node()) {
  return {ok:false, error:'unknown handle'};
}
var host = document.createElement('div');
setHtml(host, spec.html || '');
markTree(host, spec.kind, spec.sentence);
var anchor = node();
var left = spec.x;
var top = spec.y;
if (spec.kind === 'overlay') {
  if (left === undefined || left === null || top === undefined || top === null) {
    return {ok:false, error:'overlay needs a visual-viewport box'};
  }
  host.style.position = 'fixed';
  host.style.zIndex = '2147483646';
  host.style.left = left + 'px';
  host.style.top = top + 'px';
  host.style.background = '#fffef2';
  host.style.border = '2px solid #222';
  host.style.padding = '8px';
  if (spec.width != null && spec.width !== '' && spec.height != null && spec.height !== '') {
    host.style.width = spec.width + 'px';
    host.style.height = spec.height + 'px';
  } else {
    host.style.maxWidth = '280px';
  }
} else if (spec.css) host.setAttribute('style', spec.css);
function paintCopy(source, clone) {
  if (!source || !clone || !window.getComputedStyle) return;
  var keys = ['color','backgroundColor','backgroundImage','font','fontSize','fontWeight','fontFamily','border','borderRadius','padding','display','alignItems','justifyContent','textAlign','lineHeight','boxSizing','whiteSpace'];
  function apply(from, to) {
    var style;
    try { style = window.getComputedStyle(from); } catch (e) { return; }
    keys.forEach(function(key) {
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
if (spec.kind === 'magnifier' && anchor) {
  var zoom = spec.zoom == null || spec.zoom === '' ? 2 : spec.zoom;
  var clone = anchor.cloneNode(true);
  // The copy is a picture of the page node: no ids, names, inline handlers, scripts, or focus.
  clone.querySelectorAll('script').forEach(function(s) { s.remove(); });
  [clone].concat(Array.from(clone.querySelectorAll('*'))).forEach(function(el) {
    if (!el.attributes) return;
    Array.from(el.attributes).forEach(function(a) {
      var n = a.name.toLowerCase();
      if (n === 'id' || n === 'name' || n === 'for' || n === 'form' || n === 'tabindex' || n.indexOf('on') === 0) el.removeAttribute(a.name);
    });
  });
  clone.setAttribute('inert', '');
  clone.setAttribute('aria-hidden', 'true');
  paintCopy(anchor, clone);
  clone.style.transform = 'scale(' + zoom + ')';
  clone.style.transformOrigin = '0 0';
  clone.style.pointerEvents = 'none';
  host.textContent = '';
  host.appendChild(clone);
  var blank = !((clone.textContent || '').replace(/\s/g, '')) && !clone.querySelector('img,svg,canvas,video,input');
  if (blank) {
    var note = document.createElement('div');
    note.setAttribute('data-pkb-copy', 'fallback');
    note.textContent = 'target not captured';
    tagMeta(note, purposeOf(spec.kind));
    note.style.cssText = 'color:#1b1f24;background:#ffffff;font:600 13px/1.3 system-ui,sans-serif;padding:8px;border:1px solid #dfe3e8;border-radius:8px;';
    host.appendChild(note);
  }
  markTree(clone, spec.kind, spec.sentence);
} else if (spec.kind === 'magnifier') {
  host.setAttribute('data-pkb-copy', 'fallback');
  if (!((host.textContent || '').trim())) host.textContent = 'target not captured';
}
if (op === 'replace' && anchor) {
  window.__pkbUndo = window.__pkbUndo || {};
  window.__pkbUndo[spec.id] = anchor.outerHTML;
  anchor.replaceWith(host);
} else if (op === 'insert' && anchor && spec.where === 'before') anchor.parentNode.insertBefore(host, anchor);
else if (op === 'insert' && anchor) anchor.parentNode.insertBefore(host, anchor.nextSibling);
else (document.body || document.documentElement).appendChild(host);
if (!window.__pkbOverlayBound) {
  window.__pkbOverlayBound = true;
  window.__pkbEvents = window.__pkbEvents || [];
  function pkbPush(ev) {
    if (typeof window.__pkbPush === 'function') { window.__pkbPush(ev); return; }
    try {
      if (typeof window.pkbSurface === 'function') { window.pkbSurface(JSON.stringify(ev)); return; }
    } catch (ignored) { /* binding missing */ }
    window.__pkbEvents.push(ev);
  }
  document.addEventListener('click', function(ev) {
    var path = ev.composedPath ? ev.composedPath() : [];
    var leaf = path.length ? path[0] : ev.target;
    if (leaf && leaf.nodeType !== 1) leaf = leaf.parentElement;
    var n = leaf && leaf.closest ? leaf.closest('[data-pkb-agent]') : null;
    if (!n) return;
    var control = leaf.closest ? (leaf.closest('button,a,label,input,summary,[data-choice],[data-pkb-check]') || leaf) : leaf;
    var text = '';
    if (control && control.getAttribute) text = control.getAttribute('data-choice') || control.getAttribute('aria-label') || '';
    if (!text) text = (control && (control.innerText || control.value)) || '';
    pkbPush({type:'click', source:'human', id:n.getAttribute('data-pkb-id'), text:String(text).replace(/\s+/g, ' ').trim().slice(0, 200)});
  }, true);
  document.addEventListener('change', function(ev) {
    var n = ev.target && ev.target.closest ? ev.target.closest('[data-pkb-agent]') : null;
    if (!n) return;
    var t = ev.target;
    var kind = ((t.getAttribute && t.getAttribute('type')) || '').toLowerCase();
    var auto = ((t.getAttribute && t.getAttribute('autocomplete')) || '').toLowerCase();
    if (kind === 'password' || auto.indexOf('password') >= 0 || auto.indexOf('cc-') >= 0 || auto.indexOf('one-time-code') >= 0) {
      pkbPush({type:'input', id:n.getAttribute('data-pkb-id'), name:t.name || '', filled:true, sensitive:true});
    } else {
      pkbPush({type:'input', id:n.getAttribute('data-pkb-id'), name:t.name || '', value:t.value || ''});
    }
  }, true);
}
// The runtime (pkb-runtime.js) keeps a placement on its target through scroll, resize, zoom and re-render.
var tracked = anchor;
if (!tracked && spec.anchor) {
  tracked = document.getElementById(spec.anchor)
    || document.querySelector('[data-pkb-handle="' + CSS.escape(String(spec.anchor)) + '"]');
}
if (tracked && op === 'append' && spec.kind && spec.kind !== 'html' && host.isConnected) {
  if (typeof window === 'undefined' || !window.__pkbUi || !window.__pkbUi.track) {
    return {ok:false, error:'runtime-missing'};
  }
  try { window.__pkbUi.track(host, tracked, spec); } catch (ignored) { /* placement stays static */ }
}
return {ok:true, id:spec.id, x:left, y:top};
