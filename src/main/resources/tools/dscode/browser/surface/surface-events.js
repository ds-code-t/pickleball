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
