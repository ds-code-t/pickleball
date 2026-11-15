package tools.dscode.dolt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A classes-directory change rewrites classpath.cache and restarts the worker. */
class DoltRunClasspathRefreshTest {
    @TempDir
    Path project;

    @AfterEach
    void release() {
        DoltRun.stop(project);
        DoltScope.clear();
        System.clearProperty("pkb_dolt_run_classpath");
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void classesDirectoryChangeRewritesTheCacheAndRestartsTheWorker() throws Exception {
        Path features = project.resolve("features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("pay.feature"), """
                Feature: Pay
                  @dolt-classpath
                  Scenario: Ada
                    * INFO: amount is 3
                    * ASSERT: "3" equals "3"
                """);
        Files.createDirectories(project.resolve(".pickleball/dolt/sandboxes/main/worker"));
        Files.writeString(
                project.resolve(".pickleball/dolt/sandboxes/main/worker/glue.fingerprint"),
                DoltRun.glueFingerprint(project));
        System.setProperty("pkb_dolt_run_classpath", System.getProperty("java.class.path"));
        List<String> selection = List.of(
                "-Dpkb_tags=@dolt-classpath",
                "-Dpkb_glue=tools.dscode.coredefinitions",
                "-Dpkb_features=wd-file:" + features.toAbsolutePath().normalize(),
                "-Dpkb_loglevel=error",
                "-Dpkb_parallel=1",
                "-Dcucumber.execution.parallel.enabled=false");

        DoltRun.Outcome first = DoltRun.execute(project, selection, false);
        assertEquals(0, first.exit(), first.out() + first.err());
        assertFalse(first.reused(), first.out());
        DoltRun.Outcome second = DoltRun.execute(project, selection, false);
        assertEquals(0, second.exit(), second.out() + second.err());
        assertTrue(second.reused(), second.out());

        Path classes = DoltPaths.classes(project, "main");
        Files.createDirectories(classes.resolve("sample"));
        Files.write(classes.resolve("sample/Marker.class"), new byte[] {1, 2, 3, 4, 5});
        DoltRun.Outcome restarted = DoltRun.execute(project, selection, false);
        assertEquals(0, restarted.exit(), restarted.out() + restarted.err());
        assertFalse(restarted.reused(), restarted.out());
        String cache = Files.readString(
                DoltPaths.worker(project, "main").resolve(DoltRun.CACHE), StandardCharsets.UTF_8).trim();
        if (cache.startsWith("\"") && cache.endsWith("\"") && cache.length() >= 2) {
            cache = cache.substring(1, cache.length() - 1);
        }
        String firstEntry = cache.split(java.io.File.pathSeparator, 2)[0];
        assertEquals(classes.toAbsolutePath().normalize().toString(), firstEntry, cache);

        DoltRun.Outcome warm = DoltRun.execute(project, selection, false);
        assertEquals(0, warm.exit(), warm.out() + warm.err());
        assertTrue(warm.reused(), warm.out());
    }
}
