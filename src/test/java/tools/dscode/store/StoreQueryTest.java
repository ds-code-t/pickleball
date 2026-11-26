package tools.dscode.store;

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

class StoreQueryTest {
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
    void missingDatabaseListsAreEmptyAndDoNotCreateOne() throws Exception {
        assertPayload("{\"files\":[]}", run("store", "files", "list", "--json", "--project=" + project).out);
        assertPayload("{\"features\":[]}", run("store", "features", "--json", "--project=" + project).out);
        assertPayload("{\"scenarios\":[]}", run("store", "scenarios", "list", "--json", "--project=" + project).out);
        assertPayload("{\"steps\":[]}", run("store", "steps", "list", "--json", "--project=" + project).out);
        assertPayload("{\"conflicts\":[]}", run("store", "conflicts", "--json", "--project=" + project).out);
        Capture text = run("store", "files", "list", "--project=" + project);
        assertEquals(0, text.code, text.err);
        assertEquals(line("files 0"), text.out);
        Capture missing = run("store", "files", "show", "features/pay.feature", "--project=" + project);
        assertEquals(1, missing.code);
        assertTrue(missing.err.contains("file not found: features/pay.feature"), missing.err);
        assertFalse(Files.exists(StorePaths.database(project)));
        assertFalse(Files.exists(StorePaths.root(project)));
    }

    @Test
    void listAndShowAreStableJsonAndHumanLabels() throws Exception {
        write("features/pay.feature", FEATURE);
        write("configs/keep.properties", "keep=yes\n");
        Files.write(project.resolve("configs/cafe.yaml"), new byte[] {(byte) 0xFF, 0x01});
        assertEquals(0, run("store", "hydrate", "--project=" + project).code);

        Capture files = run("store", "files", "list", "--json", "--project=" + project);
        JsonNode filesJson = StoreJson.read(files.out);
        assertEquals(3, filesJson.get("files").size());
        assertEquals("configs/cafe.yaml", filesJson.get("files").get(0).get("path").asText());
        assertEquals("config", filesJson.get("files").get(0).get("sourceType").asText());
        assertFalse(filesJson.get("files").get(0).get("edited").asBoolean());
        assertTrue(files.out.indexOf("\"path\"") < files.out.indexOf("\"sourceType\""));
        assertTrue(files.out.indexOf("\"sourceType\"") < files.out.indexOf("\"edited\""));
        assertTrue(files.out.indexOf("\"edited\"") < files.out.indexOf("\"bytes\""));
        Capture filesText = run("store", "files", "list", "--project=" + project);
        assertTrue(filesText.out.contains("files 3"), filesText.out);
        assertTrue(filesText.out.contains("features/pay.feature  feature  edited=no"), filesText.out);

        Capture show = run("store", "files", "show", "features\\pay.feature", "--json", "--project=" + project);
        JsonNode shown = StoreJson.read(show.out);
        assertEquals(FEATURE, shown.get("text").asText());
        assertTrue(shown.get("base64").isNull());
        Capture binary = run("store", "files", "show", "configs/cafe.yaml", "--project=" + project);
        assertTrue(binary.out.contains("binary yes"), binary.out);
        assertTrue(binary.out.contains("sourceType config"), binary.out);

        Capture features = run("store", "features", "list", "--json", "--project=" + project);
        JsonNode featureList = StoreJson.read(features.out);
        assertEquals(1, featureList.get("features").size());
        assertEquals("Pay", featureList.get("features").get(0).get("name").asText());
        assertEquals(2, featureList.get("features").get(0).get("scenarios").asInt());
        Capture featureText = run("store", "features", "list", "--project=" + project);
        assertTrue(featureText.out.contains("features/pay.feature  Pay  scenarios=2"), featureText.out);

        Capture feature = run("store", "features", "show", "features/pay.feature", "--json", "--project=" + project);
        JsonNode featureJson = StoreJson.read(feature.out);
        assertEquals(2, featureJson.get("scenarioCount").asInt());
        assertEquals("One", featureJson.get("scenarios").get(0).get("name").asText());
        assertEquals("Scenario", featureJson.get("scenarios").get(0).get("keyword").asText());
        assertTrue(featureJson.get("text").asText().contains("@billing"));

        Capture scenarios = run("store", "scenarios", "list", "--feature=features/pay.feature", "--json", "--project=" + project);
        JsonNode scenarioJson = StoreJson.read(scenarios.out);
        assertEquals(2, scenarioJson.get("scenarios").size());
        assertEquals("Two", scenarioJson.get("scenarios").get(1).get("name").asText());
        Capture scenarioText = run("store", "scenarios", "list", "--project=" + project);
        assertTrue(scenarioText.out.contains("features/pay.feature  1  Scenario  One"), scenarioText.out);

        Capture steps = run("store", "steps", "list", "--feature=features/pay.feature", "--scenario=Two", "--json", "--project=" + project);
        JsonNode stepJson = StoreJson.read(steps.out);
        assertEquals(2, stepJson.get("steps").size());
        assertEquals("When", stepJson.get("steps").get(0).get("keyword").asText());
        assertEquals("Ada", stepJson.get("steps").get(0).get("table").get(1).get(0).asText());
        assertEquals("receipt", stepJson.get("steps").get(1).get("docstring").asText());
        assertTrue(stepJson.get("steps").get(1).get("table").isNull());
        Capture stepText = run("store", "steps", "list", "--scenario=1", "--project=" + project);
        assertTrue(stepText.out.contains("steps 1"), stepText.out);
        assertTrue(stepText.out.contains("Given  ready"), stepText.out);

        Capture conflicts = run("store", "conflicts", "--project=" + project);
        assertEquals(0, conflicts.code, conflicts.err);
        assertEquals(line("conflicts 0"), conflicts.out);
        Capture status = run("store", "status", "--json", "--project=" + project);
        assertEquals(0, StoreJson.read(status.out).get("conflicts").asInt());
    }

