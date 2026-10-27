package tools.dscode.testengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.control.protocol.Workspace;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real {@code java -cp} launches. This host has no Chrome binary, so each mode
 * forks the same {@code -D} set its launcher builds, constructs {@link PickleballRunner},
 * and prints the {@link org.openqa.selenium.chrome.ChromeOptions} that
 * {@code newChromiumDriver} would hand to {@code ChromeDriver}.
 * Plain non-Workbench resolution is {@link PlainResolutionGoldenTest}.
 */
class WorkspaceLaunchEndToEndTest {
    private static final String UNIQUE_ARG = "--pkb-phase7-workspace-arg";
    private static final String CHROME_YAML = """
            constructor: CREATE_LOCAL_DRIVER
            browser: chrome
            driver:
              capabilities:
                "goog:loggingPrefs":
                  browser: ALL
                  performance: SEVERE
              options:
                args:
                  - "--pkb-phase7-workspace-arg"
                  - "--disable-gpu"
            """;

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void editedWorkspaceDrivesMavenDirectJavaAndLiveWorker() throws Exception {
        Path project = project();
        Run seeded = fork(project, seedCommand(project, "run-1", false, null));
        assertEquals(0, seeded.exit, seeded.text());
        assertTrue(seeded.text().contains("WARN: literal secret"), seeded.text());
        assertFalse(seeded.text().contains("do-not-store-this"), seeded.text());

        Path workspace = Workspace.directory(project, "run-1");
        Map<String, String> captured = Workspace.readRunVars(workspace);
        assertEquals("firefox", captured.get(PKB_props.PKB_BROWSER));
        assertEquals("from-hook", captured.get("pkb_hook_marker"));
        assertEquals(Workspace.PARALLEL_AUTO, captured.get(PKB_props.PKB_PARALLEL));
        assertEquals("${protected:pkb_rp_api_key}", captured.get(PKB_props.PKB_RP_API_KEY));
        assertFalse(captured.containsKey(PKB_props.PKB_RUN_ID));
        for (String key : Workspace.CONTEXT_KEYS) {
            assertTrue(captured.containsKey(key), key);
        }
        assertEquals("", captured.get(PKB_props.PKB_DATA_PATH));
        String copiedFirefox = Files.readString(Workspace.configsDirectory(workspace).resolve("firefox.yaml"));
        assertTrue(copiedFirefox.contains("literal-secret-in-config"), copiedFirefox);
        String beforeEdit = Workspace.fingerprint(workspace);

        Map<String, String> edited = new LinkedHashMap<>(captured);
        edited.put(PKB_props.PKB_BROWSER, "chrome");
        edited.put("pkb_hook_marker", "edited-by-agent");
        Workspace.writeRunVars(workspace, edited);
        Workspace.atomicWriteString(Workspace.configsDirectory(workspace).resolve("chrome.yaml"), CHROME_YAML);
        String afterEdit = Workspace.fingerprint(workspace);
        assertNotEquals(beforeEdit, afterEdit);
        Path record = workspace.getParent().resolve("record.json");
        Files.writeString(record, "{\n  \"runId\" : \"run-1\"\n}\n");
        Workspace.writeFingerprint(record, afterEdit);
        assertTrue(Files.readString(record).contains("\"" + Workspace.FINGERPRINT_FIELD + "\" : \"" + afterEdit + "\""));

        Map<String, String> selection = selection();
        List<String> mavenLaunch = ConsumerMavenTestRunner.command(project, workspace, selection);
        List<String> directLaunch = Workspace.launchArguments(workspace, selection);
        assertTrue(mavenLaunch.containsAll(directLaunch), mavenLaunch.toString());
        assertTrue(mavenLaunch.contains("-Dpkb_run_purpose=workbench-discover"), mavenLaunch.toString());
        assertLaunchDoesNotForceBrowserOrParallel(mavenLaunch);
        assertLaunchDoesNotForceBrowserOrParallel(directLaunch);

        Run maven = fork(project, javaCommand(dashD(mavenLaunch)));
        Run direct = fork(project, javaCommand(directLaunch));
        Run worker = fork(project, workerCommand(project, workspace, selection));
        assertProbe(maven, "maven");
        assertProbe(direct, "direct-java");
        assertProbe(worker, "live-worker");

        Map<String, String> afterLaunch = Workspace.readRunVars(workspace);
        assertEquals("chrome", afterLaunch.get(PKB_props.PKB_BROWSER));
        assertEquals("edited-by-agent", afterLaunch.get("pkb_hook_marker"));
        assertEquals(Workspace.PARALLEL_AUTO, afterLaunch.get(PKB_props.PKB_PARALLEL));
        assertEquals("${protected:pkb_rp_api_key}", afterLaunch.get(PKB_props.PKB_RP_API_KEY));
        assertFalse(afterLaunch.containsKey(PKB_props.PKB_TAGS));
        assertEquals(CHROME_YAML, Files.readString(Workspace.configsDirectory(workspace).resolve("chrome.yaml")));
        assertEquals(afterEdit, Workspace.fingerprint(workspace));
        assertTrue(Files.readString(record).contains(afterEdit));

        Run leftAlone = fork(project, seedCommand(project, "run-1", false, null));
        assertEquals(0, leftAlone.exit, leftAlone.text());
        assertEquals("chrome", Workspace.readRunVars(workspace).get(PKB_props.PKB_BROWSER));

        Path copyRecord = Workspace.directory(project, "run-2").getParent().resolve("record.json");
        Files.createDirectories(copyRecord.getParent());
        Files.writeString(copyRecord, "{}\n");
        Run copied = fork(project, seedCommand(project, "run-2", false, "run-1"));
        assertEquals(0, copied.exit, copied.text());
        Path copiedWorkspace = Workspace.directory(project, "run-2");
        assertEquals("chrome", Workspace.readRunVars(copiedWorkspace).get(PKB_props.PKB_BROWSER));
        assertEquals("edited-by-agent", Workspace.readRunVars(copiedWorkspace).get("pkb_hook_marker"));
        assertEquals(afterEdit, Workspace.fingerprint(copiedWorkspace));
        assertTrue(Files.readString(copyRecord).contains(afterEdit), Files.readString(copyRecord));
        assertTrue(Files.readString(Workspace.configsDirectory(copiedWorkspace).resolve("chrome.yaml")).contains(UNIQUE_ARG));

        Run reseeded = fork(project, seedCommand(project, "run-1", true, null));
        assertEquals(0, reseeded.exit, reseeded.text());
        assertTrue(reseeded.text().contains("WARN: literal secret"), reseeded.text());
        Map<String, String> restored = Workspace.readRunVars(workspace);
        assertEquals("firefox", restored.get(PKB_props.PKB_BROWSER));
        assertEquals("from-hook", restored.get("pkb_hook_marker"));
        assertEquals(Workspace.PARALLEL_AUTO, restored.get(PKB_props.PKB_PARALLEL));
        assertEquals("${protected:pkb_rp_api_key}", restored.get(PKB_props.PKB_RP_API_KEY));
        assertFalse(Files.isRegularFile(Workspace.configsDirectory(workspace).resolve("chrome.yaml")));
        String reseedFingerprint = Workspace.fingerprint(workspace);
        assertNotEquals(afterEdit, reseedFingerprint);
        assertTrue(Files.readString(record).contains(reseedFingerprint), Files.readString(record));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void missingContextKeyFailsTheLaunchJvm() throws Exception {
        Path project = project();
        Path workspace = Workspace.directory(project, "run-missing");
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : Workspace.CONTEXT_KEYS) values.put(key, "");
        values.remove(PKB_props.PKB_GLUE);
        values.put(PKB_props.PKB_BROWSER, "chrome");
        Workspace.writeRunVars(workspace, values);
        Run launch = fork(project, javaCommand(Workspace.launchArguments(workspace, Map.of())));
        assertNotEquals(0, launch.exit);
        assertTrue(launch.text().contains("pkb_glue"), launch.text());
    }

