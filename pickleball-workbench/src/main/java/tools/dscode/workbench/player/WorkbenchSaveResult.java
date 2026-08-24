package tools.dscode.workbench.player;

import java.nio.file.Path;
import java.util.Objects;

/** Outcome of an explicit live-buffer Save. Deny/cancel never writes. */
public record WorkbenchSaveResult(
        boolean written,
        String status,
        String featurePath,
        String scenarioName,
        String message
) {
    public WorkbenchSaveResult {
        Objects.requireNonNull(status, "status");
        featurePath = featurePath == null ? "" : featurePath;
        scenarioName = scenarioName == null ? "" : scenarioName;
        message = message == null ? "" : message;
    }

    public static WorkbenchSaveResult written(Path featurePath, String scenarioName) {
        return new WorkbenchSaveResult(
                true,
                "WRITTEN",
                featurePath.toString(),
                scenarioName,
                "Copied the live scenario into " + featurePath.getFileName() + " / " + scenarioName + "."
        );
    }

    public static WorkbenchSaveResult denied() {
        return new WorkbenchSaveResult(false, "DENIED", "", "", "Save was denied. The original feature file was not changed.");
    }

    public static WorkbenchSaveResult cancelled(String message) {
        return new WorkbenchSaveResult(false, "CANCELLED", "", "", message);
    }

    public static WorkbenchSaveResult unsavable(String message) {
        return new WorkbenchSaveResult(false, "UNSAVABLE", "", "", message);
    }
}
