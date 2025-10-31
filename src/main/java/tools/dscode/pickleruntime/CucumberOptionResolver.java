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
 *
 * Sources (merge order):
 *   1) System properties / Environment variables
 *   2) JUnit 5 @Suite classes and related annotations (via ClassGraph if available)
 *   3) JUnit 4 @io.cucumber.junit.CucumberOptions (best-effort; via reflection if present)
 *   4) cucumber.properties / junit-platform.properties on the classpath
 */
public final class CucumberOptionResolver {

    // Final merged results (thread-safe cached)
    private static final Map<String, List<String>> CACHE = new ConcurrentHashMap<>();

    // JUnit 5 Suite + annotations (FQNs so we don't require them on classpath to compile)
    private static final String JUNIT5_SUITE_FQN = "org.junit.platform.suite.api.Suite";
    private static final String JUNIT5_PARAM_FQN = "org.junit.platform.suite.api.ConfigurationParameter";
    private static final String JUNIT5_SEL_CP_RES_FQN = "org.junit.platform.suite.api.SelectClasspathResource";
    private static final String JUNIT5_SEL_CP_RESS_FQN = "org.junit.platform.suite.api.SelectClasspathResources";
    private static final String JUNIT5_SEL_PKG_FQN = "org.junit.platform.suite.api.SelectPackages";
    private static final String JUNIT5_SEL_PKGS_FQN = "org.junit.platform.suite.api.SelectPackages"; // alias retained

    // JUnit 4 CucumberOptions
    private static final String CUCUMBER_OPTS_FQN = "io.cucumber.junit.CucumberOptions";

    // Keys in cucumber.properties / junit-platform.properties
    private static final List<String> PROPERTIES_KEYS = List.of(
            "cucumber.glue",
            "cucumber.features",
            "cucumber.plugin",
            "cucumber.filter.tags",
            "cucumber.filter.name"
    );

    private CucumberOptionResolver() {}

    /** Public entry — will merge results from all sources */
    public static Map<String, List<String>> getAllOptions() {
        if (CACHE.isEmpty()) {
            Map<String, List<String>> merged = new LinkedHashMap<>();

            System.out.println("@@CucumberOptionResolver: resolving global options…");

            loadFromEnvironment(merged);
            loadFromJUnit5SuiteAnnotations(merged);
            loadFromJUnit4RunnerClasses(merged);
            loadFromPropertiesFiles(merged);

            Map<String, List<String>> normalized = normalizePaths(merged);

            boolean empty = normalized.isEmpty() || normalized.values().stream().allMatch(List::isEmpty);
            if (empty) {
                throw new IllegalStateException(
                        "CucumberOptionResolver: No global Cucumber options were discovered.\n" +
                                "Searched (in precedence):\n" +
                                "  • System properties / Environment variables (cucumber.*)\n" +
                                "  • JUnit 5 @Suite classes: @ConfigurationParameter, @SelectClasspathResource(s), @SelectPackages\n" +
                                "  • JUnit 4 @io.cucumber.junit.CucumberOptions\n" +
                                "  • Classpath properties: cucumber.properties, junit-platform.properties\n" +
                                "Ensure one of these is present on the consuming project's classpath."
                );
            }

            // ✅ Inject default glue path if missing or empty
            normalized.compute("cucumber.glue", (k, v) -> {
                List<String> list = (v == null) ? new ArrayList<>() : new ArrayList<>(v);
                if (list.stream().noneMatch(s -> s.equals("tools.dscode.coredefinitions"))) {
                    list.add("tools.dscode.coredefinitions");
                    System.out.println("@@CucumberOptionResolver: Added default glue path 'tools.dscode.coredefinitions'");
                }
                return list;
            });

            CACHE.putAll(normalized);

            // Log once
            try {
                String[] cli = toCliArgs(CACHE);
                System.out.println("@@CucumberOptionResolver.globalOptions = " + normalized);
                System.out.println("@@CucumberOptionResolver.globalArgs    = " + java.util.Arrays.toString(cli));
            } catch (Throwable ignore) { /* best-effort */ }
        }

        return Collections.unmodifiableMap(CACHE);
    }


    /** Alias convenience: retrieve merged global cucumber options (normalized). */
    public static Map<String, List<String>> globalOptions() {
        return getAllOptions();
    }

    /** Build or reuse a global Runner using resolved options via RunnerRuntimeRegistry. */
    public static io.cucumber.core.runner.Runner getGlobalRunner() {
        String[] args = toCliArgs(getAllOptions());
        return getGlobalContext(args).runner;
    }