    @Test
    void conflictsReportUntrackedAndBothSides() throws Exception {
        write("features/pay.feature", FEATURE);
        assertEquals(0, run("store", "hydrate", "--project=" + project).code);
        try (var database = StoreDatabase.open(project);
             Statement statement = database.connection().createStatement()) {
            statement.execute("UPDATE files SET content_hash = 'untracked' WHERE path = 'features/pay.feature'");
        }
        Capture untracked = run("store", "conflicts", "--json", "--project=" + project);
        assertEquals(1, untracked.code);
        JsonNode node = StoreJson.read(untracked.out);
        assertEquals("untracked", node.get("conflicts").get(0).get("kind").asText());
        assertEquals("untracked DB edit on features/pay.feature", node.get("conflicts").get(0).get("message").asText());
        Capture text = run("store", "conflicts", "--project=" + project);
        assertTrue(text.out.contains("untracked DB edit on features/pay.feature"), text.out);

        assertEquals(0, run("store", "hydrate", "--force", "--project=" + project).code);
        assertEquals(0, run("store", "feature", "put", "features/pay.feature", "--text=" + FEATURE, "--project=" + project).code);
        Files.writeString(project.resolve("features/pay.feature"), FEATURE.replace("One", "File"), StandardCharsets.UTF_8);
        Capture both = run("store", "conflicts", "--json", "--project=" + project);
        assertEquals(1, both.code);
        JsonNode bothJson = StoreJson.read(both.out);
        assertEquals("both", bothJson.get("conflicts").get(0).get("kind").asText());
        assertEquals("conflict: features/pay.feature", bothJson.get("conflicts").get(0).get("message").asText());
    }

    @Test
    void queryLoggingIsToggleable() throws Exception {
        String previous = System.getProperty("pkb_store_log");
        try {
            System.setProperty("pkb_store_log", "query");
            assertEquals(0, run("store", "files", "list", "--project=" + project).code);
            String text = Files.readString(StorePaths.log(project), StandardCharsets.UTF_8);
            assertTrue(text.contains(" query files sandbox=main list 0"), text);
            assertTrue(text.charAt(0) == '2', text);
        } finally {
            if (previous == null) System.clearProperty("pkb_store_log");
            else System.setProperty("pkb_store_log", previous);
        }
    }

    private void write(String relative, String text) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String line(String text) {
        return text + System.lineSeparator();
    }

    /** The old object is {@code items[0]}. Those four names are not repeated on the envelope. */
    private static void assertPayload(String payload, String out) throws Exception {
        JsonNode envelope = JSON.readTree(out);
        assertEquals(1, envelope.path("v").asInt(), out);
        assertFalse(envelope.has("command"), out);
        assertFalse(envelope.has("count"), out);
        assertFalse(envelope.has("total"), out);
        assertFalse(envelope.has("rows"), out);
        assertEquals(payload, JSON.writeValueAsString(envelope.path("items").get(0)), out);
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = StoreCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
