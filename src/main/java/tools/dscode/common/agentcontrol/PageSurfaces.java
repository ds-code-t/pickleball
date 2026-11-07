package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.common.composite.CompositeRuntime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Predefined page helpers. Agent HTML is data, not script. No free-form
 * {@code Runtime.evaluate} of agent-supplied JavaScript.
 */
final class PageSurfaces {
    static final String HTML_SENTENCE = "agent-injected HTML; a person did not author this node";
    static final String OVERLAY_SENTENCE = "agent-injected overlay; a person did not author this UI";
    static final String STYLE_SENTENCE = "agent style; a person did not author this highlight";

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final CopyOnWriteArrayList<Path> PAGES = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Managed> MANAGED = new CopyOnWriteArrayList<>();
    private static volatile WebDriver driver;
    private static volatile String focusedTab;
    private static final Map<String, FrameOwner> FRAMES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, int[]> GEOMETRY = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> CDP_HIGHLIGHTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record FrameOwner(String browserId, WebDriver driver, String tab) {
    }

    private static final String LIVE_HELPER = """
            var spec = arguments[0];
            var op = spec.op;
            var resolved = null;
            function node() {
              if (resolved) return resolved;
              var handle = spec.handle || '';
              if (spec.bridge) {
                var bridged = document.querySelector('[data-pkb-bridge="' + CSS.escape(String(spec.bridge)) + '"]');
                if (bridged) {
                  bridged.removeAttribute('data-pkb-bridge');
                  resolved = bridged;
                  return bridged;
                }
              }
              if (!handle) return null;
              var direct = document.getElementById(handle);
              if (direct) return direct;
              var marked = document.querySelector('[data-pkb-handle="' + CSS.escape(handle) + '"]')
                || document.querySelector('[data-pkb-id="' + CSS.escape(handle) + '"]');
              if (marked) return marked;
              return null;
            }
            function mark(el, kind, sentence) {
              if (!el || el.nodeType !== 1) return;
              el.setAttribute('data-pkb-agent', 'agent_injected');
              el.setAttribute('data-pkb-kind', kind);
              el.setAttribute('data-pkb-sentence', sentence);
              if (spec.id) el.setAttribute('data-pkb-id', spec.id);
            }
            function markTree(el, kind, sentence) {
              mark(el, kind, sentence);
              el.querySelectorAll('*').forEach(function(child) { mark(child, kind, sentence); });
            }
            function restoreStyle(el) {
              if (!el) return;
              var original = el.getAttribute('data-pkb-original-style');
              if (original) el.setAttribute('style', original);
              else el.removeAttribute('style');
              el.removeAttribute('data-pkb-style');
              el.removeAttribute('data-pkb-css');
              el.removeAttribute('data-pkb-sentence');
              el.removeAttribute('data-pkb-original-style');
            }
            if (op === 'highlight') {
              var el = node();
              if (!el) return {ok:false, error:'unknown handle'};
              if (!el.hasAttribute('data-pkb-original-style')) {
                el.setAttribute('data-pkb-original-style', el.getAttribute('style') || '');
              }
              var original = el.getAttribute('data-pkb-original-style') || '';
              var css = spec.css || '';
              var joined = original;
              if (joined && css && joined.trim().slice(-1) !== ';') joined += ';';
              el.setAttribute('style', joined + css);
              el.setAttribute('data-pkb-style', 'style_modified');
              el.setAttribute('data-pkb-sentence', spec.sentence);
              el.setAttribute('data-pkb-css', css);
              return {ok:true};
            }
            if (op === 'unhighlight') {
              var target = spec.handle ? node() : null;
              var list = spec.handle ? [target] : Array.from(document.querySelectorAll('[data-pkb-style]'));
              if (spec.handle && !target) return {ok:false, error:'unknown handle'};
              list.forEach(restoreStyle);
              return {ok:true};
            }
            if (op === 'remove' || op === 'cleanup') {
              window.__pkbUndo = window.__pkbUndo || {};
              var ids = [];
              if (op === 'cleanup') {
                document.querySelectorAll('[data-pkb-id]').forEach(function(el) {
                  var id = el.getAttribute('data-pkb-id');
                  if (id && ids.indexOf(id) < 0) ids.push(id);
                });
              } else if (spec.id) ids.push(spec.id);
              var removed = 0;
              ids.forEach(function(id) {
                var undo = window.__pkbUndo[id];
                var nodes = document.querySelectorAll('[data-pkb-id="' + CSS.escape(id) + '"]');
                if (!nodes.length) return;
                nodes.forEach(function(el) {
                  if (undo) {
                    var tpl = document.createElement('template');
                    tpl.innerHTML = undo;
                    el.replaceWith(tpl.content);
                    delete window.__pkbUndo[id];
                  } else el.remove();
                  removed++;
                });
              });
              if (op === 'cleanup') document.querySelectorAll('[data-pkb-style]').forEach(restoreStyle);
              if (op === 'remove' && removed === 0) return {ok:false, error:'unknown id'};
              return {ok:true, removed:removed};
            }
            if (spec.handle && !node()) {
              return {ok:false, error:'unknown handle'};
            }
            var host = document.createElement('div');
            host.innerHTML = spec.html || '';
            markTree(host, spec.kind, spec.sentence);
            var anchor = node();
            var left = spec.x;
            var top = spec.y;
            if (spec.kind === 'overlay') {
              if (left === undefined || left === null || top === undefined || top === null) {
                return {ok:false, error:'overlay needs a visual-viewport box'};
              }
              host.style.position = 'fixed';
              host.style.zIndex = '2147483646';
              host.style.left = left + 'px';
              host.style.top = top + 'px';
              host.style.background = '#fffef2';
              host.style.border = '2px solid #222';
              host.style.padding = '8px';
              host.style.maxWidth = '280px';
            } else if (spec.css) host.setAttribute('style', spec.css);
            if (op === 'replace' && anchor) {
              window.__pkbUndo = window.__pkbUndo || {};
              window.__pkbUndo[spec.id] = anchor.outerHTML;
              anchor.replaceWith(host);
            } else if (op === 'insert' && anchor && spec.where === 'before') anchor.parentNode.insertBefore(host, anchor);
            else if (op === 'insert' && anchor) anchor.parentNode.insertBefore(host, anchor.nextSibling);
            else (document.body || document.documentElement).appendChild(host);
            if (!window.__pkbOverlayBound) {
              window.__pkbOverlayBound = true;
              window.__pkbEvents = window.__pkbEvents || [];
              document.addEventListener('click', function(ev) {
                var n = ev.target && ev.target.closest ? ev.target.closest('[data-pkb-agent]') : null;
                if (!n) return;
                window.__pkbEvents.push({type:'click', id:n.getAttribute('data-pkb-id'), text:(n.innerText || '').slice(0, 200)});
              }, true);
              document.addEventListener('change', function(ev) {
                var n = ev.target && ev.target.closest ? ev.target.closest('[data-pkb-agent]') : null;
                if (!n) return;
                window.__pkbEvents.push({type:'input', id:n.getAttribute('data-pkb-id'), name:ev.target.name || '', value:ev.target.value || ''});
              }, true);
            }
            return {ok:true, id:spec.id, x:left, y:top};
            """;

    /** Predefined DOM.resolveNode stamp. Not agent script. Removed by the helper after lookup. */
    static final String BRIDGE_MARK =
            "function(token){ if (!this || this.nodeType !== 1) return false; this.setAttribute('data-pkb-bridge', token); return true; }";

    /**
     * Names an iframe only when it has neither name nor id, and stamps a bridge token.
     * The caller restores both after switchTo().frame(name).
     */
    static final String FRAME_MARK =
            "function(token){ if (!this || this.nodeType !== 1) return ''; var tag = String(this.tagName || '').toLowerCase(); if (tag !== 'iframe' && tag !== 'frame') return ''; var previousName = this.getAttribute('name'); var name = previousName || this.getAttribute('id') || ''; var changedName = false; if (!name) { this.setAttribute('name', token); name = token; changedName = true; } var previousBridge = this.getAttribute('data-pkb-bridge'); this.setAttribute('data-pkb-bridge', token); return JSON.stringify({name:name, changedName:changedName, previousBridge:previousBridge}); }";

    static final String FRAME_RESTORE =
            "function(spec){ if (!this || this.nodeType !== 1 || !spec) return false; if (spec.changedName) this.removeAttribute('name'); if (spec.previousBridge == null || spec.previousBridge === '') this.removeAttribute('data-pkb-bridge'); else this.setAttribute('data-pkb-bridge', spec.previousBridge); return true; }";

    private PageSurfaces() {
    }

    record Managed(String id, WebDriver driver, String template, boolean owned) {
    }

    static void manage(WebDriver webDriver, String template, boolean owned) {
        if (webDriver == null) return;
        for (Managed managed : MANAGED) {
            if (managed.driver == webDriver) return;
        }
        String id = "browser-" + (MANAGED.size() + 1);
        if (webDriver instanceof RemoteWebDriver remote && remote.getSessionId() != null) {
            id = remote.getSessionId().toString();
        }
        MANAGED.add(new Managed(id, webDriver, template == null ? "" : template, owned));
        driver = webDriver;
    }

    static void focus(WebDriver webDriver) {
        driver = webDriver;
        focusedTab = currentHandle(webDriver);
        CompositeRuntime.focusPage(webDriver, pageTarget(webDriver));
    }

