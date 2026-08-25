package tools.dscode.workbench.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.InvestigationHandoff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigationHandoffTest {
    @TempDir
    Path project;

    @Test
    void htmlEscapesCauseAndCapsScreenshotsAndNotesMissingImages() throws Exception {
        Path run = project.resolve("reports/diagnostic-runs/run-1/scenarios/s1/screenshots");
        Files.createDirectories(run);
        Path present = run.resolve("frame-1.png");
        Files.write(present, new byte[]{1, 2, 3});

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("pkb_investigation_id", "checkout-217");
        raw.put("createdAt", "2026-08-25T06:00:00Z");
        raw.put("scenario", Map.of(
                "name", "Submit <form>",
                "feature", "features/forms.feature",
                "scenarioId", "scenario-1"
        ));
        raw.put("outcome", "CAUSE_ONLY");
        raw.put("cause", "Selector <button> failed & was missing.");
        raw.put("category", "selector");
        raw.put("failureSignature", "NoSuchElement: button");
        raw.put("runId", "run-1");
        raw.put("screenshots", List.of(
                "reports/diagnostic-runs/run-1/scenarios/s1/screenshots/frame-1.png",
                "reports/diagnostic-runs/run-1/scenarios/s1/screenshots/missing.png",
                "reports/diagnostic-runs/run-1/scenarios/s1/screenshots/extra.png"
        ));

        InvestigationHandoff.Document document = InvestigationHandoff.normalize(raw, project);
        assertEquals(2, document.screenshots().size());
        assertEquals("cause-only", document.outcome());
        assertEquals("not fixed", document.fix());

        String html = InvestigationHandoff.renderHtml(document, project);
        assertTrue(html.contains("Selector &lt;button&gt; failed &amp; was missing."));
        assertTrue(html.contains("Submit &lt;form&gt;"));
        assertTrue(html.contains("../../../reports/diagnostic-runs/run-1/scenarios/s1/screenshots/frame-1.png"));
        assertTrue(html.contains("Screenshot missing: reports/diagnostic-runs/run-1/scenarios/s1/screenshots/missing.png"));
        assertFalse(html.contains("extra.png"));
        assertFalse(html.contains("<button>"));
    }

    @Test
    void emitWritesJsonAndHtmlWithoutCopyingTheDiagnosticPack() throws Exception {
        Path shotDir = project.resolve("reports/diagnostic-runs/run-1/scenarios/s1/screenshots");
        Files.createDirectories(shotDir);
        Path png = shotDir.resolve("frame-1.png");
        Files.write(png, new byte[]{9, 9, 9});
        Files.writeString(project.resolve("reports/diagnostic-runs/run-1/run-index.json"), "{\"runId\":\"run-1\"}");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("investigationId", "nav-1");
        raw.put("cause", "The home link selector drifted.");
        raw.put("outcome", "cause-and-fix");
        raw.put("fix", "Use the catalog Home Link.");
        raw.put("runId", "run-1");
        raw.put("screenshots", List.of(
                "reports/diagnostic-runs/run-1/scenarios/s1/screenshots/frame-1.png"
        ));

        InvestigationHandoff.EmitResult result = InvestigationHandoff.emit(project, raw);
        assertEquals(".pickleball/investigations/nav-1/report.html", result.reportPath());
        assertTrue(Files.isRegularFile(result.jsonFile()));
        assertTrue(Files.isRegularFile(result.htmlFile()));
        assertTrue(Files.isRegularFile(png));

        String json = Files.readString(result.jsonFile());
        assertTrue(json.contains("\"pkb_investigation_id\": \"nav-1\""));
        assertFalse(json.contains("PNG"));
        assertFalse(json.contains("iVBORw0KGgo"));

        try (var paths = Files.walk(project.resolve(".pickleball/investigations"))) {
            List<String> names = paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .toList();
            assertEquals(List.of("investigation.json", "report.html"), names.stream().sorted().toList());
        }
        assertEquals(Map.of("reportPath", ".pickleball/investigations/nav-1/report.html"), result.sparseResult());
    }

    @Test
    void workbenchControllerEmitReturnsSparseReportPath() throws Exception {
        try (tools.dscode.workbench.WorkbenchController controller =
                     new tools.dscode.workbench.WorkbenchController(project)) {
            Object result = controller.emitInvestigation(Map.of(
                    "pkb_investigation_id", "ctrl-1",
                    "cause", "The selector drifted."
            ));
            assertEquals(
                    Map.of("reportPath", ".pickleball/investigations/ctrl-1/report.html"),
                    result
            );
            assertTrue(Files.isRegularFile(project.resolve(".pickleball/investigations/ctrl-1/report.html")));
        }
    }
}
