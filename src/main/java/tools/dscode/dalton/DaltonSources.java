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

    public record Skip(String path, String reason) {
    }

    public record Scan(List<Found> found, List<Skip> skipped) {
        public Scan {
            found = found == null ? List.of() : List.copyOf(found);
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
        }
    }

    public static List<Found> scan(Path project, List<String> only) throws IOException {
        return scan(project, only, false).found();
    }

    /**
     * {@code bypassLimits} is only for {@code --path --force}. A full hydrate
     * still applies size, binary, and exclude rules.
     */
    public static Scan scan(Path project, List<String> only, boolean bypassLimits) throws IOException {
        Path root = project.toAbsolutePath().normalize();
        DaltonDiscovery discovery = DaltonDiscovery.load(root);
        Set<String> seen = new LinkedHashSet<>();
        List<Found> found = new ArrayList<>();
        if (only == null || only.isEmpty()) {
            if (discovery.defaultIncludes()) {
                for (String relative : DEFAULT_DIRECTORIES) addTree(root, relative, seen, found);
                for (String relative : DEFAULT_FILES) addFile(root, relative, seen, found);
            } else {
                addIncluded(root, discovery.includes(), seen, found);
            }
        } else {
            for (String raw : only) {
                String relative = DaltonPaths.normalizeRelative(raw);
                Path file = root.resolve(relative);
                if (Files.isDirectory(file)) addTree(root, relative, seen, found);
                else addFile(root, relative, seen, found);
            }
        }
        if (bypassLimits) return new Scan(found, List.of());
        List<Found> kept = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>();
        for (Found source : found) {
            String reason = discovery.skipReason(source.path(), source.file());
            if (reason == null) kept.add(source);
            else {
                skipped.add(new Skip(source.path(), reason));
                DaltonLog.log(root, "discover", source.path(), reason);
            }
        }
        return new Scan(kept, skipped);
    }

    private static void addIncluded(Path root, List<String> globs, Set<String> seen, List<Found> found) throws IOException {
        for (String glob : globs) {
            String prefix = staticPrefix(glob);
            Path start = prefix.isEmpty() ? root : root.resolve(prefix);
            if (Files.isRegularFile(start)) {
                addFile(root, prefix, seen, found);
                continue;
            }
            if (!Files.isDirectory(start)) continue;
            try (Stream<Path> walk = Files.walk(start)) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    if (descentSkipped(root, file)) continue;
                    String relative = DaltonPaths.normalizeRelative(root.relativize(file.toAbsolutePath().normalize()).toString());
                    if (!DaltonDiscovery.glob(glob, relative)) continue;
                    add(root, file, seen, found);
                }
            }
        }
    }

    private static String staticPrefix(String glob) {
        int star = glob.indexOf('*');
        int question = glob.indexOf('?');
        int cut = star < 0 ? question : (question < 0 ? star : Math.min(star, question));
        String prefix = cut < 0 ? glob : glob.substring(0, cut);
        int slash = prefix.lastIndexOf('/');
        if (cut >= 0 && slash >= 0) return prefix.substring(0, slash);
        if (cut >= 0) return "";
        return prefix;
    }

    private static boolean descentSkipped(Path root, Path file) {
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        for (Path part : relative) {
            String name = part.toString();
            if (".git".equals(name) || "target".equals(name) || "build".equals(name)
                    || ".pickleball".equals(name) || "node_modules".equals(name) || ".gradle".equals(name)) {
                return true;
            }
        }
        return false;
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
