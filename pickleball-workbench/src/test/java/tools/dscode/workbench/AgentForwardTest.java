package tools.dscode.workbench;

import org.junit.jupiter.api.Test;
import tools.dscode.workbench.ui.AgentChatPanel;
import tools.dscode.workbench.ui.WorkbenchNativeOverlay;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Map<String, Object> sent = AgentChatPanel.send(actions, "hello", "run-9", "human");
        assertEquals("chat-send", op.get());
        assertEquals("hello", args.get().get("text"));
        assertEquals("human", args.get().get("source"));
        assertEquals(true, sent.get("ok"));
        AgentChatPanel.history(actions, 5);
        assertEquals("chat-history", op.get());
        AgentChatPanel.poll(actions, "4");
        assertEquals("chat-poll", op.get());
        assertEquals("4", args.get().get("id"));
    }

    @Test
    void overlayForwardSharesTheCommandObject() {
        WorkbenchServices services = fake((method, values) -> {
            if (!"agentCommand".equals(method)) return null;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("id", "pkb-1");
            body.put("sentence", "agent-injected overlay; a person did not author this UI");
            body.put("x", 3);
            body.put("y", 4);
            return body;
        });
        Map<String, Object> placed = new WorkbenchSessionActions(services).dispatch("overlay", Map.of("text", "click here"));
        assertEquals("pkb-1", placed.get("id"));
        WorkbenchNativeOverlay.place(placed);
        WorkbenchNativeOverlay.remove(placed.get("id"));
        assertTrue(String.valueOf(placed.get("sentence")).contains("person did not author"));
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
