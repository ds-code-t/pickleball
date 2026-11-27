package tools.dscode.common.agentfast;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An applied form-fill writes an agent interaction row without recording. */
class FormFillInteractionTest {
    @Test
    void appliedFieldWritesAnAgentRowAndAPasswordStoresFilled(@TempDir Path temp) throws Exception {
        DomNode email = new DomNode();
        email.localName = "input";
        email.attributes.put("id", "email");
        email.attributes.put("type", "text");
        TargetVerb.noteFill(temp, "c2", email, "email", "text", "abc@example.com");

        DomNode secret = new DomNode();
        secret.localName = "input";
        secret.attributes.put("id", "secret");
        secret.attributes.put("type", "password");
        TargetVerb.noteFill(temp, "c3", secret, "secret", "text", "hunter2");

        Path events = temp.resolve(".pickleball").resolve("agent-events.jsonl");
        List<String> lines = Files.readAllLines(events, StandardCharsets.UTF_8);
        String emailRow = lines.stream()
                .filter(line -> line.contains("\"id\":\"email\""))
                .findFirst()
                .orElse("");
        assertTrue(emailRow.contains("\"event\":\"interaction\""), emailRow);
        assertTrue(emailRow.contains("\"source\":\"agent\""), emailRow);
        assertFalse(emailRow.contains("\"source\":\"human\""), emailRow);
        assertTrue(emailRow.contains("abc@example.com"), emailRow);

        String secretRow = lines.stream()
                .filter(line -> line.contains("\"id\":\"secret\""))
                .findFirst()
                .orElse("");
        assertTrue(secretRow.contains("\"value\":\"filled\""), secretRow);
        assertFalse(secretRow.contains("hunter2"), secretRow);
        assertFalse(String.join("\n", lines).contains("\"source\":\"human\""), lines.toString());
    }
}
