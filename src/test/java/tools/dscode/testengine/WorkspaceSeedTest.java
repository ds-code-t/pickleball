package tools.dscode.testengine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.launcher.WorkbenchAgentCommands;
import tools.dscode.testengine.fixtures.BlankBrowserRunner;
import tools.dscode.testengine.fixtures.HookSourceRunner;
import tools.dscode.testengine.fixtures.WorkspaceSeedHooked;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSeedTest {
    @TempDir
    Path temp;

    private Properties saved;

    @BeforeEach
    void save() {
        saved = new Properties();
        saved.putAll(System.getProperties());
    }

    @AfterEach
    void restore() throws Exception {
        System.clearProperty(WorkspaceSeed.SEED_PROPERTY);
        System.clearProperty(Workspace.PROPERTY);
        Properties live = System.getProperties();
        live.clear();
        live.putAll(saved);
        Field instance = PickleballRunner.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    void consumerStyleHooksAreCapturedAndHeadlessIsOnlyTheFallback() throws Exception {
        clearPkbSystemProperties();
        System.setProperty(WorkspaceSeed.SEED_PROPERTY, "true");
        BlankBrowserRunner blank = new BlankBrowserRunner();
        clearRunner();
        clearPkbSystemProperties();
        System.setProperty(WorkspaceSeed.SEED_PROPERTY, "true");
        WorkspaceSeedHooked hooked = new WorkspaceSeedHooked();
        clearRunner();

        Path hookedWorkspace = temp.resolve("hooked");
        WorkspaceSeed.writeCaptured(hookedWorkspace, hooked, false);
        Map<String, String> captured = Workspace.readRunVars(hookedWorkspace);
        assertEquals("firefox", captured.get(PKB_props.PKB_BROWSER));
        assertEquals("auto", captured.get(PKB_props.PKB_PARALLEL));
        assertEquals("from-hook", captured.get("pkb_hook_marker"));
        assertEquals("qa", captured.get(PKB_props.PKB_ENVIRONMENT));
        assertEquals("hook", Workspace.readValueSources(hookedWorkspace).get("pkb_description"));
        assertEquals("hook", Workspace.readValueSources(hookedWorkspace).get(PKB_props.PKB_BROWSER));
        assertEquals("hook", Workspace.readValueSources(hookedWorkspace).get("pkb_hook_marker"));
        String description = captured.get("pkb_description");
        assertTrue(description.contains(System.getProperty("user.name")), description);
        assertTrue(description.contains(java.util.Locale.getDefault().toString()), description);
        assertTrue(description.contains(System.getProperty("java.vendor")), description);
        for (String key : Workspace.CONTEXT_KEYS) assertTrue(captured.containsKey(key), key);
        assertFalse(captured.containsKey(PKB_props.PKB_RUN_ID));

        Path blankWorkspace = temp.resolve("blank");
        WorkspaceSeed.writeCaptured(blankWorkspace, blank, false);
        assertEquals("CHROME_HEADLESS", Workspace.readRunVars(blankWorkspace).get(PKB_props.PKB_BROWSER));
        assertEquals("default", Workspace.readValueSources(blankWorkspace).get(PKB_props.PKB_BROWSER));

        assertThrows(IllegalStateException.class, () -> WorkspaceSeed.writeCaptured(hookedWorkspace, hooked, false));
        WorkspaceSeed.writeCaptured(hookedWorkspace, blank, true);
        assertEquals("CHROME_HEADLESS", Workspace.readRunVars(hookedWorkspace).get(PKB_props.PKB_BROWSER));
    }

    @Test
    void hookDescriptionFileDefaultAndCliOverrideEachKeepTheirSource() throws Exception {
        clearPkbSystemProperties();
        System.setProperty(WorkspaceSeed.SEED_PROPERTY, "true");
        System.setProperty("pkb_loglevel", "error");
        Path resources = temp.resolve("src/test/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("pickleball.properties"), "pkb_tags=@from-file\n");
        Field preview = PickleballRunner.class.getDeclaredField("PREVIEW");
        preview.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<Boolean> flag = (ThreadLocal<Boolean>) preview.get(null);
        Field projectField = PickleballRunner.class.getDeclaredField("PREVIEW_PROJECT");
        projectField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<Path> projectLocal = (ThreadLocal<Path>) projectField.get(null);
        flag.set(Boolean.TRUE);
        projectLocal.set(temp.toAbsolutePath().normalize());
        try {
            WorkspaceSeedHooked hooked = new WorkspaceSeedHooked();
            Path workspace = Workspace.directory(temp, "described");
            WorkspaceSeed.writeCaptured(workspace, hooked, false);
            Map<String, String> sources = Workspace.readValueSources(workspace);
            assertEquals("hook", sources.get("pkb_description"), sources.toString());
            assertEquals("hook", sources.get(PKB_props.PKB_BROWSER), sources.toString());
            assertEquals("properties", sources.get("pkb_tags"), sources.toString());
            assertEquals("cli/override", sources.get("pkb_loglevel"), sources.toString());
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int exit = WorkbenchAgentCommands.run(
                    new String[]{"workspace-show", temp.toString(), "--run-id=described"},
                    new PrintStream(stdout, true, StandardCharsets.UTF_8),
                    new PrintStream(stderr, true, StandardCharsets.UTF_8)
            );
            String shown = stdout.toString(StandardCharsets.UTF_8);
            assertEquals(0, exit, stderr.toString(StandardCharsets.UTF_8) + shown);
            assertTrue(shown.contains("pkb_description="), shown);
            assertTrue(shown.contains("source=hook computed"), shown);
            assertTrue(shown.contains(System.getProperty("user.name")), shown);
            assertTrue(shown.contains(System.getProperty("java.vendor")), shown);
            assertTrue(shown.contains("pkb_tags=@from-file source=properties"), shown);
            assertTrue(shown.contains("pkb_loglevel=error source=cli/override"), shown);
            assertFalse(shown.contains("pkb_tags=@from-file source=properties computed"), shown);
            assertFalse(shown.contains("pkb_loglevel=error source=cli/override computed"), shown);

            BlankBrowserRunner blank = new BlankBrowserRunner();
            WorkspaceSeed.writeCaptured(Workspace.directory(temp, "blank-show"), blank, false);
            ByteArrayOutputStream blankOut = new ByteArrayOutputStream();
            int blankExit = WorkbenchAgentCommands.run(
                    new String[]{"workspace-show", temp.toString(), "--run-id=blank-show"},
                    new PrintStream(blankOut, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
            );
            String blankShown = blankOut.toString(StandardCharsets.UTF_8);
            assertEquals(0, blankExit, blankShown);
            assertTrue(blankShown.contains("pkb_browser=CHROME_HEADLESS source=default"), blankShown);
            assertFalse(blankShown.contains("pkb_browser=CHROME_HEADLESS source=default computed"), blankShown);
        } finally {
            flag.remove();
            projectLocal.remove();
            System.clearProperty("pkb_loglevel");
        }
    }

    @Test
    void hookPutRecordsPlainPkbAndSecretKeysAsHook() throws Exception {
        clearPkbSystemProperties();
        System.setProperty(WorkspaceSeed.SEED_PROPERTY, "true");
        HookSourceRunner hooked = new HookSourceRunner();
        clearRunner();
        Path workspace = Workspace.directory(temp, "hook-put");
        WorkspaceSeed.writeCaptured(workspace, hooked, false);
        Map<String, String> sources = Workspace.readValueSources(workspace);
        assertEquals("hook", sources.get("description"), sources.toString());
        assertEquals("hook", sources.get("pkb_hook_marker"), sources.toString());
        assertEquals("hook", sources.get("pkb_password"), sources.toString());
        assertEquals("hook", sources.get(PKB_props.PKB_GLUE), sources.toString());
        assertEquals("hook plain description", Workspace.readRunVars(workspace).get("description"));
        assertEquals("runner-hook-secret-value", Workspace.readRunVars(workspace).get("pkb_password"));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"workspace-show", temp.toString(), "--run-id=hook-put"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        String shown = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit, stderr.toString(StandardCharsets.UTF_8) + shown);
        assertTrue(shown.contains("description=hook plain description source=hook computed"), shown);
        assertTrue(shown.contains("pkb_hook_marker=from-hook source=hook computed"), shown);
        assertTrue(shown.contains("pkb_password=<redacted> source=hook computed"), shown);
        assertTrue(shown.contains("pkb_glue=com.example.steps source=hook computed"), shown);
        assertFalse(shown.contains("runner-hook-secret-value"), shown);
        assertFalse(stderr.toString(StandardCharsets.UTF_8).contains("runner-hook-secret-value"));

        ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
        int jsonExit = WorkbenchAgentCommands.run(
                new String[]{"workspace-show", temp.toString(), "--run-id=hook-put", "--json"},
                new PrintStream(jsonOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
        String json = jsonOut.toString(StandardCharsets.UTF_8);
        assertEquals(0, jsonExit, json);
        assertTrue(json.contains("\"description\":\"hook\""), json);
        assertTrue(json.contains("\"pkb_hook_marker\":\"hook\""), json);
        assertTrue(json.contains("\"pkb_password\":\"hook\""), json);
        assertTrue(json.contains("\"description\""), json);
        assertTrue(json.contains("hook plain description"), json);
        assertFalse(json.contains("runner-hook-secret-value"), json);
    }

    @Test
    void runnerComputedSecretWithoutAPropertySourceStaysALiteralAndWarns() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(PKB_props.PKB_GLUE, "com.example");
        values.put(PKB_props.PKB_FEATURES, "features");
        values.put(PKB_props.PKB_DATA_PATH, "data");
        values.put(PKB_props.PKB_CALL_PATH, "calls");
        values.put(PKB_props.PKB_COMPONENT_PATH, "component");
        values.put(PKB_props.PKB_CONFIG_PATH, "configs");
        values.put("pkb_password", "runner-secret");
        assertTrue(WorkspaceSeed.runnerComputedSecretKeys(values).contains("pkb_password"));
        List<String> warnings = WorkspaceSeed.runnerComputedSecretWarnings(values);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("pkb_password"), warnings.toString());
        assertTrue(warnings.getFirst().contains("not in project properties"), warnings.toString());
        assertFalse(warnings.getFirst().contains("runner-secret"), warnings.toString());
        Path workspace = temp.resolve("secrets");
        Workspace.writeRunVars(workspace, values, WorkspaceSeed.runnerComputedSecretKeys(values));
        assertEquals("runner-secret", Workspace.readRunVars(workspace).get("pkb_password"));
        assertFalse(Files.readString(Workspace.runvarsFile(workspace)).contains("${protected:pkb_password}"));
    }

    @Test
    void cacheHitsUntilAKeyInputChangesAndExistingWorkspaceIsNotOverwritten() {
        byte[] properties = "pkb_browser=firefox\n".getBytes(StandardCharsets.UTF_8);
        WorkspaceSeed.Inputs inputs = new WorkspaceSeed.Inputs(
                Map.of("pickleball.properties", properties), 10L, "classpath-a", Map.of("pkb_environment", "qa"));
        AtomicInteger captures = new AtomicInteger();
        WorkspaceSeed.seed(temp, "run-1", false, null, inputs, workspace -> {
            captures.incrementAndGet();
            Workspace.writeRunVars(workspace, context("firefox"));
        });
        assertEquals(1, captures.get());

        WorkspaceSeed.seed(temp, "run-2", false, null, inputs, workspace -> captures.incrementAndGet());
        assertEquals(1, captures.get());
        assertEquals("firefox", Workspace.readRunVars(Workspace.directory(temp, "run-2")).get(PKB_props.PKB_BROWSER));

        WorkspaceSeed.Inputs propertiesChanged = new WorkspaceSeed.Inputs(
                Map.of("pickleball.properties", "pkb_browser=edge\n".getBytes(StandardCharsets.UTF_8)),
                10L, "classpath-a", Map.of("pkb_environment", "qa"));
        WorkspaceSeed.seed(temp, "run-3", false, null, propertiesChanged, workspace -> {
            captures.incrementAndGet();
            Workspace.writeRunVars(workspace, context("edge"));
        });
        assertEquals(2, captures.get());

        WorkspaceSeed.Inputs classesChanged = new WorkspaceSeed.Inputs(
                Map.of("pickleball.properties", properties), 11L, "classpath-a", Map.of("pkb_environment", "qa"));
        WorkspaceSeed.seed(temp, "run-4", false, null, classesChanged, workspace -> {
            captures.incrementAndGet();
            Workspace.writeRunVars(workspace, context("firefox"));
        });
        assertEquals(3, captures.get());

        WorkspaceSeed.Inputs classpathChanged = new WorkspaceSeed.Inputs(
                Map.of("pickleball.properties", properties), 10L, "classpath-b", Map.of("pkb_environment", "qa"));
        WorkspaceSeed.seed(temp, "run-5", false, null, classpathChanged, workspace -> {
            captures.incrementAndGet();
            Workspace.writeRunVars(workspace, context("firefox"));
        });
        assertEquals(4, captures.get());

        WorkspaceSeed.Inputs inputsChanged = new WorkspaceSeed.Inputs(
                Map.of("pickleball.properties", properties), 10L, "classpath-a", Map.of("pkb_environment", "prod"));
        WorkspaceSeed.seed(temp, "run-6", false, null, inputsChanged, workspace -> {
            captures.incrementAndGet();
            Workspace.writeRunVars(workspace, context("firefox"));
        });
        assertEquals(5, captures.get());

        Workspace.writeRunVars(Workspace.directory(temp, "run-7"), context("kept"));
        WorkspaceSeed.seed(temp, "run-7", false, null, inputs, workspace -> captures.incrementAndGet());
        assertEquals(5, captures.get());
        assertEquals("kept", Workspace.readRunVars(Workspace.directory(temp, "run-7")).get(PKB_props.PKB_BROWSER));

        WorkspaceSeed.seed(temp, "run-7", true, null, inputsChanged, workspace -> {
            captures.incrementAndGet();
            Workspace.writeRunVars(workspace, context("reseeded"));
        });
        assertEquals(6, captures.get());
        assertEquals("reseeded", Workspace.readRunVars(Workspace.directory(temp, "run-7")).get(PKB_props.PKB_BROWSER));
    }

    @Test
    void seedFromCopiesAnEarlierWorkspaceWithoutResolving() {
        Workspace.writeRunVars(Workspace.directory(temp, "run-a"), context("firefox"));
        AtomicInteger captures = new AtomicInteger();
        WorkspaceSeed.seed(temp, "run-b", false, "run-a", null, workspace -> captures.incrementAndGet());
        assertEquals(0, captures.get());
        assertEquals("firefox", Workspace.readRunVars(Workspace.directory(temp, "run-b")).get(PKB_props.PKB_BROWSER));
        WorkspaceSeed.seed(temp, "run-b", false, "run-a", null, workspace -> captures.incrementAndGet());
        assertEquals(0, captures.get());
        assertEquals("firefox", Workspace.readRunVars(Workspace.directory(temp, "run-b")).get(PKB_props.PKB_BROWSER));
    }

    @Test
    void mainLeavesAnExistingWorkspaceAndCopiesFrom() {
        Workspace.writeRunVars(Workspace.directory(temp, "run-1"), context("edge"));
        int unchanged = WorkspaceSeed.runMain(new String[]{
                "--project", temp.toString(), "--run", "run-1"
        });
        assertEquals(0, unchanged);
        assertEquals("edge", Workspace.readRunVars(Workspace.directory(temp, "run-1")).get(PKB_props.PKB_BROWSER));

        int copied = WorkspaceSeed.runMain(new String[]{
                "--project", temp.toString(), "--run", "run-2", "--from=run-1"
        });
        assertEquals(0, copied);
        assertEquals("edge", Workspace.readRunVars(Workspace.directory(temp, "run-2")).get(PKB_props.PKB_BROWSER));

        int missing = WorkspaceSeed.runMain(new String[]{"--project", temp.toString()});
        assertEquals(2, missing);
    }

    @Test
    void detectRecordsClasspathAndDashDAndEnvInputs() throws Exception {
        System.setProperty("pkb_environment", "from-dash-d");
        System.setProperty(Workspace.PROPERTY, "/tmp/not-a-workspace");
        Properties system = new Properties();
        system.putAll(System.getProperties());
        Map<String, String> inputs = WorkspaceSeed.resolutionInputs(
                system, Map.of("PKB_BROWSER", "firefox", "HOME", "/tmp"));
        assertEquals("from-dash-d", inputs.get("pkb_environment"));
        assertEquals("firefox", inputs.get("env:PKB_BROWSER"));
        assertFalse(inputs.containsKey(Workspace.PROPERTY));
        assertFalse(inputs.containsKey(WorkspaceSeed.SEED_PROPERTY));
        assertFalse(inputs.containsKey("env:HOME"));

        WorkspaceSeed.Inputs detected = WorkspaceSeed.detect(temp);
        assertFalse(detected.classpathFingerprint().isBlank());
        assertEquals("from-dash-d", detected.resolutionInputs().get("pkb_environment"));

        Path classpath = tools.dscode.control.protocol.PickleballLocalLayout.workbenchStateRoot(temp).resolve("classpath.txt");
        Files.createDirectories(classpath.getParent());
        Files.writeString(classpath, temp.resolve("consumer.jar").toString() + "\n");
        Files.writeString(temp.resolve("consumer.jar"), "jar");
        WorkspaceSeed.Inputs synced = WorkspaceSeed.detect(temp);
        assertTrue(synced.classpathFingerprint().contains("consumer.jar"), synced.classpathFingerprint());
        assertFalse(synced.classpathFingerprint().equals(detected.classpathFingerprint()));
    }

    private void clearRunner() throws Exception {
        Field instance = PickleballRunner.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static void clearPkbSystemProperties() {
        for (String key : System.getProperties().stringPropertyNames().toArray(String[]::new)) {
            if (key.regionMatches(true, 0, "pkb_", 0, 4)) System.clearProperty(key);
        }
    }

    private static Map<String, String> context(String browser) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : Workspace.CONTEXT_KEYS) values.put(key, key);
        values.put(PKB_props.PKB_BROWSER, browser);
        values.put(PKB_props.PKB_PARALLEL, "auto");
        return values;
    }
}
