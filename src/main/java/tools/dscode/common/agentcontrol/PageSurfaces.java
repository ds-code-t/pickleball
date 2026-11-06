package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.composite.CompositeLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            function node() {
              var handle = spec.handle || '';
              if (!handle) return null;
              var direct = document.getElementById(handle);
              if (direct) return direct;
              var marked = document.querySelector('[data-pkb-handle="' + CSS.escape(handle) + '"]')
                || document.querySelector('[data-pkb-id="' + CSS.escape(handle) + '"]');
              if (marked) return marked;
              if (spec.backend !== undefined && spec.backend !== null && String(spec.backend) !== '') {
                var owned = document.querySelector('[data-backend-node-id="' + CSS.escape(String(spec.backend)) + '"]');
                if (owned) return owned;
              }
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
    }

    /** Ranked frame identity belongs to one browser and one tab. Not a scan. */
    static void bindFrame(WebDriver webDriver, String tab, String frameId) {
        if (webDriver == null || frameId == null || frameId.isBlank()) return;
        String browserId = "";
        for (Managed managed : MANAGED) {
            if (managed.driver == webDriver) browserId = managed.id;
        }
        FRAMES.put(frameId, new FrameOwner(browserId, webDriver, tab == null ? "" : tab));
    }

    /** Geometry view for a resolved handle. Visual-viewport x/y, not a page rect. */
    public static void rememberGeometry(String handle, double x, double y) {
        if (handle == null || handle.isBlank()) return;
        GEOMETRY.put(handle, new int[]{(int) Math.round(x), (int) Math.round(y)});
        HandleRef ref = HandleRef.parse(handle);
        if (ref.frameId != null && driver != null && !FRAMES.containsKey(ref.frameId)) {
            bindFrame(driver, currentHandle(driver), ref.frameId);
        }
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
        return body;
    }

    static Map<String, Object> unhighlight(Path page, String handle) throws IOException {
        if (page != null && Files.isRegularFile(page)) {
            write(page, stripStyle(read(page), handle));
        } else if (page != null) {
            throw new IllegalArgumentException("unknown handle: " + handle);
        }
        hideHighlight(handle);
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
        String id = "pkb-" + IDS.incrementAndGet();
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
        rememberPlacement(id, digest, summary);
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
        if (id == null || id.isBlank()) throw new IllegalArgumentException("missing id");
        Placement placement = placement(id);
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
        hideHighlight(null);
        CDP_HIGHLIGHTS.clear();
        for (Managed managed : List.copyOf(MANAGED)) {
            WebDriver web = managed.driver;
            try {
                List<String> handles = new ArrayList<>(web.getWindowHandles());
                for (String handle : handles) {
                    web.switchTo().window(handle);
                    runLive(web, "cleanup", null, null, null, null, "html", HTML_SENTENCE, "", false, null, null);
                }
            } catch (RuntimeException failure) {
                CompositeLog.info("html", "cleanup skipped driver " + managed.id + " " + failure.getMessage());
            }
        }
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
                activate(managed.driver, tab);
                drainTab(project, runId, managed.driver);
            }
            activate(managed.driver, current);
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
            return;
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
            highlight.put("highlightConfig", highlightConfig(preset, style));
            cdp(target, "Overlay.highlightNode", highlight);
            if (handle != null) CDP_HIGHLIGHTS.add(handle);
            return true;
        } catch (RuntimeException failure) {
            CompositeLog.info("highlight", "Overlay.highlightNode failed " + failure.getMessage());
            return false;
        }
    }

    private static void hideHighlight(String handle) {
        if (handle != null && !handle.isBlank()) {
            WebDriver target = resolveDriver(handle);
            if (target != null && hideOn(target)) return;
        }
        for (Managed managed : MANAGED) hideOn(managed.driver);
    }

    private static boolean hideOn(WebDriver web) {
        if (!cdpCapable(web)) return false;
        try {
            cdp(web, "Overlay.hideHighlight", Map.of());
            return true;
        } catch (RuntimeException failure) {
            CompositeLog.info("highlight", "Overlay.hideHighlight failed " + failure.getMessage());
            return false;
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

    private static void cdp(WebDriver web, String method, Map<String, Object> params) {
        try {
            web.getClass().getMethod("executeCdpCommand", String.class, Map.class).invoke(web, method, params);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Map<String, Object> highlightConfig(String preset, String style) {
        double[] content = {255, 243, 160, 0.45};
        double[] border = {230, 194, 0, 1};
        String key = preset == null ? "" : preset.trim().toLowerCase(Locale.ROOT);
        if ("outline-red".equals(key)) {
            content = new double[]{210, 34, 34, 0};
            border = new double[]{210, 34, 34, 1};
        }
        double[] parsed = cssColor(style);
        if (parsed != null) border = parsed;
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("showInfo", true);
        config.put("contentColor", rgba(content));
        config.put("borderColor", rgba(border));
        return config;
    }

    private static double[] cssColor(String style) {
        if (style == null) return null;
        java.util.regex.Matcher matcher = Pattern.compile("#([0-9a-fA-F]{6})").matcher(style);
        if (!matcher.find()) return null;
        int rgb = Integer.parseInt(matcher.group(1), 16);
        return new double[]{(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, 1};
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
            return new LiveResult(false, "unknown handle", null, null, 0);
        }
        if (target == null) target = driver;
        if (target == null) return new LiveResult(false, "no live browser", null, null, 0);
        return runLive(target, op, handle, where, css, html, kind, sentence, id, measure, x, y);
    }

    private static LiveResult runAcrossTabs(String op, String handle, String where, String css, String html, String kind, String sentence, String id, boolean measure, Integer x, Integer y) {
        LiveResult last = new LiveResult(false, "unknown id", null, null, 0);
        for (Managed managed : MANAGED) {
            List<String> tabs = tabsOf(managed.driver);
            if (tabs.isEmpty()) tabs = List.of("");
            for (String tab : tabs) {
                activate(managed.driver, tab);
                LiveResult live = runLive(managed.driver, op, handle, where, css, html, kind, sentence, id, measure, x, y);
                if ("remove".equals(op)) {
                    if (live.removed() > 0) return live;
                    last = live;
                    continue;
                }
                if (live.ok()) return live;
                last = live;
            }
        }
        return last;
    }

    private static WebDriver resolveDriver(String handle) {
        HandleRef ref = HandleRef.parse(handle);
        if (ref.frameId != null) {
            FrameOwner owner = FRAMES.get(ref.frameId);
            if (owner == null) return null;
            activate(owner.driver, owner.tab);
            driver = owner.driver;
            focusedTab = owner.tab;
            return owner.driver;
        }
        if (driver != null) activate(driver, focusedTab);
        return driver;
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
            return new LiveResult(true, null, liveX, liveY, removed);
        } catch (RuntimeException failure) {
            CompositeLog.info("html", "live helper failed " + failure.getMessage());
            return new LiveResult(false, failure.getMessage(), null, null, 0);
        }
    }

    static String neutralize(String html) {
        if (html == null) return "";
        String stripped = html.replaceAll("(?is)<script\\b[^>]*>.*?</script>", "");
        stripped = stripped.replaceAll("(?is)<script\\b[^>]*/\\s*>", "");
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < stripped.length()) {
            int start = stripped.indexOf('<', index);
            if (start < 0) {
                out.append(stripped.substring(index));
                break;
            }
            out.append(stripped, index, start);
            int end = tagEnd(stripped, start);
            if (end < 0) {
                out.append(stripped.substring(start));
                break;
            }
            String tag = stripped.substring(start, end + 1);
            if (!tag.matches("(?is)</?\\s*script\\b.*")) {
                String decoded = decodeEntities(decodeEntities(tag));
                decoded = decoded.replaceAll("(?i)\\ssrcdoc\\s*=\\s*(\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'|[^\\s>]+)", "");
                decoded = decoded.replaceAll("(?i)\\son[a-z][a-z0-9_-]*\\s*=\\s*(\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'|[^\\s>]+)", "");
                decoded = decoded.replaceAll("(?i)javascript\\s*:", "");
                out.append(decoded);
            }
            index = end + 1;
        }
        return out.toString();
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

    private static void rememberPlacement(String id, String sha, String summary) {
        PLACED.put(id, new Placement(sha, summary));
    }

    private static Placement placement(String id) {
        return PLACED.get(id);
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

    private static void activate(WebDriver web, String tab) {
        if (web == null || tab == null || tab.isBlank()) return;
        try {
            String current = web.getWindowHandle();
            if (tab.equals(current)) return;
            web.switchTo().window(tab);
        } catch (RuntimeException failure) {
            CompositeLog.info("window", "activate failed " + tab + " " + failure.getMessage());
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
