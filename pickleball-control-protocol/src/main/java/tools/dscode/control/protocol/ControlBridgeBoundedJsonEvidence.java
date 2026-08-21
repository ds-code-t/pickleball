package tools.dscode.control.protocol;

/** Bounded JSON-compatible evidence without consumer-runtime object types. */
public record ControlBridgeBoundedJsonEvidence(
        Object value,
        int utf8Bytes,
        boolean truncated
) {
    public ControlBridgeBoundedJsonEvidence {
        value = ControlBridgeJson.immutableValue(value);
    }
}
