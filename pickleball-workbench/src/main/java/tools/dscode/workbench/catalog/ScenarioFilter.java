package tools.dscode.workbench.catalog;

import tools.dscode.control.protocol.WorkbenchFeatureIndex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Headless name/tag filter for catalog scenarios.
 *
 * <p>Name matching is always case-insensitive and applies only to the Gherkin
 * {@code Scenario} / {@code Scenario Outline} title. Tag matching follows
 * Cucumber inheritance already materialized on {@link ConsumerFeatureCatalog.ScenarioEntry#effectiveTags()}:
 * feature tags, optional Rule tags, the scenario/outline's own tags, and
 * Examples tags on an outline. Include tags are AND; exclude tags are NOT
 * (any listed exclude tag drops the scenario). Empty include/exclude means
 * no tag constraint. Tag queries accept values with or without a leading
 * {@code @} and split on commas and/or whitespace. Tag comparison is
 * case-sensitive after {@code @} normalization, matching Cucumber.</p>
 */
public final class ScenarioFilter {
    public enum NameMatchMode {
        STARTS_WITH("Starts with"),
        CONTAINS("Contains"),
        ENDS_WITH("Ends with"),
        FULL_MATCH("Full match");

        private final String label;

        NameMatchMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final NameMatchMode DEFAULT_NAME_MATCH = NameMatchMode.CONTAINS;

    private NameMatchMode nameMatchMode = DEFAULT_NAME_MATCH;
    private String nameQuery = "";
    private String includeTagsQuery = "";
    private String excludeTagsQuery = "";
    private List<WorkbenchFeatureIndex.FeatureLine> pathLines = List.of();

    public NameMatchMode nameMatchMode() {
        return nameMatchMode;
    }

    public void setNameMatchMode(NameMatchMode nameMatchMode) {
        this.nameMatchMode = nameMatchMode == null ? DEFAULT_NAME_MATCH : nameMatchMode;
    }

    public String nameQuery() {
        return nameQuery;
    }

    public void setNameQuery(String nameQuery) {
        this.nameQuery = nameQuery == null ? "" : nameQuery;
    }

    public String includeTagsQuery() {
        return includeTagsQuery;
    }

    public void setIncludeTagsQuery(String includeTagsQuery) {
        this.includeTagsQuery = includeTagsQuery == null ? "" : includeTagsQuery;
    }

    public String excludeTagsQuery() {
        return excludeTagsQuery;
    }

    public void setExcludeTagsQuery(String excludeTagsQuery) {
        this.excludeTagsQuery = excludeTagsQuery == null ? "" : excludeTagsQuery;
    }

    public List<String> pathLines() {
        return pathLines.stream().map(line -> line.path() + ":" + line.line()).toList();
    }

    /**
     * Cucumber-native {@code path:line} selectors. Empty means no line constraint.
     * A line on an Examples row selects that row. A line elsewhere in the scenario
     * selects the scenario entry.
     */
    public void setPathLines(List<String> selectors) {
        this.pathLines = WorkbenchFeatureIndex.parseSelectors(selectors == null ? List.of() : selectors);
    }

    public void copyFrom(ScenarioFilter other) {
        if (other == null) return;
        this.nameMatchMode = other.nameMatchMode;
        this.nameQuery = other.nameQuery;
        this.includeTagsQuery = other.includeTagsQuery;
        this.excludeTagsQuery = other.excludeTagsQuery;
        this.pathLines = other.pathLines;
    }

    public List<String> includeTags() {
        return parseTagQuery(includeTagsQuery);
    }

    public List<String> excludeTags() {
        return parseTagQuery(excludeTagsQuery);
    }

    public List<ConsumerFeatureCatalog.ScenarioEntry> apply(
            List<ConsumerFeatureCatalog.ScenarioEntry> scenarios
    ) {
        List<ConsumerFeatureCatalog.ScenarioEntry> source =
                scenarios == null ? List.of() : scenarios;
        List<String> include = includeTags();
        List<String> exclude = excludeTags();
        List<ConsumerFeatureCatalog.ScenarioEntry> matched = new ArrayList<>();
        for (ConsumerFeatureCatalog.ScenarioEntry scenario : source) {
            if (matches(scenario, include, exclude, source)) {
                matched.add(scenario);
            }
        }
        return List.copyOf(matched);
    }

    boolean matches(ConsumerFeatureCatalog.ScenarioEntry scenario) {
        return matches(scenario, includeTags(), excludeTags(), List.of(scenario));
    }

    private boolean matches(
            ConsumerFeatureCatalog.ScenarioEntry scenario,
            List<String> include,
            List<String> exclude,
            List<ConsumerFeatureCatalog.ScenarioEntry> pool
    ) {
        if (scenario == null) return false;
        if (!nameMatches(scenario.name())) return false;
        if (!pathLineMatches(scenario, pool)) return false;
        Set<String> effective = canonicalTagSet(scenario.effectiveTags());
        if (!include.isEmpty() && !effective.containsAll(include)) return false;
        if (!exclude.isEmpty()) {
            for (String tag : exclude) {
                if (effective.contains(tag)) return false;
            }
        }
        return true;
    }

    private boolean pathLineMatches(
            ConsumerFeatureCatalog.ScenarioEntry scenario,
            List<ConsumerFeatureCatalog.ScenarioEntry> pool
    ) {
        if (pathLines.isEmpty()) return true;
        for (WorkbenchFeatureIndex.FeatureLine line : pathLines) {
            if (!WorkbenchFeatureIndex.sameFeature(scenario.relativePath(), scenario.file(), line.path())) continue;
            boolean exampleLine = exampleRowAt(pool, scenario, line.line());
            if (scenario.hasExampleRow()) {
                if (scenario.focusLine() == line.line()) return true;
                if (!exampleLine && line.line() >= scenario.startLine() && line.line() <= scenario.endLine()) {
                    return true;
                }
            } else if (!exampleLine && line.line() >= scenario.startLine() && line.line() <= scenario.endLine()) {
                return true;
            }
        }
        return false;
    }

    private static boolean exampleRowAt(
            List<ConsumerFeatureCatalog.ScenarioEntry> pool,
            ConsumerFeatureCatalog.ScenarioEntry scenario,
            int line
    ) {
        if (pool == null) return false;
        for (ConsumerFeatureCatalog.ScenarioEntry other : pool) {
            if (other == null || !other.hasExampleRow()) continue;
            if (other.focusLine() != line) continue;
            if (!other.file().equals(scenario.file())) continue;
            if (!other.name().equals(scenario.name())) continue;
            return true;
        }
        return false;
    }

    private boolean nameMatches(String name) {
        String query = nameQuery.strip();
        if (query.isEmpty()) return true;
        String haystack = name == null ? "" : name;
        String a = haystack.toLowerCase(Locale.ROOT);
        String b = query.toLowerCase(Locale.ROOT);
        return switch (nameMatchMode) {
            case STARTS_WITH -> a.startsWith(b);
            case CONTAINS -> a.contains(b);
            case ENDS_WITH -> a.endsWith(b);
            case FULL_MATCH -> a.equals(b);
        };
    }

    /**
     * Splits a free-text tag field on commas and/or whitespace and strips a
     * leading {@code @} from each token. Empty input is no constraint.
     */
    public static List<String> parseTagQuery(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String part : raw.split("[,\\s]+")) {
            String canonical = canonicalTag(part);
            if (!canonical.isEmpty()) tags.add(canonical);
        }
        return List.copyOf(tags);
    }

    public static String canonicalTag(String raw) {
        if (raw == null) return "";
        String tag = raw.strip();
        while (tag.startsWith("@")) {
            tag = tag.substring(1).strip();
        }
        return tag;
    }

    static Set<String> canonicalTagSet(Collection<String> tags) {
        LinkedHashSet<String> canonical = new LinkedHashSet<>();
        if (tags == null) return canonical;
        for (String tag : tags) {
            String value = canonicalTag(tag);
            if (!value.isEmpty()) canonical.add(value);
        }
        return canonical;
    }

    static List<String> copyTags(Collection<String> tags) {
        return List.copyOf(canonicalTagSet(tags));
    }

    static List<String> parseGherkinTagLine(String trimmed) {
        if (trimmed == null || !isGherkinTagLine(trimmed)) return List.of();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String token : trimmed.split("\\s+")) {
            String canonical = canonicalTag(token);
            if (!canonical.isEmpty()) tags.add(canonical);
        }
        return List.copyOf(tags);
    }

    static boolean isGherkinTagLine(String trimmed) {
        if (trimmed == null || trimmed.isEmpty() || !trimmed.startsWith("@")) return false;
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0) return false;
        for (String token : tokens) {
            if (!token.startsWith("@") || canonicalTag(token).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "ScenarioFilter{mode=" + nameMatchMode
                + ", name='" + nameQuery
                + "', include='" + includeTagsQuery
                + "', exclude='" + excludeTagsQuery
                + "', lines=" + pathLines() + '}';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ScenarioFilter that)) return false;
        return nameMatchMode == that.nameMatchMode
                && Objects.equals(nameQuery, that.nameQuery)
                && Objects.equals(includeTagsQuery, that.includeTagsQuery)
                && Objects.equals(excludeTagsQuery, that.excludeTagsQuery)
                && Objects.equals(pathLines, that.pathLines);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nameMatchMode, nameQuery, includeTagsQuery, excludeTagsQuery, pathLines);
    }
}
