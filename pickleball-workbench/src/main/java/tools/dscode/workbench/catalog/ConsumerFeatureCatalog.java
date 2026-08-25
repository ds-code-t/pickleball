package tools.dscode.workbench.catalog;

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
import java.util.stream.Stream;

/**
 * Project-owned feature/scenario index for the Workbench picker.
 *
 * <p>Discovery stays inside the synchronized consumer project: manifest source
 * roots, the merged live resource output, conventional Maven/Gradle test
 * resource {@code features} folders, and an explicit {@code pkb_features}
 * value when it is already present in project-owned configuration. It does
 * not crawl a git worktree or invent a second project model.</p>
 *
 * <p>Feature/Scenario titles and Gherkin tags are read as structure labels for
 * browsing only. Feature, Rule, scenario/outline, and Examples tags are
 * inherited the same way Cucumber does. This class does not match or execute
 * steps and does not call Cucumber.</p>
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
            List<String> effectiveTags
    ) {
        public ScenarioEntry {
            name = name == null ? "" : name;
            featureName = featureName == null ? "" : featureName;
            Objects.requireNonNull(file, "file");
            relativePath = relativePath == null ? "" : relativePath;
            lines = List.copyOf(lines == null ? List.of() : lines);
            tags = ScenarioFilter.copyTags(tags);
            effectiveTags = ScenarioFilter.copyTags(effectiveTags);
        }

        public String displayLabel() {
            return name.isBlank() ? "(unnamed scenario)" : name;
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
        LinkedHashSet<Path> searchRoots = new LinkedHashSet<>();
        addIfDirectory(searchRoots, root.resolve("src").resolve("test").resolve("resources").resolve("features"));
        addIfDirectory(searchRoots, root.resolve("src").resolve("test").resolve("resources"));
        if (manifest != null) {
            for (String source : manifest.sourceRoots()) {
                if (source == null || source.isBlank()) continue;
                Path sourceRoot = Path.of(source).toAbsolutePath().normalize();
                addIfDirectory(searchRoots, sourceRoot);
                addIfDirectory(searchRoots, sourceRoot.resolve("features"));
            }
            if (manifest.liveOutput() != null && !manifest.liveOutput().isBlank()) {
                Path live = Path.of(manifest.liveOutput()).toAbsolutePath().normalize();
                addIfDirectory(searchRoots, live.resolve("features"));
            }
        }
        for (Path configured : configuredFeatureRoots(root)) {
            addIfDirectory(searchRoots, configured);
        }

        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (Path searchRoot : searchRoots) {
            collectFeatureFiles(searchRoot, files);
        }

        List<FeatureEntry> entries = new ArrayList<>();
        LinkedHashSet<Path> seen = new LinkedHashSet<>();
        for (Path file : files) {
            Path absolute = file.toAbsolutePath().normalize();
            if (!seen.add(absolute)) continue;
            try {
                entries.add(readFeature(root, absolute));
            } catch (IOException ignored) {
                // Skip unreadable files; the picker should not fail the whole catalog.
            }
        }
        entries.sort(Comparator.comparing(FeatureEntry::relativePath, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    static List<Path> configuredFeatureRoots(Path projectRoot) {
        List<Path> roots = new ArrayList<>();
        List<Path> propertyFiles = List.of(
                projectRoot.resolve("src").resolve("test").resolve("resources").resolve("pickleball.properties"),
                projectRoot.resolve("src").resolve("main").resolve("resources").resolve("pickleball.properties"),
                projectRoot.resolve("pickleball.properties")
        );
        for (Path file : propertyFiles) {
            if (!Files.isRegularFile(file)) continue;
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("#") || !trimmed.contains("pkb_features")) continue;
                    String value = featurePathValue(trimmed);
                    Path resolved = resolveConfiguredFeatures(projectRoot, value);
                    if (resolved != null) roots.add(resolved);
                }
            } catch (IOException ignored) {
                // Configuration is optional discovery input.
            }
        }
        return roots;
    }

    static String featurePathValue(String line) {
        int equals = line.indexOf('=');
        if (equals < 0) return "";
        String raw = line.substring(equals + 1).strip();
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    static Path resolveConfiguredFeatures(Path projectRoot, String value) {
        if (value == null || value.isBlank()) return null;
        String path = value.strip();
        if (path.startsWith("classpath:")) {
            String remainder = path.substring("classpath:".length()).replace('\\', '/');
            while (remainder.startsWith("/")) remainder = remainder.substring(1);
            Path testResources = projectRoot.resolve("src").resolve("test").resolve("resources");
            return remainder.isBlank() ? testResources : testResources.resolve(remainder);
        }
        if (path.startsWith("file:")) {
            path = path.substring("file:".length());
        }
        Path configured = Path.of(path);
        return configured.isAbsolute() ? configured.normalize() : projectRoot.resolve(configured).normalize();
    }

    static FeatureEntry readFeature(Path projectRoot, Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String featureName = "";
        List<String> featureTags = List.of();
        List<String> ruleTags = List.of();
        List<String> pendingTags = new ArrayList<>();
        String currentScenario = null;
        int scenarioStart = -1;
        boolean currentIsOutline = false;
        List<String> currentOwnTags = List.of();
        LinkedHashSet<String> currentExampleTags = new LinkedHashSet<>();
        List<String> header = new ArrayList<>();
        List<ScenarioEntry> scenarios = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).strip();
            if (ScenarioFilter.isGherkinTagLine(trimmed)) {
                pendingTags.addAll(ScenarioFilter.parseGherkinTagLine(trimmed));
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (startsWithKeyword(trimmed, "Feature:")) {
                featureName = trimmed.substring("Feature:".length()).strip();
                featureTags = List.copyOf(pendingTags);
                ruleTags = List.of();
                pendingTags.clear();
                header.add(lines.get(i));
            } else if (startsWithKeyword(trimmed, "Rule:")) {
                ruleTags = List.copyOf(pendingTags);
                pendingTags.clear();
            } else if (startsWithKeyword(trimmed, "Scenario Outline:")
                    || startsWithKeyword(trimmed, "Scenario Template:")
                    || startsWithKeyword(trimmed, "Scenario:")) {
                if (currentScenario != null) {
                    scenarios.add(scenario(
                            projectRoot, file, featureName, featureTags, ruleTags,
                            currentScenario, currentOwnTags, currentExampleTags,
                            scenarioStart, i - 1, lines, header
                    ));
                }
                currentIsOutline = startsWithKeyword(trimmed, "Scenario Outline:")
                        || startsWithKeyword(trimmed, "Scenario Template:");
                currentScenario = trimmed.contains(":")
                        ? trimmed.substring(trimmed.indexOf(':') + 1).strip()
                        : trimmed;
                scenarioStart = i;
                currentOwnTags = List.copyOf(pendingTags);
                currentExampleTags.clear();
                pendingTags.clear();
            } else if (startsWithKeyword(trimmed, "Examples:") || startsWithKeyword(trimmed, "Example:")) {
                if (currentIsOutline) {
                    currentExampleTags.addAll(pendingTags);
                }
                pendingTags.clear();
            } else {
                pendingTags.clear();
            }
        }
        if (currentScenario != null) {
            scenarios.add(scenario(
                    projectRoot, file, featureName, featureTags, ruleTags,
                    currentScenario, currentOwnTags, currentExampleTags,
                    scenarioStart, lines.size() - 1, lines, header
            ));
        }
        Path relative = relativeTo(projectRoot, file);
        String relativePath = relative.toString().replace('\\', '/');
        Path parent = relative.getParent();
        return new FeatureEntry(
                file.toAbsolutePath().normalize(),
                relativePath,
                featureName,
                parent == null ? "" : parent.toString().replace('\\', '/'),
                file.getFileName().toString(),
                scenarios,
                featureTags
        );
    }

    private static ScenarioEntry scenario(
            Path projectRoot,
            Path file,
            String featureName,
            List<String> featureTags,
            List<String> ruleTags,
            String name,
            List<String> ownTags,
            Set<String> exampleTags,
            int start,
            int end,
            List<String> lines,
            List<String> header
    ) {
        List<String> body = new ArrayList<>();
        if (!header.isEmpty()) {
            body.addAll(header);
            if (body.getLast().isBlank() == false) body.add("");
        }
        for (int i = start; i <= end && i < lines.size(); i++) {
            body.add(lines.get(i));
        }
        while (!body.isEmpty() && body.getLast().isBlank()) {
            body.removeLast();
        }
        LinkedHashSet<String> effective = new LinkedHashSet<>();
        effective.addAll(featureTags);
        effective.addAll(ruleTags);
        effective.addAll(ownTags);
        if (exampleTags != null) effective.addAll(exampleTags);
        return new ScenarioEntry(
                name,
                featureName,
                file.toAbsolutePath().normalize(),
                relativeTo(projectRoot, file).toString().replace('\\', '/'),
                start + 1,
                end + 1,
                body,
                ownTags,
                List.copyOf(effective)
        );
    }

    private static boolean startsWithKeyword(String trimmed, String keyword) {
        return trimmed.startsWith(keyword);
    }

    private static void addIfDirectory(Set<Path> roots, Path path) {
        if (path != null && Files.isDirectory(path)) {
            roots.add(path.toAbsolutePath().normalize());
        }
    }

    private static void collectFeatureFiles(Path root, Set<Path> files) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> walk = Files.walk(root, 8)) {
            walk.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".feature"))
                    .forEach(path -> files.add(path.toAbsolutePath().normalize()));
        } catch (IOException ignored) {
            // A single unreadable directory must not hide the rest of the catalog.
        }
    }

    private static Path relativeTo(Path projectRoot, Path file) {
        try {
            return projectRoot.relativize(file.toAbsolutePath().normalize());
        } catch (IllegalArgumentException ignored) {
            return file.getFileName();
        }
    }
}
