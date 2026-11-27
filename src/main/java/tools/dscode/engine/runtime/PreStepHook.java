package tools.dscode.engine.runtime;

import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.engine.EngineLog;
import tools.dscode.store.DeltaLog;
import tools.dscode.store.StoreScope;
import tools.dscode.store.sql.SqlStore;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies fork {@code kind=map} deltas immediately before a step.
 * The step and its children see the keys. The caller removes them after the
 * step so earlier steps and later non-descendant steps do not.
 * The hook reads deltas. It does not read the Node page.
 */
public final class PreStepHook {
    private PreStepHook() {
    }

    public record Slot(NodeMap map, String key, Object previous, boolean had) {
    }

    public record Applied(List<Slot> slots) {
    }

    /** Puts the fork's map values for this 1-based step onto the scenario map. */
    public static Applied apply(Path project, int step, ParsingMap map) {
        List<Slot> slots = new ArrayList<>();
        int count = 0;
        try {
            if (project != null && map != null && step > 0 && !StoreScope.current().primary()) {
                Map<String, String> values = values(project, step);
                count = values.size();
                for (NodeMap target : runMaps(map)) {
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        Object previous = target.get(entry.getKey());
                        target.put(entry.getKey(), entry.getValue());
                        slots.add(new Slot(target, entry.getKey(), previous, previous != null));
                    }
                }
            }
        } catch (SQLException | IOException | RuntimeException ignored) {
            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
            for (Slot slot : slots) keys.add(slot.key());
            count = keys.size();
        }
        EngineLog.log(project, "match", "step", "pre-step hook keys=" + count);
        return new Applied(List.copyOf(slots));
    }

    /** Restores every map {@link #apply} wrote, including the shared run map the step reads. */
    public static void clear(ParsingMap map, Applied applied) {
        if (applied == null || applied.slots().isEmpty()) return;
        NodeMap fallback = map == null ? null : map.getPrimaryRunMap();
        for (int index = applied.slots().size() - 1; index >= 0; index--) {
            Slot slot = applied.slots().get(index);
            NodeMap target = slot.map() == null ? fallback : slot.map();
            if (target == null) continue;
            if (!slot.had()) target.getRoot().remove(slot.key());
            else target.put(slot.key(), slot.previous());
        }
    }

    /**
     * The scenario map and {@link MappingProcessor#getRunMap()} are different objects
     * after {@code ExecutionContext} replaces {@code RUN_MAP}. The running step reads the shared one.
     */
    private static List<NodeMap> runMaps(ParsingMap map) {
        List<NodeMap> targets = new ArrayList<>();
        if (map != null) {
            NodeMap primary = map.getPrimaryRunMap();
            if (primary != null) targets.add(primary);
        }
        NodeMap shared = MappingProcessor.getRunMap();
        if (shared != null) {
            boolean seen = false;
            for (NodeMap existing : targets) {
                if (existing == shared) seen = true;
            }
            if (!seen) targets.add(shared);
        }
        return targets;
    }

    private static Map<String, String> values(Path project, int step) throws SQLException, IOException {
        try (SqlStore store = SqlStore.open(project, StoreScope.current())) {
            Connection connection = store.session().connection();
            String source = DeltaLog.forkSource(connection);
            Map<String, String> latest = new LinkedHashMap<>();
            for (DeltaLog.MapEdit edit : DeltaLog.mapEdits(connection)) {
                if (edit.step() != step) continue;
                if (!source.isBlank() && !source.equals(edit.run())) continue;
                latest.put(edit.key(), edit.value());
            }
            return latest;
        }
    }
}
