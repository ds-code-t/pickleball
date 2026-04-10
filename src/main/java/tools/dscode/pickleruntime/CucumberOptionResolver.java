package tools.dscode.pickleruntime;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class CucumberOptionResolver {

    private static final boolean DEBUG = false;

    private static void dbg(String fmt, Object... args) {
        if (!DEBUG) return;
        String s = (args == null || args.length == 0) ? fmt : String.format(fmt, args);
        System.out.println("[CucumberOptionResolver][DBG] " + s);
    }

    private static void snap(String label, Map<String, List<String>> m) {
        if (!DEBUG) return;
        dbg("%s glue=%s", label, m.getOrDefault("cucumber.glue", List.of()));
        dbg("%s feats=%s", label, m.getOrDefault("cucumber.features", List.of()));
        dbg("%s tags=%s", label, m.getOrDefault("cucumber.filter.tags", List.of()));
        dbg("%s plug=%s", label, m.getOrDefault("cucumber.plugin", List.of()));
        dbg("%s name=%s", label, m.getOrDefault("cucumber.filter.name", List.of()));
    }

    private static volatile Map<String, List<String>> CACHE;
    private static final Object INIT_LOCK = new Object();

    private static final String JUNIT5_SUITE_FQN = "org.junit.platform.suite.api.Suite";
    private static final String JUNIT5_PARAM_FQN = "org.junit.platform.suite.api.ConfigurationParameter";
    private static final String JUNIT5_SEL_CP_RES_FQN = "org.junit.platform.suite.api.SelectClasspathResource";
    private static final String JUNIT5_SEL_CP_RESS_FQN = "org.junit.platform.suite.api.SelectClasspathResources";
    private static final String JUNIT5_SEL_PKG_FQN = "org.junit.platform.suite.api.SelectPackages";

    private static final String CUCUMBER_OPTS_FQN = "io.cucumber.junit.CucumberOptions";

    private static final List<String> PROPERTIES_KEYS = List.of(
            "cucumber.glue",
            "cucumber.features",
            "cucumber.plugin",
            "cucumber.filter.tags",
            "cucumber.filter.name"
    );

    private CucumberOptionResolver() {
    }

    public static Map<String, List<String>> getAllOptions() {
        Map<String, List<String>> cache = CACHE;
        if (cache != null) return cache;

        synchronized (INIT_LOCK) {
            cache = CACHE;
            if (cache != null) return cache;

            Map<String, List<String>> merged = new LinkedHashMap<>();

            loadFromEnvironment(merged);
            snap("after ENV", merged);

            loadFromJUnit5SuiteAnnotations(merged);
            snap("after JUnit5", merged);

            loadFromJUnit4RunnerClasses(merged);
            snap("after JUnit4", merged);

            loadFromPropertiesFiles(merged);
            snap("after PROPS", merged);

            Map<String, List<String>> normalized = normalizePaths(merged);
            snap("after NORMALIZE", normalized);

            boolean empty = normalized.isEmpty() || normalized.values().stream().allMatch(List::isEmpty);
            if (!empty) {
                normalized.compute("cucumber.glue", (k, v) -> {
                    List<String> list = (v == null) ? new ArrayList<>() : new ArrayList<>(v);
                    if (list.stream().noneMatch("tools.dscode.coredefinitions"::equals)) {
                        list.add("tools.dscode.coredefinitions");
                    }
                    return list;
                });

                cache = immutableCopy(normalized);
                CACHE = cache;
                return cache;
            }

            // Do not permanently cache empty results; this avoids poisoning the cache
            // if resolution happens too early or under an incomplete classloader context.
            return immutableCopy(normalized);
        }
    }

    public static Map<String, List<String>> globalOptions() {
        return getAllOptions();
    }

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
        for (String f : opts.getOrDefault("cucumber.features", List.of())) {
            if (f != null && !f.isBlank()) {
                args.add(f.trim());
            }
        }

        return args.toArray(new String[0]);
    }

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

    private static void loadFromJUnit5SuiteAnnotations(Map<String, List<String>> result) {
        List<String> scanRoots = consumerScanRootsForCucumber();
        if (scanRoots.isEmpty()) return;

        try (ScanResult scan = new ClassGraph()
                .overrideClasspath(scanRoots)
                .disableNestedJarScanning()
                .enableClassInfo()
                .enableAnnotationInfo()
                .ignoreClassVisibility()
                .scan()) {

            LinkedHashSet<Class<?>> suiteRelated = new LinkedHashSet<>();
            suiteRelated.addAll(scan.getClassesWithAnnotation(JUNIT5_SUITE_FQN).loadClasses());
            suiteRelated.addAll(scan.getClassesWithAnnotation(JUNIT5_PARAM_FQN).loadClasses());
            suiteRelated.addAll(scan.getClassesWithAnnotation(JUNIT5_SEL_CP_RES_FQN).loadClasses());
            suiteRelated.addAll(scan.getClassesWithAnnotation(JUNIT5_SEL_CP_RESS_FQN).loadClasses());
            suiteRelated.addAll(scan.getClassesWithAnnotation(JUNIT5_SEL_PKG_FQN).loadClasses());

            for (Class<?> clazz : suiteRelated) {
                extractJUnit5ConfigurationParameters(clazz, result);
                extractJUnit5SelectClasspathResources(clazz, result);
                extractJUnit5SelectPackages(clazz, result);
            }

        } catch (Throwable t) {
            dbg("JUnit5 scan failed: %s", t);
            if (DEBUG) t.printStackTrace(System.out);
        }
    }

    private static void extractJUnit5ConfigurationParameter(Annotation ann, Map<String, List<String>> result) {
        try {
            Method k = ann.annotationType().getMethod("key");
            Method v = ann.annotationType().getMethod("value");
            String key = (String) k.invoke(ann);
            String value = (String) v.invoke(ann);
            if (key != null && value != null) {
                putSplit(result, key, value);
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static void extractJUnit5ConfigurationParameters(Class<?> clazz, Map<String, List<String>> result) {
        try {
            Class<? extends Annotation> paramAnnClass =
                    (Class<? extends Annotation>) Class.forName(JUNIT5_PARAM_FQN);

            Annotation[] params = clazz.getAnnotationsByType(paramAnnClass);
            if (params != null && params.length > 0) {
                for (Annotation ann : params) {
                    extractJUnit5ConfigurationParameter(ann, result);
                }
                return;
            }

            final String containerFqn = "org.junit.platform.suite.api.ConfigurationParameters";
            for (Annotation a : clazz.getAnnotations()) {
                if (!a.annotationType().getName().equals(containerFqn)) continue;

                Object arr = a.annotationType().getMethod("value").invoke(a);
                if (!(arr instanceof Object[] nestedAnnotations)) continue;

                for (Object nestedObj : nestedAnnotations) {
                    if (nestedObj instanceof Annotation nestedAnnotation &&
                            nestedAnnotation.annotationType().getName().equals(JUNIT5_PARAM_FQN)) {
                        extractJUnit5ConfigurationParameter(nestedAnnotation, result);
                    }
                }
            }
        } catch (Throwable t) {
            dbg("extractJUnit5ConfigurationParameters failed for %s: %s", clazz.getName(), t);
        }
    }

    private static void extractJUnit5SelectClasspathResources(Class<?> clazz, Map<String, List<String>> result) {
        for (Annotation ann : clazz.getAnnotations()) {
            String annName = ann.annotationType().getName();
            try {
                if (annName.equals(JUNIT5_SEL_CP_RES_FQN)) {
                    String val = (String) ann.annotationType().getMethod("value").invoke(ann);
                    addFeatureRoot(result, val);
                } else if (annName.equals(JUNIT5_SEL_CP_RESS_FQN)) {
                    Object arr = ann.annotationType().getMethod("value").invoke(ann);
                    if (!(arr instanceof Object[] nestedAnnotations)) continue;

                    for (Object nestedObj : nestedAnnotations) {
                        if (nestedObj instanceof Annotation nestedAnnotation &&
                                nestedAnnotation.annotationType().getName().equals(JUNIT5_SEL_CP_RES_FQN)) {
                            String val = (String) nestedAnnotation.annotationType().getMethod("value").invoke(nestedAnnotation);
                            addFeatureRoot(result, val);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void addFeatureRoot(Map<String, List<String>> result, String val) {
        if (val == null || val.isBlank()) return;
        String s = val.trim();
        if (!s.startsWith("classpath:")) {
            s = "classpath:" + s.replaceAll("^/*", "");
        }
        put(result, "cucumber.features", s);
    }

    private static String toClasspathUrlFromPackage(String pkg) {
        String path = pkg.replace('.', '/');
        if (!path.startsWith("classpath:")) {
            path = "classpath:" + path;
        }
        return path;
    }

    private static void extractJUnit5SelectPackages(Class<?> clazz, Map<String, List<String>> result) {
        for (Annotation ann : clazz.getAnnotations()) {
            if (!ann.annotationType().getName().equals(JUNIT5_SEL_PKG_FQN)) continue;
            try {
                String[] pkgs = (String[]) ann.annotationType().getMethod("value").invoke(ann);
                if (pkgs == null) continue;

                for (String p : pkgs) {
                    if (p != null && !p.isBlank()) {
                        put(result, "cucumber.features", toClasspathUrlFromPackage(p.trim()));
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

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
            if (v != null && !v.isBlank()) {
                put(result, "cucumber.filter.name", v);
            }
        } catch (Exception ignored) {
        }
    }

    private static void reflectArrayField(Annotation ann, String methodName, String optKey, Map<String, List<String>> result) {
        try {
            Object arr = ann.annotationType().getMethod(methodName).invoke(ann);
            if (arr instanceof String[] strings) {
                for (String s : strings) {
                    if (s != null && !s.isBlank()) {
                        put(result, optKey, s.trim());
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void loadFromPropertiesFiles(Map<String, List<String>> result) {
        for (String fileName : List.of("cucumber.properties", "junit-platform.properties")) {
            for (ClassLoader cl : candidateClassLoaders()) {
                if (cl == null) continue;
                try {
                    Enumeration<URL> resources = cl.getResources(fileName);
                    while (resources.hasMoreElements()) {
                        loadPropertiesFromUrl(resources.nextElement(), result);
                    }
                } catch (IOException ignored) {
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
                    putSplit(result, key, v);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static List<Class<?>> findClassesAnnotatedWithFQN(String annFqn) {
        try {
            Class.forName(annFqn);
        } catch (Throwable ignored) {
        }
        return Collections.emptyList();
    }

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
        s = s.replaceFirst("^classpath:/*", "classpath:");

        if ("cucumber.glue".equals(key)) {
            if (s.startsWith("classpath:")) {
                s = s.substring("classpath:".length());
            }
            s = s.replaceFirst("^/*", "");
            s = s.replace('/', '.');
        }

        return s;
    }

    private static Map<String, List<String>> immutableCopy(Map<String, List<String>> in) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(out);
    }

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

    public static URI toClasspathUriStrict(String pkgOrPath) throws URISyntaxException {
        if (pkgOrPath == null || pkgOrPath.isBlank()) return null;
        String s = pkgOrPath.trim();
        if (s.startsWith("classpath:")) s = s.substring("classpath:".length());
        s = s.replace('.', '/');
        if (!s.startsWith("/")) s = "/" + s;
        return new URI("classpath", s, null);
    }

    private static List<ClassLoader> candidateClassLoaders() {
        LinkedHashSet<ClassLoader> loaders = new LinkedHashSet<>();

        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        ClassLoader self = CucumberOptionResolver.class.getClassLoader();
        ClassLoader system = ClassLoader.getSystemClassLoader();

        if (tccl != null) loaders.add(tccl);
        if (self != null) loaders.add(self);
        if (system != null) loaders.add(system);

        return new ArrayList<>(loaders);
    }

    private static List<String> consumerScanRootsForCucumber() {
        LinkedHashSet<String> roots = new LinkedHashSet<>();

        for (String cp : currentClasspathEntries()) {
            addIfConsumerOutputDir(roots, cp);
        }

        for (ClassLoader cl : candidateClassLoaders()) {
            addRootsFromResources(cl, "junit-platform.properties", roots);
            addRootsFromResources(cl, "cucumber.properties", roots);
        }

        roots.addAll(guessConventionalOutputPaths());
        return new ArrayList<>(roots);
    }

    private static String[] currentClasspathEntries() {
        String cp = System.getProperty("java.class.path", "");
        if (cp.isEmpty()) return new String[0];
        return cp.split(File.pathSeparator);
    }

    private static void addIfConsumerOutputDir(LinkedHashSet<String> roots, String path) {
        if (path == null || path.isBlank()) return;

        File f = new File(path).getAbsoluteFile();
        if (!f.isDirectory()) return;

        String p = f.getPath().replace('\\', '/');
        if (looksLikeConsumerOutputDir(p)) {
            roots.add(f.getPath());
        }
    }

    private static boolean looksLikeConsumerOutputDir(String path) {
        String p = path.replace('\\', '/');
        return p.endsWith("/target/classes")
                || p.endsWith("/target/test-classes")
                || p.contains("/build/classes/java/main")
                || p.contains("/build/classes/java/test")
                || p.contains("/build/resources/test")
                || p.contains("/out/production/")
                || p.contains("/out/test/")
                || p.endsWith("/bin/main")
                || p.endsWith("/bin/test");
    }

    private static void addRootsFromResources(ClassLoader cl, String resourceName, LinkedHashSet<String> roots) {
        try {
            Enumeration<URL> resources = cl.getResources(resourceName);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                File dir = tryResolveClasspathDirectory(url, resourceName);
                if (dir != null) {
                    addIfConsumerOutputDir(roots, dir.getPath());
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static File tryResolveClasspathDirectory(URL url, String resourceName) {
        if (url == null || resourceName == null || resourceName.isBlank()) return null;
        if (!"file".equalsIgnoreCase(url.getProtocol())) return null;

        try {
            String raw = URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8);
            if (!raw.endsWith(resourceName)) return null;

            String dirPath = raw.substring(0, raw.length() - resourceName.length());
            File dir = new File(dirPath).getAbsoluteFile();
            return dir.isDirectory() ? dir : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> guessConventionalOutputPaths() {
        List<String> candidates = new ArrayList<>();
        File base = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();

        List<File> roots = new ArrayList<>();
        roots.add(base);
        if (base.getParentFile() != null) roots.add(base.getParentFile());
        if (base.getParentFile() != null && base.getParentFile().getParentFile() != null) {
            roots.add(base.getParentFile().getParentFile());
        }

        String[][] patterns = {
                {"target", "classes"},
                {"target", "test-classes"},
                {"target", "classes", "test"},
                {"build", "classes", "java", "main"},
                {"build", "classes", "java", "test"},
                {"build", "resources", "test"},
                {"out", "production"},
                {"out", "test"},
                {"out", "test", "classes"},
                {"bin", "main"},
                {"bin", "test"}
        };

        String[] cpEntries = currentClasspathEntries();

        for (File r : roots) {
            for (String[] p : patterns) {
                File probe = r;
                for (String seg : p) {
                    probe = new File(probe, seg);
                }

                if (!probe.isDirectory()) continue;

                File probeAbs = probe.getAbsoluteFile();
                boolean onCp = false;
                for (String cp : cpEntries) {
                    if (new File(cp).getAbsoluteFile().equals(probeAbs)) {
                        onCp = true;
                        break;
                    }
                }

                if (!onCp && looksLikeConsumerOutputDir(probeAbs.getPath())) {
                    candidates.add(probeAbs.getPath());
                }
            }
        }

        return candidates;
    }

    public static List<String> glueDistinct() {
        return new ArrayList<>(new LinkedHashSet<>(glue()));
    }
}