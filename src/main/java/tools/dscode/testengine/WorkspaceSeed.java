package tools.dscode.testengine;

import tools.dscode.common.resources.ResourceLocations;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Writes a run workspace from one normal resolution, or copies an earlier one.
 * A workspace that already exists is left alone unless {@code reseed} is set.
 */
public final class WorkspaceSeed {
    public static final String SEED_PROPERTY = "pickleball.workspace.seed";

    private WorkspaceSeed() {
    }

    public static boolean captureKeepsParallelAuto() {
        return Boolean.parseBoolean(System.getProperty(SEED_PROPERTY, "false"));
    }

    public record Inputs(
            Map<String, byte[]> propertyFiles,
            long testClassesModifiedMillis,
            String classpathFingerprint,
            Map<String, String> resolutionInputs
    ) {
    }

    public static Path seed(
            Path project,
            String runId,
            boolean reseed,
            String fromRunId,
            Inputs inputs,
            Consumer<Path> capture
    ) {
        Path workspace = Workspace.directory(project, runId);
        if (Workspace.exists(workspace) && !reseed) {
            return workspace;
        }
        if (fromRunId != null && !fromRunId.isBlank()) {
            Path from = Workspace.directory(project, fromRunId);
            if (reseed && Workspace.exists(workspace)) Workspace.replaceWorkspace(from, workspace);
            else Workspace.copyWorkspace(from, workspace);
            stampRecord(workspace);
            return workspace;
        }
        Inputs effective = inputs == null ? detect(project) : inputs;
        String key = Workspace.cacheKey(
                effective.propertyFiles(),
                effective.testClassesModifiedMillis(),
                effective.classpathFingerprint(),
                effective.resolutionInputs()
        );
        if (!reseed && Workspace.cacheHit(project, key)) {
            Workspace.restoreCache(project, key, workspace);
            stampRecord(workspace);
            return workspace;
        }
        if (capture == null) {
            throw new IllegalStateException("Workspace seed has no resolver for " + workspace);
        }
        capture.accept(workspace);
        if (!Workspace.exists(workspace)) {
            throw new IllegalStateException("Workspace seed did not write " + workspace);
        }
        Workspace.storeCache(project, key, workspace);
        stampRecord(workspace);
        return workspace;
    }

