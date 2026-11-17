package tools.dscode.testengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Level;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicSuiteBootstrapWorkbenchRootTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitWorkbenchRootOverridesBuildToolSuffixHeuristics() {
        String property = DynamicSuiteBootstrap.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY;
        String previous = System.getProperty(property);
        try {
            Path live = tempDir.resolve(".pickleball/workbench/live/classes").toAbsolutePath().normalize();
            System.setProperty(property, live.toString());

            assertTrue(DynamicSuiteBootstrap.isPreferredTestOutputRoot(live.toUri()));
            assertFalse(DynamicSuiteBootstrap.isPreferredTestOutputRoot(
                    tempDir.resolve("target/test-classes").toUri()
            ));
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void ordinaryBuildOutputHeuristicsRemainWhenWorkbenchRootIsAbsent() throws Exception {
        String property = DynamicSuiteBootstrap.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            Path mavenTestOutput = tempDir.resolve("target/test-classes");
            Files.createDirectories(mavenTestOutput);
            assertTrue(DynamicSuiteBootstrap.isPreferredTestOutputRoot(mavenTestOutput.toUri()));
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void syncedLiveClassesAreAPreferredRootWithoutAnExplicitProperty() throws Exception {
        String property = DynamicSuiteBootstrap.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            Path live = tempDir.resolve(".pickleball/v/2.1.18/workbench/live/classes");
            Files.createDirectories(live);
            assertTrue(DynamicSuiteBootstrap.isPreferredTestOutputRoot(live.toUri()));
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void warmGenerationTestClassesAreAPreferredRootWithoutAnExplicitProperty() throws Exception {
        String property = DynamicSuiteBootstrap.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            Path generation = tempDir.resolve(".pickleball/v/2.20.0/workbench/warm/gen-0a1b2c3d/test-classes");
            Files.createDirectories(generation);
            assertTrue(DynamicSuiteBootstrap.isPreferredTestOutputRoot(generation.toUri()));
            Path legacy = tempDir.resolve(".pickleball/workbench/warm/gen-ffee/test-classes");
            Files.createDirectories(legacy);
            assertTrue(DynamicSuiteBootstrap.isPreferredTestOutputRoot(legacy.toUri()));
            // The production classes copy is not a test-output root.
            Path classes = tempDir.resolve(".pickleball/v/2.20.0/workbench/warm/gen-0a1b2c3d/classes");
            Files.createDirectories(classes);
            assertFalse(DynamicSuiteBootstrap.isPreferredTestOutputRoot(classes.toUri()));
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void warmGenerationPathMatchingIsCrossPlatformAndStrict() {
        assertTrue(DynamicSuiteBootstrap.isWarmGenerationTestClasses(
                "C:\\work\\consumer\\.pickleball\\v\\2.20.0\\workbench\\warm\\gen-abc123\\test-classes"));
        assertTrue(DynamicSuiteBootstrap.isWarmGenerationTestClasses(
                "file:///C:/work/consumer/.pickleball/v/2.20.0/workbench/warm/gen-abc123/test-classes/"));
        assertTrue(DynamicSuiteBootstrap.isWarmGenerationTestClasses(
                "/home/u/consumer/.pickleball/workbench/warm/gen-abc123/test-classes"));
        assertFalse(DynamicSuiteBootstrap.isWarmGenerationTestClasses(
                "/home/u/consumer/.pickleball/workbench/warm/gen-/test-classes"));
        assertFalse(DynamicSuiteBootstrap.isWarmGenerationTestClasses(
                "/home/u/consumer/.pickleball/workbench/warm/slot-1/test-classes"));
        assertFalse(DynamicSuiteBootstrap.isWarmGenerationTestClasses(
                "/home/u/consumer/other/warm/gen-abc123/test-classes"));
        assertFalse(DynamicSuiteBootstrap.isWarmGenerationTestClasses(
                "/home/u/consumer/.pickleball/workbench/warm/gen-abc123/classes"));
        assertFalse(DynamicSuiteBootstrap.isWarmGenerationTestClasses(null));
    }

    @Test
    void explicitWorkbenchRootMatchesAWarmGenerationCopy() {
        String property = DynamicSuiteBootstrap.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY;
        String previous = System.getProperty(property);
        try {
            Path generation = tempDir.resolve(".pickleball/v/2.20.0/workbench/warm/gen-9f/test-classes")
                    .toAbsolutePath().normalize();
            System.setProperty(property, generation.toString());
            assertTrue(DynamicSuiteBootstrap.isPreferredTestOutputRoot(generation.toUri()));
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void missingConsumerRunnerWarnsWhenAWorkbenchRootWasExpected() {
        String property = DynamicSuiteBootstrap.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY;
        String warm = DynamicSuiteBootstrap.WARM_TEST_CLASSES_PROPERTY;
        String previous = System.getProperty(property);
        String previousWarm = System.getProperty(warm);
        try {
            System.clearProperty(property);
            System.clearProperty(warm);
            assertEquals(null, DynamicSuiteBootstrap.noConsumerRunnerWarning(null));
            System.setProperty(property, tempDir.resolve("workbench/warm/gen-1/test-classes").toString());
            String warning = DynamicSuiteBootstrap.noConsumerRunnerWarning(null);
            assertTrue(warning != null && warning.contains("BEFORE_CUCUMBER_RUN"), String.valueOf(warning));
            assertTrue(warning.contains(property), warning);
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
            if (previousWarm == null) System.clearProperty(warm);
            else System.setProperty(warm, previousWarm);
        }
    }

    @Test
    void liveDetachedTypedLoggingAllowsUntypedParent() {
        Entry scenario = Entry.of("scenario");
        Entry detached = scenario.child("Detached control step");

        assertDoesNotThrow(() -> detached.logWithType("PHRASE", "assertion", Level.INFO));

        assertEquals("PHRASE", detached.normalizedType);
        assertEquals(1, detached.count);
        assertEquals(1, detached.flatCount);
    }

    @Test
    void sameTypeParentStillSharesNestedCounts() {
        Entry parent = Entry.of("parent");
        parent.logWithType("STEP", "parent", Level.INFO);
        Entry child = parent.child("child");

        child.logWithType("STEP", "child", Level.INFO);

        assertEquals(2, child.count);
        assertEquals(2, child.flatCount);
    }
}
