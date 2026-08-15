package tools.dscode.control.api;

import java.util.List;

/** Versioned JSON-friendly snapshot of materialized ParsingMap resolution sources. */
public record MappingSnapshot(
        int version,
        List<NodeMapSnapshot> maps
) {
    public static final int CURRENT_VERSION = 1;

    public MappingSnapshot {
        maps = maps == null ? List.of() : List.copyOf(maps);
    }
}
