package dev.domengine.desktop;

import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopScreenshotVerbTest {
    @Test
    void capturesTheLaunchFrameAndSourceDoesNotNameGrim() throws Exception {
        String source = Files.readString(sourceFile());
        assertFalse(source.contains("grim"), "DesktopScreenshot must not mention grim");
        try (DesktopLaunchTest.Held held = DesktopLaunchTest.open()) {
            assertEquals("pkb-r4-launch", held.window);
            Path png = held.dir.resolve("shot.png");
            CliResult result = DomEngineMain.dispatch(List.of(
                    "desktop", "screenshot",
                    "--name=pkb-r4-launch",
                    "--out=" + png.toAbsolutePath()
            ));
            assertEquals(0, result.exit(), result.stdout() + result.stderr());
            byte[] bytes = Files.readAllBytes(png);
            assertTrue(bytes.length > 8, "png bytes " + bytes.length);
            assertEquals((byte) 0x89, bytes[0]);
            assertEquals((byte) 'P', bytes[1]);
            assertEquals((byte) 'N', bytes[2]);
            assertEquals((byte) 'G', bytes[3]);
            assertFalse(result.stdout().contains("grim"), result.stdout());
        }
    }

    private static Path sourceFile() {
        List<Path> candidates = List.of(
                Path.of("src/main/java/dev/domengine/desktop/DesktopScreenshot.java"),
                Path.of("dom-engine/src/main/java/dev/domengine/desktop/DesktopScreenshot.java")
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("DesktopScreenshot.java was not found from " + Path.of("").toAbsolutePath());
    }
}
