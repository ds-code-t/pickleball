package tools.dscode.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** {@code RECORD ENTITY} writes the entity, a mention, and a snapshot identity. */
public final class RecordedEntity {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RecordedEntity() {
    }

    public static void write(String value, String type, String id) {
        StoreRunContext.Frame frame = StoreRunContext.current();
        if (frame == null || frame.stepExecId() == null || frame.stepExecId().isBlank()) {
            throw new IllegalStateException(
                    "error: RECORD ENTITY needs a tracked step\nfix: set pkb_store_track=true");
        }
        String stored = value == null ? "" : value;
        try {
            StoreSecrets.rejectAssignment(frame.project(), stored);
        } catch (IllegalArgumentException secret) {
            stored = "~MASK;";
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("value", stored);
        try {
            StoreEntities.Entity entity = StoreEntities.put(
                    frame.project(), type, id, 1, JSON.writeValueAsString(payload));
            StoreEntities.note(frame.project(), frame.stepExecId(), type, id, null, "mention", stored);
            String runId = StoreExecution.runOfStep(frame.project(), frame.stepExecId());
            if (runId == null || runId.isBlank()) {
                throw new IllegalStateException(
                        "error: RECORD ENTITY needs a tracked step\nfix: set pkb_store_track=true");
            }
            try (StoreDatabase database = StoreDatabase.open(frame.project());
                 PreparedStatement statement = database.connection().prepareStatement("""
                         INSERT INTO run_snapshot_entities(run_id, entity_type, entity_id, content_hash)
                         VALUES (?, ?, ?, ?)
                         ON CONFLICT(run_id, entity_type, entity_id) DO UPDATE SET content_hash = excluded.content_hash
                         """)) {
                statement.setString(1, runId);
                statement.setString(2, type);
                statement.setString(3, id);
                statement.setString(4, entity.contentHash());
                statement.executeUpdate();
            }
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "error: RECORD ENTITY could not be stored\nfix: store hydrate", failure);
        }
    }
}
