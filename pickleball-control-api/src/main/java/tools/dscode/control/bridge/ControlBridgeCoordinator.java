
package tools.dscode.control.bridge;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.control.ControlDecision;
import tools.dscode.common.control.ControlEvent;
import tools.dscode.common.control.ControlHook;
import tools.dscode.common.control.ControlHookHandler;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.control.api.ControlCallResult;
import tools.dscode.control.api.DynamicControl;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class ControlBridgeCoordinator implements ControlHookHandler, AutoCloseable {
    static final int DEFAULT_WAIT_SECONDS = 30;
    static final int DEFAULT_PAUSE_LEASE_SECONDS = 120;
    static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    static final int MAX_WAIT_SECONDS = 600;
    static final int MAX_PAUSE_LEASE_SECONDS = 3600;
    static final int MAX_COMMAND_TIMEOUT_SECONDS = 3600;

    private static final int MAX_TEXT = 64 * 1024;

    private final String runtimeId;
    private final long pid;
    private final List<String> capabilities;
    private final ConcurrentHashMap<Long, ScenarioLane> lanes = new ConcurrentHashMap<>();
    private final AtomicBoolean pauseNextScenario = new AtomicBoolean();
    private final AtomicInteger nextPauseLeaseSeconds =
            new AtomicInteger(DEFAULT_PAUSE_LEASE_SECONDS);
    private final AtomicReference<CompletableFuture<ControlBridgeStatus>> nextPauseFuture =
            new AtomicReference<>();

    private volatile boolean closed;

    ControlBridgeCoordinator(
            String runtimeId,
            long pid,
            List<String> capabilities,
            boolean pauseFirstScenario
    ) {
        this.runtimeId = runtimeId;
        this.pid = pid;
        this.capabilities = List.copyOf(capabilities);
        this.pauseNextScenario.set(pauseFirstScenario);
    }

    @Override
    public ControlDecision onHook(ControlEvent event) {
        if (closed) {
            return ControlDecision.CONTINUE;
        }

        long threadId = Thread.currentThread().threadId();
        ScenarioLane lane = lanes.get(threadId);

        if (lane == null && GlobalState.getCurrentScenarioState() != null) {
            lane = lanes.computeIfAbsent(threadId, ScenarioLane::new);
            if (pauseNextScenario.compareAndSet(true, false)) {
                CompletableFuture<ControlBridgeStatus> future = nextPauseFuture.getAndSet(null);
                lane.requestPause(
                        nextPauseLeaseSeconds.getAndSet(DEFAULT_PAUSE_LEASE_SECONDS),
                        future
                );
            }
        }

        if (lane == null) {
            return ControlDecision.CONTINUE;
        }

        lane.capture(event);

        if (event.hook() == ControlHook.SCENARIO_END) {
            lane.finish();
            lanes.remove(threadId, lane);
            return ControlDecision.CONTINUE;
        }

        lane.drainCommands();
        if (lane.pauseRequested) {
            lane.pauseAtHook();
        }
        return ControlDecision.CONTINUE;
    }

    ControlBridgeStatus status() {
        ScenarioLane selected = selectedLane();
        int activeCount = lanes.size();

        if (selected == null) {
            boolean paused = lanes.values().stream().anyMatch(lane -> lane.paused);
            boolean pauseRequested = pauseNextScenario.get()
                    || lanes.values().stream().anyMatch(lane -> lane.pauseRequested);
            return new ControlBridgeStatus(
                    ControlBridgeRuntime.PROTOCOL_VERSION,
                    runtimeId,
                    pid,
                    activeCount,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    paused,
                    pauseRequested,
                    capabilities
            );
        }

        return selected.status(activeCount);
    }

    ControlBridgeCallResult requestPause(Integer waitSeconds, Integer leaseSeconds) {
        int wait = bounded(
                waitSeconds,
                DEFAULT_WAIT_SECONDS,
                MAX_WAIT_SECONDS,
                "waitSeconds"
        );
        int lease = bounded(
                leaseSeconds,
                DEFAULT_PAUSE_LEASE_SECONDS,
                MAX_PAUSE_LEASE_SECONDS,
                "leaseSeconds"
        );

        ScenarioLane lane = selectedLane();
        if (lane == null) {
            if (!lanes.isEmpty()) {
                return unavailable(
                        "Pause requires exactly one active scenario unless a scenario is already paused."
                );
            }

            CompletableFuture<ControlBridgeStatus> future = new CompletableFuture<>();
            CompletableFuture<ControlBridgeStatus> previous = nextPauseFuture.getAndSet(future);
            if (previous != null && !previous.isDone()) {
                previous.completeExceptionally(
                        new IllegalStateException("Pause request was replaced by a newer request.")
                );
            }
            nextPauseLeaseSeconds.set(lease);
            pauseNextScenario.set(true);
            return awaitPause(future, wait);
        }

        if (lane.paused) {
            lane.extendPause(lease);
            return success("PAUSED", lane.status(lanes.size()));
        }

        CompletableFuture<ControlBridgeStatus> future = new CompletableFuture<>();
        lane.requestPause(lease, future);
        return awaitPause(future, wait);
    }

    ControlBridgeCallResult resume() {
        ScenarioLane lane = pausedLane();
        if (lane == null) {
            lane = selectedLane();
        }
        if (lane == null) {
            pauseNextScenario.set(false);
            nextPauseLeaseSeconds.set(DEFAULT_PAUSE_LEASE_SECONDS);
            CompletableFuture<ControlBridgeStatus> pending = nextPauseFuture.getAndSet(null);
            if (pending != null && !pending.isDone()) {
                pending.complete(status());
            }
            return success("RUNNING", status());
        }

        lane.resume();
        return success("RUNNING", status());
    }

    ControlBridgeCallResult executeStep(
            String text,
            String argument,
            Integer timeoutSeconds
    ) {
        if (text == null || text.isBlank()) {
            return unavailable("Dynamic step text must not be blank.");
        }

        int timeout = bounded(
                timeoutSeconds,
                DEFAULT_COMMAND_TIMEOUT_SECONDS,
                MAX_COMMAND_TIMEOUT_SECONDS,
                "timeoutSeconds"
        );

        ScenarioLane lane = selectedLane();
        if (lane == null) {
            if (lanes.isEmpty()) {
                return unavailable("Dynamic execution requires an active Pickleball scenario.");
            }
            return unavailable(
                    "Dynamic execution requires exactly one active scenario unless one is paused."
            );
        }

        ScenarioCommand command = new ScenarioCommand(() ->
                fromControlResult(DynamicControl.executeStep(text, argument == null ? "" : argument))
        );
        lane.commands.offer(command);

        try {
            return command.result.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException failure) {
            command.cancelled.set(true);
            lane.commands.remove(command);
            return failed(
                    "CONTROL_TIMEOUT",
                    "Timed out waiting for the scenario thread to complete the control command. "
                            + "If execution had already started, it may still complete."
            );
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            command.cancelled.set(true);
            lane.commands.remove(command);
            return failed("INTERRUPTED", "Interrupted while waiting for the control command.");
        } catch (Exception failure) {
            return failed(failure.getClass().getName(), Objects.toString(failure.getMessage(), ""));
        }
    }

    @Override
    public void close() {
        closed = true;
        pauseNextScenario.set(false);
        nextPauseLeaseSeconds.set(DEFAULT_PAUSE_LEASE_SECONDS);

        CompletableFuture<ControlBridgeStatus> pendingPause = nextPauseFuture.getAndSet(null);
        if (pendingPause != null && !pendingPause.isDone()) {
            pendingPause.complete(status());
        }

        lanes.values().forEach(ScenarioLane::finish);
        lanes.clear();
    }

    private ControlBridgeCallResult awaitPause(
            CompletableFuture<ControlBridgeStatus> future,
            int waitSeconds
    ) {
        try {
            ControlBridgeStatus paused = future.get(waitSeconds, TimeUnit.SECONDS);
            return paused.paused()
                    ? success("PAUSED", paused)
                    : unavailable("Scenario ended before the requested pause was reached.");
        } catch (TimeoutException failure) {
            if (nextPauseFuture.compareAndSet(future, null)) {
                pauseNextScenario.set(false);
                nextPauseLeaseSeconds.set(DEFAULT_PAUSE_LEASE_SECONDS);
            }
            lanes.values().forEach(lane -> lane.cancelPauseRequest(future));
            return unavailable("Timed out waiting for a semantic hook where execution could pause.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return failed("INTERRUPTED", "Interrupted while waiting for the scenario to pause.");
        } catch (Exception failure) {
            return failed(failure.getClass().getName(), Objects.toString(failure.getMessage(), ""));
        }
    }

    private ScenarioLane selectedLane() {
        ScenarioLane paused = pausedLane();
        if (paused != null) {
            return paused;
        }
        if (lanes.size() != 1) {
            return null;
        }
        return lanes.values().iterator().next();
    }

    private ScenarioLane pausedLane() {
        ScenarioLane selected = null;
        for (ScenarioLane lane : lanes.values()) {
            if (!lane.paused) {
                continue;
            }
            if (selected != null) {
                return null;
            }
            selected = lane;
        }
        return selected;
    }

    private ControlBridgeCallResult fromControlResult(ControlCallResult<Object> result) {
        if (result == null) {
            return failed("NULL_RESULT", "Dynamic control returned no result.");
        }

        Object value = result.value();
        var error = result.error();
        return new ControlBridgeCallResult(
                result.status().name(),
                value == null ? null : value.getClass().getName(),
                value == null ? null : clipped(Objects.toString(value)),
                error == null
                        ? null
                        : new ControlBridgeError(
                                error.type(),
                                clipped(error.message()),
                                clipped(error.stackTrace())
                        ),
                status()
        );
    }

    private ControlBridgeCallResult success(String value, ControlBridgeStatus runtime) {
        return new ControlBridgeCallResult(
                "SUCCESS",
                String.class.getName(),
                value,
                null,
                runtime
        );
    }

    private ControlBridgeCallResult unavailable(String message) {
        return new ControlBridgeCallResult(
                "UNAVAILABLE",
                null,
                null,
                new ControlBridgeError("UNAVAILABLE", message, ""),
                status()
        );
    }

    private ControlBridgeCallResult failed(String type, String message) {
        return new ControlBridgeCallResult(
                "FAILED",
                null,
                null,
                new ControlBridgeError(type, message, ""),
                status()
        );
    }

    private static int bounded(
            Integer requested,
            int defaultValue,
            int maxValue,
            String name
    ) {
        int value = requested == null ? defaultValue : requested;
        if (value < 1 || value > maxValue) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maxValue);
        }
        return value;
    }

    private static String clipped(String value) {
        if (value == null || value.length() <= MAX_TEXT) {
            return value;
        }
        return value.substring(0, MAX_TEXT) + "\n...[truncated]";
    }

    private final class ScenarioLane {
        private final long threadId;
        private final BlockingQueue<ScenarioCommand> commands = new LinkedBlockingQueue<>();

        private volatile String scenarioId;
        private volatile String scenarioName;
        private volatile String stepText;
        private volatile String phraseText;
        private volatile String lastHook;
        private volatile String lastSignature;
        private volatile boolean paused;
        private volatile boolean pauseRequested;
        private volatile long pauseDeadlineNanos;
        private volatile CompletableFuture<ControlBridgeStatus> pauseFuture;

        private ScenarioLane(long threadId) {
            this.threadId = threadId;
        }

        private void capture(ControlEvent event) {
            CurrentScenarioState scenario = GlobalState.getCurrentScenarioState();
            StepExtension step = GlobalState.getRunningStep();
            Phrase phrase = GlobalState.getRunningPhrase();

            scenarioId = scenario == null ? null : scenario.id.toString();
            scenarioName = scenario == null ? null : scenario.scenarioName;
            stepText = safeStepText(step);
            phraseText = phrase == null ? null : clipped(Objects.toString(phrase));
            lastHook = event.hook().name();
            lastSignature = clipped(event.signature());
        }

        private void requestPause(
                int leaseSeconds,
                CompletableFuture<ControlBridgeStatus> future
        ) {
            pauseRequested = true;
            extendPause(leaseSeconds);
            if (future != null) {
                pauseFuture = future;
            }
        }

        private void extendPause(int leaseSeconds) {
            pauseDeadlineNanos = System.nanoTime()
                    + Duration.ofSeconds(leaseSeconds).toNanos();
        }

        private synchronized void cancelPauseRequest(
                CompletableFuture<ControlBridgeStatus> future
        ) {
            if (!paused && pauseFuture == future) {
                pauseFuture = null;
                pauseRequested = false;
            }
        }

        private void pauseAtHook() {
            paused = true;
            CompletableFuture<ControlBridgeStatus> future = pauseFuture;
            pauseFuture = null;
            if (future != null && !future.isDone()) {
                future.complete(status(lanes.size()));
            }

            while (!closed && pauseRequested) {
                long remaining = pauseDeadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    pauseRequested = false;
                    break;
                }

                long waitMillis = Math.min(
                        250,
                        Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))
                );
                try {
                    ScenarioCommand command = commands.poll(waitMillis, TimeUnit.MILLISECONDS);
                    if (command != null) {
                        executeCommand(command);
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    pauseRequested = false;
                    break;
                }
            }

            paused = false;
        }

        private void drainCommands() {
            ScenarioCommand command;
            while ((command = commands.poll()) != null) {
                executeCommand(command);
            }
        }

        private void executeCommand(ScenarioCommand command) {
            if (command.wakeup || command.cancelled.get()) {
                return;
            }
            try {
                command.result.complete(command.action.get());
            } catch (Throwable failure) {
                command.result.complete(failed(
                        failure.getClass().getName(),
                        Objects.toString(failure.getMessage(), "")
                ));
            }
        }

        private void resume() {
            pauseRequested = false;
            paused = false;
            commands.offer(ScenarioCommand.wakeup());
        }

        private void finish() {
            pauseRequested = false;
            paused = false;

            CompletableFuture<ControlBridgeStatus> future = pauseFuture;
            pauseFuture = null;
            if (future != null && !future.isDone()) {
                future.complete(status(Math.max(0, lanes.size() - 1)));
            }

            ScenarioCommand command;
            while ((command = commands.poll()) != null) {
                if (!command.wakeup && !command.result.isDone()) {
                    command.result.complete(unavailable(
                            "Scenario ended before the control command could execute."
                    ));
                }
            }
        }

        private ControlBridgeStatus status(int activeCount) {
            return new ControlBridgeStatus(
                    ControlBridgeRuntime.PROTOCOL_VERSION,
                    runtimeId,
                    pid,
                    activeCount,
                    threadId,
                    scenarioId,
                    scenarioName,
                    stepText,
                    phraseText,
                    lastHook,
                    lastSignature,
                    paused,
                    pauseRequested,
                    capabilities
            );
        }

        private String safeStepText(StepExtension step) {
            if (step == null) {
                return null;
            }
            try {
                return clipped(step.getStepText());
            } catch (RuntimeException ignored) {
                return clipped(step.toString());
            }
        }
    }

    @FunctionalInterface
    private interface ScenarioAction {
        ControlBridgeCallResult get();
    }

    private static final class ScenarioCommand {
        private final ScenarioAction action;
        private final CompletableFuture<ControlBridgeCallResult> result = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final boolean wakeup;

        private ScenarioCommand(ScenarioAction action) {
            this(action, false);
        }

        private ScenarioCommand(ScenarioAction action, boolean wakeup) {
            this.action = action;
            this.wakeup = wakeup;
        }

        private static ScenarioCommand wakeup() {
            return new ScenarioCommand(null, true);
        }
    }
}
