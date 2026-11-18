package tools.dscode.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedColonStepsTest {
    @TempDir
    Path project;

    @Test
    void storeStepsListKeepsNestedColonSteps() throws Exception {
        Path feature = project.resolve("features/nested.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                Feature: Nested
                  Scenario: A colon child
                    * the parent runs
                      : * the recorded city is saved as "areaBChildCity"
                      : * IF: "yes":
                        :: * , save "yes" as "inner"
                """, StandardCharsets.UTF_8);
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = StoreCli.execute(
                new String[]{"store", "steps", "list", "--project=" + project.toAbsolutePath(), "--feature=features/nested.feature"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8) + text);
        assertTrue(text.contains("the recorded city is saved as \"areaBChildCity\""), text);
        assertTrue(text.contains("save \"yes\" as \"inner\""), text);
    }
}
