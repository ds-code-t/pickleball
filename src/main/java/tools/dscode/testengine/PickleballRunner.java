package tools.dscode.testengine;

import io.cucumber.core.runner.CurrentScenarioState;
import tools.dscode.common.reporting.logging.Level;

import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static io.cucumber.core.options.Constants.FEATURES_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_NAME_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.GLUE_PROPERTY_NAME;
import static tools.dscode.common.reporting.logging.LogForwarder.logError;
import static tools.dscode.common.util.debug.DebugUtils.debugFlags;


public abstract class PickleballRunner {

    public static Level LOG_LEVEL = Level.INFO;

    static {
        EngineFilterBootstrap.ensureEngineFilterApplied("PickleballRunner.<clinit>");
        java.util.logging.Logger.getLogger("org.junit.platform.launcher.core").setLevel(java.util.logging.Level.SEVERE);
    }



    private static volatile PickleballRunner INSTANCE;
    public static final String PKB_PREFIX = "pkb_";
    static final String PKB_LOGLEVEL = PKB_PREFIX + "loglevel";
    static final String PKB_GLUE = PKB_PREFIX + "glue";
    static final String PKB_FEATURES = PKB_PREFIX + "features";
    static final String PKB_TAGS = PKB_PREFIX + "tags";

    static final String CUCUMBER_GLUE = "cucumber.glue";
    static final String CUCUMBER_FEATURES = "cucumber.features";
    static final String CUCUMBER_TAGS = "cucumber.filter.tags";
    private static final String PKB_NAME = PKB_PREFIX + "name";
    private static final String PKB_PARALLEL = PKB_PREFIX + "parallel";
    private static final String PKB_DEBUG_BROWSER = PKB_PREFIX + "debugbrowser";
    private static final String PKB_DEBUG_ARGS = PKB_PREFIX + "debugargs";

    private static final String CUCUMBER_PARALLEL_ENABLED = "cucumber.execution.parallel.enabled";
    private static final String CUCUMBER_PARALLEL_STRATEGY = "cucumber.execution.parallel.config.strategy";
    private static final String CUCUMBER_PARALLELISM = "cucumber.execution.parallel.config.fixed.parallelism";
    private static final String CUCUMBER_MAX_POOL_SIZE = "cucumber.execution.parallel.config.fixed.max-pool-size";

    protected final LinkedHashMap<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> readOnlyValues = Collections.unmodifiableMap(values);

    private static final String PKB_OPTIONS = PKB_PREFIX + "options";

    protected PickleballRunner() {
        debug("Constructing suite subclass: " + getClass().getName());

        EngineFilterBootstrap.ensureEngineFilterApplied("PickleballRunner.<init>");

        INSTANCE = this;

        globalTestDefaults();

        debug("Values after globalTestDefaults(): " + values);

        mergeResourcePropertiesIfMissing("pickleball.properties");
        mergeResourcePropertiesOverwriting("pickleball_local.properties");

        debug("Values after resource property merge: " + values);

        mergeAllSystemProperties();

        debug("Values after system property merge: " + values);

        globalTestProperties();

        debug("Values after globalTestProperties(): " + values);

        mergeAllSystemProperties();

        debug("Values after system property overrides: " + values);

        applyPkbAliases();

        debug("Values after pkb alias expansion: " + values);

        String detectedGlue = DynamicSuiteBootstrap.detectDefaultGluePackage();
        if (detectedGlue != null && !detectedGlue.isBlank()) {
            values.putIfAbsent(GLUE_PROPERTY_NAME, detectedGlue);
        }

        values.putIfAbsent(FEATURES_PROPERTY_NAME, "classpath:features");

        syncCanonicalAndAliasKeys();

        refreshPkbOptions();

        debug("Final values after defaults + alias sync: " + values);

        publishToSystemProperties();

        applyDebugFlags();

        INSTANCE = this;
        debug("Registered singleton instance: " + getClass().getName());

        values.putIfAbsent(PKB_LOGLEVEL,"INFO");
        LOG_LEVEL = Level.valueOf(values.get(PKB_LOGLEVEL).toUpperCase().trim());

    }

