package tools.dscode.common.reporting.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import tools.dscode.control.protocol.InvestigationHandoff;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.PickleballLocalStore;
import tools.dscode.control.protocol.PickleballVersion;
import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.WorkspaceSeed;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Command-line entry point for diagnostic comparison, recovery, and consumer guidance utilities. */
public final class DiagnosticCli {
    private static final String GUIDANCE_ROOT = "META-INF/pickleball/guidance/";
    private static final String AGENT_GUIDE = "AGENT-GUIDE.md";

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
                case "resolve-runvars" -> resolveRunVars(args, out);
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
        Path root = (args.length == 2 ? Path.of(args[1]) : Path.of(PickleballLocalLayout.DIRECTORY))
                .toAbsolutePath()
                .normalize();
        return PickleballLocalStore.exportGuidance(root, out, err);
    }

    private static int discoverHint(String[] args, PrintStream out) throws IOException {
        requireLength(args, 1, 2, "discover-hint [project]");
        Path project = args.length == 2
                ? Path.of(args[1]).toAbsolutePath().normalize()
                : Path.of("").toAbsolutePath().normalize();
        String named = WorkspaceSeed.namedProjectBrowser(project);
        out.println("Seed writes .pickleball/runs/<run-id>/workspace/runvars.properties and workspace/configs. Edit those files with workspace-show, workspace-edit, and workspace-seed before the next start.");
        out.println("Discover and confirm launch with -Dpkb_workspace plus selection keys (pkb_tags, pkb_name, pkb_example, pkb_features, pkb_parallel, pkb_run_id, pkb_agent_id). Isolate does not force pkb_parallel=1.");
        if (named.isBlank()) {
            out.println("Browser: pkb_browser=CHROME_HEADLESS (project names no browser; CHROME_HEADLESS is the only fallback).");
        } else {
            out.println("Browser: pkb_browser=" + named + " (project pkb_browser is kept).");
        }
        out.println("pkb_parallel=auto stays auto in the workspace and is resolved at launch. Hint does not print a recommended pkb_runvars string.");
        out.println("The agent-facing entry is Pickleball Workbench (`hint` / `discover` / `confirm` / `isolate` / `workspace-show` / `workspace-edit` / `workspace-seed`), not a separate DiagnosticCli story. Never supply pkb_run_profile as input.");
        out.println("pkb_overriderunvars is deprecated for Workbench. It still works for a plain mvn test that does not set pkb_workspace.");
        out.println();
        printDryRunResolve(project, out, false);
        out.println("After Discover, read reports/diagnostic-runs/run-catalog.json, then only the relevant run-index.json / summary.json.");
        out.println("NEXT: run discover");
        return 0;
    }

    private static int resolveRunVars(String[] args, PrintStream out) throws IOException {
        requireLength(args, 1, 2, "resolve-runvars [project]");
        Path project = args.length == 2
                ? Path.of(args[1]).toAbsolutePath().normalize()
                : Path.of("").toAbsolutePath().normalize();
        printDryRunResolve(project, out, true);
        return 0;
    }

    private static void printDryRunResolve(
            Path project,
            PrintStream out,
            boolean includeAmbient
    ) throws IOException {
        PKB_props.ResolvedRunVars resolved = tools.dscode.testengine.PickleballRunner.previewLaunch(project, includeAmbient);
        if (project == null && includeAmbient) {
            project = Path.of("");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sealed", resolved.sealed());
        body.put("runProfile", resolved.runProfile());
        body.put("runProfileFingerprint", resolved.fingerprint());
        body.put("runVars", resolved.runVars());
        body.put("provenance", resolved.provenance());
        out.println("Dry-run resolve of the launcher JVM, not the Discover worker (does not start tests, browsers, or Discover):");
        out.println("Do not treat resolve-runvars as the environment Discover will use. Trust the environment on the run record after Discover.");
        out.println(json().writeValueAsString(body));
        out.println("pkb_overriderunvars is deprecated for Workbench. A plain run that does not set pkb_workspace can still use -Dpkb_overriderunvars=<complete compact map>, never -Dpkb_run_profile=.");
        out.println();
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
            raw = json().readValue(jsonText, LinkedHashMap.class);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Investigation JSON is invalid: " + failure.getMessage());
        }
        if (firstBlank(raw, "pickleballVersion")) {
            raw.put("pickleballVersion", PickleballVersion.running(DiagnosticCli.class));
        }
        InvestigationHandoff.EmitResult result = InvestigationHandoff.emit(projectRoot, raw);
        out.println(result.reportPath());
        return 0;
    }

    private static boolean firstBlank(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return !(value instanceof String text) || text.isBlank();
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
        String json = json().writeValueAsString(result);
        if (output == null) {
            out.println(json);
            return;
        }
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.writeString(output, json + System.lineSeparator());
        out.println(output);
    }

    /**
     * Jackson is a Maven-runtime dependency, not shaded into {@code java -jar}.
     * Keep it off the class-init path so {@code guidance} / {@code export-guidance}
     * stay JDK-only.
     */
    private static ObjectMapper json() {
        return JsonHolder.JSON;
    }

    private static final class JsonHolder {
        private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
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
        out.println("  DiagnosticCli resolve-runvars [project]");
        out.println("  DiagnosticCli emit-investigation <investigation-json-or--> <consumer-project-root>");
        out.println("  DiagnosticCli compare-runs <left-run-index> <right-run-index> [output-json]");
        out.println("  DiagnosticCli compare-fingerprints <left.pkbf> <right.pkbf> [output-json]");
        out.println("  DiagnosticCli rebuild <diagnostic-runs-root-or-run-root>");
        out.println("Agents should run export-guidance, hint, discover, confirm, and isolate through PickleballWorkbenchLauncher. Same launcher; only change exec.args.");
    }
}
