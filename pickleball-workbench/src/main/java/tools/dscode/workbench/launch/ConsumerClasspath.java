package tools.dscode.workbench.launch;

import tools.dscode.control.protocol.PickleballLocalLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Consumer classpath the Workbench already resolved for a project.
 * Store and {@code domengine} forwarders launch
 * {@code java -cp <entries> tools.dscode.launcher.PickleballWorkbenchLauncher}.
 * The published jar stays thin. sqlite-jdbc and Jackson are classpath entries,
 * never shaded. The B1 fast launcher must use {@link #entries}.
 */
public final class ConsumerClasspath {
    public static final String MAIN_CLASS = "tools.dscode.launcher.PickleballWorkbenchLauncher";
    static final String CACHE_FILE = "classpath.cache";
    private static final String[] REQUIRED = {
            "sqlite-jdbc", "jackson-databind", "jackson-core", "jackson-annotations"
    };

    private ConsumerClasspath() {
    }

    /** Thin jar, then the resolved consumer list, then any missing driver jars from this JVM. */
    public static List<Path> entries(Path project, Path jar) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (jar != null) unique.add(jar.toAbsolutePath().normalize().toString());
        for (String entry : resolved(project)) addExisting(unique, entry);
        supplement(unique);
        List<Path> paths = new ArrayList<>(unique.size());
        for (String entry : unique) paths.add(Path.of(entry));
        return List.copyOf(paths);
    }

    public static String joined(Path project, Path jar) {
        String separator = java.io.File.pathSeparator;
        StringBuilder classpath = new StringBuilder();
        for (Path entry : entries(project, jar)) {
            if (!classpath.isEmpty()) classpath.append(separator);
            classpath.append(entry.toAbsolutePath().normalize());
        }
        return classpath.toString();
    }

    /**
     * Sync writes {@code classpath.txt}. The one-shot launcher writes
     * {@code classpath.cache} when that file is missing. Either list is the
     * consumer test classpath (pickleball plus runtime libraries).
     */
    static List<String> resolved(Path project) {
        if (project == null) return List.of();
        Path state = PickleballLocalLayout.workbenchStateRoot(project);
        List<String> worker = readLines(state.resolve(PickleballLocalLayout.WORKER_CLASSPATH_FILE));
        if (anyExists(worker)) return worker;
        return readCache(state.resolve(CACHE_FILE));
    }

    private static void supplement(LinkedHashSet<String> unique) {
        String classpath = System.getProperty("java.class.path", "");
        if (classpath == null || classpath.isBlank()) return;
        String[] runtime = classpath.split(java.io.File.pathSeparator, -1);
        for (String token : REQUIRED) {
            if (containsToken(unique, token)) continue;
            for (String entry : runtime) {
                if (entry == null || entry.isBlank()) continue;
                if (!fileName(entry).contains(token)) continue;
                addExisting(unique, entry);
                break;
            }
        }
    }

    private static void addExisting(LinkedHashSet<String> unique, String entry) {
        if (entry == null || entry.isBlank()) return;
        try {
            Path path = Path.of(entry.trim()).toAbsolutePath().normalize();
            if (Files.exists(path)) unique.add(path.toString());
        } catch (RuntimeException ignored) {
            // A bad classpath line is skipped. The exit code still reports the launch.
        }
    }

    private static boolean anyExists(List<String> entries) {
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            try {
                if (Files.exists(Path.of(entry.trim()))) return true;
            } catch (RuntimeException ignored) {
                // The next entry may still be a real path.
            }
        }
        return false;
    }

    private static boolean containsToken(LinkedHashSet<String> unique, String token) {
        for (String entry : unique) {
            if (fileName(entry).contains(token)) return true;
        }
        return false;
    }

    private static String fileName(String entry) {
        try {
            return Path.of(entry).getFileName().toString().toLowerCase(Locale.ROOT);
        } catch (RuntimeException failure) {
            return entry.toLowerCase(Locale.ROOT);
        }
    }

    private static List<String> readLines(Path file) {
        if (file == null || !Files.isRegularFile(file)) return List.of();
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        } catch (IOException failure) {
            return List.of();
        }
    }

    /** One quoted path-separator classpath, the same shape {@code classpath.cache} uses. */
    private static List<String> readCache(Path file) {
        if (file == null || !Files.isRegularFile(file)) return List.of();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) return List.of();
            String classpath = unquote(text.lines().findFirst().orElse(""));
            if (classpath.isBlank()) return List.of();
            List<String> entries = new ArrayList<>();
            for (String entry : classpath.split(java.io.File.pathSeparator, -1)) {
                if (!entry.isBlank()) entries.add(entry);
            }
            return entries;
        } catch (IOException failure) {
            return List.of();
        }
    }

    private static String unquote(String text) {
        String body = text == null ? "" : text.trim();
        if (body.length() < 2 || !body.startsWith("\"") || !body.endsWith("\"")) return body;
        String inner = body.substring(1, body.length() - 1);
        StringBuilder out = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char current = inner.charAt(i);
            if (current == '\\' && i + 1 < inner.length()) {
                out.append(inner.charAt(++i));
            } else {
                out.append(current);
            }
        }
        return out.toString();
    }
}
