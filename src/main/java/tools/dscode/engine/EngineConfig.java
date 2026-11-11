package tools.dscode.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Selects the execution engine. Precedence matches Dalton:
 * system property {@code pkb_engine}, else env {@code PKB_ENGINE},
 * else {@code engine.properties} {@code mode=}. Default is {@code cucumber}.
 * The Cucumber adapter stays available either way.
 */
public final class EngineConfig {
    public static final String CUCUMBER = "cucumber";
    public static final String NATIVE = "native";

    private EngineConfig() {
    }

    public static Path project() {
        String override = System.getProperty("pkb_engine_project");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    public static String mode() {
        return mode(project());
    }

    public static String mode(Path project) {
        String flag = flag();
        if (flag != null) return normalize(flag);
        return normalize(fileMode(project));
    }

    public static boolean nativeSelected() {
        return NATIVE.equals(mode());
    }

    public static boolean nativeSelected(Path project) {
        return NATIVE.equals(mode(project));
    }

    private static String normalize(String raw) {
        if (raw == null) return CUCUMBER;
        return NATIVE.equalsIgnoreCase(raw.trim()) ? NATIVE : CUCUMBER;
    }

    private static String flag() {
        String property = System.getProperty("pkb_engine");
        if (property != null && !property.isBlank()) return property;
        String env = System.getenv("PKB_ENGINE");
        if (env != null && !env.isBlank()) return env;
        return null;
    }

    private static String fileMode(Path project) {
        if (project == null) return null;
        Path file = project.resolve("engine.properties");
        if (!Files.isRegularFile(file)) return null;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int split = trimmed.indexOf('=');
                if (split < 0) continue;
                String key = trimmed.substring(0, split).trim();
                String value = trimmed.substring(split + 1).trim();
                if ("mode".equalsIgnoreCase(key) || "engine".equalsIgnoreCase(key) || "pkb_engine".equalsIgnoreCase(key)) {
                    return value;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }
}
