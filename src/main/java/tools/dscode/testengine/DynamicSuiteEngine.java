package tools.dscode.testengine;

import io.cucumber.junit.platform.engine.CucumberTestEngine;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.discovery.ClasspathResourceSelector;
import org.junit.platform.engine.discovery.ClasspathRootSelector;
import org.junit.platform.engine.discovery.DirectorySelector;
import org.junit.platform.engine.discovery.FileSelector;
import org.junit.platform.engine.discovery.IterationSelector;
import org.junit.platform.engine.discovery.MethodSelector;
import org.junit.platform.engine.discovery.ModuleSelector;
import org.junit.platform.engine.discovery.NestedClassSelector;
import org.junit.platform.engine.discovery.NestedMethodSelector;
import org.junit.platform.engine.discovery.PackageSelector;
import org.junit.platform.engine.discovery.UniqueIdSelector;
import org.junit.platform.engine.discovery.UriSelector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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

    private static String cachedDiscoverKey;
    private static TestDescriptor cachedDiscoverDescriptor;

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
            String discoverKey = discoverKey(uniqueId, discoveryRequest, mergedParameters);
            TestDescriptor cached = takeCachedDiscover(discoverKey);
            if (cached != null) {
                io.cucumber.core.runner.Phase4Timing.mark("discover-cache-hit");
                return cached;
            }

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
            storeCachedDiscover(discoverKey, discovered);
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
            clearCachedDiscover();
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
        String expanded = expandTopLevelFeatures(topLevelFeatures);
        if (expanded == null || expanded.isBlank()) {
            return suiteValues;
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>(suiteValues);
        copy.put(FEATURES_PROPERTY_NAME, expanded);
        return copy;
    }

    /**
     * {@code @/path/to/top-features.txt} is the short form Workbench passes when the
     * path list would not fit a Windows command line. The file body is the same
     * comma-separated {@code wd-file:} list an inline property would have been.
     */
    public static String expandTopLevelFeatures(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (!value.startsWith("@")) return value;
        String path = value.substring(1).trim();
        if (path.isEmpty()) return value;
        try {
            return Files.readString(Path.of(path)).trim();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(
                    "Could not read Workbench feature list " + path + ": " + failure.getMessage(),
                    failure
            );
        }
    }

    static Map<String, String> overlayTopLevelFeatures(Map<String, String> suiteValues) {
        return overlayTopLevelFeatures(suiteValues, System.getProperty(TOP_LEVEL_FEATURES_PROPERTY));
    }

    private static final List<Class<? extends DiscoverySelector>> SELECTOR_TYPES = List.of(
            ClasspathRootSelector.class,
            ClasspathResourceSelector.class,
            ClassSelector.class,
            MethodSelector.class,
            NestedClassSelector.class,
            NestedMethodSelector.class,
            UniqueIdSelector.class,
            PackageSelector.class,
            FileSelector.class,
            DirectorySelector.class,
            UriSelector.class,
            IterationSelector.class,
            ModuleSelector.class
    );

    /**
     * Surefire discovers, then discovers again, then executes. The second
     * discovery is the same configuration and would rebuild every scenario.
     * One reuse is enough. Execution clears the entry so a later run with
     * the same configuration still sees current files. Selectors are part of
     * the key, so a narrower second request does not reuse a wider tree.
     */
    private static String discoverKey(UniqueId uniqueId, EngineDiscoveryRequest request, ConfigurationParameters parameters) {
        StringBuilder key = new StringBuilder();
        key.append(uniqueId).append('\n');
        for (Class<? extends DiscoverySelector> type : SELECTOR_TYPES) {
            List<? extends DiscoverySelector> selectors = request.getSelectorsByType(type);
            if (selectors.isEmpty()) {
                continue;
            }
            key.append(type.getName()).append(':');
            for (DiscoverySelector selector : selectors) {
                key.append(selector).append('|');
            }
            key.append('\n');
        }
        for (Map.Entry<String, String> entry : materialize(parameters).entrySet()) {
            key.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return key.toString();
    }

    private static synchronized TestDescriptor takeCachedDiscover(String key) {
        if (cachedDiscoverDescriptor == null || !key.equals(cachedDiscoverKey)) {
            return null;
        }
        TestDescriptor hit = cachedDiscoverDescriptor;
        cachedDiscoverDescriptor = null;
        cachedDiscoverKey = null;
        return hit;
    }

    private static synchronized void storeCachedDiscover(String key, TestDescriptor discovered) {
        cachedDiscoverKey = key;
        cachedDiscoverDescriptor = discovered;
    }

    private static synchronized void clearCachedDiscover() {
        cachedDiscoverKey = null;
        cachedDiscoverDescriptor = null;
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