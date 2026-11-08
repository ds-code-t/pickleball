const fs = require("fs");
const input = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const statePath = process.argv[3];
const outPath = process.argv[4];
const state = JSON.parse(fs.readFileSync(statePath, "utf8"));
const context = state.context || "top";
const docState = state.docs[context] || { html: "", undo: {}, events: [], bound: false };

function cssEscape(value) {
  return String(value).replace(/[^a-zA-Z0-9_-]/g, (ch) => "\\" + ch);
}
globalThis.CSS = { escape: cssEscape };

function TextNode(data) {
  this.nodeType = 3;
  this.data = String(data);
  this.parentNode = null;
  this.childNodes = [];
}

function Element(tag) {
  this.nodeType = 1;
  this.nodeName = String(tag || "div").toUpperCase();
  this.tagName = this.nodeName;
  this.attributes = Object.create(null);
  this.childNodes = [];
  this.parentNode = null;
  this.style = {};
}
Element.prototype.setAttribute = function (name, value) {
  this.attributes[String(name)] = String(value);
};
Element.prototype.getAttribute = function (name) {
  return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
};
Element.prototype.hasAttribute = function (name) {
  return Object.prototype.hasOwnProperty.call(this.attributes, name);
};
Element.prototype.removeAttribute = function (name) {
  delete this.attributes[name];
};
Element.prototype.appendChild = function (node) {
  if (node.parentNode && node.parentNode.removeChild) node.parentNode.removeChild(node);
  node.parentNode = this;
  this.childNodes.push(node);
  return node;
};
Element.prototype.removeChild = function (node) {
  const index = this.childNodes.indexOf(node);
  if (index >= 0) this.childNodes.splice(index, 1);
  node.parentNode = null;
  return node;
};
Element.prototype.insertBefore = function (node, ref) {
  if (node.parentNode && node.parentNode.removeChild) node.parentNode.removeChild(node);
  node.parentNode = this;
  const index = ref ? this.childNodes.indexOf(ref) : -1;
  if (index < 0) this.childNodes.push(node);
  else this.childNodes.splice(index, 0, node);
  return node;
};
Element.prototype.remove = function () {
  if (this.parentNode && this.parentNode.removeChild) this.parentNode.removeChild(this);
};
Element.prototype.replaceWith = function (node) {
  if (!this.parentNode) return;
  this.parentNode.insertBefore(node, this);
  this.remove();
};
Object.defineProperty(Element.prototype, "nextSibling", {
  get() {
    if (!this.parentNode) return null;
    const kids = this.parentNode.childNodes;
    const index = kids.indexOf(this);
    return index >= 0 && index + 1 < kids.length ? kids[index + 1] : null;
  },
});
Object.defineProperty(Element.prototype, "innerText", {
  get() {
    return textOf(this);
  },
});
Object.defineProperty(Element.prototype, "textContent", {
  get() {
    return textOf(this);
  },
});
Object.defineProperty(Element.prototype, "innerHTML", {
  get() {
    return this.childNodes.map(serializeNode).join("");
  },
  set(html) {
    this.childNodes = [];
    parseInto(this, String(html));
  },
});
Object.defineProperty(Element.prototype, "outerHTML", {
  get() {
    return serializeElement(this);
  },
});
Element.prototype.querySelectorAll = function (selector) {
  const out = [];
  walk(this, (el) => {
    if (el !== this && matches(el, selector)) out.push(el);
  });
  return out;
};
Element.prototype.querySelector = function (selector) {
  const all = this.querySelectorAll(selector);
  return all.length ? all[0] : null;
};
Element.prototype.closest = function (selector) {
  let node = this;
  while (node) {
    if (node.nodeType === 1 && matches(node, selector)) return node;
    node = node.parentNode;
  }
  return null;
};

function textOf(node) {
  if (!node) return "";
  if (node.nodeType === 3) return node.data || "";
  return (node.childNodes || []).map(textOf).join("");
}

function matches(el, selector) {
  if (!el || el.nodeType !== 1) return false;
  const raw = String(selector).trim();
  if (raw === "*") return true;
  let found = raw.match(/^\[([^\]=]+)\]$/);
  if (found) return el.hasAttribute(found[1]);
  found = raw.match(/^\[([^\]=]+)="((?:\\.|[^"\\])*)"\]$/);
  if (!found) return false;
  const expected = found[2].replace(/\\(.)/g, "$1");
  return el.getAttribute(found[1]) === expected;
}

function walk(node, visit) {
  if (!node || !node.childNodes) return;
  for (const child of node.childNodes) {
    if (child.nodeType === 1) {
      visit(child);
      walk(child, visit);
    }
  }
}

