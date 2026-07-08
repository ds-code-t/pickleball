package tools.dscode.testengine;

import java.util.LinkedHashMap;

/**
 * Canonical definitions for pickleball {@code pkb_*} configuration property names.
 * <p>
 * Cucumber {@code cucumber.*} property names are defined in
 * {@link io.cucumber.core.options.Constants} and
 * {@link io.cucumber.junit.platform.engine.Constants}.
 */
public final class PKB_props {

    public static final String PKB_PREFIX = "pkb_";

    public static final String PKB_GLUE = PKB_PREFIX + "glue";
    public static final String PKB_FEATURES = PKB_PREFIX + "features";
    public static final String PKB_FEATURE_NAME = PKB_PREFIX + "featurename";
    public static final String PKB_TAGS = PKB_PREFIX + "tags";
    public static final String PKB_NAME = PKB_PREFIX + "name";
    public static final String PKB_ORDER = PKB_PREFIX + "order";
    public static final String PKB_LIMIT = PKB_PREFIX + "limit";
    public static final String PKB_PROFILE = PKB_PREFIX + "profile";
    public static final String PKB_PLUGINS = PKB_PREFIX + "plugins";
    public static final String PKB_PARALLEL = PKB_PREFIX + "parallel";
    public static final String PKB_ENVIRONMENT = PKB_PREFIX + "environment";
    public static final String PKB_BROWSER = PKB_PREFIX + "browser";

    public static final String PKB_OPTIONS = PKB_PREFIX + "options";
    public static final String PKB_CUCUMBER_CLI_ARGS = PKB_PREFIX + "cucumber_cli_args";
    public static final String PKB_CUCUMBER_CLI_FEATURE_SELECTORS =  PKB_PREFIX + "cucumber_cli_feature_selectors";
    public static final String PKB_LOGLEVEL = PKB_PREFIX + "loglevel";

    public static final String PKB_DEBUG_BROWSER = PKB_PREFIX + "debugBrowser";
    public static final String PKB_DEBUG_ARGS = PKB_PREFIX + "debugargs";

    private PKB_props() {
    }

    private static LinkedHashMap<String, String> values() {
        return PickleballRunner.rawInstance().values;
    }

    public static String get(String key) {
        String v = values().get(PickleballRunner.normalizePkbKey(key));
        return v != null ? v : "";
    }

    public static void put(String key, String value) {
        values().put(PickleballRunner.normalizePkbKey(key), value);
    }

    public static String browser() {
        return get(PKB_BROWSER);
    }

    public static void browser(String browser) {
        put(PKB_BROWSER, browser);
    }

    public static String environment() {
        return get(PKB_ENVIRONMENT);
    }

    public static void environment(String environment) {
        put(PKB_ENVIRONMENT, environment);
    }

    // -- glue --
    public static String glue() {
        return get(PKB_GLUE);
    }

    public static void glue(String gluePaths) {
        put(PKB_GLUE, gluePaths);
    }

    // -- features --
    public static String features() {
        return get(PKB_FEATURES);
    }

    public static void features(String featurePaths) {
        put(PKB_FEATURES, featurePaths);
    }

    // -- feature name filter --
    public static String featureName() {
        return get(PKB_FEATURE_NAME);
    }

    public static void featureName(String featureName) {
        put(PKB_FEATURE_NAME, featureName);
    }

    // -- tags --
    public static String tags() {
        return get(PKB_TAGS);
    }

    public static void tags(String tagExpression) {
        put(PKB_TAGS, tagExpression);
    }

    // -- name filter --
    public static String name() {
        return get(PKB_NAME);
    }

    public static void name(String nameRegex) {
        put(PKB_NAME, nameRegex);
    }

    // -- plugins --
    public static String plugins() {
        return get(PKB_PLUGINS);
    }

    public static void plugins(String pluginConfig) {
        put(PKB_PLUGINS, pluginConfig);
    }

    // -- profile --
    public static String profile() {
        return get(PKB_PROFILE);
    }

    public static void profile(String profileName) {
        put(PKB_PROFILE, profileName);
    }

    // -- parallel --
    public static String parallel() {
        return get(PKB_PARALLEL);
    }

    public static void parallel(String count) {
        put(PKB_PARALLEL, count);
    }

    public static String debugBrowser() {
        return get(PKB_DEBUG_BROWSER);
    }

    public static void debugBrowser(String enable) {
        put(PKB_DEBUG_BROWSER, enable);
    }

    public static void debugBrowser(boolean enable) {
        put(PKB_DEBUG_BROWSER, Boolean.toString(enable));
    }

}
