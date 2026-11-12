package tools.dscode.engine.parity;

import org.junit.jupiter.api.Test;
import tools.dscode.coredefinitions.LogAndAssertSteps;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.adapter.CucumberAdapterRun;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.ScenarioResult;
import tools.dscode.engine.model.StepResult;
import tools.dscode.expectedfailure.ExpectedFailureSteps;
import tools.dscode.scenarioobject.ScenarioObjectSteps;
import tools.dscode.testengine.parity.LaunchParitySteps;
import tools.dscode.untilpass.UntilPassBrowserSteps;
import com.example.pickleball.SyntaxProofSteps;
import com.example.pickleball.support.LocalTestSite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SuiteDualRunTest {
    @Test
    void undefinedStepMatches() throws Exception {
        String feature = """
                Feature: Missing
                  Scenario: One
                    * this step is not defined anywhere
                    * , save "later" as "nope"
                """;
        List<DualRun.Divergence> found = ParityHarness.compare(feature, LogAndAssertSteps.class);
        assertTrue(found.isEmpty(), found.stream().map(Object::toString).reduce("", (a, b) -> a + "\n" + b));
    }

    @Test
    void areaBMatches() throws Exception {
        List<DualRun.Divergence> found = ParityHarness.compareFeature(
                Path.of("maven-consumer-project/src/test/resources/features/area-b-control-flow.feature"),
                LogAndAssertSteps.class
        );
        assertTrue(found.isEmpty(), found.size() + " " + found.stream().limit(8).toList());
    }

    @Test
    void mappingFeaturesMatch() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String path : List.of(
                "src/test/resources/multiline-assert/multiline-assert.feature",
                "src/test/resources/xml-table/xml-table.feature")) {
            List<DualRun.Divergence> found = ParityHarness.compareFeature(Path.of(path), LogAndAssertSteps.class);
            if (!found.isEmpty()) problems.add(path + " " + found.size() + " " + found.stream().limit(3).toList());
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void repoFeaturesMatch() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Path feature : ParityHarness.featuresUnder(Path.of("src/test/resources"))) {
            Class<?> glue = glueFor(feature);
            List<DualRun.Divergence> found = ParityHarness.compareFeature(feature, glue);
            if (!found.isEmpty()) {
                problems.add(feature + " " + found.size() + " " + found.stream().limit(4).toList());
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void colonChildArgumentsMatch() throws Exception {
        String feature = """
                Feature: Colon arguments
                  Scenario: A colon child keeps its table and doc string and only DT becomes a table
                    * , if "abc":
                      : * the recorded city is saved as "areaBChildCity"
                        | city  |
                        | Tempe |
                      : * the recorded note is saved as "areaBChildNote"
                        ```
                        child note
                        ```
                    * , ensure "<areaBChildCity>" equals "Tempe"
                    * , ensure "<areaBChildNote>" equals "child note"
                    * , if "abc":
                      : * the recorded city is saved as "areaBChildOverride" DT:::city|Paris|
                        | city  |
                        | Tempe |
                    * , ensure "<areaBChildOverride>" equals "Paris"
                    * , ensure "<areaBChildOverrideMarker>" equals "DT"
                    * , if "abc":
                      : * the recorded note is saved as "areaBChildDs" DS:::hello|
                        ```
                        kept child note
                        ```
                    * , ensure "<areaBChildDs>" equals "kept child note"
                    * , ensure "<areaBChildDsMarker>" equals "DS"
                """;
        List<DualRun.Divergence> found = ParityHarness.compare(feature, SyntaxProofSteps.class);
        assertTrue(found.isEmpty(), found.size() + " " + found);
    }

    @Test
    void consumerFeaturesMatch() throws Exception {
        List<String> problems = new ArrayList<>();
        Path calls = Path.of("src/test/resources/calls");
        Path callsTarget = Path.of("maven-consumer-project/src/test/resources/calls").toAbsolutePath().normalize();
        boolean linkedCalls = false;
        if (!Files.exists(calls)) {
            Files.createDirectories(calls.getParent());
            Files.createSymbolicLink(calls, callsTarget);
            linkedCalls = true;
        }
        try (LocalTestSite site = LocalTestSite.start(8765)) {
            Path resources = Path.of("maven-consumer-project/src/test/resources");
            for (Path feature : ParityHarness.featuresUnder(resources)) {
                List<DualRun.Divergence> found = ParityHarness.compareFeature(feature, SyntaxProofSteps.class);
                if (!found.isEmpty()) {
                    problems.add(feature + "\n" + found.stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n")));
                }
            }
        } finally {
            if (linkedCalls) Files.deleteIfExists(calls);
        }
        assertTrue(problems.isEmpty(), problems.size() + " features diverged\n" + String.join("\n\n", problems));
    }

    private static Class<?> glueFor(Path feature) {
        String path = feature.toString().replace('\\', '/');
        if (path.contains("/until-pass/")) return UntilPassBrowserSteps.class;
        if (path.contains("/scenario-object/")) return ScenarioObjectSteps.class;
        if (path.contains("/expected-failure/")) return ExpectedFailureSteps.class;
        if (path.contains("/launch-parity/")) return LaunchParitySteps.class;
        return LogAndAssertSteps.class;
    }

    @Test
    void areaBHeadMatches() throws Exception {
        String feature = """
                Feature: Area B control flow

                  Scenario: Block IF ELSE-IF and ELSE nest five levels
                    * , save "untouched" as "depth5Yes"
                    * , save "untouched" as "depth5No"
                    * , save "untouched" as "depth3Else"
                    * IF: "yes":
                      : * IF: "no":
                        :: * , save "no" as "depth5Yes"
                      : * ELSE-IF: "yes":
                        :: * IF: "yes":
                          ::: * IF: "yes":
                            :::: * IF: "yes":
                              ::::: * , save "yes" as "depth5Yes"
                            :::: * ELSE:
                              ::::: * , save "no" as "depth5Yes"
                    * , ensure "<depth5Yes>" equals "yes"
                """;
        Path dir = Files.createTempDirectory("pkb-dump");
        Path file = dir.resolve("dual.feature");
        Files.writeString(file, feature);
        RunResult adapter = CucumberAdapterRun.run(file, LogAndAssertSteps.class.getPackageName());
        RunResult nativeResult = PickleballEngine.run(feature, EngineOptions.of(LogAndAssertSteps.class));
        List<DualRun.Divergence> found = DualRun.diff(adapter, nativeResult);
        assertTrue(found.isEmpty(), found.size() + "\n" + dump("adapter", adapter) + "\n" + dump("native", nativeResult));
    }

    private static String dump(String side, RunResult result) {
        StringBuilder out = new StringBuilder();
        for (ScenarioResult scenario : result.scenarios()) {
            out.append("\n== ").append(side).append(' ').append(scenario.status()).append(' ').append(scenario.name());
            int index = 0;
            for (StepResult step : scenario.steps()) {
                out.append("\n  ").append(index++).append(' ').append(step.status()).append(" [").append(step.text()).append(']');
            }
        }
        return out.toString();
    }
}
