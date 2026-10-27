package tools.dscode.testengine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.testengine.fixtures.BlankBrowserRunner;
import tools.dscode.testengine.fixtures.WorkspaceSeedHooked;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        for (String key : Workspace.CONTEXT_KEYS) assertTrue(captured.containsKey(key), key);
        assertFalse(captured.containsKey(PKB_props.PKB_RUN_ID));

        Path blankWorkspace = temp.resolve("blank");
        WorkspaceSeed.writeCaptured(blankWorkspace, blank, false);
        assertEquals("CHROME_HEADLESS", Workspace.readRunVars(blankWorkspace).get(PKB_props.PKB_BROWSER));

        assertThrows(IllegalStateException.class, () -> WorkspaceSeed.writeCaptured(hookedWorkspace, hooked, false));
        WorkspaceSeed.writeCaptured(hookedWorkspace, blank, true);
        assertEquals("CHROME_HEADLESS", Workspace.readRunVars(hookedWorkspace).get(PKB_props.PKB_BROWSER));
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
    void detectRecordsClasspathAndDashDAndEnvInputs() {
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