    /** Expose the full RunnerRuntimeContext (Runner + RuntimeOptions + Runtime). */
    public static io.cucumber.core.runner.RunnerRuntimeContext getGlobalContext() {
        String[] args = toCliArgs(getAllOptions());
        System.out.println("@@args== " + Arrays.toString(args));
        return getGlobalContext(args);
    }

    // Internal: registry hop
    private static io.cucumber.core.runner.RunnerRuntimeContext getGlobalContext(String[] cliArgs) {
        return io.cucumber.core.runner.RunnerRuntimeRegistry.getOrInit(cliArgs);
    }

    /**
     * Convert resolved options to Cucumber CLI args:
     *   --glue <g> ... --tags <t> ... --plugin <p> ... --name <n> ... <feature1> <feature2> ...
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

    /* ============================ Sources ============================ */

    /* -------- ENV + SYSTEM -------- */
    private static void loadFromEnvironment(Map<String, List<String>> result) {
        for (String key : PROPERTIES_KEYS) {
            String sys = System.getProperty(key);
            if (sys != null) putSplit(result, key, sys);

            String env = System.getenv(toEnvKey(key));
            if (env != null) putSplit(result, key, env);
        }
    }

    private static String toEnvKey(String propKey) {
        return propKey.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    /* -------- JUNIT 5 SUITE + Annotations (ClassGraph) -------- */
    private static void loadFromJUnit5SuiteAnnotations(Map<String, List<String>> result) {
        if (!isClassGraphAvailable()) {
            System.out.println("@@CucumberOptionResolver: ClassGraph not on classpath; skipping JUnit5 suite scan.");
            return;
        }
        try {
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            Class<?> cgClass = Class.forName("io.github.classgraph.ClassGraph");
            Object cg = cgClass.getConstructor().newInstance();

            Object cg2 = cg.getClass().getMethod("enableClassInfo").invoke(cg);
            cg2 = cg2.getClass().getMethod("enableAnnotationInfo").invoke(cg2);
            cg2 = cg2.getClass().getMethod("overrideClassLoaders", ClassLoader[].class)
                    .invoke(cg2, new Object[]{ new ClassLoader[]{ tccl } });

            Object scanResult = cg2.getClass().getMethod("scan").invoke(cg2);

            Collection<Class<?>> suiteRelated = new ArrayList<>();
            suiteRelated.addAll(classGraphGetClassesWithAnnotation(scanResult, JUNIT5_SUITE_FQN));
            suiteRelated.addAll(classGraphGetClassesWithAnnotation(scanResult, JUNIT5_PARAM_FQN));
            suiteRelated.addAll(classGraphGetClassesWithAnnotation(scanResult, JUNIT5_SEL_CP_RES_FQN));
            suiteRelated.addAll(classGraphGetClassesWithAnnotation(scanResult, JUNIT5_SEL_CP_RESS_FQN));
            suiteRelated.addAll(classGraphGetClassesWithAnnotation(scanResult, JUNIT5_SEL_PKG_FQN));

            System.out.println("@@CucumberOptionResolver: JUnit5 suite-related classes discovered = " + suiteRelated.size());

            for (Class<?> clazz : suiteRelated) {
                // 1) key/value configuration parameters -> glue/tags/name/etc
                for (Annotation ann : clazz.getAnnotations()) {
                    if (ann.annotationType().getName().equals(JUNIT5_PARAM_FQN)) {
                        extractJUnit5ConfigurationParameter(ann, result);
                    }
                }
                // 2) resource/package selectors -> features
                extractJUnit5SelectClasspathResources(clazz, result);
                extractJUnit5SelectPackages(clazz, result);
            }

            // close scan
            scanResult.getClass().getMethod("close").invoke(scanResult);
        } catch (Throwable t) {
            System.out.println("@@CucumberOptionResolver: JUnit5 suite scan failed: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<Class<?>> classGraphGetClassesWithAnnotation(Object scanResult, String annotationFqn)
            throws Exception {
        // scanResult.getClassesWithAnnotation(fqn) -> ClassInfoList
        Object classInfoList = scanResult.getClass()
                .getMethod("getClassesWithAnnotation", String.class)
                .invoke(scanResult, annotationFqn);
        // classInfoList.loadClasses() -> List<Class<?>>
        Object list = classInfoList.getClass().getMethod("loadClasses").invoke(classInfoList);
        return (Collection<Class<?>>) list;
    }

    private static boolean isClassGraphAvailable() {
        try {
            Class.forName("io.github.classgraph.ClassGraph");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void extractJUnit5ConfigurationParameter(Annotation ann, Map<String, List<String>> result) {
        try {
            Method k = ann.annotationType().getMethod("key");
            Method v = ann.annotationType().getMethod("value");
            String key = (String) k.invoke(ann);
            String value = (String) v.invoke(ann);
            if (key != null && value != null) putSplit(result, key, value);
        } catch (Exception ignored) {
        }
    }

    // Map @SelectClasspathResource("features") and @SelectClasspathResources({@SelectClasspathResource("features"), ...})
    private static void extractJUnit5SelectClasspathResources(Class<?> clazz, Map<String, List<String>> result) {
        for (Annotation ann : clazz.getAnnotations()) {
            String annName = ann.annotationType().getName();
            try {
                if (annName.equals(JUNIT5_SEL_CP_RES_FQN)) {
                    String val = (String) ann.annotationType().getMethod("value").invoke(ann);
                    addFeatureRoot(result, val);
                } else if (annName.equals(JUNIT5_SEL_CP_RESS_FQN)) {
                    // value() returns an array of @SelectClasspathResource
                    Object arr = ann.annotationType().getMethod("value").invoke(ann);
                    if (arr instanceof Object[] nested) {
                        for (Object nestedAnn : nested) {
                            if (nestedAnn != null &&
                                    nestedAnn.getClass().getName().equals(JUNIT5_SEL_CP_RES_FQN)) {
                                String val = (String) nestedAnn.getClass().getMethod("value").invoke(nestedAnn);
                                addFeatureRoot(result, val);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }
    private static void addFeatureRoot(Map<String, List<String>> result, String val) {
        if (val == null || val.isBlank()) return;
        String s = val.trim();
        if (!s.startsWith("classpath:")) s = "classpath:" + s.replaceAll("^/*", "");
        put(result, "cucumber.features", s);
    }

    private static String toClasspathUrlFromPackage(String pkg) {
        String path = pkg.replace('.', '/');
        if (!path.startsWith("classpath:")) path = "classpath:" + path;
        return path;
    }
    // Map @SelectPackages("x.y", "a.b") -> cucumber.features = ["classpath:x/y", "classpath:a/b"]
    private static void extractJUnit5SelectPackages(Class<?> clazz, Map<String, List<String>> result) {
        for (Annotation ann : clazz.getAnnotations()) {
            if (!ann.annotationType().getName().equals(JUNIT5_SEL_PKG_FQN)) continue;
            try {
                String[] pkgs = (String[]) ann.annotationType().getMethod("value").invoke(ann);
                if (pkgs != null) {
                    for (String p : pkgs) {
                        if (p != null && !p.isBlank()) {
                            String asFeatureRoot = toClasspathUrlFromPackage(p.trim());
                            put(result, "cucumber.features", asFeatureRoot);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private static void addFeaturePath(Map<String, List<String>> result, String path) {
        if (path == null || path.isBlank()) return;
        String normalized = path.startsWith("classpath:") ? path : "classpath:" + stripLeadingSlashes(path);
        put(result, "cucumber.features", normalized);
    }

    private static void addFeaturePackage(Map<String, List<String>> result, String pkg) {
        if (pkg == null || pkg.isBlank()) return;
        // JUnit @SelectPackages uses Java package names; translate to resource path
        String resource = pkg.replace('.', '/');
        addFeaturePath(result, resource);
    }

    private static String stripLeadingSlashes(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '/' || s.charAt(i) == '\\')) i++;
        return s.substring(i);
    }

    /* -------- JUNIT 4 CucumberOptions (best-effort) -------- */
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

    /* -------- .PROPERTIES FILES -------- */
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

    /* -------- Class scanning fallback (very weak, kept for compatibility) -------- */
    private static List<Class<?>> findClassesAnnotatedWithFQN(String annFqn) {
        // Without a scanner, we can't find user classes reliably.
        // Keep a tiny best-effort fallback: if the annotation type itself is present,
        // just return empty (so we don't throw).
        try {
            Class.forName(annFqn);
        } catch (Throwable ignored) {
        }
        return Collections.emptyList();
    }

    /* ============================ Merge helpers ============================ */

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

    /* -------- Public convenience getters -------- */

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
