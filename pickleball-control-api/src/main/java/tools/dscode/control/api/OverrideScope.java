package tools.dscode.control.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.common.mappings.NodeMap;

/** Scoped mutation of the live thread's OVERRIDE NodeMap. */
public final class OverrideScope implements AutoCloseable {
    private final NodeMap overrideMap;
    private final ObjectNode previousRoot;
    private boolean closed;

    OverrideScope(NodeMap overrideMap) {
        this.overrideMap = overrideMap;
        this.previousRoot = overrideMap.getRoot().deepCopy();
    }

    public NodeMap map() {
        return overrideMap;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        overrideMap.clearValues();
        overrideMap.merge(previousRoot);
        closed = true;
    }
}
