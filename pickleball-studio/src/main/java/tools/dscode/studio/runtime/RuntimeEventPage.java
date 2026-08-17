package tools.dscode.studio.runtime;

import java.util.List;

/** Cursor page over one consumer runtime's bounded semantic event history. */
public record RuntimeEventPage(
        List<RuntimeEvent> events,
        long nextSequence,
        long earliestAvailableSequence,
        long latestSequence,
        boolean gap,
        boolean hasMore
) {
    public RuntimeEventPage {
        events = List.copyOf(events);
    }
}
