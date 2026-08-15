package tools.dscode.control.api;

import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;

import java.util.ArrayList;
import java.util.List;

/** Restores the exact prior live NodeMap references and resolution order when closed. */
public final class MappingScope implements AutoCloseable {
    private final ParsingMap target;
    private final List<NodeMap> previousMaps;
    private final List<MapConfigurations.MapType> previousOrder;
    private boolean closed;

    MappingScope(ParsingMap target, MappingContext replacement) {
        this.target = target;
        this.previousMaps = new ArrayList<>(target.getMaps().values());
        this.previousOrder = new ArrayList<>(target.keyOrder());
        MappingControl.installExact(target, replacement.maps());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        MappingControl.installExact(target, previousMaps, previousOrder);
        closed = true;
    }
}
