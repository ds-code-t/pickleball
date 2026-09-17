package tools.dscode.workbench.ui.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchWebResourceTest {
    @Test
    void mappingAndDiagnosticPanelsShareWorkbenchTokensAndStaySeparate() throws Exception {
        String tokens = read("workbench-web.css");
        assertTrue(tokens.contains("--wb-accent"));
        assertTrue(tokens.contains("--wb-bg"));

        String mappingHtml = read("mapping-editor.html");
        assertTrue(mappingHtml.contains("workbench-web.css"));
        assertTrue(mappingHtml.contains("mapping-editor.js"));
        assertFalse(mappingHtml.contains("diagnostic-explorer"));

        String diagnosticHtml = read("diagnostic-explorer.html");
        assertTrue(diagnosticHtml.contains("workbench-web.css"));
        assertTrue(diagnosticHtml.contains("id=\"beats\""));
        assertTrue(diagnosticHtml.contains("id=\"frame\""));
        assertTrue(diagnosticHtml.contains("id=\"empty\""));
        assertTrue(diagnosticHtml.contains("id=\"speed\""));
        assertTrue(diagnosticHtml.contains("id=\"open-target\""));
        assertTrue(diagnosticHtml.contains("Execution tree"));
        assertTrue(diagnosticHtml.contains("Retained run"));
        assertFalse(diagnosticHtml.contains("gherkin-editor"));
        assertFalse(diagnosticHtml.toLowerCase().contains("mermaid"));

        String diagnosticJs = read("diagnostic-explorer.js");
        assertTrue(diagnosticJs.contains("window.setDiagnosticState"));
        assertTrue(diagnosticJs.contains("No screenshot was retained for this step."));
        assertTrue(diagnosticJs.contains("ArrowRight"));
        assertTrue(diagnosticJs.contains("isExpanded"));
        assertTrue(diagnosticJs.contains("diagnosticHost.go"));
        assertTrue(diagnosticJs.contains("nested_scenario_end"));
        assertTrue(diagnosticJs.contains("playable.length - 1"));
        assertFalse(diagnosticJs.toLowerCase().contains("mermaid"));
        assertFalse(diagnosticJs.contains("d3."));
        assertFalse(diagnosticJs.contains("cytoscape"));
    }

    private static String read(String name) throws IOException {
        try (InputStream in = WorkbenchWebResourceTest.class.getResourceAsStream(
                "/tools/dscode/workbench/ui/web/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
