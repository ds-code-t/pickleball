package tools.dscode.workbench.player;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Origin of a picker-loaded feature buffer. Demo sessions have no save path. */
public record ScenarioOrigin(
        Path file,
        String scenarioName,
        int startLine,
        int endLine,
        int exampleRow,
        String exampleLabel
) {
    public ScenarioOrigin {
        scenarioName = scenarioName == null ? "" : scenarioName;
        exampleLabel = exampleLabel == null ? "" : exampleLabel;
    }

    public ScenarioOrigin(Path file, String scenarioName, int startLine, int endLine) {
        this(file, scenarioName, startLine, endLine, 0, "");
    }

    public static ScenarioOrigin none() {
        return new ScenarioOrigin(null, "", 0, 0, 0, "");
    }

    public boolean savable() {
        return file != null;
    }

    public Optional<Path> originFile() {
        return Optional.ofNullable(file);
    }

    public boolean hasExampleRow() {
        return exampleRow > 0;
    }

    public ScenarioOrigin withEndLine(int newEndLine) {
        return new ScenarioOrigin(file, scenarioName, startLine, newEndLine, exampleRow, exampleLabel);
    }

    public ScenarioOrigin withExample(int row, String label) {
        return new ScenarioOrigin(file, scenarioName, startLine, endLine, row, label);
    }
}
