package tools.dscode.common.reporting.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Command-line entry point for diagnostic comparison and recovery utilities. */
public final class DiagnosticCli {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private DiagnosticCli() {
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args == null || args.length == 0) {
                usage(err);
                return 2;
            }
            return switch (args[0]) {
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
        out.println("  DiagnosticCli compare-runs <left-run-index> <right-run-index> [output-json]");
        out.println("  DiagnosticCli compare-fingerprints <left.pkbf> <right.pkbf> [output-json]");
        out.println("  DiagnosticCli rebuild <diagnostic-runs-root-or-run-root>");
    }
}
