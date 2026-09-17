(function () {
  var mapsEl = document.getElementById("maps");
  var tree = document.getElementById("tree");
  var status = document.getElementById("status");
  var model = { entries: [], properties: [], restorable: false, mapReference: "", rootLabel: "NodeMap" };

  function render() {
    mapsEl.innerHTML = "";
    (model.entries || []).forEach(function (entry) {
      var button = document.createElement("button");
      button.type = "button";
      button.className = "map-item" + (entry.reference === model.mapReference ? " selected" : "")
          + (entry.pending ? " pending" : "");
      button.textContent = entry.label || entry.reference;
      button.disabled = !!model.locked;
      button.addEventListener("click", function () {
        if (model.locked) return;
        if (window.mappingHost && window.mappingHost.selectMap) {
          window.mappingHost.selectMap(entry.reference);
        }
      });
      mapsEl.appendChild(button);
    });
    tree.innerHTML = "";
    tree.appendChild(rootNode());
  }

  function rootNode() {
    var wrap = document.createElement("div");
    wrap.className = "node";
    var row = document.createElement("div");
    row.className = "node-row root";
    var toggle = document.createElement("span");
    toggle.className = "toggle";
    toggle.textContent = "▾";
    var label = document.createElement("div");
    label.textContent = (model.rootLabel || "NodeMap") + " (root)";
    row.appendChild(toggle);
    row.appendChild(label);
    wrap.appendChild(row);
    var children = document.createElement("div");
    children.className = "children";
    if (!model.properties || !model.properties.length) {
      var empty = document.createElement("div");
      empty.className = "empty";
      empty.textContent = model.entries && model.entries.length
          ? "This NodeMap has no properties yet. Add one, or it will stay empty until the step runs."
          : "No NodeMaps are available in the current ParsingMap.";
      children.appendChild(empty);
    } else {
      model.properties.forEach(function (property) {
        children.appendChild(propertyNode(property, [property.key]));
      });
    }
    wrap.appendChild(children);
    return wrap;
  }

  function propertyNode(property, path) {
    var wrap = document.createElement("div");
    wrap.className = "node";
    var nested = nestedChildren(property);
    var row = document.createElement("div");
    row.className = "node-row";
    var toggle = document.createElement("button");
    toggle.type = "button";
    toggle.className = "toggle";
    toggle.textContent = nested.length ? "▾" : "•";
    toggle.disabled = !nested.length;
    var key = document.createElement("input");
    key.value = property.key;
    key.disabled = !model.restorable || !!model.locked || path.length > 1;
    var type = document.createElement("select");
    ["string", "numeric", "boolean", "object-as-json", "object-as-xml"].forEach(function (name) {
      var option = document.createElement("option");
      option.value = name;
      option.textContent = name;
      if (name === property.type) option.selected = true;
      type.appendChild(option);
    });
    type.disabled = !model.restorable || !!model.locked || path.length > 1;
    var value = document.createElement("textarea");
    value.value = nested.length ? "" : property.text;
    value.placeholder = nested.length ? "(object)" : "";
    value.disabled = !model.restorable || !!model.locked || nested.length > 0;
    var remove = document.createElement("button");
    remove.type = "button";
    remove.className = "remove";
    remove.textContent = "×";
    remove.disabled = !model.restorable || !!model.locked || path.length > 1;
    function commit() {
      if (!window.mappingHost || !window.mappingHost.propertyChanged) return;
      window.mappingHost.propertyChanged(JSON.stringify({
        mapReference: model.mapReference,
        oldKey: property.key,
        key: key.value,
        type: type.value,
        text: nested.length ? property.text : value.value
      }));
      property.key = key.value;
      property.type = type.value;
      if (!nested.length) property.text = value.value;
    }
    key.addEventListener("change", commit);
    type.addEventListener("change", commit);
    value.addEventListener("change", commit);
    remove.addEventListener("click", function () {
      if (!window.mappingHost || !window.mappingHost.propertyChanged) return;
      window.mappingHost.propertyChanged(JSON.stringify({
        mapReference: model.mapReference,
        oldKey: property.key,
        key: "",
        type: property.type,
        text: ""
      }));
    });
    row.appendChild(toggle);
    row.appendChild(key);
    row.appendChild(type);
    row.appendChild(value);
    row.appendChild(remove);
    wrap.appendChild(row);
    if (nested.length) {
      var kids = document.createElement("div");
      kids.className = "children";
      nested.forEach(function (child) {
        kids.appendChild(propertyNode(child, path.concat(child.key)));
      });
      wrap.appendChild(kids);
      toggle.addEventListener("click", function () {
        var hidden = kids.style.display === "none";
        kids.style.display = hidden ? "" : "none";
        toggle.textContent = hidden ? "▾" : "▸";
      });
    }
    return wrap;
  }

  function nestedChildren(property) {
    if (!property || (property.type !== "object-as-json" && property.type !== "object-as-xml")) return [];
    try {
      var parsed = JSON.parse(property.text || "null");
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        return Object.keys(parsed).map(function (name) {
          return childProperty(name, parsed[name]);
        });
      }
      if (Array.isArray(parsed)) {
        return parsed.map(function (item, index) {
          return childProperty("[" + index + "]", item);
        });
      }
    } catch (ignored) {
      return [];
    }
    return [];
  }

  function childProperty(key, value) {
    if (value && typeof value === "object") {
      return { key: key, type: "object-as-json", text: JSON.stringify(value) };
    }
    if (typeof value === "boolean") return { key: key, type: "boolean", text: String(value) };
    if (typeof value === "number") return { key: key, type: "numeric", text: String(value) };
    return { key: key, type: "string", text: value == null ? "" : String(value) };
  }

  document.getElementById("add").addEventListener("click", function () {
    if (!model.restorable || model.locked) return;
    var key = "newProperty";
    var n = 1;
    while ((model.properties || []).some(function (item) { return item.key === key; })) {
      key = "newProperty" + (++n);
    }
    model.properties = model.properties || [];
    model.properties.push({ key: key, type: "string", text: "" });
    render();
    if (window.mappingHost && window.mappingHost.propertyChanged) {
      window.mappingHost.propertyChanged(JSON.stringify({
        mapReference: model.mapReference,
        oldKey: "",
        key: key,
        type: "string",
        text: ""
      }));
    }
  });

  window.setMappingState = function (json) {
    model = typeof json === "string" ? JSON.parse(json) : json;
    status.textContent = model.status || "";
    document.body.classList.toggle("locked", !!model.locked);
    var add = document.getElementById("add");
    if (add) add.disabled = !!model.locked || !model.restorable;
    render();
  };

  window.onWorkbenchReady = function () {
    if (window.mappingHost && window.mappingHost.ready) window.mappingHost.ready();
  };
})();
