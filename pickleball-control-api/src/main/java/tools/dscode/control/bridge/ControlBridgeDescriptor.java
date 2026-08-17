
package tools.dscode.control.bridge;

import java.util.List;

public record ControlBridgeDescriptor(
        int protocolVersion,
        String sessionId,
        String runtimeId,
        long pid,
        String host,
        int port,
        String startedAt,
        List<String> capabilities
) {
    public ControlBridgeDescriptor {
        capabilities = List.copyOf(capabilities);
    }
}
