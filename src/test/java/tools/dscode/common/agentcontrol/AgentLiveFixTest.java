package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.WebDriver;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
