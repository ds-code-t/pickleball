package tools.dscode.control.protocol;

import java.util.List;

/** Cursor page over the bounded semantic event history retained by one consumer runtime. */
public record ControlBridgeEventPage(
        List<ControlBridgeEvent> events,
        long nextSequence,
        long earliestAvailableSequence,
        long latestSequence,
        boolean gap,
        boolean hasMore
) {
    public ControlBridgeEventPage {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
