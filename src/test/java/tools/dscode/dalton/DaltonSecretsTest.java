package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonSecretsTest {
    @TempDir
    Path project;

    @Test
    void secretFieldsAppendAndEntropyOnlyWarns() throws Exception {
        IllegalArgumentException password = assertThrows(IllegalArgumentException.class,
                () -> DaltonEntities.put(project, "Account", "ada", 1, "{\"password\":\"hunter2\"}"));
        assertTrue(password.getMessage().contains("secret reference"), password.getMessage());

        Files.writeString(project.resolve("dalton.properties"), "secret-fields=pin\nsecret-fields=badge\n");
        IllegalArgumentException pin = assertThrows(IllegalArgumentException.class,
                () -> DaltonEntities.put(project, "Card", "one", 1, "{\"pin\":\"1234\",\"name\":\"Ada\"}"));
        assertTrue(pin.getMessage().contains("pin"), pin.getMessage());
        DaltonEntities.put(project, "Card", "one", 1, "{\"pin\":\"<pin:atm>\",\"badge\":\"<badge:id>\",\"name\":\"Ada\"}");

        String token = "Ab3kLm9Qp2xRt8vNw5yZc1uHj6eF4dG7";
        String previousLog = System.getProperty("pkb_dalton_log");
        PrintStream previousErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setProperty("pkb_dalton_log", "entity");
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            DaltonEntities.Entity stored = DaltonEntities.put(
                    project, "Card", "two", 1, "{\"note\":\"" + token + "\",\"name\":\"Ada\"}");
            assertTrue(stored.payloadJson().contains(token), stored.payloadJson());
            String warned = err.toString(StandardCharsets.UTF_8);
            assertTrue(warned.contains(DaltonSecrets.warning("note")), warned);
            assertFalse(warned.contains(token), warned);
            String log = Files.readString(project.resolve(".pickleball/dalton/dalton.log"), StandardCharsets.UTF_8);
            assertTrue(log.contains(" entity note "), log);
            assertTrue(log.contains("high-entropy value on note"), log);
            assertFalse(log.contains(token), log);

            err.reset();
            DaltonEntities.put(project, "Card", "three", 1, "{\"note\":\"hello\",\"name\":\"Ada\"}");
            assertFalse(err.toString(StandardCharsets.UTF_8).contains("warn:"), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(previousErr);
            restore("pkb_dalton_log", previousLog);
        }

        String previousFields = System.getProperty("pkb_dalton_secret_fields");
        try {
            System.setProperty("pkb_dalton_secret_fields", "ssn");
            DaltonEntities.put(project, "Card", "four", 1, "{\"pin\":\"1234\",\"name\":\"Ada\"}");
            IllegalArgumentException ssn = assertThrows(IllegalArgumentException.class,
                    () -> DaltonEntities.put(project, "Card", "five", 1, "{\"ssn\":\"1234\"}"));
            assertTrue(ssn.getMessage().contains("ssn"), ssn.getMessage());
        } finally {
            restore("pkb_dalton_secret_fields", previousFields);
        }

        IllegalArgumentException note = assertThrows(IllegalArgumentException.class, () -> DaltonHistory.record(
                project,
                new DaltonHistory.Draft("run-pin", null, "passed", "", 1, 0, "pin=hunter2", "run", "")));
        assertTrue(note.getMessage().contains("secret references"), note.getMessage());
        DaltonHistory.record(project, new DaltonHistory.Draft(
                "run-ref", null, "passed", "", 1, 0, "pin=<pin:atm>", "run", "badge=<badge:id>"));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
