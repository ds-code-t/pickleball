package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.json.Jsons;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Loads the v1 or v2 schema and accepts a document of that version. */
public final class SnapshotDocuments {
    private SnapshotDocuments() {
    }

    public static JsonNode schema(int version) {
        String resource = switch (version) {
            case 1 -> "/dev/domengine/snapshot/composite-snapshot.schema.json";
            case 2 -> "/dev/domengine/snapshot/composite-snapshot-v2.schema.json";
            default -> throw new IllegalArgumentException("error: unsupported snapshot version " + version);
        };
        try (InputStream in = CompositeSnapshot.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("error: missing snapshot schema " + resource);
            return Jsons.MAPPER.readTree(in);
        } catch (IOException failure) {
            throw new IllegalStateException("error: cannot read snapshot schema " + resource, failure);
        }
    }

    public static CompositeSnapshot read(JsonNode document) {
        int version = CompositeSnapshot.schemaVersionOf(document);
        List<String> errors = SnapshotSchemas.violations(schema(version), document);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("error: snapshot schema\n" + String.join("\n", errors));
        }
        return CompositeSnapshot.fromJson(document);
    }
}
