package tools.dscode.common.reporting.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import tools.dscode.control.protocol.InvestigationHandoff;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Command-line entry point for diagnostic comparison, recovery, and consumer guidance utilities. */
public final class DiagnosticCli {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String GUIDANCE_ROOT = "META-INF/pickleball/guidance/";
    private static final String GUIDANCE_MANIFEST = "GUIDANCE-MANIFEST.json";
    private static final String PICKLEBALL_DIRECTORY = ".pickleball";
    private static final String PICKLEBALL_IGNORE_RULE = "/.pickleball/";
    private static final String AGENT_GUIDE = "AGENT-GUIDE.md";
    private static final String AGENT_GUIDE_MARKER = "# Pickleball Consumer Agent Guide";
    private static final Pattern PICKLEBALL_JAR = Pattern.compile("^pickleball-(.+)\\.jar$", Pattern.CASE_INSENSITIVE);

    private DiagnosticCli() {
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, System.in);
    }

    public static int run(String[] args, PrintStream out, PrintStream err, InputStream in) {
        try {
            if (args == null || args.length == 0) {
                usage(err);
                return 2;
            }
            return switch (args[0]) {
                case "guidance" -> guidance(args, out);
                case "export-guidance" -> exportGuidance(args, out, err);
                case "discover-hint", "hint" -> discoverHint(args, out);
                case "emit-investigation" -> emitInvestigation(args, out, in);
                case "compare-runs" -> compareRuns(args, out);
                case "compare-fingerprints" -> compareFingerprints(args, out);
                case "rebuild" -> rebuild(args, out);
                case "help", "--help", "-h" -> {
                    usage(out);
                    yield 0;
                }
                default -> {
                    err.println("Unknown diagnostic command: " + args[0]);
                    usage(err);
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            usage(err);
            return 2;
        } catch (Exception e) {
            err.println("Diagnostic command failed: " + e.getMessage());
            return 1;
        }
    }

    private static int guidance(String[] args, PrintStream out) throws IOException {
        requireLength(args, 1, 1, "guidance");
        try (InputStream input = guidanceResource(AGENT_GUIDE)) {
            out.print(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        return 0;
    }

    private static int exportGuidance(String[] args, PrintStream out, PrintStream err) throws IOException {
        requireLength(args, 1, 2, "export-guidance [output-directory]");
        Path root = (args.length == 2 ? Path.of(args[1]) : Path.of(PICKLEBALL_DIRECTORY))
                .toAbsolutePath()
                .normalize();

        if (isPickleballDirectory(root)) {
            ensureGuidanceIgnored(root, out, err);
        }

        List<String> files = guidanceFiles();
        cleanupPreviousGuidance(root, files, err);
        Files.createDirectories(root);

        String version = pickleballVersion();
        for (String relative : files) {
            Path target = currentGuidanceTarget(root, relative);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            if (AGENT_GUIDE.equals(relative)) {
                writeExportedAgentGuide(target, version);
            } else {
                try (InputStream input = guidanceResource(relative)) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        writeGuidanceManifest(root, version, files);

        out.println("Pickleball guidance exported to " + root);
        out.println("Pickleball version: " + version);
        out.println("Manifest: " + root.resolve(GUIDANCE_MANIFEST));
        out.println("Read " + root.resolve(AGENT_GUIDE));
        out.println("NEXT: follow AGENT-GUIDE — run Workbench discover");
        return 0;
    }

    private static int discoverHint(String[] args, PrintStream out) {
        requireLength(args, 1, 2, "discover-hint [project]");
        Path project = args.length == 2
                ? Path.of(args[1]).toAbsolutePath().normalize()
                : Path.of("").toAbsolutePath().normalize();
        AgentDiscoverPlanner.Plan plan = AgentDiscoverPlanner.discover(project, null, null);
        out.println("Recommended complete diagnostic Discover `pkb_runvars` (Workbench honors the project browser ladder; headed Chrome / pretty / @all project defaults do not sneak in):");
        out.println("pkb_runvars=" + plan.runVars());
        out.println();
        out.println("Browser: " + plan.browser().browser() + " (" + plan.browser().reason() + ").");
        out.println("Multi-scenario Discover/Confirm use this high pkb_parallel. Live isolate starts a headless Workbench session; then use execute-step / status / events / stop.");
        out.println("The agent-facing entry is Pickleball Workbench (`hint` / `discover` / `confirm` / `isolate`), not a separate DiagnosticCli story. After Discover, confirm (and isolate/execute-step for live debug) replay the retained pkb_run_profile through pkb_runvars. Never supply pkb_run_profile as input.");
        out.println();
        out.println("After Discover, read reports/diagnostic-runs/run-catalog.json, then only the relevant run-index.json / summary.json.");
        out.println("NEXT: run discover");
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static int emitInvestigation(String[] args, PrintStream out, InputStream in) throws IOException {
        requireLength(args, 3, 3, "emit-investigation <investigation-json-or--> <consumer-project-root>");
        Path projectRoot = Path.of(args[2]).toAbsolutePath().normalize();
        String jsonText = "-".equals(args[1])
                ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                : Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
        if (jsonText == null || jsonText.isBlank()) {
            throw new IllegalArgumentException("Investigation JSON is empty.");
        }
        Map<String, Object> raw;
        try {
            raw = JSON.readValue(jsonText, LinkedHashMap.class);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Investigation JSON is invalid: " + failure.getMessage());
        }
        if (firstBlank(raw, "pickleballVersion")) {
            raw.put("pickleballVersion", pickleballVersion());
        }
        InvestigationHandoff.EmitResult result = InvestigationHandoff.emit(projectRoot, raw);
        out.println(result.reportPath());
        return 0;
    }

    private static boolean firstBlank(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return !(value instanceof String text) || text.isBlank();
    }

    private static void ensureGuidanceIgnored(Path root, PrintStream out, PrintStream err) {
        Path consumerRoot = root.getParent();
        if (consumerRoot == null) return;

        List<String> failures = new ArrayList<>();
        Path consumerIgnore = consumerRoot.resolve(".gitignore");
        if (Files.isRegularFile(consumerIgnore)) {
            try {
                boolean added = ensureIgnoreRule(consumerIgnore, PICKLEBALL_IGNORE_RULE, ".pickleball", false);
                if (added) out.println("Added " + PICKLEBALL_IGNORE_RULE + " to " + consumerIgnore);
                return;
            } catch (IOException e) {
                failures.add(consumerIgnore + ": " + e.getMessage());
            }
        }

        GitLayout git = findGitLayout(consumerRoot);
        if (git == null) {
            if (!failures.isEmpty()) {
                err.println("WARNING: Could not add .pickleball to Git ignore rules; guidance export will continue. "
                        + String.join("; ", failures));
            }
            return;
        }

        Path repositoryIgnore = git.repositoryRoot.resolve(".gitignore");
        if (!repositoryIgnore.equals(consumerIgnore) && Files.isRegularFile(repositoryIgnore)) {
            String relative = git.repositoryRoot.relativize(root).toString().replace('\\', '/');
            String rule = "/" + relative + "/";
            try {
                boolean added = ensureIgnoreRule(repositoryIgnore, rule, relative, false);
                if (added) out.println("Added " + rule + " to " + repositoryIgnore);
                return;
            } catch (IOException e) {
                failures.add(repositoryIgnore + ": " + e.getMessage());
            }
        }

        Path exclude = git.excludeFile();
        String relative = git.repositoryRoot.relativize(root).toString().replace('\\', '/');
        String localRule = "/" + relative + "/";
        try {
            boolean added = ensureIgnoreRule(exclude, localRule, relative, true);
            if (added) out.println("Added " + localRule + " to local Git exclude " + exclude);
            return;
        } catch (IOException e) {
            failures.add(exclude + ": " + e.getMessage());
        }

        err.println("WARNING: Could not add .pickleball to Git ignore rules; guidance export will continue. "
                + String.join("; ", failures));
    }

    private static boolean ensureIgnoreRule(Path file, String rule, String normalizedTarget, boolean create) throws IOException {
        if (!create && !Files.isRegularFile(file)) return false;
        String existing = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        if (isEffectivelyIgnored(existing, normalizedTarget)) return false;

        if (create && file.getParent() != null) Files.createDirectories(file.getParent());
        String prefix = existing.isEmpty() || existing.endsWith("\n") || existing.endsWith("\r")
                ? ""
                : System.lineSeparator();
        Files.writeString(
                file,
                prefix + rule + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
        return true;
    }

    private static boolean isEffectivelyIgnored(String content, String normalizedTarget) {
        boolean ignored = false;
        String target = normalizeIgnorePattern(normalizedTarget);
        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            boolean negated = line.startsWith("!");
            if (negated) line = line.substring(1).trim();
            if (normalizeIgnorePattern(line).equals(target)) {
                ignored = !negated;
            }
        }
        return ignored;
    }

    private static String normalizeIgnorePattern(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static GitLayout findGitLayout(Path start) {
        for (Path current = start; current != null; current = current.getParent()) {
            Path marker = current.resolve(".git");
            try {
                if (Files.isDirectory(marker)) {
                    return new GitLayout(current, marker, resolveCommonGitDirectory(marker));
                }
                if (Files.isRegularFile(marker)) {
                    String line = Files.readString(marker, StandardCharsets.UTF_8).trim();
                    if (!line.toLowerCase(Locale.ROOT).startsWith("gitdir:")) continue;
                    String value = line.substring("gitdir:".length()).trim();
                    Path gitDir = Path.of(value);
                    if (!gitDir.isAbsolute()) gitDir = current.resolve(gitDir);
                    gitDir = gitDir.toAbsolutePath().normalize();
                    return new GitLayout(current, gitDir, resolveCommonGitDirectory(gitDir));
                }
            } catch (Exception ignored) {
                // Git ignore handling is best effort and must never block guidance export.
            }
        }
        return null;
    }

    private static Path resolveCommonGitDirectory(Path gitDir) {
        Path common = gitDir.resolve("commondir");
        if (!Files.isRegularFile(common)) return gitDir;
        try {
            String value = Files.readString(common, StandardCharsets.UTF_8).trim();
            if (value.isEmpty()) return gitDir;
            Path resolved = Path.of(value);
            if (!resolved.isAbsolute()) resolved = gitDir.resolve(resolved);
            return resolved.toAbsolutePath().normalize();
        } catch (IOException ignored) {
            return gitDir;
        }
    }

    private static void cleanupPreviousGuidance(Path root, List<String> currentFiles, PrintStream err) throws IOException {
        if (!Files.isDirectory(root)) return;

        // .pickleball/investigations/ is unmanaged consumer-agent output. export-guidance
        // must never list it in GUIDANCE-MANIFEST.json or delete it during cleanup.

        Path manifest = root.resolve(GUIDANCE_MANIFEST);
        if (Files.isRegularFile(manifest)) {
            Map<String, Object> previous;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = JSON.readValue(manifest.toFile(), LinkedHashMap.class);
                previous = parsed;
            } catch (Exception e) {
                err.println("WARNING: Could not read previous " + GUIDANCE_MANIFEST
                        + "; rebuilding the recognized generated guidance tree. " + e.getMessage());
                cleanupLegacyGuidance(root);
                return;
            }

            Object value = previous.get("files");
            if (value instanceof Collection<?> entries) {
                for (Object entry : entries) {
                    if (entry == null) continue;
                    String relative = String.valueOf(entry).trim();
                    if (relative.isEmpty() || currentFiles.contains(relative)) continue;
                    Path target = previousGuidanceTarget(root, relative, err);
                    if (target == null) continue;
                    // Agent investigation handoffs are unmanaged. Never delete them, even if a
                    // previous manifest incorrectly listed a path under investigations/.
                    if (InvestigationHandoff.isInvestigationsPath(root, target)) continue;
                    Files.deleteIfExists(target);
                }
                removeEmptyDirectories(root);
            }
            return;
        }

        cleanupLegacyGuidance(root);
    }

    private static void cleanupLegacyGuidance(Path root) throws IOException {
        Path guide = root.resolve(AGENT_GUIDE);
        if (!Files.isRegularFile(guide)) return;
        String text = Files.readString(guide, StandardCharsets.UTF_8);
        if (!text.contains(AGENT_GUIDE_MARKER)) return;
        deleteTree(root.resolve("docs"));
        Files.deleteIfExists(guide);
    }

    private static Path previousGuidanceTarget(Path root, String relative, PrintStream err) {
        try {
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root) || target.equals(root)) {
                err.println("WARNING: Ignoring unsafe path in previous guidance manifest: " + relative);
                return null;
            }
            return target;
        } catch (Exception e) {
            err.println("WARNING: Ignoring invalid path in previous guidance manifest: " + relative);
            return null;
        }
    }

    private static Path currentGuidanceTarget(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("Invalid bundled guidance path: " + relative);
        }
        return target;
    }

    private static void removeEmptyDirectories(Path root) throws IOException {
        List<Path> directories;
        try (var paths = Files.walk(root)) {
            directories = paths
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .filter(path -> !InvestigationHandoff.isInvestigationsPath(root, path))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
        for (Path path : directories) {
            try (var children = Files.list(path)) {
                if (children.findAny().isEmpty()) Files.deleteIfExists(path);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        List<Path> entries;
        try (var paths = Files.walk(root)) {
            entries = paths.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : entries) Files.deleteIfExists(path);
    }

    private static void writeExportedAgentGuide(Path target, String version) throws IOException {
        String body;
        try (InputStream input = guidanceResource(AGENT_GUIDE)) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String header = """
                <!-- GENERATED BY PICKLEBALL. DO NOT EDIT THIS FILE IN THE CONSUMER PROJECT. -->

                > **Version-matched generated guidance**  
                > Exported from Pickleball `%s` by Workbench `export-guidance`.  
                > Before relying on `.pickleball`, rerun the consumer bridge export command so this directory matches the currently resolved Pickleball Maven dependency.  
                > If export fails, treat any existing `.pickleball` contents as potentially stale. See `GUIDANCE-MANIFEST.json` for the completed export's version and managed-file list.

                """.formatted(version);
        Files.writeString(target, header + body, StandardCharsets.UTF_8);
    }

    private static void writeGuidanceManifest(Path root, String version, List<String> files) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("generated", true);
        manifest.put("pickleballVersion", version);
        String artifact = pickleballArtifactFile();
        if (!artifact.isBlank()) manifest.put("pickleballArtifact", artifact);
        manifest.put("exportedAt", Instant.now().toString());
        manifest.put("generatedBy", "PickleballWorkbenchLauncher export-guidance");
        manifest.put("files", files);
        manifest.put("staleSafety", "Rerun export-guidance from the currently resolved Pickleball dependency before using this guidance. If export fails, treat the existing directory as potentially stale.");

        Path target = root.resolve(GUIDANCE_MANIFEST);
        Path temporary = root.resolve("." + GUIDANCE_MANIFEST + ".tmp");
        JSON.writeValue(temporary.toFile(), manifest);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String pickleballVersion() {
        Package packageInfo = DiagnosticCli.class.getPackage();
        if (packageInfo != null && packageInfo.getImplementationVersion() != null
                && !packageInfo.getImplementationVersion().isBlank()) {
            return packageInfo.getImplementationVersion().trim();
        }

        try (InputStream input = DiagnosticCli.class.getClassLoader()
                .getResourceAsStream("META-INF/pickleball-build.properties")) {
            if (input != null) {
                Properties properties = new Properties();
                properties.load(input);
                for (String key : List.of("pickleball.version", "version", "build.version", "artifact.version")) {
                    String value = properties.getProperty(key);
                    if (value != null && !value.isBlank()) return value.trim();
                }
            }
        } catch (IOException ignored) {
            // The published artifact normally supplies implementation/build metadata; filename is the final fallback.
        }

        String artifact = pickleballArtifactFile();
        Matcher matcher = PICKLEBALL_JAR.matcher(artifact);
        return matcher.matches() ? matcher.group(1) : "unknown";
    }

    private static String pickleballArtifactFile() {
        try {
            if (DiagnosticCli.class.getProtectionDomain() == null
                    || DiagnosticCli.class.getProtectionDomain().getCodeSource() == null
                    || DiagnosticCli.class.getProtectionDomain().getCodeSource().getLocation() == null) {
                return "";
            }
            URI location = DiagnosticCli.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Path.of(location);
            Path name = path.getFileName();
            return name == null ? "" : name.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isPickleballDirectory(Path root) {
        Path name = root.getFileName();
        return name != null && PICKLEBALL_DIRECTORY.equals(name.toString());
    }

    private static List<String> guidanceFiles() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                guidanceResource("index.txt"),
                StandardCharsets.UTF_8
        ))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        }
    }

    private static InputStream guidanceResource(String relative) throws IOException {
        InputStream input = DiagnosticCli.class.getClassLoader()
                .getResourceAsStream(GUIDANCE_ROOT + relative);
        if (input == null) {
            throw new IOException("Bundled Pickleball guidance is missing: " + relative);
        }
        return input;
    }

    private static int compareRuns(String[] args, PrintStream out) throws IOException {
        requireLength(args, 3, 4, "compare-runs <left-run-index> <right-run-index> [output-json]");
        Path left = Path.of(args[1]);
        Path right = Path.of(args[2]);
        Map<String, Object> comparison = DiagnosticRunComparator.compare(left, right);
        writeResult(comparison, args.length == 4 ? Path.of(args[3]) : null, out);
        return 0;
    }

    private static int compareFingerprints(String[] args, PrintStream out) throws IOException {
        requireLength(args, 3, 4, "compare-fingerprints <left.pkbf> <right.pkbf> [output-json]");
        Path left = Path.of(args[1]);
        Path right = Path.of(args[2]);
        VisualFingerprintComparator.Result comparison = VisualFingerprintComparator.compare(
                VisualFingerprint.fromBytes(Files.readAllBytes(left)),
                VisualFingerprint.fromBytes(Files.readAllBytes(right))
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("leftFingerprint", left.toString());
        result.put("rightFingerprint", right.toString());
        result.put("comparison", comparison.asMap());
        writeResult(result, args.length == 4 ? Path.of(args[3]) : null, out);
        return 0;
    }

    private static int rebuild(String[] args, PrintStream out) throws IOException {
        requireLength(args, 2, 2, "rebuild <diagnostic-runs-root-or-run-root>");
        Path requested = Path.of(args[1]).toAbsolutePath().normalize();
        Path runsRoot;
        List<String> rebuiltRuns = new ArrayList<>();

        if (Files.isRegularFile(requested.resolve("manifest.json"))) {
            DiagnosticIndexRebuilder.rebuildRunIndex(requested);
            rebuiltRuns.add(requested.getFileName().toString());
            runsRoot = requested.getParent();
            if (runsRoot == null) throw new IOException("Run root has no parent: " + requested);
        } else {
            runsRoot = requested;
            if (!Files.isDirectory(runsRoot)) throw new IOException("Diagnostic runs root not found: " + runsRoot);
            try (var paths = Files.list(runsRoot)) {
                for (Path runRoot : paths.filter(Files::isDirectory).sorted().toList()) {
                    if (!Files.isRegularFile(runRoot.resolve("manifest.json"))) continue;
                    DiagnosticIndexRebuilder.rebuildRunIndex(runRoot);
                    rebuiltRuns.add(runRoot.getFileName().toString());
                }
            }
        }

        DiagnosticIndexRebuilder.rebuildRunCatalog(runsRoot);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("runsRoot", runsRoot.toString());
        result.put("rebuiltRuns", rebuiltRuns);
        result.put("runCatalog", runsRoot.resolve("run-catalog.json").toString());
        writeResult(result, null, out);
        return 0;
    }

    private static void writeResult(Map<String, Object> result, Path output, PrintStream out) throws IOException {
        String json = JSON.writeValueAsString(result);
        if (output == null) {
            out.println(json);
            return;
        }
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.writeString(output, json + System.lineSeparator());
        out.println(output);
    }

    private static void requireLength(String[] args, int min, int max, String usage) {
        if (args.length < min || args.length > max) {
            throw new IllegalArgumentException("Usage: DiagnosticCli " + usage);
        }
    }

    private static void usage(PrintStream out) {
        out.println("Pickleball diagnostic utility");
        out.println("The consumer AI-agent entry is Pickleball Workbench (PickleballWorkbenchLauncher), not DiagnosticCli.");
        out.println("  DiagnosticCli guidance");
        out.println("  DiagnosticCli export-guidance [output-directory]");
        out.println("  DiagnosticCli discover-hint [project]");
        out.println("  DiagnosticCli emit-investigation <investigation-json-or--> <consumer-project-root>");
        out.println("  DiagnosticCli compare-runs <left-run-index> <right-run-index> [output-json]");
        out.println("  DiagnosticCli compare-fingerprints <left.pkbf> <right.pkbf> [output-json]");
        out.println("  DiagnosticCli rebuild <diagnostic-runs-root-or-run-root>");
        out.println("Agents should run export-guidance, hint, discover, confirm, and isolate through PickleballWorkbenchLauncher. Same launcher; only change exec.args.");
    }

    private record GitLayout(Path repositoryRoot, Path gitDirectory, Path commonGitDirectory) {
        Path excludeFile() {
            return commonGitDirectory.resolve("info").resolve("exclude");
        }
    }
}
