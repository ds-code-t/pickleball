package tools.dscode.store;

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

class StoreHistoryTest {
    @TempDir
    Path project;

    @Test
    void recordListShowAndRetainByAgeThenSize() throws Exception {
        Capture missing = run("store", "history", "retain", "--days=1", "--max-bytes=100", "--project=" + project);
        assertEquals(0, missing.code, missing.err);
        assertTrue(missing.out.contains("retained runs=0 removed=0"), missing.out);
        assertTrue(missing.out.contains("database missing"), missing.out);
        assertFalse(Files.exists(StorePaths.database(project)));

        Capture old = run("store", "history", "record", "old-run",
                "--started=2020-01-01T00:00:00Z", "--status=failed", "--message=old-line",
                "--project=" + project);
        assertEquals(0, old.code, old.err);
        assertTrue(old.out.contains("run old-run status failed"), old.out);
        Capture kept = run("store", "history", "record", "new-run",
                "--status=passed", "--scenario=Pay", "--passed=1", "--failed=0",
                "--message=new-line", "--project=" + project);
        assertEquals(0, kept.code, kept.err);

        Capture shown = run("store", "history", "show", "old-run", "--project=" + project);
        assertEquals(0, shown.code, shown.err);
        assertTrue(shown.out.contains("old-line"), shown.out);
        assertTrue(shown.out.contains("status failed"), shown.out);

        Capture aged = run("store", "history", "retain", "--days=1", "--max-bytes=1000000", "--project=" + project);
        assertEquals(0, aged.code, aged.err);
        assertTrue(aged.out.contains("removed=1"), aged.out);
        Capture afterAge = run("store", "history", "list", "--project=" + project);
        assertTrue(afterAge.out.contains("new-run"), afterAge.out);
        assertFalse(afterAge.out.contains("old-run"), afterAge.out);

        run("store", "history", "record", "size-a", "--started=2024-01-01T00:00:00Z",
                "--note=aaaa", "--project=" + project);
        run("store", "history", "record", "size-b", "--started=2024-01-02T00:00:00Z",
                "--note=bbbb", "--project=" + project);
        run("store", "history", "record", "size-c", "--started=2024-01-03T00:00:00Z",
                "--note=cccc", "--project=" + project);
        Capture sized = run("store", "history", "retain", "--days=3650", "--max-bytes=19", "--project=" + project);
        assertEquals(0, sized.code, sized.err);
        assertTrue(sized.out.contains("removed=1"), sized.out);
        String left = run("store", "history", "list", "--project=" + project).out;
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
        Capture recorded = run("store", "history", "record", "--from=reports/diagnostic-runs/sample/run-index.json",
                "--project=" + project);
        assertEquals(0, recorded.code, recorded.err);
        assertTrue(recorded.out.contains("run sample status PASSED"), recorded.out);
        assertEquals(before.length, Files.readAllBytes(index).length);
        assertTrue(new String(Files.readAllBytes(index), StandardCharsets.UTF_8).contains("\"outcome\":\"PASSED\""));
        Capture list = run("store", "history", "list", "--project=" + project);
        assertTrue(list.out.contains("sample PASSED"), list.out);
        assertTrue(list.out.contains("passed=2"), list.out);
        Capture show = run("store", "history", "show", "sample", "--project=" + project);
        assertTrue(show.out.contains("scenario Pay"), show.out);
        assertTrue(show.out.contains("note diagnostic:reports/diagnostic-runs/sample/run-index.json"), show.out);
    }

    @Test
    void cleartextSecretInHistoryIsRejected() throws Exception {
        Capture rejected = run("store", "history", "record", "secret-run",
                "--note=password=hunter2", "--project=" + project);
        assertEquals(1, rejected.code, rejected.out);
        assertTrue(rejected.err.contains("secret"), rejected.err);
        if (StorePaths.ready(project)) {
            assertFalse(StoreDatabase.storedText(project).contains("hunter2"));
        }
        Capture allowed = run("store", "history", "record", "secret-run",
                "--note=password=<password:qaDb>", "--project=" + project);
        assertEquals(0, allowed.code, allowed.err);
    }

