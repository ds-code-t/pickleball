package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceTest {
    @TempDir
    Path temp;

    @Test
    void fingerprintIsStableAndChangesWhenRunvarsOrConfigsChange() throws Exception {
        Path workspace = temp.resolve("workspace");
        Map<String, String> values = context();
        values.put("pkb_browser", "firefox");
        values.put("pkb_parallel", Workspace.PARALLEL_AUTO);
        Workspace.writeRunVars(workspace, values);
        Path configs = temp.resolve("project-configs");
        Files.createDirectories(configs);
        Files.writeString(configs.resolve("CHROME.yaml"), "browser: chrome\nargs: [--window-size=800,600]\n");
        Workspace.copyConfigs(configs, workspace);

        String first = Workspace.fingerprint(workspace);
        String second = Workspace.fingerprint(workspace);
        assertEquals(first, second);
        assertEquals(64, first.length());

        Workspace.writeRunVars(workspace, Map.of("pkb_browser", "edge"));
        assertFalse(first.equals(Workspace.fingerprint(workspace)));

        Workspace.writeRunVars(workspace, values);
        assertEquals(first, Workspace.fingerprint(workspace));

        Files.writeString(Workspace.configsDirectory(workspace).resolve("CHROME.yaml"), "browser: chrome\nargs: [--headless]\n");
        assertFalse(first.equals(Workspace.fingerprint(workspace)));
    }

    @Test
    void atomicWriteLeavesNoPartialFileAndRoundTripsValues() throws Exception {
        Path workspace = temp.resolve("workspace");
        Map<String, String> values = context();
        values.put("pkb_name", "line\nbreak");
        Workspace.writeRunVars(workspace, values);
        assertTrue(Files.isRegularFile(Workspace.runvarsFile(workspace)));
        try (var walk = Files.list(workspace)) {
            assertTrue(walk.noneMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
        assertEquals("line\nbreak", Workspace.readRunVars(workspace).get("pkb_name"));
        assertEquals(Workspace.PARALLEL_AUTO, Workspace.readRunVars(workspace).get("pkb_parallel"));
    }

    @Test
    void changedOnDiskDetectsMtimeAndContent() throws Exception {
        Path workspace = temp.resolve("workspace");
        Workspace.writeRunVars(workspace, context());
        Workspace.DiskStamp stamp = Workspace.stamp(workspace);
        assertFalse(Workspace.changedSince(workspace, stamp));

        Path file = Workspace.runvarsFile(workspace);
        String text = Files.readString(file);
        Files.writeString(file, text.replace("pkb_glue=com.example", "pkb_glue=com.other"));
        assertTrue(Workspace.changedSince(workspace, stamp));
    }

    @Test
    void missingContextKeyIsAHardErrorAndBlankIsAllowed() {
        Map<String, String> values = context();
        values.remove("pkb_callpath");
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> Workspace.requireContext(values));
        assertTrue(missing.getMessage().contains("pkb_callpath"));
        assertTrue(missing.getMessage().contains("Blank values are allowed"));

        values.put("pkb_callpath", "");
        Workspace.requireContext(values);
    }

    @Test
    void protectedReferencesAreStoredInsteadOfSecretValues() {
        Map<String, String> values = context();
        values.put("pkb_rp_api_key", "super-secret");
        values.put("pkb_password", "hunter2");
        Workspace.writeRunVars(temp.resolve("workspace"), values);
        Map<String, String> stored = Workspace.readRunVars(temp.resolve("workspace"));
        assertEquals("${protected:pkb_rp_api_key}", stored.get("pkb_rp_api_key"));
        assertEquals("${protected:pkb_password}", stored.get("pkb_password"));
        assertFalse(stored.get("pkb_rp_api_key").contains("super-secret"));
        assertEquals("pkb_rp_api_key", Workspace.protectedKey(stored.get("pkb_rp_api_key")));
    }

    @Test
    void literalSecretInConfigsIsAWarningAndIsNotRewritten() throws Exception {
        Path configs = temp.resolve("configs");
        Files.createDirectories(configs);
        Files.writeString(configs.resolve("service.yaml"), "password: plain-text\ntoken: ${protected:pkb_token}\n");
        Path workspace = temp.resolve("workspace");
        Workspace.writeRunVars(workspace, context());
        Workspace.copyConfigs(configs, workspace);
        String copied = Files.readString(Workspace.configsDirectory(workspace).resolve("service.yaml"));
        assertTrue(copied.contains("password: plain-text"));
        List<String> warnings = Workspace.literalSecretWarnings(Workspace.configsDirectory(workspace));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().startsWith("WARN:"));
        assertTrue(warnings.getFirst().contains("password"));
        assertFalse(warnings.getFirst().contains("plain-text"));
    }

    @Test
    void launchArgumentsAreOnlyWorkspaceAndSelectionKeys() {
        Path workspace = temp.resolve("runs").resolve("r1").resolve("workspace");
        List<String> arguments = Workspace.launchArguments(workspace, Map.of(
                "pkb_tags", "@smoke",
                "pkb_parallel", "auto",
                "pkb_browser", "firefox",
                "pkb_run_id", "r1"
        ));
        assertTrue(arguments.stream().anyMatch(item -> item.startsWith("-Dpkb_workspace=")));
        assertTrue(arguments.contains("-Dpkb_tags=@smoke"));
        assertTrue(arguments.contains("-Dpkb_parallel=auto"));
        assertTrue(arguments.contains("-Dpkb_run_id=r1"));
        assertTrue(arguments.stream().noneMatch(item -> item.contains("pkb_browser")));
        assertTrue(arguments.stream().noneMatch(item -> item.startsWith("-Dpkb_runvars=")));
    }

    @Test
    void cacheKeyChangesWhenAnyInputChangesAndDoesNotOverwriteAnExistingWorkspace() throws Exception {
        byte[] properties = "pkb_browser=firefox\n".getBytes(StandardCharsets.UTF_8);
        String first = Workspace.cacheKey(Map.of("pickleball.properties", properties), 10L, "cp-a", Map.of("pkb_environment", "qa"));
        String same = Workspace.cacheKey(Map.of("pickleball.properties", properties), 10L, "cp-a", Map.of("pkb_environment", "qa"));
        assertEquals(first, same);
        assertFalse(first.equals(Workspace.cacheKey(Map.of("pickleball.properties", "pkb_browser=edge\n".getBytes(StandardCharsets.UTF_8)), 10L, "cp-a", Map.of("pkb_environment", "qa"))));
        assertFalse(first.equals(Workspace.cacheKey(Map.of("pickleball.properties", properties), 11L, "cp-a", Map.of("pkb_environment", "qa"))));
        assertFalse(first.equals(Workspace.cacheKey(Map.of("pickleball.properties", properties), 10L, "cp-b", Map.of("pkb_environment", "qa"))));
        assertFalse(first.equals(Workspace.cacheKey(Map.of("pickleball.properties", properties), 10L, "cp-a", Map.of("pkb_environment", "prod"))));

        Path project = temp.resolve("project");
        Path workspace = Workspace.directory(project, "run-1");
        Workspace.writeRunVars(workspace, context());
        Workspace.storeCache(project, first, workspace);
        assertTrue(Workspace.cacheHit(project, first));
        Path other = Workspace.directory(project, "run-2");
        Workspace.restoreCache(project, first, other);
        assertEquals("firefox", Workspace.readRunVars(other).get("pkb_browser"));
        assertThrows(IllegalStateException.class, () -> Workspace.restoreCache(project, first, other));
    }

    @Test
    void fingerprintIsWrittenOntoRecordJson() throws Exception {
        Path record = temp.resolve("record.json");
        Files.writeString(record, "{\n  \"runId\" : \"run-1\"\n}\n");
        Workspace.writeFingerprint(record, "abc");
        String text = Files.readString(record);
        assertTrue(text.contains("\"workspaceFingerprint\" : \"abc\""));
        assertTrue(text.contains("\"runId\" : \"run-1\""));
        Workspace.writeFingerprint(record, "def");
        assertTrue(Files.readString(record).contains("\"workspaceFingerprint\" : \"def\""));
        assertFalse(Files.readString(record).contains("abc"));
    }

    private static Map<String, String> context() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("pkb_glue", "com.example");
        values.put("pkb_features", "classpath:features");
        values.put("pkb_datapath", "data");
        values.put("pkb_callpath", "calls");
        values.put("pkb_componentpath", "component");
        values.put("pkb_configpath", "configs");
        values.put("pkb_browser", "firefox");
        values.put("pkb_parallel", Workspace.PARALLEL_AUTO);
        return values;
    }
}
