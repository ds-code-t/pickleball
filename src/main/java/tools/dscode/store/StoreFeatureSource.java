package tools.dscode.store;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature bytes from the hydrated database. When Store is off this returns
 * null and does not open a database, so file loading stays on the files.
 */
public final class StoreFeatureSource {
    private StoreFeatureSource() {
    }

    public static byte[] bytesFor(URI uri) {
        if (!StoreConfig.enabled()) return null;
        List<String> keys = keys(uri);
        if (keys.isEmpty()) return null;
        Path project = StoreConfig.project();
        if (!StorePaths.ready(project)) return null;
        try (StoreDatabase database = StoreDatabase.open(project)) {
            for (String key : keys) {
                StoreFiles.Row row = StoreFiles.find(database.connection(), key);
                if (row != null && row.raw() != null) {
                    StoreLog.log(project, "execute", key, "bytes=" + row.raw().length);
                    return row.raw();
                }
            }
            return null;
        } catch (SQLException failure) {
            StoreLog.log(project, "execute", "files", "unavailable " + failure.getMessage());
            return null;
        }
    }

    /**
     * Extra cache token for an enabled database. Null when Store is off, so
     * the file size and modification time stay the only cache key.
     */
    public static String cacheSuffix() {
        if (!StoreConfig.enabled()) return null;
        Path project = StoreConfig.project();
        if (!StorePaths.ready(project)) return "missing";
        try (StoreDatabase database = StoreDatabase.open(project);
             Statement statement = database.connection().createStatement()) {
            String hydration = "";
            try (ResultSet rows = statement.executeQuery("SELECT hydration_id FROM provenance WHERE id = 1")) {
                if (rows.next() && rows.getString(1) != null) hydration = rows.getString(1);
            }
            StringBuilder body = new StringBuilder(hydration);
            try (ResultSet rows = statement.executeQuery(
                    "SELECT path, content_hash FROM files WHERE source_type IN ('feature', 'call', 'component') ORDER BY path")) {
                while (rows.next()) {
                    body.append('\n').append(rows.getString(1)).append('\0').append(rows.getString(2));
                }
            }
            return StoreHashes.sha256(body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (SQLException failure) {
            return "unreadable";
        }
    }

    static List<String> keys(URI uri) {
        if (uri == null || uri.getScheme() == null) return List.of();
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Path file = Path.of(uri).toAbsolutePath().normalize();
                Path project = StoreConfig.project().toAbsolutePath().normalize();
                if (!file.startsWith(project)) return List.of();
                String relative = project.relativize(file).toString();
                if (relative.isBlank()) return List.of();
                return List.of(StorePaths.normalizeRelative(relative));
            }
            if ("classpath".equalsIgnoreCase(uri.getScheme())) {
                String raw = uri.getSchemeSpecificPart();
                if (raw == null) return List.of();
                while (raw.startsWith("/")) raw = raw.substring(1);
                if (raw.isBlank()) return List.of();
                String relative = StorePaths.normalizeRelative(raw);
                List<String> keys = new ArrayList<>();
                keys.add(relative);
                keys.add("src/test/resources/" + relative);
                keys.add("src/main/resources/" + relative);
                return keys;
            }
        } catch (RuntimeException ignored) {
            return List.of();
        }
        return List.of();
    }
}
