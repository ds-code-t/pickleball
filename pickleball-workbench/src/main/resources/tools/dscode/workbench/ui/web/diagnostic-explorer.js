(function () {
  var runs = document.getElementById("runs");
  var beatsEl = document.getElementById("beats");
  var frame = document.getElementById("frame");
  var step = document.getElementById("step");
  var log = document.getElementById("log");
  var status = document.getElementById("status");
  var model = { runs: [], beats: [], index: 0, playing: false, gap: "" };
  var timer = null;

  function render() {
    runs.innerHTML = "";
    (model.runs || []).forEach(function (run) {
      var option = document.createElement("option");
      option.value = run.runId;
      option.textContent = run.label || run.runId;
      if (run.selected) option.selected = true;
      runs.appendChild(option);
    });
    beatsEl.innerHTML = "";
    (model.beats || []).forEach(function (beat, index) {
      var button = document.createElement("button");
      button.type = "button";
      button.className = "beat" + (index === model.index ? " active" : "");
      button.innerHTML = "<span>" + escapeHtml(beat.stepText || beat.text || "(event)") + "</span>"
          + "<span class=\"meta\">" + escapeHtml((beat.status || "") + (beat.timestamp ? " · " + beat.timestamp : "")) + "</span>";
      button.addEventListener("click", function () {
        model.index = index;
        model.playing = false;
        render();
      });
      beatsEl.appendChild(button);
    });
    var current = (model.beats || [])[model.index];
    if (current && current.dataUri) {
      frame.src = current.dataUri;
      frame.style.display = "inline-block";
    } else {
      frame.removeAttribute("src");
      frame.style.display = "none";
    }
    step.textContent = current
        ? ((current.status ? "[" + current.status + "] " : "") + (current.stepText || current.text || ""))
        : (model.gap || "No retained diagnostic run");
    log.textContent = current && current.logLines && current.logLines.length
        ? current.logLines.join("\n")
        : (current ? "No INFO+ log lines retained for this step." : "");
    status.textContent = (model.beats || []).length
        ? ("Step " + (model.index + 1) + " / " + model.beats.length)
        : (model.gap || "No retained diagnostic run");
  }

  function escapeHtml(value) {
    return String(value || "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;");
  }

  function show(delta) {
    if (!model.beats || !model.beats.length) return;
    model.index = (model.index + delta + model.beats.length) % model.beats.length;
    render();
  }

  document.getElementById("prev").addEventListener("click", function () { show(-1); });
  document.getElementById("next").addEventListener("click", function () { show(1); });
  document.getElementById("play").addEventListener("click", function () {
    model.playing = !model.playing;
    this.textContent = model.playing ? "Pause" : "Play";
    if (timer) clearInterval(timer);
    if (model.playing) {
      timer = setInterval(function () { show(1); }, 1400);
    }
  });
  runs.addEventListener("change", function () {
    if (window.diagnosticHost && window.diagnosticHost.selectRun) {
      window.diagnosticHost.selectRun(runs.value);
    }
  });

  window.setDiagnosticState = function (json) {
    model = typeof json === "string" ? JSON.parse(json) : json;
    model.beats = model.beats || model.frames || [];
    model.index = model.index || 0;
    render();
  };

  window.onWorkbenchReady = function () {
    if (window.diagnosticHost && window.diagnosticHost.ready) window.diagnosticHost.ready();
  };
})();
