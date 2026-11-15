package tools.dscode.dolt;

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

class DoltHistoryTest {
    @TempDir
    Path project;

    @Test
    void recordListShowAndRetainByAgeThenSize() throws Exception {
        Capture missing = run("dolt", "history", "retain", "--days=1", "--max-bytes=100", "--project=" + project);
        assertEquals(0, missing.code, missing.err);
        assertTrue(missing.out.contains("retained runs=0 removed=0"), missing.out);
        assertTrue(missing.out.contains("database missing"), missing.out);
        assertFalse(Files.exists(DoltPaths.database(project)));

        Capture old = run("dolt", "history", "record", "old-run",
                "--started=2020-01-01T00:00:00Z", "--status=failed", "--message=old-line",
                "--project=" + project);
        assertEquals(0, old.code, old.err);
        assertTrue(old.out.contains("run old-run status failed"), old.out);
        Capture kept = run("dolt", "history", "record", "new-run",
                "--status=passed", "--scenario=Pay", "--passed=1", "--failed=0",
                "--message=new-line", "--project=" + project);
        assertEquals(0, kept.code, kept.err);

        Capture shown = run("dolt", "history", "show", "old-run", "--project=" + project);
        assertEquals(0, shown.code, shown.err);
        assertTrue(shown.out.contains("old-line"), shown.out);
        assertTrue(shown.out.contains("status failed"), shown.out);

        Capture aged = run("dolt", "history", "retain", "--days=1", "--max-bytes=1000000", "--project=" + project);
        assertEquals(0, aged.code, aged.err);
        assertTrue(aged.out.contains("removed=1"), aged.out);
        Capture afterAge = run("dolt", "history", "list", "--project=" + project);
        assertTrue(afterAge.out.contains("new-run"), afterAge.out);
        assertFalse(afterAge.out.contains("old-run"), afterAge.out);

        run("dolt", "history", "record", "size-a", "--started=2024-01-01T00:00:00Z",
                "--note=aaaa", "--project=" + project);
        run("dolt", "history", "record", "size-b", "--started=2024-01-02T00:00:00Z",
                "--note=bbbb", "--project=" + project);
        run("dolt", "history", "record", "size-c", "--started=2024-01-03T00:00:00Z",
                "--note=cccc", "--project=" + project);
        Capture sized = run("dolt", "history", "retain", "--days=3650", "--max-bytes=19", "--project=" + project);
        assertEquals(0, sized.code, sized.err);
        assertTrue(sized.out.contains("removed=1"), sized.out);
        String left = run("dolt", "history", "list", "--project=" + project).out;
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
        Capture recorded = run("dolt", "history", "record", "--from=reports/diagnostic-runs/sample/run-index.json",
                "--project=" + project);
        assertEquals(0, recorded.code, recorded.err);
        assertTrue(recorded.out.contains("run sample status PASSED"), recorded.out);
        assertEquals(before.length, Files.readAllBytes(index).length);
        assertTrue(new String(Files.readAllBytes(index), StandardCharsets.UTF_8).contains("\"outcome\":\"PASSED\""));
        Capture list = run("dolt", "history", "list", "--project=" + project);
        assertTrue(list.out.contains("sample PASSED"), list.out);
        assertTrue(list.out.contains("passed=2"), list.out);
        Capture show = run("dolt", "history", "show", "sample", "--project=" + project);
        assertTrue(show.out.contains("scenario Pay"), show.out);
        assertTrue(show.out.contains("note diagnostic:reports/diagnostic-runs/sample/run-index.json"), show.out);
    }

    @Test
    void cleartextSecretInHistoryIsRejected() throws Exception {
        Capture rejected = run("dolt", "history", "record", "secret-run",
                "--note=password=hunter2", "--project=" + project);
        assertEquals(1, rejected.code, rejected.out);
        assertTrue(rejected.err.contains("secret"), rejected.err);
        if (DoltPaths.ready(project)) {
            assertFalse(DoltDatabase.storedText(project).contains("hunter2"));
        }
        Capture allowed = run("dolt", "history", "record", "secret-run",
                "--note=password=<password:qaDb>", "--project=" + project);
        assertEquals(0, allowed.code, allowed.err);
    }

