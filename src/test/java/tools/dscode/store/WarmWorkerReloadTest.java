package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.runtime.WarmProcessFile;
import tools.dscode.store.sql.SqlConnections;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One store worker JVM reloads an incrementally compiled glue class.
 * The second and third runs must keep that process.
 */
class WarmWorkerReloadTest {
    @TempDir
    Path project;

    @AfterEach
    void release() {
        StoreRun.stop(project);
        StoreGlueCompiler.resetWarmState();
        SqlConnections.closeProject(project);
        StoreScope.clear();
        restore("pkb_warm");
        restore("pkb_store_run_classpath");
        restore("pkb_store_log");
        WarmProcessFile.delete(project);
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void editedGlueReloadsInTheSameWorker() throws Exception {
        StoreGlueCompiler.resetWarmState();
        Files.createDirectories(project.resolve("build/classes/java/test"));
        Files.createDirectories(project.resolve("src/test/java/glue"));
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Path mark = project.resolve("mark.txt");
        String markArg = mark.toAbsolutePath().normalize().toString().replace('\\', '/');
        Path javaFile = project.resolve("src/test/java/glue/Mark.java");
        Files.writeString(javaFile, source("1"));
        Files.writeString(features.resolve("warm.feature"), feature(markArg));
        Files.writeString(project.resolve("pom.xml"), "<project></project>\n");
        Files.writeString(project.resolve("mvnw.cmd"), """
                @echo off
                echo invoked>> "%~dp0invoked.txt"
                exit /b 0
                """);
        Files.writeString(project.resolve("mvnw"), """
                #!/bin/sh
                dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
                printf 'invoked\\n' >> "$dir/invoked.txt"
                exit 0
                """);
        WarmProcessFile.write(project, ProcessHandle.current().pid(), Instant.now());
        System.setProperty("pkb_warm", "on");
        System.setProperty("pkb_store_run_classpath", System.getProperty("java.class.path"));
        System.setProperty("pkb_store_log", "run");
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        SqlConnections.closeProject(project);

        List<String> selection = selection(features);
        StoreRun.Outcome first = StoreRun.execute(project, selection, false);
        assertEquals(0, first.exit(), workerLog(first));
        assertEquals("1", Files.readString(mark).trim(), workerLog(first));
        long pid = warmPid();
        assertTrue(ProcessHandle.of(pid).isPresent(), workerLog(first));
        assertFalse(pid == ProcessHandle.current().pid(), workerLog(first));

        Files.writeString(javaFile, source("2"));
        Files.setLastModifiedTime(javaFile, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        StoreRun.Outcome second = StoreRun.execute(project, selection, false);
        assertEquals(0, second.exit(), workerLog(second));
        assertEquals(pid, warmPid(), workerLog(second));
        assertTrue(second.reused(), workerLog(second));
        assertEquals("2", Files.readString(mark).trim(), workerLog(second));
        assertFalse(Files.isRegularFile(project.resolve("invoked.txt")), workerLog(second));
        String log = storeLog();
        int incremental = count(log, "detail=incremental");
        assertTrue(log.contains("tool=JavaCompiler detail=incremental"), log);

        Path classFile = project.resolve(".pickleball/spine/classes/glue/Mark.class");
        assertTrue(Files.isRegularFile(classFile), workerLog(second));
        FileTime classTime = Files.getLastModifiedTime(classFile);
        StoreRun.Outcome third = StoreRun.execute(project, selection, false);
        assertEquals(0, third.exit(), workerLog(third));
        assertTrue(third.reused(), workerLog(third));
        assertEquals(pid, warmPid(), workerLog(third));
        assertEquals("2", Files.readString(mark).trim(), workerLog(third));
        assertEquals(classTime, Files.getLastModifiedTime(classFile));
        assertEquals(incremental, count(storeLog(), "detail=incremental"), storeLog());
        assertFalse(Files.isRegularFile(project.resolve("invoked.txt")));
    }

    private static String source(String body) {
        return """
                package glue;

                import io.cucumber.java.en.Given;

                import java.nio.file.Files;
                import java.nio.file.Path;

                public class Mark {
                    @Given("^the warm worker marks \\"([^\\"]*)\\"$")
                    public void mark(String path) throws Exception {
                        Files.writeString(Path.of(path), "%s");
                    }
                }
                """.formatted(body);
    }

    private static String feature(String markArg) {
        return """
                Feature: Warm worker reload
                  @warm-reload
                  Scenario: Mark the file
                    * the warm worker marks "%s"
                """.formatted(markArg);
    }

    private List<String> selection(Path features) {
        String featureDir = features.toAbsolutePath().normalize().toString().replace('\\', '/');
        return List.of(
                "-Dpkb_tags=@warm-reload",
                "-Dpkb_glue=glue",
                "-Dpkb_features=wd-file:" + featureDir,
                "-Dpkb_warm=on",
                "-Dpkb_warm_packages=glue",
                "-Dpkb_heap_subprocess_percent=101",
                "-Dpkb_heap_refuse_percent=101",
                "-Dpkb_loglevel=error",
                "-Dpkb_parallel=1",
                "-Dcucumber.execution.parallel.enabled=false"
        );
    }

    private long warmPid() throws Exception {
        List<String> lines = Files.readAllLines(
                project.resolve(".pickleball/store/sandboxes/main/worker/warm.txt"));
        return Long.parseLong(lines.get(0).trim());
    }

    private String workerLog(StoreRun.Outcome outcome) throws Exception {
        Path err = project.resolve(".pickleball/store/sandboxes/main/worker/warm.err.log");
        String logged = Files.isRegularFile(err) ? Files.readString(err) : "";
        return outcome.out() + "\n" + outcome.err() + "\n" + logged;
    }

    private String storeLog() throws Exception {
        Path log = project.resolve(".pickleball/store/store.log");
        return Files.isRegularFile(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
    }

    private static int count(String text, String token) {
        int found = 0;
        int from = 0;
        while (from >= 0 && text != null) {
            int at = text.indexOf(token, from);
            if (at < 0) break;
            found++;
            from = at + token.length();
        }
        return found;
    }

    private static void restore(String key) {
        System.clearProperty(key);
    }
}
