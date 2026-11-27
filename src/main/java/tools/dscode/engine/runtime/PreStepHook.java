package tools.dscode.engine.runtime;

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

    public record Slot(String key, Object previous, boolean had) {
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
                NodeMap runMap = map.getPrimaryRunMap();
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    Object previous = runMap.get(entry.getKey());
                    runMap.put(entry.getKey(), entry.getValue());
                    slots.add(new Slot(entry.getKey(), previous, previous != null));
                }
            }
        } catch (SQLException | IOException | RuntimeException ignored) {
            count = slots.size();
        }
        EngineLog.log(project, "match", "step", "pre-step hook keys=" + count);
        return new Applied(List.copyOf(slots));
    }

    /** Restores the map to the values from before {@link #apply}. */
    public static void clear(ParsingMap map, Applied applied) {
        if (map == null || applied == null || applied.slots().isEmpty()) return;
        NodeMap runMap = map.getPrimaryRunMap();
        for (int index = applied.slots().size() - 1; index >= 0; index--) {
            Slot slot = applied.slots().get(index);
            if (!slot.had()) runMap.getRoot().remove(slot.key());
            else runMap.put(slot.key(), slot.previous());
        }
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
