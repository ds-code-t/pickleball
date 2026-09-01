package tools.dscode.common.reporting.diagnostic;

import tools.dscode.parallelutilities.ParallelCountEstimator;
import tools.dscode.testengine.PKB_props;

import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        AgentBrowserLadder.Decision browser = AgentBrowserLadder.select(projectRoot);
        LinkedHashMap<String, String> values = baseDiscoverVars(browser.browser());
        overlaySelection(values, tags, name);
        return new Plan(projectRoot, browser, PKB_props.serializeRunVars(values), blankToNull(tags), blankToNull(name));
    }

    public static String isolateRunVars(Map<String, String> retainedProfile, String tags, String name) {
        if (retainedProfile == null || retainedProfile.isEmpty()) {
            throw new IllegalStateException(missingDiscoverSnapshotMessage());
        }
        LinkedHashMap<String, String> values = copyRunVars(retainedProfile);
        values.put(PKB_props.PKB_PARALLEL, "1");
        overlaySelection(values, tags, name);
        return PKB_props.serializeRunVars(values);
    }

    public static String confirmRunVars(Map<String, String> retainedProfile, String tags, String name) {
        if (retainedProfile == null || retainedProfile.isEmpty()) {
            throw new IllegalStateException(missingDiscoverSnapshotMessage());
        }
        LinkedHashMap<String, String> values = copyRunVars(retainedProfile);
        overlaySelection(values, tags, name);
        return PKB_props.serializeRunVars(values);
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
        return values;
    }

    private static void overlaySelection(LinkedHashMap<String, String> values, String tags, String name) {
        if (tags != null && !tags.isBlank()) values.put(PKB_props.PKB_TAGS, tags.trim());
        if (name != null && !name.isBlank()) values.put(PKB_props.PKB_NAME, name.trim());
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
