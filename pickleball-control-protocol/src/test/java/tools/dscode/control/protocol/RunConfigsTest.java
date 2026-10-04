package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunConfigsTest {
    @TempDir
    Path project;

    @Test
    void writesStayInsideTheRunCopy() throws Exception {
        Path file = RunConfigs.directory(project, "run-a").resolve("nested/URL.yaml");
        RunConfigs.write(project, "run-a", "nested/URL.yaml", "home: a\n");
        assertEquals("home: a\n", Files.readString(file));
        assertEquals("home: a\n", RunConfigs.read(project, "run-a", "nested/URL.yaml"));
        assertTrue(RunConfigs.files(project, "run-a").contains("nested/URL.yaml"));
        assertThrows(IllegalArgumentException.class, () ->
                RunConfigs.write(project, "run-a", "../URL.yaml", "nope\n"));
        assertTrue(Files.notExists(project.resolve(".pickleball/runs/URL.yaml")));
    }
}
