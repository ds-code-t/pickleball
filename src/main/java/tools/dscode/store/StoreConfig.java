package tools.dscode.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Store store is on unless it is turned off. Precedence: {@code pkb_store}
 * system property, then {@code PKB_STORE}, then {@code store.properties}
 * {@code enabled=} or {@code pkb_store=} when that key is present.
 * The default is on. Accepted values are {@code true|false|yes|no|on|off|1|0}.
 * Anything else fails at first use. A former name stops the run.
 * Files stay the Git source of truth; {@code .pickleball/store/data} is the working copy.
 */
public final class StoreConfig {
    public static final String ACCEPTED = "true|false|yes|no|on|off|1|0";

    private StoreConfig() {
    }

    public static Path project() {
        String override = System.getProperty("pkb_store_project");
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
        LegacyNames.refuse(project);
        String storeProperty = System.getProperty("pkb_store");
        if (storeProperty != null && !storeProperty.isBlank()) {
            return new Decision(parse(storeProperty, "pkb_store"), "property");
        }
        String storeEnv = System.getenv("PKB_STORE");
        if (storeEnv != null && !storeEnv.isBlank()) {
            return new Decision(parse(storeEnv, "PKB_STORE"), "env");
        }
        String storeFile = namedFileValue(project, "store.properties", "pkb_store");
        if (storeFile != null) {
            return new Decision(parse(storeFile, "store.properties"), "file");
        }
        return new Decision(true, "default");
    }

    private static boolean parse(String raw, String key) {
        if (truthy(raw)) return true;
        if (falsy(raw)) return false;
        throw new IllegalArgumentException(key + " must be " + ACCEPTED + " but was '" + raw.trim() + "'");
    }

    /** The {@code enabled} or {@code pkb_store} value, or null when the key is absent. */
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
