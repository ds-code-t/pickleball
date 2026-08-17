package tools.dscode.studio.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeMappingSnapshotStoreTest {

    @Test
    void retainsBoundedSnapshotsPerSessionAndFiltersMetadata() {
        RuntimeMappingSnapshotStore store = new RuntimeMappingSnapshotStore();

        for (int index = 0; index < 52; index++) {
            store.store(
                    "session-a",
                    "runtime-a",
                    "scenario-a",
                    state("MAP-" + index, index)
            );
        }
        store.store("session-b", "runtime-b", "scenario-b", state("OTHER", 99));

        List<RuntimeMappingSnapshotSummary> sessionA = store.list(
                "session-a",
                null,
                null,
                null
        );
        assertEquals(RuntimeMappingSnapshotStore.MAX_SNAPSHOTS_PER_SESSION, sessionA.size());
        assertEquals("MAP-51", sessionA.getFirst().mapReference());
        assertEquals("MAP-2", sessionA.getLast().mapReference());
        assertEquals(
                1,
                store.list("session-b", "runtime-b", "scenario-b", "OTHER").size()
        );
        assertEquals(
                0,
                store.list("session-a", "runtime-b", null, null).size()
        );
    }

    @Test
    void snapshotsOwnTheirJsonStateAndAreSessionBound() {
        RuntimeMappingSnapshotStore store = new RuntimeMappingSnapshotStore();
        ObjectNode values = JsonNodeFactory.instance.objectNode().put("value", "original");
        RuntimeMappingState state = new RuntimeMappingState(
                1,
                "OVERRIDE",
                "OVERRIDE_MAP",
                "tools.dscode.common.mappings.NodeMap",
                List.of("SCENARIO"),
                true,
                values
        );

        RuntimeMappingSnapshot snapshot = store.store(
                "session",
                "runtime",
                "scenario",
                state
        );
        values.put("value", "changed outside");
        snapshot.state().values().put("value", "changed copy");

        RuntimeMappingSnapshot stored = store.get("session", snapshot.snapshotId());
        assertEquals("original", stored.state().values().get("value").asText());
        assertThrows(
                IllegalArgumentException.class,
                () -> store.get("other-session", snapshot.snapshotId())
        );
    }

    private static RuntimeMappingState state(String mapReference, int value) {
        return new RuntimeMappingState(
                1,
                mapReference,
                "OVERRIDE_MAP",
                "tools.dscode.common.mappings.NodeMap",
                List.of(),
                true,
                JsonNodeFactory.instance.objectNode().put("value", value)
        );
    }
}
