// pickleball DOM change listener (E2b). Called as (send, cfg). send(json) is the push channel:
// a CDP Runtime binding, a BiDi script.message channel, or a Playwright exposed binding.
// Pickleball's own surfaces (any element below <body> carrying a data-pkb-* attribute, or an id starting with pkb-)
// are left out unless cfg.own is true; their human events arrive as typed collab events instead.
(function (send, cfg) {
  var key = '__pkbDomWatch_' + cfg.id;
  try { if (window[key]) window[key].disconnect(); } catch (ignored) { /* stale */ }
  var kinds = cfg.kinds || ['childList', 'attributes', 'text'];
  var attrs = cfg.attributes || [];
  var debounce = cfg.debounce > 0 ? cfg.debounce : 0;
  var seq = 0;
  function own(node) {
    if (cfg.own) return false;
    for (var e = node && node.nodeType === 1 ? node : node && node.parentElement; e; e = e.parentElement) {
      // <html> and <body> carry page-wide pickleball markers (data-pkb-sense, data-pkb-panel-want,
      // data-pkb-agent-acting) on any page pickleball touched; they do not make the whole page own UI.
      if (e === document.documentElement || e === document.body) break;
      if (e.id && e.id.indexOf('pkb-') === 0) return true;
      if (e.tagName && e.tagName.toLowerCase().indexOf('pkb-') === 0) return true;
      var a = e.attributes;
      for (var i = 0; a && i < a.length; i++) if (a[i].name.indexOf('data-pkb') === 0) return true;
    }
    return false;
  }
  function textOf(node) {
    try { return node.nodeType === 3 ? node.data || '' : node.textContent || ''; } catch (ignored) { return ''; }
  }
  function describe(node) {
    var e = node && node.nodeType === 1 ? node : node && node.parentElement;
    if (!e || !e.tagName) return '';
    var d = e.tagName.toLowerCase();
    if (e.id) d += '#' + e.id;
    else if (typeof e.className === 'string' && e.className.trim()) d += '.' + e.className.trim().split(/\s+/)[0];
    return d;
  }
  function fresh() {
    return { childList: 0, attributes: 0, text: 0, added: 0, removed: 0, targets: [], attributeNames: [], sample: '' };
  }
  var batch = fresh();
  var count = 0;
  var first = 0;
  var timer = null;
  function flush() {
    timer = null;
    if (!count) return;
    var out = batch;
    out.id = cfg.id;
    out.seq = ++seq;
    out.records = count;
    out.firstAt = first;
    out.at = Date.now();
    out.url = location.href;
    batch = fresh();
    count = 0;
    first = 0;
    try { send(JSON.stringify(out)); } catch (ignored) { /* the channel went away */ }
  }
  function note(target, kind, text) {
    batch[kind]++;
    count++;
    if (!first) first = Date.now();
    var d = describe(target);
    if (d && batch.targets.length < 10 && batch.targets.indexOf(d) < 0) batch.targets.push(d);
    if (text && batch.sample.length < 200) batch.sample = (batch.sample ? batch.sample + ' ' : '') + text.trim().slice(0, 200 - batch.sample.length);
  }
  function wanted(text) {
    return !cfg.text || (text || '').indexOf(cfg.text) >= 0;
  }
  var observer = new MutationObserver(function (records) {
    for (var r = 0; r < records.length; r++) {
      var m = records[r];
      if (m.type === 'childList') {
        if (kinds.indexOf('childList') < 0) continue;
        var added = 0, removed = 0, text = '';
        for (var i = 0; i < m.addedNodes.length; i++) {
          var n = m.addedNodes[i];
          if (own(n)) continue;
          added++;
          text += ' ' + textOf(n);
        }
        for (var j = 0; j < m.removedNodes.length; j++) {
          var gone = m.removedNodes[j];
          if (gone.nodeType === 1 && gone.attributes) {
            var mine = false;
            for (var k = 0; k < gone.attributes.length; k++) if (gone.attributes[k].name.indexOf('data-pkb') === 0) mine = true;
            if (mine || (gone.id && gone.id.indexOf('pkb-') === 0) || gone.tagName.toLowerCase().indexOf('pkb-') === 0) continue;
          }
          removed++;
        }
        if (!added && !removed) continue;
        if (own(m.target) || !wanted(text)) continue;
        batch.added += added;
        batch.removed += removed;
        note(m.target, 'childList', text);
      } else if (m.type === 'attributes') {
        if (kinds.indexOf('attributes') < 0) continue;
        var name = m.attributeName || '';
        if (!cfg.own && name.indexOf('data-pkb') === 0) continue;
        if (attrs.length && attrs.indexOf(name) < 0) continue;
        if (own(m.target)) continue;
        var value = m.target.getAttribute ? m.target.getAttribute(name) || '' : '';
        if (!wanted(value)) continue;
        if (batch.attributeNames.indexOf(name) < 0 && batch.attributeNames.length < 10) batch.attributeNames.push(name);
        note(m.target, 'attributes', '');
      } else if (m.type === 'characterData') {
        if (kinds.indexOf('text') < 0) continue;
        if (own(m.target)) continue;
        var data = textOf(m.target);
        if (!wanted(data)) continue;
        note(m.target, 'text', data);
      }
    }
    if (!count) return;
    if (!debounce || Date.now() - first >= (cfg.maxBatchMs > 0 ? cfg.maxBatchMs : 1000)) { flush(); return; }
    if (timer) clearTimeout(timer);
    timer = setTimeout(flush, debounce);
  });
  var root = cfg.selector ? document.querySelector(cfg.selector) : document.documentElement;
  if (!root) return 'no-root';
  var options = { subtree: true };
  if (kinds.indexOf('childList') >= 0) options.childList = true;
  if (kinds.indexOf('attributes') >= 0) {
    options.attributes = true;
    if (attrs.length) options.attributeFilter = attrs;
  }
  if (kinds.indexOf('text') >= 0) options.characterData = true;
  observer.observe(root, options);
  window[key] = { disconnect: function () { observer.disconnect(); if (timer) clearTimeout(timer); delete window[key]; } };
  return 'ok';
})
