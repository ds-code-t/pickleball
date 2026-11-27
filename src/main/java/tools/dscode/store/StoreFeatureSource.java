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
        try {
            for (String key : keys) {
                StoreQuery.FileDetail detail = StoreQuery.file(project, key);
                if (detail == null || detail.text() == null) continue;
                byte[] raw = detail.text().getBytes(StandardCharsets.UTF_8);
                StoreLog.log(project, "execute", key, "bytes=" + raw.length);
                return raw;
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

    /**
     * True when the URI is a path the store hydrates, or a row that is already
     * stored. A file outside the project, and a classpath path outside those
     * roots, stays on the file loader.
     */
    public static boolean addressable(URI uri) {
        return addressable(StoreConfig.project(), uri);
    }

    /** Ownership for one run. The engine project wins over {@code pkb_store_project}. */
    public static boolean addressable(Path project, URI uri) {
        return !keys(project, uri).isEmpty();
    }

    public static List<String> keys(URI uri) {
        return keys(StoreConfig.project(), uri);
    }

    public static List<String> keys(Path project, URI uri) {
        if (uri == null || uri.getScheme() == null || project == null) return List.of();
        try {
            project = project.toAbsolutePath().normalize();
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Path file = Path.of(uri).toAbsolutePath().normalize();
                if (!file.startsWith(project)) return List.of();
                String relative = project.relativize(file).toString();
                if (relative.isBlank()) return List.of();
                String key = StorePaths.normalizeRelative(relative);
                return owned(project, key) ? List.of(key) : List.of();
            }
            if ("classpath".equalsIgnoreCase(uri.getScheme())) {
                String raw = uri.getSchemeSpecificPart();
                if (raw == null) return List.of();
                while (raw.startsWith("/")) raw = raw.substring(1);
                if (raw.isBlank()) return List.of();
                String relative = StorePaths.normalizeRelative(raw);
                List<String> keys = new ArrayList<>();
                remember(project, keys, relative);
                remember(project, keys, "src/test/resources/" + relative);
                remember(project, keys, "src/main/resources/" + relative);
                return List.copyOf(keys);
            }
        } catch (RuntimeException ignored) {
            return List.of();
        }
        return List.of();
    }

    private static void remember(Path project, List<String> keys, String relative) {
        String key = StorePaths.normalizeRelative(relative);
        if (key.isBlank() || keys.contains(key) || !owned(project, key)) return;
        keys.add(key);
    }

    /** A hydrate root that exists, or a row already stored at this path. */
    private static boolean owned(Path project, String relative) {
        String norm = StorePaths.normalizeRelative(relative);
        if (norm.isBlank()) return false;
        if (underHydrateRoot(project, norm)) return true;
        return stored(project, norm);
    }

    private static boolean underHydrateRoot(Path project, String norm) {
        StoreDiscovery discovery = StoreDiscovery.load(project);
        List<String> roots = new ArrayList<>();
        if (discovery.defaultIncludes()) {
            roots.addAll(StoreSources.DEFAULT_DIRECTORIES);
            roots.addAll(StoreSources.DEFAULT_FILES);
        } else {
            roots.addAll(discovery.includes());
        }
        for (String root : roots) {
            String prefix = rootPrefix(root);
            boolean nested = !prefix.isBlank() && (norm.equals(prefix) || norm.startsWith(prefix + "/"));
            if (!nested && !discovery.defaultIncludes() && StoreDiscovery.glob(root, norm)) nested = true;
            if (!nested) continue;
            Path anchor = prefix.isBlank() ? project.resolve(norm) : project.resolve(prefix);
            if (Files.exists(anchor)) return true;
        }
        return false;
    }

    private static String rootPrefix(String root) {
        String normalized = StorePaths.normalizeRelative(root);
        int star = normalized.indexOf('*');
        int question = normalized.indexOf('?');
        int cut = star < 0 ? question : (question < 0 ? star : Math.min(star, question));
        if (cut < 0) return normalized;
        String prefix = normalized.substring(0, cut);
        int slash = prefix.lastIndexOf('/');
        return slash >= 0 ? prefix.substring(0, slash) : "";
    }

    private static boolean stored(Path project, String norm) {
        if (!StorePaths.ready(project)) return false;
        try (StoreDatabase database = StoreDatabase.open(project)) {
            return StoreFiles.find(database.connection(), norm) != null;
        } catch (SQLException failure) {
            return false;
        }
    }
}
