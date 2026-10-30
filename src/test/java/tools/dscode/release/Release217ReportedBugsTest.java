package tools.dscode.release;

import io.cucumber.core.feature.FeatureWithLines;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballPaths;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for bugs reported against 2.1.17. */
class Release217ReportedBugsTest {

    /** Bug 1: under mvn exec:java the project's test classes are only on the context class loader. */
    @Test
    void execJavaContextClassLoaderTestOutputIsOnTheEffectiveClasspath(@TempDir Path temp) throws Exception {
        Path launcher = Files.createFile(temp.resolve("plexus-classworlds.jar"));
        Path testClasses = Files.createDirectories(temp.resolve("consumer/target/test-classes"));
        try (java.net.URLClassLoader execJava = new java.net.URLClassLoader(
                new java.net.URL[]{testClasses.toUri().toURL()}, null)) {
            java.util.List<String> entries = tools.dscode.testengine.DynamicSuiteBootstrapAccess
                    .effective(launcher.toString(), execJava);
            assertTrue(entries.contains(testClasses.toString()), entries.toString());
            assertTrue(entries.contains(launcher.toString()), entries.toString());

            String normal = testClasses + java.io.File.pathSeparator + launcher;
            assertEquals(java.util.List.of(testClasses.toString(), launcher.toString()),
                    tools.dscode.testengine.DynamicSuiteBootstrapAccess.effective(normal, execJava),
                    "a normal run's java.class.path must be used unchanged");
        }
    }

    /** Bug 2: a Workbench feature folder with a space failed Cucumber URI parsing. */
    @Test
    void featureFolderWithSpaceParsesAndResolvesToTheFile(@TempDir Path temp) throws Exception {
        Path dir = Files.createDirectories(temp.resolve("space dir"));
        Path feature = Files.writeString(dir.resolve("a b.feature"), "Feature: x\n");
        String configured = "wd-file:" + feature.toAbsolutePath().toString().replace('\\', '/') + ":3";

        String uri = PickleballPaths.toCucumberFeatureUri(configured);
        assertTrue(uri.startsWith("file:"), uri);
        assertTrue(uri.endsWith(":3"), uri);
        FeatureWithLines parsed = FeatureWithLines.parse(uri);
        assertEquals(feature.toRealPath(), Path.of(parsed.uri()).toRealPath());
        assertEquals(3, parsed.lines().iterator().next());
    }

    @Test
    void pathWithoutIllegalCharactersIsUnchanged() {
        assertEquals("file:/tmp/features/a.feature:7",
                PickleballPaths.toCucumberFeatureUri("wd-file:/tmp/features/a.feature:7"));
    }
}
