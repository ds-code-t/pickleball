package tools.dscode.testengine;

import com.epam.reportportal.utils.properties.PropertiesLoader;
import io.cucumber.core.runner.CurrentScenarioState;
import tools.dscode.common.reporting.logging.Level;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static io.cucumber.core.options.Constants.FEATURES_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_NAME_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PARALLEL_CONFIG_FIXED_MAX_POOL_SIZE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PARALLEL_CONFIG_FIXED_PARALLELISM_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PARALLEL_CONFIG_STRATEGY_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PARALLEL_EXECUTION_ENABLED_PROPERTY_NAME;
import static tools.dscode.common.reporting.logging.LogForwarder.logError;
import static tools.dscode.common.util.debug.DebugUtils.debugFlags;
import static tools.dscode.testengine.PKB_props.PKB_CUCUMBER_CLI_ARGS;
import static tools.dscode.testengine.PKB_props.PKB_CUCUMBER_CLI_FEATURE_SELECTORS;
import static tools.dscode.testengine.PKB_props.PKB_DEBUG_ARGS;
import static tools.dscode.testengine.PKB_props.PKB_DEBUG_BROWSER;
import static tools.dscode.testengine.PKB_props.PKB_FEATURES;
import static tools.dscode.testengine.PKB_props.PKB_GLUE;
import static tools.dscode.testengine.PKB_props.PKB_LOGLEVEL;
import static tools.dscode.testengine.PKB_props.PKB_NAME;
import static tools.dscode.testengine.PKB_props.PKB_OPTIONS;
import static tools.dscode.testengine.PKB_props.PKB_PARALLEL;
import static tools.dscode.testengine.PKB_props.PKB_PREFIX;
import static tools.dscode.testengine.PKB_props.PKB_PROFILE;
import static tools.dscode.testengine.PKB_props.PKB_RUN_PROFILE;
import static tools.dscode.testengine.PKB_props.PKB_TAGS;

public abstract class PickleballRunner {

    public static Level LOG_LEVEL = Level.INFO;

    static {
        EngineFilterBootstrap.ensureEngineFilterApplied("PickleballRunner.<clinit>");
        java.util.logging.Logger.getLogger("org.junit.platform.launcher.core").setLevel(java.util.logging.Level.SEVERE);
    }

    private static volatile PickleballRunner INSTANCE;

    protected final LinkedHashMap<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> readOnlyValues = Collections.unmodifiableMap(values);
    private boolean directRunProfile;

    protected PickleballRunner() {
        debug("Constructing suite subclass: " + getClass().getName());
        EngineFilterBootstrap.ensureEngineFilterApplied("PickleballRunner.<init>");
        INSTANCE = this;

        globalTestDefaults();
        debug("Values after globalTestDefaults(): " + values);

        mergeResourcePropertiesIfMissing("pickleball.properties");
        mergeResourcePropertiesOverwriting("pickleball_local.properties");
        mergeResourcePropertiesOverwriting("pickleball_local2.properties");
        debug("Values after resource property merge: " + values);

        mergeAllSystemProperties();
        debug("Values after system property merge: " + values);

        globalTestProperties();
        debug("Values after globalTestProperties(): " + values);

        mergeAllSystemProperties();
        mergeReportPortalSourceProperties();
        debug("Values after system property overrides: " + values);

        /* Build the backward-compatible default_profile from the fully resolved legacy state. */
        applyPkbAliases();
        syncReportPortalAliases(false);
        applyLegacyFrameworkDefaults();
        syncCanonicalAndAliasKeys();
        syncReportPortalAliases(false);

        PickleballProfiles.Resolution profileResolution = PickleballProfiles.apply(values);
        directRunProfile = profileResolution.direct();

        /* The selected/direct profile is now authoritative. Only aliases are derived after this point. */
        applyPkbAliases();
        syncCanonicalAndAliasKeys();
        syncReportPortalAliases(true);
        refreshRunProfile();
        refreshPkbOptions();

        debug("Final values after profile resolution + alias sync: " + values);
        publishToSystemProperties();
        applyDebugFlags();

        INSTANCE = this;
        debug("Registered singleton instance: " + getClass().getName());

        String configuredLogLevel = get(PKB_LOGLEVEL);
        String effectiveLogLevel = configuredLogLevel == null || configuredLogLevel.isBlank()
                ? "INFO"
                : configuredLogLevel.trim();
        LOG_LEVEL = Level.valueOf(effectiveLogLevel.toUpperCase(Locale.ROOT));
    }

    public static String getOptionsString() {
        return getInstance().values.get(PKB_OPTIONS);
    }

    /** True when the current runner was resolved from a direct {@code pkb_run_profile}. */
    public static boolean isDirectRunProfileActive() {
        PickleballRunner current = INSTANCE;
        return current != null && current.directRunProfile;
    }

    public static Properties getReportPortalProperties() {
        Properties properties = new Properties();
        getInstance().values.forEach((key, value) -> {
            if (key != null && value != null && key.toLowerCase(Locale.ROOT).startsWith("rp.")) {
                properties.setProperty(key, value);
            }
        });
        return properties;
    }

