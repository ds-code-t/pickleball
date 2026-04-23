package tools.dscode.testengine;

import java.util.LinkedHashMap;

public final class PKB_props {

    private static final String PREFIX = PickleballRunner.PKB_PREFIX;

    public static final String PKB_GLUE     = PREFIX + "glue";
    public static final String PKB_FEATURES = PREFIX + "features";
    public static final String PKB_TAGS     = PREFIX + "tags";
    public static final String PKB_NAME     = PREFIX + "name";
    public static final String PKB_PROFILE  = PREFIX + "profile";
    public static final String PKB_PLUGINS  = PREFIX + "plugins";
    public static final String PKB_PARALLEL = PREFIX + "parallel";

    private PKB_props() {}

    private static LinkedHashMap<String, String> values() {
        return PickleballRunner.rawInstance().values;
    }

    public static String get(String key) {
        String v = values().get(key);
        return v != null ? v : "";
    }

    public static void put(String key, String value) {
        values().put(key, value);
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

}