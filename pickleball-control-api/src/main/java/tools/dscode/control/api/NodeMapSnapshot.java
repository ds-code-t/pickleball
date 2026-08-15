package tools.dscode.control.api;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Serializable materialized NodeMap state. */
public record NodeMapSnapshot(
        String mapType,
        List<String> dataSources,
        ObjectNode values
) {
    public NodeMapSnapshot {
        dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
        values = values == null ? null : values.deepCopy();
    }

    @Override
    public ObjectNode values() {
        return values == null ? null : values.deepCopy();
    }
}
