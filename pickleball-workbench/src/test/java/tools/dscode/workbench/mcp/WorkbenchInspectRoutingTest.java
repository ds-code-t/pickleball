package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tools.dscode.control.protocol.ControlBridgeElementInspectionResult;
import tools.dscode.control.protocol.ControlBridgePageQueryResult;
import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.WorkbenchSessionActions;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI, MCP, and GUI inspect routes share the session services. Those services
 * are the worker calls that reach ElementOperations. This module does not
 * link the adapter.
 */
class WorkbenchInspectRoutingTest {
    @Test
    void guiSessionCommandsCallPageQuery() {
        AtomicReference<String> seen = new AtomicReference<>();
        ControlBridgePageQueryResult result = new ControlBridgePageQueryResult(
                "SUCCESS",
                List.of(new ControlBridgePageQueryResult.ControlBridgePageQueryHit(
                        "the \"Save\" Button", "//button", 1, 1
                )),
                null,
                null
        );
        WorkbenchServices services = services(seen, result, null);
        WorkbenchSessionActions actions = new WorkbenchSessionActions(services);
        Map<String, Object> page = actions.dispatch("inspect-page", Map.of());
        assertEquals("SUCCESS", page.get("status"));
        assertEquals("live-session", page.get("source"));
        assertEquals("inspect-page", seen.get());
        Map<String, Object> found = actions.dispatch("find-element", Map.of("category", "Button", "text", "Save"));
        assertEquals("SUCCESS", found.get("status"));
        assertEquals("live-session", found.get("source"));
        assertEquals("find-element:Button:Save", seen.get());
    }

    @Test
    void mcpElementInspectCallsElementInspect() {
        AtomicReference<String> seen = new AtomicReference<>();
        ControlBridgeElementInspectionResult result = new ControlBridgeElementInspectionResult(
                "SUCCESS", null, null, null
        );
        WorkbenchMcpTools tools = new WorkbenchMcpTools(services(seen, null, result), new ObjectMapper());
        assertTrue(tools.names().contains("workbench_element_inspect"), tools.names().toString());
        Object called = tools.call("workbench_element_inspect", Map.of("category", "Button", "text", "Save"));
        ControlBridgeElementInspectionResult inspection = assertInstanceOf(ControlBridgeElementInspectionResult.class, called);
        assertEquals("SUCCESS", inspection.status());
        assertEquals("element-inspect:Button:Save", seen.get());
    }

    @Test
    void cliLiveAndOfflineRoutesDoNotCallWebDriver() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if ("pickleball-workbench".equals(String.valueOf(root.getFileName()))) root = root.getParent();
        String cli = Files.readString(root.resolve("src/main/java/tools/dscode/launcher/WorkbenchAgentCommands.java"));
        assertTrue(cli.contains("source=offline-html"), cli);
        assertTrue(cli.contains("\"inspect-page\""), cli);
        assertTrue(cli.contains("\"find-element\""), cli);
        assertTrue(cli.contains("liveQuery"), cli);
        assertFalse(cli.contains(".findElement("), cli);
        assertFalse(cli.contains(".findElements("), cli);
        assertFalse(cli.contains("org.openqa.selenium"), cli);
        String gui = Files.readString(root.resolve(
                "pickleball-workbench/src/main/java/tools/dscode/workbench/WorkbenchSessionActions.java"));
        assertTrue(gui.contains("services.pageQuery"), gui);
        assertFalse(gui.contains("findElement("), gui);
        assertFalse(gui.contains("findElements("), gui);
        String frame = Files.readString(root.resolve(
                "pickleball-workbench/src/main/java/tools/dscode/workbench/ui/WorkbenchFrame.java"));
        assertTrue(frame.contains("new WorkbenchSessionActions"), frame);
        assertFalse(frame.contains("findElement("), frame);
        assertFalse(frame.contains("WebDriver"), frame);
        String attach = Files.readString(root.resolve(
                "pickleball-workbench/src/main/java/tools/dscode/workbench/mcp/WorkbenchAttachServer.java"));
        assertTrue(attach.contains("actions.dispatch"), attach);
        String mcp = Files.readString(root.resolve(
                "pickleball-workbench/src/main/java/tools/dscode/workbench/mcp/WorkbenchMcpTools.java"));
        assertTrue(mcp.contains("services.elementInspect"), mcp);
        assertFalse(mcp.contains("findElement("), mcp);
        assertFalse(mcp.contains("WebDriver"), mcp);
        String worker = Files.readString(root.resolve(
                "pickleball-control-api/src/main/java/tools/dscode/control/api/PageElementQuery.java"));
        assertTrue(worker.contains("ElementOperations.findElements"), worker);
        String inspect = Files.readString(root.resolve(
                "pickleball-control-api/src/main/java/tools/dscode/control/api/ElementControl.java"));
        assertTrue(inspect.contains("ElementOperations.findElements"), inspect);
    }

    private static WorkbenchServices services(
            AtomicReference<String> seen,
            ControlBridgePageQueryResult page,
            ControlBridgeElementInspectionResult inspection
    ) {
        return (WorkbenchServices) Proxy.newProxyInstance(
                WorkbenchServices.class.getClassLoader(),
                new Class<?>[]{WorkbenchServices.class},
                (proxy, method, args) -> {
                    if ("pageQuery".equals(method.getName())) {
                        String category = args == null || args.length < 1 || args[0] == null ? "" : args[0].toString();
                        String text = args == null || args.length < 2 || args[1] == null ? "" : args[1].toString();
                        seen.set(category.isBlank() ? "inspect-page" : "find-element:" + category + ":" + text);
                        return page;
                    }
                    if ("elementInspect".equals(method.getName())) {
                        String category = args == null || args[0] == null ? "" : args[0].toString();
                        String text = args == null || args.length < 2 || args[1] == null ? "" : args[1].toString();
                        seen.set("element-inspect:" + category + ":" + text);
                        return inspection;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
    }
}
