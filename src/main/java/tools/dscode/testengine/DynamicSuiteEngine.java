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

public final class DynamicSuiteEngine implements TestEngine {

    public static final String ENGINE_ID = "dynamic-cucumber-suite";

    private final CucumberTestEngine delegate = new CucumberTestEngine();

    @Override
    public String getId() {
        return ENGINE_ID;
    }

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest discoveryRequest, UniqueId uniqueId) {
        try {
            System.err.println("[DynamicSuiteEngine] discover() called");
            PickleballRunner suite = DynamicSuiteBootstrap.initialize(discoveryRequest);
            System.err.println("[DynamicSuiteEngine] Discovery succeeded using suite subclass: " + suite.getClass().getName());

            MergedConfigurationParameters mergedParameters =
                    new MergedConfigurationParameters(discoveryRequest.getConfigurationParameters(), suite.values());

            System.err.println("[DynamicSuiteEngine] Final discovery configuration parameters: "
                    + new TreeMap<>(materialize(mergedParameters)));

            DynamicEngineDiscoveryRequest wrappedRequest =
                    new DynamicEngineDiscoveryRequest(discoveryRequest, mergedParameters);

            Map<String, String> previous = applyMergedSystemProperties(suite.values());
            try {
                return delegate.discover(wrappedRequest, uniqueId);
            } finally {
                restoreSystemProperties(previous, suite.values());
            }
        } catch (RuntimeException e) {
            System.err.println("[DynamicSuiteEngine] discover() failed: " + e.getMessage());
            e.printStackTrace(System.err);
            throw e;
        }
    }

    @Override
    public void execute(ExecutionRequest request) {
        try {
            System.err.println("[DynamicSuiteEngine] execute() called");

            PickleballRunner suite = PickleballRunner.getInstance();
            System.err.println("[DynamicSuiteEngine] Using suite singleton: " + suite.getClass().getName());

            MergedConfigurationParameters mergedParameters =
                    new MergedConfigurationParameters(request.getConfigurationParameters(), suite.values());

            System.err.println("[DynamicSuiteEngine] Final execution configuration parameters: "
                    + new TreeMap<>(materialize(mergedParameters)));

            ExecutionRequest wrappedRequest = ExecutionRequest.create(
                    request.getRootTestDescriptor(),
                    request.getEngineExecutionListener(),
                    mergedParameters
            );

            Map<String, String> previous = applyMergedSystemProperties(suite.values());
            try {
                delegate.execute(wrappedRequest);
                System.err.println("[DynamicSuiteEngine] Delegate execution completed");
            } finally {
                restoreSystemProperties(previous, suite.values());
            }
        } catch (RuntimeException e) {
            System.err.println("[DynamicSuiteEngine] execute() failed: " + e.getMessage());
            e.printStackTrace(System.err);
            throw e;
        }
    }

    private static Map<String, String> applyMergedSystemProperties(Map<String, String> values) {
        Map<String, String> previous = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!PickleballRunner.isSupportedProperty(key)) {
                continue;
            }

            previous.put(key, System.getProperty(key));

            String value = entry.getValue();
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        }

        System.err.println("[DynamicSuiteEngine] Applied merged junit/cucumber system properties");
        return previous;
    }

    private static void restoreSystemProperties(Map<String, String> previous, Map<String, String> values) {
        for (String key : values.keySet()) {
            if (!PickleballRunner.isSupportedProperty(key)) {
                continue;
            }

            String oldValue = previous.get(key);
            if (oldValue == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, oldValue);
            }
        }

        System.err.println("[DynamicSuiteEngine] Restored previous junit/cucumber system properties");
    }

    private static Map<String, String> materialize(ConfigurationParameters parameters) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : parameters.keySet()) {
            parameters.get(key).ifPresent(value -> out.put(key, value));
        }
        return out;
    }
}