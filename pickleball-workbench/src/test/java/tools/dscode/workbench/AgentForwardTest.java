package tools.dscode.workbench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.ui.AgentChatPanel;
import tools.dscode.workbench.ui.WorkbenchNativeOverlay;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workbench chat and agent commands only forward the shared controller call. */
class AgentForwardTest {
    @Test
    void chatPanelForwardsSendHistoryAndPoll() {
        AtomicReference<String> op = new AtomicReference<>();
        AtomicReference<Map<String, String>> args = new AtomicReference<>();
        WorkbenchServices services = fake((method, values) -> {
            if (!"agentCommand".equals(method)) return null;
            op.set(String.valueOf(values[0]));
            @SuppressWarnings("unchecked")
            Map<String, String> forwarded = (Map<String, String>) values[1];
            args.set(forwarded);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("sentence", "forwarded");
            body.put("messages", java.util.List.of());
            return body;
        });
        WorkbenchSessionActions actions = new WorkbenchSessionActions(services);
        Map<String, Object> refused = AgentChatPanel.send(actions, "hello", null, "human");
        assertEquals(false, refused.get("ok"));
        assertEquals(null, op.get());
        Map<String, Object> sent = AgentChatPanel.send(actions, "hello", "run-9", "human");
        assertEquals("chat-send", op.get());
        assertEquals("hello", args.get().get("text"));
        assertEquals("human", args.get().get("source"));
        assertEquals("run-9", args.get().get("runId"));
        assertEquals(true, sent.get("ok"));
        actions.dispatch("diagnostic-run", Map.of("run", "run-9"));
        AgentChatPanel.send(actions, "again", null, "human");
        assertEquals("run-9", args.get().get("runId"));
        assertEquals("again", args.get().get("text"));
        AgentChatPanel.history(actions, 5);
        assertEquals("chat-history", op.get());
        assertEquals("run-9", args.get().get("runId"));
        AgentChatPanel.poll(actions, "4");
        assertEquals("chat-poll", op.get());
        assertEquals("4", args.get().get("id"));
        assertEquals("run-9", args.get().get("runId"));
    }

    @Test
    void overlayForwardSharesTheCommandObject() {
        WorkbenchServices services = fake((method, values) -> {
            if (!"agentCommand".equals(method)) return null;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("id", "pkb-1");
            body.put("sentence", "agent-injected overlay; a person did not author this UI");
            body.put("guidance", "click here");
            body.put("x", 3);
            body.put("y", 4);
            return body;
        });
        Map<String, Object> placed = new WorkbenchSessionActions(services).dispatch("overlay", Map.of("text", "click here"));
        assertEquals("pkb-1", placed.get("id"));
        assertTrue(WorkbenchNativeOverlay.caption(placed).contains("click here"));
        assertTrue(WorkbenchNativeOverlay.caption(placed).contains("person did not author"));
        WorkbenchNativeOverlay.resetForTests();
        WorkbenchNativeOverlay.place(placed);
        WorkbenchNativeOverlay.remove(placed.get("id"));
        assertEquals(List.of("pkb-1"), WorkbenchNativeOverlay.removedForTests());
        assertTrue(String.valueOf(placed.get("sentence")).contains("person did not author"));
    }

    @Test
    void overlayIsRemovedWhenTheWorkerStopsOrTheWindowCloses(@TempDir Path temp) {
        WorkbenchServices services = fake((method, values) -> {
            if ("projectRoot".equals(method)) return temp;
            return null;
        });
        WorkbenchSessionActions actions = new WorkbenchSessionActions(services);
        actions.installWindow((WorkbenchSessionActions.Window) Proxy.newProxyInstance(
                WorkbenchSessionActions.Window.class.getClassLoader(),
                new Class<?>[]{WorkbenchSessionActions.Window.class},
                (proxy, method, values) -> {
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        ));
        WorkbenchNativeOverlay.resetForTests();
        actions.dispatch("worker-stop", Map.of());
        assertFalse(WorkbenchNativeOverlay.removedForTests().isEmpty());
        WorkbenchNativeOverlay.resetForTests();
        actions.dispatch("close-window", Map.of());
        assertTrue(WorkbenchNativeOverlay.removedForTests().contains("*"));
    }

    private static WorkbenchServices fake(Invocation invocation) {
        return (WorkbenchServices) Proxy.newProxyInstance(
                WorkbenchServices.class.getClassLoader(),
                new Class<?>[]{WorkbenchServices.class},
                (proxy, method, args) -> {
                    Object value = invocation.call(method.getName(), args == null ? new Object[0] : args);
                    if (value != null || method.getReturnType() == void.class) return value;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] args);
    }
}
