package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.WorkbenchSessionActions;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Enter in the chat field sends, the same as the Send button. A blank field sends nothing. */
class AgentChatPanelEnterTest {
    @Test
    void enterInTheFieldSendsAndClearsIt() {
        List<String> ops = new ArrayList<>();
        List<Map<?, ?>> sent = new ArrayList<>();
        WorkbenchServices services = fake((method, values) -> {
            if (!"agentCommand".equals(method)) return null;
            ops.add(String.valueOf(values[0]));
            if ("chat-send".equals(values[0])) sent.add((Map<?, ?>) values[1]);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("messages", List.of());
            return body;
        });
        WorkbenchSessionActions actions = new WorkbenchSessionActions(services);
        actions.dispatch("diagnostic-run", Map.of("run", "run-7"));
        AgentChatPanel panel = new AgentChatPanel(actions);
        panel.inputField().setText("   ");
        panel.inputField().postActionEvent();
        assertTrue(sent.isEmpty(), "a blank field was sent: " + sent);
        panel.inputField().setText("ship it");
        panel.inputField().postActionEvent();
        assertEquals(1, sent.size(), "Enter did not send: " + ops);
        assertEquals("ship it", String.valueOf(sent.get(0).get("text")));
        assertEquals("human", String.valueOf(sent.get(0).get("source")));
        assertEquals("", panel.inputField().getText());
    }

    @Test
    void refusedSendKeepsTheTextAndSaysWhy() {
        WorkbenchServices services = fake((method, values) -> null);
        WorkbenchSessionActions actions = new WorkbenchSessionActions(services);
        AgentChatPanel panel = new AgentChatPanel(actions);
        panel.inputField().setText("hello");
        panel.inputField().postActionEvent();
        assertEquals("hello", panel.inputField().getText());
        assertTrue(panel.popupStatus.getText().startsWith("not sent"), panel.popupStatus.getText());
    }

    private static WorkbenchServices fake(java.util.function.BiFunction<String, Object[], Object> handler) {
        return (WorkbenchServices) Proxy.newProxyInstance(
                WorkbenchServices.class.getClassLoader(),
                new Class<?>[] {WorkbenchServices.class},
                (proxy, method, args) -> {
                    if ("selectedDiagnosticRun".equals(method.getName())) return null;
                    Object value = handler.apply(method.getName(), args);
                    if (value != null) return value;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
    }
}
