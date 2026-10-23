package tools.dscode.testengine;

import io.cucumber.junit.platform.engine.CucumberTestEngine;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static io.cucumber.core.options.Constants.FEATURES_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_NAME_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_TAGS_PROPERTY_NAME;
import static tools.dscode.common.reporting.logging.LogForwarder.logError;
import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;

public final class DynamicSuiteEngine implements TestEngine {

    public static final String ENGINE_ID = "pickleball-test-engine";

    /**
     * Workbench-only top-level feature list. When set, Cucumber discovery uses
     * these paths. {@code pkb_features} is left alone so nested
     * {@code SCENARIO} / {@code COMPONENT} / {@code CALL} / {@code RUN}
     * lookups still see the configured feature set. A normal {@code mvn test}
     * does not set this property.
     */
    public static final String TOP_LEVEL_FEATURES_PROPERTY = "pickleball.workbench.topFeatures";

    static {
        EngineFilterBootstrap.ensureEngineFilterApplied("DynamicSuiteEngine.<clinit>");
    }

    private final CucumberTestEngine delegate = new CucumberTestEngine();

    @Override
    public String getId() {
        return ENGINE_ID;
    }

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest discoveryRequest, UniqueId uniqueId) {
        try {
            io.cucumber.core.runner.Phase4Timing.mark("discover-start");
            logTrace("[DynamicSuiteEngine] discover() called");
            PickleballRunner suite = DynamicSuiteBootstrap.initialize(discoveryRequest);
            io.cucumber.core.runner.Phase4Timing.mark("runner-initialized");
            logTrace("[DynamicSuiteEngine] Discovery succeeded using suite subclass: " + suite.getClass().getName());

            MergedConfigurationParameters mergedParameters =
                    new MergedConfigurationParameters(
                            discoveryRequest.getConfigurationParameters(),
                            overlayTopLevelFeatures(suite.values())
                    );

            logTrace("[DynamicSuiteEngine] Final discovery configuration parameters: "
                    + new TreeMap<>(materialize(mergedParameters)));

            DynamicEngineDiscoveryRequest wrappedRequest =
                    new DynamicEngineDiscoveryRequest(discoveryRequest, mergedParameters);

            TestDescriptor discovered = delegate.discover(wrappedRequest, uniqueId);
            io.cucumber.core.runner.Phase4Timing.mark("cucumber-discover-done");
            Map<String, String> config = materialize(mergedParameters);
            ExampleRowFilter.applyToDiscoveredSuite(
                    discovered,
                    firstNonBlank(config.get(PKB_props.PKB_EXAMPLE), PKB_props.example()),
                    firstNonBlank(
                            config.get(FILTER_TAGS_PROPERTY_NAME),
                            config.get(PKB_props.PKB_TAGS),
                            PKB_props.tags()
                    ),
                    firstNonBlank(
                            config.get(FILTER_NAME_PROPERTY_NAME),
                            config.get(PKB_props.PKB_NAME),
                            PKB_props.name()
                    )
            );
            return discovered;
        } catch (RuntimeException e) {
            logError("[DynamicSuiteEngine] discover() failed: " + e.getMessage());
            e.printStackTrace(System.err);
            throw e;
        }
    }

    @Override
    public void execute(ExecutionRequest request) {
        try {
            io.cucumber.core.runner.Phase4Timing.mark("execute-start");
            logTrace("[DynamicSuiteEngine] execute() called");

            PickleballRunner suite = PickleballRunner.getInstance();
            logTrace("[DynamicSuiteEngine] Using suite singleton: " + suite.getClass().getName());

            MergedConfigurationParameters mergedParameters =
                    new MergedConfigurationParameters(
                            request.getConfigurationParameters(),
                            overlayTopLevelFeatures(suite.values())
                    );

            logTrace("[DynamicSuiteEngine] Final execution configuration parameters: "
                    + new TreeMap<>(materialize(mergedParameters)));

            ExecutionRequest wrappedRequest = ExecutionRequest.create(
                    request.getRootTestDescriptor(),
                    request.getEngineExecutionListener(),
                    mergedParameters
            );

                delegate.execute(wrappedRequest);
                logTrace("[DynamicSuiteEngine] Delegate execution completed");

        } catch (RuntimeException e) {
           logError("[DynamicSuiteEngine] execute() failed: " + e.getMessage());
            e.printStackTrace(System.err);
            throw e;
        }
    }

    /**
     * Copies {@code suiteValues} and, when {@code topLevelFeatures} is non-blank,
     * sets {@code cucumber.features} to that list. Does not change {@code pkb_features}
     * and does not mutate {@code suiteValues}. A blank value returns the original map.
     */
    public static Map<String, String> overlayTopLevelFeatures(
            Map<String, String> suiteValues,
            String topLevelFeatures
    ) {
        if (suiteValues == null) {
            return Map.of();
        }
        if (topLevelFeatures == null || topLevelFeatures.isBlank()) {
            return suiteValues;
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>(suiteValues);
        copy.put(FEATURES_PROPERTY_NAME, topLevelFeatures.trim());
        return copy;
    }

    static Map<String, String> overlayTopLevelFeatures(Map<String, String> suiteValues) {
        return overlayTopLevelFeatures(suiteValues, System.getProperty(TOP_LEVEL_FEATURES_PROPERTY));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, String> materialize(ConfigurationParameters parameters) {
        Map<String, String> out = new TreeMap<>();
        for (String key : parameters.keySet()) {
            parameters.get(key).ifPresent(value -> out.put(key, value));
        }
        return out;
    }
}