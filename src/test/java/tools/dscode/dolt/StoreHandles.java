package tools.dscode.dolt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Proves a test does not leave a store file open. JUnit cannot delete a locked file on Windows. */
final class StoreHandles {
    private StoreHandles() {
    }

    static void assertReleased(Path project) throws IOException {
        if (project == null) return;
        List<Path> files = new ArrayList<>();
        files.add(DoltPaths.mainDatabase(project));
        files.add(DoltPaths.runState(project));
        Path sandboxes = DoltPaths.root(project).resolve("sandboxes");
        if (Files.isDirectory(sandboxes)) {
            try (var stream = Files.list(sandboxes)) {
                for (Path dir : stream.toList()) files.add(dir.resolve("source.db"));
            }
        }
        for (Path file : files) assertNoHandle(file);
    }

    private static void assertNoHandle(Path file) throws IOException {
        Path directory = Path.of("/proc/self/fd");
        if (!Files.isDirectory(directory) || file == null) return;
        String needle = file.toAbsolutePath().normalize().toString();
        try (var stream = Files.list(directory)) {
            for (Path fd : stream.toList()) {
                Path target;
                try {
                    target = Files.readSymbolicLink(fd);
                } catch (IOException ignored) {
                    continue;
                }
                String held = target.toString();
                if (held.equals(needle) || held.startsWith(needle + "-")) {
                    throw new AssertionError("store file still open: " + held);
                }
            }
        }
    }
}