    public synchronized void captureCucumberCliArgs(String[] argv) {
        if (directRunProfile) {
            return;
        }

        String[] args = argv == null ? new String[0] : Arrays.copyOf(argv, argv.length);
        values.put(PKB_CUCUMBER_CLI_ARGS, formatCliArgs(args));

        CliOptionProjection projection = projectCucumberCliArgs(args);
        putCliOverride(PKB_TAGS, FILTER_TAGS_PROPERTY_NAME, joinTagExpressions(projection.tags));
        putCliOverride(PKB_NAME, FILTER_NAME_PROPERTY_NAME, joinNameFilters(projection.names));
        putCliOverride(PKB_GLUE, GLUE_PROPERTY_NAME, joinCommaSeparated(projection.glue));
        putCliReference(PKB_CUCUMBER_CLI_FEATURE_SELECTORS, formatCliArgs(projection.features));

        refreshRunProfile();
        refreshPkbOptions();
    }

    private void applyLegacyFrameworkDefaults() {
        String detectedGlue = DynamicSuiteBootstrap.detectDefaultGluePackage();
        if (detectedGlue != null && !detectedGlue.isBlank()) {
            values.putIfAbsent(GLUE_PROPERTY_NAME, detectedGlue);
        }
        values.putIfAbsent(FEATURES_PROPERTY_NAME, "classpath:features");
    }

    private void applyDebugFlags() {
        String debugArgs = get(PKB_DEBUG_ARGS);
        if (debugArgs != null && !debugArgs.isBlank()) {
            debugFlags.addAll(
                    Arrays.stream(debugArgs.split(","))
                            .filter(s -> !s.isBlank())
                            .map(s -> s.trim().toLowerCase(Locale.ROOT))
                            .toList());
        }
        if (debugFlags.contains("logallsteps")) {
            CurrentScenarioState.logAllSteps = true;
        }

        String raw = get(PKB_DEBUG_BROWSER);
        if (raw == null || !"true".equalsIgnoreCase(raw.trim())) {
            return;
        }
        CurrentScenarioState.globalDebugBrowser = true;
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

    private void mergeReportPortalSourceProperties() {
        try {
            Properties reportPortal = PropertiesLoader.load().getProperties();
            for (String key : reportPortal.stringPropertyNames()) {
                if (key.toLowerCase(Locale.ROOT).startsWith("rp.")) {
                    values.putIfAbsent(key.toLowerCase(Locale.ROOT), reportPortal.getProperty(key));
                }
            }

            /* Native/aliased JVM values remain the strongest normal source. */
            Properties system = System.getProperties();
            for (String key : system.stringPropertyNames()) {
                String normalized = key.toLowerCase(Locale.ROOT);
                if (!normalized.startsWith("rp.")) {
                    continue;
                }
                String alias = PickleballProfiles.reportPortalAliasKey(normalized);
                String explicitAlias = system.getProperty(alias);
                values.put(normalized, system.getProperty(key));
                values.put(alias, explicitAlias != null ? explicitAlias : system.getProperty(key));
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Failed loading ReportPortal configuration", exception);
        }
    }

    private void applyPkbAliases() {
        syncPair(PKB_GLUE, GLUE_PROPERTY_NAME);
        syncPair(PKB_FEATURES, FEATURES_PROPERTY_NAME);
        syncPair(PKB_NAME, FILTER_NAME_PROPERTY_NAME);
        syncPair(PKB_TAGS, FILTER_TAGS_PROPERTY_NAME);

        String parallel = get(PKB_PARALLEL);
        if (parallel != null && !parallel.isBlank()) {
            String trimmed = parallel.trim();
            Integer.parseInt(trimmed);

            values.put(PKB_PARALLEL, trimmed);
            values.putIfAbsent(PARALLEL_EXECUTION_ENABLED_PROPERTY_NAME, "true");
            values.putIfAbsent(PARALLEL_CONFIG_STRATEGY_PROPERTY_NAME, "fixed");
            values.putIfAbsent(PARALLEL_CONFIG_FIXED_PARALLELISM_PROPERTY_NAME, trimmed);
            values.putIfAbsent(PARALLEL_CONFIG_FIXED_MAX_POOL_SIZE_PROPERTY_NAME, trimmed);
        }
    }

    private void syncCanonicalAndAliasKeys() {
        syncPair(PKB_GLUE, GLUE_PROPERTY_NAME);
        syncPair(PKB_FEATURES, FEATURES_PROPERTY_NAME);
        syncPair(PKB_NAME, FILTER_NAME_PROPERTY_NAME);
        syncPair(PKB_TAGS, FILTER_TAGS_PROPERTY_NAME);
    }

    private void syncReportPortalAliases(boolean aliasWins) {
        List<Map.Entry<String, String>> snapshot = new ArrayList<>(values.entrySet());
        for (Map.Entry<String, String> entry : snapshot) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null || value.isBlank()) {
                continue;
            }
            String alias = PickleballProfiles.reportPortalAliasKey(key);
            if (alias != null) {
                values.putIfAbsent(alias, value);
            }
        }

        snapshot = new ArrayList<>(values.entrySet());
        for (Map.Entry<String, String> entry : snapshot) {
            String canonical = PickleballProfiles.reportPortalCanonicalKey(entry.getKey());
            String value = entry.getValue();
            if (canonical == null || value == null || value.isBlank()) {
                continue;
            }
            if (aliasWins) {
                values.put(canonical, value);
            } else {
                values.putIfAbsent(canonical, value);
            }
        }
    }

