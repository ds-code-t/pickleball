package tools.dscode.workbench.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticEvidenceNavigatorTest {
    @TempDir
    Path project;

    @Test
    void readsCatalogThenIndexAndScreenshotFramesWithoutInventingRuns() throws Exception {
        Path root = project.resolve("reports/diagnostic-runs");
        Path run = root.resolve("run-1");
        Path scenario = run.resolve("scenarios/scenario-1");
        Path shots = scenario.resolve("screenshots");
        Files.createDirectories(shots);
        Files.writeString(root.resolve("run-catalog.json"), """
                {"runs":[{"runId":"run-1","outcome":"PASSED"}]}
                """);
        Files.writeString(run.resolve("run-index.json"), """
                {"runId":"run-1","outcome":"PASSED"}
                """);
        Files.writeString(scenario.resolve("summary.json"), """
                {"lastStepText":"Then stay"}
                """);
        Files.writeString(scenario.resolve("events.jsonl"), """
                {"stepText":"Given navigate to: URL.home"}
                {"stepText":"Then stay"}
                """);
        Path png = shots.resolve("frame-1.png");
        Files.write(png, new byte[]{1, 2, 3});

        DiagnosticEvidenceNavigator navigator = new DiagnosticEvidenceNavigator(project);
        assertTrue(navigator.available());
        assertEquals("run-1", navigator.catalogRuns().getFirst().runId());
        assertEquals("run-1 · PASSED", navigator.catalogRuns().getFirst().displayLabel());
        assertEquals("PASSED", navigator.catalogRuns().getFirst().outcome());

        DiagnosticEvidenceNavigator.Timeline timeline = navigator.timeline(run);
        assertEquals(1, timeline.frames().size());
        assertEquals("Given navigate to: URL.home", timeline.frames().getFirst().stepText());

        assertTrue(navigator.layers(run, "scenario-1").stream()
                .anyMatch(layer -> layer.layer() == DiagnosticEvidenceNavigator.Layer.EVENTS && layer.present()));
        assertTrue(new DiagnosticEvidenceNavigator(project, project.resolve("missing")).catalogRuns().isEmpty());

        List<DiagnosticEvidenceNavigator.ReplayBeat> beats = navigator.replay(run);
        assertEquals(2, beats.size());
        assertEquals("Given navigate to: URL.home", beats.getFirst().stepText());
        assertEquals("Then stay", beats.get(1).stepText());
        assertEquals("step", beats.getFirst().kind());
        assertTrue(beats.getFirst().eventSeq() > 0);
        assertEquals("", beats.getFirst().sourcePath());
        assertEquals("", beats.getFirst().definition().className());
        DiagnosticEvidenceNavigator.ReplayModel model = navigator.replayModel(run);
        assertEquals(1, model.roots().size());
        assertEquals("scenario", model.roots().getFirst().kind());
        assertEquals(2, model.roots().getFirst().children().size());
    }

    @Test
    void nestedRunBecomesParentWithChildrenAndNextStepIsSibling() throws Exception {
        Path root = project.resolve("reports/diagnostic-runs");
        Path run = root.resolve("run-nested");
        Path scenario = run.resolve("scenarios/parent-scenario");
        Files.createDirectories(scenario);
        Files.writeString(root.resolve("run-catalog.json"), """
                {"runs":[{"runId":"run-nested","outcome":"FAILED"}]}
                """);
        Files.writeString(scenario.resolve("events.jsonl"), """
                {"type":"nested_scenario_start","eventSeq":10,"invocationId":"inv-login","callee":{"scenarioName":"login","featureUri":"features/login.feature","scenarioLine":4}}
                {"type":"step","eventSeq":11,"text":"Given nested login","nestedInvocationId":"inv-login","nestingLevel":1,"status":"PASSED","source":{"path":"features/login.feature","line":5},"definition":{"class":"com.example.Steps","method":"login","sourcePath":"src/test/java/com/example/Steps.java","origin":"NON_PICKLEBALL"}}
                {"type":"nested_scenario_end","eventSeq":12,"invocationId":"inv-login","outcome":"PASSED","callee":{"scenarioName":"login"}}
                {"type":"step","eventSeq":13,"text":"When RUN COMPONENT SCENARIO: login","nestingLevel":0,"status":"FAILED","source":{"path":"features/parent.feature","line":10}}
                {"type":"step","eventSeq":14,"text":"Then after run","nestingLevel":0,"status":"SKIPPED","source":{"path":"features/parent.feature","line":11}}
                """);

        DiagnosticEvidenceNavigator navigator = new DiagnosticEvidenceNavigator(project);
        DiagnosticEvidenceNavigator.ReplayModel model = navigator.replayModel(run);
        List<DiagnosticEvidenceNavigator.ReplayBeat> beats = model.beats();
        assertEquals(5, beats.size());
        assertEquals("nested_scenario_start", beats.getFirst().type());
        assertEquals("nested_scenario_end", beats.get(2).type());
        assertEquals(11, beats.get(1).eventSeq());
        assertEquals(1, beats.get(1).nestingLevel());
        assertEquals("inv-login", beats.get(1).nestedInvocationId());
        assertEquals("features/login.feature", beats.get(1).sourcePath());
        assertEquals(5, beats.get(1).sourceLine());
        assertEquals("com.example.Steps", beats.get(1).definition().className());
        assertEquals("login", beats.get(1).definition().method());
        assertEquals("src/test/java/com/example/Steps.java", beats.get(1).definition().sourcePath());
        assertEquals("NON_PICKLEBALL", beats.get(1).definition().origin());
        assertEquals("component", beats.getFirst().kind());

        DiagnosticEvidenceNavigator.ReplayNode scenarioNode = model.roots().getFirst();
        assertEquals("scenario", scenarioNode.kind());
        assertEquals(2, scenarioNode.children().size());
        DiagnosticEvidenceNavigator.ReplayNode runStep = scenarioNode.children().getFirst();
        DiagnosticEvidenceNavigator.ReplayNode after = scenarioNode.children().get(1);
        assertEquals("When RUN COMPONENT SCENARIO: login", runStep.beat().stepText());
        assertEquals("Then after run", after.beat().stepText());
        assertEquals(scenarioNode.nodeId(), runStep.parentNodeId());
        assertEquals(scenarioNode.nodeId(), after.parentNodeId());
        assertEquals(1, runStep.children().size());
        DiagnosticEvidenceNavigator.ReplayNode component = runStep.children().getFirst();
        assertEquals("component", component.kind());
        assertEquals("login", component.beat().stepText());
        assertEquals(runStep.nodeId(), component.parentNodeId());
        assertEquals(1, component.children().size());
        assertEquals("Given nested login", component.children().getFirst().beat().stepText());
        assertEquals(component.nodeId(), component.children().getFirst().parentNodeId());

        java.util.Map<String, Object> tree = navigator.treeMaps(model.roots(), model.beats()).getFirst();
        assertEquals("scenario", tree.get("kind"));
        assertEquals(Boolean.TRUE, tree.get("failed"));
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> children =
                (java.util.List<java.util.Map<String, Object>>) tree.get("children");
        assertEquals("When RUN COMPONENT SCENARIO: login", children.getFirst().get("stepText"));
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> nested =
                (java.util.List<java.util.Map<String, Object>>) children.getFirst().get("children");
        assertEquals("component", nested.getFirst().get("kind"));
        assertEquals("login", nested.getFirst().get("stepText"));
        assertEquals(0, nested.getFirst().get("beatIndex"));
    }

    @Test
    void multiRowRunChildrenAreSiblingsUnderTheParentStep() throws Exception {
        Path root = project.resolve("reports/diagnostic-runs");
        Path run = root.resolve("run-rows");
        Path scenario = run.resolve("scenarios/parent-scenario");
        Files.createDirectories(scenario);
        Files.writeString(root.resolve("run-catalog.json"), """
                {"runs":[{"runId":"run-rows","outcome":"PASSED"}]}
                """);
        Files.writeString(scenario.resolve("events.jsonl"), """
                {"type":"nested_scenario_start","eventSeq":1,"invocationId":"inv-a","callee":{"scenarioName":"login","featureUri":"features/login.feature"}}
                {"type":"step","eventSeq":2,"text":"Given login body","nestedInvocationId":"inv-a"}
                {"type":"nested_scenario_end","eventSeq":3,"invocationId":"inv-a","outcome":"PASSED"}
                {"type":"nested_scenario_start","eventSeq":4,"invocationId":"inv-b","callee":{"scenarioName":"health","featureUri":"classpath:calls/health.yaml"}}
                {"type":"step","eventSeq":5,"text":"When the service is called","nestedInvocationId":"inv-b"}
                {"type":"nested_scenario_end","eventSeq":6,"invocationId":"inv-b","outcome":"PASSED"}
                {"type":"step","eventSeq":7,"text":"When RUN"}
                {"type":"step","eventSeq":8,"text":"Then both returned"}
                """);

        DiagnosticEvidenceNavigator.ReplayNode scenarioNode =
                new DiagnosticEvidenceNavigator(project).replayModel(run).roots().getFirst();
        assertEquals(2, scenarioNode.children().size());
        DiagnosticEvidenceNavigator.ReplayNode runStep = scenarioNode.children().getFirst();
        assertEquals("When RUN", runStep.beat().stepText());
        assertEquals(2, runStep.children().size());
        assertEquals("component", runStep.children().getFirst().kind());
        assertEquals("login", runStep.children().getFirst().beat().stepText());
        assertEquals("service-call", runStep.children().get(1).kind());
        assertEquals("health", runStep.children().get(1).beat().stepText());
        assertEquals("Then both returned", scenarioNode.children().get(1).beat().stepText());
        assertEquals(scenarioNode.nodeId(), scenarioNode.children().get(1).parentNodeId());
    }

    @Test
    void sparseReadersReturnCatalogIndexClustersAndSummaryWithoutEventsOrScreenshots() throws Exception {
        Path root = project.resolve("reports/diagnostic-runs");
        Path run = root.resolve("run-1");
        Path scenario = run.resolve("scenarios/scenario-1");
        Files.createDirectories(scenario.resolve("screenshots"));
        Files.writeString(root.resolve("run-catalog.json"), """
                {"runs":[{"runId":"run-1","outcome":"FAILED","runProfile":"pkb_browser=CHROME_HEADLESS, pkb_parallel=4"}]}
                """);
        Files.writeString(run.resolve("run-index.json"), """
                {"runId":"run-1","outcome":"FAILED","scenarioCount":1,"runProfile":"pkb_browser=CHROME_HEADLESS, pkb_parallel=4"}
                """);
        Files.writeString(run.resolve("clusters.json"), """
                {"clusters":[{"id":"c1","size":1}]}
                """);
        Files.writeString(scenario.resolve("summary.json"), """
                {"scenarioId":"scenario-1","outcome":"FAILED","lastStepText":"Then stay","runProfile":"pkb_browser=CHROME_HEADLESS, pkb_parallel=4"}
                """);
        Files.writeString(scenario.resolve("events.jsonl"), "{\"stepText\":\"secret-event\"}\n");
        Files.write(scenario.resolve("screenshots/frame-1.png"), new byte[]{9, 9, 9});

        DiagnosticEvidenceNavigator navigator = new DiagnosticEvidenceNavigator(project);
        assertEquals("run-1 · FAILED", navigator.catalogRuns().getFirst().displayLabel());
        String catalog = navigator.catalogDocument().toString();
        assertTrue(catalog.contains("run-1"));
        assertTrue(catalog.contains("pkb_parallel=4"));
        assertFalse(catalog.contains("secret-event"));

        String runDocument = navigator.runDocument("run-1").toString();
        assertTrue(runDocument.contains("FAILED"));
        assertTrue(runDocument.contains("pkb_parallel=4"));
        assertTrue(runDocument.contains("\"clusters\""));
        assertFalse(runDocument.contains("secret-event"));
        assertFalse(runDocument.contains("frame-1.png"));

        String summary = navigator.scenarioSummaryDocument("run-1", "scenario-1").toString();
        assertTrue(summary.contains("Then stay"));
        assertTrue(summary.contains("pkb_parallel=4"));
        assertFalse(summary.contains("secret-event"));
        assertFalse(summary.contains("frame-1.png"));
    }
}
