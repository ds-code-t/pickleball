package tools.dscode.common.reporting.diagnostic;

import tools.dscode.parallelutilities.ParallelCountEstimator;
import tools.dscode.testengine.PKB_props;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds the complete AI Discover/Confirm {@code pkb_runvars} set. */
public final class AgentDiscoverPlanner {
    private AgentDiscoverPlanner() {
    }

    public record Plan(
            Path projectRoot,
            AgentBrowserLadder.Decision browser,
            String runVars,
            String tags,
            String name
    ) {
    }

    public static Plan discover(Path projectRoot, String tags, String name) {
        return discover(projectRoot, tags, name, null, null);
    }

    public static Plan discover(Path projectRoot, String tags, String name, String retention) {
        return discover(projectRoot, tags, name, retention, null);
    }

    public static Plan discover(
            Path projectRoot,
            String tags,
            String name,
            String retention,
            String example
    ) {
        return discover(projectRoot, tags, name, retention, example, null, null);
    }

    public static Plan discover(
            Path projectRoot,
            String tags,
            String name,
            String retention,
            String example,
            List<String> cliRunVars,
            Appendable warnings
    ) {
        AgentBrowserLadder.Decision browser = AgentBrowserLadder.select(projectRoot);
        LinkedHashMap<String, String> values = baseDiscoverVars(browser.browser());
        overlaySelection(values, tags, name, retention, example);
        applyCliRunVars(values, cliRunVars, warnings);
        return new Plan(projectRoot, browser, PKB_props.serializeRunVars(values), blankToNull(tags), blankToNull(name));
    }

    public static String isolateRunVars(Map<String, String> retainedProfile, String tags, String name) {
        return isolateRunVars(retainedProfile, tags, name, null, null);
    }

    public static String isolateRunVars(
            Map<String, String> retainedProfile,
            String tags,
            String name,
            String retention
    ) {
        return isolateRunVars(retainedProfile, tags, name, retention, null);
    }

    public static String isolateRunVars(
            Map<String, String> retainedProfile,
            String tags,
            String name,
            String retention,
            String example
    ) {
        if (retainedProfile == null || retainedProfile.isEmpty()) {
            throw new IllegalStateException(missingDiscoverSnapshotMessage());
        }
        LinkedHashMap<String, String> values = copyRunVars(retainedProfile);
        values.put(PKB_props.PKB_PARALLEL, "1");
        overlaySelection(values, tags, name, retention, example);
        return PKB_props.serializeRunVars(values);
    }

    public static String confirmRunVars(Map<String, String> retainedProfile, String tags, String name) {
        return confirmRunVars(retainedProfile, tags, name, null, null);
    }

    public static String confirmRunVars(
            Map<String, String> retainedProfile,
            String tags,
            String name,
            String retention
    ) {
        return confirmRunVars(retainedProfile, tags, name, retention, null);
    }

    public static String confirmRunVars(
            Map<String, String> retainedProfile,
            String tags,
            String name,
            String retention,
            String example
    ) {
        return confirmRunVars(retainedProfile, tags, name, retention, example, null, null);
    }

    public static String confirmRunVars(
            Map<String, String> retainedProfile,
            String tags,
            String name,
            String retention,
            String example,
            List<String> cliRunVars,
            Appendable warnings
    ) {
        if (retainedProfile == null || retainedProfile.isEmpty()) {
            throw new IllegalStateException(missingDiscoverSnapshotMessage());
        }
        LinkedHashMap<String, String> values = copyRunVars(retainedProfile);
        overlaySelection(values, tags, name, retention, example);
        applyCliRunVars(values, cliRunVars, warnings);
        return PKB_props.serializeRunVars(values);
    }

