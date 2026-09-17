(function () {
  var runs = document.getElementById("runs");
  var beatsEl = document.getElementById("beats");
  var frame = document.getElementById("frame");
  var frameGap = document.getElementById("frame-gap");
  var step = document.getElementById("step");
  var stepStatus = document.getElementById("step-status");
  var stepMeta = document.getElementById("step-meta");
  var stepSource = document.getElementById("step-source");
  var stepDefinition = document.getElementById("step-definition");
  var log = document.getElementById("log");
  var status = document.getElementById("status");
  var scrub = document.getElementById("scrub");
  var empty = document.getElementById("empty");
  var emptyCopy = document.getElementById("empty-copy");
  var replay = document.getElementById("replay");
  var play = document.getElementById("play");
  var prev = document.getElementById("prev");
  var next = document.getElementById("next");
  var openTarget = document.getElementById("open-target");
  var speed = document.getElementById("speed");
  var layersEl = document.getElementById("layers");
  var excerptEl = document.getElementById("layer-excerpt");
  var model = { runs: [], beats: [], tree: [], layers: [], index: 0, playing: false, gap: "", userExpanded: {} };
  var timer = null;
  var excerptLayer = "";

  function selectedRunId(state) {
    var list = (state && state.runs) || [];
    for (var i = 0; i < list.length; i++) {
      if (list[i].selected) return list[i].runId;
    }
    return list.length ? list[0].runId : "";
  }

  function tone(statusText) {
    var value = String(statusText || "").toLowerCase();
    if (value.indexOf("fail") >= 0 || value.indexOf("error") >= 0 || value.indexOf("ambiguous") >= 0) return "is-fail";
    if (value.indexOf("pass") >= 0 || value === "ok") return "is-pass";
    if (value.indexOf("skip") >= 0) return "is-skip";
    return "";
  }

  function escapeHtml(value) {
    return String(value || "")
        .replace(/&/g, "&")
        .replace(/</g, "<")
        .replace(/>/g, ">");
  }

  function isEdge(beat) {
    return beat && beat.type === "nested_scenario_end";
  }

  function playableIndexes() {
    var out = [];
    (model.beats || []).forEach(function (beat, index) {
      if (!isEdge(beat)) out.push(index);
    });
    return out;
  }

  function playablePosition() {
    var playable = playableIndexes();
    var pos = playable.indexOf(model.index);
    return pos < 0 ? 0 : pos;
  }

  function groupedBeats() {
    var groups = [];
    var current = null;
    (model.beats || []).forEach(function (beat, index) {
      if (isEdge(beat)) return;
      var id = beat.scenarioId || "";
      if (!current || current.id !== id) {
        current = { id: id, items: [] };
        groups.push(current);
      }
      current.items.push({ beat: beat, index: index });
    });
    return groups;
  }

  function findPath(nodes, beatIndex, path) {
    if (!nodes) return null;
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      path.push(node);
      if (node.beatIndex === beatIndex) return path.slice();
      var found = findPath(node.children, beatIndex, path);
      if (found) return found;
      path.pop();
    }
    return null;
  }

  function playheadPathIds() {
    var ids = {};
    var path = findPath(model.tree || [], model.index, []);
    if (!path) return ids;
    path.forEach(function (node) {
      if (node && node.nodeId) ids[node.nodeId] = true;
    });
    return ids;
  }

  function isExpanded(node, pathIds) {
    if (!node || !node.children || !node.children.length) return false;
    if (pathIds && pathIds[node.nodeId]) return true;
    if (model.userExpanded && Object.prototype.hasOwnProperty.call(model.userExpanded, node.nodeId)) {
      return !!model.userExpanded[node.nodeId];
    }
    if (node.kind === "scenario" && node.failed) return true;
    return false;
  }

  function stopPlay() {
    model.playing = false;
    play.textContent = "Play";
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  function intervalMs() {
    var value = speed && speed.value ? Number(speed.value) : 1400;
    return value > 0 ? value : 1400;
  }

  function nodeLink(node) {
    return {
      to: "editor",
      runId: selectedRunId(model),
      eventSeq: node && node.eventSeq ? node.eventSeq : 0,
      nodeId: node && node.nodeId ? node.nodeId : "",
      path: node && node.sourcePath ? node.sourcePath : "",
      line: node && node.sourceLine ? node.sourceLine : 0,
      kind: node && node.kind ? node.kind : "feature",
      label: node && node.stepText ? node.stepText : ""
    };
  }

  function goToNode(node) {
    if (!node || !window.diagnosticHost || !window.diagnosticHost.go) return;
    window.diagnosticHost.go(JSON.stringify(nodeLink(node)));
  }

  function currentTreeNode() {
    var path = findPath(model.tree || [], model.index, []);
    if (!path || !path.length) return null;
    return path[path.length - 1];
  }

  function seekToBeatIndex(index) {
    var playable = playableIndexes();
    if (!playable.length) {
      model.index = 0;
      return;
    }
    if (playable.indexOf(index) >= 0) {
      model.index = index;
      return;
    }
    var nearest = playable[0];
    for (var i = 0; i < playable.length; i++) {
      if (playable[i] <= index) nearest = playable[i];
    }
    model.index = nearest;
  }

  function appendTree(nodes, depth, pathIds) {
    if (!nodes) return;
    nodes.forEach(function (node) {
      if (!node || node.type === "nested_scenario_end") return;
      var expanded = isExpanded(node, pathIds);
      var hasKids = node.children && node.children.length;
      var collapsedCallee = hasKids && !expanded && (node.kind === "component" || node.kind === "service-call");
      var button = document.createElement("button");
      button.type = "button";
      var statusKind = tone(node.status);
      button.className = "beat"
          + (node.beatIndex === model.index ? " active" : "")
          + (statusKind && node.beatIndex === model.index ? " " + statusKind : "")
          + (collapsedCallee ? " collapsed-callee" : "");
      button.style.paddingLeft = (8 + depth * 14) + "px";
      var twist = document.createElement("button");
      twist.type = "button";
      twist.className = "twist";
      twist.tabIndex = -1;
      if (hasKids) {
        twist.textContent = expanded ? "▾" : "▸";
        twist.addEventListener("click", function (event) {
          event.stopPropagation();
          model.userExpanded = model.userExpanded || {};
          model.userExpanded[node.nodeId] = !expanded;
          render();
        });
      } else {
        twist.textContent = "";
      }
      var kind = document.createElement("span");
      kind.className = "kind";
      kind.textContent = node.kind === "scenario" ? "scen" : (node.kind || "").slice(0, 4);
      var copy = document.createElement("span");
      copy.className = "copy";
      var phrase = document.createElement("span");
      phrase.className = "phrase";
      phrase.textContent = node.stepText || node.kind || "(node)";
      copy.appendChild(phrase);
      var marks = document.createElement("span");
      marks.className = "marks";
      if (statusKind) {
        var chip = document.createElement("span");
        chip.className = "mark " + statusKind;
        chip.textContent = node.status || (collapsedCallee ? "done" : "");
        marks.appendChild(chip);
      } else if (collapsedCallee) {
        var done = document.createElement("span");
        done.className = "mark";
        done.textContent = "done";
        marks.appendChild(done);
      }
      button.appendChild(twist);
      button.appendChild(kind);
      button.appendChild(copy);
      button.appendChild(marks);
      button.addEventListener("click", function (event) {
        stopPlay();
        if (event.ctrlKey || event.metaKey) {
          goToNode(node);
          return;
        }
        if (typeof node.beatIndex === "number" && node.beatIndex >= 0) {
          seekToBeatIndex(node.beatIndex);
        } else if (node.children && node.children.length && typeof node.children[0].beatIndex === "number") {
          seekToBeatIndex(node.children[0].beatIndex);
        }
        render();
      });
      button.addEventListener("dblclick", function (event) {
        event.preventDefault();
        goToNode(node);
      });
      beatsEl.appendChild(button);
      if (node.beatIndex === model.index) {
        try {
          var top = button.offsetTop - 40;
          if (top < beatsEl.scrollTop || top > beatsEl.scrollTop + beatsEl.clientHeight - 64) {
            beatsEl.scrollTop = Math.max(0, top);
          }
        } catch (ignored) {}
      }
      if (hasKids && expanded) appendTree(node.children, depth + 1, pathIds);
    });
  }

  function renderFlat() {
    groupedBeats().forEach(function (group) {
      if (group.id) {
        var head = document.createElement("div");
        head.className = "scenario-head";
        head.textContent = "Scenario " + group.id;
        beatsEl.appendChild(head);
      }
      group.items.forEach(function (item) {
        var beat = item.beat;
        var button = document.createElement("button");
        button.type = "button";
        var kind = tone(beat.status);
        button.className = "beat" + (item.index === model.index ? " active" : "")
            + (item.index === model.index && kind ? " " + kind : "");
        button.style.paddingLeft = (12 + (Number(beat.nestingLevel) || 0) * 14) + "px";
        var marks = "";
        if (beat.hasScreenshot || beat.dataUri) marks += "<span class=\"mark shot\">shot</span>";
        if (kind) marks += "<span class=\"mark " + kind + "\">" + escapeHtml(beat.status) + "</span>";
        button.innerHTML =
            "<span class=\"twist\"></span>"
            + "<span class=\"num\">" + (item.index + 1) + "</span>"
            + "<span class=\"copy\"><span class=\"phrase\">" + escapeHtml(beat.stepText || beat.text || "(event)") + "</span>"
            + "<span class=\"meta\">" + escapeHtml(beat.timestamp || "") + "</span></span>"
            + "<span class=\"marks\">" + marks + "</span>";
        button.addEventListener("click", function () {
          stopPlay();
          seekToBeatIndex(item.index);
          render();
        });
        beatsEl.appendChild(button);
      });
    });
  }

  function render() {
    var beats = model.beats || [];
    var emptyMessage = model.gap || "No retained diagnostic run";
    runs.innerHTML = "";
    (model.runs || []).forEach(function (run) {
      var option = document.createElement("option");
      option.value = run.runId;
      option.textContent = run.label || run.runId;
      if (run.selected) option.selected = true;
      runs.appendChild(option);
    });
    var playable = playableIndexes();
    var hasBeats = playable.length > 0;
    empty.hidden = hasBeats;
    replay.hidden = !hasBeats;
    if (!hasBeats) {
      emptyCopy.textContent = emptyMessage;
      status.textContent = emptyMessage;
      scrub.max = 0;
      scrub.value = 0;
      prev.disabled = true;
      next.disabled = true;
      play.disabled = true;
      stopPlay();
      return;
    }
    if (playable.indexOf(model.index) < 0) seekToBeatIndex(model.index);
    play.disabled = false;
    var pos = playablePosition();
    prev.disabled = pos <= 0;
    next.disabled = pos >= playable.length - 1;
    scrub.max = String(playable.length - 1);
    scrub.value = String(pos);

    beatsEl.innerHTML = "";
    if (model.tree && model.tree.length) {
      appendTree(model.tree, 0, playheadPathIds());
    } else {
      renderFlat();
    }

    var current = beats[model.index];
    if (current && current.dataUri) {
      frame.src = current.dataUri;
      frame.style.display = "block";
      frameGap.style.display = "none";
    } else {
      frame.removeAttribute("src");
      frame.style.display = "none";
      frameGap.style.display = "block";
      frameGap.textContent = current && (current.hasScreenshot)
          ? "A screenshot file was retained for this step, but Workbench could not display it."
          : "No screenshot was retained for this step.";
    }
    var kind = tone(current && current.status);
    stepStatus.className = "badge" + (kind ? " " + kind : "");
    stepStatus.textContent = current && current.status ? current.status : "";
    step.textContent = current
        ? (current.stepText || current.text || "")
        : emptyMessage;
    var sourceLabel = "";
    if (current && current.sourcePath) {
      sourceLabel = current.sourcePath + (current.sourceLine ? (":" + current.sourceLine) : "");
    }
    if (stepSource) stepSource.textContent = sourceLabel;
    var definition = current && current.definition ? current.definition : {};
    var definitionLabel = "";
    if (definition.className || definition.method || definition.origin) {
      definitionLabel = (definition.className || "")
          + (definition.method ? ("#" + definition.method) : "")
          + (definition.origin ? (" · " + definition.origin) : "")
          + (definition.sourcePath ? (" · " + definition.sourcePath) : "");
    }
    if (stepDefinition) stepDefinition.textContent = definitionLabel;
    stepMeta.textContent = current && current.timestamp
        ? ("Beat " + (pos + 1) + " of " + playable.length + " · " + current.timestamp)
        : ("Beat " + (pos + 1) + " of " + playable.length);
    log.textContent = current && current.logLines && current.logLines.length
        ? current.logLines.join("\n")
        : "No INFO+ log lines retained for this step.";
    status.textContent = "Beat " + (pos + 1) + " of " + playable.length
        + (model.playing ? " · replaying" : "");
    renderLayers();
  }

  function renderLayers() {
    layersEl.innerHTML = "";
    var layers = model.layers || [];
    if (!layers.length) {
      var none = document.createElement("div");
      none.className = "empty";
      none.textContent = "No evidence-layer summary for this run.";
      layersEl.appendChild(none);
      excerptEl.hidden = true;
      return;
    }
    layers.forEach(function (layer) {
      var chip = document.createElement("button");
      chip.type = "button";
      chip.className = "layer " + (layer.present ? "present" : "missing")
          + (excerptLayer === layer.layer ? " active" : "");
      chip.textContent = (layer.layer || "layer") + (layer.present ? "" : " · missing");
      chip.disabled = !layer.present;
      chip.addEventListener("click", function () {
        if (excerptLayer === layer.layer) {
          excerptLayer = "";
          excerptEl.hidden = true;
        } else {
          excerptLayer = layer.layer;
          excerptEl.hidden = !layer.excerpt;
          excerptEl.textContent = layer.excerpt || "";
          if (window.diagnosticHost && window.diagnosticHost.focusLayer) {
            window.diagnosticHost.focusLayer(layer.layer);
          }
        }
        renderLayers();
      });
      layersEl.appendChild(chip);
    });
  }

  function show(delta) {
    var playable = playableIndexes();
    if (!playable.length) return;
    var pos = playablePosition();
    var nextPos = pos + delta;
    if (nextPos < 0) {
      stopPlay();
      model.index = playable[0];
      render();
      return;
    }
    if (nextPos >= playable.length) {
      stopPlay();
      model.index = playable[playable.length - 1];
      render();
      return;
    }
    model.index = playable[nextPos];
    render();
  }

  document.getElementById("prev").addEventListener("click", function () {
    stopPlay();
    show(-1);
  });
  document.getElementById("next").addEventListener("click", function () {
    stopPlay();
    show(1);
  });
  if (openTarget) {
    openTarget.addEventListener("click", function () {
      goToNode(currentTreeNode());
    });
  }
  play.addEventListener("click", function () {
    var playable = playableIndexes();
    if (!playable.length) return;
    if (model.playing) {
      stopPlay();
      render();
      return;
    }
    if (playablePosition() >= playable.length - 1) {
      stopPlay();
      render();
      return;
    }
    model.playing = true;
    play.textContent = "Pause";
    render();
    timer = setInterval(function () { show(1); }, intervalMs());
  });
  if (speed) {
    speed.addEventListener("change", function () {
      if (!model.playing) return;
      if (timer) clearInterval(timer);
      timer = setInterval(function () { show(1); }, intervalMs());
    });
  }
  scrub.addEventListener("input", function () {
    stopPlay();
    var playable = playableIndexes();
    var pos = Number(scrub.value) || 0;
    if (pos < 0) pos = 0;
    if (pos >= playable.length) pos = Math.max(0, playable.length - 1);
    model.index = playable[pos] || 0;
    render();
  });
  runs.addEventListener("change", function () {
    stopPlay();
    excerptLayer = "";
    model.userExpanded = {};
    if (window.diagnosticHost && window.diagnosticHost.selectRun) {
      window.diagnosticHost.selectRun(runs.value);
    }
  });
  document.addEventListener("keydown", function (event) {
    if (!model.beats || !model.beats.length) return;
    if (event.key === "ArrowLeft") {
      stopPlay();
      show(-1);
      event.preventDefault();
    } else if (event.key === "ArrowRight") {
      stopPlay();
      show(1);
      event.preventDefault();
    } else if (event.key === " ") {
      play.click();
      event.preventDefault();
    }
  });

  window.setDiagnosticState = function (json) {
    var next = typeof json === "string" ? JSON.parse(json) : json;
    next.beats = next.beats || next.frames || [];
    next.tree = next.tree || [];
    next.layers = next.layers || [];
    next.userExpanded = next.userExpanded || {};
    var sameRun = selectedRunId(model) && selectedRunId(model) === selectedRunId(next)
        && (model.beats || []).length === next.beats.length;
    var keepIndex = sameRun ? Math.min(model.index || 0, Math.max(0, next.beats.length - 1)) : (next.index || 0);
    var keepPlaying = sameRun && model.playing;
    var keepExpanded = sameRun ? (model.userExpanded || {}) : {};
    model = next;
    model.index = keepIndex;
    model.playing = keepPlaying;
    model.userExpanded = keepExpanded;
    if (!keepPlaying) stopPlay();
    render();
  };

  window.onWorkbenchReady = function () {
    if (window.diagnosticHost && window.diagnosticHost.ready) window.diagnosticHost.ready();
  };
})();
