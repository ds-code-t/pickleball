package tools.dscode.control.protocol;

import java.util.List;
import java.util.Map;

/** Materialized JSON-compatible state for one live NodeMap captured over the wire. */
public record ControlBridgeMappingSnapshot(
        int version,
        String mapReference,
        String mapType,
        String mapClass,
        List<String> dataSources,
        boolean restorable,
        Map<String, Object> values
) {
    public static final int CURRENT_VERSION = 1;

    public ControlBridgeMappingSnapshot {
        dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
        values = values == null ? null : ControlBridgeJson.immutableObject(values);
    }
}
