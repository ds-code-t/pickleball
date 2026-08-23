(function () {
  var runs = document.getElementById("runs");
  var frame = document.getElementById("frame");
  var step = document.getElementById("step");
  var status = document.getElementById("status");
  var layers = document.getElementById("layers");
  var excerpt = document.getElementById("excerpt");
  var model = { runs: [], frames: [], layers: [], index: 0, playing: false, gap: "" };
  var timer = null;

  function render() {
    runs.innerHTML = "";
    model.runs.forEach(function (run) {
      var option = document.createElement("option");
      option.value = run.runId;
      option.textContent = run.label || run.runId;
      if (run.selected) option.selected = true;
      runs.appendChild(option);
    });
    var current = model.frames[model.index];
    if (current && current.dataUri) {
      frame.src = current.dataUri;
      frame.style.display = "inline-block";
      step.textContent = current.stepText || "";
    } else {
      frame.removeAttribute("src");
      frame.style.display = "none";
      step.textContent = model.gap || "No retained screenshot frames for this run.";
    }
    status.textContent = model.frames.length
        ? ("Frame " + (model.index + 1) + " / " + model.frames.length)
        : (model.gap || "No retained diagnostic run");
    layers.innerHTML = "";
    (model.layers || []).forEach(function (layer, index) {
      var row = document.createElement("div");
      row.className = "layer" + (layer.present ? "" : " missing");
      row.innerHTML = "<span>" + layer.layer + "</span><span>" + (layer.present ? "available" : "absent") + "</span>";
      if (layer.present) {
        row.addEventListener("click", function () {
          excerpt.textContent = layer.excerpt || "";
          if (window.diagnosticHost && window.diagnosticHost.focusLayer) {
            window.diagnosticHost.focusLayer(layer.layer);
          }
        });
      }
      layers.appendChild(row);
    });
  }

  function show(delta) {
    if (!model.frames.length) return;
    model.index = (model.index + delta + model.frames.length) % model.frames.length;
    render();
  }

  document.getElementById("prev").addEventListener("click", function () { show(-1); });
  document.getElementById("next").addEventListener("click", function () { show(1); });
  document.getElementById("play").addEventListener("click", function () {
    model.playing = !model.playing;
    this.textContent = model.playing ? "Pause" : "Play";
    if (timer) clearInterval(timer);
    if (model.playing) {
      timer = setInterval(function () { show(1); }, 1200);
    }
  });
  runs.addEventListener("change", function () {
    if (window.diagnosticHost && window.diagnosticHost.selectRun) {
      window.diagnosticHost.selectRun(runs.value);
    }
  });

  window.setDiagnosticState = function (json) {
    model = typeof json === "string" ? JSON.parse(json) : json;
    model.index = model.index || 0;
    render();
  };

  window.onWorkbenchReady = function () {
    if (window.diagnosticHost && window.diagnosticHost.ready) window.diagnosticHost.ready();
  };
})();
