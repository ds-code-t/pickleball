package tools.dscode.common.coordination;

import tools.dscode.control.protocol.RunConfigs;

import java.nio.file.Path;

/**
 * Run-folder name for copied configs. The test JVM does not copy configs on launch.
 * Workspace seed copies configs into the workspace instead.
 */
public final class RunConfigCopy {
    public static final String DIRECTORY_NAME = RunConfigs.DIRECTORY;

    private RunConfigCopy() {
    }

    /** Visible so tests can name the run folder the same way the GUI does. */
    public static Path runConfigDirectory(Path runData) {
        return runData.resolve(RunConfigs.DIRECTORY);
    }
}
