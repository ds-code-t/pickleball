package tools.dscode.common.agentcontrol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Ids the panel already shows as Read. Shared by chat-turn so a read bubble is not a new reply. */
final class ReadMarks {
    private ReadMarks() {
    }

    static void note(Path project, String runId, Collection<String> ids) throws IOException {
        if (ids == null || ids.isEmpty()) return;
        synchronized (ReadMarks.class) {
            Set<String> stored = new LinkedHashSet<>(ids(project, runId));
            boolean changed = false;
            for (String id : ids) {
                if (id == null || id.isBlank()) continue;
                changed |= stored.add(id);
            }
            if (!changed) return;
            Path file = file(project, runId);
            AgentStore.ensure(file.getParent());
            AgentStore.JSON.writeValue(file.toFile(), stored);
        }
    }

    static Set<String> ids(Path project, String runId) {
        Path file = file(project, runId);
        if (!Files.isRegularFile(file)) return Set.of();
        try {
            Set<String> stored = new LinkedHashSet<>();
            for (Object item : AgentStore.JSON.readValue(file.toFile(), java.util.List.class)) {
                if (item != null && !String.valueOf(item).isBlank()) stored.add(String.valueOf(item));
            }
            return stored;
        } catch (IOException failure) {
            return Set.of();
        }
    }

    private static Path file(Path project, String runId) {
        return AgentStore.root(project, runId).resolve("chat").resolve("read.json");
    }
}
