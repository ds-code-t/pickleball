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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live-behavior gaps the file-fixture command tests did not prove. */
class AgentLiveFixTest {
    @TempDir
    Path temp;

    @AfterEach
    void releaseDrivers() {
        PageSurfaces.clearDriver();
        CompositeRuntime.resetForTests();
        THROW_FRAME.set(false);
        BLOCK_WINDOW.set(false);
        APPLY_CALL_TO_NODE.set(false);
        FRAME_SWITCHES.clear();
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
            if ("Page.getFrameTree".equals(method)) {
                return Map.of("frameTree", Map.of("frame", Map.of("id", "frame-a")));
            }
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
        String xhtml = PageSurfaces.neutralize("<iframe src=\"data:application/xhtml+xml,%3Cscript%3Ealert(1)%3C/script%3E\"></iframe>");
        assertFalse(xhtml.toLowerCase().contains("xhtml"), xhtml);
        assertFalse(xhtml.toLowerCase().contains("script"), xhtml);
        assertTrue(xhtml.toLowerCase().contains("<iframe"), xhtml);
        String svg = PageSurfaces.neutralize("<object data=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'%3E%3C/svg%3E\"></object>");
        assertFalse(svg.toLowerCase().contains("svg"), svg);
        assertFalse(svg.toLowerCase().contains("onload"), svg);
        assertFalse(svg.toLowerCase().contains("alert"), svg);
        assertTrue(svg.toLowerCase().contains("<object"), svg);
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
            if ("Page.getFrameTree".equals(method)) {
                return Map.of("frameTree", Map.of("frame", Map.of("id", "frame-a")));
            }
            if ("Overlay.highlightNode".equals(method) && params.get("highlightConfig") instanceof Map<?, ?> config) {
                configs.add(cast(config));
            }
            return Map.of();
        }, spec -> Map.of("ok", true));
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.bindFrame(live, "tab-a", "frame-a");
        Map<String, Object> painted = PageSurfaces.highlight(
                null, "frame-a:4", "yellow", "background-color:#abc; border-color: rgb(1, 2, 3);", null, null, null, null);
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
            if ("Page.getFrameTree".equals(method)) {
                return Map.of("frameTree", Map.of("frame", Map.of("id", "frame-a")));
            }
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
            if ("Page.getFrameTree".equals(method)) {
                return Map.of("frameTree", Map.of(
                        "frame", Map.of("id", "a"),
                        "childFrames", List.of(Map.of("frame", Map.of("id", "b")))
                ));
            }
            if ("DOM.getFrameOwner".equals(method)) return Map.of("backendNodeId", 7);
            if ("DOM.resolveNode".equals(method)) return Map.of("object", Map.of("objectId", "obj-frame"));
            if ("Runtime.callFunctionOn".equals(method)) {
                String function = String.valueOf(params.get("functionDeclaration"));
                if (function.contains("removeAttribute")) return Map.of("result", Map.of("value", true));
                return Map.of("result", Map.of("value", "{\"name\":\"child\",\"changedName\":false,\"previousBridge\":null}"));
            }
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
                String function = String.valueOf(params.get("functionDeclaration"));
                functions.add(function);
                if (function.contains("removeAttribute")) return Map.of("result", Map.of("value", true));
                if (function.contains("changedName")) {
                    return Map.of("result", Map.of("value", "{\"name\":\"child\",\"changedName\":false,\"previousBridge\":null}"));
                }
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

    @Test
    void queryOwnershipSticksToTheDispatchDriver() throws Exception {
        AtomicInteger wrong = new AtomicInteger();
        WebDriver first = scriptDriver("tab-a", spec -> {
            wrong.incrementAndGet();
            return Map.of("ok", true);
        }, false);
        WebDriver second = scriptDriver("tab-b", spec -> Map.of("ok", true, "x", spec.get("x"), "y", spec.get("y")), false);
        PageSurfaces.manage(first, "chrome-headed-quiet", true);
        PageSurfaces.manage(second, "edge-headed-quiet", true);
        PageSurfaces.focus(first);
        com.fasterxml.jackson.databind.JsonNode node = new ObjectMapper().readTree("""
                {"handle":"frame-b:15","frame_id":"frame-b","backend_node_id":15,
                 "geometry":{"document_css_px":{"x":1,"y":2,"width":3,"height":4},
                 "visual_viewport_relative":{"x":12,"y":18,"width":3,"height":4}}}
                """);
        tools.dscode.common.composite.CompositeWebElement.fromQuery("//b", node, 0, second, "tab-b");
        PageSurfaces.focus(first);
        Map<String, Object> placed = PageSurfaces.place(null, "append", "frame-b:15", null, "<b>here</b>", "overlay", null, null, null);
        assertEquals(0, wrong.get());
        assertEquals(12, placed.get("x"));
        assertEquals(18, placed.get("y"));
    }

    @Test
    void ordinaryNodeIsResolvedByThePredefinedBridgeFunction() throws Exception {
        Path dir = Files.createTempDirectory("pkb-bridge");
        Path script = dir.resolve("bridge.js");
        Files.writeString(script, """
                const fs = require('fs');
                const input = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
                function Element(name) {
                  this.nodeType = 1;
                  this.nodeName = String(name).toUpperCase();
                  this.tagName = this.nodeName;
                  this.attrs = {};
                  this.style = {};
                  this.children = [];
                  this.parent = null;
                }
                Element.prototype.setAttribute = function(n, v) { this.attrs[n] = String(v); };
                Element.prototype.getAttribute = function(n) { return Object.prototype.hasOwnProperty.call(this.attrs, n) ? this.attrs[n] : null; };
                Element.prototype.removeAttribute = function(n) { delete this.attrs[n]; };
                Element.prototype.hasAttribute = function(n) { return Object.prototype.hasOwnProperty.call(this.attrs, n); };
                Element.prototype.appendChild = function(c) { c.parent = this; this.children.push(c); return c; };
                Element.prototype.insertBefore = function(node, ref) {
                  node.parent = this;
                  const at = ref ? this.children.indexOf(ref) : -1;
                  if (at < 0) this.children.push(node);
                  else this.children.splice(at, 0, node);
                  return node;
                };
                Element.prototype.remove = function() {
                  if (!this.parent) return;
                  const at = this.parent.children.indexOf(this);
                  if (at >= 0) this.parent.children.splice(at, 1);
                };
                Element.prototype.querySelectorAll = function() { return this.children.filter(c => c && c.nodeType === 1); };
                Element.prototype.replaceWith = function(node) {
                  const kids = node.children ? node.children.slice() : [node];
                  const parent = this.parent;
                  const at = parent.children.indexOf(this);
                  parent.children.splice(at, 1, ...kids);
                  kids.forEach(k => k.parent = parent);
                };
                Object.defineProperty(Element.prototype, 'parentNode', { get() { return this.parent; } });
                Object.defineProperty(Element.prototype, 'nextSibling', {
                  get() {
                    if (!this.parent) return null;
                    const kids = this.parent.children;
                    const at = kids.indexOf(this);
                    return at >= 0 && at + 1 < kids.length ? kids[at + 1] : null;
                  }
                });
                Object.defineProperty(Element.prototype, 'outerHTML', {
                  get() {
                    const inner = this.children.map(c => c.outerHTML || c.text || '').join('');
                    return '<' + this.nodeName.toLowerCase() + '>' + inner + '</' + this.nodeName.toLowerCase() + '>';
                  }
                });
                Object.defineProperty(Element.prototype, 'innerHTML', {
                  get() { return this.children.map(c => c.outerHTML || c.text || '').join(''); },
                  set(html) { this.children = [{ text: html, outerHTML: html }]; }
                });
                function cssEscape(v) { return String(v).replace(/["\\\\]/g, '\\\\$&'); }
                const CSS = { escape: cssEscape };
                const body = new Element('body');
                const ordinary = new Element('button');
                ordinary.setAttribute('id', 'save');
                body.appendChild(ordinary);
                const pickled = new Element('span');
                pickled.setAttribute('data-backend-node-id', '15');
                body.appendChild(pickled);
                const document = {
                  nodeType: 9,
                  body,
                  documentElement: body,
                  createElement(tag) { return new Element(tag); },
                  getElementById(id) { return [ordinary, pickled].find(el => el.getAttribute('id') === String(id)) || null; },
                  querySelector(selector) {
                    const bridge = /data-pkb-bridge="([^"]+)"/.exec(selector);
                    const backend = /data-backend-node-id="([^"]+)"/.exec(selector);
                    const all = [ordinary, pickled, ...body.children];
                    if (bridge) return all.find(el => el.getAttribute && el.getAttribute('data-pkb-bridge') === bridge[1]) || null;
                    if (backend) return all.find(el => el.getAttribute && el.getAttribute('data-backend-node-id') === backend[1]) || null;
                    return null;
                  },
                  querySelectorAll() { return []; },
                  addEventListener() {}
                };
                globalThis.document = document;
                globalThis.window = { document };
                globalThis.CSS = CSS;
                const bridge = eval('(' + input.bridge + ')');
                const helper = new Function('payload', input.helper.replace('arguments[0]', 'payload'));
                const before = helper({ op: 'highlight', handle: 'child-1:15', backend: 15, css: 'outline:1px solid red', sentence: 'agent style' });
                if (before.ok !== false) throw new Error('pickled backend attribute was used: ' + JSON.stringify(before));
                if (ordinary.getAttribute('data-pkb-bridge') || ordinary.getAttribute('data-backend-node-id')) {
                  throw new Error('ordinary node was mutated before the bridge function');
                }
                const token = 'tok-live';
                function run(spec) {
                  if (bridge.call(ordinary, token) !== true) throw new Error('bridge function did not mark the node');
                  if (ordinary.getAttribute('data-backend-node-id')) throw new Error('bridge invented a backend attribute');
                  return helper(spec);
                }
                const highlight = run({ op: 'highlight', bridge: token, handle: 'child-1:15', backend: 15, css: 'outline:1px solid red', sentence: 'agent style' });
                const replaced = run({ op: 'replace', bridge: token, handle: 'child-1:15', backend: 15, html: '<b>bridged</b>', kind: 'html', id: 'pkb-9', sentence: 'sentence' });
                const inserted = run({ op: 'insert', bridge: token, handle: 'child-1:15', backend: 15, html: '<i>before</i>', kind: 'html', id: 'pkb-8', sentence: 'sentence', where: 'before' });
                const overlay = run({ op: 'append', bridge: token, handle: 'child-1:15', backend: 15, html: '<b>over</b>', kind: 'overlay', id: 'pkb-3', sentence: 'sentence', x: 8, y: 9 });
                fs.writeFileSync(process.argv[3], JSON.stringify({ highlight, replaced, inserted, overlay, html: body.innerHTML }));
                """);
        Path in = dir.resolve("in.json");
        Path out = dir.resolve("out.json");
        new ObjectMapper().writeValue(in.toFile(), Map.of(
                "bridge", PageSurfaces.bridgeMarkSource(),
                "helper", PageSurfaces.liveHelperSource()
        ));
        Process process = new ProcessBuilder("node", script.toString(), in.toString(), out.toString()).redirectErrorStream(true).start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(finished && process.exitValue() == 0 && Files.isRegularFile(out), output);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(out.toFile(), Map.class);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) parsed.get("highlight")).get("ok"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) parsed.get("replaced")).get("ok"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) parsed.get("inserted")).get("ok"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) parsed.get("overlay")).get("ok"));
        assertEquals(8, ((Number) ((Map<?, ?>) parsed.get("overlay")).get("x")).intValue());
        String html = String.valueOf(parsed.get("html"));
        assertTrue(html.contains("bridged"), html);
        assertTrue(html.contains("before"), html);
        assertTrue(html.contains("over"), html);
        assertFalse(html.contains("data-backend-node-id=\"15\"") && html.contains("data-pkb-style") && html.indexOf("data-pkb-style") < html.indexOf("button"), html);
    }

    @Test
    void failedFrameEntryFailsAndRestoresTheTemporaryName() throws Exception {
        List<String> functions = new ArrayList<>();
        THROW_FRAME.set(true);
        WebDriver live = cdpDriver("tab-a", (method, params) -> {
            if ("Page.getFrameTree".equals(method)) {
                return Map.of("frameTree", Map.of(
                        "frame", Map.of("id", "main"),
                        "childFrames", List.of(Map.of("frame", Map.of("id", "child-1")))
                ));
            }
            if ("DOM.getFrameOwner".equals(method)) return Map.of("backendNodeId", 42);
            if ("DOM.resolveNode".equals(method)) return Map.of("object", Map.of("objectId", "obj-1"));
            if ("Runtime.callFunctionOn".equals(method)) {
                functions.add(String.valueOf(params.get("functionDeclaration")));
                String function = String.valueOf(params.get("functionDeclaration"));
                if (function.contains("removeAttribute")) return Map.of("result", Map.of("value", true));
                return Map.of("result", Map.of("value", "{\"name\":\"temp\",\"changedName\":true,\"previousBridge\":null}"));
            }
            return Map.of();
        }, spec -> Map.of("ok", true));
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.bindFrame(live, "tab-a", "child-1");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                PageSurfaces.place(null, "replace", "child-1:15", null, "<b>x</b>", "html", null, null, null));
        assertTrue(failure.getMessage().toLowerCase().contains("frame") || failure.getMessage().contains("unknown"), failure.getMessage());
        assertTrue(functions.stream().anyMatch(fn -> fn.contains("changedName") && fn.contains("setAttribute")), functions.toString());
        assertTrue(functions.stream().anyMatch(fn -> fn.contains("removeAttribute")), functions.toString());
        assertFalse(FRAME_SWITCHES.isEmpty(), FRAME_SWITCHES.toString());
    }

    @Test
    void twoTabsOnOneBrowserKeepIndependentEngineSessions() throws Exception {
        Path binary = temp.resolve("composite-tabs");
        Path log = temp.resolve("tabs.log");
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
            assertNotEquals(
                    dev.domcomposite.Client.cdpKey("ws://127.0.0.1:9222/devtools/page/AAA"),
                    dev.domcomposite.Client.cdpKey("ws://127.0.0.1:9222/devtools/page/BBB")
            );
            assertTrue(dev.domcomposite.Client.cdpKey("http://127.0.0.1:9222/devtools/page/AAA").contains("AAA"));
            CompositeRuntime.use(temp, Map.of("COMPOSITE_BIN", binary.toString(), "pkb_composite", "on"),
                    getClass().getClassLoader(), temp);
            assertTrue(CompositeRuntime.primary(), String.valueOf(CompositeRuntime.current()));
            PagedDriver browser = new PagedDriver("http://127.0.0.1:9222", Map.of("tab-a", "AAA", "tab-b", "BBB"));
            PageSurfaces.manage(browser, "chrome-headed-quiet", true);
            WindowRegistry.switchTo(temp, "run", "tab-a");
            String first = CompositeRuntime.focusedSessionId();
            WindowRegistry.switchTo(temp, "run", "tab-b");
            String second = CompositeRuntime.focusedSessionId();
            assertTrue(first != null && second != null && !first.equals(second), first + " / " + second);
            WindowRegistry.switchTo(temp, "run", "tab-a");
            assertEquals(first, CompositeRuntime.focusedSessionId());
            PagedDriver other = new PagedDriver("http://127.0.0.1:9333", Map.of("only", "CCC"));
            PageSurfaces.manage(other, "edge-headed-quiet", true);
            WindowRegistry.switchTo(temp, "run", "only");
            String third = CompositeRuntime.focusedSessionId();
            assertFalse(first.equals(third) || second.equals(third), third);
            String trace = Files.readString(log);
            assertTrue(trace.contains("/devtools/page/AAA"), trace);
            assertTrue(trace.contains("/devtools/page/BBB"), trace);
            assertTrue(trace.contains("/devtools/page/CCC") || trace.contains("9333"), trace);
            int creates = 0;
            for (String line : trace.split("\n")) if (line.contains("session") && line.contains("create")) creates++;
            assertTrue(creates >= 3, trace);
        } finally {
            CompositeRuntime.resetForTests();
        }
    }

    @Test
    void hideFailsWhenTheHighlightedTabCannotBeSelected() throws Exception {
        AtomicReference<String> current = new AtomicReference<>("tab-1");
        List<String> hidden = new ArrayList<>();
        WebDriver live = tabsCdp(current, new LinkedHashSet<>(List.of("tab-1", "tab-2")), (method, params) -> {
            if ("Page.getFrameTree".equals(method)) {
                return Map.of("frameTree", Map.of("frame", Map.of("id", "a")));
            }
            if ("Overlay.hideHighlight".equals(method)) hidden.add(current.get());
            if ("Overlay.highlightNode".equals(method)) return Map.of();
            return Map.of();
        }, spec -> Map.of("ok", true, "removed", 1));
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.bindFrame(live, "tab-1", "a");
        PageSurfaces.highlight(null, "a:1", "yellow", null, null, null, null, null);
        current.set("tab-2");
        BLOCK_WINDOW.set(true);
        hidden.clear();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> PageSurfaces.unhighlight(null, "a:1"));
        assertTrue(failure.getMessage().contains("hideHighlight"), failure.getMessage());
        assertFalse(hidden.contains("tab-2"), hidden.toString());
    }

    @Test
    void borderShorthandAndTextColorUseDomFallback() throws Exception {
        Path dir = Files.createTempDirectory("pkb-style");
        Path state = dir.resolve("state.json");
        Path script = dir.resolve("context-dom.js");
        try (InputStream stream = AgentLiveFixTest.class.getResourceAsStream("context-dom.js")) {
            if (stream == null) throw new IOException("missing context-dom.js");
            Files.write(script, stream.readAllBytes());
        }
        ObjectMapper json = new ObjectMapper();
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("html", "<b id=\"save\" style=\"color:blue\">save</b>");
        top.put("undo", Map.of());
        top.put("events", List.of());
        top.put("bound", false);
        writeContext(json, state, "top", Map.of("html", "", "undo", Map.of(), "events", List.of(), "bound", false), top, Map.of());
        WebDriver raw = contextDriver(script, state);
        List<String> commands = new ArrayList<>();
        WebDriver live = (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class, CdpProbe.class},
                (proxy, method, args) -> {
                    if ("executeCdpCommand".equals(method.getName()) && args != null && args.length > 0) {
                        commands.add(String.valueOf(args[0]));
                    }
                    try {
                        return method.invoke(raw, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                        if (cause instanceof RuntimeException runtime) throw runtime;
                        if (cause instanceof Error error) throw error;
                        throw new IllegalStateException(cause);
                    }
                }
        );
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.focus(live);
        Map<String, Object> border = PageSurfaces.highlight(null, "save", "yellow", "border:9px dashed #abc", null, null, null, null);
        assertEquals("style", border.get("cdp"));
        assertTrue(String.valueOf(border.get("styleNote")).toLowerCase().contains("reversible dom"), String.valueOf(border));
        String painted = documentHtml(json.readValue(state.toFile(), Map.class), "top");
        assertTrue(painted.contains("9px") && painted.contains("dashed") && painted.contains("#abc"), painted);
        assertTrue(painted.contains("data-pkb-original-style=\"color:blue\""), painted);
        assertFalse(commands.contains("Overlay.highlightNode"), commands.toString());
        Map<String, Object> color = PageSurfaces.highlight(null, "save", "yellow", "color:#112233", null, null, null, null);
        assertEquals("style", color.get("cdp"));
        assertTrue(String.valueOf(color.get("styleNote")).toLowerCase().contains("reversible dom"), String.valueOf(color));
        String recolored = documentHtml(json.readValue(state.toFile(), Map.class), "top");
        assertTrue(recolored.contains("color:#112233"), recolored);
        assertTrue(recolored.contains("data-pkb-original-style=\"color:blue\""), recolored);
        assertFalse(commands.contains("Overlay.highlightNode"), commands.toString());
        PageSurfaces.unhighlight(null, "save");
        String restored = documentHtml(json.readValue(state.toFile(), Map.class), "top");
        assertTrue(restored.contains("style=\"color:blue\""), restored);
        assertFalse(restored.contains("data-pkb-style"), restored);
        assertFalse(restored.contains("#112233"), restored);
        assertFalse(restored.contains("dashed"), restored);
        assertTrue(commands.contains("Overlay.hideHighlight"), commands.toString());
        assertFalse(commands.contains("Overlay.highlightNode"), commands.toString());
    }

    @Test
    void childFrameSurfacesStayRemovableAndPollable() throws Exception {
        Path dir = Files.createTempDirectory("pkb-context");
        Path state = dir.resolve("state.json");
        Path script = dir.resolve("context-dom.js");
        try (InputStream stream = AgentLiveFixTest.class.getResourceAsStream("context-dom.js")) {
            if (stream == null) throw new IOException("missing context-dom.js");
            Files.write(script, stream.readAllBytes());
        }
        ObjectMapper json = new ObjectMapper();
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("html", "<b data-pkb-id=\"pkb-1\" data-pkb-agent=\"agent_injected\" data-pkb-kind=\"html\" data-pkb-sentence=\"s\">inside</b>");
        child.put("undo", Map.of());
        child.put("events", List.of(Map.of("type", "click", "id", "pkb-1", "text", "inside")));
        child.put("bound", true);
        writeContext(json, state, "child-1", child, Map.of("html", "", "undo", Map.of(), "events", List.of(), "bound", false), Map.of("name", "kid"));
        WebDriver live = contextDriver(script, state);
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.bindFrame(live, "tab-a", "child-1");
        Map<String, Object> removed = PageSurfaces.remove(null, "pkb-1");
        assertEquals(true, removed.get("removed"));
        Map<String, Object> afterRemove = json.readValue(state.toFile(), Map.class);
        String childHtml = documentHtml(afterRemove, "child-1");
        assertFalse(childHtml.contains("pkb-1"), childHtml);
        assertFalse(childHtml.contains("inside"), childHtml);
        List<?> trace = (List<?>) afterRemove.get("trace");
        assertFalse(trace.isEmpty(), String.valueOf(trace));
        assertTrue(String.valueOf(trace.get(0)).startsWith("top:"), String.valueOf(trace));
        assertTrue(trace.stream().anyMatch(row -> String.valueOf(row).startsWith("child-1:")), String.valueOf(trace));

        child.put("events", List.of(Map.of("type", "click", "id", "pkb-1", "text", "inside")));
        writeContext(json, state, "child-1", child, Map.of("html", "", "undo", Map.of(), "events", List.of(), "bound", false), Map.of("name", "kid"));
        List<Map<String, Object>> events = PageSurfaces.pollInput(temp, "frames");
        assertEquals(1, events.size());
        assertEquals("click", events.get(0).get("type"));
        Map<String, Object> afterPoll = json.readValue(state.toFile(), Map.class);
        List<?> pollTrace = (List<?>) afterPoll.get("trace");
        assertTrue(String.valueOf(pollTrace.get(0)).startsWith("top:"), String.valueOf(pollTrace));
        assertTrue(pollTrace.stream().anyMatch(row -> String.valueOf(row).startsWith("child-1:drain")), String.valueOf(pollTrace));

        Map<String, Object> styled = new LinkedHashMap<>();
        styled.put("html", "<b data-pkb-style=\"style_modified\" data-pkb-original-style=\"\" data-pkb-css=\"border:9px dashed #abc\" style=\"border:9px dashed #abc\">x</b>");
        styled.put("undo", Map.of());
        styled.put("events", List.of());
        styled.put("bound", false);
        writeContext(json, state, "child-1", styled, Map.of("html", "", "undo", Map.of(), "events", List.of(), "bound", false), Map.of("name", "kid"));
        PageSurfaces.unhighlight(null, null);
        String cleared = documentHtml(json.readValue(state.toFile(), Map.class), "child-1");
        assertFalse(cleared.contains("data-pkb-style"), cleared);
        assertFalse(cleared.contains("dashed"), cleared);
    }

    @Test
    void quitPropagatesCleanupFailureAfterReleasingTheDriver() {
        WebDriver live = scriptDriver("tab-a", spec -> Map.of("ok", false, "error", "helper broke"), false);
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        IllegalStateException failure = assertThrows(IllegalStateException.class, AgentDriverHooks::quit);
        assertTrue(failure.getMessage().contains("helper broke"), failure.getMessage());
        assertTrue(PageSurfaces.managed().isEmpty());
    }

    @Test
    void helperThatDoesNotConfirmIsNotSuccess() {
        WebDriver live = scriptDriver("tab-a", spec -> {
            return null;
        }, false);
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.focus(live);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                PageSurfaces.highlight(null, "save", "yellow", null, null, null, null, null));
        assertTrue(failure.getMessage().contains("confirm") || failure.getMessage().contains("highlight"), failure.getMessage());
    }

    @Test
    void tabEnumerationFailureFailsCleanup() {
        WebDriver live = (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWindowHandles" -> { throw new IllegalStateException("handles unavailable"); }
                    case "getWindowHandle" -> "tab-a";
                    case "switchTo" -> proxy;
                    case "executeScript" -> Map.of("ok", true, "removed", 1);
                    default -> defaultValue(method.getReturnType());
                }
        );
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> PageSurfaces.cleanup(null));
        assertTrue(failure.getMessage().contains("tab enumeration"), failure.getMessage());
    }

    @Test
    void cleanupPropagatesHelperFailure() {
        WebDriver live = scriptDriver("tab-a", spec -> Map.of("ok", false, "error", "helper broke"), false);
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> PageSurfaces.cleanup(null));
        assertTrue(failure.getMessage().contains("helper broke"), failure.getMessage());
    }

    @Test
    void livePageIdsDoNotCollideAcrossRunLedgersOrRestartAtAUsedId() throws Exception {
        WebDriver live = scriptDriver("tab-a", spec -> Map.of("ok", true), false);
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.focus(live);
        Map<String, Object> first = PageSurfaces.place(null, "append", null, null, "<b>a</b>", "html", null, null, null, temp.resolve("run-a"));
        Map<String, Object> second = PageSurfaces.place(null, "append", null, null, "<b>b</b>", "html", null, null, null, temp.resolve("run-b"));
        assertEquals("pkb-1", first.get("id"));
        assertEquals("pkb-2", second.get("id"));
        Path aged = temp.resolve("aged/placements.json");
        Files.createDirectories(aged.getParent());
        Files.writeString(aged, "{\"pkb-1\":{\"sha256\":\"abc\",\"summary\":\"old\"}}");
        assertEquals("pkb-2", PageSurfaces.allocateSurfaceId(aged));
        String stored = Files.readString(aged);
        assertTrue(stored.contains("\"next\":3") || stored.contains("\"next\": 3"), stored);
    }

    @Test
    void surfaceIdFollowsTheDestinationPageNotTheFocusedPage() throws Exception {
        AtomicInteger focusedCalls = new AtomicInteger();
        AtomicReference<String> destinationId = new AtomicReference<>();
        WebDriver focused = scriptDriver("tab-a", spec -> {
            focusedCalls.incrementAndGet();
            return Map.of("ok", true);
        }, false);
        WebDriver destination = scriptDriver("tab-b", spec -> {
            destinationId.set(String.valueOf(spec.get("id")));
            return Map.of("ok", true);
        }, false);
        PageSurfaces.manage(focused, "chrome-headed-quiet", true);
        PageSurfaces.manage(destination, "edge-headed-quiet", true);
        PageSurfaces.focus(destination);
        Map<String, Object> seeded = PageSurfaces.place(null, "append", null, null, "<b>seed</b>", "html", null, null, null, temp.resolve("run-b"));
        assertEquals("pkb-1", seeded.get("id"));
        PageSurfaces.focus(focused);
        PageSurfaces.bindHandle(destination, "tab-b", "frame-b:15");
        Map<String, Object> placed = PageSurfaces.place(null, "append", "frame-b:15", null, "<b>again</b>", "html", null, null, null, temp.resolve("run-a"));
        assertEquals("pkb-2", placed.get("id"));
        assertEquals("pkb-2", destinationId.get());
        assertEquals(0, focusedCalls.get());
    }

    @Test
    void scenarioRunCleanUpItselfRemovesNativeSurfaces() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        tools.dscode.control.protocol.AgentSurfaceEnd.register(hits::incrementAndGet);
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        Object state = unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, io.cucumber.core.runner.CurrentScenarioState.class);
        java.lang.reflect.Field debug = io.cucumber.core.runner.CurrentScenarioState.class.getDeclaredField("debugBrowser");
        debug.setAccessible(true);
        debug.setBoolean(state, true);
        io.cucumber.core.runner.CurrentScenarioState.class.getMethod("scenarioRunCleanUp").invoke(state);
        assertTrue(hits.get() >= 1);
    }

    @Test
    void queryOriginFollowsTheThreadSessionNotTheGlobalJoinPointer() throws Exception {
        Path binary = stdioBinary(temp.resolve("origin-composite"), temp.resolve("origin.log"));
        try {
            CompositeRuntime.use(temp, Map.of("COMPOSITE_BIN", binary.toString(), "pkb_composite", "on"),
                    getClass().getClassLoader(), temp);
            RemoteWebDriver first = remote("http://127.0.0.1:9222");
            RemoteWebDriver second = remote("http://127.0.0.1:9333");
            PageSurfaces.manage(first, "chrome-headed-quiet", true);
            PageSurfaces.manage(second, "edge-headed-quiet", true);
            CompositeRuntime.attach(first);
            String firstId = CompositeRuntime.focusedSessionId();
            CompositeRuntime.attach(second);
            CompositeRuntime.client().focusSession(firstId);
            CompositeRuntime.joinForTests("http://127.0.0.1:9333");
            assertTrue(String.valueOf(CompositeRuntime.joinedForTests()).contains("9333"));
            assertEquals(first, PageSurfaces.ownerForQuery());
            assertTrue(String.valueOf(CompositeRuntime.focusedCdp()).contains("9222"), CompositeRuntime.focusedCdp());
        } finally {
            CompositeRuntime.resetForTests();
        }
    }

    @Test
    void failedPageSessionDoesNotClaimTheGlobalJoinPointer() throws Exception {
        Path binary = stdioBinary(temp.resolve("fail-composite"), temp.resolve("fail.log"), true);
        try {
            CompositeRuntime.use(temp, Map.of("COMPOSITE_BIN", binary.toString(), "pkb_composite", "on"),
                    getClass().getClassLoader(), temp);
            PagedDriver browser = new PagedDriver("http://127.0.0.1:9222", Map.of("tab-a", "AAA", "tab-b", "BBB"));
            PageSurfaces.manage(browser, "chrome-headed-quiet", true);
            CompositeRuntime.attach(browser);
            String joined = CompositeRuntime.joinedForTests();
            assertTrue(joined != null && joined.contains("9222") && !joined.contains("devtools/page"), joined);
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> WindowRegistry.switchTo(temp, "run", "tab-b"));
            assertTrue(refused.getMessage().contains("page session failed"), refused.getMessage());
            assertEquals("tab-a", browser.getWindowHandle());
            String after = String.valueOf(CompositeRuntime.joinedForTests());
            assertFalse(after.contains("BBB") || after.contains("devtools/page"), after);
            assertTrue(after.contains("9222"), after);
            assertEquals(CompositeRuntime.focusedSessionId(), CompositeRuntime.client().currentSession().id());
        } finally {
            CompositeRuntime.resetForTests();
        }
    }

    @Test
    void rankedHandleStoresBrowserAndTabWithoutVisualGeometry() throws Exception {
        WebDriver owner = scriptDriver("tab-b", spec -> Map.of("ok", true), false);
        PageSurfaces.manage(owner, "edge-headed-quiet", true);
        com.fasterxml.jackson.databind.JsonNode node = new ObjectMapper().readTree("""
                {"handle":"frame-b:15","frame_id":"frame-b","backend_node_id":15}
                """);
        tools.dscode.common.composite.CompositeWebElement.fromQuery("//b", node, 0, owner, "tab-b");
        PageSurfaces.HandleRef ref = PageSurfaces.HandleRef.parse("frame-b:15");
        assertEquals("frame-b", ref.frameId());
        assertEquals(15, ref.backend());
        assertEquals("tab-b", ref.tab());
        assertFalse(ref.browserId() == null || ref.browserId().isBlank(), ref.toString());
    }

    @Test
    void publicSwitchDoesNotReportSuccessWhenThePageSessionFails() throws Exception {
        Path binary = stdioBinary(temp.resolve("public-switch-composite"), temp.resolve("public-switch.log"), "BBB");
        try {
            CompositeRuntime.use(temp, Map.of("COMPOSITE_BIN", binary.toString(), "pkb_composite", "on"),
                    getClass().getClassLoader(), temp);
            PagedDriver browser = new PagedDriver("http://127.0.0.1:9222", Map.of("tab-a", "AAA", "tab-b", "BBB"));
            PageSurfaces.manage(browser, "chrome-headed-quiet", true);
            CompositeRuntime.attach(browser);
            WindowRegistry.Handle selected = WindowRegistry.switchTo(temp, "run", "tab-a");
            assertTrue(selected.open());
            assertEquals("tab", selected.kind());
            assertEquals("tab-a", selected.handle());
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> WindowRegistry.switchTo(temp, "run", "tab-b"));
            assertTrue(failure.getMessage().contains("page session failed"), failure.getMessage());
            assertEquals("tab-a", browser.getWindowHandle());
            assertEquals("tab-a", PageSurfaces.focusedTabOf(browser));
            assertTrue(String.valueOf(CompositeRuntime.focusedCdp()).contains("AAA"), CompositeRuntime.focusedCdp());
            assertFalse(String.valueOf(CompositeRuntime.joinedForTests()).contains("BBB"), CompositeRuntime.joinedForTests());
            PagedDriver blind = new PagedDriver("http://127.0.0.1:9444", Map.of("only", "CCC"));
            blind.suppressTargetInfo(true);
            PageSurfaces.manage(blind, "edge-headed-quiet", true);
            String window = "window:" + PageSurfaces.managed().get(1).id();
            IllegalStateException windowFailure = assertThrows(IllegalStateException.class,
                    () -> WindowRegistry.switchTo(temp, "run", window));
            assertTrue(windowFailure.getMessage().contains("page session failed"), windowFailure.getMessage());
            assertNull(PageSurfaces.rememberedTargetForTests(blind, "only"));
        } finally {
            CompositeRuntime.resetForTests();
        }
    }

    @Test
    void activateDoesNotClaimATabWhosePageTargetIsMissing() throws Exception {
        Path binary = stdioBinary(temp.resolve("missing-target-composite"), temp.resolve("missing-target.log"));
        try {
            CompositeRuntime.use(temp, Map.of("COMPOSITE_BIN", binary.toString(), "pkb_composite", "on"),
                    getClass().getClassLoader(), temp);
            PagedDriver browser = new PagedDriver("http://127.0.0.1:9222", Map.of("tab-a", "AAA", "tab-b", "BBB"));
            PageSurfaces.manage(browser, "chrome-headed-quiet", true);
            CompositeRuntime.attach(browser);
            WindowRegistry.switchTo(temp, "run", "tab-a");
            String sessionA = CompositeRuntime.focusedSessionId();
            browser.suppressTargetOn("tab-b");
            assertFalse(PageSurfaces.activate(browser, "tab-b"));
            assertEquals("tab-a", browser.getWindowHandle());
            assertEquals("tab-a", PageSurfaces.focusedTabOf(browser));
            assertEquals(sessionA, CompositeRuntime.focusedSessionId());
            assertTrue(String.valueOf(CompositeRuntime.focusedCdp()).contains("AAA"), CompositeRuntime.focusedCdp());
            assertFalse(String.valueOf(CompositeRuntime.focusedCdp()).contains("BBB"), CompositeRuntime.focusedCdp());
            assertNull(PageSurfaces.rememberedTargetForTests(browser, "tab-b"));
            browser.suppressTargetOn(null);
            WindowRegistry.switchTo(temp, "run", "tab-b");
            assertEquals("BBB", PageSurfaces.rememberedTargetForTests(browser, "tab-b"));
            WindowRegistry.switchTo(temp, "run", "tab-a");
            browser.suppressTargetOn("tab-b");
            assertTrue(PageSurfaces.activate(browser, "tab-b"));
            assertEquals("tab-b", browser.getWindowHandle());
            assertEquals("tab-b", PageSurfaces.focusedTabOf(browser));
            assertTrue(String.valueOf(CompositeRuntime.focusedCdp()).contains("BBB"), CompositeRuntime.focusedCdp());
        } finally {
            CompositeRuntime.resetForTests();
        }
    }

    @Test
    void debuggerAddressOnlyActivateDoesNotClaimATabWhosePageTargetIsMissing() throws Exception {
        Path binary = stdioBinary(temp.resolve("address-only-composite"), temp.resolve("address-only.log"));
        try {
            CompositeRuntime.use(temp, Map.of("COMPOSITE_BIN", binary.toString(), "pkb_composite", "on"),
                    getClass().getClassLoader(), temp);
            PagedDriver browser = new PagedDriver("127.0.0.1:9222", Map.of("tab-a", "AAA", "tab-b", "BBB"), true);
            assertNull(browser.getCapabilities().getCapability("se:cdp"));
            Object options = browser.getCapabilities().getCapability("goog:chromeOptions");
            assertTrue(options instanceof Map<?, ?> map
                    && "127.0.0.1:9222".equals(String.valueOf(map.get("debuggerAddress"))), String.valueOf(options));
            PageSurfaces.manage(browser, "chrome-headed-quiet", true);
            CompositeRuntime.attach(browser);
            WindowRegistry.switchTo(temp, "run", "tab-a");
            String sessionA = CompositeRuntime.focusedSessionId();
            browser.suppressTargetOn("tab-b");
            assertFalse(PageSurfaces.activate(browser, "tab-b"));
            assertEquals("tab-a", browser.getWindowHandle());
            assertEquals("tab-a", PageSurfaces.focusedTabOf(browser));
            assertEquals(sessionA, CompositeRuntime.focusedSessionId());
            assertTrue(String.valueOf(CompositeRuntime.focusedCdp()).contains("AAA"), CompositeRuntime.focusedCdp());
            assertFalse(String.valueOf(CompositeRuntime.focusedCdp()).contains("BBB"), CompositeRuntime.focusedCdp());
            assertNull(PageSurfaces.rememberedTargetForTests(browser, "tab-b"));
            browser.suppressTargetOn(null);
            WindowRegistry.switchTo(temp, "run", "tab-b");
            assertEquals("BBB", PageSurfaces.rememberedTargetForTests(browser, "tab-b"));
            WindowRegistry.switchTo(temp, "run", "tab-a");
            browser.suppressTargetOn("tab-b");
            assertTrue(PageSurfaces.activate(browser, "tab-b"));
            assertEquals("tab-b", browser.getWindowHandle());
            assertEquals("tab-b", PageSurfaces.focusedTabOf(browser));
            assertTrue(String.valueOf(CompositeRuntime.focusedCdp()).contains("BBB"), CompositeRuntime.focusedCdp());
        } finally {
            CompositeRuntime.resetForTests();
        }
    }

    @Test
    void closeDoesNotLeaveTheClosedTabsSessionFocused() throws Exception {
        Path binary = stdioBinary(temp.resolve("close-survivor-composite"), temp.resolve("close-survivor.log"), "AAA");
        try {
            CompositeRuntime.use(temp, Map.of("COMPOSITE_BIN", binary.toString(), "pkb_composite", "on"),
                    getClass().getClassLoader(), temp);
            PagedDriver browser = new PagedDriver("http://127.0.0.1:9222", Map.of("tab-a", "AAA", "tab-b", "BBB"));
            PageSurfaces.manage(browser, "chrome-headed-quiet", true);
            CompositeRuntime.attach(browser);
            WindowRegistry.switchTo(temp, "run", "tab-b");
            assertTrue(String.valueOf(CompositeRuntime.focusedCdp()).contains("BBB"), CompositeRuntime.focusedCdp());
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> WindowRegistry.close(temp, "run", "tab-b"));
            assertTrue(failure.getMessage().contains("page session failed"), failure.getMessage());
            assertEquals("tab-a", browser.getWindowHandle());
            assertFalse(browser.getWindowHandles().contains("tab-b"));
            assertNotEquals("tab-b", PageSurfaces.focusedTabOf(browser));
            String cdp = String.valueOf(CompositeRuntime.focusedCdp());
            assertFalse(cdp.contains("BBB"), cdp);
            assertFalse(cdp.contains("/devtools/page/"), cdp);
            assertEquals(1, PageSurfaces.managed().size());
        } finally {
            CompositeRuntime.resetForTests();
        }
    }

    @Test
    void closingATabFocusesTheSurvivingEngineSession() {
        AtomicReference<String> current = new AtomicReference<>("tab-1");
        LinkedHashSet<String> tabs = new LinkedHashSet<>(List.of("tab-1", "tab-2"));
        WebDriver live = closingTabs(current, tabs);
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.focus(live);
        assertEquals("tab-1", PageSurfaces.focusedTabOf(live));
        WindowRegistry.close(temp, "run", "tab-1");
        assertEquals("tab-2", current.get());
        assertEquals("tab-2", PageSurfaces.focusedTabOf(live));
    }

    @Test
    void frameMarkAndRestoreKeepAnOriginallyEmptyNameAndBridge() throws Exception {
        Path dir = Files.createTempDirectory("pkb-frame-restore");
        Path script = dir.resolve("frame.js");
        Files.writeString(script, """
                const fs = require('fs');
                const input = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
                function Element() {
                  this.nodeType = 1;
                  this.tagName = 'IFRAME';
                  this.attrs = {};
                }
                Element.prototype.hasAttribute = function(n) { return Object.prototype.hasOwnProperty.call(this.attrs, n); };
                Element.prototype.getAttribute = function(n) { return this.hasAttribute(n) ? this.attrs[n] : null; };
                Element.prototype.setAttribute = function(n, v) { this.attrs[n] = String(v); };
                Element.prototype.removeAttribute = function(n) { delete this.attrs[n]; };
                const frame = new Element();
                frame.setAttribute('name', '');
                frame.setAttribute('data-pkb-bridge', '');
                const mark = eval('(' + input.mark + ')');
                const restore = eval('(' + input.restore + ')');
                const marked = JSON.parse(mark.call(frame, 'temp-name'));
                const restored = restore.call(frame, marked);
                const dropped = new Element();
                dropped.setAttribute('id', 'kept');
                const markedDrop = JSON.parse(mark.call(dropped, 'temp-name'));
                restore.call(dropped, markedDrop);
                const broken = restore.call(frame, null);
                fs.writeFileSync(process.argv[3], JSON.stringify({
                  marked,
                  restored,
                  name: frame.getAttribute('name'),
                  hadName: frame.hasAttribute('name'),
                  bridge: frame.getAttribute('data-pkb-bridge'),
                  hadBridge: frame.hasAttribute('data-pkb-bridge'),
                  droppedName: dropped.hasAttribute('name'),
                  broken
                }));
                """);
        Path in = dir.resolve("in.json");
        Path out = dir.resolve("out.json");
        new ObjectMapper().writeValue(in.toFile(), Map.of(
                "mark", PageSurfaces.frameMarkSource(),
                "restore", PageSurfaces.frameRestoreSource()
        ));
        Process process = new ProcessBuilder("node", script.toString(), in.toString(), out.toString()).redirectErrorStream(true).start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(finished && process.exitValue() == 0 && Files.isRegularFile(out), output);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(out.toFile(), Map.class);
        assertEquals(Boolean.TRUE, parsed.get("restored"));
        assertEquals(Boolean.TRUE, parsed.get("hadName"));
        assertEquals("", parsed.get("name"));
        assertEquals(Boolean.TRUE, parsed.get("hadBridge"));
        assertEquals("", parsed.get("bridge"));
        assertEquals(Boolean.FALSE, parsed.get("droppedName"));
        assertEquals(Boolean.FALSE, parsed.get("broken"));
    }

    @Test
    void frameRestoreFailureFailsTheCommand() {
        WebDriver live = cdpDriver("tab-a", (method, params) -> {
            if ("Page.getFrameTree".equals(method)) {
                return Map.of("frameTree", Map.of(
                        "frame", Map.of("id", "main"),
                        "childFrames", List.of(Map.of("frame", Map.of("id", "child-1")))
                ));
            }
            if ("DOM.getFrameOwner".equals(method)) return Map.of("backendNodeId", 42);
            if ("DOM.resolveNode".equals(method)) return Map.of("object", Map.of("objectId", "obj-1"));
            if ("Runtime.callFunctionOn".equals(method)) {
                String function = String.valueOf(params.get("functionDeclaration"));
                if (function.contains("removeAttribute")) return Map.of("result", Map.of("value", false));
                return Map.of("result", Map.of("value", "{\"name\":\"temp\",\"changedName\":true,\"previousBridge\":null}"));
            }
            return Map.of();
        }, spec -> Map.of("ok", true));
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.bindFrame(live, "tab-a", "child-1");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                PageSurfaces.place(null, "replace", "child-1:15", null, "<b>x</b>", "html", null, null, null));
        assertTrue(failure.getMessage().toLowerCase().contains("frame"), failure.getMessage());
    }

    @Test
    void sameBrowserTabsDoNotMintASecondSurfaceId() throws Exception {
        List<String> seen = new ArrayList<>();
        PagedDriver browser = new PagedDriver("http://127.0.0.1:9222", Map.of("tab-a", "AAA", "tab-b", "BBB"), seen);
        PageSurfaces.manage(browser, "chrome-headed-quiet", true);
        browser.switchTo().window("tab-b");
        PageSurfaces.focus(browser);
        Map<String, Object> seeded = PageSurfaces.place(null, "append", null, null, "<b>seed</b>", "html", null, null, null, temp.resolve("run-b"));
        assertEquals("pkb-1", seeded.get("id"));
        browser.switchTo().window("tab-a");
        PageSurfaces.focus(browser);
        PageSurfaces.bindHandle(browser, "tab-b", "BBB:15");
        Map<String, Object> placed = PageSurfaces.place(null, "append", "BBB:15", null, "<b>again</b>", "html", null, null, null, temp.resolve("run-a"));
        assertEquals("pkb-2", placed.get("id"));
        assertEquals(List.of("tab-b:pkb-1", "tab-b:pkb-2"), seen.stream().filter(row -> row.contains("pkb-")).toList());
        assertTrue(seen.stream().anyMatch(row -> row.equals("tab-b:Target.getTargetInfo")), seen.toString());
        assertEquals(1, seen.stream().filter(row -> row.equals("tab-b:pkb-1")).count());
        String tabB = browser.document("tab-b");
        assertEquals(2, count(tabB, "<div "));
        assertEquals(1, count(tabB, ">seed<"));
        assertEquals(1, count(tabB, ">again<"));
        assertEquals(2, count(tabB, "data-pkb-id=\"pkb-1\""));
        assertEquals(2, count(tabB, "data-pkb-id=\"pkb-2\""));
        assertFalse(browser.document("tab-a").contains("data-pkb-id"), browser.document("tab-a"));
        assertEquals("BBB", PageSurfaces.rememberedTargetForTests(browser, "tab-b"));
    }

    @Test
    void unknownCurrentTabDoesNotBorrowTheActivePageTarget() throws Exception {
        List<String> seen = new ArrayList<>();
        PagedDriver browser = new PagedDriver("http://127.0.0.1:9222", Map.of("tab-a", "AAA", "tab-b", "BBB"), seen);
        PageSurfaces.manage(browser, "chrome-headed-quiet", true);
        browser.switchTo().window("tab-b");
        PageSurfaces.focus(browser);
        Map<String, Object> seeded = PageSurfaces.place(null, "append", null, null, "<b>seed</b>", "html", null, null, null, temp.resolve("run-b"));
        assertEquals("pkb-1", seeded.get("id"));
        assertEquals("BBB", PageSurfaces.rememberedTargetForTests(browser, "tab-b"));
        browser.switchTo().window("tab-a");
        PageSurfaces.focus(browser);
        PageSurfaces.bindHandle(browser, "tab-b", "BBB:15");
        browser.blindCurrentHandleOnceAndIgnoreNextSwitch();
        Map<String, Object> placed = PageSurfaces.place(null, "append", "BBB:15", null, "<b>again</b>", "html", null, null, null, temp.resolve("run-a"));
        assertEquals("pkb-2", placed.get("id"));
        assertEquals("BBB", PageSurfaces.rememberedTargetForTests(browser, "tab-b"));
        assertNotEquals("AAA", PageSurfaces.rememberedTargetForTests(browser, "tab-b"));
        String tabB = browser.document("tab-b");
        assertEquals(2, count(tabB, "data-pkb-id=\"pkb-1\""));
        assertEquals(2, count(tabB, "data-pkb-id=\"pkb-2\""));
        assertEquals(1, count(tabB, ">again<"));
        assertFalse(browser.document("tab-a").contains("pkb-1"), browser.document("tab-a"));
        assertFalse(seen.stream().anyMatch(row -> row.equals("tab-a:pkb-1") || row.equals("tab-a:pkb-2")), seen.toString());
    }

    @Test
    void blankPageTargetReusesRememberedDestinationTarget() throws Exception {
        PagedDriver browser = new PagedDriver("http://127.0.0.1:9222", Map.of("tab-a", "AAA", "tab-b", "BBB"));
        PageSurfaces.manage(browser, "chrome-headed-quiet", true);
        browser.switchTo().window("tab-b");
        PageSurfaces.focus(browser);
        Map<String, Object> seeded = PageSurfaces.place(null, "append", null, null, "<b>seed</b>", "html", null, null, null, temp.resolve("run-b"));
        assertEquals("pkb-1", seeded.get("id"));
        assertEquals("BBB", PageSurfaces.rememberedTargetForTests(browser, "tab-b"));
        browser.suppressTargetInfo(true);
        Map<String, Object> placed = PageSurfaces.place(null, "append", null, null, "<b>again</b>", "html", null, null, null, temp.resolve("run-a"));
        assertEquals("pkb-2", placed.get("id"));
        assertEquals("BBB", PageSurfaces.rememberedTargetForTests(browser, "tab-b"));
        String tabB = browser.document("tab-b");
        assertEquals(2, count(tabB, "data-pkb-id=\"pkb-1\""));
        assertEquals(2, count(tabB, "data-pkb-id=\"pkb-2\""));
        assertFalse(browser.document("tab-a").contains("data-pkb-id"), browser.document("tab-a"));
    }

    @Test
    void failedPageActivationDoesNotClaimTheDestinationTab() throws Exception {
        Path binary = stdioBinary(temp.resolve("fail-tab-composite"), temp.resolve("fail-tab.log"), "BBB");
        try {
            CompositeRuntime.use(temp, Map.of("COMPOSITE_BIN", binary.toString(), "pkb_composite", "on"),
                    getClass().getClassLoader(), temp);
            PagedDriver browser = new PagedDriver("http://127.0.0.1:9222", Map.of("tab-a", "AAA", "tab-b", "BBB"));
            PageSurfaces.manage(browser, "chrome-headed-quiet", true);
            CompositeRuntime.attach(browser);
            WindowRegistry.switchTo(temp, "run", "tab-a");
            String sessionA = CompositeRuntime.focusedSessionId();
            assertEquals("tab-a", PageSurfaces.focusedTabOf(browser));
            assertTrue(String.valueOf(CompositeRuntime.focusedCdp()).contains("AAA"), CompositeRuntime.focusedCdp());
            assertFalse(PageSurfaces.activate(browser, "tab-b"));
            assertEquals("tab-a", PageSurfaces.focusedTabOf(browser));
            assertEquals("tab-a", browser.getWindowHandle());
            assertEquals(sessionA, CompositeRuntime.focusedSessionId());
            String joined = String.valueOf(CompositeRuntime.joinedForTests());
            assertFalse(joined.contains("BBB"), joined);
            assertTrue(String.valueOf(CompositeRuntime.focusedCdp()).contains("AAA"), CompositeRuntime.focusedCdp());
        } finally {
            CompositeRuntime.resetForTests();
        }
    }

    @Test
    void firstQueryDoesNotInheritAnotherThreadsJoinedCdp() throws Exception {
        Path binary = stdioBinary(temp.resolve("first-query-composite"), temp.resolve("first-query.log"));
        try {
            CompositeRuntime.use(temp, Map.of("COMPOSITE_BIN", binary.toString(), "pkb_composite", "on"),
                    getClass().getClassLoader(), temp);
            RemoteWebDriver browser = remote("http://127.0.0.1:9333");
            PageSurfaces.manage(browser, "edge-headed-quiet", true);
            CompositeRuntime.attach(browser);
            assertTrue(String.valueOf(CompositeRuntime.joinedForTests()).contains("9333"));
            AtomicReference<String> seen = new AtomicReference<>();
            AtomicReference<String> sessionCdp = new AtomicReference<>("");
            Thread other = new Thread(() -> {
                seen.set(String.valueOf(CompositeRuntime.focusedCdp()));
                dev.domcomposite.Client client = CompositeRuntime.ensureClient();
                dev.domcomposite.Session session = client == null ? null : client.currentSession();
                sessionCdp.set(session == null || session.cdp() == null ? "" : session.cdp());
            });
            other.start();
            other.join(10000);
            assertFalse(other.isAlive());
            assertFalse(String.valueOf(seen.get()).contains("9333"), seen.get());
            assertFalse(sessionCdp.get().contains("9333"), sessionCdp.get());
        } finally {
            CompositeRuntime.resetForTests();
        }
    }

    @Test
    void missingFrameTreeAndNullHandlesDoNotPretendCleanupVisitedDocuments() {
        AtomicInteger scripts = new AtomicInteger();
        WebDriver missingTree = cdpDriver("tab-a", (method, params) -> Map.of(), spec -> {
            scripts.incrementAndGet();
            return Map.of("ok", true, "removed", 1);
        });
        PageSurfaces.manage(missingTree, "chrome-headed-quiet", true);
        IllegalStateException missing = assertThrows(IllegalStateException.class, PageSurfaces::cleanupManaged);
        assertTrue(missing.getMessage().contains("frame enumeration"), missing.getMessage());
        PageSurfaces.clearDriver();
        scripts.set(0);
        WebDriver nullHandles = (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWindowHandles" -> null;
                    case "getWindowHandle" -> "tab-a";
                    case "switchTo" -> proxy;
                    case "executeScript" -> {
                        scripts.incrementAndGet();
                        yield Map.of("ok", true, "removed", 1);
                    }
                    default -> defaultValue(method.getReturnType());
                }
        );
        PageSurfaces.manage(nullHandles, "chrome-headed-quiet", true);
        IllegalStateException empty = assertThrows(IllegalStateException.class, PageSurfaces::cleanupManaged);
        assertTrue(empty.getMessage().contains("tab enumeration"), empty.getMessage());
        assertEquals(0, scripts.get());
    }

    @Test
    void scenarioCleanupStillRunsLaterHooksWhenInPageCleanupFails() throws Exception {
        AtomicInteger nativeEnds = new AtomicInteger();
        tools.dscode.control.protocol.AgentSurfaceEnd.register(nativeEnds::incrementAndGet);
        WebDriver live = scriptDriver("tab-a", spec -> Map.of("ok", false, "error", "helper broke"), false);
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        Object marker = new Object();
        tools.dscode.common.mappings.ParsingMap globals = tools.dscode.common.mappings.ParsingMap.getGlobalsParsingmap();
        if (globals.getMaps().get(tools.dscode.common.mappings.MapConfigurations.MapType.RUN_MAP).isEmpty()) {
            globals.addMaps(new tools.dscode.common.mappings.NodeMap(tools.dscode.common.mappings.MapConfigurations.MapType.RUN_MAP));
        }
        com.fasterxml.jackson.databind.node.ObjectNode config = new ObjectMapper().createObjectNode();
        config.put("_pathKey", "later-cleanup");
        config.put("cleanup", "not-a-step");
        io.cucumber.core.runner.CurrentScenarioState.currentScenarioState.remove();
        tools.dscode.coredefinitions.ObjectRegistrationSteps.registerObjectConfig(marker, config);
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        Object state = unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, io.cucumber.core.runner.CurrentScenarioState.class);
        java.lang.reflect.Field registry = io.cucumber.core.runner.CurrentScenarioState.class.getDeclaredField("cleanupRegistry");
        registry.setAccessible(true);
        registry.set(state, new ArrayList<>(List.of(marker)));
        java.lang.reflect.Field seen = io.cucumber.core.runner.CurrentScenarioState.class.getDeclaredField("cleanupRegistrySeen");
        seen.setAccessible(true);
        seen.set(state, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        boolean previous = tools.dscode.common.reporting.logging.CleanupTrace.ENABLED;
        tools.dscode.common.reporting.logging.CleanupTrace.ENABLED = true;
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(captured));
        try {
            io.cucumber.core.runner.CurrentScenarioState scenario =
                    (io.cucumber.core.runner.CurrentScenarioState) state;
            IllegalStateException failure = assertThrows(IllegalStateException.class, scenario::scenarioRunCleanUp);
            assertTrue(failure.getMessage().contains("helper broke"), failure.getMessage());
            assertTrue(nativeEnds.get() >= 1);
            assertTrue(captured.toString().contains("Cleanup failed"), captured.toString());
        } finally {
            System.setOut(original);
            tools.dscode.common.reporting.logging.CleanupTrace.ENABLED = previous;
        }
    }

    @Test
    void rankedShorthandUsesDomFallbackInsteadOfHighlightNode() throws Exception {
        Path dir = Files.createTempDirectory("pkb-ranked-style");
        Path state = dir.resolve("state.json");
        Path script = dir.resolve("context-dom.js");
        try (InputStream stream = AgentLiveFixTest.class.getResourceAsStream("context-dom.js")) {
            if (stream == null) throw new IOException("missing context-dom.js");
            Files.write(script, stream.readAllBytes());
        }
        ObjectMapper json = new ObjectMapper();
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("html", "<b id=\"save\" style=\"color:blue\">save</b>");
        top.put("undo", Map.of());
        top.put("events", List.of());
        top.put("bound", false);
        writeContext(json, state, "top", Map.of("html", "", "undo", Map.of(), "events", List.of(), "bound", false), top, Map.of());
        APPLY_CALL_TO_NODE.set(true);
        List<String> commands = new ArrayList<>();
        WebDriver raw = contextDriver(script, state);
        WebDriver live = (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class, CdpProbe.class},
                (proxy, method, args) -> {
                    if ("executeCdpCommand".equals(method.getName()) && args != null && args.length > 0) {
                        commands.add(String.valueOf(args[0]));
                        if ("Page.getFrameTree".equals(args[0])) {
                            return Map.of("frameTree", Map.of("frame", Map.of("id", "main")));
                        }
                    }
                    try {
                        return method.invoke(raw, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                        if (cause instanceof RuntimeException runtime) throw runtime;
                        if (cause instanceof Error error) throw error;
                        throw new IllegalStateException(cause);
                    }
                }
        );
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.focus(live);
        PageSurfaces.bindHandle(live, "tab-a", "main:15");
        Map<String, Object> border = PageSurfaces.highlight(null, "main:15", "yellow", "border:9px dashed #abc", null, null, null, null);
        assertEquals("style", border.get("cdp"));
        assertTrue(String.valueOf(border.get("styleNote")).toLowerCase().contains("reversible dom"), String.valueOf(border));
        assertTrue(commands.contains("Overlay.enable"), commands.toString());
        assertTrue(commands.contains("DOM.resolveNode"), commands.toString());
        assertFalse(commands.contains("Overlay.highlightNode"), commands.toString());
        String painted = documentHtml(json.readValue(state.toFile(), Map.class), "top");
        assertTrue(painted.contains("9px") && painted.contains("dashed") && painted.contains("#abc"), painted);
        assertTrue(painted.contains("data-pkb-original-style=\"color:blue\""), painted);
        PageSurfaces.unhighlight(null, "main:15");
        String restored = documentHtml(json.readValue(state.toFile(), Map.class), "top");
        assertTrue(restored.contains("style=\"color:blue\""), restored);
        assertFalse(restored.contains("dashed"), restored);
        assertFalse(commands.contains("Overlay.highlightNode"), commands.toString());
    }

    @Test
    void clearWalksEveryTabDocumentNotOnlyTheFocusedOne() throws Exception {
        Path dir = Files.createTempDirectory("pkb-tabs-clear");
        Path state = dir.resolve("state.json");
        Path script = dir.resolve("tab-dom.js");
        ObjectMapper json = new ObjectMapper();
        Files.writeString(script, """
                const fs = require('fs');
                const input = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
                const state = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
                const tab = input.tab;
                const saved = state.tabs[tab];
                function Element() {
                  this.nodeType = 1;
                  this.tagName = 'B';
                  this.attributes = Object.assign({}, saved.attributes);
                  this.childNodes = [];
                  this.parentNode = null;
                  this.style = {};
                }
                Element.prototype.setAttribute = function(name, value) { this.attributes[name] = String(value); };
                Element.prototype.getAttribute = function(name) {
                  return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
                };
                Element.prototype.hasAttribute = function(name) { return Object.prototype.hasOwnProperty.call(this.attributes, name); };
                Element.prototype.removeAttribute = function(name) { delete this.attributes[name]; };
                Element.prototype.querySelectorAll = function() { return []; };
                const el = new Element();
                const body = { childNodes: [el] };
                el.parentNode = body;
                const document = {
                  body,
                  querySelectorAll(selector) {
                    return String(selector).indexOf('data-pkb-style') >= 0 && el.hasAttribute('data-pkb-style') ? [el] : [];
                  }
                };
                globalThis.document = document;
                globalThis.CSS = { escape: function(value) { return String(value); } };
                const source = String(input.script || '').split('arguments').join('scriptArgs');
                const fn = new Function('scriptArgs', source);
                const result = fn([input.spec || {}]);
                saved.attributes = el.attributes;
                state.trace = state.trace || [];
                state.trace.push(tab);
                fs.writeFileSync(process.argv[3], JSON.stringify(state));
                fs.writeFileSync(process.argv[4], JSON.stringify({ result: result }));
                """);
        Map<String, Object> tabs = new LinkedHashMap<>();
        tabs.put("tab-a", Map.of("attributes", Map.of(
                "data-pkb-style", "style_modified",
                "data-pkb-original-style", "color:blue",
                "data-pkb-css", "border:9px dashed #abc",
                "style", "color:blue;border:9px dashed #abc"
        )));
        tabs.put("tab-b", Map.of("attributes", Map.of(
                "data-pkb-style", "style_modified",
                "data-pkb-original-style", "",
                "data-pkb-css", "color:#112233",
                "style", "color:#112233"
        )));
        json.writeValue(state.toFile(), Map.of("tabs", tabs, "trace", List.of()));
        AtomicReference<String> current = new AtomicReference<>("tab-a");
        AtomicReference<WebDriver> self = new AtomicReference<>();
        Object switcher = Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.TargetLocator.class},
                (proxy, method, args) -> {
                    if ("window".equals(method.getName()) && args != null && args.length > 0) {
                        current.set(String.valueOf(args[0]));
                    }
                    return self.get();
                }
        );
        WebDriver driver = (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWindowHandles" -> new LinkedHashSet<>(List.of("tab-a", "tab-b"));
                    case "getWindowHandle" -> current.get();
                    case "switchTo" -> switcher;
                    case "executeScript" -> runTabHelper(script, state, current.get(), args);
                    default -> defaultValue(method.getReturnType());
                }
        );
        self.set(driver);
        PageSurfaces.manage(driver, "chrome-headed-quiet", true);
        PageSurfaces.focus(driver);
        PageSurfaces.unhighlight(null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> after = json.readValue(state.toFile(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> tabsAfter = (Map<String, Object>) after.get("tabs");
        @SuppressWarnings("unchecked")
        Map<String, Object> tabA = (Map<String, Object>) ((Map<String, Object>) tabsAfter.get("tab-a")).get("attributes");
        @SuppressWarnings("unchecked")
        Map<String, Object> tabB = (Map<String, Object>) ((Map<String, Object>) tabsAfter.get("tab-b")).get("attributes");
        assertFalse(tabA.containsKey("data-pkb-style"), tabA.toString());
        assertFalse(String.valueOf(tabA.get("style")).contains("dashed"), tabA.toString());
        assertEquals("color:blue", tabA.get("style"));
        assertFalse(tabB.containsKey("data-pkb-style"), tabB.toString());
        assertFalse(tabB.containsKey("style"), tabB.toString());
        assertFalse(tabB.toString().contains("#112233"), tabB.toString());
        assertTrue(String.valueOf(after.get("trace")).contains("tab-a"), String.valueOf(after));
        assertTrue(String.valueOf(after.get("trace")).contains("tab-b"), String.valueOf(after));
    }

    private static Object runTabHelper(Path script, Path state, String tab, Object[] args) {
        try {
            Path in = Files.createTempFile("pkb-tab-in", ".json");
            Path out = Files.createTempFile("pkb-tab-out", ".json");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("script", args == null || args.length == 0 ? "" : String.valueOf(args[0]));
            input.put("spec", scriptSpec(args));
            input.put("tab", tab);
            new ObjectMapper().writeValue(in.toFile(), input);
            Process process = new ProcessBuilder("node", script.toString(), in.toString(), state.toString(), out.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished || process.exitValue() != 0 || !Files.isRegularFile(out)) {
                throw new IllegalStateException(output);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new ObjectMapper().readValue(out.toFile(), Map.class);
            return parsed.get("result");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final AtomicBoolean THROW_FRAME = new AtomicBoolean(false);
    private static final AtomicBoolean BLOCK_WINDOW = new AtomicBoolean(false);
    private static final AtomicBoolean APPLY_CALL_TO_NODE = new AtomicBoolean(false);
    private static final List<String> FRAME_SWITCHES = new ArrayList<>();

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
        Object switcher = locator(self, null);
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
        Object switcher = locator(self, current);
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
        Object switcher = locator(self, current);
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

    private static Path stdioBinary(Path binary, Path log) throws IOException {
        return stdioBinary(binary, log, false);
    }

    private static Path stdioBinary(Path binary, Path log, String rejectToken) throws IOException {
        Files.writeString(binary, """
                #!/usr/bin/env python3
                import json, sys
                log = open(%s, "a", encoding="utf-8")
                reject = %s
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
                    exit_code = 0
                    if "version" in argv:
                        body = {"version": "2.4.0"}
                    elif len(argv) >= 2 and argv[0] == "session" and argv[1] == "create":
                        cdp = flag("--cdp")
                        if reject and reject in cdp:
                            exit_code = 1
                            body = {"ok": False, "error": "page session refused"}
                        else:
                            body = {"ok": True, "id": flag("--id"), "cdp": cdp, "launched": False}
                    else:
                        body = {"ok": True}
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": exit_code,
                        "stdout": json.dumps(body),
                        "stderr": ""
                    }) + "\\n")
                    sys.stdout.flush()
                """.formatted(new ObjectMapper().writeValueAsString(log.toString()), new ObjectMapper().writeValueAsString(rejectToken)));
        Files.setPosixFilePermissions(binary, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        return binary;
    }

    private static Path stdioBinary(Path binary, Path log, boolean rejectPageTargets) throws IOException {
        Files.writeString(binary, """
                #!/usr/bin/env python3
                import json, sys
                log = open(%s, "a", encoding="utf-8")
                reject_pages = %s
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
                    exit_code = 0
                    if "version" in argv:
                        body = {"version": "2.4.0"}
                    elif len(argv) >= 2 and argv[0] == "session" and argv[1] == "create":
                        cdp = flag("--cdp")
                        if reject_pages and "/devtools/page/" in cdp:
                            exit_code = 1
                            body = {"ok": False, "error": "page session refused"}
                        else:
                            body = {"ok": True, "id": flag("--id"), "cdp": cdp, "launched": False}
                    else:
                        body = {"ok": True}
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": exit_code,
                        "stdout": json.dumps(body),
                        "stderr": ""
                    }) + "\\n")
                    sys.stdout.flush()
                """.formatted(new ObjectMapper().writeValueAsString(log.toString()), rejectPageTargets ? "True" : "False"));
        Files.setPosixFilePermissions(binary, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        return binary;
    }

    private static WebDriver closingTabs(AtomicReference<String> current, LinkedHashSet<String> tabs) {
        AtomicReference<WebDriver> self = new AtomicReference<>();
        Object switcher = locator(self, current);
        WebDriver driver = (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWindowHandles" -> new LinkedHashSet<>(tabs);
                    case "getWindowHandle" -> current.get();
                    case "switchTo" -> switcher;
                    case "close" -> {
                        tabs.remove(current.get());
                        yield null;
                    }
                    case "executeScript" -> Map.of("ok", true);
                    default -> defaultValue(method.getReturnType());
                }
        );
        self.set(driver);
        return driver;
    }

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

    private static void writeContext(
            ObjectMapper json,
            Path state,
            String context,
            Map<String, Object> child,
            Map<String, Object> top,
            Map<String, Object> iframe
    ) throws IOException {
        Map<String, Object> docs = new LinkedHashMap<>();
        docs.put("top", top);
        docs.put("child-1", child);
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("context", context);
        world.put("docs", docs);
        world.put("iframe", iframe);
        world.put("trace", new ArrayList<>());
        json.writeValue(state.toFile(), world);
    }

    @SuppressWarnings("unchecked")
    private static String documentHtml(Map<String, Object> world, String name) {
        Object docs = world.get("docs");
        if (!(docs instanceof Map<?, ?> map)) return "";
        Object doc = map.get(name);
        if (!(doc instanceof Map<?, ?> body)) return "";
        return String.valueOf(body.get("html"));
    }

    private static void setContext(Path state, String context) {
        try {
            ObjectMapper json = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> world = json.readValue(state.toFile(), Map.class);
            world.put("context", context);
            json.writeValue(state.toFile(), world);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object runContext(Path script, Path state, Map<String, Object> input) {
        try {
            Path in = Files.createTempFile("pkb-ctx-in", ".json");
            Path out = Files.createTempFile("pkb-ctx-out", ".json");
            new ObjectMapper().writeValue(in.toFile(), input);
            Process process = new ProcessBuilder("node", script.toString(), in.toString(), state.toString(), out.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished || process.exitValue() != 0 || !Files.isRegularFile(out)) {
                throw new IllegalStateException(output);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new ObjectMapper().readValue(out.toFile(), Map.class);
            return parsed.get("result");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static WebDriver contextDriver(Path script, Path state) {
        AtomicReference<WebDriver> self = new AtomicReference<>();
        Object switcher = Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.TargetLocator.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("defaultContent".equals(name) || "parentFrame".equals(name)) {
                        setContext(state, "top");
                        return self.get();
                    }
                    if ("frame".equals(name)) {
                        setContext(state, "child-1");
                        return self.get();
                    }
                    if ("window".equals(name)) return self.get();
                    return defaultValue(method.getReturnType());
                }
        );
        WebDriver driver = (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class, CdpProbe.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWindowHandles" -> Set.of("tab-a");
                    case "getWindowHandle" -> "tab-a";
                    case "switchTo" -> switcher;
                    case "executeScript" -> {
                        String source = args == null || args.length == 0 ? "" : String.valueOf(args[0]);
                        Map<String, Object> input = new LinkedHashMap<>();
                        input.put("script", source);
                        if (source.contains("window.__pkbEvents = []")) input.put("mode", "drain");
                        else {
                            input.put("mode", "helper");
                            input.put("spec", scriptSpec(args));
                        }
                        Object result = runContext(script, state, input);
                        if (result instanceof Map<?, ?> map) yield cast(map);
                        yield result;
                    }
                    case "executeCdpCommand" -> {
                        String command = String.valueOf(args[0]);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> params = args[1] instanceof Map<?, ?> map ? cast(map) : Map.of();
                        if ("Page.getFrameTree".equals(command)) {
                            yield Map.of("frameTree", Map.of(
                                    "frame", Map.of("id", "main"),
                                    "childFrames", List.of(Map.of("frame", Map.of("id", "child-1")))
                            ));
                        }
                        if ("DOM.getFrameOwner".equals(command)) yield Map.of("backendNodeId", 42);
                        if ("DOM.resolveNode".equals(command)) yield Map.of("object", Map.of("objectId", "obj-frame"));
                        if ("Runtime.callFunctionOn".equals(command)) {
                            Object arg = null;
                            if (params.get("arguments") instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> raw) {
                                arg = raw.get("value");
                            }
                            Map<String, Object> input = new LinkedHashMap<>();
                            input.put("mode", "call");
                            input.put("script", String.valueOf(params.get("functionDeclaration")));
                            input.put("arg", arg);
                            if (APPLY_CALL_TO_NODE.get()) input.put("on", "node");
                            Object value = runContext(script, state, input);
                            yield Map.of("result", Map.of("value", value == null ? false : value));
                        }
                        yield Map.of();
                    }
                    default -> defaultValue(method.getReturnType());
                }
        );
        self.set(driver);
        return driver;
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

    private static Object locator(AtomicReference<WebDriver> self, AtomicReference<String> current) {
        return Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.TargetLocator.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("defaultContent".equals(name) || "parentFrame".equals(name)) return self.get();
                    if ("frame".equals(name)) {
                        String frame = args == null || args.length == 0 || args[0] == null ? "" : String.valueOf(args[0]);
                        FRAME_SWITCHES.add(frame);
                        if (THROW_FRAME.get()) throw new IllegalStateException("frame switch failed");
                        return self.get();
                    }
                    if ("window".equals(name) && args != null && args.length > 0) {
                        if (BLOCK_WINDOW.get()) throw new IllegalStateException("tab switch failed");
                        if (current != null) current.set(String.valueOf(args[0]));
                        return self.get();
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    /** Remote driver whose CDP target id follows the selected window, not the host:port. */
    private static final class PagedDriver extends RemoteWebDriver {
        private final Map<String, String> targets;
        private final Map<String, String> documents = new LinkedHashMap<>();
        private final List<String> seen;
        private String current;
        private int blindGets;
        private int ignoredSwitches;
        private boolean suppressTarget;
        private String suppressTab;
        private Path harness;

        PagedDriver(String cdp, Map<String, String> targets) {
            this(cdp, targets, null, false);
        }

        PagedDriver(String cdp, Map<String, String> targets, List<String> seen) {
            this(cdp, targets, seen, false);
        }

        /** Publishes {@code goog:chromeOptions.debuggerAddress} and not {@code se:cdp}. */
        PagedDriver(String cdp, Map<String, String> targets, boolean debuggerAddressOnly) {
            this(cdp, targets, null, debuggerAddressOnly);
        }

        private PagedDriver(String cdp, Map<String, String> targets, List<String> seen, boolean debuggerAddressOnly) {
            super(command -> {
                Response response = new Response();
                response.setState("success");
                response.setSessionId("s" + Integer.toHexString(cdp.hashCode()));
                response.setValue(returnedCaps(cdp, debuggerAddressOnly));
                return response;
            }, caps(cdp, debuggerAddressOnly));
            this.targets = new LinkedHashMap<>(targets);
            this.seen = seen;
            this.current = targets.keySet().iterator().next();
            for (String tab : targets.keySet()) documents.put(tab, "<b id=\"anchor\">anchor</b>");
        }

        private static MutableCapabilities caps(String cdp, boolean debuggerAddressOnly) {
            MutableCapabilities capabilities = new MutableCapabilities();
            capabilities.setCapability("browserName", "chrome");
            if (debuggerAddressOnly) {
                capabilities.setCapability("goog:chromeOptions", chromeDebugger(cdp));
            } else {
                capabilities.setCapability("se:cdp", cdp);
            }
            return capabilities;
        }

        private static Map<String, Object> returnedCaps(String cdp, boolean debuggerAddressOnly) {
            Map<String, Object> returned = new LinkedHashMap<>();
            returned.put("browserName", "chrome");
            if (debuggerAddressOnly) {
                returned.put("goog:chromeOptions", chromeDebugger(cdp));
            } else {
                returned.put("se:cdp", cdp);
            }
            return returned;
        }

        private static Map<String, Object> chromeDebugger(String address) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("debuggerAddress", address);
            return options;
        }

        /** The next handle read fails, and the next window switch does not leave the current tab. */
        void blindCurrentHandleOnceAndIgnoreNextSwitch() {
            blindGets = 1;
            ignoredSwitches = 1;
        }

        void suppressTargetInfo(boolean suppress) {
            suppressTarget = suppress;
        }

        void suppressTargetOn(String tab) {
            suppressTab = tab;
        }

        String document(String tab) {
            return documents.getOrDefault(tab, "");
        }

        @Override
        public Set<String> getWindowHandles() {
            return new LinkedHashSet<>(targets.keySet());
        }

        @Override
        public String getWindowHandle() {
            if (blindGets > 0) {
                blindGets--;
                throw new org.openqa.selenium.NoSuchWindowException("current tab is unknown");
            }
            return current;
        }

        @Override
        public void close() {
            targets.remove(current);
            documents.remove(current);
        }

        @Override
        public String getTitle() {
            return "title";
        }

        @Override
        public String getCurrentUrl() {
            return "https://example.test/" + current;
        }

        @Override
        public TargetLocator switchTo() {
            PagedDriver self = this;
            return (TargetLocator) Proxy.newProxyInstance(
                    WebDriver.class.getClassLoader(),
                    new Class<?>[]{WebDriver.TargetLocator.class},
                    (proxy, method, args) -> {
                        if ("window".equals(method.getName()) && args != null && args.length > 0) {
                            String handle = String.valueOf(args[0]);
                            if (!targets.containsKey(handle)) {
                                throw new org.openqa.selenium.NoSuchWindowException(handle);
                            }
                            if (ignoredSwitches > 0) {
                                ignoredSwitches--;
                                return self;
                            }
                            current = handle;
                            return self;
                        }
                        if ("defaultContent".equals(method.getName()) || "parentFrame".equals(method.getName()) || "frame".equals(method.getName())) {
                            return self;
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
        }

        public Map<String, Object> executeCdpCommand(String commandName, Map<String, Object> parameters) {
            if (seen != null) seen.add(current + ":" + commandName);
            if ("Target.getTargetInfo".equals(commandName)) {
                if (suppressTarget || (suppressTab != null && suppressTab.equals(current))) return Map.of();
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("targetId", targets.getOrDefault(current, ""));
                info.put("type", "page");
                return Map.of("targetInfo", info);
            }
            if ("Page.getFrameTree".equals(commandName)) {
                return Map.of("frameTree", Map.of("frame", Map.of("id", targets.getOrDefault(current, "main"))));
            }
            if ("DOM.resolveNode".equals(commandName)) {
                return Map.of("object", Map.of("objectId", "node-" + current));
            }
            if ("Runtime.callFunctionOn".equals(commandName)) {
                Object arg = null;
                Object arguments = parameters == null ? null : parameters.get("arguments");
                if (arguments instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> raw) {
                    arg = raw.get("value");
                }
                String function = parameters == null ? "" : String.valueOf(parameters.get("functionDeclaration"));
                Map<String, Object> ran = runDom("call", function, null, arg);
                documents.put(current, String.valueOf(ran.get("html")));
                Object value = ran.get("result");
                return Map.of("result", Map.of("value", value == null ? false : value));
            }
            return Map.of();
        }

        @Override
        public Object executeScript(String script, Object... args) {
            Object raw = args != null && args.length > 0 ? args[0] : Map.of();
            Map<String, Object> spec = raw instanceof Map<?, ?> map ? cast(map) : Map.of();
            String id = spec.get("id") == null ? "" : String.valueOf(spec.get("id"));
            if (seen != null) seen.add(current + ":" + id);
            Map<String, Object> ran = runDom("helper", script, spec, null);
            documents.put(current, String.valueOf(ran.get("html")));
            Object result = ran.get("result");
            if (result instanceof Map<?, ?> map) return cast(map);
            return result;
        }

        private Map<String, Object> runDom(String mode, String script, Map<String, Object> spec, Object arg) {
            try {
                ensureHarness();
                Path in = Files.createTempFile("pkb-paged-in", ".json");
                Path out = Files.createTempFile("pkb-paged-out", ".json");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("mode", mode);
                body.put("script", script == null ? "" : script);
                body.put("html", documents.getOrDefault(current, ""));
                if (spec != null) body.put("spec", spec);
                if (arg != null) body.put("arg", arg);
                new ObjectMapper().writeValue(in.toFile(), body);
                Process process = new ProcessBuilder("node", harness.toString(), in.toString(), out.toString())
                        .redirectErrorStream(true)
                        .start();
                boolean finished = process.waitFor(20, TimeUnit.SECONDS);
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!finished || process.exitValue() != 0 || !Files.isRegularFile(out)) {
                    throw new IllegalStateException("tab dom did not run: " + output);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = new ObjectMapper().readValue(out.toFile(), Map.class);
                return parsed;
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        private void ensureHarness() throws IOException {
            if (harness != null) return;
            Path dir = Files.createTempDirectory("pkb-paged-dom");
            harness = dir.resolve("dom.js");
            try (InputStream stream = AgentLiveFixTest.class.getResourceAsStream("persistent-tab-dom.js")) {
                if (stream == null) throw new IOException("missing persistent-tab-dom.js");
                Files.write(harness, stream.readAllBytes());
            }
        }
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
