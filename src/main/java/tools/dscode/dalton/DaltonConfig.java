package tools.dscode.dalton;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Dolt store is on unless it is turned off. Precedence: {@code pkb_dolt}
 * system property, then {@code PKB_DOLT}, then {@code dolt.properties}
 * {@code enabled=} or {@code pkb_dolt=} when that key is present, then the
 * same checks for {@code pkb_dalton} / {@code PKB_DALTON} / {@code dalton.properties}.
 * The default is on. Accepted values are {@code true|false|yes|no|on|off|1|0}.
 * Anything else fails at first use. Files stay the Git source of truth;
 * {@code .pickleball/dolt/data} is the working copy.
 */
public final class DaltonConfig {
    public static final String ACCEPTED = "true|false|yes|no|on|off|1|0";

    private DaltonConfig() {
    }

    public static Path project() {
        String override = System.getProperty("pkb_dolt_project");
        if (override == null || override.isBlank()) {
            override = System.getProperty("pkb_dalton_project");
        }
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    public static boolean enabled() {
        return enabled(project());
    }

    public static boolean enabled(Path project) {
        return decide(project).enabled();
    }

    /** {@code property}, {@code env}, {@code file}, or {@code default}. */
    public static String source(Path project) {
        return decide(project).source();
    }

    public static boolean truthy(String raw) {
        if (raw == null) return false;
        String value = raw.trim();
        return "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value)
                || "1".equals(value);
    }

    public static boolean falsy(String raw) {
        if (raw == null) return false;
        String value = raw.trim();
        return "false".equalsIgnoreCase(value)
                || "no".equalsIgnoreCase(value)
                || "off".equalsIgnoreCase(value)
                || "0".equals(value);
    }

    private record Decision(boolean enabled, String source) {
    }

    private static Decision decide(Path project) {
        String doltProperty = System.getProperty("pkb_dolt");
        if (doltProperty != null && !doltProperty.isBlank()) {
            return new Decision(parse(doltProperty, "pkb_dolt"), "property");
        }
        String doltEnv = System.getenv("PKB_DOLT");
        if (doltEnv != null && !doltEnv.isBlank()) {
            return new Decision(parse(doltEnv, "PKB_DOLT"), "env");
        }
        String doltFile = namedFileValue(project, "dolt.properties", "pkb_dolt");
        if (doltFile != null) {
            return new Decision(parse(doltFile, "dolt.properties"), "file");
        }
        String property = System.getProperty("pkb_dalton");
        if (property != null && !property.isBlank()) {
            return new Decision(parse(property, "pkb_dalton"), "property");
        }
        String env = System.getenv("PKB_DALTON");
        if (env != null && !env.isBlank()) {
            return new Decision(parse(env, "PKB_DALTON"), "env");
        }
        String file = namedFileValue(project, "dalton.properties", "pkb_dalton");
        if (file != null) {
            return new Decision(parse(file, "dalton.properties"), "file");
        }
        return new Decision(true, "default");
    }

    private static boolean parse(String raw, String key) {
        if (truthy(raw)) return true;
        if (falsy(raw)) return false;
        throw new IllegalArgumentException(key + " must be " + ACCEPTED + " but was '" + raw.trim() + "'");
    }

    /** The {@code enabled}, {@code pkb_dolt}, or {@code pkb_dalton} value, or null when the key is absent. */
    private static String namedFileValue(Path project, String fileName, String keyName) {
        if (project == null) return null;
        Path file = project.resolve(fileName);
        if (!Files.isRegularFile(file)) return null;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int split = trimmed.indexOf('=');
                if (split < 0) continue;
                String key = trimmed.substring(0, split).trim();
                String value = trimmed.substring(split + 1).trim();
                if ("enabled".equalsIgnoreCase(key) || keyName.equalsIgnoreCase(key)) {
                    return value;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }
}
