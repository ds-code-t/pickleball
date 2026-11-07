package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.Response;
import tools.dscode.common.composite.CompositeRuntime;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
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
                BlockingQueue<String> lines = pump(child);
                String ready = lines.poll(15, TimeUnit.SECONDS);
                assertEquals("ready", ready);
                assertTrue(child.isAlive(), "chat send must wait while another process holds the lock");
                held.release();
                boolean finished = child.waitFor(15, TimeUnit.SECONDS);
                String output = String.join("\n", drain(lines));
                assertTrue(finished, ready + "\n" + output);
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

    @Test
    void sanitizerDropsC0JavascriptAndActiveHtmlButKeepsUnquotedUrls() {
        String control = PageSurfaces.neutralize("<a href=\"&#1;javascript:alert(1)\">go</a>");
        assertFalse(control.toLowerCase().contains("javascript"), control);
        assertFalse(control.toLowerCase().contains("alert"), control);
        assertTrue(control.contains(">go</a>"), control);
        String hex = PageSurfaces.neutralize("<a href=\"&#x1;javascript:alert(1)\">go</a>");
        assertFalse(hex.toLowerCase().contains("javascript"), hex);
        String frame = PageSurfaces.neutralize("<iframe src=\"data:text/html,%3Cscript%3Ealert(1)%3C/script%3E\"></iframe>");
        assertFalse(frame.toLowerCase().contains("script"), frame);
        assertFalse(frame.toLowerCase().contains("data:text/html"), frame);
        assertTrue(frame.toLowerCase().contains("<iframe"), frame);
        String url = PageSurfaces.neutralize("<a href=https://example.test/a>go</a>");
        assertTrue(url.contains("https://example.test/a"), url);
        assertFalse(url.contains("href=\"https:\""), url);
        String kept = PageSurfaces.neutralize("<section><p class=\"note\">Hello <b>there</b></p></section>");
        assertTrue(kept.contains("<section>"), kept);
        assertTrue(kept.contains("<b>there</b>"), kept);
    }

    @Test
    void geometryBindsTheQueryOwnerNotWhicheverBrowserIsFocusedLater() throws Exception {
        AtomicInteger wrong = new AtomicInteger();
        WebDriver first = scriptDriver("tab-a", spec -> {
            wrong.incrementAndGet();
            return Map.of("ok", true, "x", 1, "y", 1);
        }, false);
        WebDriver second = scriptDriver("tab-b", spec -> Map.of("ok", true, "x", spec.get("x"), "y", spec.get("y")), false);
        PageSurfaces.manage(first, "chrome-headed-quiet", true);
        PageSurfaces.manage(second, "edge-headed-quiet", true);
        PageSurfaces.focus(first);
        PageSurfaces.rememberGeometry("frame-b:15", 12, 18, second, "tab-b");
        PageSurfaces.focus(first);
        Map<String, Object> placed = PageSurfaces.place(null, "append", "frame-b:15", null, "<b>here</b>", "overlay", null, null, null);
        assertEquals(0, wrong.get());
        assertEquals(12, placed.get("x"));
        assertEquals(18, placed.get("y"));
    }

    @Test
    void explicitStylesFollowTheRequestOrSayTheyFellBack() throws Exception {
        List<Map<String, Object>> configs = new ArrayList<>();
        WebDriver live = cdpDriver("tab-a", (method, params) -> {
            if ("Overlay.highlightNode".equals(method) && params.get("highlightConfig") instanceof Map<?, ?> config) {
                configs.add(cast(config));
            }
            return Map.of();
        }, spec -> Map.of("ok", true));
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.bindFrame(live, "tab-a", "frame-a");
        Map<String, Object> painted = PageSurfaces.highlight(
                null, "frame-a:4", "yellow", "background-color:#abc; border: 2px solid tomato; border-color: rgb(1, 2, 3);", null, null, null, null);
        assertEquals("Overlay.highlightNode", painted.get("cdp"));
        assertFalse(painted.containsKey("styleNote"));
        assertEquals(170, ((Map<?, ?>) configs.get(0).get("contentColor")).get("r"));
        assertEquals(187, ((Map<?, ?>) configs.get(0).get("contentColor")).get("g"));
        assertEquals(204, ((Map<?, ?>) configs.get(0).get("contentColor")).get("b"));
        assertEquals(1, ((Number) ((Map<?, ?>) configs.get(0).get("borderColor")).get("r")).intValue());
        assertEquals(2, ((Number) ((Map<?, ?>) configs.get(0).get("borderColor")).get("g")).intValue());
        assertEquals(3, ((Number) ((Map<?, ?>) configs.get(0).get("borderColor")).get("b")).intValue());
        PageSurfaces.clearDriver();
        AtomicReference<String> css = new AtomicReference<>();
        WebDriver dom = scriptDriver("tab-a", spec -> {
            css.set(String.valueOf(spec.get("css")));
            return Map.of("ok", true);
        }, false);
        PageSurfaces.manage(dom, "chrome-headed-quiet", true);
        Map<String, Object> fallback = PageSurfaces.highlight(null, "save", "yellow", "transform: rotate(1deg);", null, null, null, null);
        assertEquals("style", fallback.get("cdp"));
        assertTrue(String.valueOf(fallback.get("styleNote")).contains("reversible DOM"), String.valueOf(fallback));
        assertTrue(css.get().contains("transform"), css.get());
    }

    @Test
    void unhighlightFailsWhenHideHighlightFails() throws Exception {
        AtomicInteger dom = new AtomicInteger();
        WebDriver live = cdpDriver("tab-a", (method, params) -> {
            if ("Overlay.hideHighlight".equals(method)) throw new IllegalStateException("hide failed");
            return Map.of();
        }, spec -> {
            dom.incrementAndGet();
            return Map.of("ok", false, "error", "dom should not run");
        });
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.bindFrame(live, "tab-a", "frame-a");
        Map<String, Object> painted = PageSurfaces.highlight(null, "frame-a:9", "yellow", null, null, null, null, null);
        assertEquals("Overlay.highlightNode", painted.get("cdp"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> PageSurfaces.unhighlight(null, "frame-a:9"));
        assertTrue(failure.getMessage().contains("hideHighlight"), failure.getMessage());
        assertEquals(0, dom.get());
        assertThrows(IllegalStateException.class, () -> PageSurfaces.cleanup(null));
    }

    @Test
    void hideHighlightVisitsEveryTabThatHadAHighlight() throws Exception {
        AtomicReference<String> current = new AtomicReference<>("tab-1");
        List<String> hidden = new ArrayList<>();
        WebDriver live = tabsCdp(current, new LinkedHashSet<>(List.of("tab-1", "tab-2")), (method, params) -> {
            if ("Overlay.hideHighlight".equals(method)) hidden.add(current.get());
            return Map.of();
        }, spec -> Map.of("ok", true, "removed", 1));
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.bindFrame(live, "tab-1", "a");
        PageSurfaces.highlight(null, "a:1", "yellow", null, null, null, null, null);
        PageSurfaces.bindFrame(live, "tab-2", "b");
        PageSurfaces.highlight(null, "b:2", "outline-red", null, null, null, null, null);
        hidden.clear();
        PageSurfaces.cleanup(null);
        assertTrue(hidden.contains("tab-1"), hidden.toString());
        assertTrue(hidden.contains("tab-2"), hidden.toString());
    }

    @Test
    void liveHelperFindsABridgedNodeWithoutAPickledBackendAttribute() throws Exception {
        Map<String, Object> highlight = runLiveHelper(Map.of(
                "op", "highlight",
                "bridge", "tok",
                "handle", "child-1:15",
                "backend", 15,
                "css", "outline:1px solid red",
                "sentence", "agent style"
        ));
        assertEquals(Boolean.TRUE, highlight.get("ok"));
        assertTrue(lastHelperHtml.contains("data-pkb-style"), lastHelperHtml);
        assertFalse(lastHelperHtml.contains("data-backend-node-id"), lastHelperHtml);
        Map<String, Object> replaced = runLiveHelper(Map.of(
                "op", "replace",
                "bridge", "tok",
                "handle", "child-1:15",
                "backend", 15,
                "html", "<b>bridged</b>",
                "kind", "html",
                "id", "pkb-9",
                "sentence", "sentence",
                "where", "after"
        ));
        assertEquals(Boolean.TRUE, replaced.get("ok"));
        assertTrue(lastHelperHtml.contains("bridged"), lastHelperHtml);
        assertTrue(lastHelperHtml.contains("data-pkb-agent"), lastHelperHtml);
        assertFalse(lastHelperHtml.contains("ordinary"), lastHelperHtml);
        assertFalse(lastHelperHtml.contains("data-backend-node-id"), lastHelperHtml);
        Map<String, Object> inserted = runLiveHelper(Map.of(
                "op", "insert",
                "bridge", "tok",
                "handle", "child-1:15",
                "backend", 15,
                "html", "<i>before</i>",
                "kind", "html",
                "id", "pkb-8",
                "sentence", "sentence",
                "where", "before"
        ));
        assertEquals(Boolean.TRUE, inserted.get("ok"));
        assertTrue(lastHelperHtml.contains("before"), lastHelperHtml);
        assertTrue(lastHelperHtml.contains("ordinary"), lastHelperHtml);
        Map<String, Object> overlay = runLiveHelper(Map.of(
                "op", "append",
                "bridge", "tok",
                "handle", "child-1:15",
                "backend", 15,
                "html", "<b>over</b>",
                "kind", "overlay",
                "id", "pkb-3",
                "sentence", "sentence",
                "x", 8,
                "y", 9
        ));
        assertEquals(Boolean.TRUE, overlay.get("ok"));
        assertEquals(8, ((Number) overlay.get("x")).intValue());
        assertTrue(lastHelperHtml.contains("over"), lastHelperHtml);
    }

    @Test
    void pageHelperBridgesAnOrdinaryNodeAndRunsTheRealHelper() throws Exception {
        List<String> functions = new ArrayList<>();
        WebDriver live = cdpDriver("tab-a", (method, params) -> {
            if ("Page.getFrameTree".equals(method)) {
                return Map.of("frameTree", Map.of(
                        "frame", Map.of("id", "main"),
                        "childFrames", List.of(Map.of("frame", Map.of("id", "child-1")))
                ));
            }
            if ("DOM.getFrameOwner".equals(method)) return Map.of("backendNodeId", 42);
            if ("DOM.resolveNode".equals(method)) return Map.of("object", Map.of("objectId", "obj-9"));
            if ("Runtime.callFunctionOn".equals(method)) {
                functions.add(String.valueOf(params.get("functionDeclaration")));
                return Map.of("result", Map.of("value", true));
            }
            return Map.of();
        }, spec -> {
            try {
                return runLiveHelper(cast(spec));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.bindFrame(live, "tab-a", "child-1");
        Map<String, Object> placed = PageSurfaces.place(null, "replace", "child-1:15", null, "<b>bridged</b>", "html", null, null, null);
        assertEquals(PageSurfaces.liveHelperSource(), LAST_SCRIPT.get());
        assertTrue(functions.stream().anyMatch(fn -> fn.contains("data-pkb-bridge") && fn.contains("function(token)")), functions.toString());
        assertTrue(functions.stream().noneMatch(fn -> fn.contains("bridged")), functions.toString());
        assertTrue(lastHelperHtml.contains("bridged"), lastHelperHtml);
        assertFalse(lastHelperHtml.contains("ordinary"), lastHelperHtml);
        assertFalse(lastHelperHtml.contains("data-backend-node-id"), lastHelperHtml);
        assertFalse(String.valueOf(placed.get("id")).isBlank());
    }

    @Test
    void twoProcessesAllocateDistinctSurfaceIdsUnderThePlacementLock() throws Exception {
        Path file = temp.resolve("ledger/placements.json");
        Process first = surfaceChild(file);
        Process second = surfaceChild(file);
        try {
            BlockingQueue<String> one = pump(first);
            BlockingQueue<String> two = pump(second);
            assertEquals("ready", one.poll(15, TimeUnit.SECONDS));
            assertEquals("ready", two.poll(15, TimeUnit.SECONDS));
            assertTrue(first.waitFor(15, TimeUnit.SECONDS));
            assertTrue(second.waitFor(15, TimeUnit.SECONDS));
            String output = String.join("\n", drain(one)) + "\n" + String.join("\n", drain(two));
            assertEquals(0, first.exitValue(), output);
            assertEquals(0, second.exitValue(), output);
            assertTrue(output.contains("id=pkb-1"), output);
            assertTrue(output.contains("id=pkb-2"), output);
            String stored = Files.readString(file);
            assertTrue(stored.contains("\"pkb-1\""), stored);
            assertTrue(stored.contains("\"pkb-2\""), stored);
            assertTrue(stored.contains("summary-pkb-1"), stored);
            assertTrue(stored.contains("summary-pkb-2"), stored);
        } finally {
            if (first.isAlive()) first.destroyForcibly();
            if (second.isAlive()) second.destroyForcibly();
        }
    }

    @Test
    void twoChatWritersReceiveDistinctIdsAfterReady() throws Exception {
        Process first = chatChild(temp, "writers");
        Process second = chatChild(temp, "writers");
        try {
            BlockingQueue<String> one = pump(first);
            BlockingQueue<String> two = pump(second);
            assertEquals("ready", one.poll(15, TimeUnit.SECONDS));
            assertEquals("ready", two.poll(15, TimeUnit.SECONDS));
            assertTrue(first.waitFor(15, TimeUnit.SECONDS));
            assertTrue(second.waitFor(15, TimeUnit.SECONDS));
            String output = String.join("\n", drain(one)) + "\n" + String.join("\n", drain(two));
            assertEquals(0, first.exitValue(), output);
            assertEquals(0, second.exitValue(), output);
            assertTrue(output.contains("id=1"), output);
            int firstId = output.indexOf("id=1");
            int secondId = output.indexOf("id=2");
            assertTrue(firstId >= 0 && secondId >= 0, output);
            assertTrue(output.indexOf("id=1", firstId + 4) < 0, output);
        } finally {
            if (first.isAlive()) first.destroyForcibly();
            if (second.isAlive()) second.destroyForcibly();
        }
    }

    @Test
    void scenarioCleanupRunsRegisteredSurfaceHooks() {
        AtomicInteger hits = new AtomicInteger();
        io.cucumber.core.runner.CurrentScenarioState.finishAgentSurfaces();
        tools.dscode.control.protocol.AgentSurfaceEnd.register(hits::incrementAndGet);
        io.cucumber.core.runner.CurrentScenarioState.finishAgentSurfaces();
        assertTrue(hits.get() >= 1);
    }

    @Test
    void aSecondBrowserKeepsItsOwnEngineSession() throws Exception {
        Path binary = temp.resolve("composite");
        Path log = temp.resolve("stdio.log");
        Files.writeString(binary, """
                #!/usr/bin/env python3
                import json, sys
                log = open(%s, "a", encoding="utf-8")
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    req = json.loads(line)
                    argv = req.get("argv") or []
                    log.write(json.dumps(argv) + "\\n")
                    log.flush()
                    def flag(name):
                        if name in argv:
                            index = argv.index(name)
                            if index + 1 < len(argv):
                                return argv[index + 1]
                        return ""
                    if "version" in argv:
                        body = {"version": "2.4.0"}
                    elif len(argv) >= 2 and argv[0] == "session" and argv[1] == "create":
                        body = {"ok": True, "id": flag("--id"), "cdp": flag("--cdp"), "launched": False}
                    else:
                        body = {"ok": True}
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": 0,
                        "stdout": json.dumps(body),
                        "stderr": ""
                    }) + "\\n")
                    sys.stdout.flush()
                """.formatted(new ObjectMapper().writeValueAsString(log.toString())));
        Files.setPosixFilePermissions(binary, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        try {
            CompositeRuntime.use(temp, Map.of("COMPOSITE_BIN", binary.toString(), "pkb_composite", "on"),
                    getClass().getClassLoader(), temp);
            assertTrue(CompositeRuntime.primary(), String.valueOf(CompositeRuntime.current()));
            RemoteWebDriver first = remote("http://127.0.0.1:9222");
            RemoteWebDriver second = remote("http://127.0.0.1:9333");
            CompositeRuntime.attach(first);
            String firstId = CompositeRuntime.focusedSessionId();
            CompositeRuntime.attach(second);
            String secondId = CompositeRuntime.focusedSessionId();
            assertTrue(firstId != null && !firstId.isBlank(), firstId);
            assertTrue(secondId != null && !secondId.isBlank(), secondId);
            assertFalse(firstId.equals(secondId), firstId + " / " + secondId);
            CompositeRuntime.focusDriver(first);
            assertEquals(firstId, CompositeRuntime.focusedSessionId());
            assertTrue(String.valueOf(CompositeRuntime.focusedCdp()).contains("9222"), CompositeRuntime.focusedCdp());
            CompositeRuntime.focusDriver(second);
            assertEquals(secondId, CompositeRuntime.focusedSessionId());
            dev.domcomposite.Session again = CompositeRuntime.client().openSession(Map.of("cdp", "http://127.0.0.1:9222"));
            assertEquals(firstId, again.id());
            String trace = Files.readString(log);
            int creates = 0;
            for (String line : trace.split("\n")) if (line.contains("session") && line.contains("create")) creates++;
            assertEquals(2, creates, trace);
        } finally {
            CompositeRuntime.resetForTests();
        }
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
                    case "executeScript" -> {
                        if (args != null && args.length > 0) LAST_SCRIPT.set(String.valueOf(args[0]));
                        yield script.apply(scriptSpec(args));
                    }
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

    private static WebDriver tabsCdp(
            AtomicReference<String> current,
            LinkedHashSet<String> tabs,
            java.util.function.BiFunction<String, Map<String, Object>, Object> cdp,
            Function<Map<?, ?>, Map<String, Object>> script
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
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class, CdpProbe.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWindowHandles" -> tabs;
                    case "getWindowHandle" -> current.get();
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

    private static final AtomicReference<String> LAST_SCRIPT = new AtomicReference<>();
    private static String lastHelperHtml = "";

    private static Process chatChild(Path project, String run) throws IOException {
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "tools.dscode.common.agentcontrol.ChatLockChild",
                project.toString(),
                run
        ).redirectErrorStream(true).start();
    }

    private static Process surfaceChild(Path file) throws IOException {
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "tools.dscode.common.agentcontrol.SurfaceIdChild",
                file.toString()
        ).redirectErrorStream(true).start();
    }

    private static BlockingQueue<String> pump(Process child) {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            } catch (IOException ignored) {
                // The caller reports a missing ready line.
            }
        }, "child-stdout");
        thread.setDaemon(true);
        thread.start();
        return lines;
    }

    private static List<String> drain(BlockingQueue<String> lines) throws InterruptedException {
        List<String> all = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            String line = lines.poll(100, TimeUnit.MILLISECONDS);
            if (line == null) {
                if (!all.isEmpty()) break;
                continue;
            }
            all.add(line);
        }
        lines.drainTo(all);
        return all;
    }

    private static Map<String, Object> runLiveHelper(Map<String, Object> spec) throws Exception {
        Path dir = Files.createTempDirectory("pkb-live-helper");
        Path in = dir.resolve("in.json");
        Path out = dir.resolve("out.json");
        String harness;
        try (InputStream stream = AgentLiveFixTest.class.getResourceAsStream("live-helper-dom.js")) {
            if (stream == null) throw new IOException("missing live-helper-dom.js");
            harness = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path script = dir.resolve("dom.js");
        Files.writeString(script, harness);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("script", PageSurfaces.liveHelperSource());
        body.put("spec", spec);
        new ObjectMapper().writeValue(in.toFile(), body);
        Process process = new ProcessBuilder("node", script.toString(), in.toString(), out.toString())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished || process.exitValue() != 0 || !Files.isRegularFile(out)) {
            throw new AssertionError("live helper did not run: " + output);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(out.toFile(), Map.class);
        lastHelperHtml = String.valueOf(parsed.get("html"));
        Object result = parsed.get("result");
        if (!(result instanceof Map<?, ?> map)) throw new AssertionError("helper result was not a map: " + parsed + " html=" + lastHelperHtml);
        return cast(map);
    }

    private static RemoteWebDriver remote(String cdp) {
        MutableCapabilities caps = new MutableCapabilities();
        caps.setCapability("browserName", "chrome");
        caps.setCapability("se:cdp", cdp);
        return new RemoteWebDriver(command -> {
            Response response = new Response();
            response.setState("success");
            response.setSessionId("s" + Integer.toHexString(cdp.hashCode()));
            Map<String, Object> returned = new LinkedHashMap<>();
            returned.put("browserName", "chrome");
            returned.put("se:cdp", cdp);
            response.setValue(returned);
            return response;
        }, caps);
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
