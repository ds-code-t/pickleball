package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.testengine.PKB_props;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N4: workspace-show, workspace-edit, and the discover launch echo must never
 * print a secret value. A stored {@code ${protected:key}} or {@code $(protected:key)}
 * is shown as written.
 */
class SecretRedactionTest {
    private static final String LITERAL = "hunter2-workspace-secret";
    private static final String REPLACEMENT = "another-hunter2-secret";
    private static final String STORED_REFERENCE = "${protected:custom_slot}";
    private static final String PAREN_REFERENCE = "$(protected:pkb_token)";

    @TempDir
    Path tempDir;

    @BeforeEach
    void captureSeedWithoutAConsumerRunner() {
        WorkspaceLaunch.testCapture = workspace -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : Workspace.CONTEXT_KEYS) values.put(key, key);
            values.put(PKB_props.PKB_BROWSER, "CHROME_HEADLESS");
            values.put(PKB_props.PKB_PARALLEL, "auto");
            Workspace.writeRunVars(workspace, values);
        };
    }

    @AfterEach
    void clearCoordination() {
        WorkspaceLaunch.testCapture = null;
        AgentCoordination.clearCurrent();
        System.clearProperty(PKB_props.PKB_RUN_ID);
        System.clearProperty(PKB_props.PKB_AGENT_ID);
    }

    @Test
    void workspaceShowRedactsLiteralSecretAndPrintsStoredReference() throws Exception {
        writeWorkspace("secret-show");

        Output shown = run("workspace-show", tempDir.toString(), "--run-id=secret-show");

        assertEquals(0, shown.exitCode(), shown.stderr());
        assertSecretAbsent(shown);
        assertTrue(shown.stdout().contains("pkb_password=<redacted>"), shown.stdout());
        assertTrue(shown.stdout().contains("pkb_rp_api_key=" + STORED_REFERENCE), shown.stdout());
        assertTrue(shown.stdout().contains("pkb_token=" + PAREN_REFERENCE), shown.stdout());
        assertTrue(shown.stdout().contains("pkb_browser=CHROME_HEADLESS"), shown.stdout());
        assertTrue(shown.stdout().contains("fix: secret redacted; use workspace-show for reference")
                || shown.stderr().contains("fix: secret redacted; use workspace-show for reference")
                || shown.stdout().contains("pkb_password=<redacted>"), shown.stdout());
    }

    @Test
    void workspaceShowJsonDoesNotEmbedTheSecret() throws Exception {
        writeWorkspace("secret-json");

        Output shown = run("workspace-show", tempDir.toString(), "--run-id=secret-json", "--json");

        assertEquals(0, shown.exitCode(), shown.stderr());
        assertSecretAbsent(shown);
        assertTrue(shown.stdout().contains("\"pkb_password\":\"<redacted>\""), shown.stdout());
        assertTrue(shown.stdout().contains(STORED_REFERENCE), shown.stdout());
        assertTrue(shown.stdout().contains(PAREN_REFERENCE), shown.stdout());
    }

    @Test
    void workspaceEditWarningDoesNotPrintTheOldOrNewSecret() throws Exception {
        writeWorkspace("secret-edit");

        Output edited = run(
                "workspace-edit", tempDir.toString(), "--run-id=secret-edit",
                "--runvar=pkb_password=" + REPLACEMENT
        );

        assertEquals(0, edited.exitCode(), edited.stderr());
        assertSecretAbsent(edited);
        assertFalse(edited.stdout().contains(REPLACEMENT), edited.stdout());
        assertFalse(edited.stderr().contains(REPLACEMENT), edited.stderr());
        assertTrue(edited.stderr().contains("<redacted>"), edited.stderr());
        assertTrue(edited.stderr().contains("secret redacted; use workspace-show for reference"), edited.stderr());
    }

    @Test
    void discoverLaunchEchoShowsTheStoredReferenceAndRedactsTheLiteral() throws Exception {
        writeProjectWrapper();
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("one.feature"), """
                Feature: One
                  Scenario: Only
                    Given a
                """);
        writeWorkspace("secret-launch");

        Output discovered = run(new String[]{
                "discover", tempDir.toString(), "--run-id=secret-launch"
        }, (project, command, out, err) -> 0);

        assertSecretAbsent(discovered);
        assertFalse(discovered.stdout().contains(REPLACEMENT), discovered.stdout());
        assertTrue(discovered.stdout().contains("workspace-runvars="), discovered.stdout());
        assertTrue(discovered.stdout().contains("pkb_rp_api_key=" + STORED_REFERENCE), discovered.stdout());
        assertFalse(discovered.stdout().contains("pkb_rp_api_key=${protected:pkb_rp_api_key}"), discovered.stdout());
        assertTrue(discovered.stdout().contains("pkb_password=<redacted>"), discovered.stdout());
        assertTrue(discovered.stdout().contains("pkb_token=" + PAREN_REFERENCE), discovered.stdout());
    }

    private void writeWorkspace(String runId) throws Exception {
        Path workspace = Workspace.directory(tempDir, runId);
        Files.createDirectories(workspace);
        for (String key : Workspace.CONTEXT_KEYS) {
            // context keys are required only at launch; show and edit still print them when present
        }
        StringBuilder body = new StringBuilder();
        for (String key : Workspace.CONTEXT_KEYS) body.append(key).append('=').append(key).append('\n');
        body.append("pkb_browser=CHROME_HEADLESS\n");
        body.append("pkb_password=").append(LITERAL).append('\n');
        body.append("pkb_rp_api_key=").append(STORED_REFERENCE).append('\n');
        body.append("pkb_token=").append(PAREN_REFERENCE).append('\n');
        Files.writeString(Workspace.runvarsFile(workspace), body.toString());
    }

    private void writeProjectWrapper() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path script = tempDir.resolve(windows ? "mvnw.cmd" : "mvnw");
        Files.writeString(script, windows ? "@echo off\r\n" : "#!/bin/sh\n");
    }

    private static void assertSecretAbsent(Output output) {
        assertFalse(output.stdout().contains(LITERAL), output.stdout());
        assertFalse(output.stderr().contains(LITERAL), output.stderr());
    }

    private Output run(String... args) {
        return run(args, null);
    }

    private Output run(String[] args, WorkbenchAgentCommands.MavenRunner maven) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = maven == null
                ? WorkbenchAgentCommands.run(
                        args,
                        new PrintStream(stdout, true, StandardCharsets.UTF_8),
                        new PrintStream(stderr, true, StandardCharsets.UTF_8))
                : WorkbenchAgentCommands.run(
                        args,
                        new PrintStream(stdout, true, StandardCharsets.UTF_8),
                        new PrintStream(stderr, true, StandardCharsets.UTF_8),
                        maven);
        return new Output(exit, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Output(int exitCode, String stdout, String stderr) {
    }
}
