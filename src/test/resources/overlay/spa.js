function render(route) {
  var view = document.getElementById('view');
  view.replaceChildren();
  var title = document.createElement('h2');
  title.textContent = route === 'b' ? 'Route B' : 'Route A';
  var button = document.createElement('button');
  button.id = route === 'b' ? 'b-action' : 'a-action';
  button.type = 'button';
  button.textContent = route === 'b' ? 'B action' : 'A action';
  view.append(title, button);
}
function go(route) {
  history.pushState({route: route}, '', '?route=' + route);
  render(route);
}
document.getElementById('to-a').addEventListener('click', function (e) { e.preventDefault(); go('a'); });
document.getElementById('to-b').addEventListener('click', function (e) { e.preventDefault(); go('b'); });
window.addEventListener('popstate', function (e) { render(e.state && e.state.route || 'a'); });
document.getElementById('open-modal').addEventListener('click', function () { document.getElementById('modal').showModal(); });
document.getElementById('modal-ok').addEventListener('click', function () { document.getElementById('modal').close(); });
render(new URLSearchParams(location.search).get('route') || 'a');
