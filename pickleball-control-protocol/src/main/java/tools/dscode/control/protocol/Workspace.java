package tools.dscode.control.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One run's resolved configuration.
 *
 * <p>Layout: {@code .pickleball/runs/<runId>/workspace/runvars.properties} and
 * {@code workspace/configs}. Workbench, agents, and the test JVM all use this
 * class. There is no second reader or writer.</p>
 */
public final class Workspace {
    public static final String PROPERTY = "pkb_workspace";
    public static final String DIRECTORY = "workspace";
    public static final String RUNVARS_FILE = "runvars.properties";
    public static final String CONFIGS_DIRECTORY = "configs";
    public static final String FINGERPRINT_FIELD = "workspaceFingerprint";
    public static final String CACHE_DIRECTORY = "resolve-cache";
    public static final String PARALLEL_AUTO = "auto";

    public static final List<String> CONTEXT_KEYS = List.of(
            "pkb_glue",
            "pkb_features",
            "pkb_datapath",
            "pkb_callpath",
            "pkb_componentpath",
            "pkb_configpath"
    );

    /** Passed on the launch command. They override the workspace for that process only. */
    public static final List<String> SELECTION_KEYS = List.of(
            "pkb_tags",
            "pkb_name",
            "pkb_example",
            "pkb_features",
            "pkb_parallel",
            "pkb_run_id",
            "pkb_agent_id"
    );

    /**
     * Coordination and CLI projection. Seed strips these so a capture does not
     * freeze the seed process. Project selection (tags, name, example, features,
     * parallel) stays; a launch {@code -D} overrides it without rewriting the file.
     */
    public static final Set<String> CAPTURE_EXCLUDED_KEYS = Set.of(
            "pkb_run_id",
            "pkb_agent_id",
            "pkb_run_group",
            "pkb_run_sequence",
            "pkb_run_who",
            "pkb_run_why",
            "pkb_investigation_id",
            "pkb_run_purpose",
            "pkb_parent_run_id",
            "pkb_baseline_run_id",
            "pkb_changed_variables",
            "pkb_cucumber_cli_args",
            "pkb_cucumber_cli_feature_selectors",
            "pkb_options",
            "pkb_run_profile",
            "pkb_profile",
            "pkb_runvars",
            "pkb_overriderunvars"
    );

    private static final String PROTECTED_PREFIX = "${protected:";
    private static final String PROTECTED_SUFFIX = "}";
    private static final Set<String> PROTECTED_PROPERTIES = Set.of(
            "pkb_rp_api_key",
            "pkb_rp_uuid",
            "pkb_rp_oauth_password",
            "pkb_rp_oauth_client_secret",
            "pkb_rp_keystore_password",
            "pkb_rp_truststore_password",
            "pkb_rp_http_proxy_password",
            "pkb_driver_download_proxy"
    );
    private static final Set<String> SENSITIVE_FRAGMENTS = Set.of(
            "password", "passwd", "secret", "token", "apikey", "api_key",
            "accesskey", "privatekey", "credential", "authorization", "cookie"
    );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?:^|[\\s\"'])([A-Za-z0-9_.-]*(?:password|passwd|secret|token|apikey|api_key|accesskey|privatekey|credential|authorization|cookie)[A-Za-z0-9_.-]*)\\s*[:=]\\s*([^\\s#]+)"
    );

    private Workspace() {
    }

    public static Path directory(Path project, String runId) {
        return RunView.runDirectory(project, runId).resolve(DIRECTORY);
    }

    public static Path runvarsFile(Path workspace) {
        return workspace.resolve(RUNVARS_FILE);
    }

    public static Path configsDirectory(Path workspace) {
        return workspace.resolve(CONFIGS_DIRECTORY);
    }

    public static boolean exists(Path workspace) {
        return workspace != null && Files.isRegularFile(runvarsFile(workspace));
    }