    public static List<String> writeCaptured(Path workspace, PickleballRunner runner, boolean reseed) {
        if (Workspace.exists(workspace) && !reseed) {
            throw new IllegalStateException(
                    "Workspace already exists at " + workspace + ". Use --reseed to replace it.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (runner != null) {
            runner.values().forEach((key, value) -> {
                if (key != null && PKB_props.isRunVariableKey(key) && value != null) values.put(key, value);
            });
        }
        values.keySet().removeIf(Workspace.CAPTURE_EXCLUDED_KEYS::contains);
        Workspace.ensureContextKeys(values);
        String browser = values.get(PKB_props.PKB_BROWSER);
        values.put(PKB_props.PKB_BROWSER, browserOrHeadless(browser));
        String configured = values.get(PKB_props.PKB_CONFIG_PATH);
        Path source = configured == null || configured.isBlank()
                ? null
                : ResourceLocations.existingDirectory(configured, ResourceLocations.workingDirectory());
        Workspace.copyConfigs(source, workspace);
        Set<String> keep = runnerComputedSecretKeys(values);
        Workspace.writeRunVars(workspace, values, keep);
        List<String> warnings = new ArrayList<>(new java.util.LinkedHashSet<>(
                java.util.stream.Stream.concat(
                        Workspace.literalSecretWarnings(Workspace.configsDirectory(workspace)).stream(),
                        runnerComputedSecretWarnings(values).stream()
                ).toList()
        ));
        return warnings;
    }

    /**
     * Sensitive runner values with no project-property source. A
     * {@code ${protected:key}} reference would fail at launch, so the workspace
     * keeps the value and the caller warns.
     */
    public static List<String> runnerComputedSecretWarnings(Map<String, String> values) {
        return runnerComputedSecrets(values).warnings();
    }

    public static Set<String> runnerComputedSecretKeys(Map<String, String> values) {
        return runnerComputedSecrets(values).keys();
    }

    /**
     * Keys whose stored value differs from the same key in a project property
     * file. A key that is not in those files is a default, not a computed override.
     */
    public static Set<String> computedKeys(Path project, Map<String, String> values) {
        Set<String> computed = new LinkedHashSet<>();
        if (values == null || values.isEmpty()) return computed;
        Map<String, String> files = projectFileProperties(project);
        values.forEach((rawKey, rawValue) -> {
            if (rawKey == null || rawKey.isBlank()) return;
            String key = Workspace.normalizeKey(rawKey);
            String stored = rawValue == null ? "" : rawValue;
            if (!files.containsKey(key)) return;
            if (!stored.equals(files.get(key))) computed.add(key);
        });
        return computed;
    }

    private static Map<String, String> projectFileProperties(Path project) {
        Map<String, String> values = new LinkedHashMap<>();
        if (project == null) return values;
        Path root = project.toAbsolutePath().normalize();
        for (Path file : List.of(
                root.resolve("src/test/resources/pickleball_local2.properties"),
                root.resolve("src/test/resources/pickleball_local.properties"),
                root.resolve("src/test/resources/pickleball.properties"),
                root.resolve("src/main/resources/pickleball_local2.properties"),
                root.resolve("src/main/resources/pickleball_local.properties"),
                root.resolve("src/main/resources/pickleball.properties"),
                root.resolve("pickleball_local2.properties"),
                root.resolve("pickleball_local.properties"),
                root.resolve("pickleball.properties")
        )) {
            if (!Files.isRegularFile(file)) continue;
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException ignored) {
                continue;
            }
            for (String key : properties.stringPropertyNames()) {
                values.put(Workspace.normalizeKey(key), properties.getProperty(key));
            }
        }
        return values;
    }

    private record SecretKeep(Set<String> keys, List<String> warnings) {
    }

    private static SecretKeep runnerComputedSecrets(Map<String, String> values) {
        Set<String> keys = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        if (values == null || values.isEmpty()) return new SecretKeep(keys, warnings);
        Map<String, String> properties = ProjectPropertySources.readWorkbench(Path.of(""));
        values.forEach((rawKey, rawValue) -> {
            if (rawKey == null || rawKey.isBlank()) return;
            String key = Workspace.normalizeKey(rawKey);
            if (!Workspace.isSensitiveKey(key)) return;
            String text = rawValue == null ? "" : rawValue;
            if (text.isBlank() || Workspace.protectedKey(text) != null) return;
            String fromFile = properties.get(key);
            if (fromFile != null && !fromFile.isBlank()) return;
            keys.add(key);
            warnings.add("WARNING: " + key
                    + " was set by the runner and is not in project properties, so the workspace keeps the value. "
                    + "A ${protected:" + key + "} reference would fail at launch.");
        });
        return new SecretKeep(Set.copyOf(keys), List.copyOf(warnings));
    }

    /**
     * Browser stored by a capture. A named project browser is kept.
     * {@code CHROME_HEADLESS} is only the fallback when that value is blank.
     */
    public static String browserOrHeadless(String browser) {
        if (browser == null || browser.isBlank()) return "CHROME_HEADLESS";
        return browser.trim();
    }

    /**
     * {@code pkb_browser} from the project property files a capture reads.
     * Empty when the project names none. This is not a launch run-var builder.
     */
    public static String namedProjectBrowser(Path project) {
        if (project == null) return "";
        Path root = project.toAbsolutePath().normalize();
        for (Path file : List.of(
                root.resolve("src/test/resources/pickleball_local2.properties"),
                root.resolve("src/test/resources/pickleball_local.properties"),
                root.resolve("src/test/resources/pickleball.properties"),
                root.resolve("src/main/resources/pickleball_local2.properties"),
                root.resolve("src/main/resources/pickleball_local.properties"),
                root.resolve("src/main/resources/pickleball.properties"),
                root.resolve("pickleball_local2.properties"),
                root.resolve("pickleball_local.properties"),
                root.resolve("pickleball.properties")
        )) {
            String browser = browserProperty(file);
            if (!browser.isBlank()) return browser;
        }
        return "";
    }

