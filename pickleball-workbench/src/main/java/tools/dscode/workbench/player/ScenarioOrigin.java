package tools.dscode.workbench.player;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Origin of a picker-loaded live buffer. Demo sessions have no save path. */
public record ScenarioOrigin(
        Path file,
        String scenarioName,
        int startLine,
        int endLine
) {
    public ScenarioOrigin {
        scenarioName = scenarioName == null ? "" : scenarioName;
    }

    public static ScenarioOrigin none() {
        return new ScenarioOrigin(null, "", 0, 0);
    }

    public boolean savable() {
        return file != null;
    }

    public Optional<Path> originFile() {
        return Optional.ofNullable(file);
    }

    public ScenarioOrigin withEndLine(int newEndLine) {
        return new ScenarioOrigin(file, scenarioName, startLine, newEndLine);
    }
}
