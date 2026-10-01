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
        String exampleLabel,
        String exampleSelector
) {
    public ScenarioOrigin {
        scenarioName = scenarioName == null ? "" : scenarioName;
        exampleLabel = exampleLabel == null ? "" : exampleLabel;
        exampleSelector = exampleSelector == null ? "" : exampleSelector;
    }

    public ScenarioOrigin(Path file, String scenarioName, int startLine, int endLine) {
        this(file, scenarioName, startLine, endLine, 0, "");
    }

    public ScenarioOrigin(
            Path file,
            String scenarioName,
            int startLine,
            int endLine,
            int exampleRow,
            String exampleLabel
    ) {
        this(file, scenarioName, startLine, endLine, exampleRow, exampleLabel, "");
    }

    public static ScenarioOrigin none() {
        return new ScenarioOrigin(null, "", 0, 0, 0, "");
    }

    /** Blank selector plus a positive row means that overall row number. */
    public String activeExampleSelector() {
        if (!exampleSelector.isBlank()) return exampleSelector.strip();
        return exampleRow > 0 ? Integer.toString(exampleRow) : "";
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
        return new ScenarioOrigin(file, scenarioName, startLine, newEndLine, exampleRow, exampleLabel, exampleSelector);
    }

    public ScenarioOrigin withExample(int row, String label) {
        return new ScenarioOrigin(file, scenarioName, startLine, endLine, row, label, "");
    }

    public ScenarioOrigin withExampleSelector(String selector) {
        return new ScenarioOrigin(
                file, scenarioName, startLine, endLine, exampleRow, exampleLabel,
                selector == null ? "" : selector
        );
    }
}
