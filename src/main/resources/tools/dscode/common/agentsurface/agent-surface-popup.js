(function () {
  var params = new URLSearchParams(location.search);
  var token = params.get("t") || "";
  var last = 0;
  var echoes = [];
  var chat = document.getElementById("chat");
  var status = document.getElementById("pkb-status");
  var input = document.getElementById("text");
  var sendButton = document.getElementById("send");

  function tagPopup(node) {
    if (!node || node.nodeType !== 1 || !node.setAttribute) return node;
    node.setAttribute("data-pkb-meta", "true");
    if (!node.getAttribute("data-pkb-purpose")) node.setAttribute("data-pkb-purpose", "panel");
    if (node.id && node.id.indexOf("pkb-") !== 0 && node.id !== "text" && node.id !== "send" && node.id !== "chat") {
      node.id = "pkb-" + node.id;
    }
    return node;
  }

  function url(path) {
    return path + (path.indexOf("?") >= 0 ? "&" : "?") + "t=" + encodeURIComponent(token);
  }

  function post(path, body) {
    return fetch(url(path), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
  }

  function setStatus(state) {
    status.setAttribute("data-state", state);
    status.title = state === "live" ? "connected" : state === "retry" ? "reconnecting" : "connecting";
  }

  function pane(name) {
    return document.getElementById(name === "diagram" ? "pkb-diagram" : name === "chat" ? "chat" : "pkb-instructions");
  }

  function node(handle) {
    if (!handle) return null;
    return document.querySelector("[data-pkb-handle='" + handle.replace(/'/g, "") + "']");
  }

  function ack(row) {
    var text = row.html || "";
    var send = function (digest) {
      post("/rendered", { seq: row.seq, pane: row.pane, handle: row.handle || "", textSha256: digest }).catch(function () {});
    };
    if (window.crypto && crypto.subtle && window.TextEncoder) {
      crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)).then(function (buf) {
        var bytes = new Uint8Array(buf);
        var hex = "";
        for (var i = 0; i < bytes.length; i++) hex += bytes[i].toString(16).padStart(2, "0");
        send(hex);
      }).catch(function () { send(""); });
      return;
    }
    send(row.textSha256 || "");
  }

  function nearBottom() {
    return chat.scrollHeight - chat.scrollTop - chat.clientHeight < 48;
  }

  function time(at) {
    var date = at ? new Date(at) : new Date();
    if (isNaN(date.getTime())) date = new Date();
    return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }

  function appendDistinguished(node, text) {
    var src = String(text == null ? "" : text);
    var re = /"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/g;
    var last = 0;
    var match;
    var any = false;
    while ((match = re.exec(src))) {
      any = true;
      if (match.index > last) node.appendChild(document.createTextNode(src.slice(last, match.index)));
      var chip = document.createElement("span");
      chip.className = "pkb-value";
      chip.textContent = match[0];
      tagPopup(chip);
      node.appendChild(chip);
      last = match.index + match[0].length;
    }
    if (!any) node.appendChild(document.createTextNode(src));
    else if (last < src.length) node.appendChild(document.createTextNode(src.slice(last)));
    return node;
  }

  function bubble(text, from, at, state) {
    var stick = nearBottom();
    var line = document.createElement("div");
    tagPopup(line);
    line.className = "pkb-msg";
    line.setAttribute("data-from", from);
    if (state) line.setAttribute("data-state", state);
    var meta = document.createElement("span");
    tagPopup(meta);
    meta.className = "pkb-meta";
    meta.textContent = (from === "human" ? "You" : from === "system" ? "System" : "Agent") + " \u00b7 " + time(at);
    var body = document.createElement("span");
    tagPopup(body);
    body.className = "pkb-body";
    appendDistinguished(body, text);
    line.appendChild(meta);
    line.appendChild(body);
    chat.appendChild(line);
    if (stick || from === "human") chat.scrollTop = chat.scrollHeight;
    if (from !== "human" && !isActive("chat")) badge("chat");
    return line;
  }

  var loadedSurface = {};

  function kindOf(row) {
    var sentence = row.sentence || "";
    return sentence.indexOf("overlay") >= 0 ? "overlay" : "html";
  }

  function ensureSurface(kind) {
    if (loadedSurface[kind]) return;
    loadedSurface[kind] = true;
  }

  function mount(row) {
    var holder = document.createElement("div");
    tagPopup(holder);
    if (row.handle) holder.setAttribute("data-pkb-handle", row.handle);
    var shadow = holder.attachShadow({ mode: "open" });
    var kind = kindOf(row);
    var link = document.createElement("link");
    tagPopup(link);
    link.rel = "stylesheet";
    link.href = url("assets/surface/" + kind + ".css");
    var body = document.createElement("div");
    tagPopup(body);
    body.className = "pkb-surface";
    body.innerHTML = row.html || "";
    shadow.appendChild(link);
    shadow.appendChild(body);
    ensureSurface(kind);
    return holder;
  }

  function apply(row) {
    if (row.seq && row.seq <= last) return;
    if (row.seq) last = row.seq;
    var target = pane(row.pane || "instructions");
    if (!target) return;
    if (row.op === "cleanup") {
      document.getElementById("pkb-instructions").replaceChildren();
      document.getElementById("pkb-diagram").replaceChildren();
      ack(row);
      return;
    }
    if (row.op === "chat") {
      var from = row.codes === "human" ? "human" : row.codes === "system" ? "system" : "agent";
      var text = row.html || "";
      var echoed = from === "human" ? echoes.indexOf(text) : -1;
      if (echoed >= 0) echoes.splice(echoed, 1);
      else bubble(text, from, row.at);
      ack(row);
      return;
    }
    var holder = mount(row);
    if (row.op === "replace" && node(row.handle)) {
      node(row.handle).replaceWith(holder);
    } else if (row.op === "insert" && node(row.handle)) {
      var anchor = node(row.handle);
      if (row.where === "before") anchor.before(holder);
      else if (row.where === "after") anchor.after(holder);
      else {
        var inner = anchor.shadowRoot && anchor.shadowRoot.querySelector(".pkb-surface");
        if (inner) inner.appendChild(holder);
        else anchor.appendChild(holder);
      }
    } else if (row.op === "remove") {
      var found = node(row.handle) || document.getElementById(row.handle || "");
      if (found) found.remove();
    } else {
      target.appendChild(holder);
    }
    var paneName = target.getAttribute("data-pane") || "";
    if (paneName !== "chat" && !isActive(paneName)) badge(paneName);
    ack(row);
  }

  // EventSource reconnects by itself with Last-Event-ID after the server ends a batch.
  // Closing it here is what made the page go deaf after the first batch.
  function connect() {
    if (!window.EventSource) {
      pollLoop(500);
      return;
    }
    var source = new EventSource(url("/events"));
    source.onopen = function () { setStatus("live"); };
    source.onmessage = function (event) {
      setStatus("live");
      try {
        apply(JSON.parse(event.data));
      } catch (failure) {
        // A malformed row is skipped; the next one still renders.
      }
    };
    source.onerror = function () {
      // CONNECTING is the normal long-poll reconnect between batches; only CLOSED is a real drop.
      if (source.readyState === EventSource.CLOSED) {
        setStatus("retry");
        setTimeout(connect, 1000);
      }
    };
  }

  function pollLoop(delay) {
    fetch(url("/poll?after=" + last))
      .then(function (response) { return response.json(); })
      .then(function (rows) {
        setStatus("live");
        rows.forEach(apply);
        setTimeout(function () { pollLoop(500); }, 500);
      })
      .catch(function () {
        setStatus("retry");
        setTimeout(function () { pollLoop(Math.min(delay * 2, 5000)); }, delay);
      });
  }

  function refreshSend() {
    sendButton.disabled = input.value.trim() === "";
    input.style.height = "auto";
    input.style.height = Math.min(input.scrollHeight, 120) + "px";
  }

  function deliver(text, line) {
    line.setAttribute("data-state", "pending");
    var old = line.querySelector(".pkb-retry");
    if (old) old.remove();
    echoes.push(text);
    post("/chat", { text: text })
      .then(function (response) {
        if (!response.ok) throw new Error("status " + response.status);
        line.removeAttribute("data-state");
      })
      .catch(function () {
        var at = echoes.indexOf(text);
        if (at >= 0) echoes.splice(at, 1);
        line.setAttribute("data-state", "failed");
        var retry = document.createElement("button");
        tagPopup(retry);
        retry.type = "button";
        retry.className = "pkb-retry";
        retry.textContent = "Retry";
        retry.addEventListener("click", function () { deliver(text, line); });
        line.querySelector(".pkb-meta").appendChild(retry);
      });
  }

  function submit() {
    var text = input.value.trim();
    if (!text) return;
    var line = bubble(text, "human", null, "pending");
    input.value = "";
    refreshSend();
    deliver(text, line);
  }

  document.getElementById("pkb-composer").addEventListener("submit", function (event) {
    event.preventDefault();
    submit();
  });
  input.addEventListener("input", refreshSend);
  input.addEventListener("keydown", function (event) {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
      event.preventDefault();
      submit();
    }
  });

  function isActive(name) {
    var tab = document.querySelector("[data-tab='" + name + "']");
    return tab && tab.getAttribute("aria-selected") === "true";
  }

  function badge(name) {
    var tab = document.querySelector("[data-tab='" + name + "']");
    if (!tab) return;
    var mark = tab.querySelector(".pkb-badge");
    if (!mark) {
      mark = document.createElement("span");
      tagPopup(mark);
      mark.className = "pkb-badge";
      mark.textContent = "\u25cf";
      tab.appendChild(mark);
    }
  }

  function show(name) {
    document.querySelectorAll("[data-tab]").forEach(function (tab) {
      var on = tab.getAttribute("data-tab") === name;
      tab.setAttribute("aria-selected", on ? "true" : "false");
      if (on) {
        var mark = tab.querySelector(".pkb-badge");
        if (mark) mark.remove();
      }
    });
    ["chat", "instructions", "diagram"].forEach(function (id) {
      var pane = document.getElementById(id) || document.getElementById("pkb-" + id);
      if (pane) pane.hidden = id !== name;
    });
    document.getElementById("pkb-composer").hidden = name !== "chat";
  }

  document.querySelectorAll("[data-tab]").forEach(function (tab) {
    tab.addEventListener("click", function () { show(tab.getAttribute("data-tab")); });
  });

  // Only agent-placed elements report input; ordinary clicks in the popup are not events.
  document.body.addEventListener("click", function (event) {
    var placed = event.target && event.target.closest ? event.target.closest("[data-pkb-id]") : null;
    if (!placed) return;
    post("/input", { type: "click", id: placed.getAttribute("data-pkb-id"), value: "" }).catch(function () {});
  });

  tagPopup(document.body);
  document.querySelectorAll("body *").forEach(tagPopup);
  setStatus("connecting");
  refreshSend();
  connect();
}());
