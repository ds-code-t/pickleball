package tools.dscode.workbench.terminal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Existing Workbench worker stdout/stderr capture files. */
public record WorkerLogFiles(Path stdout, Path stderr) {
    public List<Path> existing() {
        List<Path> files = new ArrayList<>();
        if (stdout != null) files.add(stdout);
        if (stderr != null) files.add(stderr);
        return List.copyOf(files);
    }
}
