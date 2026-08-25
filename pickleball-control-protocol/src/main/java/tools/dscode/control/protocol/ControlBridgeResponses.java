package tools.dscode.control.protocol;

/** Small mutation response envelopes shared by both sides of the wire. */
public final class ControlBridgeResponses {
    private ControlBridgeResponses() {
    }

    public record Removal(boolean removed) { }
    public record ClearResult(int removed) { }
}
