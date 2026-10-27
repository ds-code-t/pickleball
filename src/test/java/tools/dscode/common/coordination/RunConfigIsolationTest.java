package tools.dscode.common.coordination;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.control.protocol.RunConfigs;
import tools.dscode.testengine.PKB_props;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunConfigIsolationTest {
    @TempDir
    Path project;

    @AfterEach
    void restore() {
        AgentCoordination.clearCurrent();
        System.clearProperty(PKB_props.PKB_RUN_ID);
        System.clearProperty(PKB_props.PKB_AGENT_ID);
        System.clearProperty(PKB_props.PKB_RUN_GROUP);
        System.clearProperty(PKB_props.PKB_RUN_SEQUENCE);
        System.clearProperty(PKB_props.PKB_RUN_WHO);
        System.clearProperty(PKB_props.PKB_RUN_WHY);
        System.clearProperty(ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY);
        System.clearProperty("pkb_configpath");
        ParsingMap.initializeConfigs("configs");
    }

    @Test
    void aPrivateRunReadsAndWritesOnlyItsOwnConfigCopy() throws Exception {
        Path configs = project.resolve("src/test/resources/configs");
        Files.createDirectories(configs.resolve("jsonfiles"));
        Files.writeString(configs.resolve("CHROME.yaml"), "browser: chrome\nmarker: project\npassword: secret123\n", StandardCharsets.UTF_8);
        Files.writeString(configs.resolve("URL.yaml"), "home: project-home\n", StandardCharsets.UTF_8);
        Files.writeString(configs.resolve("EDGE.yaml"), "browser: edge\nmarker: project-edge\n", StandardCharsets.UTF_8);
        Files.writeString(configs.resolve("jsonfiles/accounts.json"), "{\"id\":1}\n", StandardCharsets.UTF_8);
        String projectChrome = Files.readString(configs.resolve("CHROME.yaml"));
        byte[] projectChromeBytes = Files.readAllBytes(configs.resolve("CHROME.yaml"));
        byte[] projectUrlBytes = Files.readAllBytes(configs.resolve("URL.yaml"));
        byte[] projectEdgeBytes = Files.readAllBytes(configs.resolve("EDGE.yaml"));
        byte[] projectAccountsBytes = Files.readAllBytes(configs.resolve("jsonfiles/accounts.json"));

        AgentCoordination.Run first = openResolved("run-a", "agent-a");
        assertFalse(Files.isDirectory(first.configDirectory()));
        assertFalse(Files.exists(configs.resolve("CHROME_HEADLESS.yaml")));
        assertEquals(projectChrome, Files.readString(configs.resolve("CHROME.yaml")));

        Path workspaceA = tools.dscode.control.protocol.Workspace.directory(project, "run-a");
        Path workspaceB = tools.dscode.control.protocol.Workspace.directory(project, "run-b");
        tools.dscode.control.protocol.Workspace.copyConfigs(configs, workspaceA);
        tools.dscode.control.protocol.Workspace.copyConfigs(configs, workspaceB);
        Files.writeString(
                tools.dscode.control.protocol.Workspace.configsDirectory(workspaceA).resolve("CHROME.yaml"),
                "browser: chrome\nmarker: run-a\npassword: mutated\n"
        );
        assertTrue(Files.readString(tools.dscode.control.protocol.Workspace.configsDirectory(workspaceA).resolve("CHROME.yaml")).contains("marker: run-a"));
        assertArrayEquals(projectChromeBytes, Files.readAllBytes(configs.resolve("CHROME.yaml")));
        assertTrue(Files.readString(tools.dscode.control.protocol.Workspace.configsDirectory(workspaceB).resolve("CHROME.yaml")).contains("marker: project"));
        assertFalse(Files.readString(tools.dscode.control.protocol.Workspace.configsDirectory(workspaceB).resolve("CHROME.yaml")).contains("run-a"));

        ParsingMap.initializeConfigs(configs.toString());
        assertEquals("project", String.valueOf(ParsingMap.getGlobalsParsingmap().get("configs.CHROME.marker")));
        assertEquals("project-home", String.valueOf(ParsingMap.getGlobalsParsingmap().get("configs.URL.home")));
    }

    @Test
    void aNormalTestDoesNotCopyAndStillReadsTheProjectConfigs() throws Exception {
        Path configs = project.resolve("src/test/resources/configs");
        Files.createDirectories(configs);
        Files.writeString(configs.resolve("URL.yaml"), "home: from-project\n", StandardCharsets.UTF_8);
        Files.createDirectories(project.resolve(".pickleball/runs/stale/config"));
        Files.writeString(
                project.resolve(".pickleball/runs/stale/config/URL.yaml"),
                "home: from-stale-run\n",
                StandardCharsets.UTF_8
        );

        Path staleFile = project.resolve(".pickleball/runs/stale/config/URL.yaml");
        byte[] staleBefore = Files.readAllBytes(staleFile);

        assertNull(AgentCoordination.openConsumerRun(project, new StringBuilder()));
        assertFalse(Files.isDirectory(project.resolve(".pickleball/runs/stale/reports")));
        assertArrayEquals(staleBefore, Files.readAllBytes(staleFile));
        try (var walk = Files.walk(project.resolve(".pickleball/runs"))) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            assertEquals(List.of(staleFile), files);
        }
        ParsingMap.initializeConfigs(configs.toString());
        assertEquals("from-project", String.valueOf(ParsingMap.getGlobalsParsingmap().get("configs.URL.home")));
    }

    @Test
    void openConsumerRunDoesNotCopyConfigsIntoTheRunDirectory() throws Exception {
        Path custom = project.resolve("qa-configs");
        Files.createDirectories(custom);
        Files.writeString(custom.resolve("EDGE.yaml"), "browser: edge\nmarker: custom\n", StandardCharsets.UTF_8);
        System.setProperty(PKB_props.PKB_RUN_ID, "run-custom");
        System.setProperty(PKB_props.PKB_AGENT_ID, "agent-custom");

        AgentCoordination.Run run = AgentCoordination.openConsumerRun(project, new StringBuilder());
        assertFalse(Files.isDirectory(run.configDirectory()));
        assertEquals("browser: edge\nmarker: custom\n", Files.readString(custom.resolve("EDGE.yaml")));
        ParsingMap.initializeConfigs(custom.toString());
        assertEquals("custom", String.valueOf(ParsingMap.getGlobalsParsingmap().get("configs.EDGE.marker")));
    }

    private AgentCoordination.Run openResolved(String runId, String agentId) {
        System.setProperty(PKB_props.PKB_RUN_ID, runId);
        System.setProperty(PKB_props.PKB_AGENT_ID, agentId);
        return AgentCoordination.openConsumerRun(project, new StringBuilder());
    }
}