    /** Ranked frame identity belongs to one browser and one tab. Not a scan. */
    static void bindFrame(WebDriver webDriver, String tab, String frameId) {
        if (webDriver == null || frameId == null || frameId.isBlank()) return;
        String browserId = "";
        for (Managed managed : MANAGED) {
            if (managed.driver == webDriver) browserId = managed.id;
        }
        FRAMES.putIfAbsent(frameId, new FrameOwner(browserId, webDriver, tab == null ? "" : tab));
    }

    /** Geometry view for a resolved handle. Visual-viewport x/y, not a page rect. */
    public static void rememberGeometry(String handle, double x, double y) {
        rememberGeometry(handle, x, y, null, null);
    }

    /**
     * Stores the box and, when the query captured an owner, binds that frame
     * to that driver. Does not guess from whichever browser is focused now.
     */
    public static void rememberGeometry(String handle, double x, double y, WebDriver owner, String tab) {
        if (handle == null || handle.isBlank()) return;
        GEOMETRY.put(handle, new int[]{(int) Math.round(x), (int) Math.round(y)});
        HandleRef ref = HandleRef.parse(handle);
        if (ref.frameId != null && owner != null && !FRAMES.containsKey(ref.frameId)) {
            bindFrame(owner, tab == null || tab.isBlank() ? currentHandle(owner) : tab, ref.frameId);
        }
    }

    /** Driver whose debugger address owns the focused engine session. No global-driver fallback. */
    static WebDriver ownerForQuery() {
        String cdp = CompositeRuntime.focusedCdp();
        if (cdp == null || cdp.isBlank()) return null;
        String key = dev.domcomposite.Client.cdpKey(cdp);
        for (Managed managed : MANAGED) {
            if (!(managed.driver instanceof RemoteWebDriver remote)) continue;
            String address = debuggerAddress(remote);
            if (address.isEmpty()) continue;
            String browserKey = dev.domcomposite.Client.cdpKey(address);
            if (browserKey.equals(key) || key.startsWith(browserKey + "/")) return managed.driver;
        }
        return null;
    }

    static String focusedTabOf(WebDriver web) {
        if (web != null && web == driver && focusedTab != null && !focusedTab.isBlank()) return focusedTab;
        return currentHandle(web);
    }

    static void detach(WebDriver webDriver) {
        MANAGED.removeIf(managed -> managed.driver == webDriver);
        if (driver == webDriver) {
            driver = MANAGED.isEmpty() ? null : MANAGED.get(MANAGED.size() - 1).driver;
        }
    }

    static void hold(WebDriver webDriver) {
        manage(webDriver, "", false);
    }

    static void quitOwned() {
        for (Managed managed : List.copyOf(MANAGED)) {
            if (!managed.owned) continue;
            try {
                managed.driver.quit();
            } catch (RuntimeException failure) {
                CompositeLog.info("window", "quit skipped " + managed.id + " " + failure.getMessage());
            }
            detach(managed.driver);
        }
    }

    static WebDriver driver() {
        return driver;
    }

    static List<Managed> managed() {
        return List.copyOf(MANAGED);
    }

