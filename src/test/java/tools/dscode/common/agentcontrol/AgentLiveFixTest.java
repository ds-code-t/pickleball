package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live-behavior gaps the file-fixture command tests did not prove. */
class AgentLiveFixTest {
    @TempDir
    Path temp;

    @AfterEach
    void releaseDrivers() {
        PageSurfaces.clearDriver();
    }

    @Test
    void seleniumHandlesAreTabsAndABrowserIsAWindow() {
        List<WindowRegistry.Handle> rows = WindowRegistry.classify("browser-1", "chrome-headed-quiet", List.of("CDwindow-9"));
        assertEquals("window", rows.get(0).kind());
        assertEquals("window:browser-1", rows.get(0).handle());
        assertEquals("tab", rows.get(1).kind());
        assertEquals("CDwindow-9", rows.get(1).handle());
        WebDriver first = driver("tab-a");
        WebDriver second = driver("tab-b");
        PageSurfaces.manage(first, "chrome-headed-quiet", true);
        PageSurfaces.manage(second, "edge-headed-quiet", true);
        List<WindowRegistry.Handle> live = WindowRegistry.list(temp, "run");
        assertEquals(4, live.size());
        assertEquals(2, live.stream().filter(handle -> "window".equals(handle.kind())).count());
        assertEquals(2, live.stream().filter(handle -> "tab".equals(handle.kind())).count());
        assertTrue(live.stream().anyMatch(handle -> "tab-a".equals(handle.handle()) && "tab".equals(handle.kind())));
        assertTrue(live.stream().noneMatch(handle -> "window".equals(handle.kind()) && "tab-a".equals(handle.handle())));
    }

    @Test
    void launchFileForTheRequestedProjectIsWhatTheDriverApplies() throws Exception {
        Path project = temp.resolve("project");
        Path other = temp.resolve("other");
        Files.createDirectories(project);
        Files.createDirectories(other);
        LaunchTemplates.Resolved resolved = LaunchTemplates.resolve("chrome-headed-quiet", List.of("--disable-gpu"));
        AgentLaunch.write(project, resolved);
        ObjectNode driver = AgentStore.JSON.createObjectNode();
        driver.putObject("options");
        AgentLaunch.apply("chrome", driver, project);
        assertTrue(driver.toString().contains("--disable-gpu"), driver.toString());
        ObjectNode cwd = AgentStore.JSON.createObjectNode();
        cwd.putObject("options");
        AgentLaunch.apply("chrome", cwd, other);
        assertFalse(cwd.toString().contains("--disable-gpu"), cwd.toString());
    }

    @Test
    void repeatedLaunchArgsSurviveTheMap() {
        List<String> args = AgentControl.launchArgs(List.of("--disable-gpu"), Map.of("arg", "--window-size=800,600\n--disable-gpu"));
        assertTrue(args.contains("--disable-gpu"));
        assertTrue(args.contains("--window-size=800,600"));
    }

    @Test
    void geometryBeatsCallerCoordinates() {
        int[] point = PageSurfaces.visualPoint("{\"visual_viewport_relative\":{\"x\":30,\"y\":44}}", 12, 40);
        assertEquals(30, point[0]);
        assertEquals(44, point[1]);
    }

    @Test
    void repeatedHighlightRestoresTheOriginalStyle() throws Exception {
        Path page = temp.resolve("page.html");
        Files.writeString(page, "<html><body><button id=\"save\" style=\"color:red\">Save</button></body></html>");
        PageSurfaces.highlight(page, "save", "yellow", null, null, null, null, null);
        PageSurfaces.highlight(page, "save", "yellow", null, null, null, null, null);
        String twice = Files.readString(page);
        assertFalse(twice.contains("background-color:#fff3a0;background-color:#fff3a0"), twice);
        assertEquals(1, count(attribute(twice, "style"), "background-color:#fff3a0"));
        assertTrue(twice.contains("data-pkb-original-style=\"color:red\""));
        PageSurfaces.unhighlight(page, "save");
        String restored = Files.readString(page);
        assertTrue(restored.contains("style=\"color:red\""), restored);
        assertFalse(restored.contains("data-pkb-style"));
        assertFalse(restored.contains("background-color"));
    }