    private void syncPair(String aliasKey, String canonicalKey) {
        String aliasValue = values.get(aliasKey);
        String canonicalValue = values.get(canonicalKey);

        if (canonicalValue == null && aliasValue != null && !aliasValue.isBlank()) {
            values.put(canonicalKey, aliasValue);
        }

        if (aliasValue == null && canonicalValue != null && !canonicalValue.isBlank()) {
            values.put(aliasKey, canonicalValue);
        }
    }

    private void putCliOverride(String aliasKey, String canonicalKey, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String trimmed = value.trim();
        values.put(aliasKey, trimmed);
        values.put(canonicalKey, trimmed);
    }

    private void putCliReference(String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        values.put(key, value.trim());
    }

    private static CliOptionProjection projectCucumberCliArgs(String[] args) {
        CliOptionProjection projection = new CliOptionProjection();
        if (args == null || args.length == 0) {
            return projection;
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }

            arg = arg.trim();
            if (arg.isEmpty()) {
                continue;
            }

            switch (arg) {
                case "--tags", "-t" -> i = addNext(args, i, projection.tags);
                case "--name", "-n" -> i = addNext(args, i, projection.names);
                case "--glue", "-g" -> i = addNext(args, i, projection.glue);
                case "--threads", "--plugin", "-p", "--snippets", "--order", "--count",
                     "--object-factory", "--uuid-generator", "--i18n" -> i = skipNext(args, i);
                case "--help", "-h", "--version", "-v", "--publish", "--dry-run", "-d",
                     "--no-dry-run", "--no-summary", "--monochrome", "-m", "--no-monochrome",
                     "--wip", "-w" -> {
                }
                default -> {
                    if (!arg.startsWith("-")) {
                        projection.features.add(arg);
                    }
                }
            }
        }
        return projection;
    }

    private static int addNext(String[] args, int index, List<String> out) {
        int valueIndex = index + 1;
        if (valueIndex < args.length) {
            String value = args[valueIndex];
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
            return valueIndex;
        }
        return index;
    }

    private static int skipNext(String[] args, int index) {
        return index + 1 < args.length ? index + 1 : index;
    }

    private static String joinTagExpressions(List<String> expressions) {
        if (expressions == null || expressions.isEmpty()) {
            return null;
        }
        if (expressions.size() == 1) {
            return expressions.getFirst();
        }
        return expressions.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> "(" + s + ")")
                .collect(java.util.stream.Collectors.joining(" and "));
    }

    private static String joinNameFilters(List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        if (names.size() == 1) {
            return names.getFirst();
        }
        return names.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> "(?:" + s + ")")
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private static String joinCommaSeparated(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String formatCliArgs(String[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        return Arrays.stream(args)
                .map(arg -> arg == null ? "" : arg)
                .map(PickleballRunner::quoteCliArg)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String formatCliArgs(List<String> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        return formatCliArgs(args.toArray(new String[0]));
    }

    private static String quoteCliArg(String arg) {
        if (arg.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuotes = arg.chars().anyMatch(Character::isWhitespace)
                || arg.indexOf('"') >= 0
                || arg.indexOf('\\') >= 0;
        if (!needsQuotes) {
            return arg;
        }
        return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final class CliOptionProjection {
        private final List<String> tags = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private final List<String> glue = new ArrayList<>();
        private final List<String> features = new ArrayList<>();
    }

    private void refreshRunProfile() {
        values.remove(PKB_RUN_PROFILE);
        String serialized = PickleballProfiles.serializeRunProfile(values);
        if (!serialized.isBlank()) {
            values.put(PKB_RUN_PROFILE, serialized);
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
                    || key.equals(PKB_RUN_PROFILE)
                    || key.startsWith(PickleballProfiles.INLINE_PROFILE_PREFIX)
                    || PKB_props.isRunMetadataKey(key)
                    || !key.startsWith(PKB_PREFIX)
                    || value.isBlank()) {
                continue;
            }

            if (!out.isEmpty()) {
                out.append(", ");
            }

            out.append(formatPkbOptionName(key))
                    .append("=")
                    .append(SensitiveConfiguration.displayValue(key, value.trim()));
        }

        return out.toString();
    }

    private static String formatPkbOptionName(String key) {
        String name = key.substring(PKB_PREFIX.length());
        return name.isBlank() ? key : name;
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