    static void clearDriver() {
        MANAGED.clear();
        driver = null;
        focusedTab = null;
        FRAMES.clear();
        GEOMETRY.clear();
        CDP_HIGHLIGHTS.clear();
        HIGHLIGHT_SITES.clear();
        SURFACE_SITES.clear();
        for (Path file : LIVE_IDS.values()) {
            try {
                if (file.getParent() != null) {
                    if (Files.isRegularFile(file)) Files.deleteIfExists(file);
                    Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".lock"));
                }
            } catch (IOException ignored) {
                // The next test allocates against a fresh key.
            }
        }
        LIVE_IDS.clear();
    }

    static String presetCss(String preset, String explicit) {
        String css = switch (preset == null ? "" : preset.trim().toLowerCase(Locale.ROOT)) {
            case "yellow" -> "background-color:#fff3a0;outline:2px solid #e6c200;";
            case "outline-red" -> "outline:3px solid #d22;outline-offset:2px;";
            case "" -> "";
            default -> throw new IllegalArgumentException("preset is yellow or outline-red");
        };
        if (explicit != null && !explicit.isBlank()) {
            String style = explicit.trim();
            String lower = style.toLowerCase(Locale.ROOT);
            if (lower.contains("javascript:") || lower.contains("expression(") || style.contains("<")) {
                throw new IllegalArgumentException("style is css, not script");
            }
            css = css + style;
            if (!css.endsWith(";")) css = css + ";";
        }
        if (css.isBlank()) throw new IllegalArgumentException("preset or style is required");
        return css;
    }

    static Map<String, Object> highlight(Path page, String handle, String preset, String style, Integer x, Integer y, Integer width, Integer height) throws IOException {
        String css = presetCss(preset, style);
        boolean liveOnly = page == null;
        if (liveOnly && managed().isEmpty()) {
            throw new IllegalStateException("no live browser; highlight was not applied");
        }
        if (page != null) {
            String html = read(page);
            String updated = paint(html, handle, css);
            if (updated == null) throw new IllegalArgumentException("unknown handle: " + handle);
            write(page, updated);
        }
        boolean cdp = tryHighlightNode(handle, preset, style);
        LiveResult live = new LiveResult(false, null, null, null, 0);
        if (!cdp) {
            live = runLive("highlight", handle, null, css, null, "style", STYLE_SENTENCE, "");
        }
        if (liveOnly && !cdp && !live.ok()) {
            throw new IllegalStateException(live.error() == null ? "highlight did not resolve " + handle : live.error());
        }
        CompositeLog.info("highlight", "handle=" + handle + " preset=" + text(preset) + " cdp=" + (cdp ? "Overlay.highlightNode" : "style"));
        Map<String, Object> body = labeled(List.of("style_modified"), STYLE_SENTENCE);
        body.put("handle", handle);
        body.put("preset", preset == null ? "" : preset);
        body.put("cdp", cdp ? "Overlay.highlightNode" : "style");
        body.put("reversed", false);
        if (!cdp && style != null && !style.isBlank() && parsedColors(preset, style).unsupported()) {
            body.put("styleNote", "explicit style is not representable by Overlay.highlightNode; applied as reversible DOM styling");
        }
        return body;
    }

    static Map<String, Object> unhighlight(Path page, String handle) throws IOException {
        if (page != null && Files.isRegularFile(page)) {
            write(page, stripStyle(read(page), handle));
        } else if (page != null) {
            throw new IllegalArgumentException("unknown handle: " + handle);
        }
        Hide hidden = hideHighlight(handle);
        if (hidden == Hide.FAILED) {
            throw new IllegalStateException("Overlay.hideHighlight failed");
        }
        boolean cdpOnly = handle != null && CDP_HIGHLIGHTS.remove(handle);
        if (handle == null || handle.isBlank()) CDP_HIGHLIGHTS.clear();
        LiveResult live = new LiveResult(true, null, null, null, 0);
        if (!cdpOnly) {
            live = runLive("unhighlight", handle, null, null, null, "style", STYLE_SENTENCE, "");
        }
        if (page == null && managed().isEmpty()) {
            throw new IllegalStateException("no live browser; unhighlight was not applied");
        }
        if (page == null && !cdpOnly && !live.ok()) {
            throw new IllegalStateException(live.error() == null ? "unhighlight did not resolve " + handle : live.error());
        }
        CompositeLog.info("highlight", "reverse handle=" + text(handle));
        Map<String, Object> body = labeled(List.of("style_modified"), STYLE_SENTENCE);
        body.put("handle", handle == null ? "" : handle);
        body.put("reversed", true);
        body.put("cdp", "Overlay.hideHighlight");
        return body;
    }

    static Map<String, Object> place(Path page, String op, String handle, String where, String rawHtml, String kind, Integer x, Integer y, String geometryJson) throws IOException {
        return place(page, op, handle, where, rawHtml, kind, x, y, geometryJson, null);
    }

    static Map<String, Object> place(Path page, String op, String handle, String where, String rawHtml, String kind, Integer x, Integer y, String geometryJson, Path ledger) throws IOException {
        String id = allocateId(idStore(page, ledger));
        String sentence = "overlay".equals(kind) ? OVERLAY_SENTENCE : HTML_SENTENCE;
        boolean stripped = rawHtml != null && !rawHtml.equals(neutralize(rawHtml));
        String html = labelElements(neutralize(rawHtml), kind, sentence);
        int[] point = visualPoint(geometryJson, x, y);
        if ("overlay".equals(kind) && (geometryJson == null || geometryJson.isBlank())) {
            int[] looked = lookupGeometry(handle);
            if (looked != null) point = looked;
        }
        boolean liveOnly = page == null;
        if (liveOnly && managed().isEmpty()) {
            throw new IllegalStateException("no live browser; " + kind + " was not applied");
        }
        if ("overlay".equals(kind) && liveOnly && handle != null && !handle.isBlank() && !resolved(handle)) {
            throw new IllegalArgumentException("unknown handle: " + handle);
        }
        if ("overlay".equals(kind) && point == null && page != null) {
            throw new IllegalArgumentException("overlay needs a visual-viewport box or x and y");
        }
        if ("overlay".equals(kind) && point == null && page == null) {
            throw new IllegalArgumentException("overlay needs a target or a visual-viewport box");
        }
        int left = point == null ? 0 : point[0];
        int top = point == null ? 0 : point[1];
        String css = "overlay".equals(kind) && point != null
                ? "position:fixed;z-index:2147483646;left:" + left + "px;top:" + top + "px;background:#fffef2;border:2px solid #222;padding:8px;max-width:280px;"
                : "";
        String block = "<!--pkb-start:" + id + "--><div data-pkb-id=\"" + id + "\" data-pkb-agent=\"agent_injected\" data-pkb-kind=\""
                + kind + "\" data-pkb-sentence=\"" + sentence + "\""
                + (css.isBlank() ? "" : " style=\"" + css + "\"")
                + ">" + html + "</div><!--pkb-end:" + id + "-->";
        boolean measure = false;
        String undo = null;
        if (page != null) {
            String document = read(page);
            String updated;
            if ("replace".equals(op)) {
                String[] replaced = replaceHandle(document, handle, block);
                if (replaced == null) throw new IllegalArgumentException("unknown handle: " + handle);
                updated = replaced[0];
                undo = replaced[1];
            } else if ("insert".equals(op)) {
                updated = insertHandle(document, handle, block, where);
                if (updated == null) throw new IllegalArgumentException("unknown handle: " + handle);
            } else {
                updated = append(document, block);
            }
            write(page, updated);
            if (undo != null) rememberUndo(page, id, undo);
        }
        LiveResult live = runLive(op, handle, where, css, html, kind, sentence, id, measure, point == null ? null : left, point == null ? null : top);
        if (liveOnly && !live.ok()) {
            throw new IllegalStateException(live.error() == null ? "live " + op + " did not resolve " + handle : live.error());
        }
        String summary = html.length() > 80 ? html.substring(0, 80) : html;
        String digest = sha(html);
        rememberPlacement(page, ledger, id, digest, summary);
        CompositeLog.info("html".equals(kind) ? "html" : "overlay", op + " id=" + id + " sha=" + digest + " summary=" + summary);
        Map<String, Object> body = labeled(
                "overlay".equals(kind) ? List.of("agent_injected", "overlay") : List.of("agent_injected"),
                sentence
        );
        body.put("id", id);
        body.put("handle", handle == null ? "" : handle);
        body.put("kind", kind);
        body.put("sha256", digest);
        body.put("summary", summary);
        body.put("guidance", rawHtml == null ? "" : neutralize(rawHtml));
        body.put("scriptStripped", stripped);
        body.put("x", live.x() != null ? live.x() : (point == null ? null : left));
        body.put("y", live.y() != null ? live.y() : (point == null ? null : top));
        if ("overlay".equals(kind)) {
            body.put("placement", geometryJson != null || lookupGeometry(handle) != null ? "visual-viewport-relative" : "caller");
        }
        return body;
    }

    static Map<String, Object> remove(Path page, String id) throws IOException {
        return remove(page, id, null);
    }

    static Map<String, Object> remove(Path page, String id, Path ledger) throws IOException {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("missing id");
        Placement placement = placement(page, ledger, id);
        if (page != null && Files.isRegularFile(page)) {
            String undo = undo(page, id);
            String html = read(page);
            String pattern = "(?s)<!--pkb-start:" + Pattern.quote(id) + "-->.*?<!--pkb-end:" + Pattern.quote(id) + "-->";
            if (undo != null) html = html.replaceFirst(pattern, Matcher.quoteReplacement(undo));
            else html = html.replaceAll(pattern, "");
            write(page, html);
            forgetUndo(page, id);
        }
        LiveResult live = runLive("remove", null, null, null, null, "html", HTML_SENTENCE, id);
        if (page == null && managed().isEmpty()) {
            throw new IllegalStateException("no live browser; remove was not applied");
        }
        if (page == null && !live.ok()) {
            throw new IllegalStateException(live.error() == null ? "remove failed" : live.error());
        }
        String digest = placement == null ? "" : placement.sha;
        String summary = placement == null ? "" : placement.summary;
        CompositeLog.info("html", "remove id=" + id + " sha=" + digest + " summary=" + summary);
        Map<String, Object> body = labeled(List.of("agent_injected"), HTML_SENTENCE);
        body.put("id", id);
        body.put("removed", true);
        body.put("sha256", digest);
        body.put("summary", summary);
        return body;
    }

    static Map<String, Object> cleanup(Path page) throws IOException {
        if (page != null && Files.isRegularFile(page)) {
            String html = restoreUndos(page, read(page));
            html = html.replaceAll("(?s)<!--pkb-start:.*?<!--pkb-end:.*?-->", "");
            html = stripStyle(html, null);
            write(page, html);
            clearUndos(page);
        }
        cleanupManaged();
        if (page != null && Files.isRegularFile(page)) {
            // File fixture already cleaned. Live drivers were cleaned above.
        }
        CompositeLog.info("html", "cleanup sha= summary=all-agent-surfaces");
        Map<String, Object> body = labeled(List.of("agent_injected"), HTML_SENTENCE);
        body.put("cleaned", true);
        body.put("drivers", MANAGED.size());
        return body;
    }

    static void recordInput(Path project, String runId, Map<String, String> event) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>(event);
        row.put("at", AgentStore.now());
        row.put("codes", List.of("agent_injected", "overlay"));
        row.put("sentence", OVERLAY_SENTENCE);
        Path file = AgentStore.root(project, runId).resolve("overlay-events.jsonl");
        AgentStore.appendCapped(file, AgentStore.JSON.writeValueAsString(row));
        CompositeLog.info("overlay", "input type=" + event.getOrDefault("type", ""));
        ChatLog.send(project, runId, "overlay", "system", "", "overlay input " + event.getOrDefault("type", "") + " " + event.getOrDefault("id", ""));
    }

    static List<Map<String, Object>> pollInput(Path project, String runId) throws IOException {
        drainLiveInputs(project, runId);
        Path file = AgentStore.root(project, runId).resolve("overlay-events.jsonl");
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : AgentStore.readGroup(file)) {
            events.add(AgentStore.JSON.readValue(line, Map.class));
        }
        return events;
    }

    static void cleanupAll() {
        cleanupManaged();
        for (Path page : PAGES) {
            try {
                if (Files.isRegularFile(page)) cleanup(page);
            } catch (IOException | RuntimeException ignored) {
                // Session end must not hide the original shutdown error.
            }
        }
    }

    static void cleanupManaged() {
        if (hideHighlight(null) == Hide.FAILED) {
            throw new IllegalStateException("Overlay.hideHighlight failed");
        }
        CDP_HIGHLIGHTS.clear();
        RuntimeException first = null;
        for (Managed managed : List.copyOf(MANAGED)) {
            WebDriver web = managed.driver;
            try {
                List<String> handles = new ArrayList<>(tabsOf(web));
                if (handles.isEmpty()) handles = new ArrayList<>(List.of(""));
                for (String handle : handles) {
                    if (!handle.isBlank() && !activate(web, handle)) {
                        throw new IllegalStateException("tab switch failed");
                    }
                    LiveResult live = runLive(web, "cleanup", null, null, null, null, "html", HTML_SENTENCE, "", false, null, null);
                    if (!live.ok()) {
                        throw new IllegalStateException(live.error() == null ? "cleanup failed" : live.error());
                    }
                    for (String frame : framesFor(web, handle)) {
                        if (!enterFrame(web, frame)) throw new IllegalStateException("frame entry failed");
                        LiveResult framed = runLive(web, "cleanup", null, null, null, null, "html", HTML_SENTENCE, "", false, null, null);
                        if (!framed.ok()) {
                            throw new IllegalStateException(framed.error() == null ? "cleanup failed" : framed.error());
                        }
                    }
                }
            } catch (RuntimeException failure) {
                CompositeLog.info("html", "cleanup failed driver " + managed.id + " " + failure.getMessage());
                if (first == null) first = failure;
            }
        }
        if (first != null) throw first;
    }

    static int[] visualPoint(String geometryJson, Integer x, Integer y) {
        int[] fromGeometry = geometryPoint(geometryJson);
        if (fromGeometry != null) return fromGeometry;
        if (x != null && y != null) return new int[]{x, y};
        return null;
    }

    private static void drainLiveInputs(Path project, String runId) throws IOException {
        for (Managed managed : MANAGED) {
            if (!(managed.driver instanceof JavascriptExecutor)) continue;
            String current = currentHandle(managed.driver);
            List<String> tabs = tabsOf(managed.driver);
            if (tabs.isEmpty()) tabs = List.of("");
            for (String tab : tabs) {
                if (!tab.isBlank() && !activate(managed.driver, tab)) {
                    throw new IllegalStateException("tab switch failed");
                }
                drainTab(project, runId, managed.driver);
                for (String frame : framesFor(managed.driver, tab)) {
                    if (!enterFrame(managed.driver, frame)) throw new IllegalStateException("frame entry failed");
                    drainTab(project, runId, managed.driver);
                }
            }
            if (current != null) activate(managed.driver, current);
        }
    }

    private static void drainTab(Path project, String runId, WebDriver web) throws IOException {
        if (!(web instanceof JavascriptExecutor executor)) return;
        Object value;
        try {
            value = executor.executeScript("""
                    var events = window.__pkbEvents || [];
                    window.__pkbEvents = [];
                    return events;
                    """);
        } catch (RuntimeException failure) {
            CompositeLog.info("overlay", "input poll failed " + failure.getMessage());
            throw failure;
        }
        if (!(value instanceof List<?> list)) return;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, String> event = new LinkedHashMap<>();
                map.forEach((key, raw) -> {
                    if (key != null && raw != null) event.put(String.valueOf(key), String.valueOf(raw));
                });
                recordInput(project, runId, event);
            }
        }
    }

    private static boolean tryHighlightNode(String handle, String preset, String style) {
        HandleRef ref = HandleRef.parse(handle);
        if (ref.backend == null) return false;
        WebDriver target = resolveDriver(handle);
        if (target == null || !cdpCapable(target)) return false;
        try {
            cdp(target, "Overlay.enable", Map.of());
            Map<String, Object> highlight = new LinkedHashMap<>();
            highlight.put("backendNodeId", ref.backend);
            ParsedColors colors = parsedColors(preset, style);
            if (colors.unsupported()) {
                CompositeLog.info("highlight", "style is not representable by Overlay.highlightNode; using reversible DOM styling");
                return false;
            }
            highlight.put("highlightConfig", highlightConfig(colors));
            cdp(target, "Overlay.highlightNode", highlight);
            if (handle != null) CDP_HIGHLIGHTS.add(handle);
            rememberHighlight(target, handle);
            return true;
        } catch (RuntimeException failure) {
            CompositeLog.info("highlight", "Overlay.highlightNode failed " + failure.getMessage());
            return false;
        }
    }

    private enum Hide { HIDDEN, SKIPPED, FAILED }

    private record HighlightSite(String handle, WebDriver driver, String tab) {}

    private static final CopyOnWriteArrayList<HighlightSite> HIGHLIGHT_SITES = new CopyOnWriteArrayList<>();

    private record SurfaceSite(String id, WebDriver driver, String tab, String frameId) {}

    private static final CopyOnWriteArrayList<SurfaceSite> SURFACE_SITES = new CopyOnWriteArrayList<>();

    private static void rememberSurface(WebDriver web, String id, String handle) {
        if (web == null) return;
        HandleRef ref = HandleRef.parse(handle);
        String frame = ref.frameId;
        String tab = currentHandle(web);
        if ((id == null || id.isBlank()) && (frame == null || frame.isBlank())) return;
        SURFACE_SITES.add(new SurfaceSite(id == null ? "" : id, web, tab == null ? "" : tab, frame));
    }

    private static List<SurfaceSite> sitesFor(String id) {
        if (id == null || id.isBlank()) return List.of();
        List<SurfaceSite> sites = new ArrayList<>();
        for (SurfaceSite site : SURFACE_SITES) {
            if (id.equals(site.id)) sites.add(site);
        }
        return sites;
    }

    private static List<String> framesFor(WebDriver web, String tab) {
        java.util.LinkedHashSet<String> frames = new java.util.LinkedHashSet<>();
        String wanted = tab == null ? "" : tab;
        for (SurfaceSite site : SURFACE_SITES) {
            if (site.driver != web || site.frameId == null || site.frameId.isBlank()) continue;
            if (!wanted.isBlank() && !wanted.equals(site.tab)) continue;
            frames.add(site.frameId);
        }
        return new ArrayList<>(frames);
    }

    private static void rememberHighlight(WebDriver web, String handle) {
        if (web == null) return;
        String tab = currentHandle(web);
        HIGHLIGHT_SITES.add(new HighlightSite(handle == null ? "" : handle, web, tab == null ? "" : tab));
    }

    private static Hide hideHighlight(String handle) {
        List<HighlightSite> sites = new ArrayList<>();
        if (handle != null && !handle.isBlank()) {
            for (HighlightSite site : HIGHLIGHT_SITES) {
                if (handle.equals(site.handle)) sites.add(site);
            }
            if (sites.isEmpty()) {
                WebDriver target = resolveDriver(handle);
                if (target != null) sites.add(new HighlightSite(handle, target, text(currentHandle(target))));
            }
        } else if (!HIGHLIGHT_SITES.isEmpty()) {
            sites.addAll(HIGHLIGHT_SITES);
        } else {
            for (Managed managed : MANAGED) {
                List<String> tabs = tabsOf(managed.driver);
                if (tabs.isEmpty()) tabs = List.of("");
                for (String tab : tabs) sites.add(new HighlightSite("", managed.driver, tab));
            }
        }
        Hide worst = Hide.SKIPPED;
        Set<String> seen = new HashSet<>();
        for (HighlightSite site : sites) {
            String key = System.identityHashCode(site.driver) + "|" + site.tab;
            if (!seen.add(key)) continue;
            String previous = currentHandle(site.driver);
            if (!site.tab.isBlank() && !activate(site.driver, site.tab)) {
                CompositeLog.info("window", "hide tab switch failed " + site.tab);
                worst = Hide.FAILED;
                continue;
            }
            worst = worse(worst, hideOn(site.driver));
            if (previous != null && !previous.equals(site.tab)) activate(site.driver, previous);
        }
        if (worst != Hide.FAILED) {
            if (handle == null || handle.isBlank()) HIGHLIGHT_SITES.clear();
            else HIGHLIGHT_SITES.removeIf(site -> handle.equals(site.handle));
        }
        return worst;
    }

    private static Hide worse(Hide left, Hide right) {
        if (left == Hide.FAILED || right == Hide.FAILED) return Hide.FAILED;
        if (left == Hide.HIDDEN || right == Hide.HIDDEN) return Hide.HIDDEN;
        return Hide.SKIPPED;
    }

    private static Hide hideOn(WebDriver web) {
        if (!cdpCapable(web)) return Hide.SKIPPED;
        try {
            cdp(web, "Overlay.hideHighlight", Map.of());
            return Hide.HIDDEN;
        } catch (RuntimeException failure) {
            CompositeLog.info("highlight", "Overlay.hideHighlight failed " + failure.getMessage());
            return Hide.FAILED;
        }
    }

    private static boolean cdpCapable(WebDriver web) {
        if (web == null) return false;
        if (web instanceof ChromiumDriver) return true;
        try {
            web.getClass().getMethod("executeCdpCommand", String.class, Map.class);
            return true;
        } catch (NoSuchMethodException failure) {
            return false;
        }
    }

    private static Object cdp(WebDriver web, String method, Map<String, Object> params) {
        try {
            return web.getClass().getMethod("executeCdpCommand", String.class, Map.class).invoke(web, method, params);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record ParsedColors(double[] content, double[] border, boolean unsupported) {}

    private static Map<String, Object> highlightConfig(ParsedColors colors) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("showInfo", true);
        config.put("contentColor", rgba(colors.content));
        config.put("borderColor", rgba(colors.border));
        return config;
    }

    private static ParsedColors parsedColors(String preset, String style) {
        double[] content = {255, 243, 160, 0.45};
        double[] border = {230, 194, 0, 1};
        String key = preset == null ? "" : preset.trim().toLowerCase(Locale.ROOT);
        if ("outline-red".equals(key)) {
            content = new double[]{210, 34, 34, 0};
            border = new double[]{210, 34, 34, 1};
        }
        if (style == null || style.isBlank()) return new ParsedColors(content, border, false);
        String trimmed = style.trim();
        if (!trimmed.contains(":")) {
            double[] bare = cssColor(trimmed);
            if (bare == null) return new ParsedColors(content, border, true);
            return new ParsedColors(content, opaque(bare), false);
        }
        boolean unsupported = false;
        for (String part : trimmed.split(";")) {
            if (part.isBlank()) continue;
            int colon = part.indexOf(':');
            if (colon < 0) {
                unsupported = true;
                continue;
            }
            String name = part.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(colon + 1).trim();
            switch (name) {
                case "background", "background-color", "border-color", "outline-color" -> {
                    double[] color = cssColor(value);
                    if (color == null) unsupported = true;
                    else if (name.startsWith("border") || name.startsWith("outline")) border = opaque(color);
                    else content = color;
                }
                case "border", "outline" -> {
                    double[] color = cssColor(value);
                    if (color == null) unsupported = true;
                    else border = opaque(color);
                }
                case "color" -> unsupported = true;
                default -> unsupported = true;
            }
        }
        return new ParsedColors(content, border, unsupported);
    }

    private static double[] opaque(double[] color) {
        return new double[]{color[0], color[1], color[2], color[3] <= 0 ? 1 : color[3]};
    }

    private static final Map<String, double[]> NAMED_COLORS = Map.ofEntries(
            Map.entry("black", new double[]{0, 0, 0, 1}),
            Map.entry("white", new double[]{255, 255, 255, 1}),
            Map.entry("red", new double[]{255, 0, 0, 1}),
            Map.entry("green", new double[]{0, 128, 0, 1}),
            Map.entry("blue", new double[]{0, 0, 255, 1}),
            Map.entry("yellow", new double[]{255, 255, 0, 1}),
            Map.entry("orange", new double[]{255, 165, 0, 1}),
            Map.entry("purple", new double[]{128, 0, 128, 1}),
            Map.entry("gray", new double[]{128, 128, 128, 1}),
            Map.entry("grey", new double[]{128, 128, 128, 1}),
            Map.entry("cyan", new double[]{0, 255, 255, 1}),
            Map.entry("magenta", new double[]{255, 0, 255, 1}),
            Map.entry("tomato", new double[]{255, 99, 71, 1}),
            Map.entry("transparent", new double[]{0, 0, 0, 0})
    );

    private static double[] cssColor(String style) {
        if (style == null || style.isBlank()) return null;
        String trimmed = style.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        double[] named = NAMED_COLORS.get(lower);
        if (named != null) return named.clone();
        if (lower.matches("#[0-9a-f]{6}")) {
            int rgb = Integer.parseInt(lower.substring(1), 16);
            return new double[]{(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, 1};
        }
        if (lower.matches("#[0-9a-f]{3}")) {
            String raw = "" + lower.charAt(1) + lower.charAt(1) + lower.charAt(2) + lower.charAt(2) + lower.charAt(3) + lower.charAt(3);
            int rgb = Integer.parseInt(raw, 16);
            return new double[]{(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, 1};
        }
        java.util.regex.Matcher rgb = Pattern.compile(
                "rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})(?:\\s*,\\s*([0-9]*\\.?[0-9]+))?\\s*\\)"
        ).matcher(lower);
        if (!rgb.matches()) return null;
        double alpha = rgb.group(4) == null ? 1 : Double.parseDouble(rgb.group(4));
        return new double[]{
                Integer.parseInt(rgb.group(1)),
                Integer.parseInt(rgb.group(2)),
                Integer.parseInt(rgb.group(3)),
                alpha
        };
    }

    private static Map<String, Object> rgba(double[] color) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("r", (int) color[0]);
        map.put("g", (int) color[1]);
        map.put("b", (int) color[2]);
        map.put("a", color[3]);
        return map;
    }

    private static int[] lookupGeometry(String handle) {
        if (handle == null || handle.isBlank()) return null;
        return GEOMETRY.get(handle);
    }

    private static boolean resolved(String handle) {
        if (handle == null || handle.isBlank()) return false;
        if (GEOMETRY.containsKey(handle)) return true;
        HandleRef ref = HandleRef.parse(handle);
        return ref.frameId != null && FRAMES.containsKey(ref.frameId);
    }

    private static LiveResult runLive(String op, String handle, String where, String css, String html, String kind, String sentence, String id) {
        return runLive(op, handle, where, css, html, kind, sentence, id, false, null, null);
    }

    private static LiveResult runLive(String op, String handle, String where, String css, String html, String kind, String sentence, String id, boolean measure, Integer x, Integer y) {
        if (MANAGED.isEmpty()) return new LiveResult(false, "no live browser", null, null, 0);
        if ("remove".equals(op) || "cleanup".equals(op)) {
            return runAcrossTabs(op, handle, where, css, html, kind, sentence, id, measure, x, y);
        }
        WebDriver target = resolveDriver(handle);
        if (handle != null && !handle.isBlank() && HandleRef.parse(handle).frameId != null && target == null) {
            String why = ENTRY_FAILURE.get();
            return new LiveResult(false, why == null || why.isBlank() ? "unknown handle" : why, null, null, 0);
        }
        if (target == null) target = driver;
        if (target == null) return new LiveResult(false, "no live browser", null, null, 0);
        return runLive(target, op, handle, where, css, html, kind, sentence, id, measure, x, y);
    }

    private static LiveResult runAcrossTabs(String op, String handle, String where, String css, String html, String kind, String sentence, String id, boolean measure, Integer x, Integer y) {
        LiveResult last = new LiveResult(false, "unknown id", null, null, 0);
        if ("remove".equals(op)) {
            for (SurfaceSite site : sitesFor(id)) {
                LiveResult live = runAtSite(site, op, handle, where, css, html, kind, sentence, id, measure, x, y);
                if (live.removed() > 0) return live;
                if (!live.ok() && live.error() != null && !"unknown id".equals(live.error())) return live;
                last = live;
            }
        }
        for (Managed managed : MANAGED) {
            List<String> tabs = tabsOf(managed.driver);
            if (tabs.isEmpty()) tabs = List.of("");
            for (String tab : tabs) {
                if (!tab.isBlank() && !activate(managed.driver, tab)) {
                    last = new LiveResult(false, "tab switch failed", null, null, 0);
                    if ("cleanup".equals(op)) return last;
                    continue;
                }
                LiveResult live = runLive(managed.driver, op, handle, where, css, html, kind, sentence, id, measure, x, y);
                if ("remove".equals(op)) {
                    if (live.removed() > 0) return live;
                    if (!live.ok() && live.error() != null && !"unknown id".equals(live.error())) return live;
                    last = live;
                    continue;
                }
                if (!live.ok()) return live;
                for (String frame : framesFor(managed.driver, tab)) {
                    if (!enterFrame(managed.driver, frame)) {
                        return new LiveResult(false, "frame entry failed", null, null, 0);
                    }
                    LiveResult framed = runLive(managed.driver, op, handle, where, css, html, kind, sentence, id, measure, x, y);
                    if (!framed.ok()) return framed;
                }
                last = live;
                if (!"cleanup".equals(op)) return live;
            }
        }
        return last;
    }

    private static LiveResult runAtSite(SurfaceSite site, String op, String handle, String where, String css, String html, String kind, String sentence, String id, boolean measure, Integer x, Integer y) {
        if (site.tab != null && !site.tab.isBlank() && !activate(site.driver, site.tab)) {
            return new LiveResult(false, "tab switch failed", null, null, 0);
        }
        if (site.frameId != null && !site.frameId.isBlank() && cdpCapable(site.driver) && !enterFrame(site.driver, site.frameId)) {
            return new LiveResult(false, "frame entry failed", null, null, 0);
        }
        return runLive(site.driver, op, handle, where, css, html, kind, sentence, id, measure, x, y);
    }

    private static final ThreadLocal<String> ENTRY_FAILURE = new ThreadLocal<>();

    private static WebDriver resolveDriver(String handle) {
        ENTRY_FAILURE.remove();
        HandleRef ref = HandleRef.parse(handle);
        if (ref.frameId != null) {
            FrameOwner owner = FRAMES.get(ref.frameId);
            if (owner != null) {
                if (!browserStillManaged(owner)) {
                    ENTRY_FAILURE.set("unknown handle");
                    return null;
                }
                if (!activate(owner.driver, owner.tab)) {
                    ENTRY_FAILURE.set("tab switch failed");
                    return null;
                }
                if (!enterFrame(owner.driver, ref.frameId)) {
                    ENTRY_FAILURE.set("frame entry failed");
                    return null;
                }
                driver = owner.driver;
                focusedTab = owner.tab;
                CompositeRuntime.focusPage(owner.driver, pageTarget(owner.driver));
                return owner.driver;
            }
            if (!GEOMETRY.containsKey(handle)) {
                ENTRY_FAILURE.set("unknown handle");
                return null;
            }
        }
        if (driver != null && focusedTab != null && !focusedTab.isBlank() && !activate(driver, focusedTab)) {
            ENTRY_FAILURE.set("tab switch failed");
            return null;
        }
        return driver;
    }

    private static boolean browserStillManaged(FrameOwner owner) {
        if (owner == null || owner.driver == null) return false;
        for (Managed managed : MANAGED) {
            if (managed.driver != owner.driver) continue;
            if (owner.browserId == null || owner.browserId.isBlank()) return true;
            if (owner.browserId.equals(managed.id)) return true;
        }
        return false;
    }

    /** @return false when the ranked frame could not be entered. A driver without CDP is already on its document. */
    private static boolean enterFrame(WebDriver web, String frameId) {
        if (web == null || frameId == null || frameId.isBlank()) return false;
        if (!cdpCapable(web)) return true;
        try {
            web.switchTo().defaultContent();
            List<String> chain = frameChain(web, frameId);
            if (chain == null) {
                CompositeLog.info("window", "frame enter failed " + frameId + " frame is not in the tree");
                return false;
            }
            for (String id : chain) {
                Object owner = cdp(web, "DOM.getFrameOwner", Map.of("frameId", id));
                Integer backend = backendOf(owner);
                if (backend == null) {
                    CompositeLog.info("window", "frame enter failed " + id + " missing frame owner");
                    return false;
                }
                FrameMutation mutation = frameSwitchName(web, backend);
                if (mutation == null || mutation.name == null || mutation.name.isBlank()) {
                    CompositeLog.info("window", "frame enter failed " + id + " no frame name");
                    return false;
                }
                try {
                    web.switchTo().frame(mutation.name);
                } catch (RuntimeException failure) {
                    CompositeLog.info("window", "frame switch failed " + id + " " + failure.getMessage());
                    restoreFrame(web, mutation);
                    return false;
                }
                restoreFrame(web, mutation);
            }
            return true;
        } catch (RuntimeException failure) {
            CompositeLog.info("window", "frame enter failed " + frameId + " " + failure.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> frameChain(WebDriver web, String frameId) {
        Object tree = cdp(web, "Page.getFrameTree", Map.of());
        if (!(tree instanceof Map<?, ?> root)) return null;
        List<String> chain = new ArrayList<>();
        if (!collectFrame(root.get("frameTree"), frameId, chain)) return null;
        if (!chain.isEmpty()) chain.remove(0);
        return chain;
    }

    @SuppressWarnings("unchecked")
    private static boolean collectFrame(Object node, String frameId, List<String> chain) {
        if (!(node instanceof Map<?, ?> map)) return false;
        Object frame = map.get("frame");
        String id = frame instanceof Map<?, ?> frameMap ? String.valueOf(frameMap.get("id")) : "";
        chain.add(id);
        if (frameId.equals(id)) return true;
        Object children = map.get("childFrames");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                if (collectFrame(child, frameId, chain)) return true;
            }
        }
        chain.remove(chain.size() - 1);
        return false;
    }

    private static Integer backendOf(Object owner) {
        if (!(owner instanceof Map<?, ?> map)) return null;
        Object raw = map.get("backendNodeId");
        if (raw instanceof Number number) return number.intValue();
        return null;
    }

    private record FrameMutation(String objectId, String name, boolean changedName, String previousBridge) {}

    private static FrameMutation frameSwitchName(WebDriver web, int backend) {
        try {
            Object resolved = cdp(web, "DOM.resolveNode", Map.of("backendNodeId", backend));
            String objectId = objectId(resolved);
            if (objectId == null || objectId.isBlank()) return null;
            String token = "b" + Integer.toUnsignedString(IDS.incrementAndGet(), 36);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("objectId", objectId);
            call.put("functionDeclaration", FRAME_MARK);
            call.put("arguments", List.of(Map.of("value", token)));
            call.put("returnByValue", true);
            String raw = textValue(cdp(web, "Runtime.callFunctionOn", call));
            if (raw == null || raw.isBlank() || !raw.trim().startsWith("{")) return null;
            JsonNode node = AgentStore.JSON.readTree(raw);
            String name = node.path("name").asText("");
            if (name.isBlank()) return null;
            JsonNode previous = node.get("previousBridge");
            String previousBridge = previous == null || previous.isNull() ? null : previous.asText("");
            return new FrameMutation(objectId, name, node.path("changedName").asBoolean(false), previousBridge);
        } catch (RuntimeException | IOException failure) {
            CompositeLog.info("window", "frame owner bridge failed " + failure.getMessage());
            return null;
        }
    }

    private static void restoreFrame(WebDriver web, FrameMutation mutation) {
        if (web == null || mutation == null || mutation.objectId == null || mutation.objectId.isBlank()) return;
        try {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("changedName", mutation.changedName);
            spec.put("previousBridge", mutation.previousBridge);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("objectId", mutation.objectId);
            call.put("functionDeclaration", FRAME_RESTORE);
            call.put("arguments", List.of(Map.of("value", spec)));
            call.put("returnByValue", true);
            cdp(web, "Runtime.callFunctionOn", call);
        } catch (RuntimeException failure) {
            CompositeLog.info("window", "frame bridge restore failed " + failure.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static String textValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) return "";
        Object result = map.get("result");
        if (!(result instanceof Map<?, ?> body)) return "";
        Object text = body.get("value");
        return text == null ? "" : String.valueOf(text);
    }

    private static String bridgeBackend(WebDriver web, int backend) {
        try {
            Object resolved = cdp(web, "DOM.resolveNode", Map.of("backendNodeId", backend));
            String objectId = objectId(resolved);
            if (objectId == null || objectId.isBlank()) return null;
            String token = "b" + Integer.toUnsignedString(IDS.incrementAndGet(), 36);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("objectId", objectId);
            call.put("functionDeclaration", BRIDGE_MARK);
            call.put("arguments", List.of(Map.of("value", token)));
            call.put("returnByValue", true);
            cdp(web, "Runtime.callFunctionOn", call);
            return token;
        } catch (RuntimeException failure) {
            CompositeLog.info("resolve", "backend node bridge failed " + failure.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String objectId(Object resolved) {
        if (!(resolved instanceof Map<?, ?> map)) return null;
        Object object = map.get("object");
        if (!(object instanceof Map<?, ?> node)) return null;
        Object id = node.get("objectId");
        return id == null ? null : String.valueOf(id);
    }

    private static LiveResult runLive(WebDriver current, String op, String handle, String where, String css, String html, String kind, String sentence, String id, boolean measure, Integer x, Integer y) {
        if (!(current instanceof JavascriptExecutor executor)) {
            return new LiveResult(false, "no live browser", null, null, 0);
        }
        HandleRef ref = HandleRef.parse(handle);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("op", op);
        spec.put("handle", handle == null ? "" : handle);
        spec.put("where", where == null ? "after" : where);
        spec.put("css", css == null ? "" : css);
        spec.put("html", html == null ? "" : html);
        spec.put("kind", kind);
        spec.put("sentence", sentence);
        spec.put("id", id == null ? "" : id);
        spec.put("measure", measure);
        if (ref.backend != null) spec.put("backend", ref.backend);
        if (ref.backend != null && cdpCapable(current)) {
            String bridge = bridgeBackend(current, ref.backend);
            if (bridge != null) spec.put("bridge", bridge);
        }
        if (x != null) spec.put("x", x);
        if (y != null) spec.put("y", y);
        try {
            Object value = executor.executeScript(LIVE_HELPER, spec);
            if (value instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("ok"))) {
                Object error = map.get("error");
                return new LiveResult(false, error == null ? "live helper failed" : String.valueOf(error), null, null, 0);
            }
            Integer liveX = value instanceof Map<?, ?> map && map.get("x") instanceof Number number ? number.intValue() : null;
            Integer liveY = value instanceof Map<?, ?> map && map.get("y") instanceof Number number ? number.intValue() : null;
            int removed = value instanceof Map<?, ?> map && map.get("removed") instanceof Number number ? number.intValue() : 0;
            if ("remove".equals(op) && removed <= 0) {
                return new LiveResult(false, "unknown id", null, null, 0);
            }
            rememberSurface(current, id, handle);
            return new LiveResult(true, null, liveX, liveY, removed);
        } catch (RuntimeException failure) {
            CompositeLog.info("html", "live helper failed " + failure.getMessage());
            return new LiveResult(false, failure.getMessage(), null, null, 0);
        }
    }

    static String neutralize(String html) {
        if (html == null || html.isEmpty()) return html == null ? "" : html;
        StringBuilder out = new StringBuilder(html.length());
        int index = 0;
        while (index < html.length()) {
            int start = html.indexOf('<', index);
            if (start < 0) {
                out.append(html.substring(index));
                break;
            }
            out.append(html, index, start);
            if (scriptOpen(html, start)) {
                int close = indexOfIgnoreCase(html, "</script", start + 1);
                if (close < 0) break;
                int end = tagEnd(html, close);
                index = end < 0 ? html.length() : end + 1;
                continue;
            }
            int end = tagEnd(html, start);
            if (end < 0) {
                out.append(html.substring(start));
                break;
            }
            String tag = html.substring(start, end + 1);
            if (!scriptTag(tag)) out.append(sanitizeTag(tag));
            index = end + 1;
        }
        return out.toString();
    }

    private static boolean scriptOpen(String html, int start) {
        int index = start + 1;
        while (index < html.length() && Character.isWhitespace(html.charAt(index))) index++;
        return index + 6 <= html.length()
                && html.regionMatches(true, index, "script", 0, 6)
                && (index + 6 == html.length() || !isNameChar(html.charAt(index + 6)));
    }

    private static boolean scriptTag(String tag) {
        return scriptOpen(tag, 0);
    }

    private static int indexOfIgnoreCase(String html, String token, int from) {
        int last = html.length() - token.length();
        for (int index = Math.max(0, from); index <= last; index++) {
            if (html.regionMatches(true, index, token, 0, token.length())) return index;
        }
        return -1;
    }

    private static String sanitizeTag(String tag) {
        if (tag.length() < 2 || tag.charAt(1) == '!' || tag.charAt(1) == '?') return tag;
        int index = 1;
        if (tag.charAt(index) == '/') return tag;
        while (index < tag.length() && Character.isWhitespace(tag.charAt(index))) index++;
        int nameStart = index;
        while (index < tag.length() && isNameChar(tag.charAt(index))) index++;
        if (nameStart == index) return tag;
        StringBuilder rebuilt = new StringBuilder();
        rebuilt.append('<').append(tag, nameStart, index);
        while (index < tag.length()) {
            char current = tag.charAt(index);
            if (current == '>') break;
            if (Character.isWhitespace(current) || current == '/') {
                index++;
                continue;
            }
            int attrStart = index;
            while (index < tag.length() && isAttrChar(tag.charAt(index))) index++;
            if (index == attrStart) {
                index++;
                continue;
            }
            String rawName = tag.substring(attrStart, index);
            String rawValue = null;
            while (index < tag.length() && Character.isWhitespace(tag.charAt(index))) index++;
            if (index < tag.length() && tag.charAt(index) == '=') {
                index++;
                while (index < tag.length() && Character.isWhitespace(tag.charAt(index))) index++;
                if (index < tag.length() && (tag.charAt(index) == '"' || tag.charAt(index) == '\'')) {
                    char quote = tag.charAt(index++);
                    int valueStart = index;
                    while (index < tag.length() && tag.charAt(index) != quote) index++;
                    rawValue = tag.substring(valueStart, index);
                    if (index < tag.length()) index++;
                } else {
                    int valueStart = index;
                    while (index < tag.length() && isUnquotedValueChar(tag.charAt(index))) index++;
                    rawValue = tag.substring(valueStart, index);
                }
            }
            String decodedName = decodeUntilStable(rawName).toLowerCase(Locale.ROOT);
            String decodedValue = rawValue == null ? null : decodeUntilStable(rawValue);
            if (dropAttribute(decodedName, decodedValue)) continue;
            rebuilt.append(' ').append(decodedName);
            if (decodedValue != null) rebuilt.append("=\"").append(escapeAttr(decodedValue)).append('"');
        }
        if (tag.endsWith("/>")) rebuilt.append(" />");
        else rebuilt.append('>');
        return rebuilt.toString();
    }

    private static boolean isNameChar(char current) {
        return Character.isLetterOrDigit(current) || current == ':' || current == '-' || current == '_';
    }

    private static boolean isAttrChar(char current) {
        return current != '=' && current != '>' && current != '/' && current != '"' && current != '\'' && !Character.isWhitespace(current);
    }

    private static boolean isUnquotedValueChar(char current) {
        return !Character.isWhitespace(current)
                && current != '>'
                && current != '"'
                && current != '\''
                && current != '='
                && current != '<'
                && current != '`';
    }

    private static boolean dropAttribute(String name, String value) {
        if (name.isBlank()) return true;
        if ("srcdoc".equals(name)) return true;
        if (name.length() > 2 && name.startsWith("on") && Character.isLetter(name.charAt(2))) return true;
        return urlAttribute(name) && (javascriptUrl(value) || activeHtmlDocument(value));
    }

    private static boolean urlAttribute(String name) {
        return switch (name) {
            case "href", "src", "action", "formaction", "poster", "cite", "background", "dynsrc", "lowsrc", "data", "xlink:href" -> true;
            default -> false;
        };
    }

    private static boolean javascriptUrl(String value) {
        if (value == null) return false;
        return stripUrlControls(value).toLowerCase(Locale.ROOT).startsWith("javascript:");
    }

    /** data: documents that the browser will parse as an active nested document. Other data: payloads stay. */
    private static boolean activeHtmlDocument(String value) {
        if (value == null) return false;
        String stripped = stripUrlControls(value).toLowerCase(Locale.ROOT);
        if (!stripped.startsWith("data:")) return false;
        String rest = stripped.substring("data:".length());
        int comma = rest.indexOf(',');
        String meta = comma < 0 ? rest : rest.substring(0, comma);
        int semi = meta.indexOf(';');
        String mime = (semi < 0 ? meta : meta.substring(0, semi)).trim();
        return switch (mime) {
            case "text/html", "application/xhtml+xml", "image/svg+xml", "text/xml", "application/xml" -> true;
            default -> false;
        };
    }

    private static String stripUrlControls(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x20 || current == 0x7F) continue;
            out.append(current);
        }
        return out.toString();
    }

    private static String decodeUntilStable(String value) {
        String current = value == null ? "" : value;
        for (int pass = 0; pass < 6; pass++) {
            String decoded = decodeEntities(current);
            if (decoded.equals(current)) return current;
            current = decoded;
        }
        return current;
    }

    private static String escapeAttr(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '&' -> escaped.append('&').append("amp;");
                case '"' -> escaped.append('&').append("quot;");
                case '<' -> escaped.append('&').append("lt;");
                case '>' -> escaped.append('&').append("gt;");
                default -> escaped.append(current);
            }
        }
        return escaped.toString();
    }

    private static int tagEnd(String html, int start) {
        boolean quoted = false;
        char quote = 0;
        for (int index = start + 1; index < html.length(); index++) {
            char current = html.charAt(index);
            if (quoted) {
                if (current == quote) quoted = false;
                continue;
            }
            if (current == '"' || current == '\'') {
                quoted = true;
                quote = current;
                continue;
            }
            if (current == '>') return index;
        }
        return -1;
    }

    static String labelElements(String html, String kind, String sentence) {
        if (html == null || html.isBlank()) return html == null ? "" : html;
        return html.replaceAll(
                "(?i)<(?![/!])([a-zA-Z][\\w:-]*)\\b(?![^>]*data-pkb-agent)",
                "<$1 data-pkb-agent=\"agent_injected\" data-pkb-kind=\"" + kind
                        + "\" data-pkb-sentence=\"" + sentence + "\""
        );
    }

    private static String decodeEntities(String value) {
        Matcher numeric = Pattern.compile("&#(x[0-9a-fA-F]+|\\d+);").matcher(value);
        StringBuffer buf = new StringBuffer();
        while (numeric.find()) {
            String raw = numeric.group(1);
            int code;
            try {
                code = raw.charAt(0) == 'x' || raw.charAt(0) == 'X'
                        ? Integer.parseInt(raw.substring(1), 16)
                        : Integer.parseInt(raw);
            } catch (NumberFormatException failure) {
                continue;
            }
            String ch = code >= 0 && code <= 0x10FFFF ? new String(Character.toChars(code)) : "";
            numeric.appendReplacement(buf, Matcher.quoteReplacement(ch));
        }
        numeric.appendTail(buf);
        return buf.toString()
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String paint(String html, String handle, String css) {
        if (handle == null || handle.isBlank()) return null;
        Matcher matcher = openTag(html, handle);
        if (matcher == null) return null;
        String open = matcher.group(1);
        String original = attribute(open, "data-pkb-original-style");
        if (original == null) original = attribute(open, "style");
        if (original == null) original = "";
        String stripped = open
                .replaceAll("(?i)\\sstyle\\s*=\\s*(\"[^\"]*\"|'[^']*')", "")
                .replaceAll("(?i)\\sdata-pkb-style\\s*=\\s*(\"[^\"]*\"|'[^']*')", "")
                .replaceAll("(?i)\\sdata-pkb-sentence\\s*=\\s*(\"[^\"]*\"|'[^']*')", "")
                .replaceAll("(?i)\\sdata-pkb-css\\s*=\\s*(\"[^\"]*\"|'[^']*')", "")
                .replaceAll("(?i)\\sdata-pkb-original-style\\s*=\\s*(\"[^\"]*\"|'[^']*')", "");
        String joined = original;
        if (!joined.isBlank() && !joined.trim().endsWith(";")) joined = joined + ";";
        joined = joined + css;
        String injected = stripped + " data-pkb-style=\"style_modified\" data-pkb-sentence=\"" + STYLE_SENTENCE
                + "\" data-pkb-original-style=\"" + escape(original) + "\" data-pkb-css=\"" + escape(css)
                + "\" style=\"" + escape(joined) + "\"" + matcher.group(3);
        return matcher.replaceFirst(Matcher.quoteReplacement(injected));
    }

    private static String stripStyle(String html, String handle) {
        if (handle == null || handle.isBlank()) {
            Matcher each = Pattern.compile("(?i)<[^>]*\\sdata-pkb-style\\s*=\\s*\"style_modified\"[^>]*>").matcher(html);
            String current = html;
            String previous;
            do {
                previous = current;
                Matcher one = Pattern.compile("(?i)<[^>]*\\sdata-pkb-style\\s*=\\s*\"style_modified\"[^>]*>").matcher(current);
                if (!one.find()) break;
                current = current.substring(0, one.start()) + restoreTag(one.group()) + current.substring(one.end());
            } while (!current.equals(previous));
            return current;
        }
        Matcher matcher = openTag(html, handle);
        if (matcher == null) return html;
        if (!matcher.group(0).toLowerCase(Locale.ROOT).contains("data-pkb-style")) return html;
        String restored = restoreTag(matcher.group(0));
        return html.substring(0, matcher.start()) + restored + html.substring(matcher.end());
    }

    private static String restoreTag(String tag) {
        String original = attribute(tag, "data-pkb-original-style");
        String stripped = tag
                .replaceAll("(?i)\\sstyle\\s*=\\s*(\"[^\"]*\"|'[^']*')", "")
                .replaceAll("(?i)\\sdata-pkb-style\\s*=\\s*(\"[^\"]*\"|'[^']*')", "")
                .replaceAll("(?i)\\sdata-pkb-sentence\\s*=\\s*(\"[^\"]*\"|'[^']*')", "")
                .replaceAll("(?i)\\sdata-pkb-css\\s*=\\s*(\"[^\"]*\"|'[^']*')", "")
                .replaceAll("(?i)\\sdata-pkb-original-style\\s*=\\s*(\"[^\"]*\"|'[^']*')", "");
        if (original != null && !original.isBlank()) {
            int end = stripped.endsWith(">") ? stripped.length() - 1 : stripped.length();
            return stripped.substring(0, end) + " style=\"" + escape(original) + "\">";
        }
        return stripped;
    }

    private static Matcher openTag(String html, String handle) {
        Pattern pattern = Pattern.compile("(?i)(<[^>]*\\bid\\s*=\\s*([\"'])" + Pattern.quote(handle) + "\\2[^>]*)(>)");
        Matcher matcher = pattern.matcher(html);
        return matcher.find() ? matcher : null;
    }

    private static String attribute(String tag, String name) {
        Matcher matcher = Pattern.compile("(?i)\\s" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String[] replaceHandle(String html, String handle, String block) {
        if (handle == null || handle.isBlank()) return null;
        Pattern pattern = Pattern.compile("(?is)<[^>]*\\bid\\s*=\\s*([\"'])" + Pattern.quote(handle) + "\\1[^>]*>.*?</[^>]+>");
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) return null;
        return new String[]{matcher.replaceFirst(Matcher.quoteReplacement(block)), matcher.group()};
    }

    private static String insertHandle(String html, String handle, String block, String where) {
        if (handle == null || handle.isBlank()) return null;
        Pattern pattern = Pattern.compile("(?i)<[^>]*\\bid\\s*=\\s*([\"'])" + Pattern.quote(handle) + "\\1[^>]*>");
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) return null;
        if ("before".equalsIgnoreCase(where)) {
            return html.substring(0, matcher.start()) + block + html.substring(matcher.start());
        }
        return html.substring(0, matcher.end()) + block + html.substring(matcher.end());
    }

    private static String append(String html, String block) {
        int body = html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
        if (body >= 0) return html.substring(0, body) + block + html.substring(body);
        return html + block;
    }

    private static String read(Path page) throws IOException {
        if (page == null) throw new IllegalArgumentException("missing page");
        PAGES.addIfAbsent(page.toAbsolutePath().normalize());
        if (!Files.isRegularFile(page)) throw new IllegalArgumentException("missing page: " + page);
        return Files.readString(page, StandardCharsets.UTF_8);
    }

    private static void write(Path page, String html) throws IOException {
        Files.createDirectories(page.getParent() == null ? Path.of(".") : page.getParent());
        Files.writeString(page, html, StandardCharsets.UTF_8);
    }

    static String sha(String html) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((html == null ? "" : html).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            return "";
        }
    }

    private static int[] geometryPoint(String geometryJson) {
        if (geometryJson == null || geometryJson.isBlank()) return null;
        try {
            JsonNode node = AgentStore.JSON.readTree(geometryJson);
            JsonNode box = node.path("visual_viewport_relative");
            if (!box.isObject() && node.path("geometry").isObject()) {
                box = node.path("geometry").path("visual_viewport_relative");
            }
            if (!box.isObject() || !box.has("x") || !box.has("y")) return null;
            return new int[]{box.path("x").asInt(), box.path("y").asInt()};
        } catch (IOException failure) {
            throw new IllegalArgumentException("geometry is not the visual-viewport object");
        }
    }

    private static void rememberUndo(Path page, String id, String undo) throws IOException {
        ObjectNode root = undoRoot(page);
        root.put(id, undo);
        AgentStore.JSON.writeValue(undoFile(page).toFile(), root);
    }

    private static String undo(Path page, String id) throws IOException {
        return undoRoot(page).path(id).asText(null);
    }

    private static void forgetUndo(Path page, String id) throws IOException {
        ObjectNode root = undoRoot(page);
        root.remove(id);
        AgentStore.JSON.writeValue(undoFile(page).toFile(), root);
    }

    private static String restoreUndos(Path page, String html) throws IOException {
        ObjectNode root = undoRoot(page);
        var names = root.fieldNames();
        while (names.hasNext()) {
            String id = names.next();
            String undo = root.path(id).asText("");
            html = html.replaceFirst(
                    "(?s)<!--pkb-start:" + Pattern.quote(id) + "-->.*?<!--pkb-end:" + Pattern.quote(id) + "-->",
                    Matcher.quoteReplacement(undo)
            );
        }
        return html;
    }

    private static void clearUndos(Path page) throws IOException {
        Path file = undoFile(page);
        if (Files.isRegularFile(file)) Files.delete(file);
    }

    private static ObjectNode undoRoot(Path page) throws IOException {
        Path file = undoFile(page);
        if (!Files.isRegularFile(file)) return AgentStore.JSON.createObjectNode();
        JsonNode node = AgentStore.JSON.readTree(file.toFile());
        return node instanceof ObjectNode object ? object : AgentStore.JSON.createObjectNode();
    }

    private static Path undoFile(Path page) {
        return page.resolveSibling(page.getFileName() + ".undo.json");
    }

    private record Placement(String sha, String summary) {
    }

    private static final Map<String, Placement> PLACED = new LinkedHashMap<>();

    static void clearPlacementMemory() {
        PLACED.clear();
    }

    private static void rememberPlacement(Path page, Path ledger, String id, String sha, String summary) throws IOException {
        PLACED.put(id, new Placement(sha, summary));
        writePlacement(page == null ? null : placeFile(page), id, sha, summary);
        writePlacement(ledger == null ? null : ledger.resolve("placements.json"), id, sha, summary);
    }

    private static Placement placement(Path page, Path ledger, String id) throws IOException {
        Placement memory = PLACED.get(id);
        if (memory != null) return memory;
        Placement fromPage = readPlacement(page == null ? null : placeFile(page), id);
        if (fromPage != null) return fromPage;
        return readPlacement(ledger == null ? null : ledger.resolve("placements.json"), id);
    }

    private static Path placeFile(Path page) {
        return page.resolveSibling(page.getFileName() + ".place.json");
    }

    private static final Map<String, Path> LIVE_IDS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Path idStore(Path page, Path ledger) {
        if (page != null) return placeFile(page);
        if (!MANAGED.isEmpty()) {
            String key = livePageKey();
            return LIVE_IDS.computeIfAbsent(key, ignored -> {
                String hash = sha(key);
                if (hash.length() > 16) hash = hash.substring(0, 16);
                return Path.of(System.getProperty("java.io.tmpdir", "."), "pkb-surface-ids", hash, "placements.json");
            });
        }
        if (ledger != null) return ledger.resolve("placements.json");
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".pickleball", "agent-surfaces", "placements.json");
    }

    private static String livePageKey() {
        WebDriver web = driver;
        String tab = focusedTab == null ? "" : focusedTab;
        if (web instanceof RemoteWebDriver remote) {
            String address = debuggerAddress(remote);
            if (!address.isEmpty()) {
                String target = pageTarget(web);
                return address + "|" + (target == null ? "" : target) + "|" + tab;
            }
        }
        return "local|" + System.identityHashCode(web) + "|" + tab;
    }

    private static String allocateId(Path file) throws IOException {
        String[] id = new String[1];
        withSurfaceLock(file, () -> {
            ObjectNode root = readObject(file);
            int next = nextSurfaceId(root);
            id[0] = "pkb-" + next;
            root.put("next", next + 1);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            AgentStore.JSON.writeValue(file.toFile(), root);
        });
        return id[0];
    }

    private static int nextSurfaceId(ObjectNode root) {
        int used = 0;
        var names = root.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!name.startsWith("pkb-")) continue;
            try {
                used = Math.max(used, Integer.parseInt(name.substring(4)));
            } catch (NumberFormatException ignored) {
                // Placement metadata that is not a surface id.
            }
        }
        int next = root.has("next") ? root.path("next").asInt(1) : used + 1;
        if (next <= used) next = used + 1;
        if (next < 1) next = 1;
        return next;
    }

    private interface SurfaceIo {
        void run() throws IOException;
    }

    private static void withSurfaceLock(Path file, SurfaceIo body) throws IOException {
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        if (lockFile.getParent() != null) Files.createDirectories(lockFile.getParent());
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            body.run();
        }
    }

    private static String debuggerAddress(RemoteWebDriver remote) {
        if (remote == null || remote.getCapabilities() == null) return "";
        Object direct = remote.getCapabilities().getCapability("se:cdp");
        if (direct == null) return "";
        String text = direct.toString().trim();
        return text.isEmpty() ? "" : text;
    }

    static String allocateSurfaceId(Path file) throws IOException {
        return allocateId(file);
    }

    static void recordSurface(Path file, String id, String sha, String summary) throws IOException {
        writePlacement(file, id, sha, summary);
    }

    static String liveHelperSource() {
        return LIVE_HELPER;
    }

    static String bridgeMarkSource() {
        return BRIDGE_MARK;
    }

    static String frameMarkSource() {
        return FRAME_MARK;
    }

    static String frameRestoreSource() {
        return FRAME_RESTORE;
    }

    private static void writePlacement(Path file, String id, String sha, String summary) throws IOException {
        if (file == null) return;
        withSurfaceLock(file, () -> {
            ObjectNode root = readObject(file);
            ObjectNode row = root.putObject(id);
            row.put("sha256", sha);
            row.put("summary", summary);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            AgentStore.JSON.writeValue(file.toFile(), root);
        });
    }

    private static Placement readPlacement(Path file, String id) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return null;
        JsonNode row = AgentStore.JSON.readTree(file.toFile()).path(id);
        if (!row.isObject()) return null;
        return new Placement(row.path("sha256").asText(""), row.path("summary").asText(""));
    }

    private static ObjectNode readObject(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return AgentStore.JSON.createObjectNode();
        JsonNode node = AgentStore.JSON.readTree(file.toFile());
        return node instanceof ObjectNode object ? object : AgentStore.JSON.createObjectNode();
    }

    private static Map<String, Object> labeled(List<String> codes, String sentence) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("codes", codes);
        body.put("sentence", sentence);
        return body;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\"", "");
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private record LiveResult(boolean ok, String error, Integer x, Integer y, int removed) {
    }

    private static List<String> tabsOf(WebDriver web) {
        try {
            if (web.getWindowHandles() == null) return List.of();
            return new ArrayList<>(web.getWindowHandles());
        } catch (RuntimeException failure) {
            return List.of();
        }
    }

    private static boolean activate(WebDriver web, String tab) {
        if (web == null || tab == null || tab.isBlank()) return true;
        try {
            String current = web.getWindowHandle();
            if (!tab.equals(current)) web.switchTo().window(tab);
            focusedTab = tab;
            driver = web;
            CompositeRuntime.focusPage(web, pageTarget(web));
            return true;
        } catch (RuntimeException failure) {
            CompositeLog.info("window", "activate failed " + tab + " " + failure.getMessage());
            return false;
        }
    }

    static String pageTarget(WebDriver web) {
        if (!cdpCapable(web)) return null;
        try {
            Object info = cdp(web, "Target.getTargetInfo", Map.of());
            if (!(info instanceof Map<?, ?> map)) return null;
            Object body = map.get("targetInfo") instanceof Map<?, ?> nested ? nested : map;
            if (!(body instanceof Map<?, ?> target)) return null;
            Object type = target.get("type");
            if (type != null && !"page".equals(String.valueOf(type))) return null;
            Object id = target.get("targetId");
            if (id == null) id = target.get("id");
            if (id == null) return null;
            String text = String.valueOf(id).trim();
            return text.isEmpty() ? null : text;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String currentHandle(WebDriver web) {
        if (web == null) return null;
        try {
            return web.getWindowHandle();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** Ranked find-interface id: {@code frameId:backendNodeId}, same as {@code DomCompositeAdapter.matchNode}. */
    record HandleRef(String raw, String frameId, Integer backend) {
        static HandleRef parse(String handle) {
            if (handle == null || handle.isBlank()) return new HandleRef("", null, null);
            int colon = handle.lastIndexOf(':');
            if (colon > 0 && colon < handle.length() - 1) {
                try {
                    int backend = Integer.parseInt(handle.substring(colon + 1));
                    return new HandleRef(handle, handle.substring(0, colon), backend);
                } catch (NumberFormatException ignored) {
                    return new HandleRef(handle, null, null);
                }
            }
            return new HandleRef(handle, null, null);
        }
    }
}
