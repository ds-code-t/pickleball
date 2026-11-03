package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballLocalLayout;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceLaunchForkTest {
    @TempDir
    Path temp;

    @Test
    void seedUsesTheCachedClasspathInsteadOfThisJvm() throws Exception {
        Path classpath = temp.resolve("empty-classes");
        Files.createDirectories(classpath);
        Path file = PickleballLocalLayout.workbenchStateRoot(temp).resolve("classpath.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, classpath.toString());
        Path workspace = temp.resolve(".pickleball/runs/run-1/workspace");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> WorkspaceLaunch.capture(temp, workspace, false, null)
        );
        assertTrue(failure.getMessage().contains("Workspace seed failed"), failure.getMessage());
        assertTrue(failure.getMessage().contains("exit="), failure.getMessage());
    }
}
