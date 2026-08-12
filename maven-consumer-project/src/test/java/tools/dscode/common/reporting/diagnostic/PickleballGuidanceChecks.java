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
    }

    @Test
    void dependencyExportsVersionMatchedGuidanceAndManifest() throws Exception {
        Path root = Files.createTempDirectory("pickleball-guidance");
        try {
            int status = DiagnosticCli.run(
                    new String[]{"export-guidance", root.toString()},
                    System.out,
                    System.err
            );

            assertEquals(0, status);
            assertTrue(Files.isRegularFile(root.resolve("AGENT-GUIDE.md")));
            assertTrue(Files.isRegularFile(root.resolve("GUIDANCE-MANIFEST.json")));
            assertTrue(Files.isRegularFile(root.resolve("docs/README.md")));
            assertTrue(Files.isRegularFile(root.resolve("docs/consumer-project.md")));
            assertTrue(Files.isRegularFile(root.resolve("docs/diagnostic-reporting.md")));
            assertTrue(Files.isRegularFile(root.resolve("docs/ai-run-configuration.md")));
            assertTrue(Files.isRegularFile(root.resolve("docs/diagnostic-lineage-metadata.md")));

            Map<String, Object> manifest = readManifest(root);
            assertEquals(true, manifest.get("generated"));
            String version = String.valueOf(manifest.get("pickleballVersion"));
            assertFalse(version.isBlank());
            assertTrue(asStringList(manifest.get("files")).contains("AGENT-GUIDE.md"));
            assertTrue(asStringList(manifest.get("files")).contains("docs/configuration.md"));

            String guide = Files.readString(root.resolve("AGENT-GUIDE.md"));
            assertTrue(guide.contains("use the shallowest evidence layer"));
            assertTrue(guide.contains("Exported from Pickleball `" + version + "`"));
            assertTrue(guide.contains("If export fails, treat any existing `.pickleball` contents as potentially stale"));
            assertTrue(guide.contains("keep terminal logging minimal"));
            assertTrue(guide.contains("older Pickleball release whose exporter predates the manifest lifecycle"));

            String consumerProject = Files.readString(root.resolve("docs/consumer-project.md"));
            assertTrue(consumerProject.contains("keep console verbosity low"));
            assertTrue(consumerProject.contains("older Pickleball release whose exporter predates the manifest lifecycle"));
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
