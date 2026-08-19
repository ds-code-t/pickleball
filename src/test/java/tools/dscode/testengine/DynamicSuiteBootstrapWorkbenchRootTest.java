package tools.dscode.testengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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

}
