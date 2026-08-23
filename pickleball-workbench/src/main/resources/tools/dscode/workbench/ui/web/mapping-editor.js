(function () {
  var maps = document.getElementById("maps");
  var tree = document.getElementById("tree");
  var status = document.getElementById("status");
  var model = { entries: [], properties: [], restorable: false, mapReference: "" };

  function render() {
    maps.innerHTML = "";
    model.entries.forEach(function (entry, index) {
      var option = document.createElement("option");
      option.value = entry.reference;
      option.textContent = entry.label;
      if (entry.reference === model.mapReference) option.selected = true;
      maps.appendChild(option);
      if (!model.mapReference && index === 0) option.selected = true;
    });
    tree.innerHTML = "";
    if (!model.properties.length) {
      var empty = document.createElement("div");
      empty.className = "empty";
      empty.textContent = model.entries.length
          ? "This NodeMap has no properties yet."
          : "No NodeMaps are available in the current ParsingMap.";
      tree.appendChild(empty);
      return;
    }
    model.properties.forEach(function (property) {
      tree.appendChild(row(property));
    });
  }

  function row(property) {
    var wrap = document.createElement("div");
    wrap.className = "row";
    var key = document.createElement("input");
    key.value = property.key;
    key.disabled = !model.restorable;
    var type = document.createElement("select");
    ["string", "numeric", "boolean", "object-as-json", "object-as-xml"].forEach(function (name) {
      var option = document.createElement("option");
      option.value = name;
      option.textContent = name;
      if (name === property.type) option.selected = true;
      type.appendChild(option);
    });
    type.disabled = !model.restorable;
    var value = document.createElement("textarea");
    value.value = property.text;
    value.disabled = !model.restorable;
    function commit() {
      if (!window.mappingHost || !window.mappingHost.propertyChanged) return;
      window.mappingHost.propertyChanged(JSON.stringify({
        mapReference: model.mapReference,
        oldKey: property.key,
        key: key.value,
        type: type.value,
        text: value.value
      }));
      property.key = key.value;
      property.type = type.value;
      property.text = value.value;
    }
    key.addEventListener("change", commit);
    type.addEventListener("change", commit);
    value.addEventListener("change", commit);
    wrap.appendChild(key);
    wrap.appendChild(type);
    wrap.appendChild(value);
    return wrap;
  }

  maps.addEventListener("change", function () {
    if (window.mappingHost && window.mappingHost.selectMap) {
      window.mappingHost.selectMap(maps.value);
    }
  });
  document.getElementById("add").addEventListener("click", function () {
    if (!model.restorable) return;
    var key = "newProperty";
    var n = 1;
    while (model.properties.some(function (item) { return item.key === key; })) {
      key = "newProperty" + (++n);
    }
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
    render();
  };

  window.onWorkbenchReady = function () {
    if (window.mappingHost && window.mappingHost.ready) window.mappingHost.ready();
  };
})();
