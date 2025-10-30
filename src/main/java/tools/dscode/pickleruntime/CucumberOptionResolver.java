package tools.dscode.pickleruntime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves Cucumber options from multiple sources and can bootstrap a global Runner
 * via io.cucumber.core.runner.RunnerRuntimeRegistry.
 */
public final class CucumberOptionResolver {

    // Final merged results (thread-safe cached)
    private static final Map<String, List<String>> CACHE = new ConcurrentHashMap<>();

    // Annotation FQNs for matching by reflection only
    private static final String JUNIT5_PARAM_FQN =
            "org.junit.platform.suite.api.ConfigurationParameter";
    private static final String CUCUMBER_OPTS_FQN =
            "io.cucumber.junit.CucumberOptions";

    // Keys in cucumber.properties files
    private static final List<String> PROPERTIES_KEYS = List.of(
            "cucumber.glue",
            "cucumber.features",
            "cucumber.plugin",
            "cucumber.filter.tags",
            "cucumber.filter.name"
    );

    private CucumberOptionResolver() {
    }

    /** Public entry — will merge results from all sources */
    public static Map<String, List<String>> getAllOptions() {
        if (CACHE.isEmpty()) {
            Map<String, List<String>> merged = new LinkedHashMap<>();

            loadFromEnvironment(merged);
            loadFromJUnit5SuiteClasses(merged);
            loadFromJUnit4RunnerClasses(merged);
            loadFromPropertiesFiles(merged);

            CACHE.putAll(normalizePaths(merged));
        }
        return Collections.unmodifiableMap(CACHE);
    }

    /**
     * Alias convenience: retrieve merged global cucumber options (normalized).
     */
    public static Map<String, List<String>> globalOptions() {
        return getAllOptions();
    }

    /**
     * Build a global Runner using resolved options and the shared
     * io.cucumber.core.runner.RunnerRuntimeRegistry cache.
     *
     * If no features were discovered, this still creates a Runner; it just won’t
     * discover any pickles until you add feature paths later.
     */
    public static io.cucumber.core.runner.Runner getGlobalRunner() {
        String[] args = toCliArgs(getAllOptions());
        return getGlobalContext(args).runner;
    }

    /**
     * Expose the full RunnerRuntimeContext in case callers want RuntimeOptions/Runtime.
     */
    public static io.cucumber.core.runner.RunnerRuntimeContext getGlobalContext() {
        String[] args = toCliArgs(getAllOptions());
        return getGlobalContext(args);
    }

    // Internal: registry hop
    private static io.cucumber.core.runner.RunnerRuntimeContext getGlobalContext(String[] cliArgs) {
        return io.cucumber.core.runner.RunnerRuntimeRegistry.getOrInit(cliArgs);
    }

    /**
     * Convert resolved options to Cucumber CLI args:
     *   --glue <g> ... --tags <t> ... --plugin <p> ... --name <n> ... <feature1> <feature2> ...
     *
     * Order:
     *  1) glue
     *  2) tags
     *  3) plugin
     *  4) name
     *  5) features (positional)
     */
    public static String[] toCliArgs(Map<String, List<String>> opts) {
        List<String> args = new ArrayList<>();

        for (String g : opts.getOrDefault("cucumber.glue", List.of())) {
            if (g != null && !g.isBlank()) {
                args.add("--glue");
                args.add(g.trim());
            }
        }
        for (String t : opts.getOrDefault("cucumber.filter.tags", List.of())) {
            if (t != null && !t.isBlank()) {
                args.add("--tags");
                args.add(t.trim());
            }
        }
        for (String p : opts.getOrDefault("cucumber.plugin", List.of())) {
            if (p != null && !p.isBlank()) {
                args.add("--plugin");
                args.add(p.trim());
            }
        }
        for (String n : opts.getOrDefault("cucumber.filter.name", List.of())) {
            if (n != null && !n.isBlank()) {
                args.add("--name");
                args.add(n.trim());
            }
        }
        // Features last (positional)
        for (String f : opts.getOrDefault("cucumber.features", List.of())) {
            if (f != null && !f.isBlank()) {
                args.add(f.trim());
            }
        }

        return args.toArray(new String[0]);
    }

    /* ---------------- ENV + SYSTEM ---------------- */

    private static void loadFromEnvironment(Map<String, List<String>> result) {
        for (String key : PROPERTIES_KEYS) {
            String sys = System.getProperty(key);
            if (sys != null) {
                putSplit(result, key, sys);
            }
            String env = System.getenv(toEnvKey(key));
            if (env != null) {
                putSplit(result, key, env);
            }
        }
    }

