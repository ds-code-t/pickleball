package dev.domengine.log;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only view of capped log files. It does not trim or rewrite them.
 */
public final class LogDashboard {
    private static final int MAX_LINES = 2000;

    private LogDashboard() {
    }

    public static ObjectNode list(List<Path> dirs) throws IOException {
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        ArrayNode files = node.putArray("files");
        for (Listed file : listed(dirs)) {
            ObjectNode one = files.addObject();
            one.put("name", file.path.getFileName().toString());
            one.put("path", file.path.toAbsolutePath().normalize().toString());
            one.put("bytes", file.bytes);
            one.put("modified", file.modified);
        }
        return node;
    }

    public static ObjectNode tail(List<Path> dirs, String file, int lines) throws IOException {
        Path path = resolve(dirs, file);
        List<String> all = Files.readAllLines(path, StandardCharsets.UTF_8);
        int keep = Math.max(1, Math.min(lines <= 0 ? 40 : lines, MAX_LINES));
        int from = Math.max(0, all.size() - keep);
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("file", path.getFileName().toString());
        node.put("path", path.toAbsolutePath().normalize().toString());
        node.put("bytes", Files.size(path));
        ArrayNode kept = node.putArray("lines");
        for (int index = from; index < all.size(); index++) kept.add(all.get(index));
        return node;
    }

    public static ObjectNode show(List<Path> dirs, String file) throws IOException {
        Path path = resolve(dirs, file);
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("file", path.getFileName().toString());
        node.put("path", path.toAbsolutePath().normalize().toString());
        node.put("bytes", Files.size(path));
        node.put("text", Files.readString(path, StandardCharsets.UTF_8));
        return node;
    }

    private static Path resolve(List<Path> dirs, String file) throws IOException {
        List<Path> roots = roots(dirs);
        if (file != null && !file.isBlank()) {
            Path given = Path.of(file);
            if (given.isAbsolute()) {
                Path normalized = given.toAbsolutePath().normalize();
                if (Files.isRegularFile(normalized) && inside(roots, normalized)) return normalized;
                throw new CdpException("log file not found: " + file);
            }
            String name = given.getFileName().toString();
            if (!name.equals(given.toString()) || name.contains("..")) {
                throw new CdpException("log file not found: " + file);
            }
            for (Path root : roots) {
                Path candidate = root.resolve(name).normalize();
                if (candidate.startsWith(root) && Files.isRegularFile(candidate)) return candidate;
            }
            throw new CdpException("log file not found: " + file);
        }
        List<Listed> all = listed(roots);
        if (all.isEmpty()) throw new CdpException("log file not found");
        return all.get(0).path;
    }

    private static boolean inside(List<Path> roots, Path file) {
        Path parent = file.getParent();
        if (parent == null) return false;
        for (Path root : roots) {
            if (parent.equals(root)) return true;
        }
        return false;
    }

    private static List<Listed> listed(List<Path> dirs) throws IOException {
        List<Listed> found = new ArrayList<>();
        for (Path root : roots(dirs)) {
            if (!Files.isDirectory(root)) continue;
            try (var stream = Files.list(root)) {
                for (Path path : stream.filter(Files::isRegularFile).toList()) {
                    String name = path.getFileName().toString();
                    if (!name.endsWith(".log")) continue;
                    FileTime modified = Files.getLastModifiedTime(path);
                    found.add(new Listed(path, Files.size(path), modified.toString()));
                }
            }
        }
        found.sort(Comparator.comparing((Listed item) -> item.modified).reversed().thenComparing(item -> item.path.toString()));
        return found;
    }

    private static List<Path> roots(List<Path> dirs) {
        List<Path> roots = new ArrayList<>();
        if (dirs != null) {
            for (Path dir : dirs) {
                if (dir != null) roots.add(dir.toAbsolutePath().normalize());
            }
        }
        if (roots.isEmpty()) roots.add(EngineLog.directory().toAbsolutePath().normalize());
        return roots;
    }

    private record Listed(Path path, long bytes, String modified) {
    }
}
