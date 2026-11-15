package tools.dscode.dolt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltEditContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    private String previousDolt;

    @BeforeEach
    void filePathIsTheOptOutTheseAssertionsCover() {
        previousDolt = System.getProperty("pkb_dolt");
        System.setProperty("pkb_dolt", "false");
    }

    @AfterEach
    void restoreDoltFlag() {
        if (previousDolt == null) System.clearProperty("pkb_dolt");
        else System.setProperty("pkb_dolt", previousDolt);
    }

    @Test
    void jsonEnvelopeIsStableAndValidationRunsBeforeWrite() throws Exception {
        Capture missing = run("dolt", "category", "put", "--id=Badge", "--text=", "--json", "--project=" + project);
        assertEquals(1, missing.code, missing.err);
        JsonNode bad = JSON.readTree(missing.out);
        assertFalse(bad.get("ok").asBoolean());
        assertEquals("category put", bad.get("command").asText());
        assertEquals("category", bad.get("family").asText());
        assertEquals("put", bad.get("action").asText());
        assertTrue(bad.has("confirmRequired"));
        assertTrue(bad.has("applied"));
        assertTrue(bad.has("dbEdited"));
        assertTrue(bad.has("items"));
        assertFalse(Files.exists(project.resolve("dolt-entities/ElementCategory/Badge.yaml")));
        assertFalse(Files.exists(DoltPaths.database(project)));

        Capture escape = run("dolt", "file", "put", "--path=../secrets.yaml", "--text=a: 1", "--json", "--project=" + project);
        assertEquals(1, escape.code, escape.err);
        assertTrue(escape.err.contains("..") || escape.out.contains(".."), escape.out + escape.err);

        Capture put = run("dolt", "category", "put", "--id=Badge", "--text=name: Badge\nbase: //article\n", "--json", "--project=" + project);
        assertEquals(0, put.code, put.err);
        JsonNode wrote = JSON.readTree(put.out);
        assertTrue(wrote.get("ok").asBoolean());
        assertTrue(wrote.get("applied").asBoolean());
        assertFalse(wrote.get("dolt").asBoolean());
        assertFalse(wrote.get("dbEdited").asBoolean());
        assertEquals("dolt-entities/ElementCategory/Badge.yaml", wrote.get("path").asText());
        assertEquals("name: Badge\nbase: //article\n", Files.readString(project.resolve("dolt-entities/ElementCategory/Badge.yaml")));
        assertFalse(Files.exists(DoltPaths.database(project)));

        Capture listed = run("dolt", "category", "list", "--json", "--project=" + project);
        assertEquals(0, listed.code, listed.err);
        assertEquals("Badge", JSON.readTree(listed.out).get("items").get(0).get("id").asText());

        Capture text = run("dolt", "category", "list", "--project=" + project);
        assertTrue(text.out.contains("category list 1"), text.out);
        assertTrue(text.out.contains("Badge  dolt-entities/ElementCategory/Badge.yaml"), text.out);
    }

    @Test
    void deleteRequiresConfirmationAndDoesNotWriteWithoutIt() throws Exception {
        Files.createDirectories(project.resolve("configs"));
        Files.writeString(project.resolve("configs/app.yaml"), "browser: chrome\n");
        Capture refused = run("dolt", "file", "delete", "--path=configs/app.yaml", "--json", "--project=" + project);
        assertEquals(DoltEdit.CONFIRM_EXIT, refused.code, refused.err);
        JsonNode json = JSON.readTree(refused.out);
        assertTrue(json.get("confirmRequired").asBoolean());
        assertFalse(json.get("applied").asBoolean());
        assertEquals("confirmation required", json.get("error").asText());
        assertTrue(Files.exists(project.resolve("configs/app.yaml")));

        Capture removed = run("dolt", "file", "delete", "--path=configs/app.yaml", "--confirm", "--json", "--project=" + project);
        assertEquals(0, removed.code, removed.err);
        assertTrue(JSON.readTree(removed.out).get("applied").asBoolean());
        assertFalse(Files.exists(project.resolve("configs/app.yaml")));
        assertFalse(Files.exists(DoltPaths.database(project)));
    }

    @Test
    void doltOnSetsDbEditedAndRefusesAConflict() throws Exception {
        System.clearProperty("pkb_dolt");
        Files.writeString(project.resolve("dolt.properties"), "enabled=true\n");
        Files.createDirectories(project.resolve("configs"));
        Files.writeString(project.resolve("configs/app.yaml"), "browser: chrome\n");
        assertEquals(0, run("dolt", "hydrate", "--project=" + project).code);

        Capture put = run("dolt", "file", "put", "--path=configs/app.yaml", "--text=browser: edge\n", "--json", "--project=" + project);
        assertEquals(0, put.code, put.err);
        JsonNode wrote = JSON.readTree(put.out);
        assertTrue(wrote.get("dolt").asBoolean());
        assertTrue(wrote.get("dbEdited").asBoolean());
        assertEquals("browser: chrome\n", Files.readString(project.resolve("configs/app.yaml")));

        Files.writeString(project.resolve("configs/app.yaml"), "browser: file\n");
        Capture conflict = run("dolt", "file", "put", "--path=configs/app.yaml", "--text=browser: both\n", "--json", "--project=" + project);
        assertEquals(1, conflict.code, conflict.err);
        JsonNode refused = JSON.readTree(conflict.out);
        assertTrue(refused.get("conflict").asBoolean());
        assertFalse(refused.get("applied").asBoolean());
        assertEquals("browser: file\n", Files.readString(project.resolve("configs/app.yaml")));

        Capture forced = run("dolt", "file", "put", "--path=configs/app.yaml", "--text=browser: forced\n", "--force", "--json", "--project=" + project);
        assertEquals(0, forced.code, forced.err);
        assertTrue(JSON.readTree(forced.out).get("applied").asBoolean());
    }

    @Test
    void featureScenarioCommandsKeepAStableShapeAndDoNotWrite() throws Exception {
        Files.createDirectories(project.resolve("features"));
        String original = "Feature: Pay\n  Scenario: One\n    Given ready\n";
        Files.writeString(project.resolve("features/pay.feature"), original);
        Capture add = run("dolt", "feature", "scenario-add", "--feature=features/pay.feature", "--name=Two",
                "--text=Given later", "--json", "--project=" + project);
        assertEquals(0, add.code, add.err);
        JsonNode json = JSON.readTree(add.out);
        assertEquals("feature scenario-add", json.get("command").asText());
        assertEquals("scenario-add", json.get("action").asText());
        assertTrue(json.get("applied").asBoolean());
        String rewritten = Files.readString(project.resolve("features/pay.feature"));
        assertTrue(rewritten.contains("Scenario: One"), rewritten);
        assertTrue(rewritten.contains("Scenario: Two"), rewritten);
        assertTrue(rewritten.contains("Given later"), rewritten);
        assertNotNull(tools.dscode.dolt.DoltGherkin.parse(rewritten));

        Capture step = run("dolt", "feature", "step-delete", "--feature=features/pay.feature", "--scenario=One", "--json", "--project=" + project);
        assertEquals(1, step.code);
        assertTrue(step.out.contains("needs --step") || step.err.contains("needs --step"));

        Capture direct = run("dolt", "scenario", "put", "features/pay.feature", "--project=" + project);
        assertEquals(1, direct.code);
        assertTrue(direct.err.contains("Structured edits are not supported"), direct.err);
    }

    @Test
    void gluePutWritesSourceAndEditLogIsToggleable() throws Exception {
        String previous = System.getProperty("pkb_dolt_log");
        try {
            System.setProperty("pkb_dolt_log", "edit");
            Capture put = run("dolt", "glue", "put", "--path=glue/js/steps.js", "--text=export function ready() {}\n",
                    "--json", "--project=" + project);
            assertEquals(0, put.code, put.err);
            assertTrue(Files.exists(project.resolve("glue/js/steps.js")));
            Capture listed = run("dolt", "glue", "list", "--json", "--project=" + project);
            assertEquals("glue/js/steps.js", JSON.readTree(listed.out).get("items").get(0).get("path").asText());
            String log = Files.readString(DoltPaths.log(project), StandardCharsets.UTF_8);
            assertTrue(log.contains(" edit glue/js/steps.js sandbox=main put "), log);
            assertTrue(log.charAt(0) == '2', log);
        } finally {
            if (previous == null) System.clearProperty("pkb_dolt_log");
            else System.setProperty("pkb_dolt_log", previous);
        }
    }

    @Test
    void helpDocumentsTheEditFamily() {
        Capture help = run("dolt", "help");
        assertEquals(0, help.code);
        assertTrue(help.out.contains("dolt category list|show|put|remove"));
        assertTrue(help.out.contains("dolt file put|delete"));
        assertTrue(help.out.contains("dolt glue list|show|put|remove"));
        assertTrue(help.out.contains("scenario-add|scenario-modify|scenario-delete|step-add|step-modify|step-delete"));
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DoltCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
