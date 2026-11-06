package tools.dscode.control.protocol;

import java.util.Map;

/** Small mutation response envelopes shared by both sides of the wire. */
public final class ControlBridgeResponses {
    private ControlBridgeResponses() {
    }

    public record Removal(boolean removed) { }
    public record ClearResult(int removed) { }
    public record AgentCommandResult(int exit, Map<String, Object> body) { }
}
