(function () {
  var runs = document.getElementById("runs");
  var beatsEl = document.getElementById("beats");
  var frame = document.getElementById("frame");
  var frameGap = document.getElementById("frame-gap");
  var step = document.getElementById("step");
  var stepStatus = document.getElementById("step-status");
  var stepMeta = document.getElementById("step-meta");
  var log = document.getElementById("log");
  var status = document.getElementById("status");
  var scrub = document.getElementById("scrub");
  var empty = document.getElementById("empty");
  var emptyCopy = document.getElementById("empty-copy");
  var replay = document.getElementById("replay");
  var play = document.getElementById("play");
  var prev = document.getElementById("prev");
  var next = document.getElementById("next");
  var layersEl = document.getElementById("layers");
  var excerptEl = document.getElementById("layer-excerpt");
  var model = { runs: [], beats: [], layers: [], index: 0, playing: false, gap: "" };
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
    if (value.indexOf("fail") >= 0 || value.indexOf("error") >= 0) return "is-fail";
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

  function groupedBeats() {
    var groups = [];
    var current = null;
    (model.beats || []).forEach(function (beat, index) {
      var id = beat.scenarioId || "";
      if (!current || current.id !== id) {
        current = { id: id, items: [] };
        groups.push(current);
      }
      current.items.push({ beat: beat, index: index });
    });
    return groups;
  }

  function stopPlay() {
    model.playing = false;
    play.textContent = "Play";
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
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
    var hasBeats = beats.length > 0;
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
    play.disabled = false;
    if (model.index < 0) model.index = 0;
    if (model.index >= beats.length) model.index = beats.length - 1;
    prev.disabled = model.index <= 0;
    next.disabled = model.index >= beats.length - 1;
    scrub.max = String(beats.length - 1);
    scrub.value = String(model.index);

    beatsEl.innerHTML = "";
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
        button.className = "beat" + (item.index === model.index ? " active" : "");
        var kind = tone(beat.status);
        var marks = "";
        if (beat.hasScreenshot || beat.dataUri) marks += "<span class=\"mark shot\">shot</span>";
        if (kind) marks += "<span class=\"mark " + kind + "\">" + escapeHtml(beat.status) + "</span>";
        button.innerHTML =
            "<span class=\"num\">" + (item.index + 1) + "</span>"
            + "<span class=\"copy\"><span class=\"phrase\">" + escapeHtml(beat.stepText || beat.text || "(event)") + "</span>"
            + "<span class=\"meta\">" + escapeHtml(beat.timestamp || "") + "</span></span>"
            + "<span class=\"marks\">" + marks + "</span>";
        button.addEventListener("click", function () {
          stopPlay();
          model.index = item.index;
          render();
        });
        beatsEl.appendChild(button);
        if (item.index === model.index) {
          try {
            var top = button.offsetTop - 40;
            if (top < beatsEl.scrollTop || top > beatsEl.scrollTop + beatsEl.clientHeight - 64) {
              beatsEl.scrollTop = Math.max(0, top);
            }
          } catch (ignored) {}
        }
      });
    });

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
    stepMeta.textContent = current && current.timestamp
        ? ("Step " + (model.index + 1) + " of " + beats.length + " · " + current.timestamp)
        : ("Step " + (model.index + 1) + " of " + beats.length);
    log.textContent = current && current.logLines && current.logLines.length
        ? current.logLines.join("\n")
        : "No INFO+ log lines retained for this step.";
    status.textContent = "Step " + (model.index + 1) + " of " + beats.length
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
    if (!model.beats || !model.beats.length) return;
    var nextIndex = model.index + delta;
    if (nextIndex < 0) {
      stopPlay();
      model.index = 0;
      render();
      return;
    }
    if (nextIndex >= model.beats.length) {
      stopPlay();
      model.index = model.beats.length - 1;
      render();
      return;
    }
    model.index = nextIndex;
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
  play.addEventListener("click", function () {
    if (!model.beats || !model.beats.length) return;
    if (model.playing) {
      stopPlay();
      render();
      return;
    }
    if (model.index >= model.beats.length - 1) model.index = 0;
    model.playing = true;
    play.textContent = "Pause";
    render();
    timer = setInterval(function () { show(1); }, 1400);
  });
  scrub.addEventListener("input", function () {
    stopPlay();
    model.index = Number(scrub.value) || 0;
    render();
  });
  runs.addEventListener("change", function () {
    stopPlay();
    excerptLayer = "";
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
    next.layers = next.layers || [];
    var sameRun = selectedRunId(model) && selectedRunId(model) === selectedRunId(next)
        && (model.beats || []).length === next.beats.length;
    var keepIndex = sameRun ? Math.min(model.index || 0, Math.max(0, next.beats.length - 1)) : (next.index || 0);
    var keepPlaying = sameRun && model.playing;
    model = next;
    model.index = keepIndex;
    model.playing = keepPlaying;
    if (!keepPlaying) stopPlay();
    render();
  };

  window.onWorkbenchReady = function () {
    if (window.diagnosticHost && window.diagnosticHost.ready) window.diagnosticHost.ready();
  };
})();
