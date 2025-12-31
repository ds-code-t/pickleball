package io.cucumber.core.runner.modularexecutions;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
                ? new String[] { "**/*.feature" } // default
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
            if (m.matches(rel))
                return true; // project-root-relative match
            if (m.matches(absolutePath))
                return true; // fallback: absolute match (for patterns like
                             // **/src/**)
            if (m.matches(absolutePath.getFileName()))
                return true; // fallback: simple "*.ext"
        }
        return false;
    }

    private static Path projectRoot() {
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static String absString(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }


    private static volatile URI BASE_URI;

    public static String toAbsoluteFileUri(String classpathUri) {
        if (classpathUri == null || !classpathUri.startsWith("classpath:")) return classpathUri;

        try {
            String rel = classpathUri.replaceFirst("^classpath:", "").replaceFirst("^/+", "");
            int i = rel.indexOf('/');
            String base = (i >= 0 ? rel.substring(0, i) : rel);
            String rest = (i >= 0 ? rel.substring(i + 1) : "");

            if (BASE_URI == null) {
                synchronized (FilePathResolver.class) {
                    if (BASE_URI == null) {
                        URL url = Thread.currentThread().getContextClassLoader().getResource(base + "/");
                        if (url == null) return classpathUri;
                        BASE_URI = url.toURI(); // typically .../target/test-classes/base/ or .../build/resources/test/base/
                    }
                }
            }

            URI resolved = BASE_URI.resolve(rest);
            if (!"file".equalsIgnoreCase(resolved.getScheme())) return classpathUri;

            // Canonical file:///... form
            Path builtPath = Paths.get(resolved);

            // Heuristic: prefer source tree if present
            String p = builtPath.toString().replace(File.separatorChar, '/');
            Path maybeSource = null;
            if (p.contains("/target/test-classes/")) {
                maybeSource = Paths.get(p.replace("/target/test-classes/", "/src/test/resources/"));
            } else if (p.contains("/build/resources/test/")) {
                maybeSource = Paths.get(p.replace("/build/resources/test/", "/src/test/resources/"));
            }

            Path finalPath = (maybeSource != null && Files.exists(maybeSource)) ? maybeSource : builtPath;
            return finalPath.toUri().toString(); // yields file:///C:/... on Windows
        } catch (Exception e) {
            return classpathUri; // fall back on any failure
        }
    }

    public static URI toAbsoluteFileUri(URI classpathUri) {
        if (classpathUri == null) return null;
        return URI.create(toAbsoluteFileUri(classpathUri.toString()));
    }



    public static String fixFeatureLocationForIntellij(String location) {
        if (location == null || !location.startsWith("file:")) return location;

        int featureIdx = location.indexOf(".feature");
        if (featureIdx < 0) return location;

        String pathPart = location;
        String linePart = "";

        int colonIdx = location.indexOf(':', featureIdx + ".feature".length());
        if (colonIdx != -1) {
            pathPart = location.substring(0, colonIdx);
            linePart = location.substring(colonIdx); // ":<line>"
        }

        try {
            Path p = Paths.get(URI.create(pathPart)); // decodes %20 -> space
            return p.toString() + linePart;           // OS-native, IntelliJ-friendly
        } catch (Exception e) {
            // last resort: trim file:/// and keep the rest
            return location.replaceFirst("^file:/+", "") + linePart;
        }
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