    public static Map<String, String> readRunVars(Path workspace) {
        Path file = runvarsFile(workspace);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Workspace has no " + RUNVARS_FILE + " at " + workspace);
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Could not read " + file + ": " + failure.getMessage(), failure);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key == null || key.isBlank()) continue;
            values.put(normalizeKey(key), properties.getProperty(key));
        }
        return values;
    }

    /**
     * Writes runvars atomically. Sensitive values are stored as protected
     * references. {@code pkb_parallel=auto} is kept as {@code auto}.
     * Keys in {@code keepLiteral} stay as the captured value. A sensitive key
     * already stored as a literal stays a literal on later writes.
     */
    public static void writeRunVars(Path workspace, Map<String, String> runVars) {
        writeRunVars(workspace, runVars, Set.of());
    }

    public static void writeRunVars(Path workspace, Map<String, String> runVars, Set<String> keepLiteral) {
        Set<String> keep = new LinkedHashSet<>();
        if (keepLiteral != null) {
            for (String key : keepLiteral) {
                if (key != null && !key.isBlank()) keep.add(normalizeKey(key));
            }
        }
        Path file = runvarsFile(workspace);
        if (Files.isRegularFile(file)) {
            readRunVars(workspace).forEach((key, value) -> {
                if (isSensitiveKey(key) && value != null && !value.isBlank() && protectedKey(value) == null) {
                    keep.add(normalizeKey(key));
                }
            });
        }
        Map<String, String> stored = protect(runVars, keep);
        StringBuilder body = new StringBuilder();
        new TreeMap<>(stored).forEach((key, value) -> {
            body.append(escape(key)).append('=').append(escape(value == null ? "" : value)).append('\n');
        });
        atomicWrite(file, body.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static Map<String, String> protect(Map<String, String> runVars) {
        return protect(runVars, Set.of());
    }

    public static Map<String, String> protect(Map<String, String> runVars, Set<String> keepLiteral) {
        Map<String, String> stored = new LinkedHashMap<>();
        if (runVars == null) return stored;
        runVars.forEach((key, value) -> {
            if (key == null || key.isBlank()) return;
            String normalized = normalizeKey(key);
            if (CAPTURE_EXCLUDED_KEYS.contains(normalized) || normalized.startsWith("pkb_runvars.")
                    || normalized.startsWith("pkb_overriderunvars.")
                    || normalized.startsWith("pkb_run_profile.")) {
                return;
            }
            String text = value == null ? "" : value;
            boolean keep = keepLiteral != null && keepLiteral.contains(normalized);
            if (isSensitiveKey(normalized) && !text.isBlank() && protectedKey(text) == null && !keep) {
                stored.put(normalized, protectedReference(normalized));
            } else {
                stored.put(normalized, text);
            }
        });
        return stored;
    }

    public static void ensureContextKeys(Map<String, String> runVars) {
        if (runVars == null) return;
        for (String key : CONTEXT_KEYS) {
            runVars.putIfAbsent(key, "");
        }
    }

    public static void requireContext(Map<String, String> runVars) {
        List<String> missing = new ArrayList<>();
        for (String key : CONTEXT_KEYS) {
            if (runVars == null || !runVars.containsKey(key)) missing.add(key);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Workspace is missing required context key(s) " + missing
                            + ". Seed writes pkb_glue, pkb_features, pkb_datapath, pkb_callpath, "
                            + "pkb_componentpath, and pkb_configpath. Blank values are allowed; omitted keys are not."
            );
        }
    }

    /**
     * Launch read of a workspace copy. Selection keys may override the file for this
     * process. Hooks, property files, and profiles are not applied again.
     */
    public static LinkedHashMap<String, String> loadForLaunch(Path workspace, Map<String, String> selectionOverrides) {
        LinkedHashMap<String, String> loaded = new LinkedHashMap<>(readRunVars(workspace));
        requireContext(loaded);
        if (selectionOverrides == null) return loaded;
        for (String key : SELECTION_KEYS) {
            String override = selectionOverrides.get(key);
            if (override != null && !override.isBlank()) loaded.put(key, override.trim());
        }
        return loaded;
    }

    public static void copyConfigs(Path source, Path workspace) {
        Path destination = configsDirectory(workspace);
        try {
            if (Files.isDirectory(destination)) {
                deleteTree(destination);
            }
            Files.createDirectories(destination);
            if (source == null || !Files.isDirectory(source)) return;
            Path sourceRoot = source.toAbsolutePath().normalize();
            Path destRoot = destination.toAbsolutePath().normalize();
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                for (Path path : walk.toList()) {
                    Path relative = sourceRoot.relativize(path);
                    if (relative.toString().isEmpty()) continue;
                    Path target = destRoot.resolve(relative.toString()).normalize();
                    if (!target.startsWith(destRoot)) continue;
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target);
                    } else if (Files.isRegularFile(path)) {
                        if (target.getParent() != null) Files.createDirectories(target.getParent());
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not copy configs into " + workspace + ": " + failure.getMessage(), failure);
        }
    }

    public static void copyWorkspace(Path from, Path to) {
        if (!exists(from)) {
            throw new IllegalStateException("No workspace to copy at " + from);
        }
        if (exists(to)) {
            throw new IllegalStateException("Workspace already exists at " + to + ". Use --reseed to replace it.");
        }
        writeRunVars(to, readRunVars(from));
        copyConfigs(configsDirectory(from), to);
    }

    public static void replaceWorkspace(Path from, Path to) {
        deleteIfPresent(to);
        copyWorkspace(from, to);
    }

    /** SHA-256 of runvars bytes plus every configs file, in sorted path order. */
    public static String fingerprint(Path workspace) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path runvars = runvarsFile(workspace);
            digest.update("runvars\n".getBytes(StandardCharsets.UTF_8));
            if (Files.isRegularFile(runvars)) {
                digest.update(Files.readAllBytes(runvars));
            }
            digest.update(new byte[]{0});
            Path configs = configsDirectory(workspace);
            if (Files.isDirectory(configs)) {
                try (Stream<Path> walk = Files.walk(configs)) {
                    List<Path> files = walk.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(path -> configs.relativize(path).toString().replace('\\', '/')))
                            .toList();
                    for (Path file : files) {
                        String relative = configs.relativize(file).toString().replace('\\', '/');
                        digest.update(relative.getBytes(StandardCharsets.UTF_8));
                        digest.update(new byte[]{0});
                        digest.update(Files.readAllBytes(file));
                        digest.update(new byte[]{0});
                    }
                }
            }
            return hex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Could not fingerprint " + workspace + ": " + failure.getMessage(), failure);
        }
    }

    public record DiskStamp(String fingerprint, Map<String, Long> modifiedMillis) {
    }

    public static DiskStamp stamp(Path workspace) {
        Map<String, Long> times = new TreeMap<>();
        try {
            Path runvars = runvarsFile(workspace);
            if (Files.isRegularFile(runvars)) {
                times.put(RUNVARS_FILE, Files.getLastModifiedTime(runvars).toMillis());
            }
            Path configs = configsDirectory(workspace);
            if (Files.isDirectory(configs)) {
                try (Stream<Path> walk = Files.walk(configs)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        String relative = CONFIGS_DIRECTORY + "/" + configs.relativize(file).toString().replace('\\', '/');
                        times.put(relative, Files.getLastModifiedTime(file).toMillis());
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not stamp " + workspace + ": " + failure.getMessage(), failure);
        }
        return new DiskStamp(fingerprint(workspace), Map.copyOf(times));
    }

    /** True when mtime or content hash differs from an earlier stamp. */
    public static boolean changedSince(Path workspace, DiskStamp earlier) {
        if (earlier == null) return false;
        DiskStamp now = stamp(workspace);
        return !now.fingerprint().equals(earlier.fingerprint()) || !now.modifiedMillis().equals(earlier.modifiedMillis());
    }

    public static List<String> literalSecretWarnings(Path configs) {
        List<String> warnings = new ArrayList<>();
        if (configs == null || !Files.isDirectory(configs)) return warnings;
        try (Stream<Path> walk = Files.walk(configs)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String text;
                try {
                    text = Files.readString(file);
                } catch (IOException failure) {
                    continue;
                }
                Matcher matcher = SECRET_ASSIGNMENT.matcher(text);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    String value = matcher.group(2);
                    if (value == null || value.isBlank()) continue;
                    String trimmed = value.trim();
                    if (trimmed.startsWith(PROTECTED_PREFIX) || trimmed.startsWith("<")
                            || "<redacted>".equalsIgnoreCase(trimmed) || "null".equalsIgnoreCase(trimmed)) {
                        continue;
                    }
                    warnings.add("WARN: literal secret in configs copy at " + file.getFileName() + " key " + key
                            + ". Runvars store protected references; the configs copy is plain and was not rewritten.");
                }
            }
        } catch (IOException failure) {
            return warnings;
        }
        return warnings;
    }

    /**
     * One {@code --runvar} parser. Selection keys update {@code selection} only.
     * Other keys are written into the workspace when it already exists.
     */
    public static void applyLaunchEdits(
            Path workspace,
            Map<String, String> selection,
            List<String> raw,
            Appendable warnings
    ) {
        if (raw == null || raw.isEmpty()) return;
        boolean present = workspace != null && exists(workspace);
        Map<String, String> values = present ? new LinkedHashMap<>(readRunVars(workspace)) : new LinkedHashMap<>();
        Map<String, String> original = Map.copyOf(values);
        for (String item : raw) {
            if (item == null || item.isBlank()) continue;
            int equals = item.indexOf('=');
            if (equals <= 0) {
                appendWarning(warnings, "WARNING: --runvar ignored, expected key=value: " + item);
                continue;
            }
            String key = item.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = item.substring(equals + 1).trim();
            if (!key.startsWith("pkb_")) key = "pkb_" + key;
            String existing = selection != null && selection.get(key) != null ? selection.get(key) : values.get(key);
            if (existing != null && !existing.equals(value)) {
                appendWarning(warnings, "WARNING: --runvar " + key + " replaces "
                        + OutputFormatter.shown(key, existing) + " with " + OutputFormatter.shown(key, value));
                if (SecretRedactor.leaks(key, existing) || SecretRedactor.leaks(key, value)) {
                    appendWarning(warnings, "fix: " + SecretRedactor.HINT);
                }
            }
            if (SELECTION_KEYS.contains(key)) {
                if (selection != null) selection.put(key, value);
                continue;
            }
            values.put(key, value);
        }
        if (!present) return;
        for (String key : SELECTION_KEYS) {
            if (original.containsKey(key)) values.put(key, original.get(key));
            else values.remove(key);
        }
        if (!values.equals(original)) writeRunVars(workspace, values);
    }

    public static String shown(String value) {
        if (value == null || value.isEmpty()) return "\"\"";
        return value;
    }

    private static void appendWarning(Appendable warnings, String message) {
        if (warnings == null) return;
        try {
            warnings.append(message).append(System.lineSeparator());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static Map<String, String> launchProperties(Path workspace, Map<String, String> selection) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(PROPERTY, workspace.toAbsolutePath().normalize().toString());
        if (selection != null) {
            for (String key : SELECTION_KEYS) {
                String value = selection.get(key);
                if (value != null && !value.isBlank()) properties.put(key, value);
            }
        }
        return properties;
    }

    public static List<String> launchArguments(Path workspace, Map<String, String> selection) {
        List<String> arguments = new ArrayList<>();
        launchProperties(workspace, selection).forEach((key, value) -> arguments.add("-D" + key + "=" + value));
        return List.copyOf(arguments);
    }

    public static void writeFingerprint(Path recordFile, String fingerprint) {
        String field = "\"" + FINGERPRINT_FIELD + "\"";
        String assignment = field + " : \"" + fingerprint + "\"";
        try {
            String text = Files.isRegularFile(recordFile) ? Files.readString(recordFile) : "";
            if (!text.isEmpty() && text.contains(assignment)) return;
            if (text.isEmpty()) text = "{\n}\n";
            String updated;
            if (text.contains(field)) {
                updated = text.replaceAll(
                        "\"" + FINGERPRINT_FIELD + "\"\\s*:\\s*\"[^\"]*\"",
                        Matcher.quoteReplacement(assignment)
                );
            } else {
                int close = text.lastIndexOf('}');
                if (close < 0) {
                    updated = "{\n  " + assignment + "\n}\n";
                } else {
                    String prefix = text.substring(0, close).stripTrailing();
                    boolean needsComma = !prefix.endsWith("{") && !prefix.isBlank();
                    String body = prefix;
                    if (needsComma && !body.endsWith(",")) body = body + ",";
                    updated = body + "\n  " + assignment + "\n}\n";
                }
            }
            atomicWrite(recordFile, updated.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write " + FINGERPRINT_FIELD + " to " + recordFile, failure);
        }
    }

    public static String cacheKey(
            Map<String, byte[]> propertyFiles,
            long testClassesModifiedMillis,
            String classpathFingerprint,
            Map<String, String> inputs
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (propertyFiles != null) {
                new TreeMap<>(propertyFiles).forEach((name, bytes) -> {
                    digest.update(name.getBytes(StandardCharsets.UTF_8));
                    digest.update(new byte[]{0});
                    if (bytes != null) digest.update(bytes);
                    digest.update(new byte[]{0});
                });
            }
            digest.update(Long.toString(testClassesModifiedMillis).getBytes(StandardCharsets.UTF_8));
            digest.update(new byte[]{0});
            digest.update((classpathFingerprint == null ? "" : classpathFingerprint).getBytes(StandardCharsets.UTF_8));
            digest.update(new byte[]{0});
            if (inputs != null) {
                new TreeMap<>(inputs).forEach((key, value) -> {
                    digest.update(key.getBytes(StandardCharsets.UTF_8));
                    digest.update(new byte[]{0});
                    digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
                    digest.update(new byte[]{0});
                });
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static Path cacheDirectory(Path project, String key) {
        return PickleballLocalLayout.root(project).resolve(CACHE_DIRECTORY).resolve(key);
    }

    public static boolean cacheHit(Path project, String key) {
        return exists(cacheDirectory(project, key));
    }

    public static void storeCache(Path project, String key, Path workspace) {
        Path cache = cacheDirectory(project, key);
        deleteIfPresent(cache);
        copyWorkspace(workspace, cache);
    }

    public static void restoreCache(Path project, String key, Path workspace) {
        if (exists(workspace)) {
            throw new IllegalStateException("Workspace already exists at " + workspace + ". Use --reseed to replace it.");
        }
        copyWorkspace(cacheDirectory(project, key), workspace);
    }

    public static Set<String> protectedPropertyNames() {
        return Set.copyOf(PROTECTED_PROPERTIES);
    }

    public static boolean isSensitiveKey(String key) {
        String normalized = normalizeKey(key);
        return PROTECTED_PROPERTIES.contains(normalized)
                || SENSITIVE_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    public static String protectedReference(String key) {
        return PROTECTED_PREFIX + normalizeKey(key) + PROTECTED_SUFFIX;
    }

    public static String protectedKey(String value) {
        if (value == null || !value.startsWith(PROTECTED_PREFIX) || !value.endsWith(PROTECTED_SUFFIX)) return null;
        String key = value.substring(PROTECTED_PREFIX.length(), value.length() - PROTECTED_SUFFIX.length()).trim();
        return key.isEmpty() ? null : normalizeKey(key);
    }

    public static String normalizeKey(String key) {
        if (key == null) return "";
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("rp.")) {
            return "pkb_rp_" + normalized.substring(3).replace('.', '_');
        }
        return normalized;
    }

    public static void atomicWrite(Path target, byte[] bytes) {
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write " + target + ": " + failure.getMessage(), failure);
        }
    }

    public static void atomicWriteString(Path target, String text) {
        atomicWrite(target, (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteIfPresent(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            deleteTree(root);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not replace " + root + ": " + failure.getMessage(), failure);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String hex(byte[] hash) {
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format("%02x", b));
        return out.toString();
    }
}
