package tools.dscode.testengine;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Canonical definitions for Pickleball {@code pkb_*} configuration property names. */
public final class PKB_props {

    public static final String PKB_PREFIX = "pkb_";
    public static final String PKB_GLUE = PKB_PREFIX + "glue";
    public static final String PKB_FEATURES = PKB_PREFIX + "features";
    public static final String PKB_FEATURE_NAME = PKB_PREFIX + "featurename";
    public static final String PKB_DATA_PATH = PKB_PREFIX + "datapath";
    public static final String PKB_CALL_PATH = PKB_PREFIX + "callpath";
    public static final String PKB_COMPONENT_PATH = PKB_PREFIX + "componentpath";
    public static final String PKB_CONFIG_PATH = PKB_PREFIX + "configpath";
    public static final String PKB_TAGS = PKB_PREFIX + "tags";
    public static final String PKB_NAME = PKB_PREFIX + "name";
    public static final String PKB_ORDER = PKB_PREFIX + "order";
    public static final String PKB_LIMIT = PKB_PREFIX + "limit";
    public static final String PKB_PROFILE = PKB_PREFIX + "profile";
    public static final String PKB_RUN_VARS = PKB_PREFIX + "runvars";
    public static final String PKB_RUN_VARS_PREFIX = PKB_RUN_VARS + ".";
    public static final String PKB_RUN_PROFILE = PKB_PREFIX + "run_profile";
    public static final String PKB_RUN_PROFILE_PREFIX = PKB_RUN_PROFILE + ".";
    public static final String PKB_RP_PREFIX = PKB_PREFIX + "rp_";
    public static final String PKB_RP_ENABLE = PKB_RP_PREFIX + "enable";
    public static final String PKB_RP_ENDPOINT = PKB_RP_PREFIX + "endpoint";
    public static final String PKB_RP_PROJECT = PKB_RP_PREFIX + "project";
    public static final String PKB_RP_LAUNCH = PKB_RP_PREFIX + "launch";
    public static final String PKB_RP_DESCRIPTION = PKB_RP_PREFIX + "description";
    public static final String PKB_RP_API_KEY = PKB_RP_PREFIX + "api_key";
    public static final String PKB_PLUGINS = PKB_PREFIX + "plugins";
    public static final String PKB_PARALLEL = PKB_PREFIX + "parallel";
    public static final String PKB_ENVIRONMENT = PKB_PREFIX + "environment";
    public static final String PKB_BROWSER = PKB_PREFIX + "browser";
    public static final String PKB_OPTIONS = PKB_PREFIX + "options";
    public static final String PKB_CUCUMBER_CLI_ARGS = PKB_PREFIX + "cucumber_cli_args";
    public static final String PKB_CUCUMBER_CLI_FEATURE_SELECTORS = PKB_PREFIX + "cucumber_cli_feature_selectors";
    public static final String PKB_LOGLEVEL = PKB_PREFIX + "loglevel";
    public static final String PKB_REPORTING_MODE = PKB_PREFIX + "reportingmode";
    public static final String PKB_REPORT_RETENTION = PKB_PREFIX + "reportretention";
    public static final String PKB_DIAGNOSTIC_OUTPUT = PKB_PREFIX + "diagnostic_output";
    public static final String PKB_PLATFORM_LOG = PKB_PREFIX + "platformlog";
    public static final String PKB_GIT_SNAPSHOT = PKB_PREFIX + "gitsnapshot";
    public static final String PKB_INVESTIGATION_ID = PKB_PREFIX + "investigation_id";
    public static final String PKB_RUN_PURPOSE = PKB_PREFIX + "run_purpose";
    public static final String PKB_PARENT_RUN_ID = PKB_PREFIX + "parent_run_id";
    public static final String PKB_BASELINE_RUN_ID = PKB_PREFIX + "baseline_run_id";
    public static final String PKB_CHANGED_VARIABLES = PKB_PREFIX + "changed_variables";

    public static final String PKB_DEBUG_BROWSER = PKB_PREFIX + "debugBrowser";
    public static final String PKB_DEBUG_ARGS = PKB_PREFIX + "debugargs";