    private Path project() throws Exception {
        Path project = temp.resolve("consumer");
        Files.createDirectories(project.resolve("configs"));
        Files.createDirectories(project.resolve("src/test/resources"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\nexit 0\n");
        String properties = "pkb_rp_api_key=phase7-secret\n";
        Files.writeString(project.resolve("pickleball.properties"), properties);
        Files.writeString(project.resolve("src/test/resources/pickleball.properties"), properties);
        Files.writeString(project.resolve("configs/firefox.yaml"), """
                constructor: CREATE_LOCAL_DRIVER
                browser: firefox
                password: literal-secret-in-config
                driver:
                  options:
                    args:
                      - "--project-firefox-arg"
                """);
        return project;
    }

    private static Map<String, String> selection() {
        Map<String, String> selection = new LinkedHashMap<>();
        selection.put(PKB_props.PKB_TAGS, "@phase7");
        selection.put(PKB_props.PKB_NAME, "Phase 7");
        selection.put(PKB_props.PKB_RUN_ID, "run-1");
        selection.put(PKB_props.PKB_AGENT_ID, "agent-phase7");
        return selection;
    }

    private static void assertLaunchDoesNotForceBrowserOrParallel(List<String> command) {
        for (String argument : command) {
            assertFalse(argument.contains("pkb_browser"), argument);
            assertFalse(argument.contains("pkb_hook_marker"), argument);
            assertFalse(argument.contains("pkb_parallel=1"), argument);
            assertFalse(argument.contains("CHROME_HEADLESS"), argument);
            assertFalse(argument.contains("pkb_overriderunvars"), argument);
        }
    }

    private static void assertProbe(Run run, String mode) {
        String text = run.text();
        assertEquals(0, run.exit, mode + "\n" + text);
        assertTrue(text.contains("PKB_PROBE browser=chrome"), mode + "\n" + text);
        assertTrue(text.contains("PKB_PROBE resolvedBrowser=chrome"), mode + "\n" + text);
        assertTrue(text.contains("PKB_PROBE sysBrowser=chrome"), mode + "\n" + text);
        assertTrue(text.contains(UNIQUE_ARG), mode + "\n" + text);
        assertTrue(text.contains("--disable-gpu"), mode + "\n" + text);
        assertFalse(text.contains("--headless"), mode + "\n" + text);
        assertFalse(text.contains("--project-firefox-arg"), mode + "\n" + text);
        assertFalse(text.contains("CHROME_HEADLESS"), mode + "\n" + text);
        assertTrue(text.contains("PKB_PROBE logging="), mode + "\n" + text);
        assertTrue(text.contains("ALL"), mode + "\n" + text);
        assertTrue(text.contains("SEVERE"), mode + "\n" + text);
        assertTrue(text.contains("PKB_PROBE hook=edited-by-agent"), mode + "\n" + text);
        assertFalse(text.contains("hook-ran-at-launch"), mode + "\n" + text);
        assertFalse(text.contains("from-hook"), mode + "\n" + text);
        assertFalse(text.contains("from-dash-d"), mode + "\n" + text);
        assertTrue(text.contains("PKB_PROBE secret=phase7-secret"), mode + "\n" + text);
        assertFalse(text.contains("do-not-store-this"), mode + "\n" + text);
        assertTrue(text.contains("PKB_PROBE tags=@phase7"), mode + "\n" + text);
        assertTrue(text.contains("PKB_PROBE keys="), mode + "\n" + text);
        String keys = line(text, "PKB_PROBE keys=");
        assertTrue(keys.contains("chrome"), mode + "\n" + keys);
        assertTrue(keys.contains("firefox"), mode + "\n" + keys);
        assertFalse(keys.contains("CHROME_HEADLESS"), mode + "\n" + keys);
        int parallel = Integer.parseInt(line(text, "PKB_PROBE parallel=").substring("PKB_PROBE parallel=".length()).trim());
        assertTrue(parallel >= 2, mode + " parallel=" + parallel);
    }

    private static String line(String text, String prefix) {
        for (String line : text.split("\\R")) {
            if (line.startsWith(prefix)) return line;
        }
        return "";
    }

    private static List<String> seedCommand(Path project, String runId, boolean reseed, String from) {
        List<String> command = new ArrayList<>();
        command.add(java());
        command.add("-cp");
        command.add(classpath());
        command.add(Phase7SeedMain.class.getName());
        command.add("--project=" + project.toAbsolutePath().normalize());
        command.add("--run=" + runId);
        if (reseed) command.add("--reseed");
        if (from != null) command.add("--from=" + from);
        return command;
    }

    /** Same {@code -D} order as {@code WorkbenchWorkerManager.workerCommand}. */
    private static List<String> workerCommand(Path project, Path workspace, Map<String, String> selection) throws Exception {
        Path anchor = project.resolve("anchor.feature");
        Files.writeString(anchor, "Feature: anchor\n");
        List<String> command = new ArrayList<>();
        command.add(java());
        command.add("-D" + ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY + "="
                + project.resolve("live-output").toAbsolutePath().normalize());
        Workspace.launchProperties(workspace, selection).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> command.add("-D" + entry.getKey() + "=" + entry.getValue()));
        command.add("-D" + PKB_props.PKB_BROWSER + "=CHROME_HEADLESS");
        command.add("-Dpkb_hook_marker=from-dash-d");
        command.add("-cp");
        command.add(classpath());
        command.add(WorkspaceDriverProbe.class.getName());
        command.add("--tags");
        command.add("@pickleball-workbench-anchor");
        command.add(anchor.toAbsolutePath().normalize().toUri().toString());
        assertFalse(command.stream().anyMatch(argument -> argument.contains("pkb_parallel=1")), command.toString());
        return command;
    }

    private static List<String> javaCommand(List<String> dashD) {
        List<String> command = new ArrayList<>();
        command.add(java());
        command.addAll(dashD);
        command.add("-D" + PKB_props.PKB_BROWSER + "=CHROME_HEADLESS");
        command.add("-Dpkb_hook_marker=from-dash-d");
        command.add("-cp");
        command.add(classpath());
        command.add(WorkspaceDriverProbe.class.getName());
        return command;
    }

    private static List<String> dashD(List<String> command) {
        List<String> properties = new ArrayList<>();
        for (String argument : command) {
            if (argument.startsWith("-D")) properties.add(argument);
        }
        return properties;
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String classpath() {
        return System.getProperty("java.class.path");
    }

    private static Run fork(Path directory, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread stdout = new Thread(() -> transfer(process.getInputStream(), out), "phase7-stdout");
        Thread stderr = new Thread(() -> transfer(process.getErrorStream(), err), "phase7-stderr");
        stdout.start();
        stderr.start();
        boolean finished = process.waitFor(90, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdout.join(2000);
            stderr.join(2000);
            throw new AssertionError("timed out: " + command);
        }
        stdout.join(5000);
        stderr.join(5000);
        return new Run(
                process.exitValue(),
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8)
        );
    }

    private static void transfer(java.io.InputStream in, ByteArrayOutputStream out) {
        try {
            in.transferTo(out);
        } catch (java.io.IOException ignored) {
            // The process is being destroyed.
        }
    }

    private record Run(int exit, String out, String err) {
        String text() {
            return out + "\n" + err;
        }
    }
}
