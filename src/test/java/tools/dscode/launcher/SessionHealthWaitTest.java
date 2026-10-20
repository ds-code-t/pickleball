package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionHealthWaitTest {
    @TempDir
    Path project;

    @Test
    void timeoutMatchesTheWorkerStartResolutionOrder() {
        assertEquals(Duration.ofSeconds(180), SessionHealthWait.DEFAULT_TIMEOUT);
        assertEquals(Duration.ofSeconds(12), SessionHealthWait.PROGRESS_EVERY);
        assertTrue(SessionHealthWait.PROGRESS_EVERY.compareTo(Duration.ofSeconds(10)) >= 0);
        assertTrue(SessionHealthWait.PROGRESS_EVERY.compareTo(Duration.ofSeconds(15)) <= 0);
        assertEquals(Duration.ofSeconds(60), WorkbenchSessionCommands.OPEN_WINDOW_HEALTH_TIMEOUT);

        String property = SessionHealthWait.START_TIMEOUT_PROPERTY;
        assertEquals(Duration.ofSeconds(12), SessionHealthWait.timeout(
                Map.of(property, "90"),
                key -> property.equals(key) ? "12" : null,
                key -> "99"
        ));
        assertEquals(Duration.ofSeconds(15), SessionHealthWait.timeout(
                Map.of(property, "90"),
                key -> null,
                key -> SessionHealthWait.START_TIMEOUT_ENV.equals(key) ? "15" : null
        ));
        assertEquals(Duration.ofSeconds(30), SessionHealthWait.timeout(
                Map.of(),
                key -> SessionHealthWait.START_TIMEOUT_RUNVAR.equals(key) ? "30" : null,
                key -> null
        ));
        assertEquals(Duration.ofSeconds(45), SessionHealthWait.timeout(
                Map.of(),
                key -> "pkb_runvars".equals(key)
                        ? "pkb_browser=CHROME_HEADLESS, pkb_workbench_worker_start_timeout=45"
                        : null,
                key -> null
        ));
        assertEquals(Duration.ofSeconds(90), SessionHealthWait.timeout(
                Map.of("pkb_runvars", "pkb_workbench_worker_start_timeout=90"),
                key -> null,
                key -> null
        ));
        assertEquals(Duration.ofSeconds(180), SessionHealthWait.timeout(Map.of(property, "nope"), key -> null, key -> null));
        assertEquals(Duration.ofSeconds(180), SessionHealthWait.timeout(Map.of(property, "0"), key -> null, key -> null));
        assertEquals(Duration.ofSeconds(180), SessionHealthWait.timeout(Map.of(property, "-4"), key -> null, key -> null));
        assertEquals(Duration.ofSeconds(180), SessionHealthWait.timeout(Map.of(), key -> " ", key -> " "));
        assertEquals(Duration.ofSeconds(8), SessionHealthWait.timeout(
                Map.of(),
                key -> property.equals(key) ? " 8 " : null,
                key -> null
        ));
    }

    @Test
    void systemPropertiesUseTheSameFallbackAsTheWorker() {
        String property = SessionHealthWait.START_TIMEOUT_PROPERTY;
        String previous = System.getProperty(property);
        String previousRunVars = System.getProperty("pkb_runvars");
        String previousDotted = System.getProperty(SessionHealthWait.START_TIMEOUT_RUNVAR);
        try {
            assertEquals(Duration.ofSeconds(180), SessionHealthWait.timeout(Map.of()));
            System.setProperty(property, "12");
            assertEquals(Duration.ofSeconds(12), SessionHealthWait.timeout(Map.of()));
            System.clearProperty(property);
            System.setProperty("pkb_runvars", "pkb_browser=CHROME_HEADLESS, pkb_workbench_worker_start_timeout=45");
            assertEquals(Duration.ofSeconds(45), SessionHealthWait.timeout(Map.of()));
            System.clearProperty("pkb_runvars");
            System.setProperty(SessionHealthWait.START_TIMEOUT_RUNVAR, "30");
            assertEquals(Duration.ofSeconds(30), SessionHealthWait.timeout(Map.of()));
            System.clearProperty(SessionHealthWait.START_TIMEOUT_RUNVAR);
            assertEquals(
                    Duration.ofSeconds(90),
                    SessionHealthWait.timeout(Map.of(
                            "pkb_runvars", "pkb_workbench_worker_start_timeout=90"
                    ))
            );
            assertEquals(Duration.ofSeconds(180), SessionHealthWait.timeout(Map.of(property, "nope")));
        } finally {
            restore(property, previous);
            restore("pkb_runvars", previousRunVars);
            restore(SessionHealthWait.START_TIMEOUT_RUNVAR, previousDotted);
        }
    }

    @Test
    void latestProgressStagePrefersTheNewerWorkerLog() throws Exception {
        Path sessionLog = project.resolve("session.log");
        Files.writeString(sessionLog, "progress stage=compile\n");
        Files.setLastModifiedTime(sessionLog, FileTime.fromMillis(1_000_000L));
        Path worker = project.resolve(".pickleball").resolve("workbench").resolve("logs").resolve("worker-1.err.log");
        Files.createDirectories(worker.getParent());
        Files.writeString(worker, "progress stage=compile\nprogress stage=paused\n");
        Files.setLastModifiedTime(worker, FileTime.fromMillis(2_000_000L));
        assertEquals("paused", SessionHealthWait.currentStage(project, sessionLog));
        Files.delete(worker);
        assertEquals(
                SessionHealthWait.GENERIC_STAGE,
                SessionHealthWait.currentStage(project, project.resolve("missing.log"))
        );
    }

    @Test
    void stageDurationsAndProgressLine() {
        SessionHealthWait.StageTimeline timeline = new SessionHealthWait.StageTimeline();
        assertTrue(timeline.observe(SessionHealthWait.GENERIC_STAGE, 0));
        assertFalse(timeline.observe(SessionHealthWait.GENERIC_STAGE, 10));
        assertTrue(timeline.observe("compile", 400));
        assertEquals(
                SessionHealthWait.GENERIC_STAGE + "=400ms; compile=600ms",
                timeline.durations(1000)
        );
        assertTrue(timeline.toJson().contains("\"stage\":\"compile\""));
        assertTrue(timeline.toJson().contains("\"enteredElapsedMs\":400"));
        assertEquals(
                "session-start progress elapsed=12000ms stage=compile",
                SessionHealthWait.progressLine(Duration.ofSeconds(12), "compile")
        );
        assertTrue(SessionHealthWait.progressLine(Duration.ZERO, " ").contains(SessionHealthWait.GENERIC_STAGE));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void waitPrintsProgressThenBecomesHealthyWithoutATimeout() throws Exception {
        Path log = project.resolve("session").resolve("session.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "progress stage=worker-launch\n");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicBoolean ready = new AtomicBoolean();
        long started = System.nanoTime();
        SessionHealthWait.Outcome outcome = SessionHealthWait.waitFor(
                () -> {
                    if (System.nanoTime() - started > Duration.ofMillis(70).toNanos()) ready.set(true);
                    return ready.get();
                },
                () -> true,
                project,
                log,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                Duration.ofSeconds(2),
                Duration.ofMillis(15),
                Duration.ofMillis(5)
        );
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(SessionHealthWait.Kind.HEALTHY, outcome.kind());
        assertTrue(out.contains("session-start progress"), out);
        assertTrue(out.contains("elapsed="), out);
        assertTrue(out.contains("stage=worker-launch"), out);
        assertFalse(stderr.toString(StandardCharsets.UTF_8).contains("did not become healthy"));
        assertTrue(Files.isRegularFile(log.getParent().resolve(SessionHealthWait.PROGRESS_FILE)));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void timeoutNamesStageElapsedTailAndTheSetting() throws Exception {
        Path log = project.resolve("session.log");
        StringBuilder body = new StringBuilder();
        body.append("early-marker\n");
        for (int i = 1; i <= 24; i++) body.append("boot-line-").append(i).append('\n');
        body.append("progress stage=anchor-parse\n");
        Files.writeString(log, body.toString());
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        SessionHealthWait.Outcome outcome = SessionHealthWait.waitFor(
                () -> false,
                () -> true,
                project,
                log,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                Duration.ofMillis(80),
                Duration.ofMinutes(10),
                Duration.ofMillis(10)
        );
        String err = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(SessionHealthWait.Kind.TIMED_OUT, outcome.kind());
        assertEquals("anchor-parse", outcome.stage());
        assertTrue(outcome.elapsedMs() >= 80, Long.toString(outcome.elapsedMs()));
        assertTrue(err.contains("within 80ms"), err);
        assertTrue(err.contains("stage=anchor-parse"), err);
        assertTrue(err.contains("stages:"), err);
        assertTrue(err.contains("anchor-parse="), err);
        assertTrue(err.contains("boot-line-24"), err);
        assertFalse(err.contains("early-marker"), err);
        assertTrue(err.contains("-Dpkb_workbench_worker_start_timeout=N"), err);
        assertTrue(err.contains(SessionHealthWait.START_TIMEOUT_ENV), err);
        assertTrue(err.contains("pkb_runvars member pkb_workbench_worker_start_timeout"), err);
        assertTrue(err.contains(log.toString()), err);
        assertTrue(Files.readString(log.getParent().resolve(SessionHealthWait.PROGRESS_FILE)).contains("anchor-parse"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void timeoutWithoutAMarkerUsesTheGenericStage() {
        Path log = project.resolve("session.log");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        SessionHealthWait.Outcome outcome = SessionHealthWait.waitFor(
                () -> false,
                () -> true,
                project,
                log,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                Duration.ofMillis(40),
                Duration.ofMinutes(10),
                Duration.ofMillis(10)
        );
        String err = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(SessionHealthWait.Kind.TIMED_OUT, outcome.kind());
        assertEquals(SessionHealthWait.GENERIC_STAGE, outcome.stage());
        assertTrue(err.contains("stage=" + SessionHealthWait.GENERIC_STAGE), err);
        assertTrue(err.contains("(no log output)"), err);
        assertTrue(err.contains(SessionHealthWait.GENERIC_STAGE + "="), err);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void aDeadProcessIsNotATimeout() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        long started = System.nanoTime();
        SessionHealthWait.Outcome outcome = SessionHealthWait.waitFor(
                () -> false,
                () -> false,
                project,
                project.resolve("session.log"),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                Duration.ofSeconds(30),
                Duration.ofMillis(10),
                Duration.ofMillis(10)
        );
        assertEquals(SessionHealthWait.Kind.DIED, outcome.kind());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1000);
        assertFalse(stderr.toString(StandardCharsets.UTF_8).contains("did not become healthy"));
    }

    @Test
    void anImmediateHealthCheckStaysQuiet() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        SessionHealthWait.Outcome outcome = SessionHealthWait.waitFor(
                () -> true,
                () -> true,
                project,
                project.resolve("session.log"),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Duration.ofSeconds(30),
                Duration.ofMillis(5),
                Duration.ofMillis(5)
        );
        assertEquals(SessionHealthWait.Kind.HEALTHY, outcome.kind());
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
