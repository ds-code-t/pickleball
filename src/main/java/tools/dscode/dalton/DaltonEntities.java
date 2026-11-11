package tools.dscode.dalton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Business entities. Files under {@code dalton-entities/} stay the Git source of truth.
 * The reference syntax is {@code <entity:Type#id.field>}. Payloads hold secret
 * references such as {@code <password:qaDb>} or {@code ~MASK;}, never cleartext.
 */
public final class DaltonEntities {
    public static final String REFERENCE_PREFIX = "entity:";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final Pattern REFERENCE = Pattern.compile(
            "entity:([A-Za-z][A-Za-z0-9_-]*)#([A-Za-z0-9][A-Za-z0-9_-]*)(?:\\.([A-Za-z_][A-Za-z0-9_-]*(?:\\.[A-Za-z_][A-Za-z0-9_-]*)*))?");

    private DaltonEntities() {
    }

    public record Entity(
            String type,
            String id,
            int version,
            String payloadJson,
            String secretRefs,
            String sourceFile,
            String contentHash
    ) {
    }

    public record ExportResult(int written, int skipped, int conflicts, List<String> conflictPaths) {
        public ExportResult {
            conflictPaths = conflictPaths == null ? List.of() : List.copyOf(conflictPaths);
        }

        public String line() {
            return "exported written=" + written + " skipped=" + skipped + " conflicts=" + conflicts;
        }
    }

    public static boolean isRecord(String path, String sourceType) {
        return "entity".equals(sourceType) && path != null && path.endsWith(".entity");
    }

    public static String relativePath(String type, String id) {
        requireType(type);
        requireId(id);
        return "dalton-entities/" + type + "/" + id + ".entity";
    }

    /** Parses a {@code .entity} file. Rejects cleartext secret fields. */
    public static Entity parseRecord(byte[] raw) {
        return parseRecord(DaltonConfig.project(), raw);
    }

    public static Entity parseRecord(Path project, byte[] raw) {
        if (raw == null) {
            throw new IllegalArgumentException("entity file is empty");
        }
        String type = null;
        String id = null;
        int version = -1;
        String payload = null;
        for (String line : new String(raw, StandardCharsets.UTF_8).split("\\r\\n|\\n|\\r", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon < 1) {
                throw new IllegalArgumentException("entity line needs a key: " + trimmed);
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            switch (key) {
                case "type" -> type = value;
                case "id" -> id = value;
                case "version" -> version = parseVersion(value);
                case "payload" -> payload = value;
                default -> throw new IllegalArgumentException("Unknown entity field: " + key);
            }
        }
        requireType(type);
        requireId(id);
        if (version < 1) {
            throw new IllegalArgumentException("entity version must be 1 or greater");
        }
        if (payload == null) {
            throw new IllegalArgumentException("entity payload is missing");
        }
        Prepared prepared = preparePayload(project, payload);
        String path = relativePath(type, id);
        byte[] canonical = canonicalFile(type, id, version, prepared.payload());
        return new Entity(type, id, version, prepared.payload(), prepared.secretRefs(), path, DaltonHashes.sha256(canonical));
    }

    public static void store(Connection connection, Path project, String path, byte[] raw) throws SQLException {
        Entity entity = parseRecord(project, raw);
        if (!entity.sourceFile().equals(path)) {
            throw new IllegalArgumentException(
                    "entity path " + path + " does not match " + entity.sourceFile());
        }
        upsert(connection, entity, Instant.now().toString());
        DaltonLog.log(project, "entity", entity.type() + "#" + entity.id(), "stored version=" + entity.version());
    }

    public static void storeIfMissing(Connection connection, Path project, String path, byte[] raw) throws SQLException {
        Entity parsed = parseRecord(project, raw);
        if (find(connection, parsed.type(), parsed.id()) != null) return;
        store(connection, project, path, raw);
    }

    public static Entity put(Path project, String type, String id, Integer version, String payloadJson) throws SQLException {
        requireType(type);
        requireId(id);
        Prepared prepared = preparePayload(project, payloadJson);
        String path = relativePath(type, id);
        String now = Instant.now().toString();
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            Connection connection = database.connection();
            Entity previous = find(connection, type, id);
            int useVersion = version != null ? version : (previous == null ? 1 : previous.version() + 1);
            if (useVersion < 1) {
                throw new IllegalArgumentException("entity version must be 1 or greater");
            }
            byte[] canonical = canonicalFile(type, id, useVersion, prepared.payload());
            connection.setAutoCommit(false);
            try {
                DaltonContent.write(connection, project, path, "entity", canonical, now);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            Entity entity = find(connection, type, id);
            DaltonLog.log(project, "entity", type + "#" + id, "put version=" + useVersion);
            return entity;
        }
    }

    public static Entity get(Path project, String type, String id) throws SQLException {
        if (!Files.isRegularFile(DaltonPaths.database(project))) return null;
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            return find(database.connection(), type, id);
        }
    }

    public static List<Entity> list(Path project) throws SQLException {
        if (!Files.isRegularFile(DaltonPaths.database(project))) return List.of();
        try (DaltonDatabase database = DaltonDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement(
                     "SELECT entity_type, entity_id, version, payload_json, secret_refs, source_file, content_hash FROM entities ORDER BY entity_type, entity_id");
             ResultSet rows = statement.executeQuery()) {
            List<Entity> entities = new ArrayList<>();
            while (rows.next()) entities.add(read(rows));
            return entities;
        }
    }

    public static boolean remove(Path project, String type, String id, boolean force) throws SQLException, IOException {
        requireType(type);
        requireId(id);
        String path = relativePath(type, id);
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            Connection connection = database.connection();
            Entity entity = find(connection, type, id);
            if (entity == null) return false;
            DaltonFiles.Row file = DaltonFiles.find(connection, path);
            Path disk = project.resolve(path);
            if (Files.isRegularFile(disk) && file != null) {
                String diskHash = DaltonHashes.sha256(Files.readAllBytes(disk));
                boolean fileMoved = file.lastSyncedHash() != null
                        && !file.lastSyncedHash().isBlank()
                        && !diskHash.equals(file.lastSyncedHash());
                if (fileMoved && !force) {
                    throw new IllegalArgumentException("conflict: " + path);
                }
            }
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM entities WHERE entity_type = ? AND entity_id = ?")) {
                    statement.setString(1, type);
                    statement.setString(2, id);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM files WHERE path = ?")) {
                    statement.setString(1, path);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        Path disk = project.resolve(path);
        if (Files.isRegularFile(disk)) {
            Files.delete(disk);
        }
        DaltonLog.log(project, "entity", type + "#" + id, "removed");
        return true;
    }

    public static ExportResult export(Path project, boolean force) throws SQLException, IOException {
        DaltonExtractor.Result result = DaltonExtractor.extract(
                project, new DaltonExtractor.Options(List.of("dalton-entities"), force));
        DaltonLog.log(project, "entity", "files",
                "export written=" + result.written() + " conflicts=" + result.conflicts());
        return new ExportResult(result.written(), result.skipped(), result.conflicts(), result.conflictPaths());
    }

    /**
     * Resolves {@code entity:Type#id} or {@code entity:Type#id.field} when Dalton
     * is on. Returns null when Dalton is off, the syntax does not match, or the
     * entity is absent. Does not create a database.
     */
    public static Object lookup(String source) {
        if (source == null || !DaltonConfig.enabled()) return null;
        Matcher matcher = REFERENCE.matcher(source.trim());
        if (!matcher.matches()) return null;
        String type = matcher.group(1);
        String id = matcher.group(2);
        String field = matcher.group(3);
        try {
            Entity entity = readForLookup(type, id);
            if (entity == null) return null;
            if (field == null || field.isBlank()) return entity.payloadJson();
            return fieldValue(entity.payloadJson(), field);
        } catch (RuntimeException | IOException | SQLException failure) {
            DaltonLog.log(DaltonConfig.project(), "entity", type + "#" + id, "lookup failed " + failure.getMessage());
            return null;
        }
    }

    public static byte[] canonicalFile(String type, String id, int version, String payloadJson) {
        String text = "type: " + type + "\n"
                + "id: " + id + "\n"
                + "version: " + version + "\n"
                + "payload: " + payloadJson + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Entity readForLookup(String type, String id) throws SQLException, IOException {
        Path project = DaltonConfig.project();
        if (Files.isRegularFile(DaltonPaths.database(project))) {
            Entity stored = get(project, type, id);
            if (stored != null) return stored;
        }
        Path file = project.resolve(relativePath(type, id));
        if (!Files.isRegularFile(file)) return null;
        return parseRecord(project, Files.readAllBytes(file));
    }

    private static Object fieldValue(String payload, String field) throws IOException {
        JsonNode node = JSON.readTree(payload);
        for (String part : field.split("\\.")) {
            if (node == null || !node.isObject()) return null;
            node = node.get(part);
        }
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue() ? "true" : "false";
        if (node.isIntegralNumber()) return node.bigIntegerValue().toString();
        if (node.isNumber()) return node.decimalValue().stripTrailingZeros().toPlainString();
        return canonical(node);
    }

    private static void upsert(Connection connection, Entity entity, String updatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO entities(entity_type, entity_id, version, payload_json, secret_refs, source_file, content_hash, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(entity_type, entity_id) DO UPDATE SET
                    version = excluded.version,
                    payload_json = excluded.payload_json,
                    secret_refs = excluded.secret_refs,
                    source_file = excluded.source_file,
                    content_hash = excluded.content_hash,
                    updated_at = excluded.updated_at
                """)) {
            statement.setString(1, entity.type());
            statement.setString(2, entity.id());
            statement.setInt(3, entity.version());
            statement.setString(4, entity.payloadJson());
            statement.setString(5, entity.secretRefs());
            statement.setString(6, entity.sourceFile());
            statement.setString(7, entity.contentHash());
            statement.setString(8, updatedAt);
            statement.executeUpdate();
        }
    }

    private static Entity find(Connection connection, String type, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT entity_type, entity_id, version, payload_json, secret_refs, source_file, content_hash FROM entities WHERE entity_type = ? AND entity_id = ?")) {
            statement.setString(1, type);
            statement.setString(2, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        }
    }

    private static Entity read(ResultSet rows) throws SQLException {
        return new Entity(
                rows.getString(1),
                rows.getString(2),
                rows.getInt(3),
                rows.getString(4),
                rows.getString(5),
                rows.getString(6),
                rows.getString(7)
        );
    }

    private record Prepared(String payload, String secretRefs) {
    }

    private static Prepared preparePayload(Path project, String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("entity payload is missing");
        }
        JsonNode node;
        try {
            node = JSON.readTree(payloadJson);
        } catch (IOException failure) {
            throw new IllegalArgumentException("entity payload is not JSON: " + failure.getMessage());
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("entity payload must be a JSON object");
        }
        List<String> refs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        rejectSecrets(project, node, refs, warnings);
        for (String field : warnings) {
            String message = DaltonSecrets.warning(field);
            DaltonLog.log(project, "entity", field, message);
            System.err.println(message);
        }
        Collections.sort(refs);
        return new Prepared(canonical(node), refs.isEmpty() ? "[]" : jsonArray(refs));
    }

    private static void rejectSecrets(Path project, JsonNode node, List<String> refs, List<String> warnings) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (DaltonSecrets.secretName(project, entry.getKey())) {
                    if (!DaltonSecrets.reference(entry.getValue() == null || !entry.getValue().isTextual()
                            ? null
                            : entry.getValue().textValue())) {
                        throw new IllegalArgumentException(
                                "entity field " + entry.getKey() + " must be a secret reference, not cleartext");
                    }
                    refs.add(entry.getValue().textValue().trim());
                } else {
                    if (entry.getValue() != null && entry.getValue().isTextual()
                            && DaltonSecrets.highEntropy(entry.getValue().textValue())) {
                        warnings.add(entry.getKey());
                    }
                    rejectSecrets(project, entry.getValue(), refs, warnings);
                }
            });
        } else if (node.isArray()) {
            for (JsonNode child : node) rejectSecrets(project, child, refs, warnings);
        }
    }

    private static String jsonArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) json.append(',');
            json.append(quote(values.get(index)));
        }
        json.append(']');
        return json.toString();
    }

    static String canonical(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isTextual()) return quote(node.textValue());
        if (node.isBoolean()) return node.booleanValue() ? "true" : "false";
        if (node.isIntegralNumber()) return node.bigIntegerValue().toString();
        if (node.isNumber()) return node.decimalValue().stripTrailingZeros().toPlainString();
        if (node.isArray()) {
            StringBuilder json = new StringBuilder("[");
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) json.append(',');
                json.append(canonical(node.get(index)));
            }
            json.append(']');
            return json.toString();
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            StringBuilder json = new StringBuilder("{");
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) json.append(',');
                String name = names.get(index);
                json.append(quote(name)).append(':').append(canonical(node.get(name)));
            }
            json.append('}');
            return json.toString();
        }
        throw new IllegalArgumentException("unsupported JSON in entity payload");
    }

    private static String quote(String value) {
        StringBuilder json = new StringBuilder(value.length() + 2);
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) json.append(String.format("\\u%04x", (int) c));
                    else json.append(c);
                }
            }
        }
        json.append('"');
        return json.toString();
    }

    private static int parseVersion(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("entity version is not an integer");
        }
    }

    private static void requireType(String type) {
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("entity type must match [A-Za-z][A-Za-z0-9_-]*");
        }
    }

    private static void requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("entity id must match [A-Za-z0-9][A-Za-z0-9_-]*");
        }
    }
}