    @Test
    void replaceUndoIsNotLeftInsideTheDocument() throws Exception {
        Path page = temp.resolve("page.html");
        Files.writeString(page, "<html><body><button id=\"save\">Save</button></body></html>");
        Map<String, Object> replaced = PageSurfaces.place(page, "replace", "save", null, "<span>replaced</span>", "html", null, null, null);
        String html = Files.readString(page);
        assertTrue(html.contains("replaced"), html);
        assertFalse(html.contains(">Save<"), html);
        assertTrue(Files.isRegularFile(temp.resolve("page.html.undo.json")));
        PageSurfaces.remove(page, String.valueOf(replaced.get("id")));
        String restored = Files.readString(page);
        assertTrue(restored.contains(">Save<"), restored);
        assertFalse(restored.contains("replaced"), restored);
    }

    @Test
    void labelsAndScriptPathsCoverDescendantsSrcdocAndEncodedHandlers() {
        String labeled = PageSurfaces.labelElements("<div><span>x</span></div>", "html", "sentence");
        assertEquals(2, count(labeled, "data-pkb-agent=\"agent_injected\""));
        String srcdoc = PageSurfaces.neutralize("<iframe srcdoc=\"<script>alert(1)</script>\"></iframe><p>ok</p>");
        assertFalse(srcdoc.toLowerCase().contains("srcdoc"), srcdoc);
        assertFalse(srcdoc.toLowerCase().contains("script"), srcdoc);
        assertTrue(srcdoc.contains("<p>ok</p>"), srcdoc);
        String encoded = PageSurfaces.neutralize("<img src=\"x\" &#111;nerror=\"alert(1)\" href=\"java&#115;cript:alert(1)\">");
        assertFalse(encoded.toLowerCase().contains("onerror"), encoded);
        assertFalse(encoded.toLowerCase().contains("javascript"), encoded);
        String doubled = PageSurfaces.neutralize("<img &#111;nerror=\"alert(1)\">");
        assertFalse(doubled.toLowerCase().contains("onerror"), doubled);
        String slash = PageSurfaces.neutralize("<img src=x /onerror=alert(1)>");
        assertFalse(slash.toLowerCase().contains("onerror"), slash);
        assertFalse(slash.toLowerCase().contains("alert"), slash);
        assertTrue(slash.toLowerCase().contains("<img"), slash);
        String spaced = PageSurfaces.neutralize("<a href=\"java&#9;script:alert(1)\">go</a>");
        assertFalse(spaced.toLowerCase().contains("javascript"), spaced);
        assertFalse(spaced.toLowerCase().contains("alert"), spaced);
        assertTrue(spaced.contains(">go</a>"), spaced);
        String kept = PageSurfaces.neutralize("<section><p class=\"note\">Hello <b>there</b></p><a href=\"https://example.test/a\">go</a></section>");
        assertTrue(kept.contains("<section>"), kept);
        assertTrue(kept.contains("class=\"note\""), kept);
        assertTrue(kept.contains("<b>there</b>"), kept);
        assertTrue(kept.contains("https://example.test/a"), kept);
    }

    @Test
    void removalLogKeepsTheSnippetHash() throws Exception {
        Path page = temp.resolve("page.html");
        Files.writeString(page, "<html><body><p>Hi</p></body></html>");
        Map<String, Object> placed = PageSurfaces.place(page, "append", null, null, "<b>gone</b>", "html", null, null, null);
        Map<String, Object> removed = PageSurfaces.remove(page, String.valueOf(placed.get("id")));
        assertEquals(placed.get("sha256"), removed.get("sha256"));
        assertFalse(String.valueOf(removed.get("sha256")).isBlank());
        assertFalse(String.valueOf(removed.get("summary")).isBlank());
        PageSurfaces.clearPlacementMemory();
        Map<String, Object> again = PageSurfaces.remove(page, String.valueOf(placed.get("id")));
        assertEquals(placed.get("sha256"), again.get("sha256"));
        assertEquals(removed.get("summary"), again.get("summary"));
        assertTrue(Files.isRegularFile(temp.resolve("page.html.place.json")));
    }

    @Test
    void drainFailureIsNotEmptyTraffic() throws Exception {
        IOException failed = assertThrows(IOException.class, () -> ObserveEvents.requireBuffer(
                AgentStore.JSON.readTree("{\"ok\":false,\"error\":\"observe drain failed: websocket closed\"}")
        ));
        assertTrue(failed.getMessage().contains("websocket closed"), failed.getMessage());
        assertTrue(failed.getMessage().contains("observe failed"), failed.getMessage());
        List<ObserveEvents.Event> empty = ObserveEvents.requireBuffer(
                AgentStore.JSON.readTree("{\"ok\":true,\"source\":\"cdp-session\",\"count\":0,\"events\":[]}")
        );
        assertTrue(empty.isEmpty());
    }

