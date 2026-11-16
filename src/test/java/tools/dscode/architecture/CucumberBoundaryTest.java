package tools.dscode.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cucumber is an isolated legacy adapter. Gherkin syntax libraries (gherkin, messages,
 * cucumber-expressions, tag-expressions, datatable, docstring, and the step annotations)
 * stay allowed because Gherkin syntax and step annotations do not change. Cucumber runtime
 * types stay out of the core spine:
 * <ul>
 *   <li>The spine packages listed in {@link #CLEAN} import nothing from {@code io.cucumber}.</li>
 *   <li>The Workbench, the DOM engine, and the control protocol import nothing from {@code io.cucumber}.</li>
 *   <li>Any other file that imports a runtime type is in the frozen allowlist, which may only shrink.</li>
 * </ul>
 */
class CucumberBoundaryTest {
    private static final Pattern ANY_IMPORT = Pattern.compile("(?m)^import\\s+(static\\s+)?io\\.cucumber\\.");
    private static final Pattern RUNTIME_IMPORT = Pattern.compile(
            "(?m)^import\\s+(static\\s+)?io\\.cucumber\\.(core|plugin|junit|platform|runtime|java\\.Scenario|java\\.PendingException)\\b");
    private static final String ALLOWLIST = "src/test/resources/architecture/cucumber-runtime-allowlist.txt";

    /** Core spine packages that must not import Cucumber at all, not even the syntax libraries. */
    static final List<String> CLEAN = List.of(
            "src/main/java/tools/dscode/dolt",
            "src/main/java/tools/dscode/engine/model",
            "src/main/java/tools/dscode/engine/source",
            "src/main/java/tools/dscode/engine/parity",
            "src/main/java/tools/dscode/browser",
            "src/main/java/tools/dscode/common/agentcontrol",
            "src/main/java/tools/dscode/common/agentsurface",
            "src/main/java/tools/dscode/common/composite",
            "src/main/java/tools/dscode/common/domoperations",
            "src/main/java/tools/dscode/common/browseroperations",
            "src/main/java/tools/dscode/common/seleniumextensions",
            "src/main/java/tools/dscode/common/servicecalls",
            "src/main/java/tools/dscode/common/trace",
            "src/main/java/tools/dscode/common/runtime",
            "src/main/java/tools/dscode/registry",
            "src/main/java/tools/dscode/release",
            "src/main/java/tools/dscode/parallelutilities",
            "pickleball-workbench/src/main/java",
            "dom-engine/src/main/java",
            "pickleball-control-protocol/src/main/java"
    );

    /** Source roots whose runtime imports are ratcheted by the allowlist. The {@code io.cucumber} overlay is the adapter itself. */
    private static final List<String> RATCHETED = List.of("src/main/java", "pickleball-control-api/src/main/java");
    private static final List<String> ADAPTER = List.of(
            "src/main/java/io/cucumber/",
            "src/main/java/tools/dscode/engine/adapter/cucumber/"
    );

    @Test
    void spinePackagesImportNothingFromCucumber() throws IOException {
        Path root = repoRoot();
        List<String> leaks = new ArrayList<>();
        for (String dir : CLEAN) {
            Path base = root.resolve(dir);
            assertTrue(Files.isDirectory(base), "clean package moved or was renamed: " + dir);
            for (Path file : javaFiles(base)) {
                if (ANY_IMPORT.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    leaks.add(relative(root, file));
                }
            }
        }
        assertTrue(leaks.isEmpty(), "Cucumber imports in the core spine (keep Cucumber in the adapter): " + leaks);
    }

    @Test
    void cucumberRuntimeImportsStayInTheFrozenAllowlist() throws IOException {
        Path root = repoRoot();
        Set<String> allowed = allowlist(root);
        Set<String> actual = new TreeSet<>();
        for (String dir : RATCHETED) {
            for (Path file : javaFiles(root.resolve(dir))) {
                String rel = relative(root, file);
                if (ADAPTER.stream().anyMatch(rel::startsWith)) continue;
                if (RUNTIME_IMPORT.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) actual.add(rel);
            }
        }
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(allowed);
        assertTrue(added.isEmpty(), "New Cucumber runtime imports outside the adapter. Route through the native spine instead: " + added);
        Set<String> stale = new TreeSet<>(allowed);
        stale.removeAll(actual);
        assertTrue(stale.isEmpty(), "These files no longer import Cucumber runtime types. Remove them from " + ALLOWLIST + ": " + stale);
    }

    @Test
    void cucumberAdapterListenerLivesInTheAdapterPackage() throws IOException {
        Path root = repoRoot();
        Path plugin = root.resolve("src/main/java/tools/dscode/engine/adapter/cucumber/DoltRunTracePlugin.java");
        assertTrue(Files.isRegularFile(plugin), "the Cucumber run listener must stay in tools.dscode.engine.adapter.cucumber");
        for (Path file : javaFiles(root.resolve("src/main/java/tools/dscode/dolt"))) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(!text.contains("io.cucumber"), "Dolt core names Cucumber: " + relative(root, file));
        }
    }

    private static Set<String> allowlist(Path root) throws IOException {
        Set<String> allowed = new TreeSet<>();
        for (String line : Files.readAllLines(root.resolve(ALLOWLIST), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) allowed.add(trimmed);
        }
        return allowed;
    }

    private static List<Path> javaFiles(Path base) throws IOException {
        if (!Files.isDirectory(base)) return List.of();
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("settings.gradle"))) dir = dir.getParent();
        if (dir == null) throw new IllegalStateException("settings.gradle not found above " + Path.of("").toAbsolutePath());
        return dir;
    }
}
