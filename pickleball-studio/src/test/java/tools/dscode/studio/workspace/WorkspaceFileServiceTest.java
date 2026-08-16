package tools.dscode.studio.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceFileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void readsWritesListsAndSearchesWorkspaceText() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.writeString(tempDir.resolve("src/main/java/App.java"), "class App { // Pickleball Studio\n}\n");
        Files.createDirectories(tempDir.resolve("build/generated"));
        Files.writeString(tempDir.resolve("build/generated/Hidden.java"), "Pickleball Studio");

        WorkspaceFileService files = new WorkspaceFileService(tempDir);

        List<WorkspaceEntry> tree = files.tree("", 5, 100);
        assertTrue(tree.stream().anyMatch(entry -> entry.path().equals("src/main/java/App.java")));
        assertFalse(tree.stream().anyMatch(entry -> entry.path().startsWith("build/")));

        WorkspaceTextFile read = files.readText("src/main/java/App.java");
        assertTrue(read.content().contains("Pickleball Studio"));

        WorkspaceWriteResult write = files.writeText("src/test/resources/sample.txt", "hello studio");
        assertEquals("src/test/resources/sample.txt", write.path());
        assertEquals("hello studio", Files.readString(tempDir.resolve("src/test/resources/sample.txt")));

        List<TextSearchMatch> matches = files.searchText("pickleball studio", "", false, 10);
        assertEquals(1, matches.size());
        assertEquals("src/main/java/App.java", matches.getFirst().path());
        assertEquals(1, matches.getFirst().line());
    }

    @Test
    void enforcesWorkspaceBoundary() {
        WorkspaceFileService files = new WorkspaceFileService(tempDir);

        assertThrows(IllegalArgumentException.class, () -> files.readText("../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> files.writeText("../outside.txt", "nope"));
    }

    @Test
    void honorsResultLimits() throws Exception {
        Files.writeString(tempDir.resolve("sample.txt"), "match\nmatch\nmatch\n");
        WorkspaceFileService files = new WorkspaceFileService(tempDir);

        assertEquals(2, files.searchText("match", "", true, 2).size());
        assertEquals(1, files.tree("", 5, 1).size());
    }
}
