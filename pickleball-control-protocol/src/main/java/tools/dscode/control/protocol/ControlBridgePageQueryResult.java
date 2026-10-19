package tools.dscode.control.protocol;

import java.util.List;

/** Read-only page query. Counts come from findElements and isDisplayed. Nothing is clicked. */
public record ControlBridgePageQueryResult(
        String status,
        List<ControlBridgePageQueryHit> hits,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
    public ControlBridgePageQueryResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public record ControlBridgePageQueryHit(
            String phrase,
            String xpath,
            int rawMatchCount,
            int visibleCount
    ) {
    }
}
