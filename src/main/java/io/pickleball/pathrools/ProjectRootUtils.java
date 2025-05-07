package io.pickleball.pathrools;


import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.runner.Options;
import io.cucumber.core.runner.Runner;
import io.cucumber.plugin.event.Node;
import io.pickleball.cacheandstate.GlobalCache;

import java.net.URI;
import java.nio.file.*;
import java.nio.file.Path;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.pickleball.cacheandstate.GlobalCache.getGlobalRuntime;


public final class ProjectRootUtils {

    private static Path projectRoot;

    public static Path getMainResources() {
        return mainResources;
    }

    public static Path getTestResources() {
        return testResources;
    }

    public static Path getConfigPaths() {
        return configPaths;
    }

    private static Path mainResources;
    private static Path testResources;
    private static Path configPaths;

    public static Path getProjectRoot() {
        return projectRoot;
    }

    public static void setProjectRoot(Options runnerOptions) {
        if (projectRoot == null) {
            List<String> paths = convertGluePathsToAbsoluteStrings(runnerOptions.getGlue());
            if (runnerOptions instanceof RuntimeOptions) {
                paths.addAll(convertGluePathsToAbsoluteStrings(((RuntimeOptions) runnerOptions).getFeaturePaths()));
            }
            projectRoot = findParentProjectRoot(null, paths);
            mainResources = projectRoot.resolve("src/main/resources");
            configPaths = projectRoot.resolve("src/main/resources/configs");
            testResources = projectRoot.resolve("src/test/resources");
        }
    }


    /**
     * Utility class; hide constructor.
     */
    private ProjectRootUtils() {
    }

//    /**
//     * Find the root directory of the "dependency project"—the one in which this library's
//     * code physically resides. It does this by:
//     * <ol>
//     *   <li>Finding the location of this class via its protection domain code source.</li>
//     *   <li>Recursively walking up from that path until it finds a directory that
//     *       satisfies {@link #isProjectRoot(Path)}.</li>
//     * </ol>
//     *
//     * @return the dependency project root if found; otherwise {@code null}.
//     */
//    public static Path findDependencyProjectRoot() {
//        try {
//            URL location = ProjectRootUtils.class
//                    .getProtectionDomain()
//                    .getCodeSource()
//                    .getLocation();
//            if (location != null) {
//                Path codeLocation = Paths.get(location.toURI()).toAbsolutePath();
//                return findRootRecursively(codeLocation);
//            }
//        } catch (URISyntaxException e) {
//            // log or handle if needed
//        }
//        return null;
//    }

    /**
     * Attempts to find the "parent project root" by first checking a list of candidate paths,
     * then falling back to the default methods (user.dir, stack trace),
     * and finally falling back to a default path if still not found.
     * <p>
     * The search order is:
     * <ol>
     *   <li>All provided candidate paths (in vararg order).</li>
     *   <li>{@code System.getProperty("user.dir")}</li>
     *   <li>Classes discovered from the call stack.</li>
     *   <li>The {@code defaultPath} (if still not found).</li>
     * </ol>
     *
     * @param defaultPath    The path to use if nothing else succeeds.
     * @param candidatePaths Zero or more paths that should be tried <i>first</i>.
     * @return The first valid project root found, or {@code null} if none is found.
     */
    public static Path findParentProjectRoot(String defaultPath, List<String> candidatePaths) {
        // Keep track of tried paths so we don't attempt the same one multiple times
        Set<Path> triedPaths = new HashSet<>();
        if (defaultPath != null)
            triedPaths.add(Path.of(defaultPath));
        Path found = null;


        if (candidatePaths != null) {
            for (String candidate : candidatePaths) {
                found = tryPath(candidate, triedPaths);
                if (found != null) {
                    return found;
                }
            }
        }


        if (defaultPath != null && !defaultPath.isBlank()) {
            return Path.of(defaultPath);
        }

        // If all else fails, return null
        return null;
    }

    /**
     * Helper method: attempts to find the root from a single path string,
     * skipping if we've already tried that path.
     */
    private static Path tryPath(String pathStr, Set<Path> triedPaths) {
        if (pathStr == null || pathStr.isBlank()) {
            return null;
        }
        try {
            Path path = Paths.get(pathStr).toAbsolutePath();
            if (!triedPaths.add(path)) {
                // We already tried this path
                return null;
            }
            return findRootRecursively(path);
        } catch (Exception e) {
            // If anything fails, just return null
            return null;
        }
    }

    /**
     * Recursively walks upward from the given path until it finds a directory
     * that satisfies {@link #isProjectRoot(Path)}, or returns null if none.
     */
    private static Path findRootRecursively(Path path) {
        Path current = path;
        while (current != null) {
            if (isProjectRoot(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }


    /**
     * Checks if the given directory is considered a project root.
     * For this example, we require that it contains:
     * <ul>
     *   <li>A recognized build file (build.gradle, build.gradle.kts, or pom.xml)</li>
     *   <li>An "src" directory</li>
     * </ul>
     * Adjust this to your project's needs.
     */
    private static boolean isProjectRoot(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        // Potential Gradle build file names
        Path gradleFileGroovy = dir.resolve("build.gradle");
        Path gradleFileKotlin = dir.resolve("build.gradle.kts");
        // Maven build file
        Path mavenFile = dir.resolve("pom.xml");

        // Check for an `src` directory
        Path srcDir = dir.resolve("src");

        boolean hasRecognizedBuildFile =
                Files.exists(gradleFileGroovy)
                        || Files.exists(gradleFileKotlin)
                        || Files.exists(mavenFile);

        return hasRecognizedBuildFile && Files.isDirectory(srcDir);
    }

    /**
     * Example usage / test code.
     * Typically, you wouldn't keep a main method in a utility class,
     * but this is just to demonstrate how you might call it.
     */
    public static void main(String[] args) {
        // 1) Find dependency project root
//        Path dependencyRoot = findDependencyProjectRoot();
//        System.out.println("Dependency Project Root: " + dependencyRoot);

        // 2) Find parent project root
        //    Provide a default path and a few candidate paths.
//        Path parentRoot = findParentProjectRoot(
//                String.valueOf(dependencyRoot),  // default path
//                "/some/random/path/in/parent/module",
//                "/some/other/candidate/path"
//        );
//        System.out.println("Parent Project Root: " + parentRoot);
    }


    private static List<String> convertGluePathsToAbsoluteStrings(List<URI> gluePaths) {
        List<String> absolutePaths = new ArrayList<>();
        for (URI uri : gluePaths) {
            try {
                if ("file".equals(uri.getScheme())) {
                    // Handle file URIs directly
                    absolutePaths.add(Paths.get(uri).toAbsolutePath().toString());
                } else if ("classpath".equals(uri.getScheme())) {
                    try {
                        absolutePaths.add(Paths.get(ClassLoader.getSystemResource(uri.getSchemeSpecificPart()).toURI())
                                .toAbsolutePath().toString());
                    } catch (Exception e) {
                        // handle null value
                    }

                } else {
                    throw new IllegalArgumentException("Unsupported URI scheme: " + uri.getScheme());
                }
            } catch (Exception e) {
                throw new RuntimeException("Error resolving URI to absolute path: " + uri, e);
            }
        }
        return absolutePaths;
    }

}
