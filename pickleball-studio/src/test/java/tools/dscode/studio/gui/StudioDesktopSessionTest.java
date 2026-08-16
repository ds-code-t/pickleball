
package tools.dscode.studio.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.studio.language.SourceSymbolKind;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioDesktopSessionTest {

    @TempDir
    Path tempDir;

    @Test
    void reusesWorkspaceFileLanguageAndBuildDetectionServices() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        Path source = tempDir.resolve("src/main/java/example/Sample.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;

                public class Sample {
                    public void run() {
                    }
                }
                """);

        try (StudioDesktopSession session = StudioDesktopSession.open(tempDir)) {
            assertEquals("Gradle", session.testBuildTool());
            assertTrue(
                    session.tree(10, 100).stream()
                            .anyMatch(entry -> "src/main/java/example/Sample.java".equals(entry.path()))
            );

            assertTrue(session.read("src/main/java/example/Sample.java").content().contains("class Sample"));

            session.save("notes/studio.txt", "saved");
            assertEquals("saved", Files.readString(tempDir.resolve("notes/studio.txt")));

            assertTrue(
                    session.outline("src/main/java/example/Sample.java").symbols().stream()
                            .anyMatch(symbol -> symbol.kind() == SourceSymbolKind.JAVA_CLASS
                                    && "Sample".equals(symbol.name()))
            );
            assertTrue(
                    session.searchSymbols("Sample", 20).stream()
                            .anyMatch(symbol -> "Sample".equals(symbol.name()))
            );
        }
    }
}