    @Test
    void historyLoggingIsToggleablePerEntityAndSizeCapped() throws Exception {
        String previous = System.getProperty("pkb_dolt_log");
        String previousEntity = System.getProperty("pkb_dolt_log_entity");
        String previousMax = System.getProperty("pkb_dolt_log_max_bytes");
        try {
            System.setProperty("pkb_dolt_log", "history");
            System.setProperty("pkb_dolt_log_entity", "kept-run");
            run("dolt", "history", "record", "other-run", "--project=" + project);
            run("dolt", "history", "record", "kept-run", "--project=" + project);
            String text = Files.readString(DoltPaths.log(project), StandardCharsets.UTF_8);
            assertTrue(text.contains(" history kept-run "), text);
            assertFalse(text.contains("other-run"), text);
            assertTrue(text.charAt(0) == '2', text);
            System.setProperty("pkb_dolt_log_max_bytes", "80");
            DoltLog.log(project, "history", "kept-run", "y".repeat(200));
            assertTrue(Files.size(DoltPaths.log(project)) <= 80);
            System.setProperty("pkb_dolt_log", "off");
            long size = Files.size(DoltPaths.log(project));
            DoltLog.log(project, "history", "kept-run", "again");
            assertEquals(size, Files.size(DoltPaths.log(project)));
        } finally {
            restore("pkb_dolt_log", previous);
            restore("pkb_dolt_log_entity", previousEntity);
            restore("pkb_dolt_log_max_bytes", previousMax);
        }
    }

    @Test
    void gradleTasksForwardToTheDoltCli() throws Exception {
        String gradle = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);
        assertTrue(gradle.contains("tasks.register('doltStatus'"), excerpt(gradle, "tasks.register('doltStatus'"));
        assertTrue(gradle.contains("tasks.register('doltHydrate'"), excerpt(gradle, "tasks.register('doltHydrate'"));
        assertTrue(gradle.contains("tasks.register('doltExtract'"), excerpt(gradle, "tasks.register('doltExtract'"));
        assertTrue(gradle.contains("tasks.register('doltHistory'"), excerpt(gradle, "tasks.register('doltHistory'"));
        assertTrue(gradle.contains("tasks.register('doltFeature'"), excerpt(gradle, "tasks.register('doltFeature'"));
        assertTrue(gradle.contains("tasks.register('doltEdit'"), excerpt(gradle, "tasks.register('doltEdit'"));
        assertTrue(gradle.contains("tasks.register('doltQuery'"), excerpt(gradle, "tasks.register('doltQuery'"));
        assertTrue(gradle.contains("tasks.register('doltRun'"), excerpt(gradle, "tasks.register('doltRun'"));
        int forwards = 0;
        int cursor = 0;
        String needle = "mainClass = 'tools.dscode.dolt.DoltCli'";
        while (cursor >= 0) {
            cursor = gradle.indexOf(needle, cursor);
            if (cursor < 0) break;
            forwards++;
            cursor += needle.length();
        }
        assertEquals(8, forwards, excerpt(gradle, needle));
        assertTrue(gradle.contains("args = ['dolt', 'status']"), excerpt(gradle, "args = ['dolt', 'status']"));
        assertTrue(gradle.contains("args = ['dolt', 'hydrate']"), excerpt(gradle, "args = ['dolt', 'hydrate']"));
        assertTrue(gradle.contains("args = ['dolt', 'extract']"), excerpt(gradle, "args = ['dolt', 'extract']"));
        assertTrue(gradle.contains("args = ['dolt', 'history']"), excerpt(gradle, "args = ['dolt', 'history']"));
        assertTrue(gradle.contains("args = ['dolt', 'feature']"), excerpt(gradle, "args = ['dolt', 'feature']"));
        assertTrue(gradle.contains("args = ['dolt', 'run']"), excerpt(gradle, "args = ['dolt', 'run']"));
    }

    /** Assertion messages stay a short window. The full build file is not the failure text. */
    private static String excerpt(String text, String needle) {
        if (text == null || text.isEmpty()) return "";
        int at = needle == null ? -1 : text.indexOf(needle);
        int from = at < 0 ? 0 : Math.max(0, at - 80);
        int to = Math.min(text.length(), from + 240);
        String window = text.substring(from, to).replace('\n', ' ').replace('\r', ' ');
        if (from > 0) window = "..." + window;
        if (to < text.length()) window = window + "...";
        return window;
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DoltCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