    private static String browserProperty(Path file) {
        if (!Files.isRegularFile(file)) return "";
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ignored) {
            return "";
        }
        String value = properties.getProperty(PKB_props.PKB_BROWSER);
        return value == null ? "" : value.trim();
    }

    public static Inputs detect(Path project) {
        List<String> classpath = syncedClasspath(project);
        if (classpath.isEmpty()) classpath = runtimeClasspath();
        return new Inputs(
                propertyFiles(project),
                testClassesModifiedMillis(project),
                classpathFingerprint(classpath),
                resolutionInputs(System.getProperties(), System.getenv())
        );
    }

    /**
     * The classpath {@code sync} wrote. A capture forks that list, so the seed
     * cache key has to change when it changes. The launcher JVM classpath is
     * only the fallback before the first sync.
     */
    static List<String> syncedClasspath(Path project) {
        if (project == null) return List.of();
        Path file = PickleballLocalLayout.workbenchStateRoot(project).resolve("classpath.txt");
        if (!Files.isRegularFile(file)) return List.of();
        try {
            return Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read Workbench classpath: " + file, failure);
        }
    }

    static List<String> runtimeClasspath() {
        return DynamicSuiteBootstrap.effectiveClasspathEntries();
    }

    /**
     * {@code -D} run vars and {@code PKB_} environment values that change a capture.
     * {@code pkb_workspace} is not a resolution input.
     */
    static Map<String, String> resolutionInputs(Properties system, Map<String, String> environment) {
        Map<String, String> inputs = new TreeMap<>();
        if (system != null) {
            for (String key : system.stringPropertyNames()) {
                if (key == null || SEED_PROPERTY.equals(key)) continue;
                String normalized = key.trim().toLowerCase(Locale.ROOT);
                if (!normalized.startsWith("pkb_") || Workspace.PROPERTY.equals(normalized)) continue;
                String value = system.getProperty(key);
                inputs.put(normalized, value == null ? "" : value);
            }
        }
        if (environment != null) {
            environment.forEach((name, value) -> {
                if (name != null && name.toUpperCase(Locale.ROOT).startsWith("PKB_")) {
                    inputs.put("env:" + name, value == null ? "" : value);
                }
            });
        }
        return inputs;
    }

    public static Map<String, byte[]> propertyFiles(Path project) {
        Map<String, byte[]> files = new TreeMap<>();
        if (project == null) return files;
        for (String name : List.of(
                "pickleball.properties",
                "pickleball_local.properties",
                "pickleball_local2.properties",
                "src/test/resources/pickleball.properties",
                "src/test/resources/pickleball_local.properties",
                "src/test/resources/pickleball_local2.properties"
        )) {
            Path file = project.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try {
                files.put(name, Files.readAllBytes(file));
            } catch (IOException failure) {
                throw new IllegalStateException("Could not read " + file, failure);
            }
        }
        return files;
    }

    public static long testClassesModifiedMillis(Path project) {
        long latest = 0L;
        if (project == null) return latest;
        for (Path root : List.of(
                project.resolve("target/test-classes"),
                project.resolve("build/classes/java/test"),
                project.resolve("build/classes/kotlin/test")
        )) {
            if (!Files.isDirectory(root)) continue;
            try (var walk = Files.walk(root)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    latest = Math.max(latest, Files.getLastModifiedTime(file).toMillis());
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Could not fingerprint test classes at " + root, failure);
            }
        }
        return latest;
    }

    public static String classpathFingerprint(List<String> classpath) {
        StringBuilder text = new StringBuilder();
        if (classpath == null) return "";
        for (String entry : classpath) {
            if (entry == null || entry.isBlank()) continue;
            Path path = Path.of(entry);
            long size = -1L;
            long modified = -1L;
            try {
                if (Files.isRegularFile(path)) {
                    size = Files.size(path);
                    modified = Files.getLastModifiedTime(path).toMillis();
                } else if (Files.isDirectory(path)) {
                    modified = Files.getLastModifiedTime(path).toMillis();
                }
            } catch (IOException ignored) {
                size = -1L;
            }
            text.append(entry).append('\0').append(size).append('\0').append(modified).append('\n');
        }
        return text.toString();
    }

    public static int runMain(String[] args) {
        Parsed parsed;
        try {
            parsed = Parsed.parse(args);
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage());
            return 2;
        }
        System.setProperty(SEED_PROPERTY, "true");
        System.clearProperty(Workspace.PROPERTY);
        try {
            seed(parsed.project(), parsed.runId(), parsed.reseed(), parsed.fromRunId(), null, workspace -> {
                PickleballRunner runner = DynamicSuiteBootstrap.initializeFromRuntimeClasspath();
                for (String warning : writeCaptured(workspace, runner, parsed.reseed())) {
                    System.err.println(warning);
                }
            });
            System.out.println("workspace=" + Workspace.directory(parsed.project(), parsed.runId()));
            return 0;
        } catch (RuntimeException failure) {
            System.err.println(failure.getMessage());
            return 1;
        } finally {
            System.clearProperty(SEED_PROPERTY);
        }
    }

    private static void stampRecord(Path workspace) {
        Path record = workspace.getParent() == null ? null : workspace.getParent().resolve("record.json");
        if (record != null && Files.isRegularFile(record)) {
            Workspace.writeFingerprint(record, Workspace.fingerprint(workspace));
        }
    }

    record Parsed(Path project, String runId, boolean reseed, String fromRunId) {
        static Parsed parse(String[] args) {
            Path project = Path.of("").toAbsolutePath();
            String runId = null;
            boolean reseed = false;
            String from = null;
            Path workspace = null;
            List<String> tokens = new ArrayList<>();
            if (args != null) {
                for (String arg : args) if (arg != null && !arg.isBlank()) tokens.add(arg);
            }
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if ("seed".equals(token) && i == 0) continue;
                if ("--reseed".equals(token)) {
                    reseed = true;
                    continue;
                }
                if (token.startsWith("--from=")) {
                    from = token.substring("--from=".length());
                    continue;
                }
                if ("--from".equals(token) && i + 1 < tokens.size()) {
                    from = tokens.get(++i);
                    continue;
                }
                if (token.startsWith("--run=")) {
                    runId = token.substring("--run=".length());
                    continue;
                }
                if ("--run".equals(token) && i + 1 < tokens.size()) {
                    runId = tokens.get(++i);
                    continue;
                }
                if (token.startsWith("--project=")) {
                    project = Path.of(token.substring("--project=".length()));
                    continue;
                }
                if ("--project".equals(token) && i + 1 < tokens.size()) {
                    project = Path.of(tokens.get(++i));
                    continue;
                }
                if (token.startsWith("--workspace=")) {
                    workspace = Path.of(token.substring("--workspace=".length()));
                    continue;
                }
                if (!token.startsWith("--") && runId == null) {
                    runId = token;
                    continue;
                }
                throw new IllegalArgumentException("Unknown seed argument: " + token);
            }
            if (workspace != null) {
                Path parent = workspace.getParent();
                if (runId == null && parent != null && parent.getFileName() != null) runId = parent.getFileName().toString();
                if (parent != null && parent.getParent() != null) {
                    Path runs = parent.getParent();
                    if (runs.getParent() != null && "runs".equals(String.valueOf(runs.getFileName()))) {
                        project = stripPickleball(runs.getParent());
                    }
                }
            }
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("seed requires a run id (--run=<id> or --workspace=<dir>).");
            }
            return new Parsed(project.toAbsolutePath().normalize(), runId.trim(), reseed, blank(from));
        }

        private static Path stripPickleball(Path root) {
            if (root != null && ".pickleball".equals(String.valueOf(root.getFileName())) && root.getParent() != null) {
                return root.getParent();
            }
            return root;
        }

        private static String blank(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
