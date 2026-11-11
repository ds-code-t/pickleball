package tools.dscode.dalton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Locates feature, config, call, component, template, and resource-map files. */
public final class DaltonSources {
    static final List<String> DEFAULT_DIRECTORIES = List.of(
            "src/test/resources/features",
            "src/test/resources/configs",
            "src/test/resources/calls",
            "src/test/resources/component",
            "src/test/resources/templates",
            "src/test/resources/resource-maps",
            "src/main/resources/templates",
            "features",
            "configs",
            "calls",
            "component",
            "templates",
            "resource-maps",
            "dalton-entities"
    );
    static final List<String> DEFAULT_FILES = List.of(
            "src/test/resources/pickleball.properties",
            "src/test/resources/pickleball_local.properties",
            "src/test/resources/profiles.yaml",
            "src/test/resources/profiles_local.yaml",
            "pickleball.properties",
            "profiles.yaml"
    );

    private DaltonSources() {
    }

    public record Found(String path, Path file, String sourceType) {
    }

    public static List<Found> scan(Path project, List<String> only) throws IOException {
        Path root = project.toAbsolutePath().normalize();
        Set<String> seen = new LinkedHashSet<>();
        List<Found> found = new ArrayList<>();
        if (only == null || only.isEmpty()) {
            for (String relative : DEFAULT_DIRECTORIES) addTree(root, relative, seen, found);
            for (String relative : DEFAULT_FILES) addFile(root, relative, seen, found);
            return List.copyOf(found);
        }
        for (String raw : only) {
            String relative = DaltonPaths.normalizeRelative(raw);
            Path file = root.resolve(relative);
            if (Files.isDirectory(file)) addTree(root, relative, seen, found);
            else addFile(root, relative, seen, found);
        }
        return List.copyOf(found);
    }

    public static String sourceType(String relativePath) {
        String path = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (path.contains("/calls/") || path.startsWith("calls/")) return "call";
        if (path.contains("/component/") || path.startsWith("component/")) return "component";
        if (path.contains("resource-map") || path.contains("resourcemap")) return "resource-map";
        if (path.contains("/templates/") || path.startsWith("templates/") || isTemplate(name)) return "template";
        if (path.contains("/dalton-entities/") || path.startsWith("dalton-entities/")) return "entity";
        if (name.endsWith(".feature")) return "feature";
        if (isConfig(name) || path.contains("/configs/") || path.startsWith("configs/")) return "config";
        return "other";
    }

    private static boolean isTemplate(String name) {
        return name.endsWith(".ftl") || name.endsWith(".vm") || name.endsWith(".mustache")
                || name.endsWith(".template") || name.endsWith(".tpl");
    }

    private static boolean isConfig(String name) {
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".properties")
                || name.endsWith(".json") || name.endsWith(".xml");
    }

    private static void addTree(Path root, String relative, Set<String> seen, List<Found> found) throws IOException {
        Path directory = root.resolve(relative);
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                if (skip(file)) continue;
                add(root, file, seen, found);
            }
        }
    }

    private static void addFile(Path root, String relative, Set<String> seen, List<Found> found) {
        Path file = root.resolve(relative);
        if (!Files.isRegularFile(file) || skip(file)) return;
        add(root, file, seen, found);
    }

    private static void add(Path root, Path file, Set<String> seen, List<Found> found) {
        String relative = DaltonPaths.normalizeRelative(root.relativize(file.toAbsolutePath().normalize()).toString());
        if (!seen.add(relative)) return;
        found.add(new Found(relative, file, sourceType(relative)));
    }

    private static boolean skip(Path file) {
        String text = file.toString().replace('\\', '/');
        return text.contains("/.pickleball/") || text.contains("/.git/") || text.contains("/build/") || text.contains("/target/");
    }
}
