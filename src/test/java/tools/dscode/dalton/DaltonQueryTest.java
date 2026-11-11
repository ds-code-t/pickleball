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
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonQueryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FEATURE = """
            @billing
            Feature: Pay
              Scenario: One
                Given ready
              Scenario: Two
                When the amount is 10
                  | name |
                  | Ada |
                Then stored
                  \"\"\"
                  receipt
                  \"\"\"
            """;

    @TempDir
    Path project;

    @Test
    void missingDatabaseListsAreEmptyAndDoNotCreateOne() {
        assertEquals("{\"files\":[]}\n", run("dalton", "files", "list", "--json", "--project=" + project).out);
        assertEquals("{\"features\":[]}\n", run("dalton", "features", "--json", "--project=" + project).out);
        assertEquals("{\"scenarios\":[]}\n", run("dalton", "scenarios", "list", "--json", "--project=" + project).out);
        assertEquals("{\"steps\":[]}\n", run("dalton", "steps", "list", "--json", "--project=" + project).out);
        assertEquals("{\"conflicts\":[]}\n", run("dalton", "conflicts", "--json", "--project=" + project).out);
        Capture text = run("dalton", "files", "list", "--project=" + project);
        assertEquals(0, text.code, text.err);
        assertEquals("files 0\n", text.out);
        Capture missing = run("dalton", "files", "show", "features/pay.feature", "--project=" + project);
        assertEquals(1, missing.code);
        assertTrue(missing.err.contains("file not found: features/pay.feature"), missing.err);
        assertFalse(Files.exists(DaltonPaths.database(project)));
        assertFalse(Files.exists(DaltonPaths.root(project)));
    }

    @Test
    void listAndShowAreStableJsonAndHumanLabels() throws Exception {
        write("features/pay.feature", FEATURE);
        write("configs/keep.properties", "keep=yes\n");
        Files.write(project.resolve("configs/cafe.yaml"), new byte[] {(byte) 0xFF, 0x01});
        assertEquals(0, run("dalton", "hydrate", "--project=" + project).code);

        Capture files = run("dalton", "files", "list", "--json", "--project=" + project);
        JsonNode filesJson = JSON.readTree(files.out);
        assertEquals(3, filesJson.get("files").size());
        assertEquals("configs/cafe.yaml", filesJson.get("files").get(0).get("path").asText());
        assertEquals("config", filesJson.get("files").get(0).get("sourceType").asText());
        assertFalse(filesJson.get("files").get(0).get("edited").asBoolean());
        assertTrue(files.out.indexOf("\"path\"") < files.out.indexOf("\"sourceType\""));
        assertTrue(files.out.indexOf("\"sourceType\"") < files.out.indexOf("\"edited\""));
        assertTrue(files.out.indexOf("\"edited\"") < files.out.indexOf("\"bytes\""));
        Capture filesText = run("dalton", "files", "list", "--project=" + project);
        assertTrue(filesText.out.contains("files 3"), filesText.out);
        assertTrue(filesText.out.contains("features/pay.feature  feature  edited=no"), filesText.out);

        Capture show = run("dalton", "files", "show", "features\\pay.feature", "--json", "--project=" + project);
        JsonNode shown = JSON.readTree(show.out);
        assertEquals(FEATURE, shown.get("text").asText());
        assertTrue(shown.get("base64").isNull());
        Capture binary = run("dalton", "files", "show", "configs/cafe.yaml", "--project=" + project);
        assertTrue(binary.out.contains("binary yes"), binary.out);
        assertTrue(binary.out.contains("sourceType config"), binary.out);

        Capture features = run("dalton", "features", "list", "--json", "--project=" + project);
        JsonNode featureList = JSON.readTree(features.out);
        assertEquals(1, featureList.get("features").size());
        assertEquals("Pay", featureList.get("features").get(0).get("name").asText());
        assertEquals(2, featureList.get("features").get(0).get("scenarios").asInt());
        Capture featureText = run("dalton", "features", "list", "--project=" + project);
        assertTrue(featureText.out.contains("features/pay.feature  Pay  scenarios=2"), featureText.out);

        Capture feature = run("dalton", "features", "show", "features/pay.feature", "--json", "--project=" + project);
        JsonNode featureJson = JSON.readTree(feature.out);
        assertEquals(2, featureJson.get("scenarioCount").asInt());
        assertEquals("One", featureJson.get("scenarios").get(0).get("name").asText());
        assertEquals("Scenario", featureJson.get("scenarios").get(0).get("keyword").asText());
        assertTrue(featureJson.get("text").asText().contains("@billing"));

        Capture scenarios = run("dalton", "scenarios", "list", "--feature=features/pay.feature", "--json", "--project=" + project);
        JsonNode scenarioJson = JSON.readTree(scenarios.out);
        assertEquals(2, scenarioJson.get("scenarios").size());
        assertEquals("Two", scenarioJson.get("scenarios").get(1).get("name").asText());
        Capture scenarioText = run("dalton", "scenarios", "list", "--project=" + project);
        assertTrue(scenarioText.out.contains("features/pay.feature  1  Scenario  One"), scenarioText.out);

        Capture steps = run("dalton", "steps", "list", "--feature=features/pay.feature", "--scenario=Two", "--json", "--project=" + project);
        JsonNode stepJson = JSON.readTree(steps.out);
        assertEquals(2, stepJson.get("steps").size());
        assertEquals("When", stepJson.get("steps").get(0).get("keyword").asText());
        assertEquals("Ada", stepJson.get("steps").get(0).get("table").get(1).get(0).asText());
        assertEquals("receipt", stepJson.get("steps").get(1).get("docstring").asText());
        assertTrue(stepJson.get("steps").get(1).get("table").isNull());
        Capture stepText = run("dalton", "steps", "list", "--scenario=1", "--project=" + project);
        assertTrue(stepText.out.contains("steps 1"), stepText.out);
        assertTrue(stepText.out.contains("Given  ready"), stepText.out);

        Capture conflicts = run("dalton", "conflicts", "--project=" + project);
        assertEquals(0, conflicts.code, conflicts.err);
        assertEquals("conflicts 0\n", conflicts.out);
        Capture status = run("dalton", "status", "--json", "--project=" + project);
        assertEquals(0, JSON.readTree(status.out).get("conflicts").asInt());
    }

    @Test
    void conflictsReportUntrackedAndBothSides() throws Exception {
        write("features/pay.feature", FEATURE);
        assertEquals(0, run("dalton", "hydrate", "--project=" + project).code);
        try (var connection = DriverManager.getConnection(DaltonDatabase.jdbcUrl(DaltonPaths.database(project)));
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE files SET content_hash = 'untracked' WHERE path = 'features/pay.feature'");
        }
        Capture untracked = run("dalton", "conflicts", "--json", "--project=" + project);
        assertEquals(1, untracked.code);
        JsonNode node = JSON.readTree(untracked.out);
        assertEquals("untracked", node.get("conflicts").get(0).get("kind").asText());
        assertEquals("untracked DB edit on features/pay.feature", node.get("conflicts").get(0).get("message").asText());
        Capture text = run("dalton", "conflicts", "--project=" + project);
        assertTrue(text.out.contains("untracked DB edit on features/pay.feature"), text.out);

        assertEquals(0, run("dalton", "hydrate", "--force", "--project=" + project).code);
        assertEquals(0, run("dalton", "feature", "put", "features/pay.feature", "--text=" + FEATURE, "--project=" + project).code);
        Files.writeString(project.resolve("features/pay.feature"), FEATURE.replace("One", "File"), StandardCharsets.UTF_8);
        Capture both = run("dalton", "conflicts", "--json", "--project=" + project);
        assertEquals(1, both.code);
        JsonNode bothJson = JSON.readTree(both.out);
        assertEquals("both", bothJson.get("conflicts").get(0).get("kind").asText());
        assertEquals("conflict: features/pay.feature", bothJson.get("conflicts").get(0).get("message").asText());
    }

    @Test
    void queryLoggingIsToggleable() throws Exception {
        String previous = System.getProperty("pkb_dalton_log");
        try {
            System.setProperty("pkb_dalton_log", "query");
            assertEquals(0, run("dalton", "files", "list", "--project=" + project).code);
            String text = Files.readString(DaltonPaths.log(project), StandardCharsets.UTF_8);
            assertTrue(text.contains(" query files list 0"), text);
            assertTrue(text.charAt(0) == '2', text);
        } finally {
            if (previous == null) System.clearProperty("pkb_dalton_log");
            else System.setProperty("pkb_dalton_log", previous);
        }
    }

    private void write(String relative, String text) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DaltonCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
