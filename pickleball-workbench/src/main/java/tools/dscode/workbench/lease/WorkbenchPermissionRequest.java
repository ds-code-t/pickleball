package tools.dscode.workbench.lease;

import java.nio.file.Path;
import java.util.Objects;

/** One pending Allow/Deny request shown in the Workbench UI. */
public record WorkbenchPermissionRequest(
        String id,
        WorkbenchPermissionKind kind,
        String summary,
        String featurePath,
        String scenarioName
) {
    public WorkbenchPermissionRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        summary = summary == null ? "" : summary;
        featurePath = featurePath == null ? "" : featurePath;
        scenarioName = scenarioName == null ? "" : scenarioName;
    }

    public Path originFile() {
        return featurePath.isBlank() ? null : Path.of(featurePath);
    }
}
