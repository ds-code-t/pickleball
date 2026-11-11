package tools.dscode.dalton;

import io.cucumber.core.runtime.FeaturePathLoadCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonRunTest {
    @TempDir
    Path project;

    @Test
    void fingerprintAndCompilePlanFollowTheGlue() throws Exception {
        Path java = project.resolve("src/test/java/Sample.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "class Sample {}\n");
        Files.writeString(project.resolve("pom.xml"), "<project></project>\n");
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\n");
        Files.writeString(project.resolve("mvnw.cmd"), "@echo off\n");

        String first = DaltonRun.glueFingerprint(project);
        DaltonRun.Plan missing = DaltonRun.plan(project);
        assertTrue(missing.compile(), missing.line());
        assertTrue(missing.restart());
        assertTrue(missing.compileCommand().get(0).endsWith("mvnw"), missing.compileCommand().toString());
        assertEquals("test-compile", missing.compileCommand().get(2));

        String os = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertTrue(DaltonRun.compileCommand(project).get(0).endsWith("mvnw.cmd"));
        } finally {
            System.setProperty("os.name", os);
        }

        Files.createDirectories(project.resolve(".pickleball/dalton"));
        Files.writeString(project.resolve(".pickleball/dalton/glue.fingerprint"), first);
        assertFalse(DaltonRun.plan(project).compile());

        Files.writeString(java, "class Sample { int x; }\n");
        assertNotEquals(first, DaltonRun.glueFingerprint(project));
        assertTrue(DaltonRun.plan(project).compile());

        Files.delete(project.resolve("pom.xml"));
        Files.delete(project.resolve("mvnw"));
        Files.writeString(project.resolve("build.gradle"), "plugins {}\n");
        Files.writeString(project.resolve("gradlew"), "#!/bin/sh\n");
        List<String> gradle = DaltonRun.compileCommand(project);
        assertTrue(gradle.get(0).endsWith("gradlew"), gradle.toString());
        assertEquals("testClasses", gradle.get(1));

        Files.delete(project.resolve("build.gradle"));
        Files.delete(project.resolve("gradlew"));
        assertTrue(DaltonRun.compileCommand(project).isEmpty());
    }

    @Test
    void dryRunPrintsThePlanAndCreatesNothing() {
        Capture capture = cli("dalton", "run", "--dry-run", "--tags=@none", "--name=One", "--glue=steps", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("dry-run fingerprint="), capture.out);
        assertTrue(capture.out.contains("compile=true"), capture.out);
        assertTrue(capture.out.contains("restart=true"), capture.out);
        assertFalse(Files.exists(project.resolve(".pickleball")));
        assertTrue(DaltonCli.help().contains("dalton run"));
        List<String> selected = DaltonRun.selection("@none", "One", "steps", List.of("-Dpkb_example=3", "skip"));
        assertEquals(List.of("-Dpkb_tags=@none", "-Dpkb_name=One", "-Dpkb_glue=steps", "-Dpkb_example=3"), selected);
        List<String> forced = DaltonRun.runProperties(List.of("-Dpkb_dalton=false", "-Dpkb_tags=@none"));
        assertEquals("-Dpkb_dalton=true", forced.get(forced.size() - 2));
        assertEquals(ConsumerMavenTestRunner.PLATFORM_CUCUMBER_ENGINE, forced.get(forced.size() - 1));
        assertFalse(forced.contains("-Dpkb_dalton=false"));
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void warmWorkerIsReusedAndLogsTheRun() throws Exception {
        Files.createDirectories(project.resolve("build/classes/java/test"));
        Files.createDirectories(project.resolve(".pickleball/dalton"));
        String fingerprint = DaltonRun.glueFingerprint(project);
        Files.writeString(project.resolve(".pickleball/dalton/glue.fingerprint"), fingerprint);
        String classpath = System.getProperty("pkb_dalton_run_classpath");
        String log = System.getProperty("pkb_dalton_log");
        try {
            System.setProperty("pkb_dalton_run_classpath", System.getProperty("java.class.path"));
            System.setProperty("pkb_dalton_log", "run");
            DaltonRun.Outcome first = DaltonRun.execute(project, List.of("-Dpkb_tags=@dalton-run-matches-nothing"), false);
            assertEquals(1, first.exit(), workerLog(first));
            assertFalse(first.reused(), workerLog(first));
            long pid = warmPid();
            DaltonRun.Outcome second = DaltonRun.execute(project, List.of("-Dpkb_tags=@dalton-run-matches-nothing"), false);
            assertEquals(1, second.exit(), workerLog(second));
            assertTrue(second.reused(), workerLog(second));
            assertEquals(first.exit(), second.exit());
            assertEquals(pid, warmPid());
            String lines = Files.readString(project.resolve(".pickleball/dalton/dalton.log"), StandardCharsets.UTF_8);
            assertTrue(lines.contains(" run worker started exit=1"), lines);
            assertTrue(lines.contains(" run worker reused exit=1"), lines);
        } finally {
            DaltonRun.stop(project);
            restore("pkb_dalton_run_classpath", classpath);
            restore("pkb_dalton_log", log);
        }
        assertFalse(Files.isRegularFile(project.resolve(".pickleball/dalton/warm.txt")));
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void warmRunExecutesDatabaseBytesAndMatchesTheNormalEngine() throws Exception {
        Path features = project.resolve("features");
        Files.createDirectories(features);
        Files.createDirectories(project.resolve("build/classes/java/test"));
        String feature = """
                Feature: Dalton warm sample
                  @dalton-warm
                  Scenario: Pay Ada
                    * INFO: amount is 10
                    * dalton probe records amount is 10
                    * ASSERT: "10" equals "10"

                  @dalton-warm
                  Scenario: Pay Lin
                    * dalton probe records amount is 4
                    * ASSERT: "10" equals "11"
                """;
        Path file = features.resolve("sample.feature");
        Files.writeString(file, feature);
        Files.createDirectories(project.resolve(".pickleball/dalton"));
        Files.writeString(project.resolve(".pickleball/dalton/glue.fingerprint"), DaltonRun.glueFingerprint(project));
        Capture hydrated = cli("dalton", "hydrate", "--project=" + project);
        assertEquals(0, hydrated.code, hydrated.err);

        String classpath = System.getProperty("pkb_dalton_run_classpath");
        String log = System.getProperty("pkb_dalton_log");
        try {
            System.setProperty("pkb_dalton_run_classpath", System.getProperty("java.class.path"));
            System.setProperty("pkb_dalton_log", "run");
            List<String> selection = warmSelection(features);
            DaltonRun.Outcome warm = DaltonRun.execute(project, selection, false);
            List<String> warmLines = scenarioLines(warm.out());
            assertEquals(1, warm.exit(), workerLog(warm));
            assertFalse(warm.reused(), workerLog(warm));
            assertEquals(signature(List.of(
                    "scenario PASSED Pay Ada",
                    "scenario FAILED Pay Lin",
                    "reached amount is 10",
                    "reached amount is 4"
            ), warmLines), warmLines, warm.out());
            assertEquals(2, countPrefix(warmLines, "scenario "));
            assertTrue(countPrefix(warmLines, "step ") >= 1, warmLines.toString());
            assertTrue(warmLines.stream().anyMatch(row -> row.startsWith("step FAILED ") && row.contains("ASSERT:")), warmLines.toString());
            long pid = warmPid();

            assertEquals(warmLines, normalEngine(features), warmLines.toString());

            String edited = feature
                    .replace("Scenario: Pay Ada", "Scenario: Pay from database")
                    .replace("INFO: amount is 10", "INFO: amount is 99")
                    .replace("dalton probe records amount is 10", "dalton probe records amount is 99")
                    .replace("ASSERT: \"10\" equals \"11\"", "ASSERT: \"10\" equals \"10\"");
            Path payload = project.resolve("edited.feature");
            Files.writeString(payload, edited);
            Capture put = cli("dalton", "feature", "put", "features/sample.feature",
                    "--file=" + payload.toAbsolutePath(), "--project=" + project);
            assertEquals(0, put.code, put.err);
            assertEquals(feature, Files.readString(file));

            DaltonRun.Outcome again = DaltonRun.execute(project, selection, false);
            List<String> editedLines = scenarioLines(again.out());
            assertEquals(0, again.exit(), workerLog(again));
            assertTrue(again.reused(), workerLog(again));
            assertEquals(pid, warmPid());
            assertEquals(signature(List.of(
                    "scenario PASSED Pay from database",
                    "scenario PASSED Pay Lin",
                    "reached amount is 99",
                    "reached amount is 4"
            ), editedLines), editedLines, again.out());
            assertTrue(editedLines.stream().noneMatch(row -> row.contains("Pay Ada") || row.contains("FAILED")), editedLines.toString());
            assertEquals(feature, Files.readString(file));
            assertEquals(editedLines, normalEngine(features), editedLines.toString());
        } finally {
            DaltonRun.stop(project);
            restore("pkb_dalton_run_classpath", classpath);
            restore("pkb_dalton_log", log);
            DaltonRuntime.reset();
            FeaturePathLoadCache.clear();
            MappingProcessor.resetCommonMaps();
            DaltonScenarioHarness.release();
        }
    }

    private List<String> normalEngine(Path features) {
        Properties snapshot = new Properties();
        snapshot.putAll(System.getProperties());
        try {
            System.setProperty("pkb_dalton", "true");
            System.setProperty("pkb_dalton_project", project.toString());
            DaltonRuntime.reset();
            DaltonScenarioHarness.Outcome outcome = DaltonScenarioHarness.execute(
                    features,
                    "tools.dscode.coredefinitions,tools.dscode.dalton",
                    "@dalton-warm"
            );
            List<String> combined = new ArrayList<>(outcome.events());
            for (String reached : outcome.reached()) combined.add("reached " + reached);
            return scenarioLines(combined);
        } finally {
            System.setProperties(snapshot);
            DaltonRuntime.reset();
            FeaturePathLoadCache.clear();
            MappingProcessor.resetCommonMaps();
            DaltonScenarioHarness.release();
        }
    }

    private List<String> warmSelection(Path features) {
        return List.of(
                "-Dpkb_tags=@dalton-warm",
                "-Dpkb_glue=tools.dscode.coredefinitions,tools.dscode.dalton",
                "-Dpkb_features=wd-file:" + features.toAbsolutePath().normalize(),
                "-Dpkb_loglevel=error",
                "-Dpkb_parallel=1",
                "-Dcucumber.execution.parallel.enabled=false"
        );
    }

    private static List<String> scenarioLines(String text) {
        return scenarioLines(text == null ? List.of() : List.of(text.split("\\R")));
    }

    private static List<String> scenarioLines(List<String> lines) {
        List<String> kept = new ArrayList<>();
        if (lines == null) return kept;
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.startsWith("scenario ") || trimmed.startsWith("step ") || trimmed.startsWith("reached ")) {
                kept.add(trimmed);
            }
        }
        kept.sort(null);
        return kept;
    }

    /** Expected names and reached steps must be present. Extra engine step lines stay. */
    private static List<String> signature(List<String> required, List<String> actual) {
        List<String> missing = new ArrayList<>();
        for (String line : required) {
            if (!actual.contains(line)) missing.add(line);
        }
        assertTrue(missing.isEmpty(), missing + " not in " + actual);
        return actual;
    }

    private static long countPrefix(List<String> lines, String prefix) {
        return lines.stream().filter(line -> line.startsWith(prefix)).count();
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void timingScriptMeasuresAFixtureAndReusesTheWorker() throws Exception {
        Path features = project.resolve("features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("time.feature"), """
                Feature: Dalton time
                  @dalton-time
                  Scenario: Time Ada
                    * INFO: amount is 1
                    * ASSERT: "1" equals "1"
                """);
        Files.writeString(project.resolve("build.gradle"), "// timing fixture\n");
        Path gradlew = project.resolve("gradlew");
        Files.writeString(gradlew, coldShell());
        Files.writeString(project.resolve("gradlew.bat"), coldBat());
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Files.createDirectories(project.resolve(".pickleball/dalton"));
        Files.writeString(project.resolve(".pickleball/dalton/glue.fingerprint"), DaltonRun.glueFingerprint(project));
        Path classpathFile = Path.of("build", "dalton-run-classpath.txt");
        Files.createDirectories(classpathFile.getParent());
        String previous = Files.isRegularFile(classpathFile) ? Files.readString(classpathFile) : null;
        String classpath = System.getProperty("java.class.path");
        if (classpath.indexOf(' ') >= 0) classpath = "\"" + classpath + "\"";
        Files.writeString(classpathFile, classpath + "\n");
        String featureDir = features.toAbsolutePath().normalize().toString().replace('\\', '/');
        try {
            Process process = new ProcessBuilder(
                    "python3",
                    "scripts/time_dalton_run.py",
                    "--project",
                    project.toString(),
                    "--tags=@dalton-time",
                    "--glue=tools.dscode.coredefinitions",
                    "--define=pkb_features=wd-file:" + featureDir,
                    "--define=pkb_loglevel=error",
                    "--define=pkb_parallel=1",
                    "--define=cucumber.execution.parallel.enabled=false"
            ).directory(Path.of("").toAbsolutePath().toFile()).redirectErrorStream(true).start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), text);
            assertTrue(text.contains("coldMs="), text);
            assertTrue(text.contains("coldExit=0"), text);
            assertTrue(text.contains("warm1Ms="), text);
            assertTrue(text.contains("warm1Exit=0"), text);
            assertTrue(text.contains("warm1Reused=false"), text);
            assertTrue(text.contains("warm2Ms="), text);
            assertTrue(text.contains("warm2Exit=0"), text);
            assertTrue(text.contains("warm2Reused=true"), text);
            assertTrue(text.contains("results match"), text);
            assertTrue(ProcessHandle.of(warmPid()).isPresent(), text);
        } finally {
            DaltonRun.stop(project);
            if (previous == null) Files.deleteIfExists(classpathFile);
            else Files.writeString(classpathFile, previous);
        }
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void glueChangeCompilesOnceAndRestartsTheWorker() throws Exception {
        Path features = project.resolve("features");
        Files.createDirectories(features);
        Files.createDirectories(project.resolve("build/classes/java/test"));
        Files.createDirectories(project.resolve("src/test/java"));
        Files.writeString(features.resolve("glue.feature"), """
                Feature: Dalton glue
                  @dalton-glue
                  Scenario: Glue Ada
                    * INFO: amount is 2
                    * ASSERT: "2" equals "2"
                """);
        Files.writeString(project.resolve("pom.xml"), "<project></project>\n");
        Path mvnw = project.resolve("mvnw");
        Files.writeString(mvnw, """
                #!/bin/sh
                dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
                printf 'compile\\n' >> "$dir/.pickleball/dalton/compile.log"
                exit 0
                """);
        Files.writeString(project.resolve("mvnw.cmd"), """
                @echo off
                echo compile>> "%~dp0.pickleball\\dalton\\compile.log"
                exit /b 0
                """);
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Files.setPosixFilePermissions(mvnw, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Path java = project.resolve("src/test/java/Sample.java");
        Files.writeString(java, "class Sample { int x; }\n");
        Files.createDirectories(project.resolve(".pickleball/dalton"));
        Files.writeString(project.resolve(".pickleball/dalton/glue.fingerprint"), DaltonRun.glueFingerprint(project));
        String classpath = System.getProperty("pkb_dalton_run_classpath");
        String log = System.getProperty("pkb_dalton_log");
        try {
            System.setProperty("pkb_dalton_run_classpath", System.getProperty("java.class.path"));
            System.setProperty("pkb_dalton_log", "run");
            List<String> selection = List.of(
                    "-Dpkb_tags=@dalton-glue",
                    "-Dpkb_glue=tools.dscode.coredefinitions",
                    "-Dpkb_features=wd-file:" + features.toAbsolutePath().normalize().toString().replace('\\', '/'),
                    "-Dpkb_loglevel=error",
                    "-Dpkb_parallel=1",
                    "-Dcucumber.execution.parallel.enabled=false"
            );
            DaltonRun.Outcome first = DaltonRun.execute(project, selection, false);
            assertEquals(0, first.exit(), workerLog(first));
            assertFalse(first.reused(), workerLog(first));
            assertTrue(scenarioLines(first.out()).contains("scenario PASSED Glue Ada"), first.out());
            long oldPid = warmPid();
            assertFalse(Files.isRegularFile(project.resolve(".pickleball/dalton/compile.log")));

            Files.writeString(java, "class Sample { int x; int y; }\n");
            DaltonRun.Outcome restarted = DaltonRun.execute(project, selection, false);
            assertEquals(0, restarted.exit(), workerLog(restarted));
            assertFalse(restarted.reused(), workerLog(restarted));
            assertEquals(List.of("compile"), Files.readAllLines(project.resolve(".pickleball/dalton/compile.log")));
            assertTrue(scenarioLines(restarted.out()).contains("scenario PASSED Glue Ada"), restarted.out());
            assertTrue(gone(oldPid), "old worker " + oldPid + " still alive");
            long newPid = warmPid();
            assertNotEquals(oldPid, newPid);

            DaltonRun.Outcome reused = DaltonRun.execute(project, selection, false);
            assertEquals(0, reused.exit(), workerLog(reused));
            assertTrue(reused.reused(), workerLog(reused));
            assertEquals(newPid, warmPid());
            assertEquals(List.of("compile"), Files.readAllLines(project.resolve(".pickleball/dalton/compile.log")));
            assertTrue(scenarioLines(reused.out()).contains("scenario PASSED Glue Ada"), reused.out());
        } finally {
            DaltonRun.stop(project);
            restore("pkb_dalton_run_classpath", classpath);
            restore("pkb_dalton_log", log);
        }
    }

    private static boolean gone(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) return true;
            Thread.sleep(50);
        }
        return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
    }

    private static String coldShell() {
        return """
                #!/bin/bash
                cd "$(dirname "$0")" || exit 2
                dalton="$PWD/.pickleball/dalton"
                trace="$dalton/cold-outcomes.txt"
                rm -f "$trace"
                if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
                  JAVA="$JAVA_HOME/bin/java"
                else
                  JAVA=java
                fi
                args=()
                for token in "$@"; do
                  if [[ "$token" == -D* ]]; then
                    args+=("$token")
                  fi
                done
                args+=("-Dpkb_dalton=true")
                args+=("-Dpkb_dalton_run_trace=true")
                args+=("-Dpkb_dalton_run_trace_file=$trace")
                "$JAVA" -cp "@$dalton/classpath.cache" tools.dscode.launcher.WorkbenchDirectRun "${args[@]}"
                code=$?
                if [[ -f "$trace" ]]; then
                  cat "$trace"
                fi
                exit "$code"
                """;
    }

    private static String coldBat() {
        return """
                @echo off
                setlocal EnableExtensions
                cd /d "%~dp0"
                set "DALTON=%CD%\\.pickleball\\dalton"
                set "TRACE=%DALTON%\\cold-outcomes.txt"
                if exist "%TRACE%" del /q "%TRACE%"
                set "EXTRA="
                :collect
                if "%~1"=="" goto launch
                echo %~1| findstr /b /c:"-D" >nul && set "EXTRA=%EXTRA% %~1"
                shift
                goto collect
                :launch
                if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\\bin\\java.exe") else (set "JAVA=java")
                "%JAVA%" -cp "@%DALTON%\\classpath.cache" tools.dscode.launcher.WorkbenchDirectRun %EXTRA% -Dpkb_dalton=true -Dpkb_dalton_run_trace=true "-Dpkb_dalton_run_trace_file=%TRACE%"
                set CODE=%ERRORLEVEL%
                if exist "%TRACE%" type "%TRACE%"
                exit /b %CODE%
                """;
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void timingScriptSelfCheckDoesNotLaunchASuite() throws Exception {
        Process process = new ProcessBuilder("python3", "scripts/time_dalton_run.py", "--self-check")
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true)
                .start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), text);
        assertTrue(text.contains("pickleball-test-engine"), text);
        assertTrue(text.contains("does not launch a suite"), text);
    }

    private long warmPid() throws Exception {
        List<String> lines = Files.readAllLines(project.resolve(".pickleball/dalton/warm.txt"));
        return Long.parseLong(lines.get(0).trim());
    }

    private String workerLog(DaltonRun.Outcome outcome) throws Exception {
        Path err = project.resolve(".pickleball/dalton/warm.err.log");
        String logged = Files.isRegularFile(err) ? Files.readString(err) : "";
        return outcome.out() + outcome.err() + "\n" + logged;
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private Capture cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = DaltonCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
