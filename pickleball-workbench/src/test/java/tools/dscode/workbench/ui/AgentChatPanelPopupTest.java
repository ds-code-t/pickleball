package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.WorkbenchSessionActions;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentChatPanelPopupTest {
    @Test
    void openPopupButtonDispatchesAndStatusLineNamesTheState() {
        AtomicReference<String> op = new AtomicReference<>();
        WorkbenchServices services = fake((method, values) -> {
            if (!"agentCommand".equals(method)) return null;
            op.set(String.valueOf(values[0]));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("state", "open");
            body.put("messages", java.util.List.of());
            return body;
        });
        WorkbenchSessionActions actions = new WorkbenchSessionActions(services);
        AgentChatPanel panel = new AgentChatPanel(actions);
        Map<String, Object> refused = AgentChatPanel.openPopup(actions, null);
        assertEquals(false, refused.get("ok"));
        actions.dispatch("diagnostic-run", Map.of("run", "run-9"));
        panel.openPopup.doClick();
        assertEquals("popup-open", op.get());
        assertEquals("popup open", panel.popupStatus.getText());
        assertEquals("popup closed fallback=no-chromium", AgentChatPanel.statusLine(Map.of("state", "closed", "fallback", "no-chromium")));
    }

    private static WorkbenchServices fake(java.util.function.BiFunction<String, Object[], Object> handler) {
        return (WorkbenchServices) Proxy.newProxyInstance(
                WorkbenchServices.class.getClassLoader(),
                new Class<?>[] {WorkbenchServices.class},
                (proxy, method, args) -> {
                    if ("selectedDiagnosticRun".equals(method.getName()) && handler != null) {
                        return null;
                    }
                    Object value = handler.apply(method.getName(), args);
                    if (value != null) return value;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
    }
}