    private static final Set<String> RUN_METADATA_KEYS = Set.of(
            PKB_INVESTIGATION_ID,
            PKB_RUN_PURPOSE,
            PKB_PARENT_RUN_ID,
            PKB_BASELINE_RUN_ID,
            PKB_CHANGED_VARIABLES
    );

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

    /** Diagnostic/investigation lineage that describes a run but does not control execution. */
    public static boolean isRunMetadataKey(String key) {
        String normalized = PickleballRunner.normalizePkbKey(key);
        return normalized != null && RUN_METADATA_KEYS.contains(normalized);
    }

    /** True only for effective execution RunVars, not profile controls, derived summaries, or run metadata. */
    public static boolean isRunVariableKey(String key) {
        String normalized = PickleballRunner.normalizePkbKey(key);
        if (normalized == null || !normalized.startsWith(PKB_PREFIX)) {
            return false;
        }
        return !isRunMetadataKey(normalized)
                && !normalized.equals(PKB_PROFILE)
                && !normalized.equals(PKB_RUN_VARS)
                && !normalized.startsWith(PKB_RUN_VARS_PREFIX)
                && !normalized.equals(PKB_RUN_PROFILE)
                && !normalized.startsWith(PKB_RUN_PROFILE_PREFIX)
                && !normalized.equals(PKB_OPTIONS)
                && !normalized.startsWith(PKB_PROFILE + "_");
    }

    /** True for expanded direct-run members such as {@code pkb_runvars.pkb_browser}. */
    public static boolean isRunVarsMemberKey(String key) {
        String normalized = PickleballRunner.normalizePkbKey(key);
        return normalized != null && normalized.startsWith(PKB_RUN_VARS_PREFIX);
    }

    /** True for reserved expanded internal-output names such as {@code pkb_run_profile.pkb_browser}. */
    public static boolean isRunProfileMemberKey(String key) {
        String normalized = PickleballRunner.normalizePkbKey(key);
        return normalized != null && normalized.startsWith(PKB_RUN_PROFILE_PREFIX);
    }

    public static String browser() { return get(PKB_BROWSER); }
    public static void browser(String browser) { put(PKB_BROWSER, browser); }
    public static String environment() { return get(PKB_ENVIRONMENT); }
    public static void environment(String environment) { put(PKB_ENVIRONMENT, environment); }
    public static String glue() { return get(PKB_GLUE); }
    public static void glue(String gluePaths) { put(PKB_GLUE, gluePaths); }
    public static String features() { return get(PKB_FEATURES); }
    public static void features(String featurePaths) { put(PKB_FEATURES, featurePaths); }
    public static String dataPath() { return get(PKB_DATA_PATH); }
    public static void dataPath(String dataPath) { put(PKB_DATA_PATH, dataPath); }
    public static String callPath() { return get(PKB_CALL_PATH); }
    public static void callPath(String callPath) { put(PKB_CALL_PATH, callPath); }
    public static String componentPath() { return get(PKB_COMPONENT_PATH); }
    public static void componentPath(String componentPath) { put(PKB_COMPONENT_PATH, componentPath); }
    public static String configPath() { return get(PKB_CONFIG_PATH); }
    public static void configPath(String configPath) { put(PKB_CONFIG_PATH, configPath); }
    public static String featureName() { return get(PKB_FEATURE_NAME); }
    public static void featureName(String featureName) { put(PKB_FEATURE_NAME, featureName); }
    public static String tags() { return get(PKB_TAGS); }
    public static void tags(String tagExpression) { put(PKB_TAGS, tagExpression); }
    public static String name() { return get(PKB_NAME); }
    public static void name(String nameRegex) { put(PKB_NAME, nameRegex); }
    public static String plugins() { return get(PKB_PLUGINS); }
    public static void plugins(String pluginConfig) { put(PKB_PLUGINS, pluginConfig); }
    public static String profile() { return get(PKB_PROFILE); }
    public static void profile(String profileName) { put(PKB_PROFILE, profileName); }

