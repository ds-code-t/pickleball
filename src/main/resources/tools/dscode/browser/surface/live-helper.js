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
function mark(el, kind, sentence) {
  if (!el || el.nodeType !== 1) return;
  el.setAttribute('data-pkb-agent', 'agent_injected');
  el.setAttribute('data-pkb-kind', kind);
  el.setAttribute('data-pkb-sentence', sentence);
  if (spec.id) el.setAttribute('data-pkb-id', spec.id);
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
        tpl.innerHTML = undo;
        el.replaceWith(tpl.content);
        delete window.__pkbUndo[id];
      } else el.remove();
      removed++;
    });
  });
  if (op === 'cleanup') document.querySelectorAll('[data-pkb-style]').forEach(restoreStyle);
  if (op === 'remove' && removed === 0) return {ok:false, error:'unknown id'};
  return {ok:true, removed:removed};
}
if (spec.handle && !node()) {
  return {ok:false, error:'unknown handle'};
}
var host = document.createElement('div');
host.innerHTML = spec.html || '';
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
  host.style.maxWidth = '280px';
} else if (spec.css) host.setAttribute('style', spec.css);
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
  document.addEventListener('click', function(ev) {
    var n = ev.target && ev.target.closest ? ev.target.closest('[data-pkb-agent]') : null;
    if (!n) return;
    window.__pkbEvents.push({type:'click', id:n.getAttribute('data-pkb-id'), text:(n.innerText || '').slice(0, 200)});
  }, true);
  document.addEventListener('change', function(ev) {
    var n = ev.target && ev.target.closest ? ev.target.closest('[data-pkb-agent]') : null;
    if (!n) return;
    window.__pkbEvents.push({type:'input', id:n.getAttribute('data-pkb-id'), name:ev.target.name || '', value:ev.target.value || ''});
  }, true);
}
return {ok:true, id:spec.id, x:left, y:top};
