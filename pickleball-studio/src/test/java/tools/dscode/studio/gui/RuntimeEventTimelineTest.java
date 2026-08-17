package tools.dscode.studio.gui;

import org.junit.jupiter.api.Test;
import tools.dscode.studio.runtime.RuntimeEvent;
import tools.dscode.studio.runtime.RuntimeEventPage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEventTimelineTest {

    @Test
    void tracksCursorGapAndResetsWhenSelectionChanges() {
        RuntimeEventTimeline timeline = new RuntimeEventTimeline();

        assertTrue(timeline.select("session", "runtime-a", null));
        timeline.accept(page(List.of(event(10), event(11)), 11, 10, 11, true, false));

        assertEquals(11, timeline.afterSequence());
        assertEquals(10, timeline.earliestAvailableSequence());
        assertEquals(11, timeline.latestSequence());
        assertTrue(timeline.gapObserved());
        assertEquals(10, timeline.gapEarliestSequence());
        assertEquals(List.of(10L, 11L), sequences(timeline.events()));

        assertFalse(timeline.select("session", "runtime-a", null));
        assertEquals(11, timeline.afterSequence());

        assertTrue(timeline.select("session", "runtime-a", "scenario-b"));
        assertEquals(0, timeline.afterSequence());
        assertFalse(timeline.gapObserved());
        assertTrue(timeline.events().isEmpty());
    }

    @Test
    void boundsOnlyTheDesktopViewWithoutRewindingTheRuntimeCursor() {
        RuntimeEventTimeline timeline = new RuntimeEventTimeline();
        timeline.select("session", "runtime", null);

        long sequence = 0;
        for (int pageIndex = 0; pageIndex < 3; pageIndex++) {
            List<RuntimeEvent> events = new ArrayList<>();
            for (int index = 0; index < 400; index++) {
                events.add(event(++sequence));
            }
            timeline.accept(page(events, sequence, 1, sequence, false, false));
        }

        assertEquals(1_200, timeline.afterSequence());
        assertEquals(RuntimeEventTimeline.MAX_VISIBLE_EVENTS, timeline.events().size());
        assertEquals(200, timeline.omittedVisibleEvents());
        assertEquals(201, timeline.events().getFirst().sequence());
        assertEquals(1_200, timeline.events().getLast().sequence());

        timeline.clearVisible();
        assertTrue(timeline.events().isEmpty());
        assertEquals(1_200, timeline.afterSequence());

        timeline.reload();
        assertEquals(0, timeline.afterSequence());
        assertEquals(0, timeline.omittedVisibleEvents());
    }

    private static RuntimeEventPage page(
            List<RuntimeEvent> events,
            long nextSequence,
            long earliest,
            long latest,
            boolean gap,
            boolean hasMore
    ) {
        return new RuntimeEventPage(
                events,
                nextSequence,
                earliest,
                latest,
                gap,
                hasMore
        );
    }

    private static RuntimeEvent event(long sequence) {
        return new RuntimeEvent(
                sequence,
                "2026-08-16T00:00:00Z",
                12,
                "scenario-a",
                "Scenario A",
                "BEFORE_STEP",
                "signature-" + sequence,
                "Given step " + sequence,
                null
        );
    }

    private static List<Long> sequences(List<RuntimeEvent> events) {
        return events.stream().map(RuntimeEvent::sequence).toList();
    }
}
