
package tools.dscode.studio.runtime;

import java.util.List;

public record RuntimeBridgeDescriptor(
        int protocolVersion,
        String sessionId,
        String runtimeId,
        long pid,
        String host,
        int port,
        String startedAt,
        List<String> capabilities
) {
    public RuntimeBridgeDescriptor {
        capabilities = List.copyOf(capabilities);
    }
}
