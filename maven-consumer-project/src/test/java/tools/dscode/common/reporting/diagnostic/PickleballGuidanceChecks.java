package tools.dscode.common.reporting.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PickleballGuidanceChecks {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void dependencyPrintsCanonicalAgentGuide() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int status = DiagnosticCli.run(
                new String[]{"guidance"},
                new PrintStream(output, true, StandardCharsets.UTF_8),
                System.err
        );

        assertEquals(0, status);
        String guide = output.toString(StandardCharsets.UTF_8);
        assertTrue(guide.contains("# Pickleball Consumer Agent Guide"));
        assertTrue(guide.contains("Diagnostic investigation protocol"));
        assertTrue(guide.contains("Controlled diagnostic reruns"));
        assertTrue(guide.contains("Do not put source paths"));
        assertTrue(guide.contains("GUIDANCE-MANIFEST.json"));
        assertTrue(guide.contains("keep terminal logging minimal"));
        assertTrue(guide.contains("older Pickleball release whose exporter predates the manifest lifecycle"));
            assertTrue(guide.contains("Generated Maven consumer reference"));
            assertTrue(guide.contains("maven-consumer-project/"));
            assertTrue(guide.contains("mcp ."));
            assertTrue(guide.contains("workbench_sync"));
            assertTrue(guide.contains("workbench_execute_step"));
            assertTrue(guide.contains("workbench_diagnostic_catalog"));
            assertTrue(guide.contains("workbench_investigation_emit"));
            assertTrue(guide.contains("emit-investigation"));
            assertTrue(guide.contains("pkb_reportingmode=diagnostic"));
            assertTrue(guide.contains("pkb_reportretention=failed"));
            assertTrue(guide.contains("does not end the worker"));
            assertTrue(guide.contains("is not an MCP"));
            assertTrue(guide.contains("afterSequence"));
            assertTrue(guide.contains("docs/pickleball-workbench.md"));
            assertTrue(guide.contains("DiagnosticCli help"));
            assertTrue(guide.contains("**Discover**"));
            assertTrue(guide.contains("**Confirm**"));
            assertTrue(guide.contains("Do not register IDE MCP"));
            assertTrue(guide.contains("already in this session") || guide.contains("already present"));
            assertTrue(guide.contains("run-catalog.json"));
            assertTrue(guide.contains("PickleballWorkbenchLauncher"));
            assertTrue(guide.contains("does not auto-watch") || guide.contains("workbench_sync"));
            assertTrue(guide.contains("pkb_parallel"));
            assertTrue(guide.contains("CHROME_HEADLESS") || guide.contains("browser ladder"));
            assertTrue(guide.contains("pkb_run_profile"));
            String chooser = guide.substring(0, guide.indexOf("Generated guidance lifecycle"));
            assertFalse(chooser.contains("attach.json"));
            assertFalse(chooser.contains("ui ."));
            assertFalse(chooser.contains("This is not a skip of Workbench"));
            assertFalse(chooser.contains("Do not skip Workbench"));
            assertFalse(chooser.contains("MUST use headless Chrome"));
            assertTrue(chooser.contains("one front door") || chooser.contains("Pickleball Workbench is the one front door"));
            String chooserList = guide.substring(
                    guide.indexOf("## Tool chooser"),
                    guide.indexOf("### Live isolation loop")
            );
            assertTrue(chooserList.contains("discover"));
            assertTrue(chooserList.contains("confirm"));
            assertTrue(chooserList.contains("run-catalog.json"));
            assertTrue(chooserList.contains("Do not start a live worker to run the whole suite")
                    || chooserList.contains("Do not start a live worker"));
            assertTrue(chooserList.contains("pkb_run_profile"));
            assertTrue(chooserList.contains("-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher"));
            assertTrue(chooserList.contains("-Dexec.args=discover"));
            assertTrue(chooserList.contains("change") && chooserList.contains("exec.args"));
            assertFalse(chooserList.contains("-Dexec.args=isolate"));
            assertFalse(chooserList.contains("PickleballWorkbenchLauncher isolate"));
            assertFalse(chooserList.contains("**Isolate**"));
            assertTrue(chooserList.contains("already") && chooserList.contains("workbench_*"));
            assertFalse(chooserList.contains("Access probe"));
            assertFalse(chooserList.contains("CLI/Maven-primary"));
            assertTrue(guide.contains("Maintainer-only: pointer-eval harness"));
            assertTrue(guide.contains("@agent-pointer-eval"));
            String liveLoop = guide.substring(
                    guide.indexOf("### Live isolation loop"),
                    guide.indexOf("Generated guidance lifecycle")
            );
            assertTrue(liveLoop.contains("isolate"));
            assertTrue(liveLoop.contains("workbench_*") || liveLoop.contains("workbench_sync"));
            assertTrue(liveLoop.contains("already"));
            assertTrue(liveLoop.contains("optional host wiring") || chooser.contains("optional host wiring"));
            assertFalse(liveLoop.contains("Prefer Workbench CLI `isolate`"));
            String maintainer = guide.substring(guide.indexOf("## Maintainer-only: pointer-eval harness"));
            assertTrue(maintainer.contains("@agent-pointer-eval"));
            assertTrue(guide.indexOf("## Maintainer-only: pointer-eval harness")
                    > guide.indexOf("## When the core Pickleball repository is also present"));
        }

    @Test
    void diagnosticCliHelpListsCommandsAndUnknownCommandsFailClearly() {
        ByteArrayOutputStream emptyOut = new ByteArrayOutputStream();
        ByteArrayOutputStream emptyErr = new ByteArrayOutputStream();
        assertEquals(2, DiagnosticCli.run(
                new String[]{},
                new PrintStream(emptyOut, true, StandardCharsets.UTF_8),
                new PrintStream(emptyErr, true, StandardCharsets.UTF_8)
        ));
        assertEquals("", emptyOut.toString(StandardCharsets.UTF_8));
        assertTrue(emptyErr.toString(StandardCharsets.UTF_8).contains("DiagnosticCli guidance"));

        ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
        ByteArrayOutputStream helpErr = new ByteArrayOutputStream();
        assertEquals(0, DiagnosticCli.run(
                new String[]{"help"},
                new PrintStream(helpOut, true, StandardCharsets.UTF_8),
                new PrintStream(helpErr, true, StandardCharsets.UTF_8)
        ));
        String help = helpOut.toString(StandardCharsets.UTF_8);
        assertEquals("", helpErr.toString(StandardCharsets.UTF_8));
        assertTrue(help.contains("DiagnosticCli guidance"));
        assertTrue(help.contains("DiagnosticCli export-guidance"));
        assertTrue(help.contains("DiagnosticCli discover-hint"));
        assertTrue(help.contains("Pickleball Workbench") || help.contains("PickleballWorkbenchLauncher"));
        assertTrue(help.contains("DiagnosticCli emit-investigation"));
        assertTrue(help.contains("DiagnosticCli compare-runs"));
        assertTrue(help.contains("DiagnosticCli compare-fingerprints"));
        assertTrue(help.contains("DiagnosticCli rebuild"));

        ByteArrayOutputStream dashedOut = new ByteArrayOutputStream();
        assertEquals(0, DiagnosticCli.run(
                new String[]{"--help"},
                new PrintStream(dashedOut, true, StandardCharsets.UTF_8),
                System.err
        ));
        assertEquals(help, dashedOut.toString(StandardCharsets.UTF_8));

        ByteArrayOutputStream shortOut = new ByteArrayOutputStream();
        assertEquals(0, DiagnosticCli.run(
                new String[]{"-h"},
                new PrintStream(shortOut, true, StandardCharsets.UTF_8),
                System.err
        ));
        assertEquals(help, shortOut.toString(StandardCharsets.UTF_8));

        ByteArrayOutputStream unknownOut = new ByteArrayOutputStream();
        ByteArrayOutputStream unknownErr = new ByteArrayOutputStream();
        assertEquals(2, DiagnosticCli.run(
                new String[]{"not-a-command"},
                new PrintStream(unknownOut, true, StandardCharsets.UTF_8),
                new PrintStream(unknownErr, true, StandardCharsets.UTF_8)
        ));
        assertEquals("", unknownOut.toString(StandardCharsets.UTF_8));
        String errors = unknownErr.toString(StandardCharsets.UTF_8);
        assertTrue(errors.contains("Unknown diagnostic command: not-a-command"));
        assertTrue(errors.contains("DiagnosticCli guidance"));
    }

    @Test
    void discoverHintPrintsDiagnosticMvnTestAndRunCatalogNext() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(0, DiagnosticCli.run(
                new String[]{"discover-hint"},
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8)
        ));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("pkb_runvars"));
        assertTrue(text.contains("pkb_browser=CHROME_HEADLESS"));
        assertTrue(text.contains("pkb_parallel=" + tools.dscode.parallelutilities.ParallelCountEstimator.estimate()));
        assertTrue(text.contains("pkb_reportingmode=diagnostic"));
        assertTrue(text.contains("pkb_loglevel=warn"));
        assertTrue(text.contains("pkb_reportretention=failed"));
        assertTrue(text.contains("pkb_run_profile"));
        assertFalse(text.contains("pkb_parallel=80"));
        assertFalse(text.contains("when the project supports it"));
        assertTrue(text.contains("NEXT: run discover"));
        assertTrue(text.contains("Pickleball Workbench") || text.contains("Workbench"));
    }

    @Test
    void dependencyExportsVersionMatchedGuidanceAndManifest() throws Exception {
        Path root = Files.createTempDirectory("pickleball-guidance");
        try {
            ByteArrayOutputStream exportOut = new ByteArrayOutputStream();
            int status = DiagnosticCli.run(
                    new String[]{"export-guidance", root.toString()},
                    new PrintStream(exportOut, true, StandardCharsets.UTF_8),
                    System.err
            );

            assertEquals(0, status);
            String exportText = exportOut.toString(StandardCharsets.UTF_8);
            assertTrue(exportText.contains("NEXT: follow AGENT-GUIDE"));
            assertTrue(exportText.contains("Workbench discover"));
            assertFalse(exportText.contains("mcp ."));
            assertTrue(Files.isRegularFile(root.resolve("AGENT-GUIDE.md")));
            assertTrue(Files.isRegularFile(root.resolve("GUIDANCE-MANIFEST.json")));
            assertTrue(Files.isRegularFile(root.resolve("docs/README.md")));
            assertTrue(Files.isRegularFile(root.resolve("docs/consumer-project.md")));
            assertTrue(Files.isRegularFile(root.resolve("docs/diagnostic-reporting.md")));
            assertTrue(Files.isRegularFile(root.resolve("docs/ai-run-configuration.md")));
            assertTrue(Files.isRegularFile(root.resolve("docs/diagnostic-lineage-metadata.md")));

            assertTrue(Files.isRegularFile(root.resolve("maven-consumer-project/pom.xml")));
            assertTrue(Files.isRegularFile(root.resolve(
                    "maven-consumer-project/src/test/java/com/example/pickleball/PickleballTests.java"
            )));
            assertTrue(Files.isRegularFile(root.resolve(
                    "maven-consumer-project/src/test/java/com/example/pickleball/support/LocalTestSite.java"
            )));
            assertTrue(Files.isRegularFile(root.resolve(
                    "maven-consumer-project/src/test/resources/features/dynamic-steps.feature"
            )));
            assertTrue(Files.isRegularFile(root.resolve(
                    "maven-consumer-project/src/test/resources/features/agent-pointer-eval.feature"
            )));
            assertTrue(Files.isRegularFile(root.resolve(
                    "maven-consumer-project/src/test/resources/calls/service-call-definitions.feature"
            )));
            assertTrue(Files.isRegularFile(root.resolve(
                    "maven-consumer-project/src/test/resources/configs/URL.yaml"
            )));
            assertTrue(Files.isRegularFile(root.resolve(
                    "maven-consumer-project/src/test/resources/data/files/customerPayload.json"
            )));
            assertTrue(Files.isRegularFile(root.resolve(
                    "maven-consumer-project/src/test/resources/site/forms.html"
            )));
            assertTrue(Files.isRegularFile(root.resolve(
                    "maven-consumer-project/src/test/resources/profiles.yaml"
            )));
            assertTrue(Files.isRegularFile(root.resolve(
                    "maven-consumer-project/src/test/resources/profiles_local.yaml"
            )));
            assertTrue(Files.isRegularFile(root.resolve(
                    "maven-consumer-project/src/test/resources/pickleball.properties"
            )));
            assertTrue(Files.isRegularFile(root.resolve(
                    "maven-consumer-project/src/test/resources/pickleball_local.properties"
            )));

            Map<String, Object> manifest = readManifest(root);
            assertEquals(true, manifest.get("generated"));
            String version = String.valueOf(manifest.get("pickleballVersion"));
            assertFalse(version.isBlank());

            List<String> managedFiles = asStringList(manifest.get("files"));
            assertTrue(managedFiles.contains("AGENT-GUIDE.md"));
            assertTrue(managedFiles.contains("docs/configuration.md"));
            assertTrue(managedFiles.contains("maven-consumer-project/pom.xml"));
            assertTrue(managedFiles.contains(
                    "maven-consumer-project/src/test/resources/features/dynamic-steps.feature"
            ));
            assertTrue(managedFiles.contains(
                    "maven-consumer-project/src/test/resources/features/agent-pointer-eval.feature"
            ));
            assertTrue(managedFiles.stream().noneMatch(path -> path.contains("_local2")));
            assertFalse(managedFiles.contains("maven-consumer-project/AGENTS.md"));
            assertFalse(managedFiles.contains("maven-consumer-project/.github/copilot-instructions.md"));
            assertFalse(managedFiles.contains("maven-consumer-project/mvnw"));
            assertFalse(managedFiles.contains(
                    "maven-consumer-project/src/test/java/tools/dscode/common/reporting/diagnostic/PickleballGuidanceChecks.java"
            ));

            String guide = Files.readString(root.resolve("AGENT-GUIDE.md"));
            assertTrue(guide.contains("use the shallowest evidence layer"));
            assertTrue(guide.contains("Exported from Pickleball `" + version + "`"));
            assertTrue(guide.contains("If export fails, treat any existing `.pickleball` contents as potentially stale"));
            assertTrue(guide.contains("keep terminal logging minimal"));
            assertTrue(guide.contains("older Pickleball release whose exporter predates the manifest lifecycle"));
            assertTrue(guide.contains("read-only reference snapshot"));
            assertTrue(guide.contains("mcp ."));
            assertTrue(guide.contains("workbench_diagnostic_catalog"));
            assertTrue(guide.contains("workbench_investigation_emit"));
            assertTrue(guide.contains("pkb_reportretention=failed"));
            assertTrue(guide.contains("Do not copy, modify, or execute files"));
            assertTrue(guide.contains("already in this session") || guide.contains("already present"));
            assertTrue(guide.contains("one front door"));
            assertTrue(guide.contains("PickleballWorkbenchLauncher"));
            assertTrue(guide.contains("Maintainer-only: pointer-eval harness"));
            assertTrue(guide.contains("@agent-pointer-eval"));
            assertTrue(guide.contains("-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher"));
            assertFalse(guide.contains("PickleballWorkbenchLauncher isolate"));

            String consumerProject = Files.readString(root.resolve("docs/consumer-project.md"));
            assertTrue(consumerProject.contains("keep console verbosity low"));
            assertTrue(consumerProject.contains("older Pickleball release whose exporter predates the manifest lifecycle"));
            assertTrue(consumerProject.contains("Version-matched reference snapshot"));
            assertTrue(consumerProject.contains("discover which scenarios fail")
                    || consumerProject.contains("one front door"));
            assertTrue(consumerProject.contains("do not self-register IDE MCP")
                    || consumerProject.contains("Do not register IDE MCP"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void firstManifestExportCleansRecognizedLegacyGuidanceTree() throws Exception {
        Path consumer = Files.createTempDirectory("pickleball-guidance-legacy");
        Path root = consumer.resolve(".pickleball");
        try {
            Files.createDirectories(root.resolve("docs"));
            Files.writeString(
                    root.resolve("AGENT-GUIDE.md"),
                    "# Pickleball Consumer Agent Guide\nlegacy export",
                    StandardCharsets.UTF_8
            );
            Path obsolete = root.resolve("docs/removed-in-newer-version.md");
            Path unmanaged = root.resolve("consumer-note.txt");
            Files.writeString(obsolete, "legacy generated content", StandardCharsets.UTF_8);
            Files.writeString(unmanaged, "keep me", StandardCharsets.UTF_8);

            assertEquals(0, DiagnosticCli.run(
                    new String[]{"export-guidance", root.toString()},
                    System.out,
                    System.err
            ));

            assertFalse(Files.exists(obsolete));
            assertTrue(Files.isRegularFile(unmanaged));
            assertTrue(Files.isRegularFile(root.resolve("GUIDANCE-MANIFEST.json")));
        } finally {
            deleteTree(consumer);
        }
    }

    @Test
    void exportRemovesObsoleteManagedFilesButPreservesUnmanagedFiles() throws Exception {
        Path consumer = Files.createTempDirectory("pickleball-guidance-cleanup");
        Path root = consumer.resolve(".pickleball");
        try {
            assertEquals(0, DiagnosticCli.run(
                    new String[]{"export-guidance", root.toString()},
                    System.out,
                    System.err
            ));

            Path obsolete = root.resolve("docs/obsolete-from-older-version.md");
            Path unmanaged = root.resolve("consumer-note.txt");
            Files.writeString(obsolete, "old generated content", StandardCharsets.UTF_8);
            Files.writeString(unmanaged, "keep me", StandardCharsets.UTF_8);

            Map<String, Object> manifest = readManifest(root);
            List<String> managedFiles = new ArrayList<>(asStringList(manifest.get("files")));
            managedFiles.add("docs/obsolete-from-older-version.md");
            manifest.put("files", managedFiles);
            JSON.writeValue(root.resolve("GUIDANCE-MANIFEST.json").toFile(), manifest);

            assertEquals(0, DiagnosticCli.run(
                    new String[]{"export-guidance", root.toString()},
                    System.out,
                    System.err
            ));

            assertFalse(Files.exists(obsolete));
            assertTrue(Files.isRegularFile(unmanaged));
            assertEquals("keep me", Files.readString(unmanaged, StandardCharsets.UTF_8));
        } finally {
            deleteTree(consumer);
        }
    }

    @Test
    void exportDoesNotDeleteUnmanagedInvestigationsDirectory() throws Exception {
        Path consumer = Files.createTempDirectory("pickleball-guidance-investigations");
        Path root = consumer.resolve(".pickleball");
        try {
            assertEquals(0, DiagnosticCli.run(
                    new String[]{"export-guidance", root.toString()},
                    System.out,
                    System.err
            ));

            Path investigation = root.resolve("investigations/keep-me/investigation.json");
            Path report = root.resolve("investigations/keep-me/report.html");
            Path empty = root.resolve("investigations/empty");
            Files.createDirectories(investigation.getParent());
            Files.createDirectories(empty);
            Files.writeString(investigation, "{\"pkb_investigation_id\":\"keep-me\"}", StandardCharsets.UTF_8);
            Files.writeString(report, "<html>keep</html>", StandardCharsets.UTF_8);

            Map<String, Object> manifest = readManifest(root);
            List<String> managedFiles = new ArrayList<>(asStringList(manifest.get("files")));
            managedFiles.add("investigations/keep-me/investigation.json");
            managedFiles.add("investigations/keep-me/report.html");
            manifest.put("files", managedFiles);
            JSON.writeValue(root.resolve("GUIDANCE-MANIFEST.json").toFile(), manifest);

            assertEquals(0, DiagnosticCli.run(
                    new String[]{"export-guidance", root.toString()},
                    System.out,
                    System.err
            ));

            assertTrue(Files.isRegularFile(investigation));
            assertTrue(Files.isRegularFile(report));
            assertTrue(Files.isDirectory(empty));
            assertEquals("{\"pkb_investigation_id\":\"keep-me\"}", Files.readString(investigation, StandardCharsets.UTF_8));
            Map<String, Object> next = readManifest(root);
            assertFalse(asStringList(next.get("files")).stream().anyMatch(path -> path.contains("investigations/")));
        } finally {
            deleteTree(consumer);
        }
    }

    @Test
    void exportAddsPickleballToExistingConsumerGitignoreWithoutDuplicates() throws Exception {
        Path consumer = Files.createTempDirectory("pickleball-guidance-ignore");
        Path ignore = consumer.resolve(".gitignore");
        Path root = consumer.resolve(".pickleball");
        try {
            Files.writeString(ignore, "target/" + System.lineSeparator(), StandardCharsets.UTF_8);

            assertEquals(0, DiagnosticCli.run(
                    new String[]{"export-guidance", root.toString()},
                    System.out,
                    System.err
            ));
            assertEquals(0, DiagnosticCli.run(
                    new String[]{"export-guidance", root.toString()},
                    System.out,
                    System.err
            ));

            long occurrences = Files.readAllLines(ignore, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter("/.pickleball/"::equals)
                    .count();
            assertEquals(1L, occurrences);
            assertTrue(Files.isRegularFile(root.resolve("GUIDANCE-MANIFEST.json")));
        } finally {
            deleteTree(consumer);
        }
    }

    @Test
    void ignoreUpdateFailureDoesNotBlockGuidanceExport() throws Exception {
        Path consumer = Files.createTempDirectory("pickleball-guidance-ignore-failure");
        Path root = consumer.resolve(".pickleball");
        try {
            Files.createDirectory(consumer.resolve(".gitignore"));
            Files.createDirectories(consumer.resolve(".git/info/exclude"));

            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int status = DiagnosticCli.run(
                    new String[]{"export-guidance", root.toString()},
                    System.out,
                    new PrintStream(errors, true, StandardCharsets.UTF_8)
            );

            assertEquals(0, status);
            assertTrue(Files.isRegularFile(root.resolve("AGENT-GUIDE.md")));
            assertTrue(Files.isRegularFile(root.resolve("GUIDANCE-MANIFEST.json")));
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("guidance export will continue"));
        } finally {
            deleteTree(consumer);
        }
    }

    @Test
    void exportFallsBackToLocalGitExcludeWhenNoGitignoreExists() throws Exception {
        Path consumer = Files.createTempDirectory("pickleball-guidance-exclude");
        Path exclude = consumer.resolve(".git/info/exclude");
        Path root = consumer.resolve(".pickleball");
        try {
            Files.createDirectories(exclude.getParent());
            Files.writeString(exclude, "# local excludes" + System.lineSeparator(), StandardCharsets.UTF_8);

            assertEquals(0, DiagnosticCli.run(
                    new String[]{"export-guidance", root.toString()},
                    System.out,
                    System.err
            ));

            assertTrue(Files.readAllLines(exclude, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .anyMatch("/.pickleball/"::equals));
            assertTrue(Files.isRegularFile(root.resolve("AGENT-GUIDE.md")));
        } finally {
            deleteTree(consumer);
        }
    }

    private static Map<String, Object> readManifest(Path root) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(
                root.resolve("GUIDANCE-MANIFEST.json").toFile(),
                LinkedHashMap.class
        );
        return manifest;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
