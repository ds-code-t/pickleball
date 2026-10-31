package tools.dscode.scenarioobject;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import tools.dscode.testengine.DynamicSuiteEngine;
import tools.dscode.testengine.PickleballRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static tools.dscode.Values.getScenarioObject;

class ScenarioObjectHookTest {
    @Test
    void hookStepAndAfterSeeTheSameObjectAndTheNextScenarioDoesNot() {
        Properties before = new Properties();
        before.putAll(System.getProperties());
        ScenarioObjectSteps.reset();
        try {
            System.setProperty("pkb_features", "classpath:scenario-object");
            System.setProperty("cucumber.features", "classpath:scenario-object");
            System.setProperty("pkb_glue", "tools.dscode.scenarioobject");
            System.setProperty("cucumber.glue", "tools.dscode.scenarioobject");
            System.setProperty("pkb_tags", "@scenario-object");
            System.setProperty("cucumber.filter.tags", "@scenario-object");
            System.setProperty("pkb_loglevel", "error");
            System.setProperty("cucumber.execution.parallel.enabled", "false");
            // Order-based assertions below need one scenario at a time; do not inherit a parallel run var.
            System.setProperty("pkb_parallel", "1");
            System.setProperty("junit.platform.discovery.includeEngines", DynamicSuiteEngine.ENGINE_ID);
            resetRunner();

            List<TestExecutionResult.Status> statuses = new ArrayList<>();
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClasspathResource("scenario-object"))
                    .filters(EngineFilter.includeEngines(DynamicSuiteEngine.ENGINE_ID))
                    .build();
            LauncherFactory.create().execute(request, new TestExecutionListener() {
                @Override
                public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
                    if (identifier.isTest()) {
                        statuses.add(result.getStatus());
                    }
                }
            });

            assertEquals(List.of(TestExecutionResult.Status.SUCCESSFUL, TestExecutionResult.Status.SUCCESSFUL), statuses);
            assertFalse(ScenarioObjectSteps.leaked);
            assertEquals(2, ScenarioObjectSteps.beforeTokens.size());
            assertSame(ScenarioObjectSteps.beforeTokens.get(0), ScenarioObjectSteps.stepTokens.get(0));
            assertSame(ScenarioObjectSteps.beforeTokens.get(0), ScenarioObjectSteps.afterTokens.get(0));
            assertSame(ScenarioObjectSteps.beforeTokens.get(1), ScenarioObjectSteps.stepTokens.get(1));
            assertSame(ScenarioObjectSteps.beforeTokens.get(1), ScenarioObjectSteps.afterTokens.get(1));
            assertNotSame(ScenarioObjectSteps.beforeTokens.get(0), ScenarioObjectSteps.beforeTokens.get(1));
            assertNull(getScenarioObject("from-step"));
        } finally {
            System.setProperties(before);
            resetRunner();
            ScenarioObjectSteps.reset();
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
