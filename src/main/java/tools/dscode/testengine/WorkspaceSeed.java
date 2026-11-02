package tools.dscode.testengine;

import tools.dscode.common.resources.PathLists;
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
        if (reseed) {
            try {
                Files.deleteIfExists(Workspace.valueSourcesFile(workspace));
            } catch (IOException failure) {
                throw new IllegalStateException("Could not clear value sources at " + workspace, failure);
            }
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
            Map<String, String> classified = runner.valueSources();
            runner.values().forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null) return;
                String normalized = Workspace.normalizeKey(key);
                boolean hook = "hook".equals(classified.get(key)) || "hook".equals(classified.get(normalized));
                if (PKB_props.isRunVariableKey(key) || hook) values.put(key, value);
            });
        }
        values.keySet().removeIf(Workspace.CAPTURE_EXCLUDED_KEYS::contains);
        Workspace.ensureContextKeys(values);
        String browser = values.get(PKB_props.PKB_BROWSER);
        values.put(PKB_props.PKB_BROWSER, browserOrHeadless(browser));
        String configured = values.get(PKB_props.PKB_CONFIG_PATH);
        if (configured != null && configured.indexOf(',') >= 0) {
            copyConfigDirectories(PathLists.split(configured), workspace);
        } else {
            Path source = configured == null || configured.isBlank()
                    ? null
                    : ResourceLocations.existingDirectory(configured, ResourceLocations.workingDirectory());
            Workspace.copyConfigs(source, workspace);
        }
        Set<String> keep = runnerComputedSecretKeys(values);
        Workspace.writeRunVars(workspace, values, keep);
        writeSources(workspace, runner, values, browser);
        List<String> warnings = new ArrayList<>(new java.util.LinkedHashSet<>(
                java.util.stream.Stream.concat(
                        Workspace.literalSecretWarnings(Workspace.configsDirectory(workspace)).stream(),
                        runnerComputedSecretWarnings(values).stream()
                ).toList()
        ));
        return warnings;
    }

    /**
     * First directory replaces the workspace configs tree. Later directories
     * overlay files and do not delete what the earlier directories wrote.
     * A classpath directory inside a jar has no filesystem path and is skipped;
     * runtime config load still merges it.
     */
    private static void copyConfigDirectories(List<String> paths, Path workspace) {
        boolean copied = false;
        if (paths != null) {
            for (String part : paths) {
                Path source = ResourceLocations.existingDirectory(part, ResourceLocations.workingDirectory());
                if (source == null) continue;
                if (!copied) {
                    Workspace.copyConfigs(source, workspace);
                    copied = true;
                } else {
                    Workspace.overlayConfigs(source, workspace);
                }
            }
        }
        if (!copied) Workspace.copyConfigs(null, workspace);
    }

    /**
     * Retained profile with unresolvable {@code ${protected:key}} assignments removed.
     * A reference whose key is present in project properties stays. The workspace file
     * is unchanged; this is only the string agents replay.
     */
    public static String replayableProfile(String profile, Path project) {
        if (profile == null || profile.isBlank()) return profile == null ? "" : profile;
        java.util.LinkedHashMap<String, String> parsed;
        try {
            parsed = PKB_props.parseAssignments(profile);
        } catch (RuntimeException failure) {
            return profile;
        }
        Map<String, String> files = ProjectPropertySources.readWorkbench(project == null ? Path.of("") : project);
        parsed.entrySet().removeIf(entry -> {
            String key = Workspace.protectedKey(entry.getValue());
            if (key == null) return false;
            String fromFile = files.get(key);
            return fromFile == null || fromFile.isBlank();
        });
        return PKB_props.serializeRunVars(parsed);
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
     * Keys whose source is {@code hook}. A hook value needs a reseed. Property
     * files, defaults, and CLI overrides are not marked.
     */
    public static Set<String> computedKeys(Path project, Map<String, String> values) {
        return computedKeys(project, null, values);
    }

    public static Set<String> computedKeys(Path project, Path workspace, Map<String, String> values) {
        Set<String> computed = new LinkedHashSet<>();
        sources(project, workspace, values).forEach((key, source) -> {
            if ("hook".equals(source)) computed.add(key);
        });
        return computed;
    }

    /**
     * One source for every stored value: {@code hook}, {@code properties},
     * {@code default}, or {@code cli/override}. A sidecar written at seed wins.
     * An older workspace is classified from the key and the project files.
     */
    public static Map<String, String> sources(Path project, Path workspace, Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        if (values == null || values.isEmpty()) return out;
        Map<String, String> stored = workspace == null ? Map.of() : Workspace.readValueSources(workspace);
        boolean sidecar = workspace != null && Files.isRegularFile(Workspace.valueSourcesFile(workspace));
        Map<String, String> files = Workspace.projectFileValues(project);
        values.forEach((rawKey, rawValue) -> {
            if (rawKey == null || rawKey.isBlank()) return;
            String key = Workspace.normalizeKey(rawKey);
            String text = rawValue == null ? "" : rawValue;
            if (sidecar && stored.containsKey(key)) {
                out.put(key, canonicalSource(stored.get(key)));
                return;
            }
            out.put(key, inferSource(key, text, files));
        });
        return out;
    }

    private static void writeSources(
            Path workspace,
            PickleballRunner runner,
            Map<String, String> values,
            String browserBeforeFallback
    ) {
        Map<String, String> classified = new LinkedHashMap<>();
        if (runner != null) {
            runner.valueSources().forEach((key, source) -> {
                if (key == null || key.isBlank() || source == null) return;
                classified.put(Workspace.normalizeKey(key), source);
            });
        }
        Map<String, String> files = Workspace.projectFileValues(Path.of(""));
        Map<String, String> sources = new LinkedHashMap<>();
        values.forEach((rawKey, rawValue) -> {
            if (rawKey == null || rawKey.isBlank()) return;
            String key = Workspace.normalizeKey(rawKey);
            String text = rawValue == null ? "" : rawValue;
            String source = classified.get(key);
            sources.put(key, source == null ? inferSource(key, text, files) : source);
        });
        if (browserBeforeFallback == null || browserBeforeFallback.isBlank()) {
            sources.put(Workspace.normalizeKey(PKB_props.PKB_BROWSER), "default");
        }
        Workspace.writeValueSources(workspace, sources);
    }

    private static String canonicalSource(String source) {
        if (source == null) return "default";
        String token = source.trim();
        return switch (token) {
            case "hook", "properties", "default", "cli/override" -> token;
            default -> "default";
        };
    }

    private static String inferSource(String key, String stored, Map<String, String> files) {
        if (hookComputed(key) || runnerSuppliedSecret(key, stored)) return "hook";
        if (files.containsKey(key)) {
            String filed = files.get(key);
            return stored.equals(filed == null ? "" : filed) ? "properties" : "cli/override";
        }
        return "default";
    }

    /** Allowlist used only when a workspace has no value-sources sidecar. */
    private static final Set<String> HOOK_COMPUTED_KEYS = Set.of(
            "user", "user.name", "pkb_user",
            "machine", "hostname", "os.name", "pkb_machine",
            "java.vendor", "javavendor", "jvm.vendor", "pkb_java_vendor",
            "time", "capturedat", "pkb_time"
    );

    private static boolean hookComputed(String key) {
        return HOOK_COMPUTED_KEYS.contains(key);
    }

    private static boolean runnerSuppliedSecret(String key, String stored) {
        return Workspace.isSensitiveKey(key)
                && stored != null
                && !stored.isBlank()
                && Workspace.protectedKey(stored) == null;
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
