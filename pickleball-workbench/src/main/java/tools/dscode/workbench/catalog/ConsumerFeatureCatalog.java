package tools.dscode.workbench.catalog;

import tools.dscode.control.protocol.PickleballPaths;
import tools.dscode.control.protocol.WorkbenchFeatureIndex;
import tools.dscode.workbench.sync.WorkbenchManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Project-owned feature/scenario index for the Workbench picker.
 *
 * <p>Discovery stays inside the synchronized consumer project: manifest source
 * roots, the merged live resource output, conventional Maven/Gradle test
 * resource {@code features} folders, and an explicit {@code pkb_features}
 * value when it is already present in project-owned configuration. It does
 * not crawl a git worktree or invent a second project model.</p>
 *
 * <p>Feature and scenario rows come from {@link WorkbenchFeatureIndex#indexedFeatures}.
 * This class does not parse Gherkin a second time. It copies the scenario text for the
 * picker. It does not match or execute steps and does not call Cucumber.</p>
 */
public final class ConsumerFeatureCatalog {
    public enum BrowseMode {
        FEATURE_NAME,
        FILE_PATH
    }

    public record FeatureEntry(
            Path file,
            String relativePath,
            String featureName,
            String directoryPath,
            String fileName,
            List<ScenarioEntry> scenarios,
            List<String> tags
    ) {
        public FeatureEntry {
            Objects.requireNonNull(file, "file");
            relativePath = relativePath == null ? "" : relativePath;
            featureName = featureName == null || featureName.isBlank() ? fileName : featureName;
            directoryPath = directoryPath == null ? "" : directoryPath;
            fileName = fileName == null ? file.getFileName().toString() : fileName;
            scenarios = List.copyOf(scenarios == null ? List.of() : scenarios);
            tags = ScenarioFilter.copyTags(tags);
        }

        public String browseLabel(BrowseMode mode) {
            if (mode == BrowseMode.FILE_PATH) {
                return directoryPath.isBlank() ? fileName : directoryPath + "/" + fileName;
            }
            return featureName;
        }
    }

    public record ScenarioEntry(
            String name,
            String featureName,
            Path file,
            String relativePath,
            int startLine,
            int endLine,
            List<String> lines,
            List<String> tags,
            List<String> effectiveTags,
            int exampleRow,
            String exampleLabel,
            int focusLine
    ) {
        public ScenarioEntry {
            name = name == null ? "" : name;
            featureName = featureName == null ? "" : featureName;
            Objects.requireNonNull(file, "file");
            relativePath = relativePath == null ? "" : relativePath;
            lines = List.copyOf(lines == null ? List.of() : lines);
            tags = ScenarioFilter.copyTags(tags);
            effectiveTags = ScenarioFilter.copyTags(effectiveTags);
            exampleLabel = exampleLabel == null ? "" : exampleLabel;
            if (focusLine <= 0) focusLine = startLine;
        }

        public ScenarioEntry(
                String name,
                String featureName,
                Path file,
                String relativePath,
                int startLine,
                int endLine,
                List<String> lines,
                List<String> tags,
                List<String> effectiveTags,
                int exampleRow,
                String exampleLabel
        ) {
            this(name, featureName, file, relativePath, startLine, endLine, lines, tags, effectiveTags, exampleRow, exampleLabel, startLine);
        }

        public ScenarioEntry(
                String name,
                String featureName,
                Path file,
                String relativePath,
                int startLine,
                int endLine,
                List<String> lines,
                List<String> tags,
                List<String> effectiveTags
        ) {
            this(name, featureName, file, relativePath, startLine, endLine, lines, tags, effectiveTags, 0, "");
        }

        public String displayLabel() {
            String base = name.isBlank() ? "(unnamed scenario)" : name;
            return exampleLabel.isBlank() ? base : base + " · " + exampleLabel;
        }

        public boolean hasExampleRow() {
            return exampleRow > 0;
        }
    }

    private final Path projectRoot;
    private final List<FeatureEntry> features;
    private final Set<Path> selectedFeatures = new LinkedHashSet<>();
    private final ScenarioFilter filter = new ScenarioFilter();
    private BrowseMode browseMode = BrowseMode.FEATURE_NAME;

    public ConsumerFeatureCatalog(Path projectRoot, WorkbenchManifest manifest) {
        this(projectRoot, discover(projectRoot, manifest));
    }

    ConsumerFeatureCatalog(Path projectRoot, List<FeatureEntry> features) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.features = List.copyOf(features);
    }

    public static ConsumerFeatureCatalog scan(Path projectRoot, WorkbenchManifest manifest) {
        return new ConsumerFeatureCatalog(projectRoot, manifest);
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public BrowseMode browseMode() {
        return browseMode;
    }

    public void setBrowseMode(BrowseMode browseMode) {
        this.browseMode = browseMode == null ? BrowseMode.FEATURE_NAME : browseMode;
    }

    public ScenarioFilter filter() {
        return filter;
    }

    public String scenarioQuery() {
        return filter.nameQuery();
    }

    public void setScenarioQuery(String scenarioQuery) {
        filter.setNameQuery(scenarioQuery);
    }

    public List<FeatureEntry> features() {
        return features;
    }

    public List<FeatureEntry> featuresForBrowse() {
        List<FeatureEntry> copy = new ArrayList<>(features);
        if (browseMode == BrowseMode.FILE_PATH) {
            copy.sort(Comparator
                    .comparing(FeatureEntry::directoryPath, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(FeatureEntry::fileName, String.CASE_INSENSITIVE_ORDER));
        } else {
            copy.sort(Comparator.comparing(FeatureEntry::featureName, String.CASE_INSENSITIVE_ORDER));
        }
        return List.copyOf(copy);
    }

    public boolean selected(Path file) {
        return selectedFeatures.contains(file.toAbsolutePath().normalize());
    }

    public void toggleFeature(Path file) {
        Path key = file.toAbsolutePath().normalize();
        if (!selectedFeatures.add(key)) {
            selectedFeatures.remove(key);
        }
    }

    public void selectFeature(Path file) {
        selectedFeatures.add(file.toAbsolutePath().normalize());
    }

    public void deselectFeature(Path file) {
        selectedFeatures.remove(file.toAbsolutePath().normalize());
    }

    public void clearFeatureSelection() {
        selectedFeatures.clear();
    }

    public List<Path> selectedFeatureFiles() {
        return List.copyOf(selectedFeatures);
    }

    /**
     * Scenarios that pass the optional feature-file filter. With no feature
     * selected, every catalog scenario is a candidate; name/tag filters then
     * apply to that pool.
     */
    public List<ScenarioEntry> candidateScenarios() {
        List<ScenarioEntry> pool = new ArrayList<>();
        for (FeatureEntry feature : features) {
            if (selectedFeatures.isEmpty() || selectedFeatures.contains(feature.file())) {
                pool.addAll(feature.scenarios());
            }
        }
        return List.copyOf(pool);
    }

    /**
     * Candidate scenarios after the primary name/tag filter. Feature-file
     * selection is optional and does not restrict the pool when empty.
     */
    public List<ScenarioEntry> visibleScenarios() {
        List<ScenarioEntry> pool = new ArrayList<>(filter.apply(candidateScenarios()));
        pool.sort(Comparator
                .comparing(ScenarioEntry::featureName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ScenarioEntry::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(pool);
    }

    public FeatureEntry feature(Path file) {
        Path key = file.toAbsolutePath().normalize();
        return features.stream()
                .filter(feature -> feature.file().equals(key))
                .findFirst()
                .orElse(null);
    }

    static List<FeatureEntry> discover(Path projectRoot, WorkbenchManifest manifest) {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<Path> extras = new ArrayList<>();
        if (manifest != null) {
            for (String source : manifest.sourceRoots()) {
                if (source == null || source.isBlank()) continue;
                extras.add(Path.of(source));
            }
            if (manifest.liveOutput() != null && !manifest.liveOutput().isBlank()) {
                extras.add(Path.of(manifest.liveOutput()).resolve("features"));
            }
        }
        List<WorkbenchFeatureIndex.IndexedFeature> indexed = WorkbenchFeatureIndex.indexedFeatures(
                new WorkbenchFeatureIndex.Request(root, extras, null, null, null, List.of(), false)
        );
        List<FeatureEntry> entries = new ArrayList<>();
        for (WorkbenchFeatureIndex.IndexedFeature feature : indexed) {
            Path file = root.resolve(feature.relativePath()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) continue;
            entries.add(entryFromIndex(root, file, feature));
        }
        entries.sort(Comparator.comparing(FeatureEntry::relativePath, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    /**
     * Picker rows come from the persistent index. File bytes are copied for the scenario
     * body only; they are not parsed again.
     */
    private static FeatureEntry entryFromIndex(Path projectRoot, Path file, WorkbenchFeatureIndex.IndexedFeature indexed) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            lines = List.of();
        }
        List<String> header = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("Feature:") || trimmed.startsWith("Business Need:") || trimmed.startsWith("Ability:")) {
                header.add(line);
                break;
            }
        }
        List<ScenarioEntry> scenarios = new ArrayList<>();
        String relativePath = indexed.relativePath().replace('\\', '/');
        for (WorkbenchFeatureIndex.IndexedScenario scenario : indexed.scenarios()) {
            List<String> body = new ArrayList<>();
            if (!header.isEmpty()) {
                body.addAll(header);
                body.add("");
            }
            int start = Math.max(0, scenario.startLine() - 1);
            int end = Math.max(start, scenario.endLine() - 1);
            for (int i = start; i <= end && i < lines.size(); i++) body.add(lines.get(i));
            while (!body.isEmpty() && body.getLast().isBlank()) body.removeLast();
            scenarios.add(new ScenarioEntry(
                    scenario.name(),
                    indexed.featureName(),
                    file,
                    relativePath,
                    scenario.startLine(),
                    scenario.endLine(),
                    body,
                    scenario.ownTags(),
                    scenario.effectiveTags(),
                    scenario.exampleRow(),
                    scenario.exampleLabel(),
                    scenario.focusLine()
            ));
        }
        int slash = relativePath.lastIndexOf('/');
        String directory = slash < 0 ? "" : relativePath.substring(0, slash);
        String fileName = file.getFileName() == null ? relativePath : file.getFileName().toString();
        String featureName = indexed.featureName().isBlank() ? fileName : indexed.featureName();
        return new FeatureEntry(file, relativePath, featureName, directory, fileName, scenarios, indexed.featureTags());
    }

    /** Resolves one {@code pkb_features} token. {@code file:} and a bare relative path use test resources. */
    static Path resolveConfiguredFeatures(Path projectRoot, String value) {
        if (value == null || value.isBlank() || projectRoot == null) return null;
        PickleballPaths.Parsed parsed = PickleballPaths.parse(value.strip());
        if (parsed.kind() == PickleballPaths.Kind.CLASSPATH) {
            String resource = parsed.classpathResource() == null ? "" : parsed.classpathResource();
            Path testResources = projectRoot.resolve("src").resolve("test").resolve("resources");
            return resource.isBlank() ? testResources : testResources.resolve(resource);
        }
        return PickleballPaths.toFile(parsed, Path.of("").toAbsolutePath());
    }
}
