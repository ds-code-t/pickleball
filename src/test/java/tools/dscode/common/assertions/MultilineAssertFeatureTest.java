package tools.dscode.common.assertions;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.testengine.DynamicSuiteEngine;
import tools.dscode.testengine.PickleballRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact consumer step text: quoted and unquoted multi-line ASSERT, including
 * the documented JDBC {@code RESPONSE.error} example.
 */
class MultilineAssertFeatureTest {

    @Test
    void consumerMultilineAssertStepsPass() {
        Properties before = new Properties();
        before.putAll(System.getProperties());
        try {
            System.setProperty("pkb_features", "classpath:multiline-assert");
            System.setProperty("cucumber.features", "classpath:multiline-assert");
            System.setProperty("pkb_glue", "tools.dscode.coredefinitions");
            System.setProperty("cucumber.glue", "tools.dscode.coredefinitions");
            System.setProperty("pkb_tags", "@multiline-assert");
            System.setProperty("cucumber.filter.tags", "@multiline-assert");
            System.setProperty("pkb_loglevel", "error");
            System.setProperty("cucumber.execution.parallel.enabled", "false");
            System.setProperty("junit.platform.discovery.includeEngines", DynamicSuiteEngine.ENGINE_ID);
            resetRunner();

            List<String> finished = new ArrayList<>();
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClasspathResource("multiline-assert"))
                    .filters(EngineFilter.includeEngines(DynamicSuiteEngine.ENGINE_ID))
                    .build();
            LauncherFactory.create().execute(request, new TestExecutionListener() {
                @Override
                public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
                    if (identifier.isTest()) {
                        String message = result.getThrowable().map(error -> {
                            StringBuilder text = new StringBuilder();
                            Throwable current = error;
                            while (current != null) {
                                text.append(current.getClass().getSimpleName())
                                        .append(": ")
                                        .append(current.getMessage())
                                        .append(" || ");
                                current = current.getCause();
                            }
                            return text.toString();
                        }).orElse("");
                        finished.add(result.getStatus() + " " + identifier.getDisplayName() + " " + message);
                    }
                }
            });

            assertEquals(1, finished.size(), finished.toString());
            assertTrue(finished.getFirst().startsWith("SUCCESSFUL"), finished.toString());
        } finally {
            MappingProcessor.resetCommonMaps();
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
