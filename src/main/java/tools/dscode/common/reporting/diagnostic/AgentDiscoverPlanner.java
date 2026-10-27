package tools.dscode.common.reporting.diagnostic;

import tools.dscode.parallelutilities.ParallelCountEstimator;
import tools.dscode.testengine.PKB_props;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Hint-only recommendation of diagnostic defaults. Launch modes do not use this.
 * {@code --runvar} edits go through {@code Workspace.applyLaunchEdits}.
 */
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
        AgentBrowserLadder.Decision browser = AgentBrowserLadder.select(projectRoot);
        LinkedHashMap<String, String> values = baseDiscoverVars(browser.browser());
        overlaySelection(values, tags, name, retention, example);
        return new Plan(projectRoot, browser, PKB_props.serializeRunVars(values), blankToNull(tags), blankToNull(name));
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
