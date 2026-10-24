package tools.dscode.coredefinitions;

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

class XmlTableCellFeatureTest {

    @Test
    void xmlTableCellIsStoredAndAReferenceIsNot() {
        Properties before = new Properties();
        before.putAll(System.getProperties());
        try {
            System.setProperty("pkb_features", "classpath:xml-table");
            System.setProperty("cucumber.features", "classpath:xml-table");
            System.setProperty("pkb_glue", "tools.dscode.coredefinitions");
            System.setProperty("cucumber.glue", "tools.dscode.coredefinitions");
            System.setProperty("pkb_tags", "@xml-table-cell");
            System.setProperty("cucumber.filter.tags", "@xml-table-cell");
            System.setProperty("pkb_loglevel", "error");
            System.setProperty("cucumber.execution.parallel.enabled", "false");
            System.setProperty("junit.platform.discovery.includeEngines", DynamicSuiteEngine.ENGINE_ID);
            resetRunner();

            List<String> finished = new ArrayList<>();
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClasspathResource("xml-table"))
                    .filters(EngineFilter.includeEngines(DynamicSuiteEngine.ENGINE_ID))
                    .build();
            LauncherFactory.create().execute(request, new TestExecutionListener() {
                @Override
                public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
                    if (identifier.isTest()) {
                        String message = result.getThrowable().map(Throwable::getMessage).orElse("");
                        finished.add(result.getStatus() + " " + message);
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
