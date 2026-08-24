(function () {
  var board = document.getElementById("board");
  var state = { roots: [], selectedId: null, playheadId: null, executingId: null, suppress: false, locked: false };
  var dragging = null;

  function classify(text) {
    var trimmed = String(text || "").replace(/^[\s:]+/, "");
    if (/^(Feature|Rule|Background|Scenario|Scenario Outline|Examples):/.test(trimmed)) return "structure";
    if (/^Given\b/.test(trimmed)) return "given";
    if (/^When\b/.test(trimmed)) return "when";
    if (/^Then\b/.test(trimmed)) return "then";
    if (/^(IF|ELSE|ELSE-IF)\b/.test(trimmed)) return "iff";
    return "and";
  }

  function autosize(area) {
    area.style.height = "0px";
    area.style.height = Math.max(22, area.scrollHeight) + "px";
  }

  function render() {
    board.innerHTML = "";
    state.roots.forEach(function (block) {
      board.appendChild(renderBlock(block));
    });
  }

  function renderBlock(block) {
    var card = document.createElement("div");
    card.className = "block " + classify(block.text);
    card.dataset.id = String(block.id);
    if (block.id === state.playheadId) card.classList.add("playhead");
    if (block.id === state.executingId) card.classList.add("executing");
    card.draggable = !state.locked;

    var row = document.createElement("div");
    row.className = "row";
    var handle = document.createElement("div");
    handle.className = "handle";
    handle.textContent = "⋮⋮";
    var input = document.createElement("textarea");
    input.className = "gherkin";
    input.value = block.text;
    input.spellcheck = false;
    input.readOnly = !!state.locked;
    input.addEventListener("input", function () {
      block.text = input.value;
      autosize(input);
      report();
    });
    input.addEventListener("focus", function () {
      state.selectedId = block.id;
      reportSeek();
    });
    row.appendChild(handle);
    row.appendChild(input);
    card.appendChild(row);

    var children = document.createElement("div");
    children.className = "children";
    (block.children || []).forEach(function (child) {
      children.appendChild(renderBlock(child));
    });
    card.appendChild(children);

    card.addEventListener("click", function (event) {
      if (event.target === input) return;
      state.selectedId = block.id;
      reportSeek();
    });

    card.addEventListener("dragstart", function (event) {
      if (state.locked) {
        event.preventDefault();
        return;
      }
      dragging = { id: block.id };
      card.classList.add("ghost");
      event.dataTransfer.setData("text/plain", String(block.id));
      event.dataTransfer.effectAllowed = "move";
    });
    card.addEventListener("dragend", function () {
      card.classList.remove("ghost");
      dragging = null;
      clearDrop();
    });
    card.addEventListener("dragover", function (event) {
      event.preventDefault();
      event.stopPropagation();
      card.classList.add("drop-target");
    });
    card.addEventListener("dragleave", function () {
      card.classList.remove("drop-target");
    });
    card.addEventListener("drop", function (event) {
      event.preventDefault();
      event.stopPropagation();
      card.classList.remove("drop-target");
      var sourceId = Number(event.dataTransfer.getData("text/plain"));
      if (!sourceId || sourceId === block.id) return;
      var rect = card.getBoundingClientRect();
      var nested = event.clientX > rect.left + 48 && !isStructure(block.text);
      moveBlock(sourceId, nested ? block.id : parentId(block.id), insertIndex(block.id, nested, event.clientY, rect));
    });
    setTimeout(function () { autosize(input); }, 0);
    return card;
  }

  function isStructure(text) {
    return classify(text) === "structure";
  }

  function parentId(id) {
    var found = findParent(state.roots, id, null);
    return found;
  }

  function findParent(blocks, id, parent) {
    for (var i = 0; i < blocks.length; i++) {
      if (blocks[i].id === id) return parent;
      var nested = findParent(blocks[i].children || [], id, blocks[i].id);
      if (nested !== undefined && nested !== null || (nested === null && findBlock(blocks[i].children || [], id))) {
        if (findBlock(blocks[i].children || [], id)) return blocks[i].id;
        return nested;
      }
    }
    return null;
  }

  function findBlock(blocks, id) {
    for (var i = 0; i < blocks.length; i++) {
      if (blocks[i].id === id) return blocks[i];
      var nested = findBlock(blocks[i].children || [], id);
      if (nested) return nested;
    }
    return null;
  }

  function insertIndex(targetId, nested, clientY, rect) {
    if (nested) {
      var target = findBlock(state.roots, targetId);
      return target && target.children ? target.children.length : 0;
    }
    return clientY > (rect.top + rect.height / 2) ? indexAmongSiblings(targetId) + 1 : indexAmongSiblings(targetId);
  }

  function indexAmongSiblings(id) {
    var parent = parentId(id);
    var siblings = parent == null ? state.roots : (findBlock(state.roots, parent).children || []);
    for (var i = 0; i < siblings.length; i++) {
      if (siblings[i].id === id) return i;
    }
    return siblings.length;
  }

  function moveBlock(sourceId, newParentId, index) {
    var removed = removeBlock(state.roots, sourceId);
    if (!removed) return;
    if (newParentId == null) {
      state.roots.splice(Math.max(0, Math.min(index, state.roots.length)), 0, removed);
    } else {
      var parent = findBlock(state.roots, newParentId);
      if (!parent || contains(removed, newParentId)) {
        state.roots.push(removed);
      } else {
        parent.children = parent.children || [];
        parent.children.splice(Math.max(0, Math.min(index, parent.children.length)), 0, removed);
      }
    }
    render();
    report();
  }

  function contains(block, id) {
    if (block.id === id) return true;
    return (block.children || []).some(function (child) { return contains(child, id); });
  }

  function removeBlock(blocks, id) {
    for (var i = 0; i < blocks.length; i++) {
      if (blocks[i].id === id) return blocks.splice(i, 1)[0];
      var nested = removeBlock(blocks[i].children || [], id);
      if (nested) return nested;
    }
    return null;
  }

  function flatten(blocks, level, out) {
    (blocks || []).forEach(function (block) {
      var body = String(block.text || "").replace(/^[\s:]+/, "");
      var line = level > 0 ? ("  " + ":".repeat(level) + " " + body) : (block.text || "");
      out.push({ id: block.id, text: line });
      flatten(block.children || [], level + 1, out);
    });
  }

  function report() {
    if (state.locked || state.suppress || !window.gherkinHost || !window.gherkinHost.documentChanged) return;
    var lines = [];
    flatten(state.roots, 0, lines);
    window.gherkinHost.documentChanged(JSON.stringify({
      lines: lines.map(function (item) { return item.text; }),
      selectedId: state.selectedId,
      playheadId: state.playheadId
    }));
  }

  function reportSeek() {
    if (state.locked || state.suppress || !window.gherkinHost || !window.gherkinHost.seek) return;
    window.gherkinHost.seek(state.selectedId == null ? -1 : state.selectedId);
  }

  window.setEditorState = function (json) {
    var payload = typeof json === "string" ? JSON.parse(json) : json;
    state.suppress = true;
    state.roots = payload.roots || [];
    state.selectedId = payload.selectedId;
    state.playheadId = payload.playheadId;
    state.executingId = payload.executingId;
    state.locked = !!payload.locked;
    document.body.classList.toggle("locked", state.locked);
    var add = document.getElementById("add-block");
    if (add) add.disabled = state.locked;
    render();
    state.suppress = false;
  };

  document.getElementById("add-block").addEventListener("click", function () {
    if (state.locked) return;
    if (window.gherkinHost && window.gherkinHost.requestAddStep) {
      window.gherkinHost.requestAddStep();
    }
  });

  window.onWorkbenchReady = function () {
    if (window.gherkinHost && window.gherkinHost.ready) window.gherkinHost.ready();
  };
})();
