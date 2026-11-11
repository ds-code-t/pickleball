package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonHistoryTest {
    @TempDir
    Path project;

    @Test
    void recordListShowAndRetainByAgeThenSize() throws Exception {
        Capture missing = run("dalton", "history", "retain", "--days=1", "--max-bytes=100", "--project=" + project);
        assertEquals(0, missing.code, missing.err);
        assertTrue(missing.out.contains("retained runs=0 removed=0"), missing.out);
        assertTrue(missing.out.contains("database missing"), missing.out);
        assertFalse(Files.exists(DaltonPaths.database(project)));

        Capture old = run("dalton", "history", "record", "old-run",
                "--started=2020-01-01T00:00:00Z", "--status=failed", "--message=old-line",
                "--project=" + project);
        assertEquals(0, old.code, old.err);
        assertTrue(old.out.contains("run old-run status failed"), old.out);
        Capture kept = run("dalton", "history", "record", "new-run",
                "--status=passed", "--scenario=Pay", "--passed=1", "--failed=0",
                "--message=new-line", "--project=" + project);
        assertEquals(0, kept.code, kept.err);

        Capture shown = run("dalton", "history", "show", "old-run", "--project=" + project);
        assertEquals(0, shown.code, shown.err);
        assertTrue(shown.out.contains("old-line"), shown.out);
        assertTrue(shown.out.contains("status failed"), shown.out);

        Capture aged = run("dalton", "history", "retain", "--days=1", "--max-bytes=1000000", "--project=" + project);
        assertEquals(0, aged.code, aged.err);
        assertTrue(aged.out.contains("removed=1"), aged.out);
        Capture afterAge = run("dalton", "history", "list", "--project=" + project);
        assertTrue(afterAge.out.contains("new-run"), afterAge.out);
        assertFalse(afterAge.out.contains("old-run"), afterAge.out);

        run("dalton", "history", "record", "size-a", "--started=2024-01-01T00:00:00Z",
                "--note=aaaa", "--project=" + project);
        run("dalton", "history", "record", "size-b", "--started=2024-01-02T00:00:00Z",
                "--note=bbbb", "--project=" + project);
        run("dalton", "history", "record", "size-c", "--started=2024-01-03T00:00:00Z",
                "--note=cccc", "--project=" + project);
        Capture sized = run("dalton", "history", "retain", "--days=3650", "--max-bytes=19", "--project=" + project);
        assertEquals(0, sized.code, sized.err);
        assertTrue(sized.out.contains("removed=1"), sized.out);
        String left = run("dalton", "history", "list", "--project=" + project).out;
        assertFalse(left.contains("size-a"), left);
        assertTrue(left.contains("size-c"), left);
        assertTrue(left.contains("new-run"), left);
        assertFalse(Files.exists(project.resolve("reports")));
    }

    @Test
    void diagnosticImportCopiesOutcomeWithoutChangingTheReport() throws Exception {
        Path index = project.resolve("reports/diagnostic-runs/sample/run-index.json");
        Files.createDirectories(index.getParent());
        String json = """
                {"schemaVersion":1,"runId":"sample","outcome":"PASSED","startedAt":"2026-01-02T00:00:00Z","counts":{"passed":2,"failed":0},"scenarios":[{"name":"Pay"}]}
                """;
        Files.writeString(index, json, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(index);
        Capture recorded = run("dalton", "history", "record", "--from=reports/diagnostic-runs/sample/run-index.json",
                "--project=" + project);
        assertEquals(0, recorded.code, recorded.err);
        assertTrue(recorded.out.contains("run sample status PASSED"), recorded.out);
        assertEquals(before.length, Files.readAllBytes(index).length);
        assertTrue(new String(Files.readAllBytes(index), StandardCharsets.UTF_8).contains("\"outcome\":\"PASSED\""));
        Capture list = run("dalton", "history", "list", "--project=" + project);
        assertTrue(list.out.contains("sample PASSED"), list.out);
        assertTrue(list.out.contains("passed=2"), list.out);
        Capture show = run("dalton", "history", "show", "sample", "--project=" + project);
        assertTrue(show.out.contains("scenario Pay"), show.out);
        assertTrue(show.out.contains("note diagnostic:reports/diagnostic-runs/sample/run-index.json"), show.out);
    }

    @Test
    void cleartextSecretInHistoryIsRejected() throws Exception {
        Capture rejected = run("dalton", "history", "record", "secret-run",
                "--note=password=hunter2", "--project=" + project);
        assertEquals(1, rejected.code, rejected.out);
        assertTrue(rejected.err.contains("secret"), rejected.err);
        if (Files.isRegularFile(DaltonPaths.database(project))) {
            assertFalse(new String(Files.readAllBytes(DaltonPaths.database(project)), StandardCharsets.ISO_8859_1)
                    .contains("hunter2"));
        }
        Capture allowed = run("dalton", "history", "record", "secret-run",
                "--note=password=<password:qaDb>", "--project=" + project);
        assertEquals(0, allowed.code, allowed.err);
    }

    @Test
    void historyLoggingIsToggleablePerEntityAndSizeCapped() throws Exception {
        String previous = System.getProperty("pkb_dalton_log");
        String previousEntity = System.getProperty("pkb_dalton_log_entity");
        String previousMax = System.getProperty("pkb_dalton_log_max_bytes");
        try {
            System.setProperty("pkb_dalton_log", "history");
            System.setProperty("pkb_dalton_log_entity", "kept-run");
            run("dalton", "history", "record", "other-run", "--project=" + project);
            run("dalton", "history", "record", "kept-run", "--project=" + project);
            String text = Files.readString(DaltonPaths.log(project), StandardCharsets.UTF_8);
            assertTrue(text.contains(" history kept-run "), text);
            assertFalse(text.contains("other-run"), text);
            assertTrue(text.charAt(0) == '2', text);
            System.setProperty("pkb_dalton_log_max_bytes", "80");
            DaltonLog.log(project, "history", "kept-run", "y".repeat(200));
            assertTrue(Files.size(DaltonPaths.log(project)) <= 80);
            System.setProperty("pkb_dalton_log", "off");
            long size = Files.size(DaltonPaths.log(project));
            DaltonLog.log(project, "history", "kept-run", "again");
            assertEquals(size, Files.size(DaltonPaths.log(project)));
        } finally {
            restore("pkb_dalton_log", previous);
            restore("pkb_dalton_log_entity", previousEntity);
            restore("pkb_dalton_log_max_bytes", previousMax);
        }
    }

    @Test
    void gradleTasksForwardToTheDaltonCli() throws Exception {
        String gradle = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);
        assertTrue(gradle.contains("tasks.register('daltonStatus'"), gradle);
        assertTrue(gradle.contains("tasks.register('daltonHydrate'"), gradle);
        assertTrue(gradle.contains("tasks.register('daltonExtract'"), gradle);
        assertTrue(gradle.contains("tasks.register('daltonHistory'"), gradle);
        assertTrue(gradle.contains("tasks.register('daltonFeature'"), gradle);
        assertTrue(gradle.contains("tasks.register('daltonQuery'"), gradle);
        assertTrue(gradle.contains("tasks.register('daltonRun'"), gradle);
        int forwards = 0;
        int cursor = 0;
        String needle = "mainClass = 'tools.dscode.dalton.DaltonCli'";
        while (cursor >= 0) {
            cursor = gradle.indexOf(needle, cursor);
            if (cursor < 0) break;
            forwards++;
            cursor += needle.length();
        }
        assertEquals(7, forwards, gradle);
        assertTrue(gradle.contains("args = ['dalton', 'status']"), gradle);
        assertTrue(gradle.contains("args = ['dalton', 'hydrate']"), gradle);
        assertTrue(gradle.contains("args = ['dalton', 'extract']"), gradle);
        assertTrue(gradle.contains("args = ['dalton', 'history']"), gradle);
        assertTrue(gradle.contains("args = ['dalton', 'feature']"), gradle);
        assertTrue(gradle.contains("args = ['dalton', 'run']"), gradle);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DaltonCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
