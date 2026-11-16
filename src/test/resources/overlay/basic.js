document.getElementById('rerender').addEventListener('click', function () {
  var slot = document.getElementById('save-slot');
  var fresh = document.createElement('button');
  fresh.id = 'save';
  fresh.type = 'button';
  fresh.textContent = 'Save';
  fresh.onclick = function () { window.saved = (window.saved || 0) + 1; };
  slot.replaceChildren(fresh);
});
document.getElementById('grow').addEventListener('click', function () {
  var spacer = document.getElementById('spacer');
  spacer.style.height = (spacer.offsetHeight + 200) + 'px';
});
window.hotkeys = 0;
document.addEventListener('keydown', function (event) {
  if (event.key === 's' && !event.target.closest('input,textarea,select')) {
    window.hotkeys++;
    event.preventDefault();
  }
});
