package tools.dscode.control.api;

import java.util.List;

/** Compact explanation of ordinary NodeMap lookup order for one key. */
public record ResolutionExplanation(
        String key,
        List<ResolutionCandidate> searched,
        String winningMapType,
        Object resolvedValue
) {
    public ResolutionExplanation {
        searched = searched == null ? List.of() : List.copyOf(searched);
        winningMapType = winningMapType == null ? "" : winningMapType;
    }
}
