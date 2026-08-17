package tools.dscode.control.bridge;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.control.ControlDecision;
import tools.dscode.common.control.ControlEvent;
import tools.dscode.common.control.ControlHookHandler;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

final class ControlBridgeEventRecorder implements ControlHookHandler, AutoCloseable {
    static final int MAX_EVENTS = 2048;
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private static final int MAX_EVENT_TEXT = 2048;

    private final Object lock = new Object();
    private final ArrayDeque<ControlBridgeEvent> events = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();

    private volatile boolean closed;

    @Override
    public ControlDecision onHook(ControlEvent event) {
        if (closed) {
            return ControlDecision.CONTINUE;
        }

        CurrentScenarioState scenario = GlobalState.getCurrentScenarioState();
        if (scenario == null) {
            return ControlDecision.CONTINUE;
        }

        StepExtension step = GlobalState.getRunningStep();
        Phrase phrase = GlobalState.getRunningPhrase();
        ControlBridgeEvent snapshot = new ControlBridgeEvent(
                sequence.incrementAndGet(),
                Instant.now().toString(),
                Thread.currentThread().threadId(),
                scenario.id.toString(),
                scenario.scenarioName,
                event.hook().name(),
                clipped(event.signature()),
                safeStepText(step),
                phrase == null ? null : clipped(Objects.toString(phrase))
        );

        synchronized (lock) {
            if (closed) {
                return ControlDecision.CONTINUE;
            }
            events.addLast(snapshot);
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
        }
        return ControlDecision.CONTINUE;
    }

    ControlBridgeEventPage page(
            String scenarioId,
            Long afterSequence,
            Integer limit
    ) {
        long after = afterSequence == null ? 0L : afterSequence;
        if (after < 0) {
            throw new IllegalArgumentException("afterSequence must be zero or greater.");
        }

        int pageLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (pageLimit < 1 || pageLimit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_LIMIT + "."
            );
        }

        String targetId = scenarioId == null || scenarioId.isBlank()
                ? null
                : scenarioId.trim();

        synchronized (lock) {
            long latest = sequence.get();
            long earliest = events.isEmpty()
                    ? latest + 1
                    : events.getFirst().sequence();
            boolean gap = after > 0 && after < earliest - 1;

            List<ControlBridgeEvent> page = new ArrayList<>(pageLimit);
            boolean hasMore = false;
            long lastReturned = after;

            for (ControlBridgeEvent event : events) {
                if (event.sequence() <= after) {
                    continue;
                }
                if (targetId != null && !targetId.equals(event.scenarioId())) {
                    continue;
                }
                if (page.size() >= pageLimit) {
                    hasMore = true;
                    break;
                }
                page.add(event);
                lastReturned = event.sequence();
            }

            long next = hasMore
                    ? lastReturned
                    : Math.max(after, latest);
            return new ControlBridgeEventPage(
                    page,
                    next,
                    earliest,
                    latest,
                    gap,
                    hasMore
            );
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (lock) {
            events.clear();
        }
    }

    private static String safeStepText(StepExtension step) {
        if (step == null) {
            return null;
        }
        try {
            return clipped(step.getStepText());
        } catch (RuntimeException ignored) {
            return clipped(step.toString());
        }
    }

    private static String clipped(String value) {
        if (value == null || value.length() <= MAX_EVENT_TEXT) {
            return value;
        }
        return value.substring(0, MAX_EVENT_TEXT) + "\n...[truncated]";
    }
}
