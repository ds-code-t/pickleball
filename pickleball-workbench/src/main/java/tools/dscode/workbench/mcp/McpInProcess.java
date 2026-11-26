package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.workbench.WorkbenchServices;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-process call of one Workbench MCP tool. The production path is
 * {@link WorkbenchMcpTools#call}. Tests use this so they do not copy the server harness.
 */
public final class McpInProcess {
    private McpInProcess() {
    }

    public static Map<String, Object> call(Path project, String tool, Map<String, Object> arguments) {
        WorkbenchServices services = (WorkbenchServices) Proxy.newProxyInstance(
                WorkbenchServices.class.getClassLoader(),
                new Class<?>[]{WorkbenchServices.class},
                (proxy, method, args) -> {
                    if ("projectRoot".equals(method.getName())) return project;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
        Object value = new WorkbenchMcpTools(services, new ObjectMapper()).call(tool, arguments);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("tool returned " + value);
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }
}
