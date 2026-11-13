package tools.dscode.dalton;

import tools.dscode.common.domoperations.ExecutionDictionary;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads project category YAML after built-ins. A save does not change the
 * dictionary already in use. The next run loads it on the first
 * {@code getExecutionDictionary()} call.
 */
public final class ProjectElementCategories {
    private static final Map<ExecutionDictionary, String> LOADED = new IdentityHashMap<>();

    private ProjectElementCategories() {
    }

    public static void ensureLoaded(ExecutionDictionary dictionary) {
        if (dictionary == null) return;
        Path project = DaltonConfig.project();
        String token = project.toAbsolutePath().normalize().toString();
        synchronized (LOADED) {
            if (token.equals(LOADED.get(dictionary))) return;
            if (!hasAny(project)) {
                LOADED.put(dictionary, token);
                return;
            }
            load(dictionary, project);
            LOADED.put(dictionary, token);
        }
    }

    public static void load(ExecutionDictionary dictionary, Path project) {
        if (dictionary == null || project == null) return;
        for (Source source : sources(project)) {
            CategoryRules.Document document = CategoryRules.parse(source.path(), source.body());
            apply(dictionary, document);
        }
    }

    /** Test hook. Does not clear a dictionary that already ran. */
    public static void resetForTests() {
        synchronized (LOADED) {
            LOADED.clear();
        }
    }

    private static void apply(ExecutionDictionary dictionary, CategoryRules.Document document) {
        if (tools.dscode.common.treeparsing.parsedComponents.ElementType.reservationWarning(document.name) != null) return;
        if ("ALL_CONTEXTS".equals(document.name)) return;
        if (CategoryRules.builtin(document.name) && !document.overlay) return;
        ExecutionDictionary.CategorySpec spec = dictionary.category(document.name);
        if (document.base != null && !document.base.isBlank()) spec.addBase(document.base);
        if (document.alternate != null && !document.alternate.isBlank()) spec.addAlternateBase(document.alternate);
        if (document.inherits != null && !document.inherits.isBlank()) spec.inheritsFrom(document.inherits);
        if (document.children != null && !document.children.isEmpty()) {
            spec.children(document.children.toArray(String[]::new));
        }
        if (document.flags != null && !document.flags.isEmpty()) {
            ExecutionDictionary.CategoryFlags[] flags = document.flags.stream()
                    .map(ExecutionDictionary.CategoryFlags::valueOf)
                    .toArray(ExecutionDictionary.CategoryFlags[]::new);
            spec.flags(flags);
        }
        if ("Loading".equals(document.name)) {
            spec.flags(ExecutionDictionary.CategoryFlags.ALL_CONTEXTS);
        }
    }

    private static boolean hasAny(Path project) {
        if (Files.isDirectory(project.resolve(DaltonEdit.CATEGORY_DIR))) return true;
        return DaltonConfig.enabled(project) && DaltonQuery.databasePresent(project);
    }

    private static List<Source> sources(Path project) {
        List<Source> found = new ArrayList<>();
        if (DaltonConfig.enabled(project) && DaltonQuery.databasePresent(project)) {
            try {
                for (DaltonQuery.FileSummary file : DaltonQuery.files(project)) {
                    if (!CategoryRules.isCategory(file.path())) continue;
                    DaltonQuery.FileDetail detail = DaltonQuery.file(project, file.path());
                    if (detail == null || detail.text() == null) continue;
                    found.add(new Source(file.path(), detail.text().getBytes(StandardCharsets.UTF_8)));
                }
            } catch (Exception failure) {
                throw new IllegalArgumentException("category load failed: " + failure.getMessage());
            }
            if (!found.isEmpty()) {
                found.sort(Comparator.comparing(Source::path));
                return found;
            }
        }
        Path dir = project.resolve(DaltonEdit.CATEGORY_DIR);
        if (!Files.isDirectory(dir)) return List.of();
        try (var list = Files.list(dir)) {
            for (Path file : list.filter(Files::isRegularFile).sorted().toList()) {
                if (!file.getFileName().toString().endsWith(".yaml")) continue;
                String relative = DaltonEdit.CATEGORY_DIR + "/" + file.getFileName();
                found.add(new Source(relative, Files.readAllBytes(file)));
            }
        } catch (Exception failure) {
            throw new IllegalArgumentException("category load failed: " + failure.getMessage());
        }
        return found;
    }

    private record Source(String path, byte[] body) {
    }
}
