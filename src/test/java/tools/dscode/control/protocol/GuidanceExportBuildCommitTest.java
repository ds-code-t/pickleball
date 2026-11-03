package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuidanceExportBuildCommitTest {

    @Test
    void exportGuidancePrintsVersionAndBuildCommit(@TempDir Path dir) throws Exception {
        Properties build = new Properties();
        try (InputStream in = PickleballLocalStore.class.getClassLoader()
                .getResourceAsStream("META-INF/pickleball-build.properties")) {
            assertNotNull(in, "build provenance properties are on the test classpath");
            build.load(in);
        }
        String commit = build.getProperty("commit", "");
        assertFalse(commit.isBlank(), "build provenance recorded a commit");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PickleballLocalStore.exportGuidance(dir.resolve("guidance"),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        String first = out.toString(StandardCharsets.UTF_8).lines().findFirst().orElse("");
        assertTrue(first.startsWith("Pickleball " + PickleballLocalStore.runningVersion() + " built from commit "), first);
        assertTrue(first.endsWith(commit.trim()), first);
    }
}
