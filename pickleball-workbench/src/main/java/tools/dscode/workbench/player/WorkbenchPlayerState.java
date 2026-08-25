package tools.dscode.workbench.player;

import tools.dscode.workbench.player.LiveScenarioPlayer.State;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only live-buffer snapshot for MCP/HTTP attach clients. */
public record WorkbenchPlayerState(
        String documentText,
        List<String> lines,
        State playerState,
        Long playheadId,
        String playheadText,
        Long selectedId,
        String sourceFeaturePath,
        String scenarioName,
        boolean savable
) {
    public WorkbenchPlayerState {
        Objects.requireNonNull(playerState, "playerState");
        documentText = documentText == null ? "" : documentText;
        lines = List.copyOf(lines == null ? List.of() : lines);
        sourceFeaturePath = sourceFeaturePath == null ? "" : sourceFeaturePath;
        scenarioName = scenarioName == null ? "" : scenarioName;
    }

    public Optional<Path> originFile() {
        return sourceFeaturePath.isBlank() ? Optional.empty() : Optional.of(Path.of(sourceFeaturePath));
    }
}
