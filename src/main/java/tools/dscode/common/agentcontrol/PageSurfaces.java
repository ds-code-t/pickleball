package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.browser.BrowserFacade;
import tools.dscode.browser.BrowserLog;
import tools.dscode.browser.CdpAdapter;
import tools.dscode.browser.ProtocolSelector;
import tools.dscode.browser.SurfaceAssets;
import tools.dscode.common.agentfast.RuntimeInstall;
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
    static final String HTML_SENTENCE = SurfaceAssets.sentence("html");
    static final String OVERLAY_SENTENCE = SurfaceAssets.sentence("overlay");
    static final String STYLE_SENTENCE = SurfaceAssets.sentence("highlight");

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final CopyOnWriteArrayList<Path> PAGES = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Managed> MANAGED = new CopyOnWriteArrayList<>();
    private static volatile WebDriver driver;
    private static volatile String focusedTab;
    private static final CopyOnWriteArrayList<FrameOwner> FRAME_LIST = new CopyOnWriteArrayList<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, HandleOwner> HANDLE_OWNERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<Focus> THREAD_FOCUS = new ThreadLocal<>();
    private static final Map<String, int[]> GEOMETRY = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, int[]> BOUNDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<int[]> PLACED_SIZE = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, String>> VISUAL_EXTRA = new ThreadLocal<>();
    private static final java.util.Set<String> CDP_HIGHLIGHTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record Focus(WebDriver driver, String tab) {
    }

    private record HandleOwner(String browserId, String tab, WebDriver driver) {
    }

    private record FrameOwner(String browserId, WebDriver driver, String tab, String frameId) {
    }

    private static final String LIVE_HELPER = SurfaceAssets.script("live-helper");
    private static final String RUNTIME = SurfaceAssets.script("pkb-runtime");
    private static final java.util.concurrent.ConcurrentHashMap<Integer, BrowserFacade> FACADES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<Integer> SURFACE_PROTOCOL = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ConcurrentLinkedQueue<Map<String, String>> PUSHED = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Predefined DOM.resolveNode stamp. Not agent script. Removed by the helper after lookup. */
    static final String BRIDGE_MARK = SurfaceAssets.script("bridge-mark");

    /**
     * Names an iframe only when it has neither name nor id, and stamps a bridge token.
     * The caller restores both after switchTo().frame(name).
     */
    static final String FRAME_MARK = SurfaceAssets.script("frame-mark");

    static final String FRAME_RESTORE = SurfaceAssets.script("frame-restore");

    private PageSurfaces() {
    }

    record Managed(String id, WebDriver driver, String template, boolean owned, boolean host) {
    }

    static void manage(WebDriver webDriver, String template, boolean owned) {
        manage(webDriver, template, owned, false);
    }

    static void manage(WebDriver webDriver, String template, boolean owned, boolean host) {
        if (webDriver == null) return;
        for (Managed managed : MANAGED) {
            if (managed.driver == webDriver) return;
        }
        String id = "browser-" + (MANAGED.size() + 1);
        if (webDriver instanceof RemoteWebDriver remote && remote.getSessionId() != null) {
            id = remote.getSessionId().toString();
        }
        String name = template == null ? "" : template;
        MANAGED.add(new Managed(id, webDriver, name, owned, host));
        driver = webDriver;
    }

    static boolean focus(WebDriver webDriver) {
        String tab = currentHandle(webDriver);
        String target = targetForSelectedTab(webDriver, tab);
        if (remotePage(webDriver) && (target == null || target.isBlank())) {
            CompositeLog.info("window", "page target unknown " + text(tab));
            return false;
        }
        if (!CompositeRuntime.focusPage(webDriver, target)) {
            CompositeLog.info("window", "page session failed " + text(tab));
            return false;
        }
        driver = webDriver;
        focusedTab = tab;
        THREAD_FOCUS.set(new Focus(webDriver, focusedTab));
        rememberPageTarget(webDriver, tab, target);
        return true;
    }

    /** Ranked frame identity belongs to one browser and one tab. Not a scan. */
    static void bindFrame(WebDriver webDriver, String tab, String frameId) {
        if (webDriver == null || frameId == null || frameId.isBlank()) return;
        String browserId = browserId(webDriver);
        String useTab = tab == null || tab.isBlank() ? "" : tab;
        FRAME_LIST.removeIf(existing -> existing.driver == webDriver && useTab.equals(existing.tab) && frameId.equals(existing.frameId));
        FRAME_LIST.add(new FrameOwner(browserId, webDriver, useTab, frameId));
    }

    /** The ranked handle carries browser and tab even when the hit has no visual box. */
    static void bindHandle(WebDriver webDriver, String tab, String handle) {
        if (webDriver == null || handle == null || handle.isBlank()) return;
        String useTab = tab == null || tab.isBlank() ? text(currentHandle(webDriver)) : tab;
        HANDLE_OWNERS.put(handle, new HandleOwner(browserId(webDriver), useTab, webDriver));
        HandleRef ref = parseHandle(handle);
        if (ref.frameId != null) bindFrame(webDriver, useTab, ref.frameId);
    }

    /** Geometry view for a resolved handle. Visual-viewport point only, not width or height. */
    public static void rememberGeometry(String handle, double x, double y) {
        rememberGeometry(handle, x, y, driver, focusedTabOf(driver));
    }

    /**
     * Stores only the visual-viewport point and, when the query captured an owner,
     * binds that frame to that driver. Width and height are not kept here.
     */
    public static void rememberGeometry(String handle, double x, double y, WebDriver owner, String tab) {
        if (handle == null || handle.isBlank()) return;
        GEOMETRY.put(handle, new int[]{(int) Math.round(x), (int) Math.round(y)});
        if (owner != null) bindHandle(owner, tab, handle);
    }

    /** Resolved element box. Not written by {@link #rememberGeometry}. */
    public static void rememberBounds(String handle, double x, double y, double width, double height) {
        rememberBounds(handle, x, y, width, height, null, null);
    }

    public static void rememberBounds(String handle, double x, double y, double width, double height, WebDriver owner, String tab) {
        if (handle == null || handle.isBlank()) return;
        BOUNDS.put(handle, new int[]{
                (int) Math.round(x), (int) Math.round(y), (int) Math.round(width), (int) Math.round(height)
        });
        if (owner != null) bindHandle(owner, tab, handle);
    }

    /** The point from {@link #rememberGeometry}, or null. Length is always 2. */
    public static int[] rememberedPoint(String handle) {
        if (handle == null || handle.isBlank()) return null;
        int[] point = GEOMETRY.get(handle);
        return point == null ? null : point.clone();
    }

    static void clearGeometry() {
        GEOMETRY.clear();
        BOUNDS.clear();
    }

    /** Driver whose debugger address owns this thread's engine session. No global-driver fallback. */
    static WebDriver ownerForQuery() {
        String cdp = CompositeRuntime.focusedCdp();
        if (cdp == null || cdp.isBlank()) return null;
        String key = dev.domcomposite.Client.cdpKey(cdp);
        Focus focus = THREAD_FOCUS.get();
        if (focus != null && focus.driver instanceof RemoteWebDriver remote) {
            String address = debuggerAddress(remote);
            if (!address.isEmpty()) {
                String browserKey = dev.domcomposite.Client.cdpKey(address);
                if (browserKey.equals(key) || key.startsWith(browserKey + "/")) return focus.driver;
            }
        }
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
        Focus focus = THREAD_FOCUS.get();
        if (focus != null && focus.driver == web && focus.tab != null && !focus.tab.isBlank()) return focus.tab;
        if (web != null && web == driver && focusedTab != null && !focusedTab.isBlank()) return focusedTab;
        return currentHandle(web);
    }

    static void detach(WebDriver webDriver) {
        forgetDriver(webDriver);
        MANAGED.removeIf(managed -> managed.driver == webDriver);
        if (driver == webDriver) {
            driver = MANAGED.isEmpty() ? null : MANAGED.get(MANAGED.size() - 1).driver;
        }
    }

    /** A quit browser must not keep owning handles the host page still uses. */
    private static void forgetDriver(WebDriver webDriver) {
        if (webDriver == null) return;
        FRAME_LIST.removeIf(owner -> owner.driver == webDriver);
        HIGHLIGHT_SITES.removeIf(site -> site.driver == webDriver);
        SURFACE_SITES.removeIf(site -> site.driver == webDriver);
        java.util.List<String> handles = new java.util.ArrayList<>();
        HANDLE_OWNERS.forEach((handle, owner) -> {
            if (owner.driver == webDriver) handles.add(handle);
        });
        for (String handle : handles) {
            HANDLE_OWNERS.remove(handle);
            GEOMETRY.remove(handle);
            BOUNDS.remove(handle);
        }
    }

    static void hold(WebDriver webDriver) {
        manage(webDriver, "", false);
    }

    static void quitOwned() {
        quitMatching(true);
    }

    /** Quits every non-host driver. The host driver stays until {@link #quitHost()}. */
    static void quitAll() {
        for (Managed managed : List.copyOf(MANAGED)) {
            if (managed.host) continue;
            quitManaged(managed);
        }
    }

    /** Worker-stop and {@code /v1/browser/release}. Never the host driver. */
    static void quitUnowned() {
        for (Managed managed : List.copyOf(MANAGED)) {
            if (managed.owned || managed.host) continue;
            quitManaged(managed);
        }
    }

    /** One driver the caller launched. The host driver is left attached. */
    static void quitOne(WebDriver webDriver) {
        if (webDriver == null) return;
        for (Managed managed : List.copyOf(MANAGED)) {
            if (managed.driver != webDriver || managed.host) continue;
            quitManaged(managed);
        }
    }

    /** Host release only. */
    static void quitHost() {
        for (Managed managed : List.copyOf(MANAGED)) {
            if (!managed.host) continue;
            quitManaged(managed);
        }
    }

    private static void quitMatching(boolean ownedOnly) {
        for (Managed managed : List.copyOf(MANAGED)) {
            if (managed.host) continue;
            if (ownedOnly && !managed.owned) continue;
            quitManaged(managed);
        }
    }

    private static void quitManaged(Managed managed) {
        tools.dscode.browser.DebugPortOwner.releaseDriver(managed.driver);
        try {
            managed.driver.quit();
        } catch (RuntimeException failure) {
            CompositeLog.info("window", "quit skipped " + managed.id + " " + failure.getMessage());
        }
        detach(managed.driver);
    }

    /** Drops non-host drivers from the registry. A host driver stays the focused page. */
    static void clearExceptHost() {
        boolean hostPresent = false;
        for (Managed managed : MANAGED) {
            if (managed.host) hostPresent = true;
        }
        if (!hostPresent) {
            clearDriver();
            return;
        }
        MANAGED.removeIf(managed -> !managed.host);
        driver = MANAGED.get(MANAGED.size() - 1).driver;
    }

    static WebDriver driver() {
        pruneQuit();
        return driver;
    }

    static List<Managed> managed() {
        pruneQuit();
        return List.copyOf(MANAGED);
    }

    /** A driver someone quit (session id gone) is no longer a surface; the newest live one becomes current. */
    private static void pruneQuit() {
        MANAGED.removeIf(managed -> quit(managed.driver));
        if (quit(driver)) {
            driver = MANAGED.isEmpty() ? null : MANAGED.get(MANAGED.size() - 1).driver;
            focusedTab = null;
            THREAD_FOCUS.remove();
        }
    }

    private static boolean quit(WebDriver webDriver) {
        return webDriver instanceof RemoteWebDriver remote && remote.getSessionId() == null;
    }

    static void clearDriver() {
        MANAGED.clear();
        driver = null;
        focusedTab = null;
        THREAD_FOCUS.remove();
        FRAME_LIST.clear();
        HANDLE_OWNERS.clear();
        clearGeometry();
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
        PAGE_TARGETS.clear();
        FACADES.clear();
        SURFACE_PROTOCOL.clear();
        PUSHED.clear();
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
        // x, y, width, and height are accepted and ignored. The node is painted by
        // Overlay.highlightNode or by reversible DOM style, not by a caller box.
        if (x != null || y != null || width != null || height != null) {
            CompositeLog.trace("highlight", "ignored geometry");
        }
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
            throw hideFailed();
        }
        boolean cdpOnly = handle != null && CDP_HIGHLIGHTS.remove(handle);
        if (handle == null || handle.isBlank()) CDP_HIGHLIGHTS.clear();
        LiveResult live = new LiveResult(true, null, null, null, 0);
        if (!cdpOnly) {
            if (handle == null || handle.isBlank()) {
                live = runAcrossTabs("unhighlight", handle, null, null, null, "style", STYLE_SENTENCE, "", false, null, null);
            } else {
                live = runLive("unhighlight", handle, null, null, null, "style", STYLE_SENTENCE, "");
            }
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
        return place(page, op, handle, where, rawHtml, kind, x, y, geometryJson, ledger, null, null);
    }

    static Map<String, Object> place(Path page, String op, String handle, String where, String rawHtml, String kind, Integer x, Integer y, String geometryJson, Path ledger, Integer width, Integer height) throws IOException {
        return place(page, op, handle, where, rawHtml, kind, x, y, geometryJson, ledger, width, height, Map.of());
    }

    static Map<String, Object> place(Path page, String op, String handle, String where, String rawHtml, String kind, Integer x, Integer y, String geometryJson, Path ledger, Integer width, Integer height, Map<String, String> extras) throws IOException {
        boolean overlay = "overlay".equals(kind);
        boolean visual = overlay || VisualKinds.is(kind);
        String sentence = visual || "html".equals(kind) ? SurfaceAssets.sentence(kind) : HTML_SENTENCE;
        boolean stripped = rawHtml != null && !rawHtml.equals(neutralize(rawHtml));
        String html = labelElements(neutralize(rawHtml), kind, sentence);
        boolean liveOnly = page == null;
        if (liveOnly && managed().isEmpty()) {
            throw new IllegalStateException("no live browser; " + kind + " was not applied");
        }
        if (visual && liveOnly && handle != null && !handle.isBlank()) {
            HandleRef ranked = parseHandle(handle);
            // A published frame:backend has an owner and no box yet. Read the box
            // before the unknown-handle check. An unbound ranked handle returns
            // here without a page script when the driver cannot bridge.
            if (ranked.backend != null && lookupBounds(handle) == null && lookupGeometry(handle) == null) {
                rememberRankedBox(handle, ranked.backend);
            }
        }
        if (visual && liveOnly && handle != null && !handle.isBlank() && !resolved(handle)) {
            HandleRef named = parseHandle(handle);
            if (named.frameId != null || named.browserId != null) {
                FrameOwner owner = frameOwner(named);
                if ((owner == null || !browserStillManaged(owner)) && lookupBounds(handle) == null) {
                    throw new IllegalArgumentException("unknown handle: " + handle);
                }
            }
        }
        if (visual && liveOnly && handle != null && !handle.isBlank() && !resolved(handle)) {
            String placed = tools.dscode.common.agentfast.TargetVerb.placementHandle(handle);
            if (placed != null && !placed.isBlank()) handle = placed;
            else {
                String selector = tools.dscode.common.agentfast.TargetHandles.selector(handle);
                if (selector != null && !selector.isBlank()) {
                    try {
                        String found = UiBatch.resolve(selector);
                        if (found != null && !found.isBlank()) handle = found;
                    } catch (Exception ignored) {
                        // The live lookup below still reports unknown handle.
                    }
                }
            }
            HandleRef ranked = parseHandle(handle);
            if (ranked.backend != null && !resolved(handle) && !locateLiveHandle(handle)) {
                rememberRankedBox(handle, ranked.backend);
            }
        }
        if (visual && liveOnly && handle != null && !handle.isBlank() && !resolved(handle) && !locateLiveHandle(handle)) {
            if (parseHandle(handle).backend == null) throw new IllegalArgumentException("unknown handle: " + handle);
        }
        SurfaceBox.View box = visual ? chooseBox(handle, geometryJson, x, y, width, height) : null;
        int[] point = visual
                ? (box == null ? null : new int[]{box.x(), box.y()})
                : visualPoint(geometryJson, x, y);
        if (visual && point == null && page != null) {
            throw new IllegalArgumentException(kind + " needs a visual-viewport box or x and y");
        }
        if (visual && point == null && page == null) {
            throw new IllegalArgumentException(kind + " needs a target or a visual-viewport box");
        }
        WebDriver destination = driver;
        String destinationTab = focusedTab == null ? "" : focusedTab;
        if (liveOnly) {
            Selected selected = selectDestination(handle);
            destination = selected.driver();
            destinationTab = selected.tab();
        }
        Path idFile = idStore(page, ledger, destination, destinationTab);
        String id = allocateId(idFile);
        rememberAnchor(handle, id);
        int left = point == null ? 0 : point[0];
        int top = point == null ? 0 : point[1];
        String css = overlay ? overlayCss(kind, point, box) : (visual ? VisualKinds.css(kind, point[0], point[1], box, extras) : "");
        String block = "<!--pkb-start:" + id + "--><div data-pkb-id=\"" + id + "\" data-pkb-agent=\"agent_injected\" data-pkb-meta=\"true\" data-pkb-purpose=\""
                + purposeTokens(kind) + "\" data-pkb-kind=\""
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
        int[] painted = visual && !overlay ? VisualKinds.paintedSize(kind, box) : null;
        if (overlay && box != null && box.sized()) PLACED_SIZE.set(new int[]{box.width(), box.height()});
        else if (painted != null) PLACED_SIZE.set(painted);
        else PLACED_SIZE.remove();
        VISUAL_EXTRA.set(extras == null ? Map.of() : extras);
        LiveResult live;
        try {
            live = runLive(op, handle, where, css, html, kind, sentence, id, measure, point == null ? null : left, point == null ? null : top);
        } finally {
            PLACED_SIZE.remove();
            VISUAL_EXTRA.remove();
        }
        if (liveOnly && !live.ok()) {
            throw new IllegalStateException(live.error() == null ? "live " + op + " did not resolve " + handle : live.error());
        }
        String summary = html.length() > 80 ? html.substring(0, 80) : html;
        String digest = sha(html);
        rememberPlacement(page, ledger, id, digest, summary, kind);
        if (idFile != null && !idFile.equals(page == null ? null : placeFile(page))
                && !idFile.equals(ledger == null ? null : ledger.resolve("placements.json"))) {
            writePlacement(idFile, id, digest, summary, kind);
        }
        String sizeNote = box != null && box.sized() ? " width=" + box.width() + " height=" + box.height() + " boundsSource=" + box.source() : "";
        String entity = switch (kind) {
            case "html" -> "html";
            case "overlay" -> "overlay";
            default -> VisualKinds.is(kind) ? kind : "html";
        };
        CompositeLog.info(entity, op + " id=" + id + " sha=" + digest + " summary=" + summary + sizeNote);
        Map<String, Object> body = labeled(
                visual ? List.of("agent_injected", kind) : List.of("agent_injected"),
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
        if (overlay) {
            boolean viewed = (geometryJson != null && !geometryJson.isBlank())
                    || lookupGeometry(handle) != null
                    || (box != null && "resolved".equals(box.source()));
            body.put("placement", viewed ? "visual-viewport-relative" : "caller");
            body.put("boundsSource", box == null ? "none" : box.source());
            if (box != null && box.sized()) {
                body.put("width", box.width());
                body.put("height", box.height());
            } else {
                if (width != null) body.put("width", width);
                if (height != null) body.put("height", height);
            }
        } else if (visual) {
            boolean viewed = (geometryJson != null && !geometryJson.isBlank())
                    || lookupGeometry(handle) != null
                    || (box != null && "resolved".equals(box.source()));
            body.put("placement", viewed ? "visual-viewport-relative" : "caller");
            body.put("boundsSource", box == null ? "none" : box.source());
            if (painted != null) {
                body.put("width", painted[0]);
                body.put("height", painted[1]);
            }
        }
        return body;
    }

    /** Element handle (short id, element id, or frame:backend) to the placement id that painted it. */
    private static final Map<String, String> ANCHOR_IDS = new java.util.concurrent.ConcurrentHashMap<>();

    static void rememberAnchor(String handle, String id) {
        if (handle == null || handle.isBlank() || id == null || id.isBlank()) return;
        ANCHOR_IDS.put(handle.trim(), id);
        String placed = tools.dscode.common.agentfast.TargetVerb.placementHandle(handle);
        if (placed != null && !placed.isBlank()) ANCHOR_IDS.put(placed, id);
    }

    /** Placement id for an element handle, or null when this process did not paint one. */
    static String placementFor(String handle) {
        if (handle == null || handle.isBlank()) return null;
        String direct = ANCHOR_IDS.get(handle.trim());
        if (direct != null) return direct;
        String placed = tools.dscode.common.agentfast.TargetVerb.placementHandle(handle);
        return placed == null ? null : ANCHOR_IDS.get(placed);
    }

    static Map<String, Object> remove(Path page, String id) throws IOException {
        return remove(page, id, null);
    }

    static Map<String, Object> remove(Path page, String id, Path ledger) throws IOException {
        return remove(page, id, ledger, "html");
    }

    /**
     * Removes one placement. A visual kind (label, border, arrow, step, magnifier,
     * spotlight) logs under its own {@code pkb_composite_log} entity and labels the
     * result with its own sentence. Other kinds keep the {@code html} entity.
     */
    static Map<String, Object> remove(Path page, String id, Path ledger, String kind) throws IOException {
        boolean visual = VisualKinds.is(kind);
        String entity = visual ? kind : "html";
        String sentence = visual ? SurfaceAssets.sentence(kind) : HTML_SENTENCE;
        if (id == null || id.isBlank()) throw new IllegalArgumentException("missing id");
        Placement placement = placement(page, ledger, id);
        VisualKinds.requireRemovalKind(id, placedKind(page, ledger, id), kind);
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
        CompositeLog.info(entity, "remove id=" + id + " sha=" + digest + " summary=" + summary);
        Map<String, Object> body = labeled(visual ? List.of("agent_injected", kind) : List.of("agent_injected"), sentence);
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
        cleanupManaged(false);
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
        // Values typed into password, card, or one-time-code fields are never stored, whatever the page sent.
        if ("true".equals(event.get("sensitive"))) {
            row.remove("value");
            row.put("filled", "true");
        }
        row.put("at", AgentStore.now());
        row.put("codes", List.of("agent_injected", "overlay"));
        row.put("sentence", OVERLAY_SENTENCE);
        String session = tools.dscode.common.agentfast.PushChannel.session();
        if (session == null || session.isBlank()) session = runId == null || runId.isBlank() ? "session" : runId;
        row.putIfAbsent("sessionId", session);
        Path file = AgentStore.root(project, runId).resolve("overlay-events.jsonl");
        if (duplicateEvent(file, row)) return;
        AgentStore.appendCapped(file, AgentStore.JSON.writeValueAsString(row));
        CompositeLog.info("overlay", "input type=" + event.getOrDefault("type", ""));
        ChatLog.send(project, runId, "overlay", "system", "", "overlay input " + event.getOrDefault("type", "") + " " + event.getOrDefault("id", ""));
        tools.dscode.common.agentfast.PushWake.signal();
    }

    /** A binding delivery and a queue drain are one event. The second copy is dropped. */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<String>> RECENT_EIDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean duplicateEvent(Path file, Map<String, Object> row) {
        Object eid = row == null ? null : row.get("eid");
        if (eid != null && !String.valueOf(eid).isBlank() && !"null".equals(String.valueOf(eid))) {
            String key = file.toAbsolutePath().normalize().toString();
            java.util.ArrayDeque<String> seen = RECENT_EIDS.computeIfAbsent(key, ignored -> new java.util.ArrayDeque<>());
            String token = String.valueOf(eid);
            synchronized (seen) {
                if (seen.contains(token)) return true;
                seen.addLast(token);
                while (seen.size() > 300) seen.removeFirst();
            }
            return false;
        }
        String fingerprint = eventFingerprint(row);
        if (!Files.isRegularFile(file)) return false;
        try {
            String last = "";
            for (String line : AgentStore.readGroup(file)) last = line;
            if (last.isBlank()) return false;
            Map<?, ?> prior = AgentStore.JSON.readValue(last, Map.class);
            return fingerprint.equals(eventFingerprint(prior));
        } catch (RuntimeException | java.io.IOException failure) {
            return false;
        }
    }

    private static String eventFingerprint(Map<?, ?> event) {
        StringBuilder raw = new StringBuilder();
        for (String key : List.of("type", "id", "text", "value", "source", "choice", "name")) {
            Object value = event == null ? null : event.get(key);
            raw.append(key).append('=').append(value == null ? "" : value).append('\n');
        }
        return raw.toString();
    }

    /**
     * Lines already on disk. Does not drain the live page, so a poll that starts at now
     * can return the events that drain writes and still hide rows that were already stored.
     */
    static int storedInputCount(Path project, String runId) throws IOException {
        Path file = AgentStore.root(project, runId).resolve("overlay-events.jsonl");
        return AgentStore.readGroup(file).size();
    }

    /** Events after the first {@code after} stored events. The cursor is the stored event count. */
    static List<Map<String, Object>> pollInput(Path project, String runId, int after) throws IOException {
        List<Map<String, Object>> all = pollInput(project, runId);
        if (after <= 0) return all;
        if (after >= all.size()) return List.of();
        return List.copyOf(all.subList(after, all.size()));
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

    static void cleanupExceptHost() {
        cleanupManaged(true);
    }

    static void cleanupAll() {
        cleanupManaged(false);
        RuntimeException first = null;
        for (Path page : PAGES) {
            try {
                if (Files.isRegularFile(page)) cleanup(page);
            } catch (IOException failure) {
                CompositeLog.info("html", "cleanup failed " + failure.getMessage());
                if (first == null) first = new IllegalStateException("cleanup failed", failure);
            } catch (RuntimeException failure) {
                CompositeLog.info("html", "cleanup failed " + failure.getMessage());
                if (first == null) first = failure;
            }
        }
        if (first != null) throw first;
    }

    static void cleanupManaged() {
        cleanupManaged(false);
    }

    private static void cleanupManaged(boolean skipHost) {
        if (!(skipHost && hostDriver(driver))) {
            Hide hidden = hideHighlight(null);
            if (hidden == Hide.FAILED) {
                throw hideFailed();
            }
        }
        if (!skipHost) CDP_HIGHLIGHTS.clear();
        RuntimeException first = null;
        for (Managed managed : List.copyOf(MANAGED)) {
            if (skipHost && managed.host) continue;
            WebDriver web = managed.driver;
            try {
                if (absent(web, null)) {
                    CompositeLog.info("html", "cleanup skipped closed driver " + managed.id);
                    continue;
                }
                List<String> handles;
                try {
                    handles = new ArrayList<>(tabsOf(web));
                } catch (RuntimeException failure) {
                    if (closed(failure)) {
                        CompositeLog.info("html", "cleanup skipped closed driver " + managed.id);
                        continue;
                    }
                    throw failure;
                }
                if (handles.isEmpty()) throw new IllegalStateException("tab enumeration failed");
                for (String handle : handles) {
                    if (!handle.isBlank() && !activate(web, handle)) {
                        if (absent(web, handle) || closed(lastActivateFailure.get())) {
                            CompositeLog.info("html", "cleanup skipped closed tab " + handle);
                            continue;
                        }
                        throw new IllegalStateException("tab switch failed");
                    }
                    if (!resetTop(web)) throw new IllegalStateException("top document reset failed");
                    LiveResult live = runLive(web, "cleanup", null, null, null, null, "html", HTML_SENTENCE, "", false, null, null);
                    if (!live.ok()) {
                        throw new IllegalStateException(live.error() == null ? "cleanup failed" : live.error());
                    }
                    for (String frame : framesFor(web, handle)) {
                        if (!enterFrame(web, frame)) {
                            if (!frameInTree(web, frame)) {
                                if (!resetTop(web)) throw new IllegalStateException("top document reset failed");
                                continue;
                            }
                            throw new IllegalStateException("frame entry failed");
                        }
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

    private static boolean hostDriver(WebDriver web) {
        if (web == null) return false;
        for (Managed managed : MANAGED) {
            if (managed.driver == web && managed.host) return true;
        }
        return false;
    }

    static int[] visualPoint(String geometryJson, Integer x, Integer y) {
        int[] fromGeometry = geometryPoint(geometryJson);
        if (fromGeometry != null) return fromGeometry;
        if (x != null && y != null) return new int[]{x, y};
        return null;
    }

    static SurfaceBox.View chooseBox(String handle, String geometryJson, Integer x, Integer y, Integer width, Integer height) {
        int[] json = geometryBox(geometryJson);
        if (json != null && json[2] >= 0 && json[3] >= 0) {
            return new SurfaceBox.View(json[0], json[1], json[2], json[3], "resolved");
        }
        int[] bounds = lookupBounds(handle);
        if (bounds != null) {
            return new SurfaceBox.View(bounds[0], bounds[1], bounds[2], bounds[3], "resolved");
        }
        int[] point = json == null ? null : new int[]{json[0], json[1]};
        if (point == null && x != null && y != null) point = new int[]{x, y};
        if (geometryJson == null || geometryJson.isBlank()) {
            int[] looked = lookupGeometry(handle);
            if (looked != null) point = looked;
        }
        if (point == null) return null;
        boolean stored = width != null && height != null;
        String source = stored ? "stored"
                : (json != null || lookupGeometry(handle) != null ? "visual-viewport-relative" : "caller");
        return new SurfaceBox.View(point[0], point[1], stored ? width : -1, stored ? height : -1, source);
    }

    private static String overlayCss(String kind, int[] point, SurfaceBox.View box) {
        if (!"overlay".equals(kind) || point == null) return "";
        String css = "position:fixed;z-index:2147483646;left:" + point[0] + "px;top:" + point[1]
                + "px;background:#fffef2;border:2px solid #222;padding:8px;";
        if (box != null && box.sized()) {
            return css + "width:" + box.width() + "px;height:" + box.height() + "px;";
        }
        return css + "max-width:280px;";
    }

    /**
     * Sized viewport box for a short id, an element id, or {@code frame:backend}.
     * A box stored while painting is reused. Otherwise the live box is read once.
     * A cross-origin frame handle is painted from this box on the top document.
     */
    static int[] viewportBox(String handle) {
        int[] box = lookupBounds(handle);
        if (box != null) return box.clone();
        if (handle == null || handle.isBlank()) return null;
        String placed = tools.dscode.common.agentfast.TargetVerb.placementHandle(handle);
        String ranked = placed != null && !placed.isBlank() ? placed : handle;
        HandleRef ref = parseHandle(ranked);
        if (ref.backend == null || !rememberRankedBox(ranked, ref.backend)) return null;
        box = lookupBounds(handle);
        if (box == null) box = lookupBounds(ranked);
        return box == null ? null : box.clone();
    }

    private static int[] lookupBounds(String handle) {
        if (handle == null || handle.isBlank()) return null;
        int[] box = sized(BOUNDS.get(handle));
        if (box != null) return box;
        box = sized(tools.dscode.common.agentfast.TargetVerb.boxForHandle(handle));
        if (box != null) return box;
        String placed = tools.dscode.common.agentfast.TargetVerb.placementHandle(handle);
        if (placed == null || placed.isBlank() || placed.equals(handle)) return null;
        box = sized(BOUNDS.get(placed));
        if (box != null) return box;
        return sized(tools.dscode.common.agentfast.TargetVerb.boxForHandle(placed));
    }

    private static int[] sized(int[] box) {
        if (box == null || box.length < 4 || box[2] < 0 || box[3] < 0) return null;
        return box;
    }

    private static void drainLiveInputs(Path project, String runId) throws IOException {
        flushPushed(project, runId);
        for (Managed managed : MANAGED) {
            if (!(managed.driver instanceof JavascriptExecutor)) continue;
            try {
                drainManaged(project, runId, managed);
            } catch (RuntimeException failure) {
                if (!pageSocketGone(failure)) throw failure;
                CompositeLog.info("overlay", "input poll skipped " + failure.getMessage());
            }
        }
    }

    /** One driver. A closed page socket is skipped so a poll can still return {@code browser_lost}. */
    private static void drainManaged(Path project, String runId, Managed managed) throws IOException {
        String current = currentHandle(managed.driver);
        List<String> tabs = tabsOf(managed.driver);
        if (tabs.isEmpty()) throw new IllegalStateException("tab enumeration failed");
        for (String tab : tabs) {
            if (!tab.isBlank() && !activate(managed.driver, tab)) {
                throw new IllegalStateException("tab switch failed");
            }
            if (!resetTop(managed.driver)) throw new IllegalStateException("top document reset failed");
            drainIsolated(project, runId, managed.driver);
            drainTab(project, runId, managed.driver);
            for (String frame : framesFor(managed.driver, tab)) {
                if (!enterFrame(managed.driver, frame)) throw new IllegalStateException("frame entry failed");
                drainTab(project, runId, managed.driver);
            }
        }
        if (current != null && !current.isBlank() && !activate(managed.driver, current)) {
            throw new IllegalStateException("tab restore failed");
        }
    }

    /** The page socket is gone. The message or a cause names the closed connection. */
    private static boolean pageSocketGone(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("cdp connection is closed")
                    || message.contains("cdp connection closed")
                    || message.contains("no live browser")
                    || message.contains("tab enumeration failed")
                    || message.contains("input poll")
                    || message.contains("tab switch failed")
                    || message.contains("tab restore failed")
                    || message.contains("top document reset failed")
                    || message.contains("frame entry failed"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * On a CDP or BiDi session the surface helper runs in the {@code pkb-surface} isolated world, and
     * its listeners queue events in that world's own {@code window}. Those are drained in the same
     * world; the main-world queue is read by {@link #drainTab}.
     */
    private static void drainIsolated(Path project, String runId, WebDriver web) throws IOException {
        BrowserFacade facade;
        try {
            facade = directPage(web);
        } catch (RuntimeException failure) {
            return;
        }
        if (facade == null) return;
        Object value;
        try {
            value = facade.evaluateIsolated("pkb-surface", DRAIN_ISOLATED, "drain");
        } catch (RuntimeException failure) {
            CompositeLog.info("overlay", "isolated input poll skipped " + failure.getMessage());
            return;
        }
        if (!(value instanceof String text) || text.isBlank()) return;
        for (Object item : AgentStore.JSON.readValue(text, List.class)) {
            if (item instanceof Map<?, ?> map) {
                Map<String, String> event = new LinkedHashMap<>();
                map.forEach((key, raw) -> {
                    if (key != null && raw != null) event.put(String.valueOf(key), String.valueOf(raw));
                });
                recordInput(project, runId, event);
            }
        }
    }

    private static final String DRAIN_ISOLATED =
            "function(reason){ var e = window.__pkbEvents || []; window.__pkbEvents = []; return JSON.stringify(e); }";

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
            if (pageSocketGone(failure)) return;
            throw failure;
        }
        if (!(value instanceof List<?> list)) {
            CompositeLog.info("overlay", "input poll did not return events");
            throw new IllegalStateException("input poll did not return events");
        }
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
            cdp(target, "DOM.enable", Map.of());
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

    private static volatile String lastHideDetail = "none";

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
        for (FrameOwner owner : FRAME_LIST) {
            if (owner.driver != web || owner.frameId == null || owner.frameId.isBlank()) continue;
            if (!wanted.isBlank() && !wanted.equals(owner.tab)) continue;
            frames.add(owner.frameId);
        }
        addLiveChildFrames(web, frames);
        if (cdpCapable(web)) frames.retainAll(liveFrameIds(web));
        return new ArrayList<>(frames);
    }

    private static void addLiveChildFrames(WebDriver web, java.util.Set<String> frames) {
        if (!cdpCapable(web)) return;
        Object tree;
        try {
            tree = cdp(web, "Page.getFrameTree", Map.of());
        } catch (RuntimeException failure) {
            CompositeLog.info("window", "frame enumeration failed " + failure.getMessage());
            throw new IllegalStateException("frame enumeration failed", failure);
        }
        if (!(tree instanceof Map<?, ?> root)) {
            CompositeLog.info("window", "frame enumeration failed");
            throw new IllegalStateException("frame enumeration failed");
        }
        if (!(root.get("frameTree") instanceof Map<?, ?>)) {
            CompositeLog.info("window", "frame enumeration failed");
            throw new IllegalStateException("frame enumeration failed");
        }
        collectChildFrameIds(root.get("frameTree"), frames, true);
    }

    /** Every frame id in the current tree, including the root. Empty when this driver has no CDP. */
    private static Set<String> liveFrameIds(WebDriver web) {
        if (!cdpCapable(web)) return Set.of();
        Object tree;
        try {
            tree = cdp(web, "Page.getFrameTree", Map.of());
        } catch (RuntimeException failure) {
            CompositeLog.info("window", "frame enumeration failed " + failure.getMessage());
            throw new IllegalStateException("frame enumeration failed", failure);
        }
        if (!(tree instanceof Map<?, ?> root) || !(root.get("frameTree") instanceof Map<?, ?>)) {
            CompositeLog.info("window", "frame enumeration failed");
            throw new IllegalStateException("frame enumeration failed");
        }
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        collectFrameIds(root.get("frameTree"), ids);
        return ids;
    }

    private static void collectFrameIds(Object node, java.util.Set<String> ids) {
        if (!(node instanceof Map<?, ?> map)) {
            CompositeLog.info("window", "frame enumeration failed");
            throw new IllegalStateException("frame enumeration failed");
        }
        Object frame = map.get("frame");
        String id = frame instanceof Map<?, ?> frameMap && frameMap.get("id") != null
                ? String.valueOf(frameMap.get("id")).trim()
                : "";
        if (!id.isBlank() && !"null".equals(id)) ids.add(id);
        Object children = map.get("childFrames");
        if (children instanceof List<?> list) {
            for (Object child : list) collectFrameIds(child, ids);
        } else if (children != null) {
            CompositeLog.info("window", "frame enumeration failed");
            throw new IllegalStateException("frame enumeration failed");
        }
    }

    private static void collectChildFrameIds(Object node, java.util.Set<String> frames, boolean root) {
        if (!(node instanceof Map<?, ?> map)) {
            CompositeLog.info("window", "frame enumeration failed");
            throw new IllegalStateException("frame enumeration failed");
        }
        if (!root) {
            Object frame = map.get("frame");
            String id = frame instanceof Map<?, ?> frameMap && frameMap.get("id") != null
                    ? String.valueOf(frameMap.get("id")).trim()
                    : "";
            if (!id.isBlank() && !"null".equals(id)) frames.add(id);
        }
        Object children = map.get("childFrames");
        if (children instanceof List<?> list) {
            for (Object child : list) collectChildFrameIds(child, frames, false);
        } else if (children != null) {
            CompositeLog.info("window", "frame enumeration failed");
            throw new IllegalStateException("frame enumeration failed");
        }
    }

    private static boolean resetTop(WebDriver web) {
        if (web == null) return false;
        try {
            web.switchTo().defaultContent();
            return true;
        } catch (RuntimeException failure) {
            CompositeLog.info("window", "top document reset failed " + failure.getMessage());
            return false;
        }
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
                if (absent(managed.driver, null)) continue;
                List<String> tabs = tabsOf(managed.driver);
                if (tabs.isEmpty()) throw new IllegalStateException("tab enumeration failed");
                for (String tab : tabs) sites.add(new HighlightSite("", managed.driver, tab));
            }
        }
        Hide worst = Hide.SKIPPED;
        Set<String> seen = new HashSet<>();
        for (HighlightSite site : sites) {
            String key = System.identityHashCode(site.driver) + "|" + site.tab;
            if (!seen.add(key)) continue;
            if (absent(site.driver, site.tab)) {
                CompositeLog.info("window", "hide skipped closed " + site.tab);
                continue;
            }
            String previous = currentHandle(site.driver);
            if (!site.tab.isBlank() && !activate(site.driver, site.tab)) {
                if (absent(site.driver, site.tab) || closed(lastActivateFailure.get())) {
                    CompositeLog.info("window", "hide skipped closed " + site.tab);
                    continue;
                }
                CompositeLog.info("window", "hide tab switch failed " + site.tab);
                lastHideDetail = "branch=hide-tab-switch tab=" + site.tab
                        + " handle=" + currentHandle(site.driver)
                        + " driver=" + (site.driver == null ? "null" : site.driver.getClass().getName());
                worst = Hide.FAILED;
                continue;
            }
            worst = worse(worst, hideOn(site.driver));
            if (previous != null && !previous.isBlank() && !previous.equals(site.tab) && !activate(site.driver, previous)) {
                if (absent(site.driver, previous)) {
                    CompositeLog.info("window", "hide skipped closed " + previous);
                } else {
                    CompositeLog.info("window", "hide tab restore failed " + previous);
                    lastHideDetail = "branch=hide-tab-restore tab=" + previous
                            + " site=" + site.tab
                            + " driver=" + (site.driver == null ? "null" : site.driver.getClass().getName());
                    worst = Hide.FAILED;
                }
            }
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
        if (web == null || dialogOpen(web) || absent(web, null) || !cdpCapable(web)) return Hide.SKIPPED;
        try {
            BrowserFacade facade = directPage(web);
            if (facade != null && !"cdp".equals(facade.protocol())) return Hide.SKIPPED;
            cdp(web, "DOM.enable", Map.of());
            cdp(web, "Overlay.enable", Map.of());
            cdp(web, "Overlay.hideHighlight", Map.of());
            return Hide.HIDDEN;
        } catch (RuntimeException failure) {
            if (targetGone(failure)) {
                CompositeLog.info("highlight", "Overlay.hideHighlight skipped closed target " + failure.getMessage());
                return Hide.SKIPPED;
            }
            lastHideDetail = "branch=hideOn " + failure.getClass().getName() + ": " + failure.getMessage();
            CompositeLog.info("highlight", "Overlay.hideHighlight failed " + failure.getMessage());
            return Hide.FAILED;
        }
    }

    private static IllegalStateException hideFailed() {
        return new IllegalStateException("Overlay.hideHighlight failed [" + lastHideDetail + "]");
    }

    /** A quit driver or a tab that is no longer a window handle. A null handle list is not this. */
    private static boolean absent(WebDriver web, String tab) {
        if (web == null) return true;
        try {
            java.util.Set<String> handles = web.getWindowHandles();
            if (handles == null) return false;
            return tab != null && !tab.isBlank() && !handles.contains(tab);
        } catch (RuntimeException failure) {
            return targetGone(failure);
        }
    }

    private static final ThreadLocal<RuntimeException> lastActivateFailure = new ThreadLocal<>();

    private static boolean closed(RuntimeException failure) {
        return failure != null && targetGone(failure);
    }

    private static boolean targetGone(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof org.openqa.selenium.NoSuchSessionException
                    || current instanceof org.openqa.selenium.NoSuchWindowException) {
                return true;
            }
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("invalid session")
                    || message.contains("session deleted")
                    || message.contains("no such window")
                    || message.contains("no such target")
                    || message.contains("no target with given id")
                    || message.contains("target window already closed")
                    || message.contains("inspected target navigated or closed")
                    || message.contains("session with given id not found")
                    || message.contains("no session with given id")
                    || message.contains("target closed")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static boolean rankedHandle(String handle) {
        HandleRef parsed = parseHandle(handle);
        return parsed.backend != null;
    }

    private static boolean canBridge(WebDriver web) {
        return cdpCapable(web) || direct(web) != null;
    }

    /** Viewport box for {@code frame:backend}, so a placement is not rejected before the bridge runs. */
    private static boolean rememberRankedBox(String handle, int backend) {
        WebDriver web = null;
        FrameOwner owner = frameOwner(parseHandle(handle));
        if (owner != null && owner.driver != null) web = owner.driver;
        if (web == null) web = driver;
        if (web == null && !managed().isEmpty()) web = managed().get(managed().size() - 1).driver();
        if (web == null || !canBridge(web)) return false;
        try {
            String bridge = bridgeBackend(web, backend);
            if (bridge != null && web instanceof JavascriptExecutor executor) {
                Object value = executor.executeScript(
                        "var h=arguments[0];"
                                + "var el=document.querySelector('[data-pkb-bridge=\"'+CSS.escape(h)+'\"]');"
                                + "if(!el||!el.getBoundingClientRect) return null;"
                                + "el.removeAttribute('data-pkb-bridge');"
                                + "var r=el.getBoundingClientRect();"
                                + "return {x:r.x,y:r.y,w:r.width,h:r.height};",
                        bridge
                );
                if (value instanceof Map<?, ?> map) {
                    rememberBounds(
                            handle,
                            coordinate(map.get("x")),
                            coordinate(map.get("y")),
                            coordinate(map.get("w")),
                            coordinate(map.get("h")),
                            web,
                            currentHandle(web)
                    );
                    return true;
                }
            }
            Object model = cdp(web, "DOM.getBoxModel", Map.of("backendNodeId", backend));
            double[] box = boxOf(model);
            if (box == null) {
                int[] stored = tools.dscode.common.agentfast.TargetVerb.boxForHandle(handle);
                if (stored == null) return false;
                rememberBounds(handle, stored[0], stored[1], stored[2], stored[3], web, currentHandle(web));
                return true;
            }
            rememberBounds(handle, box[0], box[1], box[2], box[3], web, currentHandle(web));
            return true;
        } catch (RuntimeException failure) {
            int[] stored = tools.dscode.common.agentfast.TargetVerb.boxForHandle(handle);
            if (stored == null || stored.length < 4 || stored[2] < 0 || stored[3] < 0) return false;
            rememberBounds(handle, stored[0], stored[1], stored[2], stored[3], web, currentHandle(web));
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static double[] boxOf(Object model) {
        if (!(model instanceof Map<?, ?> root)) return null;
        Object body = root.get("model") instanceof Map<?, ?> nested ? root.get("model") : root;
        if (!(body instanceof Map<?, ?> map)) return null;
        Object content = map.get("content");
        if (!(content instanceof List<?> quad) || quad.size() < 8) return null;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i + 1 < quad.size(); i += 2) {
            double x = coordinate(quad.get(i));
            double y = coordinate(quad.get(i + 1));
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        if (minX == Double.POSITIVE_INFINITY) return null;
        return new double[]{minX, minY, Math.max(0, maxX - minX), Math.max(0, maxY - minY)};
    }

    /** True while a JavaScript dialog is up. The page socket must not take {@code Runtime.addBinding}. */
    private static boolean dialogOpen(WebDriver web) {
        return tools.dscode.common.browseroperations.BrowserAlerts.isPresent(web);
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
        if (dialogOpen(web)) {
            throw new IllegalStateException(
                    "error: a JavaScript dialog is open.\n"
                            + "fix: accept or dismiss the dialog before a CDP call.");
        }
        BrowserFacade facade = directPage(web);
        if (facade != null) return facade.send(method, params);
        return seleniumCdp(web, method, params);
    }

    /** Selected-tab CDP. Selenium's command is already on that page; the browser socket is not. */
    private static Object seleniumCdp(WebDriver web, String method, Map<String, Object> params) {
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

    private static BrowserFacade directPage(WebDriver web) {
        BrowserFacade facade = direct(web);
        if (facade instanceof CdpAdapter adapter) {
            String target = pageTarget(web);
            if (target != null) adapter.attachPage(target);
        }
        return facade;
    }

    private static Object evaluateHelper(WebDriver web, JavascriptExecutor executor, Map<String, Object> spec) {
        if (dialogOpen(web)) {
            throw new IllegalStateException(
                    "error: a JavaScript dialog is open.\n"
                            + "fix: accept or dismiss the dialog before a page script or Runtime.addBinding.");
        }
        BrowserFacade facade = directPage(web);
        if (facade != null) {
            installSurface(web, facade);
            String json;
            try {
                json = AgentStore.JSON.writeValueAsString(spec);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            Object value = facade.evaluateIsolated("pkb-surface", helperFunction(System.identityHashCode(web)), json);
            if (runtimeMissing(value)) {
                RuntimeInstall.forget(System.identityHashCode(web));
                value = facade.evaluateIsolated("pkb-surface", helperFunction(System.identityHashCode(web)), json);
            }
            return value;
        }
        if (executor == null) {
            throw new IllegalStateException("no live browser");
        }
        BrowserLog.warnOnce(
                "page-surface-script",
                "PageSurfaces used Selenium executeScript; no CDP or BiDi session");
        int key = System.identityHashCode(web);
        if (tracksTarget(spec) && RuntimeInstall.consume(key)) {
            // Main-world fallback (D4): the runtime installs lazily, only for a placement that follows a target.
            try {
                executor.executeScript(RUNTIME);
            } catch (RuntimeException failure) {
                BrowserLog.warnOnce("page-surface-runtime", "page runtime did not install: " + failure.getMessage());
            }
        }
        Object value = executor.executeScript(LIVE_HELPER, spec);
        if (runtimeMissing(value)) {
            RuntimeInstall.forget(key);
            try {
                executor.executeScript(RUNTIME);
            } catch (RuntimeException failure) {
                BrowserLog.warnOnce("page-surface-runtime", "page runtime did not install: " + failure.getMessage());
            }
            RuntimeInstall.consume(key);
            value = executor.executeScript(LIVE_HELPER, spec);
        }
        return value;
    }

    private static boolean runtimeMissing(Object value) {
        return value != null && String.valueOf(value).contains("runtime-missing");
    }

    private static final String RUNTIME_CALL = "var a = JSON.parse(raw); var ui = window.__pkbUi;"
            + " if (!ui || typeof ui[a.m] !== 'function') return null;"
            + " var r = ui[a.m](a.a); return r === undefined || r === null ? null : JSON.stringify(r);";

    /**
     * Calls one {@code window.__pkbUi} method on the focused managed page and returns its JSON reply,
     * or null when no page is managed or the runtime is not there. {@code install} loads the runtime first.
     * CDP and BiDi sessions run it in the {@code pkb-surface} world; a Selenium-only session in the page.
     */
    static String runtimeCall(String method, Object argument, boolean install) {
        WebDriver web = driver;
        if (web == null) {
            List<Managed> all = managed();
            if (all.isEmpty()) return null;
            web = all.get(0).driver;
        }
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("m", method);
        call.put("a", argument == null ? Map.of() : argument);
        String json;
        try {
            json = AgentStore.JSON.writeValueAsString(call);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        int key = System.identityHashCode(web);
        if (dialogOpen(web)) return null;
        BrowserFacade facade = directPage(web);
        if (facade != null) {
            installSurface(web, facade);
            String function = isolatedFunction(key, install);
            Object value = facade.evaluateIsolated("pkb-surface", function, json);
            if (install && ((value == null && !function.contains("raisingLayer")) || runtimeMissing(value))) {
                RuntimeInstall.forget(key);
                function = isolatedFunction(key, true);
                value = facade.evaluateIsolated("pkb-surface", function, json);
            }
            return value == null ? null : String.valueOf(value);
        }
        if (!(web instanceof JavascriptExecutor executor)) return null;
        if (install && RuntimeInstall.consume(key)) executor.executeScript(RUNTIME);
        Object value = executor.executeScript("var raw = arguments[0];" + RUNTIME_CALL, json);
        if (install && (value == null || runtimeMissing(value))) {
            RuntimeInstall.forget(key);
            executor.executeScript(RUNTIME);
            RuntimeInstall.consume(key);
            value = executor.executeScript("var raw = arguments[0];" + RUNTIME_CALL, json);
        }
        return value == null ? null : String.valueOf(value);
    }

    /** Second call for the same driver omits {@code pkb-runtime.js}. Tests read this source. */
    static String isolatedFunction(int driverKey, boolean install) {
        return RuntimeInstall.function(driverKey, install, RUNTIME, RUNTIME_CALL);
    }

    static void resetRuntimeForTest() {
        RuntimeInstall.reset();
    }

    static WebDriver liveDriver() {
        if (driver != null) return driver;
        List<Managed> all = managed();
        return all.isEmpty() ? null : all.get(0).driver;
    }

    static String runtimeSource() {
        return RUNTIME;
    }

    /** Registers a {@code pkb-surface} preload on the focused page's protocol session; null without CDP or BiDi. */
    static String addStatePreload(String script) {
        WebDriver web = driver;
        if (web == null) {
            List<Managed> all = managed();
            if (all.isEmpty()) return null;
            web = all.get(0).driver;
        }
        BrowserFacade facade = directPage(web);
        if (facade == null) return null;
        installSurface(web, facade);
        return facade.addPreloadIn(script, "pkb-surface");
    }

    static void removeStatePreload(String identifier) {
        if (identifier == null || identifier.isBlank()) return;
        WebDriver web = driver;
        if (web == null) {
            List<Managed> all = managed();
            if (all.isEmpty()) return;
            web = all.get(0).driver;
        }
        BrowserFacade facade = directPage(web);
        if (facade != null) facade.removePreload(identifier);
    }

    static boolean hasLivePage() {
        return driver != null || !MANAGED.isEmpty();
    }

    /**
     * Moves messages typed into the in-page panel into the run chat ({@code source=human}) and marks
     * them delivered in the panel. Returns how many were stored. A page without a panel stores nothing.
     */
    static int drainPanelChat(Path project, String runId) throws IOException {
        if (!hasLivePage()) return 0;
        String raw;
        try {
            raw = runtimeCall("drainChat", null, false);
        } catch (RuntimeException failure) {
            CompositeLog.info("chat", "panel drain skipped " + failure.getMessage());
            return 0;
        }
        if (raw == null || raw.isBlank() || "null".equals(raw)) return 0;
        int stored = 0;
        java.util.List<String> pageIds = new java.util.ArrayList<>();
        for (Object item : AgentStore.JSON.readValue(raw, List.class)) {
            if (!(item instanceof Map<?, ?> map) || map.get("text") == null) continue;
            String text = String.valueOf(map.get("text"));
            if (text.isBlank()) continue;
            Object pageId = map.get("id");
            String page = pageId == null ? "" : String.valueOf(pageId);
            if (!page.isBlank() && !"null".equals(page)) pageIds.add(page);
            ChatLog.send(project, runId, null, "human", "", text, page);
            CompositeLog.info("chat", "panel human message chars=" + text.length());
            stored++;
        }
        if (!pageIds.isEmpty()) {
            ReadMarks.note(project, runId, pageIds);
            markRead(pageIds);
        }
        return stored;
    }

    /** Tells the page runtime that record mode is on or off, and shows the live script. */
    public static void armRecording(Map<String, Object> spec) {
        Map<String, Object> body = spec == null ? Map.of() : spec;
        try {
            runtimeCall("record", body, false);
        } catch (RuntimeException ignored) {
            // No managed page. Record mode still lives in the state file.
        }
        try {
            armRecordingFrames(body);
        } catch (RuntimeException ignored) {
            // A child frame that cannot take the runtime still records in the top document.
        }
    }

    /** Installs sensing in child frames, including an out-of-process frame the top document cannot read. */
    private static void armRecordingFrames(Map<String, Object> spec) {
        WebDriver web = liveDriver();
        if (web == null || !cdpCapable(web)) return;
        boolean on = Boolean.TRUE.equals(spec.get("on")) || "true".equalsIgnoreCase(String.valueOf(spec.get("on")));
        java.util.Set<String> ids;
        try {
            ids = liveFrameIds(web);
        } catch (RuntimeException failure) {
            CompositeLog.info("record", "frame arm skipped " + failure.getMessage());
            return;
        }
        boolean root = true;
        for (String id : ids) {
            if (root) {
                root = false;
                continue;
            }
            try {
                String probe = evalFrame(web, id, "window.__pkbUi&&window.__pkbUi.armSense?'yes':'no'");
                if (!"yes".equals(probe)) {
                    evalFrame(web, id, RUNTIME + "\n;'installed'");
                }
                String flag = on ? "true" : "false";
                evalFrame(web, id, "window.__pkbRecord=" + flag + ";if(window.__pkbRecord&&window.__pkbUi&&window.__pkbUi.armSense)window.__pkbUi.armSense();'ok'");
            } catch (RuntimeException failure) {
                CompositeLog.info("record", "frame " + id + " " + failure.getMessage());
            }
        }
    }

    private static String evalFrame(WebDriver web, String frameId, String expression) {
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("frameId", frameId);
        world.put("worldName", "pkb-surface");
        world.put("grantUniveralAccess", true);
        int context = contextId(cdp(web, "Page.createIsolatedWorld", world));
        if (context <= 0) throw new IllegalStateException("frame world failed");
        Map<String, Object> eval = new LinkedHashMap<>();
        eval.put("expression", expression);
        eval.put("contextId", context);
        eval.put("returnByValue", true);
        Object result = cdp(web, "Runtime.evaluate", eval);
        if (!(result instanceof Map<?, ?> map)) return "";
        Object inner = map.get("result");
        if (inner instanceof Map<?, ?> value && value.get("value") != null) return String.valueOf(value.get("value"));
        return "";
    }

    private static int contextId(Object created) {
        if (!(created instanceof Map<?, ?> map)) return 0;
        Object id = map.get("executionContextId");
        if (id instanceof Number number) return number.intValue();
        if (id == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(id));
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    /** Page bubble ids the panel already shows as Read. Empty when no page is open. */
    static java.util.List<String> readIds() {
        if (!hasLivePage()) return java.util.List.of();
        try {
            String raw = runtimeCall("readIds", null, false);
            if (raw == null || raw.isBlank() || "null".equals(raw)) return java.util.List.of();
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (Object item : AgentStore.JSON.readValue(raw, java.util.List.class)) {
                if (item != null && !String.valueOf(item).isBlank()) ids.add(String.valueOf(item));
            }
            return ids;
        } catch (RuntimeException | java.io.IOException failure) {
            return java.util.List.of();
        }
    }

    /** Marks page controls the agent has now read. A missing page is a no-op. */
    static void markRead(java.util.Collection<String> ids) {
        if (ids == null || ids.isEmpty() || !hasLivePage()) return;
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String id : ids) if (id != null && !id.isBlank()) list.add(id);
        if (list.isEmpty()) return;
        try {
            runtimeCall("markRead", list, false);
        } catch (RuntimeException failure) {
            CompositeLog.info("ui", "mark read skipped " + failure.getMessage());
        }
    }

    /** Shows an agent chat message in the in-page panel when one is open. Never installs the runtime. */
    /** True when the page runtime took the row (drawn now, or already drawn under that id). */
    static boolean pushChat(Map<String, Object> message) {
        if (!hasLivePage()) return false;
        try {
            return runtimeCall("chat", List.of(message), false) != null;
        } catch (RuntimeException failure) {
            CompositeLog.info("chat", "panel push skipped " + failure.getMessage());
            return false;
        }
    }

    private static boolean tracksTarget(Map<String, Object> spec) {
        Object kind = spec.get("kind");
        boolean anchored = present(spec.get("handle")) || present(spec.get("anchor")) || present(spec.get("bridge"));
        return "append".equals(spec.get("op")) && kind != null && !"html".equals(kind) && anchored;
    }

    private static boolean present(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static void installSurface(WebDriver web, BrowserFacade facade) {
        if (dialogOpen(web)) return;
        if (!SURFACE_PROTOCOL.add(System.identityHashCode(web))) return;
        // Same world as the helper, so one set of listeners queues events where drainIsolated reads them.
        facade.addPreloadIn(SurfaceAssets.js("html"), "pkb-surface");
        facade.addPreloadIn(RUNTIME, "pkb-surface");
        if (facade instanceof CdpAdapter cdp) {
            cdp.addBinding("pkbSurface", (name, payload) -> {
                Map<String, String> event = bindingEvent(name, payload);
                if (event != null && !event.isEmpty()) {
                    PUSHED.add(event);
                    tools.dscode.common.agentfast.PushWake.signal();
                }
            }, "pkb-surface");
        } else {
            facade.addBinding("pkbSurface", (name, payload) -> {
                Map<String, String> event = bindingEvent(name, payload);
                if (event != null && !event.isEmpty()) {
                    PUSHED.add(event);
                    tools.dscode.common.agentfast.PushWake.signal();
                }
            });
        }
    }

    private static Map<String, String> bindingEvent(String name, Object payload) {
        Map<String, String> event = new LinkedHashMap<>();
        Object parsed = payload;
        if (payload instanceof String text && text.trim().startsWith("{")) {
            try {
                parsed = AgentStore.JSON.readValue(text, Map.class);
            } catch (RuntimeException | java.io.IOException ignored) {
                parsed = payload;
            }
        }
        if (parsed instanceof Map<?, ?> map) {
            map.forEach((key, raw) -> {
                if (key != null && raw != null) event.put(String.valueOf(key), String.valueOf(raw));
            });
        } else if (payload != null) {
            event.put("value", String.valueOf(payload));
        }
        event.putIfAbsent("type", "binding");
        event.put("binding", name == null ? "pkbSurface" : name);
        if (tools.dscode.common.agentfast.PushChannel.quietKeystroke(
                event, tools.dscode.common.agentfast.PushChannel.project())) return Map.of();
        tools.dscode.common.agentfast.PushChannel.accept(event);
        tools.dscode.common.recording.InteractionLog.mirrorStream(
                tools.dscode.common.agentfast.PushChannel.project(), event);
        return event;
    }

    private static void flushPushed(Path project, String runId) throws IOException {
        Map<String, String> event;
        while ((event = PUSHED.poll()) != null) recordInput(project, runId, event);
    }

    static List<Map<String, String>> pushedForTests() {
        return List.copyOf(PUSHED);
    }

    private static BrowserFacade direct(WebDriver web) {
        if (web == null || dialogOpen(web)) return null;
        int key = System.identityHashCode(web);
        BrowserFacade cached = FACADES.get(key);
        if (cached != null) {
            String protocol = cached.protocol();
            return "cdp".equals(protocol) || "bidi".equals(protocol) ? cached : null;
        }
        BrowserFacade opened;
        try {
            opened = ProtocolSelector.forDriver(web, ProtocolSelector.Preference.DIRECT);
        } catch (RuntimeException failure) {
            BrowserLog.warnOnce("page-surface-direct", "direct protocol failed: " + failure.getMessage());
            return null;
        }
        FACADES.put(key, opened);
        String protocol = opened.protocol();
        return "cdp".equals(protocol) || "bidi".equals(protocol) ? opened : null;
    }

    private static String helperFunction(int driverKey) {
        int semi = LIVE_HELPER.indexOf(';');
        String body = LIVE_HELPER;
        if (semi > 0 && LIVE_HELPER.substring(0, semi).contains("arguments[0]")) {
            body = LIVE_HELPER.substring(semi + 1);
        }
        // The runtime is idempotent. Send the bytes once per driver, then omit them.
        String runtime = RuntimeInstall.consume(driverKey) ? RUNTIME + "\n;" : "";
        return "function(raw){ var spec = JSON.parse(raw);\n" + runtime + body + "}";
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

    /** A live {@code data-pkb-handle} or id is a resolved target. The geometry cache is filled from that box. */
    private static boolean locateLiveHandle(String handle) {
        WebDriver web = driver;
        if (web == null && !managed().isEmpty()) web = managed().get(managed().size() - 1).driver();
        if (!(web instanceof JavascriptExecutor executor)) return false;
        Object value;
        try {
            value = executor.executeScript(
                    "var h = arguments[0];"
                            + " var el = document.querySelector('[data-pkb-handle=\"' + CSS.escape(h) + '\"]');"
                            + " if (!el) el = document.getElementById(h);"
                            + " if (!el || !el.getBoundingClientRect) return null;"
                            + " var r = el.getBoundingClientRect();"
                            + " return {x:r.x, y:r.y, w:r.width, h:r.height};",
                    handle
            );
        } catch (RuntimeException failure) {
            return false;
        }
        if (!(value instanceof Map<?, ?> map)) return false;
        rememberBounds(
                handle,
                coordinate(map.get("x")),
                coordinate(map.get("y")),
                coordinate(map.get("w")),
                coordinate(map.get("h")),
                web,
                currentHandle(web)
        );
        return resolved(handle);
    }

    private static double coordinate(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return 0;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    private static boolean resolved(String handle) {
        if (handle == null || handle.isBlank()) return false;
        if (GEOMETRY.containsKey(handle) || BOUNDS.containsKey(handle)) return true;
        HandleRef ref = parseHandle(handle);
        return ref.frameId != null && frameOwner(HandleRef.parse(handle)) != null;
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
            if (tabs.isEmpty()) {
                return new LiveResult(false, "tab enumeration failed", null, null, 0);
            }
            for (String tab : tabs) {
                if (!tab.isBlank() && !activate(managed.driver, tab)) {
                    last = new LiveResult(false, "tab switch failed", null, null, 0);
                    return last;
                }
                if (!resetTop(managed.driver)) {
                    return new LiveResult(false, "top document reset failed", null, null, 0);
                }
                LiveResult live = runLive(managed.driver, op, handle, where, css, html, kind, sentence, id, measure, x, y);
                List<String> frames;
                try {
                    frames = framesFor(managed.driver, tab);
                } catch (RuntimeException failure) {
                    return new LiveResult(false, failure.getMessage() == null ? "frame enumeration failed" : failure.getMessage(), null, null, 0);
                }
                if ("remove".equals(op)) {
                    if (live.removed() > 0) return live;
                    if (!live.ok() && live.error() != null && !"unknown id".equals(live.error())) return live;
                    for (String frame : frames) {
                        if (!enterFrame(managed.driver, frame)) {
                            return new LiveResult(false, "frame entry failed", null, null, 0);
                        }
                        LiveResult framed = runLive(managed.driver, op, handle, where, css, html, kind, sentence, id, measure, x, y);
                        if (framed.removed() > 0) return framed;
                        if (!framed.ok() && framed.error() != null && !"unknown id".equals(framed.error())) return framed;
                        last = framed;
                    }
                    last = live.removed() > 0 ? live : last;
                    continue;
                }
                if (!live.ok()) return live;
                for (String frame : frames) {
                    if (!enterFrame(managed.driver, frame)) {
                        return new LiveResult(false, "frame entry failed", null, null, 0);
                    }
                    LiveResult framed = runLive(managed.driver, op, handle, where, css, html, kind, sentence, id, measure, x, y);
                    if (!framed.ok()) return framed;
                }
                last = live;
                if (!"cleanup".equals(op) && !"unhighlight".equals(op)) return live;
            }
        }
        return last;
    }

    private static LiveResult runAtSite(SurfaceSite site, String op, String handle, String where, String css, String html, String kind, String sentence, String id, boolean measure, Integer x, Integer y) {
        if (site.tab != null && !site.tab.isBlank() && !activate(site.driver, site.tab)) {
            return new LiveResult(false, "tab switch failed", null, null, 0);
        }
        if (site.frameId != null && !site.frameId.isBlank()) {
            if (!enterFrame(site.driver, site.frameId)) {
                return new LiveResult(false, "frame entry failed", null, null, 0);
            }
        } else if (!resetTop(site.driver)) {
            return new LiveResult(false, "top document reset failed", null, null, 0);
        }
        return runLive(site.driver, op, handle, where, css, html, kind, sentence, id, measure, x, y);
    }

    private static final ThreadLocal<String> ENTRY_FAILURE = new ThreadLocal<>();

    private static WebDriver resolveDriver(String handle) {
        ENTRY_FAILURE.remove();
        HandleRef ref = HandleRef.parse(handle);
        if (ref.frameId != null || ref.browserId != null) {
            // A stored viewport box is painted on the top document. Cross-origin frame
            // ids are not Selenium frames, and the node's backend id is not in the parent.
            if (lookupBounds(handle) != null) {
                if (driver != null && focusedTab != null && !focusedTab.isBlank() && !activate(driver, focusedTab)) {
                    ENTRY_FAILURE.set("tab switch failed");
                    return null;
                }
                if (driver == null && !managed().isEmpty()) return managed().get(managed().size() - 1).driver();
                return driver;
            }
            FrameOwner owner = frameOwner(ref);
            if (owner == null) {
                ENTRY_FAILURE.set("unknown handle");
                return null;
            }
            if (!browserStillManaged(owner)) {
                ENTRY_FAILURE.set("unknown handle");
                return null;
            }
            if (!activate(owner.driver, owner.tab)) {
                ENTRY_FAILURE.set("tab switch failed");
                return null;
            }
            if (ref.frameId != null && !enterFrame(owner.driver, ref.frameId)) {
                ENTRY_FAILURE.set("frame entry failed");
                return null;
            }
            driver = owner.driver;
            focusedTab = owner.tab;
            THREAD_FOCUS.set(new Focus(owner.driver, owner.tab));
            return owner.driver;
        }
        if (driver != null && focusedTab != null && !focusedTab.isBlank() && !activate(driver, focusedTab)) {
            ENTRY_FAILURE.set("tab switch failed");
            return null;
        }
        return driver;
    }

    private static FrameOwner frameOwner(HandleRef ref) {
        if (ref == null) return null;
        HandleOwner bound = ref.raw == null ? null : HANDLE_OWNERS.get(ref.raw);
        if (bound != null) return new FrameOwner(bound.browserId, bound.driver, bound.tab, ref.frameId);
        if (ref.frameId == null) return null;
        for (FrameOwner owner : FRAME_LIST) {
            if (ref.frameId.equals(owner.frameId)) return owner;
        }
        return null;
    }

    private static String browserId(WebDriver webDriver) {
        for (Managed managed : MANAGED) {
            if (managed.driver == webDriver) return managed.id;
        }
        if (webDriver instanceof RemoteWebDriver remote && remote.getSessionId() != null) {
            return remote.getSessionId().toString();
        }
        return "";
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

    /** @return false when the ranked frame was not entered. A driver without CDP does not pretend it entered. */
    private static boolean enterFrame(WebDriver web, String frameId) {
        if (web == null || frameId == null || frameId.isBlank()) return false;
        if (!cdpCapable(web)) {
            try {
                web.switchTo().defaultContent();
                web.switchTo().frame(frameId);
                return true;
            } catch (RuntimeException failure) {
                CompositeLog.info("window", "frame enter failed " + frameId + " " + failure.getMessage());
                return false;
            }
        }
        try {
            web.switchTo().defaultContent();
            List<String> chain = frameChain(web, frameId);
            if (chain == null) {
                if ("main".equals(frameId)) return resetTop(web);
                CompositeLog.info("window", "frame enter failed " + frameId + " frame is not in the tree");
                return false;
            }
            if (chain.isEmpty()) return true;
            cdp(web, "DOM.enable", Map.of());
            cdp(web, "Runtime.enable", Map.of());
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
                    if (!restoreFrame(web, mutation)) {
                        CompositeLog.info("window", "frame bridge restore failed " + id);
                    }
                    return false;
                }
                if (!restoreFrame(web, mutation)) {
                    CompositeLog.info("window", "frame bridge restore failed " + id);
                    return false;
                }
            }
            return true;
        } catch (RuntimeException failure) {
            CompositeLog.info("window", "frame enter failed " + frameId + " " + failure.getMessage());
            return false;
        }
    }

    /** True when {@code frameId} is the root or a child in the current tree. A miss is a frame that already navigated away. */
    private static boolean frameInTree(WebDriver web, String frameId) {
        try {
            return frameChain(web, frameId) != null;
        } catch (RuntimeException failure) {
            CompositeLog.info("window", "frame tree failed " + failure.getMessage());
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

    private record FrameMutation(
            String objectId,
            String name,
            boolean changedName,
            boolean hadName,
            String previousName,
            boolean hadBridge,
            String previousBridge
    ) {}

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
            JsonNode previousName = node.get("previousName");
            return new FrameMutation(
                    objectId,
                    name,
                    node.path("changedName").asBoolean(false),
                    node.path("hadName").asBoolean(false),
                    previousName == null || previousName.isNull() ? null : previousName.asText(""),
                    node.path("hadBridge").asBoolean(false),
                    previousBridge
            );
        } catch (RuntimeException | IOException failure) {
            CompositeLog.info("window", "frame owner bridge failed " + failure.getMessage());
            return null;
        }
    }

    /** @return false when the temporary name or bridge was not put back. Callers must fail the command. */
    private static boolean restoreFrame(WebDriver web, FrameMutation mutation) {
        if (web == null || mutation == null || mutation.objectId == null || mutation.objectId.isBlank()) return false;
        try {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("changedName", mutation.changedName);
            spec.put("hadName", mutation.hadName);
            spec.put("previousName", mutation.previousName);
            spec.put("hadBridge", mutation.hadBridge);
            spec.put("previousBridge", mutation.previousBridge);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("objectId", mutation.objectId);
            call.put("functionDeclaration", FRAME_RESTORE);
            call.put("arguments", List.of(Map.of("value", spec)));
            call.put("returnByValue", true);
            Object returned = cdp(web, "Runtime.callFunctionOn", call);
            if (!"true".equals(textValue(returned))) {
                CompositeLog.info("window", "frame bridge restore failed");
                return false;
            }
            return true;
        } catch (RuntimeException failure) {
            CompositeLog.info("window", "frame bridge restore failed " + failure.getMessage());
            return false;
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
        JavascriptExecutor executor = current instanceof JavascriptExecutor js ? js : null;
        if (executor == null && direct(current) == null) {
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
        if (ref.backend != null && canBridge(current)) {
            String bridge = bridgeBackend(current, ref.backend);
            if (bridge != null) spec.put("bridge", bridge);
        }
        if (x != null) spec.put("x", x);
        if (y != null) spec.put("y", y);
        int[] placedSize = PLACED_SIZE.get();
        if (placedSize != null) {
            spec.put("width", placedSize[0]);
            spec.put("height", placedSize[1]);
        }
        Map<String, String> extra = VISUAL_EXTRA.get();
        if (extra != null) {
            if (extra.get("zoom") != null) spec.put("zoom", extra.get("zoom"));
            if (extra.get("dim") != null) spec.put("dim", extra.get("dim"));
            if (extra.get("number") != null) spec.put("number", extra.get("number"));
            if (extra.get("direction") != null) spec.put("direction", extra.get("direction"));
            if (extra.get("at") != null) spec.put("at", extra.get("at"));
            if (extra.get("theme") != null) spec.put("theme", extra.get("theme"));
            if (extra.get("block") != null) spec.put("block", extra.get("block"));
            if (extra.get("anchor") != null) spec.put("anchor", extra.get("anchor"));
        }
        try {
            Object value = evaluateHelper(current, executor, spec);
            if (!(value instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("ok"))) {
                String error = value instanceof Map<?, ?> failed && failed.get("error") != null
                        ? String.valueOf(failed.get("error"))
                        : "helper did not confirm " + op;
                CompositeLog.info("html", "live helper failed " + error);
                return new LiveResult(false, error, null, null, 0);
            }
            Integer liveX = map.get("x") instanceof Number number ? number.intValue() : null;
            Integer liveY = map.get("y") instanceof Number numberY ? numberY.intValue() : null;
            int removed = map.get("removed") instanceof Number removedNumber ? removedNumber.intValue() : 0;
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

    static String purposeTokens(String kind) {
        if (kind == null) return "placement";
        return switch (kind) {
            case "overlay", "label", "html", "step", "instructions", "diagram", "callout" -> "placement callout";
            case "panel" -> "panel";
            case "guide" -> "guide";
            case "editor" -> "editor";
            case "recorder", "record" -> "recorder";
            case "layer" -> "layer";
            default -> "placement";
        };
    }

    /** Prefixes an assigned id. An id that already starts with {@code pkb-} is left as it is. */
    static String prefixAssignedIds(String html) {
        if (html == null || html.isEmpty()) return html == null ? "" : html;
        String prefixed = html.replaceAll(
                "(?i)(\\sid\\s*=\\s*)([\"'])(?!pkb-)([^\"']+)\\2",
                "$1$2pkb-$3$2");
        return prefixed.replaceAll(
                "(?i)(\\sdata-pkb-id\\s*=\\s*)([\"'])(?!pkb-)([^\"']+)\\2",
                "$1$2pkb-$3$2");
    }

    static String labelElements(String html, String kind, String sentence) {
        if (html == null || html.isBlank()) return html == null ? "" : html;
        String purpose = purposeTokens(kind);
        String stamped = html.replaceAll(
                "(?i)<(?![/!])([a-zA-Z][\\w:-]*)\\b(?![^>]*data-pkb-agent)",
                "<$1 data-pkb-agent=\"agent_injected\" data-pkb-meta=\"true\" data-pkb-purpose=\"" + purpose
                        + "\" data-pkb-kind=\"" + kind
                        + "\" data-pkb-sentence=\"" + sentence + "\""
        );
        stamped = stamped.replaceAll(
                "(?i)<(?![/!])([a-zA-Z][\\w:-]*)\\b(?![^>]*data-pkb-meta)",
                "<$1 data-pkb-meta=\"true\" data-pkb-purpose=\"" + purpose + "\""
        );
        return prefixAssignedIds(stamped);
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

    private static int[] geometryBox(String geometryJson) {
        if (geometryJson == null || geometryJson.isBlank()) return null;
        try {
            JsonNode node = AgentStore.JSON.readTree(geometryJson);
            JsonNode box = node.path("visual_viewport_relative");
            if (!box.isObject() && node.path("geometry").isObject()) {
                box = node.path("geometry").path("visual_viewport_relative");
            }
            if (!box.isObject() || !box.has("x") || !box.has("y")) return null;
            int width = box.has("width") ? box.path("width").asInt() : -1;
            int height = box.has("height") ? box.path("height").asInt() : -1;
            return new int[]{box.path("x").asInt(), box.path("y").asInt(), width, height};
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

    private record Placement(String sha, String summary, String kind) {
    }

    private static final Map<String, Placement> PLACED = new LinkedHashMap<>();

    static void clearPlacementMemory() {
        PLACED.clear();
    }

    private static void rememberPlacement(Path page, Path ledger, String id, String sha, String summary, String kind) throws IOException {
        PLACED.put(id, new Placement(sha, summary, kind == null ? "" : kind));
        writePlacement(page == null ? null : placeFile(page), id, sha, summary, kind);
        writePlacement(ledger == null ? null : ledger.resolve("placements.json"), id, sha, summary, kind);
    }

    private static Placement placement(Path page, Path ledger, String id) throws IOException {
        Placement memory = PLACED.get(id);
        if (memory != null) return memory;
        Placement fromPage = readPlacement(page == null ? null : placeFile(page), id);
        if (fromPage != null) return fromPage;
        return readPlacement(ledger == null ? null : ledger.resolve("placements.json"), id);
    }

    /**
     * Kind recorded for this id in the id store of the page that owns it: the page file's
     * place record, or the focused live page's id store. Blank when no kind was recorded.
     */
    private static String placedKind(Path page, Path ledger, String id) {
        try {
            Path store;
            if (page != null) {
                store = placeFile(page);
            } else {
                Selected selected = selectDestination(null);
                store = idStore(null, ledger, selected.driver(), selected.tab());
            }
            Placement placed = readPlacement(store, id);
            return placed == null ? "" : placed.kind;
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    private static Path placeFile(Path page) {
        return page.resolveSibling(page.getFileName() + ".place.json");
    }

    private static final Map<String, Path> LIVE_IDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> PAGE_TARGETS = new java.util.concurrent.ConcurrentHashMap<>();

    private record Selected(WebDriver driver, String tab) {
    }

    /** Choose the page the handle names before an id is taken from that page's counter. Does not enter the frame. */
    private static Selected selectDestination(String handle) {
        if (handle != null && !handle.isBlank()) {
            HandleRef ref = HandleRef.parse(handle);
            if (ref.frameId != null || ref.browserId != null) {
                FrameOwner owner = frameOwner(ref);
                if (owner != null && browserStillManaged(owner)) {
                    String tab = owner.tab == null || owner.tab.isBlank() ? text(currentHandle(owner.driver)) : owner.tab;
                    return new Selected(owner.driver, tab);
                }
                if (lookupBounds(handle) == null) throw new IllegalStateException("unknown handle");
            }
        }
        String tab = focusedTab == null ? "" : focusedTab;
        if (tab.isBlank()) {
            String current = currentHandle(driver);
            tab = current == null ? "" : current;
        }
        return new Selected(driver, tab);
    }

    private static Path idStore(Path page, Path ledger) {
        return idStore(page, ledger, driver, focusedTab == null ? "" : focusedTab);
    }

    private static Path idStore(Path page, Path ledger, WebDriver web, String tab) {
        if (page != null) return placeFile(page);
        if (!MANAGED.isEmpty()) {
            String key = livePageKey(web, tab);
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
        return livePageKey(driver, focusedTab == null ? "" : focusedTab);
    }

    private static String livePageKey(WebDriver web, String tab) {
        String useTab = tab == null ? "" : tab;
        if (web instanceof RemoteWebDriver remote) {
            String address = debuggerAddress(remote);
            if (!address.isEmpty()) {
                String target = destinationPageTarget(web, useTab);
                return address + "|" + (target == null ? "" : target) + "|" + useTab;
            }
        }
        return "local|" + System.identityHashCode(web) + "|" + useTab;
    }

    /**
     * Page target of {@code tab} itself. {@code Target.getTargetInfo} with no target
     * id is the active window, so a different Selenium handle must be selected first.
     * An unknown current window is not that tab. Switch and confirm, reuse a target
     * already remembered for this tab, or fail the allocation. Never store the active
     * window's target under a different tab, and never allocate against a blank target.
     * A discovery switch restores the thread's focused tab for this driver when the
     * current handle was unknown, so Selenium is not left on the destination.
     */
    private static String destinationPageTarget(WebDriver web, String tab) {
        String wanted = tab == null ? "" : tab;
        String current = currentHandle(web);
        if (current == null) {
            if (wanted.isBlank()) {
                CompositeLog.info("html", "destination page target is unknown");
                throw new IllegalStateException("destination page target is unknown");
            }
            return targetAfterSwitch(web, wanted, focusedTabToRestore(web));
        }
        if (wanted.isBlank() || wanted.equals(current)) {
            return confirmedOrRemembered(web, wanted.isBlank() ? current : wanted);
        }
        return targetAfterSwitch(web, wanted, current);
    }

    /**
     * Tab this thread already focused on {@code web}. Not a guessed window handle.
     * A discovery switch uses it to put Selenium back when the current handle was unknown.
     */
    private static String focusedTabToRestore(WebDriver web) {
        Focus focus = THREAD_FOCUS.get();
        if (focus != null && focus.driver == web && focus.tab != null && !focus.tab.isBlank()) return focus.tab;
        if (web != null && web == driver && focusedTab != null && !focusedTab.isBlank()) return focusedTab;
        return null;
    }

    /** The selected window is already {@code label}. A blank read is not a new page identity. */
    private static String confirmedOrRemembered(WebDriver web, String label) {
        String target = pageTarget(web);
        if (target == null || target.isBlank()) {
            String known = rememberedPageTarget(web, label);
            if (known != null) {
                CompositeLog.info("html", "destination page target reused " + label);
                return known;
            }
            CompositeLog.info("html", "destination page target is unknown " + label);
            throw new IllegalStateException("destination page target is unknown");
        }
        rememberPageTarget(web, label, target);
        return target;
    }

    /**
     * Select {@code wanted} and read its target only after the selected handle is
     * confirmed to be that tab. {@code previous} is restored when it was known,
     * including the thread's focused tab when the current handle could not be read.
     */
    private static String targetAfterSwitch(WebDriver web, String wanted, String previous) {
        String known = rememberedPageTarget(web, wanted);
        try {
            web.switchTo().window(wanted);
            String now = currentHandle(web);
            if (now == null || !wanted.equals(now)) {
                if (known != null) {
                    CompositeLog.info("window", "destination tab was not confirmed " + wanted);
                    return known;
                }
                CompositeLog.info("html", "destination page target is unknown " + wanted);
                throw new IllegalStateException("destination page target is unknown");
            }
            String target = pageTarget(web);
            if (target == null || target.isBlank()) {
                if (known != null) return known;
                CompositeLog.info("html", "destination page target is unknown " + wanted);
                throw new IllegalStateException("destination page target is unknown");
            }
            rememberPageTarget(web, wanted, target);
            return target;
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalStateException && failure.getMessage() != null
                    && failure.getMessage().contains("destination page target")) {
                throw failure;
            }
            if (known != null) {
                CompositeLog.info("window", "destination tab switch failed " + wanted + " " + failure.getMessage());
                return known;
            }
            CompositeLog.info("window", "destination tab switch failed " + wanted + " " + failure.getMessage());
            throw new IllegalStateException("destination page target is unknown", failure);
        } finally {
            String now = currentHandle(web);
            if (previous != null && now != null && !previous.equals(now)) {
                try {
                    web.switchTo().window(previous);
                } catch (RuntimeException failure) {
                    CompositeLog.info("window", "tab restore failed " + previous + " " + failure.getMessage());
                }
            }
        }
    }

    static String rememberedTargetForTests(WebDriver web, String tab) {
        return rememberedPageTarget(web, tab);
    }

    private static void rememberPageTarget(WebDriver web, String tab, String target) {
        if (web == null || tab == null || tab.isBlank() || target == null || target.isBlank()) return;
        PAGE_TARGETS.put(System.identityHashCode(web) + "|" + tab, target);
    }

    private static String rememberedPageTarget(WebDriver web, String tab) {
        if (web == null || tab == null || tab.isBlank()) return null;
        return PAGE_TARGETS.get(System.identityHashCode(web) + "|" + tab);
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

    /**
     * {@link CompositeRuntime#cdpAddress}: {@code se:cdp}, then Chromium
     * {@code debuggerAddress}. Empty when the driver published neither.
     */
    private static String debuggerAddress(RemoteWebDriver remote) {
        if (remote == null) return "";
        String address = CompositeRuntime.cdpAddress(remote.getCapabilities());
        return address == null ? "" : address;
    }

    static String allocateSurfaceId(Path file) throws IOException {
        return allocateId(file);
    }

    static void recordSurface(Path file, String id, String sha, String summary) throws IOException {
        writePlacement(file, id, sha, summary, null);
    }

    static String sentence(String kind) {
        return SurfaceAssets.sentence(kind);
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

    private static void writePlacement(Path file, String id, String sha, String summary, String kind) throws IOException {
        if (file == null) return;
        withSurfaceLock(file, () -> {
            ObjectNode root = readObject(file);
            ObjectNode row = root.putObject(id);
            row.put("sha256", sha);
            row.put("summary", summary);
            if (kind != null && !kind.isBlank()) row.put("kind", kind);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            AgentStore.JSON.writeValue(file.toFile(), root);
        });
    }

    private static Placement readPlacement(Path file, String id) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return null;
        JsonNode row = AgentStore.JSON.readTree(file.toFile()).path(id);
        if (!row.isObject()) return null;
        return new Placement(row.path("sha256").asText(""), row.path("summary").asText(""), row.path("kind").asText(""));
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
        java.util.Set<String> handles;
        try {
            handles = web.getWindowHandles();
        } catch (RuntimeException failure) {
            CompositeLog.info("window", "tab enumeration failed " + failure.getMessage());
            throw new IllegalStateException("tab enumeration failed", failure);
        }
        if (handles == null) {
            CompositeLog.info("window", "tab enumeration failed");
            throw new IllegalStateException("tab enumeration failed");
        }
        return new ArrayList<>(handles);
    }

    static boolean activate(WebDriver web, String tab) {
        if (web == null || tab == null || tab.isBlank()) return true;
        lastActivateFailure.remove();
        Focus priorFocus = THREAD_FOCUS.get();
        WebDriver priorDriver = driver;
        String priorTab = focusedTab;
        String priorWindow = null;
        try {
            priorWindow = web.getWindowHandle();
        } catch (RuntimeException ignored) {
            priorWindow = null;
        }
        try {
            if (priorWindow == null || !tab.equals(priorWindow)) web.switchTo().window(tab);
            String target = targetForSelectedTab(web, tab);
            if (remotePage(web) && (target == null || target.isBlank())) {
                CompositeLog.info("window", "page target unknown " + tab);
                restoreActivation(web, priorWindow, priorFocus, priorDriver, priorTab);
                return false;
            }
            if (!CompositeRuntime.focusPage(web, target)) {
                CompositeLog.info("window", "page session failed " + tab);
                restoreActivation(web, priorWindow, priorFocus, priorDriver, priorTab);
                return false;
            }
            focusedTab = tab;
            driver = web;
            THREAD_FOCUS.set(new Focus(web, tab));
            rememberPageTarget(web, tab, target);
            return true;
        } catch (RuntimeException failure) {
            lastActivateFailure.set(failure);
            CompositeLog.info("window", "activate failed " + tab + " " + failure.getMessage());
            restoreActivation(web, priorWindow, priorFocus, priorDriver, priorTab);
            return false;
        }
    }

    private static void restoreActivation(WebDriver web, String priorWindow, Focus priorFocus, WebDriver priorDriver, String priorTab) {
        if (web != null && priorWindow != null && !priorWindow.isBlank()) {
            try {
                if (!priorWindow.equals(web.getWindowHandle())) web.switchTo().window(priorWindow);
            } catch (RuntimeException failure) {
                CompositeLog.info("window", "tab restore failed " + priorWindow + " " + failure.getMessage());
            }
        }
        driver = priorDriver;
        focusedTab = priorTab;
        if (priorFocus == null) THREAD_FOCUS.remove();
        else THREAD_FOCUS.set(priorFocus);
    }

    /**
     * A published debugger address ({@code se:cdp} or Chromium {@code debuggerAddress}
     * on {@code goog:chromeOptions} / {@code ms:edgeOptions}) can hold a browser
     * session that is not the requested tab.
     */
    private static boolean remotePage(WebDriver web) {
        return web instanceof RemoteWebDriver remote && !debuggerAddress(remote).isEmpty();
    }

    /**
     * Target of {@code tab} only when Selenium is on that tab, or a target already
     * confirmed for it. Null on a remote page means the caller must fail. It is not
     * permission to focus the browser session under this tab's name.
     */
    private static String targetForSelectedTab(WebDriver web, String tab) {
        if (!remotePage(web)) return pageTarget(web);
        if (tab == null || tab.isBlank()) return null;
        String current = currentHandle(web);
        if (tab.equals(current)) {
            String live = pageTarget(web);
            if (live != null && !live.isBlank()) return live;
        }
        return rememberedPageTarget(web, tab);
    }

    /** The closed tab's engine session must not stay current when the survivor could not be focused. */
    static void releaseClosedTab(WebDriver web, String closed) {
        Focus focus = THREAD_FOCUS.get();
        if (focus != null && focus.driver == web && closed != null && closed.equals(focus.tab)) THREAD_FOCUS.remove();
        if (web == driver && closed != null && closed.equals(focusedTab)) focusedTab = null;
        if (remotePage(web)) CompositeRuntime.focusPage(web, null);
        CompositeLog.info("window", "closed tab session released " + text(closed));
    }

    static String pageTarget(WebDriver web) {
        if (!cdpCapable(web)) return null;
        try {
            Object info = seleniumCdp(web, "Target.getTargetInfo", Map.of());
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

    /** Ranked find-interface id: {@code frameId:backendNodeId}, plus the browser and tab bound to that hit. */
    record HandleRef(String raw, String frameId, Integer backend, String browserId, String tab) {
        static HandleRef parse(String handle) {
            HandleRef parsed = parseHandle(handle);
            HandleOwner owner = parsed.raw == null || parsed.raw.isBlank() ? null : HANDLE_OWNERS.get(parsed.raw);
            if (owner == null) return parsed;
            return new HandleRef(parsed.raw, parsed.frameId, parsed.backend, owner.browserId, owner.tab);
        }
    }

    private static HandleRef parseHandle(String handle) {
        if (handle == null || handle.isBlank()) return new HandleRef("", null, null, null, null);
        int colon = handle.lastIndexOf(':');
        if (colon > 0 && colon < handle.length() - 1) {
            try {
                int backend = Integer.parseInt(handle.substring(colon + 1));
                return new HandleRef(handle, handle.substring(0, colon), backend, null, null);
            } catch (NumberFormatException ignored) {
                return new HandleRef(handle, null, null, null, null);
            }
        }
        return new HandleRef(handle, null, null, null, null);
    }
}
