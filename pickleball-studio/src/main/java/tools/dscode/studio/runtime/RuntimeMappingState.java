package tools.dscode.studio.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Materialized state for one live consumer NodeMap. */
public record RuntimeMappingState(
        int version,
        String mapReference,
        String mapType,
        String mapClass,
        List<String> dataSources,
        boolean restorable,
        ObjectNode values
) {
    public RuntimeMappingState {
        dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
        values = values == null ? null : values.deepCopy();
    }

    @Override
    public ObjectNode values() {
        return values == null ? null : values.deepCopy();
    }
}
