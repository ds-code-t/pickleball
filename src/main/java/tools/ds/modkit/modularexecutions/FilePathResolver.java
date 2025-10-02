package tools.ds.modkit.modularexecutions;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FilePathResolver {

    public static List<String> findFilePaths(String... globs) throws IOException {
        Path root = projectRoot();
        List<PathMatcher> matchers = toGlobMatchers(globs);

        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> matchesAny(root, p, matchers))
                    .map(FilePathResolver::absString)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    public static List<String> findFileDirectoryPaths(String... globs) throws IOException {
        Path root = projectRoot();
        List<PathMatcher> matchers = toGlobMatchers(globs);

        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> matchesAny(root, p, matchers))
                    .map(Path::getParent)
                    .filter(Objects::nonNull)
                    .map(FilePathResolver::absString)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    // ------------------ helpers ------------------

    private static List<PathMatcher> toGlobMatchers(String... globs) {
        String[] effective = (globs == null || globs.length == 0)
                ? new String[]{"**/*.feature"} // default
                : globs;

        FileSystem fs = FileSystems.getDefault();
        List<PathMatcher> out = new ArrayList<>(effective.length);
        for (String g : effective) {
            // Treat globs as project-root-relative; ignore any leading '/'
            String cleaned = g.startsWith("/") ? g.substring(1) : g;
            String sysGlob = normalizeGlobForOs(cleaned);
            out.add(fs.getPathMatcher("glob:" + sysGlob));
        }
        return out;
    }

    private static String normalizeGlobForOs(String glob) {
        if (File.separatorChar == '\\') {
            // PathMatcher expects backslashes on Windows
            return glob.replace("/", "\\\\");
        }
        return glob;
    }

    private static boolean matchesAny(Path root, Path absolutePath, List<PathMatcher> matchers) {
        // Compare against path relative to root (so "src/**/*.feature" works)
        Path rel = root.relativize(absolutePath);
        for (PathMatcher m : matchers) {
            if (m.matches(rel)) return true;              // project-root-relative match
            if (m.matches(absolutePath)) return true;     // fallback: absolute match (for patterns like **/src/**)
            if (m.matches(absolutePath.getFileName())) return true; // fallback: simple "*.ext"
        }
        return false;
    }

    private static Path projectRoot() {
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static String absString(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    // ------------------ demo ------------------

    public static void main(String[] args) throws Exception {
        // Now this works as expected (root-relative):
        List<String> files = findFilePaths("/src/**/*.feature");
        System.out.println("FILES (" + files.size() + "):");
        files.forEach(System.out::println);

        List<String> dirs = findFileDirectoryPaths("/src/**/*.feature");
        System.out.println("\nDIRS (" + dirs.size() + "):");
        dirs.forEach(System.out::println);
    }
}
