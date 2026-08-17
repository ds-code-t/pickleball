package tools.dscode.studio.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.studio.language.SourceSymbolKind;
import tools.dscode.studio.workspace.WorkspaceCheckedWriteResult;
import tools.dscode.studio.workspace.WorkspaceVersionedTextFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioDesktopSessionTest {

    @TempDir
    Path tempDir;

    @Test
    void reusesWorkspaceFileLanguageBuildAndCollaborationServices() throws Exception {
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

            String path = "src/main/java/example/Sample.java";
            WorkspaceVersionedTextFile versioned = session.readVersioned(path);
            assertTrue(versioned.content().contains("class Sample"));
            assertEquals(64, versioned.sha256().length());

            session.editorState(path, true, versioned.sha256());
            assertTrue(session.editorStates().stream().anyMatch(editor -> editor.path().equals(path) && editor.dirty()));

            WorkspaceCheckedWriteResult saved = session.saveChecked(
                    path,
                    versioned.sha256(),
                    versioned.content().replace("class Sample", "class SampleRenamed")
            );
            assertTrue(saved.written());
            assertFalse(saved.blockedByDirtyEditor());

            session.editorState(path, false, saved.newSha256());
            assertTrue(session.activity(0L, 100).activities().stream()
                    .anyMatch(activity -> "workspace.write".equals(activity.operation())));

            session.save("notes/studio.txt", "saved");
            assertEquals("saved", Files.readString(tempDir.resolve("notes/studio.txt")));

            assertTrue(
                    session.outline(path).symbols().stream()
                            .anyMatch(symbol -> symbol.kind() == SourceSymbolKind.JAVA_CLASS
                                    && "SampleRenamed".equals(symbol.name()))
            );
            assertTrue(
                    session.searchSymbols("SampleRenamed", 20).stream()
                            .anyMatch(symbol -> "SampleRenamed".equals(symbol.name()))
            );
        }
    }
}