    public static String getOptionsString() {
        return INSTANCE.values.get(PKB_OPTIONS);
    }


    private void applyDebugFlags() {
        String debugArgs = values.get(PKB_DEBUG_ARGS);
        if (debugArgs != null && !debugArgs.isBlank()) {
            debugFlags.addAll(
                    Arrays.stream(debugArgs.split(","))
                            .filter(s -> !s.isBlank())
                            .map(s -> s.trim().toLowerCase())
                            .toList());
        }
        if (debugFlags.contains("logallsteps")) {
            CurrentScenarioState.logAllSteps = true;
        }

        String raw = values.get(PKB_DEBUG_BROWSER);
        if (raw == null || !"true".equalsIgnoreCase(raw.trim())) {
            return;
        }
        io.cucumber.core.runner.CurrentScenarioState.globalDebugBrowser = true;
        debug("Applied " + PKB_DEBUG_BROWSER + "=true -> CurrentScenarioState.globalDebugBrowser=true");
    }

    private void publishToSystemProperties() {
        int count = 0;
        int skipped = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }

            if (isForeignEngineDiscoveryKey(key)) {
                skipped++;
                continue;
            }
            System.setProperty(key, value);
            count++;
        }


        debug("Published " + count + " value(s) to system properties (skipped "
                + skipped + "key(s) that could trigger foreign TestEngine discovery)");
    }

    private static boolean isForeignEngineDiscoveryKey(String key) {
        return key.startsWith("cucumber.")
                || key.startsWith("junit.platform.")
                || key.startsWith("junit.jupiter.");
    }

    public void globalTestDefaults() {
    }

    public void globalTestProperties() {
    }


    public final Map<String, String> values() {
        return readOnlyValues;
    }

    public final String get(String key) {
        return values.get(normalizePkbKey(key));
    }

    public static String getTestConfigurationValue(String key) {
        return getInstance().get(key);
    }

    public static PickleballRunner getInstance() {
        PickleballRunner current = INSTANCE;
        if (current != null) {
            return current;
        }
        return DynamicSuiteBootstrap.initializeFromRuntimeClasspath();
    }

    static PickleballRunner rawInstance() {
        return INSTANCE;
    }

    private void mergeResourcePropertiesIfMissing(String resourceName) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = PickleballRunner.class.getClassLoader();
            }

            Enumeration<URL> resources = cl.getResources(resourceName);
            int count = 0;

            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                count++;
                debug("Loading resource properties from: " + url);

                Properties props = new Properties();
                try (InputStream in = url.openStream()) {
                    props.load(in);
                }

                for (String key : props.stringPropertyNames()) {
                    values.putIfAbsent(normalizePkbKey(key), props.getProperty(key));
                }
            }

            debug("Loaded " + count + " resource(s) named " + resourceName);
        } catch (Exception e) {
            logError("[DynamicSuiteBase] Failed loading " + resourceName + ": " + e);
            throw new RuntimeException("Failed loading " + resourceName, e);
        }
    }

    private void mergeResourcePropertiesOverwriting(String resourceName) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = PickleballRunner.class.getClassLoader();
            }

            Enumeration<URL> resources = cl.getResources(resourceName);
            int count = 0;

            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                count++;
                debug("Loading resource properties from: " + url);

                Properties props = new Properties();
                try (InputStream in = url.openStream()) {
                    props.load(in);
                }

                for (String key : props.stringPropertyNames()) {
                    values.put(normalizePkbKey(key), props.getProperty(key));
                }
            }

            debug("Loaded " + count + " resource(s) named " + resourceName);
        } catch (Exception e) {
            logError("[DynamicSuiteBase] Failed loading " + resourceName + ": " + e);
            throw new RuntimeException("Failed loading " + resourceName, e);
        }
    }

    private void mergeAllSystemProperties() {
        Properties sys = System.getProperties();
        int count = 0;
        int skipped = 0;

        for (String key : sys.stringPropertyNames()) {
            if (isDerivedInternalKey(key)) {
                skipped++;
                continue;
            }

            values.put(normalizePkbKey(key), sys.getProperty(key));
            count++;
        }

        debug("Applied " + count + " system property override(s), skipped "
                + skipped + " derived internal key(s)");
    }


    private void applyPkbAliases() {
        syncPair(PKB_GLUE, GLUE_PROPERTY_NAME);
        syncPair(PKB_FEATURES, FEATURES_PROPERTY_NAME);
        syncPair(PKB_NAME, FILTER_NAME_PROPERTY_NAME);
        syncPair(PKB_TAGS, FILTER_TAGS_PROPERTY_NAME);

        String parallel = values.get(PKB_PARALLEL);
        if (parallel != null && !parallel.isBlank()) {
            String trimmed = parallel.trim();
            Integer.parseInt(trimmed);

            values.put(PKB_PARALLEL, trimmed);
            values.putIfAbsent(CUCUMBER_PARALLEL_ENABLED, "true");
            values.putIfAbsent(CUCUMBER_PARALLEL_STRATEGY, "fixed");
            values.putIfAbsent(CUCUMBER_PARALLELISM, trimmed);
            values.putIfAbsent(CUCUMBER_MAX_POOL_SIZE, trimmed);
        }
    }

    private void syncCanonicalAndAliasKeys() {
        syncPair(PKB_GLUE, GLUE_PROPERTY_NAME);
        syncPair(PKB_FEATURES, FEATURES_PROPERTY_NAME);
        syncPair(PKB_NAME, FILTER_NAME_PROPERTY_NAME);
        syncPair(PKB_TAGS, FILTER_TAGS_PROPERTY_NAME);
    }

    private void syncPair(String aliasKey, String canonicalKey) {
        String aliasValue = values.get(aliasKey);
        String canonicalValue = values.get(canonicalKey);

        if (canonicalValue == null && aliasValue != null && !aliasValue.isBlank()) {
            canonicalValue = aliasValue;
            values.put(canonicalKey, canonicalValue);
        }

        if (aliasValue == null && canonicalValue != null && !canonicalValue.isBlank()) {
            aliasValue = canonicalValue;
            values.put(aliasKey, aliasValue);
        }
    }

    private String formatPkbOptions() {
        StringBuilder out = new StringBuilder();

        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (key == null
                    || value == null
                    || key.equals(PKB_OPTIONS)
                    || !key.startsWith(PKB_PREFIX)
                    || value.isBlank()) {
                continue;
            }

            if (!out.isEmpty()) {
                out.append(", ");
            }

            out.append(formatPkbOptionName(key))
                    .append("=")
                    .append(value.trim());
        }

        return out.toString();
    }

    private static String formatPkbOptionName(String key) {
        String name = key.substring(PKB_PREFIX.length());

        if (name.isBlank()) {
            return key;
        }

        return name;
    }

    private static void debug(String message) {
//        logTrace("[DynamicSuiteBase] " + message);
    }


    private void refreshPkbOptions() {
        values.remove(PKB_OPTIONS);

        String formatted = formatPkbOptions();

        if (formatted != null && !formatted.isBlank()) {
            values.put(PKB_OPTIONS, formatted);
        }
    }

    private static boolean isDerivedInternalKey(String key) {
        return PKB_OPTIONS.equals(normalizePkbKey(key));
    }


    static String normalizePkbKey(String key) {
        if (key == null) {
            return null;
        }

        return key.regionMatches(true, 0, PKB_PREFIX, 0, PKB_PREFIX.length())
                ? key.toLowerCase(Locale.ROOT)
                : key;
    }
}