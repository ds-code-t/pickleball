package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one run-index locator. {@code AgentCoordination.findRunIndex} delegates here.
 * {@code DiagnosticQuery} uses the same method, then keeps its own fallback when
 * the shared run directory exists but no index file does.
 *
 * <p>Order: private {@code diagnostic/<id>/run-index.json}, flat private
 * {@code diagnostic/run-index.json}, shared {@code reports/diagnostic-runs/<id>},
 * then the same two names under {@code record.json} {@code dataPath}. A walk of
 * depth 6 follows. The parent folder name wins before the JSON {@code runId}.
 * {@code browser-profile}, {@code config}, and {@code scratch} are skipped.
 * A JSON body over 2 MB is not read.</p>
 */
public final class RunIndexFinder {
    private static final long JSON_BYTES = 2_000_000L;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");

    private RunIndexFinder() {
    }

    public static Path find(Path project, String runId) {
        if (project == null || runId == null || runId.isBlank()) return null;
        Path runDir;
        try {
            runDir = PickleballLocalLayout.root(project).resolve("runs").resolve(requireSafeId(runId));
        } catch (RuntimeException failure) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(runDir.resolve("diagnostic").resolve(runId).resolve("run-index.json"));
        candidates.add(runDir.resolve("diagnostic").resolve("run-index.json"));
        candidates.add(project.resolve("reports").resolve("diagnostic-runs").resolve(runId).resolve("run-index.json"));
        Path record = runDir.resolve("record.json");
        if (Files.isRegularFile(record)) {
            try {
                Map<String, Object> body = JsonLite.object(JsonLite.parse(Files.readString(record, StandardCharsets.UTF_8)));
                String dataPath = body == null ? "" : text(body.get("dataPath"));
                Path data = flexible(dataPath);
                if (data != null) {
                    candidates.add(data.resolve("diagnostic").resolve(runId).resolve("run-index.json"));
                    candidates.add(data.resolve("diagnostic").resolve("run-index.json"));
                }
            } catch (RuntimeException | IOException ignored) {
                // A record that cannot be read is not an index.
            }
        }
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) return candidate;
        }
        Path walked = walk(runDir, runId);
        if (walked != null) return walked;
        return walk(project.resolve("reports").resolve("diagnostic-runs").resolve(runId), runId);
    }

    /**
     * Windows-tolerant path. Strips a {@code \\?\} prefix and treats {@code \} as a separator
     * so a recorded data path can be opened on either host.
     */
    public static Path flexible(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.trim();
        if (text.startsWith("\\\\?\\")) text = text.substring(4);
        return Path.of(text.replace('\\', '/'));
    }

    private static Path walk(Path root, String runId) {
        if (root == null || !Files.isDirectory(root)) return null;
        try (var files = Files.walk(root, 6)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                if (path.getFileName() == null || !"run-index.json".equals(path.getFileName().toString())) continue;
                if (skip(root, path)) continue;
                Path parent = path.getParent();
                if (parent != null && parent.getFileName() != null && runId.equals(parent.getFileName().toString())) {
                    return path;
                }
                try {
                    if (Files.size(path) > JSON_BYTES) continue;
                    Map<String, Object> body = JsonLite.object(
                            JsonLite.parse(Files.readString(path, StandardCharsets.UTF_8)));
                    if (body != null && runId.equals(text(body.get("runId")))) return path;
                } catch (RuntimeException | IOException ignored) {
                    // An unreadable index is not this run.
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static boolean skip(Path root, Path path) {
        Path relative;
        try {
            relative = root.relativize(path);
        } catch (IllegalArgumentException ignored) {
            return true;
        }
        if (relative.getNameCount() == 0) return true;
        String first = relative.getName(0).toString();
        return "browser-profile".equals(first) || "config".equals(first) || "scratch".equals(first);
    }

    private static String requireSafeId(String id) {
        String value = id.trim();
        if (!SAFE_ID.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException("run id must be a single folder name: " + id);
        }
        return value;
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof String text) return text;
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return "";
    }
}