    public static void profile(String... profileNames) {
        put(PKB_PROFILE, Arrays.stream(profileNames)
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining(",")));
    }

    public static void profileDefinition(String profileName, String assignments) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("profileName cannot be blank");
        }
        put(PKB_PROFILE + "_" + profileName.trim(), assignments);
    }

    /** Canonical deterministic serialization of the resolved RunVars actually used for this run. */
    public static String runProfile() { return get(PKB_RUN_PROFILE); }

    /** Parse a compact RunVar assignment string such as a retained {@code pkb_run_profile}. */
    public static LinkedHashMap<String, String> parseAssignments(String compact) {
        return PickleballProfiles.parseAssignments(compact);
    }

    /** Serialize execution RunVars in the same deterministic compact form as {@code pkb_run_profile}. */
    public static String serializeRunVars(Map<String, String> values) {
        return PickleballProfiles.serializeRunProfile(values);
    }

    /** Preferred direct RunVar input. Missing execution-context keys inherit; explicit blanks suppress inheritance. */
    public static void runVars(String assignments) {
        clearDirectRunControls();
        put(PKB_RUN_VARS, assignments);
    }

    /** Expanded direct RunVar input without compact assignment parsing. */
    public static void runVars(Map<String, String> runVars) {
        if (runVars == null) {
            throw new IllegalArgumentException("runVars cannot be null");
        }
        clearDirectRunControls();
        runVars.forEach((key, value) -> {
            String profileKey = key == null ? null : key.trim();
            String normalized = profileKey != null && profileKey.toLowerCase(java.util.Locale.ROOT).startsWith("rp.")
                    ? PickleballProfiles.reportPortalAliasKey(profileKey)
                    : PickleballRunner.normalizePkbKey(profileKey);
            if (!isRunVariableKey(normalized)) {
                throw new IllegalArgumentException("RunVar property '" + key + "' is not a Pickleball run variable.");
            }
            values().put(PKB_RUN_VARS_PREFIX + normalized, value == null ? "" : value);
        });
    }

    private static void clearDirectRunControls() {
        values().remove(PKB_RUN_VARS);
        values().keySet().removeIf(PKB_props::isRunVarsMemberKey);
    }

    public static String reportPortal(String nativePropertyName) {
        String alias = PickleballProfiles.reportPortalAliasKey(nativePropertyName);
        if (alias == null) throw new IllegalArgumentException("ReportPortal property must start with 'rp.': " + nativePropertyName);
        return get(alias);
    }

    public static void reportPortal(String nativePropertyName, String value) {
        String alias = PickleballProfiles.reportPortalAliasKey(nativePropertyName);
        if (alias == null) throw new IllegalArgumentException("ReportPortal property must start with 'rp.': " + nativePropertyName);
        put(alias, value);
    }

    public static String parallel() { return get(PKB_PARALLEL); }
    public static void parallel(String count) { put(PKB_PARALLEL, count); }
    public static String reportingMode() { return get(PKB_REPORTING_MODE); }
    public static void reportingMode(String reportingMode) { put(PKB_REPORTING_MODE, reportingMode); }
    public static String reportRetention() { return get(PKB_REPORT_RETENTION); }
    public static void reportRetention(String reportRetention) { put(PKB_REPORT_RETENTION, reportRetention); }
    public static String diagnosticOutput() { return get(PKB_DIAGNOSTIC_OUTPUT); }
    public static void diagnosticOutput(String diagnosticOutput) { put(PKB_DIAGNOSTIC_OUTPUT, diagnosticOutput); }
    public static String platformLog() { return get(PKB_PLATFORM_LOG); }
    public static void platformLog(String platformLog) { put(PKB_PLATFORM_LOG, platformLog); }
    public static String gitSnapshot() { return get(PKB_GIT_SNAPSHOT); }
    public static void gitSnapshot(String gitSnapshot) { put(PKB_GIT_SNAPSHOT, gitSnapshot); }
    public static String debugBrowser() { return get(PKB_DEBUG_BROWSER); }
    public static void debugBrowser(String enable) { put(PKB_DEBUG_BROWSER, enable); }
    public static void debugBrowser(boolean enable) { put(PKB_DEBUG_BROWSER, Boolean.toString(enable)); }
}
