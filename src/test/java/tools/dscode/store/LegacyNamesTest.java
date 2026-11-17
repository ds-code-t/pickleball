package tools.dscode.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Old names fail loudly. The fix line names the replacement. */
class LegacyNamesTest {
    @TempDir
    Path project;

    @Test
    void aCleanProjectIsQuiet() {
        assertNull(LegacyNames.problem(project));
    }

    @Test
    void anOldPropertyFailsAndNamesTheReplacement() {
        String key = "pkb_dalton_log";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "status");
            String problem = LegacyNames.problem(project);
            assertTrue(problem.contains("error: " + key + " is no longer read"), problem);
            assertTrue(problem.contains("fix: use pkb_store_log"), problem);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> LegacyNames.refuse(project));
            assertTrue(failure.getMessage().contains("fix: use pkb_store_log"), failure.getMessage());
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    @Test
    void anOldPropertiesFileFails() throws Exception {
        Files.writeString(project.resolve("dalton.properties"), "enabled=false\n", StandardCharsets.UTF_8);
        String problem = LegacyNames.problem(project);
        assertTrue(problem.contains("error: dalton.properties is no longer read"), problem);
        assertTrue(problem.contains("fix: use store.properties"), problem);
        assertEquals(1, LegacyNames.moveLines(project).size());
        assertTrue(LegacyNames.moveLines(project).get(0).contains("store.properties"), LegacyNames.moveLines(project).toString());
    }

    @Test
    void anOldEntityDirectoryFailsUntilTheNewOneExists() throws Exception {
        Files.createDirectories(project.resolve("dalton-entities"));
        String problem = LegacyNames.problem(project);
        assertTrue(problem.contains("error: dalton-entities is no longer read"), problem);
        assertTrue(problem.contains("fix: use store-entities"), problem);
        Files.createDirectories(project.resolve("store-entities"));
        assertNull(LegacyNames.problem(project));
    }

    @Test
    void anOldEnvironmentVariableFailsInAFreshJvm() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "tools.dscode.store.StoreCli",
                "store", "status", "--project=" + project.toAbsolutePath());
        builder.environment().put("PKB_DALTON", "false");
        builder.environment().remove("PKB_STORE");
        Process process = builder.start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("timed out " + out + err);
        }
        assertEquals(1, process.exitValue(), out + err);
        assertTrue(err.contains("error: PKB_DALTON is no longer read"), err);
        assertTrue(err.contains("fix: use PKB_STORE"), err);
    }
}
