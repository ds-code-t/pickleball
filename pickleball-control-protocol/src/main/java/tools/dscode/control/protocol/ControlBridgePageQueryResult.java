package tools.dscode.control.protocol;

import java.util.List;

/** Read-only page query. Counts come from one describe batch. Nothing is clicked. */
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
            int visibleCount,
            String query,
            String exposure,
            List<ControlBridgeElementEvidence> nodes
    ) {
        public ControlBridgePageQueryHit {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            query = query == null ? "" : query;
            exposure = exposure == null ? "" : exposure;
        }

        public ControlBridgePageQueryHit(String phrase, String xpath, int rawMatchCount, int visibleCount) {
            this(phrase, xpath, rawMatchCount, visibleCount, "", "", List.of());
        }
    }
}
