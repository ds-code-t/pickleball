package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.agentfast.RuntimeInstall;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code inject on} goes through {@link WorkbenchAgentCommands} and installs the
 * sibling overlay on the managed page. A reload re-reads the session, UiState, and chat.
 */
class InjectLivePageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    private WebDriver page;
    private final List<String> scripts = new ArrayList<>();
    private final AtomicBoolean runtimeMissing = new AtomicBoolean();

    @AfterEach
    void releasePage() {
        if (page != null) PageSurfaces.detach(page);
        PageSurfaces.resetRuntimeForTest();
        runtimeMissing.set(false);
    }

    @Test
    void injectOnInstallsTheLivePageAndReloadRereadsStoredState() throws Exception {
        PageSurfaces.resetRuntimeForTest();
        page = recordingPage();
        PageSurfaces.manage(page, "fixture", false);

        String id = session();
        assertEquals(0, command("inject", "on", "--session=" + id, "--agent=agent-1"));
        String sent = String.join("\n", scripts);
        assertTrue(sent.contains("window.__pkbUi"), sent);
        assertFalse(RuntimeInstall.consume(System.identityHashCode(page)), "runtime key must be the driver");

        SpineSession.attach(project, id, "cdp://fixture");
        assertTrue(SpineSession.injected(project, id, "cdp://fixture"));
        assertFalse(RuntimeInstall.consume(System.identityHashCode(page)));
        assertTrue(RuntimeInstall.consume("cdp://fixture".hashCode()));

        scripts.clear();
        assertEquals(0, command("inject", "on", "--session=" + id, "--agent=agent-2"));
        String both = String.join("\n", scripts);
        assertTrue(both.contains("agent-1") || sessionText(id).contains("agent-1"), both);
        assertTrue(both.contains("agent-2"), both);

        scripts.clear();
        Map<String, String> dom = new LinkedHashMap<>();
        dom.put("target", "native");
        dom.put("mutate", "dom");
        dom.put("handle", "item");
        dom.put("x", "1");
        dom.put("y", "2");
        dom.put("width", "3");
        dom.put("height", "4");
        AgentControl.Result domResult = AgentControl.execute(project, id, "highlight", dom, List.of(), "agent-1");
        assertEquals(0, domResult.exit(), String.valueOf(domResult.body()));
        String domScript = String.join("\n", scripts);
        assertTrue(domScript.contains("data-pkb-dom"), domScript);
        assertEquals("dom", String.valueOf(domResult.body().get("mutate")));

        scripts.clear();
        Map<String, String> sibling = new LinkedHashMap<>();
        sibling.put("target", "native");
        sibling.put("handle", "item");
        sibling.put("x", "1");
        sibling.put("y", "2");
        sibling.put("width", "3");
        sibling.put("height", "4");
        AgentControl.Result siblingResult = AgentControl.execute(project, id, "highlight", sibling, List.of(), "agent-2");
        assertEquals(0, siblingResult.exit(), String.valueOf(siblingResult.body()));
        String siblingScript = String.join("\n", scripts);
        assertTrue(siblingScript.contains("agent-2"), siblingScript);
        assertTrue(siblingScript.contains("data-pkb-layer"), siblingScript);
        assertFalse(siblingScript.contains("data-pkb-dom"), siblingScript);
        assertEquals("sibling", String.valueOf(siblingResult.body().get("mutate")));

        ObjectNode session = (ObjectNode) JSON.readTree(sessionText(id));
        session.withObject("agents").putObject("file-layer").put("layer", "file-layer");
        Files.writeString(sessionFile(id), JSON.writeValueAsString(session), StandardCharsets.UTF_8);
        Path uiState = project.resolve(".pickleball").resolve("workbench-ui").resolve("ui-state.json");
        Files.writeString(uiState, "{\"v\":1,\"marker\":\"ui-state-marker\"}\n", StandardCharsets.UTF_8);
        ChatLog.send(project, id, id, "agent", "agent-1", "reload-chat-marker");
        scripts.clear();
        runtimeMissing.set(true);
        assertEquals(0, command("inject", "on", "--session=" + id, "--agent=agent-1"));
        String replay = String.join("\n", scripts);
        assertTrue(replay.contains("file-layer"), replay);
        assertTrue(replay.contains("ui-state-marker"), replay);
        assertTrue(replay.contains("reload-chat-marker"), replay);

        scripts.clear();
        assertEquals(0, command("inject", "off", "--session=" + id, "--agent=agent-1"));
        String removed = String.join("\n", scripts);
        assertTrue(removed.contains("\"on\":false") || removed.contains("\"on\": false"), removed);
        assertFalse(SpineSession.injectEnabled(project, id));
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
        String[] argv = new String[tokens.length + 1];
        System.arraycopy(tokens, 0, argv, 0, tokens.length);
        argv[tokens.length] = project.toString();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        return WorkbenchAgentCommands.run(
                argv,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String sessionText(String id) throws Exception {
        return Files.readString(sessionFile(id), StandardCharsets.UTF_8);
    }

    private Path sessionFile(String id) {
        return project.resolve(".pickleball").resolve("workbench-ui").resolve("sessions").resolve(id + ".json");
    }

    /** A Proxy delivers {@code executeScript(String, Object...)} with the payload inside a trailing array. */
    private static void appendRecorded(StringBuilder text, Object value) {
        if (value == null) return;
        if (value instanceof Object[] nested) {
            for (Object one : nested) appendRecorded(text, one);
            return;
        }
        text.append(value).append('\n');
    }

    private WebDriver recordingPage() {
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("executeScript".equals(name)) {
                        StringBuilder text = new StringBuilder();
                        appendRecorded(text, args);
                        scripts.add(text.toString());
                        if (runtimeMissing.compareAndSet(true, false)) return "runtime-missing";
                        return "{\"ok\":true}";
                    }
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
                                    throw new org.openqa.selenium.NoAlertPresentException("no alert");
                                });
                    }
                    return null;
                });
    }
}
