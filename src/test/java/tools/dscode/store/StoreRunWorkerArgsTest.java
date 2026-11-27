package tools.dscode.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The worker argument file keeps a Windows classpath. A raw {@code @} file would turn {@code \test} into a tab. */
class StoreRunWorkerArgsTest {
    @TempDir
    Path root;

    @Test
    void windowsTestDirectoryStaysABackslashT() throws Exception {
        String classpath = "C:\\proj\\build\\classes\\java\\main;C:\\proj\\build\\classes\\java\\test";
        List<String> args = StoreRun.workerLaunchArgs(classpath, root.toString());
        Path file = StoreRun.writeLaunchArgs(root, args);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("\\\\test"), text);
        assertFalse(text.contains("\t"), text);
        assertTrue(text.contains("-cp"), text);
        assertTrue(text.contains("tools.dscode.store.StoreRunWorker"), text);
        assertTrue(args.get(1).contains("\\test"), args.toString());
        assertFalse(args.get(1).contains("\t"), args.toString());
    }
}
