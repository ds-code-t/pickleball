package tools.dscode.common.agentcontrol;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;
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
    private static volatile WebDriver driver;

    private static final String LIVE_HELPER = """
            var spec = arguments[0];
            var op = spec.op;
            function node() {
              var handle = spec.handle || '';
              if (!handle) return null;
              return document.getElementById(handle)
                || document.querySelector('[data-pkb-handle="' + CSS.escape(handle) + '"]')
                || document.querySelector('[data-pkb-id="' + CSS.escape(handle) + '"]');
            }
            function mark(el, kind, sentence) {
              el.setAttribute('data-pkb-agent', 'agent_injected');
              el.setAttribute('data-pkb-kind', kind);
              el.setAttribute('data-pkb-sentence', sentence);
              el.setAttribute('data-pkb-id', spec.id);
            }
            if (op === 'highlight') {
              var el = node();
              if (!el) return {ok:false, error:'unknown handle'};
              el.setAttribute('data-pkb-style', 'style_modified');
              el.setAttribute('data-pkb-sentence', spec.sentence);
              el.setAttribute('data-pkb-css', spec.css || '');
              el.style.cssText = ((el.getAttribute('style') || '') + ';' + (spec.css || '')).replace(/^;/, '');
              return {ok:true};
            }
            if (op === 'unhighlight') {
              var target = spec.handle ? node() : document.body;
              var list = spec.handle ? [target] : Array.from(document.querySelectorAll('[data-pkb-style]'));
              list.forEach(function(el) {
                if (!el) return;
                var css = el.getAttribute('data-pkb-css') || '';
                if (css && el.getAttribute('style')) el.setAttribute('style', el.getAttribute('style').split(css).join(''));
                el.removeAttribute('data-pkb-style');
                el.removeAttribute('data-pkb-css');
                el.removeAttribute('data-pkb-sentence');
              });
              return {ok:true};
            }
            if (op === 'remove' || op === 'cleanup') {
              var sel = op === 'cleanup' ? '[data-pkb-agent]' : '[data-pkb-id="' + CSS.escape(spec.id || '') + '"]';
              document.querySelectorAll(sel).forEach(function(el) { el.remove(); });
              if (op === 'cleanup') {
                document.querySelectorAll('[data-pkb-style]').forEach(function(el) {
                  el.removeAttribute('data-pkb-style');
                  el.removeAttribute('data-pkb-css');
                  el.removeAttribute('data-pkb-sentence');
                });
              }
              return {ok:true};
            }
            var host = document.createElement('div');
            mark(host, spec.kind, spec.sentence);
            if (spec.css) host.setAttribute('style', spec.css);
            host.innerHTML = spec.html || '';
            var anchor = node();
            if (op === 'replace' && anchor) { anchor.replaceWith(host); }
            else if (op === 'insert' && anchor && spec.where === 'before') anchor.parentNode.insertBefore(host, anchor);
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
            return {ok:true, id:spec.id};
            """;

    private PageSurfaces() {
    }

    static void hold(WebDriver webDriver) {
        driver = webDriver;
    }

    static WebDriver driver() {
        return driver;
    }

    static void clearDriver() {
        driver = null;
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
        String html = read(page);
        String updated = paint(html, handle, css);
        if (updated == null) throw new IllegalArgumentException("unknown handle: " + handle);
        write(page, updated);
        boolean cdp = tryCdpHighlight(x, y, width, height);
        runLive("highlight", handle, null, css, null, "style", STYLE_SENTENCE);
        CompositeLog.info("highlight", "handle=" + handle + " preset=" + text(preset) + " cdp=" + cdp);
        Map<String, Object> body = labeled(List.of("style_modified"), STYLE_SENTENCE);
        body.put("handle", handle);
        body.put("preset", preset == null ? "" : preset);
        body.put("cdp", cdp ? "Overlay.highlightRect" : "style");
        body.put("reversed", false);
        return body;
    }

    static Map<String, Object> unhighlight(Path page, String handle) throws IOException {
        String html = read(page);
        write(page, stripStyle(html, handle));
        runLive("unhighlight", handle, null, null, null, "style", STYLE_SENTENCE);
        CompositeLog.info("highlight", "reverse handle=" + text(handle));
        Map<String, Object> body = labeled(List.of("style_modified"), STYLE_SENTENCE);
        body.put("handle", handle == null ? "" : handle);
        body.put("reversed", true);
        return body;
    }

    static Map<String, Object> place(Path page, String op, String handle, String where, String rawHtml, String kind, Integer x, Integer y) throws IOException {
        String id = "pkb-" + IDS.incrementAndGet();
        String sentence = "overlay".equals(kind) ? OVERLAY_SENTENCE : HTML_SENTENCE;
        boolean stripped = rawHtml != null && !rawHtml.equals(neutralize(rawHtml));
        String html = neutralize(rawHtml);
        String css = "overlay".equals(kind)
                ? "position:fixed;z-index:2147483646;left:" + (x == null ? 8 : x) + "px;top:" + (y == null ? 8 : y) + "px;background:#fffef2;border:2px solid #222;padding:8px;max-width:280px;"
                : "";
        String block = "<!--pkb-start:" + id + "--><div data-pkb-id=\"" + id + "\" data-pkb-agent=\"agent_injected\" data-pkb-kind=\""
                + kind + "\" data-pkb-sentence=\"" + sentence + "\""
                + (css.isBlank() ? "" : " style=\"" + css + "\"")
                + ">" + html + "</div><!--pkb-end:" + id + "-->";
        String document = read(page);
        String updated = switch (op) {
            case "replace" -> replaceHandle(document, handle, block);
            case "insert" -> insertHandle(document, handle, block, where);
            default -> append(document, block);
        };
        if (updated == null) throw new IllegalArgumentException("unknown handle: " + handle);
        write(page, updated);
        runLive(op, handle, where, css, html, kind, sentence, id);
        String summary = html.length() > 80 ? html.substring(0, 80) : html;
        CompositeLog.info("html".equals(kind) ? "html" : "overlay", op + " id=" + id + " sha=" + sha(html) + " summary=" + summary);
        Map<String, Object> body = labeled(
                "overlay".equals(kind) ? List.of("agent_injected", "overlay") : List.of("agent_injected"),
                sentence
        );
        body.put("id", id);
        body.put("handle", handle == null ? "" : handle);
        body.put("kind", kind);
        body.put("sha256", sha(html));
        body.put("scriptStripped", stripped);
        body.put("x", x);
        body.put("y", y);
        if ("overlay".equals(kind)) {
            body.put("placement", "visual-viewport-relative");
        }
        return body;
    }

    static Map<String, Object> remove(Path page, String id) throws IOException {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("missing id");
        write(page, read(page).replaceAll("(?s)<!--pkb-start:" + Pattern.quote(id) + "-->.*?<!--pkb-end:" + Pattern.quote(id) + "-->", ""));
        runLive("remove", null, null, null, null, "html", HTML_SENTENCE, id);
        CompositeLog.info("html", "remove id=" + id);
        Map<String, Object> body = labeled(List.of("agent_injected"), HTML_SENTENCE);
        body.put("id", id);
        body.put("removed", true);
        return body;
    }

    static Map<String, Object> cleanup(Path page) throws IOException {
        String html = read(page);
        html = html.replaceAll("(?s)<!--pkb-start:.*?<!--pkb-end:.*?-->", "");
        html = stripStyle(html, null);
        write(page, html);
        runLive("cleanup", null, null, null, null, "html", HTML_SENTENCE);
        CompositeLog.info("html", "cleanup");
        Map<String, Object> body = labeled(List.of("agent_injected"), HTML_SENTENCE);
        body.put("cleaned", true);
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
        for (String line : AgentStore.readLines(file)) {
            events.add(AgentStore.JSON.readValue(line, Map.class));
        }
        return events;
    }

    static void cleanupAll() {
        for (Path page : PAGES) {
            try {
                if (Files.isRegularFile(page)) cleanup(page);
            } catch (IOException | RuntimeException ignored) {
                // Session end must not hide the original shutdown error.
            }
        }
    }

    private static void drainLiveInputs(Path project, String runId) throws IOException {
        WebDriver current = driver;
        if (!(current instanceof JavascriptExecutor executor)) return;
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

    private static boolean tryCdpHighlight(Integer x, Integer y, Integer width, Integer height) {
        WebDriver current = driver;
        if (!(current instanceof ChromiumDriver chromium)) return false;
        int left = x == null ? 8 : x;
        int top = y == null ? 8 : y;
        int w = width == null ? 120 : width;
        int h = height == null ? 32 : height;
        try {
            chromium.executeCdpCommand("Overlay.enable", Map.of());
            chromium.executeCdpCommand("Overlay.highlightRect", Map.of(
                    "x", left,
                    "y", top,
                    "width", w,
                    "height", h,
                    "color", Map.of("r", 255, "g", 243, "b", 160, "a", 0.45),
                    "outlineColor", Map.of("r", 210, "g", 34, "b", 34, "a", 1)
            ));
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static void runLive(String op, String handle, String where, String css, String html, String kind, String sentence) {
        runLive(op, handle, where, css, html, kind, sentence, "");
    }

    private static void runLive(String op, String handle, String where, String css, String html, String kind, String sentence, String id) {
        WebDriver current = driver;
        if (!(current instanceof JavascriptExecutor executor)) return;
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("op", op);
        spec.put("handle", handle == null ? "" : handle);
        spec.put("where", where == null ? "after" : where);
        spec.put("css", css == null ? "" : css);
        spec.put("html", html == null ? "" : html);
        spec.put("kind", kind);
        spec.put("sentence", sentence);
        spec.put("id", id == null ? "" : id);
        try {
            executor.executeScript(LIVE_HELPER, spec);
        } catch (RuntimeException failure) {
            CompositeLog.info("html", "live helper skipped " + failure.getMessage());
        }
    }

    static String neutralize(String html) {
        if (html == null) return "";
        String stripped = html.replaceAll("(?is)<script\\b[^>]*>.*?</script>", "");
        stripped = stripped.replaceAll("(?i)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        stripped = stripped.replaceAll("(?i)javascript:", "");
        return stripped;
    }

    private static String paint(String html, String handle, String css) {
        if (handle == null || handle.isBlank()) return null;
        Pattern pattern = Pattern.compile("(?i)(<[^>]*\\bid\\s*=\\s*([\"'])" + Pattern.quote(handle) + "\\2[^>]*)(>)");
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) return null;
        String open = matcher.group(1)
                .replaceAll("(?i)\\sdata-pkb-style=\"style_modified\"", "")
                .replaceAll("(?i)\\sdata-pkb-sentence=\"[^\"]*\"", "")
                .replaceAll("(?i)\\sdata-pkb-css=\"[^\"]*\"", "");
        String injected = open + " data-pkb-style=\"style_modified\" data-pkb-sentence=\"" + STYLE_SENTENCE
                + "\" data-pkb-css=\"" + css.replace("\"", "") + "\"" + matcher.group(3);
        return matcher.replaceFirst(Matcher.quoteReplacement(injected));
    }

    private static String stripStyle(String html, String handle) {
        Pattern pattern = handle == null || handle.isBlank()
                ? Pattern.compile("(?i)\\sdata-pkb-style=\"style_modified\"|\\sdata-pkb-sentence=\"" + Pattern.quote(STYLE_SENTENCE) + "\"|\\sdata-pkb-css=\"[^\"]*\"")
                : Pattern.compile("(?i)(<[^>]*\\bid\\s*=\\s*([\"'])" + Pattern.quote(handle) + "\\2[^>]*?)(\\sdata-pkb-style=\"style_modified\"|\\sdata-pkb-sentence=\"[^\"]*\"|\\sdata-pkb-css=\"[^\"]*\")");
        if (handle == null || handle.isBlank()) return pattern.matcher(html).replaceAll("");
        String current = html;
        String previous;
        do {
            previous = current;
            current = pattern.matcher(current).replaceAll("$1");
        } while (!current.equals(previous));
        return current;
    }

    private static String replaceHandle(String html, String handle, String block) {
        if (handle == null || handle.isBlank()) return null;
        Pattern pattern = Pattern.compile("(?is)<[^>]*\\bid\\s*=\\s*([\"'])" + Pattern.quote(handle) + "\\1[^>]*>.*?</[^>]+>");
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) return null;
        return matcher.replaceFirst(Matcher.quoteReplacement(block));
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
        if (!Files.isRegularFile(page)) return "<html><body></body></html>";
        return Files.readString(page, StandardCharsets.UTF_8);
    }

    private static void write(Path page, String html) throws IOException {
        Files.createDirectories(page.getParent() == null ? Path.of(".") : page.getParent());
        Files.writeString(page, html, StandardCharsets.UTF_8);
    }

    static String sha(String html) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(html.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            return "";
        }
    }

    private static Map<String, Object> labeled(List<String> codes, String sentence) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("codes", codes);
        body.put("sentence", sentence);
        return body;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
