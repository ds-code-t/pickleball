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
        AtomicReference<String> css = new AtomicReference<>();
        List<String> commands = new ArrayList<>();
        WebDriver live = scriptDriver("tab-a", spec -> {
            css.set(String.valueOf(spec.get("css")));
            return Map.of("ok", true);
        }, false);
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        Map<String, Object> border = PageSurfaces.highlight(null, "save", "yellow", "border:9px dashed #abc", null, null, null, null);
        assertEquals("style", border.get("cdp"));
        assertTrue(String.valueOf(border.get("styleNote")).toLowerCase().contains("reversible dom"), String.valueOf(border));
        assertTrue(css.get().contains("9px") && css.get().contains("dashed") && css.get().contains("#abc"), css.get());
        assertFalse(commands.contains("Overlay.highlightNode"));
        Map<String, Object> color = PageSurfaces.highlight(null, "save", "yellow", "color:#112233", null, null, null, null);
        assertEquals("style", color.get("cdp"));
        assertTrue(String.valueOf(color.get("styleNote")).toLowerCase().contains("reversible dom"), String.valueOf(color));
        assertTrue(css.get().contains("color:#112233") || css.get().contains("color: #112233"), css.get());
    }

    @Test
    void childFrameSurfacesStayRemovableAndPollable() throws Exception {
        AtomicReference<String> saw = new AtomicReference<>("");
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
                if (function.contains("removeAttribute")) return Map.of("result", Map.of("value", true));
                if (function.contains("changedName")) {
                    return Map.of("result", Map.of("value", "{\"name\":\"child\",\"changedName\":false,\"previousBridge\":null}"));
                }
                return Map.of("result", Map.of("value", true));
            }
            return Map.of();
        }, spec -> {
            String op = String.valueOf(spec.get("op"));
            saw.set(op + ":" + String.join(",", FRAME_SWITCHES));
            if ("remove".equals(op) || "cleanup".equals(op)) return Map.of("ok", true, "removed", 1);
            if ("highlight".equals(op)) return Map.of("ok", true);
            return Map.of("ok", true, "x", 1, "y", 1);
        });
        PageSurfaces.manage(live, "chrome-headed-quiet", true);
        PageSurfaces.bindFrame(live, "tab-a", "child-1");
        FRAME_SWITCHES.clear();
        Map<String, Object> placed = PageSurfaces.place(null, "append", "child-1:15", null, "<b>inside</b>", "html", null, null, null);
        assertFalse(FRAME_SWITCHES.isEmpty(), "place did not enter the child frame");
        FRAME_SWITCHES.clear();
        PageSurfaces.remove(null, String.valueOf(placed.get("id")));
        assertFalse(FRAME_SWITCHES.isEmpty(), "remove did not enter the child frame " + saw.get());
        FRAME_SWITCHES.clear();
        PageSurfaces.pollInput(temp, "frames");
        assertFalse(FRAME_SWITCHES.isEmpty(), "poll did not enter the child frame");
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
            WindowRegistry.switchTo(temp, "run", "tab-b");
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

    private static final AtomicBoolean THROW_FRAME = new AtomicBoolean(false);
    private static final AtomicBoolean BLOCK_WINDOW = new AtomicBoolean(false);
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
        private String current;

        PagedDriver(String cdp, Map<String, String> targets) {
            super(command -> {
                Response response = new Response();
                response.setState("success");
                response.setSessionId("s" + Integer.toHexString(cdp.hashCode()));
                Map<String, Object> returned = new LinkedHashMap<>();
                returned.put("browserName", "chrome");
                returned.put("se:cdp", cdp);
                response.setValue(returned);
                return response;
            }, caps(cdp));
            this.targets = new LinkedHashMap<>(targets);
            this.current = targets.keySet().iterator().next();
        }

        private static MutableCapabilities caps(String cdp) {
            MutableCapabilities capabilities = new MutableCapabilities();
            capabilities.setCapability("browserName", "chrome");
            capabilities.setCapability("se:cdp", cdp);
            return capabilities;
        }

        @Override
        public Set<String> getWindowHandles() {
            return new LinkedHashSet<>(targets.keySet());
        }

        @Override
        public String getWindowHandle() {
            return current;
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
            if ("Target.getTargetInfo".equals(commandName)) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("targetId", targets.getOrDefault(current, ""));
                info.put("type", "page");
                return Map.of("targetInfo", info);
            }
            return Map.of();
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
