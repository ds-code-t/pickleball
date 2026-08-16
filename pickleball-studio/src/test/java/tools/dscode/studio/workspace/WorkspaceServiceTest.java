package tools.dscode.studio.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void opensAndIdentifiesProjectMarkers() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'sample'");
        Files.createDirectories(tempDir.resolve(".git"));

        WorkspaceInfo info = new WorkspaceService().open(tempDir);

        assertEquals(tempDir.toAbsolutePath().normalize(), info.root());
        assertTrue(info.mavenProject());
        assertTrue(info.gradleProject());
        assertTrue(info.gitRepository());
    }

    @Test
    void ordinaryDirectoryIsStillAValidWorkspace() {
        WorkspaceInfo info = new WorkspaceService().open(tempDir);

        assertEquals(tempDir.toAbsolutePath().normalize(), info.root());
        assertFalse(info.mavenProject());
        assertFalse(info.gradleProject());
    }

    @Test
    void rejectsMissingWorkspace() {
        Path missing = tempDir.resolve("missing");

        assertThrows(IllegalArgumentException.class, () -> new WorkspaceService().open(missing));
    }
}
