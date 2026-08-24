package tools.dscode.workbench.player;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Preview of a gated Save into the originating scenario. */
public record WorkbenchSavePreview(
        boolean savable,
        Path featurePath,
        String scenarioName,
        String summary,
        List<String> liveScenarioLines
) {
    public WorkbenchSavePreview {
        scenarioName = scenarioName == null ? "" : scenarioName;
        summary = summary == null ? "" : summary;
        liveScenarioLines = List.copyOf(liveScenarioLines == null ? List.of() : liveScenarioLines);
    }

    public Optional<Path> originFile() {
        return Optional.ofNullable(featurePath);
    }

    public static WorkbenchSavePreview unsavable(String reason) {
        return new WorkbenchSavePreview(false, null, "", reason, List.of());
    }
}
