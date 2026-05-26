package tools.dscode.testengine;

import io.cucumber.junit.platform.engine.CucumberTestEngine;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;

import java.util.Map;
import java.util.TreeMap;

import static tools.dscode.common.reporting.logging.LogForwarder.logError;
import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;

public final class DynamicSuiteEngine implements TestEngine {

    public static final String ENGINE_ID = "pickleball-test-engine";

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
            logTrace("[DynamicSuiteEngine] discover() called");
            PickleballRunner suite = DynamicSuiteBootstrap.initialize(discoveryRequest);
            logTrace("[DynamicSuiteEngine] Discovery succeeded using suite subclass: " + suite.getClass().getName());

            MergedConfigurationParameters mergedParameters =
                    new MergedConfigurationParameters(discoveryRequest.getConfigurationParameters(), suite.values());

            logTrace("[DynamicSuiteEngine] Final discovery configuration parameters: "
                    + new TreeMap<>(materialize(mergedParameters)));

            DynamicEngineDiscoveryRequest wrappedRequest =
                    new DynamicEngineDiscoveryRequest(discoveryRequest, mergedParameters);

            return delegate.discover(wrappedRequest, uniqueId);
        } catch (RuntimeException e) {
            logError("[DynamicSuiteEngine] discover() failed: " + e.getMessage());
            e.printStackTrace(System.err);
            throw e;
        }
    }

    @Override
    public void execute(ExecutionRequest request) {
        try {
            logTrace("[DynamicSuiteEngine] execute() called");

            PickleballRunner suite = PickleballRunner.getInstance();
            logTrace("[DynamicSuiteEngine] Using suite singleton: " + suite.getClass().getName());

            MergedConfigurationParameters mergedParameters =
                    new MergedConfigurationParameters(request.getConfigurationParameters(), suite.values());

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

    private static Map<String, String> materialize(ConfigurationParameters parameters) {
        Map<String, String> out = new TreeMap<>();
        for (String key : parameters.keySet()) {
            parameters.get(key).ifPresent(value -> out.put(key, value));
        }
        return out;
    }
}