    @Test
    void chatIdsStayDurableAcrossAFreshCounterAndRolledFiles() throws Exception {
        ChatLog.Message first = ChatLog.send(temp, "chat", "run", "human", "ada", "one");
        assertEquals("1", first.id());
        Path file = temp.resolve(".pickleball/runs/chat/agent/chat/run.jsonl");
        Files.writeString(file, "\n" + " ".repeat((int) AgentStore.CAP_BYTES), java.nio.file.StandardOpenOption.APPEND);
        ChatLog.Message second = ChatLog.send(temp, "chat", "run", "agent", "ada", "two");
        assertEquals("2", second.id());
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/runs/chat/agent/chat/run.jsonl.1")));
        List<ChatLog.Message> history = ChatLog.history(temp, "chat", "run", 0);
        assertEquals(List.of("one", "two"), history.stream().map(ChatLog.Message::text).toList());
        List<ChatLog.Message> polled = ChatLog.poll(temp, "chat", "run", "1");
        assertEquals(List.of("two"), polled.stream().map(ChatLog.Message::text).toList());
        String stored = """
                {"id":"40","at":"t","source":"human","agent":"ada","channel":"run","text":"kept"}
                """;
        Files.writeString(temp.resolve(".pickleball/runs/chat/agent/chat/durable.jsonl"), stored, StandardCharsets.UTF_8);
        ChatLog.Message next = ChatLog.send(temp, "chat", "durable", "human", "ada", "after");
        assertEquals("41", next.id());
    }

