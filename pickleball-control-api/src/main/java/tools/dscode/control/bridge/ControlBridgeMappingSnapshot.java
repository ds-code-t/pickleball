package tools.dscode.control.bridge;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Materialized state for one live NodeMap captured through the Studio bridge. */
public record ControlBridgeMappingSnapshot(
        int version,
        String mapReference,
        String mapType,
        String mapClass,
        List<String> dataSources,
        boolean restorable,
        ObjectNode values
) {
    public static final int CURRENT_VERSION = 1;

    public ControlBridgeMappingSnapshot {
        dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
        values = values == null ? null : values.deepCopy();
    }

    @Override
    public ObjectNode values() {
        return values == null ? null : values.deepCopy();
    }
}
