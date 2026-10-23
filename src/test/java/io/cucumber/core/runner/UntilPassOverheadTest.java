package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.gherkin.messages.NGherkinFactory;
import io.cucumber.messages.types.PickleStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.testengine.DynamicSuiteEngine;
import tools.dscode.testengine.PickleballRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static tools.dscode.common.util.Reflect.getProperty;

/**
 * Twenty literal until passes, no browser. The 400 ms pause is not the
 * assertion. The assertion is the work around that pause.
 */
class UntilPassOverheadTest {

    @Test
    void aRepeatedLiteralPickleDoesNotShareStepState() throws Exception {
        String text = ", save \"tick\" as \"untilTick\"";
        Pickle firstPickle = NGherkinFactory.createGherkinMessagesPickle("* ", text, "");
        Step first = firstPickle.getSteps().getFirst();
        PickleStep firstMessage = (PickleStep) getProperty(first, "pickleStep");
        java.lang.reflect.Field nesting = firstMessage.getClass().getField("nestingLevel");
        nesting.setInt(firstMessage, 9);
        Pickle secondPickle = NGherkinFactory.createGherkinMessagesPickle("* ", text, "");
        Step second = secondPickle.getSteps().getFirst();
        PickleStep secondMessage = (PickleStep) getProperty(second, "pickleStep");
        assertEquals(0, nesting.getInt(secondMessage));
        assertEquals(text, second.getText());
        assertTrue(second.getText().contains("save"));
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void literalUntilPassOverheadStaysUnderThePause() {
        Properties before = new Properties();
        before.putAll(System.getProperties());
        int previousMax = StepData.globalMaxIterations;
        Level previousLogLevel = PickleballRunner.LOG_LEVEL;
        StepData.globalMaxIterations = 20;
        try {
            System.setProperty("pkb_features", "classpath:until-pass");
            System.setProperty("cucumber.features", "classpath:until-pass");
            System.setProperty("pkb_glue", "tools.dscode.coredefinitions");
            System.setProperty("cucumber.glue", "tools.dscode.coredefinitions");
            System.setProperty("pkb_tags", "@until-pass-timing");
            System.setProperty("cucumber.filter.tags", "@until-pass-timing");
            System.setProperty("pkb_loglevel", "error");
            System.setProperty("cucumber.execution.parallel.enabled", "false");
            System.setProperty("pickleball.until.passTiming", "true");
            System.setProperty(
                    "junit.platform.discovery.includeEngines",
                    DynamicSuiteEngine.ENGINE_ID
            );
            resetRunner();

            List<String> finished = new ArrayList<>();
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClasspathResource("until-pass"))
                    .filters(EngineFilter.includeEngines(DynamicSuiteEngine.ENGINE_ID))
                    .build();
            Launcher launcher = LauncherFactory.create();
            launcher.execute(request, new TestExecutionListener() {
                @Override
                public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
                    if (testIdentifier.isTest()) {
                        finished.add(testIdentifier.getDisplayName() + " " + result.getStatus());
                    }
                }
            });

            String report = UntilPassTiming.lastReport();
            System.out.println(report);
            assertEquals(20, UntilPassTiming.lastPasses(), report + " saw " + finished);
            double pause = UntilPassTiming.lastExclusiveMillisPerPass("pause");
            double wall = UntilPassTiming.lastInclusiveMillisPerPass("until-pass");
            double overhead = wall - pause;
            assertTrue(pause >= 300 && pause <= 700, "pause " + pause + "\n" + report);
            // The 400 ms pause is excluded. A returned 300 ms action settle would
            // put this near 325 ms, so the bound stays under that regression.
            assertTrue(overhead < 200, "overhead " + overhead + "\n" + report);
            assertTrue(
                    finished.stream().anyMatch(line -> line.contains("literal until")),
                    finished.toString()
            );
        } finally {
            StepData.globalMaxIterations = previousMax;
            PickleballRunner.LOG_LEVEL = previousLogLevel;
            CurrentScenarioState.currentScenarioState.remove();
            MappingProcessor.resetCommonMaps();
            UntilPassTiming.stop();
            System.setProperties(before);
            resetRunner();
        }
    }

    private static void resetRunner() {
        try {
            Field field = PickleballRunner.class.getDeclaredField("INSTANCE");
            field.setAccessible(true);
            field.set(null, null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
