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
    var t = ev.target;
    var kind = ((t.getAttribute && t.getAttribute('type')) || '').toLowerCase();
    var auto = ((t.getAttribute && t.getAttribute('autocomplete')) || '').toLowerCase();
    if (kind === 'password' || auto.indexOf('password') >= 0 || auto.indexOf('cc-') >= 0 || auto.indexOf('one-time-code') >= 0) {
      window.__pkbEvents.push({type:'input', id:n.getAttribute('data-pkb-id'), name:t.name || '', filled:true, sensitive:true});
    } else {
      window.__pkbEvents.push({type:'input', id:n.getAttribute('data-pkb-id'), name:t.name || '', value:t.value || ''});
    }
  }, true);
}
