package tools.dscode.studio.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class RuntimeMappingSnapshotStore {
    static final int MAX_SNAPSHOTS_PER_SESSION = 50;

    private final Map<String, LinkedHashMap<String, RuntimeMappingSnapshot>> sessions =
            new LinkedHashMap<>();

    synchronized RuntimeMappingSnapshot store(
            String sessionId,
            String runtimeId,
            String scenarioId,
            RuntimeMappingState state
    ) {
        Objects.requireNonNull(state, "state");
        LinkedHashMap<String, RuntimeMappingSnapshot> snapshots =
                sessions.computeIfAbsent(sessionId, ignored -> new LinkedHashMap<>());
        String snapshotId = UUID.randomUUID().toString();
        RuntimeMappingSnapshot snapshot = new RuntimeMappingSnapshot(
                snapshotId,
                Instant.now().toString(),
                sessionId,
                runtimeId,
                scenarioId,
                state
        );
        snapshots.put(snapshotId, snapshot);
        trim(snapshots);
        return snapshot;
    }

    synchronized RuntimeMappingSnapshot get(String sessionId, String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("Mapping snapshot id must not be blank.");
        }
        RuntimeMappingSnapshot snapshot = sessions
                .getOrDefault(sessionId, new LinkedHashMap<>())
                .get(snapshotId);
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Unknown mapping snapshot " + snapshotId + " for session " + sessionId
            );
        }
        return snapshot;
    }

    synchronized List<RuntimeMappingSnapshotSummary> list(
            String sessionId,
            String runtimeId,
            String scenarioId,
            String mapReference
    ) {
        LinkedHashMap<String, RuntimeMappingSnapshot> snapshots = sessions.get(sessionId);
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }

        String runtimeFilter = normalized(runtimeId);
        String scenarioFilter = normalized(scenarioId);
        String mapFilter = normalized(mapReference);
        List<RuntimeMappingSnapshotSummary> result = new ArrayList<>();
        for (RuntimeMappingSnapshot snapshot : snapshots.values()) {
            if (runtimeFilter != null && !runtimeFilter.equals(snapshot.runtimeId())) {
                continue;
            }
            if (scenarioFilter != null && !scenarioFilter.equals(snapshot.scenarioId())) {
                continue;
            }
            if (mapFilter != null && !mapFilter.equals(snapshot.mapReference())) {
                continue;
            }
            result.add(summary(snapshot));
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    synchronized void clear() {
        sessions.clear();
    }

    private static RuntimeMappingSnapshotSummary summary(RuntimeMappingSnapshot snapshot) {
        return new RuntimeMappingSnapshotSummary(
                snapshot.snapshotId(),
                snapshot.capturedAt(),
                snapshot.runtimeId(),
                snapshot.scenarioId(),
                snapshot.mapReference(),
                snapshot.mapType(),
                snapshot.mapClass(),
                snapshot.restorable()
        );
    }

    private static void trim(LinkedHashMap<String, RuntimeMappingSnapshot> snapshots) {
        Iterator<String> iterator = snapshots.keySet().iterator();
        while (snapshots.size() > MAX_SNAPSHOTS_PER_SESSION && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
