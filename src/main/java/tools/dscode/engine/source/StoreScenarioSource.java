package tools.dscode.engine.source;

import tools.dscode.engine.EngineLog;
import tools.dscode.engine.adapter.playwright.PlaywrightTestIngest;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.parse.GherkinEngineParser;
import tools.dscode.store.StoreDatabase;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreQuery;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/**
 * Feature text stored in a Store database row. The Cucumber parser is not used
 * here. A missing row is {@code error:} and {@code fix: store hydrate}.
 */
public final class StoreScenarioSource implements ScenarioSource {
    private final Path project;
    private final String featurePath;

    public StoreScenarioSource(Path project, String featurePath) {
        this.project = project;
        this.featurePath = featurePath == null || featurePath.isBlank() ? null : featurePath.replace('\\', '/');
    }

    @Override
    public String id() {
        return "store:" + (featurePath == null ? "*" : featurePath);
    }

    public static IllegalStateException missing(String path) {
        String where = path == null || path.isBlank() ? "feature" : path;
        return new IllegalStateException("error: " + where + " is not in the store\nfix: store hydrate");
    }

    /**
     * Gherkin that execution may see. {@code null} when the file is disabled.
     * Scenario rows with {@code enabled=0} or {@code @pkb-ignore} are removed
     * and each one is logged under {@code pkb_engine_log=ingest}.
     */
    public static String executableText(Path project, String path, String raw) throws SQLException {
        return executableText(project, path, raw, false);
    }

    /**
     * Same as {@link #executableText(Path, String, String)}. When {@code keepLibrary}
     * is true, a call or component file that is stored with {@code enabled=0} only
     * because it is outside the feature root is still returned. {@code @pkb-ignore}
     * on the file or a scenario still removes that text. A feature run does not
     * pass true, so those files stay out of the suite.
     */
    public static String executableText(Path project, String path, String raw, boolean keepLibrary) throws SQLException {
        if (raw == null) return "";
        String stored = StorePaths.normalizeRelative(path);
        boolean library = keepLibrary && StorePaths.executionLibrary(stored);
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement file = database.connection().prepareStatement(
                     "SELECT enabled FROM files WHERE path = ?");
             PreparedStatement featureTags = database.connection().prepareStatement(
                     "SELECT tags FROM feature_docs WHERE file_path = ?");
             PreparedStatement scenarios = database.connection().prepareStatement(
                     "SELECT name, line, tags, enabled FROM scenarios WHERE file_path = ? ORDER BY line")) {
            file.setString(1, stored);
            int fileEnabled = 1;
            try (ResultSet rows = file.executeQuery()) {
                if (rows.next()) fileEnabled = rows.getInt(1);
            }
            boolean featureIgnored = false;
            featureTags.setString(1, stored);
            try (ResultSet rows = featureTags.executeQuery()) {
                if (rows.next() && ignored(rows.getString(1))) featureIgnored = true;
            }
            scenarios.setString(1, stored);
            List<Integer> lines = new ArrayList<>();
            List<Integer> drop = new ArrayList<>();
            List<String> names = new ArrayList<>();
            try (ResultSet rows = scenarios.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1) == null ? "" : rows.getString(1);
                    int line = rows.getInt(2);
                    String tags = rows.getString(3);
                    int enabled = rows.getInt(4);
                    lines.add(line);
                    names.add(name);
                    boolean disabled = ignored(tags) || (!library && (fileEnabled == 0 || enabled == 0));
                    if (disabled) drop.add(line);
                }
            }
            if (featureIgnored || (fileEnabled == 0 && !library)) {
                for (String name : names) logSkip(project, name);
                return null;
            }
            if (drop.isEmpty()) return raw;
            for (int index = 0; index < lines.size(); index++) {
                if (drop.contains(lines.get(index))) logSkip(project, names.get(index));
            }
            return strip(raw, lines, drop);
        }
    }

    @Override
    public List<EngineFeature> load() {
        if (project == null || !StorePaths.ready(project)) throw missing(featurePath);
        try {
            if (featurePath != null && PlaywrightTestIngest.isSpec(featurePath)) {
                EngineFeature spec = PlaywrightTestIngest.loadFeature(project, featurePath);
                if (spec == null) throw missing(featurePath);
                return List.of(spec);
            }
            List<EngineFeature> features = new ArrayList<>();
            boolean found = false;
            for (StoreQuery.FeatureSummary summary : StoreQuery.features(project)) {
                if (!matches(summary.path())) continue;
                found = true;
                StoreQuery.FileDetail detail = StoreQuery.file(project, summary.path());
                if (detail == null || detail.text() == null) throw missing(summary.path());
                String text = executableText(project, summary.path(), detail.text());
                if (text == null) continue;
                features.add(GherkinEngineParser.parse("store:" + summary.path(), text));
            }
            if (featurePath == null || !featurePath.endsWith(".feature")) {
                for (String path : PlaywrightTestIngest.specPaths(project)) {
                    if (!matches(path)) continue;
                    found = true;
                    EngineFeature spec = PlaywrightTestIngest.loadFeature(project, path);
                    if (spec != null && !spec.scenarios().isEmpty()) features.add(spec);
                }
            }
            if (featurePath != null && !found) throw missing(featurePath);
            return List.copyOf(features);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private boolean matches(String stored) {
        if (featurePath == null) return true;
        String have = stored == null ? "" : stored.replace('\\', '/');
        if (have.equals(featurePath)) return true;
        String prefix = featurePath.endsWith("/") ? featurePath : featurePath + "/";
        return have.startsWith(prefix);
    }

    private static void logSkip(Path project, String name) {
        String who = name == null || name.isBlank() ? "scenario" : name;
        EngineLog.log(project, "ingest", who, "skipped enabled=0");
    }

    private static boolean ignored(String tags) {
        if (tags == null || tags.isBlank()) return false;
        for (String part : tags.split("\\s+")) {
            if ("@pkb-ignore".equals(part)) return true;
        }
        return false;
    }

    private static String strip(String raw, List<Integer> scenarioLines, List<Integer> dropLines) {
        String[] lines = raw.split("\n", -1);
        boolean[] drop = new boolean[lines.length];
        for (int disabled : dropLines) {
            if (disabled < 1 || disabled > lines.length) continue;
            int next = Integer.MAX_VALUE;
            for (int line : scenarioLines) {
                if (line > disabled) next = Math.min(next, line);
            }
            int start = disabled - 1;
            while (start > 0) {
                String previous = lines[start - 1].trim();
                if (previous.isEmpty() || previous.startsWith("@") || previous.startsWith("#")) start--;
                else break;
            }
            int end = next == Integer.MAX_VALUE ? lines.length : next - 1;
            while (end > start + 1) {
                String previous = lines[end - 1].trim();
                if (previous.isEmpty() || previous.startsWith("@") || previous.startsWith("#")) end--;
                else break;
            }
            for (int index = start; index < end && index < lines.length; index++) drop[index] = true;
        }
        StringBuilder kept = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (drop[index]) continue;
            if (kept.length() > 0) kept.append('\n');
            kept.append(lines[index]);
        }
        return kept.toString();
    }
}
