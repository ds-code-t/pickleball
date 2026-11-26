package tools.dscode.store.dashboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One self-contained HTML page. The script is inline. Nothing is loaded from
 * a network, a CDN, or a local server.
 */
public final class DashboardHtml {
    private DashboardHtml() {
    }

    public static String render(DashboardSnapshot snapshot) {
        return render(snapshot, "");
    }

    /**
     * {@code linkBase} is the project root as seen from the page's folder,
     * for example {@code ../../}. Project-relative feature, data, and artifact
     * paths are prefixed with it so the links open from the written file.
     */
    public static String render(DashboardSnapshot snapshot, String linkBase) {
        DashboardSnapshot page = snapshot == null
                ? new DashboardSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of())
                : snapshot;
        StringBuilder html = new StringBuilder();
        html.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Pickleball dashboard</title>
                <style>
                :root { color-scheme: light; --ink:#1c1915; --muted:#5c564c; --line:#e4ddd2; --paper:#f7f4ee; --card:#fffdf9; --accent:#0f6e56; --bad:#9f2d2d; --warn:#8a5a12; }
                * { box-sizing: border-box; }
                body { margin: 0; font: 15px/1.45 "Segoe UI", "Source Sans 3", sans-serif; color: var(--ink); background: var(--paper); }
                header, main { max-width: 1180px; margin-left: auto; margin-right: auto; }
                header { padding: 28px 32px 8px; }
                h1 { font-size: 28px; font-weight: 650; margin: 0 0 4px; letter-spacing: -0.03em; }
                h2 { font-size: 15px; margin: 0 0 8px; letter-spacing: 0.04em; text-transform: uppercase; color: var(--muted); }
                h3 { font-size: 12px; margin: 12px 0 4px; letter-spacing: 0.04em; text-transform: uppercase; color: var(--muted); }
                .sub { color: var(--muted); margin: 0; }
                main { padding: 8px 32px 48px; display: grid; gap: 16px; }
                .bar, .card { background: var(--card); border: 1px solid var(--line); border-radius: 14px; }
                .bar { position: sticky; top: 0; z-index: 2; padding: 14px 16px; display: flex; flex-wrap: wrap; gap: 12px 16px; align-items: end; box-shadow: 0 8px 20px rgba(28,25,21,.06); }
                label { display: grid; gap: 4px; font-size: 12px; color: var(--muted); }
                input[type=search], input[type=text] { border: 1px solid var(--line); border-radius: 8px; padding: 7px 9px; font: inherit; color: inherit; background: #fff; min-width: 9rem; }
                input[type=range] { width: min(220px, 46vw); accent-color: var(--accent); }
                #clear { border: 1px solid var(--line); border-radius: 8px; background: #fff; color: var(--ink); font: inherit; padding: 7px 12px; cursor: pointer; }
                .grid { display: grid; grid-template-columns: 1.3fr 1fr; gap: 16px; }
                .cards { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
                .card { padding: 14px 16px; min-width: 0; }
                .legend { display: flex; flex-wrap: wrap; gap: 8px 14px; margin: 0 0 8px; color: var(--muted); font-size: 12px; }
                .legend span, .kind { display: inline-flex; align-items: center; gap: 6px; }
                .legend span::before, .kind::before { content: ""; width: 8px; height: 8px; border-radius: 50%; background: var(--accent); }
                .legend [data-kind=scenario]::before, .kind[data-kind=scenario]::before { background: #2f5f98; }
                .legend [data-kind=case]::before, .kind[data-kind=case]::before { background: var(--warn); }
                .legend [data-kind=entity]::before, .kind[data-kind=entity]::before { background: #5c564c; }
                .legend [data-kind=error]::before, .kind[data-kind=error]::before { background: var(--bad); }
                .viewport { position: relative; height: min(42vh, 440px); overflow: auto; border-top: 1px solid var(--line); }
                .spacer { position: relative; width: 100%; }
                .window { position: absolute; left: 0; right: 0; top: 0; }
                button.row, .row { display: flex; align-items: center; gap: 8px; width: 100%; text-align: left; border: 0; border-bottom: 1px solid var(--line); background: transparent; padding: 8px 4px; font: inherit; color: inherit; min-height: 36px; }
                button.row { cursor: pointer; }
                button.row[aria-current=true], button.row:hover { background: #f3faf6; }
                .row > div:first-child, button.row > span.title { min-width: 0; overflow-wrap: anywhere; }
                .meta { color: var(--muted); font-size: 12px; }
                .row > .meta, #count { margin-left: auto; }
                .bad { color: var(--bad); }
                .chip { display: inline-block; border-radius: 999px; padding: 1px 8px; font-size: 12px; font-weight: 650; background: #f3efe8; }
                .chip[data-status=failed], .chip[data-status=failure] { color: var(--bad); background: #fdecec; }
                .chip[data-status=passed] { color: var(--accent); background: #e7f6f1; }
                .links a { color: var(--accent); display: inline-block; margin: 0 12px 6px 0; overflow-wrap: anywhere; }
                .empty { color: var(--muted); padding: 12px 0; }
                button:focus-visible, input:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
                @media (max-width: 900px) { .grid, .cards { grid-template-columns: 1fr; } main, header { padding-left: 16px; padding-right: 16px; } .viewport { height: min(50vh, 360px); } }
                </style>
                </head>
                <body>
                <header>
                  <h1>Run dashboard</h1>
                  <p class="sub">View only. Same embedded store. No second database. Generated <span id="when"></span>.</p>
                </header>
                <main>
                  <form class="bar" id="filters">
                    <label>Status <input id="status" type="search" placeholder="FAILED"></label>
                    <label>Feature <input id="feature" type="search" placeholder="pay.feature"></label>
                    <label>Entity or case <input id="entity" type="search" placeholder="Case#12"></label>
                    <label>Text <input id="text" type="search" placeholder="declined"></label>
                    <label>Rewind <input id="rewind" type="range" min="0" max="0" value="0"><span id="rewind-label" class="meta"></span></label>
                    <button type="button" id="clear">Clear</button>
                    <p id="count" class="meta"></p>
                  </form>
                  <section class="grid">
                    <article class="card"><h2>Timeline</h2><p class="legend" id="legend"><span data-kind="run">Run</span><span data-kind="scenario">Scenario</span><span data-kind="case">Case</span><span data-kind="entity">Entity</span><span data-kind="error">Error</span></p><div id="timeline" class="viewport"></div></article>
                    <article class="card"><h2>Run</h2><div id="detail"></div></article>
                  </section>
                  <section class="cards">
                    <article class="card"><h2>Business entities</h2><div id="entities" class="viewport"></div></article>
                    <article class="card"><h2>Case IDs</h2><div id="cases" class="viewport"></div></article>
                    <article class="card"><h2>Common errors</h2><div id="errors" class="viewport"></div></article>
                    <article class="card"><h2>Case types</h2><div id="case-types" class="viewport"></div></article>
                  </section>
                  <article class="card"><h2>Runs</h2><div id="runs" class="viewport"></div></article>
                </main>
                <script id="data" type="application/json">
                """);
        html.append(json(page, linkBase));
        html.append("""
                </script>
                <script>
                const data = JSON.parse(document.getElementById("data").textContent);
                const $ = (id) => document.getElementById(id);
                $("when").textContent = data.generatedAt || "";
                const times = [...new Set(data.markers.map(m => m.at).filter(Boolean))].sort();
                const rewind = $("rewind");
                rewind.max = String(Math.max(0, times.length - 1));
                rewind.value = rewind.max;
                let selected = data.runs.length ? data.runs[0].id : "";
                function cursor() { return times.length ? times[Number(rewind.value)] : ""; }
                function visible(at) { const end = cursor(); return !end || !at || at <= end; }
                function runsNow() {
                  const status = $("status").value.trim().toLowerCase();
                  const feature = $("feature").value.trim().toLowerCase();
                  const entity = $("entity").value.trim().toLowerCase();
                  const text = $("text").value.trim().toLowerCase();
                  const end = cursor();
                  return data.runs.filter(run => {
                    if (end && run.startedAt && run.startedAt > end) return false;
                    if (status && String(run.status).toLowerCase() !== status) return false;
                    if (feature && !(run.features || []).join("\\n").toLowerCase().includes(feature) && !String(run.featurePath).toLowerCase().includes(feature)) return false;
                    if (text) {
                      const blob = (String(run.note) + " " + String(run.scenario) + " " + (run.scenarios || []).join(" ") + " " + (run.features || []).join(" ")).toLowerCase();
                      const markerHit = data.markers.some(m => m.runId === run.id && (String(m.id) + " " + String(m.label)).toLowerCase().includes(text));
                      if (!blob.includes(text) && !markerHit) return false;
                    }
                    if (entity) {
                      const hit = data.markers.some(m => m.runId === run.id && (m.kind === "entity" || m.kind === "case") && String(m.id).toLowerCase().includes(entity) && visible(m.at));
                      if (!hit) return false;
                    }
                    return true;
                  });
                }
                function markersNow(runs) {
                  const ids = new Set(runs.map(run => run.id));
                  return data.markers.filter(m => ids.has(m.runId) && visible(m.at));
                }
                function counts(markers, kind) {
                  const map = new Map();
                  markers.filter(m => m.kind === kind).forEach(m => {
                    const key = kind === "case" ? m.id : (kind === "error" || kind === "entity" ? m.id : m.label);
                    map.set(key, (map.get(key) || 0) + 1);
                  });
                  return [...map.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
                }
                function caseTypes(markers) {
                  const map = new Map();
                  markers.filter(m => m.kind === "case").forEach(m => map.set(m.label || "unspecified", (map.get(m.label || "unspecified") || 0) + 1));
                  return [...map.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
                }
                function virtualize(host, items, draw) {
                  const row = 36;
                  host.innerHTML = "";
                  if (!items.length) { host.innerHTML = '<p class="empty">Nothing in this range.</p>'; return; }
                  const spacer = document.createElement("div");
                  spacer.className = "spacer";
                  spacer.style.height = (items.length * row) + "px";
                  const window = document.createElement("div");
                  window.className = "window";
                  spacer.appendChild(window);
                  host.appendChild(spacer);
                  const paint = () => {
                    const start = Math.max(0, Math.floor(host.scrollTop / row) - 4);
                    const end = Math.min(items.length, start + Math.ceil(host.clientHeight / row) + 8);
                    window.style.transform = "translateY(" + (start * row) + "px)";
                    window.replaceChildren();
                    for (let i = start; i < end; i++) window.appendChild(draw(items[i], i));
                  };
                  host.onscroll = paint;
                  paint();
                }
                function line(text, meta, bad) {
                  const el = document.createElement("div");
                  el.className = "row";
                  el.innerHTML = '<div' + (bad ? ' class="bad"' : '') + '></div><div class="meta"></div>';
                  el.firstChild.textContent = text;
                  el.lastChild.textContent = meta || "";
                  return el;
                }
                function pick(text, meta, bad, apply) {
                  const el = document.createElement("button");
                  el.type = "button";
                  el.className = "row";
                  el.innerHTML = '<span class="title' + (bad ? ' bad' : '') + '"></span><span class="meta"></span>';
                  el.firstChild.textContent = text;
                  el.lastChild.textContent = meta || "";
                  el.onclick = apply;
                  return el;
                }
                function render() {
                  const end = cursor();
                  $("rewind-label").textContent = end ? "Through " + end : "all";
                  const runs = runsNow();
                  $("count").textContent = "Showing " + runs.length + (runs.length === 1 ? " run" : " runs");
                  if (!runs.some(run => run.id === selected)) selected = runs.length ? runs[0].id : "";
                  const markers = markersNow(runs);
                  virtualize($("timeline"), markers, m => {
                    const button = document.createElement("button");
                    button.type = "button";
                    button.className = "row";
                    button.setAttribute("data-kind", m.kind);
                    const dot = document.createElement("span");
                    dot.className = "kind";
                    dot.setAttribute("data-kind", m.kind);
                    const title = document.createElement("span");
                    title.className = "title" + (m.kind === "error" ? " bad" : "");
                    title.textContent = m.kind + " " + m.id;
                    const meta = document.createElement("span");
                    meta.className = "meta";
                    meta.textContent = m.at || "";
                    button.append(dot, title, meta);
                    button.onclick = () => { selected = m.runId; render(); };
                    return button;
                  });
                  virtualize($("runs"), runs, run => {
                    const button = document.createElement("button");
                    button.type = "button";
                    button.className = "row";
                    button.setAttribute("aria-current", run.id === selected ? "true" : "false");
                    button.innerHTML = '<div></div><div class="meta"></div>';
                    button.firstChild.textContent = run.id + "  " + run.status;
                    button.lastChild.textContent = run.startedAt + "  failed " + run.failed;
                    button.onclick = () => { selected = run.id; render(); };
                    return button;
                  });
                  virtualize($("entities"), counts(markers, "entity"), pair => pick(pair[0], String(pair[1]), false, () => { $("entity").value = pair[0]; render(); }));
                  virtualize($("cases"), counts(markers, "case"), pair => pick(pair[0], String(pair[1]), false, () => { $("entity").value = pair[0]; render(); }));
                  virtualize($("errors"), counts(markers, "error"), pair => pick(pair[0], String(pair[1]), true, () => { $("text").value = pair[0]; render(); }));
                  virtualize($("case-types"), caseTypes(markers), pair => line(pair[0], String(pair[1])));
                  const run = runs.find(item => item.id === selected);
                  const detail = $("detail");
                  detail.replaceChildren();
                  if (!run) { detail.innerHTML = '<p class="empty">No run.</p>'; return; }
                  const title = document.createElement("p");
                  title.textContent = run.scenario || run.id;
                  detail.appendChild(title);
                  const meta = document.createElement("p");
                  meta.className = "meta";
                  const chip = document.createElement("span");
                  chip.className = "chip";
                  chip.setAttribute("data-status", String(run.status || "").toLowerCase());
                  chip.textContent = run.status || "";
                  meta.append(chip, document.createTextNode("  passed " + run.passed + "  failed " + run.failed + (run.gitCommit ? "  " + run.gitCommit : "")));
                  detail.appendChild(meta);
                  let links = null;
                  const local = (path) => /^([a-z][a-z0-9+.-]*:|\\/|[a-z]:[\\\\/])/i.test(path) ? path : (data.linkBase || "") + path;
                  const add = (href, label) => {
                    if (!href || !links) return;
                    const a = document.createElement("a");
                    a.href = href;
                    a.textContent = label;
                    links.appendChild(a);
                  };
                  const section = (name) => {
                    const heading = document.createElement("h3");
                    heading.textContent = name;
                    detail.appendChild(heading);
                    links = document.createElement("p");
                    links.className = "links";
                    detail.appendChild(links);
                  };
                  if ((run.features || []).length) section("Features");
                  (run.features || []).forEach(path => add(local(path), path));
                  if ((run.dataFiles || []).length) section("Data");
                  (run.dataFiles || []).forEach(path => add(local(path), path));
                  if ((run.artifacts || []).length) section("Artifacts");
                  (run.artifacts || []).forEach(path => add(local(path), path));
                  if (run.commitUrl) section("Commit");
                  if (run.commitUrl) add(run.commitUrl, run.gitCommit || "commit");
                }
                ["status", "feature", "entity", "text"].forEach(id => $(id).addEventListener("input", render));
                rewind.addEventListener("input", render);
                $("clear").addEventListener("click", () => {
                  ["status", "feature", "entity", "text"].forEach(id => $(id).value = "");
                  rewind.value = rewind.max;
                  render();
                });
                render();
                </script>
                </body>
                </html>
                """);
        String built = html.toString();
        int close = built.indexOf("</main>");
        if (close < 0) return built;
        return built.substring(0, close) + staticTimeline(page) + built.substring(close);
    }

    /** Static run list ordered by start time. The JSON array above keeps insertion order. */
    private static String staticTimeline(DashboardSnapshot page) {
        List<DashboardSnapshot.RunView> ordered = new ArrayList<>(page.runs());
        ordered.sort(Comparator.comparing(run -> run.startedAt() == null ? "" : run.startedAt()));
        StringBuilder section = new StringBuilder();
        section.append("<section id=\"run-timeline\"><h2>Runs by start</h2><ol>");
        for (DashboardSnapshot.RunView run : ordered) {
            section.append("<li>").append(esc(run.id()))
                    .append(' ').append(esc(run.startedAt()))
                    .append(' ').append(esc(run.note()))
                    .append("</li>");
        }
        section.append("</ol></section>");
        return section.toString();
    }

    private static String json(DashboardSnapshot page, String linkBase) {
        StringBuilder out = new StringBuilder();
        out.append("{\"generatedAt\":\"").append(esc(page.generatedAt()))
                .append("\",\"linkBase\":\"").append(esc(linkBase)).append("\",\"runs\":[");
        for (int i = 0; i < page.runs().size(); i++) {
            if (i > 0) out.append(',');
            DashboardSnapshot.RunView run = page.runs().get(i);
            out.append("{\"id\":\"").append(esc(run.id()))
                    .append("\",\"startedAt\":\"").append(esc(run.startedAt()))
                    .append("\",\"finishedAt\":\"").append(esc(run.finishedAt()))
                    .append("\",\"status\":\"").append(esc(run.status()))
                    .append("\",\"scenario\":\"").append(esc(run.scenario()))
                    .append("\",\"passed\":").append(run.passed())
                    .append(",\"failed\":").append(run.failed())
                    .append(",\"note\":\"").append(esc(run.note()))
                    .append("\",\"gitCommit\":\"").append(esc(run.gitCommit()))
                    .append("\",\"featurePath\":\"").append(esc(run.featurePath()))
                    .append("\",\"commitUrl\":\"").append(esc(run.commitUrl()))
                    .append("\",\"features\":").append(array(run.features()))
                    .append(",\"dataFiles\":").append(array(run.dataFiles()))
                    .append(",\"artifacts\":").append(array(run.artifacts()))
                    .append(",\"scenarios\":").append(array(run.scenarios()))
                    .append('}');
        }
        out.append("],\"markers\":[");
        for (int i = 0; i < page.markers().size(); i++) {
            if (i > 0) out.append(',');
            DashboardSnapshot.Marker marker = page.markers().get(i);
            out.append("{\"at\":\"").append(esc(marker.at()))
                    .append("\",\"kind\":\"").append(esc(marker.kind()))
                    .append("\",\"id\":\"").append(esc(marker.id()))
                    .append("\",\"label\":\"").append(esc(marker.label()))
                    .append("\",\"runId\":\"").append(esc(marker.runId()))
                    .append("\"}");
        }
        out.append("]}");
        return out.toString();
    }

    private static String array(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append('"').append(esc(values.get(i))).append('"');
        }
        return out.append(']').toString();
    }

    private static String esc(String value) {
        value = shown(value);
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\r' -> out.append("\\r");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                default -> {
                    // JSON.parse rejects raw control characters; U+2028/2029 break older script parsers.
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** A secret assignment never reaches the page. The whole string becomes {@code ~MASK;}. */
    private static String shown(String value) {
        if (value == null || value.isBlank()) return value;
        try {
            tools.dscode.store.StoreSecrets.rejectAssignment(null, value);
            return value;
        } catch (RuntimeException ignored) {
            return "~MASK;";
        }
    }
}
