package tools.dscode.control.bridge;

/** Safe cross-JVM representation of a live Pickleball value. */
public record ControlBridgeValue(
        String type,
        boolean jsonCompatible,
        Object jsonValue,
        String text
) {
}