    @Test
    void historyLoggingIsToggleablePerEntityAndSizeCapped() throws Exception {
        String previous = System.getProperty("pkb_store_log");
        String previousEntity = System.getProperty("pkb_store_log_entity");
        String previousMax = System.getProperty("pkb_store_log_max_bytes");
        try {
            System.setProperty("pkb_store_log", "history");
            System.setProperty("pkb_store_log_entity", "kept-run");
            run("store", "history", "record", "other-run", "--project=" + project);
            run("store", "history", "record", "kept-run", "--project=" + project);
            String text = Files.readString(StorePaths.log(project), StandardCharsets.UTF_8);
            assertTrue(text.contains(" history kept-run "), text);
            assertFalse(text.contains("other-run"), text);
            assertTrue(text.charAt(0) == '2', text);
            System.setProperty("pkb_store_log_max_bytes", "80");
            StoreLog.log(project, "history", "kept-run", "y".repeat(200));
            assertTrue(Files.size(StorePaths.log(project)) <= 80);
            System.setProperty("pkb_store_log", "off");
            long size = Files.size(StorePaths.log(project));
            StoreLog.log(project, "history", "kept-run", "again");
            assertEquals(size, Files.size(StorePaths.log(project)));
        } finally {
            restore("pkb_store_log", previous);
            restore("pkb_store_log_entity", previousEntity);
            restore("pkb_store_log_max_bytes", previousMax);
        }
    }

    @Test
    void gradleTasksForwardToTheStoreCli() throws Exception {
        String gradle = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);
        assertTrue(gradle.contains("tasks.register('storeStatus'"), excerpt(gradle, "tasks.register('storeStatus'"));
        assertTrue(gradle.contains("tasks.register('storeHydrate'"), excerpt(gradle, "tasks.register('storeHydrate'"));
        assertTrue(gradle.contains("tasks.register('storeExtract'"), excerpt(gradle, "tasks.register('storeExtract'"));
        assertTrue(gradle.contains("tasks.register('storeHistory'"), excerpt(gradle, "tasks.register('storeHistory'"));
        assertTrue(gradle.contains("tasks.register('storeFeature'"), excerpt(gradle, "tasks.register('storeFeature'"));
        assertTrue(gradle.contains("tasks.register('storeEdit'"), excerpt(gradle, "tasks.register('storeEdit'"));
        assertTrue(gradle.contains("tasks.register('storeQuery'"), excerpt(gradle, "tasks.register('storeQuery'"));
        assertTrue(gradle.contains("tasks.register('storeRun'"), excerpt(gradle, "tasks.register('storeRun'"));
        assertTrue(gradle.contains("tasks.register('storeSandbox'"), excerpt(gradle, "tasks.register('storeSandbox'"));
        assertTrue(gradle.contains("tasks.register('storeMigrate'"), excerpt(gradle, "tasks.register('storeMigrate'"));
        assertTrue(gradle.contains("tasks.register('storeRunClasspath', JavaExec)"), excerpt(gradle, "tasks.register('storeRunClasspath'"));
        int forwards = 0;
        int cursor = 0;
        String needle = "mainClass = 'tools.dscode.store.StoreCli'";
        while (cursor >= 0) {
            cursor = gradle.indexOf(needle, cursor);
            if (cursor < 0) break;
            forwards++;
            cursor += needle.length();
        }
        assertEquals(11, forwards, excerpt(gradle, needle));
        assertTrue(gradle.contains("args = ['store', 'status']"), excerpt(gradle, "args = ['store', 'status']"));
        assertTrue(gradle.contains("args = ['store', 'hydrate']"), excerpt(gradle, "args = ['store', 'hydrate']"));
        assertTrue(gradle.contains("args = ['store', 'extract']"), excerpt(gradle, "args = ['store', 'extract']"));
        assertTrue(gradle.contains("args = ['store', 'history']"), excerpt(gradle, "args = ['store', 'history']"));
        assertTrue(gradle.contains("args = ['store', 'feature']"), excerpt(gradle, "args = ['store', 'feature']"));
        assertTrue(gradle.contains("args = ['store', 'run']"), excerpt(gradle, "args = ['store', 'run']"));
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
        int code = StoreCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