    public static String isolateRunVars(
            Map<String, String> retainedProfile,
            String tags,
            String name,
            String retention,
            String example,
            List<String> cliRunVars,
            Appendable warnings
    ) {
        if (retainedProfile == null || retainedProfile.isEmpty()) {
            throw new IllegalStateException(missingDiscoverSnapshotMessage());
        }
        LinkedHashMap<String, String> values = copyRunVars(retainedProfile);
        values.put(PKB_props.PKB_PARALLEL, "1");
        overlaySelection(values, tags, name, retention, example);
        applyCliRunVars(values, cliRunVars, warnings);
        return PKB_props.serializeRunVars(values);
    }

    /**
     * Repeatable {@code --runvar key=value}. A key without the {@code pkb_} prefix gets one.
     * A different existing value is replaced and warned. The command-line value wins.
     */
    public static void applyCliRunVars(Map<String, String> values, List<String> raw, Appendable warnings) {
        if (values == null || raw == null || raw.isEmpty()) return;
        for (String item : raw) {
            if (item == null || item.isBlank()) continue;
            int equals = item.indexOf('=');
            if (equals <= 0) {
                warn(warnings, "WARNING: --runvar ignored, expected key=value: " + item);
                continue;
            }
            String key = item.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = item.substring(equals + 1).trim();
            if (!key.startsWith("pkb_")) key = "pkb_" + key;
            String existing = values.get(key);
            if (existing != null && !existing.equals(value)) {
                warn(warnings, "WARNING: --runvar " + key + " replaces " + existing + " with " + value);
            }
            values.put(key, value);
        }
    }

    private static void warn(Appendable warnings, String message) {
        if (warnings == null) return;
        try {
            warnings.append(message).append(System.lineSeparator());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Default Discover run-var string so hint/estimator strings cannot drift. */
    public static String recommendedDiscoverRunVars(String browser) {
        String selected = browser == null || browser.isBlank()
                ? AgentBrowserLadder.CHROME_HEADLESS
                : browser.trim();
        return PKB_props.serializeRunVars(baseDiscoverVars(selected));
    }

    public static String missingDiscoverSnapshotMessage() {
        return "No prior Discover snapshot. Run Workbench discover first. "
                + "Isolate/Confirm will not silently re-resolve from project defaults.";
    }

    private static LinkedHashMap<String, String> baseDiscoverVars(String browser) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(PKB_props.PKB_BROWSER, browser);
        values.put(PKB_props.PKB_PARALLEL, Integer.toString(ParallelCountEstimator.estimate()));
        values.put(PKB_props.PKB_REPORTING_MODE, "diagnostic");
        values.put(PKB_props.PKB_LOGLEVEL, "warn");
        values.put(PKB_props.PKB_REPORT_RETENTION, "failed");
        values.put(PKB_props.PKB_COMPOSITE_REPORT, "false");
        values.put(PKB_props.PKB_SCENARIO_REPORT, "false");
        return values;
    }

    private static void overlaySelection(
            LinkedHashMap<String, String> values,
            String tags,
            String name,
            String retention,
            String example
    ) {
        if (tags != null && !tags.isBlank()) values.put(PKB_props.PKB_TAGS, tags.trim());
        if (name != null && !name.isBlank()) values.put(PKB_props.PKB_NAME, name.trim());
        if (example != null && !example.isBlank()) values.put(PKB_props.PKB_EXAMPLE, example.trim());
        if (retention != null && !retention.isBlank()) {
            values.put(
                    PKB_props.PKB_REPORT_RETENTION,
                    ReportRetentionPolicy.parseExact(retention).name().toLowerCase(Locale.ROOT)
            );
        }
        values.remove(PKB_props.PKB_RUN_PROFILE);
    }

    private static LinkedHashMap<String, String> copyRunVars(Map<String, String> retainedProfile) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        retainedProfile.forEach((key, value) -> {
            if (key == null || key.isBlank()) return;
            if (PKB_props.PKB_RUN_PROFILE.equals(key) || PKB_props.isRunProfileMemberKey(key)) return;
            if (PKB_props.isRunMetadataKey(key)) return;
            values.put(key, value == null ? "" : value);
        });
        return values;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