function escapeText(value) {
  const amp = String.fromCharCode(38);
  const lt = String.fromCharCode(60);
  return String(value).split(amp).join(amp + "amp;").split(lt).join(amp + "lt;");
}
function escapeAttr(value) {
  const amp = String.fromCharCode(38);
  const quote = String.fromCharCode(34);
  return String(value).split(amp).join(amp + "amp;").split(quote).join(amp + "quot;");
}
function serializeNode(node) {
  return node.nodeType === 3 ? escapeText(node.data) : serializeElement(node);
}
function serializeElement(el) {
  const attrs = Object.keys(el.attributes)
    .map((name) => " " + name + '="' + escapeAttr(el.attributes[name]) + '"')
    .join("");
  return "<" + el.nodeName.toLowerCase() + attrs + ">" + el.innerHTML + "</" + el.nodeName.toLowerCase() + ">";
}
function decode(value) {
  const amp = String.fromCharCode(38);
  let text = String(value);
  text = text.split(amp + "lt;").join("<");
  text = text.split(amp + "gt;").join(">");
  text = text.split(amp + "quot;").join(String.fromCharCode(34));
  text = text.split(amp + "amp;").join(amp);
  return text;
}
function parseInto(parent, html) {
  const stack = [parent];
  const re = /<!--[\s\S]*?-->|<\/([a-zA-Z0-9:-]+)>|<([a-zA-Z0-9:-]+)([^>]*)>|([^<]+)/g;
  let match;
  while ((match = re.exec(html))) {
    if (match[1]) {
      const name = match[1].toUpperCase();
      while (stack.length > 1 && stack[stack.length - 1].nodeName !== name) stack.pop();
      if (stack.length > 1) stack.pop();
      continue;
    }
    if (match[2]) {
      const el = new Element(match[2]);
      const blob = match[3] || "";
      const attrRe = /([^\s=\/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g;
      let attr;
      while ((attr = attrRe.exec(blob))) {
        const value = attr[2] != null ? attr[2] : attr[3] != null ? attr[3] : attr[4] != null ? attr[4] : "";
        el.setAttribute(attr[1], value);
      }
      stack[stack.length - 1].appendChild(el);
      if (!/\/\s*$/.test(blob) && !match[0].endsWith("/>")) stack.push(el);
      continue;
    }
    if (match[4] && match[4].length) stack[stack.length - 1].appendChild(new TextNode(decode(match[4])));
  }
}

const body = new Element("body");
parseInto(body, docState.html || "");
const root = new Element("html");
root.appendChild(body);
const document = {
  nodeType: 9,
  documentElement: root,
  body,
  childNodes: [root],
  createElement(tag) {
    const el = new Element(tag);
    if (String(tag).toLowerCase() === "template") {
      const content = new Element("fragment");
      Object.defineProperty(el, "content", { get() { return content; } });
      Object.defineProperty(el, "innerHTML", {
        get() { return content.innerHTML; },
        set(html) { content.innerHTML = html; },
      });
    }
    return el;
  },
  addEventListener() {},
  querySelector(selector) { return root.querySelector(selector); },
  querySelectorAll(selector) { return root.querySelectorAll(selector); },
  getElementById(id) {
    let found = null;
    walk(root, (el) => {
      if (!found && el.getAttribute("id") === String(id)) found = el;
    });
    return found;
  },
};
globalThis.document = document;
const undo = docState.undo || {};
const events = Array.isArray(docState.events) ? docState.events.slice() : [];
globalThis.window = {
  document,
  __pkbUndo: undo,
  __pkbEvents: events,
  __pkbOverlayBound: !!docState.bound,
};

function iframeElement() {
  const frame = new Element("iframe");
  const attrs = state.iframe || {};
  Object.keys(attrs).forEach((name) => frame.setAttribute(name, attrs[name]));
  return frame;
}

let result;
try {
  if (input.mode === "call") {
    const onNode = input.on === "node";
    const target = onNode
      ? (document.getElementById(state.nodeId || "save") || document.querySelector("b") || document.body)
      : iframeElement();
    const fn = eval("(" + input.script + ")");
    const value = fn.call(target, input.arg);
    if (onNode) {
      docState.html = body.innerHTML;
      state.docs[context] = docState;
    } else {
      const attrs = {};
      Object.keys(target.attributes).forEach((name) => { attrs[name] = target.attributes[name]; });
      state.iframe = attrs;
    }
    result = value;
  } else {
    const source = String(input.script || "").replace(/\barguments\b/g, "scriptArgs");
    const spec = input.spec || {};
    const fn = new Function("scriptArgs", source);
    result = fn(input.mode === "drain" ? [] : [spec]);
  }
} catch (error) {
  result = { ok: false, error: String(error && error.stack ? error.stack : error) };
}

if (input.mode !== "call") {
  docState.html = body.innerHTML;
  docState.undo = window.__pkbUndo || {};
  docState.events = window.__pkbEvents || [];
  docState.bound = !!window.__pkbOverlayBound;
  state.docs[context] = docState;
  state.trace = state.trace || [];
  state.trace.push(context + ":" + (input.mode || "helper"));
}
fs.writeFileSync(statePath, JSON.stringify(state));
fs.writeFileSync(outPath, JSON.stringify({ result: result, html: body.innerHTML, context: context }));
