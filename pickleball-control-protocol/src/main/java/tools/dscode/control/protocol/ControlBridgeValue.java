package tools.dscode.control.protocol;

/** Safe cross-JVM representation of a live Pickleball value. */
public record ControlBridgeValue(
        String type,
        boolean jsonCompatible,
        Object jsonValue,
        String text
) {
    public ControlBridgeValue {
        jsonValue = ControlBridgeJson.immutableValue(jsonValue);
    }
}
