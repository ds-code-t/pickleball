package tools.dscode.common.coordination;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunConfigCopyRedactionTest {
    @TempDir
    Path tempDir;

    @Test
    void workbenchCopyRedactsSecretsAndLeavesTheLiveConfigAlone() throws Exception {
        Path project = tempDir.resolve("project");
        Path configs = project.resolve("configs");
        Files.createDirectories(configs);
        String live = """
                browser: chrome
                password: hunter2
                api_key: abc-123
                name: visible
                """;
        Files.writeString(configs.resolve("app.yaml"), live, StandardCharsets.UTF_8);
        Path destination = tempDir.resolve("run").resolve("config");
        RunConfigCopy.copyIfAbsent(
                project,
                destination,
                "wd-file:" + configs.toAbsolutePath().normalize().toString().replace('\\', '/'),
                project
        );
        String copied = Files.readString(destination.resolve("app.yaml"));
        assertTrue(copied.contains("browser: chrome"), copied);
        assertTrue(copied.contains("name: visible"), copied);
        assertTrue(copied.contains("password: ***"), copied);
        assertTrue(copied.contains("api_key: ***"), copied);
        assertFalse(copied.contains("hunter2"), copied);
        assertFalse(copied.contains("abc-123"), copied);
        assertEquals(live, Files.readString(configs.resolve("app.yaml")));
    }
}
