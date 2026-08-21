
package tools.dscode.control.protocol;

import java.util.List;

public record ControlBridgeDescriptor(
        int protocolVersion,
        int minimumCompatibleProtocolVersion,
        String sessionId,
        String runtimeId,
        long pid,
        String host,
        int port,
        String startedAt,
        String runtimeVersion,
        String runtimeCodeSource,
        List<String> capabilities
) {
    public ControlBridgeDescriptor {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
