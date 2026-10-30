package tools.dscode.testengine;

import tools.dscode.control.protocol.ControlProtocol;

import org.junit.platform.commons.support.ReflectionSupport;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.discovery.PackageSelector;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import static io.cucumber.core.options.Constants.GLUE_PROPERTY_NAME;
import static tools.dscode.common.reporting.logging.LogForwarder.logDebug;

public final class DynamicSuiteBootstrap {
    /** Internal direct-worker contract; not a Pickleball RunVar. */
    public static final String WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY =
            ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY;

    private DynamicSuiteBootstrap() {
    }

    static PickleballRunner initialize(EngineDiscoveryRequest discoveryRequest) {
        PickleballRunner current = PickleballRunner.rawInstance();
        if (current != null) {
            debug("Reusing existing DynamicSuiteBase instance: " + current.getClass().getName());
            return current;
        }

        synchronized (PickleballRunner.class) {
            current = PickleballRunner.rawInstance();
            if (current != null) {
                debug("Reusing existing DynamicSuiteBase instance after lock: " + current.getClass().getName());
                return current;
            }

            PickleballRunner suite = instantiateResolvedSuite(discoveryRequest);
            debug("Bootstrap instantiated suite: " + suite.getClass().getName());
            return suite;
        }
    }

    public static PickleballRunner initializeFromRuntimeClasspath() {
        PickleballRunner current = PickleballRunner.rawInstance();
        if (current != null) {
            return current;
        }

        synchronized (PickleballRunner.class) {
            current = PickleballRunner.rawInstance();
            if (current != null) {
                return current;
            }

            PickleballRunner suite = instantiateResolvedSuite(null);
            debug("Fallback bootstrap instantiated suite: " + suite.getClass().getName());
            return suite;
        }
    }

    private static volatile boolean glueDetectionDone;
    private static volatile String detectedGluePackage;

    static String detectDefaultGluePackage() {
        if (glueDetectionDone) return detectedGluePackage;
        synchronized (DynamicSuiteBootstrap.class) {
            if (!glueDetectionDone) {
                detectedGluePackage = scanForDefaultGluePackage();
                glueDetectionDone = true;
            }
            return detectedGluePackage;
        }
    }

    static String scanForDefaultGluePackage() {
        Set<String> packages = new LinkedHashSet<>();

        for (URI root : classpathRoots()) {
            if (!isPreferredTestOutputRoot(root)) {
                continue;
            }

            Path path = Path.of(root);
            if (!Files.exists(path)) {
                debug("Skipping missing classpath root during glue detection: " + root);
                continue;
            }

            debug("Scanning preferred test classpath root for glue fallback: " + root);

            List<Class<?>> found = ReflectionSupport.findAllClassesInClasspathRoot(
                    root,
                    c -> true,
                    name -> true
            );

            for (Class<?> c : found) {
                Package pkg = c.getPackage();
                if (pkg == null) {
                    continue;
                }

                String packageName = pkg.getName();
                if (isStepPackage(packageName)) {
                    packages.add(packageName);
                    debug("Glue fallback candidate package: " + packageName);
                }
            }
        }

        if (packages.isEmpty()) {
            debug("No glue fallback package detected");
            return null;
        }

        List<String> topLevelPackages = removeNestedPackages(packages);
        String glue = String.join(",", topLevelPackages);
        debug("Resolved glue fallback package(s): " + glue);
        return glue;
    }

    /**
     * The consumer runner in the preferred test-output root, or null when this
     * JVM is not a consumer test process. Dry-run and a normal launch use the
     * same class so hook defaults are not a second formula.
     */
    public static Class<? extends PickleballRunner> findConsumerRunnerOrNull() {
        return findDynamicSuiteSubclassInPreferredTestRoots();
    }

    private static volatile Class<? extends PickleballRunner> DISCOVERED_SUITE_CLASS;

    public static Class<? extends PickleballRunner> getDiscoveredSuiteClass() {
        return DISCOVERED_SUITE_CLASS;
    }

    private static PickleballRunner instantiateResolvedSuite(EngineDiscoveryRequest discoveryRequest) {
        Class<? extends PickleballRunner> suiteClass = findDynamicSuiteSubclass(discoveryRequest);
        if (suiteClass != null) {
            DISCOVERED_SUITE_CLASS = suiteClass;
            debug("Bootstrap selected DynamicSuiteBase subclass: " + suiteClass.getName());

            PickleballRunner suite = ReflectionSupport.newInstance(suiteClass);

            String suiteClassName = suiteClass.getName();
            String existingGlue = suite.values.get(GLUE_PROPERTY_NAME);

            if (existingGlue == null || existingGlue.isBlank()) {
                suite.values.put(GLUE_PROPERTY_NAME, suiteClassName);
            } else if (!containsCommaSeparatedValue(existingGlue, suiteClassName)) {
                suite.values.put(GLUE_PROPERTY_NAME, existingGlue + "," + suiteClassName);
            }

            debug("Added suite subclass as glue path: " + suiteClassName);
            return suite;
        }

        debug("No DynamicSuiteBase subclass found in consumer test output roots. Using default suite.");
        return new DefaultDynamicSuite();
    }

