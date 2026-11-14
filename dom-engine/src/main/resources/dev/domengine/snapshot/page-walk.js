function () {
  function rectOf(rect) {
    if (!rect) return {x: 0, y: 0, width: 0, height: 0};
    return {x: rect.x || 0, y: rect.y || 0, width: rect.width || 0, height: rect.height || 0};
  }
  function attrs(el) {
    var out = {};
    var list = el.attributes || [];
    for (var i = 0; i < list.length; i++) out[list[i].name] = list[i].value;
    return out;
  }
  function styleOf(el) {
    var style = window.getComputedStyle(el);
    var names = ["display", "visibility", "opacity", "color", "background-color", "font-size", "font-weight", "cursor", "pointer-events"];
    var out = {};
    for (var i = 0; i < names.length; i++) out[names[i]] = style.getPropertyValue(names[i]);
    return out;
  }
  function states(el, style) {
    var out = [];
    if (style.display === "none" || style.visibility === "hidden" || style.visibility === "collapse" || style.opacity === "0") out.push("hidden");
    if (el.disabled || el.getAttribute("aria-disabled") === "true") out.push("disabled");
    if (el.checked || el.getAttribute("aria-checked") === "true") out.push("checked");
    if (el.getAttribute("aria-selected") === "true") out.push("selected");
    if (el.readOnly || el.getAttribute("aria-readonly") === "true") out.push("readonly");
    if (el.required || el.getAttribute("aria-required") === "true") out.push("required");
    if (el.getAttribute("aria-expanded") === "true") out.push("expanded");
    try { if (el === document.activeElement) out.push("focused"); } catch (e) {}
    return out;
  }
  function xpathOf(el) {
    var parts = [];
    var node = el;
    while (node && node.nodeType === 1) {
      var tag = node.localName;
      var index = 1;
      var prev = node.previousElementSibling;
      while (prev) {
        if (prev.localName === tag) index++;
        prev = prev.previousElementSibling;
      }
      parts.unshift("/" + tag + "[" + index + "]");
      node = node.parentElement;
    }
    return parts.join("");
  }
  function walk(el) {
    var style = styleOf(el);
    var box = el.getBoundingClientRect();
    var clients = [];
    var list = el.getClientRects();
    for (var i = 0; i < list.length; i++) clients.push(rectOf(list[i]));
    var children = [];
    var nodes = el.children || [];
    for (var c = 0; c < nodes.length; c++) children.push(walk(nodes[c]));
    return {
      tag: el.localName || "",
      attributes: attrs(el),
      role: el.getAttribute("role") || "",
      name: el.getAttribute("aria-label") || "",
      states: states(el, style),
      style: style,
      rect: rectOf(box),
      clientRects: clients,
      xpath: xpathOf(el),
      children: children
    };
  }
  var visual = window.visualViewport || {};
  return JSON.stringify({
    url: String(location.href || ""),
    outerHTML: document.documentElement ? document.documentElement.outerHTML : "",
    scrollX: window.scrollX || window.pageXOffset || 0,
    scrollY: window.scrollY || window.pageYOffset || 0,
    innerWidth: window.innerWidth || 0,
    innerHeight: window.innerHeight || 0,
    devicePixelRatio: window.devicePixelRatio || 1,
    visual: {
      offsetX: visual.offsetLeft || 0,
      offsetY: visual.offsetTop || 0,
      scale: visual.scale || 1,
      width: visual.width || 0,
      height: visual.height || 0
    },
    root: document.documentElement ? walk(document.documentElement) : null
  });
}
