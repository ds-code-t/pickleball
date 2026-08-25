package tools.dscode.common.reporting.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tools.dscode.control.protocol.InvestigationHandoff;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InvestigationHandoffChecks {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void jsonToHtmlEscapesTextCapsScreenshotsAndNotesMissingImages() throws Exception {
        Path project = Files.createTempDirectory("pickleball-investigation-html");
        try {
            Path shots = project.resolve("reports/diagnostic-runs/run-1/scenarios/s1/screenshots");
            Files.createDirectories(shots);
            Files.write(shots.resolve("ok.png"), new byte[]{1, 2, 3});

            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("pkb_investigation_id", "form-1");
            raw.put("scenario", Map.of("name", "Click <Go>"));
            raw.put("cause", "Looked for <input> & clicked the wrong one.");
            raw.put("screenshots", List.of(
                    "reports/diagnostic-runs/run-1/scenarios/s1/screenshots/ok.png",
                    "reports/diagnostic-runs/run-1/scenarios/s1/screenshots/gone.png",
                    "reports/diagnostic-runs/run-1/scenarios/s1/screenshots/third.png"
            ));

            InvestigationHandoff.Document document = InvestigationHandoff.normalize(raw, project);
            assertEquals(2, document.screenshots().size());
            String html = InvestigationHandoff.renderHtml(document, project);
            assertTrue(html.contains("Looked for &lt;input&gt; &amp; clicked the wrong one."));
            assertTrue(html.contains("Click &lt;Go&gt;"));
            assertTrue(html.contains("ok.png"));
            assertTrue(html.contains("Screenshot missing: reports/diagnostic-runs/run-1/scenarios/s1/screenshots/gone.png"));
            assertFalse(html.contains("third.png"));
            assertFalse(html.contains("<input>"));
        } finally {
            deleteTree(project);
        }
    }

    @Test
    void diagnosticCliEmitWritesHandoffPairAndDoesNotCopyTheDiagnosticPack() throws Exception {
        Path project = Files.createTempDirectory("pickleball-investigation-cli");
        try {
            Path run = project.resolve("reports/diagnostic-runs/run-9/scenarios/s1/screenshots");
            Files.createDirectories(run);
            Path png = run.resolve("frame.png");
            Files.write(png, new byte[]{8, 8, 8});
            Path index = project.resolve("reports/diagnostic-runs/run-9/run-index.json");
            Files.writeString(index, "{\"runId\":\"run-9\"}", StandardCharsets.UTF_8);

            Path input = project.resolve("handoff.json");
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("pkb_investigation_id", "cli-9");
            raw.put("cause", "The catalog button was stale.");
            raw.put("outcome", "cause-only");
            raw.put("runId", "run-9");
            raw.put("failureSignature", "stale-element");
            raw.put("screenshots", List.of(
                    "reports/diagnostic-runs/run-9/scenarios/s1/screenshots/frame.png"
            ));
            JSON.writeValue(input.toFile(), raw);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int status = DiagnosticCli.run(
                    new String[]{"emit-investigation", input.toString(), project.toString()},
                    new PrintStream(output, true, StandardCharsets.UTF_8),
                    System.err
            );
            assertEquals(0, status);
            String reportPath = output.toString(StandardCharsets.UTF_8).trim();
            assertEquals(".pickleball/investigations/cli-9/report.html", reportPath);

            Path jsonFile = project.resolve(".pickleball/investigations/cli-9/investigation.json");
            Path htmlFile = project.resolve(".pickleball/investigations/cli-9/report.html");
            assertTrue(Files.isRegularFile(jsonFile));
            assertTrue(Files.isRegularFile(htmlFile));
            assertTrue(Files.isRegularFile(png));
            assertTrue(Files.isRegularFile(index));

            String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
            assertTrue(json.contains("\"pkb_investigation_id\" : \"cli-9\"")
                    || json.contains("\"pkb_investigation_id\": \"cli-9\""));
            assertTrue(json.contains("stale-element"));
            assertFalse(json.contains("iVBORw0KGgo"));

            try (var paths = Files.walk(project.resolve(".pickleball/investigations"))) {
                List<Path> files = paths.filter(Files::isRegularFile).toList();
                assertEquals(2, files.size());
            }

            String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
            assertTrue(html.contains("../../../reports/diagnostic-runs/run-9/scenarios/s1/screenshots/frame.png"));
            assertTrue(html.contains("not fixed"));
        } finally {
            deleteTree(project);
        }
    }

    @Test
    void diagnosticCliReadsInvestigationJsonFromStdin() throws Exception {
        Path project = Files.createTempDirectory("pickleball-investigation-stdin");
        try {
            String json = """
                    {"pkb_investigation_id":"stdin-1","cause":"A mapping key was wrong."}
                    """;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int status = DiagnosticCli.run(
                    new String[]{"emit-investigation", "-", project.toString()},
                    new PrintStream(output, true, StandardCharsets.UTF_8),
                    System.err,
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))
            );
            assertEquals(0, status);
            assertEquals(".pickleball/investigations/stdin-1/report.html", output.toString(StandardCharsets.UTF_8).trim());
            assertTrue(Files.isRegularFile(project.resolve(".pickleball/investigations/stdin-1/investigation.json")));
        } finally {
            deleteTree(project);
        }
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
