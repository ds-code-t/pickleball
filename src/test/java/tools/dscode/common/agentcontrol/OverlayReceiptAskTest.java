package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.WebDriver;
import tools.dscode.launcher.WorkbenchAgentCommands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overlay show and the page ack go through {@link AgentControl}. A typed answer goes through
 * {@code ask-answer} and comes back from {@code ui-poll}.
 */
class OverlayReceiptAskTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    private WebDriver page;
    private final List<Map<String, String>> pending = new CopyOnWriteArrayList<>();
    private String lastErr = "";

    @AfterEach
    void releasePage() {
        if (page != null) PageSurfaces.detach(page);
        PageSurfaces.resetRuntimeForTest();
        pending.clear();
    }

    @Test
    void shownOverlayAckSetsReadAtAndAskAnswerReachesUiPoll() throws Exception {
        PageSurfaces.resetRuntimeForTest();
        page = recordingPage();
        PageSurfaces.manage(page, "fixture", false);
        String session = session();

        Map<String, String> overlay = new LinkedHashMap<>();
        overlay.put("text", "hello");
        overlay.put("x", "4");
        overlay.put("y", "8");
        overlay.put("session", session);
        AgentControl.Result shown = AgentControl.execute(project, session, "overlay", overlay, List.of(), "agent-1");
        assertEquals(0, shown.exit(), String.valueOf(shown.body()));
        String id = String.valueOf(shown.body().get("id"));
        assertFalse(id.isBlank(), String.valueOf(shown.body()));

        AgentControl.Result unread = AgentControl.execute(project, session, "overlay-poll", Map.of("session", session), List.of(), "agent-1");
        assertEquals(0, unread.exit(), String.valueOf(unread.body()));
        Object readAt = readAt(unread.body(), id);
        assertNotNull(findEvent(unread.body(), id), String.valueOf(unread.body()));
        assertTrue(readAt == null, String.valueOf(unread.body()));

        pending.add(Map.of("type", "click", "id", id, "source", "human"));
        AgentControl.Result read = AgentControl.execute(project, session, "overlay-poll", Map.of("session", session), List.of(), "agent-1");
        assertEquals(0, read.exit(), String.valueOf(read.body()));
        assertNotNull(readAt(read.body(), id), String.valueOf(read.body()));

        assertEquals(0, command("ask", "--type=choice", "--prompt=Go", "--options=yes,no", "--id=q1", "--session=" + session), lastErr);
        int rejected = command("ask-answer", "--session=" + session, "--id=q1", "--value=maybe");
        assertEquals(2, rejected, lastErr);
        assertEquals(0, command("ask-answer", "--session=" + session, "--id=q1", "--value=yes"));
        String polled = commandOut("ui-poll", "--session=" + session);
        assertTrue(polled.contains("\"type\":\"choice\"") || polled.contains("\"type\": \"choice\""), polled);
        assertTrue(polled.contains("\"value\":\"yes\"") || polled.contains("\"value\": \"yes\""), polled);

        String page = Files.readString(Path.of("workbench-ui/pages/index.html"), StandardCharsets.UTF_8);
        assertTrue(page.contains("\"ask-answer\""), page);
    }

    @Test
    void covers_command_ask_answer() throws Exception {
        String session = session();
        assertEquals(0, command("ask", "--type=confirm", "--prompt=Ready", "--id=c1", "--session=" + session), lastErr);
        int rejected = command("ask-answer", "--session=" + session, "--id=c1", "--value=later");
        assertEquals(2, rejected, lastErr);
        assertEquals(0, command("ask-answer", "--session=" + session, "--id=c1", "--value=yes"));
    }

    private static Object readAt(Map<String, Object> body, String id) {
        Map<String, Object> event = findEvent(body, id);
        return event == null ? null : event.get("read_at");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findEvent(Map<String, Object> body, String id) {
        Object events = body.get("events");
        if (!(events instanceof List<?>)) events = body.get("items");
        if (!(events instanceof List<?> list)) return null;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && id.equals(String.valueOf(map.get("id")))) {
                return (Map<String, Object>) map;
            }
        }
        return null;
    }

    private String session() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"spine", "session", "new", project.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        return JSON.readTree(out.toString(StandardCharsets.UTF_8)).path("state").path("session").asText();
    }

    private int command(String... tokens) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(argv(tokens), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        lastErr = err.toString(StandardCharsets.UTF_8);
        return code;
    }

    private String commandOut(String... tokens) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(argv(tokens), new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    private String[] argv(String... tokens) {
        String[] argv = new String[tokens.length + 1];
        System.arraycopy(tokens, 0, argv, 0, tokens.length);
        argv[tokens.length] = project.toString();
        return argv;
    }

    private WebDriver recordingPage() {
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("executeScript".equals(name)) {
                        String script = args == null || args.length == 0 ? "" : String.valueOf(args[0]);
                        if (script.contains("window.__pkbEvents = []")) {
                            List<Map<String, String>> drained = new ArrayList<>(pending);
                            pending.clear();
                            return drained;
                        }
                        if (script.contains("ui[a.m]")) return "[]";
                        return Map.of("ok", Boolean.TRUE);
                    }
                    if ("getWindowHandles".equals(name)) return Set.of("tab");
                    if ("getWindowHandle".equals(name)) return "tab";
                    if ("toString".equals(name)) return "fixture-page";
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if ("switchTo".equals(name)) {
                        return Proxy.newProxyInstance(
                                WebDriver.class.getClassLoader(),
                                new Class<?>[]{WebDriver.TargetLocator.class},
                                (ignored, locator, locatorArgs) -> {
                                    if ("alert".equals(locator.getName())) throw new NoAlertPresentException("no alert");
                                    return proxy;
                                });
                    }
                    return null;
                });
    }
}
