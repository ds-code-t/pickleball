package tools.dscode.testengine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.testengine.fixtures.WorkspaceLaunchHooked;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceLaunchTest {
    @TempDir
    Path temp;

    private Properties saved;

    @BeforeEach
    void saveProperties() {
        saved = new Properties();
        saved.putAll(System.getProperties());
        WorkspaceLaunchHooked.hookRan = false;
    }

    @AfterEach
    void restore() throws Exception {
        Properties live = System.getProperties();
        live.clear();
        live.putAll(saved);
        Field instance = PickleballRunner.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, null);
        ParsingMap.initializeConfigs("configs");
        Path properties = Path.of("pickleball.properties");
        Files.deleteIfExists(properties);
    }

    @Test
    void workspaceSkipsHooksAppliesSelectionAndLoadsWorkspaceConfigs() throws Exception {
        Path configs = temp.resolve("configs");
        Files.createDirectories(configs);
        Files.writeString(configs.resolve("URL.yaml"), "home: from-workspace\n", StandardCharsets.UTF_8);
        Path workspace = temp.resolve("workspace");
        Map<String, String> values = context();
        values.put(PKB_props.PKB_BROWSER, "edge");
        values.put(PKB_props.PKB_PARALLEL, "auto");
        Workspace.writeRunVars(workspace, values);
        Workspace.copyConfigs(configs, workspace);
        System.setProperty(Workspace.PROPERTY, workspace.toString());
        System.setProperty(PKB_props.PKB_TAGS, "@smoke");

        WorkspaceLaunchHooked runner = new WorkspaceLaunchHooked();

        assertFalse(WorkspaceLaunchHooked.hookRan);
        assertEquals("edge", runner.get(PKB_props.PKB_BROWSER));
        assertNull(runner.get("pkb_hook_marker"));
        assertEquals("@smoke", runner.get(PKB_props.PKB_TAGS));
        assertEquals("edge", System.getProperty(PKB_props.PKB_BROWSER));
        int parallel = Integer.parseInt(runner.get(PKB_props.PKB_PARALLEL));
        assertTrue(parallel >= 1);
        assertEquals("auto", Workspace.readRunVars(workspace).get(PKB_props.PKB_PARALLEL));
        assertEquals("from-workspace", String.valueOf(ParsingMap.getGlobalsParsingmap().get("configs.URL.home")));
    }

    @Test
    void missingContextKeyIsAHardError() {
        Path workspace = temp.resolve("workspace");
        Map<String, String> values = context();
        values.remove(PKB_props.PKB_GLUE);
        Workspace.writeRunVars(workspace, values);
        System.setProperty(Workspace.PROPERTY, workspace.toString());
        IllegalStateException failure = assertThrows(IllegalStateException.class, WorkspaceLaunchHooked::new);
        assertTrue(failure.getMessage().contains("pkb_glue"));
    }

    @Test
    void protectedReferenceResolvesFromProjectPropertiesAndStaysAReferenceOnDisk() throws Exception {
        Files.writeString(Path.of("pickleball.properties"), "pkb_rp_api_key=from-project\n", StandardCharsets.UTF_8);
        Path workspace = temp.resolve("workspace");
        Map<String, String> values = context();
        values.put("pkb_rp_api_key", "do-not-store");
        Workspace.writeRunVars(workspace, values);
        assertEquals("${protected:pkb_rp_api_key}", Workspace.readRunVars(workspace).get("pkb_rp_api_key"));
        System.setProperty(Workspace.PROPERTY, workspace.toString());

        WorkspaceLaunchHooked runner = new WorkspaceLaunchHooked();

        assertEquals("from-project", runner.get("pkb_rp_api_key"));
        assertEquals("${protected:pkb_rp_api_key}", Workspace.readRunVars(workspace).get("pkb_rp_api_key"));
        assertFalse(WorkspaceLaunchHooked.hookRan);
    }

    @Test
    void withoutWorkspaceHooksStillRun() {
        System.clearProperty(Workspace.PROPERTY);
        WorkspaceLaunchHooked runner = new WorkspaceLaunchHooked();
        assertTrue(WorkspaceLaunchHooked.hookRan);
        assertEquals("from-hook", runner.get("pkb_hook_marker"));
        assertEquals("firefox", runner.get(PKB_props.PKB_BROWSER));
    }

    private static Map<String, String> context() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(PKB_props.PKB_GLUE, "com.example.steps");
        values.put(PKB_props.PKB_FEATURES, "classpath:features");
        values.put(PKB_props.PKB_DATA_PATH, "data");
        values.put(PKB_props.PKB_CALL_PATH, "calls");
        values.put(PKB_props.PKB_COMPONENT_PATH, "component");
        values.put(PKB_props.PKB_CONFIG_PATH, "configs");
        values.put(PKB_props.PKB_BROWSER, "firefox");
        values.put(PKB_props.PKB_PARALLEL, "4");
        return values;
    }
}
