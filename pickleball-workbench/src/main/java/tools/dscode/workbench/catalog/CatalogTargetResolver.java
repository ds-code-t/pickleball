package tools.dscode.workbench.catalog;

import tools.dscode.workbench.player.GherkinReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** Resolves a parsed Gherkin reference against the project catalog and data roots. */
public final class CatalogTargetResolver {
    private CatalogTargetResolver() {
    }

    public record Target(Path file, ConsumerFeatureCatalog.ScenarioEntry scenario, String detail) {
        public Target {
            detail = detail == null ? "" : detail;
        }
    }

    public static Optional<Target> resolve(
            Path projectRoot,
            ConsumerFeatureCatalog catalog,
            GherkinReference reference
    ) {
        if (reference == null) return Optional.empty();
        Path root = projectRoot == null ? Path.of(".") : projectRoot.toAbsolutePath().normalize();
        if (reference.kind() == GherkinReference.Kind.DATA_FILE) {
            return findDataFile(root, reference.selector())
                    .map(path -> new Target(path, null, "data:/" + reference.selector()));
        }
        if (catalog == null) return Optional.empty();
        String selector = reference.selector().strip();
        if (selector.isBlank()) return Optional.empty();
        List<ConsumerFeatureCatalog.ScenarioEntry> matches = catalog.features().stream()
                .flatMap(feature -> feature.scenarios().stream())
                .filter(entry -> !entry.hasExampleRow())
                .filter(entry -> matchesSelector(entry, selector, reference.kind()))
                .toList();
        if (matches.isEmpty()) return Optional.empty();
        ConsumerFeatureCatalog.ScenarioEntry first = matches.getFirst();
        String detail = matches.size() == 1
                ? first.displayLabel()
                : matches.size() + " matches; opened " + first.displayLabel();
        return Optional.of(new Target(first.file(), first, detail));
    }

    static boolean matchesSelector(
            ConsumerFeatureCatalog.ScenarioEntry entry,
            String selector,
            GherkinReference.Kind kind
    ) {
        String value = selector.startsWith("%") || selector.startsWith("@")
                ? selector.substring(1)
                : selector;
        String lower = value.toLowerCase(Locale.ROOT);
        if (selector.startsWith("%") || selector.startsWith("@")) {
            return entry.effectiveTags().stream().anyMatch(tag -> tag.equalsIgnoreCase(value) || tag.equalsIgnoreCase(selector));
        }
        if (entry.name().equalsIgnoreCase(value)) return true;
        if (entry.name().toLowerCase(Locale.ROOT).contains(lower)) return true;
        String qualified = entry.featureName() + "." + entry.name();
        return qualified.equalsIgnoreCase(value);
    }

    static Optional<Path> findDataFile(Path projectRoot, String selector) {
        String relative = selector == null ? "" : selector;
        Path dataRoot = projectRoot.resolve("src").resolve("test").resolve("resources").resolve("data");
        Path direct = dataRoot.resolve(relative.replace('/', java.io.File.separatorChar));
        if (Files.isRegularFile(direct)) return Optional.of(direct);
        String base = relative;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        String name = slash >= 0 ? base.substring(slash + 1) : base;
        Path dir = slash >= 0 ? dataRoot.resolve(base.substring(0, slash)) : dataRoot;
        if (!Files.isDirectory(dir)) return Optional.empty();
        String stem = name;
        try (Stream<Path> walk = Files.list(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        int dot = fileName.lastIndexOf('.');
                        String fileStem = dot < 0 ? fileName : fileName.substring(0, dot);
                        return fileStem.equalsIgnoreCase(stem) || fileName.equalsIgnoreCase(stem);
                    })
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }
}