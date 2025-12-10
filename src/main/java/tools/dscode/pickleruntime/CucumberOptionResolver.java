package tools.dscode.pickleruntime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static tools.dscode.common.util.DebugUtils.printDebug;

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

    // ===== DEBUG scaffold =====
    private static final boolean DEBUG = false;
    private static void dbg(String fmt, Object... args) {
        if (!DEBUG) return;
        String s = (args == null || args.length == 0) ? fmt : String.format(fmt, args);
        System.out.println("[CucumberOptionResolver][DBG] " + s);
    }
    private static String loaderName(ClassLoader cl) {
        if (cl == null) return "null";
        return cl.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(cl));
    }
    private static String where(Class<?> c) {
        try {
            var cs = c.getProtectionDomain().getCodeSource();
            return cs == null ? "<unknown>" : String.valueOf(cs.getLocation());
        } catch (Throwable ignore) { return "<n/a>"; }
    }
    private static void snap(String label, Map<String,List<String>> m) {
        if (!DEBUG) return;
        dbg("%s glue=%s", label, m.getOrDefault("cucumber.glue", List.of()));
        dbg("%s feats=%s", label, m.getOrDefault("cucumber.features", List.of()));
        dbg("%s tags=%s", label, m.getOrDefault("cucumber.filter.tags", List.of()));
        dbg("%s plug=%s", label, m.getOrDefault("cucumber.plugin", List.of()));
        dbg("%s name=%s", label, m.getOrDefault("cucumber.filter.name", List.of()));
    }
    private static Class<?> tryLoadClassGraph() {
        for (String fqn : List.of(
                "tools.dscode.thirdparty.classgraph.ClassGraph", // in case of shading/relocation
                "io.github.classgraph.ClassGraph"
        )) {
            try { return Class.forName(fqn, false, CucumberOptionResolver.class.getClassLoader()); }
            catch (Throwable ignored) {}
        }
        return null;
    }
    private static String[] currentClasspathEntries() {
        String cp = System.getProperty("java.class.path", "");
        if (cp.isEmpty()) return new String[0];
        return cp.split(File.pathSeparator);
    }
    // ================================================================

    private static final Map<String, List<String>> CACHE = new ConcurrentHashMap<>();

    // JUnit 5 Suite + annotations
    private static final String JUNIT5_SUITE_FQN       = "org.junit.platform.suite.api.Suite";
    private static final String JUNIT5_PARAM_FQN       = "org.junit.platform.suite.api.ConfigurationParameter";
    private static final String JUNIT5_SEL_CP_RES_FQN  = "org.junit.platform.suite.api.SelectClasspathResource";
    private static final String JUNIT5_SEL_CP_RESS_FQN = "org.junit.platform.suite.api.SelectClasspathResources";
    private static final String JUNIT5_SEL_PKG_FQN     = "org.junit.platform.suite.api.SelectPackages";
    private static final String JUNIT5_SEL_PKGS_FQN    = "org.junit.platform.suite.api.SelectPackages";

    // JUnit 4 CucumberOptions
    private static final String CUCUMBER_OPTS_FQN = "io.cucumber.junit.CucumberOptions";

    // Properties keys
    private static final List<String> PROPERTIES_KEYS = List.of(
            "cucumber.glue",
            "cucumber.features",
            "cucumber.plugin",
            "cucumber.filter.tags",
            "cucumber.filter.name"
    );

    private CucumberOptionResolver() {}

    public static Map<String, List<String>> getAllOptions() {
        if (CACHE.isEmpty()) {
            Map<String, List<String>> merged = new LinkedHashMap<>();


            var tccl = Thread.currentThread().getContextClassLoader();
            dbg("TCCL         = %s", loaderName(tccl));
            dbg("Self loader  = %s", loaderName(CucumberOptionResolver.class.getClassLoader()));
            dbg("Sys loader   = %s", loaderName(ClassLoader.getSystemClassLoader()));
            dbg("java.class.path = %s", System.getProperty("java.class.path", "<unset>"));
            String mp = System.getProperty("jdk.module.path");
            if (mp != null) dbg("jdk.module.path = %s", mp);
            Class<?> cg = tryLoadClassGraph();
            if (cg != null) dbg("ClassGraph present: %s @ %s", cg.getName(), where(cg));
            else dbg("ClassGraph NOT present on known loaders.");

            loadFromEnvironment(merged);            snap("after ENV", merged);
            loadFromJUnit5SuiteAnnotations(merged); snap("after JUnit5", merged);
            loadFromJUnit4RunnerClasses(merged);    snap("after JUnit4", merged);
            loadFromPropertiesFiles(merged);        snap("after PROPS", merged);

            Map<String, List<String>> normalized = normalizePaths(merged);
            snap("after NORMALIZE", normalized);

            boolean empty = normalized.isEmpty() || normalized.values().stream().allMatch(List::isEmpty);
            if (empty) {
                System.out.println(
                        "@@CucumberOptionResolver: No options discovered.\n" +
                                "  Hints:\n" +
                                "   • Ensure your consumer has a @Suite class with @ConfigurationParameter(GLUE_PROPERTY_NAME, \"...\")\n" +
                                "   • Or set cucumber.glue in junit-platform.properties (in test resources)\n" +
                                "   • Or pass -Dcucumber.glue=... on the build/test command\n" +
                                "   • If using JPMS, confirm test classes are on the runtime module/class path"
                );
                throw new IllegalStateException("CucumberOptionResolver: No global Cucumber options were discovered.");
            }

            // Inject default glue if missing
            normalized.compute("cucumber.glue", (k, v) -> {
                List<String> list = (v == null) ? new ArrayList<>() : new ArrayList<>(v);
                if (list.stream().noneMatch("tools.dscode.coredefinitions"::equals)) {
                    list.add("tools.dscode.coredefinitions");
                }
                return list;
            });

            CACHE.putAll(normalized);

            try {
                String[] cli = toCliArgs(CACHE);
            } catch (Throwable ignore) { /* best-effort */ }
        }
        return Collections.unmodifiableMap(CACHE);
    }

    public static Map<String, List<String>> globalOptions() { return getAllOptions(); }

    public static String[] toCliArgs(Map<String, List<String>> opts) {
        List<String> args = new ArrayList<>();
        for (String g : opts.getOrDefault("cucumber.glue", List.of())) {
            if (g != null && !g.isBlank()) { args.add("--glue"); args.add(g.trim()); }
        }
        for (String t : opts.getOrDefault("cucumber.filter.tags", List.of())) {
            if (t != null && !t.isBlank()) { args.add("--tags"); args.add(t.trim()); }
        }
        for (String p : opts.getOrDefault("cucumber.plugin", List.of())) {
            if (p != null && !p.isBlank()) { args.add("--plugin"); args.add(p.trim()); }
        }
        for (String n : opts.getOrDefault("cucumber.filter.name", List.of())) {
            if (n != null && !n.isBlank()) { args.add("--name"); args.add(n.trim()); }
        }
        for (String f : opts.getOrDefault("cucumber.features", List.of())) {
            if (f != null && !f.isBlank()) { args.add(f.trim()); }
        }
        return args.toArray(new String[0]);
    }

    /* ============================ Sources ============================ */

    private static void loadFromEnvironment(Map<String, List<String>> result) {
        dbg("ENV: scanning system properties & env vars");
        for (String key : PROPERTIES_KEYS) {
            String sys = System.getProperty(key);
            if (sys != null) { dbg("ENV: sysprop %s = %s", key, sys); putSplit(result, key, sys); }
            String env = System.getenv(toEnvKey(key));
            if (env != null) { dbg("ENV: env %s = %s", toEnvKey(key), env); putSplit(result, key, env); }
        }
    }
    private static String toEnvKey(String propKey) { return propKey.toUpperCase(Locale.ROOT).replace('.', '_'); }

    private static void loadFromJUnit5SuiteAnnotations(Map<String, List<String>> result) {
        try {
            Class<?> cgClass = tryLoadClassGraph();
            if (cgClass == null) {
                return;
            }
            dbg("JUnit5 scan using ClassGraph %s @ %s", cgClass.getName(), where(cgClass));

            // PASS A: no override (normal runtime cp)
            int foundA = scanJunit5(cgClass, result, null, null);

            // PASS B: fallback with loader override
            if (foundA == 0) {
                ClassLoader[] loaders = new ClassLoader[]{ Thread.currentThread().getContextClassLoader() };
                dbg("JUnit5 scan (fallback) loaders = %s", Arrays.toString(Arrays.stream(loaders).map(CucumberOptionResolver::loaderName).toArray()));
                int foundB = scanJunit5(cgClass, result, loaders, null);

                // PASS C: FINAL fallback — probe conventional test output dirs and override CLASSPATH explicitly
                if (foundB == 0) {
                    List<String> extra = guessConventionalTestOutputPaths();
                    if (!extra.isEmpty()) {
                        // Build an override classpath = current entries + extra dirs
                        List<String> cp = new ArrayList<>(List.of(currentClasspathEntries()));
                        cp.addAll(extra);
                        dbg("JUnit5 scan (overrideClasspath) adding %d test output dirs:", extra.size());
                        for (String e : extra) dbg("  + %s", e);
                        int foundC = scanJunit5(cgClass, result, null, String.join(File.pathSeparator, cp));
                        if (foundC == 0) {
                            dbg("JUnit5 scan: still 0 classes after overrideClasspath.");
                            dbg("Checklist: Is your @Suite class compiled under test scope and located in the probed dirs?");
                        }
                    } else {
                        dbg("JUnit5 scan: no conventional test output dirs detected near user.dir=%s", System.getProperty("user.dir"));
                    }
                }
            }
        } catch (Throwable t) {

            if (DEBUG) t.printStackTrace(System.out);
        }
    }

    @SuppressWarnings("unchecked")
    private static int scanJunit5(Class<?> cgClass, Map<String, List<String>> result,
                                  ClassLoader[] loadersOrNull, String overrideClasspathOrNull) throws Exception {
        Object cg = cgClass.getConstructor().newInstance();

        Object spec = cg.getClass().getMethod("enableClassInfo").invoke(cg);
        spec = spec.getClass().getMethod("enableAnnotationInfo").invoke(spec);
        try { spec = spec.getClass().getMethod("ignoreClassVisibility").invoke(spec); } catch (Throwable ignored) {}
        try { spec = spec.getClass().getMethod("enableSystemJarsAndModules").invoke(spec); } catch (Throwable ignored) {}

        if (loadersOrNull != null) {
            spec = spec.getClass().getMethod("overrideClassLoaders", ClassLoader[].class)
                    .invoke(spec, new Object[]{ loadersOrNull });
        }
        if (overrideClasspathOrNull != null) {
            spec = spec.getClass().getMethod("overrideClasspath", String.class)
                    .invoke(spec, overrideClasspathOrNull);
        }

        Object scanResult = spec.getClass().getMethod("scan").invoke(spec);

        Collection<Class<?>> suiteRelated = new ArrayList<>();
        suiteRelated.addAll(classGraphGetClassesWithAnnotation(scanResult, JUNIT5_SUITE_FQN));
        suiteRelated.addAll(classGraphGetClassesWithAnnotation(scanResult, JUNIT5_PARAM_FQN));
        suiteRelated.addAll(classGraphGetClassesWithAnnotation(scanResult, JUNIT5_SEL_CP_RES_FQN));
        suiteRelated.addAll(classGraphGetClassesWithAnnotation(scanResult, JUNIT5_SEL_CP_RESS_FQN));
        suiteRelated.addAll(classGraphGetClassesWithAnnotation(scanResult, JUNIT5_SEL_PKG_FQN));

        String mode = (overrideClasspathOrNull != null) ? " (overrideClasspath)"
                : (loadersOrNull != null ? " (override)" : "");
        dbg("JUnit5 scan%s: discovered %d annotated classes", mode, suiteRelated.size());

        int i = 0;
        for (Class<?> c : suiteRelated) {
            dbg("  #%d %s (loader=%s, loc=%s)", ++i, c.getName(), loaderName(c.getClassLoader()), where(c));
            if (i >= 50) { dbg("  ... (truncated)"); break; }
        }

        for (Class<?> clazz : suiteRelated) {
            // ✅ Repeatable-safe extraction of @ConfigurationParameter(s)
            extractJUnit5ConfigurationParameters(clazz, result);

            // Selectors -> features
            extractJUnit5SelectClasspathResources(clazz, result);
            extractJUnit5SelectPackages(clazz, result);
        }

        try { scanResult.getClass().getMethod("close").invoke(scanResult); } catch (Throwable ignored) {}
        return suiteRelated.size();
    }

    @SuppressWarnings("unchecked")
    private static Collection<Class<?>> classGraphGetClassesWithAnnotation(Object scanResult, String annotationFqn)
            throws Exception {
        Object classInfoList = scanResult.getClass()
                .getMethod("getClassesWithAnnotation", String.class)
                .invoke(scanResult, annotationFqn);
        Object list = classInfoList.getClass().getMethod("loadClasses").invoke(classInfoList);
        return (Collection<Class<?>>) list;
    }

    private static void extractJUnit5ConfigurationParameter(Annotation ann, Map<String, List<String>> result) {
        try {
            Method k = ann.annotationType().getMethod("key");
            Method v = ann.annotationType().getMethod("value");
            String key = (String) k.invoke(ann);
            String value = (String) v.invoke(ann);
            if (key != null && value != null) putSplit(result, key, value);
        } catch (Exception ignored) { }
    }

    // ===== NEW: repeatable-safe extraction for @ConfigurationParameter =====
    private static void extractJUnit5ConfigurationParameters(Class<?> clazz, Map<String, List<String>> result) {
        try {
            // 1) Preferred: Class.getAnnotationsByType(ConfigurationParameter.class)
            Class<?> paramAnnClass = Class.forName(JUNIT5_PARAM_FQN);
            Method byType = Class.class.getMethod("getAnnotationsByType", Class.class);
            Annotation[] params = (Annotation[]) byType.invoke(clazz, paramAnnClass);

            if (params != null && params.length > 0) {
                dbg("@ConfigurationParameter count on %s = %d", clazz.getName(), params.length);
                for (Annotation ann : params) {
                    try {
                        Method k = ann.annotationType().getMethod("key");
                        Method v = ann.annotationType().getMethod("value");
                        String key = (String) k.invoke(ann);
                        String val = (String) v.invoke(ann);
                        dbg("  @ConfigurationParameter on %s -> %s=%s", clazz.getName(), key, val);
                    } catch (Throwable ignored) {}
                    extractJUnit5ConfigurationParameter(ann, result);
                }
                return;
            }

            // 2) Fallback: handle the container @ConfigurationParameters, if only that is present
            final String CONTAINER_FQN = "org.junit.platform.suite.api.ConfigurationParameters";
            for (Annotation a : clazz.getAnnotations()) {
                if (a.annotationType().getName().equals(CONTAINER_FQN)) {
                    Method value = a.annotationType().getMethod("value"); // -> ConfigurationParameter[]
                    Object arr = value.invoke(a);
                    if (arr instanceof Object[] nested) {
                        dbg("@ConfigurationParameters container size on %s = %d", clazz.getName(), nested.length);
                        for (Object nestedAnn : nested) {
                            if (nestedAnn instanceof Annotation ann &&
                                    ann.annotationType().getName().equals(JUNIT5_PARAM_FQN)) {
                                extractJUnit5ConfigurationParameter(ann, result);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            dbg("extractJUnit5ConfigurationParameters failed for %s: %s", clazz.getName(), t);
        }
    }
    // ===== end NEW =====

    private static void extractJUnit5SelectClasspathResources(Class<?> clazz, Map<String, List<String>> result) {
        for (Annotation ann : clazz.getAnnotations()) {
            String annName = ann.annotationType().getName();
            try {
                if (annName.equals(JUNIT5_SEL_CP_RES_FQN)) {
                    String val = (String) ann.annotationType().getMethod("value").invoke(ann);
                    dbg("  @SelectClasspathResource on %s -> %s", clazz.getName(), val);
                    addFeatureRoot(result, val);
                } else if (annName.equals(JUNIT5_SEL_CP_RESS_FQN)) {
                    Object arr = ann.annotationType().getMethod("value").invoke(ann);
                    if (arr instanceof Object[] nested) {
                        for (Object nestedAnn : nested) {
                            if (nestedAnn != null &&
                                    nestedAnn.getClass().getName().equals(JUNIT5_SEL_CP_RES_FQN)) {
                                String val = (String) nestedAnn.getClass().getMethod("value").invoke(nestedAnn);
                                dbg("  @SelectClasspathResources nested on %s -> %s", clazz.getName(), val);
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

    private static void extractJUnit5SelectPackages(Class<?> clazz, Map<String, List<String>> result) {
        for (Annotation ann : clazz.getAnnotations()) {
            if (!ann.annotationType().getName().equals(JUNIT5_SEL_PKG_FQN)) continue;
            try {
                String[] pkgs = (String[]) ann.annotationType().getMethod("value").invoke(ann);
                if (pkgs != null) {
                    for (String p : pkgs) {
                        if (p != null && !p.isBlank()) {
                            String asFeatureRoot = toClasspathUrlFromPackage(p.trim());
                            dbg("  @SelectPackages on %s -> %s", clazz.getName(), asFeatureRoot);
                            put(result, "cucumber.features", asFeatureRoot);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private static void loadFromJUnit4RunnerClasses(Map<String, List<String>> result) {
        for (Class<?> clazz : findClassesAnnotatedWithFQN(CUCUMBER_OPTS_FQN)) {
            for (Annotation ann : clazz.getAnnotations()) {
                if (!ann.annotationType().getName().equals(CUCUMBER_OPTS_FQN)) continue;
                dbg("JUnit4 @CucumberOptions on %s", clazz.getName());
                extractJUnit4CucumberOptions(ann, result);
            }
        }
    }

    private static void extractJUnit4CucumberOptions(Annotation ann, Map<String, List<String>> result) {
        reflectArrayField(ann, "glue",     "cucumber.glue", result);
        reflectArrayField(ann, "features", "cucumber.features", result);
        reflectArrayField(ann, "plugin",   "cucumber.plugin", result);
        reflectArrayField(ann, "tags",     "cucumber.filter.tags", result);
        try {
            Method m = ann.annotationType().getMethod("name");
            String v = (String) m.invoke(ann);
            if (v != null && !v.isBlank()) put(result, "cucumber.filter.name", v);
        } catch (Exception ignored) { }
    }

    private static void reflectArrayField(
            Annotation ann, String methodName, String optKey,
            Map<String, List<String>> result) {
        try {
            Object arr = ann.annotationType().getMethod(methodName).invoke(ann);
            if (arr instanceof String[] strings) {
                dbg("  JUnit4 field %s -> %s", methodName, Arrays.toString(strings));
                for (String s : strings) {
                    if (!s.isBlank()) put(result, optKey, s.trim());
                }
            }
        } catch (Exception ignored) { }
    }

    private static void loadFromPropertiesFiles(Map<String, List<String>> result) {
        List<ClassLoader> loaders = List.of(
                Thread.currentThread().getContextClassLoader(),
                CucumberOptionResolver.class.getClassLoader()
        );
        for (String fileName : List.of("cucumber.properties", "junit-platform.properties")) {
            for (ClassLoader cl : loaders) {
                if (cl == null) continue;
                try {
                    Enumeration<URL> resources = cl.getResources(fileName);
                    while (resources.hasMoreElements()) {
                        URL url = resources.nextElement();
                        dbg("PROPS: found %s via %s at %s", fileName, loaderName(cl), url);
                        loadPropertiesFromUrl(url, result);
                    }
                } catch (IOException e) {
                    dbg("PROPS: error via %s scanning %s -> %s", loaderName(cl), fileName, e.toString());
                }
            }
        }
    }

    private static void loadPropertiesFromUrl(URL url, Map<String, List<String>> result) {
        try (InputStream is = url.openStream()) {
            Properties props = new Properties();
            props.load(is);
            for (String key : PROPERTIES_KEYS) {
                String v = props.getProperty(key);
                if (v != null) {
                    dbg("PROPS: %s -> %s", key, v);
                    putSplit(result, key, v);
                }
            }
        } catch (Exception e) {
            dbg("PROPS: failed to read %s -> %s", url, e.toString());
        }
    }

    private static List<Class<?>> findClassesAnnotatedWithFQN(String annFqn) {
        try { Class.forName(annFqn); } catch (Throwable ignored) {}
        return Collections.emptyList();
    }

    /* ============================ Helpers ============================ */

    private static void putSplit(Map<String, List<String>> map, String key, String val) {
        Arrays.stream(val.split("[,;]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .forEach(v -> put(map, key, v));
    }

    private static void put(Map<String, List<String>> map, String key, String val) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
    }

    /**
     * Normalizes paths/values for all known keys.
     *
     * For cucumber.features:
     *   - keeps classpath: URIs, but normalizes redundant slashes (existing behavior).
     *
     * For cucumber.glue:
     *   - converts any classpath-style or path-style value into a package-style string.
     *     e.g. "classpath:/tools/dscode/steps" -> "tools.dscode.steps"
     *          "tools/dscode/steps"            -> "tools.dscode.steps"
     *          "tools.dscode.steps"            -> "tools.dscode.steps" (unchanged)
     */
    private static Map<String, List<String>> normalizePaths(Map<String, List<String>> in) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            List<String> cleaned = v.stream()
                    .map(s -> normalizeValue(k, s))
                    .distinct()
                    .toList();
            out.put(k, new ArrayList<>(cleaned));
        });
        return out;
    }

    private static String normalizeValue(String key, String value) {
        if (value == null || value.isBlank()) return value;
        String s = value.trim();

        // First, normalize any classpath: prefix to a single form
        s = s.replaceFirst("^classpath:/*", "classpath:");

        // Special handling for glue: prefer package-style notation
        if ("cucumber.glue".equals(key)) {
            // Strip classpath: if present
            if (s.startsWith("classpath:")) {
                s = s.substring("classpath:".length());
            }
            // Remove leading slashes
            s = s.replaceFirst("^/*", "");
            // Convert path separators to package separators
            s = s.replace('/', '.');
        }

        return s;
    }

    public static List<String> glue()     { return getAllOptions().getOrDefault("cucumber.glue", List.of()); }
    public static List<String> features() { return getAllOptions().getOrDefault("cucumber.features", List.of()); }
    public static List<String> tags()     { return getAllOptions().getOrDefault("cucumber.filter.tags", List.of()); }
    public static List<String> plugins()  { return getAllOptions().getOrDefault("cucumber.plugin", List.of()); }

    public static URI toClasspathUriStrict(String pkgOrPath) throws URISyntaxException {
        if (pkgOrPath == null || pkgOrPath.isBlank()) return null;
        String s = pkgOrPath.trim();
        if (s.startsWith("classpath:")) s = s.substring("classpath:".length());
        s = s.replace('.', '/');
        if (!s.startsWith("/")) s = "/" + s;
        return new URI("classpath", s, null);
    }

    // --- NEW: heuristic to locate consumer test output dirs when launcher hides them from classpath ---
    // --- FIXED: no lambda captures; avoids "should be final or effectively final" ---
    private static List<String> guessConventionalTestOutputPaths() {
        List<String> candidates = new ArrayList<>();
        File base = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();

        // probe current dir and up to two parents (helps when run from submodules or IDE)
        List<File> roots = new ArrayList<>();
        roots.add(base);
        if (base.getParentFile() != null) roots.add(base.getParentFile());
        if (base.getParentFile() != null && base.getParentFile().getParentFile() != null)
            roots.add(base.getParentFile().getParentFile());

        String[][] patterns = new String[][]{
                {"target", "test-classes"},
                {"target", "classes", "test"},
                {"build", "classes", "java", "test"},
                {"build", "resources", "test"},
                {"out", "test", "classes"},
                {"bin", "test"},
        };

        String[] cpEntries = currentClasspathEntries(); // cache once

        for (File r : roots) {
            for (String[] p : patterns) {
                File probe = r;
                for (String seg : p) probe = new File(probe, seg);
                if (probe.isDirectory()) {
                    File probeAbs = probe.getAbsoluteFile();
                    boolean onCp = false;
                    for (String cp : cpEntries) {
                        File cpFile = new File(cp).getAbsoluteFile();
                        if (cpFile.equals(probeAbs)) { onCp = true; break; }
                    }
                    if (!onCp) {
                        String path = probeAbs.getPath();
                        candidates.add(path);
                    }
                }
            }
        }
        return candidates;
    }

    public static List<String> glueDistinct() {
        // glue() is already distinct today, but guard against future changes.
        return new ArrayList<>(new LinkedHashSet<>(glue()));
    }
}
