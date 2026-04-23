package tools.dscode.testengine;

import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static io.cucumber.core.options.Constants.FEATURES_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_NAME_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.GLUE_PROPERTY_NAME;


public abstract class DynamicSuiteBase {

    private static volatile DynamicSuiteBase INSTANCE;
    public static final String PKB_PREFIX = "pkb_";
    static final String PKB_GLUE = PKB_PREFIX + "glue";
    static final String PKB_FEATURES = PKB_PREFIX + "features";
    static final String PKB_TAGS = PKB_PREFIX + "tags";

    static final String CUCUMBER_GLUE = "cucumber.glue";
    static final String CUCUMBER_FEATURES = "cucumber.features";
    static final String CUCUMBER_TAGS = "cucumber.filter.tags";
    private static final String PKB_NAME =  PKB_PREFIX +"name";
    private static final String PKB_PARALLEL =  PKB_PREFIX + "parallel";

    private static final String CUCUMBER_PARALLEL_ENABLED = "cucumber.execution.parallel.enabled";
    private static final String CUCUMBER_PARALLEL_STRATEGY = "cucumber.execution.parallel.config.strategy";
    private static final String CUCUMBER_PARALLELISM = "cucumber.execution.parallel.config.fixed.parallelism";
    private static final String CUCUMBER_MAX_POOL_SIZE = "cucumber.execution.parallel.config.fixed.max-pool-size";

    protected final LinkedHashMap<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> readOnlyValues = Collections.unmodifiableMap(values);

    protected DynamicSuiteBase() {
        debug("Constructing suite subclass: " + getClass().getName());

        INSTANCE = this;

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

        debug("Final values after defaults + alias sync: " + values);

        INSTANCE = this;
        debug("Registered singleton instance: " + getClass().getName());
    }

    public void globalTestProperties() {
    }

    public final Map<String, String> values() {
        return readOnlyValues;
    }

    public final String get(String key) {
        return values.get(key);
    }

    public static String getTestConfigurationValue(String key) {
        return getInstance().get(key);
    }

    public static DynamicSuiteBase getInstance() {
        DynamicSuiteBase current = INSTANCE;
        if (current != null) {
            return current;
        }
        return DynamicSuiteBootstrap.initializeFromRuntimeClasspath();
    }

    static DynamicSuiteBase rawInstance() {
        return INSTANCE;
    }

    private void mergeResourcePropertiesIfMissing(String resourceName) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = DynamicSuiteBase.class.getClassLoader();
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
                    values.putIfAbsent(key, props.getProperty(key));
                }
            }

            debug("Loaded " + count + " resource(s) named " + resourceName);
        } catch (Exception e) {
            System.err.println("[DynamicSuiteBase] Failed loading " + resourceName + ": " + e);
            throw new RuntimeException("Failed loading " + resourceName, e);
        }
    }

    private void mergeResourcePropertiesOverwriting(String resourceName) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = DynamicSuiteBase.class.getClassLoader();
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
                    values.put(key, props.getProperty(key));
                }
            }

            debug("Loaded " + count + " resource(s) named " + resourceName);
        } catch (Exception e) {
            System.err.println("[DynamicSuiteBase] Failed loading " + resourceName + ": " + e);
            throw new RuntimeException("Failed loading " + resourceName, e);
        }
    }

    private void mergeAllSystemProperties() {
        Properties sys = System.getProperties();
        int count = 0;

        for (String key : sys.stringPropertyNames()) {
            values.put(key, sys.getProperty(key));
            count++;
        }

        debug("Applied " + count + " system property override(s)");
    }

    static boolean isSupportedProperty(String key) {
        return key != null && (
                key.startsWith("junit.jupiter.")
                        || key.startsWith("junit.platform.")
                        || key.startsWith("cucumber.")
        );
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

    private static void debug(String message) {
        System.err.println("[DynamicSuiteBase] " + message);
    }
}