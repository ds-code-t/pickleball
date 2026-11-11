package tools.dscode.dalton;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dalton is off unless {@code pkb_dalton} / {@code PKB_DALTON} is true or
 * the project root has {@code dalton.properties} with {@code enabled=true}.
 * An explicit false beats the file.
 */
public final class DaltonConfig {
    private DaltonConfig() {
    }

    public static Path project() {
        String override = System.getProperty("pkb_dalton_project");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    public static boolean enabled() {
        return enabled(project());
    }

    public static boolean enabled(Path project) {
        String flag = flag();
        if (flag != null) return truthy(flag);
        return fileEnabled(project);
    }

    public static boolean truthy(String raw) {
        if (raw == null) return false;
        String value = raw.trim();
        return "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value)
                || "1".equals(value);
    }

    private static String flag() {
        String property = System.getProperty("pkb_dalton");
        if (property != null && !property.isBlank()) return property;
        String env = System.getenv("PKB_DALTON");
        if (env != null && !env.isBlank()) return env;
        return null;
    }

    private static boolean fileEnabled(Path project) {
        if (project == null) return false;
        Path file = project.resolve("dalton.properties");
        if (!Files.isRegularFile(file)) return false;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int split = trimmed.indexOf('=');
                if (split < 0) continue;
                String key = trimmed.substring(0, split).trim();
                String value = trimmed.substring(split + 1).trim();
                if ("enabled".equalsIgnoreCase(key) || "pkb_dalton".equalsIgnoreCase(key)) {
                    return truthy(value);
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }
}