    private static String toEnvKey(String propKey) {
        return propKey.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    /* ---------------- JUNIT 5 SUITE ---------------- */

    private static void loadFromJUnit5SuiteClasses(Map<String, List<String>> result) {
        for (Class<?> clazz : findClassesAnnotatedWithFQN(JUNIT5_PARAM_FQN)) {
            for (Annotation ann : clazz.getAnnotations()) {
                if (!ann.annotationType().getName().equals(JUNIT5_PARAM_FQN)) continue;
                extractJUnit5ConfigurationParameter(ann, result);
            }
        }
    }

    private static void extractJUnit5ConfigurationParameter(
            Annotation ann, Map<String, List<String>> result) {
        try {
            Method k = ann.annotationType().getMethod("key");
            Method v = ann.annotationType().getMethod("value");
            String key = (String) k.invoke(ann);
            String value = (String) v.invoke(ann);
            if (key != null && value != null) putSplit(result, key, value);
        } catch (Exception ignored) {
        }
    }

    /* ---------------- JUNIT 4 RUNNER ---------------- */

    private static void loadFromJUnit4RunnerClasses(Map<String, List<String>> result) {
        for (Class<?> clazz : findClassesAnnotatedWithFQN(CUCUMBER_OPTS_FQN)) {
            for (Annotation ann : clazz.getAnnotations()) {
                if (!ann.annotationType().getName().equals(CUCUMBER_OPTS_FQN)) continue;
                extractJUnit4CucumberOptions(ann, result);
            }
        }
    }

    private static void extractJUnit4CucumberOptions(Annotation ann, Map<String, List<String>> result) {
        reflectArrayField(ann, "glue", "cucumber.glue", result);
        reflectArrayField(ann, "features", "cucumber.features", result);
        reflectArrayField(ann, "plugin", "cucumber.plugin", result);
        reflectArrayField(ann, "tags", "cucumber.filter.tags", result);
        try {
            Method m = ann.annotationType().getMethod("name");
            String v = (String) m.invoke(ann);
            if (v != null && !v.isBlank()) put(result, "cucumber.filter.name", v);
        } catch (Exception ignored) {
        }
    }

    private static void reflectArrayField(
            Annotation ann, String methodName, String optKey,
            Map<String, List<String>> result) {
        try {
            Object arr = ann.annotationType().getMethod(methodName).invoke(ann);
            if (arr instanceof String[] strings) {
                for (String s : strings) {
                    if (!s.isBlank()) put(result, optKey, s.trim());
                }
            }
        } catch (Exception ignored) {
        }
    }

    /* ---------------- .PROPERTIES FILES ---------------- */

    private static void loadFromPropertiesFiles(Map<String, List<String>> result) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        List<String> propFiles = List.of(
                "cucumber.properties",
                "junit-platform.properties"
        );

        for (String fileName : propFiles) {
            try {
                Enumeration<URL> resources = cl.getResources(fileName);
                while (resources.hasMoreElements()) {
                    loadPropertiesFromUrl(resources.nextElement(), result);
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static void loadPropertiesFromUrl(URL url, Map<String, List<String>> result) {
        try (InputStream is = url.openStream()) {
            Properties props = new Properties();
            props.load(is);
            for (String key : PROPERTIES_KEYS) {
                String v = props.getProperty(key);
                if (v != null) putSplit(result, key, v);
            }
        } catch (Exception ignored) {
        }
    }

    /* ---------------- CLASS SCANNING SUPPORT ---------------- */

    private static List<Class<?>> findClassesAnnotatedWithFQN(String annFqn) {
        List<Class<?>> found = new ArrayList<>();
        for (Class<?> clazz : allLoadedClasses()) {
            for (Annotation ann : clazz.getAnnotations()) {
                if (ann.annotationType().getName().equals(annFqn)) {
                    found.add(clazz);
                    break;
                }
            }
        }
        return found;
    }

    private static List<Class<?>> allLoadedClasses() {
        // Simple best-effort — replace with ClassGraph for stronger results later.
        List<Class<?>> res = new ArrayList<>();
        try {
            res.addAll(List.of(Class.forName("io.cucumber.junit.CucumberOptions")));
        } catch (Throwable ignored) {
        }
        return res;
    }

    /* ---------------- NORMALIZATION + MERGE ---------------- */

    private static void putSplit(Map<String, List<String>> map, String key, String val) {
        Arrays.stream(val.split("[,;]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .forEach(v -> put(map, key, v));
    }

    private static void put(Map<String, List<String>> map, String key, String val) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
    }

    private static Map<String, List<String>> normalizePaths(Map<String, List<String>> in) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            List<String> cleaned = v.stream()
                    .map(s -> s.replaceFirst("^classpath:/*", "classpath:"))
                    .distinct()
                    .toList();
            out.put(k, new ArrayList<>(cleaned));
        });
        return out;
    }

    /* -------- Public convenience helpers -------- */

    public static List<String> glue() {
        return getAllOptions().getOrDefault("cucumber.glue", List.of());
    }

    public static List<String> features() {
        return getAllOptions().getOrDefault("cucumber.features", List.of());
    }

    public static List<String> tags() {
        return getAllOptions().getOrDefault("cucumber.filter.tags", List.of());
    }

    public static List<String> plugins() {
        return getAllOptions().getOrDefault("cucumber.plugin", List.of());
    }
}
