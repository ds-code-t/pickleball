package tools.dscode.control.protocol;

import java.util.Map;

/** Small mutation response envelopes shared by both sides of the wire. */
public final class ControlBridgeResponses {
    private ControlBridgeResponses() {
    }

    public record Removal(boolean removed) { }
    public record ClearResult(int removed) { }
    public record AgentCommandResult(int exit, Map<String, Object> body) { }
    /** In-process verb result. {@code transport} is {@code direct} on this path. */
    public record VerbRunResult(int exit, String out, String err, String transport) { }
    /** Worker quit acknowledgement. {@code event} is {@code browser_lost} when the driver was released. */
    public record BrowserRelease(boolean released, String event) { }
}