    @Test
    void chatAppendWaitsForAnExclusiveLockHeldByAnotherProcess() throws Exception {
        Path lock = temp.resolve(".pickleball/runs/lock/agent/chat/run.jsonl.lock");
        Files.createDirectories(lock.getParent());
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = channel.lock();
            Process child = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    "tools.dscode.common.agentcontrol.ChatLockChild",
                    temp.toString(),
                    "lock"
            ).redirectErrorStream(true).start();
            try {
                Thread.sleep(500);
                assertTrue(child.isAlive(), "chat send must wait while another process holds the lock");
                held.release();
                boolean finished = child.waitFor(15, TimeUnit.SECONDS);
                String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(finished, output);
                assertEquals(0, child.exitValue(), output);
                assertTrue(output.contains("id=1"), output);
            } finally {
                if (child.isAlive()) child.destroyForcibly();
            }
        }
        ChatLog.Message second = ChatLog.send(temp, "lock", "run", "human", "ada", "parent");
        assertEquals("2", second.id());
    }

    @Test
    void focusedDriverWinsOverTheFirstManagedSuccess() throws Exception {
        AtomicInteger firstCalls = new AtomicInteger();
        WebDriver first = scriptDriver("tab-a", spec -> {
            firstCalls.incrementAndGet();
            return Map.of("ok", true, "x", 1, "y", 1);
        }, false);
        WebDriver second = scriptDriver("tab-b", spec -> Map.of("ok", true, "x", 9, "y", 8), false);
        PageSurfaces.manage(first, "chrome-headed-quiet", true);
        PageSurfaces.manage(second, "edge-headed-quiet", true);
        PageSurfaces.focus(second);
        Map<String, Object> placed = PageSurfaces.place(null, "append", null, null, "<b>live</b>", "html", null, null, null);
        assertEquals(0, firstCalls.get());
        assertEquals(9, placed.get("x"));
        assertEquals(8, placed.get("y"));
    }

    @Test
    void rankedHandleUsesFrameIdentityInsteadOfScanningDrivers() throws Exception {
        AtomicInteger wrong = new AtomicInteger();
        WebDriver first = scriptDriver("tab-a", spec -> {
            wrong.incrementAndGet();
            return Map.of("ok", true, "x", 1, "y", 1);
        }, false);
        WebDriver second = scriptDriver("tab-b", spec -> Map.of("ok", true, "x", 4, "y", 5), false);
        PageSurfaces.manage(first, "chrome-headed-quiet", true);
        PageSurfaces.manage(second, "edge-headed-quiet", true);
        PageSurfaces.focus(first);
        assertThrows(IllegalStateException.class, () ->
                PageSurfaces.place(null, "append", "frame-missing:15", null, "<i>n</i>", "html", null, null, null));
        assertEquals(0, wrong.get());
        PageSurfaces.bindFrame(second, "tab-b", "frame-b");
        Map<String, Object> placed = PageSurfaces.place(null, "append", "frame-b:15", null, "<i>n</i>", "html", null, null, null);
        assertEquals(0, wrong.get());
        assertEquals(4, placed.get("x"));
    }

    @Test
    void removalDoesNotStopAtTheFirstDriverThatDidNotHoldTheSurface() throws Exception {
        WebDriver first = scriptDriver("tab-a", spec -> Map.of("ok", true, "removed", 0), false);
        AtomicInteger hits = new AtomicInteger();
        WebDriver second = scriptDriver("tab-b", spec -> {
            hits.incrementAndGet();
            return Map.of("ok", true, "removed", 1);
        }, false);
        PageSurfaces.manage(first, "chrome-headed-quiet", true);
        PageSurfaces.manage(second, "edge-headed-quiet", true);
        Map<String, Object> removed = PageSurfaces.remove(null, "pkb-9");
        assertEquals(true, removed.get("removed"));
        assertEquals(1, hits.get());
    }

    @Test
    void closeDoesNotReportSuccessWhenQuitFails() {
        WebDriver bad = scriptDriver("tab-a", spec -> Map.of("ok", true), true);
        PageSurfaces.manage(bad, "chrome-headed-quiet", true);
        String handle = "window:" + PageSurfaces.managed().get(0).id();
        assertThrows(IllegalStateException.class, () -> WindowRegistry.close(temp, "run", handle));
        assertEquals(1, PageSurfaces.managed().size());
    }

    @Test
    void overlayUsesRememberedGeometryWhenCallerOmitsCoordinates() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WebDriver live = scriptDriver("tab-a", spec -> {
            calls.incrementAndGet();
            return Map.of("ok", true, "x", spec.get("x"), "y", spec.get("y"));
        }, false);
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.focus(live);
        PageSurfaces.rememberGeometry("main:7", 30, 44);
        Map<String, Object> placed = PageSurfaces.place(null, "append", "main:7", null, "<b>here</b>", "overlay", null, null, null);
        assertEquals(30, placed.get("x"));
        assertEquals(44, placed.get("y"));
        assertEquals("visual-viewport-relative", placed.get("placement"));
        assertTrue(calls.get() > 0);
        calls.set(0);
        assertThrows(IllegalArgumentException.class, () ->
                PageSurfaces.place(null, "append", "gone:2", null, "<b>nope</b>", "overlay", 12, 40, null));
        assertEquals(0, calls.get());
    }

    @Test
    void cdpHighlightDoesNotNeedADomMutationAndCleanupHidesIt() throws Exception {
        List<String> commands = new ArrayList<>();
        List<Map<String, Object>> configs = new ArrayList<>();
        AtomicInteger domHighlights = new AtomicInteger();
        WebDriver live = cdpDriver("tab-a", (method, params) -> {
            commands.add(method);
            if ("Overlay.highlightNode".equals(method) && params.get("highlightConfig") instanceof Map<?, ?> config) {
                configs.add(cast(config));
            }
            return Map.of();
        }, spec -> {
            if ("highlight".equals(String.valueOf(spec.get("op"))) || "unhighlight".equals(String.valueOf(spec.get("op")))) {
                domHighlights.incrementAndGet();
                return Map.of("ok", false, "error", "unknown handle");
            }
            return Map.of("ok", true, "removed", 1);
        });
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.bindFrame(live, "tab-a", "frame-a");
        Map<String, Object> painted = PageSurfaces.highlight(null, "frame-a:9", "outline-red", "outline-color:#112233;", null, null, null, null);
        assertEquals("Overlay.highlightNode", painted.get("cdp"));
        assertEquals(0, domHighlights.get());
        assertTrue(commands.contains("Overlay.highlightNode"));
        assertEquals(0d, ((Map<?, ?>) configs.get(0).get("contentColor")).get("a"));
        assertEquals(17, ((Map<?, ?>) configs.get(0).get("borderColor")).get("r"));
        PageSurfaces.unhighlight(null, "frame-a:9");
        assertEquals(0, domHighlights.get());
        assertTrue(commands.contains("Overlay.hideHighlight"));
        PageSurfaces.cleanup(null);
        assertTrue(commands.stream().filter("Overlay.hideHighlight"::equals).count() >= 2);
    }

    @Test
    void overlayInputIsPolledFromEveryTab() throws Exception {
        AtomicReference<String> current = new AtomicReference<>("tab-1");
        WebDriver live = tabDriver(current, new LinkedHashSet<>(List.of("tab-1", "tab-2")), spec -> {
            if ("tab-2".equals(current.get())) return List.of(Map.of("type", "click", "id", "pkb-2"));
            return List.of();
        });
        PageSurfaces.manage(live, "chrome-headed-quiet", false);
        List<Map<String, Object>> events = PageSurfaces.pollInput(temp, "inputs");
        assertEquals(1, events.size());
        assertEquals("click", events.get(0).get("type"));
        assertEquals("pkb-2", events.get(0).get("id"));
    }

    private static WebDriver scriptDriver(
            String handle,
            Function<Map<?, ?>, Map<String, Object>> script,
            boolean quitFails
    ) {
        AtomicReference<WebDriver> self = new AtomicReference<>();
        Object switcher = Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.TargetLocator.class},
                (proxy, method, args) -> {
                    if ("window".equals(method.getName())) return self.get();
                    return defaultValue(method.getReturnType());
                }
        );
        WebDriver driver = (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWindowHandles" -> Set.of(handle);
                    case "getWindowHandle" -> handle;
                    case "getTitle" -> "title";
                    case "getCurrentUrl" -> "https://example.test";
                    case "quit" -> {
                        if (quitFails) throw new RuntimeException("quit failed");
                        yield null;
                    }
                    case "switchTo" -> switcher;
                    case "executeScript" -> script.apply(scriptSpec(args));
                    case "toString" -> handle;
                    default -> defaultValue(method.getReturnType());
                }
        );
        self.set(driver);
        return driver;
    }

    private interface CdpProbe {
        Object executeCdpCommand(String method, Map<String, Object> params);
    }

    private static WebDriver cdpDriver(
            String handle,
            java.util.function.BiFunction<String, Map<String, Object>, Object> cdp,
            Function<Map<?, ?>, Map<String, Object>> script
    ) {
        AtomicReference<WebDriver> self = new AtomicReference<>();
        Object switcher = Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.TargetLocator.class},
                (proxy, method, args) -> "window".equals(method.getName()) ? self.get() : defaultValue(method.getReturnType())
        );
        WebDriver driver = (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class, CdpProbe.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWindowHandles" -> Set.of(handle);
                    case "getWindowHandle" -> handle;
                    case "switchTo" -> switcher;
                    case "executeScript" -> script.apply(scriptSpec(args));
                    case "executeCdpCommand" -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> params = args[1] instanceof Map<?, ?> map ? cast(map) : Map.of();
                        yield cdp.apply(String.valueOf(args[0]), params);
                    }
                    default -> defaultValue(method.getReturnType());
                }
        );
        self.set(driver);
        return driver;
    }

    private static WebDriver tabDriver(
            AtomicReference<String> current,
            LinkedHashSet<String> tabs,
            Function<Map<?, ?>, Object> script
    ) {
        AtomicReference<WebDriver> self = new AtomicReference<>();
        Object switcher = Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.TargetLocator.class},
                (proxy, method, args) -> {
                    if ("window".equals(method.getName()) && args != null && args.length > 0) {
                        current.set(String.valueOf(args[0]));
                        return self.get();
                    }
                    return defaultValue(method.getReturnType());
                }
        );
        WebDriver driver = (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWindowHandles" -> tabs;
                    case "getWindowHandle" -> current.get();
                    case "switchTo" -> switcher;
                    case "executeScript" -> script.apply(Map.of());
                    default -> defaultValue(method.getReturnType());
                }
        );
        self.set(driver);
        return driver;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scriptSpec(Object[] args) {
        Object spec = args != null && args.length > 1 ? args[1] : Map.of();
        if (spec instanceof Object[] packed) spec = packed.length == 0 ? Map.of() : packed[0];
        if (spec instanceof Map<?, ?> map) return cast(map);
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static WebDriver driver(String handle) {
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWindowHandles" -> Set.of(handle);
                    case "getTitle" -> "title";
                    case "getCurrentUrl" -> "https://example.test";
                    case "toString" -> handle;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static String attribute(String html, String name) {
        int at = html.indexOf(" style=\"");
        if (at < 0) return "";
        int start = at + " style=\"".length();
        int end = html.indexOf('"', start);
        return end < 0 ? "" : html.substring(start, end);
    }

    private static int count(String text, String token) {
        int found = 0;
        int from = 0;
        while (from >= 0) {
            int at = text.indexOf(token, from);
            if (at < 0) break;
            found++;
            from = at + token.length();
        }
        return found;
    }
}
