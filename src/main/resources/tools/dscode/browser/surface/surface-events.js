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
