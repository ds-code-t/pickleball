(function () {
  var params = new URLSearchParams(location.search);
  var token = params.get("t") || "";
  var last = 0;

  function pane(name) {
    return document.getElementById(name === "diagram" ? "diagram" : name === "chat" ? "chat" : "instructions");
  }

  function node(handle) {
    if (!handle) return null;
    return document.querySelector("[data-pkb-handle='" + handle.replace(/'/g, "") + "']");
  }

  function ack(row) {
    var text = row.html || "";
    var send = function (digest) {
      fetch("/rendered?t=" + encodeURIComponent(token), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ seq: row.seq, pane: row.pane, handle: row.handle || "", textSha256: digest })
      });
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

  function apply(row) {
    var target = pane(row.pane || "instructions");
    if (!target) return;
    if (row.op === "cleanup") {
      document.getElementById("instructions").replaceChildren();
      document.getElementById("diagram").replaceChildren();
      ack(row);
      return;
    }
    if (row.op === "chat") {
      var line = document.createElement("p");
      line.textContent = row.html || "";
      document.getElementById("chat").appendChild(line);
      ack(row);
      return;
    }
    var holder = document.createElement("div");
    holder.innerHTML = row.html || "";
    if (row.handle) holder.setAttribute("data-pkb-handle", row.handle);
    if (row.op === "replace" && node(row.handle)) {
      node(row.handle).replaceWith(holder);
    } else if (row.op === "insert" && node(row.handle)) {
      var anchor = node(row.handle);
      if (row.where === "before") anchor.before(holder);
      else if (row.where === "after") anchor.after(holder);
      else anchor.appendChild(holder);
    } else if (row.op === "remove") {
      var found = node(row.handle) || document.getElementById(row.handle || "");
      if (found) found.remove();
    } else {
      target.appendChild(holder);
    }
    ack(row);
  }

  function connect() {
    if (!window.EventSource) {
      poll();
      return;
    }
    var source = new EventSource("/events?t=" + encodeURIComponent(token));
    source.onmessage = function (event) {
      var row = JSON.parse(event.data);
      if (row.seq) last = row.seq;
      apply(row);
    };
    source.onerror = function () {
      source.close();
      poll();
    };
  }

  function poll() {
    fetch("/poll?t=" + encodeURIComponent(token) + "&after=" + last)
      .then(function (response) { return response.json(); })
      .then(function (rows) {
        rows.forEach(function (row) {
          if (row.seq > last) last = row.seq;
          apply(row);
        });
      });
  }

  document.getElementById("composer").addEventListener("submit", function (event) {
    event.preventDefault();
    var input = document.getElementById("text");
    fetch("/chat?t=" + encodeURIComponent(token), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text: input.value })
    });
    input.value = "";
  });

  document.body.addEventListener("click", function (event) {
    var id = event.target && event.target.id ? event.target.id : "";
    fetch("/input?t=" + encodeURIComponent(token), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ type: "click", id: id, value: "" })
    });
  });

  connect();
}());
