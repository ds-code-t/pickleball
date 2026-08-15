package tools.dscode.control.api;

import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;

import java.util.List;
import java.util.Objects;

/** Caller-defined mapping sources used by detached dynamic execution. */
public final class MappingContext {
    private final String description;
    private final List<NodeMap> maps;

    MappingContext(String description, List<NodeMap> maps) {
        this.description = description == null ? "" : description;
        this.maps = List.copyOf(Objects.requireNonNull(maps, "maps"));
        if (this.maps.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("mapping context must not contain null NodeMaps");
        }
    }

    public String description() {
        return description;
    }

    public List<NodeMap> maps() {
        return maps;
    }

    public NodeMap map(MapConfigurations.MapType type) {
        return maps.stream()
                .filter(map -> map.getMapType() == type)
                .findFirst()
                .orElse(null);
    }

    /** Builds a ParsingMap view over the same NodeMap instances in this context. */
    public ParsingMap parsingMap() {
        return MappingControl.isolatedParsingMap(maps);
    }
}
