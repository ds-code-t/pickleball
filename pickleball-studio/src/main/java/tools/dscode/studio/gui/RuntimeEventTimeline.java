package tools.dscode.studio.gui;

import tools.dscode.studio.runtime.RuntimeEvent;
import tools.dscode.studio.runtime.RuntimeEventPage;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

final class RuntimeEventTimeline {
    static final int MAX_VISIBLE_EVENTS = 1_000;

    private final ArrayDeque<RuntimeEvent> events = new ArrayDeque<>();

    private String sessionId;
    private String runtimeId;
    private String scenarioId;
    private long afterSequence;
    private long earliestAvailableSequence;
    private long latestSequence;
    private long omittedVisibleEvents;
    private boolean gapObserved;
    private long gapEarliestSequence;

    boolean select(String sessionId, String runtimeId, String scenarioId) {
        String normalizedSession = normalized(sessionId);
        String normalizedRuntime = normalized(runtimeId);
        String normalizedScenario = normalized(scenarioId);
        if (Objects.equals(this.sessionId, normalizedSession)
                && Objects.equals(this.runtimeId, normalizedRuntime)
                && Objects.equals(this.scenarioId, normalizedScenario)) {
            return false;
        }

        this.sessionId = normalizedSession;
        this.runtimeId = normalizedRuntime;
        this.scenarioId = normalizedScenario;
        reload();
        return true;
    }

    void accept(RuntimeEventPage page) {
        if (page.gap()) {
            gapObserved = true;
            gapEarliestSequence = page.earliestAvailableSequence();
        }
        earliestAvailableSequence = page.earliestAvailableSequence();
        latestSequence = page.latestSequence();

        for (RuntimeEvent event : page.events()) {
            events.addLast(event);
            while (events.size() > MAX_VISIBLE_EVENTS) {
                events.removeFirst();
                omittedVisibleEvents++;
            }
        }
        afterSequence = Math.max(afterSequence, page.nextSequence());
    }

    void reload() {
        events.clear();
        afterSequence = 0;
        earliestAvailableSequence = 0;
        latestSequence = 0;
        omittedVisibleEvents = 0;
        gapObserved = false;
        gapEarliestSequence = 0;
    }

    void clearVisible() {
        events.clear();
        omittedVisibleEvents = 0;
    }

    long afterSequence() {
        return afterSequence;
    }

    long earliestAvailableSequence() {
        return earliestAvailableSequence;
    }

    long latestSequence() {
        return latestSequence;
    }

    long omittedVisibleEvents() {
        return omittedVisibleEvents;
    }

    boolean gapObserved() {
        return gapObserved;
    }

    long gapEarliestSequence() {
        return gapEarliestSequence;
    }

    List<RuntimeEvent> events() {
        return List.copyOf(events);
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
