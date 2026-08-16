package tools.dscode.studio.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void cachesStudioByContentWithoutReplacingAnExistingJar() throws Exception {
        Path first = cache("first");
        Path same = cache("first");
        Path changed = cache("changed");

        assertEquals(first, same);
        assertNotEquals(first, changed);
        assertTrue(Files.isRegularFile(first));
        assertTrue(Files.isRegularFile(changed));
        assertTrue(first.getFileName().toString().startsWith("pickleball-studio-"));
        assertTrue(changed.getFileName().toString().startsWith("pickleball-studio-"));
    }

    private Path cache(String content) throws Exception {
        return StudioLauncher.cacheStudio(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                tempDir
        );
    }
}
