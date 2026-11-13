package tools.dscode.engine.adapter;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import tools.dscode.coredefinitions.GeneralSteps;
import tools.dscode.testengine.DynamicSuiteEngine;
import tools.dscode.testengine.PickleballRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeSuiteHookTest {
    @Test
    void nativeRunFiresSuiteHooksAroundTheScenario() {
        Properties before = new Properties();
        before.putAll(System.getProperties());
        SuiteHookSteps.reset();
        GeneralSteps.beforeSuiteForTests = () -> SuiteHookSteps.ORDER.add("before");
        GeneralSteps.afterSuiteForTests = () -> SuiteHookSteps.ORDER.add("after");
        try {
            System.setProperty("pkb_engine", "native");
            System.setProperty("pkb_features", "classpath:suite-hooks");
            System.setProperty("cucumber.features", "classpath:suite-hooks");
            System.setProperty("pkb_glue", "tools.dscode.engine.adapter");
            System.setProperty("cucumber.glue", "tools.dscode.engine.adapter");
            System.setProperty("pkb_tags", "@suite-hooks");
            System.setProperty("cucumber.filter.tags", "@suite-hooks");
            System.setProperty("pkb_loglevel", "error");
            System.setProperty("cucumber.execution.parallel.enabled", "false");
            System.setProperty("pkb_parallel", "1");
            System.setProperty("junit.platform.discovery.includeEngines", DynamicSuiteEngine.ENGINE_ID);
            resetRunner();

            List<TestExecutionResult.Status> statuses = new ArrayList<>();
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClasspathResource("suite-hooks"))
                    .filters(EngineFilter.includeEngines(DynamicSuiteEngine.ENGINE_ID))
                    .build();
            LauncherFactory.create().execute(request, new TestExecutionListener() {
                @Override
                public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
                    if (identifier.isTest()) statuses.add(result.getStatus());
                }
            });

            assertEquals(List.of(TestExecutionResult.Status.SUCCESSFUL), statuses, statuses.toString());
            assertEquals(List.of("before", "step", "after"), SuiteHookSteps.ORDER, SuiteHookSteps.ORDER.toString());
        } finally {
            GeneralSteps.beforeSuiteForTests = null;
            GeneralSteps.afterSuiteForTests = null;
            System.setProperties(before);
            resetRunner();
            SuiteHookSteps.reset();
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
