package tools.dscode.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.function.Function;

/**
 * {@code <workbench state>/logs/warm-pool.log}. One ISO-8601 line per decision.
 * Default on. One rotated backup at 1 MiB. Never a secret or a RunVar map.
 */
final class WarmPoolLog {
    static final String FILE_NAME = "warm-pool.log";
    static final String PROPERTY = "pkb_workbench_warm_log";
    static final String ENV = "PKB_WORKBENCH_WARM_LOG";
    static final String RUNVAR = "pkb_runvars.pkb_workbench_warm_log";
    static final long MAX_BYTES = 1024L * 1024L;

    private WarmPoolLog() {
    }

    static boolean enabled(Function<String, String> property, Function<String, String> env) {
        Function<String, String> readProperty = property == null ? key -> null : property;
        Function<String, String> readEnv = env == null ? key -> null : env;
        String raw = first(
                readProperty.apply(PROPERTY),
                readEnv.apply(ENV),
                readProperty.apply(RUNVAR),
                compact(readProperty.apply("pkb_runvars"), PROPERTY)
        );
        if (raw == null || raw.isBlank()) return true;
        String token = raw.trim();
        return !("0".equals(token) || "false".equalsIgnoreCase(token) || "off".equalsIgnoreCase(token) || "no".equalsIgnoreCase(token));
    }

    static void append(Path stateRoot, String line) {
        if (stateRoot == null || line == null || line.isBlank()) return;
        if (!enabled(System::getProperty, System::getenv)) return;
        try {
            Path logs = stateRoot.resolve("logs");
            Files.createDirectories(logs);
            Path file = logs.resolve(FILE_NAME);
            rotate(file);
            String text = Instant.now() + " " + line.replace('\n', ' ').replace('\r', ' ') + System.lineSeparator();
            Files.writeString(file, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The launch-timing line is still written.
        }
    }

    private static void rotate(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) < MAX_BYTES) return;
        Path backup = file.resolveSibling(FILE_NAME + ".1");
        Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String compact(String compact, String key) {
        if (compact == null || compact.isBlank() || key == null) return null;
        for (String part : compact.split(",")) {
            String token = part.trim();
            int equals = token.indexOf('=');
            if (equals <= 0) continue;
            if (key.equals(token.substring(0, equals).trim())) return token.substring(equals + 1).trim();
        }
        return null;
    }

    private static String first(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