    private static boolean containsCommaSeparatedValue(String csv, String value) {
        if (csv == null || csv.isBlank() || value == null || value.isBlank()) {
            return false;
        }

        for (String part : csv.split(",")) {
            if (value.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends PickleballRunner> findDynamicSuiteSubclass(EngineDiscoveryRequest discoveryRequest) {
        Set<Class<?>> matches = new LinkedHashSet<>();

        if (discoveryRequest != null) {
            List<ClassSelector> classSelectors = discoveryRequest.getSelectorsByType(ClassSelector.class);
            List<PackageSelector> packageSelectors = discoveryRequest.getSelectorsByType(PackageSelector.class);

            debug("DiscoveryRequest class selectors: " + classSelectors.size());
            for (ClassSelector selector : classSelectors) {
                Class<?> candidate = selector.getJavaClass();
                debug("ClassSelector candidate: " + candidate.getName());
                if (isCandidate(candidate)) {
                    debug("Accepted ClassSelector candidate: " + candidate.getName());
                    return (Class<? extends PickleballRunner>) candidate;
                }
            }

            debug("DiscoveryRequest package selectors: " + packageSelectors.size());
            for (PackageSelector selector : packageSelectors) {
                debug("Scanning PackageSelector package: " + selector.getPackageName());
                List<Class<?>> found = ReflectionSupport.findAllClassesInPackage(
                        selector.getPackageName(),
                        DynamicSuiteBootstrap::isCandidate,
                        name -> true
                );

                for (Class<?> c : found) {
                    debug("Accepted PackageSelector candidate: " + c.getName());
                }

                matches.addAll(found);

                if (matches.size() == 1) {
                    return (Class<? extends PickleballRunner>) matches.iterator().next();
                }
                if (matches.size() > 1) {
                    throw new IllegalStateException(
                            "Expected exactly one concrete subclass of " + PickleballRunner.class.getName()
                                    + " but found " + classNames(matches)
                    );
                }
            }
        }

        debug("No suite subclass found from request selectors. Checking consumer test output roots only.");
        return findDynamicSuiteSubclassInPreferredTestRoots();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends PickleballRunner> findDynamicSuiteSubclassInPreferredTestRoots() {
        Set<Class<?>> matches = new LinkedHashSet<>();

        for (URI root : classpathRoots()) {
            if (!isPreferredTestOutputRoot(root)) {
                continue;
            }

            Class<?> match = findSingleCandidateInRoot(root);
            if (match != null) {
                matches.add(match);
            }

            if (matches.size() == 1) {
                debug("Stopping scan after first matching preferred test root: " + root);
                return (Class<? extends PickleballRunner>) matches.iterator().next();
            }

            if (matches.size() > 1) {
                throw new IllegalStateException(
                        "Expected exactly one concrete subclass of " + PickleballRunner.class.getName()
                                + " but found " + classNames(matches)
                );
            }
        }
        return null;
    }

    private static Class<?> findSingleCandidateInRoot(URI root) {
        Path path = Path.of(root);

        if (!Files.exists(path)) {
            debug("Skipping missing classpath root: " + root);
            return null;
        }

        debug("Scanning preferred test classpath root: " + root);

        List<Class<?>> found = ReflectionSupport.findAllClassesInClasspathRoot(
                root,
                DynamicSuiteBootstrap::isCandidate,
                name -> true
        );

        if (found.isEmpty()) {
            return null;
        }

        for (Class<?> c : found) {
            debug("Accepted classpath candidate: " + c.getName());
        }

        if (found.size() > 1) {
            throw new IllegalStateException(
                    "Expected exactly one concrete subclass of " + PickleballRunner.class.getName()
                            + " in classpath root " + root + " but found " + classNames(found)
            );
        }

        return found.get(0);
    }

    private static boolean isCandidate(Class<?> c) {
        return c != null
                && PickleballRunner.class.isAssignableFrom(c)
                && c != PickleballRunner.class
                && !c.isInterface()
                && !Modifier.isAbstract(c.getModifiers());
    }

    private static boolean isStepPackage(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return false;
        }

        for (String segment : packageName.split("\\.")) {
            if (segment.startsWith("step")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> removeNestedPackages(Set<String> packages) {
        List<String> sorted = packages.stream()
                .sorted(Comparator.comparingInt(String::length).thenComparing(s -> s))
                .collect(Collectors.toList());

        List<String> result = new ArrayList<>();
        for (String candidate : sorted) {
            boolean nestedUnderExisting = false;
            for (String existing : result) {
                if (candidate.equals(existing) || candidate.startsWith(existing + ".")) {
                    nestedUnderExisting = true;
                    break;
                }
            }
            if (!nestedUnderExisting) {
                result.add(candidate);
            }
        }
        return result;
    }

    static boolean isPreferredTestOutputRoot(URI root) {
        String configured = System.getProperty(WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            if (!"file".equalsIgnoreCase(root.getScheme())) return false;
            Path expected = Path.of(configured).toAbsolutePath().normalize();
            Path candidate = Path.of(root).toAbsolutePath().normalize();
            return candidate.equals(expected);
        }

        String s = root.toString().replace('\\', '/');
        return s.endsWith("/target/test-classes/")
                || s.endsWith("/build/classes/java/test/")
                || s.endsWith("/build/classes/kotlin/test/")
                || s.endsWith("/build/classes/test/")
                || s.endsWith("/workbench/live/classes/");
    }

    /**
     * {@code java.class.path} entries, plus the file URLs of the context class
     * loader chain when {@code java.class.path} has no test output root. Under
     * {@code mvn exec:java} the JVM class path is Maven's launcher, and the
     * project's classes are only on the context class loader (2.1.17 bug 1).
     * Normal runs, whose test output is on {@code java.class.path}, are unchanged.
     */
    public static List<String> effectiveClasspathEntries() {
        return effectiveClasspathEntries(
                System.getProperty("java.class.path", ""),
                Thread.currentThread().getContextClassLoader());
    }

    static List<String> effectiveClasspathEntries(String javaClassPath, ClassLoader context) {
        List<String> entries = new ArrayList<>();
        for (String entry : (javaClassPath == null ? "" : javaClassPath).split(File.pathSeparator, -1)) {
            if (!entry.isBlank()) entries.add(entry);
        }
        boolean hasTestRoot = entries.stream().anyMatch(entry -> {
            try {
                return isPreferredTestOutputRoot(Path.of(entry).toUri());
            } catch (RuntimeException invalid) {
                return false;
            }
        });
        if (hasTestRoot) return entries;
        List<String> extra = new ArrayList<>();
        for (ClassLoader loader = context; loader != null; loader = loader.getParent()) {
            if (!(loader instanceof java.net.URLClassLoader urls)) continue;
            for (java.net.URL url : urls.getURLs()) {
                if (!"file".equalsIgnoreCase(url.getProtocol())) continue;
                try {
                    String path = Path.of(url.toURI()).toString();
                    if (!entries.contains(path) && !extra.contains(path)) extra.add(path);
                } catch (Exception ignored) {
                    // Skip URLs that are not plain file paths.
                }
            }
        }
        // Context-loader entries first so the project's test output is found before launcher jars.
        extra.addAll(entries);
        return extra.isEmpty() ? entries : extra;
    }

    private static List<URI> classpathRoots() {
        List<String> entries = effectiveClasspathEntries();
        List<URI> roots = new ArrayList<>(entries.size());

        for (String entry : entries) {
            if (entry.isBlank()) {
                continue;
            }
            URI uri = Path.of(entry).toUri();
            roots.add(uri);

            if (entry.endsWith(".jar")) {
                roots.addAll(extractManifestClassPath(entry));
            }
        }

        return roots;
    }

    private static List<URI> extractManifestClassPath(String jarPath) {
        List<URI> result = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jarPath)) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return result;
            }

            String manifestCp = manifest.getMainAttributes().getValue("Class-Path");
            if (manifestCp == null || manifestCp.isBlank()) {
                return result;
            }

            for (String cpEntry : manifestCp.split("\\s+")) {
                if (cpEntry.isBlank()) {
                    continue;
                }
                try {
                    URI entryUri = URI.create(cpEntry);
                    if (!entryUri.isAbsolute()) {
                        URI jarUri = Path.of(jarPath).toAbsolutePath().getParent().toUri();
                        entryUri = jarUri.resolve(cpEntry);
                    }
                    result.add(entryUri);
                } catch (Exception e) {
                    // Skip malformed manifest classpath entries
                }
            }
        } catch (IOException e) {
            // JAR has no readable manifest; skip
        }
        return result;
    }

    private static String classNames(Iterable<Class<?>> classes) {
        List<String> names = new ArrayList<>();
        for (Class<?> c : classes) {
            names.add(c.getName());
        }
        return names.toString();
    }

    private static void debug(String message) {
        logDebug("[DynamicSuiteBootstrap] " + message);
    }

    private static final class DefaultDynamicSuite extends PickleballRunner {
        @Override
        public void globalTestDefaults() {
            // Intentionally empty.
        }

        @Override
        public void globalTestProperties() {
            // Intentionally empty.
        }
    }
}
