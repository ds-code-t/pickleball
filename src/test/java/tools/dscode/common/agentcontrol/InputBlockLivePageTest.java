package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import tools.dscode.launcher.WorkbenchAgentCommands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code input block --on} goes through {@link WorkbenchAgentCommands} and installs a page shield.
 * Human key and pointer events are stopped. The agent {@code input} verb still runs.
 */
class InputBlockLivePageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACTIONS =
            "[{\"type\":\"key\",\"id\":\"k\",\"actions\":[{\"type\":\"keyDown\",\"value\":\"a\"}]}]";

    @TempDir
    Path project;

    private WebDriver page;
    private final List<String> scripts = new ArrayList<>();
    private String lastErr = "";

    @AfterEach
    void releasePage() {
        if (page != null) PageSurfaces.detach(page);
        PageSurfaces.resetRuntimeForTest();
    }

    @Test
    void inputBlockInstallsTheHumanShieldAndAgentInputStillRuns() throws Exception {
        PageSurfaces.resetRuntimeForTest();
        page = recordingPage();
        PageSurfaces.manage(page, "fixture", false);

        String id = session();
        assertEquals(2, command("input", "block", "--on", "--session=" + id), lastErr);
        assertTrue(lastErr.contains("fix: inject on"), lastErr);
        assertFalse(SpineSession.inputBlocked(project, id));

        assertEquals(0, command("inject", "on", "--session=" + id));
        scripts.clear();
        assertEquals(0, command("input", "block", "--on", "--session=" + id), lastErr);
        String blocked = String.join("\n", scripts);
        assertTrue(blocked.contains("inputBlock"), blocked);
        String runtime = PageSurfaces.runtimeSource();
        assertTrue(runtime.contains("data-pkb-input-block"), runtime);
        assertTrue(runtime.contains("preventDefault"), runtime);
        assertTrue(runtime.contains("keydown"), runtime);
        assertTrue(runtime.contains("pointerdown"), runtime);

        scripts.clear();
        String agentMessage = agentInput(id);
        assertFalse(agentMessage.contains("mouse and keyboard input is blocked"), agentMessage);
        String passed = String.join("\n", scripts);
        assertTrue(passed.contains("\"pass\":\"agent\""), passed);

        scripts.clear();
        assertEquals(0, command("input", "block", "--off", "--session=" + id), lastErr);
        String cleared = String.join("\n", scripts);
        assertTrue(cleared.contains("\"on\":false"), cleared);
        assertFalse(SpineSession.inputBlocked(project, id));
    }

    private String agentInput(String id) throws Exception {
        Class<?> hostType = Class.forName("tools.dscode.browser.proto.ProtoHost");
        Object host = hostType.getConstructor(Path.class, String.class, String.class, String.class)
                .newInstance(project, null, null, id);
        Class<?> commands = Class.forName("tools.dscode.browser.proto.InputCommands");
        Method input = commands.getDeclaredMethod("input", hostType, Map.class);
        input.setAccessible(true);
        try {
            input.invoke(null, host, Map.of("session", id, "actions", ACTIONS));
            fail("input should report that no protocol is live");
            return "";
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            return cause == null || cause.getMessage() == null ? "" : cause.getMessage();
        }
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
        int code = WorkbenchAgentCommands.run(
                argv,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        lastErr = err.toString(StandardCharsets.UTF_8);
        return code;
    }

    private static void appendRecorded(StringBuilder text, Object value) {
        if (value == null) return;
        if (value instanceof Object[] nested) {
            for (Object one : nested) appendRecorded(text, one);
            return;
        }
        text.append(value).append('\n');
    }

    private WebDriver recordingPage() {
        return (WebDriver) java.lang.reflect.Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("executeScript".equals(name)) {
                        StringBuilder text = new StringBuilder();
                        appendRecorded(text, args);
                        scripts.add(text.toString());
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
                        return java.lang.reflect.Proxy.newProxyInstance(
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
