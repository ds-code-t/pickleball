package tools.dscode.engine.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.dscode.coredefinitions.LogAndAssertSteps;
import tools.dscode.coredefinitions.ServiceCallSteps;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.adapter.cucumber.CucumberAdapterRun;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.ScenarioResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.StepResult;
import tools.dscode.expectedfailure.ExpectedFailureSteps;
import tools.dscode.store.StoreHydrator;
import tools.dscode.scenarioobject.ScenarioObjectSteps;
import tools.dscode.testengine.parity.LaunchParitySteps;
import tools.dscode.untilpass.UntilPassBrowserSteps;
import com.example.pickleball.SyntaxProofSteps;
import com.example.pickleball.support.LocalTestSite;
import io.cucumber.core.runner.modularexecutions.CucumberScanUtil;
import io.cucumber.core.runtime.FeaturePathLoadCache;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void ignoreFailuresMatches() throws Exception {
        String feature = """
                Feature: Ignore
                  Scenario: IGNORE FAILURES keeps the following step from failing the scenario
                    * IGNORE FAILURES
                    * FAIL: area b ignored
                    * , save "continued" as "kept"
                  Scenario: LOG FAILURES BUT CONTINUE SCENARIO keeps going
                    * LOG FAILURES BUT CONTINUE SCENARIO
                    * FAIL: area b logged
                    * , save "continued" as "kept"
                  Scenario: AND IGNORE FAILURES applies to that step
                    * ALWAYS RUN AND IGNORE FAILURES
                      : * FAIL: area b suffix ignored
                    * , save "continued" as "kept"
                """;
        List<DualRun.Divergence> found = ParityHarness.compare(feature, LogAndAssertSteps.class);
        assertTrue(found.isEmpty(), found.size() + " " + found);
    }

    @Test
    void definedRetryMatches() throws Exception {
        String feature = """
                Feature: Defined retry
                  Scenario: RETRY runs a defined step again
                    * RETRY: fail until branch "r" reaches "2"
                    * branch count "r" equals "2"
                """;
        List<DualRun.Divergence> found = ParityHarness.compare(feature, SyntaxProofSteps.class);
        assertTrue(found.isEmpty(), found.size() + " " + found);
    }

    @Test
    void retryKeepsTheGherkinLine() throws Exception {
        String feature = """
                Feature: Retry line
                  Scenario: An undefined attempt stays inside the RETRY step
                    * RETRY: this step is not defined anywhere
                """;
        List<DualRun.Divergence> found = ParityHarness.compare(feature, LogAndAssertSteps.class);
        assertTrue(found.isEmpty(), found.size() + " " + found);
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
    void serviceCallExecutionMatches() throws Exception {
        Path calls = Path.of("src/test/resources/calls");
        Path callsTarget = Path.of("maven-consumer-project/src/test/resources/calls").toAbsolutePath().normalize();
        boolean linkedCalls = false;
        boolean copiedCalls = false;
        if (!Files.exists(calls)) {
            Files.createDirectories(calls.getParent());
            try {
                Files.createSymbolicLink(calls, callsTarget);
                linkedCalls = true;
            } catch (UnsupportedOperationException | FileSystemException denied) {
                copyTree(callsTarget, calls);
                copiedCalls = true;
            }
        }
        hydrateCallLibrary();
        try (LocalTestSite site = LocalTestSite.start(8765)) {
            List<DualRun.Divergence> found = ParityHarness.compareFeature(
                    Path.of("maven-consumer-project/src/test/resources/features/service-call-execution.feature"),
                    SyntaxProofSteps.class);
            assertTrue(found.isEmpty(), found.size() + " " + found.stream().limit(12).toList());
        } finally {
            if (copiedCalls) deleteTree(calls);
            else if (linkedCalls) Files.deleteIfExists(calls);
        }
    }

    @Test
    void consumerFeaturesMatch() throws Exception {
        List<String> problems = new ArrayList<>();
        Path calls = Path.of("src/test/resources/calls");
        Path callsTarget = Path.of("maven-consumer-project/src/test/resources/calls").toAbsolutePath().normalize();
        boolean linkedCalls = false;
        boolean copiedCalls = false;
        if (!Files.exists(calls)) {
            Files.createDirectories(calls.getParent());
            try {
                Files.createSymbolicLink(calls, callsTarget);
                linkedCalls = true;
            } catch (UnsupportedOperationException | FileSystemException denied) {
                copyTree(callsTarget, calls);
                copiedCalls = true;
            }
        }
        hydrateCallLibrary();
        try (LocalTestSite site = LocalTestSite.start(8765)) {
            Path resources = Path.of("maven-consumer-project/src/test/resources");
            for (Path feature : ParityHarness.featuresUnder(resources)) {
                List<DualRun.Divergence> found = ParityHarness.compareFeature(feature, SyntaxProofSteps.class);
                if (!found.isEmpty()) {
                    problems.add(feature + "\n" + found.stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n")));
                }
            }
        } finally {
            if (copiedCalls) deleteTree(calls);
            else if (linkedCalls) Files.deleteIfExists(calls);
        }
        assertTrue(problems.isEmpty(), problems.size() + " features diverged\n" + String.join("\n\n", problems));
    }

    /**
     * TokenCall without a parent leaves {@code <PARENT.SCENARIO:url>} in the
     * endpoint. Both engines fail that step. The call does not open a socket.
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void unresolvedEndpointFailsOnBothEngines() throws Exception {
        String feature = """
                Feature: Unresolved service endpoint
                  Scenario: No parent leaves the reference in the endpoint
                    * MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
                      | endpoint | <PARENT.SCENARIO:url>/api/service-calls/token |
                      | method   | POST                                         |
                    * EXECUTE SERVICE CALL
                """;
        Path dir = Files.createTempDirectory("pkb-unresolved-endpoint");
        Path file = dir.resolve("unresolved.feature");
        try {
            Files.writeString(file, feature);
            long started = System.nanoTime();
            RunResult adapter = CucumberAdapterRun.run(file, ServiceCallSteps.class.getPackageName());
            RunResult nativeResult = PickleballEngine.run(feature, EngineOptions.of(ServiceCallSteps.class));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(elapsedMs < 60_000, "unresolved endpoint blocked for " + elapsedMs + " ms");
            assertEquals(1, adapter.scenarios().size(), adapter.toString());
            assertEquals(1, nativeResult.scenarios().size(), nativeResult.toString());
            assertEquals(Status.FAILED, adapter.scenarios().get(0).status(), dump("adapter", adapter));
            assertEquals(Status.FAILED, nativeResult.scenarios().get(0).status(), dump("native", nativeResult));
            assertTrue(
                    nativeResult.scenarios().get(0).message().contains("unresolved reference"),
                    nativeResult.scenarios().get(0).message()
            );
            assertTrue(
                    nativeResult.scenarios().get(0).message().contains("<PARENT.SCENARIO:url>"),
                    nativeResult.scenarios().get(0).message()
            );
            List<DualRun.Divergence> found = DualRun.diff(adapter, nativeResult);
            assertTrue(found.isEmpty(), found.size() + "\n" + dump("adapter", adapter) + "\n" + dump("native", nativeResult));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    /**
     * The adapter loads an explicit call path from store rows. A jar-hash hydrate
     * at suite start runs before this link exists, so the rows are stored here.
     * A path-scoped hydrate does not wipe the rest of the store.
     */
    private static void hydrateCallLibrary() throws Exception {
        StoreHydrator.hydrate(
                Path.of("").toAbsolutePath().normalize(),
                new StoreHydrator.Options(List.of("src/test/resources/calls"), false, false));
        FeaturePathLoadCache.clear();
        CucumberScanUtil.clearCache();
    }

    /** Copies the tree. Does not follow a link into the target. */
    private static void copyTree(Path source, Path destination) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    /** Deletes a copied tree. A symlink is removed as a link, not followed. */
    private static void deleteTree(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            Files.deleteIfExists(root);
            return;
        }
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
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
