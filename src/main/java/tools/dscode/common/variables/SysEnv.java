package tools.dscode.common.variables;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

public final class SysEnv {

    private SysEnv() {
    }


    public static final String pickleBallVarPrefix = "pickleball.";
    public static final String bambooVarPrefix     = "bamboo_";
    public static final String githubVarPrefix     = "GITHUB_";

    public static String putPickleBallProperty(String key, String value) {
        if (key == null || value == null) return null;

        String finalKey = key.toLowerCase().trim().startsWith(pickleBallVarPrefix)
                ? key.trim()
                : pickleBallVarPrefix + key.trim();

        return System.setProperty(finalKey, value);
    }


    public static String getPickleBallProperty(String... keys) {
        String[] newKeys = Arrays.stream(keys)
                .filter(Objects::nonNull)
                .map(k -> k.toLowerCase().trim().startsWith(pickleBallVarPrefix)
                        ? k
                        : pickleBallVarPrefix + k)
                .toArray(String[]::new);
        return systemOrEnv(newKeys);
    }

    public static String getBambooProperty(String... keys) {
        String[] newKeys = Arrays.stream(keys)
                .filter(Objects::nonNull)
                .map(k -> k.toLowerCase().trim().startsWith(bambooVarPrefix)
                        ? k
                        : bambooVarPrefix + k)
                .toArray(String[]::new);
        return systemOrEnv(newKeys);
    }

    public static String getGitHubProperty(String... keys) {
        String[] newKeys = Arrays.stream(keys)
                .filter(Objects::nonNull)
                .map(k -> k.toUpperCase().trim().startsWith(githubVarPrefix)
                        ? k
                        : githubVarPrefix + k)
                .toArray(String[]::new);
        return systemOrEnv(newKeys);
    }


    public static String system(String... keys) {
        if (keys == null || keys.length == 0) return null;
        for (String k : keys) {
            if (k == null) continue;
            String v = systemOne(k);
            if (isPresent(v)) return v;
        }
        return null;
    }

    // ---- environment variables ----

    public static String env(String... keys) {
        if (keys == null || keys.length == 0) return null;
        for (String k : keys) {
            if (k == null) continue;
            String v = envOne(k);
            if (isPresent(v)) return v;
        }
        return null;
    }

    // ---- system first, then env ----

    public static String systemOrEnv(String... keys) {
        if (keys == null || keys.length == 0) return null;

        for (String k : keys) {
            if (k == null) continue;
            String v = systemOne(k);
            if (isPresent(v)) return v;
        }
        for (String k : keys) {
            if (k == null) continue;
            String v = envOne(k);
            if (isPresent(v)) return v;
        }
        return null;
    }


    // ---- internals ----

    private static String systemOne(String key) {
        String v = System.getProperty(key);
        if (isPresent(v)) return v;

        for (Map.Entry<Object, Object> e : System.getProperties().entrySet()) {
            if (e.getKey() instanceof String s && s.trim().equalsIgnoreCase(key.trim())) {
                String val = (String) e.getValue();
                if (isPresent(val)) return val;
            }
        }
        return null;
    }

    private static String envOne(String key) {
        String v = System.getenv(key);
        if (isPresent(v)) return v;

        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getKey().trim().equalsIgnoreCase(key.trim())) {
                String val = e.getValue();
                if (isPresent(val)) return val;
            }
        }
        return null;
    }

    private static boolean isPresent(String v) {
        return v != null && !v.isBlank();
    }
}
