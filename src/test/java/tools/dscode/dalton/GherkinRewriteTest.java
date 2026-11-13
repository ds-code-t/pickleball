package tools.dscode.dalton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GherkinRewriteTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void addModifyAndDeleteKeepCommentsAndValidGherkin() throws Exception {
        Files.createDirectories(project.resolve("features"));
        String original = """
                # keep me
                Feature: Pay

                  @slow
                  Scenario: One
                    # note
                    Given ready

                  Scenario Outline: Rows
                    Given <item>
                    Examples:
                      | item |
                      | a    |
                """;
        Files.writeString(project.resolve("features/pay.feature"), original);

        Capture add = run("dalton", "feature", "scenario-add", "--feature=features/pay.feature", "--name=Two",
                "--text=When later", "--json", "--project=" + project);
        assertEquals(0, add.code, add.err);
        String added = Files.readString(project.resolve("features/pay.feature"));
        assertTrue(added.startsWith("# keep me\n"), added);
        assertTrue(added.contains("Scenario: One"), added);
        assertTrue(added.contains("# note"), added);
        assertTrue(added.contains("Scenario: Two"), added);
        assertTrue(added.contains("When later"), added);
        assertTrue(added.contains("Examples:"), added);
        assertNotNullParse(added);

        Capture modify = run("dalton", "feature", "scenario-modify", "--feature=features/pay.feature",
                "--scenario=One", "--name=Only", "--text=Then done", "--json", "--project=" + project);
        assertEquals(0, modify.code, modify.err);
        String modified = Files.readString(project.resolve("features/pay.feature"));
        assertTrue(modified.startsWith("# keep me\n"), modified);
        assertTrue(modified.contains("Scenario: Only"), modified);
        assertFalse(modified.contains("Given ready"), modified);
        assertTrue(modified.contains("# note"), modified);
        assertTrue(modified.contains("Then done"), modified);
        assertTrue(modified.contains("| a    |"), modified);
        assertNotNullParse(modified);

        Capture step = run("dalton", "feature", "step-add", "--feature=features/pay.feature",
                "--scenario=Rows", "--keyword=And", "--text=checked", "--json", "--project=" + project);
        assertEquals(0, step.code, step.err);
        String stepped = Files.readString(project.resolve("features/pay.feature"));
        int examples = stepped.indexOf("Examples:");
        int checked = stepped.indexOf("And checked");
        assertTrue(checked > 0 && examples > checked, stepped);
        assertNotNullParse(stepped);

        Capture changed = run("dalton", "feature", "step-modify", "--feature=features/pay.feature",
                "--scenario=1", "--step=1", "--text=started", "--json", "--project=" + project);
        assertEquals(0, changed.code, changed.err);
        String afterStep = Files.readString(project.resolve("features/pay.feature"));
        assertTrue(afterStep.contains("Then started"), afterStep);
        assertFalse(afterStep.contains("Then done"), afterStep);
        assertTrue(afterStep.contains("# note"), afterStep);

        Capture removedStep = run("dalton", "feature", "step-delete", "--feature=features/pay.feature",
                "--scenario=Only", "--step=1", "--confirm", "--json", "--project=" + project);
        assertEquals(0, removedStep.code, removedStep.err);
        String withoutStep = Files.readString(project.resolve("features/pay.feature"));
        assertFalse(withoutStep.contains("Given started"), withoutStep);
        assertTrue(withoutStep.contains("# note"), withoutStep);
        assertTrue(withoutStep.contains("Scenario: Only"), withoutStep);

        Capture removed = run("dalton", "feature", "scenario-delete", "--feature=features/pay.feature",
                "--scenario=Only", "--confirm", "--json", "--project=" + project);
        assertEquals(0, removed.code, removed.err);
        String left = Files.readString(project.resolve("features/pay.feature"));
        assertTrue(left.startsWith("# keep me\n"), left);
        assertFalse(left.contains("Scenario: Only"), left);
        assertFalse(left.contains("@slow"), left);
        assertTrue(left.contains("Scenario Outline: Rows"), left);
        assertNotNullParse(left);
    }

    @Test
    void daltonOnSetsDbEditedAndAConflictRefusesTheRewrite() throws Exception {
        Files.writeString(project.resolve("dalton.properties"), "enabled=true\n");
        Files.createDirectories(project.resolve("features"));
        String original = "Feature: Pay\n  Scenario: One\n    Given ready\n";
        Files.writeString(project.resolve("features/pay.feature"), original);
        assertEquals(0, run("dalton", "hydrate", "--project=" + project).code);

        Capture add = run("dalton", "feature", "scenario-add", "--feature=features/pay.feature", "--name=Two",
                "--text=Given later", "--json", "--project=" + project);
        assertEquals(0, add.code, add.err);
        JsonNode json = JSON.readTree(add.out);
        assertTrue(json.get("applied").asBoolean());
        assertTrue(json.get("dbEdited").asBoolean());
        assertEquals(original, Files.readString(project.resolve("features/pay.feature")));
        String stored = DaltonQuery.file(project, "features/pay.feature").text();
        assertTrue(stored.contains("Scenario: Two"), stored);
        assertNotNullParse(stored);

        Files.writeString(project.resolve("features/pay.feature"), "Feature: Pay\n  Scenario: Disk\n    Given disk\n");
        Capture conflict = run("dalton", "feature", "step-add", "--feature=features/pay.feature",
                "--scenario=One", "--text=And more", "--json", "--project=" + project);
        assertEquals(1, conflict.code, conflict.err);
        assertTrue(conflict.err.contains("conflict") || conflict.out.contains("conflict"), conflict.out + conflict.err);
        assertEquals("Feature: Pay\n  Scenario: Disk\n    Given disk\n", Files.readString(project.resolve("features/pay.feature")));
        assertFalse(DaltonQuery.file(project, "features/pay.feature").text().contains("And more"));

        Capture direct = run("dalton", "feature", "put", "features/pay.feature", "--text=" + original, "--force", "--project=" + project);
        assertEquals(0, direct.code, direct.err);
    }

    private static void assertNotNullParse(String text) {
        assertTrue(DaltonGherkin.parse(text) != null, text);
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DaltonCli.execute(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
